#!/bin/bash
# Renders every app icon and site icon from app/tool/app_icon.svg (needs rsvg-convert: brew install librsvg).
# Each size is rendered from the vector, not scaled down from the 1024 px PNG, then its alpha channel is
# dropped (flatten_png.swift). Run from anywhere:
#   app/tool/make_icons.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
SVG=app/tool/app_icon.svg
render() { rsvg-convert -w "$1" -h "$1" "$SVG" -o "$2"; }
out=()

# iOS: every file AppIcon.appiconset/Contents.json names, at size × scale.
icons=app/ios/Runner/Assets.xcassets/AppIcon.appiconset
while read -r size scale file; do
	px=$(echo "$size * $scale" | bc); px=${px%.*}
	render "$px" "$icons/$file"; out+=("$icons/$file")
done < <(python3 -c '
import json, sys
for i in json.load(open(sys.argv[1]))["images"]:
    print(i["size"].split("x")[0], i["scale"].rstrip("x"), i["filename"])' "$icons/Contents.json")

# iOS launch screen: the icon at 160 pt, on the storyboard's background of the same colour.
launch=app/ios/Runner/Assets.xcassets/LaunchImage.imageset
render 160 "$launch/LaunchImage.png"; render 320 "$launch/LaunchImage@2x.png"; render 480 "$launch/LaunchImage@3x.png"
out+=("$launch"/LaunchImage*.png)

# Android launcher icons.
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
	f=app/android/app/src/main/res/mipmap-${d%%:*}/ic_launcher.png
	render "${d##*:}" "$f"; out+=("$f")
done

# Site: apple-touch-icon (180 px, iOS rounds it) and favicon.ico (one 32 px PNG inside an ICO header).
render 180 deploy/site/apple-touch-icon.png; out+=(deploy/site/apple-touch-icon.png)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
render 32 "$tmp/favicon.png"; out+=("$tmp/favicon.png")

swift app/tool/flatten_png.swift "${out[@]}"
python3 - "$tmp/favicon.png" deploy/site/favicon.ico <<'PY'
import struct, sys
png = open(sys.argv[1], 'rb').read()
open(sys.argv[2], 'wb').write(struct.pack('<HHH', 0, 1, 1) + struct.pack('<BBBBHHII', 32, 32, 0, 0, 1, 32, len(png), 22) + png)
PY
echo "rendered ${#out[@]} PNGs and deploy/site/favicon.ico from $SVG"
