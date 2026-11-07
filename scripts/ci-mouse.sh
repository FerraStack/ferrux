# SPDX-License-Identifier: Apache-2.0
# Full-screen probe typed into the terminal by the CI screenshot job (POSIX sh/mksh
# with toybox stty/dd/od): switches to the alternate screen, optionally enables SGR
# mouse reporting, and prints every input byte in hex until it reads `q`.
#
#   sh mouse.sh alt     alternate screen only (scrolling should send arrow keys)
#   sh mouse.sh mouse   alternate screen + DECSET 1000/1006 (wheel events, clicks)
mode=${1:-alt}
e=$(printf '\033')
printf '%s[?1049h%s[H' "$e" "$e"
[ "$mode" = mouse ] && printf '%s[?1000h%s[?1006h' "$e" "$e"
printf 'ALTSCREEN %s - q quits\r\n' "$mode"
saved=$(stty -g)
stty raw -echo
while :; do
  b=$(dd bs=1 count=1 2> /dev/null | od -An -tx1 | tr -d ' \n')
  [ "$b" = 71 ] && break # q
  printf '%s ' "$b"
done
stty "$saved"
printf '%s[?1000l%s[?1006l%s[?1049l' "$e" "$e" "$e"
echo "ALTSCREEN done"
