#!/usr/bin/env python3
"""Collect identities and artifact sizes after timing, with no application workload."""
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


report = {'kernel': os.uname().release, 'architecture': os.uname().machine,
          'memory_limit': Path('/sys/fs/cgroup/memory.max').read_text().strip(),
          'swap_limit': Path('/sys/fs/cgroup/memory.swap.max').read_text().strip(),
          'cpu_max': Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
          'runtimes': {}, 'native': {}, 'aot': {}, 'checkpoints': {}, 'jars': {}}
for name in ['graal', 'jdk25', 'jdk27', 'jdk28', 'crac25']:
    result = subprocess.run(['/opt/' + name + '/bin/java', '-Xms32m', '-Xmx512m', '-XX:+PrintFlagsFinal', '-version'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True)
    report['runtimes'][name] = [line.strip() for line in result.stdout.splitlines() if any(term in line for term in
        ['version "', 'Runtime Environment', 'Server VM', 'UseCompactObjectHeaders ', 'UseSerialGC ', 'UseG1GC ', 'UseContainerSupport ', 'MaxHeapSize ', 'InitialHeapSize '])]
for variant in ['interpreter', 'ristretto']:
    directory = Path('/native') / variant
    binary = directory / 'gretl-crema'
    with binary.open('rb') as stream:
        magic = stream.read(4).hex()
    report['native'][variant] = {'bytes': binary.stat().st_size, 'sha256': digest(binary),
        'elf_magic': magic, 'build': json.loads((directory / 'build.json').read_text()),
        'sidecar_bytes': sum(p.stat().st_size for p in directory.glob('*.so'))}
for file in Path('/aot').glob('*.aot'):
    if file.name.startswith('probe-'):
        continue
    report['aot'][file.name] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
for directory in Path('/checkpoint').iterdir():
    if directory.is_dir():
        files = [p for p in directory.rglob('*') if p.is_file()]
        report['checkpoints'][directory.name] = {'bytes': sum(p.stat().st_size for p in files),
            'files': {str(p.relative_to(directory)): {'bytes': p.stat().st_size, 'sha256': digest(p)} for p in files}}
for file in [*Path('/app/runtime').rglob('*.jar'), *Path('/app/compiler').glob('*.jar')]:
    report['jars'][str(file)] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
if Path('/generic-checkpoint/runtime').exists():
    from generic_artifacts import checkpoint_identity
    report['generic_checkpoint'] = checkpoint_identity()
    report['post_checkpoint_jobs'] = json.loads(Path('/post-jobs/identity.json').read_text())
    assert report['generic_checkpoint']['sha256'] == report['post_checkpoint_jobs']['checkpoint']['sha256']
print(json.dumps(report, indent=2))
