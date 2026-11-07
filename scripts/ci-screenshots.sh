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

# The grid lines of the dump (no header).
grid_lines() { dump_screen | sed '1,/^---$/d'; }

# grid_row <ERE>: 0-based row of the last grid line matching (short lines only:
# soft-wrapped rows are joined in the dump).
grid_row() { grid_lines | grep -nE -- "$1" | tail -n1 | cut -d: -f1 | awk '{ print $1 - 1 }'; }

# --- terminal view state (selection UI, geometry, clipboard) -----------------

ui_state() {
  adb shell am broadcast -n "$RECEIVER" -a dev.ferrux.debug.DUMP_SCREEN > /dev/null
  adb exec-out run-as "$PKG" cat files/debug-ui.txt
}

ui_field() { ui_state | sed -n "s/^$1=//p" | head -n1; }

# expect_ui <key> <ERE> <what>: waits up to 10 s for a debug-ui field to match.
expect_ui() {
  local i v
  for i in $(seq 1 20); do
    v="$(ui_field "$1")"
    if printf '%s\n' "$v" | grep -Eq -- "$2"; then
      log "ok: $3 ($1=$v)"
      return 0
    fi
    sleep 0.5
  done
  ui_state > "$OUT/ui-state.txt" || true
  cat "$OUT/ui-state.txt" || true
  fail "$3: $1='$v' does not match /$2/"
}

# cell_xy <row> <col>: screen coordinates of the centre of a terminal cell.
cell_xy() {
  local st
  st="$(ui_state)"
  awk -v r="$1" -v c="$2" -F'[=,]' '
    $1 == "origin" { ox = $2; oy = $3 }
    $1 == "cell" { cw = $2; ch = $3 }
    END { printf "%d %d", ox + (c + 0.5) * cw, oy + (r + 0.5) * ch }' <<< "$st"
}

# cell_width: the width of one cell in pixels (float).
cell_width() { ui_field cell | cut -d, -f1; }

# word_xy <line ERE> <word>: screen coordinates of the middle of <word> on the
# last grid line matching <line ERE>.
word_xy() {
  local row line col
  row="$(grid_row "$1")"
  [ -n "$row" ] || fail "no grid line matches /$1/"
  line="$(grid_lines | sed -n "$((row + 1))p")"
  col="$(awk -v l="$line" -v w="$2" 'BEGIN { i = index(l, w); print (i ? i - 1 + int(length(w) / 2) : -1) }')"
  [ "$col" -ge 0 ] || fail "'$2' is not on line '$line'"
  cell_xy "$row" "$col"
}

# long_press <x> <y>
long_press() { adb shell input swipe "$1" "$2" "$1" "$2" 1200; }

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

# tap_bounds <node>: taps the centre of a node's bounds.
tap_bounds() {
  local b
  b="$(printf '%s' "$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')"
  set -- $b
  adb shell input tap $(((${1} + ${3}) / 2)) $(((${2} + ${4}) / 2))
}

tap_node() { tap_bounds "$(wait_node "$1")"; }

# All windows, including popups such as the floating text toolbar.
ui_dump_windows() {
  adb shell uiautomator dump --windows /sdcard/ui-all.xml > /dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui-all.xml 2> /dev/null || true
}

# tap_text <text>: taps the node whose text is exactly <text>, in any window.
tap_text() {
  local i n
  for i in $(seq 1 10); do
    n="$(ui_dump | tr '<' '\n' | grep -F -- "text=\"$1\"" | head -n1 || true)"
    [ -n "$n" ] || n="$(ui_dump_windows | tr '<' '\n' | grep -F -- "text=\"$1\"" | head -n1 || true)"
    if [ -n "$n" ]; then
      tap_bounds "$n"
      return 0
    fi
    sleep 1
  done
  ui_dump_windows > "$OUT/ui-missing.xml"
  fail "no UI node with text '$1' (see ui-missing.xml)"
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

# wait_ime_settled: if the soft keyboard is coming up, waits (up to 5 s) until the
# grid has shrunk and stopped changing.
wait_ime_settled() {
  local i prev="" rows
  for i in $(seq 1 20); do
    rows="$(header_field rows)"
    if [ -n "$rows" ] && [ "$rows" -lt "${ROWS_FULL:-0}" ] && [ "$rows" = "$prev" ]; then
      return 0
    fi
    prev="$rows"
    sleep 0.25
  done
}

