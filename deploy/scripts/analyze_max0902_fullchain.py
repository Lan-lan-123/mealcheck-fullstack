"""Reconcile real-model runs without treating transport success as answer/recognition correctness."""
import argparse
from collections import Counter,defaultdict
from datetime import datetime
import json
import math
from pathlib import Path
import re
from analyze_rag_timing50 import describe_request,distribution

ROOT=Path(__file__).resolve().parents[2]
def http_window_metrics(events,rows):
    def clock(value):return datetime.fromisoformat(value.replace('Z','+00:00'))
    first=min(clock(r['requestStartedAt']) for r in rows);last=max(clock(r['finishedAt']) for r in rows)
    selected=[e for e in events if first<=clock(e['loggedAt'])<=last]
    marks=[];by_operation=defaultdict(list)
    for e in selected:
        end=clock(e['loggedAt']).timestamp();start=end-e['elapsedMicros']/1e6
        marks.extend([(start,1),(end,-1)]);by_operation[e['operation']].append(e)
    active=peak=0
    for _,delta in sorted(marks):active+=delta;peak=max(peak,active)
    assert len(selected)==sum(r['providerAttempts'] for r in rows),'HTTP window does not reconcile with owned user ledger'
    return {'attempts':len(selected),'httpInFlightPeak':peak,
        'operations':{name:{'attempts':len(es),'failures':sum(e['outcome']!='success' for e in es),
            'httpDuration':distribution([e['elapsedMicros']/1000 for e in es]),
            'dispatchWait':distribution([e['dispatchWaitMicros']/1000 for e in es])} for name,es in by_operation.items()}}
def boundary_check(row,label):
    body=row.get('response') or {}
    recognition=(body.get('result') or {}).get('recognition',{}) if row['kind']=='boundary_pipeline' else body.get('recognition',{})
    if row.get('httpStatus')==400:
        actual=body.get('code','ERROR')
    elif recognition:
        foods=recognition.get('foods',[])
        actual='NON_FOOD' if not recognition.get('foodImage',True) else (
            'UNCERTAIN_ONLY' if foods and all(f.get('identityEvidence')=='UNKNOWN' for f in foods) else 'FOOD')
    else:actual='ERROR'
    checks={'expectedOutcome':actual in label['outcomes']}
    if actual=='FOOD' and label['outcomes']==['FOOD']:
        foods=recognition.get('foods',[])
        checks['namedFood']=any(any(n in f.get('name','') for n in label['names']) and f.get('category')==label['category'] for f in foods)
    if actual=='UNCERTAIN_ONLY' and label.get('requireUnknownIdentity'):
        checks['allUnknown']=all(f.get('identityEvidence')=='UNKNOWN' for f in recognition.get('foods',[]))
    checks['noInventedIntake']=not re.search(r'(?:摄入|吃下|用油量|喝下)[^。\n]{0,10}\d+(?:\.\d+)?\s*(?:克|毫升|千卡)',json.dumps(recognition,ensure_ascii=False))
    return {'id':row['id'],'actualOutcome':actual,'checks':checks,'passed':all(checks.values()),'httpStatus':row.get('httpStatus')}

def image_check(row,label):
    if not row.get('transportComplete'):return {'id':row['id'],'evaluable':False,'failure':(row.get('response') or {}).get('errorCode',row.get('errorType'))}
    result=row['response']['result'];recognition=result['recognition'];foods=recognition.get('foods') or []
    used=set();matched=correct=0
    for expected in label['foods']:
        names={expected['name'],*expected.get('aliases',[])}
        chosen=next((i for i,f in enumerate(foods) if i not in used and f['name'] in names),None)
        if chosen is not None:
            used.add(chosen);matched+=1;correct+=foods[chosen]['category']==expected['category']
    score=result['evaluation']['score'];detail=result['evaluation'].get('scoreBreakdown') or {}
    history=row.get('persistedRecord') or {}
    return {'id':row['id'],'evaluable':True,'expectedFoods':len(label['foods']),'predictedFoods':len(foods),
            'matchedFoods':matched,'categoryCorrect':correct,'score':score,
            'breakdownConservesScore':bool(detail) and sum(x['points'] for x in detail['items'])==detail['total']==score and 0<=score<=100,
            'fixedBalancedFixtureScoreCorrect':score==90, # all 5 frozen upload fixtures: staple + protein + vegetable, balanced goal.
            'persistedRecordMatches':row.get('historyStatus')==200 and history.get('id')==result['recordId'] and history.get('score')==score,
            'usedRuleFallback':row.get('usedRuleFallback',False)}

