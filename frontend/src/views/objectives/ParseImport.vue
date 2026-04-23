<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="教学目标管理"
      description="智能解析导入作为教学目标管理模块下的子流程，与手工新增目标、目标分解与权重校核共同构成完整管理链路。"
      :tabs="objectiveManageTabs"
    >
      <template #actions>
        <button class="btn btn-light" @click="createObjective">手工新增目标</button>
      </template>
    </ModuleHeader>

    <div class="step-row">
      <div class="step-card" :class="{ active: currentStep >= 1 }">
        <div class="step-index">1</div>
        <strong class="mt-12">上传文件</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 2 }">
        <div class="step-index">2</div>
        <strong class="mt-12">智能解析</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 3 }">
        <div class="step-index">3</div>
        <strong class="mt-12">复核确认</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 4 }">
        <div class="step-index">4</div>
        <strong class="mt-12">写入完成</strong>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard
      title="上传解析文件"
      subtitle="支持上传课程大纲原始文件，系统将调用后端解析任务并返回待复核草稿。"
    >
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

      <div class="mt-16 info-strip">
        <div>当前文件：{{ selectedFileName || '尚未选择文件' }}</div>
        <div>支持格式：pdf、doc、docx</div>
      </div>

      <div class="actions-inline mt-16">
        <input type="file" accept=".pdf,.doc,.docx" @change="handleFileChange" />
        <button class="btn btn-secondary" :disabled="uploading" @click="handleUpload">
          {{ uploading ? '任务创建中...' : '开始智能解析' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard
      v-if="task"
      title="解析任务状态"
      :subtitle="`任务编号：${task.taskId}，文件：${task.fileName || selectedFileName}`"
    >
      <div class="info-strip">
        <div>当前状态：{{ taskStatusLabel(task.status) }}</div>
        <div>提取目标：{{ task.objExtractCount || 0 }}</div>
        <div>提取考核项：{{ task.assessExtractCount || 0 }}</div>
      </div>
    </PanelCard>

    <div v-if="task && task.status === 'DONE'" class="split-panel">
      <PanelCard title="原文定位" subtitle="原始文本片段与解析结果一一对应，便于教师复核。">
        <div class="detail-list">
          <div v-for="segment in task.originalSections" :key="segment.id" class="source-segment">
            <strong>{{ segment.label }}</strong>
            <p>{{ segment.text }}</p>
          </div>
        </div>
      </PanelCard>

      <PanelCard title="解析结果复核" subtitle="可修改目标内容、类型、权重，并选择确认或忽略。">
        <div class="detail-list">
          <div v-for="item in task.objectives" :key="item.id" class="draft-card">
            <div class="achievement-row">
              <strong>{{ item.objCodeSuggest }}</strong>
              <StatusBadge :text="confidenceLabel(item.confidenceLevel)" :tone="confidenceTone(item.confidenceLevel)" />
            </div>
            <div class="form-field mt-12">
              <label>目标内容</label>
              <textarea
                v-model.trim="item.objContentFinal"
                class="text-area"
                @blur="saveDraft(item)"
              ></textarea>
            </div>
            <div class="form-grid-2 mt-12">
              <div class="form-field">
                <label>目标类型</label>
                <select v-model="item.objTypeFinal" class="select-input" @change="saveDraft(item)">
                  <option :value="1">知识</option>
                  <option :value="2">能力</option>
                  <option :value="3">素养</option>
                </select>
              </div>
              <div class="form-field">
                <label>权重</label>
                <input
                  v-model="item.weightFinal"
                  class="text-input"
                  type="number"
                  min="0.01"
                  max="100"
                  step="0.01"
                  @blur="saveDraft(item)"
                />
              </div>
            </div>
            <div class="actions-inline mt-12">
              <button class="btn btn-light" @click="setDraftStatus(item, 1)">确认</button>
              <button class="btn btn-danger" @click="setDraftStatus(item, 2)">忽略</button>
            </div>
          </div>
        </div>

        <div class="mt-16 info-strip">
          <div>已确认权重合计：{{ confirmedWeight.toFixed(2) }}</div>
          <label>
            <input v-model="overwriteExisting" type="checkbox" />
            覆盖当前课程已有目标
          </label>
        </div>

        <div class="actions-inline mt-16">
          <button class="btn btn-primary" @click="confirmTask">确认并写入系统</button>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { confirmParseTask, getParseTaskDetail, getReferenceCatalogs, updateParseDraft, uploadParseFile } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { objectiveManageTabs } from '@/constants/moduleTabs'

const router = useRouter()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const form = reactive({
  courseId: '',
  semester: ''
})

const task = ref(null)
const uploading = ref(false)
const selectedFile = ref(null)
const selectedFileName = ref('')
const overwriteExisting = ref(false)
const message = reactive({
  type: 'success',
  text: ''
})

let pollingTimer = null

const currentStep = computed(() => {
  if (!task.value) return 1
  if (task.value.status === 'PARSING') return 2
  if (task.value.status === 'DONE') return 3
  return 4
})

const confirmedWeight = computed(() => {
  if (!task.value) return 0
  return task.value.objectives
    .filter((item) => Number(item.isConfirmed) === 1)
    .reduce((sum, item) => sum + Number(item.weightFinal || 0), 0)
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function createObjective() {
  router.push({
    path: '/objectives/edit',
    query: {
      courseId: form.courseId,
      semester: form.semester
    }
  })
}

function confidenceTone(level) {
  if (level === 'HIGH') return 'success'
  if (level === 'MEDIUM') return 'warning'
  return 'danger'
}

function confidenceLabel(level) {
  if (level === 'HIGH') return '高'
  if (level === 'MEDIUM') return '中'
  return '低'
}

function taskStatusLabel(status) {
  if (status === 'PARSING') return '解析中'
  if (status === 'DONE') return '待确认'
  if (status === 'CONFIRMED') return '已完成'
  return status || '--'
}

function handleFileChange(event) {
  const file = event.target.files?.[0] || null
  selectedFile.value = file
  selectedFileName.value = file ? file.name : ''
}

function stopPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

async function pollTask(taskId) {
  const data = await getParseTaskDetail(taskId)
  task.value = data
  if (data.status === 'DONE') {
    stopPolling()
    setMessage('success', '解析任务已完成，请复核草稿结果并确认写入。')
  }
}

async function handleUpload() {
  if (!selectedFile.value) {
    setMessage('error', '请先选择需要解析的课程大纲文件。')
    return
  }

  uploading.value = true
  setMessage('', '')

  try {
    const created = await uploadParseFile({
      file: selectedFile.value,
      courseId: form.courseId,
      semester: form.semester
    })
    task.value = {
      taskId: created.taskId,
      status: created.status
    }
    setMessage('success', '解析任务已创建，系统正在轮询任务状态。')
    stopPolling()
    pollingTimer = window.setInterval(() => pollTask(created.taskId), 1000)
    await pollTask(created.taskId)
  } catch (error) {
    setMessage('error', error.message || '解析任务创建失败。')
  } finally {
    uploading.value = false
  }
}

async function saveDraft(item) {
  try {
    await updateParseDraft(item.id, {
      objContentFinal: item.objContentFinal,
      objTypeFinal: Number(item.objTypeFinal),
      weightFinal: Number(item.weightFinal),
      isConfirmed: Number(item.isConfirmed)
    })
  } catch (error) {
    setMessage('error', error.message || '草稿保存失败。')
  }
}

async function setDraftStatus(item, status) {
  item.isConfirmed = status
  await saveDraft(item)
}

async function confirmTask() {
  if (Math.abs(confirmedWeight.value - 100) > 0.01) {
    setMessage('error', '所有已确认目标的权重合计必须等于 100。')
    return
  }

  try {
    const result = await confirmParseTask(task.value.taskId, {
      outlineId: task.value.outlineId,
      overwrite: overwriteExisting.value
    })
    setMessage(
      'success',
      `写入成功：新增 ${result.importedObjectives} 条目标，写入 ${result.importedAssessItems} 项考核配置。`
    )
  } catch (error) {
    setMessage('error', error.message || '写入失败，请检查权重与确认状态。')
  }
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  form.courseId = data.courses[0]?.id || ''
  form.semester = data.semesters[0] || ''
}

onMounted(loadCatalogs)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.source-segment,
.draft-card {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.source-segment p {
  margin: 10px 0 0;
  line-height: 1.8;
  color: var(--color-text-soft);
}
</style>
