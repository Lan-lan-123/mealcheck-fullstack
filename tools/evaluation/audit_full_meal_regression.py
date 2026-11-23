"""Verify completed full-meal regression evidence without any network calls."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    directory = args.run_dir.resolve()
    root = Path(__file__).resolve().parents[2]
    result = json.loads((directory / 'results.json').read_text(encoding='utf-8'))
    summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
    calls, cases = result['calls'], result['cases']
    checks = {}
    checks['finished100UniqueCases'] = (result['state'] == 'FINISHED' and len(cases) == 100
                                        and len({r['id'] for r in cases}) == 100)
    checks['allCasesCompleted'] = all(r['status'] == 'COMPLETED' for r in cases)
    checks['allImageDecisionsMatchProposedLabels'] = all(r['foodDecisionMatchesLabel'] for r in cases)
    food = [r for r in cases if r['expected']['foodImage']]
    nonfood = [r for r in cases if not r['expected']['foodImage']]
    checks['eightyFoodScoringAndRealAiAdvice'] = (len(food) == 80 and all(
        r.get('adviceFromRealAi') and r.get('advice') and 0 <= r['evaluation']['score'] <= 100 for r in food))
    checks['twentyNonfoodWithoutScoringOrAdvice'] = (len(nonfood) == 20 and all(
        r['decision'] == 'NON_FOOD' and 'evaluation' not in r and 'advice' not in r for r in nonfood))
    expected_calls = Counter({(r['id'], 'vision-recognition'): 1 for r in cases})
    expected_calls.update({(r['id'], 'meal-advice'): 1 for r in food})
    actual_calls = Counter((c['caseId'], c['operation']) for c in calls)
    checks['exactly180ExpectedCallsNoRetry'] = len(calls) == 180 and actual_calls == expected_calls
    checks['allCallsSuccessfulKnownUsage'] = all(c.get('transportSuccess') and c.get('httpSuccess')
        and c.get('usageKnown') and c.get('httpStatus') == 200 for c in calls)
    checks['thinkingOffOutputCap2048NoTruncation'] = all(c.get('enableThinking') is False
        and c['maxCompletionTokens'] == 2048 and c['finishReason'] == 'stop'
        and c['outputTokens'] <= 2048 for c in calls)
    input_total = sum(c['inputTokens'] for c in calls)
    output_total = sum(c['outputTokens'] for c in calls)
    checks['usageLedgerReconciles'] = (input_total == result['knownInputTokens'] == summary['inputTokens']
        and output_total == result['knownOutputTokens'] == summary['outputTokens']
        and input_total + output_total == result['knownTotalTokens'] == summary['totalTokens']
        and all(c['providerUsage']['prompt_tokens'] == c['inputTokens']
                and c['providerUsage']['completion_tokens'] == c['outputTokens'] for c in calls))
    checks['withinBudgetNoOutstandingAllowances'] = (input_total + output_total <= result['tokenBudget']
        and result['unknownUsageCalls'] == 0 and result['unknownUsageAllowance'] == 0
        and result['inFlightReservedTokens'] == 0)
    manifest = json.loads((directory / 'source-manifest.json').read_text(encoding='utf-8-sig'))
    checks['allRecordedSourcesUnchanged'] = all(hashlib.sha256(Path(f['Path']).read_bytes()).hexdigest().lower()
                                             == f['SHA256'].lower() for f in manifest)
    checks['allImageHashesUnchanged'] = all(hashlib.sha256(
        (root / 'tools/datasets/synthetic-meals-100' / r['expected']['image']).read_bytes()).hexdigest()
        == r['expected']['sha256'] for r in cases)
    reviews = json.loads((directory / 'advice-review.json').read_text(encoding='utf-8'))
    checks['allEightyAdviceReviewsRecorded'] = set(reviews) == {r['id'] for r in food}
    secrets = []
    for line in (root / '.env').read_text(encoding='utf-8-sig').splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        value = value.strip().strip('\"\'')
        if len(value) >= 8 and any(word in key.upper() for word in ('KEY', 'TOKEN', 'PASSWORD', 'SECRET')):
            secrets.append(value)
    textual = [p for p in directory.iterdir() if p.suffix in {'.json', '.log', '.html', '.md'}]
    leaked = [p.name for p in textual if any(s in p.read_text(encoding='utf-8-sig', errors='replace') for s in secrets)]
    checks['noConfiguredSecretsInTextEvidence'] = not leaked
    audit = {'passed': all(checks.values()), 'checks': checks, 'providerCalls': len(calls),
             'inputTokens': input_total, 'outputTokens': output_total, 'totalTokens': input_total + output_total,
             'textFilesCheckedForConfiguredSecrets': len(textual), 'filesNeedingSecretRemoval': leaked,
             'sourceManifestEntries': len(manifest)}
    (directory / 'audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(audit, ensure_ascii=False, indent=2))
    if not audit['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
