"""Check the provider fixture can exercise image, text and weekly parsers without silent fallback."""
import importlib.util
import json
from pathlib import Path
import unittest
import io
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('stub',Path(__file__).resolve().parents[1]/'deploy/scripts/model_stub.py')
stub=importlib.util.module_from_spec(spec); spec.loader.exec_module(stub)


class ModelFixtureTest(unittest.TestCase):
    def test_knowledge_claims_bind_only_actual_tool_passages(self):
        # JSON object field order is not guaranteed by the Java serializer.
        payload = {'results': [{'id': 17, 'passages': [{'id': 2, 'text': '主食可以搭配蔬菜。'}]}], 'count': 1}
        request = {'messages': [{'content': 'Return ONLY the following JSON protocol'},
                                {'role': 'tool', 'content': json.dumps(payload, separators=(',', ':'), ensure_ascii=False)}]}
        kind, content = stub.fixture_response(request)
        self.assertEqual(kind, 'assistant-knowledge')
        claim = json.loads(content)['claims'][0]
        self.assertEqual(claim['text'], payload['results'][0]['passages'][0]['text'])
        self.assertEqual(claim['evidence'], [{'sourceId': 17, 'passageIds': [2]}])
        request['messages'].pop()
        self.assertEqual(json.loads(stub.fixture_response(request)[1])['claims'], [])

    def test_evidence_review_fixture_never_approves_text_missing_from_bound_quote(self):
        for text, expected in [('来源原句', True), ('未提供的结论', False)]:
            candidate = {'quoteTexts': [{'id': 'q0', 'text': '来源原句'}], 'claims': [{'id': 'c0',
                'evidence': [{'id': 'c0.e0', 'quoteIds': ['q0']}], 'assertions': [{'id': 'c0.a0', 'text': text}]}]}
            prompt = '你是逐分句证据核验器\n回答：' + json.dumps(candidate, ensure_ascii=False) + '\n来源：[]'
            kind, content = stub.fixture_response({'messages': [{'content': prompt}]})
            self.assertEqual(kind, 'assistant-evidence-review')
            verdict = json.loads(content)
            self.assertEqual(verdict['claims'][0]['assertions'][0]['supported'], expected)
            self.assertEqual(verdict['answersQuestion'], expected)

    def test_embedding_fixture_uses_requested_dimensions_and_json_response(self):
        kind,content=stub.fixture_response({'input':'knowledge','dimensions':512,'model':'fixture'})
        self.assertEqual(kind,'embedding');self.assertEqual(len(content['data'][0]['embedding']),512)
        handler=object.__new__(stub.Handler);handler.wfile=io.BytesIO()
        handler.send_response=lambda status:self.assertEqual(status,200)
        handler.send_header=lambda *args:None;handler.end_headers=lambda:None
        with patch.object(stub.time,'sleep'):handler.send_fixture(content,False,0,429,0)
        self.assertEqual(json.loads(handler.wfile.getvalue())['data'][0]['embedding'][0],1)
    def test_streaming_fixture_has_delta_usage_and_termination(self):
        handler=object.__new__(stub.Handler);handler.wfile=io.BytesIO();headers=[]
        handler.send_response=lambda status:self.assertEqual(status,200)
        handler.send_header=lambda key,value:headers.append((key,value))
        handler.end_headers=lambda:None
        with patch.object(stub.time,'sleep'):
            handler.send_fixture('测试建议',False,0,429,0,stream=True)
        text=handler.wfile.getvalue().decode()
        self.assertIn(('Content-Type','text/event-stream'),headers)
        self.assertIn('"delta": {"content": "测试建议"}',text)
        self.assertIn('"usage":',text);self.assertTrue(text.endswith('data: [DONE]\n\n'))
    def test_image_parts_return_food_payload(self):
        kind,content=stub.fixture_response({'messages':[{'content':[{'type':'text','text':'recognize'},{'type':'image_url','image_url':{'url':'data:image/png;base64,test'}}]}]})
        self.assertEqual(kind,'visual'); self.assertTrue(json.loads(content)['foodImage'])

    def test_text_parts_do_not_accidentally_enter_image_path(self):
        kind,content=stub.fixture_response({'messages':[{'content':[{'type':'text','text':'summary suggestions weeklyTrend'}]}]})
        self.assertEqual(kind,'assistant'); self.assertTrue(json.loads(content)['suggestions'])

    def test_weekly_outputs_include_each_required_schema(self):
        for prompt,kind,field in [('饮食趋势分析 Agent','weekly-trend','findings'),('饮食教练 Agent','weekly-plan','nextWeekSuggestions'),('安全与一致性审查 Agent','weekly-review','approved')]:
            with self.subTest(kind=kind):
                actual,content=stub.fixture_response({'messages':[{'content':prompt}]})
                self.assertEqual(actual,kind); self.assertTrue(json.loads(content)[field])


if __name__=='__main__': unittest.main()
