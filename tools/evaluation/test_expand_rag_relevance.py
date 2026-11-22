import copy
import hashlib
import unittest

from expand_rag_relevance import build, normalized


class RelevanceReviewGateTest(unittest.TestCase):
    def fixture(self):
        chunks = []
        for index, text in enumerate(('计量条件未知，不能推算。', '查阅标签计量单位，缺少重量时不能推算。', '收纳空袋。')):
            identity = hashlib.sha256((normalized('主题') + '\n\n' + normalized(text)).encode()).hexdigest()
            chunks.append({'title': '主题', 'content': text, 'contentHash': identity, 'chunkKey': str(index)})
        rows = [{'id': 'rag-001', 'question': '能推算吗？', 'answerability': 'ANSWERABLE',
                 'annotation': {}, 'evidenceTargets': [{'knowledgeId': chunks[0]['contentHash'], 'relevance': 2}],
                 'requiredEvidenceGroups': [[chunks[0]['contentHash']]]}]
        reviews = [{'reviewer': 'Codex', 'independentlyAnnotated': False, 'cases': [
            {'caseId': 'rag-001', 'inspectedKnowledgeIds': [c['contentHash'] for c in chunks[1:]],
             'judgments': [
                 {'knowledgeId': chunks[1]['contentHash'], 'relevance': 2, 'evidenceQuotes': [chunks[1]['content']],
                  'rationale': '直接说明缺少重量不能推算。', 'alternativeForGroups': [0]},
                 {'knowledgeId': chunks[2]['contentHash'], 'relevance': 0, 'evidenceQuotes': [chunks[2]['content']],
                  'rationale': '收纳空袋不能回答计量问题。', 'alternativeForGroups': []}],
             'notes': '只复核列出的候选，其他切片保持未判断。', 'chunkIssues': []}]}]
        return rows, {'chunks': chunks, 'corpusSha256': 'source'}, reviews

    def test_literal_alternative_and_negative_are_bound_without_claiming_human_gold(self):
        rows, manifest, reviews = self.fixture()
        revised, summary = build(rows, manifest, reviews)
        self.assertEqual(len(revised[0]['requiredEvidenceGroups'][0]), 2)
        self.assertEqual(revised[0]['irrelevantEvidence'][0]['relevance'], 0)
        self.assertEqual(summary['addedJudgments'], 2)
        self.assertFalse(revised[0]['annotation']['independentlyAnnotated'])
        self.assertEqual(revised[0]['annotation']['unlistedChunkPolicy'], 'UNJUDGED_NOT_IRRELEVANT')
        self.assertEqual(len(rows[0]['evidenceTargets']), 1)

    def test_unread_source_and_invented_quote_are_rejected(self):
        rows, manifest, reviews = self.fixture()
        reviews[0]['cases'][0]['inspectedKnowledgeIds'].pop()
        with self.assertRaisesRegex(ValueError, 'unread'):
            build(rows, manifest, reviews)
        rows, manifest, reviews = self.fixture()
        reviews[0]['cases'][0]['judgments'][0]['evidenceQuotes'] = ['模型编造的引文。']
        with self.assertRaisesRegex(ValueError, 'anchors'):
            build(rows, manifest, reviews)

    def test_partial_duplicate_or_false_independent_review_is_rejected(self):
        rows, manifest, reviews = self.fixture()
        for invalid in ([], reviews + copy.deepcopy(reviews)):
            with self.assertRaises(ValueError):
                build(rows, manifest, invalid)
        reviews[0]['independentlyAnnotated'] = True
        with self.assertRaisesRegex(ValueError, 'independent'):
            build(rows, manifest, reviews)

    def test_background_cannot_satisfy_a_required_group(self):
        rows, manifest, reviews = self.fixture()
        reviews[0]['cases'][0]['judgments'][0]['relevance'] = 1
        with self.assertRaisesRegex(ValueError, 'direct evidence'):
            build(rows, manifest, reviews)

    def test_source_drift_and_conflicting_prior_decision_need_adjudication(self):
        rows, manifest, reviews = self.fixture()
        manifest['chunks'][1]['content'] += '改动'
        with self.assertRaisesRegex(ValueError, 'identity'):
            build(rows, manifest, reviews)
        rows, manifest, reviews = self.fixture()
        rows[0]['evidenceTargets'].append({'knowledgeId': manifest['chunks'][1]['contentHash'], 'relevance': 1})
        with self.assertRaisesRegex(ValueError, 'adjudication'):
            build(rows, manifest, reviews)

    def test_out_of_scope_cannot_gain_retrieval_candidates(self):
        rows, manifest, reviews = self.fixture()
        rows[0]['answerability'] = 'OUT_OF_SCOPE'
        with self.assertRaisesRegex(ValueError, 'Out-of-scope'):
            build(rows, manifest, reviews)

    def test_compound_question_accepts_two_facets_without_turning_one_into_a_complete_answer(self):
        rows, manifest, reviews = self.fixture()
        old = rows[0]['requiredEvidenceGroups'][0][0]
        new = reviews[0]['cases'][0]['judgments'][0]['knowledgeId']
        reviews[0]['cases'][0]['judgments'][0]['alternativeForGroups'] = []
        reviews[0]['cases'][0]['coveragePlan'] = {'groups': [[old], [old, new]], 'rationales': ['第一部分', '第二部分']}
        revised, _ = build(rows, manifest, reviews)
        self.assertEqual(revised[0]['requiredEvidenceGroups'], [[old], [old, new]])
        self.assertNotIn(new, revised[0]['requiredEvidenceGroups'][0])
        reviews[0]['cases'][0]['coveragePlan']['groups'] = [[new]]
        reviews[0]['cases'][0]['coveragePlan']['rationales'] = ['遗漏旧完整证据']
        with self.assertRaisesRegex(ValueError, 'preserve original'):
            build(rows, manifest, reviews)

    def test_prior_false_positive_can_only_be_downgraded_with_an_explicit_read_and_replacement_plan(self):
        rows, manifest, reviews = self.fixture()
        case = reviews[0]['cases'][0]
        old = rows[0]['evidenceTargets'][0]['knowledgeId']
        new = case['judgments'][0]['knowledgeId']
        case['inspectedKnowledgeIds'].append(old)
        case['judgments'][0]['alternativeForGroups'] = []
        case['priorTargetRevisions'] = [{'knowledgeId': old, 'relevance': 1, 'rationale': '旧片只讲实施方法，不回答本题。'}]
        with self.assertRaisesRegex(ValueError, 'replacement'):
            build(rows, manifest, reviews)
        case['coveragePlan'] = {'groups': [[new]], 'rationales': ['本题直接依据'],
                                'adjudicationReason': '复核旧片后将错误直接标注降为背景。'}
        revised, summary = build(rows, manifest, reviews)
        self.assertEqual(revised[0]['evidenceTargets'][0]['relevance'], 1)
        self.assertEqual(revised[0]['requiredEvidenceGroups'], [[new]])
        self.assertEqual(summary['priorTargetDowngrades'], 1)


if __name__ == '__main__':
    unittest.main()
