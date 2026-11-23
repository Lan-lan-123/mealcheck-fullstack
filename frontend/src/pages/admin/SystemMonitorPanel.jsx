import { Info } from './AdminSummaryUi'
import { MetricSmall, Chart, formatTime } from './AdminUi'
import SectionSwitcher from '../../components/SectionSwitcher'
import AiBudgetMonitor from '../../components/AiBudgetMonitor'
import AiSettingsPanel from './AiSettingsPanel'

export default function SystemMonitorPanel({
  system,
  health,
  overview,
  operations,
  ragEvaluation,
  uploadTrends,
  ragCategoryCounts,
  monitorSection,
  setMonitorSection,
  onSettingsSaved,
}) {
  function formatBool(value) {
    return value ? '已配置' : '未配置'
  }
  return (
    <>
      <>
        <SectionSwitcher
          label="系统监控分类"
          value={monitorSection}
          onChange={setMonitorSection}
          items={[
            ['services', '服务状态'],
            ['ai', 'AI 调用'],
            ['retrieval', '检索指标'],
            ['uploads', '上传与活跃情况'],
          ]}
        />
        <section className="admin-section">
          {monitorSection === 'services' && (
            <>
              <h2>系统状态</h2>
              <div className="admin-info-grid">
                <Info label="后端" value={system.backend || 'Spring Boot'} />
                <Info label="数据库" value={system.database || 'PostgreSQL'} />
                <Info label="向量库" value={system.vectorDatabase || 'pgvector'} />
                <Info label="Embedding" value={system.embeddingMethod || 'HashEmbeddingService'} />
                <Info label="AI 模型" value={system.aiModel || '未配置'} />
                <Info label="API Key" value={formatBool(system.apiKeyConfigured)} />
                <Info label="Base URL" value={formatBool(system.baseUrlConfigured)} />
                <Info label="健康状态" value={health?.status || '未知'} />
              </div>

              <div className="admin-note">
                数据库：{health?.databaseReachable ? '正常' : '异常'}；Redis：
                {health?.redisReachable ? '正常' : '降级运行'}；上传目录：
                {health?.uploadDirectoryWritable ? '可写' : '不可写'}；知识片段：
                {health?.knowledgeChunkCount ?? overview.knowledgeChunkCount ?? 0} 条。
              </div>
            </>
          )}
          {monitorSection === 'ai' && (
            <>
              <h2>AI 调用与耗时</h2>
              <AiSettingsPanel onSaved={onSettingsSaved} />
              <AiBudgetMonitor />
              <div className="meal-analytics-grid">
                {(health?.aiCalls || system.aiCalls || []).length ? (
                  (health?.aiCalls || system.aiCalls || []).map((call) => (
                    <div className="meal-chart-card" key={call.operation}>
                      <h3>{call.operation}</h3>
                      <p className={call.success ? 'status-ok' : 'status-bad'}>
                        {call.success ? '最近调用成功' : '最近调用失败'}
                      </p>
                      <p className="muted-text">{call.message}</p>
                      <p className="muted-text">{formatTime(call.at)}</p>
                    </div>
                  ))
                ) : (
                  <div className="meal-chart-card wide">
                    <h3>AI 调用状态</h3>
                    <p className="muted-text">暂无 AI 调用记录。</p>
                  </div>
                )}
              </div>

              {(system.aiMetrics || []).length > 0 && (
                <div className="admin-table-wrap ai-metrics-table">
                  <table className="admin-table">
                    <thead>
                      <tr>
                        <th>AI 操作</th>
                        <th>调用数</th>
                        <th>成功</th>
                        <th>失败</th>
                        <th>平均耗时</th>
                        <th>最大耗时</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(system.aiMetrics || []).map((metric) => (
                        <tr key={metric.operation}>
                          <td>{metric.operation}</td>
                          <td>{metric.calls}</td>
                          <td>{metric.successCount}</td>
                          <td>{metric.failureCount}</td>
                          <td>{metric.averageLatencyMs} ms</td>
                          <td>{metric.maxLatencyMs} ms</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
          {monitorSection === 'retrieval' && (
            <>
              <h2>知识检索指标</h2>
              <div className="rag-evaluation-grid">
                <MetricSmall label="24h 检索次数" value={ragEvaluation.searches24h ?? 0} />
                <MetricSmall label="平均 Top1 分数" value={ragEvaluation.averageTopScore24h ?? 0} />
                <MetricSmall label="平均检索分数" value={ragEvaluation.averageScore24h ?? 0} />
                <MetricSmall label="低置信检索" value={ragEvaluation.lowConfidenceSearches24h ?? 0} />
                <MetricSmall label="平均粗排 Top1" value={ragEvaluation.averageRoughTopScore24h ?? 0} />
                <MetricSmall label="平均精排原始 Top1" value={ragEvaluation.averageRerankerTopScore24h ?? 0} />
                <MetricSmall label="精排调用次数" value={ragEvaluation.rerankerAppliedSearches24h ?? 0} />
                <MetricSmall label="空证据次数" value={ragEvaluation.noAnswerSearches24h ?? 0} />
                <Chart title="RAG 命中类别分布" items={ragCategoryCounts} empty="暂无检索记录" wide />
              </div>
            </>
          )}
          {monitorSection === 'uploads' && (
            <>
              <h2>上传与活跃情况</h2>
              <div className="operations-grid">
                <MetricSmall label="近 7 天活跃用户" value={operations.activeUsers7d ?? 0} />
                <MetricSmall label="近 7 天上传记录" value={operations.mealUploads7d ?? 0} />
                <MetricSmall label="AI 成功调用" value={operations.aiSuccessCount ?? 0} />
                <MetricSmall label="AI 失败调用" value={operations.aiFailureCount ?? 0} />
                <MetricSmall label="活跃违规预警" value={operations.activeNonFoodAlerts ?? 0} />
              </div>

              <div className="meal-analytics-grid">
                <Chart title="近 7 天正常上传" items={uploadTrends.dailyNormalUploads} empty="暂无正常上传记录" />
                <Chart title="近 7 天异常上传" items={uploadTrends.dailyAbnormalUploads} empty="暂无异常上传记录" />
                <Chart title="近 7 天触发预警" items={uploadTrends.dailyAlertTriggers} empty="暂无预警记录" />
                <Chart title="近 7 天受限用户" items={uploadTrends.dailyBlockedUsers} empty="暂无受限用户" />
              </div>
            </>
          )}
        </section>
      </>
    </>
  )
}
