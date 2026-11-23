// @ts-check
import Button from './Button'
import { useEffect, useRef, useState } from 'react'
import { mealDetail } from '../api/client'
import DetailDrawer from './DetailDrawer'

/** @param {{id: number; onLoaded: (record: import('../api/contracts').MealRecord) => void; onClose: () => void}} props */
export default function MealRecordLoader({ id, onLoaded, onClose }) {
  const callback = useRef(onLoaded)
  callback.current = onLoaded
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setError('')
    mealDetail(id, controller.signal).then(record => {
      if (!controller.signal.aborted) callback.current(record)
    }).catch(error => {
      if (!controller.signal.aborted) setError(error.message || '详情加载失败，请重试')
    })
    return () => controller.abort()
  }, [id, revision])

  return <DetailDrawer title="正在读取餐食记录" onClose={onClose}>
    {error ? <div role="alert"><p className="error">{error}</p>
      <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setRevision(value => value + 1)}>重新加载详情</Button>
    </div> : <p role="status">正在加载完整餐食记录…</p>}
  </DetailDrawer>
}
