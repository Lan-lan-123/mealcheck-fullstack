import unittest
from pilot_framework_headings import detach_heading, transfer


class HeadingPilotTest(unittest.TestCase):
    def test_only_standalone_trailing_heading_is_detached(self):
        self.assertEqual(detach_heading('正文。\n\n**下一节**'), ('正文。', '**下一节**'))
        self.assertEqual(detach_heading('正文含**重点**。'), ('正文含**重点**。', ''))

    def test_move_stays_within_topic_and_preserves_heading_and_body_order(self):
        chunks = [{'title': '甲', 'content': '正文。\n\n**标题**'},
                  {'title': '甲', 'content': '解释。'}, {'title': '乙', 'content': '其他。\n\n**尾标题**'}]
        rows, count = transfer(chunks)
        self.assertEqual(count, 1)
        self.assertEqual([r['content'] for r in rows], ['正文。', '**标题**\n\n解释。', '其他。\n\n**尾标题**'])

    def test_heading_only_chunk_is_not_emitted_as_empty_body(self):
        rows, count = transfer([{'title': '甲', 'content': '**标题**'}, {'title': '甲', 'content': '解释。'}])
        self.assertEqual(rows, [{'title': '甲', 'content': '**标题**\n\n解释。'}])
        self.assertEqual(count, 1)


if __name__ == '__main__':
    unittest.main()
