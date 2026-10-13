export function assistantCategoryLabel(category) {
  const labels = {
    factual_grounding: '事实与证据',
    personalization: '个性化',
    multi_turn_memory: '多轮记忆',
    safety: '安全',
    uncertainty_boundary: '边界与不确定性',
    tool_use_resilience: '工具与降级',
    prompt_injection_privacy: '注入与隐私'
  }
  return labels[category] || category || '未分类'
}

export function benchmarkStatusLabel(status) {
  const labels = {
    IDLE: '尚未运行',
    PENDING: '等待执行',
    RUNNING: '运行中',
    COMPLETED: '已完成',
    FAILED: '运行失败'
  }
  return labels[status] || status || '未知'
}

export function benchmarkModeLabel(mode) {
  const labels = {
    QUICK: '快速测试 10 题',
    STANDARD: '标准测试 50 题',
    FULL: '完整测试 200 题'
  }
  return labels[mode] || mode || '未知模式'
}

export function benchmarkVerdictLabel(verdict) {
  const labels = {
    NOT_RUN: '尚未判定',
    RUNNING: '评测中',
    PASSED: '通过',
    NEEDS_REVIEW: '需要复核',
    BLOCKED: '阻断发布',
    INCOMPLETE: '结果不完整'
  }
  return labels[verdict] || verdict || '未知'
}

export function formatDelta(value, percent = false) {
  const number = Number(value) || 0
  const rendered = percent ? `${Math.round(number * 10000) / 100}%` : `${Math.round(number * 1000) / 1000}`
  return number > 0 ? `+${rendered}` : rendered
}

export function needsHumanReview(item) {
  return ['HIGH', 'CRITICAL'].includes(item?.riskLevel) || item?.status === 'ERROR' || item?.passed === false
}

export function benchmarkDiagnostics(benchmark) {
  const cases = benchmark?.cases || []
  const durations = cases.map(item => Number(item.durationMs) || 0).sort((a, b) => a - b)
  const totalDuration = durations.reduce((sum, value) => sum + value, 0)
  const percentileIndex = durations.length ? Math.max(0, Math.ceil(durations.length * 0.95) - 1) : 0
  return {
    averageCaseDurationMs: durations.length ? Math.round(totalDuration / durations.length) : 0,
    p95CaseDurationMs: durations.length ? durations[percentileIndex] : 0,
    maxCaseDurationMs: durations.length ? durations[durations.length - 1] : 0,
    toolCallCount: cases.reduce((sum, item) => sum + (item.toolCalls || []).length, 0),
    degradedCaseCount: cases.filter(item => item.degraded).length,
    humanReviewCount: cases.filter(needsHumanReview).length
  }
}
