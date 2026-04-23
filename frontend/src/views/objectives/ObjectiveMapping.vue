<template>
  <div class="app-page page-stack">
    <div class="page-title">
      <div>
        <h1>目标考核映射</h1>
        <p>按目标维护各考核项的贡献权重矩阵，便于达成度核算直接使用。</p>
      </div>
      <div class="toolbar">
        <button class="btn btn-primary" @click="saveAll">保存映射矩阵</button>
      </div>
    </div>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadMapping">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadMapping">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard title="映射矩阵" subtitle="每个目标行建议合计为 100，低于或高于 100 会显示提醒。">
      <div class="table-shell">
        <table class="data-table matrix-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th>目标内容</th>
              <th v-for="item in assessItems" :key="item.id">{{ item.itemName }}</th>
              <th>合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.objectiveId">
              <td>{{ objectiveLabel(row.objectiveId).objCode }}</td>
              <td>{{ objectiveLabel(row.objectiveId).objContent }}</td>
              <td v-for="item in assessItems" :key="item.id">
                <input
                  v-model="row.values[item.id]"
                  class="text-input matrix-input"
                  type="number"
                  min="0"
                  max="100"
                  step="0.01"
                />
              </td>
              <td>
                <StatusBadge
                  :text="`${rowTotal(row).toFixed(2)}`"
                  :tone="Math.abs(rowTotal(row) - 100) <= 0.01 ? 'success' : 'warning'"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getObjectiveMapping, saveObjectiveMapping } from '@/api'
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

const objectives = ref([])
const assessItems = ref([])
const rows = ref([])
const message = reactive({
  type: 'success',
  text: ''
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function objectiveLabel(id) {
  return objectives.value.find((item) => Number(item.id) === Number(id)) || {
    objCode: '--',
    objContent: '--'
  }
}

function rowTotal(row) {
  return Object.values(row.values).reduce((sum, value) => sum + Number(value || 0), 0)
}

async function loadMapping() {
  const data = await getObjectiveMapping(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  objectives.value = data.objectives
  assessItems.value = data.assessItems
  rows.value = data.rows
}

async function saveAll() {
  try {
    await saveObjectiveMapping({
      courseId: filters.courseId,
      semester: filters.semester,
      rows: rows.value
    })
    setMessage('success', '目标考核映射已保存。')
  } catch (error) {
    setMessage('error', error.message || '保存失败。')
  }
}

onMounted(loadMapping)
</script>

<style scoped>
.matrix-table {
  min-width: 980px;
}

.matrix-input {
  min-width: 90px;
}
</style>
