from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_rag_screening_pilot as pilot
from ci_fixture_paths import fixture_path


class ScreeningPilotPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.corpus = (pilot.KNOWLEDGE / 'diet_guides.md').read_bytes()
        cls.rows = json.loads((pilot.KNOWLEDGE / 'rag_eval_cases.json').read_bytes())
        cls.metadata = json.loads((pilot.KNOWLEDGE / 'diet_guides_metadata.json').read_bytes())
        cls.suite = json.loads((pilot.KNOWLEDGE / 'rag_chunking_eval_suite.json').read_bytes())
        cls.manifest = json.loads(fixture_path('MEALCHECK_TEST_CHUNK_MANIFEST').read_bytes())

    def prepare(self, **updates):
        values = {'corpus_bytes': self.corpus, 'rows': self.rows, 'metadata': self.metadata,
                  'suite': self.suite, 'manifest': self.manifest}
        values.update(updates)
        return pilot.prepare(**values)

    def snapshot_fixture(self):
        chunks = self.manifest['chunks'][:2]
        expected = [{'source': pilot.SOURCE, 'title': c['title'], 'contentHash': c['contentHash'],
                     'chunkKey': c['chunkKey'], 'sourceVersion': pilot.SOURCE_VERSION} for c in chunks]
        snapshot = [{**entry, 'content': chunk['content'], 'signature': 'semantic-v1:configured-model:512:fixture',
                     'hasEmbedding': True, 'id': index + 1} for index, (entry, chunk) in enumerate(zip(expected, chunks))]
        return expected, snapshot

    def test_fixed_screening_does_not_spend_on_out_of_scope_or_answer_holdout(self):
        plan, selected, expected = self.prepare()
        self.assertEqual(plan['status'], 'PREPARED_NOT_EXECUTED')
        self.assertEqual(len(expected), 1351)
        self.assertEqual(len(selected), 30)
        self.assertEqual(plan['plannedMaximumNewVectorRequests'], 1379)
        self.assertLessEqual(plan['conservativeInputAdmissionUnits'], 2_000_000)
        self.assertEqual(plan['providerCallsMade'], 0)
        self.assertFalse(plan['indexRebuilt'])
        self.assertFalse(set(plan['selectedCaseIds']) & set(self.suite['sets']['answer10']['caseIds']))
        outside = [r for r in selected if r['answerability'] == 'OUT_OF_SCOPE']
        self.assertEqual([r['id'] for r in outside], ['rag-191', 'rag-197'])
        self.assertTrue(all(not r['agentEvaluation']['expectedTools'] for r in outside))

    def test_changed_corpus_splitter_and_candidate_identity_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'must agree'):
            self.prepare(corpus_bytes=self.corpus + b'\nchanged\n')
        bad = deepcopy(self.manifest)
        bad['splitterRevision'] = 'obsolete'
        with self.assertRaisesRegex(ValueError, 'revision differs'):
            self.prepare(manifest=bad)
        bad = deepcopy(self.manifest)
        bad['chunks'][0]['content'] += 'changed'
        with self.assertRaisesRegex(ValueError, 'identity'):
            self.prepare(manifest=bad)

    def test_fixed_ids_cannot_be_resampled_or_reordered(self):
        changed = deepcopy(self.suite)
        changed['sets']['screening30']['caseIds'][0:2] = reversed(changed['sets']['screening30']['caseIds'][0:2])
        with self.assertRaisesRegex(ValueError, 'exact fixed IDs'):
            self.prepare(suite=changed)

    def test_tool_schema_rejects_scope_retrieval_and_unbounded_calls(self):
        outside = deepcopy(self.rows[190])
        outside['agentEvaluation']['expectedTools'] = ['search_diet_knowledge']
        with self.assertRaisesRegex(ValueError, 'Tool rules'):
            pilot.validate_tool_rules(outside)
        knowledge = deepcopy(self.rows[0])
        knowledge['agentEvaluation']['maxToolCalls'] = 20
        with self.assertRaisesRegex(ValueError, 'Tool rules'):
            pilot.validate_tool_rules(knowledge)

    def test_same_count_and_stored_hash_cannot_hide_changed_actual_body(self):
        expected, snapshot = self.snapshot_fixture()
        self.assertTrue(pilot.compare_index(snapshot, expected)['indexConsistencyReady'])
        snapshot[0]['content'] = 'unrelated body with a copied stored hash'
        result = pilot.compare_index(snapshot, expected)
        self.assertFalse(result['indexConsistencyReady'])
        self.assertIn('ACTUAL_CONTENT_HASH_DIFFERS', [e['kind'] for e in result['errors']])
        snapshot[0]['content'] = self.manifest['chunks'][0]['content']
        del snapshot[0]['content']
        result = pilot.compare_index(snapshot, expected)
        self.assertFalse(result['contentIdentityFullyVerified'])
        self.assertFalse(result['indexConsistencyReady'])

    def test_missing_duplicate_stale_versions_and_mixed_signatures_are_not_ready(self):
        expected, snapshot = self.snapshot_fixture()
        bad = deepcopy(snapshot)
        bad[0]['sourceVersion'] = 'v7'
        bad[1]['signature'] = 'semantic-v1:another-model:512:fixture'
        result = pilot.compare_index(bad, expected)
        self.assertFalse(result['indexConsistencyReady'])
        self.assertIn('INDEX_FIELD_DIFFERS', [e['kind'] for e in result['errors']])
        self.assertIn('MIXED_EMBEDDING_SIGNATURES', [e['kind'] for e in result['errors']])
        self.assertFalse(pilot.compare_index(snapshot[:-1], expected)['indexConsistencyReady'])
        self.assertFalse(pilot.compare_index(snapshot + [snapshot[0]], expected)['indexConsistencyReady'])
        snapshot[0]['hasEmbedding'] = False
        self.assertFalse(pilot.compare_index(snapshot, expected)['indexConsistencyReady'])

    def test_hash_vectors_are_not_semantic_quality_and_admin_deletion_cannot_hide_in_counts(self):
        expected, snapshot = self.snapshot_fixture()
        for item in snapshot:
            item['signature'] = 'hash-v1:512'
        result = pilot.compare_index(snapshot, expected)
        self.assertTrue(result['indexConsistencyReady'])
        self.assertFalse(result['semanticIndexSnapshotConsistent'])
        self.assertFalse(result['retrievalQualityMeasured'])
        admin = {'id': 99, 'source': 'admin', 'title': 'manual', 'contentHash': 'known',
                 'chunkKey': 'manual-fixture', 'sourceVersion': 'manual', 'signature': 'hash-v1:512'}
        before = snapshot + [admin]
        self.assertTrue(pilot.compare_index(before, expected, before)['adminInventoryPreserved'])
        changed = snapshot + [{**admin, 'id': 100}]
        result = pilot.compare_index(changed, expected, before)
        self.assertFalse(result['adminInventoryPreserved'])
        self.assertFalse(result['indexConsistencyReady'])

    def test_export_is_read_only_preparation_preserves_history_and_stays_under_logs(self):
        plan, selected, expected = self.prepare()
        with tempfile.TemporaryDirectory() as directory, patch.object(pilot, 'ROOT', Path(directory)):
            output = Path(directory) / 'logs/backend/pilot'
            pilot.export(output, plan, selected, expected)
            sql = (output / 'read-only-index-snapshot.sql').read_text(encoding='utf-8')
            self.assertIn('BEGIN TRANSACTION READ ONLY;', sql)
            self.assertNotIn('DELETE ', sql)
            self.assertNotIn('UPDATE ', sql)
            self.assertIn("CASE WHEN source = 'diet_guides.md' THEN content ELSE NULL END", sql)
            checksums = json.loads((output / 'checksums.json').read_text(encoding='utf-8'))
            self.assertTrue(all(pilot.digest((output / name).read_bytes()) == value for name, value in checksums.items()))
            with self.assertRaises(FileExistsError):
                pilot.export(output, plan, selected, expected)
            with self.assertRaisesRegex(ValueError, 'logs'):
                pilot.export(Path(directory) / 'source-output', plan, selected, expected)


if __name__ == '__main__':
    unittest.main()
