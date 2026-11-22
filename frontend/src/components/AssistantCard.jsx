// @ts-check
import Button from './Button'
import PlanSaveButton from "./PlanSaveButton"
import AnswerFeedback from "./AnswerFeedback"
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createRequestScope } from '../utils/requestScope'
import AiBudgetNotice from './AiBudgetNotice'
import TaskProgress from './TaskProgress'
import AssistantToolCalls from './AssistantToolCalls'
import { Bot, MessageCircle, Plus, Send, Trash2, X } from 'lucide-react'
import {
  streamDietAssistant,
  assistantConversation,
  assistantConversations,
  assistantProactiveAdvice,
  deleteAssistantConversation
} from '../api/client'

/** @type {import('../api/contracts').ChatMessage[]} */
const INITIAL_MESSAGES = [
  {
    role: 'assistant',
    text: '你好，我是饮食助手。可以问我食堂选择、下一餐搭配、减脂增肌吃法，或者一起复盘最近的饮食记录。'
  }
]

const QUICK_QUESTIONS = [
  '我这周吃得怎么样？',
  '今天食堂怎么选更均衡？',
  '晚餐在家做，预算25元，30分钟内准备好，推荐下一餐搭配。'
]

const RISK_LABELS = {
  LOW: '低风险',
  MEDIUM: '中等风险',
  HIGH: '较高风险'
}

/** @param {import('../api/contracts').AssistantResponse} data */
function buildAssistantText(data) {
  if (data?.summary || data?.suggestions?.length || data?.weeklyTrend) {
    const lines = []
    if (data.summary) lines.push(data.summary)
    if (data.weeklyTrend) lines.push(`本周趋势：${data.weeklyTrend}`)
    if (Array.isArray(data.suggestions) && data.suggestions.length) {
      lines.push(`建议：\n${data.suggestions.map(item => `- ${item}`).join('\n')}`)
    }
    return lines.join('\n\n')
  }
  return data?.answer || '我暂时没有生成有效回答，可以换个问法再试一次。'
}

