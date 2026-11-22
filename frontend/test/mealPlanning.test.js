import test from 'node:test'
import assert from 'node:assert/strict'
import { validateApiResponse, ResponseValidationError } from '../src/api/validation.js'

const today = { date: '2026-10-18', businessZone: 'Asia/Shanghai', totalMeals: 1,
  slots: { BREAKFAST: 'RECORDED', LUNCH: 'SKIPPED', DINNER: 'UNKNOWN', SNACK: 'UNKNOWN' },
  reviewFoodCount: 2, reviewMealCount: 1, reviews: [{ recordId: 1, mealType: 'BREAKFAST', foods: ['牛奶', '候选食物'], foodCount: 2 }],
  pendingPlanCount: 0, pendingPlans: [] }
const recommendations = { date: '2026-10-18', constraints: { mealType: 'LUNCH', scene: 'CANTEEN', budget: 20, availableMinutes: 30 },
  goal: 'balanced', preferenceSummary: '不限', recentMealCount: 0, context: '暂无记录', costBasis: '参考估计', timeBasis: '不含配送', message: '选择搭配',
  options: [{ id: 'a', title: '搭配', foods: ['米饭', '豆腐', '青菜'], costMin: 10, costMax: 15, readyMinutes: 5, reasons: ['参考搭配'], actionText: '下一餐搭配' }] }
test('today contract preserves unknown and skipped slots and exact counts', () => {
  const value = validateApiResponse('/api/meals/today', today)
  assert.equal(value.slots.LUNCH, 'SKIPPED'); assert.equal(value.slots.DINNER, 'UNKNOWN')
  assert.equal(value.reviewFoodCount, 2)
})
test('today rejects inconsistent counts, missing slots and completed plans', () => {
  for (const value of [{ ...today, reviewFoodCount: 1 }, { ...today, reviewMealCount: 0 }, { ...today, slots: {} },
    { ...today, pendingPlanCount: 1, pendingPlans: [{ id: 1, title: '计划', actionText: '内容', sourceType: 'MANUAL', plannedDate: today.date, status: 'DONE', version: 0 }] }]) {
    assert.throws(() => validateApiResponse('/api/meals/today', value), ResponseValidationError)
  }
})
test('recommendations accept empty matches and reject options that violate constraints', () => {
  assert.equal(validateApiResponse('/api/meals/next-meal', recommendations).options.length, 1)
  assert.deepEqual(validateApiResponse('/api/meals/next-meal', { ...recommendations, options: [] }).options, [])
  for (const option of [{ ...recommendations.options[0], costMax: 21 }, { ...recommendations.options[0], readyMinutes: 31 },
    { ...recommendations.options[0], costMin: 16 }, { ...recommendations.options[0], foods: [] }]) {
    assert.throws(() => validateApiResponse('/api/meals/next-meal', { ...recommendations, options: [option] }), ResponseValidationError)
  }
  assert.throws(() => validateApiResponse('/api/meals/next-meal', { ...recommendations, options: [...recommendations.options, ...recommendations.options] }), ResponseValidationError)
})
