import { useEffect, useState } from 'react'
import Button from '../../components/Button'
import { adminAiSettings, saveAdminAiSettings, resetAdminAiSettings, testAdminAiSettings } from '../../api/client'
import { useDialog } from '../../components/DialogProvider'
import { formatTime } from './AdminUi'

export default function AiSettingsPanel({ onSaved }) {
  const [settings, setSettings] = useState(null)
  const [form, setForm] = useState({ model: '', baseUrl: '', apiKey: '' })
  const [busy, setBusy] = useState('load')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const { confirm } = useDialog()
  function apply(value) {
    setSettings(value)
    setForm({ model: value.model || '', baseUrl: value.baseUrl || '', apiKey: '' })
  }
  useEffect(() => {
    const controller = new AbortController()
    adminAiSettings(controller.signal).then(value => {
      if (!controller.signal.aborted) apply(value)
    }).catch(err => {
      if (!controller.signal.aborted) setError(err.message || '读取 AI 配置失败')
    }).finally(() => { if (!controller.signal.aborted) setBusy('') })
    return () => controller.abort()
  }, [])
  const dirty = settings && (form.model !== (settings.model || '') || form.baseUrl !== (settings.baseUrl || '') || !!form.apiKey)
  async function perform(action) {
    setBusy(action); setError(''); setMessage('')
    try {
      if (action === 'test') {
        const result = await testAdminAiSettings(settings.version)
        if (result.success) setMessage(`${result.model}：${result.message}`)
        else setError(`${result.model}：${result.message}`)
      } else {
        const result = action === 'reset' ? await resetAdminAiSettings(settings.version)
          : await saveAdminAiSettings({ ...form, version: settings.version })
        apply(result)
        setMessage('AI 配置已生效，后续识图、饮食助手和周报调用将使用此配置。')
        onSaved?.()
      }
    } catch (err) { setError(err.message || 'AI 配置操作失败') }
    finally { setBusy('') }
  }
  async function reset() {
    if (await confirm('将使用部署环境中的模型和 API 连接配置，是否继续？', { title: '恢复部署配置' })) await perform('reset')
  }
  async function reload() {
    setBusy('load'); setError(''); setMessage('')
    try { apply(await adminAiSettings()) }
    catch (err) { setError(err.message || '读取 AI 配置失败') }
    finally { setBusy('') }
  }
  return <section className="meal-chart-card wide" aria-label="AI 模型配置">
    <h3>AI 模型配置</h3>
    <p className="muted-text">配置用于餐食识图、饮食建议、助手和周报。请选择支持图像输入且兼容 Chat Completions 的模型。</p>
    {settings && <p>当前生效模型：<strong>{settings.model || '未配置'}</strong> · 来源：{settings.source === 'ADMIN' ? '管理员配置' : '部署配置'} · 更新于 {formatTime(settings.updatedAt)}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {settings && <form className="ai-settings-form" onSubmit={event => { event.preventDefault(); perform('save') }}>
      <label className="form-field">模型名称<input required maxLength={200} value={form.model} disabled={!!busy}
        placeholder="例如 qwen3.7-flash" autoComplete="off"
        onChange={event => setForm(current => ({ ...current, model: event.target.value }))} /></label>
      <label className="form-field">Base URL<input required type="url" maxLength={1000} value={form.baseUrl} disabled={!!busy}
        placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" autoComplete="off"
        onChange={event => setForm(current => ({ ...current, baseUrl: event.target.value }))} /></label>
      <label className="form-field">API Key<input type="password" maxLength={4096} value={form.apiKey} disabled={!!busy}
        placeholder={settings.apiKeyConfigured ? '已配置，留空保留现有密钥' : '请输入 API Key'} autoComplete="new-password" spellCheck={false}
        onChange={event => setForm(current => ({ ...current, apiKey: event.target.value }))} /></label>
      <p className="muted-text">密钥保存后不回显；留空保留现有密钥。更换 API 服务地址时需填写对应密钥。</p>
      <div className="button-row">
        <Button type="submit" variant="primary" disabled={!!busy || !dirty} busy={busy === 'save'}>保存并生效</Button>
        <Button type="button" variant="secondary" disabled={!!busy || !!dirty} busy={busy === 'test'} onClick={() => perform('test')}>测试已保存配置</Button>
        <Button type="button" variant="secondary" disabled={!!busy} onClick={reload}>刷新配置</Button>
        <Button type="button" variant="secondary" disabled={!!busy || settings.source !== 'ADMIN'} onClick={reset}>恢复部署配置</Button>
      </div>
      <p className="muted-text">保存无需重启，其他服务实例会在约 2 秒内同步。连接测试会发送一条短文本请求并消耗少量供应商额度。</p>
    </form>}
    {!settings && <Button type="button" variant="secondary" disabled={!!busy} onClick={reload}>{busy ? '读取配置中…' : '重新读取配置'}</Button>}
  </section>
}
