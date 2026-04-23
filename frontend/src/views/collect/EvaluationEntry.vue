<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="数据采集"
      description="学生评价、教学反思和督导评价共同构成非成绩类采集数据。学生评价录入结果会直接作为后续分析与建议的重要依据。"
      :tabs="collectModuleTabs"
    >
      <template #actions>
        <button class="btn btn-primary" @click="saveCurrent">保存评价数据</button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadRecord">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadRecord">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div class="grid-3">
      <StatCard label="参评人数" :value="record.evaluatorCount || 0" tone="primary" />
      <StatCard label="平均得分" :value="Number(record.avgScore || 0).toFixed(2)" tone="success" />
      <StatCard label="最近更新时间" :value="record.updatedAt || '--'" tone="secondary" />
    </div>

    <div class="grid-2">
      <PanelCard title="评价维度" subtitle="支持录入各维度平均分，可直接用于分析图表。">
        <div class="form-field">
          <label>参评人数</label>
          <input v-model="record.evaluatorCount" class="text-input" type="number" min="0" />
        </div>

        <div class="detail-list mt-16">
          <div v-for="item in record.dimensions" :key="item.key" class="dimension-card">
            <div class="achievement-row">
              <strong>{{ item.label }}</strong>
              <input v-model="item.score" class="text-input small-input" type="number" min="0" max="5" step="0.01" />
            </div>
          </div>
        </div>
      </PanelCard>

      <PanelCard title="典型反馈" subtitle="保留代表性学生反馈，便于后续做问题归因。">
        <div class="detail-list">
          <div v-for="(comment, index) in record.comments" :key="index" class="comment-card">
            <textarea v-model="record.comments[index]" class="text-area"></textarea>
            <div class="actions-inline mt-12">
              <button class="btn btn-danger" @click="removeComment(index)">删除反馈</button>
            </div>
          </div>
        </div>
        <div class="actions-inline mt-16">
          <button class="btn btn-light" @click="addComment">新增反馈</button>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { getStudentEvaluations, saveStudentEvaluations } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import { collectModuleTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const record = reactive({
  courseId: '',
  semester: '',
  evaluatorCount: 0,
  avgScore: 0,
  dimensions: [],
  comments: [],
  updatedAt: ''
})

const message = reactive({
  type: 'success',
  text: ''
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function applyRecord(payload) {
  record.courseId = payload.courseId
  record.semester = payload.semester
  record.evaluatorCount = payload.evaluatorCount
  record.avgScore = payload.avgScore
  record.dimensions = payload.dimensions
  record.comments = payload.comments
  record.updatedAt = payload.updatedAt
}

function addComment() {
  record.comments.push('')
}

function removeComment(index) {
  record.comments.splice(index, 1)
}

async function loadRecord() {
  const data = await getStudentEvaluations(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  applyRecord(data.record)
}

async function saveCurrent() {
  const saved = await saveStudentEvaluations({
    courseId: filters.courseId,
    semester: filters.semester,
    evaluatorCount: record.evaluatorCount,
    dimensions: record.dimensions,
    comments: record.comments
  })
  applyRecord(saved)
  setMessage('success', '学生评价数据已保存。')
}

onMounted(loadRecord)
</script>

<style scoped>
.dimension-card,
.comment-card {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.small-input {
  max-width: 120px;
}
</style>
