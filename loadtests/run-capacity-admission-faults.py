"""Bounded overload, database interruption and live stale-worker takeover on the private stub stack."""
import base64
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import time
import requests

spec=importlib.util.spec_from_file_location('faults',Path(__file__).with_name('run-capacity-faults.py'))
f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
b=f.b
b.META.update(schema='capacity-admission-faults-v1',accountCount=40,passed=False)

def health(name):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        state=json.loads(b.command(['docker','inspect','--format','{{json .State}}',name]))
        if state.get('Health',{}).get('Status')=='healthy': return
        time.sleep(2)
    raise RuntimeError('Container did not recover health: '+name)

def attempt(index,key,legacy=False):
    headers={'Authorization':'Bearer '+f.accounts[index]['token']}
    if not legacy:headers['Idempotency-Key']=key
    with b.IMAGE.open('rb') as image:
        response=requests.post(b.BASE+('/api/meals/analyze' if legacy else '/api/meals/analysis-requests'),headers=headers,
            files={'image':('meal.png',image,'image/png')},data={'goal':'current'},timeout=30)
    data=response.json()
    return {'index':index,'key':key,'http':response.status_code,'code':data.get('code'),
            'retryAfter':response.headers.get('Retry-After'),'state':data.get('status')}

def wait_call():
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if f.stub('__stats')['calls']>0:return
        time.sleep(.2)
    raise RuntimeError('Model call did not begin')

def state(index,key):
    uid=b.META['testUserIds'][index]
    return json.loads(f.sql(f"SELECT json_build_object('status',status,'attempts',attempts,'token',attempt_token,'live',lease_until>CURRENT_TIMESTAMP) FROM meal_analysis_requests WHERE user_id={uid} AND request_key='{key}';"))

