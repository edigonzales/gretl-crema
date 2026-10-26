#!/usr/bin/env python3
"""Create per-job AOT artifacts and real CRaC checkpoints, outside measured runs."""
import json
from pathlib import Path
import subprocess
import sys
import time
from measure import command, FULL_CP, HEAP

kind, job, form = sys.argv[1:]
mode = kind + '_' + form
if kind == 'crac_generic':
    location = Path('/generic-checkpoint/runtime')
    args = ['/opt/crac25/bin/java', *HEAP, '-XX:CRaCCheckpointTo=' + str(location),
            '-XX:CRaCEngine=warp', '-XX:CRaCEngineOptions=keep_running=false',
            '-cp', FULL_CP, 'bench.GenericCracLauncher']
elif kind.startswith('jdk'):
    args = command(mode, job)
    cache = next(arg.split('=', 1)[1] for arg in args if arg.startswith('-XX:AOTCache='))
    args = [arg for arg in args if not arg.startswith(('-XX:AOTCache=', '-XX:AOTMode='))]
    args.insert(1, '-XX:AOTCacheOutput=' + cache)
    args.insert(1, '-Xlog:aot=info')
    location = Path(cache)
else:
    location = Path('/checkpoint/' + job + '-' + form)
    args = ['/opt/crac25/bin/java', *HEAP, '-XX:CRaCCheckpointTo=' + str(location),
            '-XX:CRaCEngine=warp', '-XX:CRaCEngineOptions=keep_running=false', '-Dcrema.crac.prepare=true',
            '-cp', FULL_CP, 'bench.CracLauncher', form, job]
started = time.perf_counter()
result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=240)
elapsed = time.perf_counter() - started
files = [location] if location.is_file() else list(location.rglob('*')) if location.exists() else []
size = sum(p.stat().st_size for p in files if p.is_file())
success = result.returncode == 0 and size > 1024 if kind.startswith('jdk') else 'Checkpoint successful!' in result.stdout and size > 1024
if kind == 'crac_generic':
    success = success and 'CRAC_GENERIC_CHECKPOINT jobs-visible=0 training-runs=3' in result.stdout
data = {'kind': kind, 'job': job, 'form': form, 'command': args, 'seconds': elapsed,
        'artifact_bytes': size, 'exit_code': result.returncode, 'passed': success, 'output': result.stdout}
print(json.dumps(data, ensure_ascii=False), flush=True)
sys.exit(0 if success else 1)
