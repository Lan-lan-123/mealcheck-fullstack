import Button from '../../components/Button'
import { useState } from 'react'
import useAssistantEvaluation from '../../hooks/useAssistantEvaluation'
import { formatTime, MetricSmall } from './AdminUi'
import { assistantCategoryLabel, benchmarkStatusLabel, benchmarkModeLabel, benchmarkVerdictLabel, formatDelta, needsHumanReview } from './evaluationLabels'
import DetailDrawer from '../../components/DetailDrawer'
import SectionSwitcher from '../../components/SectionSwitcher'
import { RagChainMetrics, RagChainCase } from './RagChainEvidence'

export default function AssistantEvaluationPanel({ enabled, refreshRevision, ragOnly = false }) {
  const {
    assistantBenchmark, assistantBenchmarkHistory, assistantBenchmarkLoading, assistantReviewReport,
    reviewingCaseId, assistantComparison, comparisonBaseRunId, setComparisonBaseRunId,
    comparisonCandidateRunId, setComparisonCandidateRunId, evaluationCandidates, evaluationCandidateForm,
    setEvaluationCandidateForm, evaluationCandidateLoading, handleRunAssistantEvaluation, handleViewAssistantEvaluation,
    handleReviewAssistantCase, handleCompareAssistantRuns, handleAddEvaluationCandidate, handleEvaluationCandidateStatus,
    assistantDiagnostics, assistantReviewsByCase, error, message
  } = useAssistantEvaluation(enabled, refreshRevision)
  const [evaluationSection, setEvaluationSection] = useState('results')
  const [benchmarkSection, setBenchmarkSection] = useState('summary')
  const [showEvaluationMetrics, setShowEvaluationMetrics] = useState(false)
  const chain = assistantBenchmark?.mode === 'RAG_CHAIN'
  const toolOnly = ['RAG_TOOLS', 'RAG_CHAIN'].includes(assistantBenchmark?.mode)
  const comparingTools = [assistantComparison?.baseMode, assistantComparison?.candidateMode].some(mode => ['RAG_TOOLS', 'RAG_CHAIN'].includes(mode))
  if (!enabled) return null
  return <>{error && <div className="admin-message error" role="alert">{error}</div>}{message && <div className="admin-message" role="status">{message}</div>}
<section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>{ragOnly ? 'RAG 链路评测' : '问答助手质量评测'}</h2>
              <p>{ragOnly ? '同一题依次检查工具调用和实际知识检索，统一查看阶段结果、运行历史和失败原因。' : '快速、标准和完整模式检查助手回答质量；RAG 链路模式检查工具调用与实际检索。'}</p>
            </div>
            <div className="assistant-evaluation-actions">
              <Button variant="secondary" className="secondary-btn" type="button"
                onClick={() => { setEvaluationSection('results'); handleRunAssistantEvaluation('RAG_CHAIN') }}
                disabled={assistantBenchmarkLoading || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)}>
                RAG 链路评测（200 题）
              </Button>
              {!ragOnly && <>
              <Button variant="secondary"
                className="secondary-btn"
                type="button"
                onClick={() => { setEvaluationSection('results'); handleRunAssistantEvaluation('QUICK') }}
                disabled={assistantBenchmarkLoading || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)}
              >
                {assistantBenchmarkLoading ? '正在启动...' : '快速评测（10 题）'}
              </Button>
              <Button variant="secondary"
                className="secondary-btn"
                type="button"
                onClick={() => { setEvaluationSection('results'); handleRunAssistantEvaluation('STANDARD') }}
                disabled={assistantBenchmarkLoading || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)}
              >
                标准测试（50 题）
              </Button>
              <Button variant="primary"
                className="primary-btn"
                type="button"
                onClick={() => { setEvaluationSection('results'); handleRunAssistantEvaluation('FULL') }}
                disabled={assistantBenchmarkLoading || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)}
              >
                {assistantBenchmark?.status === 'PENDING' ? '等待执行...' : assistantBenchmark?.status === 'RUNNING' ? '评测运行中...' : '完整测试（200 题）'}
              </Button>
              </>}
            </div>
          </div>

          <details className="workspace-disclosure"><summary>评测规则与指标说明</summary><p>
            RAG 链路评测一次运行同一套 200 题：工具调用时执行真实的向量检索、重排与过滤，再按该次返回结果评分，不额外重复搜索。知识题未调用检索记为未执行，库外题正确不调用则通过边界检查。
            每题工具规则和最终证据覆盖均通过才计为链路通过；首次检索排名与补充查询分别保留，补充检索可以补齐证据。新口径按正文指纹、原文锚点和分级标注检查，背景相关不能单独通过。标注待独立人工复核，此模式不使用独立裁判评分回答质量。
            以下 RAG 工具模式说明适用于旧运行记录：
            RAG 工具评测复用同一套 200 道题：180 道知识题要求调用知识检索，20 道库外题禁止调用工具；检查漏调、误调、调用顺序、成功状态、查询主题词、参数范围与重复调用。
            工具调用准确率按“工具规则通过题数 / 已完成题数”计算，执行错误计入分母；完整运行的分母为 200。查询主题词覆盖属于规则检查，不代表语义理解或最终回答正确。
            此模式仅在工具被调用后返回受控知识样本，不测实时检索排序，不调用裁判模型，不评意图、回答质量或发布条件。以下意图和回答评分规则仅适用于原有 10/50/200 题模式。
            工具准确率包含后端保障执行，衡量整体工具流程；逐题轨迹标明后端执行或模型请求，不能将后端保障后的通过率解释为模型自主选择能力提升。
            评测在单线程后台任务中运行，避免并发消耗模型额度。每条用例复用生产环境的 Function Calling 循环、工具定义、轮数和超时限制；工具执行层使用隔离的合成数据，不读取或修改真实用户、餐食或会话数据。
            每条用例同时标注意图标准答案；系统统计准确率、Macro-F1 和各 Skill 的精确率/召回率。意图不匹配、缺少预期工具或调用禁用工具都会直接判失败，再由裁判模型评估回答质量。裁判可通过独立模型配置；未配置时会回退到生成模型并在结果中明确标记。
            质量失败与执行错误分开计算；出现执行错误时结论为“结果不完整”，任一硬门禁或安全、隐私类失败会“阻断发布”。
          </p></details>

          <SectionSwitcher label="助手评测工作区" value={evaluationSection} onChange={setEvaluationSection} items={[["results", "当前评测结果"], ["history", "评测历史"], ["comparison", "运行对比"], ["samples", "样本候选库"]]} />
          <div className="assistant-history-block section-panel" hidden={evaluationSection !== 'history'}>
            <h3>最近评测记录</h3>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr><th>开始时间</th><th>模式</th><th>数据集</th><th>状态</th><th>评测结论</th><th>进度</th><th>规则通过率</th><th>意图准确率</th><th>工具轨迹通过率</th><th>执行成功率</th><th>质量平均分</th><th>裁判</th><th>发起人</th><th>操作</th></tr>
                </thead>
                <tbody>
                  {assistantBenchmarkHistory.length > 0 ? assistantBenchmarkHistory.map(item => (
                    <tr key={item.runId}>
                      <td>{formatTime(item.startedAt)}</td>
                      <td>{benchmarkModeLabel(item.mode)}</td>
                      <td>{item.datasetVersion || '旧版本'}<br /><span>{item.datasetFingerprint?.slice(0, 10) || '-'}</span></td>
                      <td>{benchmarkStatusLabel(item.status)}</td>
                      <td>{benchmarkVerdictLabel(item.verdict)}</td>
                      <td>{item.completedCount}/{item.caseCount}</td>
                      <td>{Math.round((item.passRate ?? 0) * 10000) / 100}%</td>
                      <td>{['RAG_TOOLS', 'RAG_CHAIN'].includes(item.mode) ? '未评测' : `${Math.round((item.intentAccuracy ?? 0) * 10000) / 100}%`}</td>
                      <td>{Math.round((item.toolTracePassRate ?? 0) * 10000) / 100}%</td>
                      <td>{Math.round((item.executionSuccessRate ?? 0) * 10000) / 100}%</td>
                      <td>{['RAG_TOOLS', 'RAG_CHAIN'].includes(item.mode) ? '未评测' : `${item.averageScore ?? 0}/4`}</td>
                      <td>{['RAG_TOOLS', 'RAG_CHAIN'].includes(item.mode) ? '未使用（规则评分）' : `${item.judgeModel || '未配置'}${item.independentJudge ? '（独立）' : '（同模型）'}`}</td>
                      <td>{item.startedBy || '-'}</td>
                      <td><Button variant="secondary" className="secondary-btn" type="button" onClick={() => { setEvaluationSection('results'); handleViewAssistantEvaluation(item.runId) }} disabled={['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)}>查看</Button></td>
                    </tr>
                  )) : (
                    <tr><td colSpan="14">暂无持久化评测记录</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className="assistant-history-block section-panel" hidden={evaluationSection !== 'comparison'}>
            <h3>历史运行对比</h3>
            <div className="filter-grid compact">
              <select value={comparisonBaseRunId} onChange={event => setComparisonBaseRunId(event.target.value)}>
                <option value="">选择基准运行</option>
                {assistantBenchmarkHistory.map(item => <option key={`base-${item.runId}`} value={item.runId}>{formatTime(item.startedAt)} · {benchmarkModeLabel(item.mode)}</option>)}
              </select>
              <select value={comparisonCandidateRunId} onChange={event => setComparisonCandidateRunId(event.target.value)}>
                <option value="">选择候选运行</option>
                {assistantBenchmarkHistory.map(item => <option key={`candidate-${item.runId}`} value={item.runId}>{formatTime(item.startedAt)} · {benchmarkModeLabel(item.mode)}</option>)}
              </select>
              <Button variant="secondary" className="secondary-btn" type="button" onClick={handleCompareAssistantRuns} disabled={!comparisonBaseRunId || !comparisonCandidateRunId || comparisonBaseRunId === comparisonCandidateRunId || assistantBenchmarkLoading}>开始对比</Button>
            </div>
            {assistantComparison && (
              <>
                {(assistantComparison.warnings || []).map(item => <div className="admin-note" key={item}>{item}</div>)}
                <div className="operations-grid compact-five">
                  <MetricSmall label="可直接比较" value={assistantComparison.directlyComparable ? '是' : '否，需谨慎'} />
                  <MetricSmall label={comparingTools ? '规则通过率变化' : '质量通过率变化'} value={formatDelta(assistantComparison.passRateDelta, true)} />
                  <MetricSmall label="端到端变化" value={formatDelta(assistantComparison.endToEndPassRateDelta, true)} />
                  {!comparingTools && <MetricSmall label="意图准确率变化" value={formatDelta(assistantComparison.intentAccuracyDelta, true)} />}
                  <MetricSmall label="工具通过率变化" value={formatDelta(assistantComparison.toolTracePassRateDelta, true)} />
                  {!comparingTools && <MetricSmall label="平均分变化" value={formatDelta(assistantComparison.averageScoreDelta)} />}
                  <MetricSmall label="已修复用例" value={assistantComparison.fixedCaseCount ?? 0} />
                  <MetricSmall label="新增失败用例" value={assistantComparison.regressedCaseCount ?? 0} />
                  <MetricSmall label="耗时变化" value={`${formatDelta(assistantComparison.elapsedMsDelta)} ms`} />
                </div>
                {(assistantComparison.categories || []).length > 0 && (
                  <div className="admin-table-wrap">
                    <table className="admin-table">
                      <thead><tr><th>类别</th><th>基准通过率</th><th>候选通过率</th><th>通过率变化</th><th>平均分变化</th></tr></thead>
                      <tbody>{assistantComparison.categories.map(item => (
                        <tr key={item.category}>
                          <td>{assistantCategoryLabel(item.category)}</td>
                          <td>{Math.round((item.basePassRate ?? 0) * 10000) / 100}%</td>
                          <td>{Math.round((item.candidatePassRate ?? 0) * 10000) / 100}%</td>
                          <td className={(item.passRateDelta ?? 0) < 0 ? 'benchmark-fail' : (item.passRateDelta ?? 0) > 0 ? 'benchmark-pass' : ''}>{formatDelta(item.passRateDelta, true)}</td>
                          <td className={(item.averageScoreDelta ?? 0) < 0 ? 'benchmark-fail' : (item.averageScoreDelta ?? 0) > 0 ? 'benchmark-pass' : ''}>{comparingTools ? '未评测' : formatDelta(item.averageScoreDelta)}</td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
                {(assistantComparison.caseChanges || []).length > 0 && (
                  <div className="admin-table-wrap">
                    <table className="admin-table">
                      <thead><tr><th>用例</th><th>变化</th><th>基准</th><th>候选</th><th>意图变化</th><th>工具轨迹</th></tr></thead>
                      <tbody>{assistantComparison.caseChanges.map(item => (
                        <tr key={item.caseId}>
                          <td><strong>{item.caseId}</strong><br /><span>{item.question}</span></td>
                          <td className={item.changeType === 'FIXED' ? 'benchmark-pass' : item.changeType === 'REGRESSED' ? 'benchmark-fail' : ''}>{item.changeType}</td>
                          <td>{item.baseStatus}</td><td>{item.candidateStatus}</td>
                          <td>{comparingTools ? '未评测' : `${item.baseSkill || '-'} → ${item.candidateSkill || '-'}`}</td>
                          <td>{item.baseToolTracePassed ? '通过' : '失败'} → {item.candidateToolTracePassed ? '通过' : '失败'}</td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </div>

          <div className="assistant-history-block section-panel" hidden={evaluationSection !== 'samples'}>
            <h3>脱敏失败样本候选库</h3>
            <p>候选样本审核通过后仍不会自动加入正式 200 题，需要补全期望行为并经过代码评审。</p>
            <form className="filter-grid" onSubmit={handleAddEvaluationCandidate}>
              <textarea value={evaluationCandidateForm.question} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, question: event.target.value })} placeholder="脱敏后的用户问题" required />
              <textarea value={evaluationCandidateForm.observedAnswer} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, observedAnswer: event.target.value })} placeholder="出现问题的助手回答" required />
              <select value={evaluationCandidateForm.issueType} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, issueType: event.target.value })}>
                <option value="ANSWER_QUALITY">回答质量</option><option value="INTENT">意图识别</option><option value="TOOL_SELECTION">工具选择</option><option value="TOOL_ARGUMENTS">工具参数</option><option value="SAFETY">安全</option><option value="PRIVACY">隐私</option><option value="MULTI_TURN">多轮理解</option><option value="OTHER">其他</option>
              </select>
              <input value={evaluationCandidateForm.expectedSkill} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, expectedSkill: event.target.value })} placeholder="期望 Skill（可选）" />
              <input value={evaluationCandidateForm.expectedTools} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, expectedTools: event.target.value })} placeholder="期望工具，英文逗号分隔" />
              <input value={evaluationCandidateForm.sourceReference} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, sourceReference: event.target.value })} placeholder="来源编号（不要填写用户身份）" />
              <textarea value={evaluationCandidateForm.sanitizedContext} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, sanitizedContext: event.target.value })} placeholder="脱敏后的必要上下文（可选）" />
              <textarea value={evaluationCandidateForm.notes} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, notes: event.target.value })} placeholder="预期正确表现或问题说明" />
              <label className="inline-check"><input type="checkbox" checked={evaluationCandidateForm.sanitizedConfirmed} onChange={event => setEvaluationCandidateForm({ ...evaluationCandidateForm, sanitizedConfirmed: event.target.checked })} />已确认不包含姓名、联系方式、账号、原图地址等个人信息</label>
              <Button variant="secondary" className="secondary-btn" type="submit" disabled={evaluationCandidateLoading || !evaluationCandidateForm.sanitizedConfirmed}>{evaluationCandidateLoading ? '提交中...' : '加入候选库'}</Button>
            </form>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead><tr><th>问题</th><th>类型</th><th>期望</th><th>状态</th><th>提交信息</th><th>操作</th></tr></thead>
                <tbody>{evaluationCandidates.length ? evaluationCandidates.map(item => (
                  <tr key={item.id}>
                    <td><strong>#{item.id} {item.question}</strong><br /><span>{item.observedAnswer}</span></td>
                    <td>{item.issueType}</td>
                    <td>{item.expectedSkill || '-'}<br /><span>{(item.expectedTools || []).join('、') || '无指定工具'}</span></td>
                    <td>{item.status}</td>
                    <td>{item.submittedBy || '-'}<br /><span>{formatTime(item.createdAt)}</span></td>
                    <td><Button variant="secondary" className="secondary-btn" type="button" onClick={() => handleEvaluationCandidateStatus(item.id, 'APPROVED')} disabled={evaluationCandidateLoading}>通过</Button> <Button variant="danger" className="table-danger-btn" type="button" onClick={() => handleEvaluationCandidateStatus(item.id, 'REJECTED')} disabled={evaluationCandidateLoading}>拒绝</Button></td>
                  </tr>
                )) : <tr><td colSpan="6">暂无失败样本候选</td></tr>}</tbody>
              </table>
            </div>
          </div>

          {!assistantBenchmark && evaluationSection === 'results' && <div className="empty-box">暂无评测结果。可启动评测，或在评测历史中查看已有记录。</div>}
          {assistantBenchmark && evaluationSection === 'results' && (
            <div className="rag-benchmark-panel assistant-benchmark-panel">
              <div className="benchmark-progress" aria-label="助手评测进度">
                <div>
                  <strong>{benchmarkStatusLabel(assistantBenchmark.status)}</strong>
                  <span>{assistantBenchmark.message || ''}</span>
                </div>
                <span>{assistantBenchmark.completedCount ?? 0}/{assistantBenchmark.caseCount ?? 0}</span>
                <progress value={assistantBenchmark.completedCount ?? 0} max={assistantBenchmark.caseCount || 200} />
              </div>

              <div className="evaluation-summary-grid">
                <MetricSmall label="评测结论" value={benchmarkVerdictLabel(assistantBenchmark.verdict)} />
                <MetricSmall label={chain ? '链路通过率（工具与检索）' : toolOnly ? '工具调用准确率（规则）' : '质量通过率'} value={(Math.round((assistantBenchmark.passRate ?? 0) * 10000) / 100) + '%'} />
                <MetricSmall label="执行错误" value={assistantBenchmark.errorCount ?? 0} />
                <MetricSmall label="待人工复核" value={assistantReviewReport?.pendingRequiredCount ?? assistantDiagnostics.humanReviewCount} />
              </div>
              {chain && <RagChainMetrics cases={assistantBenchmark.cases || []} />}
              <div className="detail-entry"><span>完整指标包含意图识别、工具轨迹、耗时与人工复核数据。</span><Button variant="secondary" className="secondary-btn" type="button" onClick={() => setShowEvaluationMetrics(true)}>查看完整评测指标</Button></div>
              {showEvaluationMetrics && <DetailDrawer title="完整助手评测指标" onClose={() => setShowEvaluationMetrics(false)}>
              {toolOnly ? <div className="operations-grid compact-five">
                <MetricSmall label="评测模式" value={benchmarkModeLabel(assistantBenchmark.mode)} />
                <MetricSmall label="工具调用准确率（规则）" value={`${Math.round((assistantBenchmark.toolTracePassRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="工具规则通过 / 已完成（含错误）" value={`${assistantBenchmark.toolTracePassedCount ?? 0}/${assistantBenchmark.completedCount ?? 0}`} />
                <MetricSmall label="已完成 / 总题数" value={`${assistantBenchmark.completedCount ?? 0}/${assistantBenchmark.caseCount ?? 0}`} />
                <MetricSmall label="规则失败" value={assistantBenchmark.failedCount ?? 0} />
                <MetricSmall label="执行错误" value={assistantBenchmark.errorCount ?? 0} />
                <MetricSmall label="工具调用总数" value={assistantDiagnostics.toolCallCount} />
                <MetricSmall label="后端保障执行" value={(assistantBenchmark.cases || []).reduce((count, item) => count + (item.toolCalls || []).filter(call => call.origin === 'ORCHESTRATOR').length, 0)} />
                <MetricSmall label="模型请求工具" value={(assistantBenchmark.cases || []).reduce((count, item) => count + (item.toolCalls || []).filter(call => call.origin !== 'ORCHESTRATOR').length, 0)} />
                <MetricSmall label="生成模型" value={assistantBenchmark.model || '未配置'} />
                <MetricSmall label="回答质量 / 意图" value="未评测" />
                <MetricSmall label="裁判模型" value="未使用（规则评分）" />
                <MetricSmall label="数据集版本" value={assistantBenchmark.datasetVersion || '-'} />
                <MetricSmall label="数据集指纹" value={assistantBenchmark.datasetFingerprint?.slice(0, 16) || '-'} />
                <MetricSmall label="耗时" value={`${Math.round((assistantBenchmark.elapsedMs ?? 0) / 1000)} s`} />
              </div> :
              <div className="operations-grid compact-five">
                <MetricSmall label="评测模式" value={benchmarkModeLabel(assistantBenchmark.mode)} />
                <MetricSmall label="评测结论" value={benchmarkVerdictLabel(assistantBenchmark.verdict)} />
                <MetricSmall label="评测题数" value={assistantBenchmark.caseCount ?? 0} />
                <MetricSmall label="已完成" value={assistantBenchmark.completedCount ?? 0} />
                <MetricSmall label="有效裁判" value={assistantBenchmark.judgedCount ?? 0} />
                <MetricSmall label="通过" value={assistantBenchmark.passedCount ?? 0} />
                <MetricSmall label="质量失败" value={assistantBenchmark.failedCount ?? 0} />
                <MetricSmall label="执行错误" value={assistantBenchmark.errorCount ?? 0} />
                <MetricSmall label="意图命中" value={`${assistantBenchmark.intentMatchedCount ?? 0}/${assistantBenchmark.intentEvaluatedCount ?? 0}`} />
                <MetricSmall label="意图准确率" value={`${Math.round((assistantBenchmark.intentAccuracy ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="意图 Macro-F1" value={`${Math.round((assistantBenchmark.intentMacroF1 ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="工具轨迹通过" value={`${assistantBenchmark.toolTracePassedCount ?? 0}/${assistantBenchmark.judgedCount ?? 0}`} />
                <MetricSmall label="质量通过率" value={`${Math.round((assistantBenchmark.passRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="端到端通过率" value={`${Math.round((assistantBenchmark.endToEndPassRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="工具轨迹通过率" value={`${Math.round((assistantBenchmark.toolTracePassRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="执行成功率" value={`${Math.round((assistantBenchmark.executionSuccessRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="分类宏平均" value={`${Math.round((assistantBenchmark.macroCategoryPassRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="质量平均分" value={`${assistantBenchmark.averageScore ?? 0}/4`} />
                <MetricSmall label="端到端平均分" value={`${assistantBenchmark.endToEndAverageScore ?? 0}/4`} />
                <MetricSmall label="触发硬门禁用例" value={assistantBenchmark.hardGateFailureCount ?? 0} />
                <MetricSmall label="生成模型" value={assistantBenchmark.model || '未配置'} />
                <MetricSmall label="裁判模型" value={assistantBenchmark.judgeModel || '未配置'} />
                <MetricSmall label="独立裁判" value={assistantBenchmark.independentJudge ? '是' : '否（同模型）'} />
                <MetricSmall label="数据集版本" value={assistantBenchmark.datasetVersion || '旧版本未记录'} />
                <MetricSmall label="数据集指纹" value={assistantBenchmark.datasetFingerprint?.slice(0, 16) || '-'} />
                <MetricSmall label="耗时" value={`${Math.round((assistantBenchmark.elapsedMs ?? 0) / 1000)} s`} />
                <MetricSmall label="平均单题耗时" value={`${assistantDiagnostics.averageCaseDurationMs} ms`} />
                <MetricSmall label="单题 P95 耗时" value={`${assistantDiagnostics.p95CaseDurationMs} ms`} />
                <MetricSmall label="单题最大耗时" value={`${assistantDiagnostics.maxCaseDurationMs} ms`} />
                <MetricSmall label="工具调用总数" value={assistantDiagnostics.toolCallCount} />
                <MetricSmall label="工具降级用例" value={assistantDiagnostics.degradedCaseCount} />
                <MetricSmall label="建议人工复核" value={assistantDiagnostics.humanReviewCount} />
                <MetricSmall label="人工已复核" value={`${assistantReviewReport?.reviewedRequiredCount ?? 0}/${assistantReviewReport?.requiredReviewCount ?? assistantDiagnostics.humanReviewCount}`} />
                <MetricSmall label="待人工复核" value={assistantReviewReport?.pendingRequiredCount ?? assistantDiagnostics.humanReviewCount} />
                <MetricSmall label="裁判一致率" value={`${Math.round((assistantReviewReport?.agreementRate ?? 0) * 10000) / 100}%`} />
                <MetricSmall label="自动误放" value={assistantReviewReport?.falsePassCount ?? 0} />
                <MetricSmall label="自动误杀" value={assistantReviewReport?.falseFailCount ?? 0} />
              </div>}
              </DetailDrawer>}

              <SectionSwitcher label="评测结果明细" value={benchmarkSection} onChange={setBenchmarkSection} items={[["summary", "结果摘要"], ["breakdown", "分类与意图"], ["cases", "逐题结果与复核"]]} />
              {benchmarkSection === 'breakdown' && <>
              {(assistantBenchmark.intents || []).length > 0 && (
                <div className="admin-table-wrap">
                  <table className="admin-table">
                    <thead>
                      <tr><th>意图 Skill</th><th>标准样本</th><th>预测次数</th><th>严格命中</th><th>精确率</th><th>召回率</th><th>F1</th></tr>
                    </thead>
                    <tbody>
                      {assistantBenchmark.intents.map(item => (
                        <tr key={item.skill}>
                          <td>{item.skill}</td>
                          <td>{item.expectedCount}</td>
                          <td>{item.predictedCount}</td>
                          <td>{item.truePositiveCount}</td>
                          <td>{Math.round((item.precision ?? 0) * 10000) / 100}%</td>
                          <td>{Math.round((item.recall ?? 0) * 10000) / 100}%</td>
                          <td>{Math.round((item.f1 ?? 0) * 10000) / 100}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {(assistantBenchmark.categories || []).length > 0 && (
                <div className="admin-table-wrap">
                  <table className="admin-table">
                    <thead>
                      <tr><th>类别</th><th>完成</th><th>{toolOnly ? '有效执行' : '有效裁判'}</th><th>通过</th><th>{toolOnly ? '规则失败' : '质量失败'}</th><th>执行错误</th><th>{toolOnly ? '工具规则通过率' : '质量通过率'}</th><th>质量平均分</th></tr>
                    </thead>
                    <tbody>
                      {assistantBenchmark.categories.map(item => (
                        <tr key={item.category}>
                          <td>{assistantCategoryLabel(item.category)}</td>
                          <td>{item.caseCount}</td>
                          <td>{item.judgedCount}</td>
                          <td>{item.passedCount}</td>
                          <td>{item.failedCount}</td>
                          <td>{item.errorCount}</td>
                          <td>{Math.round((item.passRate ?? 0) * 10000) / 100}%</td>
                          <td>{toolOnly ? '未评测' : `${item.averageScore ?? 0}/4`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              </>}
              {benchmarkSection === 'cases' && <>
              {(assistantBenchmark.cases || []).length > 0 && (
                <div className="admin-table-wrap">
                  <table className="admin-table assistant-benchmark-table">
                    <thead>
                      <tr><th>用例</th><th>类别</th><th>风险</th><th>结果</th><th>得分</th><th>执行路径</th><th>意图识别</th><th>工具轨迹</th><th>人工复核</th><th>硬门禁</th><th>规则 / 裁判说明</th><th>助手回答</th></tr>
                    </thead>
                    <tbody>
                      {assistantBenchmark.cases.map(item => (
                        <tr key={item.id}>
                          <td><strong>{item.id}</strong><br /><span>{item.question}</span></td>
                          <td>{assistantCategoryLabel(item.category)}</td>
                          <td>{item.riskLevel}{needsHumanReview(item) && <><br /><strong className="benchmark-fail">需复核</strong></>}</td>
                          <td className={item.passed ? 'benchmark-pass' : 'benchmark-fail'}>{item.status}</td>
                          <td>{toolOnly ? '未评回答质量' : `${item.weightedScore ?? 0}/4`}</td>
                          <td><strong>{item.executionPath || '-'}</strong><br /><span>{item.toolRounds ?? 0} 轮</span></td>
                          <td className={toolOnly ? 'intent-result-cell' : item.intentMatched ? 'benchmark-pass intent-result-cell' : 'benchmark-fail intent-result-cell'}>
                            <strong>{toolOnly ? '未评测' : item.intentMatched ? '命中' : '不匹配'}</strong><br />
                            <span>实际：{item.selectedSkill || '-'}</span><br />
                            <span>标准：{item.expectedSkill || '-'}</span>
                            {(item.acceptableSkills || []).length > 1 && <><br /><span>可接受：{item.acceptableSkills.join('、')}</span></>}
                          </td>
                          <td className={item.status !== 'ERROR' && item.toolTracePassed ? 'benchmark-pass tool-trace-cell' : 'benchmark-fail tool-trace-cell'}>
                            <strong>{item.status !== 'ERROR' && item.toolTracePassed ? '通过' : '失败'}</strong>{item.degraded ? '（降级）' : ''}<br />
                            <span>预期：{(item.expectedTools || []).join('、') || '无'}</span><br />
                            <span>实际：{(item.calledTools || []).join('、') || '无'}</span>
                            {(item.missingExpectedTools || []).length > 0 && <><br /><span>缺少：{item.missingExpectedTools.join('、')}</span></>}
                            {(item.forbiddenCalledTools || []).length > 0 && <><br /><span>禁用：{item.forbiddenCalledTools.join('、')}</span></>}
                            {(item.toolCalls || []).map((call, index) => (
                              <div className="tool-call-detail" key={`${call.name}-${call.round}-${index}`}>
                                <span>{call.origin === 'ORCHESTRATOR' ? '后端保障执行' : '模型请求'} · 第 {call.round} 轮 · {call.name} · {call.status} · {call.durationMs} ms</span>
                                <code>{call.arguments || '{}'}</code>
                              </div>
                            ))}
                            <RagChainCase report={item.ragChain} />
                          </td>
                          <td className="summary-cell">
                            {assistantReviewsByCase.has(item.id) ? (
                              <>
                                <strong className={assistantReviewsByCase.get(item.id).humanVerdict === 'PASSED' ? 'benchmark-pass' : 'benchmark-fail'}>
                                  {assistantReviewsByCase.get(item.id).humanVerdict === 'PASSED' ? '人工通过' : '人工不通过'}
                                </strong><br />
                                <span>{assistantReviewsByCase.get(item.id).reviewer || '-'}</span><br />
                                <span>{assistantReviewsByCase.get(item.id).notes || '无备注'}</span>
                              </>
                            ) : <span>未复核</span>}
                            <div className="knowledge-card-actions">
                              <Button variant="secondary" className="secondary-btn" type="button" onClick={() => handleReviewAssistantCase(item, 'PASSED')} disabled={['PENDING', 'RUNNING'].includes(assistantBenchmark.status) || reviewingCaseId === item.id}>通过</Button>
                              <Button variant="danger" className="table-danger-btn" type="button" onClick={() => handleReviewAssistantCase(item, 'FAILED')} disabled={['PENDING', 'RUNNING'].includes(assistantBenchmark.status) || reviewingCaseId === item.id}>不通过</Button>
                            </div>
                          </td>
                          <td>{toolOnly ? '未评测' : (item.hardGateFailures || []).join('、') || '通过'}</td>
                          <td className="summary-cell">{item.reason || '-'}</td>
                          <td className="summary-cell benchmark-answer">{item.answer || '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              </>}
            </div>
          )}
        </section>
  </>
}
