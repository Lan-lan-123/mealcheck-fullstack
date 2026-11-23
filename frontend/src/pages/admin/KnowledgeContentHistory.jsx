import { useEffect, useRef, useState } from 'react'
import Button from '../../components/Button'
import { adminKnowledgeContentHistory, adminKnowledgeContentVersion, adminRestoreKnowledgeContent } from '../../api/client'
import { formatTime } from './AdminUi'

const labels = { BASELINE: '初始快照', INSERT: '新增或恢复', UPDATE: '修改', DELETE: '删除' }

export default function KnowledgeContentHistory({ selection, onChanged }) {
  const [open, setOpen] = useState(false)
  const [deletedOnly, setDeletedOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [detail, setDetail] = useState(null)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [reload, setReload] = useState(0)
  const section = useRef(null)
  const chunkId = selection?.chunkId
  useEffect(() => {
    if (!selection) return
    setOpen(true); setPage(0); setDeletedOnly(false); setDetail(null); setVersionId(null); setMessage('')
    section.current?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }, [selection])
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    setError('')
    adminKnowledgeContentHistory({ ...(chunkId ? { chunkId } : {}), deletedOnly, page, size: 10 }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setData(result) })
      .catch(err => { if (!controller.signal.aborted) setError(err.message || '读取内容历史失败') })
    return () => controller.abort()
  }, [open, chunkId, deletedOnly, page, reload])
  const [versionId, setVersionId] = useState(null)
  useEffect(() => {
    if (!versionId) return
    const controller = new AbortController()
    setDetail(null); setError(''); setReason('')
    adminKnowledgeContentVersion(versionId, controller.signal).then(value => {
      if (!controller.signal.aborted) setDetail(value)
    }).catch(err => { if (!controller.signal.aborted) setError(err.message || '读取版本失败') })
    return () => controller.abort()
  }, [versionId, reload])
  async function restore() {
    setPending(true); setError(''); setMessage('')
    try {
      await adminRestoreKnowledgeContent(detail.id, detail.currentRevision, reason.trim())
      setMessage('历史正文已恢复，已按当前生效模型重新生成向量。')
      setVersionId(null); setDetail(null); setReload(value => value + 1); onChanged?.({ restored: true })
    } catch (err) { setError(err.message || '恢复失败，请刷新历史后重试') }
    finally { setPending(false) }
  }
  return <details ref={section} className="workspace-disclosure" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>内容历史与恢复{chunkId ? ` · 知识 #${chunkId}` : ''}</summary>
    <p className="hint">记录正文和来源变更；纯向量更新及命中统计不产生正文版本。启用前只保留迁移时的现有快照。恢复会重新生成向量，语义模式会产生接口用量。</p>
    <div className="knowledge-indexing-actions"><label><input type="checkbox" checked={deletedOnly} onChange={event => { setDeletedOnly(event.target.checked); setPage(0) }} />仅看已删除知识</label>
      <Button type="button" variant="secondary" disabled={pending} onClick={() => { setDetail(null); setVersionId(null); setReload(value => value + 1) }}>刷新内容历史</Button></div>
    {message && <p role="status">{message}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {data?.content?.map(row => <article className="knowledge-job-card" key={row.id}>
      <strong>#{row.chunkId} {row.title} · 正文版本 {row.revision}</strong>
      <p className="hint">{labels[row.operation] || row.operation} · {row.actor} · {formatTime(row.recordedAt)}{row.deleted && ' · 当前已删除'}</p>
      {row.reason && <p>{row.reason}</p>}
      <Button type="button" variant="secondary" disabled={pending} onClick={() => { setVersionId(row.id); setReload(value => value + 1) }}>查看并比较版本 #{row.id}</Button>
    </article>)}
    {data && !data.content.length && <p>暂无符合条件的内容历史。</p>}
    {data && data.totalPages > 1 && <div className="button-row"><Button type="button" variant="secondary" disabled={pending || page === 0} onClick={() => setPage(value => value - 1)}>上一页历史</Button><span>{page + 1} / {data.totalPages}</span><Button type="button" variant="secondary" disabled={pending || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页历史</Button></div>}
    {detail && <section aria-label="知识版本对比">
      <div className="knowledge-content-comparison"><div><h4>选中历史正文 · 版本 {detail.revision}</h4><strong>{detail.snapshot.title}</strong><pre>{detail.snapshot.content}</pre></div>
        <div><h4>当前正文</h4>{detail.current?.length ? <><strong>{detail.current[0].title}</strong><pre>{detail.current[0].content}</pre></> : <p>知识已删除，恢复后重新加入检索。</p>}</div></div>
      <label className="form-field">恢复原因<input maxLength={400} value={reason} disabled={pending} onChange={event => setReason(event.target.value)} placeholder="说明为什么恢复此版本" /></label>
      <Button type="button" variant="primary" disabled={pending || !reason.trim()} onClick={restore}>{pending ? '正在恢复正文与向量…' : '恢复此版本正文'}</Button>
    </section>}
  </details>
}
