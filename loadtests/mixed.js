import http from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'

const base = (__ENV.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '')
const users = new SharedArray('accounts', () => JSON.parse(open(__ENV.ACCOUNTS_FILE)))
const image = open(__ENV.IMAGE_FILE, 'b')
const run = __ENV.RUN_ID || String(Date.now())
export const options = {
  vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || '20s',
  thresholds: { checks: ['rate>0.99'], http_req_failed: ['rate<0.01'],
    'http_req_duration{operation:enqueue}': ['p(95)<2000'], 'http_req_duration{operation:read}': ['p(95)<1000'] }
}
export default function () {
  if (__VU > users.length) throw new Error('One isolated account per virtual user is required')
  const headers = { Authorization: `Bearer ${users[__VU - 1].token}` }
  // Keep the existing replay workload by default; capacity uses analysis-capacity.js.
  const key = `mixed-${run}-${__VU}${__ENV.NEW_TASKS === 'true' ? `-${__ITER}` : ''}`
  const submit = http.post(`${base}/api/meals/analysis-requests`, { image: http.file(image, 'load.jpg', 'image/jpeg'), goal: 'current' },
    { headers: { ...headers, 'Idempotency-Key': key }, tags: { operation: 'enqueue' } })
  check(submit, { 'enqueue accepts or replays': response => [200, 202].includes(response.status) })
  const state = http.get(`${base}/api/meals/analysis-requests/${key}`, { headers, tags: { operation: 'poll' } })
  check(state, { 'analysis remains queryable': response => [200, 202].includes(response.status) })
  const report = http.post(`${base}/api/reports/weekly/jobs?days=7`, null, { headers, tags: { operation: 'weekly' } })
  check(report, { 'weekly task accepts or replays': response => [200, 202].includes(response.status) })
  const records = http.get(`${base}/api/meals?page=0&size=10`, { headers, tags: { operation: 'read' } })
  check(records, { 'meal list is available': response => response.status === 200 })
  sleep(1)
}
export function handleSummary(data) { return { [__ENV.RESULT_FILE || 'mixed-result.json']: JSON.stringify(data, null, 2) } }
