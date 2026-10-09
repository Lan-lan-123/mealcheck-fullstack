import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend } from 'k6/metrics';
const base = (__ENV.BASE_URL || 'http://127.0.0.1:18081').replace(/\/+$/, '');
const accounts = new SharedArray('query accounts', () => JSON.parse(open(__ENV.ACCOUNTS_FILE)));
if (!accounts.length || accounts.some(a => !a.token || !Number.isInteger(a.conversationId))) throw new Error('Seeded ACCOUNTS_FILE required');
const trend = new Trend('trend_query_ms', true), history = new Trend('history_page_ms', true);
export const options = { vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'max', 'p(95)', 'p(99)'],
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate<0.01'],
    trend_query_ms: [`p(95)<${Number(__ENV.QUERY_P95_MS || 1000)}`], history_page_ms: [`p(95)<${Number(__ENV.HISTORY_P95_MS || 500)}`] } };
export default function() {
  const account = accounts[(__VU - 1) % accounts.length];
  const params = { headers: { Authorization: `Bearer ${account.token}` }, timeout: '10s' };
  const t = http.get(`${base}/api/meals/trends?days=90`, { ...params, tags: { name: 'trend' } });
  trend.add(t.timings.duration);
  check(t, { 'trend owned seeded meals': r => r.status === 200 && r.json().total >= Number(account.meals) });
  const first = http.get(`${base}/api/assistant/conversations/${account.conversationId}?limit=40`, { ...params, tags: { name: 'history_first' } });
  history.add(first.timings.duration);
  const page = first.status === 200 ? first.json() : null;
  check(first, { 'bounded latest history': () => !!page && page.messages.length === 40 && page.hasMore && !!page.nextBefore });
  if (page?.nextBefore) {
    const older = http.get(`${base}/api/assistant/conversations/${account.conversationId}?limit=40&before=${encodeURIComponent(page.nextBefore)}`, { ...params, tags: { name: 'history_older' } });
    history.add(older.timings.duration);
    check(older, { 'cursor does not repeat latest messages': r => r.status === 200 && r.json().messages.length === 40 && r.json().messages.every(m => !page.messages.some(p => p.text === m.text)) });
  }
  sleep(.1);
}
export function handleSummary(data) {
  const failed = Object.entries(data.metrics).flatMap(([name, metric]) => Object.entries(metric.thresholds || {}).filter(([,t]) => !t.ok).map(([key]) => `${name}: ${key}`));
  const report = { passed: failed.length === 0 && data.metrics.checks?.values.passes > 0, failedThresholds: failed,
    trendMs: data.metrics.trend_query_ms?.values, historyMs: data.metrics.history_page_ms?.values,
    checks: data.metrics.checks?.values, note: 'Synthetic seeded data and local hardware; not a production capacity guarantee.' };
  return { stdout: JSON.stringify(report, null, 2), [__ENV.RESULT_FILE || 'loadtests/results/query-capacity.json']: JSON.stringify({capacity: report,k6: data}, null, 2) };
}
