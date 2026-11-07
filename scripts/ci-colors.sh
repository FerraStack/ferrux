# SPDX-License-Identifier: Apache-2.0
# Colour test typed into the terminal by the CI screenshot job (POSIX sh/mksh).
e=$(printf '\033')
printf 'ANSI  '
i=0
while [ $i -lt 8 ]; do printf '%s[4%sm  %s[0m' "$e" $i "$e"; i=$((i + 1)); done
printf '\n      '
i=0
while [ $i -lt 8 ]; do printf '%s[10%sm  %s[0m' "$e" $i "$e"; i=$((i + 1)); done
printf '\n256   '
i=16
while [ $i -lt 232 ]; do
  printf '%s[48;5;%sm %s[0m' "$e" $i "$e"
  i=$((i + 1))
  [ $(( (i - 16) % 36 )) -eq 0 ] && printf '\n      '
done
printf '\ngray  '
i=232
while [ $i -lt 256 ]; do printf '%s[48;5;%sm %s[0m' "$e" $i "$e"; i=$((i + 1)); done
printf '\ntrue  '
i=0
while [ $i -lt 36 ]; do
  r=$((255 - i * 7)); g=$((i * 7)); b=$((128 + i * 3))
  printf '%s[48;2;%s;%s;%sm %s[0m' "$e" $r $g $b "$e"
  i=$((i + 1))
done
printf '\n%s[1mbold%s[0m %s[3mitalic%s[0m %s[4munderline%s[0m %s[9mstrike%s[0m %s[2mdim%s[0m %s[7minverse%s[0m\n' \
  "$e" "$e" "$e" "$e" "$e" "$e" "$e" "$e" "$e" "$e" "$e" "$e"
printf '%s[31mred %s[32mgreen %s[33myellow %s[34mblue %s[35mmagenta %s[36mcyan%s[0m é ü ñ → λ ✓\n' \
  "$e" "$e" "$e" "$e" "$e" "$e" "$e"
echo 'truecolor ok'
