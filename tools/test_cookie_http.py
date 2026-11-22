import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import unittest
from urllib.request import Request
import cookie_http


class CookieTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                self.send_response(200)
                self.send_header('Set-Cookie', 'mealcheck_csrf=test-cookie; HttpOnly; Path=/; SameSite=Lax')
                self.end_headers()
                self.wfile.write(json.dumps({'token': 'masked-csrf', 'headerName': 'X-XSRF-TOKEN'}).encode())
            def do_POST(self):
                if self.headers.get('X-XSRF-TOKEN') != 'masked-csrf' or 'mealcheck_csrf=test-cookie' not in self.headers.get('Cookie', ''):
                    self.send_error(403); return
                self.send_response(200)
                self.send_header('Set-Cookie', 'mealcheck_session=private-tool-token; HttpOnly; Path=/; SameSite=Lax')
                self.send_header('Set-Cookie', 'mealcheck_csrf=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Lax')
                self.end_headers()
                self.wfile.write(json.dumps({'username': 'fixture', 'role': 'USER'}).encode())
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f'http://127.0.0.1:{cls.server.server_port}'
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()
    def test_urllib_cli_extracts_private_credential_from_cookie_and_attaches_csrf(self):
        with cookie_http.urlopen(Request(self.base+'/api/auth/login', data=b'{}'), timeout=3) as response:
            self.assertEqual(json.load(response)['token'], 'private-tool-token')
    def test_requests_cli_and_session_both_attach_csrf(self):
        self.assertEqual(cookie_http.post(self.base+'/api/auth/register', json={}, timeout=3).json()['token'], 'private-tool-token')
        with cookie_http.Session() as session:
            self.assertEqual(session.post(self.base+'/api/auth/login', json={}, timeout=3).json()['token'], 'private-tool-token')
    def test_non_api_model_calls_keep_their_original_transport(self):
        self.assertIsNone(cookie_http._base('https://provider.example/v1/chat/completions'))
    def test_cookie_merge_preserves_authentication_and_uses_the_current_csrf_cookie(self):
        headers = cookie_http._csrf_headers(self.base+'/api/profile/ai-cost-settings', {'Cookie': 'mealcheck_session=session; mealcheck_csrf=stale'})
        self.assertIn('mealcheck_session=session', headers['Cookie'])
        self.assertIn('mealcheck_csrf=test-cookie', headers['Cookie'])


if __name__ == '__main__': unittest.main()
