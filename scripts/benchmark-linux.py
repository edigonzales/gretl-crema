#!/usr/bin/env python3
"""Controlled fresh-container benchmark and artifact preparation for Linux ARM64."""
import argparse
import datetime
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'crema-runner/target/linux'
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['prepare', 'smoke', 'measure'])
parser.add_argument('--image', default='gretl-crema-linux:experiment')
parser.add_argument('--samples', type=int, default=10)
parser.add_argument('--warmups', type=int, default=3)
parser.add_argument('--modes', help='Comma-separated subset')
parser.add_argument('--kinds', help='Comma-separated subset for artifact preparation')
parser.add_argument('--preparation-memory', choices=['1g', '2g'], default='1g', help='Untimed artifact assembly only; timed runs always use 1g')
args = parser.parse_args()
IMAGE = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', args.image], text=True).strip()
MOUNTS = ['--mount', 'source=gretl-crema-linux-artifacts,target=/native,readonly',
          '--mount', 'source=gretl-crema-linux-aot,target=/aot',
          '--mount', 'source=gretl-crema-linux-checkpoints,target=/checkpoint']
memory = args.preparation_memory if args.action == 'prepare' else '1g'
EXPECTED_MEMORY = 1073741824 if memory == '1g' else 2147483648
LIMITS = ['--cpus=2', '--memory=' + memory, '--memory-swap=' + memory, '--pids-limit=512', '--network=none']
MODES = ['interpreter', 'ristretto', 'jdk25_bytecode', 'jdk25_source', 'jdk27_bytecode', 'jdk27_source',
         'jdk27_aot_bytecode', 'jdk27_aot_source', 'jdk27_aotserial_bytecode', 'jdk27_aotserial_source',
         'jdk27_eager_bytecode', 'jdk27_eager_source', 'crac_cold_bytecode', 'crac_cold_source',
         'crac_restore_bytecode', 'crac_restore_source', 'jdk28_bytecode', 'jdk28_source', 'jdk28_aot_bytecode', 'jdk28_aot_source']
if args.modes:
    MODES = args.modes.split(',')


def save(filename, data):
    (OUT / filename).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


