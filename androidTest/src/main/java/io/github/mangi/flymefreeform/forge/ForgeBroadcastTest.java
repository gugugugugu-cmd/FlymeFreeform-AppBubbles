/*
 * FlymeFreeform IPC 伪造广播夹具（阶段 5 安全验证）
 *
 * 目的：验证 SystemUI 侧的 GestureBubbleIpcReceiver 在**分发阶段**就拦下了
 * 普通应用发来的 GESTURE_COMMIT 广播——而不是靠 onReceive 里的 uid 事后校验。
 *
 * 这是纯 Java 的 Instrumentation 测试，不用 Compose、不用 Kotlin 标准库，
 * 避免把验证夹具本身变成又一个构建依赖。
 *
 * 判据（见 docs/PHASE5_SECURITY_PROBE.md §4）：
 *   - 日志里出现 GESTURE_IPC_RECEIVED id=forge-security-probe-* → 失败，权限未生效
 *   - 出现无 id 的 GESTURE_IPC_BAD_REQUEST                 → 存疑，请求到了接收端
 *   - 两者都没有，且**有**合法手势留下的 GESTURE_IPC_RECEIVED → 通过
 *
 * 对照组是必需的：只看到"没有记录"无法排除日志管道本身不通。
 */

package io.github.mangi.flymefreeform.forge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/*
 * 编译依赖只有 android.jar：
 *   - 用例靠 `test` 前缀方法名被 instrumentation 反射发现，不用 @Test
 *   - Context 走 ActivityThread.currentApplication()，不用 androidx.test
 */
public class ForgeBroadcastTest {

    private static final String TAG = "ForgeBroadcast";

    private static final String ACTION_COMMIT =
            "io.github.mangi.flymefreeform.action.GESTURE_COMMIT";
    private static final String ACTION_COMMIT_FINALIZE =
            "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_FINALIZE";

    private static final String EXTRA_COMPONENT = "component";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_REQUEST_ID = "request_id";

    /** 伪造请求的固定前缀，方便在 SystemUI 日志里 grep。 */
    private static final String FORGE_PREFIX = "forge-security-probe-";

    /**
     * 方法名必须以 test 开头：instrumentation 用 java.lang.reflect 查找用例，
     * 不依赖 JUnit 注解，这样夹具的编译依赖只剩 android.jar。
     */
    public void testForgedCommitBroadcastMustBeBlocked() {
        // `am instrument` 启动夹具进程后会先建好 Application，
        // 进程里必然已有 Application 实例可拿；这里用反射走
        // ActivityThread.currentApplication()，避免依赖 androidx.test。
        Context context = appContext();
        String self = context.getPackageName();

        // 目标选一个真实存在、可被气泡化的组件：微信主界面。
        // 若微信未安装，退回本应用自己的 Launcher Activity——重点不是目标是谁，
        // 而是这条广播有没有到达接收端。
        ComponentName target = new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
        if (context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm") == null) {
            Intent selfLaunch = context.getPackageManager()
                    .getLaunchIntentForPackage(self);
            if (selfLaunch != null && selfLaunch.getComponent() != null) {
                target = selfLaunch.getComponent();
            }
        }

        long stamp = System.currentTimeMillis();

        // 探测 1：普通 sendBroadcast（无权限限定）。
        // 若接收端没有 requiredPermission，这条会直接进 onReceive。
        sendForged(context, ACTION_COMMIT, target, stamp + "-plain");

        // 探测 2：带 REQUIRED 权限限定的 sendBroadcast。
        // 调用方不持有 STATUS_BAR_SERVICE，普通应用不会抛 SecurityException，
        // 而是静默地一条都送不到——这正好是区分"权限拦下"和"接收端没注册"的关键。
        sendForgedWithPermission(context, ACTION_COMMIT, target, stamp + "-perm");

        // 探测 3：finalize 变体，同样应被拦。
        sendForged(context, ACTION_COMMIT_FINALIZE, target, stamp + "-finalize");

        Log.i(TAG, "FORGE_SENT count=3 target=" + target.flattenToShortString()
                + " self=" + self
                + " — 请现在导出模块日志，检查是否出现 " + FORGE_PREFIX);
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(thread);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable t) {
            Log.w(TAG, "FORGE_CONTEXT_FALLBACK " + t.getClass().getSimpleName());
        }
        throw new IllegalStateException("无法取得夹具进程 Context");
    }

    private void sendForged(Context context, String action, ComponentName target, String id) {
        try {
            context.sendBroadcast(build(action, target, id));
        } catch (Throwable t) {
            Log.w(TAG, "FORGE_SEND_FAILED id=" + id + " " + t.getClass().getSimpleName());
        }
    }

    private void sendForgedWithPermission(
            Context context, String action, ComponentName target, String id) {
        try {
            context.sendBroadcast(build(action, target, id), "android.permission.STATUS_BAR_SERVICE");
            Log.i(TAG, "FORGE_SEND_PERMISSION_OK id=" + id
                    + "（未抛异常是预期的：无权限的发送方会被静默丢弃）");
        } catch (Throwable t) {
            Log.w(TAG, "FORGE_SEND_PERMISSION_FAILED id=" + id + " " + t.getClass().getSimpleName());
        }
    }

    private Intent build(String action, ComponentName target, String id) {
        Bundle extras = new Bundle();
        extras.putString(EXTRA_COMPONENT, target.flattenToString());
        extras.putInt(EXTRA_USER_ID, 0);
        extras.putString(EXTRA_SOURCE, "forge-probe");
        extras.putString(EXTRA_REQUEST_ID, FORGE_PREFIX + id);
        return new Intent(action).putExtras(extras);
    }
}
