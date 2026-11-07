#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Runs inside the emulator job: installs the debug APK, starts a session,
# types a command and saves screenshots + logcat to ./screenshots.
set -euxo pipefail

APK="${1:?usage: ci-screenshots.sh <app-debug.apk>}"
PKG=dev.ferrux
# Component = applicationId / Kotlin namespace + class.
ACTIVITY="$PKG/dev.ferrux.app.MainActivity"
OUT=screenshots
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
# Always keep logcat (and a last screenshot) for debugging failures.
finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  [ -e "$OUT/01-launched.png" ] || shot 00-failure || true
}
trap finish EXIT

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb install -r -g "$APK"
# Avoid the notification-permission dialog covering the terminal.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

adb logcat -c
adb shell am start -W -n "$ACTIVITY"
sleep 8
shot 01-launched

# `input text` needs %s for spaces.
adb shell input text 'echo%shello%sferrux'
adb shell input keyevent KEYCODE_ENTER
sleep 3
shot 02-echo

adb shell input text 'uname%s-a;%sid;%sls%s/system%s|%shead%s-5'
adb shell input keyevent KEYCODE_ENTER
sleep 3
shot 03-commands

adb shell input text 'ls%s--color=always%s/system'
adb shell input keyevent KEYCODE_ENTER
sleep 2
shot 04-colors

adb shell pidof "$PKG" || { echo "app is not running"; exit 1; }
