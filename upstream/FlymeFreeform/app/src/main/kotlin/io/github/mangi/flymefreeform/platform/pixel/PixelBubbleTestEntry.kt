package io.github.mangi.flymefreeform.platform.pixel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchRequest
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchResult

/**
 * 阶段 2 开发测试入口（不改手势 UI）。
 *
 * 在 SystemUI 进程动态注册广播接收器，收到 [ACTION_TEST_LAUNCH_BUBBLE] 后
 * 从 extra 解析目标组件，构造 [BubbleLaunchRequest] 提交给 [PixelAppBubbleLauncher]，
 * 并把结果以 [BubbleLaunchResult] 状态 + 诊断日志形式暴露。
 *
 * 触发方式（外部 adb / 模块 App）：
 *   adb shell am broadcast -a io.github.mangi.flymefreeform.action.TEST_LAUNCH_BUBBLE \
 *       --es component "com.tencent.mm/com.tencent.mm.ui.LauncherUI" \
 *       --el user_id 0 \
 *       --es source "test-entry"
 *
 * 结果读取：Logcat 关键字 `BUBBLE_TEST_RESULT`（状态码）与 `BUBBLE_TEST_DIAG`（诊断）。
 */
internal class PixelBubbleTestEntry(
    private val context: Context,
    private val module: XposedModule,
) {
    private val launcher =
        PixelBubbleBackend.get(context) { msg ->
            // LSPosed 模块进程里 android.util.Log 不进模块日志，必须转给 module.log()
            module.log(Log.INFO, TAG, "BUBBLE_DIAG $msg")
        }
    private var bound = false
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (bound) return
        bound = true
        // 绑定 BubbleController：
        // 1) 先试主动反射（INSTANCE 单例 / LocalServices）
        // 2) hook BubblesManager 构造器 + 事件回调兜底（阶段 1 验证过的
        //    BubblesManager.mBubbles → this$0 → BubbleController 链）
        launcher.bindController()
        installBubbleControllerCapture()
        val filter =
            IntentFilter().apply {
                addAction(ACTION_TEST_LAUNCH_BUBBLE)
                // 兼容历史文档命令（io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE）
                addAction(ACTION_TEST_LAUNCH_BUBBLE_LEGACY)
            }
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_TEST_LAUNCH_BUBBLE &&
                        intent.action != ACTION_TEST_LAUNCH_BUBBLE_LEGACY
                    ) {
                        return
                    }
                    module.log(Log.INFO, TAG, "PIXEL_BUBBLE_TEST_RECEIVED action=${intent.action}")
                    val component =
                        intent.getStringExtra(EXTRA_COMPONENT)
                            ?.let(ComponentName::unflattenFromString)
                    if (component == null) {
                        module.log(Log.WARN, TAG, "BUBBLE_TEST_RESULT REQUEST_VALIDATION_FAILED no-component")
                        return
                    }
                    val userId = intent.getLongExtra(EXTRA_USER_ID, 0L).toInt()
                    val source = intent.getStringExtra(EXTRA_SOURCE) ?: "test-entry"
                    // UserHandle.of(int) 在 SDK 37 为隐藏 API，用反射构造。
                    val userHandle = runCatching {
                        UserHandle::class.java.getMethod("of", Integer.TYPE)
                            .invoke(null, userId) as UserHandle
                    }.getOrNull()
                    if (userHandle == null) {
                        module.log(Log.WARN, TAG, "PIXEL_BUBBLE_TEST_USERHANDLE_REFLECT_FAILED", null)
                        return
                    }
                    val request =
                        BubbleLaunchRequest(
                            component = component,
                            userHandle = userHandle,
                            source = source,
                        )
                    // 广播到达时再解一次（hook 可能已在此前捕获到实例）
                    if (!launcher.isAvailable()) launcher.bindController()
                    val result = launcher.launch(request)
                    module.log(
                        Log.INFO,
                        TAG,
                        "BUBBLE_TEST_RESULT ${resultStateName(result)} available=${launcher.isAvailable()} component=$component user=$userId",
                    )
                }
            }
        // Android 14+（API 34+）：动态注册非系统广播必须显式指定 exported 标志。
        // 测试入口需要接收 adb / 外部进程广播，故用 RECEIVER_EXPORTED。
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        module.log(Log.INFO, TAG, "PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=${launcher.isAvailable()}")
    }

    /**
     * 捕获 BubbleController 实例。
     *
     * 关键教训（真机日志）：BubbleController / BubblesManager 在模块 hook 安装前
     * 就已构造完毕（SystemUI 进程常驻 + wm_shell 早期初始化），所以「hook 构造器」
     * 永远等不到调用。改为 hook 它们的**任意实例方法**——只要该对象还活着，
     * 启动后必然至少被调用一次（刷新、查询、dump 等），即可用 thisObject 拿到实例。
     */
    private fun installBubbleControllerCapture() {
        val cl = context.javaClass.classLoader ?: return
        // 目标类：BubbleController 本身（最优） + BubblesManager（阶段 1 验证的事件源）
        captureFromClass(cl, "com.android.wm.shell.bubbles.BubbleController", "CONTROLLER")
        captureFromClass(cl, "com.android.systemui.wmshell.BubblesManager", "BUBBLES_MANAGER")
        // BubblesManager.mBubbles(IBubblesImpl) 也是高频对象，其 this$0 就是 controller
        captureFromClass(cl, "com.android.wm.shell.bubbles.BubbleController\$IBubblesImpl", "IBUBBLES_IMPL")
    }

    /**
     * 对目标类：hook 全部构造器 + 全部实例方法（排除明显高频噪声的 toString/hashCode 等，
     * 但为最大化命中率仍然保留全部——仅首次命中会真正绑定，之后 AtomicBoolean 短路）。
     */
    private fun captureFromClass(cl: ClassLoader, className: String, tag: String) {
        val cls = try { cl.loadClass(className) } catch (_: Throwable) { return }
        val captured = java.util.concurrent.atomic.AtomicBoolean(false)
        var methodHooked = 0
        for (m in cls.declaredMethods) {
            if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
            if (m.isSynthetic || m.isBridge) continue
            try {
                module
                    .hook(m)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.capture_${tag}_${m.name}_${m.parameterCount}")
                    .intercept { chain ->
                        if (captured.compareAndSet(false, true)) {
                            try {
                                onInstanceCaptured(chain.thisObject, tag)
                            } catch (_: Throwable) {
                            }
                        }
                        chain.proceed()
                    }
                methodHooked++
            } catch (_: Throwable) {
            }
        }
        var ctorHooked = 0
        for (ctor in cls.declaredConstructors) {
            try {
                module
                    .hook(ctor)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.capture_${tag}_ctor_$ctorHooked")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (captured.compareAndSet(false, true)) {
                            try {
                                onInstanceCaptured(result, tag)
                            } catch (_: Throwable) {
                            }
                        }
                        result
                    }
                ctorHooked++
            } catch (_: Throwable) {
            }
        }
        module.log(
            Log.INFO,
            TAG,
            "PIXEL_BUBBLE_CAPTURE_HOOKED tag=$tag methods=$methodHooked ctors=$ctorHooked",
        )
    }

    /** 某目标对象首次被观测到：按 tag 决定是直接作为 controller 还是走 BubblesManager 链。 */
    private fun onInstanceCaptured(instance: Any?, tag: String) {
        if (instance == null) return
        when (tag) {
            "CONTROLLER" -> {
                launcher.setController(instance)
                module.log(Log.INFO, TAG, "PIXEL_BUBBLE_CONTROLLER_CAPTURED via=$tag")
            }
            "BUBBLES_MANAGER" -> {
                bindWithRetry(instance)
                module.log(Log.INFO, TAG, "PIXEL_BUBBLE_CONTROLLER_CAPTURED via=$tag")
            }
            "IBUBBLES_IMPL" -> {
                launcher.bindFromIBubblesImpl(instance)
                module.log(Log.INFO, TAG, "PIXEL_BUBBLE_CONTROLLER_CAPTURED via=$tag available=${launcher.isAvailable()}")
            }
        }
    }

    /**
     * 延迟重试绑定 controller：BubblesManager 构造后 mBubbles/this$0 可能尚未赋值，
     * 用主线程 Handler 延迟重试（最多 20 次 × 500ms），确保链就绪。
     */
    private fun bindWithRetry(bubblesManager: Any) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val task =
            object : Runnable {
                override fun run() {
                    if (launcher.isAvailable()) return
                    launcher.bindFromBubblesManager(bubblesManager)
                    if (!launcher.isAvailable() && attempts.incrementAndGet() < 20) {
                        handler.postDelayed(this, 500L)
                    }
                }
            }
        handler.post(task)
    }

    private fun resultStateName(result: BubbleLaunchResult): String =
        when (result) {
            BubbleLaunchResult.RequestValidationFailed -> "REQUEST_VALIDATION_FAILED"
            BubbleLaunchResult.TargetResolutionFailed -> "TARGET_RESOLUTION_FAILED"
            BubbleLaunchResult.BackendUnavailable -> "BACKEND_UNAVAILABLE"
            BubbleLaunchResult.BubbleRequestSubmitted -> "BUBBLE_REQUEST_SUBMITTED"
            BubbleLaunchResult.BubbleCreateFailed -> "BUBBLE_CREATE_FAILED"
            BubbleLaunchResult.TaskLaunchFailed -> "TASK_LAUNCH_FAILED"
            BubbleLaunchResult.ExpandFailed -> "EXPAND_FAILED"
            BubbleLaunchResult.ResultUnknown -> "RESULT_UNKNOWN"
            BubbleLaunchResult.FallbackLaunch -> "FALLBACK_LAUNCH"
            is BubbleLaunchResult.Failed -> "FAILED_${result.diagnosticCode}"
        }

    companion object {
        const val ACTION_TEST_LAUNCH_BUBBLE = "io.github.mangi.flymefreeform.action.TEST_LAUNCH_BUBBLE"
        const val ACTION_TEST_LAUNCH_BUBBLE_LEGACY =
            "io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_SOURCE = "source"
        private const val TAG = "FlymeFreeform"
    }
}
