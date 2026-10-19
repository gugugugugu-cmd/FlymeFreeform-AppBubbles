#!/bin/bash
set -ex
BT="$SDK_ROOT/build-tools/35.0.0"
JAR="$SDK_ROOT/platforms/android-35/android.jar"
rm -rf out skel apkroot
mkdir -p out/classes out/dex skel/res out/meta/META-INF/xposed

# 1) 编译 dex
javac -nowarn -source 11 -target 11 -d out/classes -cp "$JAR" probe/Probe.java
"$BT/d8" --min-api 29 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')

# 2) libxposed 元数据（缺了这三样 LSPosed 不会当模块）
printf 'minApiVersion=102\ntargetApiVersion=102\nstaticScope=false\n' > out/meta/META-INF/xposed/module.prop
printf 'io.github.mangi.flymefreeformprobe.Probe\nhook\n' > out/meta/META-INF/xposed/java_init.list
printf 'com.android.launcher\ncom.google.android.apps.nexuslauncher\ncom.android.systemui\n' > out/meta/META-INF/xposed/scope.list

# 3) 资源与清单
"$BT/aapt2" compile --dir skel -o skel/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest probe/AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 35 skel/res.zip

# 4) 合入 dex 与元数据，resources.arsc / AndroidManifest 保持不压缩
OUT="$PWD/out"
rm -rf "$OUT/apkroot" && mkdir "$OUT/apkroot"
cd "$OUT/apkroot"
unzip -o -q "$OUT/base.apk"
cp "$OUT/dex/classes.dex" .
cp -r "$OUT/meta/." .
rm -f "$OUT/final.apk"
zip -q -0 -X "$OUT/final.apk" resources.arsc
zip -q -0 -X "$OUT/final.apk" AndroidManifest.xml
zip -q -X "$OUT/final.apk" classes.dex
zip -q -X "$OUT/final.apk" META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list
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
