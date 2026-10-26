#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/toolchain.sh"
exec python3 "$CREMA_ROOT/scripts/benchmark.py"
