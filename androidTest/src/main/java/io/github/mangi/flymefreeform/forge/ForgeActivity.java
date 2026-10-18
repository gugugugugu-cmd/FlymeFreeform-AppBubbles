/*
 * FlymeFreeform IPC 伪造广播夹具（阶段 5 安全验证）
 *
 * 目的：验证 SystemUI 侧 GestureBubbleIpcReceiver 在**分发阶段**就拦下了
 * 普通应用发来的 GESTURE_COMMIT 广播，而不是靠 onReceive 里的 uid 事后校验。
 *
 * 为什么是 Activity 而不是 instrumentation：
 * 第一版写成 `am instrument`，结果 INSTRUMENTATION_RESULT: Process crashed。
 * 原因是 `am instrument` 会把**目标包**的 Application 加载进进程，目标包写的是
 * io.github.mangi.flymefreeform（主模块）。于是主模块的 Xposed 入口
 * （@IXposedHookLoadPackage，内部 `module.log(...)` 依赖 `module` 实例）
 * 在一个没有初始化 module 的普通进程里被调用 → NPE → 进程当场崩溃。
 * 日志 2026-10-18T23:46:03 里崩溃堆栈的 ZygoteInit → handleBindApplication
 * 链路正是 Application 构造阶段。
 *
 * 改用独立的 Activity（启动即可发广播、无需任何点击），一来绕开 Application 加载，
 * 二来省掉「用 sdcard 当 am 的权限」这类多余操作。
 *
 * 判据见 docs/PHASE5_SECURITY_PROBE.md §4。
 */

package io.github.mangi.flymefreeform.forge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

public class ForgeActivity extends Activity {

    private static final String TAG = "ForgeBroadcast";

    private static final String ACTION_COMMIT =
            "io.github.mangi.flymefreeform.action.GESTURE_COMMIT";
    private static final String ACTION_COMMIT_FINALIZE =
            "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_FINALIZE";

    /** 与工程内同名常量一致：接收端按这些键取值，键名写错会让"被拦下"看起来像"成功了"。 */
    private static final String EXTRA_COMPONENT = "component";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_REQUEST_ID = "request_id";

    /** 伪造请求的固定前缀，方便在 SystemUI 日志里检索。 */
    static final String FORGE_PREFIX = "forge-security-probe-";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildResultView("探测中…"));

        // 等界面真正挂上再发，避免进程刚建好就发导致的时间戳与日志对不齐
        new Handler(Looper.getMainLooper()).postDelayed(this::runProbes, 800);
    }

    private TextView buildResultView(String text) {
        TextView view = new TextView(this);
        view.setTextSize(14);
        view.setPadding(48, 96, 48, 48);
        view.setGravity(Gravity.START);
        view.setText(text);
        return view;
    }

    private void runProbes() {
        ComponentName target = resolveTarget();
        long stamp = System.currentTimeMillis();
        StringBuilder report = new StringBuilder();

        report.append("包名: ").append(getPackageName()).append('\n');
        report.append("uid: ").append(getApplicationInfo().uid).append('\n');
        report.append("目标: ").append(target.flattenToShortString()).append('\n');
        report.append("-----------------------------\n");

        // 探测 1：普通 sendBroadcast（无权限限定）。
        // 若接收端没有 requiredPermission，这一条会直接进 onReceive。
        report.append(probe1(context(), ACTION_COMMIT, target, stamp + "-plain"));

        // 探测 2：带 STATUS_BAR_SERVICE 限定的 sendBroadcast。
        // 调用方不持有该签名权限：普通应用不会抛 SecurityException，
        // 而是静默地一条都送不到——这正是区分「权限拦下」与「接收端没注册」的关键。
        report.append(probe2(context(), ACTION_COMMIT, target, stamp + "-perm"));

        // 探测 3：finalize 变体，同样应被拦。
        report.append(probe3(context(), ACTION_COMMIT_FINALIZE, target, stamp + "-finalize"));

        report.append("-----------------------------\n");
        report.append("已发出 3 条。请导出模块日志，\n");
        report.append("查看是否出现 id=").append(FORGE_PREFIX).append("*\n\n");
        report.append("然后在设备上正常划一次手势做对照。");
        setContentView(buildResultView(report.toString()));
        Log.i(TAG, "FORGE_DONE " + report.toString().replace('\n', ' '));
    }

    private Context context() {
        return getApplicationContext() != null ? getApplicationContext() : this;
    }

    private ComponentName resolveTarget() {
        ComponentName target = new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
        if (getPackageManager().getLaunchIntentForPackage("com.tencent.mm") == null) {
            target = new ComponentName(getPackageName(), ForgeActivity.class.getName());
        }
        return target;
    }

    private String probe1(Context ctx, String action, ComponentName target, String id) {
        try {
            ctx.sendBroadcast(build(action, target, id));
            return "1 无权限发送: 已调用\n";
        } catch (Throwable t) {
            return "1 无权限发送: " + t.getClass().getSimpleName() + '\n';
        }
    }

    private String probe2(Context ctx, String action, ComponentName target, String id) {
        try {
            ctx.sendBroadcast(build(action, target, id), "android.permission.STATUS_BAR_SERVICE");
            return "2 带权限发送: 已调用（未抛异常是预期的）\n";
        } catch (Throwable t) {
            return "2 带权限发送: " + t.getClass().getSimpleName() + '\n';
        }
    }

    private String probe3(Context ctx, String action, ComponentName target, String id) {
        try {
            ctx.sendBroadcast(build(action, target, id));
            return "3 finalize 变体: 已调用\n";
        } catch (Throwable t) {
            return "3 finalize 变体: " + t.getClass().getSimpleName() + '\n';
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
