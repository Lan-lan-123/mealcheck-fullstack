import Button from './Button'
import { quickReviewQuestions } from '../utils/recognitionExplanation'
/** @param {{food: import('../api/contracts').FoodItem; index: number; disabled?: boolean; onAnswer: (key: string, answer: string) => void}} props */
export default function QuickFoodReview({ food, index, disabled, onAnswer }) {
  const questions = quickReviewQuestions(food)
  if (!questions.length) return null
  return <fieldset className="quick-food-review" aria-label={`快捷核对 食物 ${index + 1}`}>
    <legend>快捷核对：{food.name}</legend>
    {food.note && <p>识别线索：{food.note}</p>}
    {food.reviewIssues?.map((issue, i) => <p key={i} role="status">{issue.message}</p>)}
    {questions.map(question => <div key={question.key}><p>{question.label}</p>
      <div className="quick-review-options">{question.options.map(answer => <Button key={answer} variant="secondary" type="button" disabled={disabled}
        aria-label={`食物 ${index + 1} ${question.label} ${answer}`} onClick={() => onAnswer(question.key, answer)}>{answer}</Button>)}</div>
    </div>)}
    <p className="hint">也可在下方直接修改名称和分类。选择“不确定”会保留待核对状态，不代表已确认风险；最后确认核对内容再保存。</p>
  </fieldset>
}
