// Verify the deployed frontend against real Cookie/CSRF APIs with one disposable account. No AI calls.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const { chromium, expect } = createRequire(resolve(root, 'frontend/package.json'))('@playwright/test')
const option = (name, fallback) => {
  const index = process.argv.indexOf(name)
  if (index < 0) return fallback
  if (!process.argv[index + 1]) throw new Error('Missing value for ' + name)
  return process.argv[index + 1]
}
const base = new URL(option('--base-url', 'http://127.0.0.1:5173')).origin
const output = resolve(root, option('--output', 'logs/deploy/browser-auth-' + new Date().toISOString().replace(/[:.]/g, '-')))
mkdirSync(output, { recursive: true })
const username = 'auth_fix_' + randomBytes(8).toString('hex')
const password = randomBytes(24).toString('base64url')
const checks = []
let browser, context, page, registered = false, accountCleanedUp = false, failure
function passed(name) { checks.push(name); console.log('PASS ' + name) }
function errorText(error) { return String(error?.message || error).replaceAll(password, '[REDACTED]') }

async function csrf() {
  const response = await context.request.get(base + '/api/auth/csrf')
  assert.equal(response.status(), 200, 'CSRF endpoint must succeed')
  const value = await response.json()
  assert.equal(value.headerName, 'X-XSRF-TOKEN')
  assert.ok(value.token)
  return { [value.headerName]: value.token }
}
async function captchaAnswer() {
  const label = page.locator('.captcha-row span')
  await expect(label).toHaveText(/\d+\s*\+\s*\d+\s*=\s*\?/)
  const match = (await label.innerText()).match(/(\d+)\s*\+\s*(\d+)/)
  await page.getByPlaceholder('答案').fill(String(Number(match[1]) + Number(match[2])))
}
async function checkAuthResponse(response, expectedPath) {
  assert.ok(response.ok(), expectedPath + ' must succeed, received ' + response.status())
  const data = await response.json()
  assert.equal(data.username, username)
  assert.ok(!Object.hasOwn(data, 'token'), 'Browser auth JSON must not contain a JWT')
  const headers = await response.request().allHeaders()
  assert.ok(headers['x-xsrf-token'], 'Browser must send the CSRF header')
  assert.ok(!headers.authorization, 'Browser must authenticate with cookies')
  const cookie = (await context.cookies(base)).find(item => /mealcheck_session$/.test(item.name))
  assert.ok(cookie?.httpOnly, 'Session cookie must be HttpOnly')
  const readable = await page.evaluate(() => document.cookie)
  assert.ok(!readable.includes('mealcheck_session='), 'Session cookie must not be script-readable')
  passed(expectedPath + ': Cookie/CSRF protocol and private credential')
}
async function cleanup() {
  if (!registered) return
  if ((await context.request.get(base + '/api/auth/me')).status() !== 200) {
    const challenge = await context.request.get(base + '/api/auth/captcha')
    assert.equal(challenge.status(), 200, 'Cleanup captcha must succeed')
    const value = await challenge.json(), match = value.question.match(/(\d+)\s*\+\s*(\d+)/)
    const login = await context.request.post(base + '/api/auth/login', {
      headers: await csrf(), data: { username, password, expectedRole: 'USER',
        captchaId: value.captchaId, captchaAnswer: String(Number(match[1]) + Number(match[2])) }
    })
    assert.equal(login.status(), 200, 'Cleanup must log into its own temporary account')
  }
  const deleted = await context.request.delete(base + '/api/profile/account', {
    headers: await csrf(), data: { password }
  })
  assert.equal(deleted.status(), 204, 'Temporary account must be deleted')
  assert.equal((await context.request.get(base + '/api/auth/me')).status(), 401)
  accountCleanedUp = true
  passed('temporary account deleted and credential rejected')
}

try {
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) })
  context = await browser.newContext()
  page = await context.newPage()
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'obsolete-test-credential')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'obsolete-user', role: 'ADMIN' }))
  })
  await page.goto(base)
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
  assert.ok(await page.evaluate(() => localStorage.getItem('mealcheck_token') === null && localStorage.getItem('mealcheck_user') === null))
  passed('obsolete browser credentials cleared')
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByLabel('昵称', { exact: true }).fill('认证部署临时验收')
  await captchaAnswer()
  const registration = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/register' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '创建账号', exact: true }).click()
  const created = await registration
  registered = created.ok()
  await checkAuthResponse(created, 'register')
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  const restored = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/me')
  await page.reload()
  assert.equal((await restored).status(), 200, 'Reload must restore the cookie session')
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  passed('refresh restores the authenticated workspace')
  const session = (await context.cookies(base)).find(item => /mealcheck_session$/.test(item.name))
  const logoutResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/logout')
  await page.getByRole('button', { name: '退出', exact: true }).click()
  assert.equal((await logoutResponse).status(), 204)
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
  assert.ok(!(await context.cookies(base)).some(item => /mealcheck_session$/.test(item.name)))
  const revoked = await context.request.get(base + '/api/auth/me', { headers: { Cookie: session.name + '=' + session.value } })
  assert.equal(revoked.status(), 401, 'Logged-out cookie must remain revoked')
  passed('logout clears the cookie and rejects replay')
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await captchaAnswer()
  const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '用户登录', exact: true }).click()
  await checkAuthResponse(await loginResponse, 'login')
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
} catch (error) {
  failure = errorText(error)
} finally {
  try { await cleanup() } catch (error) { failure = [failure, 'Cleanup: ' + errorText(error)].filter(Boolean).join('\n') }
  await browser?.close()
  const result = { checkedAt: new Date().toISOString(), base, passed: !failure && accountCleanedUp,
    accountCreated: registered, accountCleanedUp, aiCalls: 0, checks, ...(failure ? { error: failure } : {}) }
  writeFileSync(resolve(output, 'verification.json'), JSON.stringify(result, null, 2) + '\n')
  if (!result.passed) { console.error(failure || 'Browser authentication verification failed'); process.exitCode = 1 }
  else console.log('Browser authentication verification passed; temporary account removed; AI calls: 0')
}
