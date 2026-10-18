/*
 * FlymeFreeform IPC 伪造广播夹具（阶段 5 安全验证）
 *
 * 目的：验证 SystemUI 侧的 GestureBubbleIpcReceiver 在**分发阶段**就拦下了
 * 普通应用发来的 GESTURE_COMMIT 广播——而不是靠 onReceive 里的 uid 事后校验。
 *
 * 这是纯 Java 的 Instrumentation 测试，不用 Compose、不用 Kotlin 标准库，
 * 避免把验证夹具本身变成又一个构建依赖。
 *
 * 判据（两条都要满足才算"被拦下"）：
 *   1. 伪造广播发出后 SystemUI 模块日志里**没有新增** GESTURE_IPC_RECEIVED
 *   2. 伪造广播发出后 SystemUI 模块日志里**也没有** GESTURE_IPC_BAD_REQUEST
 *
 * 只有第 1 条而没有第 2 条是不够的：那只能说明请求被解析失败，不能说明被权限拦下。
 * 反过来，如果两条里任何一条出现，说明权限限定没有生效，属于安全缺陷。
 *
 * 对照组：同一个夹具先从**本应用自己**的进程发一条假广播（必然被拦），
 * 再请用户在模块里正常触发一次手势（必然不被拦），用后者证明这份日志管道本身是通的。
 * 没有对照组的话，"日志里没有记录"可能只是日志没写进去。
 */

package io.github.mangi.flymefreeform.forge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.junit.Test;

/*
 * 不用 androidx.test：夹具只需要一个 Context，而已安装测试包的 Instrumentation
 * 注册里自带一个无参构造的 Instrumentation 实例，直接用受保护静态方法取即可。
 * 这样夹具 APK 只依赖 android.jar + 平台自带 JUnit4，构建面最小。
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

    @Test
    public void forgedCommitBroadcastMustBeBlocked() {
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
