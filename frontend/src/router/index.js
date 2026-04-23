import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppShell from '@/components/layout/AppShell.vue'
import LoginView from '@/views/LoginView.vue'
import DashboardView from '@/views/DashboardView.vue'
import ObjectiveList from '@/views/objectives/ObjectiveList.vue'
import ObjectiveEdit from '@/views/objectives/ObjectiveEdit.vue'
import ParseImport from '@/views/objectives/ParseImport.vue'
import OutlineManage from '@/views/objectives/OutlineManage.vue'
import ObjectiveWeights from '@/views/objectives/ObjectiveWeights.vue'
import ObjectiveMapping from '@/views/objectives/ObjectiveMapping.vue'
import GradeImportView from '@/views/collect/GradeImportView.vue'
import EvaluationEntry from '@/views/collect/EvaluationEntry.vue'
import ReflectionEntry from '@/views/collect/ReflectionEntry.vue'
import SupervisorReviewView from '@/views/collect/SupervisorReviewView.vue'
import AnalysisOverview from '@/views/analysis/AnalysisOverview.vue'
import SuggestionCenter from '@/views/analysis/SuggestionCenter.vue'
import AchievementCalculation from '@/views/analysis/AchievementCalculation.vue'
import ImprovementMeasures from '@/views/analysis/ImprovementMeasures.vue'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { guestOnly: true, title: '系统登录' }
  },
  {
    path: '/',
    component: AppShell,
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: '/dashboard'
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: DashboardView,
        meta: { title: '教师工作台' }
      },
      {
        path: 'objectives/outlines',
        name: 'outlines',
        component: OutlineManage,
        meta: { title: '课程大纲管理' }
      },
      {
        path: 'objectives/list',
        name: 'objective-list',
        component: ObjectiveList,
        meta: { title: '教学目标管理' }
      },
      {
        path: 'objectives/edit/:id?',
        name: 'objective-edit',
        component: ObjectiveEdit,
        props: true,
        meta: { title: '教学目标管理' }
      },
      {
        path: 'objectives/weights',
        name: 'weights',
        component: ObjectiveWeights,
        meta: { title: '教学目标管理' }
      },
      {
        path: 'objectives/parse-import',
        name: 'parse-import',
        component: ParseImport,
        meta: { title: '教学目标管理' }
      },
      {
        path: 'objectives/mapping',
        name: 'mapping',
        component: ObjectiveMapping,
        meta: { title: '目标考核映射' }
      },
      {
        path: 'collect/grades',
        name: 'grade-import',
        component: GradeImportView,
        meta: { title: '数据采集' }
      },
      {
        path: 'collect/evaluations',
        name: 'collect-evaluations',
        component: EvaluationEntry,
        meta: { title: '数据采集' }
      },
      {
        path: 'collect/reflections',
        name: 'collect-reflections',
        component: ReflectionEntry,
        meta: { title: '数据采集' }
      },
      {
        path: 'collect/supervisors',
        name: 'collect-supervisors',
        component: SupervisorReviewView,
        meta: { title: '数据采集' }
      },
      {
        path: 'analysis/calculation',
        name: 'analysis-calculation',
        component: AchievementCalculation,
        meta: { title: '结果分析与教学改进' }
      },
      {
        path: 'analysis/overview',
        name: 'analysis-overview',
        component: AnalysisOverview,
        meta: { title: '结果分析与教学改进' }
      },
      {
        path: 'analysis/suggestions',
        name: 'suggestions',
        component: SuggestionCenter,
        meta: { title: '结果分析与教学改进' }
      },
      {
        path: 'analysis/improvements',
        name: 'improvements',
        component: ImprovementMeasures,
        meta: { title: '结果分析与教学改进' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()

  if (!authStore.initialized) {
    authStore.hydrate()
  }

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath }
    }
  }

  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return { name: 'dashboard' }
  }

  return true
})

export default router
