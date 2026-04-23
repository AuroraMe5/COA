export const objectiveManageTabs = [
  {
    label: '教学目标列表',
    to: '/objectives/list',
    matches: ['/objectives/list', '/objectives/edit']
  },
  {
    label: '目标分解与权重',
    to: '/objectives/weights'
  },
  {
    label: '智能解析导入',
    to: '/objectives/parse-import'
  }
]

export const collectModuleTabs = [
  {
    label: '成绩批量导入',
    to: '/collect/grades'
  },
  {
    label: '学生评价录入',
    to: '/collect/evaluations'
  },
  {
    label: '教学反思录入',
    to: '/collect/reflections'
  },
  {
    label: '督导评价查看',
    to: '/collect/supervisors'
  }
]

export const analysisImproveTabs = [
  {
    label: '达成度核算',
    to: '/analysis/calculation'
  },
  {
    label: '多维分析报表',
    to: '/analysis/overview'
  },
  {
    label: '智能建议中心',
    to: '/analysis/suggestions'
  },
  {
    label: '改进措施跟踪',
    to: '/analysis/improvements'
  }
]
