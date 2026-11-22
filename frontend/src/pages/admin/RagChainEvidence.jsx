import { MetricSmall } from './AdminUi'
import { ragChainMetrics } from '../../utils/ragChainMetrics'

const labels = { PASSED: '最终证据已覆盖', FAILED: '未通过', UNJUDGED: '检索结果待相关性判断', NOT_CALLED: '未调用检索', NOT_REQUIRED: '无需检索', ERROR: '执行错误' }

export function RagChainMetrics({ cases = [] }) {
  const metrics = ragChainMetrics(cases)
  const limitedLongformLabels = cases.some(row => ['rag-evidence-v2-longform', 'rag-evidence-v3-topic-specific', 'rag-evidence-v4-longform', 'rag-evidence-v5-reviewed-chunks', 'rag-evidence-v6-boundary-aligned'].includes(row.ragChain?.assessmentVersion))
  const unmeasured = metrics.answerableCount ? '旧记录未评测' : '尚无已完成知识题'
  const rate = value => value == null ? '尚无可计算样本' : `${Math.round(value * 10000) / 100}%`
  const average = value => value == null ? '尚无可计算样本' : value.toFixed(3)
  return <div className="rag-chain-metrics">
    <h3>同题链路阶段</h3>
    <div className="operations-grid compact-five">
      <MetricSmall label="知识题首轮检索成功返回" value={`${metrics.executedCount}/${metrics.answerableCount}`} />
      <MetricSmall label="首轮排名可计算题数" value={metrics.rankingMeasuredCount} />
      <MetricSmall label="首轮排名待判断题数" value={metrics.rankingPendingCount} />
      <MetricSmall label="最终证据待判断题数" value={metrics.finalPendingCount} />
      <MetricSmall label="待判断切片返回次数" value={metrics.unjudgedHitCount} />
      <MetricSmall label="首轮 Hit@3（首轮标注完整子集）" value={rate(metrics.firstHitAt3)} />
      <MetricSmall label="首轮 Hit@5（首轮标注完整子集）" value={rate(metrics.firstHitAt5)} />
      <MetricSmall label="首轮 MRR（首轮标注完整子集）" value={average(metrics.firstMrr)} />
      <MetricSmall label="首轮 nDCG@5（首轮标注完整子集）" value={average(metrics.firstNdcg)} />
      <MetricSmall label="最终证据已覆盖（含错误题）" value={metrics.finalMeasuredCount ? `${metrics.finalCoveredCount}/${metrics.finalMeasuredCount}` : unmeasured} />
      <MetricSmall label="补充检索获得证据" value={metrics.finalMeasuredCount ? metrics.recoveredCount : unmeasured} />
      <MetricSmall label="信息不足边界题" value={metrics.insufficientEvidenceCount} />
      <MetricSmall label="标注待独立人工复核" value={metrics.pendingReviewCount} />
      <MetricSmall label="知识题未调用检索" value={metrics.notCalledCount} />
      <MetricSmall label="检索阶段错误" value={metrics.retrievalErrorCount} />
      <MetricSmall label="库外题正确不调用" value={`${metrics.boundaryCorrectCount}/${metrics.boundaryCount}`} />
    </div>
    <p className="hint">首轮指标以首次检索成功且返回切片均可判断的知识题为分母，空结果计未命中。最终覆盖检查全部实际查询是否找到直接证据，背景相关不等于证据齐全；补充查询可以补齐证据，首轮指标保持原样。证据覆盖包含已取得证据但随后报错的题，链路通过率仍将执行错误计为未通过。新口径使用正文指纹、原文锚点和分级标注；标注仍待独立人工复核。边界题只检查是否找到信息不足的依据，不自动评分最终回答。</p>
    <p className="hint">含未标注切片的首轮查询单列为待判断，不进入排名指标分母；空结果仍计未命中。已覆盖必要证据时可以确认覆盖，证据不足且含未标注结果时单列待判断。数值只反映可判断子集，不能据此推断全量准确率；总链路通过仍须确认必要证据与工具规则。</p>
    {limitedLongformLabels && <p className="hint">当前证据标注包含保留的原文锚点及分批补充的助手复核结果，覆盖范围有限，仍待独立人工相关性复核。</p>}
  </div>
}

export function RagChainCase({ report }) {
  if (!report) return null
  return <details className="tool-call-detail">
    <summary>真实检索：{labels[report.retrievalStatus] || report.retrievalStatus}</summary>
    <p>预期知识：{report.expectedTitleKeywords?.join('、') || '库外题，无需检索'}</p>
    {report.assessmentVersion && !report.expectNoAnswer && <p>首轮相关排名：{report.firstRelevantRank || '未命中'}；最终证据组覆盖：{Math.round((report.finalEvidenceCoverage || 0) * 100)}%{report.recoveredBySupplement ? '；补充检索补齐证据，首轮记录保留' : ''}</p>}
    {report.annotationStatus === 'PENDING_HUMAN_REVIEW' && <p>标注状态：待独立人工复核</p>}
    {report.judgmentCoverage && <p>切片判断：首轮 {report.judgmentCoverage.primaryHitCount} 次返回中 {report.judgmentCoverage.primaryUnjudgedCount} 次待判断；全部查询 {report.judgmentCoverage.totalUnjudgedCount} 次待判断，{report.judgmentCoverage.invalidContentCount} 次正文身份无效。{report.judgmentCoverage.primaryRankingComplete ? '' : '当前首轮排名数值仅描述已标注证据，不计入汇总排名指标。'}</p>}
    {report.expectedEvidence?.length > 0 && <details><summary>查看证据标注与原文锚点</summary>
      {report.expectedEvidence.map(target => <div key={target.knowledgeId}>
        <p>{target.relevance === 2 ? '直接证据' : '相关背景'}：{target.title}</p>
        <p>{target.rationale}</p>
        {target.evidenceQuotes?.map((quote, index) => <blockquote key={index}>{quote}</blockquote>)}
      </div>)}
    </details>}
    {(report.retrievals || []).map((search, index) => <div key={`${index}-${search.toolCallId || ''}`}>
      <p>{index === 0 ? '首次检索' : '补充检索'} · {search.status} · {search.durationMs} ms · 请求 {search.requestedLimit} 条</p>
      <p>实际查询：{search.query}</p>
      {search.errorType && <p role="status">错误类型：{search.errorType}</p>}
      {search.hits?.length ? <ol>{search.hits.map((hit, rank) => <li key={`${hit.id}-${rank}`}>{hit.title}（ID {hit.id}，分数 {hit.score}）
        {hit.content && <details><summary>查看实际返回原文</summary><p>{hit.content}</p></details>}
      </li>)}</ol> : <p>未返回知识条目</p>}
    </div>)}
  </details>
}
