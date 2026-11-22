import copy
import json
from pathlib import Path
import unittest

import run_local_hash_screening as local
from ci_fixture_paths import fixture_path


class LocalHashScreeningGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.corpus = local.KNOWLEDGE / 'diet_guides.md'
        cls.manifest = json.loads(fixture_path('MEALCHECK_TEST_CHUNK_MANIFEST').read_text(encoding='utf-8'))
        cls.dataset = json.loads((local.KNOWLEDGE / 'rag_eval_cases.json').read_text(encoding='utf-8'))
        cls.suite = json.loads((local.KNOWLEDGE / 'rag_chunking_eval_suite.json').read_text(encoding='utf-8'))
        cls.metadata = json.loads((local.KNOWLEDGE / 'diet_guides_metadata.json').read_text(encoding='utf-8'))

    def test_current_source_and_fixed_screening_are_accepted(self):
        result = local.validate_inputs(self.corpus, self.manifest, self.dataset, self.suite, self.metadata)
        self.assertTrue(result['valid'])
        self.assertEqual(result['screeningCases'], 30)

    def test_stale_manifest_and_changed_splitter_are_refused(self):
        changed = copy.deepcopy(self.manifest)
        changed['corpusSha256'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'fingerprint'):
            local.validate_inputs(self.corpus, changed, self.dataset, self.suite, self.metadata)
        changed = copy.deepcopy(self.manifest)
        changed['splitterRevision'] = 'old-window'
        with self.assertRaisesRegex(ValueError, 'splitter'):
            local.validate_inputs(self.corpus, changed, self.dataset, self.suite, self.metadata)

    def test_question_drift_cannot_be_hidden_by_same_ids(self):
        changed = copy.deepcopy(self.dataset)
        changed[0]['question'] += ' changed'
        with self.assertRaisesRegex(ValueError, 'fingerprint'):
            local.validate_inputs(self.corpus, self.manifest, changed, self.suite, self.metadata)

    def test_default_budget_cannot_silently_change_the_experiment(self):
        changed = copy.deepcopy(self.metadata)
        changed['splitter']['maxTokens'] = 700
        with self.assertRaisesRegex(ValueError, 'budget'):
            local.validate_inputs(self.corpus, self.manifest, self.dataset, self.suite, changed)

    def test_output_escape_and_existing_evidence_are_refused(self):
        with self.assertRaisesRegex(ValueError, 'logs'):
            local.require_output(local.ROOT / 'frontend/new-evidence')
        with self.assertRaises(FileExistsError):
            local.require_output(local.ROOT / 'logs')


if __name__ == '__main__':
    unittest.main()
