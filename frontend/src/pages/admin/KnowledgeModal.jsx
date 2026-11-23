import Button from '../../components/Button'
import { formatTime, categoryLabel } from './AdminUi'
import { embeddingUsageLabel } from '../../utils/knowledgeTokenUsage'

export default function KnowledgeModal({ chunk, onClose }) {
  return (
    <div className="modal-backdrop">
      <section className="modal-card knowledge-modal">
        <header className="modal-head">
          <div>
            <h2>{chunk.title || `知识片段 ${chunk.id}`}</h2>
            <p>
              {chunk.source === 'admin' ? '管理员添加' : '系统 Markdown'} · {categoryLabel(chunk.category)} · 版本{' '}
              {chunk.sourceVersion || 'v1'} · 正文估算 {chunk.tokenCount ?? 0} tokens · 更新：{formatTime(chunk.updatedAt)} · 命中{' '}
              {chunk.hitCount || 0} 次 · 最近引用：{formatTime(chunk.lastHitAt)}
            </p>
            <p>{embeddingUsageLabel(chunk.embeddingInputTokens, chunk.embeddingTotalTokens)}
              {chunk.embeddingInputTokens != null && chunk.embeddingTotalTokens != null && ` · 接口总用量 ${chunk.embeddingTotalTokens} tokens`}</p>
            <p className="hint">估算长度用于正文切分；接口实际输入包含标题和正文，不能直接与正文估算值等同。历史数据、本地向量或接口未返回用量时显示未记录。</p>
          </div>
          <Button variant="secondary" className="secondary-btn" type="button" onClick={onClose}>
            关闭
          </Button>
        </header>
        <div className="knowledge-full-content">{chunk.content || chunk.contentPreview || '暂无内容'}</div>
      </section>
    </div>
  )
}
