#!/bin/bash
# Builds assist/claude-assist.apk with the SDK's own tools. Run from the repo root.
set -ex
BT=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
PLAT=$(ls -d $ANDROID_HOME/platforms/android-* | sort -V | tail -1)/android.jar
W=$(mktemp -d); cd assist
$BT/aapt2 compile --dir res -o $W/res.zip
$BT/aapt2 link -o $W/base.apk -I $PLAT --manifest AndroidManifest.xml -R $W/res.zip --auto-add-overlay
mkdir -p $W/cls $W/dex
javac -source 8 -target 8 -bootclasspath $PLAT -d $W/cls $(find src -name '*.java')
$BT/d8 --lib $PLAT --output $W/dex $(find $W/cls -name '*.class')
(cd $W/dex && zip -q -u $W/base.apk classes.dex)
$BT/zipalign -f 4 $W/base.apk $W/aligned.apk
keytool -genkeypair -keystore $W/k.jks -storepass android -keypass android -alias k -keyalg RSA -dname CN=claude -validity 365 -noprompt
$BT/apksigner sign --ks $W/k.jks --ks-pass pass:android --out claude-assist.apk $W/aligned.apk
ls -l claude-assist.apk
