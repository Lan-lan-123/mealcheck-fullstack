import usePageResource from '../hooks/usePageResource'
import { adminAiBudget } from '../api/client'

export default function AiBudgetMonitor() {
  const resource = usePageResource({ key: 'ai-budget-monitor', load: adminAiBudget })
  const data = resource.data
  return <div className="meal-chart-card wide" aria-label="AI 预算监控">
    <h3>AI 预算监控</h3>
    {resource.error ? <p role="alert">预算指标暂时不可用。</p> : resource.loading ? <p>正在读取预算指标…</p> : data?.enabled === false ? <p>每日预算限制已关闭。</p> : data?.monitorHealthy !== true ? <p role="status">预算恢复监控尚未就绪或暂时不可用。</p> : <>
      <p>待结算 {data.unsettled} 项 · 超过 10 分钟待恢复 {data.stale} 项 · 最老预留 {Math.round(data.oldest_seconds)} 秒</p>
      <p>当前实例累计：额度拒绝 {data.rejected} 次 · 结算延迟 {data.settlementDeferred} 次 · 预算存储故障 {data.unavailable} 次</p>
    </>}
    <button type="button" onClick={resource.invalidate} disabled={resource.loading}>刷新预算指标</button>
  </div>
}
