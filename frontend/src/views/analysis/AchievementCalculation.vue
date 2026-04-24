<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="结果分析与教学改进"
      description="按照任务书中的闭环逻辑，先完成达成度核算，再进入多维分析、智能建议和改进措施跟踪。当前页面用于配置核算规则并执行核算。"
      :tabs="analysisImproveTabs"
    >
      <template #actions>
        <button class="btn btn-primary" :disabled="running" @click="runCalculation">
          {{ running ? '核算中...' : '开始核算' }}
        </button>
      </template>
    </ModuleHeader>

    <div class="filter-bar">
      <div class="filter-field">
        <label>课程</label>
        <select v-model="filters.courseId" class="select-input" @change="loadPage">
          <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
            {{ course.name }}（{{ course.code }}）
          </option>
        </select>
      </div>
      <div class="filter-field">
        <label>学期</label>
        <select v-model="filters.semester" class="select-input" @change="loadPage">
          <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
            {{ semester }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div v-if="record.dataSummary.warnings?.length" class="notice warning">
      <div v-for="item in record.dataSummary.warnings" :key="item">{{ item }}</div>
    </div>

    <div class="grid-2">
      <PanelCard title="核算规则配置" subtitle="支持设置核算方法、达成阈值和及格阈值。">
        <div class="form-stack">
          <div class="form-field">
            <label>核算方法</label>
            <select v-model="record.config.calcMethod" class="select-input">
              <option value="weighted_avg">加权平均法</option>
              <option value="threshold">阈值法（班级达成率）</option>
              <option value="custom">自定义规则</option>
            </select>
          </div>
          <div class="form-grid-2">
            <div class="form-field">
              <label>达成阈值</label>
              <input v-model="record.config.thresholdValue" class="text-input" type="number" min="0.01" max="1" step="0.01" />
            </div>
            <div class="form-field">
              <label>及格阈值</label>
              <input v-model="record.config.passThreshold" class="text-input" type="number" min="0.01" max="1" step="0.01" />
            </div>
          </div>

          <template v-if="record.config.calcMethod === 'custom'">
            <div class="form-field">
              <label class="checkbox-label">
                <input type="checkbox" v-model="record.config.retakeEnabled" />
                启用补考折算（取 max(正考, 补考×0.8)）
              </label>
            </div>
            <div class="form-field">
              <label>分目标达成阈值</label>
              <div v-if="!objectives.length" class="notice info mt-8">
                请先选择有教学目标的课程学期。
              </div>
              <table v-else class="data-table threshold-table mt-8">
                <thead>
                  <tr>
                    <th>目标编号</th>
                    <th>达成阈值</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="obj in objectives" :key="obj.id">
                    <td>{{ obj.objCode }}</td>
                    <td>
                      <input
                        type="number"
                        class="text-input inline-input"
                        min="0.01"
                        max="1"
                        step="0.01"
                        v-model.number="record.config.customThresholds[String(obj.id)]"
                      />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>

          <div class="notice info method-hint">
            <template v-if="record.config.calcMethod === 'weighted_avg'">
              <strong>加权平均法：</strong>对每位学生计算分目标达成值（Σ得分率×考核权重），再取全班平均。≥0.6达成，≥0.7良好。
            </template>
            <template v-else-if="record.config.calcMethod === 'threshold'">
              <strong>阈值法：</strong>对每位学生计算达成值，≥及格阈值视为达成。班级目标达成率 = 达成人数 / 总人数。
            </template>
            <template v-else-if="record.config.calcMethod === 'custom'">
              <strong>自定义规则：</strong>为每个分目标设置独立达成阈值，可启用补考折算处理。
            </template>
          </div>
        </div>
      </PanelCard>

      <PanelCard title="核算概览" subtitle="展示当前课程的整体核算结果和最近生成时间。">
        <div class="grid-2">
          <StatCard label="课程整体达成度" :value="Number(record.overallAchievement || 0).toFixed(3)" tone="primary" />
          <StatCard label="最近生成时间" :value="record.generatedAt || '--'" tone="secondary" />
        </div>
        <div class="summary-strip mt-16">
          <div>
            <strong>{{ record.dataSummary.objectiveCount }}</strong>
            <span>教学目标</span>
          </div>
          <div>
            <strong>{{ record.dataSummary.assessItemCount }}</strong>
            <span>考核项</span>
          </div>
          <div>
            <strong>{{ record.dataSummary.confirmedGradeRows }}</strong>
            <span>已确认成绩</span>
          </div>
          <div>
            <strong>{{ record.dataSummary.pendingGradeRows }}</strong>
            <span>待确认成绩</span>
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="成绩数据覆盖情况" subtitle="按考核项检查已确认成绩数量和平均得分率，核算只使用已确认成绩。">
      <div class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>考核项</th>
              <th>类型</th>
              <th>成绩权重</th>
              <th>已确认记录</th>
              <th>待确认记录</th>
              <th>平均得分率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in record.dataSummary.assessItems" :key="item.assessItemId">
              <td>{{ item.itemName }}</td>
              <td>{{ item.itemTypeName }}</td>
              <td>{{ formatPercent(item.weight) }}</td>
              <td>{{ item.confirmedRows }}</td>
              <td>{{ item.pendingRows }}</td>
              <td class="metric">{{ Number(item.avgRate || 0).toFixed(3) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <PanelCard title="核算结果明细" subtitle="按目标展示各考核维度表现与是否达成。">
      <div class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>目标编号</th>
              <th v-if="record.config.calcMethod !== 'threshold'">平时</th>
              <th v-if="record.config.calcMethod !== 'threshold'">期中</th>
              <th v-if="record.config.calcMethod !== 'threshold'">期末</th>
              <th>{{ record.config.calcMethod === 'threshold' ? '班级达成率' : '达成值' }}</th>
              <th>目标权重</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in record.results" :key="item.objectiveId">
              <tr>
                <td>
                  <strong>{{ item.objCode }}</strong>
                  <small class="muted block-text">{{ item.objContent }}</small>
                </td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(item.normal).toFixed(3) }}</td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(item.mid).toFixed(3) }}</td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(item.final).toFixed(3) }}</td>
                <td class="metric">
                  {{ record.config.calcMethod === 'threshold'
                    ? formatPercent(Number(item.achieveValue) * 100)
                    : Number(item.achieveValue).toFixed(3) }}
                </td>
                <td>{{ formatPercent(item.objectiveWeight) }}</td>
                <td>
                  <StatusBadge :text="item.isAchieved ? '达成' : '未达成'" :tone="item.isAchieved ? 'success' : 'danger'" />
                </td>
              </tr>
              <tr v-if="item.details?.length && record.config.calcMethod !== 'threshold'" class="detail-tr">
                <td colspan="7">
                  <div class="detail-chip-grid">
                    <div v-for="detail in item.details" :key="detail.assessItemId" class="detail-chip">
                      <strong>{{ detail.itemName }}</strong>
                      <span>{{ detail.itemTypeName }} · 得分率 {{ Number(detail.scoreRate).toFixed(3) }}</span>
                      <span>贡献权重 {{ formatPercent(detail.contributionWeight) }} · 贡献值 {{ Number(detail.achieveValue).toFixed(3) }}</span>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { getAchievementCalculation, runAchievementCalculation } from '@/api'
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
  semester: ''
})

