// @ts-check
import { useRef, useState } from 'react'
import { Camera, ClipboardCheck, Plus, ArrowRight, Utensils } from 'lucide-react'
import Button from './Button'
import { saveActionPlan } from '../api/client'
import { mealTypes } from '../utils/time'

/** @param {{data: import('../api/contracts').TodayMeals | null; loading: boolean; error: string; onRefresh: () => void; onPhoto: () => void; onManual: () => void; onReview: (id: number) => void; onRecords: () => void; onPlans: () => void}} props */
export default function TodayMealsPanel({ data, loading, error, onRefresh, onPhoto, onManual, onReview, onRecords, onPlans }) {
  const [saving, setSaving] = useState(false), [actionError, setActionError] = useState('')
  const inFlight = useRef(false)
  /** @param {import('../api/contracts').ActionPlan} plan */
  async function complete(plan) {
    if (inFlight.current) return
    inFlight.current = true; setSaving(true); setActionError('')
    try { await saveActionPlan({ ...plan, status: 'DONE', expectedVersion: plan.version }, plan.id) }
    catch (err) { setActionError(err.status === 409 ? '计划已被修改，已重新加载，请核对后再打卡。' : err.message); onRefresh() }
    finally { inFlight.current = false; setSaving(false) }
  }
  return <section className="card today-panel" aria-labelledby="today-title" aria-busy={loading}>
    <div className="planning-heading"><div><p className="planning-eyebrow">每天一点，慢慢变好</p><h2 id="today-title"><Utensils size={20} />今日饮食总览</h2></div>
      {data && <span className="hint">{data.date} · {data.businessZone}</span>}</div>
    <div className="today-shortcuts"><Button variant="primary" type="button" onClick={onPhoto}><Camera size={16} />拍照记录</Button>
      <Button variant="secondary" type="button" onClick={onManual}><Plus size={16} />手动记录一餐</Button>
      <Button variant="plain" type="button" onClick={onRecords}>查看饮食记录<ArrowRight size={16} /></Button></div>
    {loading && !data && <p role="status">正在加载今日饮食…</p>}
    {error && <p className="error" role="alert">{error}<Button variant="secondary" type="button" onClick={onRefresh}>重试今日总览</Button></p>}
    {data && <><div className="today-slots">{Object.entries(data.slots).map(([type, status]) => <div className={`today-slot state-${status.toLowerCase()}`} key={type}>
      <strong>{mealTypes[/** @type {keyof typeof mealTypes} */ (type)]}</strong><span>{status === 'RECORDED' ? '已有记录' : status === 'SKIPPED' ? '已确认未吃' : '未记录，情况未知'}</span></div>)}</div>
      <p className="hint">今天已记录 {data.totalMeals} 条餐食。未指定餐次的记录计入总数；未记录不代表没有吃。</p>
      <div className="today-tasks"><div><h3>待核对食物 <span>{data.reviewFoodCount}</span></h3>
        {!data.reviewMealCount ? <p className="hint">今日记录暂无待核对食物。</p> : <ul>{data.reviews.map(meal => <li key={meal.recordId}><div><strong>{mealTypes[/** @type {keyof typeof mealTypes} */ (meal.mealType)] || '未指定餐次'}</strong><p>{meal.foods.join('、')}</p></div>
          <Button variant="secondary" type="button" onClick={() => onReview(meal.recordId)}>核对这餐</Button></li>)}</ul>}
        {data.reviewMealCount > data.reviews.length && <Button variant="plain" type="button" onClick={onRecords}>共 {data.reviewMealCount} 餐待核对，前往记录查看</Button>}</div>
        <div><h3><ClipboardCheck size={17} />今日待执行计划 <span>{data.pendingPlanCount}</span></h3>
          {actionError && <p className="error" role="alert">{actionError}</p>}
          {!data.pendingPlanCount ? <p className="hint">今天暂无待执行计划，可从下方搭配中选择一套。</p> : <ul>{data.pendingPlans.map(plan => <li key={plan.id}><div><strong>{plan.title}</strong><p>{plan.actionText}</p></div>
            <Button variant="secondary" type="button" disabled={saving || loading} onClick={() => complete(plan)}>完成打卡</Button></li>)}</ul>}
          <Button variant="plain" type="button" onClick={onPlans}>查看全部行动计划<ArrowRight size={16} /></Button></div></div></>}
  </section>
}
