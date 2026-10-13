import { useEffect, useRef } from 'react'

export default function useDialogFocus(ref, close, busy) {
  const latest = useRef({ close, busy })
  latest.current = { close, busy }
  useEffect(() => {
    const prior = document.activeElement
    const dialog = ref.current
    const focusable = () => [...dialog.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]')]
    focusable()[0]?.focus()
    const key = event => {
      if (event.key === 'Escape' && !latest.current.busy) { event.preventDefault(); latest.current.close() }
      if (event.key === 'Tab') {
        const items = focusable(), first = items[0], last = items.at(-1)
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
      }
    }
    dialog.addEventListener('keydown', key)
    return () => { dialog.removeEventListener('keydown', key); if (prior?.isConnected) prior.focus() }
  }, [ref])
}
