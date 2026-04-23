<template>
  <div class="app-page page-stack">
    <div class="page-title">
      <div>
        <h1>教师工作台</h1>
        <p>汇总本学期课程进展、待办事项与目标达成情况，便于快速进入核心流程。</p>
      </div>
    </div>

    <div class="grid-4">
      <StatCard
        v-for="item in dashboard.stats"
        :key="item.label"
        :label="item.label"
        :value="item.value"
        :tone="item.tone"
      />
    </div>

    <div class="grid-2">
      <PanelCard title="待办事项" subtitle="建议优先处理当前未完成的数据导入、核算或建议确认任务。">
        <div v-if="dashboard.todos.length" class="detail-list">
          <button
            v-for="todo in dashboard.todos"
            :key="todo.id"
            class="todo-item"
            @click="router.push(todo.route)"
          >
            <span class="todo-dot" :class="todo.level"></span>
            <span>{{ todo.text }}</span>
          </button>
        </div>
        <EmptyState
          v-else
          mark="待"
          title="当前没有待办事项"
          description="本学期核心工作已同步完成，可以直接查看分析结果或继续维护课程目标。"
        />
      </PanelCard>

      <PanelCard title="快捷入口" subtitle="按当前工作流快速跳转到高频模块。">
        <div class="quick-grid">
          <button
            v-for="item in dashboard.quickLinks"
            :key="item.route"
            class="quick-link"
            @click="router.push(item.route)"
          >
            <strong>{{ item.label }}</strong>
            <span>进入模块</span>
          </button>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="课程达成度概览" subtitle="展示当前教师名下课程的平均目标达成情况。">
      <div class="detail-list">
        <div v-for="item in dashboard.courseAchievements" :key="item.courseId" class="achievement-item">
          <div class="achievement-row">
            <strong>{{ item.courseName }}</strong>
            <span class="metric">{{ item.value.toFixed(2) }}</span>
          </div>
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: `${item.value * 100}%` }"></div>
          </div>
        </div>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboardData } from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'

const router = useRouter()

const dashboard = reactive({
  stats: [],
  todos: [],
  quickLinks: [],
  courseAchievements: []
})

async function loadDashboard() {
  const data = await getDashboardData()
  dashboard.stats = data.stats
  dashboard.todos = data.todos
  dashboard.quickLinks = data.quickLinks
  dashboard.courseAchievements = data.courseAchievements
}

onMounted(loadDashboard)
</script>

<style scoped>
.todo-item {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 14px 0;
  background: transparent;
  border: none;
  border-bottom: 1px solid #e8f0f4;
  color: var(--color-text);
  text-align: left;
}

.todo-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.todo-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  flex-shrink: 0;
}

.todo-dot.high {
  background: var(--color-danger);
}

.todo-dot.medium {
  background: var(--color-warning);
}

.todo-dot.normal {
  background: var(--color-primary);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.quick-link {
  padding: 18px;
  border-radius: 18px;
  border: 1px solid var(--color-border);
  background: linear-gradient(180deg, #fcfeff, #f1f8fb);
  text-align: left;
}

.quick-link strong {
  display: block;
  font-size: 16px;
  color: var(--color-primary-deep);
}

.quick-link span {
  display: block;
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-text-soft);
}

.achievement-item + .achievement-item {
  margin-top: 18px;
}

.achievement-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.progress-track {
  width: 100%;
  height: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: #e8f1f5;
}

.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-secondary));
}

@media (max-width: 720px) {
  .quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
