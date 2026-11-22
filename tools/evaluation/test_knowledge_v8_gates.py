"""Regression coverage for the goals previously lost during deduplication."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from assemble_knowledge_v8 import BOUNDS, audit_prose, validate_counts, validate_plan
from install_knowledge_v8 import verify_editorial


def plan():
    rows = []
    for bucket, (count, minimum, maximum) in BOUNDS.items():
        for _ in range(count):
            index = len(rows) + 1
            rows.append({'topicId': f'knowledge-{index:03}', 'title': f'主题{index}',
                         'bucket': bucket, 'minChunks': minimum, 'maxChunks': maximum})
    return rows


class StrictLongformGatesTest(unittest.TestCase):
    def test_original_distribution_and_bounds_cannot_be_relaxed(self):
        rows = plan()
        validate_plan(rows)
        rows[0]['minChunks'] = 1
        with self.assertRaisesRegex(ValueError, 'cannot be relaxed'):
            validate_plan(rows)

    def test_one_chunk_per_topic_is_rejected_even_when_all_topics_exist(self):
        rows = plan()
        chunks = [{'title': row['title'], 'tokenCount': 400, 'contentHash': row['topicId'],
                   'chunkKey': row['topicId']} for row in rows]
        with self.assertRaisesRegex(ValueError, 'actual=1'):
            validate_counts(rows, chunks)

    def test_bounds_are_per_topic_not_just_a_global_total(self):
        rows = plan()
        chunks = []
        for row in rows:
            for index in range(row['minChunks']):
                key = row['topicId'] + '-' + str(index)
                chunks.append({'title': row['title'], 'tokenCount': 400, 'contentHash': key, 'chunkKey': key})
        validate_counts(rows, chunks)
        wrong = copy.deepcopy(chunks)
        wrong[0]['title'] = rows[1]['title']
        with self.assertRaisesRegex(ValueError, 'actual=1'):
            validate_counts(rows, wrong)

    def test_copy_paste_padding_is_rejected_before_publication(self):
        body = '不能通过重复同一整段说明来增加知识正文长度，应当逐个主题说明具有实际用途的不同知识内容。'
        supplements = {key: {'sections': [{'paragraphs': [body]}]} for key in ['a', 'b']}
        with self.assertRaisesRegex(ValueError, 'Duplicate paragraph'):
            audit_prose(supplements)

    def test_frequent_sentence_padding_is_rejected_even_with_distinct_paragraphs(self):
        common = '这一句话在不同主题中反复出现却没有提供各个主题真正需要的专有信息'
        supplements = {str(i): {'sections': [{'paragraphs': [common + '。' + str(i) + '独有后缀。']}]} for i in range(10)}
        with self.assertRaisesRegex(ValueError, 'Long sentences reused'):
            audit_prose(supplements)

    def test_installation_rejects_review_of_a_different_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            candidate = Path(temporary)
            (candidate / 'candidate-diet-guides.md').write_bytes(b'candidate')
            with self.assertRaisesRegex(ValueError, 'different corpus'):
                verify_editorial(candidate, {'corpusSha256': 'wrong'})

    def test_review_must_cover_every_topic_and_match_unchanged_manuscripts(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'candidate-diet-guides.md').write_bytes(b'candidate')
            manuscript_hash = hashlib.sha256(b'article').hexdigest()
            rows = []
            for index in range(1, 201):
                topic_id = f'knowledge-{index:03}'
                (directory / (topic_id + '.md')).write_bytes(b'article')
                rows.append({'topicId': topic_id, 'manuscriptSha256': manuscript_hash,
                             'decision': 'ACCEPT', 'reviewedKnowledgeUnits': ['specific content']})
            review = {'corpusSha256': hashlib.sha256(b'candidate').hexdigest(),
                      'decision': 'APPROVED_WITH_INDEPENDENT_REVIEW_PENDING',
                      'topics': rows, 'independentlyReviewed': False}
            with patch('install_knowledge_v8.MANUSCRIPTS', directory):
                verify_editorial(directory, review)
                review['topics'] = rows[:-1]
                with self.assertRaisesRegex(ValueError, 'all 200'):
                    verify_editorial(directory, review)
                review['topics'] = rows
                (directory / 'knowledge-200.md').write_bytes(b'changed after review')
                with self.assertRaisesRegex(ValueError, 'stale'):
                    verify_editorial(directory, review)


if __name__ == '__main__':
    unittest.main()
