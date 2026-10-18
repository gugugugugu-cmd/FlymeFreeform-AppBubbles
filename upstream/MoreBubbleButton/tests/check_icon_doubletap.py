"""Local: source invariants only. --java: CI-only behavioral tests of actual helper with Android stubs."""
import pathlib, sys, tempfile, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/floatwindow/morebubblebutton'
s = (SRC / 'BubbleIconDoubleTap.java').read_text()
main = (SRC / 'MoreBubbleHookModule.java').read_text()
assert 'dispatchTouchEvent' not in main and 'GestureDetector' not in main
assert 'new BubbleIconDoubleTap(this).install(cl)' in main
for required in ['read(stack, field) == listener', '"$r8$classId"', 'read(bubble, "mIconView") == icon',
                 'ViewConfiguration.getDoubleTapTimeout()', 'Invoker.Type.ORIGIN',
                 'new Handler(view.getHandler().getLooper())', 'launcher.getActivityList(packageName, user)', 'launcher.startMainActivity(target, user, null, null)',
                 'onViewDetachedFromWindow', 'icon double tap recognized', 'icon launch target resolved', 'icon launch request submitted', 'mBubbleTaskView', 'moveTaskViewToFullscreen', 'startConvertFromBubble', 'icon fullscreen official conversion attempted', 'icon fullscreen official conversion submitted', 'com.android.wm.shell.bubbles.transitions.BubbleTransitions', 'throw new NoSuchFieldException']:
    assert required in s, required
assert 'com.android.wm.shell.bubbles.BubbleTransitions' not in s
assert 'transitionObserverInstalled && !Boolean.TRUE.equals(transitionEntered.get())' not in s
for forbidden in ['read(bubble, "mTaskId")', 'PendingIntent']:
    assert forbidden not in s.replace('// Never derive user from process/current user, taskId, key or PendingIntent.', ''), forbidden
assert 'setOnTouchListener' not in s and 'dispatchTouchEvent' not in s
assert 'Chain ' not in s and 'chain' not in s[s.index('private final class PendingClick'):s.index('private void installTransitionObserver')]
assert 'KEY_BUBBLE_DOUBLE_TAP_FULLSCREEN, true' in (SRC / 'ModuleSettings.java').read_text()
assert 'BubbleOverflow' in main and 'getIconView' in main
assert 'hide plus' in main
print('PASS: source invariants (not device/runtime testing)')
if '--java' not in sys.argv:
    sys.exit(0)
