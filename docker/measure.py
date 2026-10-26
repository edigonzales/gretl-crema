#!/usr/bin/env python3
"""One workload process inside one fresh cgroup; no polling-based peak estimates."""
import json
from pathlib import Path
import resource
import subprocess
import sys
import time

ROOT = Path('/app')
LIBS = sorted((ROOT / 'runtime/lib').glob('*.jar'))
CP = ':'.join(map(str, [ROOT / 'runtime/runner.jar', *LIBS]))
SOURCE_CP = '/app/compiler/compiler.jar:' + CP
FULL_CP = '/app/support/classes:' + SOURCE_CP
HEAP = ['-Xms32m', '-Xmx512m']


def command(mode, job):
    source = mode.endswith('_source')
    root = '/app/jobs/' + job
    stem = 'job' if job == 'h2' else 'hotloop'
    args = ['run', root + '/' + stem + ('.groovy' if source else '.jar'), '--job-root', root,
            '--db', '/work/measured-db', '--param', 'jahr=2026' if job == 'h2' else 'iterations=100000']
    if mode in ('interpreter', 'ristretto'):
        return ['/native/' + mode + '/gretl-crema', '-Djava.home=/opt/graal', *HEAP, *args]
    if mode.startswith('crac_restore_'):
        return ['/opt/crac25/bin/java', '-XX:CRaCEngine=warp', '-XX:CRaCRestoreFrom=/checkpoint/' + job + ('-source' if source else '-bytecode')]
    if mode.startswith('crac_cold_'):
        return ['/opt/crac25/bin/java', *HEAP, '-cp', FULL_CP, 'bench.CracLauncher', 'source' if source else 'bytecode', job]
    home = '/opt/jdk25' if mode.startswith('jdk25_') else '/opt/jdk28' if mode.startswith('jdk28_') else '/opt/jdk27'
    flags = []
    if '_aot' in mode or '_eager' in mode:
        suffix = '-serial' if '_aotserial' in mode else '-eager' if '_eager' in mode else '-jdk28' if mode.startswith('jdk28_') else ''
        flags = ['-XX:AOTMode=required', '-XX:AOTCache=/aot/' + job + ('-source' if source else '-bytecode') + suffix + '.aot']
        if '_aotserial' in mode:
            flags += ['-XX:+UseSerialGC']
        if '_eager' in mode:
            flags += ['-XX:+UnlockExperimentalVMOptions', '-XX:+AOTCompileEagerly']
    return [home + '/bin/java', *HEAP, *flags, '-cp', SOURCE_CP if source else CP,
            'ch.so.agi.gretl.crema.compiler.SourceMain' if source else 'ch.so.agi.gretl.crema.runner.Main', *args]


def read_number(path):
    try:
        return int(Path(path).read_text().strip())
    except (FileNotFoundError, ValueError):
        return None


if __name__ == '__main__':
    mode, job = sys.argv[1:]
    args = command(mode, job)
    started = time.perf_counter()
    process = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
    elapsed = time.perf_counter() - started
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    marker = 'SQL-Einträge: 1' if job == 'h2' else 'HOTLOOP_OK 326690737'
    result = {'mode': mode, 'job': job, 'command': args, 'process_seconds': elapsed,
              'user_cpu_seconds': usage.ru_utime, 'system_cpu_seconds': usage.ru_stime,
              'peak_rss_bytes': usage.ru_maxrss * 1024,
              'cgroup_memory_peak_bytes': read_number('/sys/fs/cgroup/memory.peak'),
              'cgroup_memory_limit_bytes': read_number('/sys/fs/cgroup/memory.max'),
              'cgroup_cpu_max': Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
              'cgroup_memory_events': Path('/sys/fs/cgroup/memory.events').read_text(),
              'exit_code': process.returncode, 'output': process.stdout,
              'passed': process.returncode == 0 and marker in process.stdout and 'job.status=SUCCEEDED' in process.stdout}
    if mode.startswith('crac_restore_'):
        result['passed'] = result['passed'] and 'CRAC_RESTORE_OK' in process.stdout
    print(json.dumps(result, ensure_ascii=False), flush=True)
    sys.exit(0 if result['passed'] else 1)
