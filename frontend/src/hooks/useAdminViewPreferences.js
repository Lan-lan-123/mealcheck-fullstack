// @ts-check
import { useState } from 'react'

// Keep view choices for the lifetime of the workspace, even when lazy panels unmount.
export default function useAdminViewPreferences() {
  const [monitorSection, setMonitorSection] = useState(/** @type {'services' | 'ai' | 'retrieval' | 'uploads'} */ ('services'))
  const [assistantStatsSection, setAssistantStatsSection] = useState(/** @type {'trend' | 'categories' | 'users' | 'references'} */ ('trend'))
  return { monitorSection, setMonitorSection, assistantStatsSection, setAssistantStatsSection }
}
