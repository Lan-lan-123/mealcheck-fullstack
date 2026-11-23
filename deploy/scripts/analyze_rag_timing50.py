"""Describe measured RAG phase times and screen deadline candidates; never changes runtime settings."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
import math
from pathlib import Path
import statistics


def nearest_rank(values, probability):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * probability) - 1)]


def distribution(values):
    clean = [float(v) for v in values if v is not None and math.isfinite(v)]
    if not clean:
        return {'n': 0, 'meanMs': None, 'medianMs': None, 'p90Ms': None, 'p95Ms': None, 'maxMs': None}
    return {'n': len(clean), 'meanMs': round(statistics.mean(clean), 3), 'medianMs': round(statistics.median(clean), 3),
            'p90Ms': round(nearest_rank(clean, .9), 3), 'p95Ms': round(nearest_rank(clean, .95), 3),
            'maxMs': round(max(clean), 3)}


def clock(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def describe_request(row):
    events = sorted(row.get('timingEvents', []), key=lambda e: clock(e['loggedAt']))
    phases = [e for e in events if e['kind'] == 'phase']
    http = [e for e in events if e['kind'] == 'http']
    admissions = [e for e in events if e['kind'] == 'admission']
    tasks = [e for e in events if e['kind'] == 'task']
    budgets = [e for e in events if e['kind'] == 'budget']
    reviews = [e for e in phases if e.get('phase') == 'review']
    response = row.get('response') or {}
    calls = Counter(e.get('operation') for e in http)
    early = row['answerability'] == 'OUT_OF_SCOPE'
    delivered = bool(row.get('transportComplete')) and bool(response.get('references')) and any(e.get('outcome') == 'success' for e in reviews)
    values = {}
    for phase in ('retrieval', 'generation', 'review', 'repair'):
        selected = [e for e in phases if e.get('phase') == phase]
        values[phase + 'TotalMs'] = sum(e['elapsedMicros'] / 1000 for e in selected) if selected else None
        values[phase + 'Calls'] = len(selected)
    for operation, name in [('embedding', 'embeddingHttp'), ('reranker', 'rerankHttp')]:
        selected = [e for e in http if e.get('operation') == operation]
        values[name + 'TotalMs'] = sum(e['elapsedMicros'] / 1000 for e in selected) if selected else None
        values[name + 'Attempts'] = len(selected)
    tail = None
    if reviews and tasks:
        first = min(reviews, key=lambda e: clock(e['loggedAt']))
        end = max(tasks, key=lambda e: clock(e['loggedAt']))
        tail = (clock(end['loggedAt']) - clock(first['loggedAt'])).total_seconds() * 1000 + first['elapsedMicros'] / 1000
        if tail < 0:
            tail = None
    caches = [e.get('cache', 'unknown') for e in phases if e.get('phase') == 'retrieval']
    censored = any(e.get('outcome') == 'failure' and (e.get('budgetAtEndMs') == 0 or e.get('phaseBudgetAtEndMs') == 0) for e in phases)
    censored |= any(e.get('phase') == 'retrieval' and e.get('outcome') == 'failure' and e.get('elapsedMicros', 0) >= 14_900_000 for e in phases)
    result = {'ordinal': row['ordinal'], 'id': row['id'], 'phase': row['phase'], 'answerability': row['answerability'],
              'endToEndMs': row.get('durationMs'), 'admissionQueueMs': admissions[0]['elapsedMicros'] / 1000 if admissions else None,
              'serverExecutionMs': tasks[-1]['executionMicros'] / 1000 if tasks and tasks[-1]['executionMicros'] >= 0 else None,
              'cacheStates': caches, 'primaryCache': caches[0] if caches else ('not_called' if early else 'missing'),
              'withEvidenceDelivery': delivered, 'expectedEarlyRefusal': early and row.get('transportComplete', False) and not http and not response.get('references'),
              'transportComplete': row.get('transportComplete', False), 'httpAttemptsLogged': len(http),
              'httpFailuresLogged': sum(e.get('outcome') != 'success' for e in http), 'providerAttempts': row.get('providerAttempts'),
              'callsByOperation': dict(calls), 'possibleDeadlineCensoring': bool(censored),
              'firstReviewMs': reviews[0]['elapsedMicros'] / 1000 if reviews else None, 'postReviewStartToTaskEndMs': tail,
              'preFirstReviewMs': budgets[0]['totalBudgetAtStartMs'] - reviews[0]['budgetAtStartMs'] if budgets and reviews else None,
              **values}
    result['telemetryComplete'] = bool(tasks and admissions) and (early or bool(budgets and phases))
    return result


def analyze(payload):
    requests = [describe_request(row) for row in sorted(payload['cases'], key=lambda r: r['ordinal'])]
    knowledge = [r for r in requests if r['answerability'] != 'OUT_OF_SCOPE']
    success = [r for r in knowledge if r['withEvidenceDelivery']]
    fields = ['endToEndMs', 'admissionQueueMs', 'serverExecutionMs', 'retrievalTotalMs', 'embeddingHttpTotalMs',
              'rerankHttpTotalMs', 'generationTotalMs', 'reviewTotalMs', 'repairTotalMs',
              'firstReviewMs', 'postReviewStartToTaskEndMs']
    groups = defaultdict(list)
    for r in knowledge:
        cache_path = '+'.join(r['cacheStates']) if r['cacheStates'] else r['primaryCache']
        groups[r['phase'] + '/' + cache_path].append(r)
    group_stats = {name: {'requests': len(rows), 'withEvidenceDelivery': sum(r['withEvidenceDelivery'] for r in rows),
                         'metrics': {field: distribution([r[field] for r in rows]) for field in fields}}
                   for name, rows in groups.items()}
    call_groups = defaultdict(list)
    for row in payload['cases']:
        for event in row.get('timingEvents', []):
            if event['kind'] == 'http':
                call_groups[event['operation'] + '/' + event['outcome']].append(event['elapsedMicros'] / 1000)
    first_review = distribution([r['firstReviewMs'] for r in success])
    complete_tail = distribution([r['postReviewStartToTaskEndMs'] for r in success])
    reserve_groups = {}
    for name, rows in groups.items():
        valid = [r for r in rows if r['withEvidenceDelivery']]
        reserve_groups[name] = {'firstReview': distribution([r['firstReviewMs'] for r in valid]),
                                'fullTail': distribution([r['postReviewStartToTaskEndMs'] for r in valid])}
    reserve = lambda stat: math.ceil((stat['p95Ms'] * 1.2 + 2000) / 1000) * 1000 if stat['n'] else None
    first_reserve = max((reserve(g['firstReview']) for g in reserve_groups.values() if g['firstReview']['n']), default=None)
    tail_reserve = max((reserve(g['fullTail']) for g in reserve_groups.values() if g['fullTail']['n']), default=None)
    candidates = []
    for total in (45_000, 60_000, 75_000, 90_000):
        covered = [r for r in success if r['endToEndMs'] <= total]
        reserve_covered = [r for r in covered if tail_reserve is not None and r['preFirstReviewMs'] is not None
                           and r['preFirstReviewMs'] + tail_reserve <= total]
        candidates.append({'assistantBudgetMs': total, 'observedSuccessfulDenominator': len(success),
                           'observedCompletionsWithinBudget': len(covered), 'coveragePct': round(100 * len(covered) / len(success), 2) if success else None,
                           'withConservativeTailReserve': len(reserve_covered),
                           'reserveCoveragePct': round(100 * len(reserve_covered) / len(success), 2) if success else None})
    all_complete = payload.get('completedUniqueQuestions') == 50 and len(requests) == 65 and all(r['telemetryComplete'] for r in requests)
    no_censoring = not any(r['possibleDeadlineCensoring'] for r in knowledge)
    eligible = [c for c in candidates if c['reserveCoveragePct'] == 100]
    selected = eligible[0]['assistantBudgetMs'] if eligible and all_complete and no_censoring else 90_000
    retrieval_stats = distribution([r['retrievalTotalMs'] for r in success])
    single_retrieval = [e['elapsedMicros'] / 1000 for row in payload['cases'] for e in row.get('timingEvents', [])
                        if e['kind'] == 'phase' and e.get('phase') == 'retrieval' and e.get('outcome') == 'success']
    retrieval_calls = distribution(single_retrieval)
    by_phase = defaultdict(dict)
    for r in knowledge:
        by_phase[r['phase']][r['id']] = r
    pairs = []
    for key, parallel in by_phase.get('matched_cold_concurrent', {}).items():
        baseline = by_phase.get('baseline_serial', {}).get(key)
        if baseline:
            pairs.append({'id': key, 'serialMs': baseline['endToEndMs'], 'concurrentMs': parallel['endToEndMs'],
                          'serialCache': baseline['primaryCache'], 'concurrentCache': parallel['primaryCache'],
                          'comparableCold': bool(baseline['cacheStates']) and bool(parallel['cacheStates'])
                              and all(c == 'miss' for c in baseline['cacheStates'] + parallel['cacheStates']),
                          'differenceMs': parallel['endToEndMs'] - baseline['endToEndMs'] if baseline['endToEndMs'] is not None and parallel['endToEndMs'] is not None else None})
    issues = []
    if not all_complete:
        issues.append('Incomplete planned sample or missing telemetry: do not narrow deadlines from this batch.')
    if not no_censoring:
        issues.append('Some operations hit current deadlines; their untruncated completion times are unknown.')
    if len(success) != len(knowledge):
        issues.append('Candidate coverage is conditional on observed evidence delivery; failed/unverified requests remain in the main denominator.')
    measured_knowledge = [r for r in knowledge if r['endToEndMs'] is not None]
    contributions = {name: sum(r[field] or 0 for r in measured_knowledge) for name, field in
                     [('admissionQueue', 'admissionQueueMs'), ('retrieval', 'retrievalTotalMs'),
                      ('generation', 'generationTotalMs'), ('review', 'reviewTotalMs'), ('repair', 'repairTotalMs')]}
    measured_end_to_end = sum(r['endToEndMs'] for r in knowledge if r['endToEndMs'] is not None)
    residual = measured_end_to_end - sum(contributions.values())
    if residual < -20 * len(measured_knowledge):
        issues.append('Phase-envelope totals exceed end-to-end time: check late cancellation logging or overlap before interpreting phase shares.')
    contributions = {k: {'totalMs': round(v, 3), 'shareOfMeasuredRagEndToEndPct': round(100*v/measured_end_to_end, 2) if measured_end_to_end else None}
                     for k, v in contributions.items()}
    return {'method': {'timeUnit': 'ms', 'median': 'statistics.median', 'p90P95': 'nearest-rank ceil(n*p)',
                       'quantileScope': 'descriptive sample statistics; no production P99 or SLO guarantee',
                       'grain': 'request totals and HTTP attempts are separate; retrieval/model envelopes overlap HTTP durations and must not be added',
                       'reserveFormula': 'max across observed phase/cache cohorts of ceil((successful-tail P95 * 1.20 + 2000ms)/1000ms)*1000ms; headroom is a proposed policy, not an estimated confidence bound',
                       'sampling': '50 known/assistant-annotated questions, with repeated cases; repeats are not additional independent questions and the observed cache mix is not production traffic weighting',
                       'counterfactualLimit': 'shorter-budget coverage replays observed durations; it is not live validation of altered cancellation/retry behavior'},
            'counts': {'plannedUnique': 50, 'observedUnique': len({r['id'] for r in requests}), 'businessRequests': len(requests),
                       'knowledgeRequests': len(knowledge), 'withEvidenceDelivery': len(success),
                       'expectedEarlyRefusals': sum(r['expectedEarlyRefusal'] for r in requests),
                       'transportComplete': sum(r['transportComplete'] for r in requests),
                       'deadlineCensored': sum(r['possibleDeadlineCensoring'] for r in requests),
                       'missingTelemetry': sum(not r['telemetryComplete'] for r in requests),
                       'providerAttempts': payload.get('totalProviderAttempts'),
                       'loggedHttpAttempts': sum(r['httpAttemptsLogged'] for r in requests)},
            'requestMetricsAllKnowledge': {field: distribution([r[field] for r in knowledge]) for field in fields},
            'groups': group_stats, 'httpAttempts': {name: distribution(values) for name, values in call_groups.items()},
            'phaseContributions': contributions,
            'phaseReconciliation': {'measuredRequestCount': len(measured_knowledge), 'endToEndTotalMs': measured_end_to_end,
                                    'unallocatedOverheadMs': round(residual, 3)},
            'reviewReserve': {'successfulRequestDenominator': len(success), 'firstReview': first_review,
                              'tailAfterFirstReviewStart': complete_tail, 'firstReviewReserveMs': first_reserve,
                              'fullTailReserveMs': tail_reserve, 'requestsWithRepair': sum(r['repairCalls'] > 0 for r in knowledge)},
            'reviewReserveByCohort': reserve_groups,
            'candidates': candidates, 'provisionalRecommendation': {'assistantBudgetMs': selected, 'singleToolMs': 15_000,
                    'cumulativeToolsMs': 20_000, 'reviewReserveMs': tail_reserve,
                    'status': 'CANDIDATE_FOR_SEPARATE_LIVE_CONFIRMATION' if all_complete and no_censoring else 'KEEP_CURRENT_LIMITS_PENDING_MORE_EVIDENCE',
                    'singleRetrievalObserved': retrieval_calls, 'cumulativeRetrievalObserved': retrieval_stats,
                    'settingsApplied': False, 'reason': 'Keep retrieval safety bounds until tails and supplemental reads are adequately sampled; assistant candidate must cover all measured successful requests with the proposed reserve.'},
            'pairedColdComparison': pairs, 'issues': issues, 'requests': requests}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    payload = json.loads(args.results.read_text(encoding='utf-8'))
    output = args.output or args.results.parent / 'analysis'
    root = Path(__file__).resolve().parents[2]
    assert output.resolve().is_relative_to(root / 'logs')
    output.mkdir(exist_ok=True)
    result = analyze(payload)
    (output / 'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'counts': result['counts'], 'reviewReserve': result['reviewReserve'],
                      'provisionalRecommendation': result['provisionalRecommendation']}, ensure_ascii=True))


if __name__ == '__main__':
    main()
