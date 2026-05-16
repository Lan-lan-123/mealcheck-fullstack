import { useEffect, useState } from 'react'
import { getToken } from '../api/client'

function buildApiUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path

  const base = import.meta.env.VITE_API_BASE || ''
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

export default function AuthImage({ url, alt }) {
  const [src, setSrc] = useState('')

  useEffect(() => {
    if (!url) {
      setSrc('')
      return
    }

    let objectUrl = ''
    const token = getToken()

    fetch(buildApiUrl(url), {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
      .then(res => {
        if (!res.ok) throw new Error('图片加载失败')
        return res.blob()
      })
      .then(blob => {
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => setSrc(''))

    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url])

  if (!src) {
    return <div className="photo-placeholder">图片加载中</div>
  }

  return <img src={src} alt={alt} />
}
