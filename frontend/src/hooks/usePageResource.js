// @ts-check
import { useCallback, useEffect, useRef, useState } from 'react'

// Each resource owns its cache, request version and error. Hidden pages do not fetch.
/**
 * @template T
 * @param {{key: string, enabled?: boolean, load: (signal: AbortSignal) => Promise<T>, initialData?: T | null, staleMs?: number}} options
 */
export default function usePageResource({ key, enabled = true, load, initialData = null, staleMs = 30_000 }) {
  /** @type {import('react').MutableRefObject<Map<string, {data: T, loadedAt: number}>>} */
  const cache = useRef(new Map())
  const version = useRef(0)
  /** @type {import('react').MutableRefObject<AbortController | null>} */
  const activeController = useRef(null)
  const loader = useRef(load)
  loader.current = load
  const fallback = useRef({ key, data: initialData })
  if (fallback.current.key !== key) fallback.current = { key, data: initialData }
  const fallbackData = fallback.current.data
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState({ key, data: initialData, loading: false, error: '' })

  const invalidate = useCallback(() => {
    version.current++
    activeController.current?.abort()
    cache.current.clear()
    setRevision(value => value + 1)
  }, [])

  const setData = useCallback(/** @param {T} data */ data => {
    version.current++
    activeController.current?.abort()
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
    const controller = new AbortController()
    activeController.current = controller
    const fetch = loader.current
    Promise.resolve().then(() => fetch(controller.signal)).then(data => {
      if (request !== version.current) return
      cache.current.set(key, { data, loadedAt: Date.now() })
      // Bound the session cache when users explore many filter combinations.
      if (cache.current.size > 20) {
        const oldestKey = cache.current.keys().next().value
        if (oldestKey !== undefined) cache.current.delete(oldestKey)
      }
      setState({ key, data, loading: false, error: '' })
    }).catch(error => {
      if (request === version.current) setState(current => ({ ...current, loading: false, error: error.message || '加载失败，请重试' }))
    })
    return () => {
      version.current++
      controller.abort()
      if (activeController.current === controller) activeController.current = null
    }
  }, [key, enabled, revision, staleMs])

  return {
    data: state.key === key ? state.data : cache.current.get(key)?.data ?? fallbackData,
    loading: enabled && (state.key !== key || state.loading),
    error: enabled && state.key === key ? state.error : '',
    invalidate,
    setData
  }
}
