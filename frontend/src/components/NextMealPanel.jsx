// @ts-check
import { useEffect, useRef, useState } from 'react'
import { CookingPot } from 'lucide-react'
import Button from './Button'
import PlanSaveButton from './PlanSaveButton'
import { recommendNextMeal } from '../api/client'
import { businessDateTime, mealTypes } from '../utils/time'
import useDietDataRevision from '../hooks/useDietDataRevision'

const contextChanges = ['meals', 'diet-goal', 'diet-preferences']
/** @param {{day: string; revision: number}} props */
export default function NextMealPanel({ day, revision }) {
  const contextRevision = useDietDataRevision(contextChanges)
  const [form, setForm] = useState(/** @type {import('../api/contracts').NextMealConstraints} */ ({
    mealType: Number(businessDateTime().slice(11, 13)) < 10 ? 'BREAKFAST' : Number(businessDateTime().slice(11, 13)) < 15 ? 'LUNCH' : 'DINNER',
    scene: 'CANTEEN', budget: 20, availableMinutes: 30
  }))
  const [result, setResult] = useState(/** @type {import('../api/contracts').NextMealRecommendations | null} */ (null))
  const [selected, setSelected] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [savedChoices, setSavedChoices] = useState(/** @type {Set<string>} */ (new Set()))
  const controller = useRef(/** @type {AbortController | null} */ (null))
  useEffect(() => { setSavedChoices(new Set()) }, [day])
  useEffect(() => {
    controller.current?.abort(); controller.current = null
    setResult(null); setSelected(''); setBusy(false); setError('')
    return () => { controller.current?.abort(); controller.current = null }
  }, [day, revision, contextRevision])
  /** @param {Partial<import('../api/contracts').NextMealConstraints>} patch */
  function edit(patch) {
    controller.current?.abort(); controller.current = null
    setForm(current => ({ ...current, ...patch })); setResult(null); setSelected(''); setBusy(false); setError('')
  }
  /** @param {import('react').FormEvent} event */
  async function submit(event) {
    event.preventDefault()
    if (controller.current) return
    const request = new AbortController(); controller.current = request
    setBusy(true); setError(''); setResult(null); setSelected('')
    try {
      const next = await recommendNextMeal(form, request.signal)
      if (!request.signal.aborted) { setResult(next); setSelected(next.options[0]?.id || '') }
    } catch (err) { if (!request.signal.aborted) setError(err.message) }
    finally { if (controller.current === request) { controller.current = null; setBusy(false) } }
  }
  const chosen = result?.options.find(option => option.id === selected)
  return <section className="card next-meal-panel" aria-labelledby="next-meal-title">
    <div className="planning-heading"><div><p className="planning-eyebrow">把建议变成下一餐</p><h2 id="next-meal-title"><CookingPot size={20} />下一餐搭配推荐</h2></div><span className="planning-badge">搭配库参考</span></div>
    <p className="hint">结合当前目标、饮食偏好和近期记录，筛选三套以内的搭配。预算为每人每餐人民币参考上限。</p>
    <form className="next-meal-form" onSubmit={submit}>
      <label className="form-field">推荐餐次<select value={form.mealType} onChange={e => edit({ mealType: /** @type {import('../api/contracts').NextMealConstraints['mealType']} */ (e.target.value) })}><option value="BREAKFAST">早餐</option><option value="LUNCH">午餐</option><option value="DINNER">晚餐</option></select></label>
      <label className="form-field">用餐场景<select value={form.scene} onChange={e => edit({ scene: /** @type {import('../api/contracts').NextMealConstraints['scene']} */ (e.target.value) })}><option value="CANTEEN">食堂</option><option value="TAKEOUT">外卖</option><option value="HOME">自己准备</option></select></label>
      <label className="form-field">预算上限（元）<input type="number" required min="1" max="500" step="1" value={Number.isNaN(form.budget) ? '' : form.budget} onChange={e => edit({ budget: e.target.valueAsNumber })} /></label>
      <label className="form-field">可用选餐 / 准备时间（分钟）<input type="number" required min="5" max="120" step="1" value={Number.isNaN(form.availableMinutes) ? '' : form.availableMinutes} onChange={e => edit({ availableMinutes: e.target.valueAsNumber })} /></label>
      <Button variant="primary" type="submit" disabled={busy}>{busy ? '正在筛选搭配…' : '推荐下一餐'}</Button>
    </form>
    <p className="hint">食堂与外卖耗时不含排队和配送。实际价格、库存与配料请在选择前核对。</p>
    {error && <p className="error" role="alert">{error}</p>}
    {result && <div className="next-meal-results" aria-live="polite"><p>{result.context}</p><p className="hint">{result.preferenceSummary}</p>
      {!result.options.length ? <p role="status">{result.message}</p> : <><fieldset className="meal-options"><legend>选择一套搭配</legend>{result.options.map(option => <label className={`meal-option ${selected === option.id ? 'is-selected' : ''}`} key={option.id}>
        <div className="meal-option-heading"><input type="radio" name="next-meal-option" value={option.id} checked={selected === option.id} onChange={() => setSelected(option.id)} /><strong>{option.title}</strong></div>
        <div className="meal-option-meta"><span>参考 {option.costMin}–{option.costMax} 元</span><span>约 {option.readyMinutes} 分钟</span></div>
        <ul>{option.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul></label>)}</fieldset>
        {chosen && <div className="selected-meal-plan"><p>已选择：<strong>{chosen.title}</strong></p><PlanSaveButton key={`${result.date}:${JSON.stringify(result.constraints)}:${chosen.id}`} singleUse initialDate={result.date}
          alreadySaved={savedChoices.has(`${result.date}:${chosen.id}`)} onSaved={() => setSavedChoices(current => new Set([...current, `${result.date}:${chosen.id}`]))}
          initialTitle={`下一餐 · ${mealTypes[result.constraints.mealType]}`} text={`下一餐搭配参考：${chosen.actionText}`} /></div>}</>}
      <details><summary>费用与耗时参考口径</summary><p className="hint">{result.costBasis}</p><p className="hint">{result.timeBasis}</p></details>
    </div>}
  </section>
}
