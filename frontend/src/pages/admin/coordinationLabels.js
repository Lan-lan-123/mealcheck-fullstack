export function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${milliseconds} ms`
  if (milliseconds < 60000) return `${(milliseconds / 1000).toFixed(1)} 秒`
  return `${(milliseconds / 60000).toFixed(1)} 分钟`
}

export function coordinationStatusLabel(status) {
  const labels = {
    RUNNING: '运行中',
    SUCCEEDED: '成功',
    DEGRADED: '降级完成',
    FAILED: '失败',
    INTERRUPTED: '已中断'
  }
  return labels[status] || status || '未知'
}

export function coordinationStatusClass(status) {
  if (status === 'SUCCEEDED') return 'coordination-status success'
  if (status === 'RUNNING') return 'coordination-status running'
  if (status === 'DEGRADED') return 'coordination-status degraded'
  return 'coordination-status failed'
}

export function coordinationNodeLabel(nodeName) {
  const labels = {
    prepare: '准备与分发',
    trend_agent: '趋势分析 Agent',
    evidence_tool: 'RAG 证据工具',
    plan_agent: '计划生成 Agent',
    review_agent: '安全审查 Agent'
  }
  return labels[nodeName] || nodeName || '未知节点'
}

export function coordinationModeLabel(mode) {
  const labels = {
    MULTI_AGENT: '完整多 Agent',
    MULTI_AGENT_PARTIAL_FALLBACK: '部分降级',
    RULE_BASED_REVIEW_FALLBACK: '审查回退',
    RULE_BASED_GRAPH_FALLBACK: '编排回退',
    INTERRUPTED: '运行中断'
  }
  return labels[mode] || mode || '运行中'
}
