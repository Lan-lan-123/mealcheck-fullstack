"""Verify the local RAG corpus and admin pagination using a disposable account.

Only the account registered by this invocation is promoted and removed. Existing
accounts, meals and knowledge content are not edited. Search hit statistics may
increase. External embedding and reranker keys must be empty for this smoke test.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import secrets
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import recovery


def verify(args):
    args.output.mkdir(parents=True, exist_ok=False)
    username = 'rag_verify_' + secrets.token_hex(8)
    password = secrets.token_urlsafe(24)
    token = None
    user_id = None
    checks = []
    completed = False
    baseline = recovery.query(args.postgres,
            'SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM meal_records)')

    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise RuntimeError('RAG verification failed: ' + name)

    def http(method, path, body=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = Request(args.base_url.rstrip('/') + path, method=method,
                headers=headers, data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except HTTPError as error:
            status, raw = error.code, error.read()
        return status, json.loads(raw) if raw else None

    try:
        recovery.run(['docker', 'exec', args.backend, 'sh', '-c',
                'test -z "$MEALCHECK_KNOWLEDGE_EMBEDDING_API_KEY" '
                '&& test -z "$MEALCHECK_KNOWLEDGE_RERANK_API_KEY"'])
        check('no external embedding or reranker credentials used', True)
        status, captcha = http('GET', '/api/auth/captcha')
        operands = re.fullmatch(r'(\d+) \+ (\d+) = \?', captcha['question'])
        check('captcha is available', status == 200 and operands is not None)
        status, auth = http('POST', '/api/auth/register', {
            'username': username, 'password': password, 'displayName': 'RAG verification fixture',
            'captchaId': captcha['captchaId'],
            'captchaAnswer': str(sum(map(int, operands.groups())))})
        check('disposable account registered', status in (200, 201) and bool(auth.get('token')))
        token = auth['token']
        user_id = int(recovery.query(args.postgres,
                "SELECT id FROM users WHERE username='" + username + "'"))
        status, _ = http('GET', '/api/admin/knowledge/chunks')
        check('ordinary user cannot access admin knowledge', status == 403)
        recovery.query(args.postgres,
                "UPDATE users SET role='ADMIN' WHERE id=" + str(user_id)
                + " AND username='" + username + "'")

        status, stats = http('GET', '/api/admin/stats')
        check('admin overview reports at least the expected system count',
                status == 200 and stats['knowledgeChunkCount'] >= args.expected_count)
        all_chunks = []
        page = 0
        while True:
            status, result = http('GET', '/api/admin/knowledge/chunks?' + urlencode({
                'source': 'diet_guides.md', 'page': page, 'size': 20}))
            check('admin page ' + str(page) + ' reports the expected total',
                    status == 200 and result['totalElements'] == args.expected_count)
            all_chunks.extend(result['content'])
            page += 1
            if page >= result['totalPages']:
                break
        check('all system topics are reachable through pagination',
                len(all_chunks) == args.expected_count
                and len({chunk['id'] for chunk in all_chunks}) == args.expected_count)
        check('topics have distinct titles and content hashes',
                len({chunk['title'] for chunk in all_chunks}) == args.expected_count
                and len({chunk['contentHash'] for chunk in all_chunks}) == args.expected_count)
        check('source version is current',
                all(chunk['sourceVersion'] == 'v3-curated-100' for chunk in all_chunks))
        for topic in ['乳糖不耐受的替代选择', '低预算食堂选餐', '剩饭剩菜的及时冷藏', '营养标签的计量单位']:
            status, result = http('GET', '/api/knowledge/search?' + urlencode({'q': topic, 'limit': 5}))
            check('new topic is retrievable: ' + topic,
                    status == 200 and any(topic in hit['title'] for hit in result['results'][:3]))
        completed = True
    finally:
        if user_id is not None:
            recovery.query(args.postgres,
                    "UPDATE users SET role='USER' WHERE id=" + str(user_id)
                    + " AND username='" + username + "'")
            status, _ = http('DELETE', '/api/profile/account', {'password': password})
            if status != 204:
                recovery.query(args.postgres, 'DELETE FROM users WHERE id=' + str(user_id)
                        + " AND username='" + username + "'")
            check('disposable account removed', recovery.query(args.postgres,
                    "SELECT count(*) FROM users WHERE username='" + username + "'") == '0')
        unchanged = recovery.query(args.postgres,
                'SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM meal_records)') == baseline
        checks.append({'name': 'existing account and meal counts preserved', 'passed': unchanged})
        report = {'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
                'scope': 'local-rag-corpus-and-admin-api',
                'passed': completed and all(item['passed'] for item in checks),
                'expectedSystemChunks': args.expected_count, 'checks': checks,
                'limitations': ['Development hash vectors; real model quality is not evaluated.']}
        (args.output / 'verification.json').write_text(
                json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'checks': len(checks),
            'report': str(args.output / 'verification.json')}))
    if not report['passed']:
        raise RuntimeError('RAG verification did not pass')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:5173')
    parser.add_argument('--postgres', default='mealcheck-postgres')
    parser.add_argument('--backend', default='mealcheck-backend')
    parser.add_argument('--expected-count', type=int, default=100)
    parser.add_argument('--output', type=Path, required=True)
    verify(parser.parse_args())
