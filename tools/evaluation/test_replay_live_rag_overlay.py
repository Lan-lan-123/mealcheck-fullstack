import copy
import unittest

from replay_live_rag_overlay import SCHEMA, SCOPE, apply_overlay, content_id, targets


class LiveOverlayGateTest(unittest.TestCase):
    def fixture(self):
        chunks = [{'title': '主题', 'content': text, 'chunkKey': str(i),
                   'contentHash': content_id('主题', text)} for i, text in enumerate(
                       ['旧证据：缺少重量不能计算。', '候选：没有重量不能推算实际摄入。', '仅在原始候选中。'])]
        old, new, raw = [c['contentHash'] for c in chunks]
        rows = [{'id': 'rag-001', 'question': '不知道重量能算吗？', 'answerability': 'ANSWERABLE',
                 'annotation': {'status': 'PENDING_HUMAN_REVIEW', 'independentlyAnnotated': False},
                 'evidenceTargets': [{'knowledgeId': old, 'relevance': 2}], 'requiredEvidenceGroups': [[old]],
                 'boundaryExpectation': {'missingFacts': ['重量']}}]
        hit = {'knowledgeId': new, 'title': chunks[1]['title'], 'content': chunks[1]['content']}
        baseline = {'results': [{'caseId': 'rag-001', 'question': rows[0]['question'],
            'capturedRetrieval': {'query': rows[0]['question'], 'status': 'SUCCESS', 'hits': [hit]},
            'rawCandidates': [{'hit': {'knowledgeId': raw}}]}]}
        manifest = {'chunks': chunks}
        fingerprints = dict(resultsSha256='r', datasetSha256='d', manifestSha256='m', corpusSha256='c')
        overlay = {'schemaVersion': SCHEMA, 'reviewScope': SCOPE, 'independentlyAnnotated': False,
                   'reviewer': 'Codex', 'source': fingerprints, 'reviewedPairs': [
                       {'caseId': 'rag-001', 'knowledgeId': new, 'relevance': 2,
                        'evidenceQuotes': [chunks[1]['content']], 'rationale': '明确说明缺少重量不能推算。',
                        'alternativeForGroups': [0]}]}
        return baseline, rows, manifest, overlay, fingerprints

    def test_adds_only_new_explicit_labels_and_keeps_original_group_members(self):
        baseline, rows, manifest, overlay, fingerprints = self.fixture()
        before = copy.deepcopy(rows)
        revised, pool = apply_overlay(baseline, rows, manifest, overlay, fingerprints)
        self.assertEqual(rows, before)
        self.assertEqual(revised[0]['requiredEvidenceGroups'][0][:-1], rows[0]['requiredEvidenceGroups'][0])
        self.assertEqual(revised[0]['evidenceTargets'][0], rows[0]['evidenceTargets'][0])
        self.assertEqual(revised[0]['boundaryExpectation'], rows[0]['boundaryExpectation'])
        self.assertFalse(revised[0]['annotation']['independentlyAnnotated'])
        self.assertEqual(len(pool), 1)

    def test_rejects_partial_duplicate_raw_only_and_known_pairs(self):
        for mutation in ('partial', 'duplicate', 'raw', 'known'):
            baseline, rows, manifest, overlay, fingerprints = self.fixture()
            if mutation == 'partial': overlay['reviewedPairs'] = []
            if mutation == 'duplicate': overlay['reviewedPairs'] *= 2
            if mutation == 'raw': overlay['reviewedPairs'][0]['knowledgeId'] = manifest['chunks'][2]['contentHash']
            if mutation == 'known': rows[0]['evidenceTargets'].append({'knowledgeId': manifest['chunks'][1]['contentHash'], 'relevance': 1})
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'exactly cover'):
                apply_overlay(baseline, rows, manifest, overlay, fingerprints)

    def test_rejects_invented_quotes_bad_grade_or_invalid_group(self):
        mutations = [('evidenceQuotes', ['编造']), ('relevance', True), ('relevance', None),
                     ('relevance', 'UNJUDGED'), ('alternativeForGroups', [True]),
                     ('alternativeForGroups', [1]), ('alternativeForGroups', [0, 0])]
        for field, value in mutations:
            baseline, rows, manifest, overlay, fingerprints = self.fixture()
            overlay['reviewedPairs'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                apply_overlay(baseline, rows, manifest, overlay, fingerprints)

    def test_background_and_irrelevant_cannot_replace_direct_groups(self):
        for grade in (0, 1):
            baseline, rows, manifest, overlay, fingerprints = self.fixture()
            overlay['reviewedPairs'][0]['relevance'] = grade
            with self.assertRaisesRegex(ValueError, 'Only direct'):
                apply_overlay(baseline, rows, manifest, overlay, fingerprints)
            overlay['reviewedPairs'][0]['alternativeForGroups'] = []
            revised, _ = apply_overlay(baseline, rows, manifest, overlay, fingerprints)
            self.assertEqual(revised[0]['requiredEvidenceGroups'], rows[0]['requiredEvidenceGroups'])
            if grade == 0: self.assertEqual(len(revised[0]['irrelevantEvidence']), 1)

    def test_rejects_false_independence_source_drift_and_body_tampering(self):
        for mutation in ('independence', 'fingerprint', 'body', 'manifest', 'query'):
            baseline, rows, manifest, overlay, fingerprints = self.fixture()
            if mutation == 'independence': overlay['independentlyAnnotated'] = True
            if mutation == 'fingerprint': fingerprints = {**fingerprints, 'resultsSha256': 'changed'}
            if mutation == 'body': baseline['results'][0]['capturedRetrieval']['hits'][0]['content'] += '改'
            if mutation == 'manifest': manifest['chunks'][1]['content'] += '改'
            if mutation == 'query': baseline['results'][0]['capturedRetrieval']['query'] += '改'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                apply_overlay(baseline, rows, manifest, overlay, fingerprints)

    def test_untouched_rows_and_outside_cases_remain_unchanged(self):
        baseline, rows, manifest, overlay, fingerprints = self.fixture()
        outside = {'id': 'rag-191', 'question': '库外', 'answerability': 'OUT_OF_SCOPE', 'evidenceTargets': [], 'requiredEvidenceGroups': []}
        rows.append(outside)
        baseline['results'].append({'caseId': 'rag-191', 'question': '库外', 'state': 'SKIPPED_OUT_OF_SCOPE'})
        revised, _ = apply_overlay(baseline, rows, manifest, overlay, fingerprints)
        self.assertEqual(revised[1], outside)
        baseline['results'][1]['capturedRetrieval'] = {'hits': []}
        with self.assertRaisesRegex(ValueError, 'Out-of-scope'):
            targets(baseline, rows, manifest)


if __name__ == '__main__':
    unittest.main()
