import { useEffect, useState } from 'react'
import { weeklyReportHistory, archivedWeeklyReport } from '../api/client'
import { emptyPage, normalizePage } from '../utils/pageData'
import JournalPagination from './JournalPagination'
import DetailDrawer from './DetailDrawer'
import TrackingSummary from './TrackingSummary'
import { formatTime } from '../utils/time'
import useDietDataRevision from '../hooks/useDietDataRevision'
const historyChanges = ['meals', 'diet-goal', 'reports']

export default function WeeklyReportHistoryPanel() {
  const revision = useDietDataRevision(historyChanges)
  const [page, setPage] = useState(0), [data, setData] = useState(() => emptyPage(20))
  const [selected, setSelected] = useState(null), [loading, setLoading] = useState(false), [error, setError] = useState('')
  useEffect(() => { const controller = new AbortController(); setLoading(true); setError('')
    weeklyReportHistory(page, controller.signal).then(result => { if (!controller.signal.aborted) setData(normalizePage(result)) })
      .catch(err => { if (err.name !== 'AbortError') setError(err.message) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, revision])
  async function open(id) {
    if (loading) return; setLoading(true); setError('')
    try { setSelected(await archivedWeeklyReport(id)) } catch (err) { setError(err.message) } finally { setLoading(false) }
  }
  return <section className="card"><h2>历史周报</h2><p className="hint">保留生成时的报告内容。过期标记表示相关餐食或饮食方向之后有变更，请以当前周报为准。</p>
    {error && <p className="error" role="alert">{error}</p>}
    {!data.totalElements && !loading && <p>暂无已保存的历史周报。</p>}
    {data.content.map(item => <article className="record" key={item.id}><p>{item.periodStart || '历史周期'} 至 {item.periodEnd || '-'} · {item.stale ? '已过期，原快照保留' : '有效快照'}</p><p className="hint">生成于 {formatTime(item.generatedAt)}</p><button type="button" disabled={loading} onClick={() => open(item.id)}>查看这份历史周报</button></article>)}
    <JournalPagination data={data} onPage={setPage} loading={loading} label="历史周报分页" />
    {selected && <DetailDrawer title="历史周报快照" onClose={() => setSelected(null)}><p className="hint">以下为生成时数据。{selected.snapshot.stale ? '此报告已过期，内容不会自动改写。' : '这是保存的原始报告。'}</p><h3>报告正文</h3><p>{selected.report?.reportText || '暂无报告正文'}</p><p>已记录 {selected.report?.totalMeals || 0} 餐 · 平均评分 {selected.report?.averageScore ?? '-'}</p><TrackingSummary summary={selected.report?.tracking} /><h3>当时的建议</h3><ul>{(selected.report?.nextWeekSuggestions || []).map((item, i) => <li key={i}>{item}</li>)}</ul></DetailDrawer>}
  </section>
}
