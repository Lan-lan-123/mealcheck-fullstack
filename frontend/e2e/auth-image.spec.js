import { test, expect } from '@playwright/test'

async function harness(page) {
  await page.route('**/api/auth/captcha', route => route.fulfill({ json: { captchaId: 'test', question: '1+1' } }))
  await page.goto('/')
  await page.evaluate(async () => {
    window.imageHarness = await import('/e2e/fixtures/imageHarness.jsx')
    window.imageRequests = []
    window.createdUrls = []
    window.revokedUrls = []
    const originalFetch = window.fetch
    window.fetch = (url, options) => {
      if (!String(url).includes('/test-images/')) return originalFetch(url, options)
      return new Promise(resolve => window.imageRequests.push({ url, options, resolve }))
    }
    const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL)
    URL.createObjectURL = blob => { const url = create(blob); window.createdUrls.push(url); return url }
    URL.revokeObjectURL = url => { window.revokedUrls.push(url); revoke(url) }
  })
}

async function render(page, name) {
  await page.evaluate(name => window.imageHarness.render(name ? `/test-images/${name}` : ''), name)
}

async function succeed(page, index) {
  await page.evaluate(index => {
    const png = atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
    const bytes = Uint8Array.from(png, c => c.charCodeAt(0))
    window.imageRequests[index].resolve({ ok: true, blob: async () => new Blob([bytes], { type: 'image/png' }) })
  }, index)
}

test('late old responses cannot overwrite a new image and unmount releases its URL', async ({ page }) => {
  await harness(page)
  await render(page, 'a')
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(1)
  await render(page, 'b')
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(2)
  expect(await page.evaluate(() => imageRequests[0].options.signal.aborted)).toBe(true)
  await succeed(page, 1)
  const img = page.locator('#image-test img')
  await expect(img).toBeVisible()
  const src = await img.getAttribute('src')
  await succeed(page, 0)
  await expect(img).toHaveAttribute('src', src)
  await page.evaluate(() => window.imageHarness.dispose())
  expect(await page.evaluate(() => createdUrls.length)).toBe(1)
  expect(await page.evaluate(() => revokedUrls)).toContain(src)
})

test('a response arriving during blob decoding after unmount creates no object URL', async ({ page }) => {
  await harness(page)
  await render(page, 'slow-blob')
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(1)
  await page.evaluate(() => imageRequests[0].resolve({ ok: true, blob: () => new Promise(resolve => { window.resolveBlob = resolve }) }))
  await expect.poll(() => page.evaluate(() => typeof window.resolveBlob)).toBe('function')
  await page.evaluate(() => { window.imageHarness.dispose(); window.resolveBlob(new Blob(['late'])) })
  expect(await page.evaluate(() => createdUrls.length)).toBe(0)
})

test('HTTP failure has a retry action and a retry can load the image', async ({ page }) => {
  await harness(page)
  await render(page, 'retry')
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(1)
  await page.evaluate(() => imageRequests[0].resolve({ ok: false }))
  await expect(page.locator('#image-test')).toContainText('图片加载失败')
  await page.getByRole('button', { name: '重新加载' }).click()
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(2)
  await succeed(page, 1)
  await expect(page.locator('#image-test img')).toBeVisible()
})

test('empty URLs and undecodable images have distinct states', async ({ page }) => {
  await harness(page)
  await render(page, '')
  await expect(page.locator('#image-test')).toHaveText('暂无图片')
  await render(page, 'broken')
  await expect.poll(() => page.evaluate(() => imageRequests.length)).toBe(1)
  await page.evaluate(() => imageRequests[0].resolve({ ok: true, blob: async () => new Blob(['bad image']) }))
  await expect(page.locator('#image-test')).toContainText('图片加载失败')
  expect(await page.evaluate(() => revokedUrls)).toContain(await page.evaluate(() => createdUrls[0]))
})
