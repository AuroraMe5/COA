<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="数据采集"
      description="将成绩、学生评价、教学反思与督导评价纳入同一数据采集闭环。当前页面用于查看督导评价结果，为分析与改进提供外部教学质量依据。"
      :tabs="collectModuleTabs"
    />

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadRecords">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadRecords">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div class="grid-3">
      <StatCard label="督导记录数" :value="records.length" tone="primary" />
      <StatCard label="平均评分" :value="averageScore" tone="success" />
      <StatCard label="最近评价时间" :value="latestDate" tone="secondary" />
    </div>

    <PanelCard
      title="督导评价列表"
      subtitle="展示督导专家的总体评分、关注要点与原始意见，便于教师在后续分析和改进措施中直接引用。"
    >
      <EmptyState
        v-if="!records.length"
        mark="督"
        title="当前课程暂无督导评价"
        description="待教学督导完成听课与评价后，这里会自动展示对应记录。"
      />
      <div v-else class="detail-list">
        <article v-for="item in records" :key="item.id" class="review-card">
          <div class="achievement-row">
            <div>
              <strong>{{ item.supervisorName }}</strong>
              <div class="muted mt-8">{{ item.createdAt }}</div>
            </div>
            <StatusBadge :text="`${Number(item.score).toFixed(1)} 分`" :tone="scoreTone(item.score)" />
          </div>

          <div class="chip-row mt-16">
            <div v-for="focus in item.focusItems" :key="focus" class="chip">
              <span class="chip-dot"></span>
              <span>{{ focus }}</span>
            </div>
          </div>

          <p class="review-content">{{ item.content }}</p>
        </article>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getSupervisorEvaluations } from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { collectModuleTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const records = ref([])

const averageScore = computed(() => {
  if (!records.value.length) return '--'
  const total = records.value.reduce((sum, item) => sum + Number(item.score || 0), 0)
  return (total / records.value.length).toFixed(1)
})

const latestDate = computed(() => {
  if (!records.value.length) return '--'
  return [...records.value].sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
    .createdAt
})

function scoreTone(score) {
  if (Number(score) >= 90) return 'success'
  if (Number(score) >= 80) return 'warning'
  return 'danger'
}

async function loadRecords() {
  const data = await getSupervisorEvaluations(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  records.value = data.records
}

onMounted(loadRecords)
</script>

<style scoped>
.review-card {
  padding: 18px;
  border-radius: 18px;
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.review-content {
  margin: 16px 0 0;
  line-height: 1.8;
  color: var(--color-text-soft);
}
</style>
