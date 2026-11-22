"""Offline connection/first-content/full-review statistics for paired fixed probes."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
from statistics import median

ROOT=Path(__file__).resolve().parents[2]
def describe(values):
    values=[v for v in values if v is not None]
    return {'n':len(values),'minMs':min(values),'medianMs':median(values),'maxMs':max(values)} if values else {'n':0}
def analyze(path):
    data=json.loads(path.read_text(encoding='utf-8'));groups=defaultdict(list)
    for row in data['rows']:groups[(row['id'],row['stream'])].append(row)
    result={'status':data['status'],'calls':len(data['rows']),'authorizationCumulative':data['authorizationCumulative'],
            'groups':{},'pairedInputIdentical':{},'observations':[]}
    for (case,stream),rows in groups.items():
        metrics={name:describe([r.get(name) for r in rows]) for name in ['connectMs','requestWriteMs','headersWaitMs','firstContentAfterSendMs','completionAfterFirstContentMs','totalMs']}
        metrics.update(calls=len(rows),complete=sum(r['complete'] for r in rows),
                       inputTokens=[r.get('usage',{}).get('prompt_tokens') for r in rows],
                       outputTokens=[r.get('usage',{}).get('completion_tokens') for r in rows],
                       uniqueOutputHashes=len({hashlib.sha256(r.get('answer','').encode()).hexdigest() for r in rows if r['complete']}))
        result['groups'][case+('_stream' if stream else '_json')]=metrics
    for case in {r['id'] for r in data['rows']}:
        rows=[r for r in data['rows'] if r['id']==case]
        result['pairedInputIdentical'][case]=len({r['pairedInputSha256'] for r in rows})==1
        for repetition in range(1,4):
            pair=[r for r in rows if r['variant'].startswith(f'pair{repetition}_')]
            if len(pair)==2:
                stream=next(r for r in pair if r['stream']);plain=next(r for r in pair if not r['stream'])
                result['observations'].append({'id':case,'pair':repetition,'streamMinusJsonMs':stream['totalMs']-plain['totalMs'],
                    'sameOutputBytes':stream.get('answer')==plain.get('answer'),'bothComplete':stream['complete'] and plain['complete']})
    result['limits']=['Fresh direct HTTPS connections, not application pool performance.',
                      'Connect includes DNS/TCP/TLS combined, not separately measured.',
                      'FirstContent is first non-whitespace content delta block, not exact GPU token time.',
                      'Non-stream first byte is not first model token; n=3 per mode does not establish P95 or causal speedup.']
    return result
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--results',type=Path,required=True);args=parser.parse_args()
    path=args.results.resolve();assert path.is_relative_to(ROOT/'logs')
    result=analyze(path);(path.parent/'pair-analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
