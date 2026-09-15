export function categoryName(c) {
  return (
    {
      staple: '主食',
      protein: '蛋白质',
      vegetable: '蔬菜',
      fruit: '水果',
      dairy: '乳制品',
      soup: '汤品',
      drink: '饮品',
      dessert: '甜品',
      fried: '油炸',
      oily: '高油',
      other: '其他'
    }[c] || c
  )
}

export function reportGenerationLabel(mode) {
  return (
    {
      MULTI_AGENT: '多 Agent 协作生成',
      MULTI_AGENT_PARTIAL_FALLBACK: '多 Agent 协作生成（部分降级）',
      RULE_BASED_REVIEW_FALLBACK: '规则周报（审查回退）',
      RULE_BASED_GRAPH_FALLBACK: '规则周报（编排回退）',
      RULE_BASED: '规则周报'
    }[mode] || '规则周报'
  )
}

export function reportReviewLabel(status) {
  return (
    {
      APPROVED_AI: 'AI 审查通过',
      APPROVED_RULES: '安全规则审查通过',
      REJECTED_FALLBACK: '审查未通过，已安全回退',
      GRAPH_FAILED: '编排异常，已安全回退',
      DISABLED: '多 Agent 已关闭',
      NOT_REQUIRED: '无需审查'
    }[status] || status || '无需审查'
  )
}
