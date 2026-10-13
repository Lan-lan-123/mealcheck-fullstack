"""Exercise restart, broker outage, bounded provider retry and timeout on the isolated stub stack."""
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import threading
import time
import requests
import subprocess

spec=importlib.util.spec_from_file_location('benchmark',Path(__file__).with_name('run-local-benchmark.py'))
b=importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
b.META.update(schema='capacity-faults-v1',realModel=False,accountCount=6,phases=[])
accounts=[]


def sql(query):
    return b.command(['docker','exec','mealcheck-capacity-postgres-1','psql','-U','mealcheck','-d','mealcheck','-At','-c',query])


def stub(path, values=None):
    source="import urllib.request; "
    if values is None: source+="print(urllib.request.urlopen('http://127.0.0.1:9082/"+path+"').read().decode())"
    else: source+="r=urllib.request.Request('http://127.0.0.1:9082/"+path+"',data="+repr(json.dumps(values).encode())+",headers={'Content-Type':'application/json'}); print(urllib.request.urlopen(r).read().decode())"
    return json.loads(b.command(['docker','exec','mealcheck-capacity-model-stub-1','python','-c',source]))


def submit(index,key):
    with b.IMAGE.open('rb') as image:
        response=requests.post(b.BASE+'/api/meals/analysis-requests',headers={'Authorization':'Bearer '+accounts[index]['token'],'Idempotency-Key':key},
            files={'image':('meal.png',image,'image/png')},data={'goal':'current'},timeout=20)
    if response.status_code not in (200,202): raise RuntimeError(f'Submission returned HTTP {response.status_code}')
    return response.json()

def submit_to_worker(index,key):
    # Send fixture input over stdin, avoiding credentials/body in process arguments.
    source="""import base64,json,sys,urllib.request
v=json.load(sys.stdin);boundary='capacity-fixture-boundary'
image=base64.b64decode(v['image'])
body=('--'+boundary+'\\r\\nContent-Disposition: form-data; name="goal"\\r\\n\\r\\ncurrent\\r\\n--'+boundary+'\\r\\nContent-Disposition: form-data; name="image"; filename="meal.png"\\r\\nContent-Type: image/png\\r\\n\\r\\n').encode()+image+('\\r\\n--'+boundary+'--\\r\\n').encode()
r=urllib.request.Request('http://mealcheck-capacity-backend-1:8080/api/meals/analysis-requests',data=body,headers={'Authorization':'Bearer '+v['token'],'Idempotency-Key':v['key'],'Content-Type':'multipart/form-data; boundary='+boundary})
print(urllib.request.urlopen(r,timeout=30).read().decode())
"""
    payload=json.dumps({'key':key,'token':accounts[index]['token'],'image':base64.b64encode(b.IMAGE.read_bytes()).decode()})
    result=subprocess.run(['docker','exec','-i','mealcheck-capacity-model-stub-1','python','-c',source],
        input=payload,capture_output=True,text=True,encoding='utf-8',timeout=40,cwd=b.ROOT)
    if result.returncode:raise RuntimeError('Direct fixture submission failed')
    return json.loads(result.stdout)


def poll(index,key,seconds=180):
    started=time.monotonic(); interruptions=0
    while time.monotonic()-started<seconds:
        try:
            response=requests.get(b.BASE+'/api/meals/analysis-requests/'+key,headers={'Authorization':'Bearer '+accounts[index]['token']},timeout=5)
            if response.status_code not in (200,202): interruptions+=1
            else:
                data=response.json()
                if data['status'] in ('SUCCEEDED','FAILED'):
                    return {**data,'observedSeconds':round(time.monotonic()-started,3),'pollInterruptions':interruptions}
        except requests.RequestException: interruptions+=1
        time.sleep(1)
    raise RuntimeError('Fault job did not reach a terminal state within observation deadline')


def wait_backend():
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        value=json.loads(b.command(['docker','inspect','--format','{{json .State}}','mealcheck-capacity-backend-1']))
        if value.get('Health',{}).get('Status')=='healthy': return
        time.sleep(2)
    raise RuntimeError('Backend did not recover health')


def drain():
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        snapshot=b.queue_snapshot()
        if snapshot['database']['pending']==snapshot['database']['unpublished']==0 and all(q['messages_ready']==q['messages_unacknowledged']==0 for q in snapshot['rabbitmq']): return snapshot
        time.sleep(3)
    raise RuntimeError('Fault jobs did not drain; preserve account data for investigation')


