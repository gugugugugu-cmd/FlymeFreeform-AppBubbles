package io.github.mangi.flymefreeform.framework

import androidx.compose.runtime.Immutable
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot

@Immutable
internal data class FrameworkConnectionState(
    val status: FrameworkConnectionStatus = FrameworkConnectionStatus.Waiting,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int? = null,
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    val grantedScopes: Set<String> = emptySet(),
    val isUpdating: Boolean = false,
    val isRequestingScope: Boolean = false,
    val issue: FrameworkConnectionIssue? = null,
) {
    val moduleEnabled: Boolean
        get() = settings.enabled

    val missingScopes: Set<String>
        get() = REQUIRED_SCOPES - grantedScopes

    /**
     * 设置项是否可改。
     *
     * 历史上这里要求 `missingScopes.isEmpty()`，缺任一 scope 就把整个设置区锁死。
     * 问题：REQUIRED_SCOPES 含 ColorOS 专属的 com.coloros.smartsidebar，
     * 在 Pixel 上根本不存在该包，用户无论如何也补不齐 → 总开关永久灰化，
     * 模块永远处于 enabled=false，手势入口因此永远不注册。
     *
     * 现在只保留「框架必须已连接」这一条真实约束：
     * 缺 scope 只影响对应进程的功能，不该连累其它设置项，更不该锁死总开关。
     */
    val canChangeSettings: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope

    val canRequestScope: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isNotEmpty()

    companion object {
        /**
         * 必需作用域。
         *
         * 不含 com.coloros.smartsidebar：那是 ColorOS 专属侧边栏，
         * Pixel / AOSP 上不存在该包，列进来只会让「申请作用域」永远无法完成。
         * 侧边栏功能在 AOSP 上本就不可用，缺它不影响手势与气泡主链。
         */
        val REQUIRED_SCOPES: Set<String> =
            linkedSetOf(
                "system",
                "com.android.systemui",
                "com.android.launcher",
            )
    }
}

internal enum class FrameworkConnectionStatus {
    Waiting,
    Connected,
    Incompatible,
    Error,
}

internal enum class FrameworkConnectionIssue {
    ServiceApiTooOld,
    RemoteCapabilityMissing,
    SystemCapabilityMissing,
    MultipleServices,
    ConnectionFailed,
    WriteFailed,
    ScopeRequestFailed,
}
