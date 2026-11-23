import Button from '../../components/Button'
import { useState } from 'react'
import KnowledgeIndexingControls from './KnowledgeIndexingControls'
import KnowledgeVectorStatus from './KnowledgeVectorStatus'
import { formatTime, categoryLabel, Pagination } from './AdminUi'
import { embeddingUsageLabel } from '../../utils/knowledgeTokenUsage'
import KnowledgeContentHistory from './KnowledgeContentHistory'

export default function KnowledgeManagementPanel({ applyKnowledgeFilters, chunks, defaultKnowledgeFilters, deletingChunkId, editingChunkForm, editingChunkId, handleAddKnowledgeChunk, handleDeleteKnowledgeChunk, handleReindex, handleUpdateKnowledgeChunk, knowledgeFiltersDraft, knowledgeForm, knowledgePageData, knowledgeSize, loading, setEditingChunkForm, setEditingChunkId, setKnowledgeFilters, setKnowledgeFiltersDraft, setKnowledgeForm, setKnowledgePage, setKnowledgeSize, setViewingChunk, startEditChunk }) {
  const [inventory, setInventory] = useState(null)
  const [selectedIds, setSelectedIds] = useState([])
  const [historySelection, setHistorySelection] = useState(null)
  const vectorRows = new Map((inventory?.chunks || []).map(row => [row.id, row]))
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>RAG 知识库维护</h2>
              <p>新增、编辑、删除知识片段，并查看来源、分类、命中次数和最近引用时间。</p>
            </div>
          </div>

          <KnowledgeIndexingControls onComplete={handleReindex} onInventory={setInventory} selectedIds={selectedIds} setSelectedIds={setSelectedIds} refreshKey={`${knowledgePageData?.totalElements ?? 0}:${chunks.map(chunk => `${chunk.id}:${chunk.updatedAt}`).join('|')}`} />

          <div className="admin-note">系统会根据知识标题和内容自动标记分类，例如减脂、增肌、高油、糖饮甜品、蔬菜和主食。检索时同类知识会优先排序。</div>
          <p className="hint">系统 Markdown 的切片修改只保存在数据库；整库重建会按源文件同步。请先预览影响，内容历史可用于比较和恢复。</p>
          <Button type="button" variant="secondary" onClick={() => setHistorySelection({ chunkId: null })}>查看全部内容历史与已删除知识</Button>

          <form className="filter-grid compact" onSubmit={applyKnowledgeFilters}>
            <input value={knowledgeFiltersDraft.keyword} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, keyword: e.target.value })} placeholder="按标题或内容搜索" />
            <select value={knowledgeFiltersDraft.source} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, source: e.target.value })}>
              <option value="all">全部来源</option>
              <option value="admin">管理员手动添加</option>
              <option value="diet_guides.md">系统 Markdown</option>
            </select>
            <select value={knowledgeFiltersDraft.sort} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, sort: e.target.value })}>
              <option value="id">按 ID</option>
              <option value="hits">按命中次数</option>
              <option value="recentHit">按最近引用</option>
            </select>
            <Button variant="secondary" className="secondary-btn" type="submit">搜索</Button>
            <Button variant="secondary" className="secondary-btn" type="button" onClick={() => { setKnowledgeFiltersDraft(defaultKnowledgeFilters); setKnowledgeFilters(defaultKnowledgeFilters); setKnowledgePage(0) }}>重置</Button>
          </form>

          <details className="workspace-disclosure"><summary>新增知识片段</summary>
          <form className="knowledge-form" onSubmit={handleAddKnowledgeChunk}>
            <input value={knowledgeForm.title} onChange={e => setKnowledgeForm({ ...knowledgeForm, title: e.target.value })} placeholder="知识标题" />
            <textarea value={knowledgeForm.content} onChange={e => setKnowledgeForm({ ...knowledgeForm, content: e.target.value })} placeholder="知识内容" rows="4" />
            <Button variant="secondary" className="secondary-btn" type="submit" disabled={loading}>新增 RAG 知识片段</Button>
          </form>

          </details>
          <div className="knowledge-list">
            {chunks.length ? chunks.map(chunk => (
              <article className="knowledge-card" key={chunk.id}>
                {editingChunkId === chunk.id ? (
                  <form className="knowledge-edit-form" onSubmit={handleUpdateKnowledgeChunk}>
                    <input value={editingChunkForm.title} onChange={e => setEditingChunkForm({ ...editingChunkForm, title: e.target.value })} />
                    <textarea value={editingChunkForm.content} onChange={e => setEditingChunkForm({ ...editingChunkForm, content: e.target.value })} rows="5" />
                    <input maxLength={500} value={editingChunkForm.reason || ''} onChange={e => setEditingChunkForm({ ...editingChunkForm, reason: e.target.value })} placeholder="修改原因（可选）" />
                    <p className="hint">基于正文版本 {editingChunkForm.expectedRevision} 编辑。发生冲突时草稿保留，请比较最新正文后重新编辑。</p>
                    <div className="knowledge-card-actions">
                      <Button variant="secondary" className="secondary-btn" type="submit" disabled={loading}>保存</Button>
                      <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setEditingChunkId(null)}>取消</Button>
                      <Button variant="secondary" type="button" onClick={() => setHistorySelection({ chunkId: chunk.id })}>对比最新正文</Button>
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="knowledge-card-head">
                      <input className="knowledge-row-check" type="checkbox" aria-label={`勾选知识 ${chunk.id}`} checked={selectedIds.includes(chunk.id)} onChange={() => setSelectedIds(ids => ids.includes(chunk.id) ? ids.filter(id => id !== chunk.id) : ids.length < 500 ? [...ids, chunk.id] : ids)} />
                      <h3>{chunk.title || `知识片段 ${chunk.id}`}</h3>
                      <span>正文估算 {chunk.tokenCount ?? 0} tokens · {chunk.contentLength ?? 0} 字</span>
                    </div>
                    <div className="knowledge-meta"><KnowledgeVectorStatus row={vectorRows.get(chunk.id)} /><span>{vectorRows.get(chunk.id)?.model || '向量来源待查询'} · {vectorRows.get(chunk.id)?.dimension ?? chunk.vectorDimension ?? '—'} 维</span></div>
                    <div className="knowledge-meta"><span>{embeddingUsageLabel(chunk.embeddingInputTokens, chunk.embeddingTotalTokens)}</span></div>
                    <div className="knowledge-meta">
                      <span className={chunk.source === 'admin' ? 'source-tag admin' : 'source-tag'}>{chunk.source === 'admin' ? '管理员添加' : '系统 Markdown'}</span>
                      <span>版本 {chunk.sourceVersion || 'v1'}</span>
                      <span>正文版本 {chunk.contentRevision ?? '待刷新'}</span>
                      <span>{categoryLabel(chunk.category)}</span>
                      <span>命中 {chunk.hitCount || 0} 次</span>
                      <span>更新：{formatTime(chunk.updatedAt)}</span>
                      <span>最近引用：{formatTime(chunk.lastHitAt)}</span>
                    </div>
                    <p>{chunk.contentPreview || '暂无内容预览'}</p>
                    <div className="knowledge-card-actions">
                      <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setViewingChunk(chunk)}>查看详情</Button>
                      <Button variant="secondary" className="secondary-btn" type="button" onClick={() => startEditChunk(chunk)}>编辑</Button>
                      <Button variant="secondary" type="button" onClick={() => setHistorySelection({ chunkId: chunk.id })}>内容历史</Button>
                      <Button variant="danger" className="table-danger-btn" type="button" onClick={() => handleDeleteKnowledgeChunk(chunk)} disabled={deletingChunkId === chunk.id}>{deletingChunkId === chunk.id ? '删除中...' : '删除'}</Button>
                    </div>
                  </>
                )}
              </article>
            )) : <div className="empty-box">暂无 RAG 知识片段</div>}
          </div>
          <Pagination pageData={knowledgePageData} pageSize={knowledgeSize} setPage={setKnowledgePage} setPageSize={setKnowledgeSize} loading={loading} sizes={[8, 10, 20, 50]} />
          <KnowledgeContentHistory selection={historySelection} onChanged={handleReindex} />
        </section>
  )
}
