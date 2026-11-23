"""Prepare local evidence packets from captured answers; never calls a model or modifies raw evidence.

Ranked excerpts are reading aids, not a semantic-support score or an automatic pass.
"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def prepare(results):
    payload = json.loads(results.read_text(encoding='utf-8'))
    rows = []
    for row in sorted(payload['cases'], key=lambda r: r['ordinal']):
        response = row.get('response') or {}
        refs = {r['id']: r for r in response.get('references', [])}
        full = {s['id']: s for c in row.get('retrievalCaches', []) for s in c.get('snippets', [])}
        sources = [{**r, 'content': full.get(i, r).get('content', ''),
                    'capturedFinalContent': r.get('content', '')} for i, r in refs.items()]
        text = response.get('summary', '')
        units = [u.strip() for u in re.split(r'(?<=[。！？；])', text) if u.strip()]
        units += response.get('suggestions', [])
        paragraphs = [(s['id'], q.strip()) for s in sources for q in re.split(r'(?<=[。！？；])', s['content'])
                      if q.strip() and not q.lstrip().startswith('参考：')]
        grams = lambda s: {s[i:i+2] for i in range(len(s)-1) if re.search(r'[\u4e00-\u9fff]', s[i:i+2])}
        df = Counter(g for _, q in paragraphs for g in grams(q))
        selected = []
        for unit in units:
            ranked = sorted(paragraphs, key=lambda pair: sum(math.log(1+len(paragraphs)/(df[g]+1))
                            for g in grams(unit) & grams(pair[1])), reverse=True)
            selected.append({'text': unit, 'candidateEvidence': [{'sourceId': i, 'quote': q} for i, q in ranked[:2]]})
        rows.append({'ordinal': row['ordinal'], 'id': row['id'], 'phase': row['phase'],
                     'question': row['question'], 'expectedAnswerability': row['answerability'],
                     'finalAnswer': response.get('answer', ''), 'summary': text, 'suggestions': response.get('suggestions', []),
                     'sources': sources, 'readingAid': selected,
                     'toolCalls': response.get('toolCalls', []),
                     'internalInformationStatusAvailable': 'informationStatus' in response,
                     'scope': 'Captured final output and cited sources only; no internal review verdict or claim mapping is present.'})
    return {'source': str(results.relative_to(ROOT)), 'sourceSha256': hashlib.sha256(results.read_bytes()).hexdigest(),
            'method': 'Exact captured source text plus lexical reading aids; semantic verdicts must be recorded separately.', 'rows': rows}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--show', nargs=2, type=int)
    args = parser.parse_args()
    data = prepare(args.results.resolve())
    if args.show:
        for r in data['rows']:
            if args.show[0] <= r['ordinal'] <= args.show[1]:
                print(f"\n#{r['ordinal']} {r['id']} {r['expectedAnswerability']} Q:{r['question']}")
                for item in r['readingAid']:
                    print('A:', item['text'])
                    for e in item['candidateEvidence'][:1]:
                        print(f"E[{e['sourceId']}]:", e['quote'])
    else:
        out = args.output or ROOT / 'logs/backend' / ('rag-answer-audit-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
        assert out.resolve().is_relative_to(ROOT / 'logs')
        out.mkdir(parents=True, exist_ok=False)
        (out / 'evidence-packet.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'packet': str(out / 'evidence-packet.json'), 'requests': len(data['rows']),
                          'sourceSha256': data['sourceSha256']}))

if __name__ == '__main__':
    main()
