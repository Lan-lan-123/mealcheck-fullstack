import { useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import Button from '../../src/components/Button'
import '../../src/styles/index.css'

function Harness() {
  const [clicks, setClicks] = useState(0), [submits, setSubmits] = useState(0), [busy, setBusy] = useState(false)
  const target = useRef(null)
  return <main className="workspace"><form onSubmit={event => { event.preventDefault(); setSubmits(value => value + 1) }}>
    <Button ref={target} onClick={() => setClicks(value => value + 1)}>普通操作</Button>
    <Button variant="primary" type="submit">提交表单</Button>
    <Button variant="primary" busy={busy} loadingText="保存中" onClick={() => { setClicks(value => value + 1); setBusy(true) }}>保存一次</Button>
    <Button disabled onClick={() => setClicks(value => value + 1)}>不可操作</Button>
    <Button variant="plain" aria-label="聚焦普通操作" onClick={() => target.current?.focus()}>聚焦</Button>
    <p role="status">点击 {clicks} 次，提交 {submits} 次</p>
  </form></main>
}
createRoot(document.getElementById('root')).render(<Harness />)
