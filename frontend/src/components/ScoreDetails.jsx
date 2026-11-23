/** @param {{breakdown?: import('../api/contracts').ScoreBreakdown | null; change?: import('../api/contracts').MealAnalysisResult['scoreChange']}} props */
export default function ScoreDetails({ breakdown, change }) {
  return <section className="score-details" aria-label="评分明细">
    {change && <p role="status">本次核对前 {change.before} 分 → 核对后 {change.after} 分（{change.after - change.before > 0 ? '+' : ''}{change.after - change.before}）
      {change.previousVersion !== change.currentVersion && '；评分版本发生变化，差值也包含规则版本影响。'}</p>}
    <details><summary>查看评分明细</summary>
      {breakdown ? <><p>按本次已识别和已标记的信息计算；仅反映搭配结构与规则风险，不代表摄入量或健康结论。</p>
        <ul>{breakdown.items.map((item, index) => <li key={`${item.rule}-${index}`}><span>{item.label}：{item.points > 0 ? '+' : ''}{item.points} 分</span>
          {!!item.foods.length && <small>依据：{item.foods.join('、')}</small>}</li>)}</ul>
        <p>合计 {breakdown.total} 分 · 评分版本 {breakdown.scoringVersion}</p></>
        : <p>这条历史记录未保存评分明细。原分数保留；主动纠正并重新评分后会保存新的明细。</p>}
    </details>
  </section>
}
