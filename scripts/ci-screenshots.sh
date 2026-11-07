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

# drag_rows <rows> <duration ms>: swipes down (towards older output) by <rows>
# terminal rows, starting a few rows from the top in the middle column.
drag_rows() {
  local x y ch dy
  read -r x y <<< "$(cell_xy 4 "$(($(ui_field cols) / 2))")"
  ch="$(ui_field cell | cut -d, -f2)"
  dy="$(awk -v ch="$ch" -v n="$1" 'BEGIN { printf "%d", ch * n }')"
  adb shell input swipe "$x" "$y" "$x" "$((y + dy))" "$2"
}

# tap_rect <left,top,right,bottom>
tap_rect() {
  local l t r b
  IFS=, read -r l t r b <<< "$1"
  adb shell input tap $(((l + r) / 2)) $(((t + b) / 2))
}


# expect_field <field> <awk condition on v> <what>: waits up to 20 s for a
# numeric header field to satisfy the condition, e.g. expect_field cols 'v < 60' ….
expect_field() {
  local i v=""
  for i in $(seq 1 40); do
    v="$(header_field "$1")"
    if [ -n "$v" ] && awk -v v="$v" "BEGIN { exit !($2) }"; then
      log "ok: $3 ($1=$v)"
      return 0
    fi
    sleep 0.5
  done
  dump_screen | head -n1 || true
  fail "$3: $1=$v does not satisfy '$2'"
}

# The IME service's own view of whether the soft keyboard is shown.
ime_shown() { adb shell dumpsys input_method | grep -q 'mInputShown=true'; }

# expect_ime_shown <what>: the keyboard is shown and the grid shrank above it.
expect_ime_shown() {
  local i
  for i in $(seq 1 20); do
    ime_shown && break
    sleep 0.5
  done
  ime_shown || fail "$1: the soft keyboard is not shown"
  expect_field rows "v < $ROWS_FULL" "$1"
}

# full_rows: hides the soft keyboard if it is up (whatever the grid size, e.g.
# after a zoom), waits for the grid to settle and prints its row count.
full_rows() {
  local i rows prev=""
  if ime_shown; then adb shell input keyevent KEYCODE_BACK; fi
  for i in $(seq 1 20); do
    ime_shown || break
    sleep 0.5
  done
  for i in $(seq 1 20); do
    rows="$(header_field rows)"
    [ -n "$rows" ] && [ "$rows" = "$prev" ] && break
    prev="$rows"
    sleep 0.5
  done
  ime_shown && rows=""
  printf '%s' "$rows"
}

# zoom <EQUALS|MINUS>: Ctrl+Alt+= / Ctrl+Alt+- from a hardware keyboard.
zoom() { adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_ALT_LEFT "KEYCODE_$1"; }

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

# swipe_node <fixed-string> <from-x%> <to-x%>: a horizontal swipe across a node's
# vertical middle, between two fractions of its width.
swipe_node() {
  local n b
  n="$(wait_node "$1")"
  b="$(printf '%s' "$n" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')"
  set -- $b "$2" "$3"
  local y=$(((${2} + ${4}) / 2)) w=$((${3} - ${1}))
  adb shell input swipe $((${1} + w * ${5} / 100)) "$y" $((${1} + w * ${6} / 100)) "$y" 300
}

# --- system UI -----------------------------------------------------------------

app_focused() { adb shell dumpsys window | grep -E 'mCurrentFocus=' | grep -q "$PKG"; }

# expect_focus <yes|no> <what>: waits up to 10 s for the app window to (not) have focus.
expect_focus() {
  local i
  for i in $(seq 1 20); do
    if app_focused; then [ "$1" = yes ] && break; else [ "$1" = no ] && break; fi
    sleep 0.5
  done
  if app_focused; then [ "$1" = yes ] || fail "$2"; else [ "$1" = no ] || fail "$2"; fi
  log "ok: $2"
}

service_running() {
  adb shell dumpsys activity services "$PKG" | grep -q "ServiceRecord.*FerruxService"
}

