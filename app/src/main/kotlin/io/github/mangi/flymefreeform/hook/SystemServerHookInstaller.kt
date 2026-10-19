package io.github.mangi.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator
import io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformLauncher
import io.github.mangi.flymefreeform.platform.pixel.PixelFallbackLauncher
import io.github.mangi.flymefreeform.platform.common.SystemServerAccess
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧安装器。
 *
 * 网关初始化入口按平台选择，**不再硬依赖 ColorOS 专属类**：
 *
 *  - ColorOS：hook `com.android.server.wm.FlexibleTaskController#systemReady`，
 *    直接用 controller 实例作为 [SystemServerAccess] 的数据源（原路径，行为不变）。
 *  - Pixel / AOSP：阶段一 DEX 审计已确认 `FlexibleTaskController` 不存在；
 *    改挂 `com.android.server.am.ActivityManagerService#systemReady`，
 *    该点在 system_server 启动序列中必然执行，且此时 ATMS 已就绪。
 *
 * 两种路径产出同一个 [ColorOsFreeformCoordinator]，
 * 指针输入入口本身是 AOSP 标准的（WindowManagerPolicyConstants$PointerEventListener
 * + WMS.registerPointerEventListener），不依赖 ColorOS 实现。
 */
internal class SystemServerHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private val environment = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }

    fun install(classLoader: ClassLoader) {
        // @author bomo 0.2.18 取证：模块扇形（CornerRadialOverlayView → GESTURE_START）就跑在本进程，
        // 装上触觉埋点才能和 smartsidebar:ui 里的系统侧边栏震动区分开（同一开关控制，默认关闭）。
        DiagnosticTracer.installHapticTrace(module) { priority, code, throwable ->
            module.log(priority, TAG, code, throwable)
        }
        HandleSwipeUpHookInstaller(module, configuration, environment).install(classLoader)

        if (installColorOsEntry(classLoader)) return
        installAospEntry(classLoader)
    }

    /**
     * ColorOS 入口：FlexibleTaskController#systemReady。
     * @return true 表示已安装（ColorOS 设备）。
     */
    private fun installColorOsEntry(classLoader: ClassLoader): Boolean {
        val systemReady =
            try {
                classLoader
                    .loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
                    .getDeclaredMethod("systemReady", Boolean::class.javaPrimitiveType)
            } catch (exception: ReflectiveOperationException) {
                return false
            } catch (exception: LinkageError) {
                return false
            }
        return try {
            module
                .hook(systemReady)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.flexible_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject
                    if (controller != null) startOnce(SystemServerAccess.fromController(controller), "COLOROS")
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEM_FREEFORM_READY_HOOK_INSTALLED entry=COLOROS")
            true
        } catch (exception: Throwable) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_UNAVAILABLE entry=COLOROS", exception)
            false
        }
    }

    /**
     * AOSP / Pixel 入口：ActivityManagerService#systemReady。
     *
     * 阶段一审计（docs/PIXEL_PORT_AUDIT.md）确认 ColorOS 专有类在 Pixel A17 上不存在，
     * 且 `WMS.registerPointerEventListener` 侧是完全标准的，故只需一个可靠的初始化时机。
     * `ActivityManagerService.systemReady(Runnable, ...)` 在启动序列中必然执行。
     */
    private fun installAospEntry(classLoader: ClassLoader) {
        val amsClass = "com.android.server.am.ActivityManagerService"
        var diagnostics = ""
        val method =
            try {
                val cls = classLoader.loadClass(amsClass)
                val candidates = cls.declaredMethods.filter { it.name == "systemReady" }
                // 反射返回顺序不可作为选择依据：必须按参数类型精确匹配。
                // AOSP 的 AMS.systemReady 在各版本间签名有变动，用「已知签名白名单」
                // 逐个比对，而不是「第一个有参数的」或「第一个叫 systemReady 的」。
                val picked = candidates.firstOrNull { matchesKnownSystemReady(it.parameterTypes) }
                if (picked == null) {
                    // 打全量候选签名，便于按真机实际情况补齐白名单
                    diagnostics = " candidates=" + candidates.joinToString(";") { m ->
                        "(" + m.parameterTypes.joinToString(",") { it.name } + ")"
                    }
                }
                picked
            } catch (exception: ReflectiveOperationException) {
                null
            } catch (exception: LinkageError) {
                null
            }
        if (method == null) {
            module.log(
                Log.WARN,
                TAG,
                "SYSTEM_FREEFORM_TARGET_UNAVAILABLE entry=AOSP reason=no-exact-signature$diagnostics",
            )
            return
        }
        try {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.ams_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    // 「安装成功」不等于「已触发」：审查要求分别留证。
                    module.log(Log.INFO, TAG, "SYSTEM_FREEFORM_ENTRY_FIRED entry=AOSP")
                    val ams = chain.thisObject
                    if (ams == null) {
                        module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_ENTRY_NO_THIS entry=AOSP")
                    } else {
                        val access = SystemServerAccess.fromActivityManager(ams)
                        if (access == null) {
                            module.log(
                                Log.WARN,
                                TAG,
                                "SYSTEM_FREEFORM_ENTRY_NO_ACCESS entry=AOSP " +
                                    "cls=${ams.javaClass.name}",
                            )
                        } else {
                            startOnce(access, "AOSP")
                        }
                    }
                    result
                }
            // 记录完整签名而非仅参数个数：审查意见指出 sig=2 无法证明选中的是
            // 启动序列里真正执行的那个入口。
            module.log(
                Log.INFO,
                TAG,
                "SYSTEM_FREEFORM_READY_HOOK_INSTALLED entry=AOSP sig=${
                    method.parameterTypes.joinToString(",", "(", ")") { it.name }
                }",
            )
        } catch (exception: Throwable) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_LINKAGE_FAILED entry=AOSP", exception)
        }
    }

    /**
     * AMS.systemReady 已确认签名白名单。
     *
     * 只接受以下已确认形态（逐项比对类型，不按个数猜）：
     *   systemReady()                                                      —— 旧版
     *   systemReady(Runnable)                                              —— 传入「系统就绪后回调」
     *   systemReady(Runnable, TimingsTraceAndSlog)                          —— Android 17 实机命中
     *
     * 早期 AOSP 的 systemReady(ActivityManagerService) 等自引用形态不属于该路径，不应误选。
     * 参数个数相同不代表语义相同。
     */
    private fun matchesKnownSystemReady(types: Array<Class<*>>): Boolean {
        // Android 17 实机签名（2026-10-18 日志 line 56）：
        //   (java.lang.Runnable, com.android.server.utils.TimingsTraceAndSlog)
        // 第二参不是此前猜测的 boolean —— 白名单必须写实，不能靠「首参是 Runnable
        // 且参数不多于 3」这种近似规则蒙对。
        if (types.size == 2 &&
            types[0] == Runnable::class.java &&
            types[1].name == "com.android.server.utils.TimingsTraceAndSlog"
        ) {
            return true
        }
        // 旧版本形态，保留兼容
        if (types.size == 1 && types[0] == Runnable::class.java) return true
        if (types.isEmpty()) return true
        return false
    }

    private fun startOnce(
        access: SystemServerAccess,
        entry: String,
    ) {
        if (!bound.compareAndSet(false, true)) return
        module.log(Log.INFO, TAG, "SYSTEM_FREEFORM_COORDINATOR_START entry=$entry")
        try {
            ColorOsFreeformCoordinator(
                systemServer = access,
                classLoader = access.context.classLoader ?: ClassLoader.getSystemClassLoader(),
                configuration = configuration,
                environmentState = environment,
                logger = { priority, code, throwable ->
                    module.log(priority, TAG, code, throwable)
                },
                // 审查意见 P1：回退策略必须跟入口走。
                // 旧实现在协调器内硬编码 ColorOsFreeformLauncher，Pixel 被明确拒绝后
                // 仍带 mZoomLaunchFlags / setLaunchWindowingMode(100) 走厂商协议。
                fallbackLauncher =
                    if (entry == "COLOROS") {
                        ColorOsFreeformLauncher(access.context)
                    } else {
                        PixelFallbackLauncher(access.context)
                    },
            ).start()
        } catch (exception: Throwable) {
            module.log(Log.ERROR, TAG, "SYSTEM_FREEFORM_COORDINATOR_FAILED entry=$entry", exception)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
    }
}
