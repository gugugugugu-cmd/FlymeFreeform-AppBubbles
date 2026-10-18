package com.floatwindow.morebubblebutton;

import android.app.Notification;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MoreBubbleHookModule extends XposedModule {
    private static final String TAG = "MoreBubbleModule";
    private Object recentsViewInstance;
    private View bubbleButton;
    private static View sSecondRow;
    private ClassLoader mLauncherClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MoreBubbleModule: " + param.getProcessName() + " | API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.google.android.apps.nexuslauncher".equals(pkg)
                || "com.android.launcher3".equals(pkg)) {
            mLauncherClassLoader = param.getDefaultClassLoader();
            hookLauncher(param);
        } else if ("com.android.systemui".equals(pkg)) {
            // SystemUI 不支持热重载，只在首次加载时 hook
            if (!param.isFirstPackage()) return;
            ClassLoader cl = param.getDefaultClassLoader();
            hookSystemUi(cl);
        }
    }

    private ClassLoader mSystemUiClassLoader;
    private static final Map<String, Long> sForcedBubbleKeys = new ConcurrentHashMap<>();
    private static final long FORCED_BUBBLE_GRACE_MS = 8000L;

    // The right corner action uses the official Bubble -> fullscreen handoff.
    // No Bubble -> fullscreen transition is submitted, so SystemUI never rebuilds
    // the edge bubble and its adjacent plus as a side effect.

    private void hookSystemUi(ClassLoader cl) {
        Log.i(TAG, "Hooking SystemUI...");
        // 1. shouldShowBubbleButton: 让所有非前台通知显示气泡按钮
        try {
            Class<?> clazz = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.NotificationContentView");
            hook(clazz.getMethod("shouldShowBubbleButton")).intercept(chain -> {
                try {
                    Context ctx = null;
                    try { ctx = ((View) chain.getThisObject()).getContext(); } catch (Throwable ignored) {}
                    if (ctx != null && !ModuleSettings.isSystemUiBubbleEnabled(ctx)) return chain.proceed();
                } catch (Throwable ignored) {}
                boolean original = (boolean) chain.proceed();
                if (original) return true;
                try {
                    Object contentView = chain.getThisObject();
                    Object row = getFieldSystemUi(contentView, "mContainingNotification");
                    if (row == null) return true;
                    Object adapter = getFieldSystemUi(row, "mEntryAdapter");
                    if (adapter == null) return true;
                    Object sbn = invokeSystemUi(adapter, "getSbn");
                    if (sbn == null) return true;
                    Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
                    if (notif == null) return true;
                    if ((notif.flags & 0x40) != 0) return false;
                    String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
                    Context viewCtx = ((View) contentView).getContext();
                    if (pkg == null || viewCtx.getPackageManager().getLaunchIntentForPackage(pkg) == null) return false;
                    return true;
                } catch (Throwable t) { return true; }
            });
            Log.i(TAG, "Hooked shouldShowBubbleButton OK");
        } catch (Throwable t) { Log.e(TAG, "Hook shouldShowBubbleButton: " + t.getMessage()); }

        // 2. injectBubbleMetadata at bind time
        try {
            Class<?> binderClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : binderClass.getDeclaredMethods()) {
                if (m.getParameterCount() >= 3 && m.getParameterTypes()[2].getName().contains("ExpandableNotificationRow")) {
                    target = m;
                    break;
                }
            }
            if (target != null) {
                hook(target).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object row = chain.getArg(2);
                        if (row == null) return result;
                        Object entry = getFieldSystemUi(row, "mEntry");
                        if (entry == null) return result;
                        Object sbn = getFieldSystemUi(entry, "mSbn");
                        if (sbn == null) return result;
                        Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
                        if (notif == null) return result;
                        if ((notif.flags & 0x40) != 0) return result;
                        if (notif.contentIntent == null) return result;
                        injectBubbleMetadata(entry, notif);
                    } catch (Throwable t) { Log.w(TAG, "bindRow meta inject: " + t.getMessage()); }
                    return result;
                });
                Log.i(TAG, "Hooked NotificationRowBinderImpl OK");
            }
        } catch (Throwable t) { Log.e(TAG, "Hook RowBinder: " + t.getMessage()); }

        // 3. BubblesManager.expandStackAndSelectBubble - 拦截系统点击调用
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            java.lang.reflect.Method expand = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("expandStackAndSelectBubble")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")) {
                    expand = m;
                    break;
                }
            }
            if (expand != null) {
                hook(expand).intercept(chain -> {
                    try {
                        Object entry = chain.getArg(0);
                        if (entry != null) {
                            Object sbn = getFieldSystemUi(entry, "mSbn");
                            Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
                            if (notif != null && (notif.flags & 0x40) == 0 && notif.contentIntent != null) {
                                expandAppBubbleFromNotification(chain.getThisObject(), entry, "expand guard");
                                return null;
                            }
                        }
                    } catch (Throwable t) { Log.w(TAG, "expand guard: " + t.getMessage()); }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked BubblesManager.expandStackAndSelectBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.expandStackAndSelectBubble(NotificationEntry) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook BubblesManager: " + t.getMessage()); }

        // 4. BubblesManager.onUserChangedBubble - 非 bubble 通知首次点击走这里，原生只折叠 shade。
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            java.lang.reflect.Method onUserChanged = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("onUserChangedBubble")
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")
                        && m.getParameterTypes()[1] == boolean.class) {
                    onUserChanged = m;
                    break;
                }
            }
            if (onUserChanged != null) {
                hook(onUserChanged).intercept(chain -> {
                    // 必须先放行原方法：它负责把通知标记为可气泡（Notification.flags |= FLAG_BUBBLE）、
                    // channel.setAllowBubbles(true)、必要时 setBubblesAllowed()，并折叠 shade。
                    // 这些是气泡能被系统接受、不会立刻被清掉的前置条件，跳过会导致小窗一闪即灭。
                    Object result = chain.proceed();
                    try {
                        boolean enabled = (boolean) chain.getArg(1);
                        Object entry = chain.getArg(0);
                        if (enabled && entry != null) {
                            expandAppBubbleFromNotification(chain.getThisObject(), entry, "user change");
                        }
                    } catch (Throwable t) { Log.w(TAG, "user change bubble: " + t.getMessage()); }
                    return result;
                });
                Log.i(TAG, "Hooked BubblesManager.onUserChangedBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.onUserChangedBubble(NotificationEntry, boolean) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook onUserChangedBubble: " + t.getMessage()); }

        // 5. dismissBubbleWithKey guard - 防止刚强制创建的气泡被 Ranking/Channel 立刻移除。
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            for (java.lang.reflect.Method dm : dataCls.getDeclaredMethods()) {
                if (dm.getName().equals("dismissBubbleWithKey")
                        && dm.getParameterCount() >= 2
                        && dm.getParameterTypes()[0] == int.class
                        && dm.getParameterTypes()[dm.getParameterCount() - 1] == String.class) {
                    final int keyArgIndex = dm.getParameterCount() - 1;
                    hook(dm).intercept(chain -> {
                        try {
                            int reason = (int) chain.getArg(0);
                            String key = (String) chain.getArg(keyArgIndex);
                            if ((reason == 4 || reason == 7 || reason == 14) && isRecentlyForcedBubble(key)) {
                                Log.i(TAG, "keep forced bubble: skip dismiss reason=" + reason + " key=" + key);
                                return null;
                            }
                        } catch (Throwable t) { Log.w(TAG, "dismiss guard: " + t.getMessage()); }
                        return chain.proceed();
                    });
                }
            }
            Log.i(TAG, "Hooked BubbleData.dismissBubbleWithKey OK");
        } catch (Throwable t) { Log.w(TAG, "Hook dismiss guard: " + t.getMessage()); }

        // 6. setSelectedBubbleInternal guard
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            java.lang.reflect.Method m = findMethodSystemUi(dataCls, "setSelectedBubbleInternal");
            if (m != null) {
                hook(m).intercept(chain -> {
                    try {
                        Object provider = chain.getArg(0);
                        Object bubbleData = chain.getThisObject();
                        if (provider != null && provider.getClass().getName().endsWith("BubbleEntry")) {
                            java.lang.reflect.Field f = findFieldSystemUi(bubbleData.getClass(), "mBubbles");
                            if (f != null) {
                                f.setAccessible(true);
                                java.util.List list = (java.util.List) f.get(bubbleData);
                                if (list != null && !list.contains(provider)) {
                                    list.add(provider);
                                    Log.i(TAG, "BubbleData.mBubbles forcibly added BubbleEntry");
                                }
                            }
                        }
                    } catch (Throwable t) { Log.w(TAG, "select guard: " + t.getMessage()); }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked setSelectedBubbleInternal OK");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook select guard: " + t.getMessage()); }

        // 7. 改造气泡小窗底部「管理」按钮 (Android 17 BubbleExpandedView)：
        //    不再屏蔽管理按钮，而是把它改造为两种动作按钮——
        //    左下角官方 mManageButton 本体点击 = 「关闭小窗」(收起气泡回到贴边列表，不删除)；
        //    在其右侧克隆一颗同款按钮，点击 = 「全屏展开」(moveTaskViewToFullscreen 反射链)。
        //    官方 updateManageButtonIfExists 每次调用都会 inflate -> addView -> setVisibility
        //    -> setOnClickListener -> post，因此 proceed 之后 mManageButton 已在 this 的 child 列表里，
        //    可直接读取并重设；克隆按钮用横向容器挂到底部，保证一左一右。
        try {
            Class<?> bevCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleExpandedView");
            hook(bevCls.getMethod("updateManageButtonIfExists")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    java.lang.reflect.Field f = bevCls.getDeclaredField("mManageButton");
                    f.setAccessible(true);
                    Object btn = f.get(chain.getThisObject());
                    if (btn instanceof View) {
                        applyCornerButtonActions(chain.getThisObject(), (View) btn);
                    } else {
                        Log.w(TAG, "corner action: mManageButton not a View; skipped");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "corner action install: " + t.getMessage(), t);
                }
                return result;
            });
            Log.i(TAG, "Hooked BubbleExpandedView.updateManageButtonIfExists (corner buttons) OK");
        } catch (Throwable t) { Log.e(TAG, "Hook manage button: " + t.getMessage()); }

        Log.i(TAG, "All SystemUI hooks installed");
    }


    private static boolean expandAppBubbleFromNotification(Object bubblesManager, Object entry, String reason) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            if (sbn == null) return false;
            String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            int userId = 0;
            try { userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn); } catch (Throwable ignored) {}
            if (userId < 0) userId = 0;
            UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            Object bubblesImpl = getFieldSystemUi(bubblesManager, "mBubbles");
            Object controller = getFieldSystemUi(bubblesImpl, "this$0");
            if (controller == null || pkg == null) return false;
            Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
            Context ctx = (Context) getFieldSystemUi(controller, "mContext");
            Intent targetIntent = getNotificationTargetIntent(notif, pkg);
            Intent launchIntent = ctx != null ? ctx.getPackageManager().getLaunchIntentForPackage(pkg) : null;
            if (targetIntent == null) targetIntent = launchIntent;
            if (targetIntent == null || launchIntent == null) {
                Log.w(TAG, reason + ": skip app bubble, no target intent for " + pkg);
                collapseShadeFromManager(bubblesManager);
                return true;
            }
            if (targetIntent.getPackage() == null) {
                targetIntent.setPackage(pkg);
            }
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            final Intent finalIntent = targetIntent;
            final UserHandle finalUser = user;
            Runnable work = () -> {
                try {
                    boolean done = expandAppBubbleA17(controller, finalIntent, finalUser, reason);
                    if (!done) done = expandAppBubbleLegacy(controller, finalIntent, finalUser, reason);
                    if (done) {
                        Log.i(TAG, reason + ": expanded app bubble for " + pkg);
                        runOnSysuiMain(bubblesManager, () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    } else {
                        Log.w(TAG, reason + ": app bubble expand method not found");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, reason + ": app bubble expand failed: " + t.getMessage());
                }
            };
            Object executor = getFieldSystemUi(controller, "mMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) execute.invoke(executor, work); else work.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": app bubble schedule failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Android 17 官方配方（与 BubbleController$IBubblesImpl$$ExternalSyntheticLambda13 / IBubbles.showAppBubble 一致）：
     *   key    = Bubble.getAppBubbleKeyForApp(intent.getPackage(), user)
     *   bubble = data.findAndRemoveBubbleFromOverflow(key)
     *   if (bubble == null) {
     *       pm   = BubbleController.getPackageManagerForUser(user.getIdentifier(), data.mContext)
     *       icon = data.mAppInfoProvider.getActivityInfoIcon(pm, intent)
     *       bubble = new Bubble(intent, user, icon, Bubble$BubbleType.TYPE_APP, key)
     *   }
     *   controller.expandStackAndSelectAppBubble(bubble, EntryPoint.NOTIFICATION_BUBBLE_BUTTON, null)
     *
     * Android 17 已删除旧的
     * expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)，这是通知横幅气泡
     * 点击后打不开小窗的根因。
     */
    private static boolean expandAppBubbleA17(Object controller, Intent intent, UserHandle user, String reason) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Class<?> bubbleCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble");
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            Class<?> typeCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble$BubbleType");
            Class<?> epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            Class<?> reqCls = cl.loadClass("com.android.wm.shell.shared.bubbles.BubbleBarLocation$UpdateLocationRequest");

            Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectAppBubble",
                    bubbleCls, epCls, reqCls);
            if (expand == null) return false;

            Object data = getFieldSystemUi(controller, "mBubbleData");
            if (data == null) return false;

            String pkg = intent.getPackage();
            if (pkg == null) return false;

            Method keyForApp = findMethodSystemUi(bubbleCls, "getAppBubbleKeyForApp", String.class, UserHandle.class);
            if (keyForApp == null) return false;
            String key = (String) keyForApp.invoke(null, pkg, user);

            Method findOverflow = findMethodSystemUi(dataCls, "findAndRemoveBubbleFromOverflow", String.class);
            Object bubble = findOverflow != null ? findOverflow.invoke(data, key) : null;

            if (bubble == null) {
                Object typeApp = enumConstant(typeCls, "TYPE_APP");
                if (typeApp == null) return false;
                Context dataCtx = (Context) getFieldSystemUi(data, "mContext");
                if (dataCtx == null) return false;
                Object pm;
                Method getPm = findMethodSystemUi(controller.getClass(),
                        "getPackageManagerForUser", int.class, Context.class);
                pm = getPm != null ? getPm.invoke(null, userIdOf(user), dataCtx)
                        : dataCtx.getPackageManager();
                Object icon = null;
                Object appInfoProvider = getFieldSystemUi(data, "mAppInfoProvider");
                if (appInfoProvider != null) {
                    Method getIcon = findMethodSystemUi(appInfoProvider.getClass(), "getActivityInfoIcon",
                            android.content.pm.PackageManager.class, Intent.class);
                    if (getIcon != null) {
                        try { icon = getIcon.invoke(appInfoProvider, pm, intent); } catch (Throwable ignored) {}
                    }
                }
                java.lang.reflect.Constructor<?> ctor = bubbleCls.getConstructor(
                        Intent.class, UserHandle.class, android.graphics.drawable.Icon.class, typeCls, String.class);
                bubble = ctor.newInstance(intent, user, icon, typeApp, key);
            }
            if (bubble == null) return false;

            Object entryPoint = enumConstant(epCls, "NOTIFICATION_BUBBLE_BUTTON");
            if (entryPoint == null) entryPoint = findEntryPoint(cl, "NOTIFICATION");
            if (entryPoint == null) return false;

            expand.invoke(controller, bubble, entryPoint, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": A17 expand failed: " + t.getMessage());
            return false;
        }
    }

    /** Android 16 及以前的旧 API，保留作为回退。 */
    private static boolean expandAppBubbleLegacy(Object controller, Intent intent, UserHandle user, String reason) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Object entryPoint = findEntryPoint(cl, "NOTIFICATION");
            Class<?> locCls = clOrNull(cl, "com.android.wm.shell.shared.bubbles.BubbleBarLocation");
            Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectBubble",
                    Intent.class, UserHandle.class,
                    entryPoint != null ? entryPoint.getClass() : Object.class, locCls);
            if (expand == null) {
                for (Method m : controller.getClass().getDeclaredMethods()) {
                    if (m.getName().equals("expandStackAndSelectBubble")
                            && m.getParameterCount() == 4
                            && m.getParameterTypes()[0] == Intent.class) {
                        m.setAccessible(true);
                        expand = m;
                        break;
                    }
                }
            }
            if (expand == null) return false;
            expand.invoke(controller, intent, user, entryPoint, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": legacy expand failed: " + t.getMessage());
            return false;
        }
    }

    private static Object enumConstant(Class<?> enumCls, String name) {
        try {
            for (Object o : (Object[]) enumCls.getMethod("values").invoke(null)) {
                if (name.equals(o.toString())) return o;
            }
        } catch (Throwable t) { Log.w(TAG, "enumConstant: " + t.getMessage()); }
        return null;
    }

    private static int userIdOf(UserHandle user) {
        try {
            Object id = invokeSystemUi(user, "getIdentifier");
            if (id instanceof Integer) return (Integer) id;
        } catch (Throwable ignored) {}
        try {
            Method m = UserHandle.class.getMethod("getUserId", UserHandle.class);
            return (Integer) m.invoke(null, user);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static Intent getNotificationTargetIntent(Notification notif, String pkg) {
        try {
            PendingIntent pi = notif != null ? notif.contentIntent : null;
            Intent intent = null;
            if (pi != null) {
                Method getIntent = findMethodSystemUi(pi.getClass(), "getIntent");
                if (getIntent != null) intent = (Intent) getIntent.invoke(pi);
            }
            if (intent == null) return null;
            Intent copy = new Intent(intent);
            copy.putExtra("IS_FROM_NOTIFICATION", true);
            if (pkg != null && copy.getPackage() == null) {
                copy.setPackage(pkg);
            }
            return copy;
        } catch (Throwable t) {
            Log.w(TAG, "notification target intent: " + t.getMessage());
            return null;
        }
    }

    private static void dismissClickedNotificationIfAutoCancel(Object bubblesManager, Object entry) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notif == null || (notif.flags & Notification.FLAG_AUTO_CANCEL) == 0) return;
            Object visibilityProvider = getFieldSystemUi(bubblesManager, "mVisibilityProvider");
            Object visibility = null;
            if (visibilityProvider != null) {
                Method obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", entry.getClass());
                if (obtain == null) obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", String.class);
                if (obtain != null) {
                    Class<?> argType = obtain.getParameterTypes()[0];
                    Object arg = argType == String.class ? getFieldSystemUi(entry, "key") : entry;
                    visibility = obtain.invoke(visibilityProvider, arg);
                }
            }
            ClassLoader cl = bubblesManager.getClass().getClassLoader();
            Class<?> statsCls = cl.loadClass("com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats");
            Object stats = null;
            for (java.lang.reflect.Constructor<?> c : statsCls.getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    c.setAccessible(true);
                    stats = c.newInstance(1, visibility);
                    break;
                }
            }
            if (stats == null) return;
            Object callbacks = getFieldSystemUi(bubblesManager, "mCallbacks");
            if (callbacks instanceof java.util.List) {
                for (Object cb : (java.util.List<?>) callbacks) {
                    Method remove = findMethodByNameAndCount(cb.getClass(), "removeNotification", 2);
                    if (remove != null) remove.invoke(cb, entry, stats);
                }
                Log.i(TAG, "dismissed clicked auto-cancel notification");
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "dismiss clicked notification: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void runOnSysuiMain(Object bubblesManager, Runnable runnable) {
        try {
            Object executor = getFieldSystemUi(bubblesManager, "mSysuiMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) {
                execute.invoke(executor, runnable);
            } else {
                new android.os.Handler(Looper.getMainLooper()).post(runnable);
            }
        } catch (Throwable t) {
            try { runnable.run(); } catch (Throwable ignored) {}
        }
    }

    private static void collapseShadeFromManager(Object bubblesManager) {
        try {
            Object shadeController = getFieldSystemUi(bubblesManager, "mShadeController");
            if (shadeController == null) return;
            Method postForce = findMethodSystemUi(shadeController.getClass(), "postAnimateForceCollapseShade");
            if (postForce != null) { postForce.invoke(shadeController); return; }
            Method instant = findMethodSystemUi(shadeController.getClass(), "instantCollapseShade");
            if (instant != null) { instant.invoke(shadeController); return; }
            Method full = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class, boolean.class, boolean.class);
            if (full != null) { full.invoke(shadeController, 2, true, true); return; }
            Method normal = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class);
            if (normal != null) normal.invoke(shadeController, 0);
        } catch (Throwable t) {
            Log.w(TAG, "collapseShade: " + t.getMessage());
        }
    }

    private static Method findMethodByNameAndCount(Class<?> c, String n, int count) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == count) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findCompatibleMethodByName(Class<?> c, String n, Class<?> argType) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(argType)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Class<?> clOrNull(ClassLoader cl, String name) {
        try { return cl.loadClass(name); } catch (Throwable t) { return null; }
    }

    private static Object findEntryPoint(ClassLoader cl, String name) {
        try {
            Class<?> epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null)) {
                if (name.equals(e.toString())) return e;
            }
        } catch (Throwable t) { Log.w(TAG, "findEntryPoint: " + t.getMessage()); }
        return null;
    }

    private static void rememberForcedBubble(String key) {
        if (key != null) sForcedBubbleKeys.put(key, System.currentTimeMillis());
    }

    private static boolean isRecentlyForcedBubble(String key) {
        if (key == null) return false;
        Long ts = sForcedBubbleKeys.get(key);
        if (ts == null) return false;
        long age = System.currentTimeMillis() - ts;
        if (age > FORCED_BUBBLE_GRACE_MS) {
            sForcedBubbleKeys.remove(key);
            return false;
        }
        return true;
    }

    private static void injectBubbleMetadata(Object entry, Notification notif) {
        try {
            java.lang.reflect.Field metaField = findFieldSystemUi(entry.getClass(), "mBubbleMetadata");
            if (metaField == null) return;
            metaField.setAccessible(true);
            Object existing = metaField.get(entry);
            if (existing instanceof Notification.BubbleMetadata) {
                setNotificationBubbleMetadata(notif, (Notification.BubbleMetadata) existing);
                return;
            }
            Notification.BubbleMetadata.Builder builder = new Notification.BubbleMetadata.Builder();
            builder.setIntent(notif.contentIntent);
            builder.setDeleteIntent(notif.deleteIntent);
            int iconResId = 0;
            try {
                java.lang.reflect.Method getSmall = notif.getClass().getMethod("getSmallIcon");
                Object smallIcon = getSmall.invoke(notif);
                if (smallIcon != null) {
                    java.lang.reflect.Method getRes = smallIcon.getClass().getMethod("getResId");
                    iconResId = (int) getRes.invoke(smallIcon);
                }
            } catch (Throwable ignore) {}
            String pkg = null;
            try {
                java.lang.reflect.Method m = notif.getClass().getMethod("getPackageName");
                pkg = (String) m.invoke(notif);
            } catch (Throwable ignore) {}
            try {
                if (iconResId != 0) {
                    builder.getClass().getMethod("setIcon", int.class).invoke(builder, iconResId);
                } else if (pkg != null) {
                    builder.getClass().getMethod("setShortcutId", String.class).invoke(builder, pkg);
                } else {
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "icon/shortcut set failed: " + t.getMessage());
                return;
            }
            Notification.BubbleMetadata metadata = builder.build();
            setNotificationBubbleMetadata(notif, metadata);
            metaField.set(entry, metadata);
            Log.i(TAG, "Set bubble metadata OK for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "injectBubbleMetadata: " + t.getMessage());
        }
    }

    private static void setNotificationBubbleMetadata(Notification notif, Notification.BubbleMetadata metadata) {
        try {
            Method m = notif.getClass().getMethod("setBubbleMetadata", Notification.BubbleMetadata.class);
            m.invoke(notif, metadata);
            return;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = findFieldSystemUi(notif.getClass(), "mBubbleMetadata");
            if (f == null) f = findFieldSystemUi(notif.getClass(), "bubbleMetadata");
            if (f != null) {
                f.setAccessible(true);
                f.set(notif, metadata);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setNotificationBubbleMetadata: " + t.getMessage());
        }
    }

    private static Object getField(Object obj, String name) {
        try { java.lang.reflect.Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
    }
    private static Object invoke(Object obj, String method) {
        try { java.lang.reflect.Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }
    private static java.lang.reflect.Field findField(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }
    private static java.lang.reflect.Method findMethod(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { java.lang.reflect.Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    // ===== 气泡小窗底部「关闭 / 全屏」双按钮改造 (Android 17 BubbleExpandedView) =====

    /** 标记我们克隆出来的右侧按钮，便于每次官方重建 mManageButton 时先清掉旧的克隆体。 */
    private static final int CORNER_ROW_TAG = 0x4d4f5245; // "MORE"

    /**
     * 改造气泡小窗底部按钮：官方 mManageButton 留在原位（垂直 LinearLayout 的最后一个 child，
     * 也就是左下角）只改文案与点击行为 = 「关闭小窗」；再克隆一颗同款按钮，用
     * layout_gravity=end 挂在同一父容器里 = 右下角「展开小窗」。
     *
     * 关键：绝不把官方按钮搬离原父容器。官方 updateManageButtonIfExists 每次都会
     * removeView+重新 inflate+addView，一旦我们把它移进自建容器，下一轮官方就把它摘走，
     * 我们的容器里留下的是脱离视图树的旧实例 → 点击失效、行为错乱。
     */
    private static void applyCornerButtonActions(Object expandedView, View manageButton) {
        try {
            // The official method can reuse the same BubbleExpandedView while switching
            // between an app bubble and the overflow drawer. Remove stale clones first.
            removeCornerButtonClones(expandedView, manageButton);

            // The overflow drawer has no task to close or expand. mIsOverflow is the
            // primary flag; getBubbleKey() and mBubble cover builds where that flag is
            // not updated before updateManageButtonIfExists() runs.
            Object isOverflow = getField(expandedView, "mIsOverflow");
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleKey = invoke(expandedView, "getBubbleKey");
            if (Boolean.TRUE.equals(isOverflow)
                    || "Overflow".equals(bubbleKey)
                    || bubble == null) {
                Log.i(TAG, "corner action: skip overflow drawer");
                return;
            }

            // 左下角按钮复用“拖到底部关闭”的官方语义：USER_GESTURE。
            // 不再调用 collapseStack()，否则只是贴边，随后 SystemUI 仍会重新展开该 Bubble。
            if (manageButton instanceof android.widget.TextView) {
                ((android.widget.TextView) manageButton).setText("关闭小窗");
            }
            manageButton.setOnClickListener(v -> dismissBubbleAsUserGesture(expandedView));
            Log.i(TAG, "corner action: left manage button -> dismiss as USER_GESTURE");

            // 右侧克隆同款按钮（右下角）：点击 = 该气泡任务全屏展开。
            View clone = cloneManageButton(manageButton);
            if (clone == null) {
                Log.w(TAG, "corner action: clone button failed; only collapse button installed");
                return;
            }
            if (clone instanceof android.widget.TextView) {
                ((android.widget.TextView) clone).setText("展开小窗");
            }
            clone.setOnClickListener(v -> expandBubbleFullscreen(expandedView));
            clone.setTag(CORNER_ROW_TAG, Boolean.TRUE);
            addCloneBesideManageButton(expandedView, manageButton, clone);
            Log.i(TAG, "corner action: cloned right button -> fullscreen");
        } catch (Throwable t) {
            Log.w(TAG, "corner action install failed: " + t.getMessage(), t);
        }
    }

    /** Remove clones from either the expanded view or the manage button's parent. */
    private static void removeCornerButtonClones(Object expandedView, View manage) {
        java.util.ArrayList<ViewGroup> candidates = new java.util.ArrayList<>();
        if (expandedView instanceof ViewGroup) candidates.add((ViewGroup) expandedView);
        if (manage != null && manage.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) manage.getParent();
            if (!candidates.contains(parent)) candidates.add(parent);
        }
        for (ViewGroup group : candidates) {
            java.util.ArrayList<View> remove = new java.util.ArrayList<>();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (Boolean.TRUE.equals(child.getTag(CORNER_ROW_TAG))) remove.add(child);
            }
            for (View child : remove) group.removeView(child);
        }
    }

    /**
     * Add the clone to the same parent without increasing the vertical stack height.
     * Android 17's parent is vertical, so a normal addView puts the clone on a new
     * row and below the visible window. Overlaying the clone at the manage button's
     * top and aligning it to END keeps both controls in the existing bottom row.
     */
    private static void addCloneBesideManageButton(Object expandedView, View manage, View clone) {
        try {
            ViewGroup parent = manage != null && manage.getParent() instanceof ViewGroup
                    ? (ViewGroup) manage.getParent()
                    : (expandedView instanceof ViewGroup ? (ViewGroup) expandedView : null);
            if (parent == null) {
                Log.w(TAG, "corner action: no parent for clone; row not added");
                return;
            }
            android.view.ViewGroup.LayoutParams base = manage.getLayoutParams();
            int width = base != null ? base.width : LayoutParams.WRAP_CONTENT;
            int height = base != null ? base.height : LayoutParams.WRAP_CONTENT;
            FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(width, height,
                    android.view.Gravity.END | android.view.Gravity.TOP);
            int top = manage.getTop();
            if (top > 0) overlay.topMargin = top;
            overlay.setMarginEnd(base instanceof LinearLayout.LayoutParams
                    ? ((LinearLayout.LayoutParams) base).getMarginEnd() : 0);
            if (parent instanceof FrameLayout) {
                parent.addView(clone, overlay);
            } else {
                // A vertical LinearLayout cannot overlay children; use its existing
                // height and pull the clone back onto the manage row.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
                if (base instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) base;
                    lp.setMarginStart(mlp.getMarginStart());
                    lp.setMarginEnd(mlp.getMarginEnd());
                    lp.bottomMargin = mlp.bottomMargin;
                    lp.topMargin = -(height > 0 ? height : manage.getMeasuredHeight());
                } else {
                    lp.topMargin = -(height > 0 ? height : manage.getMeasuredHeight());
                }
                lp.gravity = android.view.Gravity.END;
                parent.addView(clone, lp);
                // The clone is measured after insertion. Correct its baseline once
                // the official button has a real laid-out position.
                clone.post(() -> {
                    int delta = manage.getTop() - clone.getTop();
                    if (delta != 0) clone.setTranslationY(delta);
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "corner action: add clone failed: " + t.getMessage(), t);
        }
    }

    /** 复制 mManageButton：优先用官方 AlphaOptimizedButton 实例，克隆背景/文案/等宽；否则用按钮通用拷贝。 */
    private static View cloneManageButton(View manageButton) {
        try {
            Context ctx = manageButton.getContext();
            ClassLoader cl = ctx.getClassLoader();
            Class<?> aob = cl.loadClass("com.android.wm.shell.AlphaOptimizedButton");
            Object cloned = aob.getConstructor(Context.class).newInstance(ctx);
            if (cloned instanceof View) {
                View cv = (View) cloned;
                cv.setBackground(manageButton.getBackground());
                CharSequence text = manageButton instanceof android.widget.TextView
                        ? ((android.widget.TextView) manageButton).getText() : null;
                if (text != null && (cv instanceof android.widget.TextView)) {
                    android.widget.TextView tv = (android.widget.TextView) cv;
                    tv.setText(text);
                    tv.setTextColor(((android.widget.TextView) manageButton).getTextColors());
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            ((android.widget.TextView) manageButton).getTextSize());
                }
                LayoutParams mp = manageButton.getLayoutParams();
                if (mp != null) { cv.setLayoutParams(new LayoutParams(mp.width, mp.height)); }
                Log.i(TAG, "corner action: cloned AlphaOptimizedButton OK");
                return cv;
            }
        } catch (Throwable alphaErr) {
            Log.w(TAG, "corner action: AlphaOptimizedButton clone unavailable: "
                    + alphaErr.getMessage() + "; falling back to generic copy");
        }
        try {
            Context ctx = manageButton.getContext();
            android.widget.Button b = new android.widget.Button(ctx);
            b.setBackground(manageButton.getBackground());
            if (manageButton instanceof android.widget.TextView) {
                b.setText(((android.widget.TextView) manageButton).getText());
                b.setTextColor(((android.widget.TextView) manageButton).getTextColors());
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        ((android.widget.TextView) manageButton).getTextSize());
            }
            LayoutParams mp = manageButton.getLayoutParams();
            if (mp != null) { b.setLayoutParams(new LayoutParams(mp.width, mp.height)); }
            Log.i(TAG, "corner action: cloned generic Button OK");
            return b;
        } catch (Throwable genericErr) {
            Log.w(TAG, "corner action: generic clone failed: " + genericErr.getMessage(), genericErr);
            return null;
        }
    }

    /** Close exactly like dragging the bubble to the bottom dismiss target. */
    private static void dismissBubbleAsUserGesture(Object expandedView) {
        try {
            Object bubble = getField(expandedView, "mBubble");
            Object key = bubble != null ? getField(bubble, "mKey") : null;
            Object stackView = getField(expandedView, "mStackView");
            Object data = stackView != null ? getField(stackView, "mBubbleData") : null;
            if (data == null || !(key instanceof String)) {
                throw new IllegalStateException("BubbleData/key unavailable");
            }
            Method dismiss = null;
            for (Method method : data.getClass().getDeclaredMethods()) {
                if (!"dismissBubbleWithKey".equals(method.getName())
                        || method.getParameterCount() < 2
                        || method.getParameterTypes()[0] != int.class
                        || method.getParameterTypes()[method.getParameterCount() - 1] != String.class) {
                    continue;
                }
                method.setAccessible(true);
                dismiss = method;
                break;
            }
            if (dismiss == null) throw new NoSuchMethodException("dismissBubbleWithKey");
            Object[] args = new Object[dismiss.getParameterCount()];
            args[0] = 1; // BubbleData.DismissReason.USER_GESTURE on Android 17.
            for (int i = 1; i < args.length - 1; i++) {
                Class<?> type = dismiss.getParameterTypes()[i];
                args[i] = type.isPrimitive() ? primitiveDefault(type) : null;
            }
            args[args.length - 1] = key;
            dismiss.invoke(data, args);
            Log.i(TAG, "corner action: dismissed bubble key=" + key + " reason=USER_GESTURE");
        } catch (Throwable t) {
            Log.w(TAG, "corner action: dismiss bubble failed: " + t.getMessage(), t);
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    /** Legacy collapse helper retained for non-button callers. */
    private static void collapseBubbleExpandedView(Object expandedView) {
        // 收起小窗 = BubbleData.setExpanded(false)：把展开的小窗收回到贴边气泡列表。
        // BubbleStackView 在 Android 17 并没有直接的 mBubbleController 字段，改为用
        // expandedView.mStackView.mBubbleData 这个在 smali 里确认存在的字段，直接调
        // BubbleData.setExpanded(false)（官方收起展开窗的原子操作）。
        try {
            // 主路径：经 mTaskViewController.this$0 拿到 BubbleController 后收起（官方收起展开窗）。
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleTaskView = bubble != null ? getField(bubble, "mBubbleTaskView") : null;
            Object taskView = bubbleTaskView != null ? getField(bubbleTaskView, "taskView") : null;
            Object taskViewController = taskView != null ? getField(taskView, "mTaskViewController") : null;
            Object collapse = null;
            try {
                // BubbleController 是 BubbleController$BubbleTaskViewController 的内部类，this$0 即 controller。
                if (taskViewController != null) {
                    Object controller = getField(taskViewController, "this$0");
                    if (controller != null) {
                        java.lang.reflect.Method m = findMethodSystemUi(controller.getClass(), "collapseStack");
                        if (m != null) { m.invoke(controller); collapse = m; }
                    }
                }
            } catch (Throwable ignored) { }
            if (collapse != null) {
                Log.i(TAG, "corner action: collapse stack invoked (controller)");
                return;
            }
            // 备用：expandedView.mStackView.mBubbleData.setExpanded(false)（官方收起展开窗的原子状态）。
            Object stackView = getField(expandedView, "mStackView");
            Object data = stackView != null ? getField(stackView, "mBubbleData") : null;
            java.lang.reflect.Method setExpanded = data != null
                    ? findMethod(data.getClass(), "setExpanded", boolean.class) : null;
            if (setExpanded != null) {
                setExpanded.invoke(data, false);
                Log.i(TAG, "corner action: collapse stack invoked (BubbleData.setExpanded(false))");
                return;
            }
            throw new IllegalStateException("no collapse entry available");
        } catch (Throwable t) {
            Log.w(TAG, "corner action: collapse stack failed: " + t.getMessage(), t);
        }
    }

    /** Official Bubble -> fullscreen handoff, preserving the current task/page. */
    private static void expandBubbleFullscreen(Object expandedView) {
        try {
            Object bubble = getField(expandedView, "mBubble");
            Object bubbleTaskView = bubble != null ? getField(bubble, "mBubbleTaskView") : null;
            Object taskView = bubbleTaskView != null ? getField(bubbleTaskView, "taskView") : null;
            Object controller = taskView != null ? getField(taskView, "mTaskViewController") : null;
            Object taskController = controller != null ? getField(taskView, "mTaskViewTaskController") : null;
            if (controller == null || taskController == null) {
                throw new IllegalStateException("Bubble task view controller unavailable");
            }
            Method move = findMethod(controller.getClass(),
                    "moveTaskViewToFullscreen", taskController.getClass());
            if (move == null) throw new NoSuchMethodException("moveTaskViewToFullscreen");
            move.invoke(controller, taskController);
            Log.i(TAG, "corner action: official fullscreen handoff submitted");
        } catch (Throwable t) {
            Log.w(TAG, "corner action: official fullscreen handoff failed: " + t.getMessage(), t);
        }
    }

    /* Retained as a diagnostic fallback only; the corner button does not use it. */
    private static volatile long sLastCornerLaunchAt;

    /** Collapse the embedded bubble first, then launch its exact user/profile entry. */
    private static void launchBubbleApp(Object expandedView) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastCornerLaunchAt < 800L) return;
            sLastCornerLaunchAt = now;

            Object bubble = getField(expandedView, "mBubble");
            Object packageValue = bubble != null ? getField(bubble, "mPackageName") : null;
            Object userValue = bubble != null ? getField(bubble, "mUser") : null;
            if (!(packageValue instanceof String) || ((String) packageValue).trim().isEmpty()
                    || !(userValue instanceof UserHandle)) {
                throw new IllegalStateException("Bubble package/user unavailable");
            }
            String packageName = (String) packageValue;
            UserHandle user = (UserHandle) userValue;
            LauncherApps launcher = ((View) expandedView).getContext()
                    .getSystemService(LauncherApps.class);
            if (launcher == null) throw new IllegalStateException("LauncherApps unavailable");
            List<LauncherActivityInfo> activities = launcher.getActivityList(packageName, user);
            ComponentName target = null;
            if (activities != null) {
                for (LauncherActivityInfo activity : activities) {
                    if (activity == null || !user.equals(activity.getUser())) continue;
                    ComponentName component = activity.getComponentName();
                    if (component != null && packageName.equals(component.getPackageName())) {
                        target = component;
                        break;
                    }
                }
            }
            if (target == null) throw new IllegalStateException("No launcher entry for Bubble user");

            // LauncherApps accepts the request while the app is still embedded, but
            // Android 17 leaves that task behind the bubble window. Collapse first;
            // capture all launch data above because the view may be rebuilt/cleared.
            collapseBubbleExpandedView(expandedView);
            final ComponentName launchTarget = target;
            final UserHandle launchUser = user;
            final LauncherApps launchService = launcher;
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    launchService.startMainActivity(launchTarget, launchUser, null, null);
                    Log.i(TAG, "corner action: launcher start submitted package="
                            + packageName + " user=" + userIdOf(launchUser));
                } catch (Throwable t) {
                    Log.w(TAG, "corner action: delayed launcher start failed: " + t.getMessage(), t);
                }
            }, 280L);
        } catch (Throwable t) {
            Log.w(TAG, "corner action: launcher start failed: " + t.getMessage(), t);
        }
    }

    private void hookLauncher(PackageLoadedParam param) {
        ClassLoader cl = mLauncherClassLoader;

        // Hook OverviewActionsView.onFinishInflate
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onFinishInflate")).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isActionBarEnabled(ctx))
                        injectBubbleButton(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "inject failed", t); }
                return ret;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onFinishInflate: " + t.getMessage()); }

        // Hook OverviewActionsView.onClick
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onClick", View.class)).intercept(chain -> {
                View v = (View) chain.getArg(0);
                if (v != null && bubbleButton != null && v.getId() == bubbleButton.getId()) {
                    onBubbleButtonClick((View) chain.getThisObject());
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onClick: " + t.getMessage()); }

        // Hook TaskMenuView.addMenuOptions
        try {
            hook(cl.loadClass("com.android.quickstep.views.TaskMenuView")
                    .getDeclaredMethod("addMenuOptions")).intercept(chain -> {
                chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isMenuEnabled(ctx))
                        addBubbleMenuOption(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
                return null;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook addMenuOptions: " + t.getMessage()); }
    }

    // ==================== 操作栏按钮 ====================

    @SuppressLint("DiscouragedApi")
    private void injectBubbleButton(Object actionsView, ClassLoader cl) {
        Context ctx = ((View) actionsView).getContext();
        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        ViewGroup actionsParent = (ViewGroup) actionsView;

        Button btn = createBubbleButton(ctx, res, pkg);
        int positionMode = ModuleSettings.getPositionMode(ctx);
        if (positionMode == 0) {
            addToActionButtons(actionsParent, btn, res, pkg);
        } else {
            ensureSecondRow(actionsParent, btn, res, pkg);
        }
    }

    /**
     * 更新第二行位置 — 读取当前设置并应用到 LayoutParams
     */
    private void updateSecondRowPosition(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;

        // Y 偏移
        int maxOffset = (int)(48 * density);
        lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

        // X margin — 需要等 View 宽度可用
        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
            applyXMargin(ctx, lp);
        } else if (sSecondRow != null) {
            sSecondRow.post(() -> {
                if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                    applyXMargin(ctx, p);
                    sSecondRow.setLayoutParams(p);
                }
            });
        }

        if (sSecondRow != null) {
            sSecondRow.setLayoutParams(lp);
            Log.i(TAG, "Position updated: X=" + posX + " Y=" + posY);
        }
    }

    private static void applyXMargin(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        ViewGroup parent = (ViewGroup) sSecondRow.getParent();
        if (parent == null) return;
        int parentWidth = parent.getWidth();
        int btnWidth = sSecondRow.getWidth();
        if (btnWidth <= 0 || parentWidth <= 0) return;

        // 纯百分比，50%居中时自动补偿图标偏移
        int iconOffset = (int)(13 * density); // 图标左置补偿
        float maxMargin = parentWidth - btnWidth;
        int marginStart = (int)((posX / 100f) * maxMargin) - iconOffset;
        lp.setMarginStart((int) Math.max(0, Math.min(marginStart, maxMargin)));
        Log.i(TAG, "applyXMargin: posX=" + posX + " marginStart=" + marginStart
                + " maxMargin=" + maxMargin + " parentW=" + parentWidth + " btnW=" + btnWidth);
    }

    /**
     * 模式0：跟随原按钮 — 直接加到 action_buttons 末尾
     */
    private void addToActionButtons(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        // 如果之前在第二行，先移除
        if (sSecondRow != null) {
            if (bubbleButton != null) ((ViewGroup) sSecondRow).removeView(bubbleButton);
            if (((ViewGroup) sSecondRow).getChildCount() == 0) {
                actionsParent.removeView(sSecondRow);
            }
            sSecondRow = null;
        }

        int abId = res.getIdentifier("action_buttons", "id", pkg);
        LinearLayout actionButtons = (LinearLayout) actionsParent.findViewById(abId);
        if (actionButtons == null) return;

        // 去重
        for (int i = 0; i < actionButtons.getChildCount(); i++) {
            View child = actionButtons.getChildAt(i);
            if (child.getTag() != null && "bubble_button".equals(child.getTag().toString())) {
                bubbleButton = child;
                return;
            }
        }

        // 添加到末尾
        ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(mlp);
        actionButtons.addView(btn);
        bubbleButton = btn;

        // 捕获 RecentsView（从 actionsParent 的 parent 链查找）
        if (recentsViewInstance == null) {
            try {
                Class<?> rvCls = mLauncherClassLoader.loadClass(
                        "com.android.quickstep.views.RecentsView");
                View cur = actionsParent;
                while (cur != null) {
                    if (rvCls.isInstance(cur)) {
                        recentsViewInstance = cur;
                        Log.i(TAG, "Captured RecentsView in follow mode: " + cur);
                        break;
                    }
                    cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Capture RecentsView failed: " + t.getMessage());
            }
        }

        Log.i(TAG, "Bubble button added to action_buttons (follow mode)");
    }

    private boolean isClearAllButton(View child) {
        try { return mLauncherClassLoader.loadClass("com.android.quickstep.views.ClearAllButton").isInstance(child); }
        catch (Throwable t) { return false; }
    }

    @SuppressLint("DiscouragedApi")
    private Button createBubbleButton(Context ctx, android.content.res.Resources res, String pkg) {
        int styleId = res.getIdentifier("OverviewActionButton.Blur", "style", pkg);
        if (styleId == 0) styleId = res.getIdentifier("OverviewActionButton", "style", pkg);
        Button btn = (styleId != 0) ? new Button(ctx, null, 0, styleId) : new Button(ctx);
        btn.setText("消息气泡");
        btn.setContentDescription("消息气泡");
        btn.setId(View.generateViewId());
        btn.setTag("bubble_button");

        // 图标
        int iconId = res.getIdentifier("ic_bubble_button", "drawable", pkg);
        if (iconId == 0) iconId = res.getIdentifier("ic_bubble_bar", "drawable", pkg);
        if (iconId != 0) {
            android.graphics.drawable.Drawable icon = res.getDrawable(iconId, null);
            if (icon != null) btn.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        }

        btn.setOnClickListener(v -> onBubbleButtonClick((View) btn.getParent().getParent()));
        return btn;
    }

    @SuppressLint("DiscouragedApi")
    private void ensureSecondRow(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        Context ctx = actionsParent.getContext();

        if (sSecondRow != null && sSecondRow.getParent() == actionsParent) {
            for (int i = 0; i < ((ViewGroup) sSecondRow).getChildCount(); i++) {
                if (((ViewGroup) sSecondRow).getChildAt(i).getTag() != null
                        && "bubble_button".equals(((ViewGroup) sSecondRow).getChildAt(i).getTag().toString())) {
                    bubbleButton = ((ViewGroup) sSecondRow).getChildAt(i);
                    return;
                }
            }
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
            if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
            btn.setLayoutParams(mlp);
            ((ViewGroup) sSecondRow).addView(btn);
            bubbleButton = btn;
            return;
        }

        LinearLayout newSecondRow = new LinearLayout(ctx);
        newSecondRow.setTag("bubble_second_row");
        newSecondRow.setOrientation(LinearLayout.HORIZONTAL);

        // 使用 X/Y 坐标定位
        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);

        // X 轴：用 marginStart 连续定位（不再用离散 gravity）
        // posX 0%=左对齐, 50%=居中, 100%=右对齐
        float density = ctx.getResources().getDisplayMetrics().density;

        ViewGroup.MarginLayoutParams btnMlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) btnMlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(btnMlp);
        newSecondRow.addView(btn);
        bubbleButton = btn;

        // 定位：用 layout_gravity=START|BOTTOM + marginStart 实现所有位置
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = android.view.Gravity.START | android.view.Gravity.BOTTOM;

        // Y 偏移：posY 0% = 紧贴底部，100% = 向上偏移 48dp
        int maxOffset = (int)(48 * density);
        int bottomOffset = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);
        rowLp.bottomMargin = bottomOffset;

        int insertIndex = 0;
        int abId = res.getIdentifier("action_buttons", "id", pkg);
        View abv = actionsParent.findViewById(abId);
        if (abv != null) insertIndex = actionsParent.indexOfChild(abv) + 1;

        actionsParent.addView(newSecondRow, insertIndex, rowLp);
        sSecondRow = newSecondRow;

        // post 里应用 X margin + 同步 alpha
        newSecondRow.post(() -> {
            // X margin + alpha 同步 — 用 OnPreDrawListener 保证每帧都正确
            View abv2 = actionsParent.findViewById(
                    res.getIdentifier("action_buttons", "id", pkg));
            actionsParent.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        private int lastX = Integer.MIN_VALUE;
                        private int lastY = Integer.MIN_VALUE;

                        @Override public boolean onPreDraw() {
                            if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                                int posX = ModuleSettings.getPosX(ctx);
                                int posY = ModuleSettings.getPosY(ctx);
                                if (posX != lastX || posY != lastY) {
                                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                                    int maxOffset = (int)(48 * ctx.getResources().getDisplayMetrics().density);
                                    p.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);
                                    applyXMargin(ctx, p);
                                    sSecondRow.setLayoutParams(p);
                                    lastX = posX;
                                    lastY = posY;
                                    Log.i(TAG, "live position applied: X=" + posX + " Y=" + posY);
                                }
                            }
                            // alpha 同步
                            if (sSecondRow != null && abv2 != null)
                                sSecondRow.setAlpha(abv2.getAlpha());
                            return true;
                        }
                    });
        });
    }

    private void updateBubbleVisibility(Object av) {}

    // ==================== 菜单项注入 ====================

    @SuppressLint("DiscouragedApi")
    private void addBubbleMenuOption(Object menuView, ClassLoader cl) {
        try {
            Context ctx = ((View) menuView).getContext();
            android.content.res.Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();

            ViewGroup optionLayout = (ViewGroup) findMethod(menuView.getClass(), "getOptionLayout").invoke(menuView);
            Object taskContainer = findMethod(menuView.getClass(), "getTaskContainer").invoke(menuView);
            if (optionLayout == null || taskContainer == null) return;

            // 捕获 RecentsView
            try {
                Object tv = findMethod(menuView.getClass(), "getTaskView").invoke(menuView);
                if (tv != null) {
                    recentsViewInstance = findMethod(tv.getClass(), "getRecentsView").invoke(tv);
                    Log.i(TAG, "Captured RecentsView: " + recentsViewInstance);
                }
            } catch (Throwable ignored) {}

            ViewGroup menuItem = (ViewGroup) android.view.LayoutInflater.from(ctx)
                    .inflate(res.getIdentifier("task_view_menu_option", "layout", pkg), optionLayout, false);

            int bgId = res.getIdentifier("app_chip_menu_item_bg", "drawable", pkg);
            if (bgId != 0) menuItem.setBackground(res.getDrawable(bgId, ctx.getTheme()));

            int iconId = res.getIdentifier("ic_bubble_button", "drawable", pkg);
            if (iconId == 0) iconId = res.getIdentifier("ic_bubble_bar", "drawable", pkg);
            View iconView = menuItem.findViewById(res.getIdentifier("icon", "id", pkg));
            if (iconView != null && iconId != 0) {
                android.graphics.drawable.Drawable icon = res.getDrawable(iconId, ctx.getTheme());
                int tintId = res.getIdentifier("materialColorOnSurface", "color", pkg);
                if (tintId != 0) icon.setTint(res.getColor(tintId, ctx.getTheme()));
                iconView.setBackground(icon);
            }

            View tv = menuItem.findViewById(res.getIdentifier("text", "id", pkg));
            if (tv instanceof android.widget.TextView)
                ((android.widget.TextView) tv).setText("消息气泡");

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) menuItem.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            menuItem.setLayoutParams(lp);

            menuItem.setOnClickListener(v -> {
                try {
                    Object task = invoke(taskContainer, "getTask");
                    if (task == null) return;
                    Object key = getField(task, "key");
                    Intent intent = (Intent) getField(key, "baseIntent");
                    int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
                    if (intent != null) {
                        findMethod(menuView.getClass(), "close", boolean.class).invoke(menuView, true);
                        bubbleCurrentTask(ctx, intent, task, userId);
                        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> dismissOverview(ctx), 200);
                    }
                } catch (Throwable t) { Log.e(TAG, "menu click: " + t.getMessage()); }
            });

            optionLayout.addView(menuItem);
        } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
    }

    // ==================== 气泡触发 ====================

    private void onBubbleButtonClick(View actionsView) {
        Context ctx = actionsView.getContext();
        Object rv = recentsViewInstance;
        if (rv == null) rv = findRecentsViewFromHierarchy(actionsView);
        if (rv == null) { Log.w(TAG, "RecentsView not found"); return; }

        try {
            Object tv = findMethod(rv.getClass(), "getCurrentPageTaskView").invoke(rv);
            if (tv == null) return;
            List<?> tc = (List<?>) findMethod(tv.getClass(), "getTaskContainers").invoke(tv);
            if (tc == null || tc.isEmpty()) return;

            Object task = findMethod(tc.get(0).getClass(), "getTask").invoke(tc.get(0));
            Object key = getField(task, "key");
            Intent intent = (Intent) getField(key, "baseIntent");
            int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
            if (intent == null) return;

            bubbleCurrentTask(ctx, intent, task, userId);
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> dismissOverview(ctx), 200);
        } catch (Throwable t) { Log.e(TAG, "onBubbleButtonClick: " + t.getMessage()); }
    }

    private boolean bubbleCurrentTask(Context ctx, Intent taskIntent, Object task, int userId) {
        try {
            Class<?> proxyCls = mLauncherClassLoader.loadClass("com.android.quickstep.SystemUiProxy");
            Object ds = proxyCls.getField("INSTANCE").get(null);
            Object proxy = ds.getClass().getMethod("get", Context.class).invoke(ds, ctx.getApplicationContext());
            if (proxy == null) return false;

            Intent bIntent = new Intent(taskIntent);
            // Always ensure package is set (BubbleData.getOrCreateBubble needs intent.getPackage())
            if (bIntent.getPackage() == null && bIntent.getComponent() != null)
                bIntent.setPackage(bIntent.getComponent().getPackageName());

            // Set the exact top component for precise bubble targeting (for deep-linked / secondary activities)
            try {
                Object topComponent = invoke(task, "getTopComponent");
                String className = (String) invoke(topComponent, "getClassName");
                if (className != null && bIntent.getPackage() != null) {
                    bIntent.setComponent(new ComponentName(bIntent.getPackage(), className));
                }
            } catch (Throwable ignored) {}

            Object userHandle = mLauncherClassLoader.loadClass("android.os.UserHandle")
                    .getMethod("of", int.class).invoke(null, userId);

            Class<?> epCls = mLauncherClassLoader.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            Object ep = null;
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null))
                if ("NOTIFICATION".equals(e.toString())) { ep = e; break; }

            for (Method m : proxy.getClass().getMethods())
                if (m.getName().equals("showAppBubble")) {
                    m.invoke(proxy, bIntent, userHandle, ep, null);
                    Log.i(TAG, "showAppBubble OK");
                    return true;
                }
        } catch (Throwable t) { Log.e(TAG, "bubbleCurrentTask: " + t.getMessage()); }
        return false;
    }

    private Object findRecentsViewFromHierarchy(View view) {
        try {
            Class<?> rvCls = mLauncherClassLoader.loadClass("com.android.quickstep.views.RecentsView");
            // 先从 parent 链查找
            View cur = view;
            while (cur != null) {
                if (rvCls.isInstance(cur)) { recentsViewInstance = cur; return cur; }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
            // parent 链找不到，从 rootView 递归搜索
            View root = view.getRootView();
            if (root != null) {
                cur = findInTree(root, rvCls);
                if (cur != null) { recentsViewInstance = cur; return cur; }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private View findInTree(View view, Class<?> cls) {
        if (cls.isInstance(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View f = findInTree(vg.getChildAt(i), cls);
                if (f != null) return f;
            }
        }
        return null;
    }

    private void dismissOverview(Context ctx) {
        try {
            if (recentsViewInstance != null) {
                Object sm = findMethod(recentsViewInstance.getClass(), "getStateManager").invoke(recentsViewInstance);
                if (sm != null) {
                    findMethod(sm.getClass(), "moveToRestState").invoke(sm);
                    Log.i(TAG, "dismissed via moveToRestState");
                    return;
                }
            }
            Runtime.getRuntime().exec(new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"});
            Log.i(TAG, "dismissed via am start HOME");
        } catch (Throwable t) { Log.e(TAG, "dismiss: " + t.getMessage()); }
    }

    // ==================== 工具方法 ====================

    private static Object getFieldSystemUi(Object obj, String name) {
        try { java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
    }

    private static Object invokeSystemUi(Object obj, String method) {
        try { Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }

    private static java.lang.reflect.Field findFieldSystemUi(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }

    private static Method findMethodSystemUi(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    private static void showToast(Context ctx, String msg) {
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * 静态方法：从模块 Activity 调用，重新应用位置设置
     */
    public static void applyPositionFromSettings(Context ctx) {
        if (sSecondRow == null) {
            Log.i(TAG, "applyPositionFromSettings: sSecondRow is null, settings saved for next load");
            return;
        }

        try {
            Log.i(TAG, "applyPositionFromSettings: X=" + ModuleSettings.getPosX(ctx)
                    + " Y=" + ModuleSettings.getPosY(ctx));

            if (sSecondRow.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                float density = ctx.getResources().getDisplayMetrics().density;

                // Y 偏移
                int posY = ModuleSettings.getPosY(ctx);
                int maxOffset = (int)(48 * density);
                lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

                // X margin — 等布局完成后应用
                if (sSecondRow.getWidth() > 0) {
                    applyXMargin(ctx, lp);
                } else {
                    sSecondRow.post(() -> {
                        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                            applyXMargin(ctx, p);
                            sSecondRow.requestLayout();
                            Log.i(TAG, "X margin applied via post");
                        }
                    });
                }

                sSecondRow.setLayoutParams(lp);
                sSecondRow.requestLayout();
                Log.i(TAG, "Position applied: X=" + ModuleSettings.getPosX(ctx) + " Y=" + posY);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyPositionFromSettings failed: " + t.getMessage());
        }
    }

    private static View findViewByTag(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByTag(vg.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }
}
