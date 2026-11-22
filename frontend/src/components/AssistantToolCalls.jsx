// @ts-check
const TOOL_LABELS = /** @type {Record<string, string>} */ ({
  recommend_next_meal: '查询下一餐搭配',
  get_my_recent_meals: '读取近期餐食',
  get_my_current_goal: '读取饮食目标',
  get_my_weekly_report: '读取饮食周报',
  search_diet_knowledge: '检索饮食知识'
})

/** @param {{calls?: import('../api/contracts').AssistantToolCall[]}} props */
export default function AssistantToolCalls({ calls }) {
  if (!calls?.length) return null
  return <details className="assistant-tool-calls">
    <summary>工具调用记录（{calls.length} 次）</summary>
    <ol>
      {calls.map((call, index) => <li key={`${index}-${call.name}`}>
        <strong>{TOOL_LABELS[call.name] || call.name}</strong>
        <p className="hint">{call.origin === 'MODEL' ? '模型发起' : '程序安排'} · 第 {call.round} 轮 · {call.durationMs} ms · {call.status === 'SUCCESS' ? '成功' : call.status === 'REJECTED' ? '已拒绝' : call.status === 'SKIPPED' ? '已跳过' : '未完成'}</p>
        {call.resultSummary && <p>{call.resultSummary}</p>}
        <details><summary>查看工具名称与参数</summary><code>{call.name}</code><pre>{formatArguments(call.arguments)}</pre></details>
      </li>)}
    </ol>
  </details>
}

/** @param {string} value */
function formatArguments(value) {
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
