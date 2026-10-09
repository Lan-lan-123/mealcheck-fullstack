import useAgentCoordination, { defaultCoordinationFilters } from '../../hooks/useAgentCoordination'
import { formatTime, Pagination } from './AdminUi'
import { formatDuration, coordinationStatusLabel, coordinationStatusClass, coordinationModeLabel, coordinationNodeLabel } from './coordinationLabels'
import DetailDrawer from '../../components/DetailDrawer'

function Info({ label, value }) { return <div><span>{label}</span><strong>{value}</strong></div> }

export default function AgentCoordinationPanel({ enabled, refreshRevision }) {
  const {
    coordinationPageData, coordinationDetail, coordinationTrigger, coordinationLoading,
    coordinationRuns, coordinationSize, setCoordinationSize, setCoordinationPage,
    coordinationFiltersDraft, setCoordinationFiltersDraft, setCoordinationFilters, loadCoordinationRuns,
    openCoordinationRun, applyCoordinationFilters, closeCoordinationRun, error
  } = useAgentCoordination(enabled, refreshRevision)
  if (!enabled) return null
  return <>{error && <div className="admin-message error" role="alert">{error}</div>}
<section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>周报 Agent 协调运行</h2>
              <p>查看每次多 Agent 周报的整体状态、降级情况、修订次数和节点执行时间线。</p>
            </div>
            <button className="secondary-btn" type="button" onClick={loadCoordinationRuns} disabled={coordinationLoading}>
              {coordinationLoading ? '刷新中...' : '刷新运行记录'}
            </button>
          </div>

          <form className="filter-grid compact coordination-filter-grid" onSubmit={applyCoordinationFilters}>
            <select value={coordinationFiltersDraft.status} onChange={event => setCoordinationFiltersDraft({ ...coordinationFiltersDraft, status: event.target.value })}>
              <option value="ALL">全部状态</option>
              <option value="RUNNING">运行中</option>
              <option value="SUCCEEDED">成功</option>
              <option value="DEGRADED">降级完成</option>
              <option value="FAILED">失败</option>
              <option value="INTERRUPTED">已中断</option>
            </select>
            <input type="number" min="1" value={coordinationFiltersDraft.userId} onChange={event => setCoordinationFiltersDraft({ ...coordinationFiltersDraft, userId: event.target.value })} placeholder="用户 ID" />
            <input type="number" min="1" value={coordinationFiltersDraft.jobId} onChange={event => setCoordinationFiltersDraft({ ...coordinationFiltersDraft, jobId: event.target.value })} placeholder="后台任务 ID" />
            <button className="secondary-btn" type="submit" disabled={coordinationLoading}>筛选</button>
            <button className="secondary-btn" type="button" onClick={() => {
              setCoordinationFiltersDraft(defaultCoordinationFilters)
              setCoordinationFilters(defaultCoordinationFilters)
              setCoordinationPage(0)
            }}>重置</button>
          </form>

          <div className="admin-table-wrap">
            <table className="admin-table coordination-table">
              <thead>
                <tr><th>运行 ID</th><th>状态</th><th>用户 / 任务</th><th>生成模式</th><th>节点</th><th>降级</th><th>失败</th><th>修订</th><th>耗时</th><th>开始时间</th><th>详情</th></tr>
              </thead>
              <tbody>
                {coordinationRuns.length ? coordinationRuns.map(run => (
                  <tr key={run.runId}>
                    <td><code title={run.runId}>{run.runId?.slice(0, 8)}</code></td>
                    <td><span className={coordinationStatusClass(run.status)}>{coordinationStatusLabel(run.status)}</span></td>
                    <td>用户 #{run.userId}<br /><span className="muted-text">任务 {run.jobId ? `#${run.jobId}` : '手动生成'}</span></td>
                    <td>{coordinationModeLabel(run.generationMode)}<br /><span className="muted-text">{run.reviewStatus || '尚未审查'}</span></td>
                    <td>{run.stepCount ?? 0}</td>
                    <td>{run.degradedStepCount ?? 0}</td>
                    <td>{run.failedStepCount ?? 0}</td>
                    <td>{run.revisionCount ?? 0}</td>
                    <td>{formatDuration(run.elapsedMs)}</td>
                    <td>{formatTime(run.startedAt)}</td>
                    <td><button className="secondary-btn" type="button" onClick={event => openCoordinationRun(run.runId, event.currentTarget)} disabled={coordinationLoading}>查看时间线</button></td>
                  </tr>
                )) : <tr><td colSpan="11" className="empty-cell">暂无符合条件的 Agent 协调记录</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={coordinationPageData} pageSize={coordinationSize} setPage={setCoordinationPage} setPageSize={setCoordinationSize} loading={coordinationLoading} />
        </section>
    {coordinationDetail && <CoordinationTimelineModal detail={coordinationDetail} onClose={closeCoordinationRun} returnFocus={coordinationTrigger} />}
  </>
}

function CoordinationTimelineModal({ detail, onClose, returnFocus }) {
  const run = detail?.run || {}
  const steps = detail?.steps || []
  return (
    <DetailDrawer title="Agent 协调时间线" onClose={onClose} returnFocus={returnFocus}>
        <header className="modal-head">
          <div>
            <p><code>{run.runId}</code> · 用户 #{run.userId} · {run.jobId ? `后台任务 #${run.jobId}` : '手动生成'}</p>
          </div>

        </header>

        <div className="coordination-summary-grid">
          <Info label="运行状态" value={coordinationStatusLabel(run.status)} />
          <Info label="生成模式" value={coordinationModeLabel(run.generationMode)} />
          <Info label="审查状态" value={run.reviewStatus || '尚未审查'} />
          <Info label="总耗时" value={formatDuration(run.elapsedMs)} />
          <Info label="实际修订" value={run.revisionCount ?? 0} />
          <Info label="降级 / 失败" value={`${run.degradedStepCount ?? 0} / ${run.failedStepCount ?? 0}`} />
        </div>

        {run.errorMessage && <div className="coordination-run-error">运行错误：{run.errorMessage}</div>}

        <div className="coordination-timeline">
          {steps.length ? steps.map((step, index) => (
            <article className={`coordination-step ${String(step.status || '').toLowerCase()}`} key={step.id || `${step.nodeName}-${index}`}>
              <div className="coordination-step-marker"><span>{index + 1}</span></div>
              <div className="coordination-step-content">
                <div className="coordination-step-head">
                  <div>
                    <strong>{coordinationNodeLabel(step.nodeName)}</strong>
                    <span>{step.nodeType} · 第 {step.attempt} 次</span>
                  </div>
                  <span className={coordinationStatusClass(step.status)}>{coordinationStatusLabel(step.status)}</span>
                </div>
                <div className="coordination-step-meta">
                  <span>结果：{step.outcome || '暂无'}</span>
                  <span>耗时：{formatDuration(step.latencyMs)}</span>
                  <span>AI：{step.aiGenerated === null || step.aiGenerated === undefined ? '不适用' : step.aiGenerated ? '是' : '否'}</span>
                  <span>降级：{step.fallbackUsed ? '是' : '否'}</span>
                  <span>{formatTime(step.startedAt)} → {formatTime(step.finishedAt)}</span>
                </div>
                {step.errorMessage && <p className="coordination-step-error">{step.errorMessage}</p>}
              </div>
            </article>
          )) : <div className="empty-box">该运行还没有节点记录</div>}
        </div>
    </DetailDrawer>
  )
}
