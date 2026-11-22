"""Authorized Max real-model study: bounded providers, fixed synthetic materials, owned fixture users.

No run without --execute. No credentials in public evidence; temporary accounts are deleted.
"""
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
import cookie_http as client
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import hashlib
import hmac
import json
import re
import secrets
import subprocess
import threading
import time
import uuid
from run_full_chain_pilot10 import sql,cache_for_queries
from run_rag_timing50 import parse_timings,evict_exact_queries

BASE='http://127.0.0.1:5173'  # Nginx, not the backend's direct port.
MODEL='qwen3.8-max-0902'
CAP=500
MATCHED=['rag-008','rag-036','rag-070','rag-094','rag-117','rag-128','rag-187','rag-172','rag-184','rag-001']

def api(path,token=None,body=None,method='GET',files=None):
    headers={'Authorization':'Bearer '+token} if token else {}
    kwargs={'headers':headers,'timeout':155}
    if files is not None: kwargs.update(files=files,data=body)
    elif body is not None: kwargs['json']=body
    response=client.request(method,BASE+path,**kwargs)
    try: value=response.json()
    except ValueError: value={'unparsed':True}
    return response.status_code,value

def b64(data):return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
def stream_events(raw):
    events=[]
    for block in raw.decode('utf-8').replace('\r\n','\n').split('\n\n'):
        name='message';data=[]
        for line in block.split('\n'):  # splitlines also splits legitimate Unicode NEL in content.
            if line.startswith('event:'):name=line[6:].strip()
            elif line.startswith('data:'):data.append(line[5:].lstrip())
        if data:events.append({'event':name,'data':json.loads('\n'.join(data))})
    return events
def can_admit(total,individual,maximum):
    return total+maximum*len(individual)<=CAP and all(n+maximum<=100 for n in individual)
def token_for(original,account,secret):
    # Owned load-test fixtures only. Reuse actual server auth generation and expiry.
    header=json.loads(base64.urlsafe_b64decode(original.split('.')[0]+'=='))
    claims=json.loads(base64.urlsafe_b64decode(original.split('.')[1]+'=='))
    claims.update(sub=account['username'],uid=account['id'],ver=0,jti=str(uuid.uuid4()))
    unsigned=b64(json.dumps(header,separators=(',',':')).encode())+'.'+b64(json.dumps(claims,separators=(',',':')).encode())
    algorithm={'HS256':hashlib.sha256,'HS384':hashlib.sha384,'HS512':hashlib.sha512}[header['alg']]
    original_unsigned='.'.join(original.split('.')[:2])
    assert hmac.compare_digest(b64(hmac.new(secret.encode(),original_unsigned.encode(),algorithm).digest()),original.split('.')[2]),'JWT fixture key mismatch'
    return unsigned+'.'+b64(hmac.new(secret.encode(),unsigned.encode(),algorithm).digest())

