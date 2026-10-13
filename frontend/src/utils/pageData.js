export function emptyPage(size = 20) {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 }
}

export function normalizePage(data, size = 20) {
  if (Array.isArray(data)) return { content: data, page: 0, size, totalElements: data.length, totalPages: data.length ? 1 : 0 }
  return data || emptyPage(size)
}

export function compactFilters(filters) {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== undefined && value !== null && value !== ''))
}
