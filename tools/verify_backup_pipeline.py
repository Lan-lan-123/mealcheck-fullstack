"""Verify actual rclone crypt + DB/image restoration on local scratch storage.
This proves the protocol, not cloud credentials or cross-region availability.
"""
import json
import os
from pathlib import Path
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    root = Path(__file__).resolve().parents[1]
    fixture = root / 'deploy/recovery-results/rclone-fixture'
    env = json.loads((fixture / 'fixture-env.json').read_text(encoding='utf-8'))
    runtime = dict(os.environ)
    runtime.update(PATH=env['binaryDir'] + os.pathsep + runtime['PATH'], RCLONE_CONFIG=env['config'],
                   MEALCHECK_BACKUP_REMOTE='fixturecrypt:mealcheck-backups', MEALCHECK_SMOKE_REDIS_IMAGE='redis:7-alpine')
    received = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            received.append(self.rfile.read(int(self.headers['Content-Length'])).decode())
            self.send_response(202); self.end_headers()
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    runtime['MEALCHECK_BACKUP_PUSHGATEWAY'] = f'http://127.0.0.1:{server.server_port}'
    try:
        base = ['python', 'deploy/scripts/operations_backup.py', '--env-file', 'deploy/smoke.env', '--project', 'mealcheck-reliability-smoke',
                '--compose', 'docker-compose.prod.yml', '--compose', 'deploy/compose.smoke.yml', '--compose', 'deploy/compose.capacity.yml']
        for mode in ['backup', 'restore']:
            subprocess.run(base + ['--mode', mode, '--output', str(fixture / mode)], cwd=root, env=runtime, check=True)
        storage = fixture / 'encrypted-storage'
        if os.name == 'nt': storage = Path('\\\\?\\' + str(storage))
        encrypted = list(storage.rglob('*'))
        assert any(path.is_file() for path in encrypted)
        assert not any(path.name in ['manifest.json', 'postgres.dump'] for path in encrypted)
        assert all(path.read_bytes().startswith(b'RCLONE\x00\x00') for path in encrypted if path.is_file())
        assert len(received) == 2 and all('_success 1' in data for data in received)
        report={'passed': True, 'encryptionVerified': True, 'remoteReadbackVerified': True, 'restoreVerified': True, 'metricsPublished': True, 'storage': 'local-test-fixture'}
        (fixture/'verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps(report))
    finally: server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': main()