def plan():
    packet=ROOT/'tools/evaluation/rag_timing50_v1.json'
    p=json.loads(packet.read_text(encoding='utf-8'))
    for name,meta in p['sourceFiles'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==meta['sha256']
    boundary=ROOT/'tools/datasets/vision-boundaries-18-v1/dataset.json'
    v=json.loads(boundary.read_text(encoding='utf-8'))
    for c in v['cases']:assert hashlib.sha256((boundary.parent/c['image']).read_bytes()).hexdigest()==c['sha256']
    images=json.loads((ROOT/'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json').read_text(encoding='utf-8'))['cases'][:5]
    for c in images:assert hashlib.sha256((ROOT/'tools/datasets/synthetic-meals-100'/c['image']).read_bytes()).hexdigest()==c['sha256']
    return p,v,images

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--execute',action='store_true')
    parser.add_argument('--prior-attempts',type=int,default=0)
    parser.add_argument('--continue-from',type=Path)
    args=parser.parse_args()
    if args.continue_from:
        args.continue_from=args.continue_from.resolve()
        assert args.continue_from.resolve().is_relative_to(ROOT/'logs')
        previous=json.loads(args.continue_from.read_text(encoding='utf-8'))
        assert previous['model']==MODEL and previous['accountsCleanedUp']
        assert sum(r['kind']=='upload' for r in previous['cases'])==50
        args.prior_attempts=previous['providerAttempts']
    assert 0<=args.prior_attempts<=CAP
    p,v,images=plan()
    if not args.execute:
        print(json.dumps({'status':'PREPARED','questionMaximum':75,'plannedQuestions':73,'boundaryImages':18,
              'burstUsers':50,'maximumProviderAttempts':CAP,'model':MODEL}));return
    out=ROOT/'logs/backend'/('max0902-fullchain-live-'+datetime.now().strftime('%Y%m%d-%H%M%S'));out.mkdir()
    (out/'runner-executed.py').write_bytes(Path(__file__).read_bytes())
    (out/'approved-materials.json').write_text(json.dumps({'questionsSha256':hashlib.sha256((ROOT/'tools/evaluation/rag_timing50_v1.json').read_bytes()).hexdigest(),
        'boundaryCases':[{'id':c['id'],'sha256':c['sha256']} for c in v['cases']],
        'uploadCases':[{'id':c['id'],'sha256':c['sha256']} for c in images],
        'maximumProviderAttempts':CAP,'priorProviderAttempts':args.prior_attempts},indent=2),encoding='utf-8')
    print('Evidence: '+str(out),flush=True)
    accounts=[];rows=[];lock=threading.Lock();stop=threading.Event();since=datetime.now(timezone.utc).isoformat()
    summary={'status':'PREPARING','model':MODEL,'maxProviderAttempts':CAP,'priorProviderAttempts':args.prior_attempts,'accountsCleanedUp':False,
             'cases':rows,'plannedRagRequests':73,'plannedUniqueQuestions':50,'plannedBurst':50,'plannedBoundaries':18,
             'samplingLimit':'Synthetic known samples with assistant labels; not independent human gold or production SLO evidence.'}
    if args.continue_from:
        summary.update(previousEvidence=args.continue_from.relative_to(ROOT).as_posix(),plannedRagRequests=71,
                       plannedBurst=0,plannedBoundaries=17,scope='Complete remaining authorized cases; no repeat of failed 50-user burst.')
    def save(name,value):
        (out/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    def used(account):return int(sql("SELECT coalesce(sum(attempts),0) FROM ai_budget_daily WHERE quota_key='user:"+str(account['id'])+"'"))
    def usage_map():
        if not accounts:return {}
        keys=','.join("'user:"+str(a['id'])+"'" for a in accounts)
        raw=sql("SELECT coalesce(json_object_agg(quota_key,attempts),'{}') FROM (SELECT quota_key,sum(attempts) attempts FROM ai_budget_daily WHERE quota_key IN ("+keys+") GROUP BY quota_key) t")
        return {int(k.split(':')[1]):int(value) for k,value in json.loads(raw).items()}
    def spent():return args.prior_attempts+sum(usage_map().values())
    def checkpoint():
        summary['providerAttempts']=spent();save('checkpoint.json',summary)
    def capture():
        r=subprocess.run(['docker','logs','--timestamps','--since',since,'mealcheck-backend'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
        r.check_returncode();raw=r.stdout+'\n'+r.stderr
        ids={x['requestId'] for x in rows if x.get('requestId')};events=parse_timings(raw,ids)
        for row in rows:
            if row.get('requestId'):row['timingEvents']=[e for e in events if e['requestId']==row['requestId']]
        save('timing-events.json',events)
        if sum(e.get('status')==429 or e.get('status',0)>=500 for e in events if e['kind']=='http')>=2:
            stop.set();summary['stopReason']='REPEATED_PROVIDER_FAILURE_STOP'
    def execute(kind,case,account,phase,barrier=None):
        before=used(account);started=time.monotonic();key='max0902-'+uuid.uuid4().hex
        row={'kind':kind,'id':case['id'],'phase':phase,'accountIndex':account['index'],
             'startedAt':datetime.now(timezone.utc).isoformat(),'attemptsBefore':before}
        if kind=='rag':row.update(question=case['question'],answerability=case['answerability'])
        try:
            if barrier:barrier.wait(timeout=30)
            sent=time.monotonic();row['requestStartedAt']=datetime.now(timezone.utc).isoformat()
            if kind=='rag':
                response=client.post(BASE+'/api/assistant/stream',headers={'Authorization':'Bearer '+account['token'],'Accept':'text/event-stream'},
                    json={'question':case['question'],'history':[]},timeout=150)
                row['httpStatus']=response.status_code
                stream_name='stream-'+phase+'-'+case['id']+'-'+str(account['index'])+'.sse'
                (out/stream_name).write_bytes(response.content);row['rawStreamFile']=stream_name
                row['events']=stream_events(response.content)
                for event in row['events']:
                    name,value=event['event'],event['data']
                    if isinstance(value,dict) and value.get('requestId'):row['requestId']=value['requestId']
                    if name=='complete':row['response']=value
                response=row.get('response') or {};row['transportComplete']=bool(response) and response.get('deliveryStatus')=='COMPLETE'
                queries=[case['question']]
                for call in response.get('toolCalls',[]):
                    if call['name']=='search_diet_knowledge':queries.append(json.loads(call['arguments'])['query'])
                row['retrievalCaches']=cache_for_queries(queries)
                if response.get('conversationId'):
                    row['historyStatus'],row['persistedConversation']=api('/api/assistant/conversations/'+str(response['conversationId']),account['token'])
            elif kind in ('upload','boundary_pipeline'):
                image=(ROOT/'tools/datasets/synthetic-meals-100'/case['image']) if kind=='upload' else ROOT/'tools/datasets/vision-boundaries-18-v1'/case['image']
                payload=image.read_bytes();headers={'Authorization':'Bearer '+account['token'],'Idempotency-Key':key}
                response=client.post(BASE+'/api/meals/analysis-requests',headers=headers,files={'image':(image.name,payload,'image/png')},data={'goal':'balanced'},timeout=60)
                row.update(httpStatus=response.status_code,submission=response.json(),key=key,submitMs=round((time.monotonic()-sent)*1000))
                state=row['submission'];deadline=sent+240
                while state.get('status') in ('PENDING','PROCESSING') and time.monotonic()<deadline:
                    time.sleep(.6);code,state=api('/api/meals/analysis-requests/'+key,account['token'])
                    if code!=200 and code!=202:break
                row['response']=state;row['transportComplete']=state.get('status')=='SUCCEEDED'
                if row['transportComplete']:
                    record=state['result'];row['recordId']=record['recordId']
                    row['usedRuleFallback']='本地规则建议' in record.get('advice','')
                    if row['usedRuleFallback']:
                        stop.set();summary['stopReason']='MODEL_ADVICE_FALLBACK_STOP'
                    row['historyStatus'],row['persistedRecord']=api('/api/meals/'+str(row['recordId']),account['token'])
                    if kind=='upload' and account['index']==0:
                        replayBefore=used(account)
                        replay=client.post(BASE+'/api/meals/analysis-requests',headers=headers,files={'image':(image.name,payload,'image/png')},data={'goal':'balanced'},timeout=60)
                        row['idempotentReplay']={'httpStatus':replay.status_code,'response':replay.json(),'extraAttempts':used(account)-replayBefore}
            else:
                image=ROOT/'tools/datasets/vision-boundaries-18-v1'/case['image']
                code,body=api('/api/admin/vision-evaluation/recognize',account['token'],method='POST',files={'image':(image.name,image.read_bytes(),'image/png')})
                row.update(httpStatus=code,response=body,transportComplete=code==200)
                if code==200:assert body['model']==MODEL,'VISION_MODEL_MISMATCH'
            row['durationMs']=round((time.monotonic()-sent)*1000)
            if row.get('httpStatus',200) in (401,403,429,500,502,503,504):
                stop.set();summary['stopReason']='HTTP_FAILURE_STOP'
            if kind in ('upload','boundary_pipeline') and not row['transportComplete']:
                stop.set();summary['stopReason']='MEAL_PIPELINE_FAILURE_STOP'
        except Exception as error:
            row.update(errorType=type(error).__name__,transportComplete=False,durationMs=round((time.monotonic()-started)*1000))
            stop.set();summary['stopReason']='EXECUTION_ERROR_STOP'
        finally:
            row['providerAttempts']=used(account)-before;row['finishedAt']=datetime.now(timezone.utc).isoformat()
            with lock:
                row['ordinal']=len(rows)+1;rows.append(row);save(f'{row["ordinal"]:03d}-{phase}-{case["id"]}.json',row)
                print(f'{phase} {case["id"]} complete={row.get("transportComplete")} calls={row["providerAttempts"]} ms={row.get("durationMs")}',flush=True)
        return row
    def batch(kind,cases,concurrency,phase,bound):
        if stop.is_set():return False
        for offset in range(0,len(cases),concurrency):
            group=cases[offset:offset+concurrency];current=usage_map();selected=sorted(accounts,key=lambda a:current.get(a['id'],0))[:len(group)]
            if not can_admit(args.prior_attempts+sum(current.values()),[current.get(a['id'],0) for a in selected],bound):
                stop.set();summary['stopReason']='ATTEMPT_RESERVE_LIMIT';return False
            barrier=threading.Barrier(len(group)) if len(group)>1 else None
            t=time.monotonic()
            with ThreadPoolExecutor(max_workers=len(group)) as pool:
                result=list(pool.map(lambda pair:execute(kind,pair[0],pair[1],phase,barrier),zip(group,selected)))
            summary.setdefault('batches',[]).append({'phase':phase,'concurrency':len(group),'wallMs':round((time.monotonic()-t)*1000),
                        'completed':sum(r.get('transportComplete',False) for r in result),'requests':len(group)})
            assert spent()<=CAP,'APPROVED_ATTEMPT_CAP_EXCEEDED'
            assert all(r['providerAttempts']<=bound for r in result),'BUSINESS_ATTEMPT_BOUND_EXCEEDED'
            capture();checkpoint()
            if stop.is_set():return False
        return True
    try:
        assert client.get('http://127.0.0.1:8080/actuator/health',timeout=10).json()['status']=='UP'
        backend=json.loads(subprocess.check_output(['docker','inspect','mealcheck-backend'],encoding='utf-8'))[0]
        env=dict(x.split('=',1) for x in backend['Config']['Env'] if '=' in x)
        expected={'MEALCHECK_AI_MODEL':MODEL,'MEALCHECK_AI_VISION_MODEL':MODEL,'MEALCHECK_ANALYSIS_CONCURRENCY':'40',
                  'MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS':'42','MEALCHECK_REMOTE_MAX_CONCURRENT_CALLS':'42',
                  'MEALCHECK_AI_GLOBAL_MIN_START_INTERVAL_MILLIS':'400','MEALCHECK_KNOWLEDGE_AUTO_INDEX':'false'}
        assert all(env.get(k)==value for k,value in expected.items()),'DEPLOYMENT_CONFIG_MISMATCH'
        assert env.get('MEALCHECK_REMOTE_MAX_ATTEMPTS','2')=='2'
        assert env.get('MEALCHECK_KNOWLEDGE_RERANK_MODEL')=='qwen3.7-text-rerank'
        embedding=json.loads(sql("SELECT json_build_object('model',p.model,'dimension',p.dimension,'baseUrl',p.base_url) FROM knowledge_embedding_settings s JOIN knowledge_embedding_profiles p ON p.id=s.active_profile_id WHERE s.id=1"))
        assert embedding=={'model':'BAAI/bge-m3','dimension':1024,'baseUrl':'https://api.siliconflow.cn/v1'}
        summary['embedding']=embedding
        summary.update(backendImage=backend['Image'],publicConfiguration=expected,jobsBefore=int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs')),
                       inventoryBefore=json.loads(sql("SELECT json_build_object('chunks',count(*),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks")))
        summary['stage']='account_registration'
        captcha=api('/api/auth/captcha')[1];numbers=re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?',captcha['question']);assert numbers
        prefix='max0902_'+secrets.token_hex(6);password=secrets.token_urlsafe(28)
        code,auth=api('/api/auth/register',body={'username':prefix+'_00','password':password,'displayName':'Max temporary test',
                     'captchaId':captcha['captchaId'],'captchaAnswer':str(sum(map(int,numbers.groups())))},method='POST');assert code==200
        first_id=int(sql("SELECT id FROM users WHERE username='"+prefix+"_00'"))
        accounts.append({'id':first_id,'username':prefix+'_00','password':password,'token':auth['token'],'index':0})
        save('accounts.private.json',accounts)
        sql("UPDATE users SET role='ADMIN' WHERE id="+str(first_id))
        settings=api('/api/admin/ai-settings',accounts[0]['token'])[1]
        assert settings['baseUrl'].rstrip('/')=='https://dashscope.aliyuncs.com/compatible-mode/v1'
        code,settings=api('/api/admin/ai-settings',accounts[0]['token'],{'model':MODEL,'baseUrl':settings['baseUrl'],'apiKey':'','version':settings['version']},'PUT')
        assert code==200 and settings['model']==MODEL
        summary['settings']={k:settings[k] for k in ('model','baseUrl','source','version')}
        secret=env.get('MEALCHECK_JWT_SECRET','')
        if len(secret.encode())<32:secret='mealcheck-jwt-secret-change-me-please-32chars'
        summary['stage']='fixture_provisioning'
        for i in range(1,10 if args.continue_from else 50):
            name=prefix+f'_{i:02d}'
            sql("INSERT INTO users(username,password_hash,display_name,role,created_at) SELECT '"+name+"',password_hash,'Max temporary test','USER',CURRENT_TIMESTAMP FROM users WHERE id="+str(first_id))
            user_id=int(sql("SELECT id FROM users WHERE username='"+name+"'"))
            account={'id':user_id,'username':name,'password':password,'index':i};accounts.append(account)
            account['token']=token_for(auth['token'],account,secret)
            save('accounts.private.json',accounts)
        summary['stage']='fixture_authentication'
        assert all(api('/api/auth/me',a['token'])[0]==200 for a in accounts)
        summary['accountProvisioning']='1 real registration; 49 isolated DB fixture accounts sharing its password hash; per-user normal 100-call limit retained. Tokens use the existing server generation and expiry, kept private.'
        save('accounts.private.json',accounts)
        save('preflight.json',{k:value for k,value in summary.items() if k!='cases'})
        by_id={c['id']:c for c in p['cases']};initial=['rag-008','rag-172']
        summary['status']='RUNNING';summary['stage']='live'
        if not args.continue_from:
            batch('rag',[by_id[x] for x in initial],1,'rag_calibration',24)
            batch('boundary_pipeline',[v['cases'][0]],1,'vision_pipeline_calibration',8)
            burst=[dict(images[i%5],burstIndex=i) for i in range(50)]
            batch('upload',burst,50,'image_burst_50',8)
        for concurrency in (3,5,10):
            selected=[by_id[x] for x in MATCHED[:concurrency]]
            if stop.is_set():break
            save(f'cache-evictions-c{concurrency}.json',{'keysRemoved':evict_exact_queries([x['question'] for x in selected])})
            if not batch('rag',selected,concurrency,f'rag_cold_c{concurrency}',24):break
        # Only account 0 is temporarily an administrator; boundary reads are serialized under it.
        if not stop.is_set():
            for c in v['cases'][1:]:
                if spent()+2>CAP or used(accounts[0])+2>100:
                    stop.set();summary['stopReason']='ATTEMPT_RESERVE_LIMIT';break
                execute('boundary',c,accounts[0],'vision_boundaries');capture();checkpoint()
                if stop.is_set():break
        batch('rag',[c for c in p['cases'] if c['id'] not in initial],1,'rag_remaining_baseline',24)
        batch('rag',[by_id[x] for x in MATCHED[:5]],1,'rag_warm_repeat',24)
        summary['status']='COMPLETED' if not stop.is_set() else 'STOPPED'
    except Exception as error:
        summary.update(status='STOPPED',errorType=type(error).__name__)
        print('Stopped: '+type(error).__name__,flush=True)
    finally:
        if accounts:
            owned_ids=','.join(str(a['id']) for a in accounts)
            for _ in range(60):
                active=int(sql('SELECT count(*) FROM meal_analysis_requests WHERE user_id IN ('+owned_ids+") AND status IN ('PENDING','PROCESSING')"))
                if not active:break
                time.sleep(2)
            summary['pendingMealsAtCleanup']=active
        summary['providerAttempts']=spent();summary['attemptsThisExecution']=summary['providerAttempts']-args.prior_attempts
        summary['accountAttempts']=[{'index':a['index'],'attempts':used(a)} for a in accounts]
        try:capture()
        except Exception as error:summary['timingCaptureError']=type(error).__name__
        cleaned=[]
        for a in accounts:
            try:
                sql("UPDATE users SET role='USER' WHERE id="+str(a['id']))
                if a.get('token'):
                    code,_=api('/api/profile/account',a['token'],{'password':a['password']},'DELETE')
                    cleaned.append(code==204 and sql('SELECT count(*) FROM users WHERE id='+str(a['id']))=='0')
                else:cleaned.append(False)
            except Exception:cleaned.append(False)
        summary['accountsCleanedUp']=bool(accounts) and all(cleaned)
        assert summary['providerAttempts']<=CAP,'APPROVED_ATTEMPT_CAP_EXCEEDED'
        if summary['accountsCleanedUp']:(out/'accounts.private.json').unlink(missing_ok=True)
        summary.update(finishedAt=datetime.now(timezone.utc).isoformat(),
                       inventoryAfter=json.loads(sql("SELECT json_build_object('chunks',count(*),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks")),
                       jobsAfter=int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs')))
        save('results.json',summary)
        print(json.dumps({k:summary.get(k) for k in ('status','providerAttempts','accountsCleanedUp','stopReason','errorType')}),flush=True)
    if summary['status']!='COMPLETED' or not summary['accountsCleanedUp']:raise SystemExit(1)

if __name__=='__main__':main()
