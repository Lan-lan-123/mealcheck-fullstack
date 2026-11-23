"""Replay recorded real answers into an isolated temporary account; no model or question requests."""
import json
from pathlib import Path
import re
import secrets
import subprocess
from datetime import datetime
from run_full_chain_pilot10 import ROOT, http, sql


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recorded', type=Path, required=True)
    args = parser.parse_args()
    recorded = json.loads(args.recorded.read_text(encoding='utf-8'))
    assert len(recorded['cases']) == 10 and recorded['accountCleanedUp']
    out = ROOT / 'logs/backend' / ('pilot10-reference-history-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    username, password = 'pilot10_refs_' + secrets.token_hex(6), secrets.token_urlsafe(24)
    token = user_id = None
    report = {'mode': 'STORED_ACTUAL_OUTPUT_REPLAY', 'providerRequests': 0, 'cases': [], 'accountCleanedUp': False}
    try:
        captcha = http('/api/auth/captcha')
        operands = re.fullmatch(r'(\d+) \+ (\d+) = \?', captcha['question'])
        auth = http('/api/auth/register', body={'username': username, 'password': password, 'displayName': '引用历史恢复验证',
                    'captchaId': captcha['captchaId'], 'captchaAnswer': str(sum(map(int, operands.groups())))})
        token = auth['token']
        user_id = int(sql('SELECT id FROM users WHERE username=' + quote(username)))
        for original in recorded['cases']:
            response = original['response'].copy()
            title = original['persistedConversation']['title']
            conversation = int(sql('INSERT INTO assistant_conversations(user_id,title,created_at,updated_at) VALUES (' +
                                   str(user_id) + ',' + quote(title) + ',now(),now()) RETURNING id').splitlines()[0])
            for role, text, refs in [('user', original['question'], None), ('assistant', response['answer'], response['references'])]:
                sql('INSERT INTO assistant_conversation_messages(conversation_id,role,text,created_at,delivery_status,references_json) VALUES (' +
                    str(conversation) + ',' + quote(role) + ',' + quote(text) + ",now(),'COMPLETE'," +
                    ('NULL' if refs is None else quote(json.dumps(refs, ensure_ascii=False))) + ')')
            actual = http('/api/assistant/conversations/' + str(conversation), token)
            restored = actual['messages'][-1]
            assert restored['text'] == response['answer'] and restored['references'] == response['references']
            response['conversationId'] = conversation
            report['cases'].append({**original, 'response': response, 'persistedConversation': actual})
        payload = {'token': token, 'username': username, 'userId': user_id, 'cases': report['cases'],
                   'requireReferenceHistory': True, 'output': str(out)}
        result = subprocess.run(['node', 'deploy/scripts/verify_full_chain_pilot10_ui.mjs'], input=json.dumps(payload, ensure_ascii=False),
                                capture_output=True, text=True, encoding='utf-8', timeout=180, cwd=ROOT)
        report['uiExitCode'] = result.returncode
        report['uiOutput'] = result.stdout[-2000:]
        if result.returncode:
            report['uiError'] = result.stderr[-2000:]
            raise RuntimeError('REFERENCE_UI_FAILED')
    finally:
        if user_id is not None:
            http('/api/profile/account', token, {'password': password}, method='DELETE')
            report['accountCleanedUp'] = sql('SELECT count(*) FROM users WHERE id=' + str(user_id)) == '0'
        (out / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'evidence': str(out), 'verifiedCases': len(report['cases']), 'providerRequests': 0,
                          'accountCleanedUp': report['accountCleanedUp'], 'uiExitCode': report.get('uiExitCode')}, ensure_ascii=True))


if __name__ == '__main__':
    main()
