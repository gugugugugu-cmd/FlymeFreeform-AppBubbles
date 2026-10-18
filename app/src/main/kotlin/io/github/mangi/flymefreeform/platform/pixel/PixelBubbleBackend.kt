package io.github.mangi.flymefreeform.platform.pixel

import android.content.Context
import android.util.Log
import io.github.mangi.flymefreeform.platform.common.AppBubbleLauncher
import io.github.mangi.flymefreeform.platform.common.AppLaunchCommitter
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchRequest
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchResult
import android.content.ComponentName
import android.os.UserHandle

/**
 * SystemUI 进程内的 Pixel 气泡后端持有者。
 *
 * 同一个 [PixelAppBubbleLauncher] 实例同时服务两个消费方：
 *  - 开发测试入口 `PixelBubbleTestEntry`（广播触发）
 *  - 手势提交 `PixelBubbleLaunchCommitter`（扇形菜单选中应用）
 *
 * 之所以共享：BubbleController 的实例捕获是一次性的 hook 回调，多个 launcher
 * 各自捕获会导致 debugSink / controller 状态分裂，日志也难以归因。
 */
internal object PixelBubbleBackend {
    private var launcher: PixelAppBubbleLauncher? = null

    /** 取或创建进程内唯一实例；[debugSink] 只在首次创建时生效。 */
    fun get(context: Context, debugSink: ((String) -> Unit)? = null): PixelAppBubbleLauncher {
        launcher?.let { existing ->
            if (debugSink != null) existing.debugSink = debugSink
            return existing
        }
        return PixelAppBubbleLauncher(context.applicationContext ?: context)
            .also {
                it.debugSink = debugSink
                launcher = it
            }
    }

    /** 进程内共享的 launcher（未创建时返回 null）。 */
    fun current(): PixelAppBubbleLauncher? = launcher
}

/**
 * 手势提交 → 气泡启动的桥接实现（SystemUI 进程内，无跨进程跳转）。
 *
 * 阶段 2 之前 [io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator]
 * 的 committer 参数恒为 null，手势提交一直回落到 ColorOS 原生小窗启动。此类补上实现：
 * 扇形菜单选中应用后，直接提交到 Pixel 原生 App Bubbles 后端。
 */
internal class PixelBubbleLaunchCommitter(
    private val context: Context,
    private val userHandle: UserHandle = UserHandle.getUserHandleForUid(0),
    private val debugSink: ((String) -> Unit)? = null,
) : AppLaunchCommitter {
    override fun commit(component: ComponentName): Boolean {
        val backend = PixelBubbleBackend.get(context, debugSink)
        val result =
            try {
                backend.launch(
                    BubbleLaunchRequest(
                        component = component,
                        userHandle = userHandle,
                        source = "radial-gesture",
                    ),
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "commit crashed: ${t.javaClass.simpleName}: ${t.message}")
                debugSink?.invoke("WARN COMMIT_CRASHED ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        debugSink?.invoke("COMMIT ${component.flattenToShortString()} -> $result")
        // 只有确认提交成功才算 accepted，否则让 coordinator 回落原生启动。
        return result == BubbleLaunchResult.BubbleRequestSubmitted
    }

    companion object {
        private const val TAG = "PixelBubbleLaunchCommitter"
    }
}
