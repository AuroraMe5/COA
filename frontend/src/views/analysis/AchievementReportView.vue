<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="报告预览和导出"
      description="基于已完成的达成度核算结果生成课程目标达成情况报告。"
    >
      <template #actions>
        <button class="btn btn-light" :disabled="metaLoading || !hasCalcResult" @click="loadReportMeta">
          {{ metaLoading ? '读取中...' : reportMeta ? '刷新完整预览' : '生成完整预览' }}
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
        <StatCard label="课程整体达成度" :value="formatRatio(record.overallAchievement)" tone="primary" />
        <StatCard label="最近核算时间" :value="record.generatedAt || '--'" tone="secondary" />
      </div>
      <div v-else class="notice warning">
        当前课程学期还没有可用于报告的核算结果，请先在“达成度核算”页面完成核算。
      </div>
    </PanelCard>

    <PanelCard
      v-if="hasCalcResult"
      title="报告完整预览"
      subtitle="预览内容按导出 Word 的封面、章节、表格和附录同步生成。"
    >
      <div v-if="!reportMeta" class="notice info">
        点击“生成完整预览”读取与导出 Word 一致的报告内容。
      </div>

      <div v-else class="report-preview">
        <div class="preview-summary-grid">
          <StatCard label="预览课程" :value="reportCourseTitle" tone="primary" />
          <StatCard label="参与学生" :value="`${reportMeta.studentCount || 0} 人`" tone="secondary" />
          <StatCard label="课程整体达成度" :value="formatRatio(reportMeta.overallAchievement)" tone="success" />
          <StatCard
            label="低达成度学生"
            :value="`${reportMeta.weakStudentCount || 0} 人`"
            :tone="Number(reportMeta.weakStudentCount || 0) > 0 ? 'warning' : 'success'"
          />
        </div>

        <div class="report-paper">
          <section v-for="section in reportSections" :key="section.title" class="report-section">
            <h2>{{ section.title }}</h2>
            <template v-for="(block, blockIndex) in section.blocks || []" :key="`${section.title}-${blockIndex}`">
              <h3 v-if="block.type === 'subtitle'">{{ block.text }}</h3>
              <p v-else-if="block.type === 'paragraph'" class="report-paragraph">{{ block.text }}</p>
              <ol v-else-if="block.type === 'list'" class="report-list">
                <li v-for="item in block.items || []" :key="item">{{ item }}</li>
              </ol>
              <div v-else-if="block.type === 'table'" class="report-table-shell">
                <table class="report-table">
                  <thead>
                    <tr>
                      <th v-for="(header, headerIndex) in block.headers || []" :key="`${headerIndex}-${header}`">
                        {{ header }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in block.rows || []" :key="rowIndex">
                      <td v-for="(cell, cellIndex) in normalizedRow(row, block.headers)" :key="cellIndex">
                        <span v-for="(line, lineIndex) in splitCell(cell)" :key="lineIndex">{{ line }}</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </section>
        </div>
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

const reportSections = computed(() => reportMeta.value?.sections || [])

const reportCourseTitle = computed(() => {
  const info = reportMeta.value?.courseInfo || {}
  return info.name ? `${info.name}（${info.code || '--'}）` : '--'
})

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function formatRatio(value) {
  return Number(value || 0).toFixed(3)
}

function normalizedRow(row, headers = []) {
  const cells = Array.isArray(row) ? [...row] : []
  const minLength = Array.isArray(headers) ? headers.length : 0
  while (cells.length < minLength) {
    cells.push('')
  }
  return cells
}

function splitCell(value) {
  return String(value ?? '').split(/\n+/)
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
.report-preview {
  display: grid;
  gap: 18px;
}

.preview-summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.report-paper {
  display: grid;
  gap: 24px;
  padding: 26px;
  border: 1px solid #dfe9ee;
  border-radius: 8px;
  background: #fff;
}

.report-section {
  display: grid;
  gap: 14px;
  padding-bottom: 22px;
  border-bottom: 1px solid #edf2f5;
}

.report-section:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.report-section h2 {
  margin: 0;
  color: var(--color-primary-deep);
  font-size: 19px;
  line-height: 1.45;
}

.report-section h3 {
  margin: 6px 0 0;
  color: var(--color-text);
  font-size: 16px;
  line-height: 1.5;
}

.report-paragraph {
  margin: 0;
  color: var(--color-text);
  line-height: 1.9;
}

.report-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 22px;
  color: var(--color-text);
  line-height: 1.8;
}

.report-table-shell {
  max-height: 520px;
  overflow: auto;
  border: 1px solid #e1ebf0;
  border-radius: 8px;
  background: #fff;
}

.report-table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
  font-size: 13px;
}

.report-table th,
.report-table td {
  padding: 10px 12px;
  border: 1px solid #e4edf2;
  vertical-align: top;
  text-align: left;
  line-height: 1.6;
}

.report-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f2f7fa;
  color: var(--color-primary-deep);
  font-weight: 700;
}

.report-table td span {
  display: block;
}

@media (max-width: 720px) {
  .report-paper {
    padding: 16px;
  }

  .report-table {
    min-width: 640px;
  }
}
</style>
