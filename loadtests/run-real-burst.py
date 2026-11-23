"""Run one isolated, paid 50-account upload burst and retain auditable evidence.

Uses local .env credentials without copying them into evidence. Never targets an
existing deployment. Run `prepare`, `start`, `run`, then `cleanup` with one output.
"""

import sys as _auth_sys
from pathlib import Path as _AuthPath
_auth_sys.path.insert(0, str(_AuthPath(__file__).resolve().parents[1] / 'tools'))
import cookie_http as _auth_http
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import threading
import time
from urllib.parse import urlparse

import cookie_http as requests
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:15184'
BACK = 'http://127.0.0.1:18089'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def credentials():
    env = os.environ.copy()
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    for key in ('MEALCHECK_AI_API_KEY', 'MEALCHECK_AI_BASE_URL', 'MEALCHECK_AI_MODEL'):
        if not env.get(key):
            raise RuntimeError('Missing local configuration: ' + key)
    if urlparse(env['MEALCHECK_AI_BASE_URL']).hostname in ('localhost', '127.0.0.1', 'model-stub'):
        raise RuntimeError('A real provider endpoint is required')
    return env


def command(args, output=None):
    result = subprocess.run(args, cwd=ROOT, env=credentials(), text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
    if output:
        output.write_text(result.stdout, encoding='utf-8')
    if result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(args[:4]) + '; see saved evidence')
    return result.stdout


def compose(out, *args):
    return command(['docker', 'compose', '-p', project(out), '-f', str(out / 'compose.json'), *args])


def project(out):
    return 'mealcheck-' + out.name


