# SPDX-License-Identifier: Apache-2.0
# Hyperlink test typed into the terminal by the CI screenshot job (POSIX sh/mksh):
# an OSC 8 hyperlink and a plain URL.
e=$(printf '\033')
printf '%s]8;;https://ferrux.dev/docs%s\\Ferrux docs%s]8;;%s\\ here\n' "$e" "$e" "$e" "$e"
echo 'plain https://example.com/plain'
echo 'links ok'
