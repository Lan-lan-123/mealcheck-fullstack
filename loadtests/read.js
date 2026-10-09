import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const token = (__ENV.TOKEN || '').trim();

if (!token) {
  throw new Error('Set TOKEN to a test account JWT before running this script.');
}

export const options = {
  vus: 1,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/meals?page=0&size=10`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'meal_list' },
    timeout: '10s',
  });

  check(response, {
    'meal list returns 200': r => r.status === 200,
    'meal list returns JSON': r =>
      (r.headers['Content-Type'] || '').includes('application/json'),
  });

  sleep(1);
}
