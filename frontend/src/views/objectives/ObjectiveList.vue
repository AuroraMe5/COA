<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="教学目标管理"
      description="将教学目标录入、目标分解与权重配置、智能解析导入整合到同一个模块中，便于教师按照手工维护与智能辅助两条路径协同完成目标建设。"
      :tabs="objectiveManageTabs"
    >
      <template #actions>
        <button class="btn btn-light" @click="router.push('/objectives/parse-import')">智能解析导入</button>
        <button class="btn btn-primary" @click="createObjective">新增教学目标</button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadObjectives">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadObjectives">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="toolbar-spacer"></div>
      <div class="chip-row">
        <div class="chip">
          <span class="chip-dot"></span>
          <span>目标数量 {{ objectives.length }}</span>
        </div>
        <div class="chip">
          <span class="chip-dot"></span>
          <span>目标权重合计 {{ totalWeight.toFixed(2) }}</span>
        </div>
      </div>
    </div>

    <PanelCard
      title="教学目标列表"
      subtitle="从这里进入新增、编辑与智能解析流程。目标分解与权重作为同一模块下的子页签维护。"
    >
      <EmptyState
        v-if="!objectives.length"
        mark="目"
        title="当前课程暂无教学目标"
        description="你可以直接新增目标，或跳转到智能解析导入页，从课程大纲中批量生成目标草稿。"
      />
      <div v-else class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th>目标内容</th>
              <th>类型</th>
              <th>权重</th>
              <th>分解点数量</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in objectives" :key="item.id">
              <td>{{ item.objCode }}</td>
              <td>{{ item.objContent }}</td>
              <td>
                <StatusBadge :text="item.objTypeName" :tone="typeTone(item.objType)" />
              </td>
              <td>{{ item.weight }}%</td>
              <td>{{ item.decomposeCount }}</td>
              <td>
                <div class="actions-inline">
                  <button class="btn btn-light" @click="router.push(`/objectives/edit/${item.id}`)">编辑</button>
                </div>
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
import { useRouter } from 'vue-router'
import { getObjectives } from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { objectiveManageTabs } from '@/constants/moduleTabs'

const router = useRouter()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const objectives = ref([])

const totalWeight = computed(() =>
  objectives.value.reduce((sum, item) => sum + Number(item.weight || 0), 0)
)

function typeTone(type) {
  if (Number(type) === 1) return 'primary'
  if (Number(type) === 2) return 'success'
  return 'warning'
}

function createObjective() {
  router.push({
    path: '/objectives/edit',
    query: {
      courseId: filters.courseId,
      semester: filters.semester
    }
  })
}

async function loadObjectives() {
  const data = await getObjectives({
    courseId: filters.courseId,
    semester: filters.semester
  })

  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  objectives.value = data.items
}

onMounted(loadObjectives)
</script>
