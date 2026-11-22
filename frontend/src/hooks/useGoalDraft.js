// @ts-check
import { useEffect, useRef, useState } from 'react'

const emptyDraft = { goalType: 'balanced', endDate: '', note: '' }
/** @param {import('../api/contracts').DietGoal | null | undefined} goal @returns {import('../api/contracts').GoalDraft} */
const toDraft = goal => ({ goalType: goal?.goalType || 'balanced', endDate: goal?.endDate || '', note: goal?.note || '' })

/** @param {import('../api/contracts').DietGoal | null} profile */
export default function useGoalDraft(profile) {
  const [draft, setDraft] = useState(emptyDraft)
  const dirty = useRef(false)
  const version = useRef(0)
  useEffect(() => { if (!dirty.current && profile) setDraft(toDraft(profile)) }, [profile])
  /** @param {import('../api/contracts').GoalDraft} next */
  function edit(next) { dirty.current = true; version.current++; setDraft(next) }
  /** @param {import('../api/contracts').DietGoal} profile @param {number} savedVersion */
  function committed(profile, savedVersion) {
    if (version.current !== savedVersion) return
    dirty.current = false
    setDraft(toDraft(profile))
  }
  return { draft, edit, committed, version }
}
