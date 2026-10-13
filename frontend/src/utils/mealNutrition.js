export const nutritionWarning = '营养估算不准确，图片无法准确判断重量、配料和用油量；即使填写份量，结果仍可能存在较大误差，仅供参考。'

export const nutrientFields = [
  { key: 'energyKcal', label: '能量（kcal / 100g）', max: 1000 },
  { key: 'proteinGrams', label: '蛋白质（g / 100g）', max: 100 },
  { key: 'fatGrams', label: '脂肪（g / 100g）', max: 100 },
  { key: 'carbohydrateGrams', label: '碳水化合物（g / 100g）', max: 100 }
]

export function nutritionDraft(items = []) {
  return items.map(item => ({
    foodIndex: item.foodIndex, name: item.name, grams: item.grams == null ? '' : String(item.grams),
    source: item.source || '',
    per100g: Object.fromEntries(nutrientFields.map(({ key }) => [key, item.per100g?.[key] == null ? '' : String(item.per100g[key])]))
  }))
}

function optionalNumber(value) { return value == null || String(value).trim() === '' ? null : Number(value) }

export function nutritionPayload(data, enabled, items) {
  return {
    enabled, expectedRevision: data.recordRevision, expectedVersion: data.version,
    items: enabled ? items.map(item => ({
      foodIndex: item.foodIndex, grams: optionalNumber(item.grams), source: item.source.trim() || null,
      per100g: Object.fromEntries(nutrientFields.map(({ key }) => [key, optionalNumber(item.per100g[key])]))
    })) : []
  }
}
