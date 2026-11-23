"""Offline scope/packet/build gates; no network or database calls."""
import unittest
from unittest.mock import patch

import run_live_rag_screening as live
from ci_fixture_paths import fixture_path


class LivePilotGatesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prepared = fixture_path('MEALCHECK_TEST_SCREENING_PACKET')
        cls.manifest = fixture_path('MEALCHECK_TEST_CHUNK_MANIFEST')
        cls.sha = live.digest(cls.prepared / 'plan.json')
        cls.auth = {'approved': True, 'planSha256': cls.sha, 'maximumRequests': 1381,
                    'maximumAdmissionUnits': 2_000_000, 'maxRetries': 0, 'stopOnFirstProviderError': True,
                    'chatCallsAllowed': 0, 'rerankCallsAllowed': 0, 'supplementalQueryCallsAllowed': 0,
                    'preserveAdminEntries': True, 'source': 'diet_guides.md',
                    'sourceVersion': 'v8-longform-200-boundary-v2', 'authorizationEvidence': 'test fixture only'}

    def test_exact_scope_is_accepted(self):
        live.validate_authorization(self.auth, self.sha)

    def test_every_scope_field_is_enforced_and_types_are_strict(self):
        for field, wrong in {'approved': False, 'planSha256': 'stale', 'maximumRequests': 1382,
                             'maximumAdmissionUnits': 2_000_001, 'maxRetries': 1, 'stopOnFirstProviderError': False,
                             'chatCallsAllowed': 1, 'rerankCallsAllowed': 1, 'supplementalQueryCallsAllowed': 1,
                             'preserveAdminEntries': False, 'source': 'admin', 'sourceVersion': 'old',
                             'authorizationEvidence': ''}.items():
            with self.subTest(field=field):
                changed = {**self.auth, field: wrong}
                with self.assertRaises(ValueError):
                    live.validate_authorization(changed, self.sha)
        with self.assertRaises(ValueError):
            live.validate_authorization({**self.auth, 'maximumRequests': True}, self.sha)

    def test_real_prepared_wrapper_and_current_fingerprints(self):
        inputs, plan = live.validate_packet(self.prepared, self.manifest)
        self.assertEqual(plan['chunkCount'], 1351)
        self.assertEqual(plan['plannedQueryEmbeddingCount'], 28)
        self.assertEqual(plan['conservativeInputAdmissionUnits'], 1_905_828)
        self.assertEqual(len(inputs), 5)

    def test_changed_plan_is_rejected_before_compilation(self):
        original = live.load
        def changed(path):
            data = original(path)
            if path.name == 'plan.json':
                data['maxRetries'] = 1
            return data
        with patch.object(live, 'load', side_effect=changed), self.assertRaisesRegex(ValueError, 'maxRetries'):
            live.validate_packet(self.prepared, self.manifest)

    def test_changed_selected_case_and_expected_index_are_rejected(self):
        original = live.load
        for name, change in (
                ('screening30.json', lambda x: x['cases'][0].update(question='different query')),
                ('expected-chunks.json', lambda x: x['chunks'][0].update(contentHash='wrong'))):
            def changed(path):
                data = original(path)
                if path.name == name:
                    change(data)
                return data
            with self.subTest(name=name), patch.object(live, 'load', side_effect=changed), self.assertRaises(ValueError):
                live.validate_packet(self.prepared, self.manifest)

    def test_fresh_production_components_are_present(self):
        paths = live.sources()
        self.assertTrue(all(path.is_file() for path in paths))
        self.assertEqual(len(paths), len(set(paths)))
        for name in ('PgVectorKnowledgeService', 'KnowledgeEmbeddingService', 'KnowledgeSourceSyncStore',
                     'KnowledgeVectorPersistenceService', 'KnowledgeResultPostProcessor', 'RagChainAssessment'):
            self.assertIn(name + '.java', [p.name for p in paths])


if __name__ == '__main__':
    unittest.main()
