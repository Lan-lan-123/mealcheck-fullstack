import PlanSaveButton from "./PlanSaveButton"
import TrackingSummary from "./TrackingSummary"
import { formatTime } from '../utils/time'
import { useEffect, useRef, useState } from 'react'
import { listMeals } from '../api/client'
import { reportPeriod } from '../utils/reportPeriod'
import AuthImage from './AuthImage'
import Empty from './Empty'
import { reportGenerationLabel, reportReviewLabel } from '../utils/mealLabels'

export default function WeeklyReportDetail({ report, avgLabel, onClose }) {
  const heading = useRef(null)
  useEffect(() => { heading.current?.focus({ preventScroll: true }) }, [])
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setData(null)
    Promise.resolve().then(() => listMeals({ ...reportPeriod(report), page, size: 10 }, controller.signal))
      .then(next => { if (!controller.signal.aborted) setData(next) })
      .catch(err => { if (!controller.signal.aborted) setError(err.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [report.periodStart, report.periodEnd, page])
  const records = data?.content || []
  return (
    <div className="weekly-report-detail card">
        <div className="modal-head">
          <div>
            <h2 tabIndex={-1} ref={heading}>完整饮食周报</h2>
            <p>{report.periodStart} 至 {report.periodEnd} · 餐食明细按报告日期独立查询。</p>
          </div>

          <button className="modal-close" onClick={onClose} type="button">
            返回周报概览
          </button>
        </div>

        <div className="metrics">
          <div>
            <strong>{report?.totalMeals || 0}</strong>
            <span>记录数量</span>
          </div>

          <div>
            <strong>{avgLabel}</strong>
            <span>平均评分</span>
          </div>
        </div>

        <TrackingSummary summary={report.tracking} />
        <section className="weekly-section">
          <h3>周报总结</h3>
          <p>{report?.reportText || '最近 7 天暂无可分析的饮食记录。'}</p>
          {report?.generatedAt && <p className="report-meta">自动生成于 {formatTime(report.generatedAt)}</p>}
          {report?.generationMode && (
            <p className="report-meta">
              {reportGenerationLabel(report.generationMode)} · {reportReviewLabel(report.reviewStatus)}
            </p>
          )}
        </section>

        <section className="weekly-section">
          <h3>下周建议</h3>
          {report?.nextWeekSuggestions?.length ? (
            <ul className="weekly-suggestions">
              {report.nextWeekSuggestions.map(item => (
                <li key={item}>{item}<PlanSaveButton text={item} sourceType="REPORT" sourcePeriodStart={report.periodStart} sourcePeriodEnd={report.periodEnd} /></li>
              ))}
            </ul>
          ) : (
            <Empty text="暂无下周建议" />
          )}
        </section>

        <section className="weekly-section">
          <h3>风险标签统计</h3>
          <div className="chips risk">
            {Object.entries(report?.riskTotals || {}).length ? (
              Object.entries(report.riskTotals).map(([k, v]) => (
                <span key={k}>
                  {k} × {v}
                </span>
              ))
            ) : (
              <span>暂无风险标签</span>
            )}
          </div>
        </section>

        <section className="weekly-section">
          <h3>报告期间饮食图片</h3>
          {loading && <p role="status">加载餐食明细中...</p>}
          {error && <p role="alert" className="error">{error}</p>}
          {records.length ? (
            <div className="weekly-photo-grid">
              {records.map(record => (
                <article className="weekly-photo-card" key={record.id}>
                  <AuthImage url={record.imageUrl} alt="饮食图片" />
                  <div className="weekly-photo-info">
                    <strong>{record.score} 分</strong>
                    <span>{formatTime(record.occurredAt || record.createdAt)}</span>
                  </div>
                  <p>{record.summary}</p>
                  <div className="chips small">
                    {record.foods?.slice(0, 6).map((f, i) => (
                      <span key={i}>{f.name}</span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          ) : (
            !loading && !error && <Empty text="报告期间暂无饮食图片记录" />
          )}
          {!!data?.totalPages && <div className="record-pagination">
            <button type="button" disabled={loading || page === 0} onClick={() => setPage(page - 1)}>上一页</button>
            <span>第 {page + 1} / {data.totalPages} 页 · 共 {data.totalElements} 条</span>
            <button type="button" disabled={loading || page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>下一页</button>
          </div>}
        </section>
    </div>
  )
}
