<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="结果分析与教学改进"
      description="智能建议中心承接分析结果，将预警项转化为可执行的改进建议，并支持一键生成改进措施。"
      :tabs="analysisImproveTabs"
    >
      <template #actions>
        <button class="btn btn-light" @click="router.push('/analysis/improvements')">查看改进措施</button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadSuggestions">
          <option value="">全部学期</option>
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadSuggestions">
          <option value="">全部课程</option>
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>优先级</label>
        <select v-model="filters.priority" class="select-input" @change="loadSuggestions">
          <option value="">全部优先级</option>
          <option value="1">高</option>
          <option value="2">中</option>
          <option value="3">低</option>
        </select>
      </div>
      <div class="filter-field">
        <label>阅读状态</label>
        <select v-model="filters.isRead" class="select-input" @change="loadSuggestions">
          <option value="">全部</option>
          <option value="0">未读</option>
          <option value="1">已读</option>
        </select>
      </div>
    </div>

    <div class="grid-3">
      <StatCard label="高优先级" :value="priorityStats.high" tone="warning" />
      <StatCard label="中优先级" :value="priorityStats.medium" tone="primary" />
      <StatCard label="低优先级" :value="priorityStats.low" tone="success" />
    </div>

    <div class="split-panel">
      <PanelCard title="建议列表" subtitle="点击卡片查看数据依据与处理动作。">
        <div v-if="!suggestions.length">
          <EmptyState
            mark="建"
            title="当前条件下没有建议数据"
            description="可以切换筛选条件，或返回分析页重新查看课程达成趋势。"
          />
        </div>
        <div v-else class="detail-list">
          <button
            v-for="item in suggestions"
            :key="item.id"
            class="suggestion-card"
            :class="{ active: Number(selectedId) === Number(item.id), muted: item.isDismissed }"
            @click="selectSuggestion(item.id)"
          >
            <div class="achievement-row">
              <strong>{{ item.title }}</strong>
              <StatusBadge :text="item.priorityLabel" :tone="priorityTone(item.priority)" />
            </div>
            <div class="card-note mt-8">{{ item.suggestionText }}</div>
            <div class="chip-row mt-12">
              <StatusBadge :text="item.isRead ? '已读' : '未读'" :tone="item.isRead ? 'neutral' : 'danger'" />
              <StatusBadge :text="item.ruleCode" tone="primary" />
            </div>
          </button>
        </div>
      </PanelCard>

      <PanelCard title="建议详情" subtitle="展示建议依据，并支持直接执行处理动作。">
        <EmptyState
          v-if="!detail"
          mark="详"
          title="请选择一条建议"
          description="选择左侧建议后，这里会展示趋势数据依据和处理按钮。"
        />
        <template v-else>
          <div class="detail-list">
            <div class="source-segment">
              <strong>{{ detail.title }}</strong>
              <p class="mt-8">{{ detail.suggestionText }}</p>
            </div>
            <div class="detail-row">
              <span class="muted">课程</span>
              <strong>{{ detail.courseName }}</strong>
            </div>
            <div class="detail-row">
              <span class="muted">生成时间</span>
              <strong>{{ detail.createdAt }}</strong>
            </div>
          </div>

          <div class="actions-inline mt-16">
            <button class="btn btn-light" @click="handleRead(detail.id)">标记已读</button>
            <button class="btn btn-danger" @click="handleDismiss(detail.id)">忽略建议</button>
            <button class="btn btn-secondary" @click="handleCreateMeasure(detail.id)">创建改进措施</button>
          </div>

          <div v-if="message.text" class="notice mt-16" :class="message.type">{{ message.text }}</div>

          <div v-if="detail.dataBasis?.breakdown?.length" class="mt-16">
            <PanelCard title="分项依据" subtitle="展示该建议涉及的关键指标拆分。">
              <ChartPanel :option="breakdownOption" height="240px" />
            </PanelCard>
          </div>

          <div class="mt-16">
            <PanelCard title="历史趋势" subtitle="建议触发依据中的历史数据变化情况。">
              <ChartPanel :option="historyOption" height="240px" />
            </PanelCard>
          </div>
        </template>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createMeasureFromSuggestion,
  dismissSuggestion,
  getSuggestionDetail,
  getSuggestions,
  markSuggestionRead
} from '@/api'
import ChartPanel from '@/components/common/ChartPanel.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { analysisImproveTabs } from '@/constants/moduleTabs'

