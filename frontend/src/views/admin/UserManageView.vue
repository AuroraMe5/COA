<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="用户管理"
      description="管理教师账号的创建、状态、密码和删除。"
    />

    <div class="summary-grid">
      <div class="summary-card">
        <span>教师账号</span>
        <strong>{{ summary.total }}</strong>
      </div>
      <div class="summary-card">
        <span>启用中</span>
        <strong>{{ summary.enabled }}</strong>
      </div>
      <div class="summary-card">
        <span>已禁用</span>
        <strong>{{ summary.disabled }}</strong>
      </div>
    </div>

    <div class="grid-2 user-top-grid">
      <PanelCard title="新增教师账号">
        <div class="form-grid-2">
          <div class="form-field">
            <label>用户名</label>
            <input v-model.trim="teacherForm.username" class="text-input" autocomplete="off" />
          </div>
          <div class="form-field">
            <label>初始密码</label>
            <input v-model.trim="teacherForm.password" class="text-input" type="password" autocomplete="new-password" />
          </div>
          <div class="form-field">
            <label>姓名</label>
            <input v-model.trim="teacherForm.realName" class="text-input" />
          </div>
          <div class="form-field">
            <label>所属学院</label>
            <select v-model="teacherForm.collegeId" class="select-input">
              <option value="">未设置</option>
              <option v-for="college in colleges" :key="college.id" :value="college.id">
                {{ college.name }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label>邮箱</label>
            <input v-model.trim="teacherForm.email" class="text-input" type="email" />
          </div>
          <div class="form-field">
            <label>手机号</label>
            <input v-model.trim="teacherForm.phone" class="text-input" />
          </div>
          <div class="form-field">
            <label>账号状态</label>
            <select v-model.number="teacherForm.status" class="select-input">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
          </div>
        </div>
        <div class="actions-inline mt-16">
          <button class="btn btn-primary" :disabled="savingTeacher" @click="submitTeacher">
            {{ savingTeacher ? '创建中...' : '创建教师账号' }}
          </button>
          <button class="btn btn-light" @click="resetTeacherForm">清空</button>
        </div>
      </PanelCard>

      <PanelCard title="账号筛选">
        <div class="filter-card-body">
          <div class="form-field">
            <label>关键词</label>
            <input
              v-model.trim="filters.keyword"
              class="text-input"
              placeholder="用户名、姓名、邮箱、手机号"
              @keyup.enter="loadTeachers"
            />
          </div>
          <div class="form-field">
            <label>状态</label>
            <select v-model="filters.status" class="select-input" @change="loadTeachers">
              <option value="all">全部</option>
              <option value="enabled">启用中</option>
              <option value="disabled">已禁用</option>
            </select>
          </div>
          <div class="actions-inline">
            <button class="btn btn-primary" :disabled="loading" @click="loadTeachers">
              {{ loading ? '查询中...' : '查询' }}
            </button>
            <button class="btn btn-light" @click="resetFilters">重置</button>
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="教师账号">
      <template #actions>
        <button class="btn btn-light" :disabled="loading" @click="loadTeachers">刷新</button>
      </template>

      <div class="table-shell user-table-shell">
        <table class="data-table user-table">
          <thead>
            <tr>
              <th>账号</th>
              <th>所属学院</th>
              <th>联系方式</th>
              <th>状态</th>
              <th>最近登录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in teachers" :key="item.id">
              <tr>
                <td>
                  <strong>{{ item.realName }}</strong>
                  <div class="cell-note mono">{{ item.username }}</div>
                </td>
                <td>{{ item.collegeName || '—' }}</td>
                <td>
                  <div>{{ item.phone || '—' }}</div>
                  <div class="cell-note">{{ item.email || '' }}</div>
                </td>
                <td>
                  <StatusBadge :text="statusText(item.status)" :tone="statusTone(item.status)" />
                </td>
                <td>{{ item.lastLoginAt || '—' }}</td>
                <td class="nowrap">
                  <button class="btn btn-light btn-mini" @click="startResetPassword(item)">重置密码</button>
                  <button
                    class="btn btn-light btn-mini"
                    @click="toggleTeacherStatus(item)"
                  >
                    {{ Number(item.status) === 1 ? '禁用' : '启用' }}
                  </button>
                  <button class="btn btn-danger btn-mini" @click="removeTeacher(item)">删除</button>
                </td>
              </tr>
              <tr v-if="String(resetDraft.id) === String(item.id)" class="reset-row">
                <td colspan="6">
                  <div class="reset-inline">
                    <div class="form-field reset-password-field">
                      <label>新密码</label>
                      <input v-model.trim="resetDraft.password" class="text-input" type="password" autocomplete="new-password" />
                    </div>
                    <div class="actions-inline">
                      <button class="btn btn-primary" :disabled="resettingPassword" @click="submitResetPassword">
                        {{ resettingPassword ? '重置中...' : '确认重置' }}
                      </button>
                      <button class="btn btn-light" @click="cancelResetPassword">取消</button>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!teachers.length && !loading">
              <td colspan="6">
                <EmptyState mark="师" title="暂无教师账号" description="当前筛选条件下没有可显示的教师账号。" />
              </td>
            </tr>
            <tr v-if="loading">
              <td colspan="6" class="muted center-text">加载中...</td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  createTeacherUser,
  deleteTeacherUser,
  getTeacherUsers,
  resetTeacherPassword,
  updateTeacherStatus
} from '@/api'
import EmptyState from '@/components/common/EmptyState.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { confirmFeedback, showFeedback } from '@/utils/feedback'

