"""Collect only non-secret runtime fields and verify this run's cleanup."""
from datetime import datetime, timezone
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
root = parser.parse_args().evidence.resolve()
m = json.loads((root/'manifest.json').read_text(encoding='utf-8'))
assert m.get('ended') and m.get('modelRestored') is True
ids = re.search(r'user_id IN \(([\d,]+)\)',(root/'seed-records.sql').read_text()).group(1)
assert len(ids.split(',')) == 40 and all(int(v)>0 for v in ids.split(','))

def command(args):
    return subprocess.check_output(args,text=True,encoding='utf-8',timeout=30)

services = ['backend','frontend','postgres','redis','rabbitmq','model-stub']
raw = json.loads(command(['docker','inspect',*[f'mealcheck-capacity-{s}-1' for s in services]]))
data = {'at':datetime.now(timezone.utc).isoformat(),'containers':[]}
for service, container in zip(services, raw):
    state = container['State']
    item = {'service':service,'status':state['Status'],'health':state.get('Health',{}).get('Status'),
            'restartCount':container['RestartCount'],'oomKilled':state['OOMKilled'],
            'memoryBytes':container['HostConfig']['Memory'],'nanoCpus':container['HostConfig']['NanoCpus']}
    settings = dict(v.split('=',1) for v in container['Config']['Env'])
    if service=='backend':
        data['backendSettings'] = {k:settings.get(k) for k in ['MEALCHECK_ANALYSIS_CONCURRENCY',
            'MEALCHECK_AI_BASE_URL','MEALCHECK_JWT_EXPIRATION_MINUTES','MEALCHECK_UPLOAD_TMP_DIR',
            'MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS','MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES',
            'MEALCHECK_UPLOAD_MAX_PENDING_DECODES','MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES','JAVA_TOOL_OPTIONS']}
    elif service=='model-stub':
        data['modelSettings'] = {k:settings.get(k) for k in m['originalModel']}
    elif service=='frontend':
        item['tmpfs'] = container['HostConfig']['Tmpfs']
    data['containers'].append(item)

sql = "SELECT json_build_object(" + ','.join([
    f"'testUsers',(SELECT count(*) FROM users WHERE id IN ({ids}))",
    f"'testRecords',(SELECT count(*) FROM meal_records WHERE user_id IN ({ids}))",
    f"'testTasks',(SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ({ids}))",
    "'allPending',(SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING'))",
    "'unpublished',(SELECT count(*) FROM meal_analysis_outbox WHERE published_at IS NULL)"]) + ');'
data['database'] = json.loads(command(['docker','exec','mealcheck-capacity-postgres-1','psql',
    '-U','mealcheck','-d','mealcheck','-At','-c',sql]))
data['queues'] = json.loads(command(['docker','exec','mealcheck-capacity-rabbitmq-1','rabbitmqctl','-q',
    'list_queues','name','messages_ready','messages_unacknowledged','--formatter','json']))
(root/'final-health.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(data,ensure_ascii=False))
assert all(c['health']=='healthy' and c['status']=='running' and not c['oomKilled'] and
           c['restartCount']==0 for c in data['containers'])
assert not any(data['database'].values())
assert all(q['messages_ready']==0 and q['messages_unacknowledged']==0 for q in data['queues'])
assert data['backendSettings']['MEALCHECK_ANALYSIS_CONCURRENCY']=='4'
assert data['modelSettings']==m['originalModel']

temporary = data['backendSettings']['MEALCHECK_UPLOAD_TMP_DIR']
assert temporary == '/app/upload-temp'
files = command(['docker','exec','mealcheck-capacity-backend-1','find',temporary,'-type','f','-printf','%s\n']).splitlines()
volume = next(x for x in raw[0]['Mounts'] if x['Destination']==temporary)
storage = {'at':data['at'],'directory':temporary,'fileCount':len(files),
           'fileBytes':sum(int(size) for size in files),'mountType':volume['Type'],
           'volumeName':volume.get('Name'),'applicationBudgetBytes':int(data['backendSettings']['MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES']),
           'note':'Application admission budget, not filesystem quota; measured after all requests ended'}
(root/'storage.json').write_text(json.dumps(storage,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(storage,ensure_ascii=False))
assert storage['mountType']=='volume' and storage['fileCount']==0 and storage['fileBytes']==0
