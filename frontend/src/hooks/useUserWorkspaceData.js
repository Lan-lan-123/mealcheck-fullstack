import { currentGoal, latestWeeklyReport, listMeals, mealTrends } from '../api/client'
import { compactFilters, emptyPage, normalizePage } from '../utils/pageData'
import usePageResource from './usePageResource'

export default function useUserWorkspaceData(section, { recordPage, recordSize, recordFilters, dataRevision, reportPending }) {
  const records = usePageResource({ key: JSON.stringify([recordPage, recordSize, recordFilters, dataRevision]), enabled: section === 'records', initialData: emptyPage(recordSize), load: async () => normalizePage(await listMeals({ page: recordPage, size: recordSize, ...compactFilters(recordFilters) }), recordSize) })
  const trend = usePageResource({ key: `trend:${dataRevision}`, enabled: section === 'trends', load: () => mealTrends(30) })
  const report = usePageResource({ key: `report:${dataRevision}`, enabled: section === 'reports' && !reportPending, load: latestWeeklyReport })
  const goal = usePageResource({ key: 'goal', enabled: ['overview', 'goals'].includes(section), load: currentGoal })
  const active = { records, trends: trend, reports: report, goals: goal, overview: goal }[section]
  return { records, trend, report, goal, loading: active.loading, error: active.error, retry: active.invalidate }
}
