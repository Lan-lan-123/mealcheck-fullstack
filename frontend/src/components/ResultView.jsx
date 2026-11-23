import Button from './Button'
import PlanSaveButton from "./PlanSaveButton"
import { categoryName } from '../utils/mealLabels'
import MealNutritionPanel from './MealNutritionPanel'
import { needsFoodReview } from '../utils/recognitionExplanation'
import { foodAttributeSummary } from '../utils/foodClassification'
import ScoreDetails from './ScoreDetails'

/** @param {{result: import('../api/contracts').MealAnalysisResult; compact?: boolean; onViewDetails?: () => void; onReview?: () => void}} props */
export default function ResultView({ result, compact = false, onViewDetails, onReview }) {
  const evaluation = result?.evaluation || {}
  const recognition = result?.recognition || {}
  const foods = Array.isArray(recognition.foods) ? recognition.foods : []
  const riskTags = Array.isArray(evaluation.riskTags) ? evaluation.riskTags : []
  const references = Array.isArray(result?.references) ? result.references : []
  const uncertainFoods = recognition.uncertainFoods || []
  const reviewFoods = [...foods.filter(needsFoodReview).map(food => ({ food, excluded: false })), ...uncertainFoods.map(food => ({ food, excluded: true }))]

  return (
    <div className={compact ? 'result-summary' : 'result-detail'}>
      <div className="score-circle">
        <strong>{evaluation.score ?? '--'}</strong>
        <span>分</span>
      </div>

      <p className="summary">{evaluation.summary || '暂无分析摘要'}</p>
      {!compact && <ScoreDetails breakdown={evaluation.scoreBreakdown} change={result.scoreChange} />}

      {recognition.demoMode && <span className="badge warn">演示识别模式</span>}

      <h3>识别食物</h3>
      <div className="chips">
        {(compact ? foods.slice(0, 4) : foods).map((f, i) => (
          <span key={i} className={needsFoodReview(f) ? 'food-needs-review' : undefined}>
            {f.name} · {categoryName(f.category)}{foodAttributeSummary(f) && ` · ${foodAttributeSummary(f)}`}{needsFoodReview(f) && ' · 待核对'}{f.structureRoles?.filter(role => role.source === 'VISIBLE_COMPONENT').map((role, j) => <small key={j} title={role.evidence}> · 可见{categoryName(role.role)}成分</small>)}
          </span>
        ))}
        {compact && foods.length > 4 && <span>另有 {foods.length - 4} 项</span>}
      </div>
      {!!reviewFoods.length && <section className="recognition-review" aria-label="识别结果待核对">
        <h3>这些食物需要你核对</h3>
        <p>这些食物的模型置信度低于 80%，或识别线索、名称、做法仍有不确定项。置信度仅供核对参考，不等于识别正确的概率。</p>
        <ul>{reviewFoods.map(({ food, excluded }, index) => <li key={index}>
          <strong>{food.name}</strong> · {categoryName(food.category)} · 置信度 {Math.floor((food.confidence ?? 0) * 100)}%
          {excluded && <span> · 未计入当前评分</span>}
          {food.note && <p>识别线索：{food.note}</p>}
          {food.reviewIssues?.map((issue, i) => <p key={i}>{issue.message}</p>)}
        </li>)}</ul>
        <p>请对照照片检查名称、分类及遗漏项，确认后通过纠正识别重新评分。</p>
        {onReview && <Button variant="secondary" type="button" onClick={onReview}>核对并纠正识别</Button>}
      </section>}

      <h3>风险标签</h3>
      <div className="chips risk">
        {riskTags.length ? (
          (compact ? riskTags.slice(0, 3) : riskTags).map(x => <span key={x}>{x}</span>)
        ) : (
          <span>暂无明显风险</span>
        )}
      </div>

      {result?.adviceStatus?.startsWith('RULE_') && <p role="status" className="notice warn">当前展示规则建议{result.adviceFailureCode === 'MODEL_RATE_LIMITED' ? '：模型服务限流，可稍后重试。' : result.adviceStatus === 'RULE_VALIDATION' ? '：模型建议未通过事实一致性检查。' : result.adviceStatus === 'RULE_ONLY' ? '：模型尚未配置。' : '：模型服务暂不可用。'}</p>}
      {compact && <><h3>饮食建议摘要</h3><p className="advice-summary">{result?.advice || '暂无建议'}</p></>}
      {!compact && <>
      <h3>RAG 饮食建议</h3>
      <pre className="advice">{result?.advice || '暂无建议'}</pre>
      {result?.recordId && <PlanSaveButton key={result.recordId} text={result.advice} sourceType="MEAL" sourceRecordId={result.recordId} />}

      <h3>参考知识片段</h3>
      {references.map(ref => (
        <details key={ref.id}>
          <summary>
            {ref.title} · 相似度 {Number(ref.score).toFixed(2)}
          </summary>
          <p>{ref.content}</p>
        </details>
      ))}
      </>}
      {compact && <Button variant="secondary" className="secondary-btn" type="button" onClick={onViewDetails}>查看完整分析与建议</Button>}
      {compact && result?.recordId && <Button variant="secondary" className="secondary-btn" type="button" onClick={onViewDetails}>可选份量与营养估算</Button>}
      {!compact && result?.recordId && <MealNutritionPanel key={`${result.recordId}:${result.revision || 0}`} recordId={result.recordId} />}
    </div>
  )
}
