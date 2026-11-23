import copy
import importlib.util
import math
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'deploy/scripts'))
from run_rag_timing50 import parse_timings, dispatch_with_reserve
from analyze_rag_timing50 import analyze, describe_request, distribution, nearest_rank


def fixture():
    base = '2026-10-25T12:00:'
    def event(kind, second, **values):
        return {'requestId': '00000000-0000-0000-0000-000000000001', 'kind': kind,
                'loggedAt': base + second + 'Z', **values}
    events = [event('admission', '00.010', elapsedMicros=10000),
              event('budget', '00.010', totalBudgetAtStartMs=89999),
              event('http', '00.300', operation='embedding', elapsedMicros=300000, outcome='success'),
              event('http', '00.700', operation='reranker', elapsedMicros=400000, outcome='success'),
              event('phase', '01.010', phase='retrieval', elapsedMicros=1000000, outcome='success', cache='miss'),
              event('http', '03.009', operation='diet-assistant', elapsedMicros=2000000, outcome='success'),
              event('phase', '03.010', phase='generation', elapsedMicros=2000000, outcome='success', budgetAtStartMs=88999, budgetAtEndMs=86999),
              event('http', '06.009', operation='diet-assistant-evidence-review', elapsedMicros=3000000, outcome='success'),
              event('phase', '06.010', phase='review', elapsedMicros=3000000, outcome='success', budgetAtStartMs=86999, budgetAtEndMs=83999),
              event('task', '06.020', elapsedMicros=6020000, executionMicros=6010000, state='SUCCEEDED')]
    return {'ordinal': 1, 'id': 'rag-008', 'phase': 'baseline_serial', 'answerability': 'ANSWERABLE',
            'durationMs': 6030, 'transportComplete': True, 'providerAttempts': 4,
            'response': {'references': [{'id': 7}]}, 'timingEvents': events}


class TimingStudyTest(unittest.TestCase):
    def test_scheduler_uses_remaining_eligible_user_instead_of_stopping_globally(self):
        accounts = [{'index': i, 'id': i} for i in range(3)]
        used, assigned = [85, 85, 77], []
        def execute(task, account):
            assigned.append((task, account['index']))
            used[account['id']] += 2
        self.assertTrue(dispatch_with_reserve([64, 65], accounts, 3, lambda i: used[i], execute, lambda: False))
        self.assertEqual(assigned, [(64, 2), (65, 2)])
        self.assertEqual(used, [85, 85, 81])

    def test_scheduler_stops_when_all_users_lack_reserve_and_honors_failure_stop(self):
        accounts = [{'index': i, 'id': i} for i in range(3)]
        calls = []
        self.assertFalse(dispatch_with_reserve([1], accounts, 3, lambda i: 85, lambda *a: calls.append(a), lambda: False))
        self.assertFalse(dispatch_with_reserve([1], accounts, 3, lambda i: 0, lambda *a: calls.append(a), lambda: True))
        self.assertEqual(calls, [])

    def test_parser_separates_requests_and_recovers_explicit_queue_id(self):
        own = '00000000-0000-0000-0000-000000000001'
        other = '00000000-0000-0000-0000-000000000002'
        raw = f'''2026-10-25T12:00:00Z [requestId=] Assistant admission timing. requestId={own}, elapsedMicros=12000, outcome=started
2026-10-25T12:00:01Z [requestId={own}] Assistant phase timing. phase=review, elapsedMicros=3500000, outcome=success, budgetAtStartMs=80000, budgetAtEndMs=76500
2026-10-25T12:00:01Z [requestId={other}] Assistant phase timing. phase=review, elapsedMicros=9999999, outcome=success'''
        events = parse_timings(raw, {own})
        self.assertEqual(len(events), 2)
        self.assertEqual(events[0]['kind'], 'admission')
        self.assertEqual(events[1]['elapsedMicros'], 3500000)

    def test_quantiles_obey_empirical_cdf_and_missing_is_not_zero(self):
        values = [21, 5, 18, 3, 9, 12, 11, 6, 7, 15]
        for p in (.5, .9, .95):
            q = nearest_rank(values, p)
            self.assertGreaterEqual(sum(v <= q for v in values), math.ceil(p * len(values)))
            self.assertLess(sum(v < q for v in values), math.ceil(p * len(values)))
        self.assertEqual(distribution([None])['n'], 0)
        self.assertIsNone(distribution([])['p95Ms'])
        self.assertEqual(distribution([0])['p95Ms'], 0)

    def test_nested_http_and_phase_durations_are_not_added_and_tail_is_reconstructed(self):
        result = describe_request(fixture())
        self.assertEqual(result['retrievalTotalMs'], 1000)
        self.assertEqual(result['embeddingHttpTotalMs'], 300)
        self.assertEqual(result['rerankHttpTotalMs'], 400)
        self.assertEqual(result['generationTotalMs'], 2000)
        self.assertAlmostEqual(result['postReviewStartToTaskEndMs'], 3010)
        self.assertEqual(result['preFirstReviewMs'], 3000)

    def test_failure_remains_in_main_denominator_and_censored_time_prevents_narrowing(self):
        good = fixture()
        bad = copy.deepcopy(good)
        bad.update(id='rag-036', ordinal=2, durationMs=90000, transportComplete=False, response={})
        bad['timingEvents'][-2].update(outcome='failure', budgetAtEndMs=0)
        result = analyze({'cases': [good, bad], 'completedUniqueQuestions': 2, 'totalProviderAttempts': 8})
        self.assertEqual(result['counts']['knowledgeRequests'], 2)
        self.assertEqual(result['counts']['withEvidenceDelivery'], 1)
        self.assertEqual(result['counts']['deadlineCensored'], 1)
        self.assertEqual(result['provisionalRecommendation']['assistantBudgetMs'], 90000)
        self.assertFalse(result['provisionalRecommendation']['settingsApplied'])
        self.assertTrue(result['issues'])

    def test_retries_remain_separate_attempts_including_failed_duration(self):
        row = fixture()
        retry = copy.deepcopy(row['timingEvents'][2])
        retry.update(outcome='failure', elapsedMicros=1500000)
        row['timingEvents'].insert(2, retry)
        result = describe_request(row)
        self.assertEqual(result['embeddingHttpAttempts'], 2)
        self.assertEqual(result['embeddingHttpTotalMs'], 1800)
        self.assertEqual(result['httpFailuresLogged'], 1)

    def test_reserved_phase_deadline_is_censored_even_when_hard_budget_remains(self):
        row = fixture()
        generation = next(e for e in row['timingEvents'] if e.get('phase') == 'generation')
        generation.update(outcome='failure', budgetAtEndMs=30000, phaseBudgetAtEndMs=0)
        self.assertTrue(describe_request(row)['possibleDeadlineCensoring'])

    def test_warm_cache_missing_remote_calls_are_absent_not_fake_zero_latency(self):
        row = fixture()
        row['timingEvents'] = [e for e in row['timingEvents'] if e.get('operation') not in ('embedding', 'reranker')]
        next(e for e in row['timingEvents'] if e.get('phase') == 'retrieval')['cache'] = 'hit'
        result = describe_request(row)
        self.assertEqual(result['primaryCache'], 'hit')
        self.assertIsNone(result['embeddingHttpTotalMs'])
        self.assertEqual(result['embeddingHttpAttempts'], 0)


if __name__ == '__main__':
    unittest.main()
