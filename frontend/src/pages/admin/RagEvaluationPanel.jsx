import Button from '../../components/Button'
import { useState } from 'react'
import { adminRagEvaluation } from '../../api/client'
import { MetricSmall, categoryLabel } from './AdminUi'
import DetailDrawer from '../../components/DetailDrawer'
import AssistantEvaluationPanel from './AssistantEvaluationPanel'

export default function RagEvaluationPanel({ enabled }) {
  return <>
    <AssistantEvaluationPanel enabled={enabled} ragOnly />
    {enabled && <details className="workspace-disclosure">
      <summary>单独检索诊断（可选）</summary>
      <RagRetrievalDiagnostic enabled={enabled} />
    </details>}
  </>
}

function RagRetrievalDiagnostic({ enabled }) {
  const [ragBenchmark, setRagBenchmark] = useState(null)
  const [showRagMetrics, setShowRagMetrics] = useState(false)
  async function handleRunRagEvaluation() {
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const result = await adminRagEvaluation()
      setRagBenchmark(result)
      setMessage('RAG 评测已完成')
    } catch (err) {
      setError(err.message || 'RAG 评测失败')
    } finally {
      setLoading(false)
    }
  }
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(''),
    [message, setMessage] = useState('')
  return (
    <>
      <section className="admin-section" hidden={!enabled}>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {message && <p role="status">{message}</p>}
        <h2>单独检索诊断</h2>
        <p className="hint">使用当前生效向量运行 200 道检索题，可查看逐题结果。语义模式会调用向量接口；此处不生成最终回答，引用是否支持回答、是否误拒答需另做回答评测。</p>
        <p className="hint">此诊断独立运行检索，保留标题命中的代理指标，不代表上方使用正文绑定标注的链路评测结果。完整工具与检索流程使用上方“RAG 链路评测（200 题）”。</p>
        <div className="knowledge-toolbar">
          <Button
            variant="secondary"
            className="secondary-btn"
            type="button"
            onClick={handleRunRagEvaluation}
            disabled={loading}
          >
            运行 RAG 评测集
          </Button>
        </div>

        {ragBenchmark && (
          <div className="rag-benchmark-panel">
            <div className="evaluation-summary-grid">
              <MetricSmall label="评测题数" value={ragBenchmark.caseCount ?? 0} />
              <MetricSmall label="Hit@3" value={ragBenchmark.hitAt3 ?? 0} />
              <MetricSmall label="MRR" value={ragBenchmark.mrr ?? 0} />
              <MetricSmall label="回归检查" value={ragBenchmark.regressionPassed ? '通过' : '未通过'} />
            </div>
            <div className="detail-entry">
              <span>完整指标包含初排、重排、无答案检索质量和检索耗时。</span>
              <Button
                variant="secondary"
                className="secondary-btn"
                type="button"
                onClick={() => setShowRagMetrics(true)}
              >
                查看完整检索评测指标
              </Button>
            </div>
            {showRagMetrics && (
              <DetailDrawer title="完整检索评测指标" onClose={() => setShowRagMetrics(false)}>
                <div className="operations-grid compact-five">
                  <MetricSmall label="评测题数" value={ragBenchmark.caseCount ?? 0} />
                  <MetricSmall label="有答案题数" value={ragBenchmark.answerableCaseCount ?? 0} />
                  <MetricSmall label="无答案题数" value={ragBenchmark.noAnswerCaseCount ?? 0} />
                  <MetricSmall label="初排 Hit@3" value={ragBenchmark.baselineHitAt3 ?? 0} />
                  <MetricSmall label="Hit@3" value={ragBenchmark.hitAt3 ?? 0} />
                  <MetricSmall label="初排 Hit@5" value={ragBenchmark.baselineHitAt5 ?? 0} />
                  <MetricSmall label="Hit@5" value={ragBenchmark.hitAt5 ?? 0} />
                  <MetricSmall label="初排 MRR" value={ragBenchmark.baselineMrr ?? 0} />
                  <MetricSmall label="MRR" value={ragBenchmark.mrr ?? 0} />
                  <MetricSmall label="初排 nDCG@5" value={ragBenchmark.baselineNdcgAt5 ?? 0} />
                  <MetricSmall label="nDCG@5" value={ragBenchmark.ndcgAt5 ?? 0} />
                  <MetricSmall label="初排类别@3" value={ragBenchmark.baselineCategoryAccuracy ?? 0} />
                  <MetricSmall label="类别@3(1条)" value={ragBenchmark.categoryAccuracy ?? 0} />
                  <MetricSmall
                    label="重排模型"
                    value={ragBenchmark.rerankerEnabled ? ragBenchmark.rerankerProvider || '已启用' : '未启用'}
                  />
                  <MetricSmall label="回归检查" value={ragBenchmark.regressionPassed ? '通过' : '未通过'} />
                  <MetricSmall label="平均检索耗时" value={`${ragBenchmark.averageRetrievalDurationMs ?? 0} ms`} />
                  <MetricSmall label="平均重排耗时" value={`${ragBenchmark.averageRerankDurationMs ?? 0} ms`} />
                  <MetricSmall label="平均候选数" value={ragBenchmark.averageCandidateCount ?? 0} />
                  <MetricSmall label="过滤后平均结果" value={ragBenchmark.averageResultCount ?? 0} />
                  <MetricSmall label="空结果率" value={ragBenchmark.emptyResultRate ?? 0} />
                  <MetricSmall label="初排无答案检索准确率" value={ragBenchmark.baselineNoAnswerAccuracy ?? 0} />
                  <MetricSmall label="无答案检索准确率" value={ragBenchmark.noAnswerAccuracy ?? 0} />
                  <MetricSmall label="无答案误召回率" value={ragBenchmark.falsePositiveRate ?? 0} />
                  <MetricSmall label="评测耗时" value={`${ragBenchmark.evaluationDurationMs ?? 0} ms`} />
                </div>
              </DetailDrawer>
            )}

            <details className="workspace-disclosure">
              <summary>查看逐题检索结果</summary>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>问题</th>
                      <th>类型</th>
                      <th>Hit@3</th>
                      <th>Hit@5</th>
                      <th>首个相关排名</th>
                      <th>nDCG@5</th>
                      <th>期望类别</th>
                      <th>类别/无答案检索命中</th>
                      <th>粗排/精排/最终分</th>
                      <th>候选/输出</th>
                      <th>阈值/去重过滤</th>
                      <th>Top5 标题</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(ragBenchmark.cases || []).map((item) => (
                      <tr key={item.question}>
                        <td>{item.question}</td>
                        <td>{item.expectNoAnswer ? '无答案' : '有答案'}</td>
                        <td>{item.expectNoAnswer ? '-' : item.hitAt3 ? '是' : '否'}</td>
                        <td>{item.expectNoAnswer ? '-' : item.hitAt5 ? '是' : '否'}</td>
                        <td>{item.expectNoAnswer ? '-' : item.firstRelevantRank || '-'}</td>
                        <td>{item.expectNoAnswer ? '-' : (item.ndcgAt5 ?? 0)}</td>
                        <td>{categoryLabel(item.expectedCategory)}</td>
                        <td>
                          {item.expectNoAnswer
                            ? item.noAnswerCorrect
                              ? '正确返回空结果'
                              : '发生误召回'
                            : item.categoryHitAt3
                              ? '是'
                              : '否'}
                        </td>
                        <td>
                          {item.topRoughScore ?? 0}/{item.topRerankerRawScore ?? '-'}/{item.topFinalScore ?? 0}
                        </td>
                        <td>
                          {item.candidateCount ?? 0}/{item.finalResultCount ?? 0}
                        </td>
                        <td>
                          {item.thresholdFilteredCount ?? 0}/{item.duplicateFilteredCount ?? 0}
                        </td>
                        <td>{(item.topTitles || []).join(' / ') || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
          </div>
        )}
      </section>
    </>
  )
}
