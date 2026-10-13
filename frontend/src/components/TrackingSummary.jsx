export default function TrackingSummary({ summary }) {
  if (!summary) return null
  return <section className="tracking-summary" aria-label="记录覆盖与执行情况">
    <h3>记录覆盖与执行情况</h3>
    <p>{summary.periodStart} 至 {summary.periodEnd}</p>
    <div className="chips"><span>有记录 {summary.recordedDays}/{summary.days} 天 · {summary.coveragePercent}%</span><span>三餐未记录 {summary.unknownSlots} 个</span><span>明确未吃 {summary.confirmedSkippedSlots} 个</span></div>
    {!!summary.goals?.length && <ul>{summary.goals.map(p => <li key={p.goal.id}>{p.goal.kind === 'MEALS_DAILY' ? '每日记录' : '每周记录天数'}：{p.completed}/{p.goal.target} {p.goal.kind === 'MEALS_DAILY' ? '餐次' : '天'} · {p.periodStart} 至 {p.periodEnd} · {p.achieved ? '已达标' : `还差 ${p.remaining}`}{p.goal.kind === 'MEALS_DAILY' && `；目标周期内已达标 ${p.daysAchieved}/${p.eligibleDays} 天`}</li>)}</ul>}
    <p>本期计划：完成 {summary.plans?.DONE || 0} · 跳过 {summary.plans?.SKIPPED || 0} · 待执行 {summary.plans?.OPEN || 0}</p>
    <p className="hint">{summary.explanation}</p>
  </section>
}
