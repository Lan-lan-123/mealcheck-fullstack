"""Summarize recorded capacity evidence without contacting the running stack."""
import collections
import json
from pathlib import Path
import re
import sys


def summarize(path):
    manifest=json.loads((path/'manifest.json').read_text(encoding='utf-8'))
    resources=collections.defaultdict(list)
    if (path/'resources.jsonl').exists():
        for line in (path/'resources.jsonl').open(encoding='utf-8'):
            sample=json.loads(line)
            resources[sample['phase']].append(sample)
    phases=[]
    for phase in manifest['phases']:
        result_file=path/(phase['name']+'.json')
        if not result_file.exists(): continue
        result=json.loads(result_file.read_text(encoding='utf-8'))
        entry={'name':phase['name'],'exitCode':phase.get('exitCode'),'capacity':result.get('capacity'),
               'metrics':{k:v.get('values') for k,v in result.get('k6',{}).get('metrics',{}).items()
                          if k in ('meal_list_success','meal_list_duration','meal_list_requests','assistant_success','assistant_ms',
                                   'weekly_success','weekly_ms','weekly_multiagent_generated','weekly_invalidated')},
               'modelStats':phase.get('modelStats')}
        samples=resources[phase['name']]
        backend=[c for s in samples for c in s.get('containers',[]) if c['Name']=='mealcheck-capacity-backend-1']
        if backend:
            cpu=[float(c['CPUPerc'].rstrip('%')) for c in backend]
            entry['backendCpuPercent']={'max':max(cpu),'mean':sum(cpu)/len(cpu),'samples':len(cpu)}
            memory=[]
            for sample in backend:
                value,unit=re.match(r'([\d.]+)(\w+)',sample['MemUsage']).groups()
                memory.append(float(value)*{'B':2**-20,'KiB':2**-10,'MiB':1,'GiB':2**10}[unit])
            entry['backendWorkingSetMiB']={'maxSample':max(memory),'last':memory[-1],'samples':len(memory)}
        queues=[s['queues']['database']['pending'] for s in samples if 'queues' in s]
        if queues:
            entry['databasePending']={'first':queues[0],'last':queues[-1],'max':max(queues),'samples':len(queues)}
        phases.append(entry)
    peaks=collections.defaultdict(float)
    events={}
    if (path/'jvm.jsonl').exists():
        for line in (path/'jvm.jsonl').open(encoding='utf-8'):
            sample=json.loads(line); os_lines=sample.get('os','').splitlines()
            if len(os_lines)>1 and os_lines[1].isdigit():
                container=sample.get('runtime',{}).get('id','unknown')
                peaks[container]=max(peaks[container],int(os_lines[1])/2**20)
                events[container]={k:int(v) for k,v in re.findall(r'^(max|oom|oom_kill) (\d+)$',sample['os'],re.M)}
    return {'run':manifest['run'],'mode':manifest.get('mode'),'label':manifest.get('label'),
            'activePhase':manifest.get('activePhase'),'ended':manifest.get('ended'),
            'error':manifest.get('error'),'phases':phases,'cgroupPeakMiBByContainer':dict(peaks),
            'memoryEventsByContainer':events,'cleanupVerification':manifest.get('cleanupVerification'),
            'modelRestored':manifest.get('modelRestored')}


if __name__=='__main__':
    path=Path(sys.argv[1]); result=summarize(path)
    (path/'run-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
