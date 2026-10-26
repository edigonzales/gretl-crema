#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/toolchain.sh"
export CREMA_JVM_HOME="${CREMA_JVM_HOME:-$CREMA_ROOT/.tools/jdk-25.0.4.1+1/Contents/Home}"
exec python3 "$CREMA_ROOT/scripts/benchmark-jit.py" --with-jvm
