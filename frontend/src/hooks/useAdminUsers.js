// @ts-check
import { useRef, useState } from 'react'
import { adminDeleteUser, adminSetUserEnabled } from '../api/client'
import { defaultUserFilters } from '../pages/admin/adminWorkspaceConfig'

/** @param {import("../api/contracts").AdminActions} context */
export default function useAdminUsers({ confirm, setError, setMessage, setLoading, refresh }) {
  const [userPage, setUserPage] = useState(0)

  const [userSize, setUserSize] = useState(20)

  const [userFiltersDraft, setUserFiltersDraft] = useState(defaultUserFilters)

  const [userFilters, setUserFilters] = useState(defaultUserFilters)

  const [deletingUserId, setDeletingUserId] = useState(/** @type {number | null} */ (null))
  const [updatingUserId, setUpdatingUserId] = useState(/** @type {number | null} */ (null))
  const statusPending = useRef(false)

  /** @param {import("react").FormEvent} e */
  function applyUserFilters(e) {
    e.preventDefault()
    setUserPage(0)
    setUserFilters({ ...userFiltersDraft })
  }

  /** @param {import("../api/contracts").AdminUser} user */
  async function handleDeleteUser(user) {
    if (
      !(await confirm(`确认删除用户 ${user.username}？该用户的饮食记录和图片也会删除。`, {
        danger: true,
        confirmText: '确认删除',
      }))
    )
      return
    setDeletingUserId(user.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteUser(user.id)
      refresh(['users', 'meals', 'analytics', 'nonfood', 'dashboard', 'audit'])
      setMessage(`已删除用户 ${user.username}`)
    } catch (err) {
      setError(err.message || '删除用户失败')
    } finally {
      setDeletingUserId(null)
    }
  }
  /** @param {import('../api/contracts').AdminUser} user */
  async function handleSetUserEnabled(user) {
    if (statusPending.current || deletingUserId !== null) return
    statusPending.current = true
    setUpdatingUserId(user.id)
    const enabled = user.enabled === false
    const action = enabled ? '解封' : '封禁'
    try {
      if (!(await confirm(enabled
        ? `确认解封用户 ${user.username}？解封后需重新登录。`
        : `确认封禁用户 ${user.username}？该用户将无法登录，现有登录凭证也会失效。`,
      { danger: !enabled, confirmText: `确认${action}` }))) return
      setMessage('')
      setError('')
      await adminSetUserEnabled(user.id, enabled)
      refresh(['users', 'audit'])
      setMessage(`已${action}用户 ${user.username}`)
    } catch (err) {
      setError(err.message || `${action}用户失败`)
    } finally {
      statusPending.current = false
      setUpdatingUserId(null)
    }
  }

  return {
    userPage,
    setUserPage,
    userSize,
    setUserSize,
    userFiltersDraft,
    setUserFiltersDraft,
    userFilters,
    setUserFilters,
    deletingUserId,
    setDeletingUserId,
    applyUserFilters,
    handleDeleteUser,
    updatingUserId,
    handleSetUserEnabled,
  }
}
