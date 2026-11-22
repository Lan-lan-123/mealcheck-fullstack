// @ts-check
import { assistantEvent, assistantResponse, ResponseValidationError } from './validation.js'
import { API_BASE, LONG_REQUEST_TIMEOUT_MS, ApiError, csrfHeaders, expireSession, resetCsrf, sessionSnapshot, jsonOptions, request } from './transport.js'

/** @param {string} question @param {import('./contracts').AssistantHistoryItem[]} history @param {number | null} conversationId
 * @param {AbortSignal | undefined} signal @param {import('./contracts').AssistantEventHandler} onEvent @param {'REVIEWED' | 'FAST'} [reviewMode] @returns {Promise<import('./contracts').AssistantResponse>} */
export async function streamDietAssistant(question, history, conversationId, signal, onEvent, reviewMode = 'REVIEWED') {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  /** @type {string | null | undefined} */
  let requestId
  let timedOut = false
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, LONG_REQUEST_TIMEOUT_MS)
  const cancel = () => { if (requestId) cancelAssistantRequest(requestId).catch(() => {}) }
  controller.signal.addEventListener('abort', cancel, { once: true })
  try {
    const { version, authenticated } = sessionSnapshot()
    const headers = await csrfHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }, controller.signal)
    controller.signal.throwIfAborted()
    const response = await fetch(`${API_BASE}/api/assistant/stream`, { ...jsonOptions('POST', { question, history, conversationId, reviewMode }), credentials: 'include', headers, signal: controller.signal })
    requestId = response.headers.get('X-Assistant-Request-ID')
    if (requestId) onEvent('request', { requestId })
    if ([404, 405].includes(response.status)) return askDietAssistant(question, history, conversationId, signal, reviewMode)
    if (!response.ok) {
      let data; try { data = await response.json() } catch { }
      if (response.status === 401) expireSession(version, authenticated)
      if (response.status === 403 && data?.code === 'CSRF_INVALID') resetCsrf()
      throw new ApiError(data?.message || '助手请求失败', response.status, data?.code, 0, requestId)
    }
    if (!response.headers.get('Content-Type')?.includes('text/event-stream')) {
      let data
      try { data = await response.json() } catch { throw new ResponseValidationError('assistant.stream') }
      return assistantResponse(data, 'assistant.stream')
    }
    if (!response.body) throw new ResponseValidationError('assistant.stream.body')
    const reader = response.body.getReader(), decoder = new TextDecoder()
    let buffer = '', completed = null, failure = null
    const consume = () => {
      let end
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, end); buffer = buffer.slice(end + 2)
        let event = 'message'; const data = []
        for (const line of block.split('\n')) { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trimStart()) }
        if (!data.length) continue
        let raw
        try { raw = JSON.parse(data.join('\n')) } catch { throw new ResponseValidationError(`assistant.stream.${event}`) }
        const value = assistantEvent(event, raw)
        if (!value) continue
        if (value.requestId) requestId = value.requestId
        onEvent(/** @type {import('./contracts').AssistantEvent} */ (event), value)
        if (event === 'complete') completed = value
        if (event === 'error') failure = new ApiError(value.message, 502, 'STREAM_FAILED', 0, value.requestId)
      }
    }
    try {
      while (true) { const { done, value } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); buffer = buffer.replace(/\r\n/g, '\n'); consume() }
      buffer += decoder.decode(); consume()
      if (failure) throw failure
      if (!completed) throw new ApiError('连接中断，回复未完成。请查看会话历史后再重试。', 502, 'STREAM_INTERRUPTED', 0, requestId)
      return completed
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
  } catch (err) {
    cancel()
    if (timedOut) throw new ApiError('回复超时，已取消等待。', 408, 'CLIENT_TIMEOUT', 0, requestId)
    throw err
  } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); controller.signal.removeEventListener('abort', cancel) }
}

/** @param {string} question @param {import('./contracts').AssistantHistoryItem[]} [history] @param {number | null} [conversationId] @param {AbortSignal} [signal] @param {'REVIEWED' | 'FAST'} [reviewMode] @returns {Promise<import('./contracts').AssistantResponse>} */
export function askDietAssistant(question, history = [], conversationId = null, signal, reviewMode = 'REVIEWED') {
  return request('/api/assistant/ask', {
    method: 'POST',
    signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, history, conversationId, reviewMode })
  })
}

/** @param {string} id */
export function cancelAssistantRequest(id) { return request(`/api/assistant/requests/${id}`, { method: 'DELETE' }) }
