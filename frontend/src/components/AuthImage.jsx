import { useEffect, useState } from 'react'
import { getToken } from '../api/client'

function buildApiUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path

  const base = import.meta.env.VITE_API_BASE || ''
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

export default function AuthImage({ url, alt }) {
  const [image, setImage] = useState({ url: '', src: '', status: 'empty' })
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!url) {
      setImage({ url, src: '', status: 'empty' })
      return
    }

    let objectUrl = ''
    let active = true
    const controller = new AbortController()
    const token = getToken()
    setImage({ url, src: '', status: 'loading' })

    fetch(buildApiUrl(url), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: controller.signal
    })
      .then(res => {
        if (!res.ok) throw new Error('图片加载失败')
        return res.blob()
      })
      .then(blob => {
        if (!active) return
        objectUrl = URL.createObjectURL(blob)
        setImage({ url, src: objectUrl, status: 'success' })
      })
      .catch(() => { if (active) setImage({ url, src: '', status: 'error' }) })

    return () => {
      active = false
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url, revision])

  if (!url) return <div className="photo-placeholder">暂无图片</div>
  if (image.url !== url || image.status === 'loading' || image.status === 'empty') {
    return <div className="photo-placeholder" role="status">图片加载中</div>
  }
  if (image.status === 'error') return <div className="photo-placeholder" role="status">
    <span>图片加载失败</span><button type="button" onClick={() => setRevision(value => value + 1)}>重新加载</button>
  </div>

  return <img src={image.src} alt={alt} onError={() => {
    URL.revokeObjectURL(image.src)
    setImage(current => current.src === image.src ? { url, src: '', status: 'error' } : current)
  }} />
}
