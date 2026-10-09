"""Repeat only the live stale-worker takeover, without restarting a paused container."""
import importlib.util
import base64
import json
from pathlib import Path
import shutil
import sys
import time

spec=importlib.util.spec_from_file_location('admission',Path(__file__).with_name('run-capacity-admission-faults.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
f=a.f;b=a.b
b.META.update(schema='capacity-stale-worker-v1',accountCount=1,passed=False)

def main():
    print('EVIDENCE',b.OUT,flush=True);b.META['started']=b.stamp()
    runtime=json.loads(b.command(['docker','inspect','mealcheck-capacity-backend-1']))[0]
    settings=dict(v.split('=',1) for v in runtime['Config']['Env'])
    assert runtime['Config']['Labels']['com.docker.compose.project']=='mealcheck-capacity'
    assert settings['MEALCHECK_AI_BASE_URL']=='http://model-stub:9082/v1' and settings['MEALCHECK_AI_API_KEY']=='capacity-test-only'
    b.META['imageId']=runtime['Image'];b.META['initialQueues']=f.drain();b.save()
    try:
        b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--count','1'],120)
        f.accounts=json.loads(b.ACCOUNTS.read_text(encoding='utf-8'))
        encoded=f.accounts[0]['token'].split('.')[1];claims=json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
        assert claims['sub'].startswith('capacity_');b.META['testUserIds']=[int(claims['uid'])];b.save()
        shutil.copyfile(__file__,b.OUT/Path(__file__).name)
        b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=2','backend'],60);a.health('mealcheck-capacity-backend-2')
        b.command(['docker','pause','mealcheck-capacity-backend-2'],15)
        f.begin('expired-live-worker-takeover');f.stub('__control',{'delay':5})
        key='stale-worker-'+b.RUN;assert f.submit_to_worker(0,key)['status']=='PENDING';a.wait_call();old=a.state(0,key)
        b.command(['docker','pause','mealcheck-capacity-backend-1'],15)
        b.command(['docker','unpause','mealcheck-capacity-backend-2'],15)
        started=time.monotonic();deadline=started+180
        while time.monotonic()<deadline:
            current=a.state(0,key)
            if current['status']=='SUCCEEDED':break
            time.sleep(2)
        else:raise RuntimeError('Replacement worker failed to complete expired task')
        assert current['attempts']==2 and current['token']!=old['token'],current
        replacement_seconds=time.monotonic()-started
        b.command(['docker','unpause','mealcheck-capacity-backend-1'],15);a.health('mealcheck-capacity-backend-1')
        # Recreate frontend after the backend scale to refresh both DNS targets.
        b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend'],110)
        time.sleep(15);outcome=f.poll(0,key);proof=f.replay(0,key,outcome)
        stats=f.stub('__stats');assert stats['calls']==3,stats
        f.end('expired-live-worker-takeover',{'oldExecution':old,'newExecution':current,
            'replacementSeconds':round(replacement_seconds,3),'modelStats':stats,**proof});b.META['passed']=True
    finally:
        try:
            for name in ('mealcheck-capacity-backend-1','mealcheck-capacity-backend-2'):
                state=json.loads(b.command(['docker','inspect','--format','{{json .State}}',name]))
                if state.get('Paused'):b.command(['docker','unpause',name],15)
            f.stub('__control',{'delay':.05});b.META['finalQueuesBeforeCleanup']=f.drain()
            if b.ACCOUNTS.exists():b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,'--output',str(b.ACCOUNTS),'--cleanup'],120)
            uid=b.META.get('testUserIds',[0])[0]
            counts=json.loads(f.sql(f"SELECT json_build_object('users',(SELECT count(*) FROM users WHERE id={uid}),'records',(SELECT count(*) FROM meal_records WHERE user_id={uid}),'tasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id={uid}));"))
            assert not any(counts.values());b.META['cleanupVerification']=counts
            b.command(b.COMPOSE+['up','-d','--no-deps','--scale','backend=1','backend'],60)
            b.command(b.COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','90','frontend','model-stub'],110)
            b.META['cleanup']='Deleted only this run account; restored one backend and model stub'
        except Exception as error:b.META.update(cleanup='Incomplete: '+str(error),passed=False)
        b.META['activePhase']=None;b.META['ended']=b.stamp();b.save()

if __name__=='__main__':
    try:
        main()
        if not b.META['passed']:sys.exit(1)
    except Exception as error:
        b.META.update(error=str(error),passed=False);b.save();print('FAILED:',error,flush=True);sys.exit(1)