def run_container(command, label):
    name = 'gretl-crema-bench-' + uuid.uuid4().hex[:12]
    created = time.perf_counter()
    subprocess.run(['docker', 'create', '--name', name, *LIMITS, *MOUNTS, IMAGE, *command],
                   stdout=subprocess.DEVNULL, check=True)
    create_seconds = time.perf_counter() - created
    started = time.perf_counter()
    try:
        completed = subprocess.run(['docker', 'start', '--attach', name], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, timeout=300)
        elapsed = time.perf_counter() - started
        inspection = json.loads(subprocess.check_output(['docker', 'inspect', name], text=True))[0]
        try:
            data = json.loads(completed.stdout.strip().splitlines()[-1])
        except (json.JSONDecodeError, IndexError):
            data = {'passed': False, 'output': completed.stdout}
        data.update({'container': name, 'image_id': IMAGE, 'docker_create_seconds': create_seconds,
                     'docker_start_to_exit_seconds': elapsed, 'docker_create_to_exit_seconds': create_seconds + elapsed,
                     'container_oom_killed': inspection['State']['OOMKilled'],
                     'container_exit_code': inspection['State']['ExitCode'],
                     'docker_cli_exit_code': completed.returncode,
                     'limits': {key: inspection['HostConfig'][key] for key in ['NanoCpus', 'Memory', 'MemorySwap', 'PidsLimit']}})
        # Older host Python accepts microseconds, while Docker returns nanoseconds.
        begin = datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', inspection['State']['StartedAt']).replace('Z', '+00:00'))
        end = datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', inspection['State']['FinishedAt']).replace('Z', '+00:00'))
        data['engine_container_seconds'] = (end - begin).total_seconds()
        if data['container_oom_killed']:
            data['passed'] = False
        if data['limits']['NanoCpus'] != 2000000000 or data['limits']['Memory'] != EXPECTED_MEMORY:
            raise AssertionError('Limits differ from the benchmark contract')
        if args.action == 'smoke' and label.endswith('-h2') and data.get('passed'):
            checks = OUT / 'db-checks'
            checks.mkdir(exist_ok=True)
            database = checks / (label + '.mv.db')
            copied = subprocess.run(['docker', 'cp', name + ':/work/measured-db.mv.db', str(database)],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            data['database_copy'] = str(database.relative_to(ROOT))
            data['passed'] = data['passed'] and copied.returncode == 0
        return data
    except subprocess.TimeoutExpired as failure:
        return {'passed': False, 'container': name, 'error': str(failure)}
    finally:
        subprocess.run(['docker', 'rm', '--force', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if args.action == 'prepare':
    previous = OUT / 'preparation.json'
    report = json.loads(previous.read_text()) if previous.exists() else {'artifacts': []}
    report.update({'image_id': IMAGE, 'limits': LIMITS})
    kinds = args.kinds.split(',') if args.kinds else ['jdk27_aot', 'jdk27_aotserial', 'jdk27_eager', 'crac', 'jdk28_aot']
    report['artifacts'] = [item for item in report['artifacts'] if item.get('kind') not in kinds]
    for job in ['h2', 'hotloop']:
        for kind in kinds:
            for form in ['bytecode', 'source']:
                item = run_container(['python3', '/app/bench/prepare.py', kind, job, form], f'{kind}-{job}-{form}')
                report['artifacts'].append(item)
                save('preparation.json', report)
                print(f'{"PASS" if item["passed"] else "FAIL"} prepare {kind} {job} {form}: {item.get("seconds", 0):.2f}s, {item.get("artifact_bytes", 0)} bytes', flush=True)
                if not item['passed']:
                    print(item.get('output', item.get('error', ''))[-5000:], flush=True)
    report['passed'] = all(item['passed'] for item in report['artifacts'])
    save('preparation.json', report)
    raise SystemExit(0 if report['passed'] else 1)

if args.action == 'measure':
    build_names = subprocess.check_output(['docker', 'ps', '--filter', 'name=gretl-crema-linux-native-build', '--format', '{{.Names}}'], text=True).strip()
    if build_names:
        parser.error('Wait until the native builder has stopped before timing workloads')

report = {'image_id': IMAGE, 'limits': LIMITS, 'heap': '-Xms32m -Xmx512m',
          'warmups': args.warmups if args.action == 'measure' else 0,
          'samples': args.samples if args.action == 'measure' else 1,
          'fresh_container_per_execution': True, 'workloads': {}}
filename = 'results.json' if args.action == 'measure' else 'smoke.json'
for job in ['h2', 'hotloop']:
    item = {'runs': {mode: [] for mode in MODES}, 'warmup_failures': []}
    report['workloads'][job] = item
    rounds = args.warmups + args.samples if args.action == 'measure' else 1
    for index in range(rounds):
        order = MODES[index % len(MODES):] + MODES[:index % len(MODES)]
        for mode in order:
            data = run_container(['python3', '/app/bench/measure.py', mode, job], mode + '-' + job)
            data['round'] = index
            if args.action == 'measure' and index < args.warmups:
                if not data['passed']:
                    item['warmup_failures'].append({'mode': mode, **data})
            else:
                item['runs'][mode].append(data)
            if not data['passed']:
                print(f'FAIL {job} {mode}: {data.get("output", data.get("error", ""))[:2500]}', flush=True)
            save(filename, report)
        print(f'{job}: round {index + 1}/{rounds}', flush=True)
    item['summary'] = {}
    for mode, runs in item['runs'].items():
        successful = [run for run in runs if run['passed']]
        summary = {'passed': len(successful) == len(runs) and bool(runs), 'successful_runs': len(successful), 'total_runs': len(runs)}
        for metric in ['process_seconds', 'docker_start_to_exit_seconds', 'docker_create_to_exit_seconds', 'engine_container_seconds', 'peak_rss_bytes', 'cgroup_memory_peak_bytes', 'user_cpu_seconds', 'system_cpu_seconds']:
            values = [run[metric] for run in successful if run.get(metric) is not None]
            if values:
                summary[metric] = {'median': statistics.median(values), 'min': min(values), 'max': max(values)}
        item['summary'][mode] = summary
        print(f'{job} {mode}: {summary}', flush=True)
    save(filename, report)
report['passed'] = all(not w['warmup_failures'] and all(s['passed'] for s in w['summary'].values()) for w in report['workloads'].values())
save(filename, report)
raise SystemExit(0 if report['passed'] else 1)
