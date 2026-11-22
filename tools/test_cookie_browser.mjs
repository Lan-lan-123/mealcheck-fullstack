import assert from 'node:assert/strict'
import http from 'node:http'
import { chromium } from '../frontend/node_modules/@playwright/test/index.mjs'
import { cookieFetch, installBrowserSession } from '../deploy/scripts/cookie-browser.mjs'

const server = http.createServer((request, response) => {
  if (request.url === '/api/auth/csrf') {
    response.setHeader('Set-Cookie', '__Host-mealcheck_csrf=cli-csrf; Secure; HttpOnly; SameSite=Lax; Path=/')
    response.end(JSON.stringify({ token: 'masked', headerName: 'X-XSRF-TOKEN' }))
  } else if (request.url === '/api/auth/login') {
    assert.equal(request.headers['x-xsrf-token'], 'masked')
    assert.match(request.headers.cookie, /__Host-mealcheck_csrf=cli-csrf/)
    response.setHeader('Set-Cookie', '__Host-mealcheck_session=private-fixture; Secure; HttpOnly; Path=/; SameSite=Lax')
    response.end(JSON.stringify({ username: 'fixture', role: 'USER' }))
  } else if (request.url === '/echo') response.end(JSON.stringify({ cookie: request.headers.cookie || '' }))
  else { response.setHeader('Content-Type', 'text/html'); response.end('<html><body>Cookie transport fixture</body></html>') }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
let browser
try {
  const response = await cookieFetch(base + '/api/auth/login', { method: 'POST', body: '{}' })
  assert.equal((await response.json()).token, 'private-fixture')
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) })
  const context = await browser.newContext()
  await installBrowserSession(context, base, 'private-fixture')
  const page = await context.newPage()
  await page.goto(base)
  assert.equal(await page.evaluate(() => document.cookie.includes('private-fixture')), false)
  assert.match((await page.evaluate(() => fetch('/echo').then(response => response.json()))).cookie, /__Host-mealcheck_session=private-fixture/)
  console.log('CLI CSRF, production cookie detection, HttpOnly browser transfer: passed')
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)) }