def begin(name):
    if (b.OUT/'STOP_REQUESTED').exists(): raise InterruptedError('Run stopped before starting another fault')
    b.CURRENT=name; b.META['activePhase']=name; b.save(); print(b.stamp(),'START',name,flush=True)


def end(name,data):
    b.META['phases'].append({'name':name,'ended':b.stamp(),**data})
    b.META['activePhase']=None; b.save(); print(b.stamp(),'END',name,flush=True)


def replay(index,key,result):
    before=stub('__stats')['calls']; repeated=submit(index,key)
    assert repeated['status']=='SUCCEEDED' and repeated['result']['recordId']==result['result']['recordId']
    assert stub('__stats')['calls']==before
    uid=b.META['testUserIds'][index]
    assert int(sql(f"SELECT count(*) FROM meal_records WHERE user_id={uid};"))==1
    return {'sameRecordOnReplay':True,'extraCallsOnReplay':0,'userRecordCount':1}


def archived_dead_letter(user_id, key):
    # The application's archive listener persists the event before ACK. An empty
    # DLQ alone is neither success nor failure; require the matching durable event.
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        raw=sql(f"SELECT json_build_object('eventId',event_id,'errorCode',error_code,'status',status,'deadLettered',dead_lettered,'attempts',attempts) FROM task_failure_events WHERE user_id={int(user_id)} AND task_key='{key}' AND task_type='MEAL_ANALYSIS';").strip()
        if raw:
            event=json.loads(raw)
            if event['deadLettered']:
                assert event['eventId'] and event['status']=='OPEN' and event['attempts']==1,event
                return event
        time.sleep(1)
    raise RuntimeError('Timeout failure was not durably archived from the DLQ')


