import { useEffect, useState } from 'react'
import { businessToday } from '../utils/time'

export default function useBusinessDay() {
  const [day, setDay] = useState(businessToday)
  useEffect(() => {
    const refresh = () => setDay(businessToday())
    const timer = setInterval(refresh, 30_000)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => { clearInterval(timer); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh) }
  }, [])
  return day
}
