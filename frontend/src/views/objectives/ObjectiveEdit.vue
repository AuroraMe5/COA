<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="教学目标管理"
      :description="isEditMode ? '在统一模块中维护教学目标基础信息与分解点。保存后会回到教学目标列表，后续可继续在权重页集中校验。' : '在教学目标管理模块中新增目标，并同步维护目标类型、权重与分解点，为后续达成度核算做好准备。'"
    >
      <template #actions>
        <button class="btn btn-light" @click="router.push('/objectives/list')">返回列表</button>
        <button class="btn btn-primary" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '保存中...' : '保存目标' }}
        </button>
      </template>
    </ModuleHeader>

    <div class="info-strip">
      <div>当前模式：{{ isEditMode ? '编辑现有目标' : '新增教学目标' }}</div>
      <div>当前课程：{{ currentCourseName }}</div>
      <div>当前学期：{{ form.semester || '--' }}</div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard
      title="目标基础信息"
      subtitle="新增目标时会自动关联当前课程大纲；目标分解与权重可在本页一并维护。"
    >
      <div class="form-grid-2">
        <div class="form-field">
          <label>课程</label>
          <select v-model="form.courseId" class="select-input" @change="syncCourseMeta">
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
        <div class="form-field">
          <label>目标编号</label>
          <input v-model.trim="form.objCode" class="text-input" placeholder="如 OBJ-1，留空则自动生成" />
        </div>
        <div class="form-field">
          <label>目标类型</label>
          <select v-model="form.objType" class="select-input">
            <option :value="1">知识</option>
            <option :value="2">能力</option>
            <option :value="3">素养</option>
          </select>
        </div>
        <div class="form-field">
          <label>目标权重</label>
          <input v-model="form.weight" class="text-input" type="number" min="0.01" max="100" step="0.01" />
        </div>
      </div>

      <div class="form-field mt-16">
        <label>目标内容</label>
        <textarea v-model.trim="form.objContent" class="text-area" placeholder="请输入教学目标内容"></textarea>
      </div>
    </PanelCard>

    <PanelCard
      title="目标分解点"
      subtitle="支持维护知识点、能力点或素养点。建议完成录入后从侧边栏进入“目标分解与权重”进行合计校验。"
    >
      <div class="detail-list">
        <div v-for="(item, index) in form.decompose" :key="item.id" class="decompose-card">
          <div class="form-grid-2">
            <div class="form-field">
              <label>分解点编号</label>
              <input v-model.trim="item.code" class="text-input" />
            </div>
            <div class="form-field">
              <label>分解点类型</label>
              <select v-model="item.typeLabel" class="select-input">
                <option>知识点</option>
                <option>能力点</option>
                <option>素养点</option>
              </select>
            </div>
            <div class="form-field">
              <label>分解点内容</label>
              <textarea v-model.trim="item.content" class="text-area"></textarea>
            </div>
            <div class="form-field">
              <label>分解点权重</label>
              <input v-model="item.weight" class="text-input" type="number" min="0" max="100" step="0.01" />
            </div>
          </div>
          <div class="actions-inline mt-12">
            <button class="btn btn-danger" @click="removeDecompose(index)">删除分解点</button>
          </div>
        </div>
      </div>

      <div class="actions-inline mt-16">
        <button class="btn btn-light" @click="addDecompose">新增分解点</button>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getObjectiveDetail, getReferenceCatalogs, saveObjective } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import { showFeedback } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const submitting = ref(false)

const catalogs = reactive({
  courses: [],
  semesters: []
})

const message = reactive({
  type: 'success',
  text: ''
})

const form = reactive({
  id: null,
  courseId: '',
  outlineId: '',
  semester: '',
  objCode: '',
  objContent: '',
  objType: 1,
  weight: '',
  decompose: []
})

const isEditMode = computed(() => Boolean(route.params.id))
const currentCourseName = computed(() => {
  return catalogs.courses.find((course) => Number(course.id) === Number(form.courseId))?.name || '--'
})

function buildDecompose() {
  return {
    id: Date.now() + Math.random(),
    code: '',
    content: '',
    typeLabel: '知识点',
    weight: 0
  }
}

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function syncCourseMeta() {
  const currentCourse = catalogs.courses.find((course) => Number(course.id) === Number(form.courseId))
  if (currentCourse) {
    form.outlineId = currentCourse.outlineId
    if (!form.semester) {
      form.semester = currentCourse.semester
    }
  }
}

function addDecompose() {
  form.decompose.push(buildDecompose())
}

function removeDecompose(index) {
  if (form.decompose.length === 1) {
    setMessage('warning', '至少保留一个分解点，或完成目标录入后再到权重页统一调整。')
    return
  }
  form.decompose.splice(index, 1)
}

function applyPayload(payload) {
  form.id = payload.id
  form.courseId = Number(payload.courseId)
  form.outlineId = payload.outlineId
  form.semester = payload.semester
  form.objCode = payload.objCode
  form.objContent = payload.objContent
  form.objType = Number(payload.objType)
  form.weight = payload.weight
  form.decompose = (payload.decompose || []).length ? payload.decompose : [buildDecompose()]
}

function validate() {
  if (!form.objContent) {
    setMessage('error', '请输入目标内容。')
    return false
  }

  const weight = Number(form.weight)
  if (!weight || weight <= 0 || weight > 100) {
    setMessage('error', '目标权重必须在 0.01 到 100 之间。')
    return false
  }

  const invalidDecompose = form.decompose.some((item) => !item.content)
  if (invalidDecompose) {
    setMessage('error', '请填写所有分解点内容。')
    return false
  }

  return true
}

async function loadPage() {
  const catalogsData = await getReferenceCatalogs()
  catalogs.courses = catalogsData.courses
  catalogs.semesters = catalogsData.semesters

  if (route.params.id) {
    const data = await getObjectiveDetail(route.params.id)
    applyPayload(data)
    return
  }

  const queryCourseId = Number(route.query.courseId || catalogs.courses[0]?.id)
  const currentCourse = catalogs.courses.find((course) => Number(course.id) === queryCourseId) || catalogs.courses[0]
  applyPayload({
    id: null,
    courseId: currentCourse.id,
    outlineId: currentCourse.outlineId,
    semester: route.query.semester || currentCourse.semester,
    objCode: '',
    objContent: '',
    objType: 1,
    weight: '',
    decompose: [buildDecompose()]
  })
}

async function handleSubmit() {
  if (!validate()) {
    return
  }

  submitting.value = true
  setMessage('', '')

  try {
    await saveObjective({
      ...form,
      weight: Number(form.weight),
      objType: Number(form.objType)
    })
    setMessage('success', '教学目标已保存，正在返回列表页。')
    window.setTimeout(() => {
      router.push('/objectives/list')
    }, 700)
  } catch (error) {
    setMessage('error', error.message || '保存失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}

onMounted(loadPage)
</script>

<style scoped>
.decompose-card {
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}
</style>