def main():
    global accounts
    print('EVIDENCE',b.OUT,flush=True); b.META['started']=b.stamp()
    container=json.loads(b.command(['docker','inspect','mealcheck-capacity-backend-1']))[0]
    settings=dict(s.split('=',1) for s in container['Config']['Env'])
    assert container['Config']['Labels']['com.docker.compose.project']=='mealcheck-capacity'
    assert settings['MEALCHECK_AI_BASE_URL']=='http://model-stub:9082/v1' and settings['MEALCHECK_AI_API_KEY']=='capacity-test-only'
    b.META['backend']={'imageId':container['Image'],'concurrency':settings['MEALCHECK_ANALYSIS_CONCURRENCY'],
        'jvm':settings['JAVA_TOOL_OPTIONS'],'leaseSeconds':settings.get('MEALCHECK_ANALYSIS_LEASE_SECONDS','90')}
    b.META['initialQueues']=drain(); b.save()
    observer=threading.Thread(target=b.monitor,daemon=True); observer.start()
    try:
        b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--count','6'],120)
        accounts=json.loads(b.ACCOUNTS.read_text(encoding='utf-8')); ids=[]
        for account in accounts:
            encoded=account['token'].split('.')[1]; c=json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
            assert c['sub'].startswith('capacity_'); ids.append(int(c['uid']))
        b.META['testUserIds']=ids; b.save()
        shutil.copyfile(__file__,b.OUT/'run-capacity-faults.py')

        begin('provider429'); stub('__control',{'delay':.05,'errorFirst':1,'errorStatus':429,'retryAfter':1})
        key='fault-429-'+b.RUN; submit(0,key); result=poll(0,key)
        assert result['status']=='SUCCEEDED'; stats=stub('__stats'); assert stats['calls']==3,stats
        end('provider429',{'result':result,'modelStats':stats,**replay(0,key,result)})
        drain()

        begin('backend-crash'); stub('__control',{'delay':5})
        key='fault-crash-'+b.RUN; submit(1,key)
        deadline=time.monotonic()+15
        while stub('__stats')['calls']==0 and time.monotonic()<deadline: time.sleep(.2)
        assert stub('__stats')['calls']>0
        uid=ids[1]
        b.META['crashLeaseBefore']=json.loads(sql(f"SELECT json_build_object('status',status,'remainingLeaseSeconds',EXTRACT(EPOCH FROM lease_until-CURRENT_TIMESTAMP),'attempts',attempts) FROM meal_analysis_requests WHERE user_id={uid};")); b.save()
        started=time.monotonic(); b.command(['docker','restart','--time','0','mealcheck-capacity-backend-1'],30)
        wait_backend(); result=poll(1,key,180); result['secondsFromCrash']=round(time.monotonic()-started,3)
        assert result['status']=='SUCCEEDED' and result['secondsFromCrash']<180
        attempts=int(sql(f"SELECT attempts FROM meal_analysis_requests WHERE user_id={uid};"))
        assert attempts==2
        b.META['restartCountAfterInjectedCrash']=json.loads(b.command(['docker','inspect','--format','{{json .RestartCount}}','mealcheck-capacity-backend-1']))
        stats=stub('__stats'); assert stats['calls']==3,stats
        end('backend-crash',{'result':result,'attempts':attempts,'modelStats':stats,**replay(1,key,result)})
        drain()

        begin('rabbit-outage'); stub('__control',{'delay':.05})
        b.command(['docker','stop','--time','5','mealcheck-capacity-rabbitmq-1'],20)
        keys=['fault-broker-'+b.RUN+'-'+str(n) for n in range(2,5)]
        for index,key in zip(range(2,5),keys): assert submit(index,key)['status']=='PENDING'
        time.sleep(10)
        pending=json.loads(sql("SELECT json_build_object('pending',(SELECT count(*) FROM meal_analysis_requests WHERE status='PENDING'),'unpublished',(SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NULL));"))
        assert pending['pending']==pending['unpublished']==3,pending
        b.command(['docker','start','mealcheck-capacity-rabbitmq-1'],30)
        results=[poll(index,key) for index,key in zip(range(2,5),keys)]
        assert all(r['status']=='SUCCEEDED' for r in results)
        proofs=[replay(index,key,result) for index,key,result in zip(range(2,5),keys,results)]
        stats=stub('__stats'); assert stats['calls']==6,stats
        end('rabbit-outage',{'pendingDuringOutage':pending,'results':results,'replays':proofs,'modelStats':stats})
        drain()

        begin('provider-timeout'); stub('__control',{'delay':120})
        key='fault-timeout-'+b.RUN; submit(5,key); result=poll(5,key,100)
        assert result['status']=='FAILED' and result['observedSeconds']<90
        stats=stub('__stats'); assert stats['calls']==1,stats
        time.sleep(5); assert stub('__stats')['calls']==1
        assert int(sql(f'SELECT count(*) FROM meal_records WHERE user_id={ids[5]};'))==0
        dead_letter=archived_dead_letter(ids[5],key)
        end('provider-timeout',{'result':result,'modelStats':stats,'recordCount':0,'noAutomaticRebilling':True,'deadLetter':dead_letter})
        stub('__control',{'delay':.05})
        recovery=submit(5,key); result=poll(5,key)
        assert result['status']=='SUCCEEDED'
        stats=stub('__stats'); assert stats['calls']==2,stats
        end('explicit-retry-after-timeout',{'result':result,'modelStats':stats,**replay(5,key,result)})
        b.META['passed']=True
    finally:
        b.CURRENT='cleanup'
        try:
            b.command(['docker','start','mealcheck-capacity-rabbitmq-1'],30)
            stub('__control',{'delay':.05})
            b.META['finalQueues']=drain()
            if b.ACCOUNTS.exists(): b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--cleanup'],120)
            b.META['cleanup']='Deleted only this run\'s dedicated accounts and associated data'
            ids=','.join(map(str,b.META.get('testUserIds',[])))
            if ids:
                counts=json.loads(sql(f"SELECT json_build_object('users',(SELECT count(*) FROM users WHERE id IN ({ids})),'records',(SELECT count(*) FROM meal_records WHERE user_id IN ({ids})),'tasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({ids})));"))
                assert not any(counts.values()); b.META['cleanupVerification']=counts
            # Cancel any timed-out stub handler still sleeping; the provider fixture
            # returns to its Compose settings after all backend work has drained.
            b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','model-stub'],110)
            b.META['modelRestored']=True
        except Exception as error:
            b.META['cleanup']='Incomplete: '+str(error)
            b.META['passed']=False
        b.STOP.set(); observer.join(30); b.META['ended']=b.stamp(); b.save()


if __name__=='__main__':
    try:
        main()
        if not b.META.get('passed'): sys.exit(1)
    except Exception as error:
        b.META['error']=str(error); b.META['passed']=False; b.save(); print('FAILED:',error,flush=True); sys.exit(1)
