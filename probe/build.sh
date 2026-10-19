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
cd out && rm -rf apkroot && mkdir apkroot && cd apkroot
unzip -o -q ../base.apk
cp ../dex/classes.dex .
cp -r ../meta/* .
rm -f ../final.apk
zip -q -0 -X ../final.apk resources.arsc
zip -q -0 -X ../final.apk AndroidManifest.xml
zip -q -X ../final.apk classes.dex
(cd META-INF && zip -q -X ../../final.apk xposed/module.prop xposed/java_init.list xposed/scope.list)
cd ..
unzip -v final.apk

# 5) 对齐 + 签名
"$BT/zipalign" -f -p 4 out/final.apk out/aligned.apk
rm -f /tmp/k.jks
keytool -genkeypair -keystore /tmp/k.jks -storepass android -keypass android -alias k \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=probe"
"$BT/apksigner" sign --min-sdk-version 29 --ks /tmp/k.jks --ks-pass pass:android \
    --key-pass pass:android --out probe-signed.apk out/aligned.apk
"$BT/apksigner" verify probe-signed.apk
