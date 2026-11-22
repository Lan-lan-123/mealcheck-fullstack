import http from 'k6/http';

let csrf;
export function authHeaders(base, token) {
  if (!csrf) {
    const response = http.get(`${base}/api/auth/csrf`, { tags: { operation: 'csrf-bootstrap' }, timeout: '10s' });
    if (response.status !== 200) throw new Error('CSRF bootstrap failed');
    const payload = response.json();
    const name = response.cookies['__Host-mealcheck_csrf'] ? '__Host-mealcheck_csrf' : 'mealcheck_csrf';
    const cookie = response.cookies[name]?.[0]?.value;
    if (!cookie || payload.headerName !== 'X-XSRF-TOKEN' || !payload.token) throw new Error('Invalid CSRF bootstrap response');
    csrf = { token: payload.token, cookie: `${name}=${cookie}` };
  }
  return { Authorization: `Bearer ${token}`, Cookie: csrf.cookie, 'X-XSRF-TOKEN': csrf.token };
}