def main():
    print('EVIDENCE',b.OUT,flush=True);b.META['started']=b.stamp()
    runtime=json.loads(b.command(['docker','inspect','mealcheck-capacity-backend-1']))[0]
    settings=dict(v.split('=',1) for v in runtime['Config']['Env'])
    assert runtime['Config']['Labels']['com.docker.compose.project']=='mealcheck-capacity'
    assert settings['MEALCHECK_AI_BASE_URL']=='http://model-stub:9082/v1' and settings['MEALCHECK_AI_API_KEY']=='capacity-test-only'
    assert settings['MEALCHECK_ANALYSIS_MAX_OUTSTANDING']=='32' and settings['MEALCHECK_ANALYSIS_MAX_OUTSTANDING_PER_USER']=='2'
    assert int(f.sql("SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING');"))==0
    b.META['imageId']=runtime['Image'];b.META['limits']={k:settings[k] for k in [
        'MEALCHECK_ANALYSIS_MAX_OUTSTANDING','MEALCHECK_ANALYSIS_MAX_OUTSTANDING_PER_USER','MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS']}
    shutil.copyfile(__file__,b.OUT/Path(__file__).name)
    b.save()
    try:
        b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--count','40'],180)
        f.accounts=json.loads(b.ACCOUNTS.read_text(encoding='utf-8'))
        ids=[]
        for account in f.accounts:
            encoded=account['token'].split('.')[1];claims=json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
            assert claims['sub'].startswith('capacity_');ids.append(int(claims['uid']))
        b.META['testUserIds']=ids;b.save()
        b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=2','backend'],60)
        health('mealcheck-capacity-backend-2')
        b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend'],110)
        f.begin('two-replica-bounded-overload');f.stub('__control',{'delay':.05})
        b.command(['docker','stop','--time','5','mealcheck-capacity-rabbitmq-1'],20)
        first=[attempt(0,'admission-owner-'+b.RUN+'-'+str(i)) for i in range(2)]
        assert all(v['http']==202 for v in first),first
        own=attempt(0,'admission-owner-rejected-'+b.RUN)
        assert own['http']==429 and own['code']=='ANALYSIS_USER_BUSY' and own['retryAfter']=='3',own
        legacy=attempt(0,'legacy-without-key',legacy=True)
        assert legacy['http']==429 and legacy['code']=='ANALYSIS_USER_BUSY',legacy
        with ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda i:attempt(i,'admission-fill-'+b.RUN+'-'+str(i)),range(1,40)))
        accepted=first+[v for v in results if v['http']==202]
        rejected=[v for v in results if v['http']!=202]
        assert len(accepted)==32 and all(v['http']==429 and v['code']=='ANALYSIS_BUSY' for v in rejected),results
        replay=attempt(0,first[0]['key']);assert replay['state']=='PENDING'
        samples=[];started=time.monotonic();rejections=0
        while time.monotonic()-started<60:
            remaining=[v['index'] for v in rejected]
            with ThreadPoolExecutor(max_workers=4) as pool:
                more=list(pool.map(lambda i:attempt(i,'admission-overload-'+b.RUN+'-'+str(rejections)+'-'+str(i)),remaining))
            assert all(v['http']==429 and v['code']=='ANALYSIS_BUSY' for v in more),more
            rejections+=len(more)
            fixture_ids=','.join(map(str,ids))
            counts=json.loads(f.sql(f"SELECT json_build_object('active',(SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING')),'requests',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({fixture_ids})),'unpublished',(SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NULL));"))
            assert counts['active']==counts['requests']==counts['unpublished']==32,counts
            samples.append({'at':b.stamp(),**counts});time.sleep(2)
        b.command(['docker','start','mealcheck-capacity-rabbitmq-1'],30);health('mealcheck-capacity-rabbitmq-1')
        completed=[f.poll(v['index'],v['key']) for v in accepted]
        assert all(v['status']=='SUCCEEDED' for v in completed)
        drained=f.drain();stats=f.stub('__stats');assert stats['calls']==64,stats
        f.end('two-replica-bounded-overload',{'accepted':32,'initialGlobalRejected':len(rejected),'sustainedGlobalRejected':rejections,
            'userRejection':own,'legacyWithoutKeyRejection':legacy,'replayAtCapacity':replay,'samples':samples,'allAcceptedSucceeded':True,'modelStats':stats,'drain':drained})
        # Remove the second worker while testing a database interruption, then recreate it for takeover.
        b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=1','backend'],60)
        b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend'],110)
        index=rejected[0]['index'];f.begin('database-outage');f.stub('__control',{'delay':5})
        key='database-outage-'+b.RUN;assert attempt(index,key)['http']==202;wait_call()
        b.command(['docker','stop','--time','0','mealcheck-capacity-postgres-1'],20)
        time.sleep(35)
        b.command(['docker','start','mealcheck-capacity-postgres-1'],30);health('mealcheck-capacity-postgres-1')
        outcome=f.poll(index,key,180)
        # A durable FAILED outcome requires an explicit user retry; never silently rebill.
        before_retry=f.stub('__stats')
        if outcome['status']=='FAILED':
            f.stub('__control',{'delay':.05});assert attempt(index,key)['http']==202;outcome=f.poll(index,key)
        assert outcome['status']=='SUCCEEDED';proof=f.replay(index,key,outcome)
        f.end('database-outage',{'interruptionSeconds':35,'outcome':outcome,'modelStatsBeforeExplicitRetry':before_retry,
            'modelStats':f.stub('__stats'),**proof});f.drain()
        # Recreate worker 1 to avoid stale client state, then freeze a live execution.
        b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','backend'],110)
        b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend'],110)
        index=rejected[1]['index'];f.begin('expired-live-worker-takeover');f.stub('__control',{'delay':5})
        b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=2','backend'],60);health('mealcheck-capacity-backend-2')
        b.command(['docker','pause','mealcheck-capacity-backend-2'],15)
        key='stale-worker-'+b.RUN;assert f.submit_to_worker(index,key)['status']=='PENDING';wait_call();old=state(index,key)
        b.command(['docker','pause','mealcheck-capacity-backend-1'],15)
        b.command(['docker','unpause','mealcheck-capacity-backend-2'],15)
        started=time.monotonic();deadline=started+180
        while time.monotonic()<deadline:
            current=state(index,key)
            if current['status']=='SUCCEEDED':break
            time.sleep(2)
        else:raise RuntimeError('Replacement worker failed to complete expired task')
        assert current['attempts']==2 and current['token']!=old['token'],current
        replacement_seconds=time.monotonic()-started
        b.command(['docker','unpause','mealcheck-capacity-backend-1'],15);health('mealcheck-capacity-backend-1')
        time.sleep(15);outcome=f.poll(index,key);proof=f.replay(index,key,outcome)
        stats=f.stub('__stats');assert stats['calls']==3,stats
        f.end('expired-live-worker-takeover',{'oldExecution':old,'newExecution':current,
            'replacementSeconds':round(replacement_seconds,3),'modelStats':stats,**proof})
        b.META['passed']=True
    finally:
        b.META['activePhase']='cleanup';b.save()
        try:
            state1=json.loads(b.command(['docker','inspect','--format','{{json .State}}','mealcheck-capacity-backend-1']))
            if state1.get('Paused'):b.command(['docker','unpause','mealcheck-capacity-backend-1'],15)
            b.command(['docker','start','mealcheck-capacity-postgres-1','mealcheck-capacity-rabbitmq-1'],30)
            health('mealcheck-capacity-postgres-1');health('mealcheck-capacity-rabbitmq-1')
            f.stub('__control',{'delay':.05});b.META['finalQueuesBeforeCleanup']=f.drain()
            if b.ACCOUNTS.exists():b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--cleanup'],180)
            ids=','.join(map(str,b.META.get('testUserIds',[])))
            if ids:
                counts=json.loads(f.sql(f"SELECT json_build_object('users',(SELECT count(*) FROM users WHERE id IN ({ids})),'records',(SELECT count(*) FROM meal_records WHERE user_id IN ({ids})),'tasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({ids})));"))
                assert not any(counts.values());b.META['cleanupVerification']=counts
            b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=1','backend'],60)
            b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend','model-stub'],110)
            b.META['cleanup']='Deleted only this run dedicated accounts; restored one backend and healthy dependencies'
        except Exception as error:b.META['cleanup']='Incomplete: '+str(error);b.META['passed']=False
        b.META['activePhase']=None;b.META['ended']=b.stamp();b.save()

if __name__=='__main__':
    try:
        main()
        if not b.META['passed']:sys.exit(1)
    except Exception as error:
        b.META.update(error=str(error),passed=False);b.save();print('FAILED:',error,flush=True);sys.exit(1)
