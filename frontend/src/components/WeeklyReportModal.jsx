import AuthImage from './AuthImage'
import Empty from './Empty'

export default function WeeklyReportModal({ report, avgLabel, records, onClose }) {
  return (
    <div className="modal-backdrop">
      <div className="weekly-modal">
        <div className="modal-head">
          <div>
            <h2>近 7 天饮食周报</h2>
            <p>查看最近一周饮食记录、评分、风险标签和图片明细。</p>
          </div>

          <button className="modal-close" onClick={onClose} type="button">
            关闭
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

        <section className="weekly-section">
          <h3>周报总结</h3>
          <p>{report?.reportText || '最近 7 天暂无可分析的饮食记录。'}</p>
          {report?.generatedAt && <p className="report-meta">自动生成于 {new Date(report.generatedAt).toLocaleString()}</p>}
        </section>

        <section className="weekly-section">
          <h3>下周建议</h3>
          {report?.nextWeekSuggestions?.length ? (
            <ul className="weekly-suggestions">
              {report.nextWeekSuggestions.map(item => (
                <li key={item}>{item}</li>
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
          <h3>最近饮食图片</h3>
          {records.length ? (
            <div className="weekly-photo-grid">
              {records.map(record => (
                <article className="weekly-photo-card" key={record.id}>
                  <AuthImage url={record.imageUrl} alt="饮食图片" />
                  <div className="weekly-photo-info">
                    <strong>{record.score} 分</strong>
                    <span>{new Date(record.createdAt).toLocaleString()}</span>
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
            <Empty text="最近 7 天暂无饮食图片记录" />
          )}
        </section>
      </div>
    </div>
  )
}
