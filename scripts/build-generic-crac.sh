#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -f docker/Dockerfile.crac-generic -t gretl-crema-linux:generic .
docker volume create gretl-crema-linux-generic-checkpoint >/dev/null
docker volume create gretl-crema-linux-generic-jobs >/dev/null
python3 scripts/benchmark-linux.py prepare --suite generic
python3 scripts/benchmark-linux.py compile --suite generic
python3 scripts/benchmark-linux.py check --suite generic
