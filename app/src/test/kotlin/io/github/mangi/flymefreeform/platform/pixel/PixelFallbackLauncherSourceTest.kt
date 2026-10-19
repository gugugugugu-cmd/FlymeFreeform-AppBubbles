package io.github.mangi.flymefreeform.platform.pixel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查意见 P1（2026-10-18）的静态回归锁。
 *
 * 背景：协调器曾硬编码 `ColorOsFreeformLauncher` 作为「明确拒绝」后的回退，
 * 在 Pixel 上等于给 AOSP 塞 ColorOS 的启动参数。修复把回退策略改成按入口注入，
 * 但没有人能保证下一次改动不会把厂商参数重新引回 Pixel 路径。
 *
 * 这里不做行为测试（`PixelFallbackLauncher` 需要真实 Context），
 * 只锁源码约束：**Pixel 回退实现里不得出现任何厂商启动参数**。
 * 纯文本扫描，不依赖 Robolectric。
 */
class PixelFallbackLauncherSourceTest {

    private fun readSource(): String {
        // Gradle 单元测试的 cwd 是 app/；同时兼容从仓库根运行的情况。
        val candidates =
            listOf(
                File("src/main/kotlin/io/github/mangi/flymefreeform/platform/pixel/PixelFallbackLauncher.kt"),
                File("app/src/main/kotlin/io/github/mangi/flymefreeform/platform/pixel/PixelFallbackLauncher.kt"),
            )
        val file =
            candidates.firstOrNull { it.isFile }
                ?: throw AssertionError("PixelFallbackLauncher.kt not found, cwd=${File("").absolutePath}")
        return file.readText()
    }

    /** 去掉注释，只校验可执行代码。 */
    private fun codeOnly(source: String): String =
        source
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")

    @Test
    fun `pixel fallback must not carry coloros windowing mode`() {
        val code = codeOnly(readSource())
        assertTrue(
            "PixelFallbackLauncher 不得调用 setLaunchWindowingMode（ColorOS 自由窗协议）",
            "setLaunchWindowingMode" !in code,
        )
        assertTrue(
            "PixelFallbackLauncher 不得写 windowingMode",
            "windowingMode" !in code,
        )
        assertTrue(
            "PixelFallbackLauncher 不得写 mZoomLaunchFlags",
            "mZoomLaunchFlags" !in code,
        )
    }

    @Test
    fun `pixel fallback must not import coloros launcher`() {
        val code = codeOnly(readSource())
        assertTrue(
            "Pixel 回退路径不得依赖 platform.coloros 包",
            "platform.coloros" !in code,
        )
    }

    @Test
    fun `pixel fallback uses plain new-task launch`() {
        val code = codeOnly(readSource())
        assertEquals(
            "回退启动应且仅应带 FLAG_ACTIVITY_NEW_TASK 这一项 flag",
            1,
            Regex("FLAG_ACTIVITY_NEW_TASK").findAll(code).count(),
        )
        assertTrue("回退应使用不带 options 的 startActivity", "startActivity(intent)" in code)
    }
}
