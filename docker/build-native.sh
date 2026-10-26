#!/usr/bin/env bash
# Called inside the Linux builder container. Artifacts never overwrite macOS binaries.
set -euo pipefail
variant="${1:?interpreter or ristretto}"
case "$variant" in
  interpreter) jit=-H:-GraalJITCompileAtRuntime ;;
  ristretto) jit=-H:+GraalJITCompileAtRuntime ;;
  *) exit 2 ;;
esac
mkdir -p "/native/$variant"
cd "/native/$variant"
start=$(date +%s)
/opt/graal/bin/native-image -J-Xmx6g --parallelism=4 \
  -cp '/app/runtime/runner.jar:/app/runtime/lib/*' \
  -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading "$jit" \
  -H:+AllowJRTFileSystem \
  '-H:Preserve=package=ch.so.agi.gretl.runtime.*,package=ch.so.agi.gretl.crema.*,package=example,package=ch.so.agi.gretl.tasks.database,package=java.lang,package=java.lang.invoke,package=java.io,package=java.util' \
  -H:IncludeResourceBundles=ch.so.agi.gretl.i18n.messages \
  ch.so.agi.gretl.crema.runner.Main gretl-crema 2>&1 | tee build.log
echo "{\"variant\":\"$variant\",\"build_seconds\":$(( $(date +%s) - start )),\"exit_code\":0}" > build.json
