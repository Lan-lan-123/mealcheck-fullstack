export default function ReportView({ report, avgLabel, onOpenDetail }) {
  return (
    <div className="card">
      <h2>本周饮食概览</h2>
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

      <p>{report.reportText}</p>

      <button className="ghost detail-report-btn" onClick={onOpenDetail} type="button">
        查看周报详情
      </button>

      <h3>风险标签统计</h3>
      <div className="chips risk">
        {Object.entries(report.riskTotals || {}).length ? (
          Object.entries(report.riskTotals).map(([k, v]) => (
            <span key={k}>
              {k} × {v}
            </span>
          ))
        ) : (
          <span>暂无风险标签</span>
        )}
      </div>
    </div>
  )
}
