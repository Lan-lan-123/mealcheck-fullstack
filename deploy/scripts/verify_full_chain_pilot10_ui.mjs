import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { writeFileSync, readFileSync, mkdirSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { cookieFetch as fetch, installBrowserSession } from './cookie-browser.mjs'
const { chromium, expect } = createRequire(resolve('frontend/package.json'))('@playwright/test')
let input = ''
const replayAt = process.argv.indexOf('--replay-from')
let replayPassword
let settings
if (replayAt >= 0) {
  const recorded = JSON.parse(readFileSync(process.argv[replayAt + 1], 'utf8'))
  const output = resolve('logs/frontend/pilot10-recorded-ui-' + new Date().toISOString().replace(/[:.]/g, '-'))
  mkdirSync(output)
  const username = 'pilot10_ui_' + randomBytes(8).toString('hex')
  replayPassword = randomBytes(24).toString('base64url')
  const captcha = await (await fetch('http://127.0.0.1:5173/api/auth/captcha')).json()
  const operands = captcha.question.match(/(\d+) \+ (\d+)/)
  const response = await fetch('http://127.0.0.1:5173/api/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password: replayPassword, displayName: '十题输出页面回放', captchaId: captcha.captchaId, captchaAnswer: String(Number(operands[1]) + Number(operands[2])) }) })
  if (!response.ok) throw new Error('UI replay registration failed')
  const auth = await response.json()
  settings = { token: auth.token, username, userId: auth.user?.id, cases: recorded.cases, output, recordedReplay: true }
} else {
  for await (const chunk of process.stdin) input += chunk
  settings = JSON.parse(input)
}
const browser = await chromium.launch({ headless: true })
const checks = [], errors = []
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.on('pageerror', e => errors.push(e.message))
  if (settings.recordedReplay) {
    await page.route('**/api/assistant/conversations**', async route => {
      if (route.request().method() !== 'GET') throw new Error('Replay must not submit questions')
      const path = new URL(route.request().url()).pathname
      const id = Number(path.split('/').at(-1))
      const found = settings.cases.find(c => c.response.conversationId === id)
      const data = found ? found.persistedConversation : settings.cases.map(c => ({ id: c.response.conversationId, title: c.persistedConversation.title, updatedAt: c.persistedConversation.updatedAt }))
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    })
  }
  await installBrowserSession(page.context(), 'http://127.0.0.1:5173', settings.token)
  // Read only: show the actual persisted conversations; never submit another question.
  await page.goto('http://127.0.0.1:5173/user/overview')
  await page.locator('.assistant-fab').click()
  const panel = page.getByRole('region', { name: '饮食助手聊天窗口' })
  await panel.waitFor({ state: 'visible' })
  for (const item of settings.cases) {
    if (!item.response?.conversationId) throw new Error('Missing actual conversation: ' + item.id)
    const title = item.persistedConversation.title
    await panel.locator('.assistant-session-list').getByRole('button', { name: title, exact: true }).click()
    await expect(panel.locator('.assistant-message.user').last()).toContainText(item.question)
    const message = panel.locator('.assistant-message.assistant').last()
    await message.waitFor()
    const prefix = (item.response.summary || item.response.answer || '').slice(0, 20)
    if (prefix) await expect(message).toContainText(prefix)
    if (settings.requireReferenceHistory) {
      await expect(message.locator('.assistant-reference-list details')).toHaveCount(item.response.references?.length || 0)
      for (const [index, reference] of (item.response.references || []).entries()) {
        const detail = message.locator('.assistant-reference-list details').nth(index)
        await expect(detail.locator('summary')).toHaveText(reference.title)
        if (!(await detail.evaluate(el => el.open))) await detail.locator('summary').click()
        await expect(detail.locator('p')).toHaveText(reference.content)
      }
    }
    checks.push({ id: item.id, persistedAnswerShown: true, referencesShown: await message.locator('details').count() })
    if (['rag-008', 'rag-172', 'rag-200'].includes(item.id)) await panel.screenshot({ path: resolve(settings.output, item.id + '-desktop.png') })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await panel.screenshot({ path: resolve(settings.output, 'pilot10-mobile.png') })
  if (errors.length) throw new Error('Browser page errors')
  writeFileSync(resolve(settings.output, 'ui-results.json'), JSON.stringify({ mode: settings.recordedReplay ? 'RECORDED_ACTUAL_OUTPUT_REPLAY' : 'LIVE_PERSISTED_CONVERSATIONS', checks, browserErrors: errors, providerCallsFromUiRequested: 0 }, null, 2))
  console.log(JSON.stringify({ displayedCases: checks.length, recordedReplay: !!settings.recordedReplay, output: settings.output }))
} finally {
  await browser.close()
  if (settings.recordedReplay) {
    const result = await fetch('http://127.0.0.1:5173/api/profile/account', { method: 'DELETE', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.token }, body: JSON.stringify({ password: replayPassword }) })
    writeFileSync(resolve(settings.output, 'cleanup.json'), JSON.stringify({ accountCleanedUp: result.status === 204 }))
    if (result.status !== 204) throw new Error('UI replay temporary account cleanup failed')
  }
}