const router = useRouter()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  semester: '',
  courseId: '',
  priority: '',
  isRead: '',
  isDismissed: 0
})

const suggestions = ref([])
const selectedId = ref('')
const detail = ref(null)
const message = reactive({
  type: 'success',
  text: ''
})

const priorityStats = computed(() => ({
  high: suggestions.value.filter((item) => Number(item.priority) === 1).length,
  medium: suggestions.value.filter((item) => Number(item.priority) === 2).length,
  low: suggestions.value.filter((item) => Number(item.priority) === 3).length
}))

const historyOption = computed(() => ({
  grid: { top: 24, right: 18, bottom: 28, left: 36 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: detail.value?.dataBasis?.histDetail?.map((item) => item.semester) || [],
    axisLabel: { color: '#5f7382' }
  },
  yAxis: {
    type: 'value',
    axisLabel: { color: '#5f7382' }
  },
  series: [
    {
      type: 'line',
      smooth: true,
      data: detail.value?.dataBasis?.histDetail?.map((item) => item.avg) || [],
      lineStyle: { color: '#1f5f8b', width: 3 },
      itemStyle: { color: '#1f5f8b' },
      areaStyle: { color: 'rgba(31, 95, 139, 0.14)' }
    }
  ]
}))

const breakdownOption = computed(() => ({
  grid: { top: 24, right: 18, bottom: 28, left: 36 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: detail.value?.dataBasis?.breakdown?.map((item) => item.label) || [],
    axisLabel: { color: '#5f7382' }
  },
  yAxis: {
    type: 'value',
    min: 0,
    max: 1,
    axisLabel: { color: '#5f7382' }
  },
  series: [
    {
      type: 'bar',
      data: detail.value?.dataBasis?.breakdown?.map((item) => item.value) || [],
      itemStyle: { color: '#0f8a78', borderRadius: [8, 8, 0, 0] }
    }
  ]
}))

function priorityTone(priority) {
  if (Number(priority) === 1) return 'danger'
  if (Number(priority) === 2) return 'warning'
  return 'success'
}

function setMessage(type, text) {
  message.type = type
  message.text = text
}

async function selectSuggestion(id) {
  selectedId.value = id
  detail.value = await getSuggestionDetail(id)
}

async function loadSuggestions() {
  const data = await getSuggestions(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  suggestions.value = data.items

  if (data.items.length) {
    if (!data.items.some((item) => Number(item.id) === Number(selectedId.value))) {
      await selectSuggestion(data.items[0].id)
    } else {
      await selectSuggestion(selectedId.value)
    }
  } else {
    selectedId.value = ''
    detail.value = null
  }
}

async function handleRead(id) {
  await markSuggestionRead(id)
  setMessage('success', '建议已标记为已读。')
  await loadSuggestions()
}

async function handleDismiss(id) {
  await dismissSuggestion(id, { dismissReason: '教师确认当前建议暂不采纳' })
  setMessage('warning', '建议已忽略。')
  await loadSuggestions()
}

async function handleCreateMeasure(id) {
  const result = await createMeasureFromSuggestion(id)
  setMessage('success', '已创建改进措施，正在跳转到改进措施页。')
  router.push(result.redirectUrl)
}

onMounted(loadSuggestions)
</script>

<style scoped>
.suggestion-card {
  width: 100%;
  padding: 16px;
  text-align: left;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.suggestion-card.active {
  border-color: rgba(31, 95, 139, 0.28);
  background: linear-gradient(180deg, rgba(234, 243, 251, 0.85), #fff);
}

.suggestion-card.muted {
  opacity: 0.68;
}

.source-segment {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.source-segment p {
  margin: 0;
  line-height: 1.8;
  color: var(--color-text-soft);
}
</style>
