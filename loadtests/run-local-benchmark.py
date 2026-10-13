"""Run bounded k6 workloads against the isolated capacity stack and retain evidence."""
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
RUN = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
OUT = ROOT / 'loadtests' / 'results' / RUN
OUT.mkdir(parents=True, exist_ok=False)
BASE = 'http://127.0.0.1:18080'
IMAGE = ROOT / 'tools/datasets/synthetic-meals-100/images/synth-001.png'
ACCOUNTS = OUT / 'accounts.json'
COMPOSE = ['docker', 'compose', '-p', 'mealcheck-capacity', '--env-file', 'deploy/smoke.env',
           '-f', 'docker-compose.prod.yml', '-f', 'deploy/compose.smoke.yml',
           '-f', 'deploy/compose.capacity.yml']
K6 = shutil.which('k6')
META = {'run': RUN, 'baseUrl': BASE, 'started': None, 'phases': [], 'cleanup': None}
STOP = threading.Event()
CURRENT = 'preparation'


def stamp():
    return datetime.now(timezone(timedelta(hours=8))).isoformat()


def command(args, timeout=30, env=None):
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr[-1500:]}')
    return result.stdout


def save():
    (OUT / 'manifest.json').write_text(json.dumps(META, ensure_ascii=False, indent=2), encoding='utf-8')


def model_env(inspect):
    return {k: v for k, v in (value.split('=', 1) for value in inspect['Config']['Env'])
            if k in {'MODEL_STUB_DELAY_SECONDS', 'MODEL_STUB_ERROR_EVERY_N', 'MODEL_STUB_ERROR_STATUS'}}


def queue_snapshot():
    text = command(['docker', 'exec', 'mealcheck-capacity-postgres-1', 'psql', '-U', 'mealcheck',
                    '-d', 'mealcheck', '-At', '-c',
                    "SELECT json_build_object('pending', (SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING')), 'unpublished', (SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NULL), 'statuses', (SELECT json_object_agg(status,n) FROM (SELECT status,count(*) n FROM meal_analysis_requests GROUP BY status) s));"], 15)
    db = json.loads(text.strip())
    queues = command(['docker', 'exec', 'mealcheck-capacity-rabbitmq-1', 'rabbitmqctl', '-q',
                      'list_queues', 'name', 'messages_ready', 'messages_unacknowledged',
                      '--formatter', 'json'], 15)
    return {'database': db, 'rabbitmq': json.loads(queues)}


def drain():
    for _ in range(48):
        data = queue_snapshot()
        if data['database']['pending'] == 0 and data['database']['unpublished'] == 0:
            return data
        time.sleep(5)
    raise RuntimeError('Background tasks did not drain within 240 seconds; stop adding load')


def monitor():
    count = 0
    with (OUT / 'resources.jsonl').open('w', encoding='utf-8') as stream:
        while not STOP.is_set():
            sample = {'at': stamp(), 'phase': CURRENT}
            try:
                raw = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                               'mealcheck-capacity-backend-1', 'mealcheck-capacity-postgres-1',
                               'mealcheck-capacity-rabbitmq-1', 'mealcheck-capacity-frontend-1',
                               'mealcheck-capacity-model-stub-1'], 15)
                sample['containers'] = [json.loads(line) for line in raw.splitlines() if line.strip()]
                if count % 3 == 0:
                    sample['queues'] = queue_snapshot()
            except Exception as error:
                sample['error'] = str(error)
            stream.write(json.dumps(sample, ensure_ascii=False) + '\n')
            stream.flush()
            count += 1
            STOP.wait(5)


