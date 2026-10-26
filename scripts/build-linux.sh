#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
case "$(docker info --format '{{.Architecture}}')" in
  aarch64|arm64) ;;
  *) echo 'These pinned runtimes qualify Linux ARM64 only' >&2; exit 1 ;;
esac
python3 scripts/setup-linux-runtimes.py
[[ -f crema-runner/target/lib/groovy-6.0.0.jar ]] || { echo 'Build the Maven artifacts with ./mvnw verify first' >&2; exit 1; }
docker build --target native-builder -f docker/Dockerfile -t gretl-crema-linux-builder:experiment .
docker volume create gretl-crema-linux-artifacts >/dev/null
docker volume create gretl-crema-linux-aot >/dev/null
docker volume create gretl-crema-linux-checkpoints >/dev/null
docker run --rm --cpus=4 --memory=8g --memory-swap=8g \
  --mount source=gretl-crema-linux-artifacts,target=/native \
  gretl-crema-linux-builder:experiment bash -c \
  'bash /app/bench/build-native.sh interpreter && bash /app/bench/build-native.sh ristretto'
docker build --target runtime -f docker/Dockerfile -t gretl-crema-linux:experiment .
