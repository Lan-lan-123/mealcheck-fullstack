"""Compare immutable predictions against two label versions and summarize offline scoring changes."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=root / 'loadtests/results/full100-20261016/results.json')
    parser.add_argument('--replay', type=Path, default=root / 'loadtests/results/round1-fixes-20261016/offline-replay.json')
    parser.add_argument('--audited', type=Path, default=root / 'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json')
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
    replay = json.loads(args.replay.read_text(encoding='utf-8'))
    if replay['baselineSha256'] != digest(args.baseline):
        raise ValueError('Baseline changed after replay')
    base_dataset = root / 'tools/datasets/synthetic-meals-100/dataset.json'
    spec = importlib.util.spec_from_file_location('vision', root / 'tools/vision_evaluation.py')
    vision = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vision)
    original, original_fingerprint = vision.load_dataset(base_dataset, allow_synthetic=True)
    revised, revised_fingerprint = vision.load_dataset(args.audited, allow_synthetic=True)
    predictions = {r['id']: {'recognition': r['recognition']} for r in baseline['cases']}
    metrics, scoring = {}, {}
    for name, dataset in [('original', original), ('assistantAudited', revised)]:
        rows = [vision.score(case, predictions[case['id']]) for case in dataset['cases']]
        metrics[name] = {'version': dataset['version'], 'metrics': vision.summarize(rows), 'cases': rows}
    food_rows = [r for r in replay['cases'] if r['status'] == 'REPLAYED']
    changed_risks = [{'id': r['id'], 'before': r['before']['riskTags'], 'after': r['after']['riskTags']}
                     for r in food_rows if r['before']['riskTags'] != r['after']['riskTags']]
    changed_summaries = [r['id'] for r in food_rows if r['before']['summary'] != r['after']['summary']]
    prompt_checks = []
    for row in food_rows:
        messages = row['advicePrompt']['messages']
        prompt_checks.append([m['role'] for m in messages] == ['system', 'user']
                             and '统一称本餐' in messages[0]['content']
                             and '不能把 protein 分类直接称为肉' in messages[0]['content']
                             and '不是重量或营养摄入测量' in messages[1]['content'])
    changes = json.loads(args.audited.with_name('label-changes-v2.json').read_text(encoding='utf-8'))
    renames = []
    for case in changes['changes']:
        for before, after in zip(case['before'], case['after']):
            if before['name'] != after['name']:
                renames.append({'id': case['id'], 'before': before['name'], 'after': after['name']})
    checks = dict(replay['checks'])
    checks['all80PromptsContainSystemFactBoundaries'] = len(prompt_checks) == 80 and all(prompt_checks)
    checks['originalLabelsPreserved'] = digest(base_dataset) == revised['baseDatasetSha256']
    checks['all100ImagesAndLabelsAligned'] = len(revised['cases']) == 100 and {r['id'] for r in revised['cases']} == set(predictions)
    checks['syntheticNonIndependentStatusPreserved'] = revised['datasetKind'] == 'synthetic' and revised['independentlyAnnotated'] is False
    checks['recordedNumericScoresReplayedWithoutUnexpectedChanges'] = replay['numericScoreChanges'] == 0
    report = {
        'scope': 'Offline fixed scoring and prompt request capture; unchanged saved model predictions',
        'checks': checks, 'allChecksPassed': all(checks.values()),
        'realModelCalls': 0, 'newProviderTokens': 0, 'adviceOutputQualityRetested': False,
        'baselineSha256': digest(args.baseline), 'baseDatasetSha256': digest(base_dataset),
        'originalFingerprint': original_fingerprint, 'auditedFingerprint': revised_fingerprint,
        'modelQualityDirectlyComparableAcrossLabelVersions': False,
        'baselineScoringVersion': replay['baselineScoringVersion'], 'currentScoringVersion': replay['currentScoringVersion'],
        'foodReplays': replay['foodReplays'], 'nonfoodSkipped': replay['nonfoodSkipped'],
        'numericScoreChanges': replay['numericScoreChanges'], 'changedRiskCases': len(changed_risks),
        'changedSummaryCases': changed_summaries, 'changedRisks': changed_risks,
        'labelReviewedCases': changes['reviewedCases'], 'labelChangedCases': changes['changedCases'],
        'renamedFoodLabels': renames, 'labelMetrics': metrics,
    }
    output = args.replay.with_name('offline-summary.json')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    compact = {k: v for k, v in report.items() if k not in {'changedRisks', 'renamedFoodLabels', 'labelMetrics'}}
    compact['renamedLabels'] = len(renames)
    compact['originalNameRecall'] = metrics['original']['metrics']['recall']
    compact['auditedNameRecall'] = metrics['assistantAudited']['metrics']['recall']
    print(json.dumps(compact, ensure_ascii=False, indent=2))
    if not report['allChecksPassed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
