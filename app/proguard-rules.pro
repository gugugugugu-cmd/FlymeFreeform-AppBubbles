# Add project specific ProGuard rules here.
-dontwarn io.github.libxposed.annotation.**

# !! 关键 !!
# 入口类绝不能混淆：LSPosed 通过 META-INF/xposed/java_init.list 里的【全限定类名】
# 反射加载入口类。若允许 R8 改名，配合 -adaptresourcefilecontents，
# java_init.list 会被改写成混淆后的短名（实测变成 "e50"），而该短名在 DEX 里
# 根本查不到 → LSPosed 静默加载失败，模块完全不生效（日志里一行都不会有）。
# 因此入口类必须 -keep 且 !allowobfuscation；同时移除 -adaptresourcefilecontents，
# 避免 R8 再去改写这个文件。
-keep class com.floatwindow.morebubblebutton.MoreBubbleHookModule { *; }

# 其余 XposedModule 子类仍可优化，但同样不能改名
-keep,allowoptimization,allowaccessmodification,allowshrinking class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
