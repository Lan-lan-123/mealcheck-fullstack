import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

const DialogContext = createContext(null)

export function DialogProvider({ children }) {
  const [request, setRequest] = useState(null)
  const pending = useRef([])
  const current = useRef(null)
  const sequence = useRef(0)
  const showNext = useCallback(() => {
    if (current.current || !pending.current.length) return
    current.current = pending.current.shift()
    setRequest(current.current)
  }, [])
  const ask = useCallback((kind, message, options = {}) => new Promise(resolve => {
    pending.current.push({ kind, message, ...options, resolve, id: ++sequence.current })
    showNext()
  }), [showNext])
  const finish = useCallback(value => {
    const resolved = current.current
    if (!resolved) return
    current.current = null
    setRequest(null)
    resolved.resolve(value)
    queueMicrotask(showNext)
  }, [showNext])
  useEffect(() => () => {
    current.current?.resolve(null)
    pending.current.forEach(item => item.resolve(null))
  }, [])
  const value = useMemo(() => ({
    confirm: (message, options) => ask('confirm', message, options),
    prompt: (message, options) => ask('prompt', message, options),
    notify: (message, options) => ask('notify', message, options)
  }), [ask])
  return <DialogContext.Provider value={value}>{children}{request && createPortal(<AppDialog key={request.id} request={request} finish={finish} />, document.body)}</DialogContext.Provider>
}

export function useDialog() { return useContext(DialogContext) }

function AppDialog({ request, finish }) {
  const ref = useRef(null)
  const [answer, setAnswer] = useState(request.defaultValue || '')
  const title = request.title || (request.kind === 'prompt' ? '填写信息' : request.kind === 'notify' ? '操作提示' : '确认操作')
  useEffect(() => {
    const element = ref.current
    const previousFocus = document.activeElement
    element.showModal()
    element.querySelector(request.kind === 'prompt' ? 'input, textarea' : '[data-initial-focus]')?.focus()
    return () => { element.close(); if (previousFocus?.isConnected) previousFocus.focus() }
  }, [request.kind])
  const cancel = () => finish(request.kind === 'confirm' ? false : null)
  return <dialog ref={ref} className="app-dialog" aria-labelledby="app-dialog-title" aria-describedby="app-dialog-message" onCancel={event => { event.preventDefault(); cancel() }}>
    <form onSubmit={event => { event.preventDefault(); finish(request.kind === 'prompt' ? (request.inputType === 'password' ? answer : answer.trim()) : true) }}>
      <h2 id="app-dialog-title">{title}</h2>
      <p id="app-dialog-message">{request.message}</p>
      {request.kind === 'prompt' && <label className="form-field">{request.inputLabel || '内容'}{request.multiline
        ? <textarea required={request.required !== false} value={answer} onChange={event => setAnswer(event.target.value)} />
        : <input type={request.inputType || 'text'} autoComplete={request.inputType === 'password' ? 'current-password' : 'off'} required={request.required !== false} value={answer} onChange={event => setAnswer(event.target.value)} />}</label>}
      <div className="app-dialog-actions">
        {request.kind !== 'notify' && <button className="secondary-btn" type="button" data-initial-focus onClick={cancel}>取消</button>}
        <button className={request.danger ? 'danger-btn' : 'secondary-btn'} type="submit" data-initial-focus={request.kind === 'notify' ? '' : undefined}>{request.confirmText || (request.kind === 'notify' ? '知道了' : '确认')}</button>
      </div>
    </form>
  </dialog>
}
