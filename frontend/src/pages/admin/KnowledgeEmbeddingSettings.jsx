import { useEffect, useRef, useState } from 'react'
import Button from '../../components/Button'
import { adminEmbeddingSettings, saveAdminEmbeddingSettings, testAdminEmbeddingSettings, applyAdminEmbeddingSettings, previewAdminEmbeddingSettings } from '../../api/client'
import KnowledgeRebuildPreview from './KnowledgeRebuildPreview'

const editable = value => ({ model: value?.model || '', baseUrl: value?.baseUrl || '', apiKey: '', dimension: value?.dimension || 512, sendDimensions: value?.sendDimensions ?? true })

export default function KnowledgeEmbeddingSettings({ refreshKey, rebuilding, onApplied }) {
  const [settings, setSettings] = useState(null)
  const [form, setForm] = useState(editable())
  const [busy, setBusy] = useState('load')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [reload, setReload] = useState(0)
  const [plan, setPlan] = useState(null)
  const baseline = editable(settings?.draft || settings?.active)
  const dirty = settings && Object.keys(baseline).some(key => form[key] !== baseline[key])
  const dirtyRef = useRef(false)
  dirtyRef.current = !!dirty
  const lastReload = useRef(reload)
  function update(value) { setSettings(value); setForm(editable(value.draft || value.active)) }
  useEffect(() => {
    const controller = new AbortController()
    const preserveEdits = dirtyRef.current && lastReload.current === reload
    lastReload.current = reload
    setBusy('load')
    adminEmbeddingSettings(controller.signal).then(value => {
      if (!controller.signal.aborted) {
        if (preserveEdits) setSettings(value)
        else update(value)
        setError('')
      }
    }).catch(err => {
      if (!controller.signal.aborted) setError(err.message || '读取向量模型配置失败')
    }).finally(() => { if (!controller.signal.aborted) setBusy('') })
    return () => controller.abort()
  }, [reload, refreshKey])
  function field(key, value) { setForm(current => ({ ...current, [key]: value })); setMessage('') }
  async function perform(action, previewToken) {
    setBusy(action); setError(''); setMessage('')
    try {
      if (action === 'save') {
        update(await saveAdminEmbeddingSettings({ ...form, version: settings.version }))
        setMessage('待用配置已保存。请测试连接，通过后应用配置。')
      } else if (action === 'test') {
        const result = await testAdminEmbeddingSettings(settings.version)
        // Refresh the test status without overwriting this operation's result message.
        update(await adminEmbeddingSettings())
        if (result.success) setMessage(`${result.model}：${result.message} ${result.dimension} 维 · ${result.elapsedMs} ms`)
        else setError(result.message || '向量连接测试失败')
      } else if (action === 'preview') {
        setPlan(await previewAdminEmbeddingSettings(settings.version))
      } else {
        const result = await applyAdminEmbeddingSettings(settings.version, previewToken)
        setPlan(null)
        update(result.settings)
        setMessage(result.rebuilding ? '重建任务已提交，正在使用固定的配置版本生成向量；完成后自动切换。' : '连接配置已生效，后续请求使用新配置。')
        onApplied?.(result)
      }
    } catch (err) { setError(err.message || '向量配置操作失败') }
    finally { setBusy('') }
  }
  return <section className="embedding-settings" aria-label="向量模型配置">
    {plan && <KnowledgeRebuildPreview plan={plan} pending={!!busy} error={error} onConfirm={() => perform('apply', plan.previewToken)} onRefresh={() => perform('preview')} onClose={() => { setPlan(null); setError('') }} />}
    <div className="knowledge-indexing-heading"><h4>向量模型配置</h4><span className="hint">OpenAI 兼容 Embeddings 接口</span></div>
    <p className="hint">独立配置向量服务的模型、地址和密钥。保存后先测试；更换模型、地址或维度时，整库重建成功后生效。</p>
    {settings && <div className="embedding-configuration-summary">
      <p><strong>当前生效：</strong>{settings.activeMode === 'SEMANTIC' ? `${settings.active.model} · ${settings.active.dimension} 维` : '本地哈希向量'}
        {settings.activeMode === 'SEMANTIC' && <span className="hint"> · {settings.active.baseUrl}</span>}</p>
      {settings.draft && <p><strong>已保存配置：</strong>{settings.draft.model} · {settings.draft.dimension} 维 · {settings.draft.testedAt ? '连接测试已通过' : '待测试'}</p>}
    </div>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {settings && <form className="ai-settings-form" onSubmit={event => { event.preventDefault(); perform('save') }}>
      <label className="form-field">向量模型名称<input required maxLength={200} value={form.model} disabled={!!busy} autoComplete="off"
        placeholder="填写服务商提供的 Embedding 模型名称" onChange={event => field('model', event.target.value)} /></label>
      <label className="form-field">向量 Base URL<input required type="url" maxLength={1000} value={form.baseUrl} disabled={!!busy} autoComplete="off"
        placeholder="https://your-provider.example/v1" onChange={event => field('baseUrl', event.target.value)} /></label>
      <label className="form-field">向量 API Key<input type="password" maxLength={4096} value={form.apiKey} disabled={!!busy} autoComplete="new-password" spellCheck={false}
        placeholder={(settings.draft || settings.active)?.apiKeyConfigured ? '已配置，留空保留；更换地址请填写新密钥' : '请输入 API Key'} onChange={event => field('apiKey', event.target.value)} /></label>
      <label className="form-field">向量维度<input required type="number" min={1} max={2000} step={1} value={form.dimension} disabled={!!busy}
        onChange={event => field('dimension', event.target.value === '' ? '' : Number(event.target.value))} /></label>
      <label className="embedding-dimension-option"><input type="checkbox" checked={form.sendDimensions} disabled={!!busy}
        onChange={event => field('sendDimensions', event.target.checked)} />请求指定维度（接口支持时启用）</label>
      <p className="hint">关闭后使用模型默认输出维度，仍须与填写的维度一致。密钥加密保存且不回显；更换地址时需重新填写密钥。</p>
      <div className="button-row">
        <Button type="submit" variant="secondary" disabled={!!busy || (!dirty && !!settings.draft)} busy={busy === 'save'}>保存待用配置</Button>
        <Button type="button" variant="secondary" disabled={!!busy || !!dirty || !settings.draft} busy={busy === 'test'} onClick={() => perform('test')}>测试向量连接</Button>
        <Button type="button" variant="primary" disabled={!!busy || rebuilding || !!dirty || !settings.draft?.testedAt} busy={busy === 'apply'} onClick={() => perform(settings.rebuildRequired ? 'preview' : 'apply')}>
          {settings.rebuildRequired ? '应用配置并重建全部向量' : '应用连接配置'}</Button>
        <Button type="button" variant="secondary" disabled={!!busy} onClick={() => { setMessage(''); setReload(value => value + 1) }}>刷新向量配置</Button>
      </div>
      <p className="hint">重建期间继续使用原模型和原向量；可继续保存下一版配置，当前任务使用提交时的版本。连接测试会产生少量 API 用量。切换后长期记忆向量会自动重建，期间使用文本记忆。</p>
    </form>}
    {!settings && <Button type="button" variant="secondary" disabled={!!busy} onClick={() => setReload(value => value + 1)}>{busy ? '读取向量配置中…' : '重新读取向量配置'}</Button>}
  </section>
}
