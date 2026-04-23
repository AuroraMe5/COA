<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="结果分析与教学改进"
      description="多维分析报表承接达成度核算结果，帮助教师从课程整体、目标明细与历史趋势三个角度定位问题。"
      :tabs="analysisImproveTabs"
    />

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadOverview">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadOverview">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>趋势对象</label>
        <select v-model="filters.objectiveId" class="select-input" @change="loadTrend">
          <option value="">课程整体</option>
          <option v-for="item in overview.objectives" :key="item.objectiveId" :value="item.objectiveId">
            {{ item.objCode }}
          </option>
        </select>
      </div>
    </div>

    <div class="grid-3">
      <StatCard label="课程整体达成度" :value="overview.overallAchievement?.toFixed(3) || '--'" tone="primary" />
      <StatCard label="目标数量" :value="overview.objectives.length" tone="secondary" />
      <StatCard
        label="达成状态"
        :value="overview.isAllAchieved ? '全部达成' : '存在预警'"
        :tone="overview.isAllAchieved ? 'success' : 'warning'"
      />
    </div>

    <div class="grid-2">
      <PanelCard title="课程达成度对比" subtitle="对比当前教师名下课程的整体达成度。">
        <ChartPanel :option="barOption" height="300px" />
      </PanelCard>
      <PanelCard title="目标达成雷达图" subtitle="展示当前课程各教学目标的达成值分布。">
        <ChartPanel :option="radarOption" height="300px" />
      </PanelCard>
    </div>

    <div class="grid-2">
      <PanelCard title="趋势分析" subtitle="支持查看课程整体或单目标达成趋势。">
        <ChartPanel :option="trendOption" height="320px" />
      </PanelCard>
      <PanelCard title="分析摘要" subtitle="自动归纳当前课程的主要分析结论。">
        <div class="detail-list">
          <div v-for="item in overview.suggestionSummary" :key="item" class="source-segment">
            {{ item }}
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="目标明细结果" subtitle="展示各目标在不同考核维度下的表现。">
      <div class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th>平时</th>
              <th>期中</th>
              <th>期末</th>
              <th>达成度</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in overview.objectives" :key="item.objectiveId">
              <td>{{ item.objCode }}</td>
              <td>{{ item.normal.toFixed(2) }}</td>
              <td>{{ item.mid.toFixed(2) }}</td>
              <td>{{ item.final.toFixed(2) }}</td>
              <td class="metric">{{ item.achieveValue.toFixed(2) }}</td>
              <td>
                <StatusBadge :text="item.isAchieved ? '达成' : '未达成'" :tone="item.isAchieved ? 'success' : 'danger'" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getCourseOverview, getTrendData } from '@/api'
import ChartPanel from '@/components/common/ChartPanel.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { analysisImproveTabs } from '@/constants/moduleTabs'

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: '',
  objectiveId: ''
})

const overview = reactive({
  overallAchievement: 0,
  isAllAchieved: false,
  objectives: [],
  compareCourses: [],
  radarData: { indicators: [], values: [] },
  suggestionSummary: []
})

const trend = ref([])

const barOption = computed(() => ({
  grid: { top: 30, right: 20, bottom: 36, left: 40 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: overview.compareCourses.map((item) => item.courseName),
    axisLabel: { color: '#5f7382' }
  },
  yAxis: {
    type: 'value',
    min: 0,
    max: 1,
    axisLabel: { color: '#5f7382' }
  },
  series: [
    {
      type: 'bar',
      data: overview.compareCourses.map((item) => item.value),
      itemStyle: {
        borderRadius: [8, 8, 0, 0],
        color: '#1f5f8b'
      }
    }
  ]
}))

const radarOption = computed(() => ({
  tooltip: {},
  radar: {
    indicator: overview.radarData.indicators,
    splitArea: { areaStyle: { color: ['#f7fbfd', '#eef6f8'] } },
    axisName: { color: '#415463' }
  },
  series: [
    {
      type: 'radar',
      data: [
        {
          value: overview.radarData.values,
          areaStyle: { color: 'rgba(15, 138, 120, 0.22)' },
          lineStyle: { color: '#0f8a78' },
          itemStyle: { color: '#0f8a78' }
        }
      ]
    }
  ]
}))

const trendOption = computed(() => ({
  grid: { top: 30, right: 20, bottom: 36, left: 40 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: trend.value.map((item) => item.semester),
    axisLabel: { color: '#5f7382' }
  },
  yAxis: {
    type: 'value',
    min: 0,
    max: 1,
    axisLabel: { color: '#5f7382' }
  },
  series: [
    {
      type: 'line',
      smooth: true,
      data: trend.value.map((item) => item.value),
      lineStyle: { color: '#0f8a78', width: 3 },
      itemStyle: { color: '#0f8a78' },
      areaStyle: { color: 'rgba(15, 138, 120, 0.16)' }
    }
  ]
}))

async function loadOverview() {
  const data = await getCourseOverview({
    courseId: filters.courseId,
    semester: filters.semester
  })

  catalogs.courses = data.referenceData.courses
  catalogs.semesters = data.referenceData.semesters
  filters.courseId = data.courseId
  filters.semester = data.semester
  overview.overallAchievement = data.overallAchievement
  overview.isAllAchieved = data.isAllAchieved
  overview.objectives = data.objectives
  overview.compareCourses = data.compareCourses
  overview.radarData = data.radarData
  overview.suggestionSummary = data.suggestionSummary

  if (!overview.objectives.some((item) => Number(item.objectiveId) === Number(filters.objectiveId))) {
    filters.objectiveId = ''
  }

  await loadTrend()
}

async function loadTrend() {
  trend.value = await getTrendData({
    courseId: filters.courseId,
    objectiveId: filters.objectiveId
  })
}

onMounted(loadOverview)
</script>

<style scoped>
.source-segment {
  padding: 14px 16px;
  border-radius: 16px;
  background: #fbfdfe;
  border: 1px solid #e6eef2;
  line-height: 1.8;
}
</style>
