<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="基础信息管理"
      description="维护学院、专业和学期等基础目录数据。"
    />

    <div class="tab-strip" role="tablist" aria-label="基础信息类型">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-button"
        :class="{ active: activeTab === tab.key }"
        type="button"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="summary-grid">
      <div class="summary-card">
        <span>{{ currentTabLabel }}总数</span>
        <strong>{{ summary.total }}</strong>
      </div>
      <div class="summary-card">
        <span>启用中</span>
        <strong>{{ summary.enabled }}</strong>
      </div>
      <div class="summary-card">
        <span>已停用</span>
        <strong>{{ summary.disabled }}</strong>
      </div>
    </div>

    <div class="grid-2 admin-catalog-top">
      <PanelCard :title="formTitle">
        <div v-if="activeTab === 'colleges'" class="form-grid-2">
          <div class="form-field">
            <label>学院编码</label>
            <input v-model.trim="collegeForm.code" class="text-input" autocomplete="off" />
          </div>
          <div class="form-field">
            <label>学院名称</label>
            <input v-model.trim="collegeForm.name" class="text-input" />
          </div>
          <div class="form-field">
            <label>状态</label>
            <select v-model.number="collegeForm.status" class="select-input">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </div>
        </div>

        <div v-else-if="activeTab === 'majors'" class="form-grid-2">
          <div class="form-field">
            <label>所属学院</label>
            <select v-model="majorForm.collegeId" class="select-input">
              <option value="">请选择学院</option>
              <option v-for="college in colleges" :key="college.id" :value="college.id">
                {{ college.name }}（{{ college.code }}）
              </option>
            </select>
          </div>
          <div class="form-field">
            <label>专业编码</label>
            <input v-model.trim="majorForm.code" class="text-input" autocomplete="off" />
          </div>
          <div class="form-field">
            <label>专业名称</label>
            <input v-model.trim="majorForm.name" class="text-input" />
          </div>
          <div class="form-field">
            <label>状态</label>
            <select v-model.number="majorForm.status" class="select-input">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </div>
        </div>

        <div v-else class="form-grid-2">
          <div class="form-field">
            <label>学期编码</label>
            <input v-model.trim="semesterForm.code" class="text-input" placeholder="如：2025-2026-1" autocomplete="off" />
          </div>
          <div class="form-field">
            <label>学期名称</label>
            <input v-model.trim="semesterForm.name" class="text-input" placeholder="如：2025-2026学年第一学期" />
          </div>
          <div class="form-field">
            <label>学年</label>
            <input v-model.trim="semesterForm.schoolYear" class="text-input" placeholder="如：2025-2026" />
          </div>
          <div class="form-field">
            <label>学期序号</label>
            <select v-model.number="semesterForm.termNo" class="select-input">
              <option :value="1">第一学期</option>
              <option :value="2">第二学期</option>
            </select>
          </div>
          <div class="form-field">
            <label>开始日期</label>
            <input v-model="semesterForm.startDate" class="text-input" type="date" />
          </div>
          <div class="form-field">
            <label>结束日期</label>
            <input v-model="semesterForm.endDate" class="text-input" type="date" />
          </div>
          <div class="form-field">
            <label>状态</label>
            <select v-model.number="semesterForm.status" class="select-input">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </div>
        </div>

        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="saving" @click="submitCurrent">
            {{ saving ? '保存中...' : formSubmitText }}
          </button>
          <button class="btn btn-light" type="button" @click="resetCurrentForm">清空</button>
        </div>
      </PanelCard>

      <PanelCard title="筛选">
        <div class="filter-card-body">
          <div class="form-field">
            <label>关键字</label>
            <input
              v-model.trim="filters.keyword"
              class="text-input"
              :placeholder="keywordPlaceholder"
              @keyup.enter="loadItems"
            />
          </div>
          <div v-if="activeTab === 'majors'" class="form-field">
            <label>学院</label>
            <select v-model="filters.collegeId" class="select-input" @change="loadItems">
              <option value="">全部学院</option>
              <option v-for="college in colleges" :key="college.id" :value="college.id">
                {{ college.name }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label>状态</label>
            <select v-model="filters.status" class="select-input" @change="loadItems">
              <option value="all">全部</option>
              <option value="enabled">启用中</option>
              <option value="disabled">已停用</option>
            </select>
          </div>
          <div class="actions-inline">
            <button class="btn btn-primary" :disabled="loading" @click="loadItems">
              {{ loading ? '查询中...' : '查询' }}
            </button>
            <button class="btn btn-light" @click="resetFilters">重置</button>
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard :title="currentTabLabel">
      <template #actions>
        <button class="btn btn-light" :disabled="loading" @click="loadItems">刷新</button>
      </template>

      <div class="table-shell admin-catalog-table-shell">
        <table class="data-table admin-catalog-table">
          <thead>
            <tr v-if="activeTab === 'colleges'">
              <th>学院</th>
              <th>编码</th>
              <th>关联数据</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
            <tr v-else-if="activeTab === 'majors'">
              <th>专业</th>
              <th>编码</th>
              <th>所属学院</th>
              <th>关联数据</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
            <tr v-else>
              <th>学期</th>
              <th>编码</th>
              <th>学年/序号</th>
              <th>日期范围</th>
              <th>关联数据</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in items" :key="item.id">
              <tr v-if="activeTab === 'colleges'">
                <td><strong>{{ item.name }}</strong></td>
                <td class="mono">{{ item.code }}</td>
                <td>
                  专业 {{ item.majorCount || 0 }} / 课程 {{ item.courseCount || 0 }} / 教师 {{ item.teacherCount || 0 }}
                </td>
                <td><StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" /></td>
                <td>{{ item.updatedAt || '-' }}</td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="editItem(item)">编辑</button>
                  <button class="btn btn-danger btn-mini" @click="removeItem(item)">删除</button>
                </td>
              </tr>

              <tr v-else-if="activeTab === 'majors'">
                <td><strong>{{ item.name }}</strong></td>
                <td class="mono">{{ item.code }}</td>
                <td>{{ item.collegeName || '-' }}</td>
                <td>班级 {{ item.classCount || 0 }} / 课程 {{ item.courseCount || 0 }}</td>
                <td><StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" /></td>
                <td>{{ item.updatedAt || '-' }}</td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="editItem(item)">编辑</button>
                  <button class="btn btn-danger btn-mini" @click="removeItem(item)">删除</button>
                </td>
              </tr>

              <tr v-else>
                <td><strong>{{ item.name }}</strong></td>
                <td class="mono">{{ item.code }}</td>
                <td>{{ item.schoolYear }} / 第 {{ item.termNo }} 学期</td>
                <td>{{ item.startDate || '-' }} 至 {{ item.endDate || '-' }}</td>
                <td>
                  授课 {{ item.courseTeacherCount || 0 }} / 班课 {{ item.classCourseCount || 0 }} / 大纲 {{ item.outlineCount || 0 }}
                </td>
                <td><StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" /></td>
                <td>{{ item.updatedAt || '-' }}</td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="editItem(item)">编辑</button>
                  <button class="btn btn-danger btn-mini" @click="removeItem(item)">删除</button>
                </td>
              </tr>
            </template>
            <tr v-if="!items.length && !loading">
              <td :colspan="emptyColspan">
                <EmptyState mark="基" :title="`暂无${currentTabLabel}`" description="当前筛选条件下没有可显示的数据。" />
              </td>
            </tr>
            <tr v-if="loading">
              <td :colspan="emptyColspan" class="muted center-text">加载中...</td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createAdminCollege,
  createAdminMajor,
  createAdminSemester,
  deleteAdminCollege,
  deleteAdminMajor,
  deleteAdminSemester,
  getAdminColleges,
  getAdminMajors,
  getAdminSemesters,
  updateAdminCollege,
  updateAdminMajor,
  updateAdminSemester
} from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { confirmFeedback, showFeedback } from '@/utils/feedback'

