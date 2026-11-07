#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Emulator smoke test + screenshots for CI. Every step asserts real state:
# the terminal grid (via the debug-only DebugReceiver dump), UI nodes (via
# uiautomator) and crashes (logcat). Any failed assertion fails the job.
#
#   ci-screenshots.sh <app-debug.apk> [full|smoke]
#
# smoke: launch + echo only (used for the API 30 job).
set -euo pipefail

APK="${1:?usage: ci-screenshots.sh <app-debug.apk> [full|smoke]}"
MODE="${2:-full}"
PKG=dev.ferrux
ACTIVITY="$PKG/dev.ferrux.app.MainActivity"
RECEIVER="$PKG/dev.ferrux.app.debug.DebugReceiver"
OUT="${OUT:-screenshots}"
mkdir -p "$OUT"
HERE="$(cd "$(dirname "$0")" && pwd)"

log() { echo "== $*"; }
fail() {
  echo "::error::$*" >&2
  exit 1
}

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  log "screenshot $1"
}

# --- terminal state ----------------------------------------------------------

dump_screen() {
  adb shell am broadcast -n "$RECEIVER" -a dev.ferrux.debug.DUMP_SCREEN > /dev/null
  adb exec-out run-as "$PKG" cat files/debug-screen.txt
}

# expect_screen <ERE> <what>: waits up to 30 s for the grid/header to match.
expect_screen() {
  local i
  for i in $(seq 1 30); do
    if dump_screen > "$OUT/screen.txt" 2> /dev/null && grep -Eq -- "$1" "$OUT/screen.txt"; then
      log "ok: $2"
      return 0
    fi
    sleep 1
  done
  echo "--- last screen dump ---"
  cat "$OUT/screen.txt" || true
  fail "$2: /$1/ not found in terminal dump"
}

header_field() { dump_screen | head -n1 | tr ' ' '\n' | sed -n "s/^$1=//p"; }

# --- UI nodes ----------------------------------------------------------------

ui_dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml 2> /dev/null || true
}

# node <fixed-string>: the first UI node whose attributes contain it.
node() { ui_dump | tr '<' '\n' | grep -F -- "$1" | head -n1 || true; }

wait_node() {
  local i n
  for i in $(seq 1 20); do
    n="$(node "$1")"
    if [ -n "$n" ]; then
      printf '%s' "$n"
      return 0
    fi
    sleep 1
  done
  ui_dump > "$OUT/ui-missing.xml"
  fail "no UI node matching $1 (see ui-missing.xml)"
}

tap_node() {
  local n b
  n="$(wait_node "$1")"
  b="$(printf '%s' "$n" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')"
  set -- $b
  adb shell input tap $(((${1} + ${3}) / 2)) $(((${2} + ${4}) / 2))
}

# --- soft keyboard -----------------------------------------------------------

# Gboard pops up when `input text` starts typing. ROWS_FULL (measured at
# launch, keyboard hidden) is the ground truth: fewer rows = keyboard up.
hide_ime() {
  local i rows
  rows="$(header_field rows)"
  [ -n "$rows" ] && [ "$rows" -lt "$ROWS_FULL" ] || return 0
  adb shell input keyevent KEYCODE_BACK # consumed by the IME while it is shown
  for i in $(seq 1 20); do
    rows="$(header_field rows)"
    [ "$rows" -ge "$ROWS_FULL" ] && return 0
    sleep 0.5
  done
  fail "could not hide the soft keyboard ($rows < $ROWS_FULL rows)"
}

# --- input -------------------------------------------------------------------

# `input text` needs %s for spaces; keep shell metacharacters out (adb shell
# passes the string through the device's sh).
type_line() {
  adb shell input text "$1"
  adb shell input keyevent KEYCODE_ENTER
}

# --- crash detection ---------------------------------------------------------

check_crash() {
  adb logcat -d -v threadtime > "$OUT/logcat-full.txt" || true
  grep -E "$PKG|AndroidRuntime|DEBUG|crash_dump|libc|ferrux|FerruxDump" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true
  if grep -A2 'FATAL EXCEPTION' "$OUT/logcat-full.txt" | grep -q "Process: $PKG"; then
    grep -A30 'FATAL EXCEPTION' "$OUT/logcat-full.txt" | head -60
    fail "Java crash in $PKG"
  fi
  if grep -Eq ">>> $PKG <<<|Fatal signal .*\($PKG\)" "$OUT/logcat-full.txt"; then
    grep -E -A20 ">>> $PKG <<<|Fatal signal" "$OUT/logcat-full.txt" | head -60
    fail "native crash in $PKG"
  fi
  adb shell pidof "$PKG" > /dev/null || fail "$PKG is not running"
}
trap 'check_crash || true' EXIT

