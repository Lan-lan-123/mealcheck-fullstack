"""Prove that a healthy 100-second async task survives its 90-second worker lease."""
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import threading
import time

spec = importlib.util.spec_from_file_location('faults', Path(__file__).with_name('run-capacity-faults.py'))
f = importlib.util.module_from_spec(spec); spec.loader.exec_module(f)
b = f.b
b.META.update(schema='capacity-lease-smoke-v1', accountCount=1, mode='lease-smoke')


def main():
    print('EVIDENCE', b.OUT, flush=True); b.META['started'] = b.stamp()
    container = json.loads(b.command(['docker', 'inspect', 'mealcheck-capacity-backend-1']))[0]
    settings = dict(s.split('=', 1) for s in container['Config']['Env'])
    assert container['Config']['Labels']['com.docker.compose.project'] == 'mealcheck-capacity'
    assert settings['MEALCHECK_AI_BASE_URL'] == 'http://model-stub:9082/v1'
    assert int(settings.get('MEALCHECK_ANALYSIS_LEASE_SECONDS', '90')) == 90
    b.META['backend'] = {'imageId': container['Image'], 'concurrency': settings['MEALCHECK_ANALYSIS_CONCURRENCY'],
                         'jvm': settings['JAVA_TOOL_OPTIONS'], 'leaseSeconds': 90}
    b.META['initialQueues'] = f.drain(); b.save()
    observers = []
    try:
        b.command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', b.BASE,
                   '--output', str(b.ACCOUNTS), '--count', '1'], 120)
        f.accounts = json.loads(b.ACCOUNTS.read_text(encoding='utf-8'))
        encoded = f.accounts[0]['token'].split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
        assert claims['sub'].startswith('capacity_') and isinstance(claims['uid'], int)
        uid = claims['uid']; b.META['testUserIds'] = [uid]
        shutil.copyfile(__file__, b.OUT/'run-capacity-lease-smoke.py')
        f.begin('healthy-past-lease'); f.stub('__control', {'delay': 50})
        key = 'lease-smoke-'+b.RUN
        started = time.monotonic(); f.submit(0, key)
        samples = []; b.META['leaseSamples'] = samples

        def leases():
            while not b.STOP.is_set():
                sample = json.loads(f.sql(f"SELECT json_build_object('status',status,'attempts',attempts,'remainingLeaseSeconds',EXTRACT(EPOCH FROM lease_until-CURRENT_TIMESTAMP)) FROM meal_analysis_requests WHERE user_id={uid};"))
                samples.append({'elapsedSeconds': time.monotonic()-started, **sample})
                b.STOP.wait(5)

        for target in (b.monitor, leases):
            observer = threading.Thread(target=target, daemon=True); observers.append(observer); observer.start()
        result = f.poll(0, key, 150)
        stats = f.stub('__stats')
        attempts = int(f.sql(f'SELECT attempts FROM meal_analysis_requests WHERE user_id={uid};'))
        assert result['status'] == 'SUCCEEDED' and result['observedSeconds'] > 90, result
        assert attempts == 1 and stats['calls'] == 2, stats
        assert any(s['elapsedSeconds'] > 90 and s['status'] == 'PROCESSING'
                   and s['attempts'] == 1 and s['remainingLeaseSeconds'] > 0 for s in samples), samples
        f.end('healthy-past-lease', {'result': result, 'attempts': attempts, 'modelStats': stats,
                                  **f.replay(0, key, result)})
        b.META['passed'] = True
    finally:
        b.CURRENT = 'cleanup'; b.STOP.set()
        for observer in observers: observer.join(15)
        try:
            f.stub('__control', {'delay': .05}); b.META['finalQueues'] = f.drain()
            if b.ACCOUNTS.exists():
                b.command([sys.executable, 'deploy/scripts/capacity_accounts.py', '--base-url', b.BASE,
                           '--output', str(b.ACCOUNTS), '--cleanup'], 120)
            ids = ','.join(map(str, b.META.get('testUserIds', [])))
            if ids:
                counts = json.loads(f.sql(f"SELECT json_build_object('users',(SELECT count(*) FROM users WHERE id IN ({ids})),'records',(SELECT count(*) FROM meal_records WHERE user_id IN ({ids})),'tasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({ids})));"))
                b.META['cleanupVerification'] = counts; assert not any(counts.values())
            b.command(b.COMPOSE+['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'model-stub'], 110)
            b.META.update(cleanup='Deleted only this run\'s dedicated account and associated data', modelRestored=True)
        except Exception as error:
            b.META.update(cleanup='Incomplete: '+str(error), passed=False)
        b.META['ended'] = b.stamp(); b.save()


if __name__ == '__main__':
    try:
        main()
        if not b.META.get('passed'): sys.exit(1)
    except Exception as error:
        b.META.update(error=str(error), passed=False); b.save(); print('FAILED:', error, flush=True); sys.exit(1)
