import { useEffect, useRef, useState } from 'react'

const emptyDraft = { goalType: 'balanced', endDate: '', note: '' }
const toDraft = goal => ({ goalType: goal?.goalType || 'balanced', endDate: goal?.endDate || '', note: goal?.note || '' })

export default function useGoalDraft(profile) {
  const [draft, setDraft] = useState(emptyDraft)
  const dirty = useRef(false)
  const version = useRef(0)
  useEffect(() => { if (!dirty.current && profile) setDraft(toDraft(profile)) }, [profile])
  function edit(next) { dirty.current = true; version.current++; setDraft(next) }
  function committed(profile, savedVersion) {
    if (version.current !== savedVersion) return
    dirty.current = false
    setDraft(toDraft(profile))
  }
  return { draft, edit, committed, version }
}
