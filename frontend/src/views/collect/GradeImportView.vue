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

    <PanelCard title="导入参数" subtitle="支持按课程、考核项和学期建立独立导入批次。">
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
          <label>考核项</label>
          <select v-model="form.assessItemId" class="select-input">
            <option v-for="item in filteredAssessItems" :key="item.id" :value="item.id">
              {{ item.itemName }}（权重 {{ item.weight }}%）
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

      <div class="actions-inline mt-16">
        <input type="file" accept=".xlsx,.xls" @change="handleFileChange" />
        <button class="btn btn-secondary" :disabled="uploading" @click="handleUpload">
          {{ uploading ? '上传中...' : '上传并创建批次' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard v-if="batch" title="导入批次状态" :subtitle="`批次号：${batch.batchId}`">
      <div class="info-strip">
        <div>文件：{{ batch.fileName }}</div>
        <div>状态：{{ batch.status }}</div>
        <div>有效行：{{ batch.validRows || 0 }}</div>
        <div>异常行：{{ batch.errorRows || 0 }}</div>
      </div>
    </PanelCard>

    <PanelCard
      v-if="batch && batch.status === 'PARSED'"
      title="校验预览结果"
      subtitle="请先核对异常行，再确认导入有效数据。"
    >
      <div class="chip-row">
        <div class="chip">
          <span class="chip-dot"></span>
          <span>有效数据 {{ batch.validRows }}</span>
        </div>
        <div class="chip">
          <span class="chip-dot" style="background: var(--color-danger)"></span>
          <span>异常数据 {{ batch.errorRows }}</span>
        </div>
      </div>

      <div class="table-shell mt-16">
        <table class="data-table">
          <thead>
            <tr>
              <th>行号</th>
              <th>学号</th>
              <th>姓名</th>
              <th>成绩</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in batch.preview" :key="`${item.row}-${item.studentId}`">
              <td>{{ item.row }}</td>
              <td>{{ item.studentId }}</td>
              <td>{{ item.name }}</td>
              <td>{{ item.score }}</td>
              <td>
                <StatusBadge
                  :text="item.valid ? '有效' : item.errorMsg"
                  :tone="item.valid ? 'success' : 'danger'"
                />
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
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  confirmGradeBatch,
  getGradeBatchPreview,
  getReferenceCatalogs,
  uploadGradeFile
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { collectModuleTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: [],
  assessItems: []
})

const form = reactive({
  courseId: '',
  assessItemId: '',
  semester: ''
})

const selectedFile = ref(null)
const uploading = ref(false)
const batch = ref(null)
const message = reactive({
  type: 'success',
  text: ''
})

let pollingTimer = null

const filteredAssessItems = computed(() =>
  catalogs.assessItems.filter((item) => Number(item.courseId) === Number(form.courseId))
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

function stopPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

watch(filteredAssessItems, (items) => {
  if (!items.length) {
    form.assessItemId = ''
    return
  }

  if (!items.some((item) => Number(item.id) === Number(form.assessItemId))) {
    form.assessItemId = items[0].id
  }
})

async function pollBatch(batchId) {
  const data = await getGradeBatchPreview(batchId)
  batch.value = data
  if (data.status === 'PARSED') {
    stopPolling()
    setMessage('success', '导入文件已解析完成，请核对预览结果并确认导入。')
  }
}

async function handleUpload() {
  if (!selectedFile.value) {
    setMessage('error', '请先选择成绩文件。')
    return
  }

  uploading.value = true
  setMessage('', '')

  try {
    const created = await uploadGradeFile({
      file: selectedFile.value,
      courseId: form.courseId,
      assessItemId: form.assessItemId,
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
    setMessage(
      'success',
      `导入完成：已写入 ${result.importedCount} 条有效成绩，跳过 ${result.skippedCount} 条异常数据。`
    )
  } catch (error) {
    setMessage('error', error.message || '成绩导入失败。')
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
