from copy import deepcopy
import hashlib
import unittest

from rebind_rag_chunk_boundaries import MigrationBlocked, normalized, rebind_rows, update_metadata


def manifest(entries):
    chunks = []
    for index, (title, text) in enumerate(entries):
        identity = hashlib.sha256((normalized(title) + '\n\n' + normalized(text)).encode()).hexdigest()
        chunks.append({'title': title, 'content': text, 'contentHash': identity,
                       'chunkKey': f'part-{index}', 'chunkIndex': index, 'tokenCount': 300})
    return {'chunks': chunks, 'chunkCount': len(chunks), 'topicCount': len({t for t, _ in entries}),
            'corpusSha256': 'unchanged', 'minTokens': 300, 'maxTokens': 500, 'overlapTokens': 50}


def target(chunk, quotes, grade=2):
    return {'knowledgeId': chunk['contentHash'], 'chunkKey': chunk['chunkKey'], 'title': chunk['title'],
            'evidenceQuotes': quotes, 'relevance': grade, 'rationale': '原题语义判断。'}


def row(targets, groups=None):
    return {'id': 'rag-001', 'question': '需要什么依据？', 'answerability': 'ANSWERABLE',
            'agentEvaluation': {'expectedTools': ['search_diet_knowledge']},
            'annotationRevision': 'old', 'annotation': {'status': 'PENDING_HUMAN_REVIEW',
                                                        'independentlyAnnotated': False},
            'evidenceTargets': targets,
            'requiredEvidenceGroups': groups if groups is not None else [[t['knowledgeId'] for t in targets if t['relevance'] == 2]]}


def covered(groups, retrieved):
    return all(set(group) & retrieved for group in groups)