def prepare(out):
    out.mkdir(parents=True, exist_ok=False)
    template = json.loads((ROOT / 'loadtests/results/full-e2e-20261015-143645/compose.json').read_text())
    template['name'] = project(out)
    backend = template['services']['backend']
    shutil.copy2(ROOT / 'backend/target/mealcheck-backend.jar', out / 'app.jar')
    backend['volumes'] = [f'{(out / "app.jar").as_posix()}:/app/app.jar:ro']
    backend['command'] = ['java', '-XX:MaxRAMPercentage=45.0', '-jar', '/app/app.jar']
    backend['mem_limit'] = '1g'
    backend['cpus'] = 1.5
    backend['ports'] = ['127.0.0.1:18089:8080']
    backend['environment'].update({
        'MEALCHECK_CORS_ALLOWED_ORIGINS': BASE,
        'MEALCHECK_ANALYSIS_CONCURRENCY': '8', 'MEALCHECK_ANALYSIS_MAX_OUTSTANDING': '32',
        'MEALCHECK_ANALYSIS_MAX_OUTSTANDING_PER_USER': '2',
        'MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS': '10',
        'MEALCHECK_AI_GLOBAL_MAX_STARTS_PER_MINUTE': '120',
        'MEALCHECK_AI_DAILY_CALLS': '10', 'MEALCHECK_AI_GLOBAL_DAILY_CALLS': '110',
        'MEALCHECK_AI_DAILY_COST': '2', 'MEALCHECK_AI_GLOBAL_DAILY_COST': '10',
        # Only test-account preparation limits are relaxed in this fresh namespace.
        'MEALCHECK_RATE_LIMIT_REGISTRATION_CAPACITY': '100',
        'MEALCHECK_RATE_LIMIT_REGISTRATION_REFILL_TOKENS': '100',
        'MEALCHECK_RATE_LIMIT_CAPTCHA_CAPACITY': '100',
        'MEALCHECK_RATE_LIMIT_CAPTCHA_REFILL_TOKENS': '100',
    })
    frontend = template['services']['frontend']
    frontend['ports'] = ['127.0.0.1:15184:8080']
    frontend['mem_limit'] = '512m'
    frontend['tmpfs'] = ['/tmp:rw,size=256m']
    save(out / 'compose.json', template)
    images = []
    for index in range(1, 6):
        source = ROOT / f'tools/datasets/synthetic-meals-100/images/synth-{index:03}.png'
        target = out / f'meal-{index}.jpg'
        with Image.open(source) as img:
            img = img.convert('RGB')
            img.thumbnail((1024, 1024))
            img.save(target, 'JPEG', quality=88)
            images.append(dict(name=target.name, size=target.stat().st_size, width=img.width, height=img.height,
                               sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    env = credentials()
    save(out / 'manifest.json', dict(created=datetime.now(timezone.utc).isoformat(), project=project(out),
         providerHost=urlparse(env['MEALCHECK_AI_BASE_URL']).hostname, model=env['MEALCHECK_AI_MODEL'],
         jarSha256=hashlib.sha256((out / 'app.jar').read_bytes()).hexdigest(), images=images,
         tokenTarget=300000, stopThreshold=220000, clientAutomaticRetries=False,
         note='Isolated local deployment; synthetic meal pictures; optional paced recovery is a separate phase.'))
    print('Prepared isolated environment and five image fixtures', flush=True)


def request(method, path, account=None, **kwargs):
    headers = dict(kwargs.pop('headers', {}))
    if account:
        headers['Authorization'] = 'Bearer ' + account['token']
    return requests.request(method, BASE + path, headers=headers, timeout=kwargs.pop('timeout', 30), **kwargs)


def metrics(out, label):
    account = json.loads((out / 'accounts.private.json').read_text())[-1]
    response = requests.get(BACK + '/actuator/prometheus',
                            headers={'Authorization': 'Bearer ' + account['token']}, timeout=10)
    response.raise_for_status()
    (out / f'metrics-{label}.txt').write_text(response.text, encoding='utf-8')
    def total(name, label_filter=None):
        return sum(float(line.rsplit(' ', 1)[1]) for line in response.text.splitlines()
                   if line.startswith(name + '{') and (not label_filter or label_filter in line))
    return dict(input=total('mealcheck_ai_tokens_total', 'direction="input"'),
                output=total('mealcheck_ai_tokens_total', 'direction="output"'),
                calls=total('mealcheck_ai_http_attempts_total'),
                failedCalls=total('mealcheck_ai_http_attempts_total', 'outcome="failure"'),
                retries=total('mealcheck_ai_http_retries_total'),
                missingUsage=total('mealcheck_ai_usage_missing_total'),
                estimatedCost=total('mealcheck_ai_estimated_cost_total'))


def identities(out):
    ids = compose(out, 'ps', '-q').split()
    data = json.loads(command(['docker', 'inspect', *ids]))
    return [dict(id=row['Id'], name=row['Name'], service=row['Config']['Labels']['com.docker.compose.service'],
                 restartCount=row['RestartCount'], startedAt=row['State']['StartedAt'],
                 status=row['State']['Status'], oomKilled=row['State']['OOMKilled']) for row in data]


def audit(out, label):
    sql = """SELECT json_build_object(
      'users',(SELECT count(*) FROM users), 'records',(SELECT count(*) FROM meal_records),
      'tasks',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT user_id,request_key,status,record_id,attempts,last_error FROM meal_analysis_requests ORDER BY user_id) t),
      'duplicateUsers',(SELECT count(*) FROM (SELECT user_id FROM meal_records GROUP BY user_id HAVING count(*)>1) d),
      'outstanding',(SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING')),
      'outboxUnpublished',(SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NULL),
      'budgets',(SELECT coalesce(json_agg(b),'[]') FROM (SELECT quota_key,attempts,spent,reserved FROM ai_budget_daily ORDER BY quota_key) b),
      'unsettled',(SELECT count(*) FROM ai_budget_reservations WHERE status IN ('RESERVED','SENT'))
    );"""
    value = json.loads(compose(out, 'exec', '-T', 'postgres', 'psql', '-U', 'mealcheck', '-d', 'mealcheck', '-Atc', sql).strip())
    save(out / f'database-{label}.json', value)
    queues = compose(out, 'exec', '-T', 'rabbitmq', 'rabbitmqctl', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged')
    (out / f'queues-{label}.txt').write_text(queues, encoding='utf-8')
    return value, queues


def run(out):
    before = identities(out)
    save(out / 'containers-before.json', before)
    account_file = out / 'accounts.private.json'
    accounts = json.loads(account_file.read_text()) if account_file.exists() else []
    for index in range(len(accounts), 51):
        captcha = request('GET', '/api/auth/captcha')
        captcha.raise_for_status()
        captcha = captcha.json()
        numbers = re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?', captcha['question'])
        if not numbers:
            raise RuntimeError('Unexpected captcha')
        account = dict(username='burst50_' + out.name[-6:] + f'_{index:02}', password='Burst50-test-only-' + os.urandom(12).hex())
        response = request('POST', '/api/auth/register', json=dict(**account, displayName='Burst50 test',
                           captchaId=captcha['captchaId'], captchaAnswer=str(sum(map(int, numbers.groups())))))
        response.raise_for_status()
        account['token'] = response.json()['token']
        accounts.append(account)
        save(out / 'accounts.private.json', accounts)
    print('Prepared 50 burst accounts plus one calibration/query account', flush=True)
    images = [(out / f'meal-{i}.jpg').read_bytes() for i in range(1, 6)]
    start_usage = metrics(out, 'before')

    def upload(index, barrier=None, key=None):
        payload = images[index % 5]
        key = key or f'burst50-{out.name[-6:]}-{index:02}'
        client = requests.Session()
        client.trust_env = False
        prepared = client.prepare_request(requests.Request('POST', BASE + '/api/meals/analysis-requests',
            files={'image': (f'meal-{index % 5 + 1}.jpg', payload, 'image/jpeg')}, data={'goal': 'current'},
            headers={'Idempotency-Key': key, 'Authorization': 'Bearer ' + accounts[index]['token']}))
        if barrier:
            barrier.wait(timeout=30)
        started = time.perf_counter()
        value = dict(index=index, key=key, started=started)
        try:
            response = client.send(prepared, timeout=45)
            value.update(httpStatus=response.status_code, retryAfter=response.headers.get('Retry-After'))
            try:
                value['body'] = response.json()
            except ValueError:
                value['body'] = {'unparsedResponse': response.text[:300]}
        except requests.RequestException as error:
            value.update(httpStatus=0, error=type(error).__name__)
        finally:
            client.close()
        value['submitSeconds'] = time.perf_counter() - started
        return value

    def wait_one(value, deadline):
        # Resolve ambiguous submissions by key without submitting them again.
        state = value.get('body', {})
        while time.perf_counter() < deadline:
            if state.get('status') in ('SUCCEEDED', 'FAILED', 'RETRYABLE'):
                value.update(final=state, completed=time.perf_counter(), e2eSeconds=time.perf_counter() - value['started'])
                return value
            time.sleep(1)
            response = request('GET', '/api/meals/analysis-requests/' + value['key'], accounts[value['index']])
            if response.status_code == 200:
                state = response.json()
            elif response.status_code == 404 and value['httpStatus'] == 0:
                value['final'] = {'status': 'UNKNOWN_NOT_FOUND'}
                return value
        value['final'] = {'status': 'CLIENT_TIMEOUT', 'lastObserved': state.get('status')}
        return value

    calibration = wait_one(upload(50), time.perf_counter() + 180)
    save(out / 'calibration.json', calibration)
    calibration_usage = metrics(out, 'calibration')
    if calibration.get('final', {}).get('status') != 'SUCCEEDED':
        raise RuntimeError('Calibration failed; no burst was sent')
    average_tokens = calibration_usage['input'] + calibration_usage['output']
    if calibration_usage['missingUsage'] or average_tokens * 51 > 240000:
        raise RuntimeError('Calibration cannot support the 300k token budget; no burst was sent')
    print(f'Calibration successful: {average_tokens:.0f} token; launching exactly 50 requests', flush=True)
    stopped = threading.Event()
    samples = []
    budget_abort = []

    def monitor():
        counter = 0
        while not stopped.is_set():
            point = {'time': time.perf_counter()}
            try:
                started = time.perf_counter()
                response = request('GET', '/api/meals?page=0&size=10', accounts[50], timeout=10)
                point.update(queryStatus=response.status_code, queryMs=(time.perf_counter() - started) * 1000)
                point['recordCount'] = response.json().get('totalElements') if response.status_code == 200 else None
                point['usage'] = metrics(out, f'live-{counter % 2}')
                if sum(point['usage'][x] for x in ('input', 'output')) >= 220000 or point['usage']['missingUsage']:
                    budget_abort.append(point)
                    compose(out, 'stop', 'backend')
                    stopped.set()
                if counter % 3 == 0:
                    point['resources'] = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                                                  project(out) + '-backend-1']).strip()
            except Exception as error:
                point['error'] = type(error).__name__
            samples.append(point)
            save(out / 'health-samples.json', samples)
            counter += 1
            stopped.wait(1)

    observer = threading.Thread(target=monitor, daemon=True)
    observer.start()
    try:
        barrier = threading.Barrier(51)
        with ThreadPoolExecutor(max_workers=50) as pool:
            pending = [pool.submit(upload, index, barrier) for index in range(50)]
            barrier.wait(timeout=30)
            first = [future.result() for future in pending]
        save(out / 'first-submissions.json', first)
        accepted = [x for x in first if x['httpStatus'] in (200, 202)]
        ambiguous = [x for x in first if x['httpStatus'] == 0 or x['httpStatus'] >= 500]
        rejected = [x for x in first if x['httpStatus'] == 429]
        print(f'First wave: accepted={len(accepted)}, 429={len(rejected)}, other={50-len(accepted)-len(rejected)}', flush=True)
        deadline = time.perf_counter() + 300
        with ThreadPoolExecutor(max_workers=50) as pool:
            completed = list(pool.map(lambda value: wait_one(value, deadline), accepted + ambiguous))
        save(out / 'first-completed.json', completed)
        first_usage = metrics(out, 'first-final')
        first_audit, _ = audit(out, 'first-final')
        print(f'First wave terminal states: {[x.get("final",{}).get("status") for x in completed]}', flush=True)
        recovery = []
        projected = first_usage['input'] + first_usage['output'] + len(rejected) * average_tokens * 1.5
        if rejected and not budget_abort and projected < 240000 and all(x.get('final', {}).get('status') == 'SUCCEEDED' for x in completed):
            print(f'Budget permits a separate paced recovery for {len(rejected)} rejected uploads', flush=True)
            for offset in range(0, len(rejected), 4):
                batch = rejected[offset:offset+4]
                time.sleep(max(3, max(int(x.get('retryAfter') or 1) for x in batch)))
                with ThreadPoolExecutor(max_workers=4) as pool:
                    values = list(pool.map(lambda x: upload(x['index'], key=x['key']), batch))
                    deadline = time.perf_counter() + 180
                    values = list(pool.map(lambda x: wait_one(x, deadline) if x['httpStatus'] in (200,202) else x, values))
                recovery.extend(values)
                save(out / 'recovery.json', recovery)
                usage = metrics(out, 'recovery-progress')
                if usage['input'] + usage['output'] > 200000 or usage['missingUsage'] or any(x.get('final', {}).get('status') != 'SUCCEEDED' for x in values):
                    break
        time.sleep(5)
        final_usage = metrics(out, 'final')
        database, queues = audit(out, 'final')
        after = identities(out)
        save(out / 'containers-after.json', after)
        starts = [x['started'] for x in first]
        successful = [x for x in completed if x.get('final', {}).get('status') == 'SUCCEEDED']
        query = [x for x in samples if 'queryStatus' in x]
        durations = sorted(x['queryMs'] for x in query)
        summary = dict(firstWave=dict(attempted=50, accepted=len(accepted), rejected429=len(rejected),
            other=50-len(accepted)-len(rejected), launchSpreadMs=(max(starts)-min(starts))*1000,
            completedSuccessfully=len(successful),
            lastAcceptedCompletionSeconds=max((x.get('completed', min(starts)) - min(starts) for x in completed), default=0),
            e2eSeconds=[x['e2eSeconds'] for x in successful], database=first_audit, usage=first_usage),
            recovery=dict(attempted=len(recovery), succeeded=sum(x.get('final', {}).get('status') == 'SUCCEEDED' for x in recovery)),
            queries=dict(samples=len(query), succeeded=sum(x['queryStatus'] == 200 and x['recordCount'] == 1 for x in query),
                         p95Ms=durations[max(0, math.ceil(len(durations)*.95)-1)] if durations else None,
                         maxMs=max(durations, default=None)),
            usage=final_usage, totalKnownTokens=final_usage['input'] + final_usage['output'],
            database=database, budgetAborted=bool(budget_abort),
            noRestarts=all(x['restartCount']==0 and x['status']=='running' and not x['oomKilled'] for x in after)
                       and {x['id'] for x in before} == {x['id'] for x in after},
            queueOutput=queues, note='First wave and paced recovery are separate. Query account has one real calibration meal.')
        save(out / 'summary.json', summary)
        print(json.dumps({k:v for k,v in summary.items() if k not in ('firstWave','database','queueOutput')}, ensure_ascii=False), flush=True)
    finally:
        stopped.set()
        observer.join(timeout=15)
        (out / 'backend-runtime.log').write_text(compose(out, 'logs', '--no-color', 'backend'), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare','start','run','finalize','cleanup'])
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    if out.parent != (ROOT / 'loadtests/results').resolve() or not re.fullmatch(r'real-burst50-\d{8}-\d{6}', out.name):
        raise RuntimeError('Output must be a new loadtests/results/real-burst50-YYYYMMDD-HHMMSS directory')
    if args.action == 'prepare':
        prepare(out)
    elif args.action == 'start':
        if command(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project(out)]).strip():
            raise RuntimeError('This project already has containers')
        (out / 'startup.log').write_text(compose(out, 'up','-d','--wait','--wait-timeout','150'), encoding='utf-8')
        for _ in range(90):
            try:
                if requests.get(BACK + '/actuator/health', timeout=3).json().get('status') == 'UP':
                    print('Isolated backend ready', flush=True)
                    return
            except (requests.RequestException, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError('Backend not healthy')
    elif args.action == 'run':
        if (out / 'calibration.json').exists() or (out / 'first-submissions.json').exists():
            raise RuntimeError('Never rerun paid calls in the same evidence directory')
        run(out)
    elif args.action == 'finalize':
        container = project(out) + '-backend-1'
        memory = command(['docker','exec',container,'sh','-c',
                          'sha256sum /app/app.jar; cat /sys/fs/cgroup/memory.peak; cat /sys/fs/cgroup/memory.events'])
        (out / 'runtime-memory-and-jar.txt').write_text(memory, encoding='utf-8')
        failures = compose(out,'exec','-T','postgres','psql','-U','mealcheck','-d','mealcheck','-Atc',
            "SELECT coalesce(json_agg(t),'[]') FROM (SELECT task_key,phase,error_code,error_summary,recoverable FROM task_failure_events ORDER BY task_key) t;")
        save(out / 'failure-events.json', json.loads(failures.strip()))
        summary = json.loads((out / 'summary.json').read_text(encoding='utf-8'))
        save(out / 'summary-original.json', summary)
        summary['checks'] = dict(
            all50Dispatched=summary['firstWave']['attempted']==50,
            launchedWithinOneSecond=summary['firstWave']['launchSpreadMs']<1000,
            allAcceptedSucceeded=summary['firstWave']['accepted']==summary['firstWave']['completedSuccessfully'],
            noDuplicateRecords=summary['database']['duplicateUsers']==0,
            noOutstandingTasks=summary['database']['outstanding']==0,
            noUnpublishedOutbox=summary['database']['outboxUnpublished']==0,
            queriesAvailable=summary['queries']['samples']==summary['queries']['succeeded'],
            noRestarts=summary['noRestarts'])
        summary['passed'] = all(summary['checks'].values())
        summary['usage']['unreconciledFailedAttempts'] = summary['usage']['failedCalls']
        summary['usage']['note'] = ('Known token counters omit transport failures without a response; missingUsage=0 does not prove all attempts have usage. Global DB spent includes conservative settlement for unknown usage, not the provider bill.')
        summary['recovery']['skipReason'] = 'Accepted tasks failed; do not add paid recovery load'
        save(out / 'summary.json', summary)
        token = credentials()['MEALCHECK_AI_API_KEY']
        leaked = [f.name for f in out.iterdir() if f.suffix in ('.json','.txt','.log','.py')
                  and token in f.read_text(encoding='utf-8',errors='replace')]
        save(out / 'credential-scan.json', dict(providerKeyFoundInFiles=leaked))
        print('Final evidence saved; passed=' + str(summary['passed']), flush=True)
    else:
        # Scope deletion to the exact, fresh namespace and its explicit Compose file.
        ids = command(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project(out)]).split()
        if ids:
            inspected = json.loads(command(['docker','inspect',*ids]))
            if any(x['Config']['Labels'].get('com.docker.compose.project') != project(out) for x in inspected):
                raise RuntimeError('Cleanup label mismatch')
            (out / 'cleanup.log').write_text(compose(out, 'down','--volumes'), encoding='utf-8')
        remaining = command(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project(out)]).strip()
        save(out / 'cleanup.json', dict(project=project(out), remainingContainers=remaining, cleaned=not bool(remaining)))
        print('Isolated test environment cleaned', flush=True)


if __name__ == '__main__':
    main()
