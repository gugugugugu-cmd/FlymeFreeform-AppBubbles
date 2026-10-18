package io.github.mangi.flymefreeform.platform.pixel

import android.content.Context
import android.util.Log
import io.github.mangi.flymefreeform.platform.common.AppBubbleLauncher

/**
 * SystemUI 进程内的 Pixel 气泡后端持有者。
 *
 * 同一个 [PixelAppBubbleLauncher] 实例同时服务两个消费方：
 *  - 开发测试入口 `PixelBubbleTestEntry`（广播触发）
 *  - 手势提交 `GestureBubbleIpcReceiver`（system_server 经 [io.github.mangi.flymefreeform.platform.common.GestureBubbleIpcProtocol] 跨进程送入）
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
