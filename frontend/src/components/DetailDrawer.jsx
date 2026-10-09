import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

// Native modal dialogs keep background controls inert and handle keyboard focus.
export default function DetailDrawer({ title, onClose, children, returnFocus }) {
  const dialog = useRef(null)
  const titleId = useId()
  useEffect(() => {
    const element = dialog.current
    const previous = returnFocus || document.activeElement
    const overflow = document.body.style.overflow
    element.showModal()
    document.body.style.overflow = 'hidden'
    const keydown = event => {
      if (event.key !== 'Tab') return
      const controls = [...element.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]')].filter(control => control.getClientRects().length)
      const first = controls[0], last = controls.at(-1)
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
    }
    element.addEventListener('keydown', keydown)
    return () => {
      element.removeEventListener('keydown', keydown)
      element.close()
      document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus({ preventScroll: true })
    }
  }, [])
  return createPortal(
    <dialog ref={dialog} className="detail-drawer workspace" aria-labelledby={titleId}
      onCancel={event => { event.preventDefault(); onClose() }}
      onClick={event => { if (event.target === dialog.current && event.clientX < dialog.current.getBoundingClientRect().left) onClose() }}>
      <header className="detail-drawer-head"><h2 id={titleId}>{title}</h2><button autoFocus className="icon-button" type="button" onClick={onClose} aria-label="关闭详情"><X size={20} /></button></header>
      <div className="detail-drawer-body" tabIndex={0} role="region" aria-label={`${title}内容`}>{children}</div>
    </dialog>, document.body
  )
}
