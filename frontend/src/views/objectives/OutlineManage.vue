<template>
  <div class="app-page page-stack">
    <div class="page-title">
      <div>
        <h1>课程大纲管理</h1>
        <p>维护课程大纲版本、课程简介、目标来源，并支持发布前检查与状态切换。</p>
      </div>
    </div>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadOutlines">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadOutlines">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div class="grid-2">
      <PanelCard title="大纲列表" subtitle="可查看当前课程在学期内的大纲版本与状态。">
        <div v-if="!outlines.length">
          <EmptyState
            mark="纲"
            title="当前课程暂无大纲记录"
            description="填写右侧表单后即可创建当前课程的大纲记录。"
          />
        </div>
        <div v-else class="detail-list">
          <button
            v-for="item in outlines"
            :key="item.id"
            class="outline-item"
            :class="{ active: Number(selectedOutlineId) === Number(item.id) }"
            @click="selectOutline(item)"
          >
            <div class="achievement-row">
              <strong>{{ item.version }}</strong>
              <StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" />
            </div>
            <div class="card-note mt-8">{{ item.overview }}</div>
            <div class="muted mt-8">更新时间：{{ item.updatedAt }}</div>
          </button>
        </div>
      </PanelCard>

      <PanelCard title="大纲编辑" subtitle="可更新课程简介、来源依据，并执行发布校验。">
        <div class="form-stack">
          <div class="form-grid-2">
            <div class="form-field">
              <label>版本号</label>
              <input v-model.trim="form.version" class="text-input" />
            </div>
            <div class="form-field">
              <label>状态</label>
              <select v-model="form.status" class="select-input">
                <option value="DRAFT">草稿</option>
                <option value="PUBLISHED">已发布</option>
              </select>
            </div>
          </div>

          <div class="form-field">
            <label>课程简介</label>
            <textarea v-model.trim="form.overview" class="text-area"></textarea>
          </div>

          <div class="form-field">
            <label>目标来源</label>
            <textarea v-model.trim="form.targetSource" class="text-area"></textarea>
          </div>

          <div class="chip-row">
            <div class="chip">
              <span class="chip-dot"></span>
              <span>目标数量 {{ form.objectiveCount }}</span>
            </div>
            <div class="chip">
              <span class="chip-dot"></span>
              <span>考核项数量 {{ form.assessItemCount }}</span>
            </div>
          </div>

          <div class="actions-inline">
            <button class="btn btn-light" @click="createOutline">新建大纲</button>
            <button class="btn btn-primary" @click="saveCurrent">保存大纲</button>
            <button class="btn btn-secondary" :disabled="!form.id" @click="publishCurrent">发布大纲</button>
          </div>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getOutlines, publishOutline, saveOutline } from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const outlines = ref([])
const selectedOutlineId = ref(null)
const message = reactive({
  type: 'success',
  text: ''
})

const form = reactive({
  id: null,
  courseId: '',
  semester: '',
  version: 'V1.0',
  status: 'DRAFT',
  overview: '',
  targetSource: '',
  objectiveCount: 0,
  assessItemCount: 0
})

function statusTone(status) {
  return status === 'PUBLISHED' ? 'success' : 'warning'
}

function statusText(status) {
  return status === 'PUBLISHED' ? '已发布' : '草稿'
}

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function applyForm(payload) {
  form.id = payload.id || null
  form.courseId = payload.courseId || filters.courseId
  form.semester = payload.semester || filters.semester
  form.version = payload.version || 'V1.0'
  form.status = payload.status || 'DRAFT'
  form.overview = payload.overview || ''
  form.targetSource = payload.targetSource || ''
  form.objectiveCount = payload.objectiveCount || 0
  form.assessItemCount = payload.assessItemCount || 0
}

function selectOutline(item) {
  selectedOutlineId.value = item.id
  applyForm(item)
}

function createOutline() {
  selectedOutlineId.value = null
  applyForm({
    id: null,
    courseId: filters.courseId,
    semester: filters.semester,
    version: `V${outlines.value.length + 1}.0`,
    status: 'DRAFT',
    objectiveCount: 0,
    assessItemCount: 0
  })
}

async function loadOutlines() {
  const data = await getOutlines(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  outlines.value = data.items
  if (data.items.length) {
    selectOutline(data.items[0])
  } else {
    createOutline()
  }
}

async function saveCurrent() {
  await saveOutline({ ...form, courseId: Number(form.courseId) || Number(filters.courseId), semester: filters.semester })
  setMessage('success', '课程大纲已保存。')
  await loadOutlines()
}

async function publishCurrent() {
  try {
    await publishOutline(form.id)
    setMessage('success', '大纲已发布，相关目标配置校验通过。')
    await loadOutlines()
  } catch (error) {
    setMessage('error', error.message || '发布失败，请检查目标与权重配置。')
  }
}

onMounted(loadOutlines)
</script>

<style scoped>
.outline-item {
  width: 100%;
  border: 1px solid #e6eef2;
  border-radius: 18px;
  background: #fbfdfe;
  padding: 16px;
  text-align: left;
}

.outline-item.active {
  border-color: rgba(31, 95, 139, 0.28);
  background: linear-gradient(180deg, rgba(234, 243, 251, 0.85), #fff);
}
</style>