const DEFAULT_PASSWORD = 'admin123'

const teachers = ref([])
const colleges = ref([])
const loading = ref(false)
const savingTeacher = ref(false)
const resettingPassword = ref(false)
const summary = reactive({
  total: 0,
  enabled: 0,
  disabled: 0
})
const filters = reactive({
  keyword: '',
  status: 'all'
})
const teacherForm = reactive(defaultTeacherForm())
const resetDraft = reactive({
  id: '',
  password: DEFAULT_PASSWORD
})

function defaultTeacherForm() {
  return {
    username: '',
    password: DEFAULT_PASSWORD,
    realName: '',
    email: '',
    phone: '',
    collegeId: '',
    status: 1
  }
}

function applySummary(payload = {}) {
  summary.total = payload.total || 0
  summary.enabled = payload.enabled || 0
  summary.disabled = payload.disabled || 0
}

async function loadTeachers() {
  loading.value = true
  try {
    const data = await getTeacherUsers({
      keyword: filters.keyword,
      status: filters.status
    })
    teachers.value = data.teachers || []
    colleges.value = data.colleges || []
    applySummary(data.summary)
  } catch (error) {
    showFeedback('error', error.message || '教师账号加载失败')
  } finally {
    loading.value = false
  }
}

async function submitTeacher() {
  if (!teacherForm.username || !teacherForm.password || !teacherForm.realName) {
    showFeedback('warning', '请填写用户名、初始密码和姓名')
    return
  }

  savingTeacher.value = true
  try {
    await createTeacherUser({
      username: teacherForm.username,
      password: teacherForm.password,
      realName: teacherForm.realName,
      email: teacherForm.email,
      phone: teacherForm.phone,
      collegeId: teacherForm.collegeId || null,
      status: teacherForm.status
    })
    resetTeacherForm()
    await loadTeachers()
    showFeedback('success', '教师账号已创建')
  } catch (error) {
    showFeedback('error', error.message || '教师账号创建失败')
  } finally {
    savingTeacher.value = false
  }
}

function resetTeacherForm() {
  Object.assign(teacherForm, defaultTeacherForm())
}

function resetFilters() {
  filters.keyword = ''
  filters.status = 'all'
  loadTeachers()
}

function startResetPassword(item) {
  resetDraft.id = item.id
  resetDraft.password = DEFAULT_PASSWORD
}

function cancelResetPassword() {
  resetDraft.id = ''
  resetDraft.password = DEFAULT_PASSWORD
}

async function submitResetPassword() {
  if (!resetDraft.password) {
    showFeedback('warning', '请填写新密码')
    return
  }

  resettingPassword.value = true
  try {
    await resetTeacherPassword(resetDraft.id, { password: resetDraft.password })
    cancelResetPassword()
    await loadTeachers()
    showFeedback('success', '教师密码已重置')
  } catch (error) {
    showFeedback('error', error.message || '密码重置失败')
  } finally {
    resettingPassword.value = false
  }
}

async function toggleTeacherStatus(item) {
  const enabled = Number(item.status) !== 1
  const action = enabled ? '启用' : '禁用'
  const confirmed = await confirmFeedback(`${action}教师账号“${item.realName || item.username}”？`, {
    confirmText: action
  })
  if (!confirmed) {
    return
  }

  try {
    await updateTeacherStatus(item.id, { enabled })
    await loadTeachers()
    showFeedback('success', `教师账号已${action}`)
  } catch (error) {
    showFeedback('error', error.message || `${action}失败`)
  }
}

async function removeTeacher(item) {
  const confirmed = await confirmFeedback(`删除教师账号“${item.realName || item.username}”？`, {
    type: 'warning',
    confirmText: '删除'
  })
  if (!confirmed) {
    return
  }

  try {
    await deleteTeacherUser(item.id)
    await loadTeachers()
    showFeedback('success', '教师账号已删除')
  } catch (error) {
    showFeedback('error', error.message || '教师账号删除失败')
  }
}

function statusText(status) {
  return Number(status) === 1 ? '启用' : '禁用'
}

function statusTone(status) {
  return Number(status) === 1 ? 'success' : 'neutral'
}

onMounted(loadTeachers)
</script>

<style scoped>
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

.user-top-grid {
  align-items: start;
}

.filter-card-body {
  display: grid;
  gap: 16px;
}

.user-table-shell {
  min-height: 220px;
}

.user-table {
  min-width: 920px;
}

.cell-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-soft);
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

.reset-row td {
  background: var(--bg-panel-soft);
}

.reset-inline {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}

.reset-password-field {
  min-width: min(320px, 100%);
}

@media (max-width: 860px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
