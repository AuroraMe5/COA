<template>
  <div class="app-page page-stack" :class="{ 'embedded-page': embedded }">
    <ModuleHeader
      v-if="!embedded"
      title="教学目标管理"
      description="将目标分解与权重从原有独立页面并入教学目标管理模块。这里用于集中校验目标层与分解点层的权重配置。"
    >
      <template #actions>
        <button class="btn btn-primary" @click="saveAll">保存权重配置</button>
      </template>
    </ModuleHeader>

    <div v-if="!embedded" class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadWeights">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadWeights">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="toolbar-spacer"></div>
      <div class="chip-row">
        <div class="chip">
          <span class="chip-dot"></span>
          <span>目标权重合计 {{ totalWeight.toFixed(2) }}</span>
        </div>
        <div class="chip">
          <span class="chip-dot" :style="{ background: invalidCount ? 'var(--color-danger)' : 'var(--color-secondary)' }"></span>
          <span>待修正目标 {{ invalidCount }}</span>
        </div>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard
      title="目标与分解点权重"
      subtitle="课程目标总权重应为 100，每个目标下分解点权重也建议合计为 100。保存前系统会进行统一校验。"
    >
      <div class="detail-list">
        <div v-for="objective in objectives" :key="objective.id" class="weight-card">
          <div class="achievement-row">
            <strong>{{ objective.objCode }}</strong>
            <div class="chip-row">
              <StatusBadge :text="objective.objTypeName" :tone="typeTone(objective.objType)" />
              <StatusBadge
                :text="`分解合计 ${decomposeTotal(objective).toFixed(2)}`"
                :tone="Math.abs(decomposeTotal(objective) - 100) <= 0.01 ? 'success' : 'warning'"
              />
            </div>
          </div>
          <p class="card-note mt-8">{{ objective.objContent }}</p>
          <div class="form-grid-2 mt-12">
            <div class="form-field">
              <label>目标权重</label>
              <input v-model="objective.weight" class="text-input" type="number" min="0.01" max="100" step="0.01" />
            </div>
          </div>
          <div class="table-shell mt-16">
            <table class="data-table">
              <thead>
                <tr>
                  <th>分解点编号</th>
                  <th>分解点内容</th>
                  <th>类型</th>
                  <th>权重</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="part in objective.decompose" :key="part.id">
                  <td>{{ part.code }}</td>
                  <td>{{ part.content }}</td>
                  <td>{{ part.typeLabel }}</td>
                  <td>
                    <input v-model="part.weight" class="text-input table-input" type="number" min="0" max="100" step="0.01" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </PanelCard>

    <div v-if="embedded" class="embedded-footer-actions">
      <button class="btn btn-primary" @click="saveAll">保存权重配置</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getObjectiveWeights, saveObjectiveWeights } from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { showFeedback } from '@/utils/feedback'

const props = defineProps({
  embedded: {
    type: Boolean,
    default: false
  },
  initialCourseId: {
    type: [String, Number],
    default: ''
  },
  initialSemester: {
    type: String,
    default: ''
  }
})

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const objectives = ref([])
const message = reactive({
  type: 'success',
  text: ''
})

const totalWeight = computed(() =>
  objectives.value.reduce((sum, item) => sum + Number(item.weight || 0), 0)
)

const invalidCount = computed(() =>
  objectives.value.filter((item) => Math.abs(decomposeTotal(item) - 100) > 0.01).length
)

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function typeTone(type) {
  if (Number(type) === 1) return 'primary'
  if (Number(type) === 2) return 'success'
  return 'warning'
}

function decomposeTotal(objective) {
  return objective.decompose.reduce((sum, item) => sum + Number(item.weight || 0), 0)
}

async function loadWeights() {
  const data = await getObjectiveWeights(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  objectives.value = data.objectives
}

async function saveAll() {
  if (Math.abs(totalWeight.value - 100) > 0.01) {
    setMessage('error', '课程目标权重合计必须等于 100。')
    return
  }

  const invalidObjective = objectives.value.find((item) => Math.abs(decomposeTotal(item) - 100) > 0.01)
  if (invalidObjective) {
    setMessage('error', `${invalidObjective.objCode} 的分解点权重合计必须等于 100。`)
    return
  }

  try {
    await saveObjectiveWeights({
      courseId: filters.courseId,
      semester: filters.semester,
      objectives: objectives.value
    })
    setMessage('success', '权重配置已保存。')
  } catch (error) {
    setMessage('error', error.message || '保存失败。')
  }
}

onMounted(() => {
  filters.courseId = props.initialCourseId || ''
  filters.semester = props.initialSemester || ''
  loadWeights()
})
</script>

<style scoped>
.embedded-page {
  padding: 0;
}

.embedded-footer-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.weight-card {
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.table-input {
  min-width: 100px;
}
</style>
