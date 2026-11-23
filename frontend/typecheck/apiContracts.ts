import { askDietAssistant, streamDietAssistant, mealDetail, login, register, answerFeedback, declareSkippedMeal } from '../src/api/client'
import { ApiError, jsonOptions } from '../src/api/transport'

// This function is only compiled, never run or bundled. An unused expectation fails CI.
function apiContracts() {
  login({ username: 'alice', password: 'password', captchaId: 'captcha', captchaAnswer: '1234' })
  register({ username: 'alice', password: 'password', captchaId: 'captcha', captchaAnswer: '1234' })
  answerFeedback(1, { verdict: 'HELPFUL', category: 'GENERAL' })
  const controller = new AbortController()
  askDietAssistant('question', [], null, controller.signal, 'FAST')
  streamDietAssistant('question', [], null, controller.signal, () => {}, 'REVIEWED')
  const error = new ApiError('failed', 429, 'RATE_LIMITED', 10, 'request-id')
  error.status.toFixed()
  jsonOptions('POST', { version: 1 })

  // @ts-expect-error Authentication requires the server captcha contract.
  login({ username: 'alice', password: 'password' })
  // @ts-expect-error Role values must match the server contract.
  login({ username: 'alice', password: 'password', captchaId: 'c', captchaAnswer: '1', expectedRole: 'OWNER' })
  // @ts-expect-error Assistant history contains messages, not a raw string.
  askDietAssistant('question', 'history')
  // @ts-expect-error Cancellation requires an AbortSignal.
  mealDetail(1, controller)
  // @ts-expect-error Feedback is an enum contract, not an arbitrary verdict.
  answerFeedback(1, { verdict: 'MAYBE', category: 'GENERAL' })
  // @ts-expect-error Undo is a boolean, never a truthy string.
  declareSkippedMeal({ day: '2026-10-29', mealType: 'DINNER' }, 'false')
  // @ts-expect-error Review mode must be explicitly supported.
  streamDietAssistant('question', [], null, undefined, () => {}, 'UNRESTRICTED')
}
void apiContracts
