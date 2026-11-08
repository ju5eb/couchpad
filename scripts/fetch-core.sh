#!/usr/bin/env bash
# Downloads the Snes9x libretro core into jniLibs for the given ABI (default armeabi-v7a).
set -euo pipefail

abi="${1:-armeabi-v7a}"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/jniLibs/$abi"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -sSfL -o "$tmp/core.zip" \
  "https://buildbot.libretro.com/nightly/android/latest/$abi/snes9x_libretro_android.so.zip"
unzip -q "$tmp/core.zip" -d "$tmp"
mkdir -p "$dest"
cp "$tmp/snes9x_libretro_android.so" "$dest/libsnes9x_libretro_android.so"
echo "Core installed in $dest"
