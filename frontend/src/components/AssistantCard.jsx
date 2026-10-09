import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createRequestScope } from '../utils/requestScope'
import AiBudgetNotice from './AiBudgetNotice'
import { Bot, MessageCircle, Plus, Send, Trash2, X } from 'lucide-react'
import {
  askDietAssistant,
  assistantConversation,
  assistantConversations,
  assistantProactiveAdvice,
  deleteAssistantConversation
} from '../api/client'

const INITIAL_MESSAGES = [
  {
    role: 'assistant',
    text: '你好，我是饮食助手。可以问我食堂选择、下一餐搭配、减脂增肌吃法，或者一起复盘最近的饮食记录。'
  }
]

const QUICK_QUESTIONS = [
  '我这周吃得怎么样？',
  '今天食堂怎么选更均衡？',
  '明天更适合怎么吃？'
]

const RISK_LABELS = {
  LOW: '低风险',
  MEDIUM: '中等风险',
  HIGH: '较高风险'
}

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

export default function AssistantCard({ onError }) {
  const [open, setOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState(INITIAL_MESSAGES)
  const [loading, setLoading] = useState(false)
  const [conversations, setConversations] = useState([])
  const [conversationId, setConversationId] = useState(null)
  const [nextBefore, setNextBefore] = useState(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const historyScope = useRef(createRequestScope())
  const olderRequest = useRef(null)
  const messageViewport = useRef(null)
  const prependScroll = useRef(null)
  const [proactiveItems, setProactiveItems] = useState([])
  const scope = useRef(createRequestScope())
  const activeRequest = useRef(null)
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

  const requestHistory = useMemo(
    () =>
      messages
        .filter(message => message.role === 'user' || message.role === 'assistant')
        .slice(-8)
        .map(message => ({ role: message.role, text: message.text })),
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
  }

  async function submit(e, presetQuestion) {
    e?.preventDefault()
    const text = (presetQuestion || question).trim()
    if (!text || loading) return
    const current = scope.current.begin()
    const controller = new AbortController()
    activeRequest.current = controller

    setLoading(true)
    setQuestion('')
    onError?.('')
    setMessages(current => [...current, { role: 'user', text }])

    try {
      const data = await askDietAssistant(text, requestHistory, conversationId, controller.signal)
      if (!current()) return
      if (data.conversationId) {
        setConversationId(data.conversationId)
      }
      setMessages(current => [
        ...current,
        {
          role: 'assistant',
          text: buildAssistantText(data),
          summary: data.summary,
          suggestions: Array.isArray(data.suggestions) ? data.suggestions : [],
          weeklyTrend: data.weeklyTrend,
          riskLevel: data.riskLevel,
          references: Array.isArray(data.references) ? data.references : []
        }
      ])
      await refreshSidebar()
    } catch (err) {
      if (!current() || err.name === 'AbortError') return
      const message = err.message || '饮食助手暂时不可用，请稍后再试。'
      setMessages(current => [...current, { role: 'assistant', text: message }])
      onError?.(message)
    } finally {
      if (current()) {
        setLoading(false)
        activeRequest.current = null
      }
    }
  }

  async function openConversation(id) {
    resetHistoryPage()
    activeRequest.current?.abort()
    const current = scope.current.begin()
    setLoading(true)
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
            <button type="button" onClick={() => setOpen(false)} aria-label="关闭饮食助手">
              <X size={20} />
            </button>
          </header>

          <div className="assistant-workspace">
            <aside className="assistant-sidebar">
              <button className="assistant-new-btn" type="button" onClick={startNewConversation}>
                <Plus size={16} />
                新对话
              </button>
              <div className="assistant-session-list">
                {conversations.map(item => (
                  <div className={conversationId === item.id ? 'active' : ''} key={item.id}>
                    <button type="button" onClick={() => openConversation(item.id)}>{item.title}</button>
                    <button type="button" onClick={() => removeConversation(item.id)} aria-label="删除会话">
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
              </div>
            </aside>

            <div className="assistant-main">
              <AiBudgetNotice revision={messages.length} />
              {!!proactiveItems.length && (
                <div className="assistant-proactive">
                  <strong>主动建议</strong>
                  {proactiveItems.slice(0, 4).map(item => <span key={item}>{item}</span>)}
                </div>
              )}

              <div className="assistant-messages" ref={messageViewport}>
                {nextBefore && <button type="button" onClick={loadOlderMessages} disabled={loadingOlder || loading}>
                  {loadingOlder ? '正在加载历史消息…' : '加载更早的消息'}
                </button>}
                {messages.map((message, index) => (
                  <div className={`assistant-message ${message.role}`} key={`${message.role}-${index}`}>
                    {message.riskLevel && (
                      <span className={`assistant-risk ${String(message.riskLevel).toLowerCase()}`}>
                        {RISK_LABELS[message.riskLevel] || message.riskLevel}
                      </span>
                    )}
                    <div>{message.text}</div>
                    {message.references?.length > 0 && (
                      <div className="assistant-reference-list">
                        <span>参考依据</span>
                        {message.references.map(reference => (
                          <details key={reference.id || reference.title}>
                            <summary>{reference.title || '知识片段'}</summary>
                            <p>{reference.content}</p>
                          </details>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
                {loading && <div className="assistant-message assistant">正在调用用户记录、目标、周报和知识库生成建议...</div>}
              </div>

              <div className="assistant-quick-list">
                {QUICK_QUESTIONS.map(item => (
                  <button key={item} type="button" onClick={e => submit(e, item)} disabled={loading}>
                    {item}
                  </button>
                ))}
              </div>

              <form className="assistant-chat-form" onSubmit={submit}>
                <input
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  placeholder="问问怎么吃更合适"
                />
                <button type="submit" disabled={loading || !question.trim()} aria-label="发送问题">
                  <Send size={18} />
                </button>
              </form>
            </div>
          </div>
        </section>
      )}

      <button className="assistant-fab" type="button" onClick={() => setOpen(value => !value)}>
        {open ? <X size={24} /> : <MessageCircle size={24} />}
      </button>
    </div>
  )
}
