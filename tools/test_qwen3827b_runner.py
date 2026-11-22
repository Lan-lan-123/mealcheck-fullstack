import json
import subprocess
import tempfile
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from types import SimpleNamespace

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'deploy/scripts'))
import run_qwen3827b_fullchain as runner

class Qwen27BRunnerTest(unittest.TestCase):
    def test_paired_random30_has_balanced_order_and_rejects_tampering(self):
        pool,_,_=runner.plan();source=runner.ROOT/'tools/evaluation/rag_timing50_v1.json'
        selection={'source':source.relative_to(runner.ROOT).as_posix(),'sourceSha256':runner.hashlib.sha256(source.read_bytes()).hexdigest(),'seed':20261029,'cases':runner.random.Random(20261029).sample(pool['cases'],30)}
        selected,schedule=runner.paired_schedule(pool,selection)
        self.assertEqual(len(selected['cases']),30);self.assertEqual(len(schedule),60)
        self.assertEqual(sum(schedule[i]['mode']=='FAST' for i in range(0,60,2)),15)
        self.assertEqual(sum(c['mode']=='REVIEWED' for c in schedule),30)
        bad=json.loads(json.dumps(selection));bad['cases'][0]['question']='changed'
        with self.assertRaises(AssertionError):runner.paired_schedule(pool,bad)
        with tempfile.TemporaryDirectory(dir=runner.ROOT/'logs') as temporary:
            saved=Path(temporary)/'selection.json';saved.write_text(json.dumps(selection),encoding='utf-8')
            with patch('sys.argv',['runner','--small','--questions-only','--provider','siliconflow','--paired-selection',str(saved),'--attempt-cap','300']),patch('builtins.print') as output:
                runner.main();result=json.loads(output.call_args.args[0])
        self.assertEqual(result['questions'],60);self.assertEqual(result['maximumProviderAttempts'],300)
        self.assertEqual(result['mealPipelineImages'],0);self.assertEqual(result['boundaryIds'],[])
    def test_paired50_preflight_preserves_every_question_and_approved_cap(self):
        pool,_,_=runner.plan();source=runner.ROOT/'tools/evaluation/rag_timing50_v1.json'
        selection={'source':source.relative_to(runner.ROOT).as_posix(),'sourceSha256':runner.hashlib.sha256(source.read_bytes()).hexdigest(),'seed':20261030,'plannedQuestions':50,'plannedBusinessRequests':100,'cases':runner.random.Random(20261030).sample(pool['cases'],50)}
        selected,schedule=runner.paired_schedule(pool,selection)
        self.assertEqual(len(selected['cases']),50);self.assertEqual(len(schedule),100)
        self.assertEqual(sum(schedule[i]['mode']=='FAST' for i in range(0,100,2)),25)
        self.assertEqual({c['id'] for c in selected['cases']},{c['id'] for c in pool['cases']})
        with tempfile.TemporaryDirectory(dir=runner.ROOT/'logs') as temporary:
            saved=Path(temporary)/'selection.json';saved.write_text(json.dumps(selection),encoding='utf-8')
            with patch('sys.argv',['runner','--small','--questions-only','--provider','siliconflow','--paired-selection',str(saved),'--attempt-cap','500']),patch('builtins.print') as output:
                runner.main();result=json.loads(output.call_args.args[0])
        self.assertEqual(result['questions'],100);self.assertEqual(result['maximumProviderAttempts'],500)
        self.assertEqual(result['temporaryAccounts'],7);self.assertEqual(result['mealPipelineImages'],0)
        for count in (0,51,True):
            with self.assertRaises(AssertionError):runner.paired_schedule(pool,dict(selection,plannedQuestions=count))
        with self.assertRaises(AssertionError):runner.paired_schedule(pool,dict(selection,plannedBusinessRequests=50))

    def test_repeat_preflight_is_twelve_questions_without_image_or_concurrency_expansion(self):
        import subprocess
        result=subprocess.run([sys.executable,str(Path(runner.__file__)), '--provider','siliconflow','--compare-from',
            str(runner.ROOT/'logs/backend/siliconflow-qwen3827b-fullchain-live-20261028-213759/results.json'),
            '--repeat-count','3'],capture_output=True,text=True,check=True)
        plan=json.loads(result.stdout)
        self.assertEqual((plan['questions'],plan['repeatCount'],plan['concurrency']),(12,3,1))
        self.assertEqual((plan['mealPipelineImages'],plan['boundaryIds']),(0,[]))
        self.assertTrue(plan['frozenEvidence']);self.assertEqual(plan['maximumProviderAttempts'],80)

    def test_questions_only_plan_cannot_dispatch_images_and_respects_remaining_cap(self):
        result=subprocess.run([sys.executable,str(runner.ROOT/'deploy/scripts/run_qwen3827b_fullchain.py'),'--small','--questions-only','--provider','siliconflow','--attempt-cap','64'],cwd=runner.ROOT,capture_output=True,text=True,check=True)
        plan=json.loads(result.stdout)
        self.assertEqual(plan['questions'],10);self.assertEqual(plan['mealPipelineImages'],0);self.assertEqual(plan['boundaryIds'],[])
        self.assertEqual(plan['maximumProviderAttempts'],64)

    def test_targeted_repeat_stays_inside_fixed_questions_and_never_sends_images(self):
        with patch('sys.argv',['runner','--small','--questions-only','--provider','siliconflow','--case-id','rag-128','--case-id','rag-187','--repeat-count','2','--attempt-cap','60']),patch('builtins.print') as output:
            runner.main();plan=json.loads(output.call_args.args[0])
        self.assertEqual(plan['questions'],4);self.assertEqual(plan['mealPipelineImages'],0);self.assertEqual(plan['boundaryIds'],[])
        self.assertEqual(plan['maximumProviderAttempts'],60)

    def test_fast_mode_has_a_bounded_smaller_read_only_pipeline(self):
        result=subprocess.run([sys.executable,str(runner.ROOT/'deploy/scripts/run_qwen3827b_fullchain.py'),'--small','--questions-only','--provider','siliconflow','--review-mode','FAST','--case-id','rag-070','--attempt-cap','22'],cwd=runner.ROOT,capture_output=True,text=True,check=True)
        plan=json.loads(result.stdout);self.assertEqual(plan['questions'],1);self.assertEqual(plan['maximumProviderAttempts'],22)
        self.assertEqual(plan['mealPipelineImages'],0)

    def test_frozen_cache_value_is_final_stdin_argument_and_ttl_is_preserved(self):
        with patch.object(runner.subprocess,'run',return_value=SimpleNamespace(stdout=b'OK\n')) as call:
            runner.write_frozen_cache('rag:search:test',b'[{"id":1}]',12345)
            command=call.call_args.args[0]
            self.assertEqual(command[-2:],['rag:search:test','12345'])
            self.assertEqual(call.call_args.kwargs['input'],b'[{"id":1}]')
            self.assertIn('ARGV[2]',command[8])
        with patch.object(runner.subprocess,'run',return_value=SimpleNamespace(stdout=b'ERR bad args\n')):
            with self.assertRaises(RuntimeError):runner.write_frozen_cache('rag:search:test',b'[]',123)

    def test_background_consumer_429_is_detected_without_assistant_request_ids(self):
        window=[{'operation':'meal-advice','status':429},{'operation':'meal-advice','status':429}]
        self.assertIsNone(runner.upstream_stop_reason([]))
        self.assertEqual(runner.provider_health_stop_reason([],window),'REPEATED_PROVIDER_FAILURE_STOP')
        self.assertIsNone(runner.provider_health_stop_reason([],[{'operation':'vision-recognition','status':200}]))
    def test_adaptive_question_groups_reduce_concurrency_without_losing_budget_reserve(self):
        self.assertEqual(runner.adaptive_group_size(300,[0]*50,24,5),5)
        self.assertEqual(runner.adaptive_group_size(390,[0]*50,24,5),3)
        self.assertEqual(runner.adaptive_group_size(460,[0]*50,24,5),1)
        self.assertEqual(runner.adaptive_group_size(477,[0]*50,24,5),0)
    def test_cancellation_only_uses_owned_ids_and_never_returns_tokens(self):
        seen=[]
        results=runner.cancel_owned_requests({'owned-a':'private-a','owned-b':'private-b'},lambda rid,token:seen.append((rid,token)) or True)
        self.assertEqual(set(seen),{('owned-a','private-a'),('owned-b','private-b')})
        self.assertTrue(all(r['cancelled'] for r in results))
        self.assertNotIn('private',json.dumps(results))
    def test_successful_sse_with_failed_model_stops_and_403_is_not_called_quota(self):
        result={'deliveryStatus':'COMPLETE','answerStatus':'DEGRADED','failureCode':'MODEL_QUOTA_EXHAUSTED'}
        self.assertEqual(runner.response_stop_reason(result),'PROVIDER_QUOTA_OR_CONFIGURATION_STOP')
        self.assertEqual(runner.upstream_stop_reason([{'kind':'http','status':403}]),'PROVIDER_ACCESS_OR_QUOTA_STOP')
        self.assertIsNone(runner.response_stop_reason({'deliveryStatus':'COMPLETE'}))
        self.assertIsNone(runner.upstream_stop_reason([{'kind':'http','status':400}]))
        self.assertEqual(runner.upstream_stop_reason([{'kind':'http','status':0}]*2),'REPEATED_PROVIDER_FAILURE_STOP')
    def test_image_success_with_rate_limited_advice_stops(self):
        self.assertEqual(runner.response_stop_reason({'status':'SUCCEEDED','result':{'adviceFailureCode':'MODEL_RATE_LIMITED'}}),'PROVIDER_RATE_LIMIT_STOP')
    def test_frozen_materials_and_target_model(self):
        questions,boundaries,images=runner.plan()
        self.assertEqual(runner.MODEL,'qwen3.8-27b')
        self.assertEqual(runner.RERANK_MODEL,'BAAI/bge-reranker-v2-m3')
        self.assertEqual(runner.RERANK_URL,'https://api.siliconflow.cn/v1/rerank')
        self.assertEqual((len(questions['cases']),len(boundaries['cases']),len(images)),(50,18,5))
        self.assertEqual(runner.CAP,500)

    def test_full_burst_reserves_every_possible_retry_before_launch(self):
        self.assertTrue(runner.can_admit(100,[0]*50,8))
        self.assertFalse(runner.can_admit(101,[0]*50,8))
        self.assertFalse(runner.can_admit(0,[93],8))

    def test_failures_stop_escalation_but_fallback_alone_does_not(self):
        good={'transportComplete':True,'usedRuleFallback':True}
        bad={'transportComplete':False}
        self.assertFalse(runner.repeated_failure_stop([good]*50))
        self.assertTrue(runner.repeated_failure_stop([bad]))
        self.assertTrue(runner.repeated_failure_stop([bad]+[good]*4))
        self.assertFalse(runner.repeated_failure_stop([bad]+[good]*49))
        self.assertTrue(runner.repeated_failure_stop([bad]*2+[good]*48))

    def test_complete_sse_preserves_unicode_and_exposes_provider_errors(self):
        body='event: complete\ndata: '+json.dumps({'summary':'按实际标签核对。\u0085保留'},ensure_ascii=False)+'\n\n'
        self.assertEqual(runner.stream_events(body.encode())[0]['data']['summary'],'按实际标签核对。\u0085保留')
        self.assertRaises(json.JSONDecodeError,runner.stream_events,b'event: complete\ndata: broken\n\n')

if __name__=='__main__':unittest.main()
