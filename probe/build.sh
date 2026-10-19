#!/bin/bash
set -ex
BT="$SDK_ROOT/build-tools/35.0.0"
JAR="$SDK_ROOT/platforms/android-35/android.jar"
rm -rf out skel
mkdir -p out/classes out/dex skel/res

cat > skel/apktool.yml <<'YML'
version: 2.10.0
apkFileName: empty.apk
isFrameworkApk: false
sdkInfo:
  minSdkVersion: '29'
  targetSdkVersion: '35'
packageInfo:
  forcedPackageId: '127'
usesFramework:
  ids: [1]
YML

cp probe/AndroidManifest.xml skel/AndroidManifest.xml
"$BT/aapt2" compile --dir skel -o skel/res.zip

javac -nowarn -source 11 -target 11 -d out/classes -cp "$JAR" probe/Probe.java
"$BT/d8" --min-api 29 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')

"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest skel/AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 35 skel/res.zip
rm -rf apkroot && mkdir apkroot && cd apkroot && unzip -o -q ../out/base.apk
cp ../out/dex/classes.dex .
zip -q -r ../out/final.apk .
cd ..
cp out/final.apk out/base.apk
unzip -l out/base.apk

keytool -genkeypair -keystore /tmp/k.jks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=probe"
"$BT/apksigner" sign --ks /tmp/k.jks --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 --out out/probe-signed.apk out/base.apk
unzip -l out/probe-signed.apk
"$BT/apksigner" verify out/probe-signed.apk
