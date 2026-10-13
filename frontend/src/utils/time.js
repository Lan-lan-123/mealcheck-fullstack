const BUSINESS_ZONE = import.meta.env?.VITE_BUSINESS_ZONE || 'Asia/Shanghai'
const LEGACY_OFFSET = import.meta.env?.VITE_LEGACY_TIMESTAMP_OFFSET || 'Z'

export function formatTime(value) {
  if (!value) return '-'
  // Existing snapshots may contain timestamps without offsets. Do not interpret
  // them in the browser's timezone. Date-only business fields stay date-only.
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  const text = String(value)
  const explicit = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}${LEGACY_OFFSET}`
  const date = new Date(explicit)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { timeZone: BUSINESS_ZONE, hour12: false })
}

export function businessDateTime(value = new Date().toISOString()) {
  const text = String(value)
  const date = new Date(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}${LEGACY_OFFSET}`)
  if (Number.isNaN(date.getTime())) return ''
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(date).map(p => [p.type, p.value]))
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}
export function businessToday() { return businessDateTime().slice(0, 10) }
export const mealTypes = { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐', SNACK: '加餐', UNSPECIFIED: '未指定餐次' }
