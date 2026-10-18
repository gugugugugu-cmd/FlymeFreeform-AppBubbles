package io.github.mangi.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator
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
        OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
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
        val method =
            try {
                val cls = classLoader.loadClass(amsClass)
                cls.declaredMethods.firstOrNull {
                    it.name == "systemReady" && it.parameterTypes.isNotEmpty()
                } ?: cls.declaredMethods.firstOrNull { it.name == "systemReady" }
            } catch (exception: ReflectiveOperationException) {
                null
            } catch (exception: LinkageError) {
                null
            }
        if (method == null) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_UNAVAILABLE entry=AOSP")
            return
        }
        try {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.ams_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    val ams = chain.thisObject
                    if (ams != null) {
                        val access = SystemServerAccess.fromActivityManager(ams)
                        if (access != null) startOnce(access, "AOSP")
                    }
                    result
                }
            module.log(
                Log.INFO,
                TAG,
                "SYSTEM_FREEFORM_READY_HOOK_INSTALLED entry=AOSP sig=${method.parameterCount}",
            )
        } catch (exception: Throwable) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_LINKAGE_FAILED entry=AOSP", exception)
        }
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
