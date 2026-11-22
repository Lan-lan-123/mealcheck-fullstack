"""Offline reporting for ordered image runs stopped by the provider; no model calls."""
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
    baseline = {r['id']: r for r in read(root / 'loadtests/results/full100-20261016/results.json')['cases']}
    labels = {r['id']: r for r in read(root / 'tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json')['cases']}
    old_reviews = read(root / 'loadtests/results/full100-20261016/advice-review.json')
    review_file = directory / 'advice-review.json'
    reviews = read(review_file) if review_file.exists() else {}
    spec = importlib.util.spec_from_file_location('vision_evaluation', root / 'tools/vision_evaluation.py')
    evaluator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(evaluator)
    calls = run['calls']
    known = [c for c in calls if c.get('usageKnown')]
    successes = [c for c in calls if c.get('transportSuccess')]
    recognized = [r for r in run['cases'] if 'recognition' in r]
    complete = [r for r in run['cases'] if r['status'] == 'COMPLETED']
    manifest = read(directory / 'source-manifest.json')
    changed = [f['Path'] for f in manifest if hashlib.sha256(Path(f['Path']).read_bytes()).hexdigest() != f['SHA256'].lower()]
    breakdowns = [r['evaluation']['scoreBreakdown'] for r in run['cases'] if 'evaluation' in r]
    checks = {
        'finished': run['state'] == 'FINISHED',
        'tokenLedgerReconciles': sum(c.get('inputTokens', 0) + c.get('outputTokens', 0) for c in known) == run['knownTotalTokens'],
        'withinLocalCeiling': run['knownTotalTokens'] + run['unknownUsageAllowance'] <= run['tokenBudget'],
        'noInFlightReservation': run['inFlightReservedTokens'] == 0,
        'noRetriesOrThinking': run['maxAttempts'] == 1 and all(not c['enableThinking'] and c['maxCompletionTokens'] == 2048 for c in calls),
        'realAdviceRequiredForFoodCompletion': all(not r['recognition']['foodImage'] or r.get('adviceFromRealAi') for r in complete),
        'scoreDetailsReconcile': all(sum(line['points'] for line in b['items']) == b['total'] for b in breakdowns)
            and all(r['evaluation']['scoreBreakdown']['total'] == r['evaluation']['score'] for r in run['cases'] if 'evaluation' in r),
        'foodAttributesPresent': all(isinstance(f.get('attributes'), dict) and all(k in f['attributes']
            and (f['attributes'][k] is None or type(f['attributes'][k]) is bool) for k in ('fried', 'highOil'))
            for r in recognized for f in r['recognition'].get('foods', [])),
        'sameImageHashes': all(r['expected']['sha256'] == baseline[r['id']]['expected']['sha256'] == labels[r['id']]['sha256'] for r in recognized),
        'providerRejectionIsLastCall': not run.get('providerStopReason') or bool(calls and calls[-1].get('httpStatus', 0) >= 400),
    }
    rows = []
    for row in recognized:
        case_id = row['id']
        metric = evaluator.score(labels[case_id], {'recognition': row['recognition']})
        previous_metric = evaluator.score(labels[case_id], {'recognition': baseline[case_id]['recognition']})
        rows.append({'id': case_id, 'status': row['status'], 'beforeNameMatches': previous_metric['matched'],
                     'nameMatches': metric['matched'], 'expectedNames': metric['expected'],
                     'beforeScore': baseline[case_id].get('evaluation', {}).get('score'),
                     'score': row.get('evaluation', {}).get('score'), 'foods': row['recognition'].get('foods', []),
                     'riskTags': row.get('evaluation', {}).get('riskTags', []),
                     'oldAdviceIssues': old_reviews.get(case_id, {}).get('issues', []),
                     'realAiAdviceCompleted': bool(row.get('adviceFromRealAi')), 'adviceReview': reviews.get(case_id),
                     'advice': row.get('advice', '')})
    summary = {
        'scope': run['scope'], 'datasetKind': 'synthetic', 'independentlyAnnotated': False,
        'model': run['model'], 'scoringVersion': run['scoringVersion'],
        'providerStopReason': run.get('providerStopReason', ''), 'budgetStopped': run['budgetStopped'],
        'statusCounts': dict(Counter(r['status'] for r in run['cases'])), 'imagesRecognized': len(recognized),
        'foodDecisionMatches': sum(bool(r.get('foodDecisionMatchesLabel')) for r in recognized),
        'foodDecisionComparedImages': len(recognized),
        'foodDecisionMismatchCases': [r['id'] for r in recognized if not r.get('foodDecisionMatchesLabel')],
        'completedFoodImages': sum(bool(r['recognition']['foodImage']) for r in complete),
        'completedNonfoodImages': sum(not r['recognition']['foodImage'] for r in complete),
        'imagesCompleted': len(complete), 'realAiAdviceCompleted': sum(r['realAiAdviceCompleted'] for r in rows),
        'providerCalls': len(calls), 'successfulCalls': len(successes),
        'knownInputTokens': run['knownInputTokens'], 'knownOutputTokens': run['knownOutputTokens'],
        'knownTotalTokens': run['knownTotalTokens'], 'unknownUsageCalls': run['unknownUsageCalls'],
        'unknownUsageAllowance': run['unknownUsageAllowance'], 'tokenCeiling': run['tokenBudget'],
        'rejections': [{k: c.get(k) for k in ('caseId', 'operation', 'httpStatus', 'providerErrorCode', 'usageKnown')} for c in calls if c.get('httpStatus', 0) >= 400],
        'sourceFilesChangedSinceCompilation': changed,
        'adviceReviewed': len(reviews), 'adviceIssueCases': [case_id for case_id, review in reviews.items() if review.get('issues')],
        'oldAdviceIssueCasesRetested': [r['id'] for r in rows if r['oldAdviceIssues'] and r['realAiAdviceCompleted']],
        'checks': checks, 'cases': rows,
    }
    (directory / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 图片复测记录', '', f"模型：{run['model']}；停止原因：{summary['providerStopReason'] or '本轮完成或本地预算停止'}。",
             f"识别完成 {len(recognized)} 张，全链路完成 {len(complete)} 张；已知消耗 {run['knownTotalTokens']:,} token。",
             '未知用量请求保守预留单列，不等同于供应商实际扣费。合成图、助手复核，不是独立人工验收。', '',
             '| 图片 | 状态 | 同一审核标签下名称匹配（旧→新） | 旧分→新分 | 真实 AI 建议 |', '|---|---|---|---|---|']
    for row in rows:
        lines.append(f"| {row['id']} | {row['status']} | {row['beforeNameMatches']}→{row['nameMatches']}/{row['expectedNames']} | {row['beforeScore']}→{row['score']} | {row['realAiAdviceCompleted']} |")
    for row in rows:
        lines.extend(['', f"## {row['id']}", '', row['advice'] or '未完成建议阶段。', '', f"文本复核：{row['adviceReview']}"])
    (directory / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'cases'}, ensure_ascii=False, indent=2))
    if not all(checks.values()):
        raise SystemExit('Evidence checks failed; inspect summary.json')


if __name__ == '__main__':
    main()
