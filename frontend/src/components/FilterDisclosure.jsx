import { useEffect, useState } from 'react'

export default function FilterDisclosure({ children, activeCount = 0 }) {
  const [open, setOpen] = useState(() => !window.matchMedia('(max-width: 760px)').matches)
  useEffect(() => {
    const query = window.matchMedia('(max-width: 760px)')
    const update = () => setOpen(!query.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return <details className="filter-disclosure" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>筛选条件{activeCount > 0 && <span>已填写 {activeCount} 项</span>}</summary>
    {children}
  </details>
}
