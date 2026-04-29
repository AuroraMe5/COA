<template>
  <div class="app-page page-stack">
    <ModuleHeader title="成绩批量导入" />

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard title="导入参数">
      <div class="form-grid-4">
        <div class="form-field">
          <label>班级</label>
          <select v-model="form.classId" class="select-input" @change="handleClassChange">
            <option value="">不限定班级</option>
            <option v-for="item in catalogs.classes" :key="item.id" :value="item.id">{{ item.className }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>学期</label>
          <select v-model="form.semester" class="select-input" @change="handleSemesterChange">
            <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">{{ semester }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>课程</label>
          <select v-model="form.courseId" class="select-input">
            <option value="">请选择课程</option>
            <option v-for="course in courseOptions" :key="course.id" :value="course.id">
              {{ course.name }}（{{ course.code }}）
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>成绩文件</label>
          <input type="file" accept=".xlsx,.xls,.csv" @change="handleFileChange" />
        </div>
      </div>

      <div class="assess-chip-grid mt-16">
        <div v-for="item in filteredAssessItems" :key="item.id" class="assess-chip">
          <span>{{ item.itemName }}</span>
          <small>{{ item.itemTypeName }} · 权重 {{ item.weight }}%</small>
        </div>
        <div v-if="form.courseId && form.semester && !filteredAssessItems.length" class="notice warning">
          当前课程本学期尚未配置考核项。
        </div>
      </div>

      <div class="actions-inline mt-16">
        <button class="btn btn-secondary" :disabled="uploading" @click="handleUpload">
          {{ uploading ? '上传中...' : '上传并创建成绩批次' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard v-if="batch" title="成绩导入批次">
      <div class="info-strip">
        <div>文件：{{ batch.fileName || '—' }}</div>
        <div>状态：{{ batchStatusLabel(batch.status) }}</div>
        <div>有效行：{{ batch.validRows || 0 }}</div>
        <div>异常行：{{ batch.errorRows || 0 }}</div>
      </div>
    </PanelCard>

    <PanelCard v-if="batch && batch.status === 'PARSED'" title="成绩预览">
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
            <tr v-for="row in batch.previewRows || []" :key="`${row.row}-${row.studentNo}`" :class="{ 'invalid-row': !row.valid }">
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
                <span v-else class="metric">有效</span>
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
  getClassCourses,
  getGradeBatchPreview,
  getReferenceCatalogs,
  updateGradePreviewRow,
  uploadGradeFile
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import { showFeedback } from '@/utils/feedback'

const catalogs = reactive({ courses: [], semesters: [], assessItems: [], classes: [] })
const classCourses = ref([])
const selectedFile = ref(null)
const uploading = ref(false)
const savingRow = ref(null)
const batch = ref(null)
const message = reactive({ type: 'success', text: '' })
let pollingTimer = null

const form = reactive({ classId: '', semester: '', courseId: '' })

const courseOptions = computed(() => {
  if (!form.classId) return catalogs.courses
  const courseIds = new Set(
    classCourses.value
      .filter((item) => String(item.classId) === String(form.classId) && String(item.semester) === String(form.semester))
      .map((item) => String(item.courseId))
  )
  return courseIds.size ? catalogs.courses.filter((course) => courseIds.has(String(course.id))) : catalogs.courses
})

const filteredAssessItems = computed(() =>
  catalogs.assessItems.filter(
    (item) => String(item.courseId) === String(form.courseId) && String(item.semester) === String(form.semester)
  )
)

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function handleFileChange(event) {
  selectedFile.value = event.target.files?.[0] || null
}

async function handleClassChange() {
  await loadClassCourses()
  pickDefaultCourse()
}

async function handleSemesterChange() {
  await loadClassCourses()
  pickDefaultCourse()
}

function pickDefaultCourse() {
  if (!courseOptions.value.some((item) => String(item.id) === String(form.courseId))) {
    form.courseId = courseOptions.value[0]?.id || ''
  }
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
  const data = await getGradeBatchPreview(batchId)
  batch.value = data
  if (data.status === 'PARSED') {
    stopPolling()
    setMessage('success', '成绩文件已解析完成。')
  }
}

async function handleUpload() {
  if (!selectedFile.value) {
    setMessage('error', '请先选择成绩文件。')
    return
  }
  if (!form.courseId || !form.semester) {
    setMessage('error', '请选择课程和学期。')
    return
  }
  if (!filteredAssessItems.value.length) {
    setMessage('error', '当前课程本学期尚未配置考核项，无法导入成绩。')
    return
  }
  uploading.value = true
  try {
    const created = await uploadGradeFile({
      file: selectedFile.value,
      courseId: form.courseId,
      classId: form.classId,
      semester: form.semester
    })
    batch.value = { batchId: created.batchId, status: created.status }
    stopPolling()
    pollingTimer = window.setInterval(() => pollBatch(created.batchId), 1000)
    await pollBatch(created.batchId)
  } catch (error) {
    setMessage('error', error.message || '成绩导入批次创建失败。')
  } finally {
    uploading.value = false
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

async function confirmImport() {
  try {
    const result = await confirmGradeBatch(batch.value.batchId, { importMode: 'valid_only' })
    batch.value.status = 'CONFIRMED'
    setMessage('success', `导入完成：已写入 ${result.importedCount} 条有效成绩，跳过 ${result.skippedCount} 条异常数据。`)
  } catch (error) {
    setMessage('error', error.message || '成绩导入失败。')
  }
}

async function loadClassCourses() {
  const data = await getClassCourses({
    classId: form.classId || undefined,
    semester: form.semester || undefined
  })
  classCourses.value = data.items || []
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses || []
  catalogs.semesters = data.semesters || []
  catalogs.assessItems = data.assessItems || []
  catalogs.classes = data.classes || []
  form.semester = catalogs.semesters[0] || ''
  form.courseId = catalogs.courses[0]?.id || ''
  await loadClassCourses()
  pickDefaultCourse()
}

onMounted(loadCatalogs)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.form-grid-4 {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

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
  border-radius: var(--radius-sm);
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
  min-height: 30px;
  padding: 6px 10px;
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .form-grid-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 780px) {
  .form-grid-4 {
    grid-template-columns: 1fr;
  }
}
</style>
