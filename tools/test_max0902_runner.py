import base64
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deploy/scripts'))
from run_max0902_fullchain import stream_events,can_admit,token_for,b64,plan

class MaxRunnerTest(unittest.TestCase):
    def test_utf8_sse_does_not_split_multibyte_chinese_or_unicode_nel(self):
        value={'text':'只有蔬菜，先核对搭配。\u0085保留文字'}
        raw=('event: delta\r\ndata: '+json.dumps(value,ensure_ascii=False)+'\r\n\r\n').encode()
        self.assertEqual(stream_events(raw),[{'event':'delta','data':value}])
    def test_heartbeat_and_complete_events(self):
        raw=b': heartbeat\n\nevent: queued\ndata: {"requestId":"id"}\n\nevent: complete\ndata: {"deliveryStatus":"COMPLETE"}\n\n'
        self.assertEqual([e['event'] for e in stream_events(raw)],['queued','complete'])
    def test_malformed_json_is_not_claimed_as_a_completed_response(self):
        with self.assertRaises(json.JSONDecodeError):stream_events(b'event: complete\ndata: broken\n\n')
    def test_attempt_admission_includes_prior_runs_and_inflight_reserve(self):
        self.assertTrue(can_admit(4+96,[0]*50,8))
        self.assertFalse(can_admit(4+97,[0]*50,8))
        self.assertFalse(can_admit(0,[93],8))
        self.assertTrue(can_admit(4+100,[0]*10,24))
    def test_fixture_tokens_preserve_real_generation_and_expiry_and_check_key(self):
        secret='x'*32;header={'alg':'HS256'};claims={'sub':'seed','uid':1,'ver':0,'auth_gen':'test-gen','exp':2000000000}
        unsigned=b64(json.dumps(header).encode())+'.'+b64(json.dumps(claims).encode())
        original=unsigned+'.'+b64(hmac.new(secret.encode(),unsigned.encode(),hashlib.sha256).digest())
        token=token_for(original,{'id':2,'username':'fixture'},secret)
        result=json.loads(base64.urlsafe_b64decode(token.split('.')[1]+'=='))
        self.assertEqual((result['uid'],result['auth_gen'],result['exp']),(2,'test-gen',2000000000))
        with self.assertRaises(AssertionError):token_for(original,{'id':2,'username':'fixture'},'wrong')
    def test_frozen_authorized_materials_validate_locally(self):
        questions,boundaries,images=plan()
        self.assertEqual((len(questions['cases']),len(boundaries['cases']),len(images)),(50,18,5))

if __name__=='__main__':unittest.main()
