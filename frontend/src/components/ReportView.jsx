import TrackingSummary from "./TrackingSummary"
import { formatTime } from '../utils/time'
import { reportGenerationLabel, reportReviewLabel } from '../utils/mealLabels'
import { WeeklyReportProgress } from './TaskProgress'

export default function ReportView({ report, avgLabel, onOpenDetail, onGenerate, jobStatus, jobProgress }) {
  return (
    <div className="card">
      <h2>本周饮食概览</h2>
      {onGenerate && <button className="secondary-btn" type="button" onClick={onGenerate} disabled={!!jobStatus}>
        {jobStatus === 'RETRY' ? '生成暂时失败，正在自动重试…' : jobStatus ? '周报生成中…' : '生成今日周报 / 查看进度'}
      </button>}
      <WeeklyReportProgress task={jobProgress} />
      <div className="metrics">
        <div>
          <strong>{report.totalMeals}</strong>
          <span>记录数量</span>
        </div>

        <div>
          <strong>{avgLabel}</strong>
          <span>平均评分</span>
        </div>
      </div>

      <TrackingSummary summary={report.tracking} />
      <p className="report-summary">{report.reportText}</p>

      {report.generatedAt && (
        <p className="report-meta">生成于 {formatTime(report.generatedAt)}</p>
      )}
      {report.generationMode && (
        <p className="report-meta">
          {reportGenerationLabel(report.generationMode)} · {reportReviewLabel(report.reviewStatus)}
        </p>
      )}

      <p className="hint">{report.periodStart} 至 {report.periodEnd} · 完整报告包含下周建议、风险统计和本期餐食明细。</p>

      <button className="ghost detail-report-btn" onClick={onOpenDetail} type="button" disabled={!!jobStatus}>
        查看周报详情
      </button>

    </div>
  )
}
