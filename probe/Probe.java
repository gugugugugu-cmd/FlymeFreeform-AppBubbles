package io.github.mangi.flymefreeformprobe;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 Instrumentation 注入目标桌面进程，仅用公开 API，
 * 输出真实存在的类 / 方法签名，以及最近任务视图的节点结构。
 */
public class Probe extends Instrumentation {
    private static final String TAG = "FlymeProbe";
    private static final int MAX_NODES = 900;

    private volatile boolean printedStruct = false;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        try {
            String pkg = System.getProperty("flyme.probe.pkg");
            if (pkg == null) pkg = System.getenv("FLYME_PROBE_PKG");
            if (pkg == null) {
                ApplicationInfo ai = getContext().getApplicationInfo();
                pkg = ai == null ? "?" : ai.packageName;
            }
            Log.i(TAG, "PROBE_PKG=" + pkg);
            if (pkg.contains("launcher")) dumpLauncher();
            else dumpSystemUi();
            if (pkg.contains("launcher")) watchRecentTaskWindows();
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_FAIL", t);
        }
        for (int i = 0; i < 240; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
        }
        finish(0, null);
    }

    private void watchRecentTaskWindows() {
        final UiAutomation ua = getUiAutomation();
        ua.setOnAccessibilityEventListener(new UiAutomation.OnAccessibilityEventListener() {
            @Override public void onAccessibilityEvent(AccessibilityEvent event) {
                if (printedStruct) return;
                CharSequence cn = event.getClassName();
                String name = cn == null ? "" : cn.toString();
                Log.i(TAG, "PROBE_WINDOW " + event.getEventType() + " " + name);
                if (name.contains("Recents") || name.contains("TaskView") || name.contains("Quickstep")) {
                    printedStruct = true;
                    AccessibilityNodeInfo root = ua.getRootInActiveWindow();
                    if (root == null) { Log.i(TAG, "PROBE_STRUCT no_root"); return; }
                    List<String> out = new ArrayList<>();
                    walk(root, 0, out);
                    for (String line : out) Log.i(TAG, "PROBE_NODE " + line);
                    Log.i(TAG, "PROBE_STRUCT_DONE lines=" + out.size());
                }
            }
        });
        AccessibilityServiceInfo info = ua.getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ua.setServiceInfo(info);
    }

    private void walk(AccessibilityNodeInfo node, int depth, List<String> out) {
        if (node == null || out.size() > MAX_NODES) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth && i < 24; i++) sb.append("  ");
        CharSequence cn = node.getClassName();
        CharSequence id = node.getViewIdResourceName();
        sb.append(cn == null ? "?" : cn).append(" #").append(id == null ? "-" : id)
          .append(" txt=").append(node.getText() == null ? "-" : node.getText())
          .append(" desc=").append(node.getContentDescription() == null ? "-" : node.getContentDescription())
          .append(" act=").append(node.isClickable() || node.isLongClickable());
        out.add(sb.toString());
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), depth + 1, out);
    }

    private void dumpLauncher() {
        for (String[] spec : new String[][] {
                {"com.android.quickstep.views.TaskMenuView", "addMenuOptions"},
                {"com.android.quickstep.views.TaskMenuView", ""},
                {"com.android.quickstep.views.TaskView", "showMenu"},
                {"com.android.quickstep.views.TaskView", "onPrepareMenu"},
                {"com.android.quickstep.views.RecentsView", "onPrepareMenu"},
                {"com.android.quickstep.views.TaskThumbnailView", "addAction"},
        }) {
            dump(spec[0], spec[1]);
        }
    }

    private void dumpSystemUi() {
        for (String[] spec : new String[][] {
                {"com.android.systemui.notification.NotificationBubbleButton", ""},
                {"com.android.systemui.wmshell.BubblesManager", "setBubblesManager"},
        }) {
            dump(spec[0], spec[1]);
        }
    }

    private void dump(String className, String methodFilter) {
        try {
            Class<?> c = Class.forName(className, false, Probe.class.getClassLoader());
            StringBuilder sb = new StringBuilder("PROBE_CLASS_OK " + className);
            for (Method m : c.getDeclaredMethods()) {
                if (methodFilter.isEmpty() || m.getName().contains(methodFilter)) {
                    sb.append(" | ").append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(ps[i].getName());
                    }
                    sb.append(")->").append(m.getReturnType().getName());
                }
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.i(TAG, "PROBE_CLASS_MISSING " + className + " : " + t);
        }
    }
}
