#!/usr/bin/env python3
"""Compile unseen measured jobs only after the generic checkpoint exists."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
from generic_artifacts import checkpoint_identity
from measure import SOURCE_CP, HEAP

identity = checkpoint_identity()
latest_checkpoint_file = max(item['mtime_ns'] for item in identity['files'].values())
jobs = []

def classes(path):
    with zipfile.ZipFile(path) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if name.endswith('.class')}

for job, stem in [('h2', 'job'), ('hotloop', 'hotloop'), ('alternate', 'job')]:
    destination = Path('/post-jobs') / job
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    if job == 'alternate':
        (destination / 'job.groovy').write_text("parameter('wert', type: 'integer', required: true, description: 'New restore argument')\naction('newJob') { println \"POST_CHECKPOINT_OK ${parameter('wert') * 7}\" }\ndefaults 'newJob'\n")
    else:
        for path in (Path('/app/jobs') / job).iterdir():
            if path.is_file() and path.suffix != '.jar':
                shutil.copyfile(path, destination / path.name)
    jar = destination / (stem + '.jar')
    subprocess.run(['/opt/jdk25/bin/java', *HEAP, '-cp', SOURCE_CP,
                    'ch.so.agi.gretl.crema.compiler.CompilerMain', str(destination / (stem + '.groovy')), '--output', str(jar)],
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    assert jar.stat().st_mtime_ns > latest_checkpoint_file
    content = classes(jar)
    if job != 'alternate':
        assert content == classes(Path('/app/jobs') / job / (stem + '.jar')), 'Job bytecode differs from controls'
    jobs.append({'job': job, 'jar': str(jar), 'mtime_ns': jar.stat().st_mtime_ns,
                 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'classes': content,
                 'compiled_after_checkpoint': True, 'class_bytes_match_controls': job != 'alternate'})
report = {'passed': checkpoint_identity()['sha256'] == identity['sha256'], 'checkpoint': identity, 'jobs': jobs}
Path('/post-jobs/identity.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
