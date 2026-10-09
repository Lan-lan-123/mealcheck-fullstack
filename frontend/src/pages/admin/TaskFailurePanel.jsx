import { useEffect, useRef, useState } from 'react'
import { adminTaskFailures, adminTaskFailureSummary, adminTaskFailureDetail, adminTaskFailureAction } from '../../api/client'
import usePageResource from '../../hooks/usePageResource'
import DetailDrawer from '../../components/DetailDrawer'
import { formatTime, MetricSmall, Pagination } from './AdminUi'

const types = { MEAL_ANALYSIS: '图片分析', WEEKLY_REPORT: '周报生成', BROKER: '未关联死信' }
const statuses = { OPEN: '待处理', AUTO_RETRY: '自动恢复中', RETRYING: '已发起重试', RETRY_FAILED: '重试失败', RECOVERED: '已恢复', CLOSED: '已关闭' }
const phases = { ANALYZE: '图片分析', GENERATE: '周报生成', SAVE: '结果保存', CLAIM: '任务领取', DISPATCH: '消息派发', WORKER: '执行中断', UNKNOWN: '历史记录' }
const defaults = { type: 'ALL', status: 'OPEN', code: '', userId: '', from: '', to: '' }
const emptyPage = { content: [], totalElements: 0, totalPages: 0 }