const tabs = [
  { key: 'colleges', label: '学院信息' },
  { key: 'majors', label: '专业信息' },
  { key: 'semesters', label: '学期信息' }
]

const activeTab = ref('colleges')
const items = ref([])
const colleges = ref([])
const loading = ref(false)
const saving = ref(false)
const summary = reactive({
  total: 0,
  enabled: 0,
  disabled: 0
})
const filters = reactive({
  keyword: '',
  status: 'all',
  collegeId: ''
})
const collegeForm = reactive(defaultCollegeForm())
const majorForm = reactive(defaultMajorForm())
const semesterForm = reactive(defaultSemesterForm())

const currentTabLabel = computed(() => tabs.find((tab) => tab.key === activeTab.value)?.label || '基础信息')
const editingId = computed(() => currentForm().id)
const formTitle = computed(() => `${editingId.value ? '编辑' : '新增'}${currentTabLabel.value}`)
const formSubmitText = computed(() => editingId.value ? '保存修改' : `新增${currentTabLabel.value}`)
const keywordPlaceholder = computed(() => {
  if (activeTab.value === 'semesters') return '编码、名称或学年'
  if (activeTab.value === 'majors') return '编码、名称或学院'
  return '编码或名称'
})
const emptyColspan = computed(() => activeTab.value === 'colleges' ? 6 : activeTab.value === 'majors' ? 7 : 8)

