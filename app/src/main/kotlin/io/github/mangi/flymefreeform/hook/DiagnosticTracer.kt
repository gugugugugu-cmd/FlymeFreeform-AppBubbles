package io.github.mangi.flymefreeform.hook

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * @author bomo 一次性取证工具（0.2.18）。
 *
 * **要回答的问题**：划边缘时的震动到底是谁发的？候选有三个，光看现象分不清：
 * 1. 模块自己的扇形菜单 —— `CornerRadialOverlayView.onAttachedToWindow` 里
 *    `performHapticFeedback(GESTURE_START)`，跑在 **system_server**；
 * 2. 系统侧边栏浮标的展开逻辑 —— 跑在 **smartsidebar:ui**；
 * 3. SystemUI / Launcher 的边缘手势层。
 *
 * 三处最终都调 `View.performHapticFeedback`，因此在这里埋点、把调用栈打出来，
 * 配合进程名（LSPosed 日志自带 `(com.coloros.smartsidebar:ui)` 等前缀）即可定位。
 *
 * **开销控制**：默认关闭，只在 `flymefreeform_haptic_trace=1` 时打印，且同类日志限流
 * ——震动是高频操作，不限流会把持久化日志冲爆（0.2.16 已经在 logcat 缓冲上吃过一次亏）。
 */
internal object DiagnosticTracer {
    /** 取证总开关：`adb shell settings put global flymefreeform_haptic_trace 1`（取证完记得置 0）。 */
    private const val TRACE_KEY = "flymefreeform_haptic_trace"

    private const val TRACE_CACHE_MS = 3_000L
    private const val THROTTLE_MS = 250L
    private const val STACK_DEPTH = 9

    private var traceOn = false
    private var traceReadAt = 0L
    private var lastLogAt = 0L

    /**
     * 安装触觉反馈取证钩子。解析按「名字 + 参数个数」而非参数类型名，
     * 与项目其它 hook 保持一致（ColorOS 升级后 androidx / 框架内部类签名会漂移）。
     */
    fun installHapticTrace(module: XposedModule, log: (Int, String, Throwable?) -> Unit) {
        val methods = runCatching {
            View::class.java.declaredMethods.filter { it.name == "performHapticFeedback" && it.parameterCount in 1..2 }
        }.getOrNull()
        if (methods.isNullOrEmpty()) {
            log(Log.WARN, "HAPTIC_TRACE_UNAVAILABLE", null)
            return
        }
        methods.forEachIndexed { index, method ->
            runCatching {
                module.hook(method).setId("flymefreeform.trace.haptic$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        trace(chain, log)
                        chain.proceed()
                    }
            }.onFailure { log(Log.WARN, "HAPTIC_TRACE_HOOK_FAILED index=$index", it) }
        }
        log(Log.INFO, "HAPTIC_TRACE_INSTALLED count=${methods.size}", null)
    }

    private fun trace(chain: XposedInterface.Chain, log: (Int, String, Throwable?) -> Unit) {
        val view = chain.thisObject as? View
        if (!traceEnabled(view?.context)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < THROTTLE_MS) return
        lastLogAt = now
        val feedback = chain.getArg(0) as? Int
        val stack = runCatching {
            Throwable().stackTrace
                .dropWhile { it.className == DiagnosticTracer::class.java.name }
                .take(STACK_DEPTH)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        }.getOrDefault("?")
        log(
            Log.INFO,
            "HAPTIC feedback=$feedback view=${view?.javaClass?.simpleName} " +
                "attached=${view?.isAttachedToWindow} stack=$stack",
            null,
        )
    }

    /** 取证开关读取：带缓存（震动路径上对每次调用都读 Settings 太贵），关掉时几乎零开销。 */
    fun traceEnabled(context: Context?): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - traceReadAt < TRACE_CACHE_MS) return traceOn
        if (context == null) return traceOn
        traceReadAt = now
        traceOn = runCatching {
            Settings.Global.getInt(context.contentResolver, TRACE_KEY, 0)
        }.getOrDefault(0) == 1
        return traceOn
    }
}
