<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="学生成绩管理"
      description="按课程和学期查询已确认成绩，支持按学生横向查看各考核项成绩，并可进行新增、修改和删除。"
    />

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard title="查询条件" subtitle="请选择课程和学期后查询。可按考核项或学生学号、姓名进一步筛选。">
      <div class="form-grid-4">
        <div class="form-field">
          <label>课程</label>
          <select v-model="filter.courseId" class="select-input">
            <option value="">请选择课程</option>
            <option v-for="course in catalogs.courses" :key="course.id" :value="String(course.id)">
              {{ course.name }}（{{ course.code }}）
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>学期</label>
          <select v-model="filter.semester" class="select-input">
            <option value="">请选择学期</option>
            <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">{{ semester }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>班级</label>
          <select v-model="filter.classId" class="select-input">
            <option value="">全部班级</option>
            <option v-for="item in catalogs.classes" :key="item.id" :value="String(item.id)">
              {{ item.className }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>考核项</label>
          <select v-model="filter.assessItemId" class="select-input">
            <option value="">全部考核项</option>
            <option v-for="item in filteredAssessItems" :key="item.id" :value="String(item.id)">
              {{ item.itemName }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>学生</label>
          <input v-model.trim="filter.keyword" class="text-input" placeholder="学号或姓名" />
        </div>
      </div>
      <div class="actions-inline mt-16">
        <button class="btn btn-light" @click="resetFilter">重置</button>
      </div>
    </PanelCard>

    <PanelCard title="成绩列表">
      <template #subtitle>
        <span v-if="filter.courseId && filter.semester">
          共 <strong>{{ total }}</strong> 名学生
          <span v-if="loading" class="muted">（更新中…）</span>
        </span>
        <span v-else class="muted">请先选择课程和学期</span>
      </template>

      <div v-if="!filter.courseId || !filter.semester" class="notice info">
        请先选择课程和学期，筛选结果将实时刷新。
      </div>
      <div v-else-if="loading && !rows.length" class="notice info">正在加载成绩...</div>
      <div v-else-if="!loading && !rows.length" class="notice warning">
        未查询到成绩数据。可以通过"成绩批量导入"导入，也可以在此手动新增。
      </div>

      <template v-if="filter.courseId && filter.semester && !loading">
        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="!canEdit" @click="startAddRow">新增学生成绩</button>
        </div>

        <div v-if="draftRow" class="editor-card mt-16">
          <div class="editor-title">{{ draftMode === 'create' ? '新增学生成绩' : '编辑学生成绩' }}</div>
          <div class="form-grid-2 mt-12">
            <div class="form-field">
              <label>学号</label>
              <input v-model.trim="draftRow.studentNo" class="text-input" :disabled="draftMode === 'edit'" />
            </div>
            <div class="form-field">
              <label>姓名</label>
              <input v-model.trim="draftRow.studentName" class="text-input" />
            </div>
          </div>
          <div class="score-editor-grid mt-12">
            <div v-for="cell in draftRow.cells" :key="cell.assessItemId" class="form-field score-editor-cell">
              <label>{{ cell.assessItemName }}</label>
              <input
                v-model="cell.score"
                class="text-input"
                type="number"
                min="0"
                :max="cell.maxScore"
                step="0.01"
              />
              <small>满分 {{ cell.maxScore }}</small>
            </div>
          </div>
          <div class="actions-inline mt-16">
            <button class="btn btn-secondary" :disabled="saving" @click="saveDraftRow">
              {{ saving ? '保存中...' : '保存' }}
            </button>
            <button class="btn btn-light" @click="cancelDraft">取消</button>
          </div>
        </div>

        <div v-if="rows.length" class="table-shell grade-manage-shell mt-16">
          <table class="data-table grade-manage-table">
            <thead>
              <tr>
                <th class="sticky-col seq-col">序号</th>
                <th class="sticky-col student-col">学号</th>
                <th class="sticky-col name-col">姓名</th>
                <th v-for="column in columns" :key="column.assessItemId" class="score-col">
                  <span>{{ column.assessItemName }}</span>
                  <small>满分 {{ column.maxScore }}</small>
                </th>
                <th class="action-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, idx) in rows" :key="row.studentNo">
                <td class="sticky-col seq-col muted">{{ idx + 1 }}</td>
                <td class="sticky-col student-col mono">{{ row.studentNo }}</td>
                <td class="sticky-col name-col">{{ row.studentName }}</td>
                <td v-for="cell in row.cells" :key="cell.assessItemId" class="score-col metric">
                  {{ formatScore(cell.score) }}
                </td>
                <td class="action-col">
                  <button class="btn btn-light btn-compact" @click="startEditRow(row)">编辑</button>
                  <button class="btn btn-danger btn-compact" @click="deleteRow(row)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  deleteImportedGradeRow,
  getImportedGrades,
  getReferenceCatalogs,
  saveImportedGradeRow
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'

const catalogs = reactive({ courses: [], semesters: [], assessItems: [], classes: [] })
const filter = reactive({ courseId: '', semester: '', classId: '', assessItemId: '', keyword: '' })
const columns = ref([])
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const draftRow = ref(null)
const draftMode = ref('')
const message = reactive({ type: 'info', text: '' })

const filteredAssessItems = computed(() => {
  return catalogs.assessItems.filter((item) => {
    const sameCourse = !filter.courseId || String(item.courseId) === String(filter.courseId)
    const sameSemester = !filter.semester || String(item.semester) === String(filter.semester)
    return sameCourse && sameSemester
  })
})

const canEdit = computed(() => Boolean(filter.courseId && filter.semester && filteredAssessItems.value.length))

// 课程或学期变化：重置子筛选并立即查询（两者都选中时），否则清空结果
watch(
  [() => filter.courseId, () => filter.semester],
  ([course, semester]) => {
    filter.assessItemId = ''
    cancelDraft()
    if (course && semester) {
      loadGrades()
    } else {
      rows.value = []
      columns.value = []
      total.value = 0
    }
  }
)

// 考核项变化：直接刷新
watch(
  () => filter.assessItemId,
  () => {
    if (filter.courseId && filter.semester) loadGrades()
  }
)

watch(
  () => filter.classId,
  () => {
    if (filter.courseId && filter.semester) loadGrades()
  }
)

// 关键字变化：防抖 350ms 后刷新
let keywordTimer = null
watch(
  () => filter.keyword,
  () => {
    if (!filter.courseId || !filter.semester) return
    clearTimeout(keywordTimer)
    keywordTimer = setTimeout(loadGrades, 350)
  }
)

function setMessage(type, text) {
  message.type = type
  message.text = text
}

async function loadGrades() {
  if (!filter.courseId || !filter.semester) return

  loading.value = true
  cancelDraft()
  try {
    const data = await getImportedGrades({
      courseId: filter.courseId,
      semester: filter.semester,
      classId: filter.classId || undefined,
      assessItemId: filter.assessItemId || undefined,
      keyword: filter.keyword || undefined
    })
    columns.value = data.columns || []
    rows.value = data.rows || []
    total.value = data.total || rows.value.length
    setMessage('', '')
  } catch (error) {
    columns.value = []
    rows.value = []
    total.value = 0
    setMessage('error', error.message || '成绩查询失败。')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filter.courseId = ''
  filter.semester = ''
  filter.classId = ''
  filter.assessItemId = ''
  filter.keyword = ''
  clearTimeout(keywordTimer)
  cancelDraft()
  setMessage('', '')
  // watch([courseId, semester]) 会自动清空 rows/columns
}

function startAddRow() {
  if (!canEdit.value) {
    setMessage('error', '当前课程学期尚未配置考核项，无法新增成绩。')
    return
  }
  const sourceColumns = columns.value.length
    ? columns.value
    : filteredAssessItems.value
        .filter((item) => !filter.assessItemId || String(item.id) === String(filter.assessItemId))
        .map((item) => ({
        assessItemId: item.id,
        assessItemName: item.itemName,
        maxScore: 100
      }))
  draftMode.value = 'create'
  draftRow.value = {
    studentNo: '',
    studentName: '',
    cells: sourceColumns.map((column) => ({
      gradeId: null,
      assessItemId: column.assessItemId,
      assessItemName: column.assessItemName,
      score: '',
      maxScore: column.maxScore || 100
    }))
  }
}

function startEditRow(row) {
  draftMode.value = 'edit'
  draftRow.value = {
    studentNo: row.studentNo,
    studentName: row.studentName,
    cells: row.cells.map((cell) => ({ ...cell }))
  }
}

function cancelDraft() {
  draftMode.value = ''
  draftRow.value = null
}

async function saveDraftRow() {
  if (!draftRow.value) return
  saving.value = true
  try {
    await saveImportedGradeRow({
      courseId: filter.courseId,
      semester: filter.semester,
      classId: filter.classId,
      studentNo: draftRow.value.studentNo,
      studentName: draftRow.value.studentName,
      cells: draftRow.value.cells
    })
    setMessage('success', draftMode.value === 'create' ? '学生成绩已新增。' : '学生成绩已保存。')
    cancelDraft()
    await loadGrades()
  } catch (error) {
    setMessage('error', error.message || '成绩保存失败。')
  } finally {
    saving.value = false
  }
}

async function deleteRow(row) {
  const confirmed = window.confirm(`确定删除 ${row.studentName}（${row.studentNo}）的本次查询范围内成绩吗？`)
  if (!confirmed) return
  try {
    await deleteImportedGradeRow({
      courseId: filter.courseId,
      semester: filter.semester,
      studentNo: row.studentNo,
      assessItemId: filter.assessItemId || undefined
    })
    setMessage('success', '学生成绩已删除。')
    await loadGrades()
  } catch (error) {
    setMessage('error', error.message || '成绩删除失败。')
  }
}

function formatScore(value) {
  if (value === null || value === undefined || value === '') return '--'
  const number = Number(value)
  return Number.isNaN(number) ? value : number.toFixed(2)
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses || []
  catalogs.semesters = data.semesters || []
  catalogs.assessItems = data.assessItems || []
  catalogs.classes = data.classes || []
}

onMounted(loadCatalogs)
</script>

<style scoped>
.form-grid-4 {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.editor-card {
  padding: 16px;
  border: 1px solid #dce8ef;
  border-radius: 8px;
  background: #fbfdfe;
}

.editor-title {
  font-weight: 800;
}

.score-editor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.score-editor-cell small {
  display: block;
  margin-top: 6px;
  color: var(--color-text-soft);
}

.grade-manage-shell {
  border: 1px solid #e2edf3;
  border-radius: 8px;
  max-height: 560px;
}

.grade-manage-table {
  min-width: 900px;
}

.grade-manage-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #f7fbfd;
}

.grade-manage-table th,
.grade-manage-table td {
  padding: 10px;
  vertical-align: middle;
  background: #fff;
}

.grade-manage-table .sticky-col {
  position: sticky;
  z-index: 3;
  box-shadow: 1px 0 0 #e8f0f4;
}

.grade-manage-table th.sticky-col {
  z-index: 4;
}

.seq-col {
  left: 0;
  width: 64px;
  min-width: 64px;
  text-align: center;
}

.student-col {
  left: 64px;
  width: 150px;
  min-width: 150px;
}

.name-col {
  left: 214px;
  width: 128px;
  min-width: 128px;
}

.score-col {
  min-width: 132px;
  text-align: right;
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

.action-col {
  min-width: 150px;
  white-space: nowrap;
}

.btn-compact {
  padding: 8px 12px;
  margin-right: 8px;
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: 13px;
}

@media (max-width: 1080px) {
  .form-grid-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .form-grid-4 {
    grid-template-columns: 1fr;
  }
}
</style>