function defaultCollegeForm() {
  return {
    id: '',
    code: '',
    name: '',
    status: 1
  }
}

function defaultMajorForm() {
  return {
    id: '',
    collegeId: '',
    code: '',
    name: '',
    status: 1
  }
}

function defaultSemesterForm() {
  return {
    id: '',
    code: '',
    name: '',
    schoolYear: '',
    termNo: 1,
    startDate: '',
    endDate: '',
    status: 1
  }
}

function currentForm() {
  if (activeTab.value === 'colleges') return collegeForm
  if (activeTab.value === 'majors') return majorForm
  return semesterForm
}

function applySummary(payload = {}) {
  summary.total = payload.total || 0
  summary.enabled = payload.enabled || 0
  summary.disabled = payload.disabled || 0
}

async function loadItems() {
  loading.value = true
  try {
    let data
    if (activeTab.value === 'colleges') {
      data = await getAdminColleges({ keyword: filters.keyword, status: filters.status })
    } else if (activeTab.value === 'majors') {
      data = await getAdminMajors({
        keyword: filters.keyword,
        status: filters.status,
        collegeId: filters.collegeId || undefined
      })
      colleges.value = data.colleges || colleges.value
    } else {
      data = await getAdminSemesters({ keyword: filters.keyword, status: filters.status })
    }
    items.value = data.items || []
    applySummary(data.summary)
  } catch (error) {
    showFeedback('error', error.message || `${currentTabLabel.value}加载失败`)
  } finally {
    loading.value = false
  }
}

async function ensureCollegeOptions() {
  if (colleges.value.length) return
  const data = await getAdminMajors({ status: 'all' })
  colleges.value = data.colleges || []
}

async function switchTab(tabKey) {
  activeTab.value = tabKey
  resetFilters(false)
  resetCurrentForm()
  if (tabKey === 'majors') {
    await ensureCollegeOptions()
  }
  await loadItems()
}

function resetFilters(reload = true) {
  filters.keyword = ''
  filters.status = 'all'
  filters.collegeId = ''
  if (reload) {
    loadItems()
  }
}

function resetCurrentForm() {
  Object.assign(collegeForm, defaultCollegeForm())
  Object.assign(majorForm, defaultMajorForm())
  Object.assign(semesterForm, defaultSemesterForm())
}

