"""Run two actual backend JVMs in a disposable, network-isolated Compose project.

Only the bundled model stub is allowed. No production credentials or user data are read.
"""

import sys as _auth_sys
from pathlib import Path as _AuthPath
_auth_sys.path.insert(0, str(_AuthPath(__file__).resolve().parents[2] / 'tools'))
import cookie_http as _auth_http
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def request(base, path, token=None, body=None, method=None, timeout=55):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers=headers, method=method)
    try:
        response = _auth_http.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def wait_for(predicate, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(.2)
    raise AssertionError('Timed out waiting for acceptance condition')


class Stream:
    def __init__(self, base, token, question):
        self.ready, self.done = threading.Event(), threading.Event()
        self.request_id, self.events, self.error = None, [], None
        self.base, self.token, self.question = base, token, question
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()
        if not self.ready.wait(15) or self.error:
            raise AssertionError('Stream admission failed: ' + str(self.error))

    def read(self):
        try:
            req = urllib.request.Request(self.base + '/api/assistant/stream',
                    data=json.dumps({'question': self.question}).encode(),
                    headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
            with _auth_http.urlopen(req, timeout=55) as response:
                self.request_id = response.headers.get('X-Assistant-Request-ID')
                if not self.request_id or not re.fullmatch(r'[a-f0-9-]{36}', self.request_id):
                    raise AssertionError('Missing request ID')
                self.ready.set()
                event = 'message'
                for raw in response:
                    line = raw.decode('utf-8').strip()
                    if line.startswith('event:'):
                        event = line[6:].strip()
                    elif line.startswith('data:'):
                        self.events.append({'event': event, 'data': json.loads(line[5:].strip())})
        except Exception as error:
            self.error = type(error).__name__
        finally:
            self.ready.set()
            self.done.set()


class Acceptance:
    def __init__(self, output, jar=None):
        self.output = output.resolve()
        if self.output.exists():
            raise RuntimeError('Refusing to overwrite acceptance evidence')
        self.output.mkdir(parents=True)
        self.project = 'mealcheck-assistant-cluster-' + uuid.uuid4().hex[:12]
        self.env = dict(os.environ)
        ports = set()
        while len(ports) < 3:
            ports.add(free_port())
        a, b, stub = ports
        self.env.update(CLUSTER_PORT_A=str(a), CLUSTER_PORT_B=str(b), CLUSTER_PORT_STUB=str(stub))
        self.a, self.b, self.stub = [f'http://127.0.0.1:{port}' for port in (a, b, stub)]
        jar = ROOT / 'backend/target/mealcheck-backend.jar' if jar is None else Path(jar).resolve()
        shutil.copy2(jar, self.output / 'app.jar')
        self.env['CLUSTER_TEST_JAR'] = str(self.output / 'app.jar')
        self.compose = ['docker', 'compose', '-p', self.project, '--env-file', str(ROOT / 'deploy/assistant-cluster.env'),
                        '-f', str(ROOT / 'deploy/compose.assistant-cluster.yml')]
        self.checks = []
        self.created = False

    def command(self, args, *, input=None, timeout=180, check=True):
        result = subprocess.run(args, cwd=ROOT, env=self.env, input=input, capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=timeout)
        if check and result.returncode:
            raise RuntimeError('Fixture command failed: ' + result.stderr[-1500:])
        return result

    def sql(self, sql):
        return self.command(self.compose + ['exec', '-T', 'postgres', 'psql', '-U', 'mealcheck', '-d', 'mealcheck',
                           '-At', '-v', 'ON_ERROR_STOP=1'], input=sql).stdout.strip()

    def record(self, name, assertion=True, **data):
        self.checks.append({'name': name, 'passed': bool(assertion), **data})
        (self.output / 'progress.json').write_text(json.dumps(self.checks, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(self.checks[-1], ensure_ascii=False), flush=True)
        if not assertion:
            raise AssertionError(name)

    def status(self, base, token, request_id):
        code, value = request(base, '/api/assistant/requests/' + request_id, token)
        assert code == 200
        return value['state']

    def account(self):
        code, captcha = request(self.a, '/api/auth/captcha')
        assert code == 200
        a, b = map(int, re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?', captcha['question']).groups())
        name = 'cluster_' + uuid.uuid4().hex[:20]
        code, auth = request(self.a, '/api/auth/register', body={'username': name, 'password': 'Cluster-' + uuid.uuid4().hex,
                       'captchaId': captcha['captchaId'], 'captchaAnswer': str(a+b)})
        assert code in (200, 201)
        return name, auth['token']

    def delay(self, seconds):
        wait_for(lambda: request(self.stub, '/__stats')[1]['activeCalls'] == 0, 30)
        assert request(self.stub, '/__control', body={'delay': seconds})[0] == 200

    def complete_count(self, request_id):
        assert re.fullmatch(r'[a-f0-9-]{36}', request_id)
        return int(self.sql("SELECT count(*) FROM assistant_conversation_messages WHERE role='assistant' "
                            "AND delivery_status='COMPLETE' AND request_id='" + request_id + "'"))

    def start(self):
        existing = self.command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project]).stdout.strip()
        if existing:
            raise RuntimeError('Refusing to reuse an existing project')
        self.created = True
        result = self.command(self.compose + ['up', '-d'], timeout=240)
        (self.output / 'startup.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        for base in (self.a, self.b):
            wait_for(lambda: request(base, '/actuator/health', timeout=3)[0] == 200, 180)
        ids = self.command(self.compose + ['ps', '-q', 'backend-a', 'backend-b']).stdout.split()
        configs = json.loads(self.command(['docker', 'inspect', *ids]).stdout)
        assert len(configs) == 2
        for config in configs:
            assert config['Config']['Labels']['com.docker.compose.project'] == self.project
            env = dict(value.split('=', 1) for value in config['Config']['Env'])
            assert env['MEALCHECK_AI_API_KEY'] == 'cluster-test-only'
            assert env['MEALCHECK_AI_BASE_URL'] == 'http://model-stub:9082/v1'
        expected=hashlib.sha256((self.output / 'app.jar').read_bytes()).hexdigest()
        for service in ('backend-a','backend-b'):
            actual=self.command(self.compose+['exec','-T',service,'sha256sum','/app/app.jar']).stdout.split()[0]
            assert actual==expected
        self.record('two-actual-backend-processes-and-isolated-model-config', containerIds=ids)

    def run(self):
        self.start()
        name, token = self.account()
        _, stranger = self.account()
        self.delay(6)
        stream = Stream(self.a, token, '总结我本周的饮食趋势')
        wait_for(lambda: self.status(self.b, token, stream.request_id) == 'RUNNING')
        wait_for(lambda: request(self.stub, '/__stats')[1]['activeCalls'] > 0)
        for path in ('/api/assistant/ask', '/api/chat', '/api/assistant/stream'):
            code, _ = request(self.b, path, token, {'question': '重复请求'})
            self.record('cross-instance-admission-' + path.rsplit('/', 1)[-1], code == 409, status=code)
        self.record('foreign-status-is-hidden', request(self.b, '/api/assistant/requests/' + stream.request_id, stranger)[0] == 404)
        request(self.b, '/api/assistant/requests/' + stream.request_id, stranger, method='DELETE')
        self.record('foreign-cancellation-does-not-change-task', self.status(self.b, token, stream.request_id) == 'RUNNING')
        request(self.b, '/api/assistant/requests/' + stream.request_id, token, method='DELETE')
        assert stream.done.wait(15)
        wait_for(lambda: self.status(self.b, token, stream.request_id) == 'CANCELLED')
        self.record('remote-running-cancellation-does-not-publish-complete-turn', self.complete_count(stream.request_id) == 0)

        self.delay(6)
        _, busy_token = self.account()
        _, queued_token = self.account()
        busy = Stream(self.a, busy_token, '总结我本周的饮食趋势')
        wait_for(lambda: self.status(self.b, busy_token, busy.request_id) == 'RUNNING')
        queued = Stream(self.a, queued_token, '总结我本周的饮食趋势')
        assert self.status(self.b, queued_token, queued.request_id) == 'QUEUED'
        request(self.b, '/api/assistant/requests/' + queued.request_id, queued_token, method='DELETE')
        assert queued.done.wait(12)
        wait_for(lambda: self.status(self.b, queued_token, queued.request_id) == 'CANCELLED')
        self.record('remote-queued-cancellation-never-publishes', self.complete_count(queued.request_id) == 0)
        request(self.b, '/api/assistant/requests/' + busy.request_id, busy_token, method='DELETE')
        assert busy.done.wait(12)

        self.delay(6)
        with ThreadPoolExecutor(max_workers=1) as executor:
            sync = executor.submit(request, self.b, '/api/assistant/ask', token, {'question': '总结我本周的饮食趋势'})
            request_id = wait_for(lambda: self.sql("SELECT request_id FROM assistant_tasks t JOIN users u ON u.id=t.user_id "
                              "WHERE u.username='" + name + "' AND t.state='RUNNING'"))
            code, _ = request(self.a, '/api/assistant/stream', token, {'question': '重复请求'})
            self.record('synchronous-owner-blocks-streaming-on-other-node', code == 409)
            request(self.a, '/api/assistant/requests/' + request_id, token, method='DELETE')
            code, body = sync.result(timeout=15)
            self.record('synchronous-request-can-be-cancelled-from-other-node', code == 409 and body.get('code') == 'ASSISTANT_CANCELLED')
            # The HTTP cancellation response precedes worker cleanup by design.
            # A subsequent turn must wait for the shared occupancy to be released.
            wait_for(lambda: self.status(self.a, token, request_id) == 'CANCELLED')

        self.delay(.05)
        code, response = request(self.b, '/api/chat', token, {'question': '总结我本周的饮食趋势'})
        assert code == 200, f'Legacy chat status: {code}'
        self.record('legacy-chat-route-completes-on-shared-manager', self.complete_count(response['requestId']) == 1
                    and '测试建议' in response['answer'])
        self.delay(.05)
        success = Stream(self.a, token, '总结我本周的饮食趋势')
        assert success.done.wait(25)
        wait_for(lambda: self.status(self.b, token, success.request_id) == 'SUCCEEDED')
        self.record('normal-streaming-still-completes', any(event['event'] == 'complete' for event in success.events)
                    and any(event['event']=='delta' and '测试建议' in event['data'].get('text','') for event in success.events)
                    and self.complete_count(success.request_id) == 1)

        self.delay(8)
        killed = Stream(self.a, token, '总结我本周的饮食趋势')
        wait_for(lambda: request(self.stub, '/__stats')[1]['activeCalls'] > 0)
        self.command(self.compose + ['kill', '-s', 'SIGKILL', 'backend-a'])
        wait_for(lambda: self.status(self.b, token, killed.request_id) == 'INTERRUPTED', 20)
        self.record('killed-owner-is-reaped-without-complete-history', self.complete_count(killed.request_id) == 0)
        self.delay(.05)
        assert request(self.b, '/api/assistant/ask', token, {'question': '总结我本周的饮食趋势'})[0] == 200
        self.command(self.compose + ['up', '-d', 'backend-a'])
        wait_for(lambda: request(self.a, '/actuator/health', timeout=3)[0] == 200, 120)
        self.record('owner-restart-does-not-replay-interrupted-model-work', self.status(self.b, token, killed.request_id) == 'INTERRUPTED')

        self.delay(8)
        stale = Stream(self.a, token, '总结我本周的饮食趋势')
        wait_for(lambda: request(self.stub, '/__stats')[1]['activeCalls'] > 0)
        self.command(self.compose + ['pause', 'backend-a'])
        try:
            wait_for(lambda: self.status(self.b, token, stale.request_id) == 'INTERRUPTED', 20)
            self.delay(.05)
            code, replacement = request(self.b, '/api/assistant/ask', token, {'question': '总结我本周的饮食趋势'})
            assert code == 200
        finally:
            self.command(self.compose + ['unpause', 'backend-a'])
        assert stale.done.wait(25)
        self.record('resumed-stale-owner-cannot-publish-or-overwrite-replacement',
                    self.complete_count(stale.request_id) == 0 and self.complete_count(replacement['requestId']) == 1
                    and self.status(self.b, token, stale.request_id) == 'INTERRUPTED')
        self.record('no-active-registrations-left', self.sql("SELECT count(*) FROM assistant_tasks WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')") == '0')

    def finish(self, passed, error=None):
        if self.created:
            logs = self.command(self.compose + ['logs', '--no-color', '--tail', '500'], check=False)
            (self.output / 'compose.log').write_text(logs.stdout + logs.stderr, encoding='utf-8')
            # Only this randomly generated, initially absent project can be removed.
            self.command(self.compose + ['unpause', 'backend-a'], check=False)
            cleanup = self.command(self.compose + ['down', '--volumes', '--remove-orphans'], check=False)
            remaining = self.command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project]).stdout.strip()
            clean = cleanup.returncode == 0 and not remaining
        else:
            clean = True
        manifest = {'status': 'PASSED' if passed and clean else 'FAILED', 'scope': 'two-actual-backend-processes-local-model-stub',
                    'finishedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'project': self.project,
                    'jarSha256': hashlib.sha256((self.output / 'app.jar').read_bytes()).hexdigest(),
                    'checks': self.checks, 'cleanupPassed': clean, 'realModelCalls': 0, 'error': error}
        (self.output / 'verification.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar',type=Path,help='Fixed verified JAR; defaults to backend/target/mealcheck-backend.jar')
    args = parser.parse_args()
    run = Acceptance(args.output,args.jar)
    passed, error = False, None
    try:
        run.run()
        passed = True
    except Exception as failure:
        error = type(failure).__name__ + ': ' + str(failure)
        traceback.print_exc(file=sys.stdout)
        print(error, flush=True)
    finally:
        result = run.finish(passed, error)
    if result['status'] != 'PASSED':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
