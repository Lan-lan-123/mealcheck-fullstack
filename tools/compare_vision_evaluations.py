"""Compare immutable recognition reports only when their labeled dataset is identical."""
import argparse
import json
from pathlib import Path


def compare(base, candidate):
    comparable = (base['datasetFingerprint'] == candidate['datasetFingerprint']
                  and base.get('datasetKind', 'real') == candidate.get('datasetKind', 'real')
                  and base.get('independentlyAnnotated', True) == candidate.get('independentlyAnnotated', True))
    fields = ['precision','recall','categoryAccuracy','foodImageAccuracy','p95DurationMs','estimatedCost']
    delta = {key: candidate['metrics'][key] - base['metrics'][key] if comparable and candidate['metrics'][key] is not None and base['metrics'][key] is not None else None for key in fields}
    left = {case['id']: case for case in base['cases']}
    differences = []
    for case in candidate['cases']:
        prior = left.get(case['id'])
        if prior is None or any(case.get(key) != prior.get(key) for key in ['matched','categoryCorrect','missing','extra','error']):
            differences.append({'id': case['id'], 'before': prior, 'after': case})
    return {'baseRunId': base['runId'], 'candidateRunId': candidate['runId'], 'comparable': comparable, 'metricDelta': delta, 'changedCases': differences}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base', type=Path); parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = compare(json.loads(args.base.read_text(encoding='utf-8')), json.loads(args.candidate.read_text(encoding='utf-8')))
    with args.output.open('x', encoding='utf-8') as stream: json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'comparable': report['comparable'], 'changedCases': len(report['changedCases'])}))
    if not report['comparable']: raise SystemExit(2)


if __name__ == '__main__': main()
