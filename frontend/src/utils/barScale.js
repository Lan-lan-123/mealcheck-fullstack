export function numericValue(value) {
  if (!['number', 'string'].includes(typeof value) || (typeof value === 'string' && !value.trim())) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

export function scaleBars(items) {
  const values = items.map(item => numericValue(item.value))
  const valid = values.filter(value => value !== null)
  const min = Math.min(0, ...valid), max = Math.max(0, ...valid)
  const range = max - min || 1
  const baseline = -min / range * 100
  return values.map(value => ({
    value, baseline,
    left: value === null ? baseline : Math.min(baseline, (value - min) / range * 100),
    width: value === null ? 0 : Math.abs(value) / range * 100
  }))
}