export default function TaskFailurePanel({ refreshRevision, onAction }) {
  const [filters, setFilters] = useState(defaults), [draft, setDraft] = useState(defaults)
  const [page, setPage] = useState(0), [size, setSize] = useState(20), [selected, setSelected] = useState(null)
  const [reason, setReason] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('')
  const operation = useRef(null), actionLock = useRef(false), mounted = useRef(true)
  const lastRefresh = useRef(refreshRevision)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const list = usePageResource({ key: JSON.stringify([filters, page, size]), initialData: emptyPage,
    load: () => adminTaskFailures({ ...filters, page, size }) })
  const summary = usePageResource({ key: 'summary', initialData: null, load: adminTaskFailureSummary })
  const detail = usePageResource({ key: String(selected), enabled: selected !== null, initialData: null,
    load: () => adminTaskFailureDetail(selected) })
  const refresh = () => { list.invalidate(); summary.invalidate(); detail.invalidate() }
  useEffect(() => {
    if (lastRefresh.current === refreshRevision) return
    lastRefresh.current = refreshRevision
    list.invalidate(); summary.invalidate(); detail.invalidate()
  }, [refreshRevision])
  useEffect(() => { setReason(''); setError(''); operation.current = null }, [selected])
  const failure = detail.data?.failure
  async function act(kind) {
    if (actionLock.current || !failure || !reason.trim()) return
    actionLock.current = true; setBusy(true); setError(''); setMessage('')
    const signature = JSON.stringify([failure.id, failure.version, kind, reason.trim()])
    if (operation.current?.signature !== signature) operation.current = { signature, payload: {
      operationId: crypto.randomUUID(), expectedVersion: failure.version, reason: reason.trim()
    } }
    try {
      const result = await adminTaskFailureAction(failure.id, kind, operation.current.payload)
      if (!mounted.current) return
      detail.setData(result); list.invalidate(); summary.invalidate(); onAction?.()
      setMessage(kind === 'retry' ? '任务已重新入队，可刷新详情查看结果。' : '失败事件已关闭，历史记录已保留。')
      setReason(''); operation.current = null
    } catch (err) {
      if (!mounted.current) return
      setError(err.message || '操作失败，请重试')
      if (err.status === 409 || err.status === 404) refresh()
    } finally {
      actionLock.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return <>
    {list.error && <div className="admin-message error" role="alert">{list.error}</div>}
    {summary.error && <div className="admin-message error" role="alert">{summary.error}</div>}
    {message && <div className="admin-message" role="status">{message}</div>}
    <section className="admin-section">
      <div className="admin-section-head"><div><h2>失败任务管理</h2><p>查看失败原因，处理图片分析、周报任务及死信，并跟踪恢复结果。</p></div>
        <button className="secondary-btn" type="button" onClick={refresh} disabled={list.loading || busy}>刷新失败任务</button></div>
      {summary.data && <div className="meal-stat-grid">
        <MetricSmall label="待处理事件" value={summary.data.open} /><MetricSmall label="已发起重试" value={summary.data.retrying} />
        <MetricSmall label="最老待处理时间" value={`${Math.floor(summary.data.oldestOpenSeconds / 60)} 分钟`} /></div>}
      <form className="filter-grid compact" onSubmit={event => { event.preventDefault(); setPage(0); setFilters({ ...draft }) }}>
        <select aria-label="失败业务" value={draft.type} onChange={event => setDraft({ ...draft, type: event.target.value })}>
          <option value="ALL">全部业务</option>{Object.entries(types).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
        <select aria-label="处理状态" value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}>
          <option value="ALL">全部状态</option>{Object.entries(statuses).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
        <input aria-label="错误代码" placeholder="错误代码" value={draft.code} onChange={event => setDraft({ ...draft, code: event.target.value })} />
        <input aria-label="失败用户 ID" placeholder="用户 ID" type="number" min="1" value={draft.userId} onChange={event => setDraft({ ...draft, userId: event.target.value })} />
        <label>起始时间（北京时间）<input type="datetime-local" value={draft.from} onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
        <label>结束时间（北京时间）<input type="datetime-local" value={draft.to} onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
        <button className="secondary-btn" type="submit">筛选</button>
        <button className="secondary-btn" type="button" onClick={() => { setDraft(defaults); setFilters(defaults); setPage(0) }}>重置</button>
      </form>
      <div className="admin-table-wrap"><table className="admin-table"><thead><tr>
        <th>事件 / 业务</th><th>用户 / 任务</th><th>原因</th><th>处理状态</th><th>执行次数</th><th>失败时间</th><th>详情</th>
      </tr></thead><tbody>{(list.data?.content || []).map(item => <tr key={item.id}>
        <td>#{item.id}<br />{types[item.taskType] || item.taskType}{item.deadLettered && <span className="muted-text"> · 已归档死信</span>}</td>
        <td>{item.userId ? `用户 #${item.userId}` : '未关联用户'}<br /><code>{item.taskKey || '未关联任务'}</code></td>
        <td>{item.errorSummary}<br /><span className="muted-text">{phases[item.phase] || item.phase} · {item.errorCode}</span></td>
        <td>{statuses[item.status] || item.status}</td><td>{item.attempts}</td><td>{formatTime(item.failedAt)}</td>
        <td><button className="secondary-btn" type="button" onClick={() => { setSelected(item.id); setMessage('') }}>查看并处理</button></td>
      </tr>)}{!list.data?.content?.length && <tr><td colSpan="7" className="empty-cell">{list.loading ? '加载中…' : '当前筛选下没有失败事件'}</td></tr>}</tbody></table></div>
      <Pagination pageData={{ ...list.data, page }} pageSize={size} setPage={setPage} setPageSize={setSize} loading={list.loading} />
    </section>
    {selected !== null && <DetailDrawer title="失败任务详情" onClose={() => { if (!busy) setSelected(null) }}>
      {detail.loading && <p role="status">正在加载详情…</p>}
      {detail.error && <div className="admin-message error" role="alert">{detail.error}<button type="button" className="secondary-btn" onClick={detail.invalidate}>重新加载详情</button></div>}
      {failure && <>
        <h3>{types[failure.taskType]} · #{failure.id}</h3><p>{failure.errorSummary}</p>
        <dl className="failure-detail-grid"><dt>错误代码</dt><dd>{failure.errorCode}</dd><dt>失败阶段</dt><dd>{phases[failure.phase]}</dd>
          <dt>任务状态</dt><dd>{failure.taskStatus || '任务不存在'}</dd><dt>处理状态</dt><dd>{statuses[failure.status]}</dd>
          <dt>派发事件</dt><dd><code>{failure.eventId || '无'}</code></dd><dt>人工重试次数</dt><dd>{failure.manualRetryCount}</dd>
          <dt>处理说明</dt><dd>{failure.handlingNote || '尚未填写'}</dd></dl>
        {!failure.retryable && <p className="hint">无法重试：{failure.retryBlockedReason}</p>}
        {failure.retryable && <p className="hint">重新执行可能再次调用模型并产生费用。结果会经过幂等检查。</p>}
        {['OPEN', 'RETRY_FAILED'].includes(failure.status) && <form onSubmit={event => event.preventDefault()}>
          <label className="form-field">处理原因<textarea maxLength={500} required value={reason} onChange={event => setReason(event.target.value)} disabled={busy} /></label>
          <div className="failure-actions"><button className="secondary-btn" type="button" onClick={() => act('retry')} disabled={busy || detail.loading || !failure.retryable || !reason.trim()}>重新执行任务</button>
            <button className="secondary-btn" type="button" onClick={() => act('close')} disabled={busy || detail.loading || !reason.trim()}>关闭失败事件</button></div>
        </form>}
        {error && <div className="admin-message error" role="alert">{error}</div>}
        {message && <div className="admin-message" role="status">{message}</div>}
        <button className="secondary-btn" type="button" onClick={detail.invalidate} disabled={busy || detail.loading}>刷新任务结果</button>
        <h3>失败与恢复记录</h3><ol className="failure-timeline">{detail.data.history.map(item => <li key={item.id}>
          <strong>{formatTime(item.failedAt)} · {statuses[item.status]}</strong><p>{item.errorSummary} · {phases[item.phase]} · 第 {item.attempts} 次执行</p>
          {item.handlingNote && <p>处理说明：{item.handlingNote}</p>}</li>)}</ol>
        <h3>管理操作记录</h3>{detail.data.operations.length ? <ol className="failure-timeline">{detail.data.operations.map(op => <li key={op.operationId}>
          <strong>{formatTime(op.createdAt)} · {op.action === 'RETRY' ? '发起重试' : '关闭事件'} · 管理员 #{op.adminId}</strong><p>{op.reason}</p></li>)}</ol> : <p className="hint">暂无管理操作</p>}
        {failure.taskType === 'WEEKLY_REPORT' && <a href={`/admin/agent-coordination?jobId=${encodeURIComponent(failure.taskKey)}`}>查看周报 Agent 协调记录（任务 #{failure.taskKey}）</a>}
      </>}
    </DetailDrawer>}
  </>
}
