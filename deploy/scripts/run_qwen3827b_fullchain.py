"""Authorized Qwen3.8-27B real-model study: bounded providers, fixed synthetic materials, owned fixture users.

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
import os
import re
import random
import secrets
import subprocess
import threading
import time
import uuid
from run_full_chain_pilot10 import sql,cache_for_queries
from run_rag_timing50 import parse_timings,evict_exact_queries

BASE='http://127.0.0.1:5173'  # Nginx, not the backend's direct port.
MODEL='qwen3.8-27b'
RERANK_MODEL='BAAI/bge-reranker-v2-m3'
RERANK_URL='https://api.siliconflow.cn/v1/rerank'
CAP=500
CHAT_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
MATCHED=['rag-008','rag-036','rag-070','rag-094','rag-117','rag-128','rag-187','rag-172','rag-184','rag-001']

def repeated_failure_stop(results):
    failures=sum(not row.get('transportComplete',False) for row in results)
    return bool(failures and (len(results)<5 or failures>=2 or failures/len(results)>=.2))

def response_stop_reason(response):
    """Transport completion is independent of model success; never inspect natural-language text."""
    result=response.get('result') or {}
    code=response.get('failureCode') or response.get('adviceFailureCode') or response.get('errorCode') or response.get('code') or result.get('adviceFailureCode')
    if code=='MODEL_RATE_LIMITED':return 'PROVIDER_RATE_LIMIT_STOP'
    if code in ('MODEL_QUOTA_EXHAUSTED','MODEL_ACCESS_DENIED','REMOTE_CONFIGURATION','AI_BUDGET_EXCEEDED'):
        return 'PROVIDER_QUOTA_OR_CONFIGURATION_STOP'
    return None

def upstream_stop_reason(events):
    statuses=[e.get('status',0) for e in events if e.get('kind')=='http']
    if any(s in (401,402,403) for s in statuses):return 'PROVIDER_ACCESS_OR_QUOTA_STOP'
    if sum(s==429 or s>=500 or s==0 for s in statuses)>=2:return 'REPEATED_PROVIDER_FAILURE_STOP'
    return None

def provider_health_stop_reason(events,http_window):
    # Background image consumers have no assistant request ID. Use the operational
    # window only as a conservative stop signal; never attribute other users' results.
    return upstream_stop_reason(events) or upstream_stop_reason([dict(e,kind='http') for e in http_window])

def cancel_owned_requests(active,cancel):
    """Use only cancellation IDs and tokens belonging to this execution; never publish tokens."""
    def one(item):
        request_id,token=item
        try:return {'requestId':request_id,'cancelled':bool(cancel(request_id,token))}
        except Exception:return {'requestId':request_id,'cancelled':False}
    with ThreadPoolExecutor(max_workers=min(8,max(1,len(active)))) as pool:return list(pool.map(one,active.items()))

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

def adaptive_group_size(total,individual,maximum,requested):
    return next((n for n in (10,5,3,1) if n<=requested and n<=len(individual)
                 and can_admit(total,individual[:n],maximum)),0)

def configure_fixture_audit(ids,output):
    """Temporarily audit only this execution's synthetic fixture accounts."""
    assert all(isinstance(i,int) and i>0 for i in ids)
    environment=os.environ.copy()
    environment.update(MEALCHECK_ADVICE_AUDIT_ENABLED='true' if ids else 'false',
                       MEALCHECK_ADVICE_AUDIT_USER_IDS=','.join(map(str,ids)),
                       MEALCHECK_ASSISTANT_REVIEW_AUDIT_ENABLED='true' if ids else 'false',
                       MEALCHECK_ASSISTANT_REVIEW_AUDIT_USER_IDS=','.join(map(str,ids)))
    stamp=datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    with (output/('audit-config-'+stamp+'.log')).open('xb') as stream:
        subprocess.run(['docker','compose','up','-d','--no-build','--no-deps','backend'],
                       cwd=ROOT,env=environment,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=120)
        subprocess.run(['docker','compose','restart','frontend'],cwd=ROOT,
                       stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=60)
    deadline=time.monotonic()+100
    while time.monotonic()<deadline:
        try:
            if (client.get('http://127.0.0.1:8080/actuator/health',timeout=3).json().get('status')=='UP'
                    and client.get(BASE+'/api/auth/csrf',timeout=3).status_code==200):return
        except Exception:pass
        time.sleep(1)
    raise RuntimeError('AUDIT_CONFIGURATION_HEALTH_TIMEOUT')
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

