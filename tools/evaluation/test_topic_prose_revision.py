import json
from pathlib import Path
import tempfile
import unittest

from revise_knowledge_topic_prose import load_prose, sentence_reuse


class TopicProseRevisionTest(unittest.TestCase):
    def test_literal_sources_cover_each_topic_once_without_fallback_templates(self):
        directory = Path(__file__).parent
        literal = load_prose(directory / 'knowledge_topic_revisions.md')
        retained = json.loads((directory / 'knowledge_longform_topic_details.json').read_text(encoding='utf-8'))
        retained_ids = {f'knowledge-{int(index):03d}' for index in retained}
        self.assertFalse(set(literal) & retained_ids)
        self.assertEqual(set(literal) | retained_ids, {f'knowledge-{index:03d}' for index in range(1, 201)})

    def test_duplicate_topic_ids_are_rejected_instead_of_silently_overwritten(self):
        body = '处理对象和实际配方应当有可靠的说明依据，不应将记录中缺失的数据任意填写为确定数值，以免改变其原本表达的含义。'
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'prose.md'
            path.write_text(('## knowledge-001\n**操作**\n\n' + body + '\n\n') * 2, encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Duplicate literal topic'):
                load_prose(path)

    def test_sentence_reuse_counts_distinct_topics_and_occurrences_separately(self):
        common = '不同记录日期中的实际用餐内容需要依据本人已经确认的事实分别描述'
        private = '这一条只用于演示同一个主题内部重复出现不会增加跨主题计数的情形'
        supplements = {f'topic-{index}': {'sections': [{'paragraphs': [common + '。' + private + '。' if index == 0 else common + '。']}]} for index in range(10)}
        report = sentence_reuse(supplements)
        self.assertEqual(report['sentenceOccurrences'], 11)
        self.assertEqual(report['sharedSentenceOccurrences'], 10)
        self.assertAlmostEqual(report['sharedOccurrenceRatio'], 10 / 11)
        one_topic = {'only-topic': {'sections': [{'paragraphs': [('。'.join([private] * 12)) + '。']}]} }
        self.assertEqual(sentence_reuse(one_topic)['sharedSentenceOccurrences'], 0)


if __name__ == '__main__':
    unittest.main()
