#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds libferrux.so for the APK with cargo-ndk.
# Same command as the Gradle task `cargoNdkBuild` (android/app/build.gradle.kts);
# CI calls this script and then runs Gradle with -Pferrux.skipCargo=true.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_NDK_HOME:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
export ANDROID_NDK_HOME
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-6}"

cargo ndk -t arm64-v8a -t x86_64 --platform 30 \
  -o android/app/src/main/jniLibs \
  build --release -p ferrux-android
