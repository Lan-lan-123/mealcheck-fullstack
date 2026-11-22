"""Fresh isolated real-model 1/2/4 probes and one 50-upload acceptance run.

Retains per-phase metrics across backend recreations. Reuses only the helper
functions of run-real-burst.py, never its paid run() entry point.
"""

import sys as _auth_sys
from pathlib import Path as _AuthPath
_auth_sys.path.insert(0, str(_AuthPath(__file__).resolve().parents[1] / 'tools'))
import cookie_http as _auth_http
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import math
from pathlib import Path
import re
import threading
import time

import cookie_http as requests

spec = importlib.util.spec_from_file_location('burst', Path(__file__).with_name('run-real-burst.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
TOTAL_NEW_TOKEN_TARGET = 190000
UNKNOWN_ATTEMPT_RESERVE = 7000


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def ready():
    for _ in range(90):
        try:
            if requests.get(b.BACK + '/actuator/health', timeout=3).json().get('status') == 'UP':
                return
        except (requests.RequestException, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Backend failed readiness')


def prepare(out):
    b.prepare(out)
    config = load(out / 'compose.json')
    backend = config['services']['backend']
    backend['environment'].update({
        'MEALCHECK_ANALYSIS_CONCURRENCY': '1',
        'MEALCHECK_ANALYSIS_MAX_OUTSTANDING': '64',
        'MEALCHECK_UPLOAD_MAX_PENDING_DECODES': '62',
        'MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS': '15000',
        'MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS': '3',
        'MEALCHECK_REMOTE_MAX_CONCURRENT_CALLS': '3',
        'MEALCHECK_AI_GLOBAL_DAILY_CALLS': '150',
    })
    # Multipart files and accepted images use disk-backed, isolated volumes.
    backend['tmpfs'] = ['/tmp:rw,size=64m']
    backend['volumes'] += ['upload_tmp:/tmp/multipart', 'uploads:/tmp/uploads']
    config['volumes'] = {'upload_tmp': {}, 'uploads': {}}
    config['services']['upload-init'] = dict(image='eclipse-temurin:17-jre',user='0:0',
        entrypoint=['sh','-c','chown 10001:10001 /tmp/multipart /tmp/uploads'],
        volumes=['upload_tmp:/tmp/multipart','uploads:/tmp/uploads'])
    backend['depends_on']['upload-init']={'condition':'service_completed_successfully'}
    frontend = config['services']['frontend']
    nginx = (b.ROOT / 'frontend/nginx.conf').read_text(encoding='utf-8')
    nginx = nginx.replace('limit_conn mealcheck_uploads 20;', 'limit_conn mealcheck_uploads 64;')
    (out / 'nginx.conf').write_text(nginx, encoding='utf-8')
    frontend['volumes'][0] = f'{(out / "nginx.conf").as_posix()}:/etc/nginx/conf.d/default.conf:ro'
    frontend['mem_limit'] = '1536m'
    frontend['tmpfs'] = ['/tmp:rw,size=1g']
    b.save(out / 'compose.json', config)
    manifest = load(out / 'manifest.json')
    manifest.update(targetAllAccepted=50,targetCompleteWithinSeconds=60,newTokenTarget=TOTAL_NEW_TOKEN_TARGET,
        previousKnownTokens=22189,previousUnknownAttempts=12,previousUnknownReserveTokens=82080,
        note='1/2/4 backend consumer probes; candidate ingress 64 with matching disk/buffer budgets; one paid 50-person burst.')
    b.save(out / 'manifest.json', manifest)


def accounts(out,count=65):
    file = out / 'accounts.private.json'
    values = load(file) if file.exists() else []
    for index in range(len(values),count):
        response = b.request('GET','/api/auth/captcha'); response.raise_for_status()
        captcha = response.json()
        match = re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?',captcha['question'])
        if not match: raise RuntimeError('Unexpected captcha')
        import os
        user=dict(username=f'capacity_{out.name[-6:]}_{index:02}',password='Capacity-test-'+os.urandom(12).hex())
        response=b.request('POST','/api/auth/register',json=dict(**user,displayName='Real capacity test',
            captchaId=captcha['captchaId'],captchaAnswer=str(sum(map(int,match.groups())))))
        response.raise_for_status(); user['token']=response.json()['token']; values.append(user)
        b.save(file,values)
    return values


def phase(out, name, indices, concurrency, users, prior, all_at_once=False):
    directory=out/name; directory.mkdir(exist_ok=False)
    config=load(out/'compose.json')
    env=config['services']['backend']['environment']
    env['MEALCHECK_ANALYSIS_CONCURRENCY']=str(concurrency)
    env['MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS']=str(concurrency+2)
    env['MEALCHECK_REMOTE_MAX_CONCURRENT_CALLS']=str(concurrency+2)
    b.save(out/'compose.json',config)
    (directory/'startup.log').write_text(b.compose(out,'up','-d','--no-deps','--force-recreate','backend'),encoding='utf-8')
    ready()
    before=b.identities(out); b.save(directory/'containers-before.json',before)
    initial=b.metrics(out,'before-'+name)
    images=[(out/f'meal-{i}.jpg').read_bytes() for i in range(1,6)]
    prepared={}
    for ordinal,index in enumerate(indices):
        session=requests.Session();session.trust_env=False
        key=f'{name}-{out.name[-6:]}-{index:02}'
        request=requests.Request('POST',b.BASE+'/api/meals/analysis-requests',
            files={'image':(f'meal-{ordinal%5+1}.jpg',images[ordinal%5],'image/jpeg')}, data={'goal':'current'},
            headers={'Idempotency-Key':key,'Authorization':'Bearer '+users[index]['token']})
        prepared[index]=(session,session.prepare_request(request),key)
    stop=threading.Event(); samples=[]

    def monitor():
        while not stop.is_set():
            item={'monotonic':time.perf_counter()}
            try:
                start=time.perf_counter()
                response=b.request('GET','/api/meals?page=0&size=10',users[64],timeout=10)
                item.update(status=response.status_code,queryMs=(time.perf_counter()-start)*1000)
                item['usage']=b.metrics(out,'live-'+name)
            except Exception as error:item['error']=type(error).__name__
            samples.append(item);b.save(directory/'health-samples.json',samples);stop.wait(1)

    observer=threading.Thread(target=monitor,daemon=True);observer.start()
    barrier=threading.Barrier(len(indices)+1) if all_at_once else None

    def task(index):
        session,request,key=prepared[index]
        if barrier:barrier.wait(timeout=30)
        started=time.perf_counter()
        result={'index':index,'key':key,'started':started,'startedUtc':datetime.now(timezone.utc).isoformat()}
        try:
            response=session.send(request,timeout=45)
            result.update(httpStatus=response.status_code,submitSeconds=time.perf_counter()-started,
                          retryAfter=response.headers.get('Retry-After'))
            # Nginx JSON has no charset; decode explicitly to retain Chinese evidence.
            try:state=json.loads(response.content.decode('utf-8'))
            except (ValueError,UnicodeError):state={}
            result['submission']=state
            if response.status_code not in (200,202):return result
            while state.get('status') not in ('SUCCEEDED','FAILED','RETRYABLE') and time.perf_counter()-started<1200:
                time.sleep(.5)
                response=b.request('GET','/api/meals/analysis-requests/'+key,users[index],timeout=15)
                if response.status_code==200:state=response.json()
                else:result.setdefault('pollErrors',[]).append(response.status_code)
            result.update(final=state,completed=time.perf_counter(),e2eSeconds=time.perf_counter()-started)
        except requests.RequestException as error:result['error']=type(error).__name__
        finally:
            session.close()
            b.save(directory/f'task-{index:02}.json',result)
        return result

    try:
        print(f'Starting {name}: tasks={len(indices)}, consumers={concurrency}, synchronized={all_at_once}',flush=True)
        with ThreadPoolExecutor(max_workers=len(indices) if all_at_once else concurrency) as pool:
            futures=[pool.submit(task,index) for index in indices]
            if barrier:barrier.wait(timeout=30)
            results=[future.result() for future in futures]
        time.sleep(2)
        usage=b.metrics(out,'final-'+name)
        database,queues=b.audit(out,name)
        after=b.identities(out);b.save(directory/'containers-after.json',after)
        b.save(directory/'results.json',results)
        known=usage['input']+usage['output']
        failures=sum(x.get('final',{}).get('status')=='FAILED' for x in results)
        succeeded=[x for x in results if x.get('final',{}).get('status')=='SUCCEEDED']
        starts=[x['started'] for x in results]
        durations=sorted(x['e2eSeconds'] for x in succeeded)
        summary=dict(name=name,consumers=concurrency,attempted=len(indices),
            accepted=sum(x.get('httpStatus') in (200,202) for x in results),
            rejected429=sum(x.get('httpStatus')==429 for x in results),succeeded=len(succeeded),failed=failures,
            usage=usage,knownTokens=known,unknownReserveTokens=usage['missingUsage']*UNKNOWN_ATTEMPT_RESERVE,
            priorKnownTokens=prior,launchSpreadMs=(max(starts)-min(starts))*1000,
            lastCompletionSeconds=max((x.get('completed',x['started'])-min(starts) for x in results),default=0),
            within60Seconds=sum(x['completed']-min(starts)<=60 for x in succeeded) if all_at_once else None,
            e2eSeconds=durations,queries=len(samples),queryFailures=sum(x.get('status')!=200 for x in samples),
            unchangedContainerIds={x['id'] for x in before}=={x['id'] for x in after},
            noRestarts=all(x['restartCount']==0 and not x['oomKilled'] and x['status']=='running' for x in after),
            outstanding=database['outstanding'],duplicateUsers=database['duplicateUsers'],queueOutput=queues)
        summary['aiAdviceFallbacks']=sum('本地规则建议' in x.get('final',{}).get('result',{}).get('advice','')
                                         for x in succeeded)
        b.save(directory/'summary.json',summary)
        print(json.dumps({k:v for k,v in summary.items() if k not in ('e2eSeconds','queueOutput')},ensure_ascii=False),flush=True)
        return summary
    finally:
        stop.set();observer.join(timeout=10)
        (directory/'backend.log').write_text(b.compose(out,'logs','--no-color','backend'),encoding='utf-8')
        (directory/'runtime-memory.txt').write_text(b.compose(out,'exec','-T','backend','sh','-c',
            'sha256sum /app/app.jar; cat /sys/fs/cgroup/memory.peak; cat /sys/fs/cgroup/memory.events'),encoding='utf-8')


def execute(out):
    if (out/'paid-run-started.json').exists():raise RuntimeError('Never repeat paid phases in one directory')
    b.save(out/'paid-run-started.json',{'utc':datetime.now(timezone.utc).isoformat()})
    users=accounts(out); summaries=[];tokens=0
    for concurrency,indices in ((1,[64,0]),(2,list(range(1,5))),(4,list(range(5,13)))):
        result=phase(out,f'probe-c{concurrency}',indices,concurrency,users,tokens)
        summaries.append(result);tokens+=result['knownTokens']+result['unknownReserveTokens']
        b.save(out/'phases.json',summaries)
        if result['succeeded']!=len(indices) or result['usage']['failedCalls'] or tokens>50000:
            print('Probe was not stable; no higher-concurrency probe will be sent',flush=True)
            break
    stable=[x for x in summaries if x['succeeded']==x['attempted'] and not x['usage']['failedCalls']]
    # Even an unsuccessful probe must not be silently converted into a passing burst.
    if not stable:
        b.save(out/'conclusion.json',dict(passed=False,reason='No stable real-model probe',phases=summaries));return
    if len(stable)!=3:
        b.save(out/'conclusion.json',dict(passed=False,reason='All three stable probes are required before a 50-task burst',phases=summaries));return
    measured=max(stable,key=lambda x:x['consumers'])
    # Future runs use the verified final profile; preserve the historical
    # resume/retest actions solely for the documented experimental workflow.
    candidate=40
    average=sum(x['knownTokens'] for x in stable)/sum(x['succeeded'] for x in stable)
    projected=tokens+50*average*1.25
    b.save(out/'candidate.json',dict(consumers=candidate,measuredStableConsumers=measured['consumers'],
        projectedNewTokenAllowance=projected,usedAllowance=tokens,targetSeconds=60,
        note='Final measured profile: 40 consumers and 400 ms pacing. Every new run must independently pass.'))
    if projected>TOTAL_NEW_TOKEN_TARGET:
        b.save(out/'conclusion.json',dict(passed=False,reason='Insufficient reserved token allowance for a 50-task burst',phases=summaries));return
    print('Waiting 61 seconds to clear rolling application call-admission window before the only 50-task burst',flush=True)
    config=load(out/'compose.json')
    config['services']['backend']['environment']['MEALCHECK_AI_GLOBAL_MIN_START_INTERVAL_MILLIS']='400'
    b.save(out/'compose.json',config)
    time.sleep(61)
    burst=phase(out,'burst50',list(range(13,63)),candidate,users,tokens,True)
    summaries.append(burst);b.save(out/'phases.json',summaries)
    passed=(burst['accepted']==50 and burst['succeeded']==50 and burst['within60Seconds']==50
        and not burst['queryFailures'] and burst['noRestarts'] and not burst['duplicateUsers'] and not burst['outstanding']
        and not burst['aiAdviceFallbacks'])
    b.save(out/'conclusion.json',dict(passed=passed,target='50 accepted and successfully saved within 60 seconds',
        knownNewTokens=sum(x['knownTokens'] for x in summaries),unknownReserveTokens=sum(x['unknownReserveTokens'] for x in summaries),
        previousKnownTokens=22189,previousUnknownReserveTokens=82080,phases=summaries))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','start','run','resume-burst','retest-burst','cleanup'])
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    out=args.output.resolve() if args.output else b.ROOT/'loadtests/results'/('real-burst50-'+datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S'))
    if out.parent!=(b.ROOT/'loadtests/results').resolve() or not re.fullmatch(r'real-burst50-\d{8}-\d{6}',out.name):
        raise RuntimeError('Invalid isolated evidence path')
    if args.action=='prepare':prepare(out);print(out)
    elif args.action=='start':
        if b.command(['docker','ps','-aq','--filter','label=com.docker.compose.project='+b.project(out)]).strip():
            raise RuntimeError('Fresh project required')
        (out/'startup.log').write_text(b.compose(out,'up','-d','--wait','--wait-timeout','150'),encoding='utf-8');ready();print('Ready',flush=True)
    elif args.action=='run':execute(out)
    elif args.action=='resume-burst':
        if (out/'burst50').exists():raise RuntimeError('The only paid burst has already started')
        summaries=load(out/'phases.json')
        if len(summaries)!=3 or any(x['succeeded']!=x['attempted'] for x in summaries):
            raise RuntimeError('Three successful baseline probes are required')
        database,_=b.audit(out,'before-candidate')
        if database['outstanding'] or database['outboxUnpublished']:raise RuntimeError('Do not replace an active backend')
        import shutil,hashlib
        b.compose(out,'stop','backend')
        shutil.copy2(out/'app.jar',out/'app-probes.jar')
        shutil.copy2(b.ROOT/'backend/target/mealcheck-backend.jar',out/'app.jar')
        config=load(out/'compose.json')
        config['services']['backend']['environment']['MEALCHECK_AI_GLOBAL_MIN_START_INTERVAL_MILLIS']='500'
        b.save(out/'compose.json',config)
        manifest=load(out/'manifest.json')
        manifest.update(candidateJarSha256=hashlib.sha256((out/'app.jar').read_bytes()).hexdigest(),
                        candidateMinStartIntervalMillis=500)
        b.save(out/'manifest.json',manifest)
        candidate=load(out/'candidate.json')
        if candidate['projectedNewTokenAllowance']>TOTAL_NEW_TOKEN_TARGET:raise RuntimeError('Token forecast exceeds budget')
        users=load(out/'accounts.private.json')
        used=sum(x['knownTokens']+x['unknownReserveTokens'] for x in summaries)
        burst=phase(out,'burst50',list(range(13,63)),candidate['consumers'],users,used,True)
        summaries.append(burst);b.save(out/'phases.json',summaries)
        passed=(burst['accepted']==50 and burst['succeeded']==50 and burst['within60Seconds']==50
            and not burst['queryFailures'] and burst['noRestarts'] and not burst['duplicateUsers']
            and not burst['outstanding'] and not burst['aiAdviceFallbacks'])
        b.save(out/'conclusion.json',dict(passed=passed,target='50 accepted and successfully saved within 60 seconds',
            knownNewTokens=sum(x['knownTokens'] for x in summaries),
            unknownReserveTokens=sum(x['unknownReserveTokens'] for x in summaries),
            previousKnownTokens=22189,previousUnknownReserveTokens=82080,phases=summaries))
    elif args.action=='retest-burst':
        if (out/'burst50-r2').exists():raise RuntimeError('Never repeat the second paid burst')
        summaries=load(out/'phases.json')
        if len(summaries)!=4 or summaries[-1]['succeeded']!=50:
            raise RuntimeError('Completed first burst required')
        database,_=b.audit(out,'before-r2')
        if database['outstanding'] or database['outboxUnpublished']:raise RuntimeError('Active tasks remain')
        users=accounts(out,115)
        b.compose(out,'stop','backend')
        import shutil,hashlib
        shutil.copy2(out/'app.jar',out/'app-burst-r1.jar')
        shutil.copy2(b.ROOT/'backend/target/mealcheck-backend.jar',out/'app.jar')
        config=load(out/'compose.json')
        env=config['services']['backend']['environment']
        env['MEALCHECK_AI_GLOBAL_MIN_START_INTERVAL_MILLIS']='400'
        env['MEALCHECK_AI_GLOBAL_DAILY_CALLS']='260'
        b.save(out/'compose.json',config)
        manifest=load(out/'manifest.json')
        manifest.update(totalTokenTarget=450000,newTokenTarget=345000,budgetIncreaseAuthorized=True,
            finalJarSha256=hashlib.sha256((out/'app.jar').read_bytes()).hexdigest(),
            finalConsumers=40,finalMinStartIntervalMillis=400)
        b.save(out/'manifest.json',manifest)
        used=sum(x['knownTokens']+x['unknownReserveTokens'] for x in summaries)
        estimated=used+summaries[-1]['knownTokens']*1.25
        if estimated>345000:raise RuntimeError('Revised token allowance insufficient')
        burst=phase(out,'burst50-r2',list(range(65,115)),40,users,used,True)
        summaries.append(burst);b.save(out/'phases.json',summaries)
        passed=(burst['accepted']==50 and burst['succeeded']==50 and burst['within60Seconds']==50
            and not burst['queryFailures'] and burst['noRestarts'] and not burst['duplicateUsers']
            and not burst['outstanding'] and not burst['aiAdviceFallbacks'])
        b.save(out/'conclusion-r1.json',load(out/'conclusion.json'))
        b.save(out/'conclusion.json',dict(passed=passed,target='50 accepted and successfully saved within 60 seconds',
            knownNewTokens=sum(x['knownTokens'] for x in summaries),unknownReserveTokens=sum(x['unknownReserveTokens'] for x in summaries),
            previousKnownTokens=22189,previousUnknownReserveTokens=82080,totalTokenTarget=450000,phases=summaries))
    else:
        inspected=b.identities(out)
        if inspected:
            database,_=b.audit(out,'before-cleanup')
            if database['outstanding'] or database['outboxUnpublished']:
                raise RuntimeError('Wait for accepted tasks to drain before deleting the isolated project')
        (out/'cleanup.log').write_text(b.compose(out,'down','--volumes'),encoding='utf-8')
        remaining=b.command(['docker','ps','-aq','--filter','label=com.docker.compose.project='+b.project(out)]).strip()
        b.save(out/'cleanup.json',dict(project=b.project(out),remainingContainers=remaining,cleaned=not bool(remaining)))
        print('Cleaned isolated project',flush=True)


if __name__=='__main__':main()
