import JournalPagination from './JournalPagination'
import { emptyPage, normalizePage } from '../utils/pageData'
import useDietDataRevision from '../hooks/useDietDataRevision'
const changeKinds = ['plans']
import { useEffect, useState } from 'react'
import { actionPlans, saveActionPlan } from '../api/client'
import PlanSaveButton from './PlanSaveButton'
import { businessToday } from '../utils/time'

const statusLabels = { OPEN: '待执行', DONE: '已完成', SKIPPED: '已跳过' }
export default function ActionPlansPanel() {
  const [plans, setPlans] = useState([]), [editing, setEditing] = useState(null)
  const revision = useDietDataRevision(changeKinds)
  const [page, setPage] = useState(0), [pageData, setPageData] = useState(() => emptyPage(20)), [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [dates, setDates] = useState({ from: '', to: '' }), [filter, setFilter] = useState({})
  useEffect(() => { let live = true; setError(''); setLoading(true); actionPlans({ ...filter, page }).then(p => { if (live) { const data = normalizePage(p, 20); setPageData(data); setPlans(data.content); } }).catch(e => { if (live) setError(e.message) }).finally(() => { if (live) setLoading(false) }); return () => { live = false } }, [revision, filter, page])
  async function update(plan, status) {
    if (busy) return; setBusy(true); setError('')
    try { await saveActionPlan({ ...plan, status, expectedVersion: plan.version }, plan.id); setEditing(null) }
    catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  return <section className="card"><h2>行动计划</h2><p className="hint">默认展示过去 30 天至未来 90 天的计划，可按日期回顾其他周期，每次最多查询 366 天。完成与跳过会进入对应计划日期的周报汇总。</p>
    <form className="record-filter-form" onSubmit={e => { e.preventDefault(); setPage(0); setFilter({ ...dates }) }}>
      <label className="form-field">计划开始日期<input type="date" min="2000-01-01" max={dates.to || undefined} required value={dates.from} onChange={e => setDates({ ...dates, from: e.target.value })} /></label>
      <label className="form-field">计划结束日期<input type="date" min={dates.from || '2000-01-01'} required value={dates.to} onChange={e => setDates({ ...dates, to: e.target.value })} /></label>
      <button type="submit" disabled={busy}>筛选计划</button><button type="button" disabled={busy} onClick={() => { setDates({ from: '', to: '' }); setPage(0); setFilter({}) }}>重置计划筛选</button>
    </form>
    <PlanSaveButton key="manual-plan" text="请填写具体、可执行的饮食行动。" />
    {error && <p className="error" role="alert">{error}</p>}
    <JournalPagination data={pageData} onPage={setPage} loading={loading || busy} label="行动计划分页" />
    {!loading && !plans.length && <p>暂时没有计划，可以从餐食建议、助手回答或周报中加入。</p>}
    <div className="plan-list">{plans.map(plan => <article className="record" key={plan.id}><h3>{plan.title}</h3><p>{plan.actionText}</p><p className="hint">{plan.plannedDate} · {statusLabels[plan.status]} · {({ MANUAL: '手动创建', MEAL: '餐食建议', ASSISTANT: '助手回答', REPORT: '周报建议' })[plan.sourceType]}</p>{plan.sourcePeriodStart && <p className="hint">来源周报：{plan.sourcePeriodStart} 至 {plan.sourcePeriodEnd}</p>}{plan.note && <p>执行备注：{plan.note}</p>}
      <div className="journal-actions"><button type="button" disabled={busy || plan.status === 'DONE'} onClick={() => update(plan, 'DONE')}>完成打卡</button><button type="button" disabled={busy || plan.status === 'SKIPPED'} onClick={() => update(plan, 'SKIPPED')}>跳过</button><button type="button" disabled={busy} onClick={() => setEditing({ ...plan })}>调整计划</button>{plan.status !== 'OPEN' && <button type="button" disabled={busy} onClick={() => update(plan, 'OPEN')}>重新打开</button>}</div>
      {editing?.id === plan.id && <form className="journal-form" onSubmit={e => { e.preventDefault(); update(editing, editing.status) }}>
        <label className="form-field">标题<input required maxLength={120} disabled={busy} value={editing.title} onChange={e => setEditing({ ...editing, title: e.target.value })} /></label>
        <label className="form-field">行动内容<textarea required maxLength={2000} disabled={busy} value={editing.actionText} onChange={e => setEditing({ ...editing, actionText: e.target.value })} /></label>
        <label className="form-field">调整日期<input required type="date" min="2000-01-01" disabled={busy} value={editing.plannedDate || businessToday()} onChange={e => setEditing({ ...editing, plannedDate: e.target.value })} /></label>
        <label className="form-field">执行备注<textarea maxLength={500} disabled={busy} value={editing.note || ''} onChange={e => setEditing({ ...editing, note: e.target.value })} /></label>
        <button disabled={busy}>保存调整</button><button type="button" disabled={busy} onClick={() => setEditing(null)}>取消调整</button>
      </form>}
    </article>)}</div>
  </section>
}
