<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="结果分析与教学改进"
      description="根据任务书中的闭环逻辑，这里将改进建议落到具体措施，并跟踪预期效果与实际效果，形成可追踪的教学改进记录。"
      :tabs="analysisImproveTabs"
    >
      <template #actions>
        <button class="btn btn-light" @click="createMeasure">新增措施</button>
        <button class="btn btn-primary" @click="saveCurrent">保存措施</button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadMeasures">
          <option value="">全部课程</option>
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadMeasures">
          <option value="">全部学期</option>
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>状态</label>
        <select v-model="filters.status" class="select-input" @change="loadMeasures">
          <option value="">全部状态</option>
          <option value="PLANNED">计划中</option>
          <option value="IN_PROGRESS">执行中</option>
          <option value="DONE">已完成</option>
        </select>
      </div>
    </div>

    <div class="grid-4">
      <StatCard label="计划中" :value="statusStats.planned" tone="primary" />
      <StatCard label="执行中" :value="statusStats.inProgress" tone="warning" />
      <StatCard label="已完成" :value="statusStats.done" tone="success" />
      <StatCard label="待补效果" :value="pendingEffectCount" tone="secondary" />
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div class="split-panel">
      <PanelCard title="措施列表" subtitle="可从智能建议中心跳转至此继续补充改进详情与效果跟踪。">
        <div v-if="!measures.length">
          <EmptyState
            mark="措"
            title="当前没有改进措施"
            description="你可以从智能建议中心一键创建，或者在这里手动新增改进措施。"
          />
        </div>
        <div v-else class="detail-list">
          <button
            v-for="item in measures"
            :key="item.id"
            class="measure-card"
            :class="{ active: Number(selectedId) === Number(item.id) }"
            @click="selectMeasure(item)"
          >
            <div class="achievement-row">
              <strong>{{ courseName(item.courseId) }}</strong>
              <StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" />
            </div>
            <div class="card-note mt-8">{{ item.problemDesc }}</div>
            <div class="muted mt-8">更新于 {{ item.updatedAt }}</div>
          </button>
        </div>
      </PanelCard>

      <PanelCard title="措施编辑" subtitle="支持维护问题、措施、预期效果与实际效果，形成闭环记录。">
        <div class="form-stack">
          <div class="form-grid-2">
            <div class="form-field">
              <label>课程</label>
              <select v-model="form.courseId" class="select-input">
                <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
                  {{ course.name }}
                </option>
              </select>
            </div>
            <div class="form-field">
              <label>学期</label>
              <select v-model="form.semester" class="select-input">
                <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
                  {{ semester }}
                </option>
              </select>
            </div>
            <div class="form-field">
              <label>负责人</label>
              <input v-model.trim="form.owner" class="text-input" />
            </div>
            <div class="form-field">
              <label>截止日期</label>
              <input v-model="form.deadline" class="text-input" type="date" />
            </div>
            <div class="form-field">
              <label>执行状态</label>
              <select v-model="form.status" class="select-input">
                <option value="PLANNED">计划中</option>
                <option value="IN_PROGRESS">执行中</option>
                <option value="DONE">已完成</option>
              </select>
            </div>
            <div class="form-field">
              <label>关联规则</label>
              <input v-model.trim="form.linkedRuleCode" class="text-input" />
            </div>
          </div>

          <div class="form-field">
            <label>问题描述</label>
            <textarea v-model.trim="form.problemDesc" class="text-area"></textarea>
          </div>
          <div class="form-field">
            <label>改进措施内容</label>
            <textarea v-model.trim="form.measureContent" class="text-area"></textarea>
          </div>
          <div class="form-field">
            <label>预期效果</label>
            <textarea v-model.trim="form.expectedEffect" class="text-area"></textarea>
          </div>
          <div class="form-field">
            <label>实际效果</label>
            <textarea v-model.trim="form.actualEffect" class="text-area"></textarea>
          </div>
          <div class="muted">最近更新时间：{{ form.updatedAt || '--' }}</div>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getImprovementMeasures, saveImprovementMeasure } from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { analysisImproveTabs } from '@/constants/moduleTabs'

const route = useRoute()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: '',
  status: ''
})

const measures = ref([])
const selectedId = ref(null)
const message = reactive({
  type: 'success',
  text: ''
})

const form = reactive({
  id: null,
  courseId: '',
  semester: '',
  owner: '王斌',
  deadline: '',
  status: 'PLANNED',
  linkedRuleCode: '',
  problemDesc: '',
  measureContent: '',
  expectedEffect: '',
  actualEffect: '',
  updatedAt: ''
})

const statusStats = computed(() => ({
  planned: measures.value.filter((item) => item.status === 'PLANNED').length,
  inProgress: measures.value.filter((item) => item.status === 'IN_PROGRESS').length,
  done: measures.value.filter((item) => item.status === 'DONE').length
}))

const pendingEffectCount = computed(() =>
  measures.value.filter((item) => item.status === 'DONE' && !(item.actualEffect || item.effectSummary)).length
)

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function statusTone(status) {
  if (status === 'DONE') return 'success'
  if (status === 'IN_PROGRESS') return 'warning'
  return 'primary'
}

function statusText(status) {
  if (status === 'DONE') return '已完成'
  if (status === 'IN_PROGRESS') return '执行中'
  return '计划中'
}

function courseName(courseId) {
  return catalogs.courses.find((item) => Number(item.id) === Number(courseId))?.name || '--'
}

function applyForm(payload) {
  form.id = payload.id || null
  form.courseId = payload.courseId || catalogs.courses[0]?.id || ''
  form.semester = payload.semester || catalogs.semesters[0] || ''
  form.owner = payload.owner || '王斌'
  form.deadline = payload.deadline || ''
  form.status = payload.status || 'PLANNED'
  form.linkedRuleCode = payload.linkedRuleCode || ''
  form.problemDesc = payload.problemDesc || ''
  form.measureContent = payload.measureContent || ''
  form.expectedEffect = payload.expectedEffect || ''
  form.actualEffect = payload.actualEffect || payload.effectSummary || ''
  form.updatedAt = payload.updatedAt || ''
}

function selectMeasure(item) {
  selectedId.value = item.id
  applyForm(item)
}

function createMeasure() {
  selectedId.value = null
  applyForm({
    id: null,
    courseId: filters.courseId || catalogs.courses[0]?.id || '',
    semester: filters.semester || catalogs.semesters[0] || '',
    status: 'PLANNED'
  })
}

async function loadMeasures() {
  const data = await getImprovementMeasures(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  measures.value = data.items

  const targetId = Number(route.query.measureId || selectedId.value || '')
  const target = data.items.find((item) => Number(item.id) === targetId)
  if (target) {
    selectMeasure(target)
  } else if (data.items.length) {
    selectMeasure(data.items[0])
  } else {
    createMeasure()
  }
}

async function saveCurrent() {
  if (!form.problemDesc || !form.measureContent) {
    setMessage('error', '请至少填写问题描述和改进措施内容。')
    return
  }

  await saveImprovementMeasure({
    ...form,
    courseId: Number(form.courseId),
    semester: form.semester,
    effectSummary: form.actualEffect
  })
  setMessage('success', '改进措施已保存。')
  await loadMeasures()
}

onMounted(loadMeasures)
</script>

<style scoped>
.measure-card {
  width: 100%;
  padding: 16px;
  text-align: left;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.measure-card.active {
  border-color: rgba(31, 95, 139, 0.28);
  background: linear-gradient(180deg, rgba(234, 243, 251, 0.85), #fff);
}
</style>
