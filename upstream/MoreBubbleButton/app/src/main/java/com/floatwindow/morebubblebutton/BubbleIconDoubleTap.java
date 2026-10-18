package com.floatwindow.morebubblebutton;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** Verified against the target SystemUI DEX, not an inherited ViewGroup hook. */
final class BubbleIconDoubleTap {
    private static final String TAG = "MoreBubbleModule";
    private final MoreBubbleHookModule module;
    // Values must not strongly reference their weak keys, listeners, bubbles or views.
    private final Map<Object, PendingClick> pending = new WeakHashMap<>();
    private final Map<Object, Touch> touches = new WeakHashMap<>();
    // One-shot marker used by the SystemUI dismiss hook to avoid tearing down
    // the TaskView before the official fullscreen handoff has rendered.
    private static final Map<String, Long> officialConversions = new HashMap<>();
    private static final long CLEANUP_GRACE_MS = 220L;
    private Method clickMethod;
    private Class<?> clickClass;
    private Class<?> touchClass;
    private Class<?> bubbleClass;
    private boolean transitionObserverInstalled;
    private final ThreadLocal<Boolean> conversionAttempt = new ThreadLocal<>();
    private final ThreadLocal<Integer> transitionTaskId = new ThreadLocal<>();
    private final ThreadLocal<Boolean> transitionEntered = new ThreadLocal<>();

    BubbleIconDoubleTap(MoreBubbleHookModule module) { this.module = module; }

    void install(ClassLoader cl) {
        try {
            clickClass = cl.loadClass("com.android.wm.shell.bubbles.BubbleStackView$6");
            touchClass = cl.loadClass("com.android.wm.shell.bubbles.BubbleStackView$7");
            bubbleClass = cl.loadClass("com.android.wm.shell.bubbles.Bubble");
            clickMethod = method(clickClass, "onClick", View.class);
            installTransitionObserver(cl);
            // onTouch is declared on RelativeTouchListener: scope its receiver precisely.
            Method touchMethod = method(touchClass, "onTouch", View.class, MotionEvent.class);
            module.hook(touchMethod).intercept(chain -> {
                Object listener = chain.getThisObject();
                View icon = (View) chain.getArg(0);
                MotionEvent event = (MotionEvent) chain.getArg(1);
                try {
                    Object stack = scopedStack(listener, touchClass, "mBubbleTouchListener");
                    if (stack != null && icon != null && event != null) {
                        observeTouch(stack, listener, icon, event);
                    }
                } catch (Throwable t) { Log.w(TAG, "icon touch observation failed", t); }
                // Never replace/consume the official touch listener or change its result.
                try { return chain.proceed(); }
                finally {
                    if (event != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                        touches.remove(listener);
                    }
                }
            });
            module.hook(clickMethod).intercept(chain -> {
                try {
                    Object listener = chain.getThisObject();
                    View icon = (View) chain.getArg(0);
                    Object stack = scopedStack(listener, clickClass, "mBubbleClickListener");
                    if (stack != null && icon != null && handleClick(stack, listener, icon)) {
                        return null;
                    }
                } catch (Throwable t) { Log.w(TAG, "icon click interception failed", t); }
                return chain.proceed();
            });
            Log.i(TAG, "icon double-tap hooks installed: $6.onClick(View), $7 classId=0 touch");
        } catch (Throwable t) { Log.e(TAG, "icon double-tap unavailable; official clicks retained", t); }
    }

    private Object scopedStack(Object listener, Class<?> expected, String field) throws Exception {
        if (!expected.isInstance(listener) || !Integer.valueOf(0).equals(read(listener, "$r8$classId"))) {
            return null;
        }
        Object stack = read(listener, "this$0");
        return stack != null && read(stack, field) == listener ? stack : null;
    }

    private Object clickedBubble(Object stack, View icon) throws Exception {
        Object data = read(stack, "mBubbleData");
        for (Object bubble : (List<?>) read(data, "mBubbles")) {
            if (bubbleClass.isInstance(bubble) && read(bubble, "mIconView") == icon) return bubble;
        }
        return null; // Overflow and unrelated views are not application bubble icons.
    }

    static void markOfficialConversion(String key) {
        if (key == null) return;
        synchronized (officialConversions) {
            officialConversions.put(key, SystemClock.uptimeMillis());
        }
    }

    static boolean consumeRecentOfficialConversion(String key) {
        if (key == null) return false;
        synchronized (officialConversions) {
            Long at = officialConversions.get(key);
            if (at == null) return false;
            long age = SystemClock.uptimeMillis() - at;
            if (age < 0 || age > CLEANUP_GRACE_MS) {
                officialConversions.remove(key);
                return false;
            }
            officialConversions.remove(key);
            return true;
        }
    }

