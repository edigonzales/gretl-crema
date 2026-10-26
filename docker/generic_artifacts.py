"""Identity of the single checkpoint, independent of Docker's mutable volume name."""
import hashlib
from pathlib import Path


def checkpoint_identity():
    directory = Path('/generic-checkpoint/runtime')
    files = {}
    combined = hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        if path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            name = str(path.relative_to(directory))
            files[name] = {'bytes': path.stat().st_size, 'sha256': digest.hexdigest(), 'mtime_ns': path.stat().st_mtime_ns}
            combined.update(name.encode() + b'\0' + digest.digest())
    if not files:
        raise AssertionError('Missing generic checkpoint')
    return {'sha256': combined.hexdigest(), 'bytes': sum(item['bytes'] for item in files.values()), 'files': files}
