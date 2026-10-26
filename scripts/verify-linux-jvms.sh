#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$PWD/crema-runner/target/linux"
mkdir -p "$out"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$out/source-tests.tar.gz" pom.xml mvnw .mvn examples crema-core/pom.xml crema-core/src crema-compiler/pom.xml crema-compiler/src crema-runner/pom.xml crema-runner/src
docker run --rm --cpus=2 --memory=1g --memory-swap=1g --pids-limit=512 \
  --mount source=gretl-crema-linux-maven,target=/maven \
  --mount "type=bind,source=$out,target=/results" \
  --mount "type=bind,source=$PWD/docker/verify-jvms.sh,target=/verify-jvms.sh,readonly" \
  "${CREMA_LINUX_IMAGE:-gretl-crema-linux:experiment}" bash /verify-jvms.sh
