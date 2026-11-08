#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Cross-app test of the Ferrux API (docs/API.md §10) on an emulator:
#
#   ci-api.sh <apk-dir>
#
# <apk-dir> holds, from the android CI job:
#   app-debug.apk                     Ferrux, knownCerts = the "known" throwaway key
#   sample.apk, sample-test.apk       client + instrumentation, same debug key as Ferrux
#   sample-known.apk, -test-known.apk re-signed with the throwaway key listed in knownCerts
#   sample-other.apk, -test-other.apk re-signed with a throwaway key that is not listed
#
# Checks: install order (client before Ferrux), pipes, PTY, resize, kill, files,
# keep-alive (Ferrux's service in the foreground), denial of other signers, and a
# screenshot of the sample rendering a live shell from the PTY fd Ferrux passed.
set -euo pipefail

DIR="${1:?usage: ci-api.sh <apk-dir>}"
FERRUX=dev.ferrux
SAMPLE=dev.ferrux.apiclient.sample
RUNNER="$SAMPLE.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="$SAMPLE.ApiCrossAppTest"
OUT="${OUT:-screenshots}"
mkdir -p "$OUT"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

log() { echo "== api: $*"; }

# has_permission: whether the installed sample holds Ferrux's API permission.
# (Output captured first: `grep -q` closing a pipe early would trip pipefail.)
has_permission() {
  local dump
  dump="$(adb shell dumpsys package "$SAMPLE")"
  grep -q "$FERRUX.permission.API: granted=true" <<< "$dump"
}
fail() {
  echo "::error::api: $*" >&2
  adb logcat -d -s FerruxApiTest:* FerruxApiSample:* Ferrux:* ferrux:* AndroidRuntime:E > "$OUT/api-logcat.txt" 2>&1 || true
  exit 1
}

uninstall_clients() {
  adb uninstall "$SAMPLE.test" > /dev/null 2>&1 || true
  adb uninstall "$SAMPLE" > /dev/null 2>&1 || true
}

install_clients() { # <sample.apk> <test.apk>
  adb install -r -t "$1" > /dev/null || fail "install $1"
  adb install -r -t "$2" > /dev/null || fail "install $2"
}

# instrument <expect> [extra am args…]: runs ApiCrossAppTest, fails unless all pass.
instrument() {
  local expect="$1"
  shift
  local out="$OUT/api-instrument-$expect-$RANDOM.txt"
  adb shell am instrument -w -r -e expect "$expect" -e class "$TEST_CLASS" "$@" "$RUNNER" > "$out" 2>&1 || true
  if ! grep -q '^OK (' "$out"; then
    cat "$out"
    fail "instrumentation ($expect) failed"
  fi
  log "instrumentation ($expect): $(grep '^OK (' "$out")"
}

# --- 1. Client first, then Ferrux: the permission is defined after the request.
log "API level $API"
adb uninstall "$FERRUX" > /dev/null 2>&1 || true
uninstall_clients
install_clients "$DIR/sample.apk" "$DIR/sample-test.apk"
adb install -r "$DIR/app-debug.apk" > /dev/null || fail "install Ferrux"
has_permission || fail "permission not granted to the sample after installing Ferrux second"
log "ok: permission granted (client installed before Ferrux)"

# --- 2. The functional tests (granted).
adb shell am force-stop "$FERRUX"
instrument granted

# --- 3. Keep-alive: while held, Ferrux's FerruxService runs in the foreground.
adb shell am force-stop "$FERRUX"
# The client must be visible to let Ferrux start its foreground service.
adb shell am start -W -n "$SAMPLE/.SampleActivity" > /dev/null
adb shell am instrument -w -e expect granted -e keepalive 1 \
  -e class "$TEST_CLASS#keepAliveHold" "$RUNNER" > "$OUT/api-keepalive.txt" 2>&1 &
held=0
for _ in $(seq 1 20); do
  sleep 1
  services="$(adb shell dumpsys activity services "$FERRUX" 2>/dev/null || true)"
  # Foreground state of the service, or Ferrux's ongoing notification (posted only
  # when FerruxService is foreground).
  notes="$(adb shell dumpsys notification --noredact 2>/dev/null | grep -A3 "pkg=$FERRUX" || true)"
  if grep -q 'isForeground=true' <<< "$services" || grep -q 'FLAG_ONGOING_EVENT\|ongoing' <<< "$notes"; then
    held=1
    break
  fi
done
# Keep the evidence either way.
printf '%s\n' "$services" > "$OUT/api-keepalive-services.txt"
printf '%s\n' "$notes" > "$OUT/api-keepalive-notification.txt"
wait || true
grep -q '^OK (' "$OUT/api-keepalive.txt" || { cat "$OUT/api-keepalive.txt"; fail "keep-alive test failed"; }
[ "$held" = 1 ] || fail "FerruxService was not in the foreground during the keep-alive (see api-keepalive-services.txt)"
log "ok: keep-alive put FerruxService in the foreground"

# --- 4. Screenshot: the sample renders a live shell from the PTY fd.
adb shell am force-stop "$SAMPLE"
adb shell am start -W -n "$SAMPLE/.SampleActivity" > /dev/null
ok=0
for _ in $(seq 1 30); do
  adb shell uiautomator dump /sdcard/api-ui.xml > /dev/null 2>&1 || true
  ui="$(adb exec-out cat /sdcard/api-ui.xml 2> /dev/null || true)"
  if grep -q 'Terminal OK' <<< "$ui"; then
    ok=1
    break
  fi
  sleep 1
done
adb exec-out screencap -p > "$OUT/api-01-sample-terminal.png"
[ "$ok" = 1 ] || fail "the sample's terminal never showed the command output (see api-01-sample-terminal.png)"
log "ok: sample terminal shows output from the shell in Ferrux"
adb shell am force-stop "$SAMPLE"

# --- 5. A client signed with an unlisted key is denied.
uninstall_clients
install_clients "$DIR/sample-other.apk" "$DIR/sample-test-other.apk"
if has_permission; then fail "permission granted to a client signed with an unlisted key"; fi
instrument denied
log "ok: unlisted signer denied"

# --- 6. A key listed in knownCerts is granted on API 31+ (knownSigner); API 30 only
#        knows `signature`, so the same client is denied there.
uninstall_clients
install_clients "$DIR/sample-known.apk" "$DIR/sample-test-known.apk"
if [ "$API" -ge 31 ]; then
  instrument granted -e class "$TEST_CLASS#pipesEcho"
  log "ok: knownSigner client granted"
else
  instrument denied
  log "ok: knownSigner client denied on API $API (no knownSigner before 31)"
fi
uninstall_clients
log "all API checks passed"
