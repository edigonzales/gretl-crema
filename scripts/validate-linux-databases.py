#!/usr/bin/env python3
"""Check smoke-test databases in an independent JDBC process, outside timings."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--suite', choices=['original', 'generic'], default='original')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = root / ('crema-runner/target/linux-generic' if args.suite == 'generic' else 'crema-runner/target/linux')
smoke = json.loads((out / 'smoke.json').read_text())
assert smoke['passed']
files = [out / 'db-checks' / (mode + '-h2.mv.db') for mode in smoke['workloads']['h2']['runs']]
image = 'gretl-crema-linux:generic' if args.suite == 'generic' else 'gretl-crema-linux:experiment'
checks = []
for file in files:
    result = subprocess.run([
        'docker', 'run', '--rm', '--cpus=2', '--memory=1g', '--memory-swap=1g', '--network=none',
        '--mount', 'type=bind,source=' + str(file.parent) + ',target=/checks',
        image, '/opt/jdk25/bin/java', '-Xms32m', '-Xmx512m',
        '-cp', '/app/support/classes:/app/runtime/lib/*', 'DatabaseCheck', '/checks/' + file.stem[:-3],
        'SELECT CASE WHEN COUNT(*)=1 AND MIN(JAHR)=2026 AND MIN(BYTES)=17 THEN 1 ELSE 0 END FROM MESSUNG', '1'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    checks.append({'file': file.name, 'passed': result.returncode == 0 and 'DATABASE_CHECK_OK' in result.stdout,
                   'output': result.stdout})
report = {'passed': bool(checks) and all(item['passed'] for item in checks), 'databases': checks}
(out / 'database-validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'databases': len(checks)}))
raise SystemExit(0 if report['passed'] else 1)
