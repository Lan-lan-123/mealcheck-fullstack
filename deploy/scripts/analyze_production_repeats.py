"""Offline repeat-run statistics; source presence is not semantic correctness."""
import argparse
from collections import defaultdict,Counter
import json
from pathlib import Path
import re
from statistics import median

ROOT=Path(__file__).resolve().parents[2]
def stats(values):
    return {'n':len(values),'minSeconds':round(min(values),3),'medianSeconds':round(median(values),3),'maxSeconds':round(max(values),3)} if values else {'n':0}
def analyze(path):
    data=json.loads(path.read_text(encoding='utf-8'));groups=defaultdict(list);details=[]
    for row in data['cases']:
        answer=row.get('response') or {};timing=defaultdict(float)
        for event in row.get('timingEvents',[]):
            if event['kind']=='phase':timing[event['phase']]+=event['elapsedMicros']/1e6
        refs=answer.get('references') or []
        original={s['id']:s for c in row.get('retrievalCaches',[]) for s in c.get('snippets',[])}
        item={'ordinal':row['ordinal'],'requestId':row.get('requestId'),'id':row['id'],'phase':row['phase'],
              'transportSeconds':row.get('transportMs',0)/1000,'phases':dict(timing),
              'modelCalls':row.get('providerAttempts',0),'status':answer.get('answerStatus'),'failureCode':answer.get('failureCode'),
              'frozenEvidenceMatches':row.get('frozenEvidenceMatches',False),'referenceIds':[s['id'] for s in refs],
              'allReferencesExistInCapturedSources':bool(refs) and all(s['id'] in original for s in refs),
              'summary':answer.get('summary',''),'suggestions':answer.get('suggestions',[])}
        groups[row['id']].append(item);details.append(item)
    metrics={}
    for case,rows in groups.items():
        metrics[case]={'attempted':len(rows),'delivered':sum(bool(r['status']=='COMPLETE' and r['summary'] and not r['failureCode']) for r in rows),
                      'total':stats([r['transportSeconds'] for r in rows]),
                      **{p:stats([r['phases'][p] for r in rows if p in r['phases']]) for p in ['generation','review']}}
    observations=[];review_usage=[];shapes=[]
    files=sorted(path.parent.glob('runtime-*.log'))
    if files:
        for line in files[-1].read_text(encoding='utf-8').splitlines():
            if 'AI request shape.' in line:
                request=re.search(r'\[requestId=([^\]]+)\]',line)
                shapes.append({'requestId':request.group(1) if request else None,**dict(re.findall(r'(\w+)=([^,\s]+)',line.split('AI request shape.',1)[1]))})
            if 'AI response observation.' in line:observations.append(dict(re.findall(r'(\w+)=([^,\s]+)',line.split('AI response observation.',1)[1])))
            if 'Assistant review response.' in line:
                request=re.search(r'\[requestId=([^\]]+)\]',line)
                review_usage.append({'requestId':request.group(1) if request else None,**dict(re.findall(r'(\w+)=([^,\s]+)',line.split('Assistant review response.',1)[1]))})
    for case,rows in groups.items():
        request_ids={r['requestId'] for r in rows}
        hashes=[s['payloadSha256'] for s in shapes if s['requestId'] in request_ids and s.get('operation')=='diet-assistant']
        metrics[case]['generationRequestHashCount']=len(hashes)
        metrics[case]['generationRequestIdentical']=len(hashes)==len(rows) and len(set(hashes))==1
    return {'status':data['status'],'plannedRequests':data['plannedRagRequests'],'attempted':len(details),
            'delivered':sum(bool(r['status']=='COMPLETE' and r['summary'] and not r['failureCode']) for r in details),
            'failureCodes':dict(Counter(r['failureCode'] for r in details if r['failureCode'])),
            'frozenEvidenceMatched':sum(r['frozenEvidenceMatches'] for r in details),
            'modelCalls':data['attemptsThisExecution'],'callLedgerReconciles':sum(r['modelCalls'] for r in details)==data['attemptsThisExecution'],
            'accountsCleanedUp':data['accountsCleanedUp'],'perQuestion':metrics,'rows':details,
            'httpObservations':observations,'reviewUsage':review_usage,'requestShapes':shapes,
            'limitation':'Three repeats per question are descriptive; no reliable tail quantile/SLO claim. Reference presence is not semantic accuracy; inspect each answer against its cited passages.'}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--results',type=Path,required=True);args=parser.parse_args()
    path=args.results.resolve();assert path.is_relative_to(ROOT/'logs')
    result=analyze(path);(path.parent/'repeat-analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['status','attempted','delivered','modelCalls','frozenEvidenceMatched','perQuestion']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
