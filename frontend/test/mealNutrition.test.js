import test from 'node:test'
import assert from 'node:assert/strict'
import { nutritionDraft, nutritionPayload } from '../src/utils/mealNutrition.js'

test('unknown values stay null while explicit zeros survive a save and reload', () => {
  const draft = nutritionDraft([{ foodIndex: 0, name: '示例食物', grams: 150, per100g: { energyKcal: 0, proteinGrams: null }, source: '标签' }])
  const payload = nutritionPayload({ recordRevision: 3, version: 2 }, true, draft)
  assert.equal(payload.items[0].grams, 150)
  assert.equal(payload.items[0].per100g.energyKcal, 0)
  assert.equal(payload.items[0].per100g.proteinGrams, null)
  assert.equal(payload.items[0].per100g.fatGrams, null)
  assert.equal(payload.expectedRevision, 3)
  assert.equal(payload.expectedVersion, 2)
})

test('disabling clears all submitted data and never opts a meal in implicitly', () => {
  const draft = nutritionDraft([{ foodIndex: 0, name: '示例食物', grams: 100 }])
  assert.deepEqual(nutritionPayload({ recordRevision: 0, version: 4 }, false, draft), {
    enabled: false, expectedRevision: 0, expectedVersion: 4, items: []
  })
  draft[0].grams = ' '
  assert.equal(nutritionPayload({ recordRevision: 0, version: 4 }, true, draft).items[0].grams, null)
})
