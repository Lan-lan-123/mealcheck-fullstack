// @ts-check
import { useRef, useState } from 'react'
import { adminDeleteMeal } from '../api/client'
import { defaultMealFilters } from '../pages/admin/adminWorkspaceConfig'

/** @param {import("../api/contracts").AdminActions & {getMeals: () => import("../api/contracts").AdminMeal[]}} context */
export default function useAdminMeals({ confirm, setError, setMessage, setLoading, refresh, getMeals }) {
  const [showMealManager, setShowMealManager] = useState(false)

  const [selectedMealIds, setSelectedMealIds] = useState(/** @type {number[]} */ ([]))
  const bulkDeleteInFlight = useRef(false)

  const [deletingMealId, setDeletingMealId] = useState(/** @type {number | null} */ (null))

  const [mealPage, setMealPage] = useState(0)

  const [mealSize, setMealSize] = useState(20)

  const [mealFiltersDraft, setMealFiltersDraft] = useState(defaultMealFilters)

  const [mealFilters, setMealFilters] = useState(defaultMealFilters)

  /** @param {import("react").FormEvent} e */
  function applyMealFilters(e) {
    e.preventDefault()
    setMealPage(0)
    setMealFilters({ ...mealFiltersDraft })
  }

  /** @param {import("../api/contracts").AdminMeal} meal */
  async function handleDeleteMeal(meal) {
    if (!(await confirm(`确认删除饮食记录 #${meal.id}？相关图片也会删除。`, { danger: true, confirmText: '确认删除' })))
      return
    setDeletingMealId(meal.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteMeal(meal.id)
      refresh(['meals', 'analytics', 'dashboard', 'audit'])
      setMessage(`已删除饮食记录 #${meal.id}`)
    } catch (err) {
      setError(err.message || '删除饮食记录失败')
    } finally {
      setDeletingMealId(null)
    }
  }

  async function handleDeleteSelectedMeals() {
    if (!selectedMealIds.length || bulkDeleteInFlight.current) return
    const ids = [...selectedMealIds]
    bulkDeleteInFlight.current = true
    let started = false
    try {
      if (!(await confirm(`确认删除选中的 ${ids.length} 条饮食记录？`, {
        danger: true,
        confirmText: '确认删除',
      }))) return
      started = true
      setLoading(true)
      setMessage('')
      setError('')
      const results = await Promise.allSettled(ids.map((id) => adminDeleteMeal(id)))
      const succeeded = new Set(ids.filter((_, index) => results[index].status === 'fulfilled'))
      const failed = ids.filter((id) => !succeeded.has(id))
      setSelectedMealIds((selected) => selected.filter((id) => !succeeded.has(id)))
      // A rejected request can also have committed; always reload authoritative data.
      refresh(['meals', 'analytics', 'dashboard', 'audit'])
      setMessage(failed.length ? `已删除 ${succeeded.size} 条饮食记录，${failed.length} 条删除失败。` : `已删除 ${succeeded.size} 条饮食记录`)
      if (failed.length) {
        const rejection = results.find((result) => result.status === 'rejected')
        const reason = rejection?.status === 'rejected' ? rejection.reason?.message : ''
        setError(`未删除记录：${failed.map((id) => `#${id}`).join('、')}。${reason || '删除请求失败'}；请核对刷新后的列表，再重试仍选中的记录。`)
      }
    } catch (err) {
      setError(err.message || '批量删除失败')
    } finally {
      bulkDeleteInFlight.current = false
      if (started) setLoading(false)
    }
  }

  /** @param {number} id */
  function toggleMealSelection(id) {
    setSelectedMealIds((ids) => (ids.includes(id) ? ids.filter((item) => item !== id) : [...ids, id]))
  }

  function toggleAllMealSelection() {
    setSelectedMealIds(selectedMealIds.length === getMeals().length ? [] : getMeals().map((meal) => meal.id))
  }
  return {
    showMealManager,
    setShowMealManager,
    selectedMealIds,
    setSelectedMealIds,
    deletingMealId,
    setDeletingMealId,
    mealPage,
    setMealPage,
    mealSize,
    setMealSize,
    mealFiltersDraft,
    setMealFiltersDraft,
    mealFilters,
    setMealFilters,
    applyMealFilters,
    handleDeleteMeal,
    handleDeleteSelectedMeals,
    toggleMealSelection,
    toggleAllMealSelection,
  }
}
