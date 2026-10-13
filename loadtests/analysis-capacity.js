import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

function numberEnv(name, fallback, minimum = 0, integer = false) {
  const value = Number(__ENV[name] === undefined ? fallback : __ENV[name]);
  if (!Number.isFinite(value) || value < minimum || (integer && !Number.isInteger(value))) {
    throw new Error(`${name} must be ${integer ? 'an integer' : 'a number'} >= ${minimum}`);
  }
  return value;
}
function durationSeconds(value) {
  const match = /^(\d+(?:\.\d+)?)(s|m|h)$/.exec(value);
  if (!match || Number(match[1]) <= 0) throw new Error('DURATION must be a positive duration such as 30s, 5m or 1h');
  return Number(match[1]) * { s: 1, m: 60, h: 3600 }[match[2]];
}

const base = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const mode = __ENV.MODE || 'arrival';
if (!['arrival', 'burst'].includes(mode)) throw new Error('MODE must be arrival or burst');
if (!__ENV.ACCOUNTS_FILE || !__ENV.IMAGE_FILE) throw new Error('Set ACCOUNTS_FILE and IMAGE_FILE to absolute paths');
const accounts = new SharedArray('analysis accounts', () => {
  const values = JSON.parse(open(__ENV.ANALYSIS_ACCOUNTS_FILE || __ENV.ACCOUNTS_FILE).replace(/^\uFEFF/, ''));
  if (!Array.isArray(values) || !values.length || values.some(v => !v || typeof v.token !== 'string' || !v.token.trim())) {
    throw new Error('ACCOUNTS_FILE must contain a nonempty array of {"token":"JWT"}');
  }
  if (new Set(values.map(v => v.token.trim())).size !== values.length) throw new Error('Use distinct test accounts, not duplicate tokens');
  return values.map(v => ({ token: v.token.trim() }));
});
const image = open(__ENV.IMAGE_FILE, 'b');
const variants = __ENV.IMAGES_FILE ? new SharedArray('analysis image variants', () =>
  JSON.parse(open(__ENV.IMAGES_FILE))) : [];
const variantBytes = variants.map(item => open(item.path, 'b'));
const filename = __ENV.IMAGE_FILE.replace(/\\/g, '/').split('/').pop();
const imageType = __ENV.IMAGE_TYPE || (/\.png$/i.test(filename) ? 'image/png' : /\.webp$/i.test(filename) ? 'image/webp' : 'image/jpeg');
const duration = __ENV.DURATION || '5m';
const durationSec = durationSeconds(duration);
const rate = numberEnv('RATE_PER_MINUTE', 5, 1, true);
const burst = numberEnv('BURST_TASKS', 20, 1, true);
const maxVUs = numberEnv('MAX_VUS', 20, 1, true);
const preVUs = numberEnv('PREALLOCATED_VUS', Math.min(10, maxVUs), 1, true);
if (preVUs > maxVUs) throw new Error('PREALLOCATED_VUS must not exceed MAX_VUS');
const taskTimeout = numberEnv('TASK_TIMEOUT_SECONDS', 180, 1);
const httpTimeout = numberEnv('HTTP_TIMEOUT_SECONDS', 15, 0.1);
const pollSeconds = numberEnv('POLL_SECONDS', 3, 0.05);
const p95Limit = numberEnv('E2E_P95_MS', 60000, 1);
const failureLimit = numberEnv('MAX_FAILURE_RATE', 0.01, 0);
if (failureLimit >= 1) throw new Error('MAX_FAILURE_RATE must be < 1');
const minCompleted = numberEnv('MIN_COMPLETED', 1, 1, true);
const attempted = new Counter('analysis_attempted');
const accepted = new Counter('analysis_accepted');
const succeeded = new Counter('analysis_succeeded');
const failed = new Counter('analysis_failed');
const rejected = new Counter('analysis_rejected');
const timedOut = new Counter('analysis_timed_out');
const unresolved = new Counter('analysis_unresolved');
const unknownSubmission = new Counter('analysis_unknown_submission');
const rateLimited = new Counter('analysis_submit_429');
const uploadBusy = new Counter('analysis_upload_busy');
const acceptedUpload = new Trend('analysis_accepted_upload_ms', true);
const pollErrors = new Counter('analysis_poll_errors');
const successRate = new Rate('analysis_success_rate');
const e2e = new Trend('analysis_e2e_ms', true);
const observation = new Trend('analysis_observation_ms', true);
const counters = [attempted, accepted, succeeded, failed, rejected, timedOut, unresolved,
  unknownSubmission, rateLimited, uploadBusy, pollErrors];

