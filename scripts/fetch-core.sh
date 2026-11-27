#!/usr/bin/env bash
# Downloads the libretro cores Couchpad uses into jniLibs.
#   scripts/fetch-core.sh                 # every core, armeabi-v7a and arm64-v8a
#   scripts/fetch-core.sh armeabi-v7a     # every core, one ABI
#   scripts/fetch-core.sh arm64-v8a mgba  # one core, one ABI
set -euo pipefail

CORES=(snes9x fceumm genesis_plus_gx mgba)   # SNES, NES, Genesis, GBA/GB/GBC — see domain/Console.kt
abis=("${1:-armeabi-v7a}")
[ $# -eq 0 ] && abis=(armeabi-v7a arm64-v8a)
cores=("${@:2}")
[ ${#cores[@]} -eq 0 ] && cores=("${CORES[@]}")

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for abi in "${abis[@]}"; do
  dest="$root/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  for core in "${cores[@]}"; do
    curl -sSfL -o "$tmp/$core.zip" \
      "https://buildbot.libretro.com/nightly/android/latest/$abi/${core}_libretro_android.so.zip"
    unzip -q -o "$tmp/$core.zip" -d "$tmp"
    cp "$tmp/${core}_libretro_android.so" "$dest/lib${core}_libretro_android.so"
    echo "$abi: $core"
  done
done
