import test from 'node:test'
import assert from 'node:assert/strict'
import { foodForEditing, unknownFoodAttributes, foodAttributeSummary } from '../src/utils/foodClassification.js'

test('legacy fried and oily categories expose base groups and independent risks without mutating history', () => {
  const chicken = { name: '炸鸡', category: 'fried' }
  assert.deepEqual(foodForEditing(chicken), { name: '炸鸡', category: 'protein', attributes: { fried: true, highOil: null } })
  assert.equal(chicken.category, 'fried')
  assert.equal(foodForEditing({ name: '薯条', category: 'fried' }).category, 'staple')
  assert.deepEqual(foodForEditing({ name: '红烧肉', category: 'oily' }).attributes, { fried: null, highOil: true })
  assert.equal(foodForEditing({ name: '未知炸物', category: 'fried' }).category, 'other')
})

test('explicit unknown and false attributes survive independent editing', () => {
  const unknown = unknownFoodAttributes()
  assert.deepEqual(foodForEditing({ name: '炸鸡', category: 'fried', attributes: unknown }).attributes, unknown)
  assert.deepEqual(foodForEditing({ name: '红烧肉', category: 'protein', attributes: { fried: false, highOil: false } }).attributes,
    { fried: false, highOil: false })
  assert.equal(foodAttributeSummary({ name: '豆腐', category: 'protein', attributes: unknown }), '')
  assert.equal(foodAttributeSummary({ name: '豆腐', category: 'protein', attributes: { fried: false, highOil: true } }), '高油风险')
})
