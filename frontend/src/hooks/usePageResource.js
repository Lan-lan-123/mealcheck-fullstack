import { useCallback, useEffect, useRef, useState } from 'react'

// Each resource owns its cache, request version and error. Hidden pages do not fetch.
export default function usePageResource({ key, enabled = true, load, initialData = null, staleMs = 30_000 }) {
  const cache = useRef(new Map())
  const version = useRef(0)
  const loader = useRef(load)
  loader.current = load
  const fallback = useRef({ key, data: initialData })
  if (fallback.current.key !== key) fallback.current = { key, data: initialData }
  const fallbackData = fallback.current.data
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState({ key, data: initialData, loading: false, error: '' })

  const invalidate = useCallback(() => {
    version.current++
    cache.current.clear()
    setRevision(value => value + 1)
  }, [])

  const setData = useCallback(data => {
    version.current++
    cache.current.set(key, { data, loadedAt: Date.now() })
    setState({ key, data, loading: false, error: '' })
  }, [key])

  useEffect(() => {
    if (!enabled) return
    const request = ++version.current
    const cached = cache.current.get(key)
    if (cached && Date.now() - cached.loadedAt < staleMs) {
      setState({ key, data: cached.data, loading: false, error: '' })
      return
    }
    setState(current => ({ key, data: current.key === key ? current.data : fallbackData, loading: true, error: '' }))
    const fetch = loader.current
    Promise.resolve().then(fetch).then(data => {
      if (request !== version.current) return
      cache.current.set(key, { data, loadedAt: Date.now() })
      // Bound the session cache when users explore many filter combinations.
      if (cache.current.size > 20) cache.current.delete(cache.current.keys().next().value)
      setState({ key, data, loading: false, error: '' })
    }).catch(error => {
      if (request === version.current) setState(current => ({ ...current, loading: false, error: error.message || '加载失败，请重试' }))
    })
    return () => { version.current++ }
  }, [key, enabled, revision, staleMs])

  return {
    data: state.key === key ? state.data : cache.current.get(key)?.data ?? fallbackData,
    loading: enabled && (state.key !== key || state.loading),
    error: enabled && state.key === key ? state.error : '',
    invalidate,
    setData
  }
}
