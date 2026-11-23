import {
  Activity,
  MessageCircle,
  BookOpen,
  ClipboardList,
  FlaskConical,
  LayoutDashboard,
  ListChecks,
  Network,
  ScanLine,
  TriangleAlert,
  Users,
} from 'lucide-react'

export const adminNavigation = [
  {
    key: 'overview',
    label: '系统概览',
    icon: LayoutDashboard,
    group: '工作台',
    description: '掌握平台运行情况，关注关键数据与待处理事项。',
  },
  {
    key: 'users',
    label: '用户管理',
    icon: Users,
    group: '业务管理',
    description: '查找用户、查看账户信息，管理平台用户。',
  },
  {
    key: 'meals',
    label: '饮食记录',
    icon: ClipboardList,
    group: '业务管理',
    description: '集中查看餐食记录与评分，了解用户的饮食情况。',
  },
  {
    key: 'nonfood',
    label: '异常上传',
    icon: TriangleAlert,
    group: '业务管理',
    description: '查看非饮食图片与上传预警，及时处理异常。',
  },
  {
    key: 'knowledge',
    label: 'RAG 知识库',
    icon: BookOpen,
    group: '智能服务',
    description: '维护饮食知识与检索内容，为智能建议提供可靠依据。',
  },
  {
    key: 'assistant-stats',
    label: '助手统计',
    icon: MessageCircle,
    group: '智能服务',
    description: '了解助手使用频率、常见问题与知识引用情况。',
  },
  {
    key: 'rag-evaluation',
    label: 'RAG 链路评测',
    icon: BookOpen,
    group: '质量评测',
    description: '沿同一流程检查工具调用与实际知识检索，定位阶段问题。',
  },
  {
    key: 'assistant-evaluation',
    label: '助手评测',
    icon: FlaskConical,
    group: '质量评测',
    description: '评估回答质量，跟踪测试结果与样本改进。',
  },
  {
    key: 'meal-corrections',
    label: '识别纠错评测',
    icon: ScanLine,
    group: '质量评测',
    description: '审核识别纠错样本，持续改进餐食识别效果。',
  },
  {
    key: 'monitor',
    label: '系统监控',
    icon: Activity,
    group: '系统管理',
    description: '按服务、AI 调用、检索与上传情况查看完整运行指标。',
  },
  {
    key: 'agent-coordination',
    label: 'Agent 协调',
    icon: Network,
    group: '系统管理',
    description: '查看周报任务的执行过程、运行状态与协调记录。',
  },
  {
    key: 'task-failures',
    label: '失败任务',
    icon: TriangleAlert,
    group: '系统管理',
    description: '查看失败原因，安全重试任务并跟踪恢复结果。',
  },
  {
    key: 'audit',
    label: '审计日志',
    icon: ListChecks,
    group: '系统管理',
    description: '追踪管理操作，让每一次变更清晰可查。',
  },
]

export const defaultUserFilters = { username: '', role: 'ALL' }

export const defaultMealFilters = { username: '', from: '', to: '', minScore: '', maxScore: '' }

export const defaultKnowledgeFilters = { keyword: '', source: 'all', sort: 'id' }

export const defaultNonFoodFilters = { username: '', blockedOnly: false }
