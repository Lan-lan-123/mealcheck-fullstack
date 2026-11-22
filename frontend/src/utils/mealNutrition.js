// @ts-check
export const nutritionWarning = '营养估算不准确，图片无法准确判断重量、配料和用油量；即使填写份量，结果仍可能存在较大误差，仅供参考。'

/** @type {{key: import('../api/contracts').NutrientKey; label: string; max: number}[]} */
export const nutrientFields = [
  { key: 'energyKcal', label: '能量（kcal / 100g）', max: 1000 },
  { key: 'proteinGrams', label: '蛋白质（g / 100g）', max: 100 },
  { key: 'fatGrams', label: '脂肪（g / 100g）', max: 100 },
  { key: 'carbohydrateGrams', label: '碳水化合物（g / 100g）', max: 100 }
]

/** @param {import('../api/contracts').NutritionItem[]} [items] @returns {import('../api/contracts').NutritionDraftItem[]} */
export function nutritionDraft(items = []) {
  return items.map(item => ({
    foodIndex: item.foodIndex, name: item.name, grams: item.grams == null ? '' : String(item.grams),
    source: item.source || '',
    per100g: { energyKcal: format(item.per100g?.energyKcal), proteinGrams: format(item.per100g?.proteinGrams),
      fatGrams: format(item.per100g?.fatGrams), carbohydrateGrams: format(item.per100g?.carbohydrateGrams) }
  }))
}

/** @param {number | null | undefined} value */
function format(value) { return value == null ? '' : String(value) }
/** @param {string} value @returns {number | null} */
function optionalNumber(value) {
  if (value.trim() === '') return null
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) throw new Error('请填写有效的份量和营养数值。')
  return parsed
}

/** @param {Pick<import('../api/contracts').NutritionResponse, 'recordRevision' | 'version'>} data @param {boolean} enabled
 * @param {import('../api/contracts').NutritionDraftItem[]} items @returns {import('../api/contracts').NutritionUpdate} */
export function nutritionPayload(data, enabled, items) {
  return {
    enabled, expectedRevision: data.recordRevision, expectedVersion: data.version,
    items: enabled ? items.map(item => ({
      foodIndex: item.foodIndex, grams: optionalNumber(item.grams), source: item.source.trim() || null,
      per100g: { energyKcal: optionalNumber(item.per100g.energyKcal), proteinGrams: optionalNumber(item.per100g.proteinGrams),
        fatGrams: optionalNumber(item.per100g.fatGrams), carbohydrateGrams: optionalNumber(item.per100g.carbohydrateGrams) }
    })) : []
  }
}
