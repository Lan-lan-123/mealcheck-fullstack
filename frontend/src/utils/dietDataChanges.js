// @ts-check
const EVENT = 'mealcheck:diet-data-changed'
/** @param {string} kind */
export function publishDietChange(kind) {
  window.dispatchEvent(new CustomEvent(EVENT, { detail: { kind } }))
}
/** @param {(kind: string) => void} listener */
export function subscribeDietChanges(listener) {
  /** @param {Event} event */
  const handler = event => listener(/** @type {CustomEvent<{kind: string}>} */ (event).detail.kind)
  window.addEventListener(EVENT, handler)
  return () => window.removeEventListener(EVENT, handler)
}
