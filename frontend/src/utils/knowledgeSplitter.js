export const knowledgeSplitters = [
  { value: 'recursive-markdown', label: 'A · 当前递归切分', description: '按主题分组，优先在完整句段处切分，小标题随正文；重叠按完整句对齐，实际长度可能随尾片条件变化。' },
  { value: 'langchain4j-recursive', label: 'B · LangChain4j 递归切分', description: '按主题分组，使用 LangChain4j 递归切分；重叠按完整句子处理，实际长度可能较短。' },
  { value: 'structural-markdown', label: 'C · 结构感知切分', description: '识别加粗小标题，优先保留完整段落、案例和列表块，过长再递归切分；短小结构允许低于目标最小长度。' },
  { value: 'langchain4j-enhanced', label: 'D · LangChain4j 增强切分', description: '以 B 为主，将孤立尾标题移至同主题的后续正文；新增超长块使用 A 的递归边界与完整句重叠处理，短块允许低于目标最小长度。' },
]

export const splitterLabel = value => knowledgeSplitters.find(item => item.value === value)?.label
  || (value === 'legacy-window' ? '历史窗口切分' : '尚未记录')
