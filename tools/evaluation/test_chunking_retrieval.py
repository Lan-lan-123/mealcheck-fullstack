import copy
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from run_chunking_retrieval import ROOT, cache_key, embedding_text, score_case, validate_vector, embed
from test_chunking_smoke import fixture


class RetrievalPilotTest(unittest.TestCase):
    def test_plan_mode_never_constructs_http_client_or_starts_workers(self):
        with tempfile.TemporaryDirectory(prefix='chunking-plan-test-', dir=ROOT / 'logs/backend') as directory:
            root = Path(directory)
            output = root / 'output'
            output.mkdir()
            with patch('run_chunking_retrieval.ROOT', root), \
                 patch('run_chunking_retrieval.urllib.request.build_opener', side_effect=AssertionError('network')), \
                 patch('run_chunking_retrieval.ThreadPoolExecutor', side_effect=AssertionError('workers')):
                vectors, ledger = embed(['甲', '乙', '甲'], output,
                    ('https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', 'unused', 'test', 2), False, True)
            self.assertEqual(vectors, {})
            self.assertEqual(ledger['missingInputs'], 2)
            self.assertEqual(ledger['plannedRequests'], 1)
            self.assertEqual(ledger['calls'], [])

    def test_cache_identity_separates_model_endpoint_dimension_and_exact_text(self):
        base = ('https://example.com/embeddings', 'model', 2, '正文')
        original = cache_key(*base)
        for index, value in enumerate(('https://other.com/embeddings', 'other', 3, '正文 ')):
            args = list(base)
            args[index] = value
            self.assertNotEqual(cache_key(*args), original)

    def test_vector_validation_rejects_bad_dimensions_nonfinite_and_zero(self):
        for value in ([0, 0], [1], [float('nan'), 1], [float('inf'), 1]):
            with self.assertRaises(ValueError):
                validate_vector(value, 2)
        np.testing.assert_allclose(validate_vector([1, 2], 2), [1, 2])

    def test_embedding_text_matches_production_title_and_body_separator(self):
        self.assertEqual(embedding_text({'title': ' 题 ', 'content': ' 正文 '}), '题\n\n正文')

    def test_alternative_group_is_or_but_multiple_groups_are_and(self):
        corpus = '## 主题\n甲乙丙丁'
        manifest, suite = fixture(corpus, '乙丙', ['甲乙', '丙丁'])
        case = suite['cases'][0]
        case['requiredEvidenceGroups'] = [['missing-alternative', 'unit'], ['other-required']]
        result = score_case(manifest, corpus, suite, case)
        self.assertEqual(result['groupRecall'], 0.5)
        self.assertFalse(result['allGroupsCovered'])
        self.assertEqual(result['quoteHits'], 1)
        missing = copy.deepcopy(manifest)
        missing['chunks'] = missing['chunks'][:1]
        self.assertEqual(score_case(missing, corpus, suite, case)['groupRecall'], 0)


if __name__ == '__main__':
    unittest.main()
