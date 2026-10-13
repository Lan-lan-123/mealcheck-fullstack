import TrackingSummary from "./TrackingSummary"
import MiniBars from './MiniBars'
import ScoreTrend from './ScoreTrend'
import { useState } from 'react'
import SectionSwitcher from './SectionSwitcher'
import DetailDrawer from './DetailDrawer'

export default function TrendCard({ trend }) {
  const [section, setSection] = useState('scores')
  const [showDetails, setShowDetails] = useState(false)
  if (!trend) return null
  const charts = {
    scores: { title: '评分趋势', items: trend.dailyAverageScores || [], empty: '暂无评分趋势' },
    foods: { title: '常见食物', items: trend.foodCounts || [], empty: '暂无食物统计' },
    risks: { title: '常见风险', items: trend.riskTagCounts || [], empty: '暂无风险统计' }
  }
  const active = charts[section]
  const previewItems = section === 'scores' ? active.items.slice(-7) : active.items.slice(0, 7)

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

      <TrackingSummary summary={trend.tracking} />
      <SectionSwitcher label="饮食趋势分类" value={section} onChange={setSection} items={[["scores", "评分趋势"], ["foods", "常见食物"], ["risks", "常见风险"]]} />
      <div className="trend-chart"><h3>{active.title}</h3>{section === 'scores' ? <ScoreTrend items={previewItems} emptyText={active.empty} /> : <MiniBars items={previewItems} emptyText={active.empty} />}</div>
      <div className="detail-entry"><span>{active.items.length > 7 ? (section === 'scores' ? '当前展示最近 7 个日期' : '当前展示前 7 项') : '当前展示全部项目'}</span><button className="secondary-btn" type="button" onClick={() => setShowDetails(true)}>查看全部趋势指标</button></div>
      {showDetails && <DetailDrawer title="完整饮食趋势指标" onClose={() => setShowDetails(false)}>{Object.values(charts).map(chart => <section key={chart.title}><h3>{chart.title}</h3><MiniBars items={chart.items} emptyText={chart.empty} /></section>)}</DetailDrawer>}
    </div>
  )
}
