#!/bin/sh
# Regenerates the launcher icon from the generator: SVG masters, VectorDrawables,
# store PNG and preview sheet. Needs python3 and rsvg-convert (librsvg).
# usage (from the repo root): branding/icon/tools/build.sh <ferrux|ferride|lomio>
set -eu
export PYTHONDONTWRITEBYTECODE=1
APP=$1
TOOLS=$(cd "$(dirname "$0")" && pwd)
ICON=$TOOLS/..
RES=${RES:-$ICON/../../android/app/src/main/res}
python3 "$TOOLS/gen.py" "$APP" "$ICON"
for layer in background foreground monochrome; do
  python3 "$TOOLS/svg2vd.py" "$ICON/ic_launcher_$layer.svg" "$RES/drawable/ic_launcher_$layer.xml" \
    "Generated from branding/icon/ic_launcher_$layer.svg by branding/icon/tools/build.sh; edit the generator, not this file."
done
rsvg-convert -w 512 -h 512 "$ICON/icon.svg" -o "$ICON/icon-512.png"
tmp=$(mktemp -d)
python3 "$TOOLS/preview.py" "$APP" "$tmp/preview.svg"
rsvg-convert "$tmp/preview.svg" -o "$ICON/preview.png"
rm -rf "$tmp"
