"""Matched fresh-connection probes of fixed reviews, streaming versus non-streaming."""
import argparse
import copy
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import subprocess
import time
import diagnose_sf_generation as diagnostic

ROOT=Path(__file__).resolve().parents[2]
INPUT=ROOT/'logs/backend/fixed-review-20261029/requests.json'

def cases():
    fixed=json.loads(INPUT.read_text(encoding='utf-8'));assert [r['id'] for r in fixed]==['rag-036','rag-094']
    plan=[]
    for repetition in range(3):
        # Reverse pair order in the middle round to reduce simple time/order bias.
        modes=(False,True) if repetition!=1 else (True,False)
        for sample in fixed:
            for stream in modes:
                body=copy.deepcopy(sample['body']);body['stream']=stream
                if stream:body['stream_options']={'include_usage':True}
                else:body.pop('stream_options',None)
                assert body['model']==diagnostic.MODEL and body['temperature']==0 and body['max_tokens']==2048 and body['enable_thinking'] is False
                plan.append({'id':sample['id'],'variant':f'pair{repetition+1}_'+('stream' if stream else 'json'),'body':body})
    return plan

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--execute',action='store_true');args=parser.parse_args()
    plan=cases()
    if not args.execute:print(json.dumps({'calls':len(plan),'repeatsPerMode':3,'secondsPerCall':60,'freshConnections':True}));return
    diagnostic.LIMIT_SECONDS=60
    result=subprocess.run(['docker','inspect','mealcheck-backend'],capture_output=True,text=True,encoding='utf-8',check=True)
    env=dict(x.split('=',1) for x in json.loads(result.stdout)[0]['Config']['Env'] if '=' in x)
    assert env['MEALCHECK_AI_MODEL']==diagnostic.MODEL and env['MEALCHECK_AI_BASE_URL'].rstrip('/')=='https://api.siliconflow.cn/v1'
    key=env['MEALCHECK_AI_API_KEY'];assert key
    out=ROOT/'logs/backend'/('sf-review-stream-pair-'+datetime.now().strftime('%Y%m%d-%H%M%S'));out.mkdir()
    (out/'requests.json').write_bytes(INPUT.read_bytes());(out/'plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'runner.py').write_bytes(Path(__file__).read_bytes());(out/'transport.py').write_bytes(Path(diagnostic.__file__).read_bytes())
    summary={'status':'RUNNING','maximumCalls':12,'callsBefore':352,'rows':[]};failures=0
    print('Evidence: '+str(out),flush=True)
    for case in plan:
        if (out/'STOP').exists():summary.update(status='STOPPED',stopReason='USER_STOP');break
        row=diagnostic.probe(case,key)
        row['payloadSha256']=hashlib.sha256(json.dumps(case['body'],ensure_ascii=False).encode()).hexdigest()
        normalized={k:v for k,v in case['body'].items() if k not in ('stream','stream_options')}
        row['pairedInputSha256']=hashlib.sha256(json.dumps(normalized,ensure_ascii=False,sort_keys=True).encode()).hexdigest()
        summary['rows'].append(row);summary.update(callsThisRun=len(summary['rows']),authorizationCumulative=352+len(summary['rows']))
        (out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:row.get(k) for k in ['id','variant','status','connectMs','headersWaitMs','firstContentAfterSendMs','totalMs','complete','failure']},ensure_ascii=False),flush=True)
        failures=0 if row['complete'] else failures+1
        if row['status'] in (401,402,403,429) or failures>=2:
            summary.update(status='STOPPED',stopReason='PROVIDER_ERROR_OR_REPEATED_FAILURE');break
        time.sleep(2)
    else:summary['status']='COMPLETED'
    summary['finishedAt']=datetime.now(timezone.utc).isoformat();(out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Requests dispatched: '+str(len(summary['rows'])),flush=True)

if __name__=='__main__':main()
