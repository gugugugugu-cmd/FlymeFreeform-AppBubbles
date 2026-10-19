package io.github.mangi.flymefreeform.platform.common

import android.content.Context

/**
 * system_server 侧访问系统服务的最小抽象。
 *
 * 背景：ColorOS 协调器原先把 `com.android.server.wm.FlexibleTaskController` 当作
 * 上下文与 ATMS 的载体（`mContext` / `mAtms` 两个字段）。但阶段一 DEX 审计已确认
 * **Pixel Android 17 上不存在该类**，硬依赖会让整个协调器无法初始化。
 *
 * 实际上协调器只用到这两样东西，因此抽出本接口，让不同 ROM 各自提供：
 *  - ColorOS：沿用 FlexibleTaskController（保持既有行为不变）
 *  - 其它平台（Pixel 等）：直接由 system_server 自身的服务引用构造
 */
internal interface SystemServerAccess {

    /** system_server 侧 Context（用于 registerReceiver / getService 等）。 */
    val context: Context

    /**
     * `ActivityTaskManagerService` 实例。
     * 协调器用它读 `mRootWindowContainer` / `mWindowManager`。
     */
    val activityTaskManager: Any

    companion object {
        /** ColorOS 路径：从 FlexibleTaskController 取 mContext / mAtms。 */
        fun fromController(controller: Any): SystemServerAccess =
            object : SystemServerAccess {
                override val context: Context =
                    read(controller, "mContext") as? Context
                        ?: error("FlexibleTaskController.mContext unavailable")

                override val activityTaskManager: Any =
                    read(controller, "mAtms") ?: error("FlexibleTaskController.mAtms unavailable")
            }

        /**
         * AOSP / Pixel 路径：从 ActivityManagerService 取 Context 与 ATMS。
         *
         * AMS 自身没有 mContext（它有 mContexts / mSystemContext），
         * 因此优先 mContexts 数组首项，回退 mSystemContext；
         * ATMS 通过 `mActivityTaskManager` 字段取得。
         *
         * @return null 表示关键字段缺失，调用方应记录并跳过（不抛异常影响 system_server）。
         */
        fun fromActivityManager(ams: Any): SystemServerAccess? {
            val ctx =
                (read(ams, "mContext") as? Context)
                    ?: (read(ams, "mSystemContext") as? Context)
                    ?: ((read(ams, "mContexts") as? Array<*>)?.filterIsInstance<Context>()?.firstOrNull())
                    ?: return null
            val atms =
                read(ams, "mActivityTaskManager")
                    ?: read(ams, "mAtmInternal")
                    ?: return null
            return object : SystemServerAccess {
                override val context: Context = ctx
                override val activityTaskManager: Any = atms
            }
        }

        private fun read(
            target: Any,
            name: String,
        ): Any? =
            try {
                var cls: Class<*>? = target.javaClass
                while (cls != null) {
                    try {
                        val field = cls.getDeclaredField(name)
                        field.isAccessible = true
                        return field.get(target)
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
    }
}
