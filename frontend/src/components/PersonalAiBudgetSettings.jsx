import { useEffect, useState } from 'react'
import Button from './Button'
import { personalAiCostSettings, savePersonalAiCostSettings } from '../api/client'

export default function PersonalAiBudgetSettings() {
  const [settings, setSettings] = useState(null)
  const [limit, setLimit] = useState('0')
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setBusy(true); setError(''); setMessage('')
    personalAiCostSettings(controller.signal).then(data => {
      if (!controller.signal.aborted) { setSettings(data); setLimit(String(data.dailyCost)) }
    }).catch(err => { if (!controller.signal.aborted) setError(err.message || '读取个人预算失败') })
      .finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => controller.abort()
  }, [revision])
  async function save(event) {
    event.preventDefault(); setBusy(true); setError(''); setMessage('')
    try {
      const result = await savePersonalAiCostSettings({ version: settings.version, dailyCost: limit })
      setSettings(result); setLimit(String(result.dailyCost)); setMessage('个人预算已保存，后续调用按新预算检查。')
      window.dispatchEvent(new Event('mealcheck:ai-budget-changed'))
    } catch (err) { setError(err.message || '保存个人预算失败') }
    finally { setBusy(false) }
  }
  return <div className="personal-ai-budget-settings">
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {settings && <form onSubmit={save} className="ai-cost-settings-form">
      <label className="form-field">我的每日金额上限（{settings.currency}）
        <input type="number" min="0" max="999999999" step="0.000001" required value={limit} disabled={busy}
          onChange={event => setLimit(event.target.value)} />
      </label>
      <p>设为 0 表示不增加个人金额限制。管理员对每人的上限：{Number(settings.administratorDailyCost) > 0 ? `${settings.administratorDailyCost} ${settings.currency}` : '未启用'}；当前生效上限：{Number(settings.effectiveDailyCost) > 0 ? `${settings.effectiveDailyCost} ${settings.currency}` : '未启用'}。</p>
      <p>个人预算与管理员限制同时生效，取较严格者。修改不清零今日用量，也不取消已开始的调用。费用为估算值，以供应商账单为准。</p>
      {!settings.enabled && <p role="status">管理员已关闭预算检查，保存的设置会在预算检查启用后生效。</p>}
      <div className="button-row">
        <Button type="submit" variant="primary" disabled={busy} busy={busy}>保存个人预算</Button>
        <Button type="button" variant="secondary" disabled={busy} onClick={() => setRevision(value => value + 1)}>刷新个人预算配置</Button>
      </div>
    </form>}
    {!settings && <Button type="button" variant="secondary" disabled={busy} onClick={() => setRevision(value => value + 1)}>{busy ? '正在读取个人预算…' : '重新读取个人预算'}</Button>}
  </div>
}
