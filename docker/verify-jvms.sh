#!/usr/bin/env bash
set -euo pipefail
# Untimed compatibility tests. The permission tests require a non-root user.
apt-get update > /results/test-tools-install.log 2>&1
apt-get install -y --no-install-recommends unzip curl >> /results/test-tools-install.log 2>&1
mkdir -p /work/tests
tar -xzf /results/source-tests.tar.gz -C /work/tests
chown -R nobody:nogroup /work /maven
for version in jdk25 jdk27 jdk28; do
  status=0
  su -s /bin/sh nobody -c "cd /work/tests && HOME=/work JAVA_HOME=/opt/$version ./mvnw -Dmaven.repo.local=/maven clean verify" > "/results/$version-tests.log" 2>&1 || status=$?
  echo "$status" > "/results/$version-tests.exit"
  python3 - "$version" "$status" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
totals = {key: 0 for key in ['tests', 'errors', 'failures', 'skipped']}
for path in Path('/work/tests').glob('*/target/surefire-reports/TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(root.get(key, 0))
result = {'runtime': sys.argv[1], 'exit_code': int(sys.argv[2]), 'user': 'nobody', **totals}
result['passed'] = result['exit_code'] == 0 and totals['tests'] == 204 and totals['errors'] == totals['failures'] == totals['skipped'] == 0
Path('/results/' + sys.argv[1] + '-tests.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
sys.exit(0 if result['passed'] else 1)
PY
done
