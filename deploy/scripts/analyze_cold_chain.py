"""Describe cold RAG stages without double-counting retrieval and its child HTTP calls."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median

ROOT=Path(__file__).resolve().parents[2]
def stats(values):
    return {'n':len(values),'medianSeconds':round(median(values),4),'minSeconds':round(min(values),4),'maxSeconds':round(max(values),4),'totalSeconds':round(sum(values),4)} if values else {'n':0}
def analyze(path):
    data=json.loads(path.read_text(encoding='utf-8'));details=[];attempts=[]
    for row in data['cases']:
        phases=defaultdict(float);http=defaultdict(float);phase_counts=defaultdict(int);queue=0
        for event in row.get('timingEvents',[]):
            if event['kind']=='phase':phases[event['phase']]+=event['elapsedMicros']/1e6;phase_counts[event['phase']]+=1
            if event['kind']=='admission':queue+=event['elapsedMicros']/1e6
            if event['kind']=='http':http[event['operation']]+=event['elapsedMicros']/1e6;attempts.append(event)
        total=row['transportMs']/1000;answer=row.get('response') or {}
        details.append({'id':row['id'],'phase':row['phase'],'ordinal':row['ordinal'],'totalSeconds':total,'queueSeconds':queue,
            'stageSeconds':dict(phases),'httpSeconds':dict(http),'stageCounts':dict(phase_counts),
            'otherSeconds':round(total-queue-sum(phases.values()),6),
            'calls':row['providerAttempts'],'status':answer.get('answerStatus'),'failureCode':answer.get('failureCode'),
            'summary':answer.get('summary'),'suggestions':answer.get('suggestions',[]),'referenceIds':[r['id'] for r in answer.get('references',[])]})
    metrics={'endToEnd':stats([r['totalSeconds'] for r in details]),'applicationAdmission':stats([r['queueSeconds'] for r in details]),
        'embeddingHttp':stats([r['httpSeconds'].get('embedding',0) for r in details]),
        'rerankerHttp':stats([r['httpSeconds'].get('reranker',0) for r in details]),
        'otherUnsplit':stats([r['otherSeconds'] for r in details])}
    for phase in ['retrieval','generation','review','repair']:
        metrics[phase]=stats([r['stageSeconds'][phase] for r in details if phase in r['stageSeconds']])
    return {'status':data['status'],'requests':len(details),'delivered':sum(r['status']=='COMPLETE' and not r['failureCode'] for r in details),
        'ledgerCalls':data['attemptsThisExecution'],'httpCalls':len(attempts),'ledgerReconciles':len(attempts)==data['attemptsThisExecution'],
        'httpFailures':sum(e['outcome']!='success' for e in attempts),'metrics':metrics,'rows':details,
        'repairRequests':sum(r['stageCounts'].get('repair',0)>0 for r in details),
        'limitations':['Retrieval includes embedding/rerank and must not be added to these child durations.',
                      'Review sums all review rounds per request. Repair is reported separately.',
                      'Other contains uninstrumented gateway/context/persistence/delivery; not a measured DB duration.',
                      'Small serial sample, no reliable P95 or concurrency capacity inference.']}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--results',type=Path,required=True);args=parser.parse_args()
    path=args.results.resolve();assert path.is_relative_to(ROOT/'logs')
    result=analyze(path);(path.parent/'cold-chain-analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['status','requests','delivered','ledgerCalls','httpCalls','httpFailures','repairRequests','metrics']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
