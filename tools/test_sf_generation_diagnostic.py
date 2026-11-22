import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deploy/scripts'))
import diagnose_sf_generation as diagnostic

class DiagnosticTest(unittest.TestCase):
    def test_fixed_scope_and_no_thinking(self):
        cases=diagnostic.inputs()
        self.assertEqual(len(cases),6)
        self.assertEqual({c['id'] for c in cases},{'rag-070','rag-094'})
        self.assertTrue(all(c['body']['enable_thinking'] is False and c['body']['max_tokens']==1024 for c in cases))

    def test_stream_timing_and_split_events_without_persisting_reasoning(self):
        class Socket:
            def settimeout(self,value):assert 0<value<=59
        class Response:
            status=200
            def __init__(self):
                self.chunks=iter([b'data: {"choices":[{"delta":{"con',b'tent":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',b''])
            def getheader(self,name):return 'provider-trace'
            def read1(self,size):return next(self.chunks)
        class Connection:
            sock=Socket()
            def __init__(self,*args,**kwargs):pass
            def connect(self):pass
            def request(self,*args,**kwargs):pass
            def getresponse(self):return Response()
            def close(self):pass
        with patch.object(diagnostic.http.client,'HTTPSConnection',Connection):
            result=diagnostic.probe(diagnostic.inputs()[2],'private-test-key')
        self.assertTrue(result['complete']);self.assertEqual(result['answer'],'ok')
        self.assertIsNotNone(result['connectMs']);self.assertIsNotNone(result['headersWaitMs']);self.assertIsNotNone(result['firstContentAfterSendMs']);
        self.assertIsNotNone(result['firstContentMs']);self.assertNotIn('private-test-key',json.dumps(result))

    def test_trace_headers_are_bounded(self):
        self.assertIsNone(diagnostic.trace('bad header\nvalue'))
        self.assertIsNone(diagnostic.trace('x'*161))

if __name__=='__main__':unittest.main()
