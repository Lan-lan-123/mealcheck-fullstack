import test from 'node:test'
import assert from 'node:assert/strict'
import { quickReviewQuestions, needsFoodReview } from '../src/utils/recognitionExplanation.js'
import { validateApiResponse, ResponseValidationError } from '../src/api/validation.js'

test('high confidence does not suppress explicit identity ambiguity or an unresolved risk', () => {
  const drink = { name: '牛奶', category: 'dairy', confidence: .95, note: '可能是牛奶或豆浆', attributes: { fried: null, highOil: null } }
  assert.equal(needsFoodReview(drink), true)
  assert.deepEqual(quickReviewQuestions(drink)[0].options, ['牛奶', '豆浆', '不确定'])
  const pork = { name: '红烧肉', category: 'protein', confidence: .99, attributes: { fried: false, highOil: null } }
  assert.equal(quickReviewQuestions(pork)[0].key, 'highOil')
  assert.equal(quickReviewQuestions({ ...pork, attributes: { fried: false, highOil: false } }).length, 0)
  assert.equal(needsFoodReview({ name: '未确认饮品', category: 'drink', confidence: 1 }), true)
  assert.equal(needsFoodReview({ name: '青菜', category: 'vegetable', confidence: .99, note: '绿色菜叶' }), false)
  assert.deepEqual(quickReviewQuestions({ name: '奶油蛋糕', category: 'dessert', confidence: .6 })[0].options, ['名称与分类正确', '不确定'])
})

test('score details preserve signed rules, reject inconsistent totals and support old records', () => {
  const old = { id: 1, createdAt: '2026-10-17T10:00:00', score: 0, foods: [] }
  const path = '/api/meals/1'
  assert.equal(validateApiResponse(path, old).scoreBreakdown, null)
  const scoreBreakdown = { scoringVersion: 'v5', total: 0, items: [
    { rule: 'base', label: '基础分', points: 50, foods: [] },
    { rule: 'risk', label: '风险扣分', points: -60, foods: ['炸鸡'] },
    { rule: 'range', label: '限制到零', points: 10, foods: [] }] }
  assert.deepEqual(validateApiResponse(path, { ...old, scoreBreakdown }).scoreBreakdown, scoreBreakdown)
  assert.throws(() => validateApiResponse(path, { ...old, scoreBreakdown: { ...scoreBreakdown, total: 1 } }), ResponseValidationError)
  assert.throws(() => validateApiResponse(path, { ...old, score: 10, scoreBreakdown }), ResponseValidationError)
})

test('semantic conflicts survive API parsing and stay reviewable at high confidence', () => {
  const food = { name: '番茄炒蛋', category: 'protein', confidence: .99, note: '炒制',
    attributes: { fried: null, highOil: true }, reviewIssues: [{ code: 'FRYING_CONFLICT', message: '油炸属性与炒制线索冲突' }] }
  const parsed = validateApiResponse('/api/meals/1', { id: 1, createdAt: '2026-10-17T10:00:00', score: 67, foods: [food] }).foods[0]
  assert.deepEqual(parsed.reviewIssues, food.reviewIssues)
  assert.equal(needsFoodReview(parsed), true)
  assert.equal(quickReviewQuestions(parsed)[0].key, 'fried')
  assert.equal(parsed.attributes.highOil, true)
  assert.equal(quickReviewQuestions({ ...food, reviewIssues: [{ code: 'CATEGORY_CONFLICT', message: '玉米分类待核对' }] })[0].key, 'category')
})

test('preferences have strict styles and versions without dropping saved restrictions', () => {
  const settings = { style: 'VEGAN', avoidFoods: ['花生'], dislikedFoods: ['香菜'], version: 2 }
  assert.deepEqual(validateApiResponse('/api/profile/diet-preferences', settings), settings)
  assert.throws(() => validateApiResponse('/api/profile/diet-preferences', { ...settings, version: -1 }), ResponseValidationError)
  assert.throws(() => validateApiResponse('/api/profile/diet-preferences', { ...settings, style: 'anything' }), ResponseValidationError)
})
