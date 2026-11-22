import Button from './Button'
import { useRef, useState } from 'react'
import useDialogFocus from '../hooks/useDialogFocus'
import { correctMeal } from '../api/client'
import { categoryName } from '../utils/mealLabels'
import { needsFoodReview } from '../utils/recognitionExplanation'
import AuthImage from './AuthImage'
import FoodAttributesEditor from './FoodAttributesEditor'
import QuickFoodReview from './QuickFoodReview'
import { foodCategories as categories, foodForEditing, unknownFoodAttributes } from '../utils/foodClassification'

/** @param {{record: Pick<import('../api/contracts').MealRecord, 'id' | 'foods' | 'revision' | 'uncertainFoods'>; onClose: () => void; onSaved: (result: import('../api/contracts').MealAnalysisResult) => void}} props */
export default function MealCorrectionModal({ record, onClose, onSaved }) {
  const [foods, setFoods] = useState(() => (record.foods || []).map(food => ({ ...foodForEditing(food), confirmed: !needsFoodReview(food) })))
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const dialog = useRef(null)
  useDialogFocus(dialog, onClose, saving)
  const pendingReview = foods.filter(food => needsFoodReview(food) && !food.confirmed).length
  function change(index, field, value) { setFoods(current => current.map((food, i) => i === index ? { ...food, [field]: value,
    ...(field === 'name' ? { attributes: unknownFoodAttributes(), reviewIssues: [] } : {}), confirmed: !needsFoodReview(food) } : food)) }
  function answer(index, key, value) {
    setFoods(current => current.map((food, i) => {
      if (i !== index) return food
      if (key === 'category') return { ...food, category: value === '主食' ? 'staple' : 'other',
        reviewIssues: value === '不确定' ? food.reviewIssues : food.reviewIssues?.filter(issue => issue.code !== 'CATEGORY_CONFLICT'), confirmed: false }
      if (key === 'identity') {
        const unknown = value === '不确定'
        const drink = food.category === 'drink' || /^(牛奶|纯牛奶|鲜奶|低脂牛奶|未确认饮品)$/.test(food.name.trim())
          || (/牛奶/.test(food.note || '') && /豆浆/.test(food.note || ''))
        const name = unknown ? (drink ? '未确认饮品' : '未确认食物')
          : value === '名称与分类正确' ? food.name : value
        return { ...food, name, category: unknown ? (name === '未确认饮品' ? 'drink' : 'other') : value === '牛奶' ? 'dairy' : value === '豆浆' ? 'drink' : food.category,
          note: unknown ? '名称仍待核对' : '用户已核对名称与分类', confidence: 1,
          attributes: name === food.name ? food.attributes : unknownFoodAttributes(),
          reviewIssues: name === food.name ? food.reviewIssues : [], confirmed: false }
      }
      return { ...food, attributes: { ...food.attributes, [key]: value === '不确定' ? null : value === '是' },
        reviewIssues: value === '不确定' ? food.reviewIssues : food.reviewIssues?.filter(issue => issue.code !== (key === 'fried' ? 'FRYING_CONFLICT' : 'OIL_CONFLICT')), confirmed: false }
    }))
  }
  async function save(event) {
    event.preventDefault()
    if (saving) return
    if (pendingReview) { setError('请先核对标记的食物，确认或移除后再保存。'); return }
    setSaving(true); setError('')
    try { onSaved(await correctMeal(record.id, { expectedRevision: record.revision || 0, foods: foods.map(({ name, category, attributes }) => ({ name, category, attributes })), reason })) }
    catch (err) { setError(err.status === 409 ? '记录已被修改，请关闭窗口并刷新后再编辑。' : err.message) }
    finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section ref={dialog} className="weekly-modal" role="dialog" aria-modal="true" aria-label="纠正识别结果">
    <div className="modal-head"><h2>纠正识别结果</h2><Button variant="secondary" className="secondary-btn" type="button" disabled={saving} onClick={onClose}>关闭</Button></div>
    <p>修改名称、分类，或添加漏识别的菜品。保存后将重新计算评分；原始结果与每次修改都会保留。</p>
    <p className="hint">分类表示食物来源，油炸与高油风险分别核对；不确定时保留“待核对”。风险属性不是实际用油量测量，修改名称后请重新核对。</p>
    {(foods.some(needsFoodReview) || !!record.uncertainFoods?.length) && <div className="correction-photo"><AuthImage url={`/api/meals/${record.id}/image`} alt="待核对的餐食图片" /></div>}
    {foods.some(needsFoodReview) && <p role="status">{pendingReview ? `还有 ${pendingReview} 项食物需要核对，确认或移除后才能保存。` : '标记的食物已核对。'}模型自报置信度仅供参考，不等于识别正确的概率。</p>}
    {!!record.uncertainFoods?.length && <div className="recognition-review">
      <p>以下候选未计入评分，请对照照片核对。仅在确认存在时添加，也可以继续修改名称和分类。</p>
      {record.uncertainFoods.map((food, index) => <div key={index}>
        <p>{food.name} · {categoryName(food.category)} · 模型自报置信度 {Math.floor((food.confidence ?? 0) * 100)}%{food.note && ` · 识别线索：${food.note}`}</p>
        <Button variant="secondary" type="button" disabled={saving || foods.length >= 30 || foods.some(item => item.name === food.name)}
          onClick={() => setFoods(current => [...current, { ...foodForEditing(food), confirmed: true }])}>确认并添加：{food.name}</Button>
      </div>)}
    </div>}
    <form onSubmit={save}>
      {foods.map((food, index) => <div className="correction-row" key={index}>
        <QuickFoodReview food={food} index={index} disabled={saving} onAnswer={(key, value) => answer(index, key, value)} />
        <input aria-label={`食物名称 ${index + 1}`} value={food.name} maxLength={100} required disabled={saving} onChange={e => change(index, 'name', e.target.value)} />
        <select aria-label={`食物分类 ${index + 1}`} value={food.category} disabled={saving} onChange={e => change(index, 'category', e.target.value)}>
          {categories.map(category => <option key={category} value={category}>{categoryName(category)}</option>)}
        </select>
        <Button variant="secondary" className="secondary-btn" type="button" disabled={saving} onClick={() => setFoods(foods.filter((_, i) => i !== index))}>移除</Button>
        <FoodAttributesEditor attributes={food.attributes} labelPrefix={`食物 ${index + 1}`} disabled={saving} onChange={value => change(index, 'attributes', value)} />
        {needsFoodReview(food) && <div className="correction-review">
          <p>模型自报置信度 {Math.floor((food.confidence ?? 0) * 100)}%{food.note && ` · 识别线索：${food.note}`}</p>
          <label><input type="checkbox" aria-label={`确认食物 ${index + 1} 已核对`} checked={food.confirmed} disabled={saving}
            onChange={event => setFoods(current => current.map((item, i) => i === index ? { ...item, confirmed: event.target.checked } : item))} />我已核对名称、分类与风险；无法确认的项目保留待核对</label>
        </div>}
      </div>)}
      <Button variant="secondary" className="secondary-btn" type="button" disabled={saving || foods.length >= 30} onClick={() => setFoods([...foods, { name: '', category: 'other', attributes: unknownFoodAttributes(), confirmed: true }])}>添加食物</Button>
      <label>修改原因<input aria-label="修改原因" value={reason} maxLength={500} required disabled={saving} onChange={e => setReason(e.target.value)} /></label>
      {error && <p role="alert" className="error">{error}</p>}
      <Button variant="primary" type="submit" className="primary" disabled={saving || !foods.length || pendingReview > 0}>{saving ? '保存中...' : '保存并重新评分'}</Button>
    </form>
  </section></div>
}
