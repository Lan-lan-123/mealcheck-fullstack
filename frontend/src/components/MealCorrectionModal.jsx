import { useRef, useState } from 'react'
import useDialogFocus from '../hooks/useDialogFocus'
import { correctMeal } from '../api/client'
import { categoryName } from '../utils/mealLabels'

const categories = ['staple', 'protein', 'vegetable', 'fruit', 'dairy', 'soup', 'drink', 'dessert', 'fried', 'oily', 'other']
export default function MealCorrectionModal({ record, onClose, onSaved }) {
  const [foods, setFoods] = useState(() => (record.foods || []).map(food => ({ name: food.name, category: food.category })))
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const dialog = useRef(null)
  useDialogFocus(dialog, onClose, saving)
  function change(index, field, value) { setFoods(current => current.map((food, i) => i === index ? { ...food, [field]: value } : food)) }
  async function save(event) {
    event.preventDefault()
    if (saving) return
    setSaving(true); setError('')
    try { onSaved(await correctMeal(record.id, { expectedRevision: record.revision || 0, foods, reason })) }
    catch (err) { setError(err.status === 409 ? '记录已被修改，请关闭窗口并刷新后再编辑。' : err.message) }
    finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section ref={dialog} className="weekly-modal" role="dialog" aria-modal="true" aria-label="纠正识别结果">
    <div className="modal-head"><h2>纠正识别结果</h2><button type="button" disabled={saving} onClick={onClose}>关闭</button></div>
    <p>修改名称、分类，或添加漏识别的菜品。保存后将重新计算评分；原始结果与每次修改都会保留。</p>
    <form onSubmit={save}>
      {foods.map((food, index) => <div className="correction-row" key={index}>
        <input aria-label={`食物名称 ${index + 1}`} value={food.name} maxLength={100} required disabled={saving} onChange={e => change(index, 'name', e.target.value)} />
        <select aria-label={`食物分类 ${index + 1}`} value={food.category} disabled={saving} onChange={e => change(index, 'category', e.target.value)}>
          {categories.map(category => <option key={category} value={category}>{categoryName(category)}</option>)}
        </select>
        <button type="button" disabled={saving} onClick={() => setFoods(foods.filter((_, i) => i !== index))}>移除</button>
      </div>)}
      <button type="button" disabled={saving || foods.length >= 30} onClick={() => setFoods([...foods, { name: '', category: 'other' }])}>添加食物</button>
      <label>修改原因<input aria-label="修改原因" value={reason} maxLength={500} required disabled={saving} onChange={e => setReason(e.target.value)} /></label>
      {error && <p role="alert" className="error">{error}</p>}
      <button className="primary" disabled={saving || !foods.length}>{saving ? '保存中...' : '保存并重新评分'}</button>
    </form>
  </section></div>
}
