#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/toolchain.sh"
crema_check_toolchain
cd "$CREMA_ROOT"
variant=interpreter
suffix=""
jit_option=-H:-GraalJITCompileAtRuntime
case "${1:-}" in
  "") [[ $# == 0 ]] || { echo 'Usage: build-native.sh [--jit]' >&2; exit 2; } ;;
  --jit) [[ $# == 1 ]] || { echo 'Usage: build-native.sh [--jit]' >&2; exit 2; }
    variant=ristretto; suffix=-jit; jit_option=-H:+GraalJITCompileAtRuntime ;;
  *) echo 'Usage: build-native.sh [--jit]' >&2; exit 2 ;;
esac
mkdir -p crema-runner/target
"$JAVA_HOME/bin/native-image" --expert-options-all > crema-runner/target/native-options.txt
if ! grep -q 'RuntimeClassLoading' crema-runner/target/native-options.txt; then
  echo 'This GraalVM does not provide Crema' >&2
  exit 1
fi
if ! grep -q 'GraalJITCompileAtRuntime' crema-runner/target/native-options.txt; then
  echo 'This GraalVM does not provide Ristretto' >&2
  exit 1
fi
[[ -f crema-runner/target/lib/groovy-6.0.0.jar ]] || { echo 'Run ./mvnw verify first' >&2; exit 1; }
start=$(date +%s)
set +e
"$JAVA_HOME/bin/native-image" \
  -cp "$CREMA_CP" \
  -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading "$jit_option" \
  -H:+AllowJRTFileSystem \
  '-H:Preserve=package=ch.so.agi.gretl.runtime.*,package=ch.so.agi.gretl.crema.*,package=example,package=ch.so.agi.gretl.tasks.database,package=java.lang,package=java.lang.invoke,package=java.io,package=java.util' \
  -H:IncludeResourceBundles=ch.so.agi.gretl.i18n.messages \
  ch.so.agi.gretl.crema.runner.Main "crema-runner/target/gretl-crema$suffix" \
  2>&1 | tee "crema-runner/target/native-build$suffix.log"
status=${PIPESTATUS[0]}
set -e
echo "{\"variant\":\"$variant\",\"build_seconds\":$(( $(date +%s) - start )),\"exit_code\":$status}" > "crema-runner/target/native-build$suffix.json"
exit "$status"
