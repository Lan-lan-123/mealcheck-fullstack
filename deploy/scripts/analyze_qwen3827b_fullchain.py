"""Describe the authorized 27B study; transport completion never serves as semantic accuracy."""
import argparse
from collections import Counter,defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
from analyze_max0902_fullchain import analyze as baseline_analysis,http_window_metrics
from analyze_rag_timing50 import distribution

ROOT=Path(__file__).resolve().parents[2]

def latency(rows):
    return distribution([r['transportMs'] for r in rows if r.get('transportMs') is not None])

def answer_outcomes(rows):
    """Model outcome is separate from transport delivery and from semantic accuracy."""
    questions=[r for r in rows if r['kind']=='rag']
    return {'questions':len(questions),
            'statuses':dict(Counter((r.get('response') or {}).get('answerStatus','UNKNOWN') for r in questions)),
            'partialReasons':dict(Counter(code for r in questions if (r.get('response') or {}).get('answerStatus')=='PARTIAL'
                                          for code in r['response'].get('answerReasonCodes',[]))),
            'failureCodes':dict(Counter(r['response']['failureCode'] for r in questions if (r.get('response') or {}).get('failureCode')))}

def analyze(paths):
    result=baseline_analysis(paths,include_http_windows=False)
    result['method'].update(budget='A newly authorized total cap of 500; count attemptsThisExecution once, including retries and failures.',
        stopRule='Pilot followed by image concurrency 1/5/10/50. Stop escalation on repeated failures or quota/config errors. Optional question concurrency may be skipped if its worst-case reserve does not fit.',
        clientDuration='Original clientObserved includes evidence/history collection; phase transportDuration ends when the business response/terminal result is received, excluding later evidence reads.',
        quality='Frozen assistant-authored synthetic labels and named regression rules; semantic correctness requires recorded per-answer source review, not citations or successful review alone.')
    payloads=[json.loads(p.read_text(encoding='utf-8')) for p in paths]
    models={p['model'] for p in payloads}
    assert len(models)==1 and models<= {'qwen3.8-27b','Qwen/Qwen3.8-27B'},'MODEL_MISMATCH'
    result['model']=next(iter(models))
    result['status']='COMPLETED' if all(p['status']=='COMPLETED' for p in payloads) else 'STOPPED_PARTIAL'
    result['stops']=[{k:p.get(k) for k in ('status','stopReason','errorType','stage','skippedPhases')} for p in payloads]
    result['sourceResults']=[{'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths]
    result['ledgerReconciles']=all(sum(a['attempts'] for a in p.get('accountAttempts',[]))==p.get('attemptsThisExecution',0) for p in payloads)
    phases=defaultdict(list)
    for r in result['rows']:phases[r['phase']].append(r)
    result['phaseMetrics']={}
    result['answerOutcomes']=answer_outcomes(result['rows'])
    for name,rows in phases.items():
        result['phaseMetrics'][name]={'requests':len(rows),'completed':sum(bool(r.get('transportComplete')) for r in rows),
            'answerOutcomes':answer_outcomes(rows),
            'providerAttempts':sum(r.get('providerAttempts',0) for r in rows),'transportDuration':latency(rows),
            'failureCodes':dict(Counter((r.get('response') or {}).get('errorCode') or (r.get('response') or {}).get('code') or r.get('errorType','NO_COMPLETE_RESPONSE') for r in rows if not r.get('transportComplete')))}
    result['questionFailures']=[{'id':r['id'],'phase':r['phase'],'httpStatus':r.get('httpStatus'),'events':r.get('events'),
        'errorType':r.get('errorType')} for r in result['rows'] if r['kind']=='rag' and not r.get('transportComplete')]
    events=[];fallbacks=[]
    for path in paths:
        for file,dest in [('all-http-timing-window.json',events),('advice-fallback-events.json',fallbacks)]:
            p=path.parent/file
            if p.exists():dest.extend(json.loads(p.read_text(encoding='utf-8')))
    result['adviceFallbackReasons']=dict(Counter(code for row in fallbacks for code in row['reasons'].strip('[]').split(', ') if code))
    result['httpByPhase']={}
    for name,rows in phases.items():
        try:result['httpByPhase'][name]=http_window_metrics(events,rows)
        except (AssertionError,ValueError) as error:
            result['httpByPhase'][name]={'reconciliationError':str(error),'ledgerAttempts':sum(r['providerAttempts'] for r in rows)}
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--results',nargs='+',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    out=args.output.resolve();assert out.is_relative_to(ROOT/'logs');out.mkdir(parents=True,exist_ok=True)
    result=analyze([p.resolve() for p in args.results])
    (out/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'rag-for-offline-review.json').write_text(json.dumps({'cases':[r for r in result['rows'] if r['kind']=='rag' and r.get('response')]},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('status','counts','phaseMetrics','accountsCleanedUp','ledgerReconciles','adviceFallbackReasons')},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
