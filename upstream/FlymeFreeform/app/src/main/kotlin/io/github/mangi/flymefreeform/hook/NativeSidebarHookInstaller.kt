package io.github.mangi.flymefreeform.hook

import android.app.Service
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/**
 * @author bomo 禁用原生侧边栏浮标与滑动入口；仅安装到 smartsidebar:edgepanel。
 *
 * 17.9.8 的 FloatBarMainView 与 InputUtils 区域观察器属于常驻进程，UI 进程中隐藏
 * PanelMainView 不能阻止它们触发。复用既有隐藏开关，同时清空原厂维护的 Secure 区域、
 * 内存区域和区域观察器范围；保留原厂服务生命周期及模块在 :ui 的面板/工具桥接。
 *
 * @param module 当前进程的模块实例；签名不匹配时只记录对应功能失败，不阻断宿主。
 */
internal class NativeSidebarHookInstaller(private val module: XposedModule) {
    private var lastRegionRequest: String? = null

    /** @author bomo 用宿主类加载器分别安装区域和浮标钩子；两条路径独立报告兼容性结果。 */
    fun install(loader: ClassLoader) {
        installRegionSuppression(loader)
        installFloatBarSuppression()
    }

    /**
     * @author bomo 改写原厂统一区域更新入口，并在服务创建后清理上一次进程留下的区域。
     * @param loader 宿主类加载器；所有反射签名在安装前校验，部分安装失败时撤销本组钩子。
     */
    private fun installRegionSuppression(loader: ClassLoader) {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val inputClass = loader.loadClass(INPUT_CLASS)
            val updateRegion = inputClass.getDeclaredMethod("t", Rect::class.java, Boolean::class.javaPrimitiveType)
            require(Modifier.isStatic(updateRegion.modifiers) && updateRegion.returnType == Boolean::class.javaPrimitiveType)
            val serviceClass = loader.loadClass(SERVICE_CLASS)
            require(Service::class.java.isAssignableFrom(serviceClass))
            val create = serviceClass.getDeclaredMethod("onCreate")
            require(create.returnType == Void.TYPE)

            handles += module.hook(updateRegion).setId("flymefreeform.native.region")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (!shouldSuppress(currentApplication())) return@intercept chain.proceed()
                    val requested = (chain.getArg(0) as Rect).toShortString()
                    // 不修改原厂传入 Rect 对象，防止它同时用于浮标布局；proceed(args) 直接调用后续链。
                    val result = chain.proceed(arrayOf(Rect(), chain.getArg(1)))
                    if (lastRegionRequest != requested) {
                        lastRegionRequest = requested
                        log(Log.INFO, "NATIVE_GESTURE_REGION requested=$requested effective=empty result=$result")
                    }
                    result
                }
            handles += module.hook(create).setId("flymefreeform.native.service_create")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val service = chain.thisObject as? Service
                    if (shouldSuppress(service)) {
                        runCatching { updateRegion.invoke(null, Rect(), true) }
                            .onSuccess { log(Log.INFO, "NATIVE_GESTURE_REGION_INITIALIZED result=$it") }
                            .onFailure { log(Log.WARN, "NATIVE_GESTURE_REGION_INIT_FAILED", it) }
                    }
                    result
                }
            log(Log.INFO, "NATIVE_GESTURE_REGION_HOOK_INSTALLED")
        } catch (exception: Exception) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "NATIVE_GESTURE_REGION_HOOK_UNAVAILABLE", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "NATIVE_GESTURE_REGION_HOOK_UNAVAILABLE", error)
        }
    }

    /**
     * @author bomo 在原生浮标窗口添加/更新前去掉可见性与触摸入口；其他窗口原样执行。
     * 关闭隐藏开关并重启侧边栏进程即可由原厂重新创建窗口参数，不改系统侧边栏总开关。
     */
    private fun installFloatBarSuppression() {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val windowManager = Class.forName("android.view.WindowManagerImpl")
            val methods = listOf("addView", "updateViewLayout").map { name ->
                windowManager.getDeclaredMethod(name, View::class.java, ViewGroup.LayoutParams::class.java)
            }
            methods.forEachIndexed { index, method ->
                handles += module.hook(method).setId("flymefreeform.native.float_bar_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val view = chain.getArg(0) as? View
                        val params = chain.getArg(1) as? WindowManager.LayoutParams
                        if (view?.javaClass?.name == FLOAT_BAR_CLASS && params != null && shouldSuppress(view.context)) {
                            val flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            val changed = params.flags != flags || params.alpha != 0f
                            params.flags = flags
                            params.alpha = 0f
                            if (changed) log(Log.INFO, "NATIVE_FLOAT_BAR_DISABLED flags=0x${flags.toString(16)} alpha=0.0")
                        }
                        chain.proceed()
                    }
            }
            log(Log.INFO, "NATIVE_FLOAT_BAR_HOOK_INSTALLED")
        } catch (exception: Exception) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "NATIVE_FLOAT_BAR_HOOK_UNAVAILABLE", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "NATIVE_FLOAT_BAR_HOOK_UNAVAILABLE", error)
        }
    }

    /** @author bomo 无缓存读取既有隐藏开关；启动早期无 Context 或读取失败时保留原厂行为并记录异常。 */
    private fun shouldSuppress(context: Context?): Boolean {
        if (context == null) return false
        return try {
            Settings.Global.getInt(context.contentResolver, HIDE_NATIVE_BAR_KEY, 0) == 1
        } catch (exception: RuntimeException) {
            log(Log.WARN, "NATIVE_GESTURE_SETTING_UNAVAILABLE", exception)
            false
        }
    }

    /** @author bomo 获取已初始化的宿主 Application；创建前返回 null，不提前构造原厂单例。 */
    private fun currentApplication(): Context? =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context

    /** @author bomo 将兼容性/区域改写结果写入模块日志；throwable 为空表示常规状态记录。 */
    private fun log(priority: Int, message: String, throwable: Throwable? = null) =
        module.log(priority, "FlymeFreeform", message, throwable)

    private companion object {
        const val INPUT_CLASS = "com.oplus.smartsidebar.utils.InputUtils"
        const val SERVICE_CLASS = "com.coloros.edgepanel.service.EdgePanelService"
        const val FLOAT_BAR_CLASS = "com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView"
        const val HIDE_NATIVE_BAR_KEY = "flymefreeform_hide_native_bar"
    }
}
