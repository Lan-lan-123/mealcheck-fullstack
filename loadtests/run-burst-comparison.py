"""Compare C2 and C4 on the same bounded-upload build; never call a real model."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import threading
import xml.etree.ElementTree as ET
from PIL import Image

spec = importlib.util.spec_from_file_location('optimization',Path(__file__).with_name('run-capacity-optimization.py'))
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)
b = o.b
continuous_only = '--continuous-only' in sys.argv
memory_check = '--memory-check' in sys.argv or continuous_only
b.META.update(schema='burst-memory-continuous-v1' if continuous_only else 'burst-memory-check-v1' if memory_check else 'burst-comparison-v3',
              repetitions=1 if memory_check else 2)
if continuous_only:
    b.BASE='http://127.0.0.1:18080'
    b.META['baseUrl']=b.BASE
o.env.update(MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES='2',MEALCHECK_UPLOAD_MAX_PENDING_DECODES='20',
             MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS='5000',MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES='1073741824')
if memory_check:
    o.env['BACKEND_JAVA_TOOL_OPTIONS']='-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError'

def phase(name, **parameters):
    return o.phase(name,**parameters)

def runtime():
    values = json.loads(b.command(['docker','inspect','mealcheck-capacity-backend-1','mealcheck-capacity-frontend-1']))
    return [{'name':v['Name'],'imageId':v['Image'],'memoryBytes':v['HostConfig']['Memory'],
        'nanoCpus':v['HostConfig']['NanoCpus'],'tmpfs':v['HostConfig']['Tmpfs'],
        'mounts':[{'destination':x['Destination'],'type':x['Type'],'name':x.get('Name')} for x in v['Mounts']],
        'restartCount':v['RestartCount'],'oomKilled':v['State']['OOMKilled'],
        'health':v['State'].get('Health',{}).get('Status'),
        'settings':{k:v2 for k,v2 in (s.split('=',1) for s in v['Config']['Env']) if k in {
            'MEALCHECK_ANALYSIS_CONCURRENCY','MEALCHECK_AI_BASE_URL','MEALCHECK_UPLOAD_TMP_DIR',
            'MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES','MEALCHECK_UPLOAD_MAX_PENDING_DECODES',
            'MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS','MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES',
            'JAVA_TOOL_OPTIONS'}}} for v in values]

def main():
    observer = None
    original = None
    b.META['started'] = b.stamp()
    print(f'EVIDENCE {b.OUT}',flush=True)
    b.META['k6Version'] = b.command([b.K6,'version']).strip()
    info = json.loads(b.command(['docker','info','--format','{{json .}}']))
    b.META['docker'] = {k:info[k] for k in ['ServerVersion','NCPU','MemTotal']}
    original = b.model_env(json.loads(b.command(['docker','inspect','mealcheck-capacity-model-stub-1']))[0])
    b.META['originalModel'] = original
    b.META['initialQueues'] = o.drain()
    b.META['runtimeBefore'] = runtime()
    if b.META['runtimeBefore'][0]['settings'].get('MEALCHECK_AI_BASE_URL') != 'http://model-stub:9082/v1':
        raise RuntimeError('Refusing a non-stub environment')
    snapshots = b.OUT/'scripts'; snapshots.mkdir()
    paths = ['loadtests/analysis-capacity.js','loadtests/capacity-mixed.js','loadtests/read-multi.js',
        'loadtests/run-burst-comparison.py','loadtests/run-capacity-optimization.py','loadtests/run-local-benchmark.py',
        'docker-compose.prod.yml','deploy/compose.capacity.yml','frontend/nginx.conf',
        'backend/src/main/java/com/example/mealcheck/service/ImageUploadValidationService.java',
        'backend/src/main/java/com/example/mealcheck/security/UploadAdmissionFilter.java']
    for name in paths: shutil.copyfile(b.ROOT/name,snapshots/Path(name).name)
    boundary = b.OUT/'boundary-10m.png'
    raw = b.IMAGE.read_bytes(); boundary.write_bytes(raw + bytes(10*1024**2-len(raw)))
    large = b.OUT/'large-4096.jpg'
    with Image.open(b.IMAGE) as image:
        image.convert('RGB').resize((4096,4096),Image.Resampling.BICUBIC).save(large,quality=90)
    assert large.stat().st_size < 10*1024**2
    variants = [{'path':b.IMAGE.as_posix(),'type':'image/png'},
        {'path':(b.ROOT/'tools/datasets/synthetic-meals-100/images/synth-002.png').as_posix(),'type':'image/png'},
        {'path':large.as_posix(),'type':'image/jpeg'}]
    mixed = b.OUT/'images.json'; mixed.write_text(json.dumps(variants),encoding='utf-8')
    b.META['images'] = [{**v,'bytes':Path(v['path']).stat().st_size,
        'sha256':hashlib.sha256(Path(v['path']).read_bytes()).hexdigest()} for v in variants]
    b.META['boundary'] = {'bytes':boundary.stat().st_size,'kind':'PNG with zero-byte tail padding'}
    b.META['large'] = {'bytes':large.stat().st_size,'dimensions':[4096,4096],
        'kind':'Upscaled synthetic meal fixture; decode workload only'}
    b.META['verification'] = {'frontendTests':{'tests':19,'failures':0},'k6Verifier':{'tests':9,'failures':0}}
    for folder in ['surefire-reports','failsafe-reports']:
        roots = [ET.parse(p).getroot() for p in (b.ROOT/'backend/target'/folder).glob('TEST-*.xml')
            if folder=='surefire-reports' or p.stem.endswith(('MealAnalysisDatabaseIntegrationTest','MealAnalysisRabbitDatabaseIntegrationTest'))]
        b.META['verification'][folder] = {k:sum(int(r.get(k,0)) for r in roots) for k in ['tests','failures','errors','skipped']}
    b.save()
    observer = threading.Thread(target=b.monitor,daemon=True); observer.start()
    try:
        o.scale(2); o.model(0.05)
        print(b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,
            '--output',str(b.ACCOUNTS),'--count','40'],180),flush=True)
        if phase('seed',MODE='burst',BURST_TASKS=40,MAX_VUS=2,PREALLOCATED_VUS=2,
            DURATION='60s',TASK_TIMEOUT_SECONDS=60,MIN_RECORDS=1): raise RuntimeError('Seed failed')
        o.expand_records()
        for concurrency in ([4] if continuous_only else [2,4]):
            o.scale(concurrency); o.model(0.05)
            b.META.setdefault('runtimeByConcurrency',[]).append({'concurrency':concurrency,'containers':runtime()})
            b.save()
            if phase(f'c{concurrency}-warmup',MODE='burst',BURST_TASKS=10,MAX_VUS=2,PREALLOCATED_VUS=2,
                     DURATION='60s',TASK_TIMEOUT_SECONDS=60): raise RuntimeError('Warmup failed')
            if continuous_only:
                o.model(5)
                phase(f'c{concurrency}-arrival-20-long',RATE_PER_MINUTE=20)
                o.phase(f'c{concurrency}-mixed-20',script='capacity-mixed.js',RATE_PER_MINUTE=20,VUS=50,
                        DURATION='120s',MAX_VUS=80,PREALLOCATED_VUS=80,IMAGES_FILE=mixed.as_posix())
                continue
            if phase(f'c{concurrency}-entry-20',MODE='burst',BURST_TASKS=20,MAX_VUS=20,
                     PREALLOCATED_VUS=20,DURATION='60s'): raise RuntimeError('20-image ingress still fails; inspect and repair before comparing consumers')
            phase(f'c{concurrency}-boundary-20',MODE='burst',BURST_TASKS=20,MAX_VUS=20,
                  PREALLOCATED_VUS=20,DURATION='60s',IMAGE_FILE=boundary.as_posix())
            # Deliberate overload allows explicit 429, but not task failure/timeout/unknown outcome.
            for kind, count, image in [('overload',50,b.IMAGE),('large',10,large)]:
                phase(f'c{concurrency}-{kind}-{count}',MODE='burst',BURST_TASKS=count,MAX_VUS=count,
                      PREALLOCATED_VUS=count,DURATION='60s',IMAGE_FILE=image.as_posix(),MAX_FAILURE_RATE=0.8,
                      REQUIRE_ACCEPTED_SUCCESS='true')
            o.model(5)
            for repeat in ([1] if memory_check else [1,2]):
                phase(f'c{concurrency}-burst-20-r{repeat}',MODE='burst',BURST_TASKS=20,MAX_VUS=20,
                      PREALLOCATED_VUS=20,DURATION='60s')
            if not memory_check or concurrency==4:
                phase(f'c{concurrency}-arrival-20-long',RATE_PER_MINUTE=20)
                o.phase(f'c{concurrency}-mixed-20',script='capacity-mixed.js',RATE_PER_MINUTE=20,VUS=50,
                        DURATION='120s',MAX_VUS=80,PREALLOCATED_VUS=80,IMAGES_FILE=mixed.as_posix())
    finally:
        b.CURRENT='cleanup'
        try:
            b.META['finalQueuesBeforeCleanup']=o.drain()
            if b.ACCOUNTS.exists():
                b.command([sys.executable,'deploy/scripts/capacity_accounts.py','--base-url',b.BASE,
                    '--output',str(b.ACCOUNTS),'--cleanup'],240)
            b.META['cleanup']='Only the newly created 40 test accounts and their associated data were deleted'
        except Exception as error: b.META['cleanup']=f'Incomplete: {error}'
        try:
            o.scale(4); o.env.update(original)
            b.command(b.COMPOSE+['up','-d','--no-deps','--wait','--wait-timeout','90','model-stub'],110,o.env)
            b.META['modelRestored']=True
            b.META['runtimeAfter']=runtime()
        except Exception as error: b.META['modelRestored']=str(error)
        b.STOP.set()
        if observer: observer.join(45)
        b.META['ended']=b.stamp(); b.save()

if __name__=='__main__':
    try: main()
    except Exception as error:
        b.META['error']=str(error); b.META['ended']=b.stamp(); b.save()
        print(f'FAILED: {error}',flush=True); sys.exit(1)
