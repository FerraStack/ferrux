#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds libferrux.so (the app) and libferruxterm.so (terminal-view) with cargo-ndk.
# Same commands as the Gradle tasks `cargoNdkBuild` (android/app, android/terminal-view);
# CI calls this script and then runs Gradle with -Pferrux.skipCargo=true.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_NDK_HOME:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
export ANDROID_NDK_HOME
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-6}"

cargo ndk -t arm64-v8a -t x86_64 --platform 30 \
  -o android/app/src/main/jniLibs \
  build --release -p ferrux-android

# libferruxterm.so: the terminal-only library shipped in the terminal-view AAR
# (task `cargoNdkBuild` in android/terminal-view/build.gradle.kts).
cargo ndk -t arm64-v8a -t x86_64 --platform 30 \
  -o android/terminal-view/src/main/jniLibs \
  build --release -p ferrux-term-jni
