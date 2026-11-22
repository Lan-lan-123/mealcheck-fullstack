// @ts-check
export const REVIEW_CONFIDENCE = 0.8
/** @param {import('../api/contracts').FoodItem} food */
export function needsFoodReview(food) {
  return !!food.reviewIssues?.length || (typeof food.confidence === 'number' && food.confidence < REVIEW_CONFIDENCE) || quickReviewQuestions(food).length > 0
}
/** @param {import('../api/contracts').FoodItem} food
 * @returns {{key: 'identity' | 'category' | 'fried' | 'highOil'; label: string; options: string[]}[]} */
export function quickReviewQuestions(food) {
  const note = food.note || '', name = food.name || ''
  const questions = []
  const identity = /可能是|可能为|疑似|或豆浆|或牛奶|无法辨认|名称不确定|未确认/.test(note + name)
  if (identity || (typeof food.confidence === 'number' && food.confidence < REVIEW_CONFIDENCE)) {
    const drinks = /^(牛奶|纯牛奶|鲜奶|低脂牛奶|豆浆|无糖豆浆|未确认饮品)$/.test(name.trim())
      || (/牛奶/.test(note) && /豆浆/.test(note))
    questions.push({ key: /** @type {const} */ ('identity'), label: '这是什么食物？', options: drinks ? ['牛奶', '豆浆', '不确定'] : ['名称与分类正确', '不确定'] })
  }
  /** @param {string} code */
  const conflict = code => food.reviewIssues?.some(issue => issue.code === code)
  if (conflict('CATEGORY_CONFLICT'))
    questions.push({ key: /** @type {const} */ ('category'), label: '按项目口径核对分类', options: ['主食', '不确定'] })
  if (food.attributes?.fried == null && (conflict('FRYING_CONFLICT') || /炸鸡|薯条|炸鱼|炸豆腐|油炸|炸制|煎炸/.test(name + note)))
    questions.push({ key: /** @type {const} */ ('fried'), label: '是否经过油炸？', options: ['是', '否', '不确定'] })
  if (food.attributes?.highOil == null && (conflict('OIL_CONFLICT') || (food.attributes?.fried !== true && /红烧肉|五花肉|红烧茄子|用油|油光|油润|油腻|高油|肥瘦/.test(name + note))))
    questions.push({ key: /** @type {const} */ ('highOil'), label: '是否有明显高油风险？', options: ['是', '否', '不确定'] })
  return questions
}
/** @param {unknown} code @param {string} [message] */
export function analysisFailure(code, message = '') {
  if (code === 'ANALYSIS_STORAGE_BUSY') return { title: '图片存储暂时繁忙', message: '请等待已有图片处理完成后，使用原图和原请求重试。', action: 'retry' }
  if (code === 'NON_FOOD') return { title: '未识别到餐食', message: '图片未识别为餐食。请选择包含食物或饮品的照片；如果判断有误，可换一个角度重新拍摄。', action: 'replace' }
  if (code === 'IMAGE_UNCLEAR') return { title: '图片不清晰或细节不足', message: '暂时无法可靠识别食物。请在光线充足处拍摄，保持镜头稳定，让餐食完整入镜并避免遮挡。', action: 'replace' }
  if (code === 'RECOGNITION_INCONSISTENT') return { title: '识别结果不一致', message: '模型返回的餐食标记与食物列表不一致，暂时无法确认结果。请稍后使用原图重试；这不表示图片没有食物。', action: 'retry' }
  if (code === 'NON_FOOD_LIMIT') return { title: '上传暂受限制', message: '连续上传非食物图片已触发限制。请等待限制解除，再选择清晰的餐食照片。', action: 'wait' }
  if (code === 'REMOTE_TIMEOUT' || code === 'AI_DEADLINE_EXCEEDED') return { title: '模型响应超时', message: '本次分析未能在时限内完成。请稍后使用原图重试；无需因此更换照片。', action: 'retry' }
  if (code === 'MODEL_RESPONSE_INVALID') return { title: '识别结果格式无效', message: '模型返回的数据格式不完整或字段无效，暂未生成分析结果。请稍后使用原图重试；这不表示照片不清晰或没有食物。', action: 'retry' }
  if (code === 'MODEL_REQUEST_LIMITED') return { title: '模型服务请求受限', message: '请等待服务限制解除；如果持续出现，请联系管理员检查模型服务额度。无需更换餐食照片。', action: 'wait' }
  if (code === 'REMOTE_CONFIGURATION') return { title: '模型连接配置需要检查', message: '模型接口拒绝了请求，请联系管理员检查密钥、模型权限及连接配置后再试。无需更换餐食照片。', action: 'wait' }
  if (code === 'AI_BUDGET_EXCEEDED') return { title: 'AI 额度暂时用尽', message: '今日 AI 额度已用尽，请查看额度重置时间，重置后再使用原图重试。', action: 'wait' }
  if (['MODEL_SERVICE_ERROR', 'AI_CALL_FAILED', 'DEPENDENCY_BUSY', 'DEPENDENCY_UNAVAILABLE'].includes(String(code))) {
    return { title: '模型服务暂时异常', message: '模型服务暂时不可用、超时或返回无效结果。请稍后使用原图重试；无需因此更换餐食照片。', action: 'retry' }
  }
  if (['AI_BUDGET_UNAVAILABLE', 'UPLOAD_BUSY'].includes(String(code))) return { title: '暂未取得分析结果', message, action: 'wait' }
  return { title: '暂未取得分析结果', message: message || '请查询上次分析结果，确认任务状态后再重试。', action: 'query' }
}
