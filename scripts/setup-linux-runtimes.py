#!/usr/bin/env python3
"""Install checksum-pinned Linux ARM64 runtimes locally, without changing host SDKs."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / '.tools/linux'
specifications = json.loads((ROOT / 'docker/runtimes.json').read_text())
if len(sys.argv) > 1:
    specifications = {name: specifications[name] for name in sys.argv[1:]}


def install(item):
    name, spec = item
    directory = BASE / name
    marker = directory / '.installed-sha256'
    if marker.exists() and marker.read_text().strip() == spec['sha256']:
        print(f'{name}: already installed', flush=True)
        return
    archive = BASE / 'downloads' / (name + '.tar.gz')
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        print(f'{name}: downloading {spec["version"]}', flush=True)
        request = urllib.request.Request(spec['url'], headers={'User-Agent': 'gretl-crema-linux-benchmark'})
        with urllib.request.urlopen(request, timeout=90) as response, archive.with_suffix('.part').open('wb') as output:
            while block := response.read(1024 * 1024):
                output.write(block)
        archive.with_suffix('.part').replace(archive)
    actual = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != spec['sha256']:
        raise RuntimeError(f'{name}: checksum mismatch: {actual}')
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(['tar', '-xzf', str(archive), '--strip-components=1', '-C', str(directory)], check=True)
    marker.write_text(actual + '\n')
    print(f'{name}: installed and verified', flush=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(install, specifications.items()))