function editItem(item) {
  if (activeTab.value === 'colleges') {
    Object.assign(collegeForm, {
      id: item.id,
      code: item.code || '',
      name: item.name || '',
      status: Number(item.status) === 0 ? 0 : 1
    })
    return
  }

  if (activeTab.value === 'majors') {
    Object.assign(majorForm, {
      id: item.id,
      collegeId: item.collegeId || '',
      code: item.code || '',
      name: item.name || '',
      status: Number(item.status) === 0 ? 0 : 1
    })
    return
  }

  Object.assign(semesterForm, {
    id: item.id,
    code: item.code || '',
    name: item.name || '',
    schoolYear: item.schoolYear || '',
    termNo: Number(item.termNo) || 1,
    startDate: item.startDate || '',
    endDate: item.endDate || '',
    status: Number(item.status) === 0 ? 0 : 1
  })
}

async function submitCurrent() {
  if (!validateCurrentForm()) return

  saving.value = true
  try {
    const form = currentForm()
    const payload = { ...form, id: undefined }
    if (activeTab.value === 'colleges') {
      form.id ? await updateAdminCollege(form.id, payload) : await createAdminCollege(payload)
    } else if (activeTab.value === 'majors') {
      form.id ? await updateAdminMajor(form.id, payload) : await createAdminMajor(payload)
    } else {
      form.id ? await updateAdminSemester(form.id, payload) : await createAdminSemester(payload)
    }
    resetCurrentForm()
    await loadItems()
    showFeedback('success', `${currentTabLabel.value}已保存`)
  } catch (error) {
    showFeedback('error', error.message || `${currentTabLabel.value}保存失败`)
  } finally {
    saving.value = false
  }
}

function validateCurrentForm() {
  if (activeTab.value === 'colleges') {
    if (!collegeForm.code || !collegeForm.name) {
      showFeedback('warning', '请填写学院编码和学院名称')
      return false
    }
  } else if (activeTab.value === 'majors') {
    if (!majorForm.collegeId || !majorForm.code || !majorForm.name) {
      showFeedback('warning', '请选择学院并填写专业编码和专业名称')
      return false
    }
  } else if (!semesterForm.code || !semesterForm.name || !semesterForm.schoolYear || !semesterForm.termNo) {
    showFeedback('warning', '请填写学期编码、学期名称、学年和学期序号')
    return false
  }
  return true
}

async function removeItem(item) {
  const confirmed = await confirmFeedback(`删除“${item.name}”？`, {
    type: 'warning',
    confirmText: '删除'
  })
  if (!confirmed) return

  try {
    if (activeTab.value === 'colleges') {
      await deleteAdminCollege(item.id)
    } else if (activeTab.value === 'majors') {
      await deleteAdminMajor(item.id)
    } else {
      await deleteAdminSemester(item.id)
    }
    if (String(currentForm().id) === String(item.id)) {
      resetCurrentForm()
    }
    await loadItems()
    showFeedback('success', `${currentTabLabel.value}已删除`)
  } catch (error) {
    showFeedback('error', error.message || `${currentTabLabel.value}删除失败`)
  }
}

function statusText(status) {
  return Number(status) === 1 ? '启用' : '停用'
}

function statusTone(status) {
  return Number(status) === 1 ? 'success' : 'neutral'
}

onMounted(loadItems)
</script>

<style scoped>
.tab-strip {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  width: fit-content;
}

.tab-button {
  min-height: 34px;
  padding: 7px 14px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-soft);
  cursor: pointer;
}

.tab-button.active {
  background: var(--color-primary-deep);
  color: #fff;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  padding: 16px;
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-soft);
}

.summary-card span {
  display: block;
  font-size: 13px;
  color: var(--color-text-soft);
}

.summary-card strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  color: var(--color-primary-deep);
}

.admin-catalog-top {
  align-items: start;
}

.filter-card-body {
  display: grid;
  gap: 16px;
}

.admin-catalog-table-shell {
  min-height: 240px;
}

.admin-catalog-table {
  min-width: 920px;
}

.mono {
  font-family: Consolas, "Courier New", monospace;
}

.nowrap {
  white-space: nowrap;
}

.btn-mini {
  min-height: 30px;
  padding: 6px 10px;
  font-size: 12px;
}

.center-text {
  text-align: center;
}

@media (max-width: 860px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .tab-strip {
    display: grid;
    width: 100%;
  }
}
</style>
