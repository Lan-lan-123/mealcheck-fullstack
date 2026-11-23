"""Cookie/CSRF-aware transport for the repository's private CLI verification clients.

Only /api/ requests are adapted. For old CLI account-file consumers, successful
login/register JSON gains a client-local `token` extracted from Set-Cookie; the
server never returns that field to browser JavaScript. Never print credentials.
"""
import io
import json
from http.cookies import SimpleCookie
import threading
import urllib.request as _urllib
from urllib.parse import urlsplit

_csrf = {}
_lock = threading.Lock()


def _base(url):
    parts = urlsplit(url)
    if '/api/' not in parts.path:
        return None
    return f'{parts.scheme}://{parts.netloc}' + parts.path.split('/api/', 1)[0]


def _cookies(headers):
    values = headers.get_all('Set-Cookie') if hasattr(headers, 'get_all') else [headers.get('Set-Cookie', '')]
    result = {}
    for value in values or []:
        parsed = SimpleCookie()
        parsed.load(value)
        result.update({key: item.value for key, item in parsed.items()})
    return result


def _csrf_headers(url, headers, timeout=15):
    base = _base(url)
    if base is None:
        return headers
    if isinstance(timeout, tuple): timeout = max(timeout)
    if timeout is None: timeout = 15
    with _lock:
        if base not in _csrf:
            with _urllib.urlopen(base + '/api/auth/csrf', timeout=timeout) as response:
                payload = json.loads(response.read())
                cookies = _cookies(response.headers)
            name = next((key for key in ('__Host-mealcheck_csrf', 'mealcheck_csrf') if cookies.get(key)), None)
            if not name or payload.get('headerName') != 'X-XSRF-TOKEN' or not payload.get('token'):
                raise RuntimeError('CSRF bootstrap did not return the expected token and cookie')
            _csrf[base] = (name, cookies[name], payload['token'])
        name, cookie, token = _csrf[base]
    result = dict(headers or {})
    cookie_header = next((result.pop(key) for key in list(result) if key.lower() == 'cookie'), '')
    parsed = SimpleCookie(); parsed.load(cookie_header); parsed[name] = cookie
    result['Cookie'] = '; '.join(f'{key}={item.value}' for key, item in parsed.items())
    result['X-XSRF-TOKEN'] = token
    return result


def _login_payload(url, payload, headers):
    if urlsplit(url).path.rstrip('/').endswith(('/api/auth/login', '/api/auth/register')) and isinstance(payload, dict):
        cookies = _cookies(headers)
        token = cookies.get('__Host-mealcheck_session') or cookies.get('mealcheck_session')
        if token:
            return {**payload, 'token': token}
    return payload


class _UrlResponse:
    def __init__(self, response, body):
        self._response = response
        self._body = io.BytesIO(body)
    def __getattr__(self, name):
        return getattr(self._response, name)
    def read(self, *args):
        return self._body.read(*args)
    def __enter__(self):
        return self
    def __exit__(self, *args):
        self._response.close()


def urlopen(request, *args, **kwargs):
    url = request.full_url if isinstance(request, _urllib.Request) else request
    if isinstance(request, _urllib.Request) and request.get_method() not in ('GET', 'HEAD', 'OPTIONS'):
        for key, value in _csrf_headers(url, dict(request.header_items()), kwargs.get('timeout', 15)).items():
            request.add_header(key, value)
    response = _urllib.urlopen(request, *args, **kwargs)
    if urlsplit(url).path.rstrip('/').endswith(('/api/auth/login', '/api/auth/register')):
        payload = _login_payload(url, json.loads(response.read()), response.headers)
        return _UrlResponse(response, json.dumps(payload).encode())
    return response


class _RequestsResponse:
    def __init__(self, response):
        self._response = response
    def __getattr__(self, name):
        return getattr(self._response, name)
    def json(self, **kwargs):
        return _login_payload(self._response.url, self._response.json(**kwargs), self._response.headers)
    def __enter__(self):
        return self
    def __exit__(self, *args):
        self._response.close()


def request(method, url, **kwargs):
    import requests as transport
    if method.upper() not in ('GET', 'HEAD', 'OPTIONS'):
        kwargs['headers'] = _csrf_headers(url, kwargs.get('headers'), kwargs.get('timeout', 15))
    return _RequestsResponse(transport.request(method, url, **kwargs))


def get(url, **kwargs): return request('GET', url, **kwargs)
def post(url, **kwargs): return request('POST', url, **kwargs)
def put(url, **kwargs): return request('PUT', url, **kwargs)
def delete(url, **kwargs): return request('DELETE', url, **kwargs)
def patch(url, **kwargs): return request('PATCH', url, **kwargs)


class Session:
    def __init__(self):
        import requests as transport
        self._session = transport.Session()
    def __getattr__(self, name): return getattr(self._session, name)
    def __setattr__(self, name, value):
        if name == '_session': object.__setattr__(self, name, value)
        else: setattr(self._session, name, value)
    def request(self, method, url, **kwargs):
        if method.upper() not in ('GET', 'HEAD', 'OPTIONS') and _base(url):
            headers = dict(kwargs.get('headers') or {})
            prepared = self._session.prepare_request(__import__('requests').Request('GET', url, headers=headers))
            if prepared.headers.get('Cookie'):
                headers['Cookie'] = prepared.headers['Cookie']
            kwargs['headers'] = _csrf_headers(url, headers, kwargs.get('timeout', 15))
        return _RequestsResponse(self._session.request(method, url, **kwargs))
    def get(self, url, **kwargs): return self.request('GET', url, **kwargs)
    def post(self, url, **kwargs): return self.request('POST', url, **kwargs)
    def put(self, url, **kwargs): return self.request('PUT', url, **kwargs)
    def delete(self, url, **kwargs): return self.request('DELETE', url, **kwargs)
    def close(self): self._session.close()
    def __enter__(self): return self
    def __exit__(self, *args): self.close()


def __getattr__(name):
    import requests as transport
    return getattr(transport, name)
