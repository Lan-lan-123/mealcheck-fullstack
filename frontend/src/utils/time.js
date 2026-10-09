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
