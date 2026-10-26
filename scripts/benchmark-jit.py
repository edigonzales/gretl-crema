#!/usr/bin/env python3
"""Paired fresh-process comparison; trace runs are separate from timed runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--with-jvm', action='store_true', help='Compare native binaries with a normal HotSpot JVM, both bytecode and source starts')
options = parser.parse_args()
OUT = ROOT / ('crema-runner/target/benchmark-jvm' if options.with_jvm else 'crema-runner/target/benchmark-jit')
OUT.mkdir(parents=True, exist_ok=True)
TARGET = ROOT / 'crema-runner/target'
BINARIES = {'interpreter': TARGET / 'gretl-crema', 'ristretto': TARGET / 'gretl-crema-jit'}
MODES = {
    'interpreter': (BINARIES['interpreter'], []),
    'ristretto': (BINARIES['ristretto'], []),
    'ristretto_jit_disabled': (BINARIES['ristretto'], ['-XX:-JITEnableCompilation']),
}
JVM = None
if options.with_jvm:
    jvm_home = os.environ.get('CREMA_JVM_HOME')
    if not jvm_home or not (Path(jvm_home) / 'bin/java').is_file():
        parser.error('Run scripts/setup-benchmark-jvm.sh or set CREMA_JVM_HOME to the reference HotSpot JDK')
    JVM = Path(jvm_home) / 'bin/java'
    MODES.pop('ristretto_jit_disabled')
    MODES['jvm_bytecode'] = (JVM, [])
    MODES['jvm_source'] = (JVM, [])
REPORT = {'warmups_per_mode': 3, 'measured_processes_per_mode': 10,
          'timing': 'fresh process creation to exit, including job execution; warm filesystem caches; direct binaries, no shell',
          'order': 'paired rounds, rotated mode order each round',
          'binaries': {}, 'workloads': {}}
if JVM is not None:
    version = subprocess.run([str(JVM), '-XX:+PrintFlagsFinal', '-version'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    if re.search(r'UseJVMCICompiler\s*=\s*true', version):
        parser.error('The reference JVM has the Graal/JVMCI compiler enabled; use the Temurin reference JDK for the normal HotSpot comparison')
    settings = [line.strip() for line in version.splitlines() if any(term in line for term in
        ['UseJVMCICompiler ', 'UseCompiler ', 'TieredCompilation ', 'version "', 'Runtime Environment', 'Server VM'])]
    REPORT['jvm'] = {'java': str(JVM), 'settings': settings, 'flags': [],
                     'startup_included': True, 'source_compilation_included_in_source_mode': True}
    REPORT['runtime_jar_sha256'] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in [TARGET / 'crema-runner-0.1.0-SNAPSHOT.jar', ROOT / 'crema-compiler/target/crema-compiler-0.1.0-SNAPSHOT.jar', *sorted((TARGET / 'lib').glob('*.jar'))]
    }
for name, binary in BINARIES.items():
    REPORT['binaries'][name] = {'bytes': binary.stat().st_size,
        'sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
        'build': json.loads((TARGET / ('native-build-jit.json' if name == 'ristretto' else 'native-build.json')).read_text())}


def save():
    (OUT / 'results.json').write_text(json.dumps(REPORT, indent=2) + '\n')


def execute(args, marker):
    started = time.perf_counter()
    result = subprocess.run(list(map(str, args)), cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
    elapsed = time.perf_counter() - started
    if result.returncode or marker not in result.stdout or 'job.status=SUCCEEDED' not in result.stdout:
        raise RuntimeError(f'Exit {result.returncode}, expected {marker}: {args}\n{result.stdout[:10000]}')
    return elapsed, result.stdout


def arguments(mode, workload, extra=()):
    binary, flags = MODES[mode]
    if mode.startswith('jvm_'):
        cp = str(TARGET / 'crema-runner-0.1.0-SNAPSHOT.jar') + os.pathsep + str(TARGET / 'lib/*')
        main = 'ch.so.agi.gretl.crema.runner.Main'
        entry = workload['jar']
        if mode == 'jvm_source':
            cp = str(ROOT / 'crema-compiler/target/crema-compiler-0.1.0-SNAPSHOT.jar') + os.pathsep + cp
            main = 'ch.so.agi.gretl.crema.compiler.SourceMain'
            entry = workload['source']
        return [binary, *flags, '-cp', cp, main, 'run', entry,
                '--job-root', workload['directory'], '--db', workload['directory'] / 'db', '--param', workload['parameter']]
    return [binary, '-Djava.home=' + os.environ['JAVA_HOME'], *flags, *extra,
            'run', workload['jar'], '--job-root', workload['directory'], '--db', workload['directory'] / 'db',
            '--param', workload['parameter']]


demo = OUT / 'demo'
demo.mkdir(exist_ok=True)
for name in ['job.groovy', 'schema.sql', 'input.txt']:
    shutil.copy2(ROOT / 'examples/demo' / name, demo / name)
hot = OUT / 'hotloop'
hot.mkdir(exist_ok=True)
shutil.copy2(ROOT / 'examples/jit/hotloop.groovy', hot / 'hotloop.groovy')
state = 17
iterations = 100000
for _ in range(iterations):
    state = (state * 1664525 + 1013904223) & 2147483647
workloads = {
    'h2_demo': {'directory': demo, 'source': demo / 'job.groovy', 'jar': demo / 'job.jar',
                'parameter': 'jahr=2026', 'marker': 'SQL-Einträge: 1'},
    'dynamic_groovy_hotloop': {'directory': hot, 'source': hot / 'hotloop.groovy', 'jar': hot / 'hotloop.jar',
                'parameter': f'iterations={iterations}', 'marker': f'HOTLOOP_OK {state}'},
}

for name, workload in workloads.items():
    subprocess.run([str(ROOT / 'bin/gretl-crema-compile'), str(workload['source']), '--output', str(workload['jar'])], cwd=ROOT, check=True)
    item = {'parameter': workload['parameter'], 'expected_output': workload['marker'],
            'job_jar_sha256': hashlib.sha256(workload['jar'].read_bytes()).hexdigest(), 'modes': {}}
    REPORT['workloads'][name] = item
    samples = {mode: [] for mode in MODES}
    try:
        names = list(MODES)
        for round_number in range(13):
            order = names[round_number % len(names):] + names[:round_number % len(names)]
            for mode in order:
                elapsed, _ = execute(arguments(mode, workload), workload['marker'])
                if round_number >= 3:
                    samples[mode].append(elapsed)
            print(f'{name}: round {round_number + 1}/13 completed', flush=True)
        for mode, values in samples.items():
            item['modes'][mode] = {'seconds': values, 'median_seconds': statistics.median(values),
                                   'min_seconds': min(values), 'max_seconds': max(values)}
            print(f'{name} {mode}: median={statistics.median(values):.4f}s range={min(values):.4f}..{max(values):.4f}s', flush=True)
        save()
        # Diagnostics are not mixed into measured samples. Record the actual compiler activity.
        _, trace = execute(arguments('ristretto', workload, ['-XX:+JITTraceCompilation', '-XX:+JITTraceCodeInstallations', '-XX:+JITPrintExceptions']), workload['marker'])
        (OUT / (name + '-jit-trace.log')).write_text(trace)
        item['trace_log'] = name + '-jit-trace.log'
        installations = [line for line in trace.splitlines() if line.startswith('[Ristretto CodeMap]')]
        item['compiler_activity'] = {
            'finished_compilations': sum(line.startswith('[Ristretto Compiler] Finished compilation') for line in trace.splitlines()),
            'code_installations': len(installations),
            'installed_code_bytes': sum(int(re.search(r' size=(\d+)', line).group(1)) for line in installations),
            'osr_installations': sum('entryBCI=-1 ' not in line for line in installations),
            'job_closure_installations': [line for line in installations if 'hotloop$_run_closure' in line],
        }
        if name == 'dynamic_groovy_hotloop' and not item['compiler_activity']['job_closure_installations']:
            raise RuntimeError('Hotloop returned the correct result but no JIT installation for the job closure was observed')
        item['passed'] = True
    except Exception as failure:
        item['passed'] = False
        item['error'] = str(failure)
        item['partial_samples'] = samples
        print(f'{name}: FAILED: {str(failure)[:3000]}', flush=True)
    save()

REPORT['binary_hashes_unchanged'] = all(hashlib.sha256(binary.read_bytes()).hexdigest() == REPORT['binaries'][name]['sha256'] for name, binary in BINARIES.items())
REPORT['passed'] = REPORT['binary_hashes_unchanged'] and all(item['passed'] for item in REPORT['workloads'].values())
save()
print(f'Report: {OUT / "results.json"}', flush=True)
sys.exit(0 if REPORT['passed'] else 1)
