#!/usr/bin/env python3
"""Validate and preserve measured evidence without binaries or checkpoint payloads."""
import argparse
import json
from pathlib import Path
import shutil

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--suite', choices=['original', 'generic'], default='original')
args = parser.parse_args()
generic = args.suite == 'generic'
source = root / ('crema-runner/target/linux-generic' if generic else 'crema-runner/target/linux')
destination = root / 'docs/results'
destination.mkdir(exist_ok=True)

def read(name):
    return json.loads((source / name).read_text())

runs = read('results.json')
assert runs['passed'] and runs['samples'] == 10 and runs['warmups'] == 3
count = 0
containers = set()
for workload in runs['workloads'].values():
    assert not workload['warmup_failures'] and len(workload['runs']) == (8 if generic else 20)
    for samples in workload['runs'].values():
        assert len(samples) == 10
        for row in samples:
            assert row['passed'] and row['exit_code'] == 0
            assert row['image_id'] == runs['image_id']
            assert row['limits']['NanoCpus'] == 2_000_000_000
            assert row['limits']['Memory'] == row['limits']['MemorySwap'] == 1_073_741_824
            assert row['cgroup_cpu_max'] == '200000 100000'
            assert 'oom_kill 0' in row['cgroup_memory_events']
            assert row['container'] not in containers
            containers.add(row['container'])
            count += 1
assert count == (160 if generic else 400)
for name in ['database-validation.json', 'smoke.json', 'preparation.json']:
    assert read(name)['passed']
if generic:
    assert read('validation.json')['passed'] and read('jobs.json')['passed']
    environment = read('environment.json')
    assert environment['generic_checkpoint']['sha256'] == read('jobs.json')['checkpoint']['sha256']
    for original, archived in [('results.json', 'runs.json'), ('environment.json', 'environment.json'),
                                ('preparation.json', 'preparation.json'), ('jobs.json', 'jobs.json'),
                                ('validation.json', 'validation.json'), ('database-validation.json', 'databases.json'),
                                ('smoke.json', 'smoke.json')]:
        shutil.copyfile(source / original, destination / ('linux-generic-' + archived))
    print('Preserved 160 measured runs, 16 smoke cases, 8 database checks and generic checkpoint acceptance.')
    raise SystemExit(0)
for version in ['jdk25', 'jdk27', 'jdk28']:
    assert read(version + '-tests.json')['passed']

for original, archived in [
    ('results.json', 'linux-runs.json'), ('environment.json', 'linux-environment.json'),
    ('preparation.json', 'linux-preparation.json'), ('database-validation.json', 'linux-databases.json'),
    ('smoke.json', 'linux-smoke.json'), ('jdk28-source-build-1g-probe.json', 'linux-aot-build-oom.json')]:
    shutil.copyfile(source / original, destination / archived)
shutil.copyfile(source / 'runtime-diagnostics.log', destination / 'linux-diagnostics.log')
(destination / 'linux-tests.json').write_text(json.dumps(
    [read(version + '-tests.json') for version in ['jdk25', 'jdk27', 'jdk28']], indent=2) + '\n')
print('Preserved 400 measured runs, 40 smoke cases, 20 database checks and 3 × 204 Maven tests.')
