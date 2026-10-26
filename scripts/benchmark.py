#!/usr/bin/env python3
"""Three warmups + ten fresh processes per mode; failures stop the measurement."""
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'crema-runner/target/benchmark'
OUT.mkdir(parents=True, exist_ok=True)
demo = OUT / 'demo'
demo.mkdir(exist_ok=True)
for name in ['job.groovy', 'schema.sql', 'input.txt']:
    shutil.copy2(ROOT / 'examples/demo' / name, demo / name)
jar = demo / 'job.jar'


def execute(args):
    start = time.perf_counter()
    completed = subprocess.run(list(map(str, args)), cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    elapsed = time.perf_counter() - start
    if completed.returncode:
        raise RuntimeError(f'Benchmark failed: {args}\n{completed.stdout}')
    return elapsed


execute([ROOT / 'bin/gretl-crema-compile', demo / 'job.groovy', '--output', jar])
common = ['--job-root', demo, '--db', demo / 'db', '--param', 'jahr=2026']
cp = str(ROOT / 'crema-runner/target/crema-runner-0.1.0-SNAPSHOT.jar') + os.pathsep + str(ROOT / 'crema-runner/target/lib/*')
compiler_cp = str(ROOT / 'crema-compiler/target/crema-compiler-0.1.0-SNAPSHOT.jar') + os.pathsep + cp
java = Path(os.environ['JAVA_HOME']) / 'bin/java'
binary = ROOT / 'crema-runner/target/gretl-crema'
# Measure direct processes, excluding shell/toolchain discovery overhead.
modes = {
    'jvm_source': [java, '-cp', compiler_cp, 'ch.so.agi.gretl.crema.compiler.SourceMain', 'run', demo / 'job.groovy', *common],
    'jvm_bytecode': [java, '-cp', cp, 'ch.so.agi.gretl.crema.runner.Main', 'run', jar, *common],
    'crema_interpreter': [binary, '-Djava.home=' + os.environ['JAVA_HOME'], 'run', jar, *common],
}
report = {'warmups': 3, 'measured_processes': 10, 'timing': 'process creation to exit, including task execution; filesystem caches warm',
          'binary_bytes': binary.stat().st_size, 'native_build': json.loads((ROOT / 'crema-runner/target/native-build.json').read_text()), 'modes': {}}
for mode, args in modes.items():
    try:
        for _ in range(3):
            execute(args)
        values = [execute(args) for _ in range(10)]
        report['modes'][mode] = {'seconds': values, 'median_seconds': statistics.median(values), 'min_seconds': min(values), 'max_seconds': max(values)}
        print(f'{mode}: median={statistics.median(values):.4f}s range={min(values):.4f}..{max(values):.4f}s', flush=True)
    except Exception as failure:
        report['modes'][mode] = {'error': str(failure)}
        print(f'{mode}: FAILED: {str(failure)[:1500]}', flush=True)
    (OUT / 'results.json').write_text(json.dumps(report, indent=2))
report['passed'] = all('error' not in result for result in report['modes'].values())
(OUT / 'results.json').write_text(json.dumps(report, indent=2))
print(f'Report: {OUT / "results.json"}')
sys.exit(0 if report['passed'] else 1)
