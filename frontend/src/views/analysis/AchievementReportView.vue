<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="报告预览和导出"
      description="基于已完成的达成度核算结果生成课程目标达成情况报告。"
    >
      <template #actions>
        <button class="btn btn-light" :disabled="metaLoading || !hasCalcResult" @click="loadReportMeta">
          {{ metaLoading ? '读取中...' : '预览报告信息' }}
        </button>
        <button class="btn btn-primary" :disabled="downloading || !hasCalcResult" @click="handleDownload">
          {{ downloading ? '生成中...' : '下载报告' }}
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

    <PanelCard title="报告数据状态" subtitle="报告只读取当前课程学期最近一次已保存的达成度核算结果。">
      <div v-if="hasCalcResult" class="grid-2">
        <StatCard label="课程整体达成度" :value="Number(record.overallAchievement || 0).toFixed(3)" tone="primary" />
        <StatCard label="最近核算时间" :value="record.generatedAt || '--'" tone="secondary" />
      </div>
      <div v-else class="notice warning">
        当前课程学期还没有可用于报告的核算结果，请先在“达成度核算”页面完成核算。
      </div>
    </PanelCard>

    <PanelCard v-if="hasCalcResult" title="报告预览" subtitle="预览报告将写入的关键统计项，确认后可导出 Word。">
      <div class="export-section">
        <div v-if="reportMeta" class="meta-preview">
          <StatCard label="参与学生" :value="`${reportMeta.studentCount} 人`" tone="primary" />
          <StatCard
            label="低达成度学生"
            :value="`${reportMeta.weakStudentCount} 人`"
            :tone="reportMeta.weakStudentCount > 0 ? 'warning' : 'success'"
          />
          <StatCard
            v-for="obj in reportMeta.objectives"
            :key="obj.id"
            :label="obj.name"
            :value="`${(Number(obj.achievement || 0) * 100).toFixed(1)}%`"
            :helper="obj.judgement"
            :tone="Number(obj.achievement || 0) >= 0.6 ? 'success' : 'warning'"
          />
        </div>

        <div v-else class="notice info">
          点击“预览报告信息”读取课程目标达成度、成绩分布和改进建议摘要。
        </div>

        <div v-if="reportMeta?.smartAnalysis" class="report-analysis">
          <strong>报告智能摘要</strong>
          <p>{{ reportMeta.smartAnalysis.summary }}</p>
          <div class="report-actions">
            <span v-for="item in reportMeta.smartAnalysis.improvements" :key="item">{{ item }}</span>
          </div>
        </div>

        <div v-if="reportMeta?.gradeDistribution?.length" class="table-shell">
          <table class="data-table">
            <thead>
              <tr>
                <th>分数段</th>
                <th>人数</th>
                <th>占比</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in reportMeta.gradeDistribution" :key="item.label">
                <td>{{ item.label }}</td>
                <td>{{ item.count }}</td>
                <td>{{ Number(item.pct || 0).toFixed(2) }}%</td>
              </tr>
            </tbody>
          </table>
        </div>

        <p class="export-desc">
          导出的报告包含课程概况、目标支撑关系、成绩评定方法、成绩统计与试题分析、课程目标达成情况、分段达成情况、总结与改进建议。
        </p>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { downloadReport, getAchievementCalculation, getReportPreviewMeta } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import { showFeedback } from '@/utils/feedback'

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
    calcRuleId: null
  },
  generatedAt: '',
  overallAchievement: 0
})

const currentOutlineId = ref(null)
const reportMeta = ref(null)
const metaLoading = ref(false)
const downloading = ref(false)
const message = reactive({
  type: 'success',
  text: ''
})

const hasCalcResult = computed(() => Boolean(currentOutlineId.value && record.config.calcRuleId && record.generatedAt))

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function applyRecord(payload = {}) {
  record.config.calcRuleId = payload.config?.calcRuleId || null
  record.generatedAt = payload.generatedAt || ''
  record.overallAchievement = payload.overallAchievement || 0
}

async function loadPage() {
  try {
    const data = await getAchievementCalculation(filters)
    catalogs.courses = data.courses
    catalogs.semesters = data.semesters
    filters.courseId = data.currentCourseId
    filters.semester = data.currentSemester
    currentOutlineId.value = data.outlineId
    reportMeta.value = null
    applyRecord(data.record)
  } catch (error) {
    setMessage('error', error.message || '报告数据读取失败。')
  }
}

function assertReportParams() {
  if (!currentOutlineId.value) {
    setMessage('error', '当前课程学期尚未建立课程大纲，暂不能生成报告。')
    return false
  }
  if (!record.config.calcRuleId || !record.generatedAt) {
    setMessage('error', '请先完成一次达成度核算，再生成报告。')
    return false
  }
  return true
}

async function loadReportMeta() {
  if (!assertReportParams()) {
    return
  }
  metaLoading.value = true
  try {
    reportMeta.value = await getReportPreviewMeta(currentOutlineId.value, record.config.calcRuleId)
  } catch (error) {
    setMessage('error', error.message || '报告信息读取失败。')
  } finally {
    metaLoading.value = false
  }
}

async function handleDownload() {
  if (!assertReportParams()) {
    return
  }
  downloading.value = true
  try {
    await downloadReport(currentOutlineId.value, record.config.calcRuleId)
    setMessage('success', '达成度报告已生成。')
  } catch (error) {
    setMessage('error', error.message || '报告生成失败。')
  } finally {
    downloading.value = false
  }
}

onMounted(loadPage)
</script>

<style scoped>
.export-section {
  display: grid;
  gap: 16px;
}

.meta-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.export-desc {
  margin: 0;
  color: var(--color-text-soft);
  line-height: 1.8;
}

.report-analysis {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid #e3edf2;
  border-radius: 8px;
  background: #fbfdfe;
}

.report-analysis p {
  margin: 0;
  color: var(--color-text-soft);
  line-height: 1.8;
}

.report-actions {
  display: grid;
  gap: 8px;
}

.report-actions span {
  padding: 8px 10px;
  border-left: 3px solid var(--color-primary);
  background: #fff;
  color: var(--color-text);
  line-height: 1.6;
}
</style>
