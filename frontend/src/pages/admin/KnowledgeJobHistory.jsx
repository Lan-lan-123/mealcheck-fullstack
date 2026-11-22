import { useEffect, useState } from 'react'
import Button from '../../components/Button'
import { adminKnowledgeJobAction, adminKnowledgeJobDetails, adminKnowledgeJobHistory } from '../../api/client'
import { formatTime } from './AdminUi'
import { splitterLabel } from '../../utils/knowledgeSplitter'

const labels = { PENDING: '排队中', RUNNING: '生成中', RETRY: '等待重试', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已终止', INVALIDATED: '配置已失效' }
const active = job => ['PENDING', 'RUNNING', 'RETRY'].includes(job?.status)

export default function KnowledgeJobHistory({ job, mode, activeMode, splitterStrategy, onChange, onResubmit }) {
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [detail, setDetail] = useState(null)
  const [pending, setPending] = useState('')
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    adminKnowledgeJobHistory({ page, size: 8 }, controller.signal).then(result => {
      if (!controller.signal.aborted) { setData(result); setError('') }
    }).catch(err => { if (!controller.signal.aborted) setError(err.message || '读取任务历史失败') })
    return () => controller.abort()
  }, [open, page, reload, job?.id, job?.status, job?.updated_at])

  async function action(row, name) {
    setPending(`${name}:${row.id}`); setError('')
    try {
      if (name === 'resubmit' && onResubmit) { await onResubmit(row); return }
      const result = await adminKnowledgeJobAction(row.id, name, name === 'resubmit' ? (row.scope === 'SELECTED' ? activeMode : mode) : undefined,
        name === 'resubmit' && row.scope !== 'SELECTED' ? splitterStrategy : undefined)
      onChange(result); setDetail(null); setReload(value => value + 1)
    } catch (err) { setError(err.message || '任务操作失败') }
    finally { setPending('') }
  }
  async function showDetails(id) {
    setPending(`detail:${id}`); setError('')
    try { setDetail(await adminKnowledgeJobDetails(id)) }
    catch (err) { setError(err.message || '读取任务记录失败') }
    finally { setPending('') }
  }
  function actions(row) {
    return <div className="knowledge-card-actions">
      {active(row) && <Button type="button" variant="danger" disabled={!!pending} onClick={() => action(row, 'cancel')}>终止任务 #{row.id}</Button>}
      {row.status === 'FAILED' && row.last_error !== 'PREVIEW_STALE' && <Button type="button" variant="secondary" disabled={!!pending || active(job)} onClick={() => action(row, 'retry')}>重试任务 #{row.id}</Button>}
      {['FAILED', 'CANCELLED', 'INVALIDATED'].includes(row.status) && <Button type="button" variant="secondary" disabled={!!pending || active(job)} onClick={() => action(row, 'resubmit')}>重新提交 #{row.id}</Button>}
    </div>
  }
  return <div className="knowledge-job-history">
    {job && <>
      {job.failure_summary && <p className="hint">{job.failure_summary}{job.status === 'RETRY' && ` 下次处理：${formatTime(job.next_attempt_at)}`}</p>}
      {actions(job)}
      {['FAILED', 'CANCELLED', 'INVALIDATED'].includes(job.status) && <p className="hint">“重试”继续原配置的有效暂存结果；“重新提交”先按上方方式和切分重新预览，局部任务保留仍存在的选择并使用当前生效方式。使用待用模型请从向量模型配置处预览并应用。</p>}
    </>}
    {error && <p className="error" role="alert">{error}</p>}
    <details className="workspace-disclosure" onToggle={event => setOpen(event.currentTarget.open)}>
      <summary>任务历史与恢复</summary>
      {data && <>
        <div className="knowledge-job-list">{(data.content || []).map(row => <article className="knowledge-job-card" key={row.id}>
          <div className="knowledge-indexing-heading"><strong>#{row.id} · {row.scope === 'SELECTED' ? '选中知识' : '整库重建'}</strong><span className={`knowledge-vector-badge ${row.status === 'SUCCEEDED' ? 'ready' : active(row) ? 'working' : 'outdated'}`}>{labels[row.status] || row.status}</span></div>
          <p className="hint">{row.embedding_mode === 'SEMANTIC' ? '语义模型' : row.embedding_mode === 'LOCAL' ? '本地生成' : '自动维护'} · {row.dimension} 维 · {row.status === 'SUCCEEDED' ? row.totalChunks : row.staged ?? 0} / {row.totalChunks} 条</p>
          {row.profile_id && <p className="hint">固定配置版本 #{row.profile_id}</p>}
          {row.splitter_strategy && <p className="hint">切分方式：{splitterLabel(row.splitter_strategy)}</p>}
          <p className="hint">创建：{formatTime(row.created_at)} · 更新：{formatTime(row.updated_at)}</p>
          {row.failure_summary && <p>{row.failure_summary}</p>}
          {job?.id !== row.id && actions(row)}
          <Button type="button" variant="secondary" disabled={!!pending} onClick={() => showDetails(row.id)}>查看任务 #{row.id} 记录</Button>
        </article>)}</div>
        {!data.content?.length && <p className="hint">暂无任务记录</p>}
        <div className="knowledge-indexing-actions"><Button type="button" variant="secondary" onClick={() => setPage(value => value - 1)} disabled={page === 0 || !!pending}>上一页任务</Button><span>第 {page + 1} 页 · 共 {data.totalElements ?? 0} 个任务</span><Button type="button" variant="secondary" onClick={() => setPage(value => value + 1)} disabled={page + 1 >= (data.totalPages || 0) || !!pending}>下一页任务</Button></div>
      </>}
      {detail && <div className="knowledge-job-detail"><h4>任务 #{detail.id} · {labels[detail.status]}</h4><p className="hint">向量版本：{detail.signature}</p>
        {(detail.events || []).map((event, index) => <p key={index}>{formatTime(event.created_at)} · {event.summary}</p>)}
        {!detail.events?.length && <p className="hint">此历史任务尚无详细事件记录。</p>}
      </div>}
    </details>
  </div>
}
