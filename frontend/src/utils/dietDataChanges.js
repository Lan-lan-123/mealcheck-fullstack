const EVENT = 'mealcheck:diet-data-changed'
export function publishDietChange(kind) {
  window.dispatchEvent(new CustomEvent(EVENT, { detail: { kind } }))
}
export function subscribeDietChanges(listener) {
  const handler = event => listener(event.detail.kind)
  window.addEventListener(EVENT, handler)
  return () => window.removeEventListener(EVENT, handler)
}
