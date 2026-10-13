import { useEffect, useState } from 'react'
import { Check, CircleAlert, LoaderCircle } from 'lucide-react'

export default function TaskProgress({ label, title, description, startedAt, finishedAt, active = true, tone = 'default', steps = [], stepIndex, longWaitMessage }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (!active || !startedAt) return
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active, startedAt])
  const seconds = startedAt ? Math.max(0, Math.floor(((finishedAt || now) - startedAt) / 1000)) : 0
  const elapsed = seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  const Icon = tone === 'success' ? Check : tone === 'warning' ? CircleAlert : LoaderCircle

  return <section className={`task-progress task-progress--${tone}`} aria-label={label}>
    <div className="task-progress-heading">
      <Icon size={18} className={active && tone !== 'warning' ? 'task-progress-spinner' : ''} aria-hidden="true" />
      <div role="status" aria-atomic="true"><strong>{title}</strong>{description && <p>{description}</p>}</div>
    </div>
    {!!steps.length && <ol className="task-progress-steps" aria-label="处理阶段">
      {steps.map((step, index) => {
        const done = tone === 'success' || (stepIndex != null && index < stepIndex)
        const current = active && index === stepIndex
        return <li key={step} className={done ? 'is-complete' : current ? 'is-current' : ''} aria-current={current ? 'step' : undefined}>
          <span className="task-progress-step-icon" aria-hidden="true">{done ? <Check size={12} /> : index + 1}</span>
          <span>{step}</span>
        </li>
      })}
    </ol>}
    {active && <div className="task-progress-track" role="progressbar" aria-label={title} aria-valuetext={title}><span /></div>}
    {startedAt && <small className="task-progress-time" aria-live="off">{active ? '已等待' : '本次等待'} {elapsed}</small>}
    {active && seconds >= 30 && longWaitMessage && <p className="task-progress-help">{longWaitMessage}</p>}
  </section>
}

const MEAL_STAGES = ['上传图片', '等待处理', '智能分析', '完成']
const MEAL_STATUS = {
  UPLOADING: ['正在上传图片', '图片接收后将开始分析。', 0],
  UPLOAD_RETRY: ['上传入口繁忙，正在自动重试', '请保留当前图片，等待重试。', 0],
  CHECKING: ['正在查询上次分析进度', '正在找回已提交的任务。', null],
  PENDING: ['图片已接收，正在排队', '任务已提交，可以刷新页面继续查询。', 1],
  PROCESSING: ['正在分析餐食', '正在识别食物、计算评分并生成饮食建议。', 2],
  SUCCEEDED: ['餐食分析已完成', '评分与饮食建议已更新。', 3],
  INTERRUPTED: ['暂未取得分析结果', '请查询上次分析结果，或使用原图重试。', null]
}

export function MealAnalysisProgress({ task }) {
  if (!task) return null
  const [title, description, stepIndex] = MEAL_STATUS[task.status] || MEAL_STATUS.CHECKING
  const active = !['SUCCEEDED', 'INTERRUPTED'].includes(task.status)
  return <TaskProgress label="餐食分析进度" {...task} active={active}
    title={task.connectionInterrupted ? '连接暂时中断，正在重新查询' : title}
    description={task.message || (task.connectionInterrupted ? '任务状态暂时无法更新，正在自动重连。' : description)}
    tone={!active && task.status !== 'SUCCEEDED' || task.connectionInterrupted || task.status === 'UPLOAD_RETRY' ? 'warning' : task.status === 'SUCCEEDED' ? 'success' : 'default'}
    steps={MEAL_STAGES} stepIndex={stepIndex}
    longWaitMessage={['PENDING', 'PROCESSING'].includes(task.status) ? '处理时间较长，可以刷新页面继续查询，请勿重复提交。' : '等待时间较长，请保留当前图片；稍后可查询上次分析结果。'} />
}

const REPORT_STATUS = {
  SUBMITTING: ['正在提交周报请求', '正在创建或查找本次周报任务。', 0],
  PENDING: ['周报正在排队', '任务已提交，等待开始生成。', 1],
  RUNNING: ['正在生成周报', '正在整理本周饮食记录和建议。', 2],
  RETRY: ['周报正在自动重试', '本次生成遇到问题，系统正在安排重试。', 2],
  SUCCEEDED: ['周报已生成', '本周饮食概览已更新。', 3],
  INTERRUPTED: ['暂未取得周报结果', '请稍后再次查询生成进度。', null]
}

export function WeeklyReportProgress({ task }) {
  if (!task) return null
  const [title, description, stepIndex] = REPORT_STATUS[task.status] || REPORT_STATUS.SUBMITTING
  const active = !['SUCCEEDED', 'INTERRUPTED'].includes(task.status)
  return <TaskProgress label="周报生成进度" {...task} active={active} title={title} description={task.message || description}
    tone={task.status === 'SUCCEEDED' ? 'success' : ['RETRY', 'INTERRUPTED'].includes(task.status) ? 'warning' : 'default'}
    steps={['提交请求', '等待处理', '生成周报', '完成']} stepIndex={stepIndex}
    longWaitMessage="生成时间较长，可以先查看其他栏目；返回后会继续显示进度。" />
}
