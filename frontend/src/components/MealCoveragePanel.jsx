import { useEffect, useState } from 'react'
import { declareSkippedMeal, mealCoverage } from '../api/client'
import { businessToday, mealTypes } from '../utils/time'
import TrackingSummary from './TrackingSummary'

export default function MealCoveragePanel({ revision, onChanged }) {
  const [day, setDay] = useState(businessToday), [summary, setSummary] = useState(null)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => {
    const controller = new AbortController(); setSummary(null); setError('')
    mealCoverage(day, day, controller.signal).then(setSummary).catch(err => { if (err.name !== 'AbortError') setError(err.message) })
    return () => controller.abort()
  }, [day, revision])
  async function toggle(type, state) {
    if (busy) return; setBusy(true); setError('')
    try { await declareSkippedMeal({ day, mealType: type }, state === 'SKIPPED'); onChanged() }
    catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  return <section className="card"><h2>餐次记录状态</h2><label className="form-field">查看日期<input type="date" min="2000-01-01" max={businessToday()} required value={day} onChange={e => { if (e.target.value) setDay(e.target.value) }} /></label>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="meal-slots">{Object.entries(summary?.daily?.[0]?.slots || {}).map(([type, state]) => <div key={type}><strong>{mealTypes[type]}</strong><span>{state === 'RECORDED' ? '已有记录' : state === 'SKIPPED' ? '明确未吃' : '未记录，情况未知'}</span>{state !== 'RECORDED' && <button type="button" disabled={busy} onClick={() => toggle(type, state)}>{state === 'SKIPPED' ? '撤销未吃标记' : '确认这餐未吃'}</button>}</div>)}</div>
    <TrackingSummary summary={summary} />
  </section>
}
