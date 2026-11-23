"""Bounded live HTTP pilot against the current stack; never rebuilds knowledge or changes model settings."""

import sys as _auth_sys
from pathlib import Path as _AuthPath
_auth_sys.path.insert(0, str(_AuthPath(__file__).resolve().parents[2] / 'tools'))
import cookie_http as _auth_http
import argparse
import collections
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def sql(query):
    result = subprocess.run(['docker', 'exec', 'mealcheck-postgres', 'psql', '-U', 'mealcheck', '-d', 'mealcheck', '-At', '-v', 'ON_ERROR_STOP=1', '-c', query], capture_output=True, text=True, encoding='utf-8', timeout=30)
    if result.returncode:
        raise RuntimeError('DATABASE_CHECK_FAILED')
    return result.stdout.strip()


def http(path, token=None, body=None, method=None):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request('http://127.0.0.1:8080' + path, headers=headers, method=method or ('POST' if body is not None else 'GET'), data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
    with _auth_http.urlopen(request, timeout=150) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def metrics(token):
    request = urllib.request.Request('http://127.0.0.1:8080/actuator/prometheus', headers={'Authorization': 'Bearer ' + token})
    try:
        with _auth_http.urlopen(request, timeout=10) as response:
            lines = response.read().decode().splitlines()
        prefixes = ('mealcheck_ai_http_attempts_total', 'mealcheck_ai_http_retries_total', 'mealcheck_rag_search_requests_total', 'mealcheck_rag_rerank_requests_total')
        return {line.rsplit(' ', 1)[0]: float(line.rsplit(' ', 1)[1]) for line in lines if line.startswith(prefixes)}
    except Exception:
        return {}


def chat(token, question):
    request = urllib.request.Request('http://127.0.0.1:8080/api/assistant/stream', method='POST', headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'text/event-stream'}, data=json.dumps({'question': question, 'history': []}, ensure_ascii=False).encode())
    started = time.monotonic()
    with _auth_http.urlopen(request, timeout=150) as response:
        raw = response.read().decode('utf-8').replace('\r\n', '\n')
    events, final = [], None
    for block in raw.split('\n\n'):
        name, data = 'message', []
        for line in block.splitlines():
            if line.startswith('event:'):
                name = line[6:].strip()
            elif line.startswith('data:'):
                data.append(line[5:].lstrip())
        if data:
            value = json.loads('\n'.join(data)); events.append({'event': name, 'data': value})
            if name == 'complete':
                final = value
    return {'durationMs': round((time.monotonic() - started) * 1000), 'events': events, 'response': final}


