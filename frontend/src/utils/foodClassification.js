// @ts-check
export const foodCategories = ['staple', 'protein', 'vegetable', 'fruit', 'dairy', 'soup', 'drink', 'dessert', 'other']
/** @returns {import('../api/contracts').FoodAttributes} */
export function unknownFoodAttributes() { return { fried: null, highOil: null } }

// Same narrow legacy-name mapping as the server. Unknown ingredients remain 'other' for human review.
/** @param {string} name @param {string} category */
function editableCategory(name, category) {
  if (foodCategories.includes(category)) return category
  if (!['fried', 'oily'].includes(category)) return 'other'
  const normalized = name.trim().toLowerCase().replace(/[\u2010-\u2015\u2212]/g, '-').replace(/\s+/g, ' ')
  if (/^(?:(?:油炸|香酥|酥炸|脆皮|煎炸|炸)?(?:鸡|鸡腿|鸡翅|鸡块|鸡排|鸡胸肉|鸡肉|鱼|鱼排|鱼块|虾|虾仁|豆腐)(?:块)?|红烧肉|红烧五花肉|五花肉|braised pork belly|pork belly|(?:(?:deep|pan)[ -])?fried (?:chicken|fish|shrimp|tofu)(?: wings| legs| pieces)?)$/.test(normalized)) return 'protein'
  if (/^(?:(?:油炸|香脆|酥脆|炸)?(?:薯条|土豆条|油条|馒头)|(?:french |fried )?fries)$/.test(normalized)) return 'staple'
  return 'other'
}

/** @template {{name: string, category: string, attributes?: import('../api/contracts').FoodAttributes | null}} T @param {T} food */
export function foodForEditing(food) {
  const supplied = food.attributes
  const category = food.category.trim().toLowerCase()
  return { ...food, category: editableCategory(food.name, category), attributes: {
    fried: supplied != null ? supplied.fried ?? null : category === 'fried' ? true : null,
    highOil: supplied != null ? supplied.highOil ?? null : category === 'oily' ? true : null
  } }
}

/** @param {import('../api/contracts').FoodItem} food */
export function foodAttributeSummary(food) {
  return [food.attributes?.fried === true ? '油炸做法' : '', food.attributes?.highOil === true ? '高油风险' : ''].filter(Boolean).join('、')
}
