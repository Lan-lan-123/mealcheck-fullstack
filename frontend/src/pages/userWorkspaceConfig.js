import { ChartNoAxesCombined, ClipboardList, FileText, LayoutDashboard, Target } from 'lucide-react'

export const userNavigation = [
  { key: 'overview', label: '饮食分析', icon: LayoutDashboard, group: '我的饮食', description: '从一张餐盘照片开始，了解这一餐的营养与搭配。' },
  { key: 'records', label: '饮食记录', icon: ClipboardList, group: '我的饮食', description: '回顾每一餐的选择，让更好的饮食习惯有迹可循。' },
  { key: 'trends', label: '饮食趋势', icon: ChartNoAxesCombined, group: '健康洞察', description: '了解评分、食物与风险的变化，发现饮食习惯中的小进步。' },
  { key: 'reports', label: '我的周报', icon: FileText, group: '健康洞察', description: '生成并阅读饮食周报，把每周的变化变成下一步行动。' },
  { key: 'plans', label: '行动计划', icon: ClipboardList, group: '个人计划', description: '保存建议、完成打卡，并回顾行动执行情况。' },
  { key: 'goals', label: '饮食目标', icon: Target, group: '个人计划', description: '设定适合自己的饮食方向，让每一餐都有明确目标。' }
]

export const goals = [
  { value: 'current', label: '使用当前目标' },
  { value: 'balanced', label: '均衡饮食' },
  { value: 'fat_loss', label: '减脂目标' },
  { value: 'muscle_gain', label: '增肌目标' },
  { value: 'light', label: '清淡饮食' }
]

export const defaultRecordFilters = {
  goal: '',
  from: '',
  to: '',
  minScore: '',
  maxScore: ''
}
