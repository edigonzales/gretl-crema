#!/usr/bin/env python3
"""Functional acceptance outside timings, always restoring the same read-only snapshot."""
import json
from pathlib import Path
import shutil
import subprocess
from generic_artifacts import checkpoint_identity
from measure import CP, HEAP

identity = checkpoint_identity()
build = json.loads(Path('/post-jobs/identity.json').read_text())
assert build['checkpoint']['sha256'] == identity['sha256']
assert all(job['compiled_after_checkpoint'] for job in build['jobs'])
base = ['/opt/crac25/bin/java', '-XX:CRaCEngine=warp', '-XX:CRaCRestoreFrom=/generic-checkpoint/runtime',
        'ch.so.agi.gretl.crema.runner.Main']
checks = []

def run(name, arguments, expected=0, marker=None):
    result = subprocess.run(base + arguments, capture_output=True, text=True, timeout=60)
    output = result.stdout + result.stderr
    passed = result.returncode == expected and 'Restore successful!' in output and (marker is None or marker in output)
    checks.append({'name': name, 'passed': passed, 'exit_code': result.returncode, 'output': output})
    assert passed, (name, output)

def h2(name, year, root='/post-jobs/h2', expected=0, marker='job.status=SUCCEEDED'):
    database = '/work/' + name
    run(name, ['run', '/post-jobs/h2/job.jar', '--job-root', root, '--db', database, '--param', 'jahr=' + str(year)], expected, marker)
    return database

def jdbc(name, database, query):
    result = subprocess.run(['/opt/jdk25/bin/java', *HEAP, '-cp', '/app/support/classes:' + CP,
                             'DatabaseCheck', database, query, '1'], capture_output=True, text=True, timeout=60)
    checks.append({'name': name, 'passed': result.returncode == 0, 'output': result.stdout + result.stderr})
    assert result.returncode == 0, checks[-1]

for year in [2037, 2038]:
    database = h2('year-' + str(year), year)
    jdbc('jdbc-' + str(year), database,
         f'SELECT CASE WHEN COUNT(*)=1 AND MIN(JAHR)={year} AND MIN(BYTES)=17 THEN 1 ELSE 0 END FROM MESSUNG')
for value in [6, 13]:
    run('alternate-' + str(value), ['run', '/post-jobs/alternate/job.jar', '--job-root', '/post-jobs/alternate',
        '--db', '/work/unused', '--param', 'wert=' + str(value)], marker='POST_CHECKPOINT_OK ' + str(value * 7))
state = 17
for _ in range(1000):
    state = (state * 1664525 + 1013904223) & 2147483647
run('hotloop-new-parameter', ['run', '/post-jobs/hotloop/hotloop.jar', '--job-root', '/post-jobs/hotloop',
    '--db', '/work/unused', '--param', 'iterations=1000'], marker='HOTLOOP_OK ' + str(state))
h2('invalid-parameter', 'invalid', expected=1, marker='ParameterException')
run('missing-jar', ['run', '/post-jobs/missing.jar', '--job-root', '/post-jobs', '--db', '/work/unused'], 1, 'NoSuchFileException')
run('invalid-usage', ['run'], 2, '--help')
root = Path('/work/external-sql')
shutil.copytree('/post-jobs/h2', root)
sql = root / 'schema.sql'
original = sql.read_text()
sql.write_text(original + '\nINSERT INTO messung(jahr, bytes) VALUES (1999, -1);\n')
database = h2('external-sql', 2040, str(root))
jdbc('jdbc-external-sql', database,
     'SELECT CASE WHEN COUNT(*)=2 AND SUM(CASE WHEN JAHR=2040 AND BYTES=17 THEN 1 ELSE 0 END)=1 THEN 1 ELSE 0 END FROM MESSUNG')
sql.unlink()
h2('missing-sql', 2040, str(root), 1, 'Task schema:')
sql.write_text('THIS IS INVALID SQL;\n')
h2('invalid-sql', 2040, str(root), 1, 'Task schema:')
after = checkpoint_identity()
assert identity['sha256'] == after['sha256']
print(json.dumps({'passed': all(check['passed'] for check in checks), 'checks': checks,
                  'checkpoint': identity, 'checkpoint_unchanged': True, 'job_build': build}, ensure_ascii=False))
