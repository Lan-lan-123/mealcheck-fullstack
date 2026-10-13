import { currentGoal, latestWeeklyReport, listMeals, mealTrends } from '../api/client'
import { compactFilters, emptyPage, normalizePage } from '../utils/pageData'
import usePageResource from './usePageResource'
import useDietDataRevision from './useDietDataRevision'

const trackingChanges = ['meals', 'behavior-goals', 'plans', 'diet-goal']

export default function useUserWorkspaceData(section, { recordPage, recordSize, recordFilters, dataRevision, reportPending }) {
  const trackingRevision = useDietDataRevision(trackingChanges)
  const records = usePageResource({ key: JSON.stringify([recordPage, recordSize, recordFilters, dataRevision]), enabled: section === 'records', initialData: emptyPage(recordSize), load: async () => normalizePage(await listMeals({ page: recordPage, size: recordSize, ...compactFilters(recordFilters) }), recordSize) })
  const trend = usePageResource({ key: `trend:${dataRevision}:${trackingRevision}`, enabled: section === 'trends', load: () => mealTrends(30) })
  const report = usePageResource({ key: `report:${dataRevision}:${trackingRevision}`, enabled: section === 'reports' && !reportPending, load: latestWeeklyReport })
  const goal = usePageResource({ key: 'goal', enabled: ['overview', 'goals'].includes(section), load: currentGoal })
  const active = { records, trends: trend, reports: report, goals: goal, overview: goal, plans: { loading: false, error: null, invalidate: () => {} } }[section]
  return { records, trend, report, goal, loading: active.loading, error: active.error, retry: active.invalidate }
}
