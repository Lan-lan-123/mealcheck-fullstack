"""Bounded 50-question timing study. Three isolated users; 300 attempts total; no index/config changes."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time
from run_full_chain_pilot10 import ROOT, sql, http, chat, cache_for_queries


def parse_timings(raw, request_ids):
    markers = {'AI attempt timing. ': 'http', 'Assistant phase timing. ': 'phase',
               'Assistant admission timing. ': 'admission', 'Assistant task timing. ': 'task',
               'Assistant budget timing. ': 'budget'}
    events = []
    for line in raw.splitlines():
        marker = next((m for m in markers if m in line), None)
        if not marker:
            continue
        owned = [i for i in re.findall(r'requestId=([a-f0-9-]{36})', line) if i in request_ids]
        if not owned:
            continue
        data = dict(re.findall(r'(\w+)=([^,\s]+)', line.split(marker, 1)[1]))
        for key in ('elapsedMicros', 'dispatchWaitMicros', 'budgetAtStartMs', 'budgetAtEndMs',
                    'totalBudgetAtStartMs', 'phaseBudgetAtStartMs', 'phaseBudgetAtEndMs', 'executionMicros', 'attempt', 'status'):
            if key in data:
                data[key] = int(data[key])
        events.append({'requestId': owned[-1], 'kind': markers[marker],
                       'loggedAt': line.split(' ', 1)[0], **data})
    return events


def usage(user_id):
    return int(sql("SELECT coalesce(sum(attempts),0) FROM ai_budget_daily WHERE quota_key='user:" + str(user_id) + "'"))


def dispatch_with_reserve(tasks, accounts, concurrency, get_usage, execute, stopped):
    """One active request per user; stop for reserve only when every free user is ineligible.

    execute(task, account) must return after its usage has settled. This scheduler never
    changes limits or creates replacement users. False means pending tasks lack reserve.
    """
    pending = iter(tasks)
    next_task = next(pending, None)
    active = {}
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        while next_task is not None or active:
            while next_task is not None and len(active) < concurrency and not stopped():
                busy = {a['index'] for a in active.values()}
                available = [(get_usage(a['id']), a) for a in accounts if a['index'] not in busy]
                eligible = [(used, a) for used, a in available if used <= 84]
                if not eligible:
                    break
                _, account = min(eligible, key=lambda pair: (pair[0], pair[1]['index']))
                active[executor.submit(execute, next_task, account)] = account
                next_task = next(pending, None)
            if not active:
                return next_task is None
            finished, _ = wait(active, return_when=FIRST_COMPLETED)
            for future in finished:
                del active[future]
                future.result()
            if stopped():
                return False
    return True


def evict_exact_queries(questions):
    removed = []
    for question in questions:
        digest = hashlib.sha256(question.strip().lower().encode()).hexdigest()
        scan = subprocess.run(['docker', 'exec', 'mealcheck-redis', 'redis-cli', '--raw', '--scan',
                               '--pattern', 'rag:search:*:' + digest + '*'], capture_output=True, text=True, timeout=15)
        scan.check_returncode()
        keys = [key for key in scan.stdout.splitlines() if key.endswith(':' + digest) or key.endswith(':' + digest + ':stale')]
        if keys:
            subprocess.run(['docker', 'exec', 'mealcheck-redis', 'redis-cli', 'DEL', *keys],
                           check=True, capture_output=True, timeout=15)
        removed.extend(keys)
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    packet = ROOT / 'tools/evaluation/rag_timing50_v1.json'
    plan = json.loads(packet.read_text(encoding='utf-8'))
    for file, info in plan['sourceFiles'].items():
        assert hashlib.sha256((ROOT / file).read_bytes()).hexdigest() == info['sha256'], file
    assert len(plan['cases']) == len({c['id'] for c in plan['cases']}) == 50
    assert sum(len(p['caseIds']) for p in plan['phases']) == 65
    assert plan['maximumProviderAttempts'] == 300 and plan['temporaryAccounts'] == 3
    if not args.execute:
        print(json.dumps({'status': 'PREPARED', 'uniqueQuestions': 50, 'maximumRequests': 65, 'maximumAttempts': 300,
                          'planSha256': hashlib.sha256(packet.read_bytes()).hexdigest()}))
        return
    out = ROOT / 'logs/backend' / ('rag-timing50-live-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    print('Evidence: ' + str(out), flush=True)
    accounts, rows = [], []
    lock = threading.Lock()
    stop = threading.Event()
    since = datetime.now(timezone.utc).isoformat()
    summary = {'status': 'PREFLIGHT', 'planSha256': hashlib.sha256(packet.read_bytes()).hexdigest(),
               'maxProviderAttempts': 300, 'maxBusinessRequests': 65, 'maximumConcurrency': 3,
               'accountsCleanedUp': False, 'cases': rows}
    def save(name, data):
        (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    def capture_timings():
        result = subprocess.run(['docker', 'logs', '--timestamps', '--since', since, 'mealcheck-backend'],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        result.check_returncode()
        ids = {r['requestId'] for r in rows if r.get('requestId')}
        raw = result.stdout + '\n' + result.stderr
        events = parse_timings(raw, ids)
        for row in rows:
            row['timingEvents'] = [e for e in events if e['requestId'] == row.get('requestId')]
            failures = [line for line in raw.splitlines() if row.get('requestId') and row['requestId'] in line and 'AI HTTP attempt failed.' in line]
            row['httpFailures'] = [dict(zip(['operation', 'reason', 'status'], m.groups())) for line in failures
                                   if (m := re.search(r'operation=([^,]+), reason=([^,]+), status=(\d+)', line))]
        save('timing-events.json', events)
    try:
        assert http('/actuator/health')['status'] == 'UP'
        inventory = json.loads(sql("SELECT json_build_object('chunks',count(*),'compatible',count(*) FILTER (WHERE embedding_signature=(SELECT signature FROM knowledge_embedding_settings WHERE id=1)),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks"))
        assert inventory == {'chunks': 1351, 'compatible': 1351, 'signatures': 1}
        embedding = json.loads(sql("SELECT json_build_object('mode',s.mode,'model',p.model,'dimension',p.dimension,'baseUrl',p.base_url) FROM knowledge_embedding_settings s JOIN knowledge_embedding_profiles p ON p.id=s.active_profile_id WHERE s.id=1"))
        assert embedding == {'mode': 'SEMANTIC', 'model': 'BAAI/bge-m3', 'dimension': 1024, 'baseUrl': 'https://api.siliconflow.cn/v1'}
        backend = json.loads(subprocess.check_output(['docker', 'inspect', 'mealcheck-backend'], encoding='utf-8'))[0]
        env = dict(v.split('=', 1) for v in backend['Config']['Env'] if '=' in v)
        required = {'MEALCHECK_ASSISTANT_TOOLS_TIMEOUT_MILLIS': '15000', 'MEALCHECK_ASSISTANT_TOOLS_TOTAL_TIMEOUT_MILLIS': '20000',
                    'MEALCHECK_ASSISTANT_FUNCTION_CALLING_TOTAL_TIMEOUT_SECONDS': '90', 'MEALCHECK_AI_OPERATION_TIMEOUT_SECONDS': '120',
                    'MEALCHECK_KNOWLEDGE_AUTO_INDEX': 'false', 'MEALCHECK_AI_DAILY_CALLS': '100',
                    'MEALCHECK_KNOWLEDGE_RERANK_ENABLED': 'true', 'MEALCHECK_KNOWLEDGE_RERANK_MODEL': 'qwen3.7-text-rerank'}
        assert all(env.get(k) == v for k, v in required.items()), 'PUBLIC_CONFIGURATION_CHANGED'
        summary.update({'embedding': embedding, 'inventoryBefore': inventory, 'backendImage': backend['Image'],
                        'publicConfiguration': required, 'jobsBefore': int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs'))})
        for index in range(3):
            username, password = 'timing50_' + secrets.token_hex(8), secrets.token_urlsafe(24)
            captcha = http('/api/auth/captcha')
            numbers = re.fullmatch(r'(\d+) \+ (\d+) = \?', captcha['question'])
            assert numbers, 'CAPTCHA_CHANGED'
            auth = http('/api/auth/register', body={'username': username, 'password': password, 'displayName': '阶段耗时临时测试',
                        'captchaId': captcha['captchaId'], 'captchaAnswer': str(sum(map(int, numbers.groups())))})
            user_id = int(sql("SELECT id FROM users WHERE username='" + username + "'"))
            accounts.append({'index': index, 'username': username, 'password': password, 'token': auth['token'], 'id': user_id})
        sql('UPDATE users SET role=\'ADMIN\' WHERE id=' + str(accounts[0]['id']))
        settings = http('/api/admin/ai-settings', accounts[0]['token'])
        assert settings['model'] == 'qwen3.8-flash' and settings['baseUrl'].rstrip('/') == 'https://dashscope.aliyuncs.com/compatible-mode/v1'
        save('preflight.json', {k: v for k, v in summary.items() if k != 'cases'})
        by_id = {c['id']: c for c in plan['cases']}
        def run_one(phase, case_id, account, ordinal):
            if stop.is_set():
                return
            before = usage(account['id'])
            if before > 84:  # Leave room for the bounded production retries/repair; never raise normal user limits.
                with lock:
                    summary['stopReason'] = 'ACCOUNT_USAGE_CHANGED_AFTER_DISPATCH'
                stop.set(); return
            case = by_id[case_id]
            row = {'ordinal': ordinal, 'id': case_id, 'phase': phase, 'accountIndex': account['index'],
                   'question': case['question'], 'answerability': case['answerability'], 'attemptsBefore': before,
                   'startedAt': datetime.now(timezone.utc).isoformat()}
            try:
                row.update(chat(account['token'], case['question']))
                response = row.get('response') or {}
                row['requestId'] = response.get('requestId') or next((e['data'].get('requestId') for e in row['events']
                                                                  if isinstance(e['data'], dict) and e['data'].get('requestId')), None)
                row['transportComplete'] = bool(response) and response.get('deliveryStatus', 'COMPLETE') == 'COMPLETE'
                if row.get('requestId'):
                    recent = subprocess.run(['docker', 'logs', '--timestamps', '--since', row['startedAt'], 'mealcheck-backend'],
                                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=20)
                    own_failures = [line for line in (recent.stdout + '\n' + recent.stderr).splitlines()
                                    if row['requestId'] in line and 'AI HTTP attempt failed.' in line]
                    if any(int(m.group(1)) >= 400 for line in own_failures if (m := re.search(r'status=(\d+)', line))):
                        stop.set()
                        with lock:
                            summary['stopReason'] = 'PROVIDER_HTTP_FAILURE_OBSERVED'
                queries = [case['question']]
                for call in response.get('toolCalls', []):
                    if call['name'] == 'search_diet_knowledge':
                        queries.append(json.loads(call['arguments'])['query'])
                row['retrievalCaches'] = cache_for_queries(queries)
                if response.get('conversationId'):
                    row['persistedConversation'] = http('/api/assistant/conversations/' + str(response['conversationId']), account['token'])
                if row.get('requestId'):
                    for _ in range(15):
                        status = http('/api/assistant/requests/' + row['requestId'], account['token'])
                        if status.get('state') not in ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED'):
                            break
                        time.sleep(.1)
            except Exception as error:
                row['errorType'] = type(error).__name__
                row['transportComplete'] = False
                stop.set()
                with lock:
                    summary['stopReason'] = 'REQUEST_EXECUTION_ERROR'
            finally:
                row['attemptsAfter'] = usage(account['id'])
                row['providerAttempts'] = row['attemptsAfter'] - before
                row['finishedAt'] = datetime.now(timezone.utc).isoformat()
                with lock:
                    rows.append(row); save(f'{ordinal:03d}-{phase}-{case_id}.json', row)
                    print(f'{ordinal:02d} {phase} {case_id} complete={row["transportComplete"]} calls={row["providerAttempts"]}', flush=True)
        summary['status'] = 'RUNNING'
        ordinal = 0
        for phase in plan['phases']:
            if stop.is_set():
                break
            if phase['name'] == 'matched_cold_concurrent':
                save('targeted-cache-evictions.json', {'questions': phase['caseIds'],
                     'keysRemoved': evict_exact_queries([by_id[i]['question'] for i in phase['caseIds']])})
            tasks = []
            for case_id in phase['caseIds']:
                ordinal += 1
                tasks.append((phase['name'], case_id, ordinal))
            completed = dispatch_with_reserve(tasks, accounts, phase['concurrency'], usage,
                lambda task, account: run_one(task[0], task[1], account, task[2]), stop.is_set)
            if not completed and not stop.is_set():
                summary['stopReason'] = 'ALL_ACCOUNTS_RESERVE_LOW'
                stop.set()
            capture_timings()
            if any(int(f['status']) >= 400 for r in rows for f in r.get('httpFailures', [])):
                stop.set(); summary['stopReason'] = 'PROVIDER_HTTP_FAILURE_OBSERVED'
            save('checkpoint.json', summary)
        summary['status'] = 'COMPLETED_PENDING_ANALYSIS' if len(rows) == 65 else 'STOPPED'
    except Exception as error:
        summary.update({'status': 'STOPPED', 'errorType': type(error).__name__, 'errorCode': str(error)[:180]})
    finally:
        if rows:
            try:
                capture_timings()
            except Exception as error:
                summary['timingCaptureError'] = type(error).__name__
        summary['accountAttempts'] = [usage(a['id']) for a in accounts]
        summary['totalProviderAttempts'] = sum(summary['accountAttempts'])
        assert summary['totalProviderAttempts'] <= 300 and all(v <= 100 for v in summary['accountAttempts'])
        cleaned = []
        for account in accounts:
            try:
                sql('UPDATE users SET role=\'USER\' WHERE id=' + str(account['id']))
                http('/api/profile/account', account['token'], {'password': account['password']}, method='DELETE')
                cleaned.append(sql('SELECT count(*) FROM users WHERE id=' + str(account['id'])) == '0')
            except Exception as error:
                cleaned.append(False)
                summary.setdefault('cleanupErrors', []).append({'accountIndex': account['index'], 'errorType': type(error).__name__})
        summary['accountsCleanedUp'] = len(cleaned) == 3 and all(cleaned)
        summary['completedUniqueQuestions'] = len({r['id'] for r in rows})
        summary['completedBusinessRequests'] = len(rows)
        summary['inventoryAfter'] = json.loads(sql("SELECT json_build_object('chunks',count(*),'signatures',count(DISTINCT embedding_signature)) FROM knowledge_chunks"))
        summary['jobsAfter'] = int(sql('SELECT count(*) FROM knowledge_embedding_rebuild_jobs'))
        summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
        save('results.json', summary)
        print(json.dumps({k: summary.get(k) for k in ['status', 'completedUniqueQuestions', 'completedBusinessRequests',
              'totalProviderAttempts', 'accountsCleanedUp', 'stopReason']}, ensure_ascii=True), flush=True)
    if summary['status'] == 'STOPPED' or not summary['accountsCleanedUp']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
