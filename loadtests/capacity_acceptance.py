"""Seed and verify only deploy/compose.acceptance.yml's scratch stack; never uses production credentials."""
import argparse
import base64
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROJECT = 'mealcheck-capacity-acceptance'
COMPOSE = ['docker', 'compose', '-p', PROJECT, '-f', str(ROOT / 'deploy/compose.acceptance.yml')]
BASE = 'http://127.0.0.1:18081'
STUB = 'http://127.0.0.1:19082'


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=kwargs.pop('timeout', 90), **kwargs)


def http(path, token=None, body=None, base=BASE):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = f'Bearer {token}'
    request = urllib.request.Request(base + path, headers=headers, data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        try: return error.code, json.loads(error.read())
        finally: error.close()


def verify_stack():
    # Verify both the Compose namespace and the known fixture key before any SQL or fault injection.
    ids = command(COMPOSE + ['ps', '-q', 'backend', 'postgres']).stdout.split()
    if len(ids) != 2: raise RuntimeError('Start the dedicated compose.acceptance.yml stack first')
    result = command(['docker', 'inspect', *ids])
    if result.returncode: raise RuntimeError(result.stderr)
    containers = json.loads(result.stdout)
    for container in containers:
        if container['Config']['Labels'].get('com.docker.compose.project') != PROJECT:
            raise RuntimeError('Refusing a non-acceptance container')
    backend = next(c for c in containers if c['Config']['Labels']['com.docker.compose.service'] == 'backend')
    env = dict(value.split('=', 1) for value in backend['Config']['Env'])
    if env.get('MEALCHECK_AI_API_KEY') != 'capacity-only' or env.get('MEALCHECK_AI_BASE_URL') != 'http://model-stub:9082/v1':
        raise RuntimeError('Refusing to seed or inject faults outside the model fixture')
    return backend['Id']


def wait_ready(token=None):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            status, _ = http('/api/meals?size=1' if token else '/actuator/health', token,
                             base=BASE if token else 'http://127.0.0.1:19091')
            if status == 200: return
        except (OSError, ValueError): pass
        time.sleep(.5)
    raise RuntimeError('Acceptance backend did not become ready')


def sql(statement):
    result = command(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'mealcheck', '-d', 'mealcheck', '-At', '-v', 'ON_ERROR_STOP=1'], input=statement)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout.strip()


