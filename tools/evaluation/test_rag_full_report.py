import unittest
from report_rag_full import summarize


class FullRagReportTest(unittest.TestCase):
    def test_actual_refusal_and_partial_answers_do_not_depend_on_judge(self):
        data = {'cases': [
            {'actualRefused': True, 'qualityJudgment': {'actuallyRefused': False}},
            {'partialAnswer': True, 'groundingIssues': [{'code': 'UNSUPPORTED_NUMBER'}]},
            {'summary': '知识检索或证据核验暂时不可用，无法可靠生成回答。请稍后再试。'}]}
        summary = summarize(data)
        self.assertEqual(summary['explicitKnowledgeRefusalCount'], 2)
        self.assertEqual(summary['falseRefusalProxyCount'], 0)
        self.assertEqual(summary['partialAnswerCount'], 1)
        self.assertEqual(summary['groundingIssueCodes'], {'UNSUPPORTED_NUMBER': 1})

    def test_tool_errors_stay_in_denominator_and_cache_usage_is_excluded(self):
        data = {'executionMode': 'LIVE_PROVIDER', 'qualityMeasurement': True, 'state': 'COMPLETE_WITH_ERRORS',
                'selectedCaseIds': [1, 2], 'cases': [
                    {'caseId': 1, 'status': 'COMPLETE', 'toolPassed': True, 'functionCallingSuccessful': True},
                    {'caseId': 2, 'status': 'ERROR', 'toolPassed': False, 'functionCallingSuccessful': False}],
                'calls': [{'operation': 'embedding', 'cacheHit': True, 'usage': {'total_tokens': 99}},
                          {'operation': 'chat', 'cacheHit': False, 'usage': {'total_tokens': 12}}]}
        summary = summarize(data)
        self.assertEqual(summary['toolAccuracy'], 0.5)
        self.assertEqual(summary['executionErrors'], 1)
        self.assertEqual(summary['actualProviderCalls'], 1)
        self.assertEqual(summary['embeddingCacheHits'], 1)
        self.assertEqual(summary['providerUsage']['total_tokens'], 12)
        self.assertFalse(summary['complete'])

    def test_offline_results_cannot_claim_real_quality_measurement(self):
        data = {'executionMode': 'OFFLINE_FIXTURE', 'qualityMeasurement': False, 'state': 'COMPLETE',
                'selectedCaseIds': [1], 'cases': [{'caseId': 1, 'status': 'COMPLETE', 'qualityPassed': True}]}
        summary = summarize(data)
        self.assertFalse(summary['realModelEvidence'])
        self.assertFalse(summary['complete'])
        self.assertIsNone(summary['qualityProxyPassRate'])

    def test_empty_references_do_not_count_as_source_identity_success(self):
        data = {'cases': [{'references': [], 'referencesExistInKnowledge': True}]}
        summary = summarize(data)
        self.assertEqual(summary['casesWithReferencesCount'], 0)
        self.assertEqual(summary['referenceIdentityPassedCount'], 0)

    def test_partial_coverage_is_not_a_complete_run(self):
        data = {'executionMode': 'LIVE_PROVIDER', 'qualityMeasurement': True, 'state': 'COMPLETE',
                'selectedCaseIds': [1, 2], 'cases': [{'caseId': 1, 'status': 'COMPLETE',
                'usedRemoteChat': True, 'qualityJudgment': {}}]}
        self.assertFalse(summarize(data)['complete'])

    def test_backend_boundary_can_complete_without_generator_call(self):
        data = {'executionMode': 'LIVE_PROVIDER', 'qualityMeasurement': True, 'state': 'COMPLETE',
                'selectedCaseIds': [154], 'cases': [{'caseId': 154, 'status': 'COMPLETE',
                'usedRemoteChat': False, 'generationPath': 'BACKEND_BOUNDARY',
                'qualityJudgment': {'factualVerdict': 'UNVERIFIED'}}]}
        summary = summarize(data)
        self.assertTrue(summary['complete'])
        self.assertEqual(summary['factualVerdicts'], {'UNVERIFIED': 1})


if __name__ == '__main__':
    unittest.main()
