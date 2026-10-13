"""Check the provider fixture can exercise image, text and weekly parsers without silent fallback."""
import importlib.util
import json
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('stub',Path(__file__).resolve().parents[1]/'deploy/scripts/model_stub.py')
stub=importlib.util.module_from_spec(spec); spec.loader.exec_module(stub)


class ModelFixtureTest(unittest.TestCase):
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
