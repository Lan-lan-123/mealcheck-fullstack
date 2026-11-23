import Button from '../../components/Button'
import { useState } from 'react'
import { MetricCard, Info } from './AdminSummaryUi'
import DetailDrawer from '../../components/DetailDrawer'
import { formatTime } from './AdminUi'

export default function AdminOverviewPanel({ overview, health, operations, nonFoodUploadAlerts, setActiveTab }) {
  const [showAlerts, setShowAlerts] = useState(false)
  return (
    <>
      <section className="admin-grid">
        <MetricCard value={overview.userCount ?? 0} label="注册用户" />
        <MetricCard value={overview.mealRecordCount ?? 0} label="饮食记录" />
        <MetricCard value={overview.todayNormalUploadCount ?? 0} label="今日正常上传" />
        <MetricCard value={overview.todayAbnormalUploadCount ?? 0} label="今日异常上传" />
        <MetricCard value={overview.knowledgeChunkCount ?? 0} label="RAG 片段" />
      </section>
      <>
        <section className="admin-section overview-status">
          <div className="admin-section-head">
            <div>
              <h2>运行摘要</h2>
              <p>关键状态集中查看，完整指标可前往系统监控。</p>
            </div>
            <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setActiveTab('monitor')}>
              查看系统监控
            </Button>
          </div>
          <div className="overview-status-grid">
            <Info label="服务健康" value={health?.status || '待获取'} />
            <Info label="近 7 天活跃用户" value={operations.activeUsers7d ?? 0} />
            <Info label="活跃违规预警" value={operations.activeNonFoodAlerts ?? 0} />
          </div>
          <div className="overview-alert-summary">
            <span>
              {nonFoodUploadAlerts.length
                ? '有 ' + nonFoodUploadAlerts.length + ' 位用户触发上传预警'
                : '暂无用户上传预警'}
            </span>
            <Button variant="secondary" className="secondary-btn" type="button" onClick={() => setShowAlerts(true)}>
              查看预警详情
            </Button>
          </div>
        </section>
        <section className="admin-section">
          <h2>常用工作入口</h2>
          <div className="overview-shortcuts">
            {[
              ['users', '用户管理', '查找和管理平台账户'],
              ['meals', '饮食记录', '查看餐食与评分'],
              ['assistant-stats', '助手统计', '查看使用情况与问答趋势'],
              ['knowledge', 'RAG 知识库', '维护饮食知识内容'],
            ].map(([key, label, description]) => (
              <Button variant="secondary" type="button" key={key} onClick={() => setActiveTab(key)}>
                <strong>{label}</strong>
                <span>{description}</span>
              </Button>
            ))}
          </div>
        </section>
      </>
      {showAlerts && (
        <DetailDrawer title="上传预警详情" onClose={() => setShowAlerts(false)}>
          {nonFoodUploadAlerts.length ? (
            <>
              {nonFoodUploadAlerts.length > 0 && (
                <div className="admin-alert-panel">
                  <div>
                    <h3>非饮食图片上传预警</h3>
                    <p>以下用户在短时间内连续上传了非饮食图片，请关注是否存在误用或异常操作。</p>
                  </div>
                  <div className="admin-alert-list">
                    {nonFoodUploadAlerts.map((alert) => (
                      <article key={alert.username}>
                        <strong>{alert.username}</strong>
                        <span>{alert.displayName || '未设置昵称'}</span>
                        <span>
                          {alert.windowMinutes} 分钟内 {alert.count} 次，阈值 {alert.limit} 次
                        </span>
                        <span>{formatTime(alert.lastTriggeredAt)}</span>
                      </article>
                    ))}
                  </div>
                </div>
              )}
            </>
          ) : (
            <p className="hint">暂无用户上传预警。</p>
          )}
        </DetailDrawer>
      )}
    </>
  )
}
