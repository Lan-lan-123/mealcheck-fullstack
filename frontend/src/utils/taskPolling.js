// @ts-check
/** @param {number} milliseconds @param {AbortSignal} [signal] @returns {Promise<void>} */
export function waitForPoll(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      reject(new DOMException('Request cancelled', 'AbortError'))
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort)
      resolve()
    }, milliseconds)
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) abort()
  })
}

/** @template T @param {Promise<T>} promise @param {AbortSignal} [signal] @returns {Promise<T>} */
function cancellable(promise, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => {
      signal?.removeEventListener('abort', abort)
      reject(new DOMException('Request cancelled', 'AbortError'))
    }
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) abort()
    promise.then(value => { signal?.removeEventListener('abort', abort); resolve(value) },
      error => { signal?.removeEventListener('abort', abort); reject(error) })
  })
}

/**
 * Only status reads are retried. Submission and task failures remain the caller's responsibility.
 * @template {{status: string, retryAfterSeconds?: number, connectionInterrupted?: boolean}} T
 * @template K, R
 * @param {T} initial @param {K} key
 * @param {import('../api/contracts').PollOptions<T, K> & {maxPolls: number, maxDurationMs: number,
 * timeoutMessage: string, evaluate: (state: T) => {done: false} | {done: true, result: R}}} options
 * @returns {Promise<R>}
 */
export async function pollTask(initial, key, {
  load, signal, onUpdate = () => {}, wait = waitForPoll, maxPolls, maxDurationMs,
  random = Math.random, now = Date.now,
  visibility = typeof document === 'undefined' ? null : document, timeoutMessage, evaluate
}) {
  const active = new AbortController()
  const abort = () => active.abort()
  let timedOut = false
  const deadline = now() + maxDurationMs
  const timer = setTimeout(() => { timedOut = true; active.abort() }, maxDurationMs)
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()
  /** @type {(() => void) | undefined} */
  let wake
  let becameVisible = false
  const visibilityChanged = () => {
    becameVisible = !visibility?.hidden
    wake?.()
  }
  visibility?.addEventListener('visibilitychange', visibilityChanged)
  const check = () => {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    if (timedOut || now() >= deadline) throw Object.assign(new Error(timeoutMessage), { code: 'POLLING_TIMEOUT' })
    if (active.signal.aborted) throw new DOMException('Request cancelled', 'AbortError')
  }
  let state = initial, unchanged = 0, failures = 0, retryAfter = 0
  try {
    for (let poll = 0; ; poll++) {
      check()
      onUpdate(state)
      check()
      const result = evaluate(state)
      if (result.done) return result.result
      if (poll >= maxPolls) throw Object.assign(new Error(timeoutMessage), { code: 'POLLING_TIMEOUT' })
      const base = Math.max(2000, (state.retryAfterSeconds || 3) * 1000)
      const delay = Math.max(base, retryAfter, Math.min(10000, base * 1.5 ** Math.min(unchanged + failures, 6) + Math.floor(random() * 250)))
      // A visibility change cancels the old timer: hidden tabs get a new slower wait,
      // returning tabs query immediately, including changes during an in-flight read.
      while (!becameVisible) {
        const waiting = new AbortController()
        const stopWait = () => waiting.abort()
        active.signal.addEventListener('abort', stopWait, { once: true })
        wake = stopWait
        try {
          await cancellable(wait(Math.min(visibility?.hidden ? Math.max(30000, delay) : delay, deadline - now()), waiting.signal), waiting.signal)
          break
        } catch (error) {
          check()
          if (!waiting.signal.aborted) throw error
        } finally {
          wake = undefined
          active.signal.removeEventListener('abort', stopWait)
        }
      }
      becameVisible = false
      check()
      try {
        const next = await cancellable(load(key, active.signal), active.signal)
        check()
        unchanged = failures || next.status !== state.status ? 0 : unchanged + 1
        failures = 0
        retryAfter = 0
        state = { ...next, connectionInterrupted: false }
      } catch (error) {
        check()
        if (error.name === 'AbortError' || error.code === 'INVALID_API_RESPONSE'
          || (error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status))
          || ++failures >= 5) throw error
        retryAfter = Math.max(0, Number(error.retryAfter) || 0) * 1000
        state = { ...state, connectionInterrupted: true }
      }
    }
  } catch (error) {
    check()
    throw error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
    visibility?.removeEventListener('visibilitychange', visibilityChanged)
  }
}
