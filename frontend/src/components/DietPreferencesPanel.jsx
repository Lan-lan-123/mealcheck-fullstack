import { useEffect, useRef, useState } from 'react'
import Button from './Button'
import { dietPreferences, saveDietPreferences } from '../api/client'

/** @param {{onSaved?: () => void}} props */
export default function DietPreferencesPanel({ onSaved }) {
  const [settings, setSettings] = useState(/** @type {import('../api/contracts').DietPreferences | null} */ (null))
  const [style, setStyle] = useState(/** @type {import('../api/contracts').DietPreferences['style']} */ ('OMNIVORE'))
  const [avoid, setAvoid] = useState(''), [disliked, setDisliked] = useState('')
  const [loading, setLoading] = useState(true), [saving, setSaving] = useState(false), [message, setMessage] = useState(''), [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const alive = useRef(false)
  useEffect(() => {
    alive.current = true
    const controller = new AbortController()
    setLoading(true); setError('')
    dietPreferences(controller.signal).then(value => {
      if (controller.signal.aborted) return
      setSettings(value); setStyle(value.style); setAvoid(value.avoidFoods.join('、')); setDisliked(value.dislikedFoods.join('、'))
    }).catch(err => { if (!controller.signal.aborted) setError(err.message) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { alive.current = false; controller.abort() }
  }, [revision])
  /** @param {string} text */
  function names(text) {
    const values = [...new Set(text.split(/[,，、\n]/).map(s => s.trim()).filter(Boolean))]
    if (values.length > 20 || values.some(s => s.length > 40 || !/^[\p{L}\p{N} （）()·、-]+$/u.test(s))) throw new Error('每类最多填写 20 项，每项最多 40 字，请填写食材名称。')
    return values
  }
  async function save(event) {
    event.preventDefault()
    if (!settings || saving || loading) return
    setSaving(true); setError(''); setMessage('')
    try {
      const next = await saveDietPreferences({ style, avoidFoods: names(avoid), dislikedFoods: names(disliked), version: settings.version })
      if (!alive.current) return
      setSettings(next); setAvoid(next.avoidFoods.join('、')); setDisliked(next.dislikedFoods.join('、'))
      setMessage('饮食偏好已保存，将用于后续单餐建议、助手回复和新周报。'); onSaved?.()
    } catch (err) { if (alive.current) setError(err.status === 409 ? '偏好已在其他页面修改，请重新加载后核对。' : err.message) }
    finally { if (alive.current) setSaving(false) }
  }
  return <section className="card diet-preferences"><h2>饮食偏好与忌口</h2>
    <p className="hint">由你明确设置，优先于聊天中推测的偏好。忌口和不喜欢的食材都将避开推荐；设置不会改变已记录食物和结构评分。</p>
    <p className="hint">照片无法确认所有配料，设置也不能保证识别出隐藏成分；配料不明确时请自行核对。</p>
    {loading && <p role="status">加载饮食偏好…</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    <form className="goal-form" onSubmit={save}>
      <label className="form-field">饮食类型<select aria-label="饮食类型" disabled={loading || saving || !settings} value={style} onChange={e => { setStyle(/** @type {import('../api/contracts').DietPreferences['style']} */ (e.target.value)); setMessage('') }}>
        <option value="OMNIVORE">不限</option><option value="VEGETARIAN">蛋奶素</option><option value="VEGAN">纯素</option></select></label>
      <label className="form-field">忌口食材<input aria-label="忌口食材" disabled={loading || saving || !settings} value={avoid} maxLength={820} onChange={e => { setAvoid(e.target.value); setMessage('') }} placeholder="例如：花生、虾；用顿号或逗号分隔" /></label>
      <label className="form-field">不喜欢的食材<input aria-label="不喜欢的食材" disabled={loading || saving || !settings} value={disliked} maxLength={820} onChange={e => { setDisliked(e.target.value); setMessage('') }} placeholder="例如：香菜、芹菜" /></label>
      <div className="quick-review-options"><Button type="submit" variant="secondary" disabled={loading || saving || !settings}>{saving ? '保存中…' : '保存饮食偏好'}</Button>
        <Button type="button" variant="secondary" disabled={loading || saving} onClick={() => { setMessage(''); setRevision(r => r + 1) }}>重新加载偏好</Button>
        <Button type="button" variant="secondary" disabled={loading || saving || !settings} onClick={() => { setStyle('OMNIVORE'); setAvoid(''); setDisliked(''); setMessage('已清空表单，保存后生效。') }}>清空偏好</Button></div>
    </form>
  </section>
}
