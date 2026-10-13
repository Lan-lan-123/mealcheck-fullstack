"""Verify a running local deployment with a disposable account and labelled fixture.

No model calls or existing user-data mutations. Credentials stay in memory.
The optional PostgreSQL fixture is created only for the newly registered account
and removed through account deletion. This is an engineering smoke test.
"""
import argparse
import base64
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import re
import secrets
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import recovery


def verify(base_url, postgres, backend, output, browser=False):
    output.mkdir(parents=True, exist_ok=False)
    checks = []
    token = None
    username = 'deploy_' + secrets.token_hex(8)
    password = secrets.token_urlsafe(24)
    user_id = None
    fixture_path = None
    completed = False
    original_users = int(recovery.query(postgres, 'SELECT count(*) FROM users'))
    original_meals = int(recovery.query(postgres, 'SELECT count(*) FROM meal_records'))

    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise RuntimeError('Deployment check failed: ' + name)

    def http(method, path, body=None, authenticated=True):
        headers = {'Content-Type': 'application/json'}
        if token and authenticated:
            headers['Authorization'] = 'Bearer ' + token
        request = Request(base_url.rstrip('/') + path, method=method, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(request, timeout=20) as response:
                status, content = response.status, response.read()
        except HTTPError as error:
            status, content = error.code, error.read()
        try:
            data = json.loads(content)
        except (ValueError, UnicodeDecodeError):
            data = content
        return status, data

    try:
        status, html = http('GET', '/', authenticated=False)
        check('frontend served through Nginx', status == 200 and b'id="root"' in html)
        asset = re.search(rb'src="([^"]+\.js)"', html).group(1).decode()
        status, bundle = http('GET', asset, authenticated=False)
        check('deployed bundle includes optional nutrition UI', status == 200 and '营养估算不准确'.encode() in bundle)
        status, _ = http('GET', '/api/meals', authenticated=False)
        check('unauthenticated access rejected', status == 401)

        status, captcha = http('GET', '/api/auth/captcha', authenticated=False)
        operands = re.fullmatch(r'(\d+) \+ (\d+) = \?', captcha['question'])
        check('Redis-backed captcha available', status == 200 and operands is not None)
        status, auth = http('POST', '/api/auth/register', {
            'username': username, 'password': password, 'displayName': '部署验收临时账户',
            'captchaId': captcha['captchaId'], 'captchaAnswer': str(sum(map(int, operands.groups())))
        }, authenticated=False)
        check('temporary user registration works', status in (200, 201) and isinstance(auth.get('token'), str))
        token = auth['token']
        user_id = int(recovery.query(postgres, "SELECT id FROM users WHERE username='" + username + "'"))
        status, health = http('GET', '/api/health')
        check('proxied health reports database, Redis and writable uploads', status == 200 and health['status'] == 'UP'
              and health['databaseReachable'] and health['redisReachable'] and health['uploadDirectoryWritable'])
        status, _ = http('GET', '/api/meals?page=0&size=5')
        check('authenticated meal query works', status == 200)
        status, _ = http('GET', '/api/profile/ai-budget')
        check('AI budget query works without model credentials', status == 200)
        status, _ = http('GET', '/api/admin/stats')
        check('ordinary user cannot access admin API', status == 403)

        # A clearly labelled fixture supplies data for real HTTP nutrition verification.
        # It avoids real model costs and never changes an existing meal.
        fixture_path = 'deployment-smoke-' + secrets.token_hex(8) + '.png'
        image = output / 'fixture.png'
        image.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRZkAAAAASUVORK5CYII='))
        foods = json.dumps([{'name': 'Deployment smoke fixture (synthetic)', 'category': 'other', 'confidence': 1}])
        meal_id = int(recovery.query(postgres,
            "INSERT INTO meal_records(user_id,score,goal,summary,detected_foods_json,stored_image_path,created_at) VALUES ("
            + str(user_id) + ",80,'balanced','Synthetic deployment fixture','" + foods + "','" + fixture_path
            + "',CURRENT_TIMESTAMP AT TIME ZONE 'UTC') RETURNING id").splitlines()[0])
        recovery.run(['docker', 'cp', str(image), backend + ':/app/uploads/' + fixture_path])
        path = '/api/meals/' + str(meal_id) + '/nutrition'
        status, image_data = http('GET', '/api/meals/' + str(meal_id) + '/image')
        check('authenticated image route serves the fixture', status == 200 and image_data.startswith(b'\x89PNG'))
        status, nutrition = http('GET', path)
        check('nutrition disabled by default', status == 200 and not nutrition['enabled'] and nutrition['totals'] is None)
        partial = {'enabled': True, 'expectedRevision': 0, 'expectedVersion': 0,
                   'items': [{'foodIndex': 0, 'grams': 150, 'per100g': None, 'source': None}]}
        status, nutrition = http('PUT', path, partial)
        check('missing nutrients never generate a total', status == 200 and nutrition['status'] == 'INCOMPLETE' and nutrition['totals'] is None)
        complete = {'enabled': True, 'expectedRevision': 0, 'expectedVersion': 1,
                    'items': [{'foodIndex': 0, 'grams': 150, 'source': '部署验收虚构标签，仅用于测试',
                               'per100g': {'energyKcal': 200, 'proteinGrams': 10, 'fatGrams': 5, 'carbohydrateGrams': 20}}]}
        invalid = json.loads(json.dumps(complete))
        invalid['items'][0]['per100g']['energyKcal'] = -1
        status, _ = http('PUT', path, invalid)
        check('negative nutrients rejected without saving', status == 400)
        invalid = json.loads(json.dumps(complete))
        invalid['items'][0]['source'] = ' '
        status, _ = http('PUT', path, invalid)
        check('nutrient values require a meaningful source', status == 400)
        status, nutrition = http('PUT', path, complete)
        check('opt-in calculation persists and warns about inaccuracy', status == 200 and nutrition['status'] == 'ESTIMATED'
              and nutrition['totals']['energyKcal'] == 300 and '不准确' in nutrition['warning'])
        status, _ = http('PUT', path, complete)
        check('outdated version rejected', status == 409)
        zero = json.loads(json.dumps(complete))
        zero['expectedVersion'] = 2
        zero['items'][0]['per100g'] = dict.fromkeys(zero['items'][0]['per100g'], 0)
        status, nutrition = http('PUT', path, zero)
        check('explicit zeros remain known rather than missing', status == 200 and nutrition['status'] == 'ESTIMATED'
              and nutrition['totals']['energyKcal'] == 0)
        corrected_name = 'Corrected deployment fixture (synthetic)'
        status, correction = http('POST', '/api/meals/' + str(meal_id) + '/corrections', {
            'expectedRevision': 0, 'foods': [{'name': corrected_name, 'category': 'vegetable'}],
            'reason': 'Synthetic deployment verification only'})
        check('real correction API advances the meal revision', status == 200 and correction['revision'] == 1)
        status, nutrition = http('GET', path)
        check('food revision invalidates previous estimate', status == 200 and nutrition['status'] == 'STALE'
              and nutrition['totals'] is None and nutrition['items'][0]['grams'] is None)
        status, nutrition = http('PUT', path, {'enabled': False, 'expectedRevision': 1, 'expectedVersion': 3, 'items': []})
        check('disabling removes inputs and totals', status == 200 and not nutrition['enabled']
              and nutrition['totals'] is None and nutrition['items'][0]['grams'] is None)
        if browser:
            browser_script = Path(__file__).with_name('verify_local_browser.mjs')
            browser_config = {'baseUrl': base_url, 'auth': auth, 'foodName': corrected_name,
                              'output': str((output / 'browser').resolve()), 'phase': 'normal'}
            recovery.run(['node', str(browser_script)], data=json.dumps(browser_config).encode())
            check('running production mobile UI saves and clears estimates', True)
            # Only this run's labelled synthetic meal is altered to exercise empty state.
            recovery.query(postgres, "UPDATE meal_records SET detected_foods_json='[]',revision=revision+1 WHERE id=" + str(meal_id))
            browser_config['phase'] = 'empty'
            recovery.run(['node', str(browser_script)], data=json.dumps(browser_config).encode())
            check('running production UI blocks empty meal estimation', True)
        today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
        backdate = today - timedelta(days=10)
        status, manual = http('POST', '/api/meals/manual', {
            'occurredAt': str(backdate) + 'T00:01:00+08:00', 'mealType': 'LUNCH', 'goal': 'current',
            'note': 'Synthetic journal verification', 'foods': [{'name': '青菜（部署验证）', 'category': 'vegetable'}]})
        check('manual backdate is saved without image recognition', status == 200 and manual['recordSource'] == 'MANUAL'
              and manual['imageUrl'] is None and manual['timeConfirmed'])
        journal_id = manual['id']
        status, page = http('GET', '/api/meals?from=' + str(backdate) + '&to=' + str(backdate))
        check('actual meal date determines the history filter', status == 200 and any(m['id'] == journal_id for m in page['content']))
        metadata = {'occurredAt': str(today) + 'T00:02:00+08:00', 'mealType': 'DINNER', 'note': 'Synthetic edit', 'expectedVersion': 0}
        status, updated = http('PUT', '/api/meals/' + str(journal_id) + '/metadata', metadata)
        check('metadata edit retains independent food revision', status == 200 and updated['metadataVersion'] == 1 and updated['revision'] == 0)
        status, _ = http('PUT', '/api/meals/' + str(journal_id) + '/metadata', metadata)
        check('old metadata version is rejected', status == 409)
        status, _ = http('PUT', '/api/meals/skipped', {'day': str(today), 'mealType': 'BREAKFAST'})
        check('explicit skipped slot can be saved', status == 200)
        status, coverage = http('GET', '/api/meals/coverage?from=' + str(today) + '&to=' + str(today))
        slots = coverage['daily'][0]['slots']
        check('unknown and explicitly not eaten are distinct', status == 200 and slots['BREAKFAST'] == 'SKIPPED' and slots['LUNCH'] == 'UNKNOWN' and slots['DINNER'] == 'RECORDED')
        status, _ = http('POST', '/api/profile/behavior-goals', {'kind': 'MEALS_DAILY', 'target': 1,
            'startDate': str(today), 'endDate': str(today), 'active': True, 'expectedVersion': 0})
        check('behavior goal is saved', status == 200)
        status, coverage = http('GET', '/api/meals/coverage?from=' + str(today) + '&to=' + str(today))
        check('behavior target uses recorded meal slots without nutrition', status == 200 and coverage['goals'][0]['achieved'] and coverage['goals'][0]['completed'] == 1)
        status, plan = http('POST', '/api/plans', {'title': 'Synthetic plan', 'actionText': 'Add vegetables', 'sourceType': 'MEAL', 'sourceRecordId': journal_id, 'plannedDate': str(today)})
        check('owned meal advice can become a plan', status == 200 and plan['status'] == 'OPEN')
        status, plan = http('PUT', '/api/plans/' + str(plan['id']), {**plan, 'status': 'DONE', 'expectedVersion': 0})
        check('plan check-in is persisted', status == 200 and plan['completedAt'] is not None)
        status, report = http('GET', '/api/reports/weekly/latest')
        check('weekly report includes coverage goals and plan execution', status == 200 and report['tracking']['plans']['DONE'] == 1 and report['tracking']['goals'][0]['achieved'])
        # Never accidentally turn this engineering smoke test into a billable real-model run.
        recovery.run(['docker', 'exec', backend, 'sh', '-c', 'test -z "$MEALCHECK_AI_API_KEY"'])
        status, stream = http('POST', '/api/assistant/stream', {'question': '午餐怎样搭配更均衡？'})
        check('local assistant SSE passes through production Nginx', status == 200 and b'event:meta' in stream and b'event:delta' in stream and b'event:complete' in stream)
        event = None
        for block in stream.decode().replace('\r\n', '\n').split('\n\n'):
            if 'event:complete' in block:
                event = json.loads(next(line[5:] for line in block.splitlines() if line.startswith('data:')))
        check('answer has a persisted message and request identifier', event is not None and event['messageId'] and event['requestId'])
        status, _ = http('PUT', '/api/assistant/messages/' + str(event['messageId']) + '/feedback', {'verdict': 'HELPFUL', 'category': 'GENERAL', 'note': 'Synthetic feedback'})
        check('answer feedback is saved', status == 200)
        status, feedback = http('GET', '/api/assistant/messages/' + str(event['messageId']) + '/feedback')
        check('feedback links to the answer request ID', status == 200 and feedback['requestId'] == event['requestId'])
        status, _ = http('POST', '/api/plans', {'title': 'Synthetic assistant plan', 'actionText': 'Review the advice', 'sourceType': 'ASSISTANT', 'sourceMessageId': event['messageId'], 'plannedDate': str(today)})
        check('completed assistant answer can become an owned plan', status == 200)
        # Additional optimization fixtures belong only to this newly registered account.
        recovery.query(postgres, "INSERT INTO behavior_goals(user_id,kind,target,start_date,end_date,active) "
            "SELECT " + str(user_id) + ", 'MEALS_DAILY',1,DATE '2010-01-01'+n,DATE '2010-01-01'+n,false FROM generate_series(1,105) n")
        status, goals_page = http('GET', '/api/profile/behavior-goals?page=1&size=100')
        check('goal pagination exposes rows beyond the former limit', status == 200 and goals_page['totalElements'] == 106 and len(goals_page['content']) == 6)
        oldest = goals_page['content'][-1]
        status, _ = http('PUT', '/api/profile/behavior-goals/' + str(oldest['id']), {**oldest, 'target': 2, 'expectedVersion': oldest['version']})
        check('old goal can be edited by ID beyond the first page', status == 200)
        recovery.query(postgres, "INSERT INTO action_plans(user_id,title,action_text,source_type,planned_date) "
            "SELECT " + str(user_id) + ", 'Deployment paged plan '||n,'Synthetic action','MANUAL',DATE '" + str(today) + "' FROM generate_series(1,600) n")
        status, plans_page = http('GET', '/api/plans?from=' + str(today) + '&to=' + str(today) + '&page=6&size=100')
        check('plan pagination exposes rows beyond five hundred', status == 200 and plans_page['totalElements'] == 602 and len(plans_page['content']) == 2)
        status, original_report = http('GET', '/api/reports/weekly/latest')
        report_json = json.dumps(original_report, ensure_ascii=False).replace("'", "''")
        snapshot_id = int(recovery.query(postgres, "INSERT INTO weekly_report_snapshots(user_id,days,period_start,period_end,report_json) VALUES ("
            + str(user_id) + ",7,DATE '" + str(today - timedelta(days=6)) + "',DATE '" + str(today) + "','" + report_json + "') RETURNING id").splitlines()[0])
        status, _ = http('PUT', '/api/plans/' + str(plan['id']), {**plan, 'note': 'Synthetic note-only change', 'expectedVersion': plan['version']})
        check('plan note changes retain the saved report narrative', status == 200 and recovery.query(postgres, 'SELECT stale FROM weekly_report_snapshots WHERE id=' + str(snapshot_id)) == 'f')
        status, _ = http('PUT', '/api/meals/' + str(journal_id) + '/metadata', {**metadata, 'occurredAt': str(backdate) + 'T00:02:00+08:00', 'expectedVersion': 1})
        check('meal date changes mark related reports stale without deleting them', status == 200 and recovery.query(postgres, 'SELECT stale FROM weekly_report_snapshots WHERE id=' + str(snapshot_id)) == 't')
        status, archived = http('GET', '/api/reports/weekly/history/' + str(snapshot_id))
        check('historical snapshot preserves its original report content', status == 200 and archived['snapshot']['stale'] and archived['report']['reportText'] == original_report['reportText'])
        status, _ = http('PUT', '/api/meals/' + str(journal_id) + '/metadata', {**metadata, 'expectedVersion': 2})
        check('journal fixture is restored to today for browser verification', status == 200)
        status, fresh_report = http('GET', '/api/reports/weekly/latest')
        check('current report remains anchored to today rather than an archived period', status == 200 and fresh_report['periodEnd'] == str(today))
        status, history = http('GET', '/api/reports/weekly/history?page=0&size=20')
        check('history pagination exposes retained stale snapshots', status == 200 and history['totalElements'] == 1 and history['content'][0]['stale'])
        if browser:
            config = {'baseUrl': base_url, 'auth': auth, 'output': str((output / 'journal-browser').resolve())}
            recovery.run(['node', str(Path(__file__).with_name('verify_journal_browser.mjs'))], data=json.dumps(config).encode())
            check('running production journal goals plans and assistant UI verified', True)
        completed = True
    finally:
        if user_id is not None:
            status, _ = http('DELETE', '/api/profile/account', {'password': password})
            if status != 204:
                # Fallback is restricted to the account this run just registered.
                recovery.query(postgres, 'DELETE FROM meal_records WHERE user_id=' + str(user_id))
                recovery.query(postgres, 'DELETE FROM users WHERE id=' + str(user_id) + " AND username='" + username + "'")
                if fixture_path:
                    recovery.run(['docker', 'exec', backend, 'rm', '-f', '/app/uploads/' + fixture_path])
            checks.append({'name': 'account deletion API succeeds', 'passed': status == 204})
            check('temporary account removed', recovery.query(postgres, 'SELECT count(*) FROM users WHERE id=' + str(user_id)) == '0')
            if fixture_path:
                recovery.run(['docker', 'exec', backend, 'sh', '-c', 'test ! -e /app/uploads/' + fixture_path])
        check('original account and meal counts preserved',
              int(recovery.query(postgres, 'SELECT count(*) FROM users')) == original_users
              and int(recovery.query(postgres, 'SELECT count(*) FROM meal_records')) == original_meals)
        report = {'finishedAtUtc': datetime.now(timezone.utc).isoformat(), 'baseUrl': base_url,
                  'scope': 'local-running-deployment', 'passed': completed and all(item['passed'] for item in checks),
                  'originalUsers': original_users, 'originalMeals': original_meals, 'checks': checks,
                  'limitations': ['Real image recognition and provider billing are not verified.',
                                  'Nutrition fixture data is synthetic and was removed.']}
        (output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'checks': len(checks), 'report': str(output / 'verification.json')}))
    if not report['passed']:
        raise RuntimeError('Deployment verification or cleanup was incomplete; see verification.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:5173')
    parser.add_argument('--postgres', default='mealcheck-postgres')
    parser.add_argument('--backend', default='mealcheck-backend')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--browser', action='store_true', help='Also verify the deployed UI with local Playwright/Chromium')
    args = parser.parse_args()
    verify(args.base_url, args.postgres, args.backend, args.output, args.browser)
