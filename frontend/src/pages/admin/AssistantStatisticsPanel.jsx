import { MetricSmall, Chart } from './AdminUi'
import SectionSwitcher from '../../components/SectionSwitcher'

export default function AssistantStatisticsPanel({ assistantStats, assistantStatsSection, setAssistantStatsSection }) {
  return (
    <>
      <section className="admin-section">
        <div className="assistant-stats-panel">
          <div className="admin-section-head compact-head">
            <div>
              <h2>助手统计</h2>
              <p>观察智能问答助手的使用频率、会话质量、常见问题和 RAG 引用情况。</p>
            </div>
          </div>

          <div className="operations-grid compact-five">
            <MetricSmall label="助手会话数量" value={assistantStats.conversationCount ?? 0} />
            <MetricSmall label="用户提问次数" value={assistantStats.questionCount ?? 0} />
            <MetricSmall label="平均轮次" value={assistantStats.averageTurns ?? 0} />
            <MetricSmall label="主动建议触发" value={assistantStats.proactiveTriggerCount ?? 0} />
            <MetricSmall label="RAG 引用知识数" value={(assistantStats.topRagReferences || []).length} />
          </div>

          <div className="assistant-stats-grid">
            <SectionSwitcher
              label="助手统计分类"
              value={assistantStatsSection}
              onChange={setAssistantStatsSection}
              items={[
                ['trend', '问答趋势'],
                ['categories', '问题分类'],
                ['users', '用户使用情况'],
                ['references', '知识引用排行'],
              ]}
            />
            {assistantStatsSection === 'trend' && (
              <Chart title="最近 7 天助手问答趋势" items={assistantStats.questionTrend7d} empty="暂无助手问答记录" />
            )}
            {assistantStatsSection === 'categories' && (
              <Chart title="用户常问问题分类" items={assistantStats.questionCategories} empty="暂无问题分类数据" />
            )}
            {assistantStatsSection === 'users' && (
              <Chart title="每个用户提问次数" items={assistantStats.questionsByUser} empty="暂无用户提问数据" />
            )}
            {assistantStatsSection === 'references' && (
              <Chart title="RAG 知识引用排行" items={assistantStats.topRagReferences} empty="暂无 RAG 引用记录" />
            )}
          </div>
        </div>
      </section>
    </>
  )
}
