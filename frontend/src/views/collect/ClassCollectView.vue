<template>
  <div class="app-page page-stack">
    <ModuleHeader title="班级与学生管理" />

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div class="summary-grid">
      <div class="summary-card">
        <span>班级</span>
        <strong>{{ classes.length }}</strong>
      </div>
      <div class="summary-card">
        <span>当前班级学生</span>
        <strong>{{ students.length }}</strong>
      </div>
      <div class="summary-card">
        <span>班级课程</span>
        <strong>{{ classCourses.length }}</strong>
      </div>
    </div>

    <div class="grid-2">
      <PanelCard title="班级管理">
        <div class="form-grid-2">
          <div class="form-field">
            <label>班级编码</label>
            <input v-model.trim="classForm.classCode" class="text-input" placeholder="如：CS2023-01" />
          </div>
          <div class="form-field">
            <label>班级名称</label>
            <input v-model.trim="classForm.className" class="text-input" placeholder="如：计算机2023级1班" />
          </div>
          <div class="form-field">
            <label>专业</label>
            <select v-model="classForm.majorId" class="select-input">
              <option value="">未设置</option>
              <option v-for="major in catalogs.majors" :key="major.id" :value="major.id">
                {{ major.name }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label>年级</label>
            <input v-model.trim="classForm.gradeYear" class="text-input" placeholder="如：2023" />
          </div>
        </div>
        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="savingClass" @click="submitClass">
            {{ savingClass ? '保存中...' : classForm.id ? '保存班级' : '创建班级' }}
          </button>
          <button class="btn btn-light" @click="resetClassForm">清空</button>
        </div>

        <div class="table-shell mt-16 class-table-shell">
          <table class="data-table compact-table">
            <thead>
              <tr>
                <th>班级</th>
                <th>专业</th>
                <th>年级</th>
                <th>学生</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in classes" :key="item.id" :class="{ selected: String(item.id) === String(selectedClassId) }">
                <td>
                  <strong>{{ item.className }}</strong>
                  <div class="cell-note">{{ item.classCode }}</div>
                </td>
                <td>{{ item.majorName || '—' }}</td>
                <td>{{ item.gradeYear || '—' }}</td>
                <td>{{ item.studentCount || 0 }}</td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="selectClass(item)">选择</button>
                  <button class="btn btn-light btn-mini" @click="editClass(item)">编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard title="学生信息">
        <div class="panel-context">
          <span>当前班级</span>
          <strong>{{ selectedClass?.className || '请选择班级' }}</strong>
        </div>

        <div class="actions-inline mt-16">
          <input type="file" accept=".xlsx,.xls,.csv" :disabled="!selectedClassId" @change="handleStudentFileChange" />
          <button class="btn btn-secondary" :disabled="!selectedClassId || uploadingStudents" @click="handleStudentUpload">
            {{ uploadingStudents ? '导入中...' : '导入学生' }}
          </button>
          <button class="btn btn-light" :disabled="!selectedClassId" @click="startAddStudent">新增学生</button>
        </div>

        <div v-if="studentDraft" class="editor-card mt-16">
          <div class="form-grid-2">
            <div class="form-field">
              <label>学号</label>
              <input v-model.trim="studentDraft.studentNo" class="text-input" />
            </div>
            <div class="form-field">
              <label>姓名</label>
              <input v-model.trim="studentDraft.studentName" class="text-input" />
            </div>
            <div class="form-field">
              <label>性别</label>
              <select v-model="studentDraft.gender" class="select-input">
                <option value="">未设置</option>
                <option value="男">男</option>
                <option value="女">女</option>
              </select>
            </div>
            <div class="form-field">
              <label>手机号</label>
              <input v-model.trim="studentDraft.phone" class="text-input" />
            </div>
            <div class="form-field">
              <label>邮箱</label>
              <input v-model.trim="studentDraft.email" class="text-input" />
            </div>
          </div>
          <div class="actions-inline mt-16">
            <button class="btn btn-primary" :disabled="savingStudent" @click="submitStudent">
              {{ savingStudent ? '保存中...' : '保存学生' }}
            </button>
            <button class="btn btn-light" @click="cancelStudentDraft">取消</button>
          </div>
        </div>

        <div class="table-shell mt-16 student-table-shell">
          <table class="data-table compact-table">
            <thead>
              <tr>
                <th>学号</th>
                <th>姓名</th>
                <th>性别</th>
                <th>联系方式</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in students" :key="item.id">
                <td class="mono">{{ item.studentNo }}</td>
                <td>{{ item.studentName }}</td>
                <td>{{ item.gender || '—' }}</td>
                <td>
                  <div>{{ item.phone || '—' }}</div>
                  <div class="cell-note">{{ item.email || '' }}</div>
                </td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="editStudent(item)">编辑</button>
                  <button class="btn btn-danger btn-mini" @click="removeStudent(item)">删除</button>
                </td>
              </tr>
              <tr v-if="selectedClassId && !students.length">
                <td colspan="5" class="muted center-text">暂无学生</td>
              </tr>
              <tr v-if="!selectedClassId">
                <td colspan="5" class="muted center-text">请选择班级</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="班级课程">
      <div class="form-grid-4">
        <div class="form-field">
          <label>班级</label>
          <select v-model="courseForm.classId" class="select-input" @change="syncSelectedClassFromCourse">
            <option value="">请选择班级</option>
            <option v-for="item in classes" :key="item.id" :value="item.id">{{ item.className }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>学期</label>
          <select v-model="courseForm.semester" class="select-input" @change="loadClassCourses">
            <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">{{ semester }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>课程</label>
          <select v-model="courseForm.courseId" class="select-input">
            <option value="">请选择课程</option>
            <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
              {{ course.name }}（{{ course.code }}）
            </option>
          </select>
        </div>
        <div class="form-field action-field">
          <label>&nbsp;</label>
          <button class="btn btn-primary" :disabled="savingClassCourse" @click="submitClassCourse">
            {{ savingClassCourse ? '保存中...' : '添加课程' }}
          </button>
        </div>
      </div>

      <div class="table-shell mt-16">
        <table class="data-table compact-table">
          <thead>
            <tr>
              <th>班级</th>
              <th>课程</th>
              <th>学期</th>
              <th>任课教师</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in classCourses" :key="item.id">
              <td>{{ item.className }}</td>
              <td>{{ item.courseName }}（{{ item.courseCode }}）</td>
              <td>{{ item.semester }}</td>
              <td>{{ item.teacherName || '—' }}</td>
              <td>
                <button class="btn btn-danger btn-mini" @click="removeClassCourse(item)">移除</button>
              </td>
            </tr>
            <tr v-if="!classCourses.length">
              <td colspan="5" class="muted center-text">暂无班级课程</td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  deleteClassCourse,
  deleteStudent,
  getClassCourses,
  getClassStudents,
  getClasses,
  getReferenceCatalogs,
  saveClass,
  saveClassCourse,
  saveStudent,
  uploadStudents
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'

const catalogs = reactive({ courses: [], semesters: [], majors: [] })
const classes = ref([])
const students = ref([])
const classCourses = ref([])
const selectedClassId = ref('')
const selectedStudentFile = ref(null)
const uploadingStudents = ref(false)
const savingClass = ref(false)
const savingStudent = ref(false)
const savingClassCourse = ref(false)
const studentDraft = ref(null)
const message = reactive({ type: 'success', text: '' })

const classForm = reactive(blankClass())
const courseForm = reactive({ classId: '', semester: '', courseId: '' })

const selectedClass = computed(() => classes.value.find((item) => String(item.id) === String(selectedClassId.value)))

watch(selectedClassId, async (value) => {
  courseForm.classId = value
  await Promise.all([loadStudents(), loadClassCourses()])
})

function blankClass() {
  return { id: null, classCode: '', className: '', majorId: '', gradeYear: '' }
}

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function resetClassForm() {
  Object.assign(classForm, blankClass())
}

function editClass(item) {
  Object.assign(classForm, {
    id: item.id,
    classCode: item.classCode,
    className: item.className,
    majorId: item.majorId || '',
    gradeYear: item.gradeYear || ''
  })
}

function selectClass(item) {
  selectedClassId.value = item.id
  editClass(item)
}

async function submitClass() {
  savingClass.value = true
  try {
    const result = await saveClass({ ...classForm })
    setMessage('success', classForm.id ? '班级已保存。' : '班级已创建。')
    await loadClasses()
    selectedClassId.value = result.item?.id || classForm.id || selectedClassId.value
    resetClassForm()
  } catch (error) {
    setMessage('error', error.message || '班级保存失败。')
  } finally {
    savingClass.value = false
  }
}

function handleStudentFileChange(event) {
  selectedStudentFile.value = event.target.files?.[0] || null
}

async function handleStudentUpload() {
  if (!selectedClassId.value || !selectedStudentFile.value) {
    setMessage('error', '请先选择班级和学生信息文件。')
    return
  }
  uploadingStudents.value = true
  try {
    const result = await uploadStudents(selectedClassId.value, { file: selectedStudentFile.value })
    students.value = result.students || []
    setMessage('success', `学生导入完成：写入 ${result.importedCount || 0} 人，跳过 ${result.skippedCount || 0} 行。`)
    await loadClasses()
  } catch (error) {
    setMessage('error', error.message || '学生导入失败。')
  } finally {
    uploadingStudents.value = false
  }
}

function startAddStudent() {
  studentDraft.value = {
    id: null,
    studentNo: '',
    studentName: '',
    gender: '',
    classId: selectedClassId.value,
    majorId: selectedClass.value?.majorId || '',
    phone: '',
    email: ''
  }
}

function editStudent(item) {
  studentDraft.value = { ...item }
}

function cancelStudentDraft() {
  studentDraft.value = null
}

async function submitStudent() {
  if (!studentDraft.value) return
  savingStudent.value = true
  try {
    await saveStudent({
      ...studentDraft.value,
      classId: studentDraft.value.classId || selectedClassId.value,
      majorId: studentDraft.value.majorId || selectedClass.value?.majorId || ''
    })
    setMessage('success', '学生信息已保存。')
    cancelStudentDraft()
    await Promise.all([loadStudents(), loadClasses()])
  } catch (error) {
    setMessage('error', error.message || '学生保存失败。')
  } finally {
    savingStudent.value = false
  }
}

async function removeStudent(item) {
  if (!window.confirm(`确定删除 ${item.studentName}（${item.studentNo}）吗？`)) return
  try {
    await deleteStudent(item.id)
    setMessage('success', '学生已删除。')
    await Promise.all([loadStudents(), loadClasses()])
  } catch (error) {
    setMessage('error', error.message || '学生删除失败。')
  }
}

function syncSelectedClassFromCourse() {
  selectedClassId.value = courseForm.classId
}

async function submitClassCourse() {
  if (!courseForm.classId || !courseForm.courseId || !courseForm.semester) {
    setMessage('error', '请选择班级、课程和学期。')
    return
  }
  savingClassCourse.value = true
  try {
    const data = await saveClassCourse({ ...courseForm })
    classCourses.value = data.items || []
    setMessage('success', '班级课程已保存。')
  } catch (error) {
    setMessage('error', error.message || '班级课程保存失败。')
  } finally {
    savingClassCourse.value = false
  }
}

async function removeClassCourse(item) {
  if (!window.confirm(`确定移除 ${item.className} 的 ${item.courseName} 吗？`)) return
  try {
    await deleteClassCourse(item.id)
    setMessage('success', '班级课程已移除。')
    await loadClassCourses()
  } catch (error) {
    setMessage('error', error.message || '班级课程移除失败。')
  }
}

async function loadClasses() {
  const data = await getClasses()
  classes.value = data.items || []
  if (!selectedClassId.value && classes.value.length) {
    selectedClassId.value = classes.value[0].id
  }
}

async function loadStudents() {
  if (!selectedClassId.value) {
    students.value = []
    return
  }
  const data = await getClassStudents(selectedClassId.value)
  students.value = data.items || []
}

async function loadClassCourses() {
  const data = await getClassCourses({
    classId: courseForm.classId || selectedClassId.value || undefined,
    semester: courseForm.semester || undefined
  })
  classCourses.value = data.items || []
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses || []
  catalogs.semesters = data.semesters || []
  catalogs.majors = data.majors || []
  courseForm.semester = catalogs.semesters[0] || ''
  courseForm.courseId = catalogs.courses[0]?.id || ''
  await loadClasses()
  await Promise.all([loadStudents(), loadClassCourses()])
}

onMounted(loadCatalogs)
</script>

<style scoped>
.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.summary-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  box-shadow: var(--shadow-soft);
}

.summary-card span {
  color: var(--color-text-soft);
}

.summary-card strong {
  color: var(--color-primary-deep);
  font-size: 24px;
}

.form-grid-2,
.form-grid-4 {
  display: grid;
  gap: 14px;
}

.form-grid-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-grid-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.panel-context {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fbfdfe;
}

.panel-context span,
.cell-note {
  color: var(--color-text-soft);
  font-size: 12px;
}

.panel-context strong {
  color: var(--color-primary-deep);
}

.class-table-shell,
.student-table-shell {
  max-height: 420px;
}

.selected td {
  background: rgba(15, 138, 120, 0.07);
}

.editor-card {
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fbfdfe;
}

.action-field {
  justify-content: end;
}

.center-text {
  text-align: center;
}

.nowrap {
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono, monospace);
}

.btn-mini {
  min-height: 30px;
  padding: 6px 10px;
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .summary-grid,
  .form-grid-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 780px) {
  .summary-grid,
  .form-grid-2,
  .form-grid-4 {
    grid-template-columns: 1fr;
  }
}
</style>
