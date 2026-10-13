import { useEffect, useRef, useState } from 'react'
import { mealNutrition, updateMealNutrition } from '../api/client'
import { nutrientFields, nutritionDraft, nutritionPayload, nutritionWarning } from '../utils/mealNutrition'

export default function MealNutritionPanel({ recordId }) {
  const [data, setData] = useState(null)
  const [enabled, setEnabled] = useState(false)
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [reload, setReload] = useState(0)
  const writeController = useRef(null)

  function accept(next) {
    if (!next || !Array.isArray(next.items) || typeof next.enabled !== 'boolean') {
      throw new Error('份量设置暂时不可用，请重新加载。')
    }
    setData(next); setEnabled(next.enabled); setItems(nutritionDraft(next.items)); setDirty(false)
  }

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setData(null); setError(''); setMessage('')
    mealNutrition(recordId, controller.signal).then(next => {
      if (!controller.signal.aborted) accept(next)
    }).catch(err => { if (!controller.signal.aborted) setError(err.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); writeController.current?.abort() }
  }, [recordId, reload])

  function change(index, field, value, nutrient = false) {
    setItems(current => current.map((item, i) => i === index
      ? nutrient ? { ...item, per100g: { ...item.per100g, [field]: value } } : { ...item, [field]: value }
      : item))
    setDirty(true); setMessage('')
  }

  async function save(event) {
    event.preventDefault()
    if (saving || !data) return
    const controller = new AbortController()
    writeController.current = controller
    setSaving(true); setError(''); setMessage('')
    try {
      const next = await updateMealNutrition(recordId, nutritionPayload(data, enabled, items), controller.signal)
      if (!controller.signal.aborted) {
        accept(next)
        setMessage(next.enabled ? '已保存可选份量和估算资料。' : '已关闭，份量和估算资料已清除。')
      }
    } catch (err) {
      if (!controller.signal.aborted) setError(err.status === 409 ? '餐食或份量已被修改，请重新加载后填写。' : err.message)
    } finally {
      if (!controller.signal.aborted) setSaving(false)
      if (writeController.current === controller) writeController.current = null
    }
  }

  return <section className="nutrition-panel" aria-label="可选份量与营养估算">
    <h3>份量与营养粗略估算（可选）</h3>
    <p className="hint">默认关闭。跳过也可正常查看识别、结构评分和饮食建议。</p>
    <p className="nutrition-warning">{nutritionWarning}</p>
    {loading && <p role="status">正在读取可选设置...</p>}
    {error && <div className="error" role="alert">{error}<button className="secondary-btn" type="button" disabled={saving || loading}
      onClick={() => setReload(value => value + 1)}>重新加载设置</button></div>}
    {!loading && data && <form onSubmit={save}>
      <label className="nutrition-toggle"><input type="checkbox" checked={enabled} disabled={saving || (!enabled && items.length === 0)}
        onChange={event => { setEnabled(event.target.checked); setDirty(true); setMessage('') }} />开启本餐可选份量与估算</label>
      {items.length === 0 && <p role="status">当前餐食没有可估算的食物项，请先纠正识别结果。</p>}
      {enabled && <>
        <p className="hint">请填写重量（克）。有营养标签或可靠资料时，可补充每 100 克的数据并注明来源；未知值留空，不会按零计算。</p>
        {data.status === 'STALE' && <p role="status" className="nutrition-warning">食物识别已修改，旧估算已失效，请重新填写份量和数据。</p>}
        {items.map((item, index) => <fieldset className="nutrition-food" key={item.foodIndex} disabled={saving}>
          <legend>{item.name}</legend>
          <label>份量（克）<input type="number" aria-label={`${item.name} 份量（克）`} min="0.1" max="10000" step="0.01"
            placeholder="未知可留空" value={item.grams} onChange={event => change(index, 'grams', event.target.value)} /></label>
          <details><summary>填写每 100 克营养数据（可选）</summary>
            <div className="nutrition-inputs">{nutrientFields.map(field => <label key={field.key}>{field.label}
              <input type="number" aria-label={`${item.name} ${field.label}`} min="0" max={field.max} step="0.01"
                placeholder="未知可留空" value={item.per100g[field.key]} onChange={event => change(index, field.key, event.target.value, true)} />
            </label>)}</div>
            <label>数据来源<input aria-label={`${item.name} 数据来源`} maxLength={200} placeholder="例如：包装标签或资料名称及链接"
              value={item.source} required={Object.values(item.per100g).some(value => value !== '')}
              onChange={event => change(index, 'source', event.target.value)} /></label>
          </details>
        </fieldset>)}
        {!dirty && data.status === 'INCOMPLETE' && <>
          <p role="status">无法估算整餐：份量或营养数据不完整。已保存填写内容，未知项不会按零计算。</p>
          <p className="hint">已具备完整估算资料：{data.estimatedFoods ?? 0} / {items.length} 项食物。</p>
        </>}
        {!dirty && data.status === 'ESTIMATED' && data.totals && <div className="nutrition-estimate" role="status">
          <strong>本餐营养粗略估算 · 仅供参考</strong>
          <dl><div><dt>估算能量</dt><dd>{data.totals.energyKcal} kcal</dd></div>
            <div><dt>估算蛋白质</dt><dd>{data.totals.proteinGrams} g</dd></div>
            <div><dt>估算脂肪</dt><dd>{data.totals.fatGrams} g</dd></div>
            <div><dt>估算碳水</dt><dd>{data.totals.carbohydrateGrams} g</dd></div></dl>
          <p className="hint">按您填写的份量和每 100 克资料换算，未经系统核实。估算值不参与结构评分、趋势或周报达标判断。</p>
        </div>}
        {dirty && <p className="hint">填写内容尚未保存，保存后重新计算。</p>}
      </>}
      {!enabled && data.enabled && <p className="hint">保存关闭后，将清除本餐已填写的份量和估算资料。</p>}
      <button className="secondary-btn" type="submit" disabled={saving || !dirty}>
        {saving ? '保存中...' : enabled ? '保存份量与粗略估算' : '保存关闭设置'}
      </button>
      {message && <p role="status">{message}</p>}
    </form>}
  </section>
}
