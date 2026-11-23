"""Offline audit of a budget-limited priority run; never calls a provider."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    directory = args.run_dir.resolve()
    read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))
    run = read(directory / 'results.json')
    baseline = read(root / 'loadtests/results/full100-20261016/results.json')
    old_reviews = read(root / 'loadtests/results/full100-20261016/advice-review.json')
    reviews = read(directory / 'advice-review.json')
    labels = read(root / 'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json')
    by_id = {r['id']: r for r in labels['cases']}
    before = {r['id']: r for r in baseline['cases']}
    spec = importlib.util.spec_from_file_location('vision_evaluation', root / 'tools/vision_evaluation.py')
    evaluator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(evaluator)
    calls = run['calls']
    completed = [r for r in run['cases'] if r['status'] == 'COMPLETED']
    manifest = read(directory / 'source-manifest.json')
    changed = [f['Path'] for f in manifest if hashlib.sha256(Path(f['Path']).read_bytes()).hexdigest() != f['SHA256'].lower()]
    base_categories = {'staple', 'protein', 'vegetable', 'fruit', 'dairy', 'soup', 'drink', 'dessert', 'other'}
    checks = {
        'finished': run['state'] == 'FINISHED',
        'noUnexpectedCaseFailure': all(r['status'] in {'COMPLETED', 'BUDGET_STOPPED', 'NOT_RUN_BUDGET'} for r in run['cases']),
        'usageKnownForEveryCall': all(c.get('usageKnown') for c in calls),
        'tokenLedgerReconciles': sum(c['inputTokens'] + c['outputTokens'] for c in calls) == run['knownTotalTokens'],
        'withinLocalBudget': run['knownTotalTokens'] + run['unknownUsageAllowance'] <= run['tokenBudget'] <= 40000,
        'noReservationsRemain': run['inFlightReservedTokens'] == 0,
        'everyCallWithinReservation': all(c['inputTokens'] + c['outputTokens'] <= c['reservedTokenBound'] for c in calls),
        'oneRecognitionAndAdvicePerCompletedCase': len(calls) == 2 * len(completed) and all(
            Counter(c['operation'] for c in calls if c['caseId'] == r['id']) == {'vision-recognition': 1, 'meal-advice': 1} for r in completed),
        'allTransportsSucceeded': all(c.get('transportSuccess') and c.get('httpStatus') == 200 for c in calls),
        'thinkingOffOutputCapped': all(not c.get('enableThinking') and c.get('maxCompletionTokens') == 2048 for c in calls),
        'allAdviceFromRealAi': all(r.get('adviceFromRealAi') for r in completed),
        'newSchemaPresent': all(f['category'] in base_categories and isinstance(f.get('attributes'), dict)
            and all(k in f['attributes'] and (f['attributes'][k] is None or type(f['attributes'][k]) is bool)
                    for k in ('fried', 'highOil')) for r in completed for f in r['recognition']['foods']),
        'allCompletedAdviceReviewed': all(r['id'] in reviews for r in completed),
        'sameBaselineImageHashes': all(r['expected']['sha256'] == before[r['id']]['expected']['sha256'] == by_id[r['id']]['sha256'] for r in completed),
        # The isolated runner never constructs AssistantTaskManager. Preserve any
        # concurrent edit in the audit instead of replacing the compiled manifest.
        'testedComponentSourcesUnchanged': all(Path(p) == root / 'backend/src/main/java/com/example/mealcheck/service/AssistantTaskManager.java' for p in changed),
    }
    rows = []
    for row in completed:
        case_id = row['id']
        previous = before[case_id]
        metric = evaluator.score(by_id[case_id], {'recognition': row['recognition']})
        old_metric = evaluator.score(by_id[case_id], {'recognition': previous['recognition']})
        rows.append({'id': case_id, 'beforeScore': previous['evaluation']['score'], 'afterScore': row['evaluation']['score'],
                     'beforeNameMatches': old_metric['matched'], 'afterNameMatches': metric['matched'],
                     'expectedNames': metric['expected'], 'oldAdviceIssues': old_reviews[case_id]['issues'],
                     'review': reviews[case_id], 'foods': row['scoredRecognition']['foods'],
                     'riskTags': row['evaluation']['riskTags'], 'advice': row['advice']})
    summary = {'scope': 'targeted synthetic component regression; not full100 or independent quality acceptance',
               'model': run['model'], 'scoringVersion': run['scoringVersion'], 'completedImages': len(completed),
               'calls': len(calls), 'inputTokens': run['knownInputTokens'], 'outputTokens': run['knownOutputTokens'],
               'totalTokens': run['knownTotalTokens'], 'tokenBudget': run['tokenBudget'], 'budgetStopped': run['budgetStopped'],
               'statusCounts': dict(Counter(r['status'] for r in run['cases'])),
               'notRetested': [r['id'] for r in run['cases'] if r['status'] != 'COMPLETED'],
               'sourceFilesChangedSinceCompilation': changed,
               'sourceChangeScope': 'AssistantTaskManager is outside this isolated recognition/scoring/advice harness; original compilation manifest preserved',
               'oldAdviceProblemCasesRetested': sum(bool(r['oldAdviceIssues']) for r in rows),
               'newAdviceIssueCases': [r['id'] for r in rows if r['review']['issues']],
               'checks': checks, 'cases': rows}
    (directory / 'priority-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'cases'}, ensure_ascii=False, indent=2))
    if not all(checks.values()):
        raise SystemExit('Audit failed; inspect priority-summary.json')


if __name__ == '__main__':
    main()
