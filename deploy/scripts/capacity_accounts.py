"""Create/delete dedicated load-test accounts; keep their JWTs in a private file."""
import argparse
import json
import os
import re
from pathlib import Path
import uuid
import smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--count', type=int, default=5, help='Total number of accounts to prepare (default: 5)')
    args = parser.parse_args()
    if args.count < 1: parser.error('--count must be positive')
    if args.cleanup:
        for account in json.loads(args.output.read_text(encoding='utf-8')):
            smoke.check(args.base_url, '/api/profile/account', method='DELETE', body={'password': account['password']}, token=account['token'], expected=204)
        args.output.unlink(); print('Load-test accounts deleted'); return
    accounts = json.loads(args.output.read_text(encoding='utf-8')) if args.resume and args.output.exists() else []
    # Persist after each creation so a failed preparation can still be cleaned up.
    if args.output.exists() and not args.resume: raise RuntimeError('Use a new output file, --resume, or --cleanup')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for index in range(len(accounts), args.count):
        captcha = smoke.check(args.base_url, '/api/auth/captcha')
        match = re.fullmatch(r'(\d+)\s*\+\s*(\d+)\s*=\s*\?', captcha['question'])
        if not match: raise RuntimeError('Unexpected captcha format')
        password = 'Capacity-' + uuid.uuid4().hex
        value = smoke.check(args.base_url, '/api/auth/register', method='POST', body={'username': 'capacity_' + uuid.uuid4().hex[:16], 'displayName': 'Capacity fixture', 'password': password,
            'captchaId': captcha['captchaId'], 'captchaAnswer': str(int(match[1]) + int(match[2]))})
        accounts.append({'token': value['token'], 'password': password})
        args.output.write_text(json.dumps(accounts), encoding='utf-8')
        if os.name != 'nt': args.output.chmod(0o600)
    print(f'Prepared {len(accounts)} dedicated accounts')


if __name__ == '__main__': main()