def phase(name, script, parameters):
    global CURRENT
    CURRENT = name
    if (OUT/'STOP_REQUESTED').exists():
        raise InterruptedError('User requested stopping the run')
    values = {'BASE_URL': BASE, 'ACCOUNTS_FILE': ACCOUNTS.as_posix(), 'IMAGE_FILE': IMAGE.as_posix(),
              'RESULT_FILE': (OUT / f'{name}.json').as_posix(), 'RUN_ID': f'bench-{RUN}-{name}',
              'K6_NO_USAGE_REPORT': 'true', **parameters}
    # Use an allowlist rather than inherit unrelated k6 settings from the invoking shell.
    env = {k: v for k, v in os.environ.items() if not k.startswith('K6_') and
           k not in {'TOKEN', 'BASE_URL', 'ACCOUNTS_FILE', 'IMAGE_FILE', 'MODE', 'RATE_PER_MINUTE',
                     'DURATION', 'MAX_VUS', 'PREALLOCATED_VUS', 'TASK_TIMEOUT_SECONDS', 'RESULT_FILE',
                     'RUN_ID', 'POLL_SECONDS', 'HTTP_TIMEOUT_SECONDS', 'E2E_P95_MS', 'MAX_FAILURE_RATE',
                     'MIN_COMPLETED', 'BURST_TASKS', 'VUS', 'IMAGES_FILE', 'MIN_RECORDS', 'IMAGE_TYPE', 'GOAL',
                     'REQUIRE_ACCEPTED_SUCCESS', 'WEEKLY_TASKS', 'ASSISTANT_RATE',
                     'ANALYSIS_ACCOUNTS_FILE', 'WEEKLY_ACCOUNTS_FILE'}}
    env.update(values)
    started = stamp()
    print(f'{started} START {name}', flush=True)
    with (OUT / f'{name}.log').open('w', encoding='utf-8') as log:
        duration_match = re.fullmatch(r'(\d+(?:\.\d+)?)(s|m|h)', values.get('DURATION', '60s'))
        if not duration_match:
            raise ValueError('DURATION must be a duration such as 60s or 10m')
        duration_seconds = float(duration_match[1]) * {'s': 1, 'm': 60, 'h': 3600}[duration_match[2]]
        process_timeout = duration_seconds + 2 * int(values.get('TASK_TIMEOUT_SECONDS', '180')) + 90
        process = subprocess.Popen([K6, 'run', '--quiet', '--no-color', '--out',
                                 f"json={OUT / (name + '.timeseries.jsonl')}",
                                 str(ROOT / 'loadtests' / script)],
                                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + process_timeout
        while process.poll() is None:
            if (OUT/'STOP_REQUESTED').exists() or time.monotonic() >= deadline:
                process.terminate(); process.wait(timeout=15)
                META['phases'].append({'name':name,'started':started,'ended':stamp(),
                    'exitCode':process.returncode,'parameters':values,'interrupted':True})
                save()
                raise InterruptedError('Run stopped; phase results are incomplete')
            time.sleep(1)
    entry = {'name': name, 'started': started, 'ended': stamp(), 'exitCode': process.returncode,
             'parameters': values}
    META['phases'].append(entry)
    save()
    print(f'{stamp()} END {name} exit={process.returncode}', flush=True)
    if process.returncode not in {0, 99} or not (OUT / f'{name}.json').exists():
        raise RuntimeError(f'k6 execution failed; inspect {name}.log')
    return process.returncode


def main():
    global CURRENT
    META['started'] = stamp()
    print(f'EVIDENCE {OUT}', flush=True)
    save()
    META['k6Version'] = command([K6, 'version']).strip()
    docker = json.loads(command(['docker', 'info', '--format', '{{json .}}']))
    META['docker'] = {k: docker.get(k) for k in ['ServerVersion', 'NCPU', 'MemTotal', 'OperatingSystem']}
    META['image'] = {'path': str(IMAGE.relative_to(ROOT)), 'bytes': IMAGE.stat().st_size,
                     'sha256': hashlib.sha256(IMAGE.read_bytes()).hexdigest(), 'synthetic': True}
    META['gitHead'] = command(['git', 'rev-parse', 'HEAD']).strip()
    META['dirtyFileCount'] = len(command(['git', 'status', '--porcelain']).splitlines())
    existing = json.loads(command(['docker', 'inspect', 'mealcheck-capacity-model-stub-1']))[0]
    original_model = model_env(existing)
    META['originalModel'] = original_model
    env = dict(os.environ)
    env.update(MODEL_STUB_DELAY_SECONDS='0.05', MODEL_STUB_ERROR_EVERY_N='0')
    inspect = json.loads(command(['docker', 'inspect', 'mealcheck-capacity-backend-1']))[0]
    allowed = {'MEALCHECK_ANALYSIS_CONCURRENCY', 'MEALCHECK_AI_DAILY_CALLS', 'MEALCHECK_AI_SYSTEM_DAILY_CALLS',
               'MEALCHECK_AI_BASE_URL', 'SPRING_PROFILES_ACTIVE'}
    META['backend'] = {k: v for k, v in (value.split('=', 1) for value in inspect['Config']['Env']) if k in allowed}
    if META['backend'].get('MEALCHECK_AI_BASE_URL') != 'http://model-stub:9082/v1':
        raise RuntimeError('Refusing benchmark: backend is not configured to use the isolated model stub')
    META['backendResources'] = {k: inspect['HostConfig'].get(k) for k in ['Memory', 'NanoCpus']}
    META['initialQueues'] = drain()
    save()
    observer = threading.Thread(target=monitor, daemon=True)
    observer.start()
    try:
        print(command(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'model-stub'], 110, env), flush=True)
        print(command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', BASE,
                       '--output', str(ACCOUNTS), '--count', '20'], 120), flush=True)
        seed_code = phase('seed', 'analysis-capacity.js', {'MODE': 'burst', 'BURST_TASKS': '20',
                          'MAX_VUS': '2', 'DURATION': '60s', 'POLL_SECONDS': '0.5', 'TASK_TIMEOUT_SECONDS': '60'})
        if seed_code:
            raise RuntimeError('Seed tasks failed; do not run queries against incomplete fixtures')
        META['seedDrain'] = drain()
        env.update(MODEL_STUB_DELAY_SECONDS='5', MODEL_STUB_ERROR_EVERY_N='0')
        command(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'model-stub'], 110, env)
        META['testModel'] = {'delaySeconds': 5, 'errorEveryN': 0}
        save()
        for vus in [10, 30, 50]:
            phase(f'read-{vus}', 'read-multi.js', {'VUS': str(vus), 'DURATION': '60s'})
        for rate in [5, 10, 20]:
            phase(f'arrival-{rate}', 'analysis-capacity.js', {'MODE': 'arrival', 'RATE_PER_MINUTE': str(rate),
                  'DURATION': '60s', 'MAX_VUS': '20', 'PREALLOCATED_VUS': '20',
                  'TASK_TIMEOUT_SECONDS': '180', 'POLL_SECONDS': '1'})
            META['phases'][-1]['drain'] = drain()
            save()
        phase('burst-20', 'analysis-capacity.js', {'MODE': 'burst', 'BURST_TASKS': '20', 'MAX_VUS': '20',
              'DURATION': '60s', 'TASK_TIMEOUT_SECONDS': '180', 'POLL_SECONDS': '1'})
        META['phases'][-1]['drain'] = drain()
        save()
    finally:
        CURRENT = 'cleanup'
        try:
            drain()
            if ACCOUNTS.exists():
                command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', BASE,
                         '--output', str(ACCOUNTS), '--cleanup'], 120)
            META['cleanup'] = 'Only the newly created 20 test accounts and their data were deleted'
        except Exception as error:
            META['cleanup'] = f'Incomplete: {error}'
        env.update(original_model)
        try:
            command(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'model-stub'], 110, env)
            META['modelRestored'] = True
        except Exception as error:
            META['modelRestored'] = str(error)
        STOP.set()
        observer.join(timeout=45)
        META['ended'] = stamp()
        save()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        META['error'] = str(error)
        META['ended'] = stamp()
        save()
        print(f'FAILED: {error}', flush=True)
        sys.exit(1)
