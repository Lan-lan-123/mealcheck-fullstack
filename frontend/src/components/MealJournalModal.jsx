import { useRef, useState } from 'react'
import { createManualMeal, updateMealMetadata } from '../api/client'
import { businessDateTime, mealTypes } from '../utils/time'
import { categoryName } from '../utils/mealLabels'
import useDialogFocus from '../hooks/useDialogFocus'

const categories = ['staple', 'protein', 'vegetable', 'fruit', 'dairy', 'soup', 'drink', 'dessert', 'fried', 'oily', 'other']
export default function MealJournalModal({ record, onClose, onSaved }) {
  const [time, setTime] = useState(() => businessDateTime(record?.occurredAt || record?.createdAt))
  const [type, setType] = useState(record?.mealType || 'UNSPECIFIED')
  const [note, setNote] = useState(record?.note || '')
  const [foods, setFoods] = useState([{ name: '', category: 'other' }])
  const [saving, setSaving] = useState(false), [error, setError] = useState('')
  const dialog = useRef(null)
  useDialogFocus(dialog, onClose, saving)
  async function submit(e) {
    e.preventDefault(); if (saving) return; setSaving(true); setError('')
    const body = { occurredAt: time, mealType: type, note, expectedVersion: record?.metadataVersion || 0 }
    try { onSaved(record ? await updateMealMetadata(record.id, body) : await createManualMeal({ ...body, goal: 'current', foods })) }
    catch (err) { setError(err.status === 409 ? '用餐信息已变动，请刷新后再编辑。' : err.message) }
    finally { setSaving(false) }
  }
  return <div className="modal-backdrop"><section className="weekly-modal journal-modal" role="dialog" aria-modal="true" aria-label={record ? '修改用餐信息' : '手动记录与补记'} ref={dialog}>
    <div className="modal-head"><h2>{record ? '修改用餐信息' : '手动记录与补记'}</h2><button type="button" disabled={saving} onClick={onClose}>关闭</button></div>
    <p className="hint">用餐时间按项目业务时区填写。补记会归入实际用餐日期，创建时间保留。</p>
    <form className="journal-form" onSubmit={submit}>
      <label className="form-field">实际用餐时间<input type="datetime-local" required min="2000-01-01T00:00" max={businessDateTime()} value={time} onChange={e => setTime(e.target.value)} disabled={saving} /></label>
      <label className="form-field">餐次<select aria-label="餐次" value={type} onChange={e => setType(e.target.value)} disabled={saving}>{Object.entries(mealTypes).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      {!record && <fieldset><legend>手动填写食物（不调用图片识别）</legend>{foods.map((food, index) => <div className="correction-row" key={index}>
        <input aria-label={`手动食物名称 ${index + 1}`} required maxLength={100} value={food.name} disabled={saving} onChange={e => setFoods(items => items.map((f, i) => i === index ? { ...f, name: e.target.value } : f))} />
        <select aria-label={`手动食物分类 ${index + 1}`} value={food.category} disabled={saving} onChange={e => setFoods(items => items.map((f, i) => i === index ? { ...f, category: e.target.value } : f))}>{categories.map(c => <option key={c} value={c}>{categoryName(c)}</option>)}</select>
        <button type="button" disabled={saving || foods.length === 1} onClick={() => setFoods(items => items.filter((_, i) => i !== index))}>移除</button>
      </div>)}<button type="button" disabled={saving || foods.length >= 30} onClick={() => setFoods(items => [...items, { name: '', category: 'other' }])}>添加手动食物</button></fieldset>}
      <label className="form-field">备注<textarea value={note} maxLength={500} disabled={saving} onChange={e => setNote(e.target.value)} /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="primary" disabled={saving}>{saving ? '保存中…' : '保存用餐记录'}</button>
    </form>
  </section></div>
}
