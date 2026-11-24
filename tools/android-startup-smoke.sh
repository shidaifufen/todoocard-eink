#!/usr/bin/env bash
set -euo pipefail
mkdir -p startup-proof
PKG=com.shidaifufen.todocard
ACTIVITY="$PKG/.MainActivity"
APK="$(find startup-apk -name '*.apk' -print -quit)"
test -n "$APK"

# Reproduce the shipped version on ART/ICU, then verify upgrade without deleting
# application data. JVM/Robolectric alone did not expose the regex difference.
curl --fail --location --retry 3 \
  https://github.com/shidaifufen/todoocard-eink/releases/download/v1.5.0-android/todocard-android-1.5.0.apk \
  --output startup-proof/baseline-1.5.0.apk
adb install startup-proof/baseline-1.5.0.apk
adb logcat -c
adb shell am start -W -n "$ACTIVITY" > startup-proof/baseline-start.txt
sleep 5
adb logcat -d > startup-proof/baseline-logcat.txt
grep -q 'PatternSyntaxException' startup-proof/baseline-logcat.txt
grep -q 'LayoutPack' startup-proof/baseline-logcat.txt
rm startup-proof/baseline-1.5.0.apk

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" > startup-proof/fixed-start.txt
sleep 8
adb shell pidof "$PKG" > startup-proof/fixed-pid.txt
test -s startup-proof/fixed-pid.txt
adb logcat -d > startup-proof/fixed-logcat.txt
if grep -q 'FATAL EXCEPTION\|PatternSyntaxException' startup-proof/fixed-logcat.txt; then
  cat startup-proof/fixed-logcat.txt
  exit 1
fi
adb shell dumpsys activity activities > startup-proof/fixed-activities.txt
grep -q "mResumedActivity.*$PKG" startup-proof/fixed-activities.txt
adb exec-out screencap -p > startup-proof/fixed-startup.png

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" > startup-proof/second-start.txt
sleep 5
adb shell pidof "$PKG" > startup-proof/second-pid.txt
test -s startup-proof/second-pid.txt
adb logcat -d > startup-proof/second-logcat.txt
if grep -q 'FATAL EXCEPTION\|PatternSyntaxException' startup-proof/second-logcat.txt; then exit 1; fi
