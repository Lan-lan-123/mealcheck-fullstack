import { useId, useState } from 'react'
import { numericValue } from '../utils/barScale'

// Scores always use a 0–100 axis, so small fluctuations are not exaggerated.
export default function ScoreTrend({ items = [], emptyText }) {
  const titleId = useId()
  const [selected, setSelected] = useState(null)
  if (!items.length) return <p className="empty-mini">{emptyText}</p>
  const dated = items.every(item => /^\d{4}-\d{2}-\d{2}$/.test(item.label) && Number.isFinite(Date.parse(item.label)))
  const series = dated ? [...items].sort((a, b) => Date.parse(a.label) - Date.parse(b.label)) : items
  const first = dated ? Date.parse(series[0].label) : 0
  const span = dated ? Date.parse(series.at(-1).label) - first : series.length - 1
  const points = series.map((item, index) => {
    const value = numericValue(item.value)
    return { ...item, value, x: span ? 42 + ((dated ? Date.parse(item.label) - first : index) / span) * 500 : 292,
      y: value === null || value < 0 || value > 100 ? null : 174 - value * 1.5 }
  })
  let gap = true
  const path = points.map(point => {
    if (point.y === null) { gap = true; return '' }
    const command = gap ? 'M' : 'L'; gap = false
    return `${command}${point.x},${point.y}`
  }).join(' ')
  const chosen = points[selected] || points.at(-1)
  return <div className="score-trend">
    <svg viewBox="0 0 580 212" role="img" aria-labelledby={titleId}>
      <title id={titleId}>评分趋势，纵轴 0 至 100 分。日期与数值可在完整指标中查看。</title>
      {[0, 25, 50, 75, 100].map(value => <g key={value}><line x1="42" x2="542" y1={174 - value * 1.5} y2={174 - value * 1.5} className="score-trend-grid" /><text x="32" y={178 - value * 1.5} textAnchor="end">{value}</text></g>)}
      <path d={path} className="score-trend-line" />
      {points.filter(point => point.y !== null).map((point, index) => <circle key={`${point.label}-${index}`} cx={point.x} cy={point.y} r="4" tabIndex="0" aria-label={`${point.label}：${point.value} 分`} onFocus={() => setSelected(points.indexOf(point))} onMouseEnter={() => setSelected(points.indexOf(point))}><title>{point.label}：{point.value} 分</title></circle>)}
      <text x="42" y="202">{series[0].label}</text><text x="542" y="202" textAnchor="end">{series.at(-1).label}</text>
    </svg>
    <p className="score-trend-caption">{chosen.label} · {chosen.y === null ? '暂无有效评分' : `${chosen.value} 分`}<span>评分范围 0–100；缺失值不补为 0</span></p>
  </div>
}
