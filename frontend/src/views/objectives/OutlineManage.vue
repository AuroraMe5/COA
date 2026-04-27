<template>
  <div class="app-page page-stack">
    <ModuleHeader title="课程管理">
      <template #actions>
        <button class="btn btn-light" @click="goTo('/objectives/parse-import')">智能解析导入</button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadCourseDetail">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadCourseDetail">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
      <div class="toolbar-spacer"></div>
      <div class="actions-inline">
        <button class="btn btn-light" @click="loadCourseDetail">刷新</button>
      </div>
    </div>

    <div
      v-if="message.text"
      class="toast-notice"
      :class="message.type === 'error' ? 'toast-error' : 'toast-success'"
      :role="message.type === 'error' ? 'alert' : 'status'"
    >
      <div>
        <strong>{{ message.type === 'error' ? '操作未完成' : '操作已完成' }}</strong>
        <p>{{ message.text }}</p>
      </div>
      <button class="toast-close" type="button" aria-label="关闭提醒" @click="clearMessage">×</button>
    </div>

    <div class="summary-grid">
      <div class="summary-card">
        <span>课程目标</span>
        <strong>{{ detail.summary.objectiveCount || 0 }}</strong>
        <small>权重合计 {{ formatNumber(detail.summary.objectiveWeight) }}</small>
      </div>
      <div class="summary-card">
        <span>考核项</span>
        <strong>{{ detail.summary.assessItemCount || 0 }}</strong>
        <small>权重合计 {{ formatNumber(detail.summary.assessWeight) }}</small>
      </div>
      <div class="summary-card">
        <span>目标考核映射</span>
        <strong>{{ detail.summary.mappingRowCount || 0 }}</strong>
      </div>
      <div class="summary-card">
        <span>解析教学内容</span>
        <strong>{{ detail.summary.teachingContentCount || 0 }}</strong>
        <small>考核说明 {{ detail.summary.assessmentDetailCount || 0 }} 项</small>
      </div>
    </div>

    <div class="grid-2">
      <PanelCard title="课程基础信息">
        <div class="course-context">
          <div>
            <span>开课学期</span>
            <strong>{{ filters.semester || '—' }}</strong>
          </div>
          <div>
            <span>授课教师</span>
            <strong>{{ teacherText }}</strong>
          </div>
        </div>

        <div class="form-grid-2">
          <div class="form-field">
            <label>课程编号</label>
            <input v-model.trim="courseForm.courseCode" class="text-input" />
          </div>
          <div class="form-field">
            <label>课程中文名称</label>
            <input v-model.trim="courseForm.courseNameZh" class="text-input" />
          </div>
          <div class="form-field">
            <label>课程英文名称</label>
            <input v-model.trim="courseForm.courseNameEn" class="text-input" />
          </div>
          <div class="form-field">
            <label>课程类型</label>
            <input v-model.trim="courseForm.courseType" class="text-input" />
          </div>
          <div class="form-field">
            <label>学时</label>
            <input v-model="courseForm.hours" class="text-input" type="number" min="0" step="1" />
          </div>
          <div class="form-field">
            <label>学分</label>
            <input v-model="courseForm.credits" class="text-input" type="number" min="0" step="0.5" />
          </div>
          <div class="form-field">
            <label>授课对象</label>
            <input v-model.trim="courseForm.targetStudents" class="text-input" />
          </div>
          <div class="form-field">
            <label>授课语言</label>
            <input v-model.trim="courseForm.teachingLanguage" class="text-input" />
          </div>
          <div class="form-field">
            <label>开课院系</label>
            <input v-model.trim="courseForm.collegeName" class="text-input" />
          </div>
          <div class="form-field">
            <label>课程负责人</label>
            <input v-model.trim="courseForm.courseOwner" class="text-input" />
          </div>
        </div>

        <div class="form-field mt-16">
          <label>先修课程</label>
          <input v-model.trim="courseForm.prerequisiteCourse" class="text-input" />
        </div>

        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="savingCourse" @click="saveCourseInfo">
            {{ savingCourse ? '保存中...' : '保存课程信息' }}
          </button>
        </div>
      </PanelCard>

      <PanelCard title="课程简介">
        <div class="form-field mt-0">
          <label>课程简介</label>
          <textarea v-model.trim="outlineForm.overview" class="text-area intro-area"></textarea>
        </div>

        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="savingOutline" @click="saveCourseIntro">
            {{ savingOutline ? '保存中...' : '保存课程简介' }}
          </button>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="课程目标与毕业要求">
      <template #actions>
        <button class="btn btn-light" @click="openObjectiveDialog('list')">目标列表</button>
        <button class="btn btn-light" @click="openObjectiveDialog('weights')">目标分解与权重</button>
      </template>

      <div v-if="detail.objectives.length" class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th>目标内容</th>
              <th>毕业要求</th>
              <th>关联程度</th>
              <th>权重</th>
              <th>分解点</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="objective in detail.objectives" :key="objective.id">
              <td class="nowrap">{{ objective.objCode }}</td>
              <td>{{ objective.objContent }}</td>
              <td>
                <strong>{{ objective.gradReqId || '未配置' }}</strong>
                <div v-if="objective.gradReqDesc" class="cell-note">{{ objective.gradReqDesc }}</div>
              </td>
              <td>{{ relationText(objective.relationLevel) }}</td>
              <td>{{ formatNumber(objective.weight) }}</td>
              <td>{{ objective.decomposeCount || 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState
        v-else
        mark="目标"
        title="当前课程暂无课程目标"
      />
    </PanelCard>

    <PanelCard title="考核组成与目标映射">
      <template #actions>
        <button class="btn btn-light" @click="openAssessDialog">考核项修改</button>
        <button class="btn btn-light" @click="openObjectiveDialog('mapping')">目标考核映射</button>
      </template>

      <div class="grid-2">
        <div>
          <h3 class="section-subtitle">考核项</h3>
          <div v-if="detail.assessItems.length" class="table-shell compact-shell">
            <table class="data-table compact-table">
              <thead>
                <tr>
                  <th>考核项</th>
                  <th>类型</th>
                  <th>权重</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in detail.assessItems" :key="item.id">
                  <td>{{ item.itemName }}</td>
                  <td>{{ itemTypeText(item.itemType) }}</td>
                  <td>{{ formatNumber(item.weight) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="notice warning">当前学期暂无考核项配置。</div>
        </div>

        <div>
          <h3 class="section-subtitle">映射矩阵</h3>
          <div v-if="detail.mappingRows.length && detail.assessItems.length" class="table-shell compact-shell">
            <table class="data-table compact-table mapping-summary-table">
              <thead>
                <tr>
                  <th>目标</th>
                  <th v-for="item in detail.assessItems" :key="item.id">{{ item.itemName }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in detail.mappingRows" :key="row.objectiveId">
                  <td class="nowrap">{{ objectiveLabel(row.objectiveId) }}</td>
                  <td v-for="item in detail.assessItems" :key="item.id">
                    {{ formatNumber(row.values?.[String(item.id)]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="notice warning">当前学期暂无目标考核映射。</div>
        </div>
      </div>
    </PanelCard>

    <PanelCard title="教学内容表">
      <template #actions>
        <button class="btn btn-primary" @click="openTeachingDialog">维护教学内容表</button>
      </template>

      <div v-if="latestTeachingContents.length" class="table-shell">
        <table class="data-table compact-table teaching-preview-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>教学内容</th>
              <th>讲授学时</th>
              <th>实践学时</th>
              <th>教学方式</th>
              <th>涉及目标</th>
              <th>基本要求</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in previewTeachingContents" :key="item.id || `${index}-${item.title || 'row'}`">
              <td class="nowrap">{{ (currentPreviewTeachingPage - 1) * teachingPreviewPageSize + index + 1 }}</td>
              <td>{{ item.title || '—' }}</td>
              <td>{{ item.lectureHours || 0 }}</td>
              <td>{{ item.practiceHours || 0 }}</td>
              <td>{{ item.teachingMethod || '—' }}</td>
              <td>{{ item.relatedObjectives || '—' }}</td>
              <td>
                <div class="preview-requirements">{{ item.requirements || '—' }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="latestTeachingContents.length" class="table-pager">
        <span class="muted">
          第 {{ currentPreviewTeachingPage }} / {{ previewTeachingPageCount }} 页，共 {{ latestTeachingContents.length }} 条
        </span>
        <div class="actions-inline">
          <button class="btn btn-light btn-mini" :disabled="currentPreviewTeachingPage <= 1" @click="changePreviewTeachingPage(currentPreviewTeachingPage - 1)">上一页</button>
          <button class="btn btn-light btn-mini" :disabled="currentPreviewTeachingPage >= previewTeachingPageCount" @click="changePreviewTeachingPage(currentPreviewTeachingPage + 1)">下一页</button>
        </div>
      </div>
      <EmptyState
        v-if="!latestTeachingContents.length"
        mark="内容"
        title="暂无教学内容"
      />
    </PanelCard>

    <div v-if="objectiveDialog.open" class="modal-backdrop" @click.self="closeObjectiveDialog">
      <section class="modal-panel" role="dialog" aria-modal="true" :aria-label="objectiveDialogTitle">
        <header class="modal-head">
          <div>
            <h2>{{ objectiveDialogTitle }}</h2>
            <p>{{ selectedCourseText }} / {{ filters.semester }}</p>
          </div>
          <button class="btn btn-light btn-mini" @click="closeObjectiveDialog">关闭</button>
        </header>
        <div class="modal-body">
          <ObjectiveList
            v-if="objectiveDialog.type === 'list'"
            :key="dialogComponentKey"
            embedded
            :initial-course-id="filters.courseId"
            :initial-semester="filters.semester"
          />
          <ObjectiveWeights
            v-if="objectiveDialog.type === 'weights'"
            :key="dialogComponentKey"
            embedded
            :initial-course-id="filters.courseId"
            :initial-semester="filters.semester"
          />
          <ObjectiveMapping
            v-if="objectiveDialog.type === 'mapping'"
            :key="dialogComponentKey"
            embedded
            :initial-course-id="filters.courseId"
            :initial-semester="filters.semester"
          />
        </div>
      </section>
    </div>

    <div v-if="assessDialog.open" class="modal-backdrop" @click.self="closeAssessDialog">
      <section class="modal-panel assess-modal-panel" role="dialog" aria-modal="true" aria-label="考核项修改">
        <header class="modal-head">
          <div>
            <h2>考核项修改</h2>
            <p>{{ selectedCourseText }} / {{ filters.semester }}</p>
          </div>
          <button class="btn btn-light btn-mini" @click="closeAssessDialog">关闭</button>
        </header>
        <div class="modal-body assess-modal-body">
          <div class="assess-summary">
            <div>
              <span>考核项</span>
              <strong>{{ assessItemDrafts.length }}</strong>
            </div>
            <div>
              <span>权重合计</span>
              <strong>{{ formatNumber(assessWeightTotal) }}</strong>
            </div>
            <div>
              <span>满分</span>
              <strong>{{ formatNumber(assessMaxScoreTotal) }}</strong>
            </div>
          </div>

          <PanelCard title="考核项维护">
            <template #actions>
              <button class="btn btn-light" @click="addAssessItemRow">新增考核项</button>
            </template>
            <div class="table-shell assess-edit-shell">
              <table class="data-table compact-table assess-edit-table">
                <thead>
                  <tr>
                    <th>考核项</th>
                    <th>类型</th>
                    <th>权重</th>
                    <th>满分</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(item, index) in assessItemDrafts" :key="item.__rowKey">
                    <td>
                      <input v-model.trim="item.itemName" class="text-input assess-name-input" />
                    </td>
                    <td>
                      <select v-model="item.itemType" class="select-input assess-type-select">
                        <option value="normal">平时</option>
                        <option value="mid">期中</option>
                        <option value="final">期末</option>
                        <option value="practice">实践</option>
                        <option value="report">报告</option>
                      </select>
                    </td>
                    <td>
                      <input v-model="item.weight" class="text-input assess-number-input" type="number" min="0" max="100" step="0.01" />
                    </td>
                    <td>
                      <input v-model="item.maxScore" class="text-input assess-number-input" type="number" min="0" step="0.5" />
                    </td>
                    <td>
                      <button class="btn btn-danger btn-mini" @click="removeAssessItemRow(index)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </PanelCard>

          <div class="modal-footer-actions">
            <span class="muted">考核项权重合计应为 100。</span>
            <button class="btn btn-primary" :disabled="savingAssessItems" @click="saveAssessItems">
              {{ savingAssessItems ? '保存中...' : '保存考核项' }}
            </button>
          </div>
        </div>
      </section>
    </div>

    <div v-if="teachingDialog.open" class="modal-backdrop" @click.self="closeTeachingDialog">
      <section class="modal-panel teaching-modal-panel" role="dialog" aria-modal="true" aria-label="教学内容表">
        <header class="modal-head">
          <div>
            <h2>教学内容表</h2>
            <p>{{ selectedCourseText }} / {{ filters.semester }}</p>
          </div>
          <button class="btn btn-light btn-mini" @click="closeTeachingDialog">关闭</button>
        </header>
        <div class="modal-body teaching-modal-body">
          <div class="filter-bar modal-filter-bar">
            <div class="filter-field">
              <label>教学方式</label>
              <select v-model="teachingMethodFilter" class="select-input" @change="resetTeachingScroll">
                <option value="">全部方式</option>
                <option v-for="method in teachingMethodOptions" :key="method" :value="method">
                  {{ method }}
                </option>
              </select>
            </div>
            <div class="toolbar-spacer"></div>
            <div class="actions-inline">
              <button class="btn btn-light" @click="addTeachingContentRow">新增教学内容</button>
            </div>
          </div>

          <div class="teaching-summary">
            <div>
              <span>教学内容</span>
              <strong>{{ filteredTeachingContents.length }}</strong>
            </div>
            <div>
              <span>已显示</span>
              <strong>{{ visibleTeachingContents.length }}</strong>
            </div>
            <div>
              <span>基本要求</span>
              <strong>{{ filteredTeachingRequirementCount }}</strong>
            </div>
          </div>

          <PanelCard class="teaching-edit-panel" title="教学内容维护">
            <div class="teaching-scroll" @scroll="handleTeachingScroll">
              <div v-if="visibleTeachingContents.length" class="table-shell teaching-edit-shell">
                <table class="data-table compact-table teaching-modal-table">
                  <thead>
                    <tr>
                      <th>序号</th>
                      <th>教学内容</th>
                      <th>讲授学时</th>
                      <th>实践学时</th>
                      <th>教学方式</th>
                      <th>涉及目标</th>
                      <th>基本要求</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in visibleTeachingContents" :key="item.__rowKey">
                      <td class="nowrap">{{ item.__displayIndex }}</td>
                      <td>
                        <textarea v-model.trim="item.title" class="text-area title-area"></textarea>
                      </td>
                      <td>
                        <input v-model="item.lectureHours" class="text-input hours-input" type="number" min="0" step="0.5" />
                      </td>
                      <td>
                        <input v-model="item.practiceHours" class="text-input hours-input" type="number" min="0" step="0.5" />
                      </td>
                      <td>
                        <select v-model="item.teachingMethod" class="select-input method-select">
                          <option value="">未设置</option>
                          <option v-for="method in teachingMethodOptions" :key="method" :value="method">
                            {{ method }}
                          </option>
                        </select>
                      </td>
                      <td>
                        <input v-model.trim="item.relatedObjectives" class="text-input objective-input" placeholder="如：课程目标1、2" />
                      </td>
                      <td>
                        <textarea v-model.trim="item.requirements" class="text-area requirements-edit-area"></textarea>
                      </td>
                      <td>
                        <button class="btn btn-danger btn-mini" @click="removeTeachingContentRow(item.__sourceIndex)">删除</button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <EmptyState
                v-else
                mark="内容"
                title="暂无匹配的教学内容"
              />
            </div>
          </PanelCard>

          <div class="modal-footer-actions">
            <span class="muted">
              已显示 {{ visibleTeachingContents.length }} / {{ filteredTeachingContents.length }} 条
            </span>
            <button class="btn btn-primary" :disabled="savingTeachingContents" @click="saveTeachingContents">
              {{ savingTeachingContents ? '保存中...' : '保存教学内容表' }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getCourseDetail,
  getReferenceCatalogs,
  saveOutline,
  updateCourse,
  updateCourseAssessItems,
  updateCourseTeachingContents
} from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import ObjectiveList from '@/views/objectives/ObjectiveList.vue'
import ObjectiveMapping from '@/views/objectives/ObjectiveMapping.vue'
import ObjectiveWeights from '@/views/objectives/ObjectiveWeights.vue'

const route = useRoute()
const router = useRouter()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const filters = reactive({
  courseId: '',
  semester: ''
})

const detail = reactive({
  course: {},
  outline: {},
  objectives: [],
  assessItems: [],
  mappingRows: [],
  teacherNames: [],
  latestParsedCourse: {},
  summary: {}
})

const courseForm = reactive(blankCourse())
const outlineForm = reactive(blankOutline())
const savingCourse = ref(false)
const savingOutline = ref(false)
const savingTeachingContents = ref(false)
const savingAssessItems = ref(false)
const teachingVisibleLimit = ref(10)
const teachingMethodFilter = ref('')
const previewTeachingPage = ref(1)
const assessItemDrafts = ref([])
const message = reactive({
  type: 'success',
  text: ''
})
let messageTimer = null
const objectiveDialog = reactive({
  open: false,
  type: 'list'
})
const teachingDialog = reactive({
  open: false
})
const assessDialog = reactive({
  open: false
})

const defaultTeachingMethods = ['讲授', '上机', '实验', '实践', '讨论', '案例教学', '项目教学', '线上', '线下', '混合式']
const teachingPreviewPageSize = 10

const latestTeachingContents = computed(() => arrayValue(detail.latestParsedCourse.teachingContents))
const previewTeachingPageCount = computed(() =>
  Math.max(1, Math.ceil(latestTeachingContents.value.length / teachingPreviewPageSize))
)
const currentPreviewTeachingPage = computed(() =>
  Math.min(previewTeachingPage.value, previewTeachingPageCount.value)
)
const previewTeachingContents = computed(() => {
  const start = (currentPreviewTeachingPage.value - 1) * teachingPreviewPageSize
  return latestTeachingContents.value.slice(start, start + teachingPreviewPageSize)
})
const teachingMethodOptions = computed(() => {
  const methods = new Set(defaultTeachingMethods)
  latestTeachingContents.value.forEach((item) => {
    const method = String(item.teachingMethod || '').trim()
    if (method) methods.add(method)
  })
  return [...methods]
})
const filteredTeachingContents = computed(() =>
  latestTeachingContents.value
    .map((item, index) => {
      item.__sourceIndex = index
      item.__displayIndex = index + 1
      item.__rowKey = item.id || `${index}-${item.title || 'row'}`
      return item
    })
    .filter((item) => !teachingMethodFilter.value || item.teachingMethod === teachingMethodFilter.value)
)
const visibleTeachingContents = computed(() =>
  filteredTeachingContents.value.slice(0, teachingVisibleLimit.value)
)
const filteredTeachingRequirementCount = computed(() =>
  filteredTeachingContents.value.filter((item) => String(item.requirements || '').trim()).length
)
const hasMoreTeachingContents = computed(() =>
  visibleTeachingContents.value.length < filteredTeachingContents.value.length
)
const assessWeightTotal = computed(() =>
  assessItemDrafts.value.reduce((sum, item) => sum + Number(item.weight || 0), 0)
)
const assessMaxScoreTotal = computed(() =>
  assessItemDrafts.value.reduce((sum, item) => sum + Number(item.maxScore || 0), 0)
)
const teacherText = computed(() => detail.teacherNames.length ? detail.teacherNames.join('、') : '未配置')
const objectiveMap = computed(() => new Map(detail.objectives.map((item) => [Number(item.id), item])))
const selectedCourseText = computed(() => {
  const course = catalogs.courses.find((item) => String(item.id) === String(filters.courseId))
  return course ? `${course.name}（${course.code}）` : '当前课程'
})
const objectiveDialogTitle = computed(() => {
  const map = {
    list: '目标列表',
    weights: '目标分解与权重',
    mapping: '目标考核映射'
  }
  return map[objectiveDialog.type] || '目标配置'
})
const dialogComponentKey = computed(() => `${objectiveDialog.type}-${filters.courseId}-${filters.semester}`)

function blankCourse() {
  return {
    id: null,
    courseCode: '',
    courseNameZh: '',
    courseNameEn: '',
    courseType: '',
    targetStudents: '',
    teachingLanguage: '',
    collegeName: '',
    hours: '',
    credits: '',
    prerequisiteCourse: '',
    courseOwner: ''
  }
}

function blankOutline() {
  return {
    id: null,
    courseId: '',
    semester: '',
    version: 'V1.0',
    status: 'DRAFT',
    overview: '',
    targetSource: '',
    objectiveCount: 0,
    assessItemCount: 0
  }
}

function arrayValue(value) {
  return Array.isArray(value) ? value : []
}

function ensureTeachingContentsShape() {
  if (!detail.latestParsedCourse || typeof detail.latestParsedCourse !== 'object') {
    detail.latestParsedCourse = {}
  }
  if (!Array.isArray(detail.latestParsedCourse.teachingContents)) {
    detail.latestParsedCourse.teachingContents = []
  }
  return detail.latestParsedCourse.teachingContents
}

function changePreviewTeachingPage(page) {
  const numericPage = Number(page)
  const safePage = Number.isFinite(numericPage) ? numericPage : 1
  previewTeachingPage.value = Math.min(Math.max(1, safePage), previewTeachingPageCount.value)
}

function setMessage(type, text) {
  clearMessageTimer()
  message.type = type
  message.text = text
  if (text) {
    messageTimer = window.setTimeout(() => {
      clearMessage()
    }, 3000)
  }
}

function clearMessage() {
  clearMessageTimer()
  message.text = ''
}

function clearMessageTimer() {
  if (messageTimer) {
    window.clearTimeout(messageTimer)
    messageTimer = null
  }
}

function applyCourse(payload = {}) {
  Object.assign(courseForm, blankCourse(), {
    id: payload.id || null,
    courseCode: payload.courseCode || '',
    courseNameZh: payload.courseNameZh || payload.courseName || '',
    courseNameEn: payload.courseNameEn || '',
    courseType: payload.courseType || '',
    targetStudents: payload.targetStudents || '',
    teachingLanguage: payload.teachingLanguage || '',
    collegeName: payload.collegeName || '',
    hours: payload.hours ?? '',
    credits: payload.credits ?? '',
    prerequisiteCourse: payload.prerequisiteCourse || '',
    courseOwner: payload.courseOwner || ''
  })
}

function applyOutline(payload = {}) {
  Object.assign(outlineForm, blankOutline(), {
    id: payload.id || null,
    courseId: payload.courseId || filters.courseId,
    semester: payload.semester || filters.semester,
    version: payload.version || 'V1.0',
    status: payload.status || 'DRAFT',
    overview: payload.overview || '',
    targetSource: payload.targetSource || '',
    objectiveCount: payload.objectiveCount || 0,
    assessItemCount: payload.assessItemCount || 0
  })
}

function applyDetail(payload) {
  catalogs.courses = payload.courses || catalogs.courses
  catalogs.semesters = payload.semesters || catalogs.semesters
  filters.courseId = payload.currentCourseId || filters.courseId
  filters.semester = payload.currentSemester || filters.semester
  detail.course = payload.course || {}
  detail.outline = payload.outline || {}
  detail.objectives = payload.objectives || []
  detail.assessItems = payload.assessItems || []
  detail.mappingRows = payload.mappingRows || []
  detail.teacherNames = payload.teacherNames || []
  detail.latestParsedCourse = payload.latestParsedCourse || {}
  ensureTeachingContentsShape()
  resetTeachingScroll()
  changePreviewTeachingPage(1)
  detail.summary = payload.summary || {}
  applyCourse(detail.course)
  applyOutline(detail.outline)
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses || []
  catalogs.semesters = data.semesters || []
  filters.courseId = route.query.courseId || catalogs.courses[0]?.id || ''
  filters.semester = route.query.semester || catalogs.semesters[0] || ''
  if (filters.courseId) {
    await loadCourseDetail()
  }
}

async function loadCourseDetail() {
  if (!filters.courseId) return
  try {
    const data = await getCourseDetail(filters.courseId, { semester: filters.semester })
    applyDetail(data)
  } catch (error) {
    setMessage('error', error.message || '课程详情加载失败。')
  }
}

function numberOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

async function saveCourseInfo() {
  if (!courseForm.courseCode || !courseForm.courseNameZh) {
    setMessage('error', '课程编号和课程名称不能为空。')
    return
  }

  savingCourse.value = true
  try {
    const data = await updateCourse(courseForm.id, {
      ...courseForm,
      semester: filters.semester,
      hours: numberOrNull(courseForm.hours),
      credits: numberOrNull(courseForm.credits)
    })
    applyDetail(data)
    setMessage('success', '课程基础信息已保存。')
  } catch (error) {
    setMessage('error', error.message || '课程基础信息保存失败。')
  } finally {
    savingCourse.value = false
  }
}

async function saveCourseIntro() {
  savingOutline.value = true
  try {
    const saved = await saveOutline({
      ...outlineForm,
      courseId: Number(filters.courseId),
      semester: filters.semester,
      status: outlineForm.status || 'DRAFT',
      version: outlineForm.version || 'V1.0',
      targetSource: outlineForm.targetSource || ''
    })
    outlineForm.id = saved.id
    setMessage('success', '课程简介已保存。')
    await loadCourseDetail()
    return saved
  } catch (error) {
    setMessage('error', error.message || '课程简介保存失败。')
    return null
  } finally {
    savingOutline.value = false
  }
}

function emptyTeachingContent() {
  return {
    title: '',
    lectureHours: '',
    practiceHours: '',
    teachingMethod: '',
    relatedObjectives: '',
    requirements: ''
  }
}

function cloneAssessItems() {
  assessItemDrafts.value = detail.assessItems.map((item, index) => ({
    id: item.id || null,
    itemName: item.itemName || '',
    itemType: item.itemType || 'normal',
    weight: item.weight ?? '',
    maxScore: item.maxScore ?? 100,
    __rowKey: item.id || `new-${Date.now()}-${index}`
  }))
}

function emptyAssessItem() {
  return {
    id: null,
    itemName: '',
    itemType: 'normal',
    weight: '',
    maxScore: 100,
    __rowKey: `new-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }
}

function addAssessItemRow() {
  assessItemDrafts.value.push(emptyAssessItem())
}

function removeAssessItemRow(index) {
  assessItemDrafts.value.splice(index, 1)
}

function validateAssessItemDrafts() {
  if (!assessItemDrafts.value.length) {
    setMessage('error', '至少需要保留 1 个考核项。')
    return false
  }
  if (assessItemDrafts.value.some((item) => !String(item.itemName || '').trim())) {
    setMessage('error', '考核项名称不能为空。')
    return false
  }
  if (Math.abs(assessWeightTotal.value - 100) > 0.01) {
    setMessage('error', `考核项权重合计必须等于 100，当前为 ${formatNumber(assessWeightTotal.value)}。`)
    return false
  }
  return true
}

async function saveAssessItems() {
  if (!validateAssessItemDrafts()) return

  savingAssessItems.value = true
  try {
    const payload = {
      assessItems: assessItemDrafts.value.map((item) => ({
        id: item.id || null,
        itemName: item.itemName || '',
        itemType: item.itemType || 'normal',
        weight: numberOrNull(item.weight) ?? 0,
        maxScore: numberOrNull(item.maxScore) ?? 100
      }))
    }
    const result = await updateCourseAssessItems(filters.courseId, filters.semester, payload)
    applyDetail(result)
    cloneAssessItems()
    setMessage('success', '考核项已保存。')
  } catch (error) {
    setMessage('error', error.message || '考核项保存失败。')
  } finally {
    savingAssessItems.value = false
  }
}

function addTeachingContentRow() {
  const rows = ensureTeachingContentsShape()
  const row = emptyTeachingContent()
  if (teachingMethodFilter.value) {
    row.teachingMethod = teachingMethodFilter.value
  }
  rows.push(row)
  teachingVisibleLimit.value = Math.max(teachingVisibleLimit.value, filteredTeachingContents.value.length + 1)
}

function removeTeachingContentRow(index) {
  latestTeachingContents.value.splice(index, 1)
  if (teachingVisibleLimit.value > filteredTeachingContents.value.length) {
    teachingVisibleLimit.value = Math.max(10, filteredTeachingContents.value.length)
  }
}

function resetTeachingScroll() {
  teachingVisibleLimit.value = 10
}

function handleTeachingScroll(event) {
  const target = event.target
  if (!target || !hasMoreTeachingContents.value) return
  if (target.scrollTop + target.clientHeight >= target.scrollHeight - 120) {
    teachingVisibleLimit.value += 10
  }
}

async function saveTeachingContents() {
  savingTeachingContents.value = true
  try {
    const payload = {
      teachingContents: latestTeachingContents.value.map((item) => ({
        title: item.title || '',
        lectureHours: item.lectureHours ?? '',
        practiceHours: item.practiceHours ?? '',
        teachingMethod: item.teachingMethod || '',
        relatedObjectives: item.relatedObjectives || '',
        requirements: item.requirements || ''
      }))
    }
    const result = await updateCourseTeachingContents(filters.courseId, filters.semester, payload)
    applyDetail(result)
    setMessage('success', '教学内容表已保存。')
  } catch (error) {
    setMessage('error', error.message || '教学内容表保存失败。')
  } finally {
    savingTeachingContents.value = false
  }
}

function goTo(path) {
  router.push({
    path,
    query: {
      courseId: filters.courseId,
      semester: filters.semester
    }
  })
}

function openObjectiveDialog(type) {
  objectiveDialog.type = type
  objectiveDialog.open = true
}

async function closeObjectiveDialog() {
  objectiveDialog.open = false
  await loadCourseDetail()
}

function openAssessDialog() {
  cloneAssessItems()
  assessDialog.open = true
}

async function closeAssessDialog() {
  assessDialog.open = false
  await loadCourseDetail()
}

function openTeachingDialog() {
  teachingDialog.open = true
  resetTeachingScroll()
}

async function closeTeachingDialog() {
  teachingDialog.open = false
  await loadCourseDetail()
}

function formatNumber(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '0'
  return number.toFixed(Math.abs(number - Math.round(number)) < 0.001 ? 0 : 2)
}

function relationText(level) {
  if (level === 'H') return '高'
  if (level === 'M') return '中'
  if (level === 'L') return '低'
  return level || '未配置'
}

function itemTypeText(type) {
  const map = {
    normal: '平时',
    mid: '期中',
    final: '期末',
    practice: '实践',
    report: '报告'
  }
  return map[type] || '其他'
}

function objectiveLabel(objectiveId) {
  const objective = objectiveMap.value.get(Number(objectiveId))
  return objective?.objCode || `目标${objectiveId}`
}

onMounted(loadCatalogs)
onBeforeUnmount(clearMessageTimer)
</script>

<style scoped>
.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.toast-notice {
  position: fixed;
  top: 18px;
  right: 22px;
  z-index: 160;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  width: min(420px, calc(100vw - 32px));
  padding: 14px 16px;
  border: 1px solid rgba(56, 161, 105, 0.28);
  border-left: 4px solid var(--color-success, #38a169);
  border-radius: var(--radius-md);
  background: #f3fff8;
  color: var(--color-text);
  box-shadow: 0 18px 45px rgba(15, 31, 45, 0.18);
}

.toast-error {
  border-color: rgba(229, 62, 62, 0.3);
  border-left-color: var(--color-danger, #e53e3e);
  background: #fff7f7;
}

.toast-notice strong {
  display: block;
  font-size: 15px;
}

.toast-notice p {
  margin: 5px 0 0;
  color: var(--color-text-soft);
  line-height: 1.55;
}

.toast-close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--color-text-soft);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
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

.summary-card span,
.summary-card small {
  color: var(--color-text-soft);
}

.summary-card strong {
  font-size: 26px;
  color: var(--color-primary-deep);
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.intro-area {
  min-height: 380px;
  line-height: 1.75;
  padding: 14px 16px;
}

.course-context {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.course-context > div {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fbfdfe;
}

.course-context span {
  font-size: 12px;
  color: var(--color-text-soft);
}

.course-context strong {
  color: var(--color-primary-deep);
  line-height: 1.5;
}

.nowrap {
  white-space: nowrap;
}

.cell-note {
  margin-top: 4px;
  color: var(--color-text-soft);
  font-size: 12px;
  line-height: 1.5;
}

.section-subtitle {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--color-primary-deep);
}

.compact-shell .data-table {
  min-width: 520px;
}

.compact-table th,
.compact-table td {
  padding: 10px;
}

.mapping-summary-table {
  min-width: 640px;
}

.teaching-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.assess-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.teaching-summary > div {
  display: grid;
  gap: 7px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fbfdfe;
}

.assess-summary > div {
  display: grid;
  gap: 7px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fbfdfe;
}

.teaching-summary span,
.teaching-summary small {
  color: var(--color-text-soft);
}

.assess-summary span {
  color: var(--color-text-soft);
}

.teaching-summary strong {
  font-size: 24px;
  color: var(--color-primary-deep);
}

.assess-summary strong {
  font-size: 24px;
  color: var(--color-primary-deep);
}

.teaching-preview-table {
  min-width: 1080px;
}

.preview-requirements {
  min-width: 280px;
  max-height: 96px;
  overflow: auto;
  color: var(--color-text-soft);
  line-height: 1.7;
  white-space: pre-wrap;
}

.table-pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}

.btn-mini {
  min-height: 30px;
  padding: 5px 10px;
  font-size: 12px;
}

.teaching-modal-panel {
  width: min(1420px, 97vw);
}

.teaching-modal-body {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 16px;
}

.assess-modal-panel {
  width: min(960px, 96vw);
}

.assess-modal-body {
  display: grid;
  gap: 16px;
}

.assess-edit-table {
  min-width: 760px;
}

.assess-name-input {
  min-width: 220px;
}

.assess-type-select {
  min-width: 128px;
}

.assess-number-input {
  min-width: 96px;
}

.modal-filter-bar {
  margin: 0;
  box-shadow: none;
}

.teaching-edit-panel {
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
}

.teaching-edit-panel :deep(.panel-body) {
  min-height: 0;
  overflow: hidden;
}

.teaching-scroll {
  min-height: 0;
  max-height: calc(92vh - 390px);
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
}

.teaching-edit-shell {
  border: 0;
  border-radius: 0;
}

.teaching-modal-table {
  min-width: 1320px;
}

.title-area {
  min-width: 260px;
  min-height: 96px;
  line-height: 1.65;
}

.method-select {
  min-width: 132px;
}

.hours-input {
  min-width: 86px;
}

.objective-input {
  min-width: 170px;
}

.requirements-edit-area {
  min-width: 400px;
  min-height: 160px;
  line-height: 1.75;
  padding: 12px 14px;
  font-size: 14px;
}

.modal-footer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding-top: 2px;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 31, 45, 0.42);
}

.modal-panel {
  width: min(1280px, 96vw);
  max-height: 92vh;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  border-radius: var(--radius-lg);
  background: var(--bg-app);
  border: 1px solid var(--color-border);
  box-shadow: 0 24px 80px rgba(15, 31, 45, 0.26);
  overflow: hidden;
}

.modal-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  padding: 16px 18px;
  border-bottom: 1px solid var(--color-border);
  background: var(--bg-panel);
}

.modal-head h2 {
  margin: 0;
  color: var(--color-primary-deep);
  font-size: 18px;
}

.modal-head p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  font-size: 13px;
}

.modal-body {
  min-height: 0;
  overflow: auto;
  padding: 18px;
}

@media (max-width: 1180px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 980px) {
  .summary-grid,
  .assess-summary,
  .teaching-summary,
  .course-context,
  .form-grid-2 {
    grid-template-columns: 1fr;
  }

  .table-pager,
  .modal-footer-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .modal-backdrop {
    padding: 12px;
  }

  .modal-panel {
    width: 100%;
    max-height: 94vh;
  }
}
</style>