def seed(output, count, meals, messages):
    sys.path.insert(0, str(ROOT / 'deploy/scripts'))
    import smoke
    accounts = []
    for _ in range(count):
        captcha = smoke.check(BASE, '/api/auth/captcha')
        a, b = map(int, re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?', captcha['question']).groups())
        import uuid
        username = 'capacity_' + uuid.uuid4().hex[:20]
        auth = smoke.check(BASE, '/api/auth/register', method='POST', body={'username': username, 'password': 'Capacity-' + uuid.uuid4().hex,
             'captchaId': captcha['captchaId'], 'captchaAnswer': str(a + b)})
        payload = auth['token'].split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        uid = int(claims['uid'])
        if uid < 1 or claims['sub'] != username: raise RuntimeError('Unexpected test account identity')
        conversation = int(sql(f"INSERT INTO assistant_conversations(user_id,title) VALUES ({uid},'capacity-history') RETURNING id;").splitlines()[0])
        sql(f"""
          INSERT INTO meal_records(user_id,score,goal,detected_foods_json,risk_tags_json,category_counts_json,advice,created_at)
          SELECT {uid},65+n%35,'balanced','[{{"name":"米饭","category":"staple","confidence":0.9}}]','["vegetable_low"]','{{"staple":1}}',
              repeat('capacity advice ',100),CURRENT_TIMESTAMP-(n%89)*INTERVAL '1 day' FROM generate_series(1,{meals}) n;
          INSERT INTO assistant_conversation_messages(conversation_id,role,text,created_at)
          SELECT {conversation},CASE WHEN n%2=0 THEN 'assistant' ELSE 'user' END,'capacity-message-'||n,
              CURRENT_TIMESTAMP-({messages}-n)*INTERVAL '1 second' FROM generate_series(1,{messages}) n;
        """)
        accounts.append({'token': auth['token'], 'conversationId': conversation, 'meals': meals})
    sql('ANALYZE meal_records; ANALYZE assistant_conversation_messages;')
    path = output / 'accounts.json'
    path.write_text(json.dumps(accounts), encoding='utf-8')
    if os.name != 'nt': path.chmod(0o600)
    return path, accounts


def scrape():
    with urllib.request.urlopen('http://127.0.0.1:19091/actuator/prometheus', timeout=5) as response:
        return response.read().decode()


def bind_plan_parameters(query, uid):
    values = {'userId': str(uid), 'since': "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')-INTERVAL '90 days'",
              'storageZone': "'UTC'", 'zone': "'Asia/Shanghai'"}
    def bind(match):
        name = match.group(1)
        if name not in values:
            raise RuntimeError(f'Unbound diagnostic SQL parameter: {name}')
        return values[name]
    return re.sub(r'(?<!:):([A-Za-z][A-Za-z0-9_]*)', bind, query)


def capture_query_plans(accounts, output):
    # Read the actual repository SQL so acceptance evidence follows future query changes.
    payload = accounts[0]['token'].split('.')[1]
    uid = int(json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['uid'])
    if uid < 1: raise RuntimeError('Invalid fixture user')
    source = (ROOT / 'backend/src/main/java/com/example/mealcheck/repository/MealRecordRepository.java').read_text(encoding='utf-8')
    queries = re.findall(r'@Query\(value\s*=\s*"""(.*?)""",\s*nativeQuery\s*=\s*true\)\s*[^;]+?\b(trendSummary|trendDailyScores|trendFacts)\(', source, re.S)
    if {name for _, name in queries} != {'trendSummary', 'trendDailyScores', 'trendFacts'}:
        raise RuntimeError('Could not identify all actual trend queries')
    plans = ['Sequential diagnostic EXPLAIN (ANALYZE, BUFFERS) on synthetic fixture data; cache is not reset between statements. Not a controlled before/after benchmark.']
    queries.insert(0, (f'SELECT * FROM meal_records WHERE user_id={uid} AND created_at>CURRENT_TIMESTAMP-INTERVAL \'90 days\' ORDER BY created_at', 'legacyFullEntityRead'))
    for query, name in queries:
        query = bind_plan_parameters(query, uid)
        plans.append(name + '\n' + sql('EXPLAIN (ANALYZE, BUFFERS) ' + query + ';'))
    (output / 'query-plans.txt').write_text('\n\n'.join(plans) + '\n', encoding='utf-8')


def metric(text, name):
    return sum(float(line.split()[-1]) for line in text.splitlines() if re.match(re.escape(name) + r'(\{|\s)', line))


def queue_p95(before, after):
    def buckets(text):
        result = {}
        for line in text.splitlines():
            if line.startswith('mealcheck_analysis_queue_wait_seconds_bucket{'):
                bound = float(re.search(r'le="([^"]+)"', line).group(1))
                result[bound] = result.get(bound, 0) + float(line.split()[-1])
        return result
    old, new = buckets(before), buckets(after)
    count = new.get(math.inf, 0) - old.get(math.inf, 0)
    if count <= 0: return None
    for bound in sorted(new):
        if new[bound] - old.get(bound, 0) >= count * .95: return bound if math.isfinite(bound) else None


def run_k6(name, script, settings, output, backend):
    env = os.environ.copy();env.update({k: str(v) for k, v in settings.items()})
    env.update(BASE_URL=BASE, RESULT_FILE=str(output / f'{name}.json'))
    before = scrape();samples = [];stop = threading.Event()
    def sample():
        while not stop.is_set():
            try:
                result = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', backend], timeout=10)
                stat = json.loads(result.stdout) if result.returncode == 0 else None
                current = scrape()
                samples.append({'at': time.time(), 'container': stat, 'jvmHeapBytes': sum(float(line.split()[-1]) for line in current.splitlines()
                                if line.startswith('jvm_memory_used_bytes{') and 'area="heap"' in line)})
            except (OSError, ValueError, subprocess.TimeoutExpired): pass
            stop.wait(1)
    thread = threading.Thread(target=sample, daemon=True);thread.start()
    try: result = command([shutil.which('k6') or 'k6', 'run', '--quiet', str(ROOT / 'loadtests' / script)], env=env, timeout=300)
    finally: stop.set();thread.join(timeout=15)
    (output / f'{name}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    after = scrape();(output / f'{name}-metrics.prom').write_text(after, encoding='utf-8')
    report = json.loads((output / f'{name}.json').read_text())['capacity']
    report.update(exitCode=result.returncode, resourceSamples=samples, sampledJvmHeapPeakBytes=max((s['jvmHeapBytes'] for s in samples),default=None),
        queueWaitP95UpperBoundSeconds=queue_p95(before, after),
        estimatedCostDelta=metric(after,'mealcheck_ai_estimated_cost_total')-metric(before,'mealcheck_ai_estimated_cost_total'),
        databaseAcquireSeconds=metric(after,'hikaricp_connections_acquire_seconds_sum')-metric(before,'hikaricp_connections_acquire_seconds_sum'),
        httpRetries=metric(after,'mealcheck_ai_http_retries_total')-metric(before,'mealcheck_ai_http_retries_total'),
        modelCalls=http('/__stats', base=STUB)[1]['calls'])
    # Exact SQL totals complement sampled application/container metrics.
    report['databaseTasks'] = json.loads(sql("SELECT json_build_object('pending',count(*) FILTER(WHERE status='PENDING'),'processing',count(*) FILTER(WHERE status='PROCESSING'),'failed',count(*) FILTER(WHERE status='FAILED')) FROM meal_analysis_requests;"))
    report['passed'] = bool(report['passed']) and result.returncode == 0
    return report


def dependency_fault(service, token):
    command(COMPOSE + ['stop', service], timeout=45).check_returncode()
    try:
        status, body = http('/api/meals?size=1', token)
        failed_closed = status == 503 and body.get('code') == 'AUTH_DEPENDENCY_UNAVAILABLE'
    finally:
        command(COMPOSE + ['start', service], timeout=45).check_returncode()
        wait_ready(token)
    return {'passed': failed_closed, 'statusDuringOutage': status, 'code': body.get('code'), 'recoveredWithSameToken': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--accounts', type=int, default=5)
    parser.add_argument('--meals-per-user', type=int, default=10000)
    parser.add_argument('--messages-per-user', type=int, default=2000)
    parser.add_argument('--read-duration', default='30s')
    parser.add_argument('--fault-checks', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.accounts <= 20 or not 1 <= args.meals_per_user <= 100000 or not 80 <= args.messages_per_user <= 100000:
        parser.error('Bounded fixture sizes required')
    output = args.output.resolve();output.mkdir(parents=True, exist_ok=True)
    if (output / 'accounts.json').exists(): parser.error('Use a new output directory to keep evidence and test identities separate')
    backend = verify_stack();wait_ready()
    path, accounts = seed(output,args.accounts,args.meals_per_user,args.messages_per_user)
    from PIL import Image
    image = output / 'large-image.jpg';Image.effect_noise((4096,4096),35).convert('RGB').save(image,quality=80)
    cases = {}
    try:
        http('/__control', body={'delay': .05}, base=STUB)
        cases['queries'] = run_k6('queries','query-capacity.js',{'ACCOUNTS_FILE':path,'DURATION':args.read_duration,'VUS':args.accounts},output,backend)
        for name, control in [('large-images',{'delay':.05}),('slow-provider',{'delay':1}),('provider-429',{'delay':.05,'errorFirst':2,'retryAfter':1}),('admission-overload',{'delay':1})]:
            http('/__control',body=control,base=STUB)
            cases[name] = run_k6(name,'analysis-capacity.js',{'ACCOUNTS_FILE':path,'IMAGE_FILE':image,'MODE':'burst','BURST_TASKS':args.accounts,
                'MAX_VUS':args.accounts if name=='admission-overload' else min(2,args.accounts),'PREALLOCATED_VUS':args.accounts if name=='admission-overload' else min(2,args.accounts),
                'MAX_FAILURE_RATE':.8 if name=='admission-overload' else .01,'MIN_COMPLETED':1 if name=='admission-overload' else args.accounts,
                'DURATION':'30s','TASK_TIMEOUT_SECONDS':120,'HTTP_TIMEOUT_SECONDS':20,'POLL_SECONDS':.5,'E2E_P95_MS':60000},output,backend)
            if name=='admission-overload':
                case=cases[name]
                case['passed']=case['passed'] and case['failed']==0 and case['timedOut']==0 and case['unresolved']==0 and case['rejected']==case['submit429']
                case['note']='Deliberate decode saturation: allow at most 80% explicit HTTP 429 admission rejection; accepted tasks must succeed, with no execution failure, timeout or unknown result.'
            if name=='provider-429':
                cases[name]['passed']=cases[name]['passed'] and cases[name]['httpRetries']>=2
        if args.fault_checks:
            for service in ('redis','postgres'): cases[service+'-outage']=dependency_fault(service,accounts[0]['token'])
    finally: http('/__control',body={'delay':.05},base=STUB)
    report = {'passed':all(case['passed'] for case in cases.values()),'fixture':{'accounts':args.accounts,'meals':args.accounts*args.meals_per_user,
              'messages':args.accounts*args.messages_per_user,'imageDimensions':[4096,4096],'imageBytes':image.stat().st_size,'backendHeapLimitMiB':512,'backendCpuLimit':2},
              'cases':cases,'limitations':'Local synthetic data, bounded short runs and a model substitute. Heap/container values are sampled; queue P95 is a histogram bucket upper bound. This does not establish provider capacity or recognition accuracy.'}
    report['queryPlansCaptured'] = False
    try:
        capture_query_plans(accounts, output)
        report['queryPlansCaptured'] = True
    finally:
        # Preserve completed phases even if a later evidence query fails.
        report['passed'] = report['passed'] and report['queryPlansCaptured']
        (output / 'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'cases':{k:v['passed'] for k,v in cases.items()},'evidence':str(output / 'acceptance.json')}))
    if not report['passed']: raise SystemExit(1)


if __name__ == '__main__': main()
