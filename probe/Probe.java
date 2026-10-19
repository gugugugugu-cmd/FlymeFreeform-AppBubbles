package io.github.mangi.flymefreeformprobe;

import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 以 Instrumentation 方式注入目标进程（libxposed 的 legacy 兼容层），
 * 只做一件事：在进程里输出真实存在的目标类/方法，避免「类名猜错导致 hook 静默失败」。
 */
public class Probe extends Instrumentation {
    private static final String TAG = "FlymeProbe";
    private static final String TARGET = "com.android.launcher";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        try {
            Bundle args = getArguments();
            String pkg = args == null ? null : args.getString("package");
            if (pkg == null) {
                ApplicationInfo ai = getContext().getApplicationInfo();
                pkg = ai == null ? "?" : ai.packageName;
            }
            Log.i(TAG, "PROBE_PKG=" + pkg);
            if (TARGET.equals(pkg) || pkg.startsWith(TARGET)) {
                dumpLauncher();
            } else if (pkg.startsWith("com.android.systemui")) {
                dumpSystemUi();
            }
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_FAIL", t);
        }
        Log.i(TAG, "PROBE_DONE");
        SystemClock.sleep(2000);
        finish(0, null);
    }

    private void dumpLauncher() {
        for (String[] spec : new String[][] {
                {"com.android.quickstep.views.TaskMenuView", "addMenuOptions"},
                {"com.android.quickstep.views.TaskView", "onPrepareMenu"},
                {"com.android.launcher3.views.BubbleTextHolder", ""},
                {"com.android.quickstep.views.TaskView", "showMenu"},
                {"com.android.quickstep.views.RecentsView", "showMenu"},
                {"com.android.launcher3.taskbar.TaskbarActivityContext", "updateSysuiFlags"},
        }) {
            dump(spec[0], spec[1]);
        }
    }

    private void dumpSystemUi() {
        for (String[] spec : new String[][] {
                {"com.android.systemui.notification.NotificationBubbleButton", "shouldShow"},
                {"com.android.systemui.notification.NotificationBubbleButton", "shouldShowBubbleButton"},
                {"com.android.systemui.wmshell.BubblesManager", "setBubblesManager"},
                {"com.android.systemui.statusbar.notification.row.NotificationContentInflater", "inflate"},
        }) {
            dump(spec[0], spec[1]);
        }
    }

    private void dump(String className, String methodFilter) {
        try {
            ClassLoader cl = Probe.class.getClassLoader();
            Class<?> c = Class.forName(className, false, cl);
            StringBuilder sb = new StringBuilder("PROBE_CLASS_OK " + className);
            for (Method m : c.getDeclaredMethods()) {
                if (methodFilter.isEmpty() || m.getName().contains(methodFilter)) {
                    sb.append(" | ").append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(ps[i].getName());
                    }
                    sb.append(") -> ").append(m.getReturnType().getName());
                    if (m.isSynthetic()) sb.append(" [synthetic]");
                }
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.i(TAG, "PROBE_CLASS_MISSING " + className + " : " + t);
        }
    }
}