def known_semantic_checks(row):
    response=row.get('response') or {};text=response.get('summary','');full=text+' '+' '.join(response.get('suggestions',[]))
    checks={}
    if row['id']=='rag-128' and re.search('差值|作差|前后差|减去',full):
        checks['weightDifferenceConditions']=bool(re.search('没有其他损失|无其他损失|洒出|损失|分享|添加',full))
    if row['id']=='rag-060' and re.search('分之|\d+/\d+|一半',full):checks['equalSliceCondition']=bool(re.search('平均|等分|等大|均分|均匀',full))
    if row['id']=='rag-070':checks['coreCaffeineJudgment']='咖啡因' in text or bool(re.search('完全没有|不等于零',text))
    if row['id']=='rag-120' and '不同' in full:checks['uncertainDifferenceNotStrengthened']=bool(re.search('可能|不一定|未必',full))
    if row['id']=='rag-073':checks['noCategoricalFruitBan']=not bool(re.search('(?:果干|蜜饯).{0,5}不能直接替代新鲜水果',full))
    if row['id'] in ('rag-191','rag-197'):checks['noMedicalRefusalTemplate']=not bool(re.search('诊断|用药',full))
    if row['answerability']=='INSUFFICIENT_EVIDENCE':checks['statesInputLimitation']=bool(re.search('不能|无法|不等于|缺少|不应|不确定|没有|未知',full))
    return checks