# `input text` needs %s for spaces; keep shell metacharacters out (adb shell
# passes the string through the device's sh).
#
# Typing makes Gboard pop up, and keystrokes injected while it animates in can
# arrive out of order ("echo" -> "ehco"). So the first character goes alone,
# then the rest once the keyboard has settled.
type_text() {
  adb shell input text "${1:0:1}"
  wait_ime_settled
  if [ ${#1} -gt 1 ]; then adb shell input text "${1:1}"; fi
}

type_line() {
  type_text "$1"
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

log "06: CTRL toggle"
# Behavioural check: CTRL applies to exactly the next key. Ctrl+A moves to
# the start of the line in mksh, then '#' (unmodified) comments the line out.
type_text 'echo%sX'
tap_node 'content-desc="CTRL"'
sleep 1
log "CTRL node: $(node 'content-desc="CTRL"' | grep -oE '(checkable|checked|selected)="[a-z]+"' | tr '\n' ' ')"
shot 06-ctrl
adb shell input text a
adb shell "input text '#'" # quoted: # starts a comment in the device shell
expect_screen '^~ \$ #echo X$' "CTRL+A then # (modifier applied to one key only)"
adb shell input keyevent KEYCODE_ENTER
expect_screen '^sessions=2 ' "sessions still alive"

log "07: long press selects a word, with handles and the action mode"
type_line 'clear'
type_line 'echo%sselect%sFERRUXWORD%sdone'
expect_screen '^select FERRUXWORD done$' "selection test line"
hide_ime
LINE='^select FERRUXWORD done$'
long_press $(word_xy "$LINE" FERRUXWORD)
expect_ui selection '^FERRUXWORD$' "long press selected the word"
expect_ui handles '^2$' "both selection handles shown"
expect_ui actionMode '^Copy\|.*Select all' "floating action mode with Copy … Select all"
sleep 1 # let the toolbar fade in
shot 07-selection
tap_text Copy
expect_ui clipboard '^FERRUXWORD$' "Copy put the word on the clipboard"
expect_ui selecting '^0$' "Copy ended selection mode"
expect_ui handles '^0$' "handles gone"

log "08: double tap selects a word, triple tap the line"
xy="$(word_xy "$LINE" done)"
adb shell "input tap $xy; input tap $xy"
expect_ui selection '^done$' "double tap selected a word"
sleep 1
adb shell "input tap $xy; input tap $xy; input tap $xy"
expect_ui selection '^select FERRUXWORD done$' "triple tap selected the line"
adb shell input keyevent KEYCODE_BACK
expect_ui selecting '^0$' "Back ended selection mode"

log "09: dragging the end handle extends the selection"
long_press $(word_xy "$LINE" select)
expect_ui selection '^select$' "long press selected 'select'"
expect_ui actionMode 'Copy' "action mode shown"
handle="$(ui_field endHandle)"
[ -n "$handle" ] || fail "end handle not shown"
cw="$(cell_width)"
# The end handle sits after 'select' (column 6); drag it after 'done' (column 22).
read -r hx hy dx <<< "$(awk -F, -v cw="$cw" '{ printf "%d %d %d", ($1 + $3) / 2, ($2 + $4) / 2, 16 * cw }' <<< "$handle")"
adb shell input swipe "$hx" "$hy" "$((hx + dx))" "$hy" 1000
expect_ui selection '^select FERRUXWORD done$' "end handle dragged to the end of the line"
sleep 1
shot 08-handle-drag
tap_text 'Select all'
expect_ui selection '\\n.*select FERRUXWORD done' "Select all covers earlier lines too"
sleep 1
shot 09-select-all
adb shell input keyevent KEYCODE_BACK
expect_ui selecting '^0$' "Back ended selection mode"

log "10: long press on blank space offers Paste"
type_text 'echo%s'
rows="$(ui_field rows)"
long_press $(cell_xy "$((rows - 1))" 10)
tap_text Paste
adb shell input keyevent KEYCODE_ENTER
expect_screen '^FERRUXWORD$' "pasted text reached the shell"
hide_ime

log "11: hyperlinks: OSC 8 underline, Open link"
adb shell "run-as $PKG sh -c 'cat > files/home/links.sh'" < "$HERE/ci-hyperlink.sh"
type_line 'clear'
type_line 'sh%slinks.sh'
expect_screen '^links ok$' "hyperlink script ran"
hide_ime
expect_ui links '=https://ferrux\.dev/docs' "OSC 8 link is in the snapshot"
long_press $(word_xy '^Ferrux docs here$' docs)
expect_ui link '^https://ferrux\.dev/docs$' "the selection is on the hyperlink"
expect_ui actionMode 'Open link' "Open link offered"
sleep 1
shot 10-hyperlink
adb shell input keyevent KEYCODE_BACK
expect_ui selecting '^0$' "Back ended selection mode"
long_press $(word_xy '^plain https' 'https://example.com/plain')
expect_ui selection '^https://example\.com/plain$' "long press selected the whole URL"
expect_ui actionMode 'Open link' "Open link offered for a plain URL"
adb shell input keyevent KEYCODE_BACK
expect_ui selecting '^0$' "Back ended selection mode"

check_crash
log "all checks passed"
