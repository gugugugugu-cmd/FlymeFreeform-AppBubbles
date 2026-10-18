package io.github.mangi.flymefreeform.platform.pixel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.mangi.flymefreeform.platform.common.FallbackLaunchResult
import io.github.mangi.flymefreeform.platform.common.FallbackLauncher

/**
 * AOSP / Pixel 的回退启动（审查意见 P1，2026-10-18）。
 *
 * 与 [io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformLauncher] 的关键区别：
 * **不携带任何厂商参数**。不调 `setLaunchWindowingMode`、不写 `mZoomLaunchFlags`、
 * 不写 `android:activity.windowingMode`。在 AOSP 上带这些参数等于伪造 ColorOS 协议，
 * 行为不可预期（可能被 `WindowManager` 忽略，也可能触发隐藏校验）。
 *
 * 语义与气泡路径保持一致：`Started` 只表示 `startActivity` 成功返回，
 * **不代表**窗口已可见。日志里必须区分这两件事。
 */
internal class PixelFallbackLauncher(
    private val context: Context,
) : FallbackLauncher {

    override fun launchFallback(component: ComponentName): FallbackLaunchResult {
        if (!isLaunchable(component)) return FallbackLaunchResult.TargetUnavailable

        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            FallbackLaunchResult.Started
        } catch (exception: SecurityException) {
            FallbackLaunchResult.Failed("PIXEL_FALLBACK_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FallbackLaunchResult.Failed("PIXEL_FALLBACK_START_FAILED", exception)
        }
    }

    private fun isLaunchable(component: ComponentName): Boolean =
        try {
            context.packageManager
                .getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
}
