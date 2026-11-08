#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Source this from CI emulator scripts:  . "$(dirname "$0")/ci-adb-guard.sh"
#
# ensure_device [what]: makes sure the emulator is reachable and booted before a
# step. On GitHub's runners adb sometimes reports "device offline" mid-job (the
# emulator process is fine, the adb transport dropped). This waits for the
# device, re-checks sys.boot_completed, and tries `adb reconnect offline` once
# before giving up, so infra hiccups don't look like test failures.

ensure_device() {
  local what="${1:-next step}" attempt state boot
  for attempt in 1 2; do
    state="$(adb get-state 2> /dev/null || true)"
    if [ "$state" = device ]; then
      boot="$(timeout 20 adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r' || true)"
      [ "$boot" = 1 ] && return 0
    fi
    if [ "$attempt" = 1 ]; then
      echo "== adb: device '${state:-missing}' before $what; reconnecting"
      adb reconnect offline > /dev/null 2>&1 || true
      timeout 60 adb wait-for-device || true
      # Wait for the boot flag again after a reconnect (up to 60 s).
      for _ in $(seq 1 30); do
        boot="$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r' || true)"
        [ "$boot" = 1 ] && break
        sleep 2
      done
    fi
  done
  echo "::error::adb: device not available before $what (state '${state:-missing}')" >&2
  return 1
}
