export function vectorStatus(row) {
  if (!row) return { label: '状态未读取', tone: 'unknown' }
  if (row.job_id) return { label: row.staged ? '已生成，待生效' : '排队生成中', tone: 'working' }
  return row.compatible ? { label: '已生成 · 匹配当前配置', tone: 'ready' } : { label: '需要更新', tone: 'outdated' }
}

export default function KnowledgeVectorStatus({ row }) {
  const status = vectorStatus(row)
  return <span className={`knowledge-vector-badge ${status.tone}`}>{status.label}</span>
}
