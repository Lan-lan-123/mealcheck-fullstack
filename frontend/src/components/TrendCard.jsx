import MiniBars from './MiniBars'

export default function TrendCard({ trend }) {
  if (!trend) return null

  return (
    <div className="card trend-card">
      <div className="trend-head">
        <div>
          <h2>趋势概览</h2>
          <p>{trend.days || 30} 天评分、食物和风险统计</p>
        </div>
        <strong>{trend.averageScore || 0}</strong>
      </div>

      <div className="trend-metrics">
        <span>记录 {trend.total || 0} 条</span>
        <span>高分 {trend.highScoreCount || 0} 条</span>
        <span>低分 {trend.lowScoreCount || 0} 条</span>
      </div>

      <p className="trend-summary">{trend.scoreTrend || '趋势暂不明显'}。{trend.suggestion || ''}</p>

      <div className="trend-grid">
        <section>
          <h3>评分趋势</h3>
          <MiniBars items={trend.dailyAverageScores || []} emptyText="暂无评分趋势" />
        </section>
        <section>
          <h3>常见食物</h3>
          <MiniBars items={trend.foodCounts || []} emptyText="暂无食物统计" />
        </section>
        <section>
          <h3>常见风险</h3>
          <MiniBars items={trend.riskTagCounts || []} emptyText="暂无风险统计" />
        </section>
      </div>
    </div>
  )
}
