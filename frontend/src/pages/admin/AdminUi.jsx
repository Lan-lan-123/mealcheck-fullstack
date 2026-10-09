import { useState } from 'react'
import MiniBars from '../../components/MiniBars'
import ScoreTrend from '../../components/ScoreTrend'
import DetailDrawer from '../../components/DetailDrawer'

export { formatTime } from '../../utils/time'

export function categoryLabel(category) {
  const labels = {
    fat_loss: '减脂',
    muscle_gain: '增肌',
    high_oil: '高油',
    sugar: '糖饮甜品',
    vegetable: '蔬菜',
    staple: '主食',
    general: '通用',
    none: '无答案'
  }
  return labels[category] || category || '通用'
}

export function MetricSmall({ label, value }) {
  return <div className="meal-stat-card"><span>{label}</span><strong>{value}</strong></div>
}

export function Chart({ title, items, empty, wide, trend = false }) {
  const [showDetails, setShowDetails] = useState(false)
  const allItems = items || []
  const preview = trend ? allItems.slice(-7) : allItems.slice(0, 7)
  return <div className={wide ? 'meal-chart-card wide' : 'meal-chart-card'}><h3>{title}</h3>{trend ? <ScoreTrend items={preview} emptyText={empty} /> : <MiniBars items={preview} emptyText={empty} />}
    {allItems.length > 7 && <div className="detail-entry"><span>当前展示{trend ? '最近 7 个日期' : '前 7 项'}，共 {allItems.length} 项</span><button className="secondary-btn" type="button" onClick={() => setShowDetails(true)}>查看全部指标</button></div>}
    {showDetails && <DetailDrawer title={`${title} · 全部指标`} onClose={() => setShowDetails(false)}><MiniBars items={allItems} emptyText={empty} /></DetailDrawer>}
  </div>
}

export function FoodTags({ names = [] }) {
  return (
    <div className="food-tag-list">
      {names.length ? names.map((name, index) => <span className="food-tag" key={`${name}-${index}`}>{name}</span>) : <span className="muted-text">暂无</span>}
    </div>
  )
}

export function Pagination({ pageData, pageSize, setPage, setPageSize, loading, sizes = [10, 20, 50] }) {
  return (
    <div className="pagination">
      <button className="secondary-btn" type="button" onClick={() => setPage(page => Math.max(0, page - 1))} disabled={pageData.page <= 0 || loading}>上一页</button>
      <span>第 {(pageData.page ?? 0) + 1} / {Math.max(1, pageData.totalPages || 1)} 页，共 {pageData.totalElements || 0} 条</span>
      <button className="secondary-btn" type="button" onClick={() => setPage(page => page + 1)} disabled={(pageData.page ?? 0) + 1 >= (pageData.totalPages || 1) || loading}>下一页</button>
      <select value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}>
        {sizes.map(size => <option key={size} value={size}>{size} 条/页</option>)}
      </select>
    </div>
  )
}
