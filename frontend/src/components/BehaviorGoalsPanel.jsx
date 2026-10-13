import JournalPagination from './JournalPagination'
import { emptyPage, normalizePage } from '../utils/pageData'
import useDietDataRevision from '../hooks/useDietDataRevision'
const changeKinds = ['behavior-goals']
import { useEffect, useState } from 'react'
import { behaviorGoals, mealCoverage, saveBehaviorGoal } from '../api/client'
import { businessToday } from '../utils/time'
import TrackingSummary from './TrackingSummary'

export default function BehaviorGoalsPanel() {
  const [goals, setGoals] = useState([]), [summary, setSummary] = useState(null), [editing, setEditing] = useState(null)
  const revision = useDietDataRevision(changeKinds)
  const [page, setPage] = useState(0), [pageData, setPageData] = useState(() => emptyPage(20)), [loading, setLoading] = useState(false)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const initial = () => ({ kind: 'RECORD_DAYS_WEEKLY', target: 5, startDate: businessToday(), endDate: new Date(new Date(`${businessToday()}T12:00:00Z`).getTime() + 27 * 86400000).toISOString().slice(0, 10), active: true, expectedVersion: 0 })
  const [draft, setDraft] = useState(initial)
  useEffect(() => { let live = true; setLoading(true); Promise.all([behaviorGoals({ page }), mealCoverage(businessToday(), businessToday())]).then(([g, s]) => { if (live) { const data = normalizePage(g, 20); setPageData(data); setGoals(data.content); setSummary(s) } }).catch(e => { if (live) setError(e.message) }).finally(() => { if (live) setLoading(false) }); return () => { live = false } }, [page, revision])
  async function save(e) {
    e.preventDefault(); if (busy) return; setBusy(true); setError('')
    try { await saveBehaviorGoal(draft, editing); setDraft(initial()); setEditing(null); setPage(0) }
    catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  return <section className="card"><h2>可追踪的行为目标</h2><p className="hint">每周按周一至周日统计有记录的天数；每日目标按当日不同餐次统计，同一餐次多条记录只算一次；未指定餐次不计入每日目标。同一类型的有效周期不能重叠。营养估算不参与达标判断。</p>
    <TrackingSummary summary={summary} />
    <ul className="behavior-goal-list">{goals.map(g => <li key={g.id}><span>{g.kind === 'MEALS_DAILY' ? '每天记录' : '每周记录'} {g.target} {g.kind === 'MEALS_DAILY' ? '个餐次' : '天'} · {g.startDate} 至 {g.endDate} · {g.active ? '启用' : '停用'}</span><button type="button" disabled={busy} onClick={() => { setEditing(g.id); setDraft({ ...g, expectedVersion: g.version }) }}>调整目标</button></li>)}</ul>
    <JournalPagination data={pageData} onPage={setPage} loading={loading || busy} label="行为目标分页" />
    <h3>{editing ? '调整行为目标' : '新增行为目标'}</h3><form className="journal-form" onSubmit={save}>
      <label className="form-field">目标类型<select value={draft.kind} disabled={busy} onChange={e => setDraft({ ...draft, kind: e.target.value, target: Math.min(draft.target, 4) })}><option value="RECORD_DAYS_WEEKLY">每周记录几天</option><option value="MEALS_DAILY">每天记录几餐</option></select></label>
      <label className="form-field">目标数量<input type="number" min="1" max={draft.kind === 'MEALS_DAILY' ? 4 : 7} required value={draft.target} disabled={busy} onChange={e => setDraft({ ...draft, target: Number(e.target.value) })} /></label>
      <label className="form-field">开始日期<input type="date" required min="2000-01-01" value={draft.startDate} disabled={busy} onChange={e => setDraft({ ...draft, startDate: e.target.value })} /></label>
      <label className="form-field">结束日期<input type="date" required min={draft.startDate} value={draft.endDate} disabled={busy} onChange={e => setDraft({ ...draft, endDate: e.target.value })} /></label>
      <label><input type="checkbox" checked={draft.active} disabled={busy} onChange={e => setDraft({ ...draft, active: e.target.checked })} />启用目标</label>
      {error && <p className="error" role="alert">{error}</p>}<button className="primary" disabled={busy}>{busy ? '保存中…' : '保存行为目标'}</button>{editing && <button type="button" disabled={busy} onClick={() => { setEditing(null); setDraft(initial()) }}>取消调整</button>}
    </form>
  </section>
}
