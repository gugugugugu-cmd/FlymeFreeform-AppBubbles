#!/bin/bash
set -ex
BT="$SDK_ROOT/build-tools/35.0.0"
JAR="$SDK_ROOT/platforms/android-35/android.jar"
rm -rf out skel && mkdir -p out/classes skel
cat > skel/AndroidManifest.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.mangi.flymefreeformprobe">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />
    <application android:hasCode="true" />
</manifest>
XML
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
java -jar /usr/local/bin/apktool.jar b skel -o skel/empty.apk
cp skel/empty.apk out/base.apk
rm -f out/classes.dex
javac -nowarn -source 11 -target 11 -d out/classes -cp "$JAR" probe/Probe.java
"$BT/d8" --min-api 29 --lib "$JAR" --output out $(find out/classes -name '*.class')
rm -rf apkroot && mkdir apkroot && cd apkroot && unzip -o -q ../out/base.apk
keytool -genkeypair -keystore /tmp/k.jks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=probe"
"$BT/apksigner" sign --ks /tmp/k.jks --ks-pass pass:android --key-pass pass:android --out ../out/probe-signed.apk ../out/base.apk
cd .. && unzip -l out/probe-signed.apk
"$BT/apksigner" verify out/probe-signed.apk