def write_frozen_cache(key,payload,ttl_millis):
    # -x appends stdin as the LAST argument; Lua keeps the value before SET's PX option.
    script="if ARGV[1]=='-1' then return redis.call('SET',KEYS[1],ARGV[2]) else return redis.call('SET',KEYS[1],ARGV[2],'PX',ARGV[1]) end"
    result=subprocess.run(['docker','exec','-i','mealcheck-redis','redis-cli','--raw','-x','EVAL',script,'1',key,str(ttl_millis)],
                          input=payload,check=True,capture_output=True)
    if result.stdout.strip()!=b'OK':raise RuntimeError('CACHE_REPLAY_WRITE_FAILED')

def paired_schedule(pool,selection):
    source=ROOT/'tools/evaluation/rag_timing50_v1.json'
    assert selection['source']==source.relative_to(ROOT).as_posix()
    assert selection['sourceSha256']==hashlib.sha256(source.read_bytes()).hexdigest(),'PAIRED_SOURCE_CHANGED'
    count=selection.get('plannedQuestions',30)
    assert isinstance(count,int) and not isinstance(count,bool) and 2<=count<=len(pool['cases']),'PAIRED_COUNT_INVALID'
    assert selection.get('plannedBusinessRequests',count*2)==count*2,'PAIRED_REQUEST_COUNT_INVALID'
    expected=random.Random(selection['seed']).sample(pool['cases'],count)
    assert [c['id'] for c in selection['cases']]==[c['id'] for c in expected],'PAIRED_SAMPLE_CHANGED'
    assert all(a['question']==b['question'] and a['answerability']==b['answerability'] for a,b in zip(selection['cases'],expected)),'PAIRED_QUESTION_CHANGED'
    fast_first=set(random.Random(selection['seed']+1).sample(range(count),count//2))
    schedule=[]
    for index,case in enumerate(expected):
        for mode in (('FAST','REVIEWED') if index in fast_first else ('REVIEWED','FAST')):
            schedule.append({'id':case['id'],'mode':mode,'pairIndex':index+1})
    return dict(pool,cases=expected),schedule

def main():
    global MODEL,CHAT_BASE_URL,CAP
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--execute',action='store_true')
    parser.add_argument('--provider',choices=('dashscope','siliconflow'),default='dashscope')
    parser.add_argument('--rag-concurrency',type=int,choices=(1,3,5,10),default=1)
    parser.add_argument('--small',action='store_true',help='10 fixed questions, one meal pipeline, four synthetic boundaries; at most 120 provider attempts')
    parser.add_argument('--questions-only',action='store_true',help='With --small, run only the fixed questions; no image calls')
    parser.add_argument('--review-mode',choices=('REVIEWED','FAST'),default='REVIEWED')
    parser.add_argument('--paired-selection',type=Path,help='Previously saved bounded question selection; both modes, sequential balanced order')
    parser.add_argument('--compare-from',type=Path,help='Replay the four prior production questions with their exact cached evidence')
    parser.add_argument('--cold-retrieval',action='store_true',help='Comparison corpus with fresh embedding/rerank each request; do not freeze evidence')
    parser.add_argument('--case-id',choices=('rag-008','rag-036','rag-070','rag-094','rag-117','rag-128','rag-187','rag-172','rag-184','rag-200'),action='append')
    parser.add_argument('--stop-on-repair',action='store_true')
    parser.add_argument('--attempt-cap',type=int)
    parser.add_argument('--stop-on-review-issue',action='store_true')
    parser.add_argument('--repeat-count',type=int,choices=(1,2,3,4,8),default=1,help='Bounded repeats for the comparison corpus')
    parser.add_argument('--prior-attempts',type=int,default=0)
    parser.add_argument('--continue-from',type=Path)
    args=parser.parse_args()
    assert not args.questions_only or args.small,'QUESTIONS_ONLY_REQUIRES_SMALL'
    assert args.review_mode!='FAST' or args.questions_only,'FAST_REQUIRES_SEQUENTIAL_QUESTIONS_ONLY'
    if args.provider=='siliconflow':MODEL='Qwen/Qwen3.8-27B';CHAT_BASE_URL='https://api.siliconflow.cn/v1'
    if args.continue_from:
        args.continue_from=args.continue_from.resolve()
        assert args.continue_from.resolve().is_relative_to(ROOT/'logs')
        previous=json.loads(args.continue_from.read_text(encoding='utf-8'))
        assert previous['model']==MODEL and previous['accountsCleanedUp']
        assert sum(r['kind']=='upload' for r in previous['cases'])==50
        args.prior_attempts=previous['providerAttempts']
    assert 0<=args.prior_attempts<=CAP
    p,v,images=plan()
    comparison=None
    schedule=None
    if args.paired_selection:
        assert args.small and args.questions_only and args.provider=='siliconflow' and not args.compare_from and not args.case_id and not args.continue_from and args.repeat_count==1 and args.rag_concurrency==1
        args.paired_selection=args.paired_selection.resolve()
        assert args.paired_selection.is_relative_to(ROOT/'logs')
        p,schedule=paired_schedule(p,json.loads(args.paired_selection.read_text(encoding='utf-8')))
    if args.compare_from:
        args.compare_from=args.compare_from.resolve()
        assert args.compare_from==ROOT/'logs/backend/siliconflow-qwen3827b-fullchain-live-20261028-213759/results.json'
        comparison=json.loads(args.compare_from.read_text(encoding='utf-8'))
        assert comparison['accountsCleanedUp'] and comparison['model']==MODEL
        ids=[c['id'] for c in comparison['cases'] if c['kind']=='rag']
        assert len(ids)==4
        p=dict(p,cases=[next(c for c in p['cases'] if c['id']==i) for i in ids])
        args.small=True
    assert not args.cold_retrieval or comparison is not None,'COLD_REQUIRES_COMPARISON_CORPUS'
    assert not args.case_id or comparison is not None or args.questions_only,'CASE_FILTER_REQUIRES_COMPARISON_OR_QUESTIONS_ONLY'
    if args.case_id:p=dict(p,cases=[c for c in p['cases'] if c['id'] in args.case_id])
    assert args.repeat_count==1 or comparison is not None or args.questions_only,'REPEATS_REQUIRE_COMPARISON_OR_QUESTIONS_ONLY'
    if args.small:
        assert not args.continue_from and args.prior_attempts==0 and args.rag_concurrency==1,'SMALL_REQUIRES_FRESH_SEQUENTIAL_RUN'
        CAP=120
        selected={'leftover-01','leftover-05','occlusion-04','package-03'}
        v=dict(v,cases=[c for c in v['cases'] if c['id'] in selected])
        images=images[:1]
        if comparison:CAP=80;v=dict(v,cases=[]);images=[]
        if args.questions_only:v=dict(v,cases=[]);images=[]
        if schedule:CAP=500
    if args.attempt_cap is not None:
        assert (comparison is not None or args.questions_only) and (12 if args.review_mode=='FAST' else 24)<=args.attempt_cap<=CAP
        CAP=args.attempt_cap
    if not args.execute and args.small:
        print(json.dumps({'status':'PREPARED','questions':len(schedule) if schedule else len(p['cases'][:10])*args.repeat_count,'repeatCount':args.repeat_count,'mealPipelineImages':len(images),'boundaryIds':[c['id'] for c in v['cases']],
                         'frozenEvidence':bool(comparison) and not args.cold_retrieval,'temporaryAccounts':max(3,(CAP+75)//76) if schedule else 3,'maximumProviderAttempts':CAP,'concurrency':1,'model':MODEL}));return
    if not args.execute:
        print(json.dumps({'status':'PREPARED','questionMaximum':75,'plannedQuestions':73,'boundaryImages':18,
              'burstUsers':50,'uploadStages':[1,5,10,50],'uploadRequests':66,'maximumProviderAttempts':CAP,
              'model':MODEL,'baseUrl':CHAT_BASE_URL,'remainingQuestionConcurrencyMaximum':args.rag_concurrency,
              'rerankModel':RERANK_MODEL,'rerankUrl':RERANK_URL}));return
    prefix='siliconflow-' if args.provider=='siliconflow' else ''
    out=ROOT/'logs/backend'/(prefix+'qwen3827b-fullchain-live-'+datetime.now().strftime('%Y%m%d-%H%M%S'));out.mkdir()
    (out/'runner-executed.py').write_bytes(Path(__file__).read_bytes())
    (out/'approved-materials.json').write_text(json.dumps({'questionsSha256':hashlib.sha256((ROOT/'tools/evaluation/rag_timing50_v1.json').read_bytes()).hexdigest(),
        'boundaryCases':[{'id':c['id'],'sha256':c['sha256']} for c in v['cases']],
        'uploadCases':[{'id':c['id'],'sha256':c['sha256']} for c in images],
        'maximumProviderAttempts':CAP,'priorProviderAttempts':args.prior_attempts},indent=2),encoding='utf-8')
    print('Evidence: '+str(out),flush=True)
    accounts=[];rows=[];lock=threading.Lock();stop=threading.Event();active_requests={};monitor_end=threading.Event();since=datetime.now(timezone.utc).isoformat();audit_configured=False
    summary={'status':'PREPARING','model':MODEL,'maxProviderAttempts':CAP,'priorProviderAttempts':args.prior_attempts,'accountsCleanedUp':False,
             'cases':rows,'plannedRagRequests':73,'plannedUniqueQuestions':50,'plannedBurst':50,'plannedUploadStages':[1,5,10,50],'plannedUploadRequests':66,'plannedBoundaries':18,
             'samplingLimit':'Synthetic known samples with assistant labels; not independent human gold or production SLO evidence.'}
    if args.small:
        summary.update(scope='small',plannedRagRequests=min(10,len(p['cases']))*args.repeat_count,plannedUniqueQuestions=min(10,len(p['cases'])),plannedBurst=0,
                       plannedUploadStages=[1],plannedUploadRequests=1,plannedBoundaries=4)
    if comparison:
        summary.update(scope='production_comparison',repeatCount=args.repeat_count,plannedRagRequests=len(p['cases'])*args.repeat_count,plannedUniqueQuestions=len(p['cases']),
                       plannedUploadStages=[],plannedUploadRequests=0,plannedBoundaries=0,
                       comparisonSource=args.compare_from.relative_to(ROOT).as_posix(),frozenEvidence=not args.cold_retrieval,coldRetrieval=args.cold_retrieval)
    if args.questions_only:
        summary.update(scope='small_questions_only',repeatCount=args.repeat_count,reviewMode=args.review_mode,plannedUploadStages=[],plannedUploadRequests=0,plannedBoundaries=0)
    if schedule:
        summary.update(scope='paired_questions',reviewMode='PAIRED',plannedRagRequests=len(schedule),plannedUniqueQuestions=len(p['cases']),selectionFile=args.paired_selection.relative_to(ROOT).as_posix(),selectionSha256=hashlib.sha256(args.paired_selection.read_bytes()).hexdigest())
        (out/'selection.json').write_bytes(args.paired_selection.read_bytes())
        (out/'paired-schedule.json').write_text(json.dumps(schedule,indent=2),encoding='utf-8')
    summary.update(provider=args.provider,baseUrl=CHAT_BASE_URL,remainingQuestionConcurrencyMaximum=args.rag_concurrency)
    if args.continue_from:
        summary.update(previousEvidence=args.continue_from.relative_to(ROOT).as_posix(),plannedRagRequests=71,
                       plannedBurst=0,plannedBoundaries=17,scope='Complete remaining authorized cases; no repeat of failed 50-user burst.')
    def save(name,value):
        (out/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    def halt(reason):
        with lock:
            if stop.is_set():return
            stop.set();summary.setdefault('stopReason',reason);active=dict(active_requests)
        def cancel(request_id,token):
            return client.request('DELETE',BASE+'/api/assistant/requests/'+request_id,
                                  headers={'Authorization':'Bearer '+token},timeout=5).status_code in (200,204)
        summary.setdefault('cancelledOwnedRequests',[]).extend(cancel_owned_requests(active,cancel))
    def watch_user_stop():
        while not monitor_end.wait(.5):
            if (out/'STOP').exists():halt('USER_STOP_REQUESTED');return
    monitor=threading.Thread(target=watch_user_stop,daemon=True);monitor.start()
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
        # Keep raw operational evidence from this execution; no credentials or request bodies are logged by transport.
        stamp=datetime.now().strftime('%Y%m%d-%H%M%S-%f')
        (out/('runtime-'+stamp+'.log')).write_text(raw,encoding='utf-8')
        http=[];fallbacks=[];review_decisions=[]
        for line in raw.splitlines():
            if 'Assistant grounding decision. diagnostic=' in line:
                decision=json.loads(line.split('Assistant grounding decision. diagnostic=',1)[1])
                request=re.search(r'\[requestId=([^\]]+)\]',line)
                if request and request.group(1) in ids:decision['requestId']=request.group(1);review_decisions.append(decision)
            if 'AI attempt timing. ' in line:
                item=dict(re.findall(r'(\w+)=([^,\s]+)',line.split('AI attempt timing. ',1)[1]))
                for key in ('attempt','elapsedMicros','dispatchWaitMicros','status'):
                    if key in item:item[key]=int(item[key])
                item['loggedAt']=line.split(' ',1)[0];http.append(item)
            if 'Meal advice using rule fallback. reasons=' in line:
                fallbacks.append({'loggedAt':line.split(' ',1)[0],'reasons':line.split('reasons=',1)[1].strip()})
        save('review-decisions.json',review_decisions)
        if args.stop_on_repair and any(d.get('stage')=='REPAIR_REQUESTED' for d in review_decisions):halt('REPAIR_CAUSE_CAPTURED')
        if args.stop_on_review_issue and any(d.get('localCodes') or d.get('failedChecks') or d.get('answersQuestion')=='false' or d.get('knowledgeMissingCount',0)>0 for d in review_decisions if d.get('stage') in ('PREPARE','REVIEW')):
            halt('REVIEW_ISSUE_CAPTURED')
        save('all-http-timing-window.json',http);save('advice-fallback-events.json',fallbacks)
        reason=provider_health_stop_reason(events,http)
        if reason:halt(reason)
    def execute(kind,case,account,phase,barrier=None):
        before=used(account);started=time.monotonic();key='qwen3827b-'+uuid.uuid4().hex
        row={'kind':kind,'id':case['id'],'phase':phase,'accountIndex':account['index'],
             'startedAt':datetime.now(timezone.utc).isoformat(),'attemptsBefore':before}
        if kind=='rag':row.update(question=case['question'],answerability=case['answerability'])
        frozen=[]
        try:
            if comparison and kind=='rag':
                prior=next(c for c in comparison['cases'] if c['id']==case['id'])
                assert prior['question']==case['question']
                if args.cold_retrieval:row['retrievalCacheKeysRemoved']=evict_exact_queries([case['question']])
                for cache in ([] if args.cold_retrieval else prior['retrievalCaches']):
                    cachekey=cache['cacheKey'];assert cachekey.startswith('rag:search:')
                    old=subprocess.check_output(['docker','exec','mealcheck-redis','redis-cli','--raw','GET',cachekey])
                    ttl=int(subprocess.check_output(['docker','exec','mealcheck-redis','redis-cli','PTTL',cachekey]))
                    frozen.append((cachekey,old,ttl,time.monotonic()))
                    write_frozen_cache(cachekey,json.dumps(cache['snippets'],ensure_ascii=False).encode(),1200000)
            if barrier:barrier.wait(timeout=30)
            if stop.is_set():
                row.update(transportComplete=False,skipped=True,skipReason='STOPPED_BEFORE_DISPATCH');return row
            sent=time.monotonic();row['requestStartedAt']=datetime.now(timezone.utc).isoformat()
            if kind=='rag':
                response=client.post(BASE+'/api/assistant/stream',headers={'Authorization':'Bearer '+account['token'],'Accept':'text/event-stream'},
                    json={'question':case['question'],'history':[],'reviewMode':case.get('_reviewMode',args.review_mode)},timeout=150,stream=True)
                early_id=response.headers.get('X-Assistant-Request-ID','')
                if re.fullmatch(r'[a-fA-F0-9-]{36}',early_id):
                    with lock:active_requests[early_id]=account['token']
                try:
                    if stop.is_set() and early_id:
                        client.request('DELETE',BASE+'/api/assistant/requests/'+early_id,
                                       headers={'Authorization':'Bearer '+account['token']},timeout=5)
                    raw_stream=response.content
                finally:
                    with lock:active_requests.pop(early_id,None)
                    response.close()
                row['transportMs']=round((time.monotonic()-sent)*1000)
                row['httpStatus']=response.status_code
                stream_name='stream-'+phase+'-'+case['id']+'-'+str(account['index'])+'.sse'
                (out/stream_name).write_bytes(raw_stream);row['rawStreamFile']=stream_name
                row['events']=stream_events(raw_stream)
                for event in row['events']:
                    name,value=event['event'],event['data']
                    if isinstance(value,dict) and value.get('requestId'):row['requestId']=value['requestId']
                    if name=='complete':row['response']=value
                response=row.get('response') or {};row['transportComplete']=bool(response) and response.get('deliveryStatus')=='COMPLETE'
                row['modelOutcome']=response.get('answerStatus','UNKNOWN')
                row['reviewMode']=case.get('_reviewMode',args.review_mode)
                row['answerReasonCodes']=response.get('answerReasonCodes',[])
                reason=response_stop_reason(response)
                if reason:halt(reason)
                queries=[case['question']]
                for call in response.get('toolCalls',[]):
                    if call['name']=='search_diet_knowledge':queries.append(json.loads(call['arguments'])['query'])
                row['retrievalCaches']=cache_for_queries(queries)
                if comparison:
                    row['frozenEvidenceMatches']=row['retrievalCaches']==prior['retrievalCaches']
                    if not row['frozenEvidenceMatches'] and not args.cold_retrieval:halt('FROZEN_EVIDENCE_MISMATCH')
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
                row['transportMs']=round((time.monotonic()-sent)*1000)
                if row['transportComplete']:
                    record=state['result'];row['recordId']=record['recordId']
                    row['usedRuleFallback']='本地规则建议' in record.get('advice','')
                    # A rule fallback is a quality outcome to inspect, not a transport failure.
                    row['historyStatus'],row['persistedRecord']=api('/api/meals/'+str(row['recordId']),account['token'])
                    if kind=='upload' and account['index']==0:
                        replayBefore=used(account)
                        replay=client.post(BASE+'/api/meals/analysis-requests',headers=headers,files={'image':(image.name,payload,'image/png')},data={'goal':'balanced'},timeout=60)
                        row['idempotentReplay']={'httpStatus':replay.status_code,'response':replay.json(),'extraAttempts':used(account)-replayBefore}
            else:
                image=ROOT/'tools/datasets/vision-boundaries-18-v1'/case['image']
                code,body=api('/api/admin/vision-evaluation/recognize',account['token'],method='POST',files={'image':(image.name,image.read_bytes(),'image/png')})
                row['transportMs']=round((time.monotonic()-sent)*1000)
                row.update(httpStatus=code,response=body,transportComplete=code==200)
                if code==200:assert body['model']==MODEL,'VISION_MODEL_MISMATCH'
            row['durationMs']=round((time.monotonic()-sent)*1000)
            if row.get('httpStatus',200) in (401,403,429,500,502,503,504):
                halt('HTTP_FAILURE_STOP')
            reason=response_stop_reason(row.get('response') or {})
            if reason:halt(reason)
        except Exception as error:
            row.update(errorType=type(error).__name__,transportComplete=False,durationMs=round((time.monotonic()-started)*1000))
            stop.set();summary.setdefault('stopReason','EXECUTION_ERROR_STOP')
        finally:
            for cachekey,old,ttl,clock in frozen:
                left=ttl-int((time.monotonic()-clock)*1000)
                if ttl==-2 or ttl>=0 and left<=0:
                    subprocess.run(['docker','exec','mealcheck-redis','redis-cli','DEL',cachekey],check=True,capture_output=True)
                else:
                    write_frozen_cache(cachekey,old[:-1] if old.endswith(b'\n') else old,left if ttl>=0 else -1)
            row['providerAttempts']=used(account)-before;row['finishedAt']=datetime.now(timezone.utc).isoformat()
            with lock:
                row['ordinal']=len(rows)+1;rows.append(row);save(f'{row["ordinal"]:03d}-{phase}-{case["id"]}.json',row)
                print(f'{phase} {case["id"]} complete={row.get("transportComplete")} calls={row["providerAttempts"]} ms={row.get("durationMs")}',flush=True)
        return row
    def batch(kind,cases,concurrency,phase,bound,optional=False,adaptive=False):
        if stop.is_set():return False
        offset=0
        while offset<len(cases):
            current=usage_map();available=sorted(accounts,key=lambda a:current.get(a['id'],0))
            size=min(concurrency,len(cases)-offset)
            if adaptive:
                size=adaptive_group_size(args.prior_attempts+sum(current.values()),[current.get(a['id'],0) for a in available],bound,size)
                if not size:stop.set();summary['stopReason']='ATTEMPT_RESERVE_LIMIT';return False
            group=cases[offset:offset+size];selected=available[:len(group)];actual_phase=phase+f'_c{size}' if adaptive else phase
            if not can_admit(args.prior_attempts+sum(current.values()),[current.get(a['id'],0) for a in selected],bound):
                summary.setdefault('skippedPhases',[]).append({'phase':phase,'reason':'ATTEMPT_RESERVE_LIMIT','spent':args.prior_attempts+sum(current.values()),'reserved':bound*len(group)})
                if not optional:stop.set();summary['stopReason']='ATTEMPT_RESERVE_LIMIT'
                return False
            barrier=threading.Barrier(len(group)) if len(group)>1 else None
            t=time.monotonic()
            with ThreadPoolExecutor(max_workers=len(group)) as pool:
                result=list(pool.map(lambda pair:execute(kind,pair[0],pair[1],actual_phase,barrier),zip(group,selected)))
            summary.setdefault('batches',[]).append({'phase':actual_phase,'concurrency':len(group),'wallMs':round((time.monotonic()-t)*1000),
                        'completed':sum(r.get('transportComplete',False) for r in result),'requests':len(group)})
            assert spent()<=CAP,'APPROVED_ATTEMPT_CAP_EXCEEDED'
            assert all(r['providerAttempts']<=bound for r in result),'BUSINESS_ATTEMPT_BOUND_EXCEEDED'
            if not stop.is_set() and repeated_failure_stop(result):
                stop.set();summary['stopReason']='REPEATED_PIPELINE_FAILURE_STOP'
            capture();checkpoint()
            if stop.is_set():return False
            offset+=len(group)
        return True
    try:
        assert client.get('http://127.0.0.1:8080/actuator/health',timeout=10).json()['status']=='UP'
        backend=json.loads(subprocess.check_output(['docker','inspect','mealcheck-backend'],encoding='utf-8'))[0]
        env=dict(x.split('=',1) for x in backend['Config']['Env'] if '=' in x)
        expected={'MEALCHECK_AI_MODEL':MODEL,'MEALCHECK_AI_VISION_MODEL':MODEL,'MEALCHECK_ANALYSIS_CONCURRENCY':'40',
                  'MEALCHECK_AI_GLOBAL_MAX_CONCURRENT_CALLS':'42','MEALCHECK_REMOTE_MAX_CONCURRENT_CALLS':'42',
                  'MEALCHECK_AI_GLOBAL_MIN_START_INTERVAL_MILLIS':'400','MEALCHECK_KNOWLEDGE_AUTO_INDEX':'false',
                  'MEALCHECK_AI_MINIMUM_HTTP_EXECUTION_MILLIS':'1000','MEALCHECK_AI_MAX_ADMISSION_WAITERS':'1024'}
        assert all(env.get(k)==value for k,value in expected.items()),'DEPLOYMENT_CONFIG_MISMATCH'
        assert env.get('MEALCHECK_REMOTE_MAX_ATTEMPTS','2')=='2'
        assert env.get('MEALCHECK_KNOWLEDGE_RERANK_MODEL')==RERANK_MODEL
        assert env.get('MEALCHECK_KNOWLEDGE_RERANK_URL')==RERANK_URL
        assert env.get('MEALCHECK_KNOWLEDGE_RERANK_ENABLED')=='true'
        assert env.get('MEALCHECK_ADVICE_AUDIT_ENABLED','false')=='false','AUDIT_MUST_START_DISABLED'
        assert sql("SELECT count(*) FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING')")=='0','EXISTING_MEALS_PENDING'
        embedding=json.loads(sql("SELECT json_build_object('model',p.model,'dimension',p.dimension,'baseUrl',p.base_url) FROM knowledge_embedding_settings s JOIN knowledge_embedding_profiles p ON p.id=s.active_profile_id WHERE s.id=1"))
        assert embedding=={'model':'BAAI/bge-m3','dimension':1024,'baseUrl':'https://api.siliconflow.cn/v1'}
        assert sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")>='52','MIGRATION_52_REQUIRED'
        assert sql("SELECT count(*) FROM ai_call_waiters")=='0','STALE_ADMISSION_WAITERS'
        summary['embedding']=embedding
        summary.update(backendImage=backend['Image'],publicConfiguration=expected,jobsBefore=int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs')),
                       inventoryBefore=json.loads(sql("SELECT json_build_object('chunks',count(*),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks")))
        summary['stage']='account_registration'
        captcha=api('/api/auth/captcha')[1];numbers=re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?',captcha['question']);assert numbers
        prefix='qwen3827b_'+secrets.token_hex(6);password=secrets.token_urlsafe(28)
        code,auth=api('/api/auth/register',body={'username':prefix+'_00','password':password,'displayName':'27B temporary test',
                     'captchaId':captcha['captchaId'],'captchaAnswer':str(sum(map(int,numbers.groups())))},method='POST');assert code==200
        first_id=int(sql("SELECT id FROM users WHERE username='"+prefix+"_00'"))
        accounts.append({'id':first_id,'username':prefix+'_00','password':password,'token':auth['token'],'index':0})
        save('accounts.private.json',accounts)
        sql("UPDATE users SET role='ADMIN' WHERE id="+str(first_id))
        settings=api('/api/admin/ai-settings',accounts[0]['token'])[1]
        assert settings['baseUrl'].rstrip('/')==CHAT_BASE_URL and settings['model']==MODEL,'ACTIVE_CONNECTION_MISMATCH'
        summary['settings']={k:settings[k] for k in ('model','baseUrl','source','version')}
        secret=env.get('MEALCHECK_JWT_SECRET','')
        if len(secret.encode())<32:secret='mealcheck-jwt-secret-change-me-please-32chars'
        summary['stage']='fixture_provisioning'
        fixture_count=max(3,(CAP+75)//76) if schedule else 3 if args.small else 10 if args.continue_from else 50
        for i in range(1,fixture_count):
            name=prefix+f'_{i:02d}'
            sql("INSERT INTO users(username,password_hash,display_name,role,created_at) SELECT '"+name+"',password_hash,'27B temporary test','USER',CURRENT_TIMESTAMP FROM users WHERE id="+str(first_id))
            user_id=int(sql("SELECT id FROM users WHERE username='"+name+"'"))
            account={'id':user_id,'username':name,'password':password,'index':i};accounts.append(account)
            account['token']=token_for(auth['token'],account,secret)
            save('accounts.private.json',accounts)
        summary['stage']='fixture_authentication'
        assert all(api('/api/auth/me',a['token'])[0]==200 for a in accounts)
        summary['accountProvisioning']=f'1 real registration; {len(accounts)-1} owned DB fixture accounts; normal per-user limit and server token expiry retained; private tokens deleted after cleanup.'
        save('accounts.private.json',accounts)
        # Set before recreation so finally restores defaults even if startup fails.
        audit_configured=True
        configure_fixture_audit([a['id'] for a in accounts],out)
        since=datetime.now(timezone.utc).isoformat()
        summary['fixtureAdviceAuditEnabled']=True
        summary['reranker']={'model':RERANK_MODEL,'url':RERANK_URL}
        save('preflight.json',{k:value for k,value in summary.items() if k!='cases'})
        by_id={c['id']:c for c in p['cases']};initial=[c['id'] for c in p['cases'][:10]]
        summary['status']='RUNNING';summary['stage']='live'
        assert not args.continue_from,'Use one execution; retained cumulative evidence must not reset the budget'
        save('cache-evictions-initial.json',{'keysRemoved':[] if comparison else evict_exact_queries([c['question'] for c in (p['cases'][:10] if args.small else p['cases'])])})
        if schedule:
            for item in schedule:
                if stop.is_set():break
                case=dict(by_id[item['id']],_reviewMode=item['mode'])
                save('cache-eviction-pair-'+str(item['pairIndex'])+'-'+item['mode']+'.json',{'keysRemoved':evict_exact_queries([case['question']])})
                if not batch('rag',[case],1,'pair'+str(item['pairIndex'])+'_'+item['mode'].lower(),12 if item['mode']=='FAST' else 24):break
        else:
            for repetition in range(args.repeat_count):
                if stop.is_set():break
                phase='rag_pilot10' if args.repeat_count==1 else f'rag_repeat{repetition+1}'
                batch('rag',[by_id[x] for x in initial],1,phase,12 if args.review_mode=='FAST' else 24)
        if args.small:
            batch('upload',images,1,'image_small',8)
            for c in v['cases']:
                if stop.is_set():break
                if spent()+2>CAP or used(accounts[0])+2>100:
                    halt('ATTEMPT_RESERVE_LIMIT');break
                execute('boundary',c,accounts[0],'vision_boundaries_small');capture();checkpoint()
        else:
            for level in (1,5,10,50):
                burst=[dict(images[i%5],burstIndex=i) for i in range(level)]
                if not batch('upload',burst,level,f'image_c{level}',8):break
            if not stop.is_set():
                for c in v['cases']:
                    if spent()+2>CAP or used(accounts[0])+2>100:
                        stop.set();summary['stopReason']='ATTEMPT_RESERVE_LIMIT';break
                    execute('boundary',c,accounts[0],'vision_boundaries');capture();checkpoint()
                    if stop.is_set():break
            remaining=[c for c in p['cases'] if c['id'] not in initial]
            batch('rag',remaining,args.rag_concurrency,'rag_remaining',24,adaptive=args.rag_concurrency>1)
            # Spend on the fixed corpus before optional repeats or extra question load.
            for concurrency in (3,5,10):
                if stop.is_set():break
                selected=[by_id[x] for x in MATCHED[:concurrency]]
                save(f'cache-evictions-c{concurrency}.json',{'keysRemoved':evict_exact_queries([x['question'] for x in selected])})
                batch('rag',selected,concurrency,f'rag_cold_c{concurrency}',24,optional=True)
            for c in remaining[-5:]:
                if stop.is_set():break
                batch('rag',[c],1,'rag_immediate_repeat',24,optional=True)
        summary['status']='COMPLETED' if not stop.is_set() else 'STOPPED'
    except Exception as error:
        summary.update(status='STOPPED',errorType=type(error).__name__)
        print('Stopped: '+type(error).__name__,flush=True)
    finally:
        monitor_end.set();monitor.join(timeout=1)
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
        if audit_configured:
            review_audits=[]
            for file in (ROOT/'logs/advice-audit/review').glob('*.json'):
                event=json.loads(file.read_text(encoding='utf-8'))
                if event.get('fixtureUserId') in {a['id'] for a in accounts}:review_audits.append(event)
            save('fixture-review-audit.json',review_audits)
            audit_rows=[]
            for file in (ROOT/'logs/advice-audit').glob('*.json'):
                event=json.loads(file.read_text(encoding='utf-8'))
                if event.get('fixtureUserId') in {a['id'] for a in accounts}:audit_rows.append(event)
            save('fixture-advice-audit.json',audit_rows)
            try:
                configure_fixture_audit([],out);summary['fixtureAdviceAuditRestored']=True
            except Exception as error:
                summary['fixtureAdviceAuditRestored']=False;summary['auditRestoreError']=type(error).__name__
        assert summary['providerAttempts']<=CAP,'APPROVED_ATTEMPT_CAP_EXCEEDED'
        if summary['accountsCleanedUp']:(out/'accounts.private.json').unlink(missing_ok=True)
        summary.update(finishedAt=datetime.now(timezone.utc).isoformat(),
                       inventoryAfter=json.loads(sql("SELECT json_build_object('chunks',count(*),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks")),
                       jobsAfter=int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs')))
        save('results.json',summary)
        print(json.dumps({k:summary.get(k) for k in ('status','providerAttempts','accountsCleanedUp','stopReason','errorType')}),flush=True)
    if summary['status']!='COMPLETED' or not summary['accountsCleanedUp']:raise SystemExit(1)

if __name__=='__main__':main()
