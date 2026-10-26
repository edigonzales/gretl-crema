#!/usr/bin/env python3
"""Independent native acceptance processes. Nonzero exit if any required check fails."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--jit', action='store_true', help='Check the separate Ristretto binary')
options = parser.parse_args()
suffix = '-jit' if options.jit else ''
OUT = ROOT / ('crema-runner/target/acceptance' + suffix)
OUT.mkdir(parents=True, exist_ok=True)
RUN = [str(ROOT / ('bin/gretl-crema' + suffix))]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
JAVAC = str(Path(os.environ['JAVA_HOME']) / 'bin/javac')
JAR = str(Path(os.environ['JAVA_HOME']) / 'bin/jar')
H2 = ROOT / 'crema-runner/target/lib/h2-2.3.232.jar'
BINARY = ROOT / ('crema-runner/target/gretl-crema' + suffix)
REPORT = {'variant': 'ristretto' if options.jit else 'interpreter',
          'binary_sha256_before': hashlib.sha256(BINARY.read_bytes()).hexdigest(), 'checks': []}


def command(args, expected=0, contains=None):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if result.returncode != expected or (contains and contains not in result.stdout):
        raise AssertionError(f'Command: {args}\nExpected exit {expected}, got {result.returncode}\n{result.stdout}')
    return result.stdout


def check(name, function):
    started = time.monotonic()
    try:
        detail = function()
        item = {'name': name, 'passed': True, 'detail': detail}
    except Exception as failure:
        item = {'name': name, 'passed': False, 'detail': str(failure)}
    item['seconds'] = time.monotonic() - started
    REPORT['checks'].append(item)
    print(f"{'PASS' if item['passed'] else 'FAIL'} {name}", flush=True)
    if not item['passed']:
        print(item['detail'][:5000], flush=True)
    (OUT / 'results.json').write_text(json.dumps(REPORT, indent=2))


def compile_job(source, jar, plain=False):
    command([ROOT / 'bin/gretl-crema-compile', *(['--plain'] if plain else []), source, '--output', jar])


def run_args(jar, directory, year='2026'):
    return ['run', jar, '--job-root', directory, '--db', directory / 'db', '--param', 'jahr=' + year]


def database(directory, sql, expected):
    return command([JAVA, '-cp', str(OUT / 'classes') + os.pathsep + str(H2), 'DatabaseCheck', directory / 'db', sql, str(expected)])


classes = OUT / 'classes'
classes.mkdir(exist_ok=True)
command([JAVAC, '-d', classes, ROOT / 'tests/DatabaseCheck.java', ROOT / 'examples/probes/JavaProbe.java'])
java_jar = OUT / 'java-probe.jar'
command([JAR, '--create', '--file', java_jar, '-C', classes, 'probes'])
check('java_class_loading', lambda: command(RUN + ['probe', java_jar, 'probes.JavaProbe'], contains='JAVA_PROBE_OK 42'))

groovy_jar = OUT / 'groovy-probe.jar'
compile_job(ROOT / 'examples/probes/GroovyProbe.groovy', groovy_jar, plain=True)
check('groovy_class_and_closure', lambda: command(RUN + ['probe', groovy_jar, 'probes.GroovyProbe'], contains='GROOVY_PROBE_OK 42'))
check('missing_entry_class', lambda: command(RUN + ['probe', java_jar, 'probes.Missing'], expected=1, contains='ClassNotFoundException'))
check('invalid_cli', lambda: command(RUN + ['run', java_jar], expected=2, contains='required'))

local = OUT / 'local'
local.mkdir(exist_ok=True)
(local / 'input.txt').write_text('local-fixture')
(local / 'job.groovy').write_text('''parameter('jahr', type: 'integer', required: true, description: 'Jahr')
task('messen', FileSize) { input = file('input.txt') }
action('melden', dependsOn: 'messen') {
    if (result('messen').bytes != 13 || parameter('jahr') != 2026) throw new IllegalStateException('wrong result')
    println "GRETL_LOCAL_OK ${result('messen').bytes}"
}
defaults 'melden'
''')
local_jar = local / 'job.jar'
compile_job(local / 'job.groovy', local_jar)
check('gretl_parameter_and_predecessor', lambda: command(RUN + run_args(local_jar, local), contains='GRETL_LOCAL_OK 13'))

demo = OUT / 'demo'
demo.mkdir(exist_ok=True)
for file in ['job.groovy', 'schema.sql', 'input.txt']:
    shutil.copy2(ROOT / 'examples/demo' / file, demo / file)
demo_jar = demo / 'job.jar'
compile_job(demo / 'job.groovy', demo_jar)


def sql_job():
    output = command(RUN + run_args(demo_jar, demo), contains='job.status=SUCCEEDED')
    database(demo, f'SELECT COUNT(*) FROM messung WHERE jahr=2026 AND bytes={(demo / "input.txt").stat().st_size}', 1)
    database(demo, 'SELECT COUNT(*) FROM messung', 1)
    return output


check('gretl_sql_and_independent_jdbc', sql_job)


def equivalence():
    native = command(RUN + run_args(demo_jar, demo), contains='job.status=SUCCEEDED')
    database(demo, f'SELECT COUNT(*) FROM messung WHERE jahr=2026 AND bytes={(demo / "input.txt").stat().st_size}', 1)
    jvm = command([ROOT / 'bin/gretl-crema-jvm', *run_args(demo_jar, demo)], contains='job.status=SUCCEEDED')
    database(demo, f'SELECT COUNT(*) FROM messung WHERE jahr=2026 AND bytes={(demo / "input.txt").stat().st_size}', 1)
    def outcomes(text):
        return [line for line in text.splitlines() if line.startswith(('gretl.task.', 'SQL-Einträge:', 'job.status='))]
    assert outcomes(native) == outcomes(jvm), (native, jvm)
    return '\n'.join(outcomes(native))


check('jvm_native_equivalence', equivalence)


def sql_change():
    original = (demo / 'schema.sql').read_text()
    before = hashlib.sha256(demo_jar.read_bytes()).hexdigest()
    try:
        (demo / 'schema.sql').write_text(original + '\nINSERT INTO messung VALUES (1900,7);\n')
        output = command(RUN + run_args(demo_jar, demo), contains='job.status=SUCCEEDED')
        database(demo, 'SELECT COUNT(*) FROM messung', 2)
        database(demo, 'SELECT COUNT(*) FROM messung WHERE jahr=1900 AND bytes=7', 1)
        assert hashlib.sha256(demo_jar.read_bytes()).hexdigest() == before
        return output
    finally:
        (demo / 'schema.sql').write_text(original)


check('external_sql_without_recompile', sql_change)


def late_job():
    source = demo / 'late.groovy'
    source.write_text((demo / 'job.groovy').read_text().replace("println \"SQL-Einträge:", "println \"LATE_JOB_OK SQL-Einträge:"))
    late = demo / 'late.jar'
    compile_job(source, late)
    output = command(RUN + run_args(late, demo, '2031'), contains='LATE_JOB_OK')
    database(demo, 'SELECT COUNT(*) FROM messung WHERE jahr=2031', 1)
    return output


check('second_job_compiled_after_native_build', late_job)
check('invalid_parameter', lambda: command(RUN + run_args(demo_jar, demo, 'invalid'), expected=1, contains='jahr'))


def bad_sql(missing):
    original = (demo / 'schema.sql').read_text()
    try:
        if missing:
            (demo / 'schema.sql').unlink()
        else:
            (demo / 'schema.sql').write_text('THIS IS NOT SQL;')
        return command(RUN + run_args(demo_jar, demo), expected=1,
                       contains='NoSuchFileException' if missing else 'JdbcSQLSyntaxErrorException')
    finally:
        (demo / 'schema.sql').write_text(original)


check('missing_sql_file', lambda: bad_sql(True))
check('invalid_sql', lambda: bad_sql(False))
REPORT['binary_sha256_after'] = hashlib.sha256(BINARY.read_bytes()).hexdigest()
REPORT['binary_unchanged'] = REPORT['binary_sha256_before'] == REPORT['binary_sha256_after']
REPORT['passed'] = REPORT['binary_unchanged'] and all(item['passed'] for item in REPORT['checks'])
(OUT / 'results.json').write_text(json.dumps(REPORT, indent=2))
print(f'Report: {OUT / "results.json"}', flush=True)
sys.exit(0 if REPORT['passed'] else 1)