    private boolean enabled(View icon) {
        return ModuleSettings.isDoubleTapFullscreenEnabled(icon.getContext());
    }

    private boolean handleClick(Object stack, Object listener, View icon) throws Exception {
        PendingClick old = pending.get(stack);
        if (!enabled(icon)) {
            if (old != null) old.replay(); // Drain the first single; this click proceeds normally.
            return false;
        }
        Object bubble = clickedBubble(stack, icon);
        Touch touch = touches.get(read(stack, "mBubbleTouchListener"));
        if (bubble == null || !Boolean.TRUE.equals(method(bubble.getClass(), "isApp").invoke(bubble))
                || !Boolean.TRUE.equals(read(stack, "mIsExpanded"))
                || Boolean.TRUE.equals(read(stack, "mIsExpansionAnimating"))
                || Boolean.TRUE.equals(read(stack, "mIsBubbleSwitchAnimating"))
                || Boolean.TRUE.equals(read(stack, "mShowedUserEducationInTouchListenerActive"))
                || !icon.isAttachedToWindow() || icon.getHandler() == null
                || Looper.myLooper() != icon.getHandler().getLooper()
                || (touch != null && (!touch.valid || touch.icon.get() != icon))) {
            if (old != null) old.replay();
            return false;
        }
        long now = SystemClock.uptimeMillis();
        long secondTime = touch != null ? touch.downTime : now;
        if (old != null && old.icon.get() == icon && old.bubble.get() == bubble
                && secondTime >= old.when
                && secondTime - old.when <= ViewConfiguration.getDoubleTapTimeout()) {
            old.clear();
            Log.i(TAG, "icon double tap recognized");
            // Prefer the SystemUI-owned conversion; launcher startup is compatibility fallback only.
            if (!convertToFullscreen(stack, bubble, icon)) {
                if (!launchApp(stack, bubble, icon)) {
                    // Neither conversion nor launcher request was possible: retain one official single click.
                    replayOriginal(listener, icon);
                }
            }
            return true;
        }
        if (old != null) old.replay();
        // Replaying another icon can change/remove this bubble; never queue a stale target.
        if (clickedBubble(stack, icon) != bubble || !icon.isAttachedToWindow()) return false;
        PendingClick next = new PendingClick(stack, listener, icon, bubble, now);
        pending.put(stack, next);
        icon.addOnAttachStateChangeListener(next);
        if (!next.handler.postDelayed(next, ViewConfiguration.getDoubleTapTimeout())) {
            next.clear();
            return false;
        }
        Log.i(TAG, "icon single click deferred by system double-tap timeout");
        return true;
    }

