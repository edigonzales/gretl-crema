#!/usr/bin/env python3
"""Collect artifact identities after timing; never run alongside measurements."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--suite', choices=['original', 'generic'], default='original')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
generic = args.suite == 'generic'
out = root / ('crema-runner/target/linux-generic' if generic else 'crema-runner/target/linux')
out.mkdir(parents=True, exist_ok=True)
image = 'gretl-crema-linux:generic' if generic else 'gretl-crema-linux:experiment'
image_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip()
command = ['docker', 'run', '--rm', '--cpus=2', '--memory=1g', '--memory-swap=1g', '--pids-limit=512', '--network=none']
for volume, target in [('gretl-crema-linux-artifacts', '/native'), ('gretl-crema-linux-aot', '/aot'),
                       ('gretl-crema-linux-checkpoints', '/checkpoint')]:
    command += ['--mount', f'source={volume},target={target},readonly']
if generic:
    command += ['--mount', 'source=gretl-crema-linux-generic-checkpoint,target=/generic-checkpoint,readonly',
                '--mount', 'source=gretl-crema-linux-generic-jobs,target=/post-jobs,readonly']
command += ['--mount', 'type=bind,source=' + str(root / 'docker/metadata.py') + ',target=/app/bench/metadata.py,readonly',
            image_id, 'python3', '/app/bench/metadata.py']
report = json.loads(subprocess.check_output(command, text=True))
report['image_id'] = image_id
report['docker_engine_version'] = subprocess.check_output(['docker', 'version', '--format', '{{.Server.Version}}'], text=True).strip()
(out / 'environment.json').write_text(json.dumps(report, indent=2) + '\n')
print('Saved environment identities for ' + image_id)
