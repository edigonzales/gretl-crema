#!/usr/bin/env bash
# Local, pinned reference JVM. Does not change JAVA_HOME or SDKMAN defaults.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
archive="$root/.tools/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.4.1_1.tar.gz"
jdk="$root/.tools/jdk-25.0.4.1+1/Contents/Home"
checksum=61979887f7506a24a57439ff99adb8b3a7fc89977d9cfe3b8984f58a981b7b9d
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo 'This reference download is for macOS ARM64' >&2
  exit 1
fi
mkdir -p "$root/.tools"
if [[ ! -x "$jdk/bin/java" ]]; then
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 2 --output "$archive.part" \
      'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.4.1_1.tar.gz'
    mv "$archive.part" "$archive"
  fi
  printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check
  tar -xzf "$archive" -C "$root/.tools"
fi
"$jdk/bin/java" -version
echo "CREMA_JVM_HOME=$jdk"