# --- run ---------------------------------------------------------------------

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell wm size | tee "$OUT/display.txt"

adb install -r -g "$APK"
# The notification-permission dialog would cover the terminal.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2> /dev/null || true
adb logcat -c
adb shell am start -W -n "$ACTIVITY"

log "01: fresh launch lands in a shell"
expect_screen '^sessions=1 ' "one session auto-created"
expect_screen '^~ \$' "shell prompt visible"
ROWS_FULL="$(header_field rows)"
log "grid at launch: ${ROWS_FULL} rows x $(header_field cols) cols"
[ "$ROWS_FULL" -ge 30 ] || fail "terminal only has $ROWS_FULL rows on a full screen"
shot 01-launch

log "02: echo"
type_line 'echo%shello%sferrux'
expect_screen '^hello ferrux$' "echo output in grid"
hide_ime
shot 02-echo
check_crash

if [ "$MODE" = smoke ]; then
  log "smoke test passed"
  exit 0
fi

log "03: ls + colours"
adb shell "run-as $PKG sh -c 'cat > files/home/colors.sh'" < "$HERE/ci-colors.sh"
type_line 'ls%s-la%s/system'
expect_screen 'build\.prop' "ls -la /system output"
type_line 'sh%scolors.sh'
expect_screen '^truecolor ok$' "colour test ran"
hide_ime
shot 03-colors

log "04: soft keyboard resizes the terminal"
hide_ime
rows_before="$(header_field rows)"
tap_node 'content-desc="Terminal"'
for i in $(seq 1 20); do
  rows_now="$(header_field rows)"
  [ -n "$rows_now" ] && [ "$rows_now" -lt "$rows_before" ] && break
  sleep 1
done
[ "$rows_now" -lt "$rows_before" ] || fail "terminal did not shrink above the IME ($rows_before -> $rows_now rows)"
log "ok: rows $rows_before -> $rows_now with IME"
adb shell dumpsys input_method | grep -E 'mInputShown|isInputViewShown' | head -n3 || true
shot 04-ime

log "05: drawer with two sessions"
tap_node 'content-desc="Sessions"'
wait_node 'text="New session"' > /dev/null
tap_node 'text="New session"'
expect_screen '^sessions=2 ' "second session created"
expect_screen '^~ \$' "second session prompt"
tap_node 'content-desc="Sessions"'
wait_node 'text="Session 1"' > /dev/null
wait_node 'text="Session 2"' > /dev/null
shot 05-drawer
tap_node 'text="Session 2"'
sleep 1

# expect_node <selector> <attr="value">... : waits for a UI node with all attributes.
expect_node() {
  local sel="$1" i n a ok
  shift
  for i in $(seq 1 10); do
    n="$(node "$sel")"
    ok=1
    for a in "$@"; do printf '%s' "$n" | grep -qF -- "$a" || ok=0; done
    if [ "$ok" = 1 ]; then
      log "ok: $sel has $*"
      return 0
    fi
    sleep 1
  done
  echo "node: $n"
  fail "$sel lacks one of: $*"
}

log "06: CTRL toggle, also as seen by accessibility services"
expect_node 'content-desc="CTRL"' 'checkable="true"' 'checked="false"' 'clickable="true"'
expect_node 'content-desc="ESC"' 'clickable="true"'
expect_node 'content-desc="Up"' 'clickable="true"'
# Behavioural check: CTRL applies to exactly the next key. Ctrl+A moves to
# the start of the line in mksh, then '#' (unmodified) comments the line out.
adb shell input text 'echo%sX'
tap_node 'content-desc="CTRL"'
expect_node 'content-desc="CTRL"' 'checkable="true"' 'checked="true"'
shot 06-ctrl
adb shell input text a
adb shell "input text '#'" # quoted: # starts a comment in the device shell
expect_screen '^~ \$ #echo X$' "CTRL+A then # (modifier applied to one key only)"
expect_node 'content-desc="CTRL"' 'checked="false"'
adb shell input keyevent KEYCODE_ENTER
expect_screen '^sessions=2 ' "sessions still alive"

check_crash
log "all checks passed"
