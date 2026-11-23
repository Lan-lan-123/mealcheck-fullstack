import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisFailure, needsFoodReview } from '../src/utils/recognitionExplanation.js'
import { resolveMealAnalysis } from '../src/utils/mealAnalysisJob.js'
import { validateApiResponse } from '../src/api/validation.js'

test('low confidence is distinguished from missing confidence and keeps the review threshold inclusive at 80%', () => {
  for (const confidence of [0, .49, .5, .7999]) assert.equal(needsFoodReview({ confidence }), true)
  for (const confidence of [.8, 1, undefined]) assert.equal(needsFoodReview({ confidence }), false)
})
test('typed task failures retain their reason and choose replacement, retry or query appropriately', async () => {
  for (const [code, action] of [['NON_FOOD', 'replace'], ['IMAGE_UNCLEAR', 'replace'], ['MODEL_SERVICE_ERROR', 'retry'],
    ['REMOTE_TIMEOUT', 'retry'], ['RECOGNITION_INCONSISTENT', 'retry'], ['NON_FOOD_LIMIT', 'wait'], ['DATABASE_UNAVAILABLE', 'query']]) {
    assert.equal(analysisFailure(code).action, action)
    await assert.rejects(resolveMealAnalysis({ key: 'key', status: 'FAILED', errorCode: code }, {
      load: async () => assert.fail('failed tasks do not poll')
    }), error => error.code === code && error.message.length > 0)
  }
  assert.equal(analysisFailure(undefined, '网络中断').message, '网络中断')
  assert.match(analysisFailure('RECOGNITION_INCONSISTENT').message, /不表示图片没有食物/)
})
test('excluded candidates survive API parsing but malformed candidate confidence fails', () => {
  const base = { recordId: 1, recognition: { foods: [{ name: '米饭', category: 'staple', confidence: .9 }],
    uncertainFoods: [{ name: '鸡肉', category: 'protein', confidence: .3, note: '被遮挡' }] }, evaluation: { score: 80 } }
  const parse = value => validateApiResponse('/api/meals/1/corrections', value, 'POST')
  assert.equal(parse(base).recognition.uncertainFoods[0].confidence, .3)
  for (const confidence of [undefined, '0.3', .8]) assert.throws(() => parse({ ...base, recognition: {
    ...base.recognition, uncertainFoods: [{ name: '鸡肉', category: 'protein', confidence }]
  } }), { code: 'INVALID_API_RESPONSE' })
})

test('timeouts, malformed model data and limits retain distinct safe explanations without polling failed tasks', async () => {
  for (const status of ['FAILED', 'RETRYABLE']) {
    for (const [code, action, title] of [
      ['REMOTE_TIMEOUT', 'retry', '模型响应超时'], ['MODEL_RESPONSE_INVALID', 'retry', '识别结果格式无效'],
      ['MODEL_REQUEST_LIMITED', 'wait', '模型服务请求受限'], ['REMOTE_CONFIGURATION', 'wait', '模型连接配置需要检查'],
      ['AI_BUDGET_EXCEEDED', 'wait', 'AI 额度暂时用尽']
    ]) {
      assert.equal(analysisFailure(code).action, action)
      assert.equal(analysisFailure(code).title, title)
      await assert.rejects(resolveMealAnalysis({ key: 'same-request', status, errorCode: code, message: 'private-provider-body' }, {
        load: async () => assert.fail('terminal task does not poll or resubmit')
      }), error => error.code === code && !error.message.includes('private-provider-body'))
    }
  }
})
