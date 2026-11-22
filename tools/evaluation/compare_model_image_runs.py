"""Compare completed same-image component runs; no provider calls or automatic advice judging."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from statistics import mean, median


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))
    root = Path(__file__).resolve().parents[2]
    ref, candidate = read(args.reference / 'results.json'), read(args.candidate / 'results.json')
    labels = {r['id']: r for r in read(root / 'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json')['cases']}
    spec = importlib.util.spec_from_file_location('vision_evaluation', root / 'tools/vision_evaluation.py')
    evaluator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(evaluator)
    completed = lambda run: {r['id']: r for r in run['cases'] if r['status'] == 'COMPLETED'}
    left, right = completed(ref), completed(candidate)
    paired = [case_id for case_id in left if case_id in right]
    same = {
        'goal': ref['goal'] == candidate['goal'], 'scoringVersion': ref['scoringVersion'] == candidate['scoringVersion'],
        'thinkingMode': ref['thinkingMode'] == candidate['thinkingMode'],
        'knowledgeExcerpts': read(args.reference / 'evidence-snippets.json') == read(args.candidate / 'evidence-snippets.json'),
        'normalizedImages': all(left[i]['normalizedImage']['sha256'] == right[i]['normalizedImage']['sha256'] for i in paired),
    }
    manifests = [{Path(f['Path']).name: f['SHA256'].lower() for f in read(directory / 'source-manifest.json')} for directory in (args.reference, args.candidate)]
    for name in ('VisionFoodRecognitionService.java', 'AdviceGenerationService.java', 'MealScoringService.java', 'FoodClassification.java', 'ImageUploadValidationService.java'):
        same[name] = manifests[0].get(name) is not None and manifests[0].get(name) == manifests[1].get(name)
    rows = []
    for case_id in paired:
        a, b = left[case_id], right[case_id]
        am = evaluator.score(labels[case_id], {'recognition': a['recognition']})
        bm = evaluator.score(labels[case_id], {'recognition': b['recognition']})
        usage = lambda run: sum(c.get('inputTokens', 0) + c.get('outputTokens', 0) for c in run['calls'] if c['caseId'] == case_id and c.get('usageKnown'))
        rows.append({'id': case_id, 'expectedNames': am['expected'], 'referenceNameMatches': am['matched'], 'candidateNameMatches': bm['matched'],
                     'referenceFoods': a['recognition'].get('foods', []), 'candidateFoods': b['recognition'].get('foods', []),
                     'referenceScore': a.get('evaluation', {}).get('score'), 'candidateScore': b.get('evaluation', {}).get('score'),
                     'referenceTokens': usage(ref), 'candidateTokens': usage(candidate),
                     'referenceDurationMs': a['durationMs'], 'candidateDurationMs': b['durationMs']})
    def metrics(prefix):
        return {'matchedNames': sum(r[f'{prefix}NameMatches'] for r in rows), 'expectedNames': sum(r['expectedNames'] for r in rows),
                'tokens': sum(r[f'{prefix}Tokens'] for r in rows),
                'meanDurationMs': mean(r[f'{prefix}DurationMs'] for r in rows) if rows else None,
                'medianDurationMs': median(r[f'{prefix}DurationMs'] for r in rows) if rows else None}
    summary = {'referenceModel': ref['model'], 'candidateModel': candidate['model'], 'pairedCompletedImages': len(paired),
               'comparabilityChecks': same, 'comparableInputs': bool(paired) and all(same.values()),
               'reference': metrics('reference'), 'candidate': metrics('candidate'),
               'scoreDifferences': [r['id'] for r in rows if r['referenceScore'] != r['candidateScore']],
               'limits': 'Selected synthetic same-image comparison, not independent accuracy; exact aliases may miss synonyms. Duration reflects this run and network conditions. Advice needs separate reading, not an automatic quality pass.',
               'cases': rows}
    (args.candidate / 'paired-comparison.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'cases'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
