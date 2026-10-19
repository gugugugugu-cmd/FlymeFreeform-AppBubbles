#!/bin/bash
set -ex
BT="$SDK_ROOT/build-tools/35.0.0"
JAR="$SDK_ROOT/platforms/android-35/android.jar"
OUT="$PWD/out"
rm -rf out skel
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/meta/META-INF/xposed" skel/res

# 1) dex
javac -nowarn -source 11 -target 11 -d "$OUT/classes" -cp "$JAR" probe/Probe.java
"$BT/d8" --min-api 29 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 2) libxposed 元数据（缺这三样 LSPosed 不认）
printf 'minApiVersion=102\ntargetApiVersion=102\nstaticScope=false\n' > "$OUT/meta/META-INF/xposed/module.prop"
printf 'io.github.mangi.flymefreeformprobe.Probe\nhook\n' > "$OUT/meta/META-INF/xposed/java_init.list"
printf 'com.android.launcher\ncom.google.android.apps.nexuslauncher\ncom.android.systemui\n' > "$OUT/meta/META-INF/xposed/scope.list"

# 3) 资源 + 清单 -> base.apk（本步产生的 arsc 会在下一步重写为 stored）
"$BT/aapt2" compile --dir skel -o skel/res.zip
"$BT/aapt2" link -I "$JAR" --manifest probe/AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 35 \
    -o "$OUT/base.apk" skel/res.zip

# 4) 解包后追加 dex 与元数据到 APK 根目录
rm -rf "$OUT/apkroot" && mkdir -p "$OUT/apkroot/META-INF/xposed"
cd "$OUT/apkroot"
unzip -o -q "$OUT/base.apk"
cp "$OUT/dex/classes.dex" .
cp "$OUT/meta/META-INF/xposed/module.prop" .
cp "$OUT/meta/META-INF/xposed/java_init.list" META-INF/xposed/
cp "$OUT/meta/META-INF/xposed/scope.list" META-INF/xposed/
rm -f "$OUT/final.apk"
zip -q -0 -X "$OUT/final.apk" resources.arsc
zip -q -X "$OUT/final.apk" AndroidManifest.xml classes.dex \
    META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list
cd "$OUT"
unzip -v "$OUT/final.apk"

# 5) 对齐 + 签名
"$BT/zipalign" -f -p 4 "$OUT/final.apk" "$OUT/aligned.apk"
rm -f /tmp/k.jks
keytool -genkeypair -keystore /tmp/k.jks -storepass android -keypass android -alias k \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=probe"
"$BT/apksigner" sign --min-sdk-version 29 --ks /tmp/k.jks --ks-pass pass:android \
    --key-pass pass:android --out "$OUT/probe-signed.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/probe-signed.apk"
