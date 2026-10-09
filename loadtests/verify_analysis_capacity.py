"""Verify the real k6 script against a loopback-only fake HTTP API; no real accounts or AI calls."""
import json
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]


class CapacityScriptTest(unittest.TestCase):
    def run_case(self, behavior, script='analysis-capacity.js', cohorts=False, **settings):
        tasks = {}
        weekly_tokens = set()
        lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, status, body):
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                try: self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                if self.path.startswith('/api/reports/weekly/jobs'):
                    token=self.headers['Authorization']
                    if token not in ('Bearer fake-account-c','Bearer fake-account-d'):
                        self.reply(401,{}); return
                    weekly_tokens.add(token)
                    self.reply(202,{'id':1 if token.endswith('-c') else 2,'status':'PENDING'}); return
                if self.path=='/api/assistant/ask':
                    self.reply(200,{'answer':'测试建议：补充蔬菜。'}); return
                key = self.headers['Idempotency-Key']
                with lock:
                    if key in tasks:
                        self.reply(409, {})
                        return
                    tasks[key] = {'polls': 0, 'token': self.headers['Authorization']}
                if behavior == 'rejected': self.reply(429, {}); return
                if behavior == 'unknown': self.reply(503, {}); return
                self.reply(202, {'key': key, 'status': 'PENDING', 'result': None})
            def do_GET(self):
                if self.path.startswith('/api/meals?'):
                    self.reply(401 if behavior == 'bad_auth' else 200, {'totalElements': 1000,'content':[{'id':1}]})
                    return
                if self.path.startswith('/api/reports/weekly/jobs/'):
                    status='STALE' if behavior=='weekly_stale' else 'SUCCEEDED'
                    self.reply(200,{'id':int(self.path.rsplit('/',1)[-1]),'status':status,
                        'report':{'reportText':'测试周报：规律饮食。','generationMode':'RULES' if behavior=='weekly_fallback' else 'MULTI_AGENT','reviewStatus':'APPROVED_AI'}})
                    return
                key = self.path.rsplit('/', 1)[-1]
                with lock:
                    task = tasks[key]
                    task['polls'] += 1
                if behavior == 'transient' and task['polls'] == 1:
                    self.reply(503, {}); return
                status = {'failed': 'FAILED', 'retryable': 'RETRYABLE', 'timeout': 'PROCESSING'}.get(behavior, 'SUCCEEDED')
                if behavior == 'partial_failure' and key.endswith('-0'): status = 'FAILED'
                if behavior == 'bad_json': self.reply(200, {'unexpected': True}); return
                self.reply(200, {'key': key, 'status': status,
                                 'result': None if behavior == 'missing_result' else {'recordId': 123}})

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        env = os.environ.copy()
        env.update({'MODE': 'burst', 'BURST_TASKS': '4', 'MAX_VUS': '4', 'PREALLOCATED_VUS': '4',
                    'DURATION': '1s', 'TASK_TIMEOUT_SECONDS': '1', 'HTTP_TIMEOUT_SECONDS': '.5',
                    'POLL_SECONDS': '.05', 'E2E_P95_MS': '1000', 'MAX_FAILURE_RATE': '.01',
                    'MIN_COMPLETED': '1', 'RATE_PER_MINUTE': '5', 'RUN_ID': 'verification-run',
                    'BASE_URL': f'http://127.0.0.1:{server.server_port}'})
        env.update(settings)
        results = ROOT / 'loadtests' / 'results'
        results.mkdir(exist_ok=True)
        try:
            with tempfile.TemporaryDirectory(dir=results) as directory:
                folder = Path(directory)
                (folder / 'accounts.json').write_text(json.dumps([{'token': 'fake-account-a'}, {'token': 'fake-account-b'}]))
                if cohorts:
                    writers=[{'token':'fake-account-a'},{'token':'fake-account-b'}]
                    weekly=[{'token':'fake-account-c'},{'token':'fake-account-d'}]
                    (folder/'accounts.json').write_text(json.dumps(writers+weekly))
                    (folder/'analysis.json').write_text(json.dumps(writers))
                    (folder/'weekly.json').write_text(json.dumps(weekly))
                    env.update(ANALYSIS_ACCOUNTS_FILE=str(folder/'analysis.json'),WEEKLY_ACCOUNTS_FILE=str(folder/'weekly.json'),
                               WEEKLY_TASKS='2',ASSISTANT_RATE='60',VUS='2')
                (folder / 'image.jpg').write_bytes(b'fake-image-only-for-script-verification')
                env.update({'ACCOUNTS_FILE': str(folder / 'accounts.json'), 'IMAGE_FILE': str(folder / 'image.jpg'),
                            'RESULT_FILE': str(folder / 'result.json')})
                process = subprocess.run([shutil.which('k6') or 'k6', 'run', '--quiet', str(ROOT / 'loadtests' / script)],
                                         cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
                report = json.loads((folder / 'result.json').read_text())['capacity'] if (folder / 'result.json').exists() else None
                if cohorts and report:
                    report['weeklyTokens']=sorted(weekly_tokens)
                    report['mixedMetrics']=json.loads((folder/'result.json').read_text())['k6']['metrics']
                return process, report, tasks
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_success_unique_tasks_and_account_rotation(self):
        process, report, tasks = self.run_case('success')
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertTrue(report['passed'])
        self.assertEqual(report['succeeded'], 4)
        self.assertEqual(len(tasks), 4)
        self.assertEqual(len({task['token'] for task in tasks.values()}), 2)
        self.assertEqual(report['unfinished'], 0)

    def test_ai_mixed_uses_independent_weekly_accounts_and_real_generation_markers(self):
        process,report,tasks=self.run_case('success',script='capacity-ai-mixed.js',cohorts=True,
                                         MODE='arrival',DURATION='5s',RATE_PER_MINUTE='60')
        self.assertEqual(process.returncode,0,process.stdout+process.stderr)
        self.assertTrue(report['passed'])
        self.assertEqual(set(report['weeklyTokens']),{'Bearer fake-account-c','Bearer fake-account-d'})
        self.assertTrue(tasks)
        self.assertTrue(all(v['token'] in ('Bearer fake-account-a','Bearer fake-account-b') for v in tasks.values()))
        self.assertEqual(report['mixedMetrics']['weekly_multiagent_generated']['values']['count'],2)

    def test_ai_mixed_stale_and_fallback_do_not_count_as_success(self):
        for behavior in ('weekly_stale','weekly_fallback'):
            with self.subTest(behavior=behavior):
                started=time.monotonic()
                process,report,_=self.run_case(behavior,script='capacity-ai-mixed.js',cohorts=True,
                                              MODE='arrival',DURATION='5s',RATE_PER_MINUTE='60')
                self.assertNotEqual(process.returncode,0)
                self.assertFalse(report['passed'])
                self.assertLess(time.monotonic()-started,20)
                self.assertEqual(report['mixedMetrics']['weekly_success']['values']['rate'],0)
                self.assertEqual(report['mixedMetrics']['weekly_invalidated']['values']['count'],2 if behavior=='weekly_stale' else 0)

    def test_failure_states_do_not_pass(self):
        for behavior, counter in [('failed', 'failed'), ('retryable', 'failed'), ('rejected', 'rejected'),
                                  ('unknown', 'unknownSubmissions'), ('bad_json', 'unresolved'),
                                  ('missing_result', 'unresolved'), ('timeout', 'timedOut')]:
            with self.subTest(behavior=behavior):
                process, report, _ = self.run_case(behavior)
                self.assertNotEqual(process.returncode, 0)
                self.assertNotIn('hint="script exception"', process.stderr)
                self.assertFalse(report['passed'])
                self.assertEqual(report[counter], 4)
                self.assertEqual(report['succeeded'], 0)
                self.assertIsNone(report['successfulE2eMs'])

    def test_transient_poll_error_can_recover(self):
        process, report, _ = self.run_case('transient')
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertEqual(report['pollErrors'], 4)
        self.assertEqual(report['succeeded'], 4)

    def test_accepted_failure_cannot_hide_in_overload_rejection_budget(self):
        process, report, _ = self.run_case('partial_failure', MAX_FAILURE_RATE='.8', REQUIRE_ACCEPTED_SUCCESS='true')
        self.assertNotEqual(process.returncode, 0)
        self.assertFalse(report['passed'])
        self.assertEqual(report['accepted'], 4)
        self.assertEqual(report['succeeded'], 3)
        self.assertEqual(report['failed'], 1)
        self.assertIn('analysis_failed: count==0', report['failedThresholds'])

    def test_arrival_rate_and_dropped_iterations(self):
        process, report, _ = self.run_case('timeout', MODE='arrival', RATE_PER_MINUTE='1200', MAX_VUS='1', PREALLOCATED_VUS='1')
        self.assertNotEqual(process.returncode, 0)
        self.assertGreater(report['droppedIterations'], 0)
        self.assertFalse(report['passed'])

    def test_arrival_success(self):
        process, report, tasks = self.run_case('success', MODE='arrival', RATE_PER_MINUTE='240')
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertTrue(report['passed'])
        self.assertEqual(report['droppedIterations'], 0)
        self.assertGreaterEqual(len(tasks), 3)

    def test_minimum_success_samples_is_enforced(self):
        process, report, _ = self.run_case('success', MIN_COMPLETED='5')
        self.assertNotEqual(process.returncode, 0)
        self.assertFalse(report['passed'])

    def test_auth_failure_stops_before_upload(self):
        process, report, tasks = self.run_case('bad_auth')
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(len(tasks), 0)
        if report: self.assertFalse(report['passed'])


class ModelStubTest(unittest.TestCase):
    def test_delay_health_and_injected_failure(self):
        spec = importlib.util.spec_from_file_location('capacity_model_stub', ROOT / 'deploy/scripts/model_stub.py')
        stub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(stub)
        stub.DELAY_SECONDS = .02
        stub.ERROR_EVERY_N = 2
        stub.ERROR_STATUS = 429
        server = ThreadingHTTPServer(('127.0.0.1', 0), stub.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            with urllib.request.urlopen(base + '/health', timeout=2) as response:
                self.assertEqual(response.status, 200)
            payload = json.dumps({'messages': [{'content': [{'type': 'image_url'}]}]}).encode()
            started = time.perf_counter()
            with urllib.request.urlopen(urllib.request.Request(base + '/v1/chat/completions', data=payload), timeout=2) as response:
                body = json.loads(response.read())
                self.assertTrue(json.loads(body['choices'][0]['message']['content'])['foodImage'])
            self.assertGreaterEqual(time.perf_counter() - started, .02)
            with self.assertRaises(urllib.error.HTTPError) as context:
                urllib.request.urlopen(urllib.request.Request(base + '/v1/chat/completions', data=payload), timeout=2)
            self.assertEqual(context.exception.code, 429)
            context.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__': unittest.main()
