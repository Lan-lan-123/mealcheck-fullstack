import { useEffect, useState } from 'react'
import { answerFeedback, getAnswerFeedback } from '../api/client'

export default function AnswerFeedback({ message }) {
  const [verdict, setVerdict] = useState('HELPFUL'), [category, setCategory] = useState('GENERAL'), [note, setNote] = useState('')
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false), [result, setResult] = useState('')
  const [loaded, setLoaded] = useState(false), [loading, setLoading] = useState(false)
  useEffect(() => {
    if (!open || loaded) return
    let live = true; setLoading(true)
    getAnswerFeedback(message.messageId).then(data => { if (live && data) { setVerdict(data.verdict); setCategory(data.category); setNote(data.note || ''); setResult('已提交反馈') } })
      .catch(() => {}).finally(() => { if (live) { setLoaded(true); setLoading(false) } })
    return () => { live = false }
  }, [message.messageId, open, loaded])
  async function submit(e) {
    e.preventDefault(); if (busy || loading) return; setBusy(true)
    try { await answerFeedback(message.messageId, { verdict, category, note }); setResult('已提交反馈'); setOpen(false) } catch (err) { setResult(err.message) } finally { setBusy(false) }
  }
  return <div className="answer-feedback"><p className="hint">请求 ID：{message.requestId || '历史回答未记录'}{message.deliveryStatus && message.deliveryStatus !== 'COMPLETE' && ` · ${message.deliveryStatus === 'CANCELLED' ? '已取消，内容未完成' : '服务异常，内容未完成'}`}</p>
    <button type="button" onClick={() => setOpen(!open)}>{open ? '收起反馈' : '评价这条回答'}</button>{result && <span role="status">{result}</span>}
    {open && <form className="journal-form" onSubmit={submit}>{loading && <p role="status">读取已有反馈…</p>}<label className="form-field">是否有帮助<select disabled={busy || loading} value={verdict} onChange={e => setVerdict(e.target.value)}><option value="HELPFUL">有帮助</option><option value="UNHELPFUL">无帮助</option></select></label>
      <label className="form-field">问题类型<select disabled={busy || loading} value={category} onChange={e => setCategory(e.target.value)}>{Object.entries({ GENERAL: '一般反馈', INCORRECT: '内容或识别错误', IRRELEVANT: '答非所问', UNCLEAR: '解释不清', SERVICE_ERROR: '服务异常' }).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <label className="form-field">问题说明<textarea disabled={busy || loading} value={note} maxLength={500} onChange={e => setNote(e.target.value)} /></label><button disabled={busy || loading}>提交回答反馈</button></form>}
  </div>
}