// setup() returns one shared run ID to all VUs; an iteration ID names one new task.
export function setup() {
  counters.forEach(metric => metric.add(0));
  for (const account of accounts) {
    const response = http.get(`${base}/api/meals?page=0&size=1`, {
      headers: { Authorization: `Bearer ${account.token}` }, timeout: `${httpTimeout}s`,
      tags: { name: 'account_preflight', operation: 'preflight' },
    });
    if (response.status !== 200) throw new Error(`Account preflight failed with HTTP ${response.status}; refresh tokens/check BASE_URL`);
  }
  const run = __ENV.RUN_ID || `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  if (!/^[A-Za-z0-9_-]{8,48}$/.test(run)) throw new Error('RUN_ID must contain 8 to 48 letters, digits, underscores or hyphens');
  console.log(`mode=${mode}, accounts=${accounts.length}, maxVUs=${maxVUs}, run=${run}`);
  return { run };
}

export const options = {
  scenarios: {
    analysis: mode === 'arrival' ? {
      executor: 'constant-arrival-rate', rate, timeUnit: '1m', duration,
      preAllocatedVUs: preVUs, maxVUs,
      gracefulStop: `${Math.ceil(taskTimeout + httpTimeout + pollSeconds + 5)}s`,
    } : {
      executor: 'shared-iterations', vus: Math.min(burst, maxVUs), iterations: burst,
      maxDuration: `${Math.ceil(durationSec + taskTimeout + httpTimeout + pollSeconds + 5)}s`,
      gracefulStop: `${Math.ceil(taskTimeout + httpTimeout + pollSeconds + 5)}s`,
    },
  },
  setupTimeout: `${Math.ceil(accounts.length * httpTimeout + 10)}s`,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    analysis_success_rate: [`rate>=${1 - failureLimit}`],
    analysis_succeeded: [`count>=${minCompleted}`],
    analysis_timed_out: ['count==0'],
    analysis_unresolved: ['count==0'],
    ...(__ENV.REQUIRE_ACCEPTED_SUCCESS === 'true' ? { analysis_failed: ['count==0'] } : {}),
    analysis_e2e_ms: [`p(95)<${p95Limit}`],
    dropped_iterations: ['count==0'],
  },
};

function readState(response, key) {
  if (![200, 202].includes(response.status)) return null;
  try {
    const body = response.json();
    return body.key === key && ['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RETRYABLE'].includes(body.status) ? body : null;
  } catch (_) { return null; }
}
function finish(metric, ok, start) {
  metric.add(1);
  successRate.add(ok);
  observation.add(Date.now() - start);
  if (ok) e2e.add(Date.now() - start);
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const account = accounts[iteration % accounts.length];
  const key = `analysis-${data.run}-${iteration}`;
  const headers = { Authorization: `Bearer ${account.token}` };
  const start = Date.now();
  const deadline = start + taskTimeout * 1000;
  attempted.add(1);
  const variantIndex = iteration % Math.max(1, variants.length);
  const variant = variants[variantIndex];
  const response = http.post(`${base}/api/meals/analysis-requests`, {
    image: http.file(variant ? variantBytes[variantIndex] : image,
      variant ? variant.path.replace(/\\/g, '/').split('/').pop() : filename,
      variant ? variant.type : imageType), goal: __ENV.GOAL || 'current',
  }, {
    headers: { ...headers, 'Idempotency-Key': key },
    timeout: `${Math.min(httpTimeout, taskTimeout)}s`,
    tags: { name: 'analysis_submit', operation: 'enqueue' },
  });
  if (response.status === 429) {
    rateLimited.add(1);
    try { if (response.json().code === 'UPLOAD_BUSY') uploadBusy.add(1); } catch (_) { /* Count non-JSON rejection as usual. */ }
  }
  let state = readState(response, key);
  if (!state) {
    // A timeout/5xx can occur after the server committed a task. Do not create a second task.
    if (response.status === 0 || response.status >= 500 || [200, 202].includes(response.status)) {
      unknownSubmission.add(1);
      finish(unresolved, false, start);
    } else finish(rejected, false, start);
    return;
  }
  accepted.add(1);
  acceptedUpload.add(Date.now() - start);
  while (true) {
    if (state.status === 'SUCCEEDED') {
      const valid = state.result && state.result.recordId != null;
      check(state, { 'successful task contains saved record': () => Boolean(valid) });
      finish(valid ? succeeded : unresolved, Boolean(valid), start);
      return;
    }
    if (state.status === 'FAILED' || state.status === 'RETRYABLE') {
      finish(failed, false, start);
      return;
    }
    const remaining = deadline - Date.now();
    if (remaining <= 0) { finish(timedOut, false, start); return; }
    sleep(Math.min(pollSeconds, remaining / 1000));
    if (Date.now() >= deadline) { finish(timedOut, false, start); return; }
    const poll = http.get(`${base}/api/meals/analysis-requests/${key}`, {
      headers, timeout: `${Math.max(0.001, Math.min(httpTimeout, (deadline - Date.now()) / 1000))}s`,
      tags: { name: 'analysis_poll', operation: 'poll' },
    });
    const next = readState(poll, key);
    if (next) state = next;
    else {
      pollErrors.add(1);
      if (poll.status !== 0 && poll.status < 500 && poll.status !== 429) {
        finish(unresolved, false, start);
        return;
      }
    }
  }
}

export function handleSummary(data) {
  const count = name => data.metrics[name]?.values?.count || 0;
  const total = count('analysis_attempted');
  const successful = count('analysis_succeeded');
  const outcomes = ['analysis_succeeded', 'analysis_failed', 'analysis_rejected', 'analysis_timed_out', 'analysis_unresolved'];
  const accounted = outcomes.reduce((sum, name) => sum + count(name), 0);
  const unfinished = Math.max(0, total - accounted);
  const failedThresholds = Object.entries(data.metrics).flatMap(([name, metric]) =>
    Object.entries(metric.thresholds || {}).filter(([, threshold]) => !threshold.ok).map(([limit]) => `${name}: ${limit}`));
  const samples = data.metrics.analysis_e2e_ms?.values || null;
  const report = {
    mode, configuredRatePerMinute: mode === 'arrival' ? rate : null,
    configuredBurstTasks: mode === 'burst' ? burst : null,
    configuredDurationSeconds: durationSec, accountCount: accounts.length, maxVUs,
    taskTimeoutSeconds: taskTimeout, pollSeconds,
    actualStartedPerMinute: mode === 'arrival' ? total / durationSec * 60 : null,
    attempts: total, accepted: count('analysis_accepted'), succeeded: successful,
    failed: count('analysis_failed'), rejected: count('analysis_rejected'),
    timedOut: count('analysis_timed_out'), unresolved: count('analysis_unresolved'), unfinished,
    unknownSubmissions: count('analysis_unknown_submission'), submit429: count('analysis_submit_429'),
    uploadBusy: count('analysis_upload_busy'),
    acceptedUploadMs: count('analysis_accepted') ? data.metrics.analysis_accepted_upload_ms?.values || null : null,
    pollErrors: count('analysis_poll_errors'),
    droppedIterations: count('dropped_iterations'),
    successFraction: total ? successful / total : 0,
    successfulE2eMs: successful ? samples : null,
    passed: total > 0 && successful >= minCompleted && unfinished === 0 &&
      successful / total >= 1 - failureLimit && failedThresholds.length === 0,
    failedThresholds,
    note: 'E2E includes upload and polling delay. Success percentiles exclude failures; assess all outcome counts. Timed-out/unresolved tasks may still execute on the backend.',
  };
  const output = __ENV.RESULT_FILE || 'loadtests/results/analysis-capacity.json';
  return {
    stdout: `\nImage analysis capacity: ${report.passed ? 'PASS' : 'FAIL'}\n${JSON.stringify(report, null, 2)}\n`,
    [output]: JSON.stringify({ capacity: report, k6: data }, null, 2),
  };
}
