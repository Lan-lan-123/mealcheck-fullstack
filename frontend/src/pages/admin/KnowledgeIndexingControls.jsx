import { useEffect, useRef, useState } from 'react'
import Button from '../../components/Button'
import { adminBuildKnowledge, adminPreviewKnowledge, adminKnowledgeIndexing, adminKnowledgeJobDetails } from '../../api/client'
import KnowledgeRebuildPreview from './KnowledgeRebuildPreview'
import KnowledgeVectorStatus, { vectorStatus } from './KnowledgeVectorStatus'
import { formatTime } from './AdminUi'
import KnowledgeJobHistory from './KnowledgeJobHistory'
import KnowledgeEmbeddingSettings from './KnowledgeEmbeddingSettings'
import { knowledgeSplitters, splitterLabel } from '../../utils/knowledgeSplitter'
import { embeddingUsageLabel } from '../../utils/knowledgeTokenUsage'

const running = job => ['PENDING', 'RUNNING', 'RETRY'].includes(job?.status)
const modeLabel = (mode, model) => mode === 'SEMANTIC' ? `语义向量模型（${model}）` : '本地哈希向量'

export default function KnowledgeIndexingControls({ onComplete, onInventory, selectedIds = [], setSelectedIds = () => {}, refreshKey = '' }) {
  const [state, setState] = useState(null)
  const [mode, setMode] = useState('LOCAL')
  const [splitterStrategy, setSplitterStrategy] = useState('recursive-markdown')
  const [pending, setPending] = useState(false)
  const [plan, setPlan] = useState(null)
  const [error, setError] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [reload, setReload] = useState(0)
  const [filter, setFilter] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [view, setView] = useState('map')
  const observedJob = useRef(null)
  const initialized = useRef(false)
  const generation = useRef(0)
  const complete = useRef(onComplete)
  complete.current = onComplete
  const inventoryCallback = useRef(onInventory)
  inventoryCallback.current = onInventory
  const selectionCallback = useRef(setSelectedIds)
  selectionCallback.current = setSelectedIds

  useEffect(() => {
    const controller = new AbortController()
    let timer
    const current = ++generation.current
    async function load() {
      try {
        const result = await adminKnowledgeIndexing(controller.signal)
        if (controller.signal.aborted || current !== generation.current) return
        if (!result?.activeMode) throw new Error('暂时无法读取知识库向量配置')
        setState(result)
        inventoryCallback.current?.(result.inventory)
        if (result.inventory && !result.inventory.truncated) {
          const existing = new Set(result.inventory.chunks.map(chunk => chunk.id))
          selectionCallback.current(ids => ids.filter(id => existing.has(id)))
        }
        setError('')
        if (!initialized.current) {
          setMode(running(result.job) && result.job.embedding_mode ? result.job.embedding_mode : result.activeMode)
          setSplitterStrategy((running(result.job) && result.job.splitter_strategy) || result.activeSplitterStrategy || 'recursive-markdown')
          initialized.current = true
        }
        if (running(result.job)) {
          observedJob.current = result.job.id
          timer = setTimeout(load, 2000)
        } else if (observedJob.current === result.job?.id) {
          observedJob.current = null
          if (result.job.status === 'SUCCEEDED') {
            selectionCallback.current([])
            complete.current?.(result.job)
          }
        }
      } catch (err) {
        if (!controller.signal.aborted && current === generation.current) {
          setError(err.message || '读取重建进度失败，请刷新状态')
        }
      }
    }
    load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [reload, refreshKey])

  async function build(selected = false, overrides = null) {
    setPending(true)
    setSubmitError('')
    try { setPlan(await adminPreviewKnowledge(overrides?.mode || mode, overrides?.ids || (selected ? selectedIds : undefined), selected ? undefined : splitterStrategy)) }
    catch (err) { setSubmitError(err.message || '读取重建预览失败') }
    finally { setPending(false) }
  }

  async function submitPlan() {
    ++generation.current
    setPending(true)
    setSubmitError('')
    try {
      const job = await adminBuildKnowledge(plan.mode, plan.chunkIds || undefined, plan.splitterStrategy || undefined, plan.previewToken)
      setPlan(null)
      observedJob.current = job.id
      setState(current => ({ ...current, job }))
    } catch (err) {
      setSubmitError(err.message || '提交知识库重建失败')
    } finally {
      setPending(false)
      setReload(value => value + 1)
    }
  }

  const job = state?.job
  const busy = pending || running(job) || !!plan
  const model = state?.semanticModel || 'qwen3.7-text-embedding-flash'
  const inventory = state?.inventory
  const counts = inventory?.counts
  const visible = (inventory?.chunks || []).filter(chunk => {
    const matches = filter === 'all' || (filter === 'ready' ? chunk.compatible : filter === 'outdated' ? !chunk.compatible : filter === 'working' ? !!chunk.job_id : chunk.mode === filter)
    return matches && `${chunk.title || ''} ${chunk.id}`.toLowerCase().includes(keyword.trim().toLowerCase())
  })
  function toggle(id) {
    setSelectedIds(ids => ids.includes(id) ? ids.filter(value => value !== id) : ids.length < 500 ? [...ids, id] : ids)
  }
  const percentage = job?.totalChunks ? Math.round((job.status === 'SUCCEEDED' ? job.totalChunks : job.staged || 0) / job.totalChunks * 100) : 0
  return (
    <div className="knowledge-indexing">
      {plan && <KnowledgeRebuildPreview plan={plan} pending={pending} error={submitError} onConfirm={submitPlan} onRefresh={() => build(!!plan.chunkIds)} onClose={() => { setPlan(null); setSubmitError('') }} />}
      <div className="knowledge-indexing-heading"><h3>向量工作台</h3><span className="hint">按实际入库记录统计</span></div>
      <p>当前生效：{state ? modeLabel(state.activeMode, model) : '读取中…'}{state && ` · ${state.dimension} 维`}</p>
      <KnowledgeEmbeddingSettings rebuilding={busy} refreshKey={job?.status === 'SUCCEEDED' ? job.id : ''} onApplied={result => {
        if (result.rebuilding) {
          setMode('SEMANTIC')
          observedJob.current = result.job.id
          setState(current => ({ ...current, job: result.job }))
        }
        setReload(value => value + 1)
      }} />
      {counts && <>
        <div className="knowledge-vector-stats">
          {[['知识总数', counts.total, 'all'], ['匹配当前配置', counts.compatible, 'ready'], ['语义模型生成', counts.semantic, 'SEMANTIC'], ['本地生成', counts.local, 'LOCAL'], ['需要更新', counts.outdated, 'outdated']].map(([label, value, key]) =>
            <button type="button" key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}><strong>{value}</strong><span>{label}</span></button>)}
        </div>
        <div className="knowledge-vector-distribution" aria-label={`语义模型 ${counts.semantic} 条，本地 ${counts.local} 条，来源未识别 ${counts.unknown} 条`}>
          <span className="semantic" style={{ flex: counts.semantic }} /><span className="local" style={{ flex: counts.local }} /><span className="unknown" style={{ flex: counts.unknown }} />
        </div>
        {!!counts.outdated && <p className="hint">有 {counts.outdated} 条与当前配置不匹配，可勾选后更新；全部匹配后恢复向量检索，期间仍可使用关键词检索。</p>}
      </>}
      <section className="knowledge-splitter-settings" aria-label="知识切分方式">
        <div className="knowledge-indexing-heading"><h4>知识切分方式</h4><span className="hint">当前知识源：{state ? splitterLabel(state.activeSplitterStrategy) : '读取中…'}</span></div>
        <div className="knowledge-splitter-options" role="group" aria-label="选择切分方式">
          {knowledgeSplitters.map(option => <Button key={option.value} type="button" variant={splitterStrategy === option.value ? 'primary' : 'secondary'}
            aria-pressed={splitterStrategy === option.value} disabled={!state || !!error || busy} onClick={() => setSplitterStrategy(option.value)}>{option.label}</Button>)}
        </div>
        <p>{knowledgeSplitters.find(option => option.value === splitterStrategy)?.description}</p>
        <p className="hint">共用长度估算：目标 {state?.chunkMinTokens ?? 300}～{state?.chunkMaxTokens ?? 500}，重叠目标 {state?.chunkOverlapTokens ?? 50} 个估算 tokens。实际长度和重叠随方案而异。</p>
        <p className="hint">选择后点击“重建系统 RAG 知识库”应用。知识源同步成功时发布新切分，向量生成方式在任务完成后切换；管理员新增知识保留。</p>
        {state && splitterStrategy !== state.activeSplitterStrategy && <p className="hint">已选 {splitterLabel(splitterStrategy)}，等待提交整库重建。</p>}
      </section>
      <div className="knowledge-indexing-actions">
        <label htmlFor="knowledge-embedding-mode">向量生成方式</label>
        <select id="knowledge-embedding-mode" value={mode} onChange={event => setMode(event.target.value)} disabled={!state || busy}>
          <option value="LOCAL">本地哈希向量（无需模型接口）</option>
          <option value="SEMANTIC" disabled={!state?.semanticAvailable}>语义向量模型（{model}）</option>
        </select>
        <Button variant="primary" className="primary-btn" type="button" onClick={() => build()} disabled={!state || !!error || busy || (mode === 'SEMANTIC' && !state.semanticAvailable)}>
          {busy ? '知识库重建中…' : '重建系统 RAG 知识库'}
        </Button>
        <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setReload(value => value + 1)} disabled={pending}>刷新状态</Button>
      </div>
      <div className="knowledge-selection-toolbar">
        <strong>已选 {selectedIds.length} 条</strong>
        <Button variant="primary" type="button" onClick={() => build(true)} disabled={!state || !!error || busy || !selectedIds.length || selectedIds.length > 500 || mode !== state.activeMode || (mode === 'SEMANTIC' && !state.semanticAvailable)}>仅生成选中知识</Button>
        <Button variant="secondary" type="button" onClick={() => setSelectedIds([])} disabled={!selectedIds.length || busy}>清空选择</Button>
      </div>
      <p className="hint">选中项会按当前生效模型重新生成，每批最多 500 条。{state && mode !== state.activeMode ? '当前选择了另一种方式，请使用整库重建完成切换。' : '如需从本地切换到语义模型，请先使用整库重建。'}</p>
      {state && !state.semanticAvailable && <p className="hint">语义向量模型尚未配置。请配置向量接口与 API Key；已有阿里云百炼聊天配置时可直接复用。</p>}
      {job && <p role="status" aria-live="polite">
        {job.scope === 'SELECTED' && '选中知识 · '}{running(job) ? `${job.source_synced ? '正在生成向量' : '正在同步与切分知识源'}：${job.staged ?? 0} / ${job.totalChunks ?? 0} 条${job.status === 'RETRY' ? '，等待继续处理' : ''}`
          : job.status === 'SUCCEEDED' ? `重建完成，共 ${job.totalChunks ?? 0} 条知识片段`
          : job.status === 'CANCELLED' ? `任务已终止，当前向量方式继续生效。${job.scope !== 'SELECTED' && job.source_synced ? '已同步的正文与切分保留，可从内容历史比较和恢复。' : ''}`
          : job.status === 'INVALIDATED' ? '任务配置已失效，请按当前配置重新提交。'
          : '重建失败，原向量方式保持生效。请检查接口配置后重试或重新提交。'}
      </p>}
      {job?.splitter_strategy && <p className="hint">本次任务切分：{splitterLabel(job.splitter_strategy)}</p>}
      {job && <div className="knowledge-vector-progress" role="progressbar" aria-label="向量生成进度" aria-valuenow={percentage} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${percentage}%` }} /></div>}
      {error && <p className="error" role="alert">{error}</p>}
      {submitError && <p className="error" role="alert">{submitError}</p>}
      <KnowledgeJobHistory job={job} mode={mode} splitterStrategy={splitterStrategy} activeMode={state?.activeMode} onChange={result => {
        observedJob.current = running(result) ? result.id : null
        setState(current => ({ ...current, job: result }))
        setReload(value => value + 1)
      }} onResubmit={async row => {
        if (row.scope === 'SELECTED') {
          const previous = await adminKnowledgeJobDetails(row.id)
          await build(true, { mode: state.activeMode, ids: previous.chunkIds })
        } else await build()
      }} />
      {inventory && <div className="knowledge-vector-inventory">
        <div className="knowledge-indexing-heading"><h4>逐条向量状态</h4><span className="hint">绿色：匹配配置 · 蓝色：处理中 · 橙色：需更新</span></div>
        <div className="knowledge-indexing-actions">
          <input aria-label="搜索向量明细" placeholder="按标题或 ID 搜索" value={keyword} onChange={event => setKeyword(event.target.value)} />
          <select aria-label="筛选向量状态" value={filter} onChange={event => setFilter(event.target.value)}>
            <option value="all">全部向量</option><option value="ready">匹配当前配置</option><option value="SEMANTIC">语义模型生成</option><option value="LOCAL">本地生成</option><option value="UNKNOWN">来源未识别</option><option value="outdated">需要更新</option><option value="working">正在处理</option>
          </select>
          <Button variant="secondary" type="button" onClick={() => setView(view === 'map' ? 'table' : 'map')}>{view === 'map' ? '查看明细列表' : '查看状态分布'}</Button>
          <Button variant="secondary" type="button" disabled={busy || !visible.length} onClick={() => setSelectedIds(ids => [...new Set([...ids, ...visible.map(row => row.id)])].slice(0, 500))}>勾选筛选结果</Button>
        </div>
        <p className="hint">显示 {visible.length} / {counts?.total ?? 0} 条，点击卡片勾选。选择会跨筛选保留。{inventory.truncated && '当前展示前 2000 条，可在下方分页知识列表中继续勾选。'}</p>
        <p className="hint">正文估算长度用于切分；实际输入用量来自生成当前向量的接口响应，包含标题和正文。历史数据、本地向量或接口未返回用量时显示未记录；重新生成对应向量后更新。</p>
        {view === 'map' ? <div className="knowledge-vector-map" aria-label="知识向量状态分布">
          {visible.map(row => <button type="button" className={`knowledge-vector-tile ${vectorStatus(row).tone}`} key={row.id} aria-pressed={selectedIds.includes(row.id)} aria-label={`选择知识 ${row.id}：${row.title}`} disabled={busy} onClick={() => toggle(row.id)} title={`${row.title}\n${row.model || '来源未识别'} · ${row.dimension} 维\n${vectorStatus(row).label}`}>
            <span>#{row.id} {selectedIds.includes(row.id) && '✓'}</span><strong>{row.title || '无标题'}</strong><small>{row.mode === 'SEMANTIC' ? '语义模型' : row.mode === 'LOCAL' ? '本地哈希' : '来源未识别'}</small>
            <small>{embeddingUsageLabel(row.embedding_input_tokens, row.embedding_total_tokens)}</small>
          </button>)}
        </div> : <div className="knowledge-vector-table-wrap"><table className="knowledge-vector-table"><thead><tr><th>选择</th><th>知识 / 实际模型</th><th>Token 用量</th><th>状态 / 最近生成</th></tr></thead><tbody>
          {visible.map(row => <tr key={row.id}><td><input type="checkbox" aria-label={`选择知识 ${row.id}：${row.title}`} checked={selectedIds.includes(row.id)} disabled={busy} onChange={() => toggle(row.id)} /></td><td><strong>#{row.id} {row.title}</strong><small>{row.model || '来源未识别'} · {row.dimension} 维</small></td><td><span>正文估算 {row.estimated_tokens ?? '—'} tokens</span><small>{embeddingUsageLabel(row.embedding_input_tokens, row.embedding_total_tokens)}</small></td><td><KnowledgeVectorStatus row={row} /><small>{row.generated_at ? formatTime(row.generated_at) : '历史生成时间未记录'}</small></td></tr>)}
        </tbody></table></div>}
        {!visible.length && <p className="empty-box">没有符合条件的知识</p>}
      </div>}
    </div>
  )
}
