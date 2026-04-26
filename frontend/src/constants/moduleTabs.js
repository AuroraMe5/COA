export const objectiveManageTabs = [
  {
    label: '课程大纲管理',
    to: '/objectives/outlines'
  },
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
  },
  {
    label: '目标考核映射',
    to: '/objectives/mapping'
  }
]

export const collectModuleTabs = [
  {
    label: '成绩批量导入',
    to: '/collect/grades'
  },
  {
    label: '学生成绩管理',
    to: '/collect/grades/manage'
  }
]

export const analysisImproveTabs = [
  {
    label: '达成度核算与报告',
    to: '/analysis/calculation'
  }
]