const objectives = ref([])

const record = reactive({
  config: {
    calcMethod: 'weighted_avg',
    thresholdValue: 0.7,
    passThreshold: 0.6,
    retakeEnabled: false,
    customThresholds: {}
  },
  generatedAt: '',
  overallAchievement: 0,
  results: [],
  dataSummary: {
    objectiveCount: 0,
    assessItemCount: 0,
    mappingCount: 0,
    confirmedGradeRows: 0,
    pendingGradeRows: 0,
    assessItems: [],
    warnings: []
  }
})

const running = ref(false)
const message = reactive({
  type: 'success',
  text: ''
})

watch(() => record.config.calcMethod, (method) => {
  if (method === 'custom') {
    objectives.value.forEach((obj) => {
      const key = String(obj.id)
      if (!(key in record.config.customThresholds)) {
        record.config.customThresholds[key] = record.config.thresholdValue
      }
    })
  }
})

function setMessage(type, text) {
  message.type = type
  message.text = text
}

function applyRecord(payload) {
  if (payload.config) {
    record.config.calcMethod = payload.config.calcMethod || 'weighted_avg'
    record.config.thresholdValue = payload.config.thresholdValue || 0.7
    record.config.passThreshold = payload.config.passThreshold || 0.6
    record.config.retakeEnabled = Boolean(payload.config.retakeEnabled)
    record.config.customThresholds = payload.config.customThresholds || {}
  }
  record.generatedAt = payload.generatedAt
  record.overallAchievement = payload.overallAchievement
  record.results = payload.results
  record.dataSummary = payload.dataSummary || record.dataSummary
}

function formatPercent(value) {
  return `${Number(value || 0).toFixed(2)}%`
}

async function loadPage() {
  const data = await getAchievementCalculation(filters)
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  filters.courseId = data.currentCourseId
  filters.semester = data.currentSemester
  objectives.value = data.objectives || []
  applyRecord(data.record)
}

async function runCalculation() {
  running.value = true
  try {
    const payload = {
      courseId: filters.courseId,
      semester: filters.semester,
      calcMethod: record.config.calcMethod,
      thresholdValue: record.config.thresholdValue,
      passThreshold: record.config.passThreshold
    }
    if (record.config.calcMethod === 'custom') {
      payload.customThresholds = record.config.customThresholds
      payload.retakeEnabled = record.config.retakeEnabled
    }
    const data = await runAchievementCalculation(payload)
    objectives.value = data.objectives || []
    applyRecord(data.record)
    setMessage('success', '达成度核算已完成，结果已更新。')
  } catch (error) {
    setMessage('error', error.message || '核算失败。')
  } finally {
    running.value = false
  }
}

onMounted(loadPage)
</script>

<style scoped>
.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.summary-strip div {
  padding: 12px;
  border: 1px solid #e3edf2;
  border-radius: 8px;
  background: #fbfdfe;
}

.summary-strip strong {
  display: block;
  font-size: 22px;
  color: var(--color-primary);
}

.summary-strip span,
.block-text {
  display: block;
  margin-top: 6px;
}

.detail-tr td {
  background: #fbfdfe;
}

.detail-chip-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.detail-chip {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #e1ebf1;
  border-radius: 8px;
  background: #fff;
  color: var(--color-text-soft);
}

.detail-chip strong {
  color: var(--color-text);
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-weight: normal;
}

.threshold-table {
  width: 100%;
}

.inline-input {
  width: 100px;
  padding: 6px 8px;
}

.method-hint {
  font-size: 13px;
  line-height: 1.6;
}

.mt-8 {
  margin-top: 8px;
}
</style>