def analyze(paths,include_http_windows=True):
    payloads=[json.loads(p.read_text(encoding='utf-8')) for p in paths]
    rows=[]
    for path,p in zip(paths,payloads):
        for original in p['cases']:
            r=dict(original,sourceBatch=path.parent.name,sourceOrdinal=original['ordinal'],ordinal=len(rows)+1)
            rows.append(r)
    rag=[r for r in rows if r['kind']=='rag'];uploads=[r for r in rows if r['kind']=='upload'];boundaries=[r for r in rows if r['kind'] in ('boundary','boundary_pipeline')]
    measurable=[r for r in rag if r.get('response')]
    described=[describe_request(r) for r in measurable]
    groups=defaultdict(list)
    for r,d in zip(measurable,described):groups[r['phase']].append(d)
    labeldata=json.loads((ROOT/'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json').read_text(encoding='utf-8'))
    labels={c['id']:c for c in labeldata['cases'][:5]}
    image_checks=[image_check(r,labels[r['id']]) for r in uploads];evaluable=[r for r in image_checks if r['evaluable']]
    boundarylabels={c['id']:c for c in json.loads((ROOT/'tools/datasets/vision-boundaries-18-v1/dataset.json').read_text(encoding='utf-8'))['cases']}
    boundary_checks=[boundary_check(r,boundarylabels[r['id']]) for r in boundaries]
    supported=[]
    for r in measurable:
        checks=known_semantic_checks(r);resp=r['response'];refs=resp.get('references',[])
        supported.append({'ordinal':r['ordinal'],'id':r['id'],'phase':r['phase'],'summary':resp.get('summary',''),
            'citationsPresent':bool(refs),'earlyRefusal':r['answerability']=='OUT_OF_SCOPE' and not refs and not resp.get('toolCalls'),
            'knownRegressionChecks':checks,'regressionsPassed':all(checks.values()),
            'limitedOrFailedVerification':bool(re.search('未能在时限|未完成.*核验|证据不足.*无法|证据不足.*不能',resp.get('summary','')))})
    attempts=[]
    for p in payloads:attempts.append(p.get('attemptsThisExecution',p.get('providerAttempts',0)-p.get('priorProviderAttempts',0)))
    total=sum(attempts)
    assert total==payloads[-1]['providerAttempts'],(attempts,payloads[-1]['providerAttempts'])
    assert total<=500
    all_complete=payloads[-1]['status']=='COMPLETED'
    result={'method':{'quantiles':'median and nearest-rank P95, milliseconds; counts and valid denominators retained',
        'clientDuration':'Includes parsing, own-history verification and cache evidence reads; not pure model time.',
        'quality':'Known regression rules and synthetic assistant-authored labels are not independent human semantic/vision gold.',
        'budget':'Incremental account attempts per execution, not cumulative sums counted twice. Failed initial SSE parse used 4 attempts.',
        'stopRule':'Failed image burst was not rerun. RAG and boundaries continued separately under the remaining total budget.'},
        'status':'COMPLETE_WITH_FAILURES' if all_complete else 'PARTIAL',
        'counts':{'ragRequestsIncludingHarnessFailure':len(rag),'parsedRagResponses':len(measurable),'uniqueQuestions':len({r['id'] for r in measurable}),
            'harnessUnassessable':len(rag)-len(measurable),'uploadSubmissions':len(uploads),'uploadsAccepted':sum(r.get('httpStatus')==202 for r in uploads),
            'uploadsSaved':len(evaluable),'uploadFailures':len(uploads)-len(evaluable),'providerAttempts':total,
            'attemptsPerExecution':attempts,'boundaryCases':len(boundaries),'boundaryChecksPassed':sum(r['passed'] for r in boundary_checks)},
        'ragPhaseMetrics':{name:{'n':len(ds),'clientObserved':distribution([d['endToEndMs'] for d in ds]),
            'queue':distribution([d['admissionQueueMs'] for d in ds]),'retrieval':distribution([d['retrievalTotalMs'] for d in ds]),
            'generation':distribution([d['generationTotalMs'] for d in ds]),'review':distribution([d['reviewTotalMs'] for d in ds]),
            'evidenceDelivery':sum(d['withEvidenceDelivery'] for d in ds)} for name,ds in groups.items()},
        'imageMetrics':{'allTerminalClientDuration':distribution([r.get('durationMs') for r in uploads]),
            'successfulClientDuration':distribution([r.get('durationMs') for r in uploads if r.get('transportComplete')]),
            'modelAdviceAccepted':sum(not r['usedRuleFallback'] for r in evaluable),'ruleFallbacks':sum(r['usedRuleFallback'] for r in evaluable),
            'scoringConservation':{'passed':sum(r['breakdownConservesScore'] for r in evaluable),'denominator':len(evaluable)},
            'fixedFixtureScore':{'passed':sum(r['fixedBalancedFixtureScoreCorrect'] for r in evaluable),'denominator':len(evaluable)},
            'historyRoundTrip':{'passed':sum(r['persistedRecordMatches'] for r in evaluable),'denominator':len(evaluable)},
            'matchedFoodNames':sum(r['matchedFoods'] for r in evaluable),'expectedFoodNamesOnSavedResults':sum(r['expectedFoods'] for r in evaluable),
            'categoryCorrectOnSavedResults':sum(r['categoryCorrect'] for r in evaluable),
            'predictionCountOnSavedResults':sum(r['predictedFoods'] for r in evaluable),
            'failureCodes':dict(Counter(r['failure'] for r in image_checks if not r['evaluable']))},
        'imageChecks':image_checks,'boundaryChecks':boundary_checks,'ragKnownRegressionChecks':supported,
        'batches':[b for p in payloads for b in p.get('batches',[])],
        'accountsCleanedUp':all(p.get('accountsCleanedUp') for p in payloads),
        'knowledgeIndexUnchanged':all(p.get('inventoryBefore')==p.get('inventoryAfter') and p.get('jobsBefore')==p.get('jobsAfter')==2 for p in payloads),
        'rows':rows}
    for path in paths:
        evidence=path.parent/'all-http-timing-window.json'
        if include_http_windows and evidence.exists() and uploads:result['imageHttpWindow']=http_window_metrics(json.loads(evidence.read_text(encoding='utf-8')),uploads)
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--results',nargs='+',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    assert args.output.resolve().is_relative_to(ROOT/'logs');args.output.mkdir(exist_ok=True,parents=True)
    result=analyze(args.results)
    (args.output/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (args.output/'rag-for-offline-review.json').write_text(json.dumps({'cases':[r for r in result['rows'] if r['kind']=='rag' and r.get('response')]},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('status','counts','imageMetrics','accountsCleanedUp','knowledgeIndexUnchanged')},ensure_ascii=False))

if __name__=='__main__':main()
