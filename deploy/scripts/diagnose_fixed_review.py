"""Repeat fixed production-protocol review requests, never generate a new candidate answer."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import diagnose_sf_generation as diagnostic

ROOT=Path(__file__).resolve().parents[2]
INPUT=ROOT/'logs/backend/fixed-review-20261029/requests.json'

def cases():
    fixed=json.loads(INPUT.read_text(encoding='utf-8'))
    assert [x['id'] for x in fixed]==['rag-036','rag-094']
    for x in fixed:
        assert x['body']['model']==diagnostic.MODEL and x['body']['temperature']==0 and x['body']['max_tokens']==2048
        assert x['body']['enable_thinking'] is False and x['body']['stream'] is False
    return [{'id':x['id'],'variant':f'review_repeat{round+1}','body':x['body']} for round in range(3) for x in fixed]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--execute',action='store_true');args=parser.parse_args()
    plan=cases()
    if not args.execute:print(json.dumps({'requests':len(plan),'secondsPerRequest':60,'temperature':0,'maxTokens':2048}));return
    diagnostic.LIMIT_SECONDS=60
    inspected=subprocess.run(['docker','inspect','mealcheck-backend'],capture_output=True,text=True,encoding='utf-8',check=True)
    env=dict(x.split('=',1) for x in json.loads(inspected.stdout)[0]['Config']['Env'] if '=' in x)
    assert env['MEALCHECK_AI_MODEL']==diagnostic.MODEL and env['MEALCHECK_AI_BASE_URL'].rstrip('/')=='https://api.siliconflow.cn/v1'
    key=env['MEALCHECK_AI_API_KEY'];assert key
    out=ROOT/'logs/backend'/('sf-fixed-review-live-'+datetime.now().strftime('%Y%m%d-%H%M%S'));out.mkdir()
    (out/'requests.json').write_bytes(INPUT.read_bytes());(out/'runner.py').write_bytes(Path(__file__).read_bytes())
    (out/'transport.py').write_bytes(Path(diagnostic.__file__).read_bytes())
    rows=[];failures=0;summary={'kind':'fixed_review_direct_provider','maximumCalls':6,'callsBefore':346,
        'status':'RUNNING','inputFileSha256':hashlib.sha256(INPUT.read_bytes()).hexdigest(),'rows':rows}
    print('Evidence: '+str(out),flush=True)
    for case in plan:
        if (out/'STOP').exists():summary.update(status='STOPPED',stopReason='USER_STOP');break
        row=diagnostic.probe(case,key);row['payloadSha256']=hashlib.sha256(json.dumps(case['body'],ensure_ascii=False).encode()).hexdigest()
        row['maxTokens']=case['body']['max_tokens'];row['temperature']=case['body']['temperature'];rows.append(row)
        failures=0 if row['complete'] else failures+1
        summary.update(callsThisRun=len(rows),authorizationCumulative=346+len(rows))
        (out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:row.get(k) for k in ['id','variant','status','headersMs','totalMs','complete','failure','usage']},ensure_ascii=False),flush=True)
        if row['status'] in (401,402,403,429) or failures>=2:
            summary.update(status='STOPPED',stopReason='PROVIDER_ERROR_OR_REPEATED_FAILURE');break
        time.sleep(2)
    else:summary['status']='COMPLETED'
    summary['finishedAt']=datetime.now(timezone.utc).isoformat()
    (out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Requests dispatched: '+str(len(rows)),flush=True)

if __name__=='__main__':main()
