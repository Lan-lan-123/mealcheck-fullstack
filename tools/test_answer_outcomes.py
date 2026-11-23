import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deploy/scripts'))
from analyze_qwen3827b_fullchain import answer_outcomes

class AnswerOutcomesTest(unittest.TestCase):
    def test_successful_delivery_is_not_counted_as_a_complete_model_answer(self):
        rows=[{'kind':'rag','transportComplete':True,'response':r} for r in (
            {'answerStatus':'COMPLETE'},
            {'answerStatus':'PARTIAL','answerReasonCodes':['UNSUPPORTED_STRENGTH']},
            {'answerStatus':'DEGRADED','failureCode':'MODEL_TIMEOUT'},
            {})]
        result=answer_outcomes(rows)
        self.assertEqual(result['statuses'],{'COMPLETE':1,'PARTIAL':1,'DEGRADED':1,'UNKNOWN':1})
        self.assertEqual(result['partialReasons'],{'UNSUPPORTED_STRENGTH':1})
        self.assertEqual(result['failureCodes'],{'MODEL_TIMEOUT':1})

if __name__=='__main__': unittest.main()
