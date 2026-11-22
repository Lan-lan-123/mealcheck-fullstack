from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deploy/scripts'))
from analyze_max0902_fullchain import boundary_check,image_check,known_semantic_checks,http_window_metrics

class MaxAnalysisTest(unittest.TestCase):
    def test_expected_unclear_is_not_a_transport_quality_failure(self):
        row={'id':'hidden','kind':'boundary','httpStatus':400,'response':{'code':'IMAGE_UNCLEAR'}}
        label={'outcomes':['IMAGE_UNCLEAR'],'requireUnknownIdentity':True}
        self.assertTrue(boundary_check(row,label)['passed'])
        self.assertFalse(boundary_check(row,{'outcomes':['FOOD']})['passed'])
    def test_scoring_and_storage_are_separate_from_recognition(self):
        row={'id':'synth','transportComplete':True,'historyStatus':200,'persistedRecord':{'id':9,'score':90},
             'response':{'result':{'recordId':9,'recognition':{'foods':[{'name':'rice','category':'staple'}]},
                 'evaluation':{'score':90,'scoreBreakdown':{'total':90,'items':[{'points':50},{'points':40}]}}}}}
        label={'foods':[{'name':'rice','category':'staple'}]}
        good=image_check(row,label);self.assertTrue(good['breakdownConservesScore']);self.assertTrue(good['persistedRecordMatches'])
        row['response']['result']['evaluation']['scoreBreakdown']['total']=99
        self.assertFalse(image_check(row,label)['breakdownConservesScore'])
        self.assertFalse(image_check({'id':'synth','transportComplete':False,'response':{'errorCode':'REMOTE_TIMEOUT'}},label)['evaluable'])
    def test_known_regression_requires_core_caffeine_response(self):
        r={'id':'rag-070','answerability':'ANSWERABLE','response':{'summary':'是否适合要看个人敏感程度。'}}
        self.assertFalse(known_semantic_checks(r)['coreCaffeineJudgment'])
        r['response']['summary']='低因仍可能有咖啡因。'
        self.assertTrue(known_semantic_checks(r)['coreCaffeineJudgment'])
    def test_http_concurrency_does_not_include_dispatch_wait(self):
        rows=[{'requestStartedAt':'2026-10-26T00:00:00+00:00','finishedAt':'2026-10-26T00:00:10+00:00','providerAttempts':2}]
        events=[{'loggedAt':'2026-10-26T00:00:05Z','elapsedMicros':2_000_000,'dispatchWaitMicros':9_000_000,'operation':'vision','outcome':'success'},
                {'loggedAt':'2026-10-26T00:00:06Z','elapsedMicros':2_000_000,'dispatchWaitMicros':9_000_000,'operation':'advice','outcome':'success'}]
        self.assertEqual(http_window_metrics(events,rows)['httpInFlightPeak'],2)
        rows[0]['providerAttempts']=3
        with self.assertRaises(AssertionError):http_window_metrics(events,rows)

if __name__=='__main__':unittest.main()
