// @ts-check
import { useState } from 'react'
import {
  adminAddKnowledgeChunk,
  adminDeleteKnowledgeChunk,
  adminUpdateKnowledgeChunk,
} from '../api/client'
import { defaultKnowledgeFilters } from '../pages/admin/adminWorkspaceConfig'

/** @param {any} result */
function getReindexMessage(result) {
  const count =
    typeof result === 'number' ? result : (result?.chunks ?? result?.count ?? result?.total ?? result?.indexedCount)
  return count === undefined || count === null ? 'RAG 知识库已重建' : `RAG 知识库已重建，共写入 ${count} 条知识片段`
}

/** @param {import("../api/contracts").AdminActions} context */
export default function useAdminKnowledge({ confirm, setError, setMessage, setLoading, refresh }) {
  const [knowledgeForm, setKnowledgeForm] = useState({ title: '', content: '' })

  const [knowledgeFiltersDraft, setKnowledgeFiltersDraft] = useState(defaultKnowledgeFilters)

  const [knowledgeFilters, setKnowledgeFilters] = useState(defaultKnowledgeFilters)

  const [knowledgePage, setKnowledgePage] = useState(0)

  const [knowledgeSize, setKnowledgeSize] = useState(8)

  const [editingChunkId, setEditingChunkId] = useState(/** @type {number | null} */ (null))

  const [editingChunkForm, setEditingChunkForm] = useState({ title: '', content: '', expectedRevision: 0, reason: '' })

  const [deletingChunkId, setDeletingChunkId] = useState(/** @type {number | null} */ (null))

  const [viewingChunk, setViewingChunk] = useState(
    /** @type {import("../api/contracts").KnowledgeChunk | null} */ (null),
  )

  /** @param {import("react").FormEvent} e */
  function applyKnowledgeFilters(e) {
    e.preventDefault()
    setKnowledgePage(0)
    setKnowledgeFilters({ ...knowledgeFiltersDraft })
  }

  /** @param {any} result */
  function handleReindex(result) {
    refresh(['knowledge', 'dashboard', 'health', 'audit'])
    if (result?.restored) {
      setMessage('历史正文与向量已恢复')
      return
    }
    if (result?.scope === 'SELECTED') {
      setMessage(`已更新选中的 ${result.totalChunks ?? 0} 条知识向量`)
      return
    }
    setMessage(getReindexMessage(result?.totalChunks ?? result))
  }

  /** @param {import("react").FormEvent} e */
  async function handleAddKnowledgeChunk(e) {
    e.preventDefault()
    setLoading(true)
    setMessage('')
    setError('')
    try {
      await adminAddKnowledgeChunk(knowledgeForm)
      setKnowledgeForm({ title: '', content: '' })
      setKnowledgePage(0)
      refresh(['knowledge', 'dashboard', 'health', 'audit'])
      setMessage('已新增 RAG 知识片段')
    } catch (err) {
      setError(err.message || '新增知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  /** @param {import("../api/contracts").KnowledgeChunk} chunk */
  function startEditChunk(chunk) {
    setEditingChunkId(chunk.id)
    setEditingChunkForm({ title: chunk.title || '', content: chunk.content || chunk.contentPreview || '', expectedRevision: chunk.contentRevision, reason: '' })
  }

  /** @param {import("react").FormEvent} e */
  async function handleUpdateKnowledgeChunk(e) {
    e.preventDefault()
    if (editingChunkId === null) return
    setLoading(true)
    setMessage('')
    setError('')
    try {
      await adminUpdateKnowledgeChunk(editingChunkId, editingChunkForm)
      setEditingChunkId(null)
      setEditingChunkForm({ title: '', content: '', expectedRevision: 0, reason: '' })
      refresh(['knowledge', 'dashboard', 'audit'])
      setMessage('已更新 RAG 知识片段')
    } catch (err) {
      setError(err.message || '更新知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  /** @param {import("../api/contracts").KnowledgeChunk} chunk */
  async function handleDeleteKnowledgeChunk(chunk) {
    const name = chunk.title || `知识片段 ${chunk.id}`
    if (!(await confirm(`确认删除 ${name}？`, { danger: true, confirmText: '确认删除' }))) return
    setDeletingChunkId(chunk.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteKnowledgeChunk(chunk.id, chunk.contentRevision)
      refresh(['knowledge', 'dashboard', 'health', 'audit'])
      setMessage('已删除 RAG 知识片段')
    } catch (err) {
      setError(err.message || '删除知识片段失败')
    } finally {
      setDeletingChunkId(null)
    }
  }
  return {
    knowledgeForm,
    setKnowledgeForm,
    knowledgeFiltersDraft,
    setKnowledgeFiltersDraft,
    knowledgeFilters,
    setKnowledgeFilters,
    knowledgePage,
    setKnowledgePage,
    knowledgeSize,
    setKnowledgeSize,
    editingChunkId,
    setEditingChunkId,
    editingChunkForm,
    setEditingChunkForm,
    deletingChunkId,
    setDeletingChunkId,
    viewingChunk,
    setViewingChunk,
    applyKnowledgeFilters,
    handleReindex,
    handleAddKnowledgeChunk,
    startEditChunk,
    handleUpdateKnowledgeChunk,
    handleDeleteKnowledgeChunk,
  }
}
