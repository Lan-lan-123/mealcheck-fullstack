import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://localhost:18080').replace(/\/+$/, '');
const accounts = new SharedArray('read accounts', () =>
  JSON.parse(open(__ENV.ACCOUNTS_FILE).replace(/^\uFEFF/, '')));
if (!accounts.length) throw new Error('Prepare dedicated accounts first');
const minimumRecords = Number(__ENV.MIN_RECORDS || 1);
const requests = new Counter('meal_list_requests');
const success = new Rate('meal_list_success');
const latency = new Trend('meal_list_duration', true);
export const options = {
  vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '60s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'], meal_list_duration: ['p(95)<500'], checks: ['rate>0.99'],
    meal_list_success: ['rate>0.99'],
  },
};
export function setup() {
  for (const account of accounts) {
    const response = http.get(`${base}/api/meals?page=0&size=10`, {
      headers: { Authorization: `Bearer ${account.token}` }, tags: { name: 'account_preflight' },
    });
    if (response.status !== 200 || response.json().totalElements < minimumRecords) {
      throw new Error('Every test account must have a valid JWT and at least one saved meal');
    }
  }
}
export default function () {
  const account = accounts[(__VU - 1) % accounts.length];
  const response = http.get(`${base}/api/meals?page=0&size=10`, {
    headers: { Authorization: `Bearer ${account.token}` },
    tags: { name: 'meal_list' }, timeout: '10s',
  });
  let valid = false;
  try {
    const body = response.json();
    valid = Array.isArray(body.content) && body.totalElements >= 1;
  } catch (_) { /* check records the failure */ }
  requests.add(1);
  success.add(response.status === 200 && valid);
  latency.add(response.timings.duration);
  check(response, {
    'meal list returns 200': r => r.status === 200,
    'meal list contains saved records': () => valid,
  });
  sleep(1);
}
export function handleSummary(data) {
  return { [__ENV.RESULT_FILE]: JSON.stringify(data, null, 2) };
}
