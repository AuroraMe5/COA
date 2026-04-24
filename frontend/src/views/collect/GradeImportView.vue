<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="数据采集"
      description="数据采集模块统一承接成绩、评价、反思与督导信息，成绩批量导入负责形成达成度核算的基础成绩数据。"
      :tabs="collectModuleTabs"
    />

    <div class="step-row">
      <div class="step-card" :class="{ active: step >= 1 }">
        <div class="step-index">1</div>
        <strong class="mt-12">选择课程</strong>
      </div>
      <div class="step-card" :class="{ active: step >= 2 }">
        <div class="step-index">2</div>
        <strong class="mt-12">上传文件</strong>
      </div>
      <div class="step-card" :class="{ active: step >= 3 }">
        <div class="step-index">3</div>
        <strong class="mt-12">数据校验</strong>
      </div>
      <div class="step-card" :class="{ active: step >= 4 }">
        <div class="step-index">4</div>
        <strong class="mt-12">确认导入</strong>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard title="导入参数" subtitle="支持 xls、xlsx、csv 成绩文件。系统会按当前课程本学期的考核项自动匹配成绩列。">
      <div class="form-grid-2">
        <div class="form-field">
          <label>课程</label>
          <select v-model="form.courseId" class="select-input">
            <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
              {{ course.name }}（{{ course.code }}）
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
      </div>

      <div class="mt-16">
        <strong>当前课程考核项</strong>
        <div v-if="filteredAssessItems.length" class="assess-chip-grid mt-12">
          <div v-for="item in filteredAssessItems" :key="item.id" class="assess-chip">
            <span>{{ item.itemName }}</span>
            <small>{{ item.itemTypeName || itemTypeLabel(item.itemType) }} · 权重 {{ item.weight }}%</small>
          </div>
        </div>
        <div v-else class="notice warning mt-12">
          当前课程本学期尚未配置考核项，请先在教学目标管理中维护后再导入成绩。
        </div>
      </div>

      <div class="actions-inline mt-16">
        <input type="file" accept=".xlsx,.xls,.csv" @change="handleFileChange" />
        <button class="btn btn-secondary" :disabled="uploading" @click="handleUpload">
          {{ uploading ? '上传中...' : '上传并创建批次' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard v-if="batch" title="导入批次状态" :subtitle="`批次号：${batch.batchId}`">
      <div class="info-strip">
        <div>文件：{{ batch.fileName }}</div>
        <div>状态：{{ batchStatusLabel(batch.status) }}</div>
        <div>有效行：{{ batch.validRows || 0 }}</div>
        <div>异常行：{{ batch.errorRows || 0 }}</div>
      </div>
    </PanelCard>

    <PanelCard
      v-if="batch && batch.status === 'PARSED'"
      title="校验预览结果"
    >
      <div class="table-shell grade-preview-shell">
        <table class="data-table grade-preview-table">
          <thead>
            <tr>
              <th class="sticky-col row-col">行号</th>
              <th class="sticky-col student-col">学号</th>
              <th class="sticky-col name-col">姓名</th>
              <th v-for="column in batch.previewColumns || []" :key="column.assessItemId" class="score-col">
                <span>{{ column.assessItemName }}</span>
                <small>满分 {{ column.maxScore }}</small>
              </th>
              <th class="status-col">状态</th>
              <th class="action-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in batch.previewRows || []"
              :key="`${row.row}-${row.studentNo}`"
              :class="{ 'invalid-row': !row.valid }"
            >
              <td class="sticky-col row-col">{{ row.row }}</td>
              <td class="sticky-col student-col">
                <input v-model.trim="row.studentNo" class="text-input preview-input student-input" />
              </td>
              <td class="sticky-col name-col">
                <input v-model.trim="row.studentName" class="text-input preview-input name-input" />
              </td>
              <td v-for="cell in row.cells" :key="cell.gradeId || cell.assessItemId" class="score-cell">
                <input
                  v-model="cell.score"
                  class="text-input preview-input score-input"
                  :class="{ invalid: !cell.valid }"
                  type="number"
                  min="0"
                  :max="cell.maxScore"
                  step="0.01"
                />
                <small v-if="!cell.valid" class="cell-error">{{ cell.errorMsg }}</small>
              </td>
              <td class="status-col">
                <span v-if="!row.valid" class="status-text-error">异常</span>
              </td>
              <td class="action-col">
                <button class="btn btn-secondary btn-compact" :disabled="savingRow === row.row" @click="savePreviewRow(row)">
                  {{ savingRow === row.row ? '保存中' : '保存修改' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="actions-inline mt-16">
        <button class="btn btn-primary" @click="confirmImport">确认导入有效数据</button>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  confirmGradeBatch,
  getGradeBatchPreview,
  getReferenceCatalogs,
  updateGradePreviewRow,
  uploadGradeFile
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import { collectModuleTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: [],
  assessItems: []
})

const form = reactive({
  courseId: '',
  semester: ''
})

const selectedFile = ref(null)
const uploading = ref(false)
const savingRow = ref(null)
const batch = ref(null)
const message = reactive({
  type: 'success',
  text: ''
})

let pollingTimer = null

const filteredAssessItems = computed(() =>
  catalogs.assessItems.filter(
    (item) => Number(item.courseId) === Number(form.courseId) && String(item.semester) === String(form.semester)
  )
)

const step = computed(() => {
  if (!batch.value) return 1
  if (batch.value.status === 'PARSING') return 3
  if (batch.value.status === 'PARSED') return 4
  return 4
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function handleFileChange(event) {
  selectedFile.value = event.target.files?.[0] || null
}

function itemTypeLabel(type) {
  if (type === 'normal') return '平时'
  if (type === 'mid') return '期中'
  if (type === 'final') return '期末'
  if (type === 'practice') return '实践'
  if (type === 'report') return '报告'
  return '其他'
}

function batchStatusLabel(status) {
  if (status === 'PARSING') return '解析中'
  if (status === 'PARSED') return '待确认'
  if (status === 'CONFIRMED') return '已导入'
  if (status === 'FAILED') return '解析失败'
  return status || '--'
}

function stopPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

async function pollBatch(batchId) {
  try {
    const data = await getGradeBatchPreview(batchId)
    batch.value = data
    if (data.status === 'PARSED') {
      stopPolling()
      setMessage('success', '导入文件已解析完成，请核对预览结果并确认导入。')
    }
  } catch (error) {
    stopPolling()
    setMessage('error', error.message || '预览结果读取失败，请稍后重试。')
    throw error
  }
}

async function handleUpload() {
  if (!selectedFile.value) {
    setMessage('error', '请先选择成绩文件。')
    return
  }
  const lowerName = selectedFile.value.name.toLowerCase()
  if (!['.xls', '.xlsx', '.csv'].some((ext) => lowerName.endsWith(ext))) {
    setMessage('error', '仅支持上传 xls、xlsx 或 csv 格式的成绩文件。')
    return
  }
  if (!filteredAssessItems.value.length) {
    setMessage('error', '当前课程本学期尚未配置考核项，无法导入成绩。')
    return
  }

  uploading.value = true
  setMessage('', '')

  try {
    const created = await uploadGradeFile({
      file: selectedFile.value,
      courseId: form.courseId,
      semester: form.semester
    })
    batch.value = {
      batchId: created.batchId,
      status: created.status
    }
    setMessage('success', '导入批次已创建，系统正在轮询解析结果。')
    stopPolling()
    pollingTimer = window.setInterval(() => pollBatch(created.batchId), 1000)
    await pollBatch(created.batchId)
  } catch (error) {
    setMessage('error', error.message || '导入批次创建失败。')
  } finally {
    uploading.value = false
  }
}

async function confirmImport() {
  try {
    const result = await confirmGradeBatch(batch.value.batchId, { importMode: 'valid_only' })
    if (batch.value) {
      batch.value.status = 'CONFIRMED'
    }
    setMessage(
      'success',
      `导入完成：已写入 ${result.importedCount} 条有效成绩，跳过 ${result.skippedCount} 条异常数据。`
    )
  } catch (error) {
    setMessage('error', error.message || '成绩导入失败。')
  }
}

async function savePreviewRow(row) {
  if (!batch.value) return
  savingRow.value = row.row
  try {
    const updated = await updateGradePreviewRow(batch.value.batchId, {
      studentNo: row.studentNo,
      studentName: row.studentName,
      cells: row.cells.map((cell) => ({
        gradeId: cell.gradeId,
        score: cell.score,
        maxScore: cell.maxScore
      }))
    })
    batch.value = updated
    setMessage('success', `第 ${row.row} 行已重新校验。`)
  } catch (error) {
    setMessage('error', error.message || '预览行保存失败。')
  } finally {
    savingRow.value = null
  }
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  catalogs.assessItems = data.assessItems
  form.courseId = data.courses[0]?.id || ''
  form.semester = data.semesters[0] || ''
}

onMounted(loadCatalogs)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.assess-chip-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}

.assess-chip {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #dce8ef;
  border-radius: 10px;
  background: #fbfdfe;
}

.assess-chip span {
  font-weight: 700;
}

.assess-chip small {
  color: var(--color-text-soft);
}

.grade-preview-shell {
  border: 1px solid #e2edf3;
  border-radius: 8px;
  max-height: 560px;
}

.grade-preview-table {
  min-width: 980px;
}

.grade-preview-table th {
  position: sticky;
  top: 0;
  z-index: 2;
}

.grade-preview-table th,
.grade-preview-table td {
  padding: 10px;
  vertical-align: middle;
  background: #fff;
}

.grade-preview-table th {
  background: #f7fbfd;
}

.grade-preview-table .sticky-col {
  position: sticky;
  z-index: 3;
  box-shadow: 1px 0 0 #e8f0f4;
}

.grade-preview-table th.sticky-col {
  z-index: 4;
}

.row-col {
  left: 0;
  width: 72px;
  min-width: 72px;
}

.student-col {
  left: 72px;
  width: 150px;
  min-width: 150px;
}

.name-col {
  left: 222px;
  width: 128px;
  min-width: 128px;
}

.score-col {
  min-width: 132px;
}

.score-col span {
  display: block;
  color: var(--color-text);
}

.score-col small {
  display: block;
  margin-top: 4px;
  color: var(--color-text-soft);
  font-weight: 500;
}

.status-col {
  min-width: 150px;
}

.action-col {
  min-width: 110px;
}

.preview-input {
  width: 100%;
  min-width: 0;
  padding: 8px 9px;
  border-radius: 6px;
}

.score-input {
  max-width: 96px;
}

.preview-input.invalid {
  border-color: var(--color-danger);
  background: #fff7f7;
}

.invalid-row td {
  background: #fffafa;
}

.cell-error {
  display: block;
  margin-top: 6px;
  color: var(--color-danger);
  font-size: 12px;
  line-height: 1.35;
}

.status-text-error {
  color: var(--color-danger);
  font-weight: 700;
}

.btn-compact {
  padding: 8px 12px;
  white-space: nowrap;
}
</style>
