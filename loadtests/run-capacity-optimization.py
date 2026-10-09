"""Validate ingress sizing and compare C2/C4 with long and mixed workloads."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time

import importlib.util

spec = importlib.util.spec_from_file_location('benchmark', Path(__file__).with_name('run-local-benchmark.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
b.META['schema'] = 'optimization-v2'
b.META['accountCount'] = 40
b.META['recordsPerAccount'] = 1000
b.META['realModel'] = 'Not run: no real API key or provider configuration is available locally'
env = dict(os.environ)
original_model = None


def drain():
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        value = b.queue_snapshot()
        if value['database']['pending'] == 0 and value['database']['unpublished'] == 0:
            return value
        time.sleep(5)
    raise RuntimeError('Work did not drain within 15 minutes; no more load will be added')


def scale(concurrency):
    b.CURRENT = f'scale-c{concurrency}'
    env['MEALCHECK_ANALYSIS_CONCURRENCY'] = str(concurrency)
    b.command(b.COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'backend'], 110, env)
    current = json.loads(b.command(['docker', 'inspect', 'mealcheck-capacity-backend-1']))[0]
    settings = dict(item.split('=', 1) for item in current['Config']['Env'])
    if settings.get('MEALCHECK_AI_BASE_URL') != 'http://model-stub:9082/v1':
        raise RuntimeError('Refusing to benchmark a real model endpoint')
    if settings.get('MEALCHECK_ANALYSIS_CONCURRENCY') != str(concurrency):
        raise RuntimeError('Requested concurrency did not apply')
    b.META.setdefault('configurations', []).append({'at': b.stamp(), 'concurrency': concurrency,
        'imageId': current['Image'], 'memoryBytes': current['HostConfig']['Memory'],
        'nanoCpus': current['HostConfig']['NanoCpus']})
    b.save()


def model(delay):
    b.CURRENT = 'model-configuration'
    env.update(MODEL_STUB_DELAY_SECONDS=str(delay), MODEL_STUB_ERROR_EVERY_N='0')
    b.command(b.COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'model-stub'], 110, env)


def phase(name, script='analysis-capacity.js', **parameters):
    defaults = {'MODE': 'arrival', 'DURATION': '600s', 'MAX_VUS': '80', 'PREALLOCATED_VUS': '80',
                'TASK_TIMEOUT_SECONDS': '180', 'POLL_SECONDS': '1', 'MIN_RECORDS': '1000'}
    defaults.update({k: str(v) for k, v in parameters.items()})
    code = b.phase(name, script, defaults)
    b.META['phases'][-1]['drain'] = drain()
    b.save()
    return code


def expand_records():
    accounts = json.loads(b.ACCOUNTS.read_text(encoding='utf-8'))
    ids = []
    for account in accounts:
        encoded = account['token'].split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        if not claims['sub'].startswith('capacity_') or not isinstance(claims['uid'], int):
            raise RuntimeError('Unexpected test account identity')
        ids.append(claims['uid'])
    allowed_ids = ','.join(str(v) for v in ids)
    columns = b.command(['docker', 'exec', 'mealcheck-capacity-postgres-1', 'psql', '-U', 'mealcheck',
        '-d', 'mealcheck', '-At', '-c', "SELECT column_name FROM information_schema.columns WHERE table_name='meal_records' AND column_name<>'id' ORDER BY ordinal_position;"]).splitlines()
    import re
    if not all(re.fullmatch('[a-z_]+', column) for column in columns):
        raise RuntimeError('Unexpected schema column')
    names = ','.join('"'+c+'"' for c in columns)
    values = ','.join("t.created_at - (n % 365) * interval '1 day'" if c=='created_at' else 't."'+c+'"' for c in columns)
    sql = f"BEGIN; WITH t AS (SELECT DISTINCT ON (user_id) * FROM meal_records WHERE user_id IN ({allowed_ids}) ORDER BY user_id,id) INSERT INTO meal_records ({names}) SELECT {values} FROM t CROSS JOIN generate_series(1,999) n; COMMIT; SELECT min(n),max(n),sum(n) FROM (SELECT user_id,count(*) n FROM meal_records WHERE user_id IN ({allowed_ids}) GROUP BY user_id) s;"
    (b.OUT/'seed-records.sql').write_text(sql, encoding='utf-8')
    result = subprocess.run(['docker', 'exec', '-i', 'mealcheck-capacity-postgres-1', 'psql', '-U',
        'mealcheck', '-d', 'mealcheck', '-At', '-v', 'ON_ERROR_STOP=1'], input=sql, capture_output=True,
        encoding='utf-8', timeout=180)
    if result.returncode:
        raise RuntimeError('Fixture expansion failed: '+result.stderr)
    if not result.stdout.strip().endswith(f'1000|1000|{len(ids)*1000}'):
        raise RuntimeError('Fixture expansion did not produce 1000 records per account')
    b.META['seedRecords'] = {'accountCount':len(ids),'recordsPerAccount':1000,'total':len(ids)*1000,
        'method':'Cloned real API-generated records only for the newly created test account IDs'}
    b.save()


def main():
    global original_model
    b.META['started'] = b.stamp()
    print(f'EVIDENCE {b.OUT}', flush=True)
    b.META['k6Version'] = b.command([b.K6,'version']).strip()
    d = json.loads(b.command(['docker','info','--format','{{json .}}']))
    b.META['docker'] = {k:d.get(k) for k in ['ServerVersion','NCPU','MemTotal','OperatingSystem']}
    original = json.loads(b.command(['docker','inspect','mealcheck-capacity-model-stub-1']))[0]
    original_model = b.model_env(original)
    b.META['originalModel'] = original_model
    # Apply resource defaults before collecting the actual runtime configuration.
    b.command(b.COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'frontend'], 110, env)
    frontend = json.loads(b.command(['docker','inspect','mealcheck-capacity-frontend-1']))[0]
    b.META['frontend'] = {'imageId':frontend['Image'],'memoryBytes':frontend['HostConfig']['Memory'],
                          'tmpfs':frontend['HostConfig']['Tmpfs']}
    b.META['gitHead'] = b.command(['git','rev-parse','HEAD']).strip()
    b.META['initialQueues'] = drain()
    snapshots = b.OUT/'scripts'
    snapshots.mkdir()
    for name in ['analysis-capacity.js','read-multi.js','capacity-mixed.js','run-capacity-optimization.py','run-local-benchmark.py']:
        shutil.copyfile(b.ROOT/'loadtests'/name, snapshots/name)
    # Preserve a boundary fixture without changing the visual image content.
    boundary = b.OUT/'boundary-10m.png'
    raw = b.IMAGE.read_bytes()
    boundary.write_bytes(raw + bytes(10*1024**2-len(raw)))
    tiny = b.ROOT/'loadtests/results/meal.jpg'
    variants = [{'path':b.IMAGE.as_posix(),'type':'image/png','dimensions':[1254,1254]},
        {'path':(b.ROOT/'tools/datasets/synthetic-meals-100/images/synth-002.png').as_posix(),
         'type':'image/png','dimensions':[1254,1254]},
        {'path':tiny.as_posix(),'type':'image/jpeg','dimensions':[16,16]}]
    variants_file = b.OUT/'images.json'
    variants_file.write_text(json.dumps(variants),encoding='utf-8')
    b.META['images'] = [{**v,'bytes':Path(v['path']).stat().st_size} for v in variants]
    b.META['boundary'] = {'bytes':boundary.stat().st_size,'note':'Valid source PNG with trailing zero padding for input-byte boundary only'}
    b.save()
    observer = threading.Thread(target=b.monitor, daemon=True)
    observer.start()
    try:
        scale(2)
        model(0.05)
        print(b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,
                         '--output',str(b.ACCOUNTS),'--count','40'],180),flush=True)
        if phase('seed', MODE='burst', BURST_TASKS=40, MAX_VUS=2, PREALLOCATED_VUS=2,
                 DURATION='60s', TASK_TIMEOUT_SECONDS=60, MIN_RECORDS=1):
            raise RuntimeError('API fixture seeding failed')
        expand_records()
        phase('entry-burst-20', MODE='burst', BURST_TASKS=20, MAX_VUS=20, PREALLOCATED_VUS=20, DURATION='60s')
        phase('boundary-10m-5', MODE='burst', BURST_TASKS=5, MAX_VUS=5, PREALLOCATED_VUS=5,
              DURATION='60s', IMAGE_FILE=boundary.as_posix())
        for vus in [10,30,50]:
            phase(f'read-{vus}-1000', script='read-multi.js', VUS=vus, DURATION='60s')
        model(5)
        phase('c2-arrival-20-long', RATE_PER_MINUTE=20)
        scale(4)
        for rate in [5,10,20]:
            phase(f'c4-arrival-{rate}-long', RATE_PER_MINUTE=rate)
        phase('c4-burst-20', MODE='burst', BURST_TASKS=20, MAX_VUS=20, PREALLOCATED_VUS=20,DURATION='60s')
        phase('c4-mixed-20-long', script='capacity-mixed.js', RATE_PER_MINUTE=20, VUS=50,
              MAX_VUS=40, PREALLOCATED_VUS=40, IMAGES_FILE=variants_file.as_posix())
    finally:
        b.CURRENT = 'cleanup'
        try:
            b.META['finalQueuesBeforeCleanup'] = drain()
            if b.ACCOUNTS.exists():
                b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,
                    '--output',str(b.ACCOUNTS),'--cleanup'],180)
            b.META['cleanup'] = 'Only the newly created 40 test accounts and their associated data were deleted'
        except Exception as error:
            b.META['cleanup'] = f'Incomplete: {error}'
        try:
            scale(4)
            env.update(original_model)
            b.command(b.COMPOSE+['up','-d','--no-deps','--wait','--wait-timeout','90','model-stub'],110,env)
            b.META['modelRestored'] = True
        except Exception as error:
            b.META['modelRestored'] = str(error)
        b.STOP.set()
        observer.join(timeout=45)
        b.META['ended'] = b.stamp()
        b.save()


if __name__=='__main__':
    try:
        main()
    except Exception as error:
        b.META['error'] = str(error)
        b.META['ended'] = b.stamp()
        b.save()
        print(f'FAILED: {error}',flush=True)
        sys.exit(1)