/** @param {{onError?: (message: string) => void}} props */
export default function AssistantCard({ onError }) {
  const [open, setOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [reviewMode, setReviewMode] = useState(/** @type {'REVIEWED' | 'FAST'} */ ('REVIEWED'))
  const [messages, setMessages] = useState(INITIAL_MESSAGES)
  const [loading, setLoading] = useState(false)
  const [loadingTask, setLoadingTask] = useState(/** @type {{kind: 'reply' | 'history'; startedAt: number; status?: 'QUEUED' | 'RUNNING'} | null} */ (null))
  const [conversations, setConversations] = useState(/** @type {import('../api/contracts').ConversationSummary[]} */ ([]))
  const [conversationId, setConversationId] = useState(/** @type {number | null} */ (null))
  const [nextBefore, setNextBefore] = useState(/** @type {string | null} */ (null))
  const [loadingOlder, setLoadingOlder] = useState(false)
  const historyScope = useRef(createRequestScope())
  const olderRequest = useRef(/** @type {AbortController | null} */ (null))
  const messageViewport = useRef(/** @type {HTMLDivElement | null} */ (null))
  const prependScroll = useRef(/** @type {{height: number; top: number} | null} */ (null))
  const [proactiveItems, setProactiveItems] = useState(/** @type {string[]} */ ([]))
  const scope = useRef(createRequestScope())
  const activeRequest = useRef(/** @type {AbortController | null} */ (null))
  const sidebarScope = useRef(createRequestScope())
  useEffect(() => () => {
    scope.current.invalidate()
    sidebarScope.current.invalidate()
    historyScope.current.invalidate()
    olderRequest.current?.abort()
    activeRequest.current?.abort()
  }, [])

  useLayoutEffect(() => {
    if (prependScroll.current && messageViewport.current) {
      const { height, top } = prependScroll.current
      messageViewport.current.scrollTop = top + messageViewport.current.scrollHeight - height
      prependScroll.current = null
    }
  }, [messages])

  useLayoutEffect(() => {
    if (open && loading && messageViewport.current) messageViewport.current.scrollTop = messageViewport.current.scrollHeight
  }, [open, loading])

  const requestHistory = useMemo(
    () =>
      messages
        .filter(message => (message.role === 'user' || message.role === 'assistant') && message.reviewStatus !== 'NOT_REVIEWED' && (!message.answerStatus || message.answerStatus === 'COMPLETE') && (!message.deliveryStatus || message.deliveryStatus === 'COMPLETE'))
        .slice(-8)
        .map(message => ({ role: message.role, text: message.text.slice(0, 8000) })),
    [messages]
  )

  useEffect(() => {
    if (!open) return
    refreshSidebar().catch(err => onError?.(err.message))
  }, [open])

  async function refreshSidebar() {
    const current = sidebarScope.current.begin()
    const [sessions, proactive] = await Promise.allSettled([
      assistantConversations(),
      assistantProactiveAdvice()
    ])
    if (!current()) return
    if (sessions.status === 'fulfilled') setConversations(sessions.value || [])
    if (proactive.status === 'fulfilled') setProactiveItems(proactive.value?.items || [])
    const failure = [sessions, proactive].find(result => result.status === 'rejected')
    if (failure?.status === 'rejected') onError?.(failure.reason.message || '助手历史或建议暂时不可用，请重新打开后重试。')
  }

  /** @param {import('react').SyntheticEvent} [e] @param {string} [presetQuestion] */
  async function submit(e, presetQuestion) {
    e?.preventDefault()
    const text = (presetQuestion || question).trim()
    if (!text || loading) return
    const current = scope.current.begin()
    const controller = new AbortController()
    activeRequest.current = controller

    setLoading(true)
    setLoadingTask({ kind: 'reply', startedAt: Date.now() })
    setQuestion('')
    onError?.('')
    const streamKey = crypto.randomUUID()
    setMessages(current => [...current, { role: 'user', text }, { role: 'assistant', text: '', streamKey, deliveryStatus: 'STREAMING' }])

    try {
      const data = await streamDietAssistant(text, requestHistory, conversationId, controller.signal, (event, value) => {
        if (!current()) return
        if (event === "queued") setLoadingTask(task => task && ({ ...task, status: 'QUEUED' }))
        if (event === "meta") setLoadingTask(task => task && ({ ...task, status: 'RUNNING' }))
        if (event === "meta" && value.conversationId) setConversationId(value.conversationId)
        setMessages(items => items.map(item => item.streamKey !== streamKey ? item : { ...item, ...(value.requestId ? { requestId: value.requestId } : {}), text: event === "delta" ? item.text + (value.text || '') : item.text }))
      }, reviewMode)
      if (!current()) return
      if (data.conversationId) {
        setConversationId(data.conversationId)
      }
      setMessages(items => items.map(item => item.streamKey !== streamKey ? item : {
          ...item,
          messageId: data.messageId, requestId: data.requestId, deliveryStatus: data.deliveryStatus || "COMPLETE",
          reviewStatus: data.reviewStatus, answerStatus: data.answerStatus, answerReasonCodes: data.answerReasonCodes, failureCode: data.failureCode, upstreamStatus: data.upstreamStatus,
          role: 'assistant',
          text: buildAssistantText(data),
          summary: data.summary,
          suggestions: Array.isArray(data.suggestions) ? data.suggestions : [],
          weeklyTrend: data.weeklyTrend,
          riskLevel: data.riskLevel,
          references: Array.isArray(data.references) ? data.references : [],
          toolCalls: data.toolCalls || []
        }))
      setLoading(false)
      await refreshSidebar()
    } catch (err) {
      if (!current()) return
      if (err.name === 'AbortError') {
        setMessages(items => items.map(item => item.streamKey !== streamKey ? item : { ...item, text: item.text || '回复已取消', deliveryStatus: 'CANCELLED' }))
        return
      }
      const message = err.message || '饮食助手暂时不可用，请稍后再试。'
      setMessages(items => items.map(item => item.streamKey !== streamKey ? item : { ...item, text: item.text ? `${item.text}\n\n${message}` : message, requestId: err.requestId || item.requestId, deliveryStatus: 'ERROR' }))
      onError?.(message)
    } finally {
      if (current()) {
        setLoading(false)
        activeRequest.current = null
      }
    }
  }

  /** @param {number} id */
  async function openConversation(id) {
    resetHistoryPage()
    activeRequest.current?.abort()
    const current = scope.current.begin()
    setLoading(true)
    setLoadingTask({ kind: 'history', startedAt: Date.now() })
    onError?.('')
    try {
      const data = await assistantConversation(id)
      if (!current()) return
      setConversationId(data.id)
      setMessages(data.messages?.length ? data.messages : INITIAL_MESSAGES)
      setNextBefore(data.hasMore ? data.nextBefore : null)
    } catch (err) {
      if (current()) onError?.(err.message)
    } finally { if (current()) setLoading(false) }
  }

  function resetHistoryPage() {
    historyScope.current.invalidate()
    olderRequest.current?.abort()
    olderRequest.current = null
    prependScroll.current = null
    setNextBefore(null)
    setLoadingOlder(false)
  }

  async function loadOlderMessages() {
    if (!conversationId || !nextBefore || loadingOlder || loading) return
    const current = historyScope.current.begin()
    const controller = new AbortController()
    olderRequest.current = controller
    setLoadingOlder(true)
    onError?.('')
    try {
      const data = await assistantConversation(conversationId, { before: nextBefore, signal: controller.signal })
      if (!current()) return
      if (messageViewport.current) prependScroll.current = {
        height: messageViewport.current.scrollHeight, top: messageViewport.current.scrollTop
      }
      setMessages(messages => [...(data.messages || []), ...messages])
      setNextBefore(data.hasMore ? data.nextBefore : null)
    } catch (err) {
      if (current() && err.name !== 'AbortError') onError?.(err.message)
    } finally {
      if (current()) { setLoadingOlder(false); olderRequest.current = null }
    }
  }

  /** @param {number} id */
  async function removeConversation(id) {
    if (conversationId === id) startNewConversation()
    try {
      await deleteAssistantConversation(id)
      await refreshSidebar()
    } catch (err) { onError?.(err.message) }
  }

  function startNewConversation() {
    resetHistoryPage()
    scope.current.invalidate()
    activeRequest.current?.abort()
    activeRequest.current = null
    setLoading(false)
    setConversationId(null)
    setMessages(INITIAL_MESSAGES)
  }

  return (
    <div className={`assistant-widget ${open ? 'open' : ''}`}>
      {open && (
        <section className="assistant-panel" aria-label="饮食助手聊天窗口">
          <header className="assistant-panel-head">
            <div>
              <Bot size={20} />
              <strong>饮食助手</strong>
            </div>
            <Button variant="plain" type="button" onClick={() => setOpen(false)} aria-label="关闭饮食助手">
              <X size={20} />
            </Button>
          </header>

          <div className="assistant-workspace">
            <aside className="assistant-sidebar">
              <Button variant="plain" className="assistant-new-btn" type="button" onClick={startNewConversation}>
                <Plus size={16} />
                新对话
              </Button>
              <div className="assistant-session-list">
                {conversations.map(item => (
                  <div className={conversationId === item.id ? 'active' : ''} key={item.id}>
                    <Button variant="plain" type="button" onClick={() => openConversation(item.id)}>{item.title}</Button>
                    <Button variant="plain" type="button" onClick={() => removeConversation(item.id)} aria-label="删除会话">
                      <Trash2 size={14} />
                    </Button>
                  </div>
                ))}
              </div>
            </aside>

            <div className="assistant-main">
              <div className="assistant-messages" ref={messageViewport}>
                <p className="hint">复核回答会检查结论与依据，通常需要更久。快速回答不做额外模型复核。</p>
                <AiBudgetNotice revision={messages.length} />
                {!!proactiveItems.length && (
                  <div className="assistant-proactive">
                    <strong>主动建议</strong>
                    {proactiveItems.slice(0, 4).map(item => <span key={item}>{item}</span>)}
                  </div>
                )}
                {nextBefore && <Button variant="plain" type="button" onClick={loadOlderMessages} disabled={loadingOlder || loading}>
                  {loadingOlder ? '正在加载历史消息…' : '加载更早的消息'}
                </Button>}
                {messages.map((message, index) => (
                  <div className={`assistant-message ${message.role}`} key={`${conversationId ?? 'new'}:${message.role}:${message.messageId ?? message.streamKey ?? message.requestId ?? index}`}>
                    {message.riskLevel && message.reviewStatus !== 'NOT_REVIEWED' && (
                      <span className={`assistant-risk ${String(message.riskLevel).toLowerCase()}`}>
                        {RISK_LABELS[message.riskLevel] || message.riskLevel}
                      </span>
                    )}
                    <div>{message.text || "正在等待回复…"}</div>
                    {message.role === 'assistant' && message.reviewStatus === 'NOT_REVIEWED' && <p className="hint" role="status">快速回答：未经过模型证据复核，重要结论请核对参考依据。</p>}
                    {message.role === 'assistant' && message.reviewStatus === 'VERIFIED' && <p className="hint">已完成模型证据复核</p>}
                    {message.role === 'assistant' && (message.answerStatus === 'DEGRADED' || message.deliveryStatus === 'ERROR') && <p className="hint" role="status">本次回复未完成全部核验，请根据提示处理后再试。</p>}
                    {message.role === 'assistant' && message.answerStatus === 'PARTIAL' && <p className="hint" role="status">本次仅给出有依据的部分内容，具体限制见回答中的说明。</p>}
                    {message.role === 'assistant' && <AssistantToolCalls calls={message.toolCalls} />}
                    {message.role === "assistant" && message.messageId && <><AnswerFeedback key={message.messageId} message={message} />{(!message.answerStatus || message.answerStatus === 'COMPLETE') && (!message.deliveryStatus || message.deliveryStatus === "COMPLETE") && <PlanSaveButton key={`plan:${message.messageId}`} text={message.text} sourceType="ASSISTANT" sourceMessageId={message.messageId} />}</>}
                    {message.role === "assistant" && !message.messageId && message.requestId && <p className="hint">请求 ID：{message.requestId} · {message.deliveryStatus === "CANCELLED" ? "已取消，内容未完成" : message.deliveryStatus === "ERROR" ? "回复未完成" : "正在生成"}</p>}
                    {!!message.references?.length && (
                      <div className="assistant-reference-list">
                        <span>参考依据</span>
                        {message.references?.map(reference => (
                          <details key={reference.id || reference.title}>
                            <summary>{reference.title || '知识片段'}</summary>
                            <p>{reference.content}</p>
                          </details>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
                {loading && <div className="assistant-message assistant assistant-progress">
                  <TaskProgress label="饮食助手进度" startedAt={loadingTask?.startedAt}
                    title={loadingTask?.kind === 'history' ? '正在加载历史对话' : loadingTask?.status === 'QUEUED' ? '正在排队等待回复' : reviewMode === 'FAST' ? '正在生成快速回答' : '正在生成并复核回答'}
                    description={loadingTask?.kind === 'history' ? '正在读取这段对话的消息。' : loadingTask?.status === 'QUEUED' ? '排队时间计入回复期限，可以随时取消等待。' : '正在结合你的问题与饮食信息生成回复。'}
                    longWaitMessage="等待时间较长，请稍候；关闭聊天窗口后仍可返回查看。" />
                </div>}
                <div className="assistant-quick-list">
                  {QUICK_QUESTIONS.map(item => (
                    <Button variant="plain" key={item} type="button" onClick={e => submit(e, item)} disabled={loading}>
                      {item}
                    </Button>
                  ))}
                </div>
              </div>

              {loading && loadingTask?.kind === "reply" && <Button variant="secondary" type="button" className="secondary-btn" onClick={() => activeRequest.current?.abort()}>取消等待</Button>}
              <label className={`assistant-review-choice${loading ? ' is-busy' : ''}`}>回答方式
                <select aria-label="回答方式" value={reviewMode} disabled={loading} onChange={e => setReviewMode(e.target.value === 'FAST' ? 'FAST' : 'REVIEWED')}>
                  <option value="REVIEWED">复核回答（推荐）</option>
                  <option value="FAST">快速回答（不复核）</option>
                </select>
              </label>
              <form className="assistant-chat-form" onSubmit={submit}>
                <input
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  placeholder="问问怎么吃更合适"
                />
                <Button variant="plain" type="submit" disabled={loading || !question.trim()} aria-label="发送问题">
                  <Send size={18} />
                </Button>
              </form>
            </div>
          </div>
        </section>
      )}

      <Button variant="plain" className="assistant-fab" type="button" onClick={() => setOpen(value => !value)}>
        {open ? <X size={24} /> : <MessageCircle size={24} />}
      </Button>
    </div>
  )
}
