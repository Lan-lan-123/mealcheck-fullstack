import Button from './Button'
import { formatTime } from '../utils/time'
import { useEffect, useState } from 'react'
import { aiBudget } from '../api/client'
import PersonalAiBudgetSettings from './PersonalAiBudgetSettings'

export default function AiBudgetNotice({ revision = 0 }) {
  const [usage, setUsage] = useState(null)
  const [error, setError] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [settingsOpen, setSettingsOpen] = useState(false)
  useEffect(() => {
    const changed = () => setRefresh(value => value + 1)
    window.addEventListener('mealcheck:ai-budget-changed', changed)
    return () => window.removeEventListener('mealcheck:ai-budget-changed', changed)
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setError(false)
    aiBudget(controller.signal).then(data => {
      if (!controller.signal.aborted) setUsage(data)
    }).catch(err => { if (!controller.signal.aborted && err.name !== 'AbortError') setError(true) })
    return () => controller.abort()
  }, [revision, refresh])
  if (!error && (!usage || typeof usage.enabled !== 'boolean')) return null
  const reset = usage?.resetAt ? formatTime(usage.resetAt) : '次日'
  const costLimited = Number(usage?.costLimit) > 0
  const spent = Number(usage?.spent || 0), reserved = Number(usage?.reserved || 0)
  const exhausted = usage?.remainingCalls === 0 || (costLimited && spent + reserved >= Number(usage.costLimit))
  return <aside className="ai-budget-notice" aria-label="AI 每日额度">
    <span role={exhausted || error ? 'status' : undefined}>
      {error ? 'AI 额度暂时无法查询。' : usage.enabled === false ? '管理员已关闭每日预算检查。' : exhausted ? `今日 AI 额度已用尽，${reset} 重置。` : `今日 AI 剩余 ${usage.remainingCalls} / ${usage.callLimit} 次调用，${reset} 重置。`}
      {!error && costLimited && ` 估算费用：已用 ${spent.toFixed(4)}，预留 ${reserved.toFixed(4)}，上限 ${Number(usage.costLimit).toFixed(4)} ${usage.currency || ''}。`}
    </span>
    {!error && <small>一次分析或对话可能使用多次调用，重试也计入额度。</small>}
    <Button variant="secondary" type="button" onClick={() => setRefresh(value => value + 1)}>刷新额度</Button>
    <Button variant="secondary" type="button" aria-expanded={settingsOpen} onClick={() => setSettingsOpen(value => !value)}>{settingsOpen ? '收起个人预算' : '设置个人预算'}</Button>
    {settingsOpen && <PersonalAiBudgetSettings />}
  </aside>
}