def cache_for_queries(queries):
    result = []
    for query in dict.fromkeys(queries):
        digest = hashlib.sha256(query.strip().lower().encode()).hexdigest()
        scan = subprocess.run(['docker', 'exec', 'mealcheck-redis', 'redis-cli', '--raw', '--scan', '--pattern', 'rag:search:*:' + digest], capture_output=True, text=True, encoding='utf-8', timeout=15)
        for key in scan.stdout.splitlines():
            raw = subprocess.run(['docker', 'exec', 'mealcheck-redis', 'redis-cli', '--raw', 'GET', key], capture_output=True, text=True, encoding='utf-8', timeout=15)
            try:
                result.append({'query': query, 'cacheKey': key, 'snippets': json.loads(raw.stdout)})
            except ValueError:
                pass
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', required=True)
    parser.add_argument('--resume', type=Path)
    args = parser.parse_args()
    packet = ROOT / 'tools/evaluation/full_chain_pilot10_v1.json'
    plan = json.loads(packet.read_text(encoding='utf-8'))
    for name, info in plan['sourceFiles'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != info['sha256']:
            raise RuntimeError('FROZEN_SOURCE_CHANGED:' + name)
    out = ROOT / 'logs/backend' / ('full-chain-pilot10-live-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    out.mkdir()
    def save(name, data):
        with (out / name).open('x', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
    summary = {'status': 'PREFLIGHT', 'planSha256': hashlib.sha256(packet.read_bytes()).hexdigest(), 'maximumQuestions': 10, 'maximumUserDailyAttemptsRequired': 100, 'noRebuild': True, 'cases': [], 'accountCleanedUp': False}
    previous_attempts = 0
    resumed_ids = set()
    if args.resume:
        previous = json.loads(args.resume.read_text(encoding='utf-8'))
        if previous['planSha256'] != summary['planSha256'] or len(previous['cases']) != 1 or previous['cases'][0]['id'] != 'rag-008':
            raise RuntimeError('RESUME_SCOPE_CHANGED')
        previous_attempts = previous['userProviderAttempts']
        summary['cases'] = previous['cases']
        summary['resumedFrom'] = str(args.resume)
        summary['priorProviderAttempts'] = previous_attempts
        resumed_ids = {case['id'] for case in previous['cases']}
    username, password, token, user_id = 'pilot10_' + secrets.token_hex(8), secrets.token_urlsafe(24), None, None
    print('Evidence: ' + str(out), flush=True)
    try:
        if http('/actuator/health')['status'] != 'UP':
            raise RuntimeError('BACKEND_NOT_HEALTHY')
        state = json.loads(sql("SELECT json_build_object('mode',s.mode,'signature',s.signature,'model',p.model,'dimension',p.dimension,'baseUrl',p.base_url) FROM knowledge_embedding_settings s JOIN knowledge_embedding_profiles p ON p.id=s.active_profile_id WHERE s.id=1"))
        if state['mode'] != 'SEMANTIC' or state['model'] != 'BAAI/bge-m3' or state['dimension'] != 1024 or state['baseUrl'] != 'https://api.siliconflow.cn/v1':
            raise RuntimeError('ACTIVE_EMBEDDING_SCOPE_CHANGED')
        inventory = json.loads(sql("SELECT json_build_object('total',count(*),'signatures',count(DISTINCT embedding_signature),'compatible',count(*) FILTER (WHERE embedding_signature=(SELECT signature FROM knowledge_embedding_settings WHERE id=1))) FROM knowledge_chunks"))
        if inventory['total'] != 1351 or inventory['compatible'] != inventory['total'] or inventory['signatures'] != 1:
            raise RuntimeError('INDEX_NOT_COMPATIBLE')
        summary.update({'activeEmbedding': state, 'inventory': inventory})
        backend = json.loads(subprocess.check_output(['docker', 'inspect', 'mealcheck-backend'], encoding='utf-8'))[0]
        env = dict(item.split('=', 1) for item in backend['Config']['Env'] if '=' in item)
        if env.get('MEALCHECK_KNOWLEDGE_RERANK_ENABLED') != 'true' or env.get('MEALCHECK_KNOWLEDGE_RERANK_MODEL') != 'qwen3.7-text-rerank':
            raise RuntimeError('RERANK_NOT_CONFIGURED')
        if int(env.get('MEALCHECK_AI_DAILY_CALLS', '100')) > 100:
            raise RuntimeError('USER_DAILY_BUDGET_EXCEEDS_PILOT_CAP')
        summary['backendImage'] = backend['Image']
        captcha = http('/api/auth/captcha'); numbers = re.fullmatch(r'(\d+) \+ (\d+) = \?', captcha['question'])
        if not numbers:
            raise RuntimeError('CAPTCHA_FORMAT')
        auth = http('/api/auth/register', body={'username': username, 'password': password, 'displayName': '完整RAG十题临时验收', 'captchaId': captcha['captchaId'], 'captchaAnswer': str(sum(map(int, numbers.groups())))})
        token = auth['token']
        user_id = int(sql("SELECT id FROM users WHERE username='" + username + "'"))
        sql("UPDATE users SET role='ADMIN' WHERE id=" + str(user_id) + " AND username='" + username + "'")
        settings = http('/api/admin/ai-settings', token)
        if settings['model'] != 'qwen3.8-flash' or settings['baseUrl'].rstrip('/') != 'https://dashscope.aliyuncs.com/compatible-mode/v1':
            raise RuntimeError('TEXT_MODEL_SCOPE_CHANGED')
        summary['textModel'] = settings['model']; summary['rerankModel'] = 'qwen3.7-text-rerank'
        save('preflight.json', {k: v for k, v in summary.items() if k != 'cases'})
        summary['status'] = 'RUNNING'
        for case in plan['cases']:
            if case['id'] in resumed_ids:
                continue
            attempts = int(sql("SELECT coalesce(sum(attempts),0) FROM ai_budget_daily WHERE quota_key='user:" + str(user_id) + "'"))
            if attempts + previous_attempts > 84:
                raise RuntimeError('REQUEST_CAP_STOP_BEFORE_NEXT_CASE')
            before = metrics(token); begin = sql('SELECT coalesce(max(id),0) FROM rag_search_events')
            log_since = datetime.now(timezone.utc).isoformat()
            row = {'id': case['id'], 'question': case['question'], 'answerability': case['answerability'], 'userAttemptsBefore': attempts}
            try:
                row.update(chat(token, case['question']))
                after = metrics(token); row['metricDeltas'] = {k: v - before.get(k, 0) for k, v in after.items() if v != before.get(k, 0)}
                response = row.get('response')
                queries = [case['question']]
                for call in (response or {}).get('toolCalls', []):
                    if call['name'] == 'search_diet_knowledge':
                        queries.append(json.loads(call['arguments'])['query'])
                row['retrievalCaches'] = cache_for_queries(queries)
                row['searchEvents'] = json.loads(sql('SELECT coalesce(json_agg(t),\'[]\'::json) FROM (SELECT * FROM rag_search_events WHERE id>' + begin + ' ORDER BY id) t'))
                row['userAttemptsAfter'] = int(sql("SELECT coalesce(sum(attempts),0) FROM ai_budget_daily WHERE quota_key='user:" + str(user_id) + "'"))
                if response and response.get('conversationId'):
                    row['persistedConversation'] = http('/api/assistant/conversations/' + str(response['conversationId']), token)
                row['transportComplete'] = bool(response) and response.get('deliveryStatus', 'COMPLETE') == 'COMPLETE'
                row['qualityStatus'] = 'PENDING_EVIDENCE_REVIEW'
                failure = any('mealcheck_ai_http_attempts_total' in k and 'outcome="failure"' in k and v > 0 for k, v in row['metricDeltas'].items())
                if failure:
                    logs = subprocess.run(['docker', 'logs', '--since', log_since, 'mealcheck-backend'], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=15)
                    request_id = (response or {}).get('requestId', '')
                    attempts_log = [line for line in (logs.stdout + '\n' + logs.stderr).splitlines() if request_id and request_id in line and 'AI HTTP attempt failed.' in line]
                    row['transportFailureCategories'] = [dict(zip(['operation', 'reason', 'status'], match.groups())) for line in attempts_log if (match := re.search(r'operation=([^,]+), reason=([^,]+), status=(\d+)', line))]
                    local_cancel = bool(row['transportFailureCategories']) and all(item['reason'] == 'interrupted' and item['status'] == '0' for item in row['transportFailureCategories'])
                    row['localCancellationObserved'] = local_cancel
                    if not local_cancel:
                        row['stopReason'] = 'PROVIDER_FAILURE_OBSERVED'
            except Exception as error:
                row['errorType'] = type(error).__name__; row['transportComplete'] = False; row['stopReason'] = 'CASE_EXECUTION_ERROR'
            summary['cases'].append(row); save(case['id'] + '-result.json', row)
            print(case['id'] + ' complete=' + str(row['transportComplete']) + ' attempts=' + str(row.get('userAttemptsAfter')), flush=True)
            if row.get('stopReason'):
                summary['status'] = 'STOPPED'; break
        else:
            summary['status'] = 'COMPLETE_PENDING_QUALITY_REVIEW'
        if len(summary['cases']) == 10:
            payload = {'token': token, 'username': username, 'userId': user_id, 'cases': [c for c in summary['cases'] if c['id'] not in resumed_ids], 'output': str(out)}
            ui = subprocess.run(['node', 'deploy/scripts/verify_full_chain_pilot10_ui.mjs'], input=json.dumps(payload, ensure_ascii=False), capture_output=True, text=True, encoding='utf-8', timeout=180, cwd=ROOT)
            summary['uiExitCode'] = ui.returncode
            summary['uiOutput'] = ui.stdout[-2000:]
            if ui.returncode:
                summary['uiError'] = ui.stderr[-1500:]
    except Exception as error:
        summary['status'] = 'STOPPED'; summary['errorType'] = type(error).__name__; summary['errorCode'] = str(error)[:160]
    finally:
        if user_id is not None:
            summary['userProviderAttempts'] = int(sql("SELECT coalesce(sum(attempts),0) FROM ai_budget_daily WHERE quota_key='user:" + str(user_id) + "'"))
            summary['cumulativeUserProviderAttempts'] = previous_attempts + summary['userProviderAttempts']
            try:
                sql("UPDATE users SET role='USER' WHERE id=" + str(user_id) + " AND username='" + username + "'")
                http('/api/profile/account', token, {'password': password}, method='DELETE')
                summary['accountCleanedUp'] = sql('SELECT count(*) FROM users WHERE id=' + str(user_id)) == '0'
            except Exception as error:
                summary['cleanupErrorType'] = type(error).__name__
        summary['finishedAt'] = datetime.now(timezone.utc).isoformat(); save('results.json', summary)
        print(json.dumps({'status': summary['status'], 'completedCases': len(summary['cases']), 'providerAttempts': summary.get('userProviderAttempts', 0), 'accountCleanedUp': summary['accountCleanedUp'], 'evidence': str(out)}, ensure_ascii=True), flush=True)
    if summary['status'].startswith('STOPPED') or not summary['accountCleanedUp']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
