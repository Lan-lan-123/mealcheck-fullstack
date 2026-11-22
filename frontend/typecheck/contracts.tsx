import Button from '../src/components/Button'
import { mealDetail, listMealSummaries, mealAnalysisRequest, weeklyReportJob, mealNutrition, updateMealNutrition, assistantConversation, askDietAssistant, listMeals } from '../src/api/client'
import MealNutritionPanel from '../src/components/MealNutritionPanel'
import AssistantCard from '../src/components/AssistantCard'
import type { MealAnalysisState, WeeklyReportJob } from '../src/api/contracts'

// Compile-time contract checks are never bundled into the application.
async function contracts() {
  const page = await listMealSummaries({ page: 0, size: 20 })
  page.content[0]?.foodNames.map(name => name.toUpperCase())
  const meal = await mealDetail(1)
  meal.foods.map(food => food.category)
  const analysis: MealAnalysisState = await mealAnalysisRequest('request-key')
  const weekly: WeeklyReportJob = await weeklyReportJob(1)
  const nutrition = await mealNutrition(1)
  nutrition.items.map(item => item.per100g?.energyKcal)
  const conversation = await assistantConversation(1)
  conversation.messages.map(message => message.text.toUpperCase())
  const answer = await askDietAssistant('下一餐如何搭配？')
  answer.references?.map(reference => reference.content.toUpperCase())
  return [analysis, weekly]
}
void contracts

const button = <Button variant="primary" busy type="submit">保存</Button>
// @ts-expect-error Unsupported variants must fail the CI check.
const invalidButton = <Button variant="white-old-style" />
// @ts-expect-error Summary pages intentionally do not contain full advice.
listMealSummaries().then(page => page.content[0].advice)
// @ts-expect-error Backend task statuses are a closed contract.
const invalidTask: MealAnalysisState = { key: 'key', status: 'FINISHED' }
void [button, invalidButton, invalidTask]
// @ts-expect-error Nutrition IDs cannot become strings unnoticed.
const invalidNutrition = <MealNutritionPanel recordId="1" />
// @ts-expect-error The assistant publishes readable error messages.
const invalidAssistant = <AssistantCard onError={(error: Error) => console.log(error.message)} />
// @ts-expect-error Save versions are required to prevent stale overwrites.
updateMealNutrition(1, { enabled: true, items: [] })
// @ts-expect-error Conversation messages are a validated array of typed messages.
assistantConversation(1).then(detail => detail.messages.trim())
// @ts-expect-error The legacy meal endpoint may return an array; consumers must narrow its shape.
listMeals().then(meals => meals.content)
void [invalidNutrition, invalidAssistant]
