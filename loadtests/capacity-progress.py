"""Read the latest local evidence without accessing credentials or the test stack."""
import json
from pathlib import Path
import re

root=Path(__file__).resolve().parent/'results'
folders=sorted(p for p in root.iterdir() if p.is_dir() and re.fullmatch(r'\d{8}-\d{6}',p.name))
folder=folders[-1]
def tail(name,count=1):
    path=folder/name
    if not path.exists(): return []
    with path.open('rb') as stream:
        offset=stream.seek(0,2); raw=b''
        while offset and raw.count(b'\n')<=count:
            size=min(offset,65536); offset-=size; stream.seek(offset)
            raw=stream.read(size)+raw
    records=[]
    for line in reversed(raw.splitlines()):
        try: records.append(json.loads(line))
        except (json.JSONDecodeError,UnicodeDecodeError): continue  # A writer may be midway through its last sample.
        if len(records)==count: break
    return list(reversed(records))
meta=json.loads((folder/'manifest.json').read_text(encoding='utf-8')) if (folder/'manifest.json').exists() else {}
data={'run':folder.name,'phase':meta.get('activePhase'),'ended':meta.get('ended'),'error':meta.get('error')}
jvm=tail('jvm.jsonl')
if jvm and 'os' in jvm[-1]:
    sample=jvm[-1]; lines=sample['os'].splitlines(); values=dict(re.findall(r'^([a-z_]+) (\d+)$',sample['os'],re.M))
    data.update(at=sample['at'],phase=data['phase'] or sample['phase'],
                totalMiB=round(int(lines[0])/2**20,2),peakMiB=round(int(lines[1])/2**20,2),
                workingSetMiB=round((int(lines[0])-int(values.get('inactive_file',0)))/2**20,2),
                memoryEvents={k:int(values.get(k,0)) for k in ('max','oom','oom_kill')},runtime=sample.get('runtime'))
    data['completedCounter']=[x for x in sample.get('prometheus','').splitlines() if x.startswith('mealcheck_analysis_jobs_total')]
    data['weeklyCompleted']=[x for x in sample.get('prometheus','').splitlines() if x.startswith('mealcheck_weekly_report_jobs_total')]
for sample in reversed(tail('resources.jsonl',4)):
    if 'queues' in sample:
        data['database']=sample['queues']['database']; break
print(json.dumps(data,ensure_ascii=False))
