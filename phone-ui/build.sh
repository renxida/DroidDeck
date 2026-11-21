#!/bin/bash
# Builds phone-ui/claude-phone.apk with the SDK's own tools (no Gradle). Run from the repo root.
set -e
BT=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
PLAT=$(ls -d $ANDROID_HOME/platforms/android-* | sort -V | tail -1)/android.jar
echo "build-tools $BT platform $PLAT"
W=$(mktemp -d); cd phone-ui
$BT/aapt2 compile --dir res -o $W/res.zip
$BT/aapt2 link -o $W/base.apk -I $PLAT --manifest AndroidManifest.xml -R $W/res.zip -A assets --java $W/gen --auto-add-overlay --min-sdk-version 33 --target-sdk-version 36
mkdir -p $W/cls $W/dex
javac -source 11 -target 11 -nowarn -Xlint:-options -cp $PLAT -d $W/cls $(find src $W/gen -name '*.java')
$BT/d8 --release --min-api 33 --lib $PLAT --output $W/dex $(find $W/cls -name '*.class')
(cd $W/dex && zip -q -u $W/base.apk classes.dex)
$BT/zipalign -f 4 $W/base.apk $W/aligned.apk
keytool -genkeypair -keystore $W/k.jks -storepass android -keypass android -alias k -keyalg RSA -dname CN=claude -validity 365 -noprompt 2>/dev/null
$BT/apksigner sign --ks $W/k.jks --ks-pass pass:android --out claude-phone.apk $W/aligned.apk
ls -l claude-phone.apk
