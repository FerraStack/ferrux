# SPDX-License-Identifier: Apache-2.0
# OSC 52 clipboard write typed into the terminal by the CI screenshot job (POSIX sh,
# toybox base64): the app must ask before it touches the clipboard.
printf '\033]52;c;%s\007' "$(printf 'OSC52-OK' | base64)"
echo 'osc52 sent'
