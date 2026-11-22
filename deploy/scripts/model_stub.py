"""Deterministic external AI replacement for isolated capacity tests, never production."""
import json
import math
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time

DELAY_SECONDS = float(os.environ.get('MODEL_STUB_DELAY_SECONDS', '.05'))
ERROR_EVERY_N = int(os.environ.get('MODEL_STUB_ERROR_EVERY_N', '0'))
ERROR_STATUS = int(os.environ.get('MODEL_STUB_ERROR_STATUS', '429'))
RETRY_AFTER = 0
ERROR_FIRST_N = 0
if not math.isfinite(DELAY_SECONDS) or DELAY_SECONDS < 0 or ERROR_EVERY_N < 0 or not 400 <= ERROR_STATUS <= 599:
    raise ValueError('Invalid model stub delay/error configuration')
calls = 0
active_calls = 0
peak_active_calls = 0
calls_by_kind = {}
calls_lock = threading.Lock()


def fixture_response(request):
    """Exercise the real parsers; plain text would silently test weekly fallbacks."""
    if 'input' in request and 'dimensions' in request:
        dimension=int(request['dimensions'])
        if not 1 <= dimension <= 2000: raise ValueError('Invalid fixture embedding dimension')
        return 'embedding', {'object':'list','data':[{'index':0,'embedding':[1.0]+[0.0]*(dimension-1)}],
                             'usage':{'prompt_tokens':10,'total_tokens':10}}
    messages = request.get('messages', [])
    if any(isinstance(item.get('content'), list) and any(part.get('type') == 'image_url' for part in item['content'] if isinstance(part, dict)) for item in messages):
        return 'visual', json.dumps({'foodImage': True, 'foods': [{'name': '米饭', 'category': 'staple', 'confidence': .9}]}, ensure_ascii=False)
    prompt = '\n'.join('\n'.join(str(part.get('text', '')) for part in item['content'] if isinstance(part, dict))
                       if isinstance(item.get('content'), list) else str(item.get('content', '')) for item in messages)
    if '你是逐分句证据核验器' in prompt and '回答：' in prompt:
        answer, _ = json.JSONDecoder().raw_decode(prompt.rsplit('回答：', 1)[1].lstrip())
        quotes = {q['id']: q['text'] for q in answer.get('quoteTexts', [])}
        claims = []
        for claim in answer.get('claims', []):
            bindings = claim.get('evidence', [])
            texts = [quotes[q] for b in bindings for q in b.get('quoteIds', []) if q in quotes]
            judgments = []
            for assertion in claim.get('assertions', []):
                supported = bool(assertion.get('text')) and any(assertion['text'] in text for text in texts)
                judgments.append({'id': assertion['id'], 'supported': supported, 'standalone': supported,
                                  'checks': dict.fromkeys(('fact','degree','causality','conditions'), supported),
                                  'evidenceIds': [b['id'] for b in bindings] if supported else [], 'reason': '' if supported else 'not verbatim'})
            claims.append({'id': claim['id'], 'assertions': judgments})
        return 'assistant-evidence-review', json.dumps({'inputMissing': [], 'knowledgeMissing': [], 'searchQuery': '',
            'answersQuestion': bool(claims) and all(a['supported'] for c in claims for a in c['assertions']), 'claims': claims}, ensure_ascii=False)
    if 'Return ONLY the following JSON protocol' in prompt:
        # Knowledge fixtures quote only passages actually delivered by the production retrieval tool.
        sources = []
        for message in reversed(messages):
            content = message.get('content', '')
            if isinstance(content, str):
                for index, char in enumerate(content):
                    if char != '{': continue
                    try:
                        payload, _ = json.JSONDecoder().raw_decode(content[index:])
                        if isinstance(payload, dict) and isinstance(payload.get('results'), list):
                            sources = payload['results']
                            break
                    except (ValueError, TypeError):
                        pass
                if sources: break
        claims = []
        for source in sources:
            for passage in source.get('passages', []):
                text = passage.get('text', '')
                if text and len(text) <= 120 and not text.startswith('且'):
                    claims = [{'kind': 'ANSWER', 'text': text, 'evidence': [{'sourceId': source['id'], 'passageIds': [passage['id']]}]}]
                    break
            if claims: break
        return 'assistant-knowledge', json.dumps({'claims': claims}, ensure_ascii=False)
    if '饮食趋势分析 Agent' in prompt:
        kind, data = 'weekly-trend', {'summary': '测试趋势：饮食记录以主食为主。', 'findings': ['建议增加食物种类'], 'focusKeywords': ['均衡饮食']}
    elif '饮食教练 Agent' in prompt:
        kind, data = 'weekly-plan', {'reportText': '测试周报：保持规律饮食并增加食物种类。', 'highlights': ['持续记录饮食', '关注食物多样性'], 'nextWeekSuggestions': ['增加蔬菜', '搭配蛋白质', '保持规律饮食']}
    elif '安全与一致性审查 Agent' in prompt:
        kind, data = 'weekly-review', {'approved': True, 'feedback': '测试审查通过', 'issues': []}
    elif 'weeklyTrend' in prompt or request.get('tools'):
        kind, data = 'assistant', {'summary': '测试建议：补充蔬菜与蛋白质。', 'suggestions': ['增加食物种类'], 'riskLevel': 'LOW', 'weeklyTrend': '测试趋势'}
    else:
        return 'advice', '测试建议：补充蔬菜与蛋白质。'
    return kind, json.dumps(data, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def send_json(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self):
        if self.path == '/__stats':
            with calls_lock: value, kinds, active, peak = calls, dict(calls_by_kind), active_calls, peak_active_calls
            self.send_json(200, {'stub': True, 'calls': value, 'callsByKind': kinds, 'activeCalls': active, 'peakActiveCalls': peak})
            return
        self.send_json(200 if self.path == '/health' else 404, {'stub': True})
    def do_POST(self):
        global calls, peak_active_calls, DELAY_SECONDS, ERROR_EVERY_N, ERROR_STATUS, RETRY_AFTER, ERROR_FIRST_N
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/__control':
            delay = float(request.get('delay', .05))
            every = int(request.get('errorEvery', 0))
            status = int(request.get('errorStatus', 429))
            retry = int(request.get('retryAfter', 0))
            first = int(request.get('errorFirst', 0))
            if not math.isfinite(delay) or not 0 <= delay <= 120 or not 0 <= every <= 1000 or not 400 <= status <= 599 or not 0 <= retry <= 120 or not 0 <= first <= 1000:
                self.send_json(400, {'error': 'Invalid fixture configuration'}); return
            with calls_lock:
                DELAY_SECONDS, ERROR_EVERY_N, ERROR_STATUS, RETRY_AFTER, ERROR_FIRST_N, calls = delay, every, status, retry, first, 0
                calls_by_kind.clear()
                peak_active_calls = active_calls
            self.send_json(200, {'stub': True}); return
        self.respond_fixture(request)

    def respond_fixture(self, request):
        global calls, active_calls, peak_active_calls
        kind, content = fixture_response(request)
        with calls_lock:
            calls += 1
            active_calls += 1
            peak_active_calls = max(peak_active_calls, active_calls)
            calls_by_kind[kind] = calls_by_kind.get(kind, 0) + 1
            fail = calls <= ERROR_FIRST_N or (ERROR_EVERY_N > 0 and calls % ERROR_EVERY_N == 0)
            delay, status, retry = DELAY_SECONDS, ERROR_STATUS, RETRY_AFTER
        try:
            self.send_fixture(content, fail, delay, status, retry, stream=request.get('stream') is True)
        finally:
            with calls_lock: active_calls -= 1

    def send_fixture(self, content, fail, delay, status, retry, stream=False):
        time.sleep(delay)
        if fail:
            # This fixture is exposed only on loopback by the isolated acceptance Compose.
            data = json.dumps({'error': {'message': 'Simulated model failure', 'type': 'capacity_test'}}).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Retry-After', str(retry))
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass
            return
        if isinstance(content,dict):
            self.send_json(200,content)
            return
        if stream:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Connection', 'close')
            self.end_headers()
            events = [
                {'id': 'capacity-stub', 'choices': [{'index': 0, 'delta': {'content': content}, 'finish_reason': None}]},
                {'id': 'capacity-stub', 'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}],
                 'usage': {'prompt_tokens': 100, 'completion_tokens': 20, 'total_tokens': 120}}
            ]
            try:
                for event in events:
                    self.wfile.write(('data: ' + json.dumps(event, ensure_ascii=False) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self.send_json(200, {'id': 'capacity-stub', 'object': 'chat.completion', 'model': 'capacity-stub',
            'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 20, 'total_tokens': 120}})


if __name__ == '__main__': ThreadingHTTPServer(('0.0.0.0', 9082), Handler).serve_forever()