assert __import__('os').environ.get('GITHUB_ACTIONS') == 'true', 'Java compilation permitted only on GitHub Actions'
files = {
'android/os/Looper.java': '''package android.os; public class Looper { static final Looper L=new Looper(); public static Looper myLooper(){return L;} }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long now; public static long uptimeMillis(){return now;} }''',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 public static class Job {Handler h; Runnable r; long t; Job(Handler h,Runnable r,long t){this.h=h;this.r=r;this.t=t;}}
 public static List<Job> jobs=new ArrayList<>(); Looper l; public Handler(Looper l){this.l=l;}
 public Looper getLooper(){return l;} public boolean postDelayed(Runnable r,long delay){jobs.add(new Job(this,r,SystemClock.now+delay));return true;}
 public void removeCallbacks(Runnable r){jobs.removeIf(j->j.h==this&&j.r==r);}
 public void removeCallbacksAndMessages(Object token){jobs.removeIf(j->j.h==this);}
 public static void advance(long n){SystemClock.now=n; boolean again; do {again=false;for(Job j:new ArrayList<>(jobs))if(j.t<=n&&jobs.remove(j)){j.r.run();again=true;}}while(again);}
}''',
'android/content/Context.java': '''package android.content; import android.content.pm.*; public class Context {public LauncherApps launcher=new LauncherApps();public <T> T getSystemService(Class<T> cls){return cls.cast(launcher);}}''',
'android/os/UserHandle.java': '''package android.os; public class UserHandle {public final int id; public UserHandle(int i){id=i;} public int getIdentifier(){return id;} public boolean equals(Object o){return o instanceof UserHandle && ((UserHandle)o).id==id;} public int hashCode(){return id;}}''',
'android/content/ComponentName.java': '''package android.content; public class ComponentName {String pkg,cls;public ComponentName(String p,String c){pkg=p;cls=c;}public String getPackageName(){return pkg;}public String flattenToShortString(){return pkg+"/"+cls;}}''',
'android/content/pm/LauncherActivityInfo.java': '''package android.content.pm;import android.os.*;import android.content.*;public class LauncherActivityInfo {ComponentName c;UserHandle u;public LauncherActivityInfo(ComponentName c,UserHandle u){this.c=c;this.u=u;}public ComponentName getComponentName(){return c;}public UserHandle getUser(){return u;}}''',
'android/content/pm/LauncherApps.java': '''package android.content.pm;import android.os.*;import android.content.*;import java.util.*;public class LauncherApps {public int launches;public boolean missing,fail,deny,wrongPackage,wrongUser;public UserHandle requestedUser,launchedUser;public String requestedPackage;public ComponentName launchedComponent;public List<LauncherActivityInfo> getActivityList(String p,UserHandle u){requestedPackage=p;requestedUser=u;if(deny)throw new SecurityException("profile unavailable");if(missing)return List.of();return List.of(new LauncherActivityInfo(new ComponentName(wrongPackage?"wrong.package":p,"Main"),wrongUser?new UserHandle(99):u));}public void startMainActivity(ComponentName c,UserHandle u,Object bounds,Object options){if(fail)throw new SecurityException("launch denied");launches++;launchedComponent=c;launchedUser=u;}}''',
'android/view/View.java': '''package android.view; import android.os.*; import android.content.*; import java.util.*; public class View {
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public Handler h=new Handler(Looper.myLooper()); public boolean attached=true; List<OnAttachStateChangeListener> ls=new ArrayList<>();
 public Context context=new Context(); public Context getContext(){return context;} public Handler getHandler(){return h;} public boolean isAttachedToWindow(){return attached;}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){ls.add(l);} public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){ls.remove(l);}
 public void detach(){attached=false;for(var l:new ArrayList<>(ls))l.onViewDetachedFromWindow(this);}
}''',
'android/view/ViewConfiguration.java': '''package android.view; import android.content.*; public class ViewConfiguration {
 public static int getDoubleTapTimeout(){return 300;} public static int getLongPressTimeout(){return 500;}
 public static ViewConfiguration get(Context c){return new ViewConfiguration();} public int getScaledTouchSlop(){return 8;}
}''',
'android/view/MotionEvent.java': '''package android.view; public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 public int a;public long t; public float x,y; public int pointers=1;
 public MotionEvent(int a,long t,float x,float y){this.a=a;this.t=t;this.x=x;this.y=y;}
 public int getActionMasked(){return a;} public long getEventTime(){return t;} public float getRawX(){return x;} public float getRawY(){return y;}public int getPointerCount(){return pointers;}
}''',
'android/util/Log.java': '''package android.util; public class Log {
 public static int i(String t,String s){return 0;}public static int d(String t,String s){return 0;}
 public static int w(String t,String s,Throwable x){return 0;}public static int e(String t,String s,Throwable x){return 0;}
}''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api; public interface XposedInterface {
 interface Invoker { enum Type {ORIGIN} Invoker setType(Type t);Object invoke(Object receiver,Object... args) throws Exception; }
 interface Chain {Object getThisObject();Object getArg(int i);Object proceed() throws Throwable;}
 interface Interceptor {Object intercept(Chain c) throws Throwable;} interface HookBuilder {void intercept(Interceptor i);}
}''',
'com/floatwindow/morebubblebutton/MoreBubbleHookModule.java': '''package com.floatwindow.morebubblebutton;
import java.lang.reflect.*;import io.github.libxposed.api.*;
public class MoreBubbleHookModule { public XposedInterface.HookBuilder hook(Method m){return i->{};}
 public XposedInterface.Invoker getInvoker(Method m){return new XposedInterface.Invoker(){
 public XposedInterface.Invoker setType(Type t){if(t!=Type.ORIGIN)throw new AssertionError();return this;}
 public Object invoke(Object r,Object... args)throws Exception{return m.invoke(r,args);}};}
}''',
'com/floatwindow/morebubblebutton/ModuleSettings.java': '''package com.floatwindow.morebubblebutton; import android.content.*;
public class ModuleSettings {static boolean enabled=true;public static boolean isDoubleTapFullscreenEnabled(Context c){return enabled;}}''',
}
files['com/floatwindow/morebubblebutton/IconTest.java'] = r'''package com.floatwindow.morebubblebutton;
import android.os.*;import android.view.*;import android.content.pm.*;import java.util.*;import java.lang.reflect.*;
public class IconTest {
 public static class Listener {public int $r8$classId;public Stack this$0;public int singles;public void onClick(View v){singles++;}}
 public static class TouchListener {public int $r8$classId;public Stack this$0;public boolean performedLongClick,movedEnough;}
 public static class Bubble {public View mIconView=new View();public String mPackageName="test.app";public UserHandle mUser=new UserHandle(0);public boolean isApp(){return true;}}
 public static class Data {public List<Bubble> mBubbles=new ArrayList<>();}
 public static class Stack {public Data mBubbleData=new Data();public Listener mBubbleClickListener=new Listener();public TouchListener mBubbleTouchListener=new TouchListener();public boolean mIsExpanded=true,mIsExpansionAnimating,mIsBubbleSwitchAnimating,mShowedUserEducationInTouchListenerActive;}
 static class Fixture {
  BubbleIconDoubleTap h=new BubbleIconDoubleTap(new MoreBubbleHookModule());Stack stack=new Stack();Bubble a=new Bubble(),b=new Bubble();
  Fixture()throws Exception {
   Handler.jobs.clear();SystemClock.now=1000;ModuleSettings.enabled=true;
   stack.mBubbleClickListener.this$0=stack;stack.mBubbleTouchListener.this$0=stack;stack.mBubbleData.mBubbles.add(a);stack.mBubbleData.mBubbles.add(b);b.mPackageName="test.other";
   set(h,"bubbleClass",Bubble.class);set(h,"clickMethod",Listener.class.getMethod("onClick",View.class));
  }
  boolean click(Bubble x)throws Exception{return (boolean)call(h,"handleClick",new Class[]{Object.class,Object.class,View.class},stack,stack.mBubbleClickListener,x.mIconView);}
  void touch(Bubble x,int action,long t,float dx)throws Exception{SystemClock.now=t;call(h,"observeTouch",new Class[]{Object.class,Object.class,View.class,MotionEvent.class},stack,stack.mBubbleTouchListener,x.mIconView,new MotionEvent(action,t,dx,0));}
  LauncherApps launcher(){return a.mIconView.context.launcher;}
  int launches(){return launcher().launches+b.mIconView.context.launcher.launches;}
  int singles(){return stack.mBubbleClickListener.singles;}
 }
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object call(Object o,String n,Class[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  Fixture f=new Fixture();check(f.click(f.a),"defer first");check(f.singles()==0,"no immediate collapse");Handler.advance(1300);check(f.singles()==1&&f.launches()==0,"one original single");Handler.advance(2000);check(f.singles()==1,"not repeated");
  f=new Fixture();f.click(f.a);SystemClock.now=1100;f.click(f.a);Handler.advance(2000);check(f.launches()==1&&f.singles()==0,"same bubble double");
  f=new Fixture();f.click(f.a);SystemClock.now=1100;f.click(f.b);Handler.advance(1500);check(f.launches()==0&&f.singles()==2,"different icons single only");
  f=new Fixture();f.click(f.a);f.a.mIconView.detach();Handler.advance(2000);check(f.singles()==0&&f.launches()==0,"detach cleanup");
  f=new Fixture();f.click(f.a);ModuleSettings.enabled=false;SystemClock.now=1100;check(!f.click(f.a),"disabled current proceeds");check(f.singles()==1&&f.launches()==0,"disabled drains first");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);f.touch(f.a,2,1150,30);Handler.advance(2000);check(f.launches()==0&&f.singles()==0,"drag clears pending");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);f.touch(f.a,3,1150,0);Handler.advance(2000);check(f.launches()==0&&f.singles()==0,"cancel clears pending");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);Handler.advance(1600);check(!f.click(f.a)&&f.launches()==0,"long press never launches");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1290,0);Handler.advance(1310);check(f.singles()==0,"second down pauses first");f.touch(f.a,1,1340,0);f.click(f.a);check(f.launches()==1&&f.singles()==0,"second up launches after pause");
  f=new Fixture();f.a.mUser=new UserHandle(10);f.click(f.a);SystemClock.now=1100;f.click(f.a);check(f.launches()==1&&f.launcher().launchedUser.equals(new UserHandle(10))&&f.launcher().requestedUser.equals(new UserHandle(10)),"work profile exact user");
  f=new Fixture();f.click(f.a);SystemClock.now=1100;f.click(f.a);check("test.app".equals(f.launcher().requestedPackage)&&"test.app".equals(f.launcher().launchedComponent.getPackageName())&&f.launcher().launchedUser.equals(new UserHandle(0)),"same user exact package launcher");
  for(int scenario=0;scenario<9;scenario++){
   f=new Fixture();f.click(f.a);
   switch(scenario){case 0:f.a.mPackageName=null;break;case 1:f.a.mUser=null;break;case 2:f.a.mUser=new UserHandle(-1);break;case 3:f.launcher().missing=true;break;case 4:f.launcher().fail=true;break;case 5:f.launcher().deny=true;f.a.mUser=new UserHandle(10);break;case 6:f.launcher().wrongPackage=true;break;case 7:f.launcher().wrongUser=true;break;case 8:f.a.mIconView.context.launcher=null;break;}
   SystemClock.now=1100;f.click(f.a);Handler.advance(2000);
   check((scenario==8||f.launches()==0)&&f.singles()==1,"safe single fallback scenario="+scenario);
  }
  f=new Fixture();check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},f.stack.mBubbleClickListener,Listener.class,"mBubbleClickListener")==f.stack,"correct scope");
  Listener other=new Listener();other.this$0=f.stack;check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},other,Listener.class,"mBubbleClickListener")==null,"identity excludes merged role");
  f.stack.mBubbleClickListener.$r8$classId=1;check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},f.stack.mBubbleClickListener,Listener.class,"mBubbleClickListener")==null,"classId excludes flyout");
  try{call(f.h,"read",new Class[]{Object.class,String.class},f.stack,"notPresent");throw new AssertionError("missing field hidden");}catch(InvocationTargetException expected){check(expected.getCause() instanceof NoSuchFieldException,"missing distinct from null");}
  System.out.println("PASS: gesture, same-user/work-profile launch and 9 safe fallback scenarios with stubs; NOT on-device testing");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    root = pathlib.Path(directory)
    for name, text in files.items():
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    helper = root / 'com/floatwindow/morebubblebutton/BubbleIconDoubleTap.java'
    helper.write_text(s)
    subprocess.run(['javac', '-d', str(root / 'classes')] + [str(p) for p in root.rglob('*.java')], check=True)
    subprocess.run(['java', '-cp', str(root / 'classes'), 'com.floatwindow.morebubblebutton.IconTest'], check=True)
