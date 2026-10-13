"""Reproducible memory diagnosis and consumer sweep on the isolated local stub stack."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import threading
import time
import math
import re
from PIL import Image

spec = importlib.util.spec_from_file_location('optimization', Path(__file__).with_name('run-capacity-optimization.py'))
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)
b = o.b
parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=['memory', 'sweep', 'mixed-ai', 'soak'], required=True)
parser.add_argument('--label', required=True)
parser.add_argument('--jvm', default='-XX:MaxRAMPercentage=45.0 -XX:+ExitOnOutOfMemoryError')
parser.add_argument('--consumers', type=int, nargs='+', default=[4, 6])
parser.add_argument('--rates', type=int, nargs='+', default=[20, 30, 40])
parser.add_argument('--duration', default='900s')
parser.add_argument('--fresh', action='store_true', help='Recreate backend before fixture setup for a cold-start memory comparison')
parser.add_argument('--decode-wait-ms', type=int, default=8000)
parser.add_argument('--weekly-tasks', type=int, default=40)
parser.add_argument('--weekly-rate', type=int, default=0, help='New weekly reports per minute throughout the mixed run')
parser.add_argument('--assistant-rate', type=int, default=12)
parser.add_argument('--max-vus', type=int, default=160,
                    help='Provision enough clients to observe 40/min even at the 180-second task deadline')
parser.add_argument('--skip-bursts', action='store_true', help='Repeat only sustained rates after an existing burst comparison')
args = parser.parse_args()
if args.mode in ('mixed-ai', 'soak') and len(args.consumers) != 1:
    parser.error('Mixed/soak runs require exactly one --consumers value; use separate runs for fresh fixtures')
duration_match=re.fullmatch(r'(\d+)(s|m|h)',args.duration)
if not duration_match: parser.error('Duration must be an integer followed by s, m or h')
duration_seconds=int(duration_match[1])*{'s':1,'m':60,'h':3600}[duration_match[2]]
weekly_accounts=max(args.weekly_tasks,math.ceil(duration_seconds/60*args.weekly_rate)+2)
account_count = 40+weekly_accounts if args.mode == 'mixed-ai' else 40
o.env['BACKEND_JAVA_TOOL_OPTIONS'] = args.jvm
o.env['MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS'] = str(args.decode_wait_ms)
b.META.update(schema='capacity-next-v1', mode=args.mode, label=args.label, jvm=args.jvm,
              consumers=args.consumers, rates=args.rates, duration=args.duration,
              recordsPerAccount=1 if args.mode == 'memory' else 1000, decodeWaitMillis=args.decode_wait_ms,
              weeklyTasks=args.weekly_tasks, weeklyRate=args.weekly_rate, assistantRate=args.assistant_rate,
              maxVUs=args.max_vus, skipBursts=args.skip_bursts)
b.META['accountCount'] = account_count
METRIC_PREFIXES = ('jvm_memory_', 'jvm_gc_', 'jvm_buffer_', 'jvm_threads_live_',
                   'process_cpu_', 'hikaricp_connections_', 'mealcheck_analysis_',
                   'mealcheck_remote_', 'mealcheck_ai_', 'mealcheck_weekly_', 'mealcheck_failure_')


def runtime():
    values = json.loads(b.command(['docker', 'inspect', 'mealcheck-capacity-backend-1']))
    v = values[0]
    settings = dict(s.split('=', 1) for s in v['Config']['Env'])
    if v['Config']['Labels'].get('com.docker.compose.project') != 'mealcheck-capacity' or settings.get('MEALCHECK_AI_BASE_URL') != 'http://model-stub:9082/v1':
        raise RuntimeError('Refusing a non-isolated, non-stub environment')
    return {'at': b.stamp(), 'containerId': v['Id'], 'imageId': v['Image'], 'memoryBytes': v['HostConfig']['Memory'],
            'nanoCpus': v['HostConfig']['NanoCpus'], 'restartCount': v['RestartCount'],
            'oomKilled': v['State']['OOMKilled'], 'health': v['State'].get('Health', {}).get('Status'),
            'concurrency': settings.get('MEALCHECK_ANALYSIS_CONCURRENCY'), 'jvm': settings.get('JAVA_TOOL_OPTIONS'),
            'decodeWaitMillis': settings.get('MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS'),
            'leaseSeconds': settings.get('MEALCHECK_ANALYSIS_LEASE_SECONDS'),
            'maxConcurrentDecodes': settings.get('MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES'),
            'maxPendingDecodes': settings.get('MEALCHECK_UPLOAD_MAX_PENDING_DECODES'),
            'maxOutstanding': settings.get('MEALCHECK_ANALYSIS_MAX_OUTSTANDING'),
            'maxOutstandingPerUser': settings.get('MEALCHECK_ANALYSIS_MAX_OUTSTANDING_PER_USER'),
            'globalAiConcurrency': settings.get('MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS'),
            'globalAiStartsPerMinute': settings.get('MEALCHECK_AI_GLOBAL_MAX_STARTS_PER_MINUTE'),
            'globalAiDailyCalls': settings.get('MEALCHECK_AI_GLOBAL_DAILY_CALLS')}


def model_control(**values):
    # Use only the private test stub; no model endpoint is exposed on the host.
    source = "import urllib.request; r=urllib.request.Request('http://127.0.0.1:9082/__control',data=" + repr(json.dumps(values).encode()) + ",headers={'Content-Type':'application/json'}); print(urllib.request.urlopen(r).read().decode())"
    return b.command(['docker', 'exec', 'mealcheck-capacity-model-stub-1', 'python', '-c', source])


def diagnostics():
    code = "import urllib.request; s=urllib.request.urlopen('http://backend:9091/actuator/prometheus',timeout=3).read().decode(); print('\\n'.join(l for l in s.splitlines() if l.startswith(" + repr(METRIC_PREFIXES) + ")))"
    with (b.OUT/'jvm.jsonl').open('w', encoding='utf-8') as stream:
        while not b.STOP.is_set():
            data = {'at': b.stamp(), 'phase': b.CURRENT}
            try:
                fmt = '{"id":"{{.Id}}","restartCount":{{.RestartCount}},"oomKilled":{{.State.OOMKilled}},"status":"{{.State.Status}}"}'
                data['runtime'] = json.loads(b.command(['docker','inspect','--format',fmt,'mealcheck-capacity-backend-1'],10))
                if data['runtime']['oomKilled'] or data['runtime']['restartCount']:
                    (b.OUT/'STOP_REQUESTED').write_text('Backend OOM/restart detected; stop adding load', encoding='utf-8')
                data['prometheus'] = b.command(['docker', 'exec', 'mealcheck-capacity-model-stub-1', 'python', '-c', code], 10)
                data['os'] = b.command(['docker', 'exec', 'mealcheck-capacity-backend-1', 'sh', '-c',
                    'cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.stat /proc/1/status; du -sk /app/upload-temp /tmp'], 10)
            except Exception as error:
                data['error'] = str(error)
            stream.write(json.dumps(data, ensure_ascii=False)+'\n'); stream.flush()
            b.STOP.wait(5)


def run_phase(name, **parameters):
    b.META['activePhase'] = name; b.save()
    before_budget=global_budget()
    code = o.phase(name, **parameters)
    if args.mode == 'mixed-ai' and name != 'seed':
        b.META['phases'][-1]['weeklyJobsAfterObservation'] = weekly_jobs()
        drain_weekly()
    stats_code = "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:9082/__stats').read().decode())"
    b.META['phases'][-1]['modelStats'] = json.loads(b.command(['docker','exec','mealcheck-capacity-model-stub-1','python','-c',stats_code]))
    b.META['phases'][-1]['globalBudgetBefore']=before_budget
    b.META['phases'][-1]['globalBudgetAfter']=global_budget()
    b.META['activePhase'] = None; b.save()
    return code


def global_budget():
    query="SELECT json_build_object('attempts',coalesce(sum(attempts),0),'reserved',coalesce(sum(reserved),0),'spent',coalesce(sum(spent),0)) FROM ai_budget_daily WHERE quota_key='global';"
    return json.loads(b.command(['docker','exec','mealcheck-capacity-postgres-1','psql','-U','mealcheck','-d','mealcheck','-At','-c',query]))


def weekly_jobs():
    ids=','.join(map(str,b.META.get('testUserIds',[])))
    if not ids: return []
    query=f"SELECT coalesce(json_agg(json_build_object('id',id,'userId',user_id,'status',status,'attempts',attempts,'lastError',last_error,'createdAt',created_at,'updatedAt',updated_at)),'[]'::json) FROM weekly_report_jobs WHERE user_id IN ({ids});"
    return json.loads(b.command(['docker','exec','mealcheck-capacity-postgres-1','psql','-U','mealcheck','-d','mealcheck','-At','-c',query]))


def drain_weekly():
    deadline=time.monotonic()+900
    while time.monotonic()<deadline:
        jobs=weekly_jobs()
        if all(j['status'] not in ('PENDING','RUNNING','RETRY') for j in jobs):
            b.META['weeklyJobsBeforeCleanup']=jobs; b.save(); return
        time.sleep(3)
    raise RuntimeError('Dedicated weekly jobs did not drain; preserve account data for investigation')


def idle(name, seconds):
    b.CURRENT = name
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if (b.OUT/'STOP_REQUESTED').exists():
            raise InterruptedError('User requested stopping the run')
        time.sleep(min(1, deadline - time.monotonic()))


def fixture_ids():
    ids = []
    for account in json.loads(b.ACCOUNTS.read_text(encoding='utf-8')):
        encoded = account['token'].split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
        if not claims['sub'].startswith('capacity_') or not isinstance(claims['uid'], int):
            raise RuntimeError('Unexpected test account identity')
        ids.append(claims['uid'])
    b.META['testUserIds'] = ids; b.save()


def main():
    print(f'EVIDENCE {b.OUT}', flush=True)
    b.META['started'] = b.stamp()
    b.META['runtimeBefore'] = runtime()
    b.META['initialQueues'] = o.drain()
    b.META['k6Version'] = b.command([b.K6, 'version']).strip()
    original = b.model_env(json.loads(b.command(['docker', 'inspect', 'mealcheck-capacity-model-stub-1']))[0])
    b.META['originalModel'] = original
    snapshots = b.OUT/'scripts'; snapshots.mkdir()
    for path in ['loadtests/run-capacity-next.py', 'loadtests/run-local-benchmark.py', 'loadtests/run-capacity-optimization.py',
                 'loadtests/analysis-capacity.js', 'loadtests/capacity-mixed.js', 'loadtests/read-multi.js',
                 'backend/src/main/java/com/example/mealcheck/service/ImageUploadValidationService.java',
                 'backend/src/main/java/com/example/mealcheck/service/MealAnalysisLeaseService.java',
                 'backend/src/main/java/com/example/mealcheck/service/MealAnalysisRequestStore.java',
                 'backend/src/main/java/com/example/mealcheck/service/MealAnalysisRequestService.java',
                 'backend/src/main/java/com/example/mealcheck/service/AnalysisAdmissionException.java',
                 'backend/src/main/java/com/example/mealcheck/controller/MealController.java',
                 'backend/src/main/java/com/example/mealcheck/controller/GlobalExceptionHandler.java',
                 'backend/src/main/java/com/example/mealcheck/service/AiCallPermitStore.java',
                 'backend/src/main/java/com/example/mealcheck/service/AiCallConcurrency.java',
                 'backend/src/main/java/com/example/mealcheck/service/AiBudgetStore.java',
                 'backend/src/main/java/com/example/mealcheck/service/AiHttpTransport.java',
                 'backend/src/main/resources/db/migration/V23__global_admission_and_ai_calls.sql',
                 'backend/src/main/java/com/example/mealcheck/config/AppProperties.java', 'backend/Dockerfile',
                 'loadtests/capacity-ai-mixed.js', 'deploy/scripts/model_stub.py',
                 'frontend/src/utils/mealAnalysisJob.js',
                 'deploy/compose.capacity.yml', 'docker-compose.prod.yml']:
        shutil.copyfile(b.ROOT/path, snapshots/Path(path).name)
    large = b.OUT/'large-4096.jpg'
    with Image.open(b.IMAGE) as image:
        image.convert('RGB').resize((4096, 4096), Image.Resampling.BICUBIC).save(large, quality=90)
    variants = [{'path': b.IMAGE.as_posix(), 'type': 'image/png'},
                {'path': (b.ROOT/'tools/datasets/synthetic-meals-100/images/synth-002.png').as_posix(), 'type': 'image/png'},
                {'path': large.as_posix(), 'type': 'image/jpeg'}]
    mixed = b.OUT/'images.json'; mixed.write_text(json.dumps(variants), encoding='utf-8')
    b.META['images'] = [{**v, 'bytes': Path(v['path']).stat().st_size,
                        'sha256': hashlib.sha256(Path(v['path']).read_bytes()).hexdigest()} for v in variants]
    b.save()
    observers = [threading.Thread(target=f, daemon=True) for f in (b.monitor, diagnostics)]
    selected = args.consumers[0]
    try:
        if args.fresh:
            o.env['MEALCHECK_ANALYSIS_CONCURRENCY'] = str(selected)
            b.command(b.COMPOSE+['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'backend'], 110, o.env)
        o.scale(selected); o.model(.05)
        for observer in observers: observer.start()
        b.command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', b.BASE,
                   '--output', str(b.ACCOUNTS), '--count', str(account_count)], 240)
        fixture_ids()
        cohorts = {}
        if args.mode == 'mixed-ai':
            accounts = json.loads(b.ACCOUNTS.read_text(encoding='utf-8'))
            for role, subset in [('analysis', accounts[:40]), ('weekly', accounts[40:])]:
                path = b.OUT/(role+'-accounts.json')
                path.write_text(json.dumps(subset), encoding='utf-8')
                cohorts[role.upper()+'_ACCOUNTS_FILE'] = path.as_posix()
            b.META['cohorts'] = {'analysisUserIds':b.META['testUserIds'][:40],
                                 'weeklyUserIds':b.META['testUserIds'][40:],
                                 'reason':'Weekly snapshots become STALE when the same user adds a meal'}
            b.save()
        if run_phase('seed', MODE='burst', BURST_TASKS=account_count, MAX_VUS=2, PREALLOCATED_VUS=2,
                     DURATION='120s', TASK_TIMEOUT_SECONDS=60, MIN_RECORDS=1):
            raise RuntimeError('Seed failed')
        if args.mode != 'memory': o.expand_records()
        for concurrency in args.consumers:
            if args.mode != 'memory':
                o.env['MEALCHECK_ANALYSIS_CONCURRENCY'] = str(concurrency)
                b.command(b.COMPOSE+['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'backend'], 110, o.env)
            o.scale(concurrency); model_control(delay=.05)
            b.META.setdefault('runtimes', []).append(runtime()); b.save()
            if args.mode == 'memory':
                for repeat in range(1, 4):
                    if run_phase(f'c{concurrency}-large{repeat}', MODE='burst', BURST_TASKS=10, MAX_VUS=10,
                                 PREALLOCATED_VUS=10, DURATION='60s', IMAGE_FILE=large.as_posix(), MIN_RECORDS=1):
                        raise RuntimeError('Large image validation failed; no further load added')
                    idle(f'c{concurrency}-idle{repeat}', 30)
                idle('memory-idle', 60)
                continue
            model_control(delay=5)
            if args.mode == 'sweep':
                burst_failed = False
                for repeat in ([] if args.skip_bursts else range(1, 4)):
                    result = run_phase(f'c{concurrency}-burst{repeat}', MODE='burst', BURST_TASKS=20,
                                       MAX_VUS=20, PREALLOCATED_VUS=20, DURATION='60s')
                    if result:
                        b.META.setdefault('gates', []).append({'concurrency': concurrency,
                            'failedBurst': repeat, 'skippedRates': args.rates,
                            'reason': 'Stop adding load after a failed burst acceptance threshold'})
                        b.save(); burst_failed = True; break
                if burst_failed: continue
                for rate in args.rates:
                    result = run_phase(f'c{concurrency}-r{rate}', RATE_PER_MINUTE=rate, DURATION=args.duration,
                                       MAX_VUS=args.max_vus, PREALLOCATED_VUS=args.max_vus)
                    if result:
                        b.META.setdefault('gates', []).append({'concurrency': concurrency, 'failedRate': rate,
                            'skippedHigherRates': [r for r in args.rates if r > rate],
                            'reason': 'Stop increasing sustained load after a failed acceptance threshold'})
                        b.save(); break
            else:
                script = 'capacity-ai-mixed.js' if args.mode == 'mixed-ai' else 'capacity-mixed.js'
                result = run_phase(f'c{concurrency}-mixed', script=script, RATE_PER_MINUTE=args.rates[0],
                          VUS=50, DURATION=args.duration, IMAGES_FILE=mixed.as_posix(),
                          MAX_VUS=args.max_vus, PREALLOCATED_VUS=args.max_vus,
                          WEEKLY_TASKS=args.weekly_tasks, WEEKLY_RATE=args.weekly_rate, ASSISTANT_RATE=args.assistant_rate, **cohorts)
                if result: raise RuntimeError('Mixed workload failed acceptance thresholds')
    finally:
        b.CURRENT = 'cleanup'
        try:
            model_control(delay=.05)
            b.META['finalQueuesBeforeCleanup'] = o.drain()
            if args.mode == 'mixed-ai': drain_weekly()
            if b.ACCOUNTS.exists():
                b.command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', b.BASE,
                           '--output', str(b.ACCOUNTS), '--cleanup'], 240)
            b.META['cleanup'] = f'Deleted only this run\'s {account_count} dedicated test accounts and associated data'
            for role in ('analysis', 'weekly'):
                (b.OUT/(role+'-accounts.json')).unlink(missing_ok=True)
            b.META['finalQueues'] = b.queue_snapshot()
            ids = ','.join(map(str, b.META.get('testUserIds', [])))
            if ids:
                sql = f"SELECT json_build_object('users',(SELECT count(*) FROM users WHERE id IN ({ids})),'records',(SELECT count(*) FROM meal_records WHERE user_id IN ({ids})),'tasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({ids})));"
                b.META['cleanupVerification'] = json.loads(b.command(['docker','exec','mealcheck-capacity-postgres-1','psql','-U','mealcheck','-d','mealcheck','-At','-c',sql]))
                if any(b.META['cleanupVerification'].values()): raise RuntimeError('Test account data remained after cleanup')
        except Exception as error: b.META['cleanup'] = f'Incomplete: {error}'
        try:
            o.scale(selected); o.env.update(original)
            b.command(b.COMPOSE+['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'model-stub'], 110, o.env)
            b.META['modelRestored'] = True
            b.META['runtimeAfter'] = runtime()
        except Exception as error: b.META['restoreError'] = str(error)
        b.STOP.set()
        for observer in observers:
            if observer.ident is not None: observer.join(30)
        b.META['ended'] = b.stamp(); b.save()
        if b.META.get('restoreError') or not b.META.get('modelRestored') or str(b.META.get('cleanup','')).startswith('Incomplete:'):
            raise RuntimeError('Environment restoration or dedicated-account cleanup is incomplete; inspect manifest')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        b.META['error'] = str(error); b.META['ended'] = b.stamp(); b.save()
        print(f'FAILED: {error}', flush=True); sys.exit(1)
