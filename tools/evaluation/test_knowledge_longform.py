import copy
from collections import Counter
import json
from pathlib import Path
import unittest

from prepare_knowledge_longform import BUCKETS, CORPUS, plan, prompt
from generate_knowledge_longform import assess, render
from generate_knowledge_longform import unsupported_medical_terms
from generate_knowledge_longform import decode_supplement, accounted_usage
from migrate_rag_longform_labels import migrate, normalized
import hashlib
import io
import tempfile
from unittest.mock import patch
from urllib.error import HTTPError
import generate_knowledge_longform as generation
import split_knowledge_generation as parallel_generation


class KnowledgeLongformTest(unittest.TestCase):
    def setUp(self):
        self.rows = plan(CORPUS.read_text(encoding='utf-8'))

    def test_distribution_and_original_topics_are_frozen(self):
        self.assertEqual(len(self.rows), 200)
        self.assertEqual(len({row['title'] for row in self.rows}), 200)
        self.assertEqual(Counter(row['bucket'] for row in self.rows),
                         {key: value[0] for key, value in BUCKETS.items()})
        self.assertEqual(sum(row['minChunks'] for row in self.rows), 1220)
        self.assertEqual(sum(row['maxChunks'] for row in self.rows), 1880)
        self.assertEqual(self.rows[43]['bucket'], 'SHORT')
        self.assertEqual(self.rows[63]['bucket'], 'EXTENDED')
        self.assertEqual(self.rows[191]['bucket'], 'EXTENDED')

    def test_source_guard_distinguishes_claims_from_explicit_limits(self):
        row = {'title': '主食', 'originalBody': '可以轮换。'}
        def supplement(text):
            return {'sections': [{'heading': '边界', 'paragraphs': [text]}]}
        self.assertEqual(unsupported_medical_terms(row, supplement('无法预测个体食用后的血糖变化。'), []), [])
        self.assertEqual(unsupported_medical_terms(row, supplement('这种主食会稳定血糖，改善学习能力。'), []), ['血糖'])
        self.assertEqual(unsupported_medical_terms(row, supplement('不要引入新的生理机制解释，如更正后血糖会平稳。'), []), [])
        self.assertEqual(unsupported_medical_terms(row, supplement('若用户有糖尿病，应咨询专业医生，不依赖通用选餐原则。'), []), [])

    def test_repeated_section_keys_are_preserved_without_silent_data_loss(self):
        decoded = decode_supplement('{"sections":[{"heading":"甲","paragraphs":["第一段"],"heading":"乙","paragraphs":["第二段"]}]}')
        self.assertEqual([section['heading'] for section in decoded['sections']], ['甲', '乙'])
        self.assertEqual(decoded['sections'][0]['paragraphs'], ['第一段'])
        with self.assertRaises(ValueError):
            decode_supplement('{"sections":[],"sections":[]}')
        self.assertEqual(decode_supplement('{"sections":[{"paragraphs":["正文"],"heading":"甲"}]}')['sections'][0]['heading'], '甲')
        self.assertEqual(decode_supplement('**第一节**\n\n第一段。\n\n第二段。')['sections'][0]['paragraphs'], ['第一段。', '第二段。'])

    def test_token_details_cannot_hide_reasoning_usage(self):
        self.assertEqual(accounted_usage({'total_tokens': 100, 'prompt_tokens': 50, 'completion_tokens': 50,
                                         'completion_tokens_details': {'text_tokens': 50, 'reasoning_tokens': 10}}, 500), 110)
        self.assertEqual(accounted_usage({}, 500), 500)
        self.assertEqual(accounted_usage({'total_tokens': 0}, 500), 500)

    def test_render_preserves_source_and_rejects_fake_links_or_duplicate_padding(self):
        row = self.rows[0]
        paragraph = '本主题的案例应当明确区分已经观察到的餐食事实与仍然缺失的份量条件，先记录可以确认的内容，再解释哪些结论需要额外信息支持。'
        supplement = {'sections': [{'heading': '记录练习', 'paragraphs': [paragraph]}]}
        article = render(row, supplement)
        self.assertIn(row['originalBody'], article)
        for bad in [paragraph + 'https://example.com/fake', paragraph + '\n## 偷换主题']:
            invalid = copy.deepcopy(supplement)
            invalid['sections'][0]['paragraphs'] = [bad]
            with self.assertRaises(ValueError):
                render(row, invalid)
        invalid = copy.deepcopy(supplement)
        invalid['sections'][0]['paragraphs'].append(paragraph)
        with self.assertRaises(ValueError):
            render(row, invalid)

    def test_assessment_uses_splitter_output_not_character_estimates(self):
        class FakeSplitter:
            def split(self, markdown):
                return {'chunks': [{'tokenCount': 400, 'contentHash': 'only-one'}], 'tokenCount': 400}
        _, _, issues = assess(self.rows[0], {'sections': [{'heading': '案例', 'paragraphs': ['实例' * 40]}]}, FakeSplitter())
        self.assertTrue(any(issue.startswith('chunk_count=') for issue in issues))

    def test_prompt_carries_specific_topic_and_source_without_gold_labels(self):
        messages = prompt(self.rows[63])
        request = json.loads(messages[1]['content'])
        self.assertEqual(request['topic'], '营养标签的计量单位')
        self.assertEqual(request['productionSplit']['requiredChunks'], [12, 20])
        self.assertNotIn('evidenceTargets', request)
        self.assertIn('不可添加诊断', messages[0]['content'])

    def test_evidence_migration_uses_real_quotes_and_preserves_or_alternatives(self):
        self.assertEqual(normalized('\u3000甲\u3000'), '\u3000甲\u3000')
        chunks = []
        for title, content in [('甲', '原始证据甲。扩展正文。'), ('乙', '原始证据乙。其他正文。')]:
            identity = hashlib.sha256((title + '\n\n' + content).encode()).hexdigest()
            chunks.append({'title': title, 'content': content, 'contentHash': identity, 'chunkKey': title})
        row = {'id': 'rag-001', 'question': '原问题', 'annotation': {},
               'evidenceTargets': [{'knowledgeId': old, 'title': title, 'evidenceQuotes': [quote], 'relevance': 2}
                                   for old, title, quote in [('old-a', '甲', '原始证据甲。'), ('old-b', '乙', '原始证据乙。')]],
               'requiredEvidenceGroups': [['old-a', 'old-b']]}
        actual = migrate([row], {'chunks': chunks, 'corpusSha256': 'new'})[0]
        self.assertEqual(actual['question'], row['question'])
        self.assertEqual(actual['requiredEvidenceGroups'], [[chunk['contentHash'] for chunk in chunks]])
        self.assertEqual(actual['annotation']['coverage'], 'PRESERVED_ORIGINAL_ANCHORS_ONLY')
        self.assertFalse(actual['annotation']['independentlyAnnotated'])
        self.assertEqual(row['evidenceTargets'][0]['knowledgeId'], 'old-a')
        invalid = copy.deepcopy(row)
        invalid['evidenceTargets'][0]['evidenceQuotes'] = ['正文没有的证据']
        with self.assertRaises(ValueError):
            migrate([invalid], {'chunks': chunks, 'corpusSha256': 'new'})

    def generation_fixture(self, directory):
        root = Path(directory)
        corpus = root / 'corpus.md'
        corpus.write_text('original', encoding='utf-8')
        batch = root / 'logs/batch'
        batch.mkdir(parents=True)
        fixture = {'corpusSha256': hashlib.sha256(corpus.read_bytes()).hexdigest(),
                   'topics': [self.rows[43]], 'minimumTotalChunks': 2, 'maximumTotalChunks': 3}
        (batch / 'plan.json').write_text(json.dumps(fixture), encoding='utf-8')
        return root, corpus, batch

    def test_budget_denial_sends_no_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root, corpus, batch = self.generation_fixture(directory)
            with patch.object(generation, 'ROOT', root), patch.object(generation, 'CORPUS', corpus), \
                 patch.object(generation, 'environment', return_value=({'MEALCHECK_AI_API_KEY': 'secret'}, 'https://example.invalid', 'mock')), \
                 patch.object(generation, 'Splitter'), patch.object(generation, 'urlopen') as remote:
                generation.generate(batch, True, 1, 200)
                remote.assert_not_called()
            state = json.loads((batch / 'generation.json').read_text())
            self.assertEqual(state['status'], 'BUDGET_STOP')
            self.assertEqual(state['calls'], [])

    def test_user_pause_blocks_calls_before_reading_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root, corpus, batch = self.generation_fixture(directory)
            marker = root / 'logs/backend/knowledge-longform-generation-hold.json'
            marker.parent.mkdir(parents=True)
            marker.write_text('{"status":"PAUSED_BY_USER"}', encoding='utf-8')
            with patch.object(generation, 'ROOT', root), patch.object(generation, 'CORPUS', corpus), \
                 patch.object(generation, 'environment') as credentials, patch.object(generation, 'urlopen') as remote:
                with self.assertRaisesRegex(ValueError, '暂不继续调用'):
                    generation.generate(batch, True, 3000000, 200)
                credentials.assert_not_called()
                remote.assert_not_called()

    def test_provider_error_stops_with_reserved_usage_and_no_credential_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root, corpus, batch = self.generation_fixture(directory)
            with patch.object(generation, 'ROOT', root), patch.object(generation, 'CORPUS', corpus), \
                 patch.object(generation, 'environment', return_value=({'MEALCHECK_AI_API_KEY': 'never-save-this-key'}, 'https://example.invalid', 'mock')), \
                 patch.object(generation, 'Splitter'), \
                 patch.object(generation, 'urlopen', side_effect=HTTPError('https://example.invalid', 429, 'private body', {}, None)) as remote:
                with self.assertRaises(RuntimeError):
                    generation.generate(batch, True, 100000, 200)
                self.assertEqual(remote.call_count, 1)
                with self.assertRaises(ValueError):
                    generation.generate(batch, True, 100000, 200)
                self.assertEqual(remote.call_count, 1)
            state = json.loads((batch / 'generation.json').read_text())
            self.assertEqual(state['status'], 'PROVIDER_ERROR')
            self.assertGreater(state['calls'][0]['accountedTokens'], 0)
            for evidence in batch.rglob('*.json'):
                self.assertNotIn('never-save-this-key', evidence.read_text(encoding='utf-8'))

    def test_unknown_usage_keeps_full_reservation(self):
        class FakeSplitter:
            def __init__(self, batch):
                pass
            def split(self, markdown):
                return {'chunks': [{'tokenCount': 400, 'contentHash': 'a'}, {'tokenCount': 400, 'contentHash': 'b'}],
                        'tokenCount': 750}
            def close(self):
                pass
        response = {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({
            'sections': [{'heading': '已知条件', 'paragraphs': ['仅根据已经确认的就餐信息说明实际的饮品选择和记录过程。' * 4]}]})}}]}
        with tempfile.TemporaryDirectory() as directory:
            root, corpus, batch = self.generation_fixture(directory)
            with patch.object(generation, 'ROOT', root), patch.object(generation, 'CORPUS', corpus), \
                 patch.object(generation, 'environment', return_value=({'MEALCHECK_AI_API_KEY': 'secret'}, 'https://example.invalid', 'mock')), \
                 patch.object(generation, 'Splitter', FakeSplitter), \
                 patch.object(generation, 'urlopen', return_value=io.BytesIO(json.dumps(response).encode())):
                generation.generate(batch, True, 100000, 200)
            call = json.loads((batch / 'generation.json').read_text())['calls'][0]
            self.assertEqual(call['accountedTokens'], call['inputAllowance'] + call['maximumOutputTokens'])

    def test_parallel_allocations_preserve_one_global_budget_and_disjoint_scopes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent = root / 'logs/parent'
            parent.mkdir(parents=True)
            plan = {'corpusSha256': 'source', 'topics': self.rows}
            (parent / 'plan.json').write_text(json.dumps(plan), encoding='utf-8')
            state = {'status': 'REVIEW_CHECKPOINT', 'drafts': {row['topicId']: {'status': 'VALID'} for row in self.rows[:10]},
                     'calls': [{'accountedTokens': 100} for _ in range(10)]}
            (parent / 'generation.json').write_text(json.dumps(state), encoding='utf-8')
            for filename in ['before-diet-guides.md', 'before-rag-cases.json', 'runtime.json']:
                (parent / filename).write_text('{}', encoding='utf-8')
            with patch.object(parallel_generation, 'ROOT', root):
                parallel_generation.prepare(parent)
            manifest = json.loads((parent / 'parallel-plan.json').read_text(encoding='utf-8'))
            allocations = [json.loads((Path(child['path']) / 'allocation.json').read_text()) for child in manifest['children']]
            self.assertEqual(manifest['parentCallsAlreadySent'] + sum(child['maxCalls'] for child in manifest['children']), 200)
            self.assertEqual(manifest['parentConservativeTokens'] + sum(child['tokenBudget'] for child in manifest['children']), 3000000)
            self.assertFalse(set(allocations[0]['topicIds']).intersection(allocations[1]['topicIds']))
            self.assertEqual(sum(len(allocation['topicIds']) for allocation in allocations), 190)
            with patch.object(generation, 'ROOT', root), patch.object(generation, 'urlopen') as remote:
                with self.assertRaises(ValueError):
                    generation.generate(parent, True, 3000000, 200)
                remote.assert_not_called()

    def test_shared_stop_preserves_first_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'stop.json'
            allocation = {'sharedStopFile': str(path)}
            generation.signal_stop(allocation, 'PROVIDER_ERROR', Path(directory), httpStatus=429)
            generation.signal_stop(allocation, 'CONTENT_REVIEW_REQUIRED', Path(directory), topicId='knowledge-001')
            self.assertEqual(json.loads(path.read_text())['status'], 'PROVIDER_ERROR')


if __name__ == '__main__':
    unittest.main()
