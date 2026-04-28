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
import ClassCollectView from '@/views/collect/ClassCollectView.vue'
import GradeManageView from '@/views/collect/GradeManageView.vue'
import AchievementCalculation from '@/views/analysis/AchievementCalculation.vue'
import AchievementReportView from '@/views/analysis/AchievementReportView.vue'

// meta.title 目前主要用于路由语义和后续扩展。
// 头部组件已经不再直接把它渲染成大标题，因此不会再出现“左上角重复标题”的问题。
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
        meta: { title: '课程管理' }
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
        path: 'collect/classes',
        name: 'class-collect',
        component: ClassCollectView,
        meta: { title: '班级与学生管理' }
      },
      {
        path: 'collect/grades',
        name: 'grade-import',
        redirect: '/collect/grades/manage'
      },
      {
        path: 'collect/grades/manage',
        name: 'grade-manage',
        component: GradeManageView,
        meta: { title: '学生成绩管理' }
      },
      {
        path: 'analysis/calculation',
        name: 'analysis-calculation',
        component: AchievementCalculation,
        meta: { title: '达成度核算' }
      },
      {
        path: 'analysis/report',
        name: 'analysis-report',
        component: AchievementReportView,
        meta: { title: '报告预览和导出' }
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

  // 页面刷新后，Pinia 会丢失内存态，所以先尝试从 localStorage 回填。
  if (!authStore.initialized) {
    authStore.hydrate()
  }

  // 页面刷新后，如果 localStorage 里还有旧 token，需要先向后端确认它是否仍然有效。
  // 这样可以避免“本地看起来已登录，但一进页面所有接口都报 token 失效”的情况。
  if (authStore.accessToken && !authStore.sessionValidated) {
    await authStore.ensureSession()
  }

  // 需要登录但当前没有会话时，带着 redirect 回登录页，登录后可以回跳。
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
