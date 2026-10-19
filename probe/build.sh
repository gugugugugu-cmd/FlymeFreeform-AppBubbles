#!/bin/bash
set -ex
BT="$SDK_ROOT/build-tools/35.0.0"
JAR="$SDK_ROOT/platforms/android-35/android.jar"
rm -rf out skeleton && mkdir -p out/classes skeleton/minimal
cp probe/skeleton/minimal/AndroidManifest.xml skeleton/minimal/AndroidManifest.xml
java -jar /usr/local/bin/apktool.jar b skeleton/minimal -o skeleton/empty.apk
unzip -o -q skeleton/empty.apk -d out
javac -nowarn -source 11 -target 11 -d out/classes -cp "$JAR" probe/Probe.java
"$BT/d8" --min-api 29 --lib "$JAR" --output out $(find out/classes -name '*.class')
sed -i 's/apkFileName: .*/apkFileName: probe.apk/' out/apktool.yml
java -jar /usr/local/bin/apktool.jar b out -o out/probe-unsigned.apk
unzip -l out/probe-unsigned.apk
keytool -genkeypair -keystore /tmp/k.jks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=probe"
"$BT/apksigner" sign --ks /tmp/k.jks --ks-pass pass:android --key-pass pass:android --out out/probe-signed.apk out/probe-unsigned.apk
"$BT/apksigner" verify out/probe-signed.apk
