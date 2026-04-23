<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="结果分析与教学改进"
      description="按照任务书中的闭环逻辑，先完成达成度核算，再进入多维分析、智能建议和改进措施跟踪。当前页面用于配置核算规则并执行核算。"
      :tabs="analysisImproveTabs"
    >
      <template #actions>
        <button class="btn btn-primary" :disabled="running" @click="runCalculation">
          {{ running ? '核算中...' : '开始核算' }}
        </button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadPage">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadPage">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div class="grid-2">
      <PanelCard title="核算规则配置" subtitle="支持设置核算方法、达成阈值和及格阈值。">
        <div class="form-stack">
          <div class="form-field">
            <label>核算方法</label>
            <select v-model="record.config.calcMethod" class="select-input">
              <option value="weighted_avg">加权平均法</option>
              <option value="threshold">阈值法</option>
            </select>
          </div>
          <div class="form-grid-2">
            <div class="form-field">
              <label>达成阈值</label>
              <input v-model="record.config.thresholdValue" class="text-input" type="number" min="0.01" max="1" step="0.01" />
            </div>
            <div class="form-field">
              <label>及格阈值</label>
              <input v-model="record.config.passThreshold" class="text-input" type="number" min="0.01" max="1" step="0.01" />
            </div>
          </div>
          <div class="notice warning">
            加权平均法用于按考核项目贡献权重汇总目标达成值；阈值法配置可用于后续规则扩展。
          </div>
        </div>
      </PanelCard>

      <PanelCard title="核算概览" subtitle="展示当前课程的整体核算结果和最近更新时间。">
        <div class="grid-2">
          <StatCard label="课程整体达成度" :value="Number(record.overallAchievement || 0).toFixed(3)" tone="primary" />
          <StatCard label="最近生成时间" :value="record.generatedAt || '--'" tone="secondary" />
        </div>
      </PanelCard>
    </div>

    <PanelCard title="核算结果明细" subtitle="按目标展示各考核维度表现与是否达成。">
      <div class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th>平时</th>
              <th>期中</th>
              <th>期末</th>
              <th>达成值</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in record.results" :key="item.objectiveId">
              <td>{{ item.objCode }}</td>
              <td>{{ Number(item.normal).toFixed(2) }}</td>
              <td>{{ Number(item.mid).toFixed(2) }}</td>
              <td>{{ Number(item.final).toFixed(2) }}</td>
              <td class="metric">{{ Number(item.achieveValue).toFixed(2) }}</td>
              <td>
                <StatusBadge :text="item.isAchieved ? '达成' : '未达成'" :tone="item.isAchieved ? 'success' : 'danger'" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getAchievementCalculation, runAchievementCalculation } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { analysisImproveTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const record = reactive({
  config: {
    calcMethod: 'weighted_avg',
    thresholdValue: 0.7,
    passThreshold: 0.6
  },
  generatedAt: '',
  overallAchievement: 0,
  results: []
})

const running = ref(false)
const message = reactive({
  type: 'success',
  text: ''
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function applyRecord(payload) {
  record.config = payload.config
  record.generatedAt = payload.generatedAt
  record.overallAchievement = payload.overallAchievement
  record.results = payload.results
}

async function loadPage() {
  const data = await getAchievementCalculation(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  applyRecord(data.record)
}

async function runCalculation() {
  running.value = true
  try {
    const result = await runAchievementCalculation({
      courseId: filters.courseId,
      semester: filters.semester,
      calcMethod: record.config.calcMethod,
      thresholdValue: record.config.thresholdValue,
      passThreshold: record.config.passThreshold
    })
    applyRecord(result)
    setMessage('success', '达成度核算已完成，结果已更新。')
  } catch (error) {
    setMessage('error', error.message || '核算失败。')
  } finally {
    running.value = false
  }
}

onMounted(loadPage)
</script>