    private void observeTouch(Object stack, Object listener, View icon, MotionEvent event) throws Exception {
        PendingClick old = pending.get(stack);
        if (!enabled(icon)) {
            if (old != null) old.replay();
            touches.remove(listener);
            return;
        }
        if (clickedBubble(stack, icon) == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            Touch touch = new Touch(icon, event);
            touches.put(listener, touch);
            if (old != null && old.icon.get() == icon && touch.downTime - old.when >= 0
                    && touch.downTime - old.when <= ViewConfiguration.getDoubleTapTimeout()) {
                // Wait for the second UP without letting the first click collapse the task.
                // Use our own Handler: RelativeTouchListener removes ALL messages on View's Handler.
                old.handler.removeCallbacks(old);
                old.handler.postDelayed(old.cancelLongPress, ViewConfiguration.getLongPressTimeout());
            }
            return;
        }
        Touch touch = touches.get(listener);
        if (touch == null || touch.icon.get() != icon) return;
        int slop = ViewConfiguration.get(icon.getContext()).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || Math.hypot(event.getRawX() - touch.x, event.getRawY() - touch.y) > slop
                || event.getEventTime() - touch.downTime >= ViewConfiguration.getLongPressTimeout()
                || Boolean.TRUE.equals(read(listener, "performedLongClick"))
                || Boolean.TRUE.equals(read(listener, "movedEnough"))) {
            touch.valid = false;
            if (old != null) old.clear();
        }
    }

    private static final class Touch {
        final WeakReference<View> icon;
        final long downTime;
        final float x, y;
        boolean valid = true;
        Touch(View view, MotionEvent event) {
            icon = new WeakReference<>(view);
            downTime = event.getEventTime();
            x = event.getRawX(); y = event.getRawY();
        }
    }

    private void replayOriginal(Object listener, View icon) {
        try {
            // API 101 inspected with javap: ORIGIN bypasses interception, no saved Chain.
            module.getInvoker(clickMethod).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(listener, icon);
            Log.i(TAG, "icon official single click replayed once");
        } catch (Throwable t) { Log.e(TAG, "icon original single click replay failed", t); }
    }

    private final class PendingClick implements Runnable, View.OnAttachStateChangeListener {
        final WeakReference<Object> stack, listener, bubble;
        final WeakReference<View> icon;
        final Handler handler;
        final long when;
        boolean cleared;
        final Runnable cancelLongPress = this::cancelHeldClick;
        private void cancelHeldClick() {
            Object owner = stack.get();
            if (owner != null) {
                try {
                    Touch touch = touches.get(read(owner, "mBubbleTouchListener"));
                    if (touch != null) touch.valid = false;
                } catch (Exception t) { Log.w(TAG, "icon long-press cleanup failed", t); }
            }
            clear();
        }
        PendingClick(Object owner, Object clickListener, View view, Object target, long time) {
            stack = new WeakReference<>(owner); listener = new WeakReference<>(clickListener);
            icon = new WeakReference<>(view); bubble = new WeakReference<>(target); when = time;
            handler = new Handler(view.getHandler().getLooper());
        }
        void clear() {
            if (cleared) return;
            cleared = true;
            handler.removeCallbacks(this);
            handler.removeCallbacks(cancelLongPress);
            Object owner = stack.get();
            if (owner != null && pending.get(owner) == this) pending.remove(owner);
            View view = icon.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
        }
        void replay() {
            if (cleared) return;
            clear();
            Object owner = stack.get(), receiver = listener.get(), target = bubble.get();
            View view = icon.get();
            try {
                if (owner != null && receiver != null && target != null && view != null
                        && view.isAttachedToWindow() && clickedBubble(owner, view) == target
                        && read(owner, "mBubbleClickListener") == receiver) {
                    replayOriginal(receiver, view);
                }
            } catch (Throwable t) { Log.w(TAG, "icon pending single invalidated", t); }
        }
        @Override public void run() { replay(); }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { clear(); }
    }

    /** Hooks the transition entry point only for diagnostics; conversion itself remains SystemUI-owned. */
    private void installTransitionObserver(ClassLoader cl) {
        try {
            // Android 17 stores BubbleTransitions in the transitions subpackage.
            Class<?> transitions = cl.loadClass("com.android.wm.shell.bubbles.transitions.BubbleTransitions");
            Method start = null;
            for (Method candidate : transitions.getDeclaredMethods()) {
                if ("startConvertFromBubble".equals(candidate.getName()) && candidate.getParameterTypes().length == 2) {
                    start = candidate; break;
                }
            }
            if (start == null) throw new NoSuchMethodException("BubbleTransitions.startConvertFromBubble");
            start.setAccessible(true);
            module.hook(start).intercept(chain -> {
                Integer taskId = transitionTaskId.get();
                if (Boolean.TRUE.equals(conversionAttempt.get()) && taskId != null) {
                    transitionEntered.set(Boolean.TRUE);
                    Log.i(TAG, "icon fullscreen official transition entered taskId=" + taskId);
                }
                return chain.proceed();
            });
            transitionObserverInstalled = true;
        } catch (Throwable t) {
            // Observer is optional and must never disable the official conversion path.
            Log.w(TAG, "icon fullscreen transition observer unavailable", t);
        }
    }

    private boolean convertToFullscreen(Object stack, Object bubble, View icon) {
        boolean attempted = false;
        int taskId = -1;
        try {
            if (!enabled(icon) || clickedBubble(stack, icon) != bubble || !icon.isAttachedToWindow())
                throw new IllegalStateException("clicked icon target invalidated");
            Object bubbleTaskView = read(bubble, "mBubbleTaskView");
            Object taskView = read(bubbleTaskView, "taskView");
            Object controller = read(taskView, "mTaskViewController");
            Object taskController = read(taskView, "mTaskViewTaskController");
            Object info = read(taskController, "mTaskInfo");
            if (info == null) throw new IllegalStateException("Bubble task info unavailable");
            taskId = ((Number) read(info, "taskId")).intValue();
            if (taskId < 0) throw new IllegalStateException("invalid Bubble task id");
            attempted = true;
            // Diagnostic only: official getter follows live task-view listener IDs; never inspect raw mTaskId.
            try {
                Object data = read(stack, "mBubbleData");
                Method getter = method(data.getClass(), "getBubbleInStackWithTaskId", int.class);
                Object match = getter.invoke(data, taskId);
                Log.i(TAG, "icon fullscreen bubble lookup taskId=" + taskId + " matched=" + (match != null));
            } catch (Throwable diagnostic) {
                Log.w(TAG, "icon fullscreen bubble lookup diagnostic unavailable taskId=" + taskId, diagnostic);
            }
            transitionTaskId.set(taskId);
            transitionEntered.set(Boolean.FALSE);
            conversionAttempt.set(Boolean.TRUE);
            Log.i(TAG, "icon fullscreen official conversion attempted taskId=" + taskId);
            method(controller.getClass(), "moveTaskViewToFullscreen", taskController.getClass()).invoke(controller, taskController);
            Log.i(TAG, "icon fullscreen official conversion invocation returned taskId=" + taskId
                    + "; visual completion NOT verified");
            // moveTaskViewToFullscreen() only submits an asynchronous Shell transition.  The
            // observer is diagnostic and must never turn a successfully submitted official
            // conversion into a second LauncherApps startup path.
            try {
                Object key = read(bubble, "mKey");
                if (key instanceof String) markOfficialConversion((String) key);
            } catch (Throwable ignored) { }
            Log.i(TAG, "icon fullscreen official conversion submitted taskId=" + taskId
                    + "; launcher fallback disabled after submission");
            return true;
        } catch (Throwable t) {
            if (attempted) Log.w(TAG, "icon fullscreen official conversion failed taskId=" + taskId
                    + "; launcher fallback", t);
            else Log.w(TAG, "icon fullscreen official conversion setup unavailable; launcher fallback", t);
            return false;
        } finally {
            conversionAttempt.remove();
            transitionTaskId.remove();
        }
    }

    private boolean launchApp(Object stack, Object bubble, View icon) {
        try {
            if (!enabled(icon) || clickedBubble(stack, icon) != bubble || !icon.isAttachedToWindow()) {
                throw new IllegalStateException("clicked icon target invalidated");
            }
            // Verified in target SystemUI DEX (81i4aezl): app constructor assigns
            // Intent.getPackage() -> mPackageName and its UserHandle argument -> mUser.
            // Never derive user from process/current user, taskId, key or PendingIntent.
            Object packageValue = read(bubble, "mPackageName");
            Object userValue = read(bubble, "mUser");
            if (!(packageValue instanceof String) || ((String) packageValue).trim().isEmpty()
                    || !(userValue instanceof UserHandle)) {
                throw new IllegalStateException("clicked Bubble package/user unavailable");
            }
            String packageName = (String) packageValue;
            UserHandle user = (UserHandle) userValue;
            int userId = ((Number) method(UserHandle.class, "getIdentifier").invoke(user)).intValue();
            if (userId < 0) throw new IllegalStateException("special/unknown Bubble user rejected");
            Log.i(TAG, "icon launch target resolved package=" + packageName + " user=" + userId);
            Context context = icon.getContext();
            LauncherApps launcher = context.getSystemService(LauncherApps.class);
            if (launcher == null) throw new IllegalStateException("LauncherApps unavailable");
            // getActivityList resolves ACTION_MAIN/CATEGORY_LAUNCHER for this exact user,
            // including work profiles. No current-user fallback on permission/profile errors.
            List<LauncherActivityInfo> activities = launcher.getActivityList(packageName, user);
            ComponentName target = null;
            if (activities != null) {
                for (LauncherActivityInfo activity : activities) {
                    if (activity == null || !user.equals(activity.getUser())) continue;
                    ComponentName component = activity.getComponentName();
                    if (component != null && packageName.equals(component.getPackageName())) {
                        target = component;
                        break; // Use the first ordinary launcher entry, not the Bubble's deep link.
                    }
                }
            }
            if (target == null) throw new IllegalStateException("no MAIN/LAUNCHER activity for Bubble user");
            if (!enabled(icon) || clickedBubble(stack, icon) != bubble || !icon.isAttachedToWindow()
                    || !packageName.equals(read(bubble, "mPackageName"))
                    || !user.equals(read(bubble, "mUser"))) {
                throw new IllegalStateException("Bubble target changed during launcher resolution");
            }
            launcher.startMainActivity(target, user, null, null);
            Log.i(TAG, "icon launch request submitted package=" + packageName + " user=" + userId
                    + " component=" + target.flattenToShortString() + "; visual completion NOT verified");
            // Do not collapse/dismiss/remove the Bubble: no unverified stack side effects.
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "icon launch resolution/request failed; original single-click fallback"
                    + "; visual completion NOT verified", t);
            return false;
        }
    }

    // Missing field/method is an explicit compatibility error, distinct from a present null.
    private static Object read(Object object, String name) throws Exception {
        if (object == null) throw new IllegalStateException("null owner for field " + name);
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Class<?> owner = type;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name, args);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
}
