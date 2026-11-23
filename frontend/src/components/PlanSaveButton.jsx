import Button from './Button'
import { useState } from 'react'
import { saveActionPlan } from '../api/client'
import { businessToday } from '../utils/time'

/** @param {{text: string; sourceType?: 'MANUAL' | 'MEAL' | 'ASSISTANT' | 'REPORT'; sourceRecordId?: number; sourceMessageId?: number; sourcePeriodStart?: string; sourcePeriodEnd?: string; singleUse?: boolean; initialTitle?: string; initialDate?: string; alreadySaved?: boolean; onSaved?: () => void}} props */
export default function PlanSaveButton({ text, sourceType = 'MANUAL', sourceRecordId, sourceMessageId, sourcePeriodStart, sourcePeriodEnd, singleUse = false, initialTitle = '饮食行动计划', initialDate, alreadySaved = false, onSaved }) {
  const manual = sourceType === 'MANUAL' && !singleUse
  const [open, setOpen] = useState(false), [saved, setSaved] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [actionText, setAction] = useState((text || '').slice(0, 2000)), [title, setTitle] = useState(initialTitle), [date, setDate] = useState(initialDate || businessToday())
  async function submit(e) {
    e.preventDefault(); if (busy || ((saved || alreadySaved) && !manual)) return; setBusy(true); setError('')
    try {
      await saveActionPlan({ title, actionText, sourceType, sourceRecordId, sourceMessageId, sourcePeriodStart, sourcePeriodEnd, plannedDate: date })
      setSaved(true); setOpen(false); onSaved?.()
      if (manual) { setAction(''); setTitle('饮食行动计划'); setDate(businessToday()) }
    }
    catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  if (!text) return null
  return <div className="plan-save"><Button variant="secondary" className="secondary-btn" type="button" disabled={(saved || alreadySaved) && !manual} onClick={() => setOpen(!open)}>{open ? '收起计划编辑' : saved || alreadySaved ? manual ? '继续新增计划' : '已加入计划' : '加入计划'}</Button>
    {open && <form className="journal-form" onSubmit={submit}><label className="form-field">计划标题<input required maxLength={120} value={title} onChange={e => setTitle(e.target.value)} disabled={busy} /></label>
      <label className="form-field">具体行动<textarea required maxLength={2000} value={actionText} onChange={e => setAction(e.target.value)} disabled={busy} /></label>
      {(text || '').length > 2000 && <p className="hint">建议较长，已截取前 2000 字，请调整为可执行的行动。</p>}
      <label className="form-field">计划日期<input type="date" required min="2000-01-01" value={date} onChange={e => setDate(e.target.value)} disabled={busy} /></label>
      {error && <p className="error" role="alert">{error}</p>}<Button variant="primary" type="submit" className="primary" disabled={busy}>{busy ? '保存中…' : '保存行动计划'}</Button></form>}
  </div>
}
