// @ts-check
/** @template [T=never] @param {number} [size] @returns {import('../api/contracts').Page<T>} */
export function emptyPage(size = 20) {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 }
}

/** @template T @param {import('../api/contracts').Page<T> | T[] | null | undefined} data @param {number} [size] @returns {import('../api/contracts').Page<T>} */
export function normalizePage(data, size = 20) {
  if (Array.isArray(data)) return { content: data, page: 0, size, totalElements: data.length, totalPages: data.length ? 1 : 0 }
  return data || emptyPage(size)
}

/** @template {object} T @param {T} filters @returns {Partial<T>} */
export function compactFilters(filters) {
  return /** @type {Partial<T>} */ (Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== undefined && value !== null && value !== '')))
}
