<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="数据采集"
      description="教学反思录入与学生评价、督导评价共同构成教学质量证据链，用于支撑后续智能建议和改进闭环。"
      :tabs="collectModuleTabs"
    >
      <template #actions>
        <button class="btn btn-primary" @click="saveCurrent">保存反思记录</button>
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

    <PanelCard title="反思内容" subtitle="建议从问题表现、原因分析、改进计划和下一步动作四部分填写。">
      <div class="form-stack">
        <div class="form-field">
          <label>问题概述</label>
          <textarea v-model.trim="record.problemSummary" class="text-area"></textarea>
        </div>
        <div class="form-field">
          <label>原因分析</label>
          <textarea v-model.trim="record.reasonAnalysis" class="text-area"></textarea>
        </div>
        <div class="form-field">
          <label>改进计划</label>
          <textarea v-model.trim="record.improvementPlan" class="text-area"></textarea>
        </div>
        <div class="form-field">
          <label>下一步动作</label>
          <textarea v-model.trim="record.nextAction" class="text-area"></textarea>
        </div>
        <div class="muted">最近更新时间：{{ record.updatedAt || '--' }}</div>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { getTeachingReflection, saveTeachingReflection } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
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
  problemSummary: '',
  reasonAnalysis: '',
  improvementPlan: '',
  nextAction: '',
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
  record.problemSummary = payload.problemSummary
  record.reasonAnalysis = payload.reasonAnalysis
  record.improvementPlan = payload.improvementPlan
  record.nextAction = payload.nextAction
  record.updatedAt = payload.updatedAt
}

async function loadRecord() {
  const data = await getTeachingReflection(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  applyRecord(data.record)
}

async function saveCurrent() {
  const saved = await saveTeachingReflection({
    courseId: filters.courseId,
    semester: filters.semester,
    problemSummary: record.problemSummary,
    reasonAnalysis: record.reasonAnalysis,
    improvementPlan: record.improvementPlan,
    nextAction: record.nextAction
  })
  applyRecord(saved)
  setMessage('success', '教学反思已保存。')
}

onMounted(loadRecord)
</script>
