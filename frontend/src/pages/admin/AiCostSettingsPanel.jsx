import { useEffect, useState } from 'react'
import Button from '../../components/Button'
import { adminAiCostSettings, saveAdminAiCostSettings, resetAdminAiCostSettings } from '../../api/client'
import { useDialog } from '../../components/DialogProvider'

export default function AiCostSettingsPanel({ onSaved }) {
  const [settings, setSettings] = useState(null)
  const [form, setForm] = useState({ globalDailyCost: '0', userDailyCost: '0', systemDailyCost: '0', prices: [] })
  const [busy, setBusy] = useState('load')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [revision, setRevision] = useState(0)
  const { confirm } = useDialog()
  function apply(data) {
    setSettings(data)
    setForm({ globalDailyCost: String(data.globalDailyCost), userDailyCost: String(data.userDailyCost), systemDailyCost: String(data.systemDailyCost),
      prices: (data.prices || []).map(price => ({ ...price, inputCostPerMillion: String(price.inputCostPerMillion), outputCostPerMillion: String(price.outputCostPerMillion) })) })
  }
  useEffect(() => {
    const controller = new AbortController()
    setBusy('load'); setError(''); setMessage('')
    adminAiCostSettings(controller.signal).then(data => { if (!controller.signal.aborted) apply(data) })
      .catch(err => { if (!controller.signal.aborted) setError(err.message || '读取费用配置失败') })
      .finally(() => { if (!controller.signal.aborted) setBusy('') })
    return () => controller.abort()
  }, [revision])
  function editPrice(index, field, value) {
    setForm(current => ({ ...current, prices: current.prices.map((price, i) => i === index ? { ...price, [field]: value } : price) }))
  }
  async function save(action) {
    setBusy(action); setError(''); setMessage('')
    try {
      apply(action === 'reset' ? await resetAdminAiCostSettings(settings.version)
        : await saveAdminAiCostSettings({ ...form, version: settings.version }))
      setMessage('费用配置已保存，后续调用使用新设置；今日用量保留。')
      onSaved?.(); window.dispatchEvent(new Event('mealcheck:ai-budget-changed'))
    } catch (err) { setError(err.message || '保存费用配置失败') }
    finally { setBusy('') }
  }
  async function reset() {
    if (await confirm('恢复部署环境中的金额上限和模型价格，个人预算及今日用量会保留。', { title: '恢复部署费用配置' })) await save('reset')
  }
  return <section className="meal-chart-card wide" aria-label="AI 费用设置">
    <h3>AI 费用设置</h3>
    <p>金额限制按需开启，设为 0 即关闭对应限制。调用次数和并发限制继续独立生效。</p>
    {error && <p className="error-text" role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {settings && <form className="ai-cost-settings-form" onSubmit={event => { event.preventDefault(); save('save') }}>
      <p>币种：{settings.currency} · 来源：{settings.source === 'ADMIN' ? '管理员配置' : '部署配置'}</p>
      {!settings.enabled && <p role="status">部署配置已关闭预算检查，金额限制将在预算检查启用后生效。</p>}
      <div className="ai-cost-limits">
        {[['globalDailyCost', '全站每日金额上限'], ['userDailyCost', '每位用户每日金额上限'], ['systemDailyCost', '系统任务每日金额上限']].map(([field, label]) => <label className="form-field" key={field}>{label}（{settings.currency}）
          <input type="number" required min="0" max="999999999" step="0.000001" value={form[field]} disabled={!!busy}
            onChange={event => setForm(current => ({ ...current, [field]: event.target.value }))} />
        </label>)}
      </div>
      <p>用户可以设置更严格的个人预算。降低上限可能立即阻止新调用；修改不取消在途请求、不清零用量。</p>
      <h4>模型单价</h4>
      <p>按供应商核实的单价填写，单位为 {settings.currency} / 百万 token。接口地址需填写完整请求地址，例如以 /chat/completions 或 /embeddings 结尾。未配置价格时仍按预留估算记账；明确免费的模型可填写 0。</p>
      {form.prices.map((price, index) => <fieldset className="ai-price-entry" key={index} disabled={!!busy}>
        <legend>模型价格 {index + 1}</legend>
        <label className="form-field">完整接口地址<input type="url" required maxLength={1000} value={price.endpoint}
          placeholder="https://api.example.com/v1/chat/completions" onChange={event => editPrice(index, 'endpoint', event.target.value)} /></label>
        <label className="form-field">模型名称<input required maxLength={200} value={price.model} onChange={event => editPrice(index, 'model', event.target.value)} /></label>
        <div className="ai-cost-limits">
          <label className="form-field">输入单价<input type="number" required min="0" max="999999999" step="0.000001" value={price.inputCostPerMillion} onChange={event => editPrice(index, 'inputCostPerMillion', event.target.value)} /></label>
          <label className="form-field">输出单价<input type="number" required min="0" max="999999999" step="0.000001" value={price.outputCostPerMillion} onChange={event => editPrice(index, 'outputCostPerMillion', event.target.value)} /></label>
        </div>
        <Button type="button" variant="secondary" onClick={() => setForm(current => ({ ...current, prices: current.prices.filter((_, i) => i !== index) }))}>移除模型价格 {index + 1}</Button>
      </fieldset>)}
      <div className="button-row">
        <Button type="button" variant="secondary" disabled={!!busy || form.prices.length >= 100} onClick={() => setForm(current => ({ ...current, prices: [...current.prices, { endpoint: '', model: '', inputCostPerMillion: '0', outputCostPerMillion: '0', currency: settings.currency }] }))}>添加模型价格</Button>
        <Button type="submit" variant="primary" disabled={!!busy} busy={busy === 'save'}>保存费用设置</Button>
        <Button type="button" variant="secondary" disabled={!!busy} onClick={() => setRevision(value => value + 1)}>刷新费用配置</Button>
        <Button type="button" variant="secondary" disabled={!!busy || settings.source !== 'ADMIN'} onClick={reset}>恢复部署费用配置</Button>
      </div>
      <p>币种沿用部署配置。费用为估算值，供应商对图片、缓存和失败请求的计费可能不同，最终以供应商账单为准。</p>
    </form>}
    {!settings && <Button type="button" variant="secondary" disabled={!!busy} onClick={() => setRevision(value => value + 1)}>{busy ? '正在读取费用配置…' : '重新读取费用配置'}</Button>}
  </section>
}
