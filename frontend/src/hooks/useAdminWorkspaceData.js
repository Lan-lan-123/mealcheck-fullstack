import { adminAuditLogs, adminDashboard, adminKnowledgeChunks, adminMealAnalytics, adminMeals, adminNonFoodUploads, adminUsers, healthCheck } from '../api/client'
import { emptyPage, normalizePage } from '../utils/pageData'
import usePageResource from './usePageResource'

export default function useAdminWorkspaceData(tab, parameters) {
  const { userPage, userSize, userFilters, mealPage, mealSize, mealFilters, knowledgePage, knowledgeSize, knowledgeFilters, auditPage, auditSize, nonFoodPage, nonFoodSize, nonFoodFilters } = parameters
  const dashboard = usePageResource({ key: 'dashboard', enabled: ['overview', 'monitor', 'assistant-stats'].includes(tab), load: adminDashboard })
  const health = usePageResource({ key: 'health', enabled: ['overview', 'monitor'].includes(tab), load: healthCheck })
  const users = usePageResource({ key: JSON.stringify([userPage, userSize, userFilters]), enabled: tab === 'users', initialData: emptyPage(userSize), load: async () => normalizePage(await adminUsers({ page: userPage, size: userSize, ...userFilters }), userSize) })
  const meals = usePageResource({ key: JSON.stringify([mealPage, mealSize, mealFilters]), enabled: tab === 'meals', initialData: emptyPage(mealSize), load: async () => normalizePage(await adminMeals({ page: mealPage, size: mealSize, ...mealFilters }), mealSize) })
  const analytics = usePageResource({ key: JSON.stringify(mealFilters), enabled: tab === 'meals', load: () => adminMealAnalytics(mealFilters) })
  const knowledge = usePageResource({ key: JSON.stringify([knowledgePage, knowledgeSize, knowledgeFilters]), enabled: tab === 'knowledge', initialData: emptyPage(knowledgeSize), load: async () => normalizePage(await adminKnowledgeChunks({ page: knowledgePage, size: knowledgeSize, ...knowledgeFilters }), knowledgeSize) })
  const audit = usePageResource({ key: JSON.stringify([auditPage, auditSize]), enabled: tab === 'audit', initialData: emptyPage(auditSize), load: async () => normalizePage(await adminAuditLogs({ page: auditPage, size: auditSize }), auditSize) })
  const nonfood = usePageResource({ key: JSON.stringify([nonFoodPage, nonFoodSize, nonFoodFilters]), enabled: tab === 'nonfood', initialData: emptyPage(nonFoodSize), load: async () => normalizePage(await adminNonFoodUploads({ page: nonFoodPage, size: nonFoodSize, ...nonFoodFilters }), nonFoodSize) })
  const resources = { dashboard, health, users, meals, analytics, knowledge, audit, nonfood }
  const visible = { overview: ['dashboard', 'health'], monitor: ['dashboard', 'health'], 'assistant-stats': ['dashboard'], users: ['users'], meals: ['meals', 'analytics'], knowledge: ['knowledge'], audit: ['audit'], nonfood: ['nonfood'] }[tab] || []
  return {
    ...resources,
    loading: visible.some(name => resources[name].loading),
    error: visible.map(name => resources[name].error).filter(Boolean).join('；'),
    refresh(names = visible) { names.forEach(name => resources[name].invalidate()) }
  }
}