class BoundaryRebindingTest(unittest.TestCase):
    def test_split_quote_facets_preserve_and_with_alternative_or(self):
        old = manifest([('主题', '甲条件。乙条件。'), ('另一来源', '完整替代。')])
        new = manifest([('主题', '甲条件。'), ('主题', '乙条件。'), ('另一来源', '完整替代。')])
        original = row([target(old['chunks'][0], ['甲条件。', '乙条件。']),
                        target(old['chunks'][1], ['完整替代。'])])
        revised, stats = rebind_rows([original], old, new)
        groups = revised[0]['requiredEvidenceGroups']
        a, b, c = [chunk['contentHash'] for chunk in new['chunks']]
        self.assertEqual(len(groups), 2)
        self.assertFalse(covered(groups, {a}))
        self.assertFalse(covered(groups, {b}))
        self.assertTrue(covered(groups, {a, b}))
        self.assertTrue(covered(groups, {c}))
        self.assertEqual(revised[0]['question'], original['question'])
        self.assertEqual(revised[0]['agentEvaluation'], original['agentEvaluation'])
        self.assertFalse(revised[0]['annotation']['independentlyAnnotated'])
        self.assertEqual(stats['afterCoverageGroups'], 2)

    def test_all_quotes_in_two_overlapping_chunks_are_complete_alternatives(self):
        old = manifest([('主题', '甲条件。乙条件。')])
        new = manifest([('主题', '前文。甲条件。乙条件。'), ('主题', '甲条件。乙条件。后文。')])
        revised, _ = rebind_rows([row([target(old['chunks'][0], ['甲条件。', '乙条件。'])])], old, new)
        groups = revised[0]['requiredEvidenceGroups']
        self.assertEqual(len(groups), 1)
        self.assertEqual(len(groups[0]), 2)
        self.assertTrue(covered(groups, {new['chunks'][0]['contentHash']}))
        self.assertTrue(covered(groups, {new['chunks'][1]['contentHash']}))

    def test_partial_or_missing_single_quote_is_a_review_block_not_automatic_weakening(self):
        old = manifest([('主题', '实际重量与可靠标签都需要。')])
        new = manifest([('主题', '实际重量与'), ('主题', '可靠标签都需要。')])
        with self.assertRaises(MigrationBlocked) as result:
            rebind_rows([row([target(old['chunks'][0], ['实际重量与可靠标签都需要。'])])], old, new)
        self.assertEqual(result.exception.issues[0]['kind'], 'ANCHOR_SPLIT_OR_MISSING')

    def test_invalid_identity_and_changed_corpus_are_rejected(self):
        old = manifest([('主题', '原文。')])
        new = deepcopy(old)
        new['chunks'][0]['content'] = '被改正文。'
        with self.assertRaisesRegex(ValueError, 'identity'):
            rebind_rows([row([target(old['chunks'][0], ['原文。'])])], old, new)
        new = deepcopy(old)
        new['corpusSha256'] = 'changed'
        with self.assertRaisesRegex(ValueError, 'unchanged'):
            rebind_rows([row([target(old['chunks'][0], ['原文。'])])], old, new)

    def test_expanded_negative_context_and_positive_negative_collision_are_blocked(self):
        old = manifest([('主题', '收纳空袋。'), ('主题', '核对实际重量。')])
        new = manifest([('主题', '收纳空袋。核对实际重量。')])
        original = row([target(old['chunks'][1], ['核对实际重量。'])])
        original['irrelevantEvidence'] = [target(old['chunks'][0], ['收纳空袋。'], 0)]
        with self.assertRaises(MigrationBlocked) as result:
            rebind_rows([original], old, new)
        self.assertEqual({i['kind'] for i in result.exception.issues},
                         {'NEGATIVE_CONTEXT_EXPANDED', 'POSITIVE_NEGATIVE_COLLISION'})

    def test_background_and_direct_merge_retains_all_quotes_and_stronger_supported_grade(self):
        old = manifest([('主题', '一般背景。'), ('主题', '本题直接条件。')])
        new = manifest([('主题', '一般背景。本题直接条件。')])
        original = row([target(old['chunks'][0], ['一般背景。'], 1),
                        target(old['chunks'][1], ['本题直接条件。'])])
        revised, _ = rebind_rows([original], old, new)
        final = revised[0]['evidenceTargets']
        self.assertEqual(len(final), 1)
        self.assertEqual(final[0]['relevance'], 2)
        self.assertEqual(final[0]['evidenceQuotes'], ['一般背景。', '本题直接条件。'])
        self.assertEqual(len(final[0]['boundaryMigrationSources']), 2)

    def test_out_of_scope_remains_without_retrievable_evidence(self):
        old = manifest([('主题', '原文。')])
        original = row([], [])
        original['answerability'] = 'OUT_OF_SCOPE'
        original['expectNoAnswer'] = True
        revised, _ = rebind_rows([original], old, old)
        self.assertEqual(revised[0]['evidenceTargets'], [])
        self.assertEqual(revised[0]['requiredEvidenceGroups'], [])

    def test_default_length_goals_are_hard_bounds_not_mutable_metadata(self):
        from rebind_rag_chunk_boundaries import BOUNDS
        entries, topics = [], []
        for bucket, number in [('SHORT', 40), ('MEDIUM', 60), ('LONG', 60), ('EXTENDED', 40)]:
            for index in range(number):
                title = f'{bucket}-{index}'
                minimum, maximum = BOUNDS[bucket]
                topics.append({'title': title, 'lengthBucket': bucket, 'minChunks': minimum,
                               'maxChunks': maximum, 'actualChunks': minimum})
                entries.extend((title, f'正文{part}。') for part in range(minimum))
        actual = manifest(entries)
        metadata = {'topics': topics, 'corpusSha256': 'unchanged', 'chunkCount': len(entries),
                    'splitter': {'strategy': 'recursive-markdown'}}
        revised = update_metadata(metadata, actual, actual)
        self.assertEqual(revised['lengthGoalDeviationCount'], 0)
        metadata['topics'][0]['maxChunks'] = 30
        with self.assertRaisesRegex(ValueError, 'length plan'):
            update_metadata(metadata, actual, actual)

    def negative_decision_fixture(self):
        old = manifest([('主题', '本题条件。'), ('另一主题', '收纳空袋。')])
        new = manifest([('主题', '本题条件。'), ('另一主题', '收纳空袋。新增上下文。')])
        original = row([target(old['chunks'][0], ['本题条件。'])])
        original['irrelevantEvidence'] = [target(old['chunks'][1], ['收纳空袋。'], 0)]
        decision = {'caseId': 'rag-001', 'previousKnowledgeId': old['chunks'][1]['contentHash'],
                    'newKnowledgeId': new['chunks'][1]['contentHash'], 'relevance': 'UNJUDGED',
                    'quotes': ['新增上下文。'], 'rationale': '新增上下文不能沿用旧整片无关判断。',
                    'reviewer': 'Codex root', 'corpusSha256': 'unchanged', 'newManifestSha256': 'actual-manifest-bytes'}
        return old, new, original, decision

    def test_explicit_unjudged_decision_preserves_negative_history_without_creating_zero_label(self):
        old, new, original, decision = self.negative_decision_fixture()
        revised, counts = rebind_rows([original], old, new, adjudications=[decision],
                                     new_manifest_sha256='actual-manifest-bytes')
        self.assertEqual(revised[0]['irrelevantEvidence'], [])
        history = revised[0]['boundaryPriorNegativeAdjudications'][0]
        self.assertEqual(history['previousTarget']['relevance'], 0)
        self.assertEqual(history['decision']['relevance'], 'UNJUDGED')
        self.assertEqual(counts['negativeTargetsChangedToUnjudged'], 1)
        self.assertEqual(len(revised[0]['evidenceTargets']), 1)

    def test_reviewed_new_direct_context_does_not_automatically_satisfy_an_original_group(self):
        old, new, original, decision = self.negative_decision_fixture()
        decision['relevance'] = 2
        revised, _ = rebind_rows([original], old, new, adjudications=[decision],
                                new_manifest_sha256='actual-manifest-bytes')
        self.assertEqual(len(revised[0]['evidenceTargets']), 2)
        self.assertFalse(covered(revised[0]['requiredEvidenceGroups'], {decision['newKnowledgeId']}))

    def test_adjudication_reviewer_source_fingerprint_and_quotes_are_mandatory(self):
        old, new, original, decision = self.negative_decision_fixture()
        for key, bad in [('reviewer', ''), ('newManifestSha256', 'wrong'), ('quotes', ['伪造引句。'])]:
            changed = deepcopy(decision)
            changed[key] = bad
            with self.assertRaises(ValueError):
                rebind_rows([original], old, new, adjudications=[changed],
                            new_manifest_sha256='actual-manifest-bytes')


if __name__ == '__main__':
    unittest.main()
