import Button from '../../components/Button'
import { splitterLabel } from '../../utils/knowledgeSplitter'

export default function KnowledgeRebuildPreview({ plan, pending, error, onConfirm, onRefresh, onClose }) {
  return <div className="modal-backdrop"><section className="modal-card knowledge-modal" role="dialog" aria-modal="true" aria-labelledby="knowledge-preview-title">
    <header className="modal-head"><h2 id="knowledge-preview-title">重建影响预览</h2><Button type="button" variant="secondary" disabled={pending} onClick={onClose}>关闭预览</Button></header>
    <p>{plan.chunkIds ? `仅更新选中的 ${plan.targetChunks} 条向量，正文保持不变。` : `知识片段：${plan.currentChunks} → ${plan.targetChunks} 条；新增 ${plan.inserted}、更新正文 ${plan.updated}、删除 ${plan.deleted} 条。`}</p>
    <p>目标：{plan.targetModel} · {plan.targetDimension} 维{plan.splitterStrategy && ` · ${splitterLabel(plan.splitterStrategy)}`}</p>
    <p>预计向量请求上限：知识源同步 {plan.sourceEmbeddingRequests} 次，目标向量 {plan.targetEmbeddingRequests} 次（不含重试）。</p>
    <p className="hint">保守输入估算 {Number(plan.estimatedInputUpperBound || 0).toLocaleString()} 单位，按 UTF-8 字节数加请求余量估算，并非计费 token 或费用；有效暂存可减少实际请求。</p>
    {!!plan.manualChanges && <div role="alert"><strong>将影响 {plan.manualChanges} 条系统知识的人工修改。</strong><p>提交后将按项目源文件恢复这些片段；管理员单独新增的知识保留。历史正文可在“内容历史与恢复”中查看。</p>
      <ul>{plan.affectedManualChanges.map(row => <li key={row.id}>#{row.id} {row.title}：{row.action}</li>)}</ul>
      {plan.manualChanges > plan.affectedManualChanges.length && <p>仅列出前 {plan.affectedManualChanges.length} 条。</p>}</div>}
    {!plan.chunkIds && <p className="hint">知识源同步和目标向量切换分阶段发布。同步已完成后取消任务，不会撤销已发布的正文或切分；目标向量切换前继续使用原向量方式。</p>}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="button-row"><Button type="button" variant="primary" disabled={pending || !!error} onClick={onConfirm}>确认影响并提交</Button>
      <Button type="button" variant="secondary" disabled={pending} onClick={onRefresh}>重新预览</Button></div>
  </section></div>
}
