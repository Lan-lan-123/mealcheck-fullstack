// @ts-check
import { currentGoal, latestWeeklyReport, listMealSummaries, mealTrends, todayMeals } from '../api/client'
import useBusinessDay from './useBusinessDay'
import { compactFilters, emptyPage, normalizePage } from '../utils/pageData'
import usePageResource from './usePageResource'
import useDietDataRevision from './useDietDataRevision'

const trackingChanges = ['meals', 'behavior-goals', 'plans', 'diet-goal', 'diet-preferences']

/** @param {string} section @param {import('../api/contracts').UserWorkspaceParameters} parameters */
export default function useUserWorkspaceData(section, { recordPage, recordSize, recordFilters, dataRevision, reportPending }) {
  const trackingRevision = useDietDataRevision(trackingChanges)
  const day = useBusinessDay()
  const today = usePageResource({ key: `today:${day}:${dataRevision}:${trackingRevision}`, enabled: section === 'overview', load: todayMeals })
  const records = usePageResource({ key: JSON.stringify([recordPage, recordSize, recordFilters, dataRevision]), enabled: section === 'records', initialData: emptyPage(recordSize), load: async signal => normalizePage(await listMealSummaries({ page: recordPage, size: recordSize, ...compactFilters(recordFilters) }, signal), recordSize) })
  const trend = usePageResource({ key: `trend:${dataRevision}:${trackingRevision}`, enabled: section === 'trends', load: signal => mealTrends(30, signal) })
  const report = usePageResource({ key: `report:${dataRevision}:${trackingRevision}`, enabled: section === 'reports' && !reportPending, load: latestWeeklyReport })
  const goal = usePageResource({ key: 'goal', enabled: ['overview', 'goals'].includes(section), load: currentGoal })
  const active = section === 'records' ? records : section === 'trends' ? trend : section === 'reports' ? report : section === 'plans' ? { loading: false, error: '', invalidate: () => {} } : goal
  return { records, trend, report, goal, today, day, loading: active.loading, error: active.error,
    retry: () => { active.invalidate(); if (section === 'overview') today.invalidate() } }
}
