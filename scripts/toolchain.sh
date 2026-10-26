#!/usr/bin/env bash
# Source from repository scripts. No global SDKMAN/default-JDK changes.
set -euo pipefail
CREMA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:-$HOME/.sdkman/candidates/java/25.4.4+1-graal}"
if [[ ! -x "$GRAALVM_HOME/bin/native-image" ]]; then
  echo "Set GRAALVM_HOME to Oracle GraalVM 25.4.4.1.1 with native-image" >&2
  exit 1
fi
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo "This experiment is qualified only for macOS ARM64" >&2
  exit 1
fi
export GRAALVM_HOME JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
CREMA_CP="$CREMA_ROOT/crema-runner/target/crema-runner-0.1.0-SNAPSHOT.jar:$CREMA_ROOT/crema-runner/target/lib/*"
CREMA_COMPILER_CP="$CREMA_ROOT/crema-compiler/target/crema-compiler-0.1.0-SNAPSHOT.jar:$CREMA_CP"

# Build-time qualification only. Native launches must not start a JVM for a version check.
crema_check_toolchain() {
  local crema_version
  crema_version="$("$GRAALVM_HOME/bin/java" -version 2>&1)"
  if [[ "$crema_version" != *"Oracle GraalVM 25.4.4.1.1"* ]]; then
    echo "Unqualified GraalVM: $crema_version" >&2
    return 1
  fi
}
