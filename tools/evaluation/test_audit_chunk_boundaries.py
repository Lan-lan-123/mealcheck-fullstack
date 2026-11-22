import copy
import hashlib
import unittest

from audit_chunk_boundaries import compare, identity, ordered_coverage


class ChunkBoundaryAuditTest(unittest.TestCase):
    def manifest(self, body, pieces, title='主题', max_tokens=500):
        corpus = ('## ' + title + '\n' + body).encode('utf-8')
        chunks = [{'title': title, 'content': piece, 'contentHash': identity(title, piece),
                   'chunkIndex': index, 'tokenCount': len(piece)} for index, piece in enumerate(pieces)]
        return corpus, {'corpusSha256': hashlib.sha256(corpus).hexdigest(),
                        'chunkCount': len(chunks), 'topicCount': 1, 'minTokens': 1,
                        'maxTokens': max_tokens, 'chunks': chunks}

    def test_alternative_repeated_offsets_are_not_simultaneous_coverage(self):
        corpus, manifest = self.manifest('甲乙。甲乙。', ['甲乙。'])
        result = compare(manifest, manifest, corpus)
        self.assertFalse(result['after']['continuityAndCoveragePassed'])
        self.assertIn('NO_ORDERED_GAP_FREE_SOURCE_COVERAGE', [e['kind'] for e in result['after']['errors']])

    def test_ordered_repeated_text_can_use_later_match_instead_of_first(self):
        corpus, manifest = self.manifest('甲乙。中间。甲乙。', ['甲乙。中间。', '甲乙。'])
        result = compare(manifest, manifest, corpus)
        self.assertTrue(result['passed'])
        self.assertEqual(result['after']['topics'][0]['ambiguousChunkLocations'], 1)
        self.assertTrue(ordered_coverage([[(0, 6)], [(0, 3), (6, 9)]], 9))

    def test_gap_wrong_topic_and_forged_identity_are_rejected(self):
        corpus, manifest = self.manifest('第一句。第二句。第三句。', ['第一句。', '第三句。'])
        self.assertFalse(compare(manifest, manifest, corpus)['passed'])
        changed = copy.deepcopy(manifest)
        changed['chunks'][0]['content'] = '别的主题。'
        kinds = {e['kind'] for e in compare(manifest, changed, corpus)['after']['errors']}
        self.assertIn('NOT_CONTIGUOUS_IN_SAME_TOPIC', kinds)
        self.assertIn('CONTENT_IDENTITY_MISMATCH', kinds)

    def test_clean_sentences_replace_mid_sentence_and_orphan_heading(self):
        body = '第一句包括条件。\n\n**下一节**\n\n第二句保留限制。'
        corpus, before = self.manifest(body, ['第一句包括条件。\n\n**下一节**', '括条件。\n\n**下一节**\n\n第二句保留限制。'])
        _, after = self.manifest(body, ['第一句包括条件。', '**下一节**\n\n第二句保留限制。'])
        result = compare(before, after, corpus)
        self.assertTrue(result['passed'])
        self.assertEqual(result['before']['diagnosticCounts']['TRAILING_HEADING'], 1)
        self.assertEqual(result['before']['diagnosticCounts']['MID_SENTENCE_START'], 1)
        self.assertEqual(result['after']['diagnosticCounts'], {})

    def test_long_unpunctuated_sentence_fallback_is_distinct_from_avoidable_fragment(self):
        body = '开头' + '甲' * 600 + '结束。'
        corpus, manifest = self.manifest(body, [body[:500], body[450:]])
        result = compare(manifest, manifest, corpus)
        self.assertTrue(result['passed'])
        self.assertEqual(result['after']['diagnosticCounts']['OVERSIZED_SENTENCE_FALLBACK'], 1)
        self.assertEqual(result['after']['fixableDiagnosticCount'], 0)

    def test_whitespace_formatting_is_not_content_loss_and_corpus_hash_must_match(self):
        body = '第一句。\n\n第二句。'
        corpus, manifest = self.manifest(body, ['第一句。\n第二句。'])
        self.assertTrue(compare(manifest, manifest, corpus)['passed'])
        changed = copy.deepcopy(manifest)
        changed['corpusSha256'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'fingerprint'):
            compare(manifest, changed, corpus)

    def test_over_budget_chunk_is_not_accepted_even_if_coverage_is_complete(self):
        corpus, manifest = self.manifest('完整正文。', ['完整正文。'], max_tokens=3)
        self.assertIn('TOKEN_BUDGET_VIOLATION', {e['kind'] for e in compare(manifest, manifest, corpus)['after']['errors']})

    def test_full_coverage_does_not_hide_a_mid_sentence_end(self):
        corpus, manifest = self.manifest('前一句。后一句。', ['前一句。后', '后一句。'])
        result = compare(manifest, manifest, corpus)
        self.assertTrue(result['after']['continuityAndCoveragePassed'])
        self.assertFalse(result['passed'])
        self.assertEqual(result['after']['diagnosticCounts']['MID_SENTENCE_END'], 1)


if __name__ == '__main__':
    unittest.main()