# shade_node <fixed-string>: opens the notification shade and waits for a node,
# expanding our notification if its actions are folded away.
shade_node() {
  local i n
  adb shell cmd statusbar expand-notifications
  for i in $(seq 1 20); do
    n="$(node "$1")"
    if [ -n "$n" ]; then
      printf '%s' "$n"
      return 0
    fi
    # Collapsed notifications hide their actions; the expand button shows them.
    [ "$i" = 5 ] && { n="$(node 'content-desc="Expand"')"; [ -n "$n" ] && tap_bounds "$n"; }
    sleep 1
  done
  ui_dump > "$OUT/ui-missing.xml"
  fail "no notification node matching $1 (see ui-missing.xml)"
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

# show_ime: brings the soft keyboard up through the debug hook (if it is hidden)
# and waits for it to settle. Keystrokes injected while Gboard is still starting
# get reordered or lost ("echo" -> "ehco", "errux").
show_ime() {
  local rows
  rows="$(header_field rows)"
  if [ -n "$rows" ] && [ "$rows" -ge "${ROWS_FULL:-0}" ]; then
    adb shell am broadcast -n "$RECEIVER" -a dev.ferrux.debug.SHOW_IME > /dev/null
  fi
  wait_ime_settled
}

# `input text` needs %s for spaces; keep shell metacharacters out (adb shell
# passes the string through the device's sh).
#
# Even with the keyboard up, Gboard's first start can still drop or reorder a few
# injected keys. So the typed text is checked on the prompt line; if it isn't
# there, the line is erased and typed once more. A real input bug fails twice.
type_text() {
  local want="${1//%s/ }" attempt i line
  want="${want% }"
  show_ime
  for attempt in 1 2; do
    adb shell input text "$1"
    for i in $(seq 1 12); do
      line="$(grid_lines | grep -v '^$' | tail -n1)"
      case "$line" in *"$want") return 0 ;; esac
      sleep 0.25
    done
    log "typed '$want' but the line shows '$line'; retyping"
    # Erase what reached the line (after the 4-character "~ $ " prompt).
    adb shell input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq 1 $((${#line} > 4 ? ${#line} - 4 : 1))))
    sleep 0.5
  done
  fail "could not type '$want' (line: '$line')"
}

type_line() {
  type_text "$1"
  adb shell input keyevent KEYCODE_ENTER
}

# run_line <command>: types a command and waits for the next prompt, so the next
# input is not typed ahead into the running command.
run_line() {
  local i
  type_line "$1"
  for i in $(seq 1 40); do
    [ "$(grid_lines | grep -v '^$' | tail -n1)" = '~ $' ] && return 0
    sleep 0.25
  done
  fail "no prompt after '$1'"
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

log "02b: clear empties the screen, also after the keyboard closes (#30)"
# `clear` (toybox) writes ESC[2J ESC[H. With the keyboard up the grid is short;
# hiding the keyboard must not pull the cleared lines back into view.
type_line 'seq%s1%s60'
expect_screen '^60$' "60 lines printed"
run_line 'clear'
[ "$(grid_lines | head -n1)" = '~ $' ] || { grid_lines | head -n5; fail "after clear the first row is not the prompt"; }
rows_clear="$(header_field rows)"
log "ok: cleared with the keyboard up ($rows_clear rows)"
hide_ime
expect_screen "rows=$ROWS_FULL " "keyboard hidden, full grid"
first="$(grid_lines | head -n1)"
[ "$first" = '~ $' ] || { grid_lines | head -n5; fail "the cleared output came back when the keyboard closed (first row: '$first')"; }
[ -z "$(grid_lines | sed -n '2,$p' | grep -v '^$')" ] || { grid_lines | head -n5; fail "rows below the prompt are not empty"; }
log "ok: first row is the prompt, the rest is empty ($rows_clear -> $ROWS_FULL rows)"
shot 02b-clear

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
run_line 'clear'
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
# At column 0 the start handle would hang off the left edge: it flips inside (#21).
expect_ui startHandle '^[0-9]+,[0-9]+,[0-9]+,[0-9]+:mirrored$' "start handle at column 0 is fully on screen, mirrored"
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
run_line 'clear'
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

log "12: scrollback: drag, fling, scrollbar and the jump button"
run_line 'clear'
type_line 'seq%s1%s400'
expect_screen '^400$' "400 lines printed"
hide_ime
expect_ui history '^[0-9]{3,}$' "hundreds of lines of history"
expect_ui offset '^0$' "starts on the live screen"
expect_ui jumpButton '^$' "no jump button on the live screen"
drag_rows 10 1500
expect_ui offset '^([89]|1[0-2])$' "a slow 10-row drag scrolled back about 10 lines"
expect_ui scrollbar '^1$' "scrollbar shown while scrolled back"
expect_ui jumpButton '^[0-9]+,[0-9]+,[0-9]+,[0-9]+$' "jump-to-live button shown"
off1="$(ui_field offset)"
expect_screen "^$((400 - ROWS_FULL - off1 + 3))\$" "the grid shows older lines"
shot 11-scrollback
drag_rows 10 60
sleep 2 # let the fling settle
off2="$(ui_field offset)"
[ "$off2" -gt $((off1 + 15)) ] || fail "fling did not carry on: offset $off1 -> $off2"
log "ok: fling carried on after the finger lifted (offset $off1 -> $off2)"
tap_rect "$(ui_field jumpButton)"
expect_ui offset '^0$' "jump button returned to the live screen"
expect_screen '^400$' "live screen again"

log "13: on the alternate screen a swipe sends arrow keys"
adb shell "run-as $PKG sh -c 'cat > files/home/mouse.sh'" < "$HERE/ci-mouse.sh"
type_line 'sh%smouse.sh%salt'
expect_screen '^ALTSCREEN alt' "full-screen probe started"
hide_ime
drag_rows 3 1000
expect_screen '1b 5b 41 1b 5b 41' "the program received Up arrows"
expect_ui offset '^0$' "the view itself did not scroll"
expect_ui scrollbar '^0$' "no scrollbar on the alternate screen"
shot 12-altscreen-scroll
adb shell input text q
expect_screen '^ALTSCREEN done$' "probe exited"

log "14: with mouse reporting, swipes are wheel events and taps are clicks"
type_line 'sh%smouse.sh%smouse'
expect_screen '^ALTSCREEN mouse' "probe started with mouse reporting"
hide_ime
drag_rows 3 1000
expect_screen '1b 5b 3c 36 34 3b' "the program received wheel-up events (ESC[<64;)"
adb shell input tap $(cell_xy 2 4)
expect_screen '1b 5b 3c 30 3b 35 3b 33 4d 1b 5b 3c 30 3b 35 3b 33 6d' "a tap is a click at column 5, row 3 (ESC[<0;5;3M/m)"
hide_ime
shot 13-mouse
adb shell input text q
expect_screen '^ALTSCREEN done$' "probe exited"

log "15: an OSC 52 clipboard write needs the user's permission"
adb shell "run-as $PKG sh -c 'cat > files/home/osc52.sh'" < "$HERE/ci-osc52.sh"
clip_before="$(ui_field clipboard)"
type_line 'sh%sosc52.sh'
expect_screen '^osc52 sent$' "the program sent OSC 52"
hide_ime
wait_node 'wants to copy 8 characters to the clipboard' > /dev/null
log "ok: permission snackbar shown"
expect_ui clipboard "^$clip_before\$" "the clipboard is untouched until allowed"
shot 14-osc52-ask
tap_text Allow
expect_ui clipboard '^OSC52-OK$' "Allow copied the program's text"

log "16: URLs are detected; tapping a link offers Open link / Copy link"
run_line 'clear'
type_line 'echo%svisit%shttps://ferrux.dev/tap%snow'
expect_screen '^visit https://ferrux.dev/tap now$' "URL line"
hide_ime
expect_ui links '=https://ferrux\.dev/tap' "the URL is in the snapshot's links"
adb shell input tap $(word_xy '^visit https' 'https://ferrux.dev/tap')
expect_ui linkMenu '^Open link\|Copy link$' "tapping the URL opened the link menu"
expect_ui linkMenuUri '^https://ferrux\.dev/tap$' "for that URL"
sleep 1
shot 15-link-tap
tap_text 'Copy link'
expect_ui clipboard '^https://ferrux\.dev/tap$' "Copy link copied the URL"
expect_ui linkMenu '^$' "the menu closed"
type_line 'sh%slinks.sh'
expect_screen '^links ok$' "hyperlink script ran"
hide_ime
adb shell input tap $(word_xy '^Ferrux docs here$' Ferrux)
expect_ui linkMenuUri '^https://ferrux\.dev/docs$' "an OSC 8 link offers its target, not its text"
adb shell input tap $(cell_xy "$(($(ui_field rows) - 2))" 10)
expect_ui linkMenu '^$' "a tap elsewhere closed the menu"

log "17: Open link hands the URL to another app (ACTION_VIEW)"
check_crash
run_line 'clear'
type_line 'echo%sopen%shttps://ferrux.dev/open%snow'
expect_screen '^open https://ferrux.dev/open now$' "URL line"
hide_ime
adb shell input tap $(word_xy '^open https' 'https://ferrux.dev/open')
expect_ui linkMenuUri '^https://ferrux\.dev/open$' "the link menu is for that URL"
tap_text 'Open link'
# ActivityTaskManager logs every activity start; URIs are shortened to scheme://host/...
started=""
for i in $(seq 1 20); do
  started="$(adb logcat -d -v brief -s ActivityTaskManager:I | grep -E 'START u0 \{act=android\.intent\.action\.VIEW dat=https://ferrux\.dev' | tail -n1 || true)"
  [ -n "$started" ] && break
  sleep 0.5
done
[ -n "$started" ] || fail "Open link did not start an ACTION_VIEW activity for https://ferrux.dev"
log "ok: Open link fired ACTION_VIEW: $started"
expect_ui linkMenu '^$' "the menu closed"
sleep 3 # let the browser (or the app chooser) draw
top="$(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' || true)"
log "top activity after Open link: $top"
shot 16-link-open
adb shell am start -W -n "$ACTIVITY" > /dev/null
expect_screen '^open https://ferrux.dev/open now$' "back in Ferrux, same session"

log "18: switching apps keeps the session, the view, its zoom and the keyboard"
hide_ime
view0="$(header_field view)"
size0="$(header_field textSizeSp)"
cols0="$(header_field cols)"
current0="$(header_field current)"
[ "${view0:-0}" != 0 ] || fail "no terminal view reported"
zoom EQUALS
zoom EQUALS
expect_field textSizeSp "v > $size0 * 1.15" "Ctrl+Alt+= twice zoomed in"
expect_field cols "v < $cols0" "zoom resized the grid"
size1="$(header_field textSizeSp)"
ROWS_FULL="$(full_rows)" # keyboard hidden, at this zoom
[ "${ROWS_FULL:-0}" -ge 25 ] || fail "zoomed grid with the keyboard hidden has only '${ROWS_FULL}' rows"
log "zoomed grid: $ROWS_FULL rows x $(header_field cols) cols"
tap_node 'content-desc="Terminal"'
expect_ime_shown "keyboard up before leaving"
adb shell input keyevent KEYCODE_HOME
for i in $(seq 1 20); do
  adb shell dumpsys window | grep -E 'mCurrentFocus=' | grep -q "$PKG" || break
  sleep 0.5
done
adb shell dumpsys window | grep -E 'mCurrentFocus=' | grep -q "$PKG" && fail "HOME did not leave the app"
log "ok: app in the background"
sleep 2
adb shell am start -W -n "$ACTIVITY" > /dev/null
expect_screen "^sessions=2 current=$current0 " "same sessions, same current session"
expect_field view "v == $view0" "same TerminalView instance"
expect_field textSizeSp "v == $size1" "zoom kept"
expect_ime_shown "keyboard back after returning"
sleep 1
shot 17-resumed

log "19: the zoom is saved across a process restart"
hide_ime
sleep 1 # DataStore writes asynchronously; give it a moment before the kill
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" > /dev/null
expect_screen '^sessions=1 ' "fresh process, one new session"
expect_screen '^~ \$' "shell prompt visible"
expect_field view "v != $view0" "a new TerminalView"
expect_field textSizeSp "v == $size1" "saved zoom applied"
expect_field rows "v == $ROWS_FULL" "grid sized for the saved zoom"
shot 18-restarted
zoom MINUS
zoom MINUS
expect_field textSizeSp "v > $size0 - 0.02 && v < $size0 + 0.02" "Ctrl+Alt+- twice zoomed back out"
expect_field cols "v == $cols0" "grid back to its original width"
ROWS_FULL="$(full_rows)"
[ "${ROWS_FULL:-0}" -ge 30 ] || fail "grid with the keyboard hidden has only '${ROWS_FULL}' rows"

log "20: a failed shell stays open with a notice; Enter restarts it in the same directory"
type_line 'cd%s/system'
expect_screen '^/system \$' "cd /system"
type_line 'echo%sBEFORE-EXIT'
expect_screen '^BEFORE-EXIT$' "marker line"
failed="$(header_field current)"
type_line 'exit%s3'
expect_screen '^\[Process completed \(exit code 3\)' "exit notice in the grid"
expect_screen "^sessions=1 current=$failed .* exits=3 " "the failed session stays open, marked exited"
expect_screen '^BEFORE-EXIT$' "its output is still readable"
hide_ime
shot 19-exited
tap_node 'content-desc="Sessions"'
wait_node 'Exited with code 3' > /dev/null
log "ok: drawer marks the session as exited"
shot 20-drawer-exited
adb shell input keyevent KEYCODE_BACK # close the drawer
sleep 1
adb shell input keyevent KEYCODE_ENTER
expect_field current "v != $failed" "Enter started a new shell"
expect_screen '^sessions=1 .* exits=- ' "restarted in place, running"
expect_screen '^/system \$' "restarted in /system"
dump_screen | grep -q 'Process completed' && fail "the restarted session still shows the notice"
shot 21-exit-restarted

log "21: a swipe closes an exited session; a clean exit closes by default"
tap_node 'content-desc="Sessions"'
tap_node 'text="New session"'
expect_screen '^sessions=2 ' "second session"
expect_screen '^~ \$' "second session prompt"
type_line 'exit%s1'
expect_screen '^\[Process completed \(exit code 1\)' "exit notice"
hide_ime
swipe_node 'content-desc="Terminal"' 80 15
expect_screen '^sessions=1 .* exits=- ' "the swipe closed the exited session"
tap_node 'content-desc="Sessions"'
tap_node 'text="New session"'
expect_screen '^sessions=2 ' "another session"
expect_screen '^~ \$' "its prompt"
type_line 'exit'
expect_screen '^sessions=1 .* exits=- ' "exit 0 closed the session"
hide_ime

log "22: the notification: session count, tap to open, Exit closes everything"
tap_node 'content-desc="Sessions"'
tap_node 'text="New session"'
expect_screen '^sessions=2 ' "two sessions"
expect_screen '^~ \$' "its prompt"
hide_ime
current2="$(header_field current)"
adb shell input keyevent KEYCODE_HOME
expect_focus no "app in the background"
shade_node 'text="2 sessions"' > /dev/null
log "ok: notification says 2 sessions"
shade_node 'text="Exit"' > /dev/null
log "ok: notification has an Exit action"
sleep 1
shot 22-notification
tap_bounds "$(shade_node 'text="2 sessions"')"
expect_focus yes "tapping the notification opened the app"
expect_screen "^sessions=2 current=$current2 " "same sessions, same current session"
tap_bounds "$(shade_node 'text="Exit"')"
for i in $(seq 1 20); do
  service_running || break
  sleep 0.5
done
service_running && fail "the service still runs after Exit"
log "ok: Exit stopped the service"
adb shell cmd statusbar collapse
expect_focus no "Exit closed the app"
adb shell dumpsys activity activities | grep -q "ActivityRecord.*$PKG/" && fail "an Activity is still around after Exit"
log "ok: no Activity left"
adb shell dumpsys notification --noredact | grep -qE "NotificationRecord.*pkg=$PKG" && fail "the notification is still posted after Exit"
log "ok: notification removed"
adb shell am start -W -n "$ACTIVITY" > /dev/null
expect_screen '^sessions=1 ' "a fresh start after Exit"
expect_field current "v != $current2" "the old sessions are gone"

check_crash
log "all checks passed"
