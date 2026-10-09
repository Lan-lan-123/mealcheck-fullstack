import http from 'k6/http';
import { sleep, check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';
import { options as mixedOptions, setup as mixedSetup, analyze, read,
  handleSummary } from './capacity-mixed.js';

const base = (__ENV.BASE_URL || 'http://127.0.0.1:18080').replace(/\/+$/, '');
const accounts = new SharedArray('ai-mixed accounts', () => JSON.parse(open(__ENV.ACCOUNTS_FILE)));
const weeklyAccounts = new SharedArray('weekly accounts', () => JSON.parse(open(__ENV.WEEKLY_ACCOUNTS_FILE || __ENV.ACCOUNTS_FILE)));
const assistantSuccess = new Rate('assistant_success');
const assistantMs = new Trend('assistant_ms', true);
const weeklySuccess = new Rate('weekly_success');
const weeklyMs = new Trend('weekly_ms', true);
const weeklyGenerated = new Counter('weekly_multiagent_generated');
const weeklyInvalidated = new Counter('weekly_invalidated');
export const options = {
  ...mixedOptions,
  scenarios: {
    ...mixedOptions.scenarios,
    assistant: { executor: 'constant-arrival-rate', rate: Number(__ENV.ASSISTANT_RATE || 12), timeUnit: '1m',
      duration: __ENV.DURATION || '30m', preAllocatedVUs: 8, maxVUs: 8, exec: 'ask', gracefulStop: '130s' },
    // Distinct users avoid mistaking cached weekly reports for new AI generation.
    weekly: Number(__ENV.WEEKLY_RATE || 0) > 0
      ? { executor: 'constant-arrival-rate', rate: Number(__ENV.WEEKLY_RATE), timeUnit: '1m',
          duration: __ENV.DURATION || '30m', preAllocatedVUs: 2, maxVUs: 2, exec: 'weekly', gracefulStop: '130s' }
      : { executor: 'shared-iterations', iterations: Math.min(Number(__ENV.WEEKLY_TASKS || 40), weeklyAccounts.length), vus: 2,
          maxDuration: __ENV.DURATION || '30m', exec: 'weekly', gracefulStop: '130s' },
  },
  thresholds: { ...mixedOptions.thresholds, assistant_success: ['rate>=0.99'], assistant_ms: ['p(95)<30000'],
    weekly_success: ['rate>=0.99'], weekly_ms: ['p(95)<60000'], weekly_multiagent_generated: ['count>=1'],
    weekly_invalidated: ['count==0'] },
};
export function setup() { return mixedSetup(); }
export { analyze, read, handleSummary };
function params(account) { return { headers: { Authorization: `Bearer ${account.token}`, 'Content-Type': 'application/json' }, timeout: '65s' }; }
export function ask() {
  const account = accounts[exec.scenario.iterationInTest % accounts.length];
  const started = Date.now();
  const response = http.post(`${base}/api/assistant/ask`, JSON.stringify({ question: '请根据最近的饮食记录给出均衡饮食建议。' }), params(account));
  let data; try { data = response.json(); } catch (_) { data = {}; }
  // The marker proves the model path completed; HTTP 200 with local fallback is not counted as AI success.
  const ok = response.status === 200 && typeof data.answer === 'string' && data.answer.includes('测试建议');
  assistantSuccess.add(ok); assistantMs.add(Date.now() - started);
  check(response, { 'assistant returned model advice': () => ok });
}
export function weekly() {
  // Each arrival must create a fresh snapshot; never cycle into a cached report.
  if (exec.scenario.iterationInTest >= weeklyAccounts.length) { weeklySuccess.add(false); return; }
  const account = weeklyAccounts[exec.scenario.iterationInTest];
  const started = Date.now();
  const response = http.post(`${base}/api/reports/weekly/jobs?days=7`, null, params(account));
  let data; try { data = response.json(); } catch (_) { data = {}; }
  if (![200, 202].includes(response.status) || !data.id) { weeklySuccess.add(false); return; }
  while (Date.now() - started < 120000 && !['SUCCEEDED', 'DEAD', 'FAILED', 'STALE'].includes(data.status)) {
    sleep(1);
    const result = http.get(`${base}/api/reports/weekly/jobs/${data.id}`, params(account));
    if (![200, 202].includes(result.status)) { weeklySuccess.add(false); return; }
    try { data = result.json(); } catch (_) { weeklySuccess.add(false); return; }
  }
  weeklyInvalidated.add(data.status === 'STALE' ? 1 : 0);
  const ok = data.status === 'SUCCEEDED' && data.report && data.report.reportText.includes('测试周报')
    && data.report.generationMode === 'MULTI_AGENT' && data.report.reviewStatus === 'APPROVED_AI';
  weeklySuccess.add(ok); weeklyMs.add(Date.now() - started); weeklyGenerated.add(ok ? 1 : 0);
  check(data, { 'weekly completed model generation and review': () => ok });
}
