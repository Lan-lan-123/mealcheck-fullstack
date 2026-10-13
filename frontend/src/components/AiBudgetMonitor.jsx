import usePageResource from '../hooks/usePageResource'
import { adminAiBudget } from '../api/client'

export default function AiBudgetMonitor() {
  const resource = usePageResource({ key: 'ai-budget-monitor', load: adminAiBudget })
  const data = resource.data
  const global = data?.globalUsage
  const usagePercent = global?.callLimit > 0 ? Math.round(Number(global.attempts || 0) / global.callLimit * 100) : null
  return <div className="meal-chart-card wide" aria-label="AI 预算监控">
    <h3>AI 预算监控</h3>
    {resource.error ? <p role="alert">预算指标暂时不可用。</p> : resource.loading ? <p>正在读取预算指标…</p> : data?.enabled === false ? <p>每日预算限制已关闭。</p> : <>
      {global && <>
        <p>全站今日已用 {Number(global.attempts || 0).toLocaleString()} / {Number(global.callLimit || 0).toLocaleString()} 次 · 剩余 {Number(global.remainingCalls || 0).toLocaleString()} 次</p>
        <p>额度重置：{global.resetAt ? new Date(global.resetAt).toLocaleString('zh-CN') : '暂不可用'}</p>
        <p>已结算估算费用 {Number(global.spent || 0).toFixed(4)} · 预留 {Number(global.reserved || 0).toFixed(4)} {data.pricing?.currency || ''} · 金额上限 {Number(global.costLimit) > 0 ? Number(global.costLimit).toLocaleString() : '未启用'}</p>
        {usagePercent >= 80 && <p role="status">全站日额度已使用 {usagePercent}%，请检查消耗速度并及时调整。</p>}
      </>}
      {data?.pricing && <p>独立模型价格配置 {data.pricing.catalogEntries} 项；未知用量按预留估算记账，费用以供应商账单为准。</p>}
      {data?.pricing && !data.pricing.legacyChatPriceConfigured && !data.pricing.catalogEntries && <p role="status">尚未配置模型价格，当前费用按预留估算记账。</p>}
      {data?.monitorHealthy !== true ? <p role="status">预算恢复监控尚未就绪或暂时不可用。</p> : <>
      <p>待结算 {data.unsettled} 项 · 超过 10 分钟待恢复 {data.stale} 项 · 最老预留 {Math.round(data.oldest_seconds)} 秒</p>
      <p>当前实例累计：额度拒绝 {data.rejected} 次 · 结算延迟 {data.settlementDeferred} 次 · 预算存储故障 {data.unavailable} 次</p>
      </>}
    </>}
    <button type="button" onClick={resource.invalidate} disabled={resource.loading}>刷新预算指标</button>
  </div>
}
