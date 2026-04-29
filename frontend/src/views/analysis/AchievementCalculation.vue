<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="达成度核算"
      description="基于课程目标、考核内容分值分配和已确认原始成绩完成达成度核算。"
    />

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
      <div class="filter-field">
        <label>班级</label>
        <select v-model="filters.classId" class="select-input" @change="loadPage">
          <option value="">全部班级</option>
          <option v-for="item in catalogs.classes" :key="item.id" :value="String(item.id)">
            {{ item.className }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="message.text" class="notice" :class="message.type">{{ message.text }}</div>

    <div v-if="record.dataSummary.warnings?.length" class="notice warning">
      <div v-for="item in record.dataSummary.warnings" :key="item">{{ item }}</div>
    </div>

    <div class="grid-2">
      <PanelCard title="核算规则配置" subtitle="支持设置核算方法、达成阈值和良好阈值。">
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
              <label>良好阈值</label>
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
              <strong>加权平均法：</strong>对每位学生计算分目标达成值（Σ得分率×考核权重），再取全班平均。默认≥0.6达成，≥0.7良好。
            </template>
            <template v-else-if="record.config.calcMethod === 'threshold'">
              <strong>阈值法：</strong>对每位学生计算达成值，≥达成阈值视为达成。班级目标达成率 = 达成人数 / 总人数，默认≥0.7视为良好。
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

    <PanelCard title="课程目标分值分配" subtitle="按考核内容设置其计入各课程目标的分值，合计后形成达成度汇总表中的目标实际得分。">
      <template #actions>
        <button class="btn btn-secondary" :disabled="mappingSaving" @click="saveContentMapping">
          {{ mappingSaving ? '保存中...' : '保存分配矩阵' }}
        </button>
      </template>
      <div v-if="!contentBlocks.length || !contentObjectives.length" class="notice info">
        请先维护课程目标和考核内容，并完成成绩导入后再配置分配矩阵。
      </div>
      <div v-else class="content-map-block-stack">
        <section v-for="block in contentBlocks" :key="block.assessItemId" class="content-map-block">
          <header class="content-map-block-head">
            <div>
              <h3>{{ block.assessItemName }}</h3>
              <p>
                {{ block.assessItemTypeName || '考核项' }}
                · 考核项权重 {{ formatScore(block.assessItemWeight) }}
                · 考核内容 {{ block.contents.length }} 项
              </p>
            </div>
            <div class="content-map-block-summary">
              <StatusBadge
                :text="`本块已分配 ${formatScore(blockAllocatedTotal(block))}`"
                :tone="isBlockAllocationOk(block) ? 'success' : 'warning'"
              />
              <small>内容满分合计 {{ formatScore(blockContentMaxTotal(block)) }}</small>
            </div>
          </header>

          <div class="table-shell content-map-shell">
            <table class="data-table content-map-table">
              <thead>
                <tr>
                  <th>序号</th>
                  <th>考核内容</th>
                  <th>类型</th>
                  <th>内容满分</th>
                  <th v-for="obj in contentObjectives" :key="obj.id">{{ obj.objCode }}</th>
                  <th>已分配</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="content in block.contents" :key="content.assessContentId">
                  <td class="mono">{{ content.contentNo }}</td>
                  <td>
                    <strong>{{ content.contentName }}</strong>
                  </td>
                  <td>{{ content.contentTypeName }}</td>
                  <td>{{ formatScore(content.maxScore) }}</td>
                  <td v-for="obj in contentObjectives" :key="obj.id">
                    <input
                      class="text-input matrix-input"
                      type="number"
                      min="0"
                      step="0.01"
                      :value="contentMappingValue(obj.id, content.assessContentId)"
                      @input="setContentMappingValue(obj.id, content.assessContentId, $event.target.value)"
                    />
                  </td>
                  <td>
                    <StatusBadge
                      :text="formatScore(contentAllocatedTotal(content.assessContentId))"
                      :tone="contentAllocatedTotal(content.assessContentId) <= Number(content.maxScore || 0) + 0.01 ? 'success' : 'warning'"
                    />
                  </td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td colspan="4">本块目标小计</td>
                  <td v-for="obj in contentObjectives" :key="obj.id">
                    {{ formatScore(blockObjectiveAllocatedTotal(block, obj.id)) }}
                  </td>
                  <td>{{ formatScore(blockAllocatedTotal(block)) }}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        </section>

        <div class="content-map-total-strip">
          <div
            v-for="obj in contentObjectives"
            :key="obj.id"
            :class="{ warning: !isObjectiveAllocationOk(obj) }"
          >
            <span>{{ obj.objCode }} 目标合计</span>
            <strong>{{ formatScore(objectiveAllocatedTotal(obj.id)) }} / {{ formatScore(obj.weight) }}</strong>
            <small>{{ objectiveAllocationGapText(obj) }}</small>
          </div>
        </div>
      </div>
    </PanelCard>

    <PanelCard v-if="hasSmartAnalysis" title="智能分析">
      <div class="analysis-layout">
        <div class="analysis-main">
          <div class="analysis-heading">
            <span class="risk-badge" :class="record.smartAnalysis.riskLevel">{{ riskLabel }}</span>
            <strong>{{ analysisTitle }}</strong>
          </div>
          <p class="analysis-summary-text">{{ record.smartAnalysis.summary }}</p>

          <div class="analysis-columns">
            <div>
              <h3>关键发现</h3>
              <ul class="analysis-list">
                <li v-for="item in record.smartAnalysis.highlights" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <h3>改进动作</h3>
              <ul class="analysis-list">
                <li v-for="item in record.smartAnalysis.actions" :key="item">{{ item }}</li>
              </ul>
            </div>
          </div>
        </div>

        <div class="weak-panel">
          <div>
            <h3>薄弱目标</h3>
            <div v-if="record.smartAnalysis.weakObjectives?.length" class="weak-list">
              <div v-for="item in record.smartAnalysis.weakObjectives" :key="item.objectiveId" class="weak-row">
                <div>
                  <strong>{{ item.objCode }}</strong>
                  <small>{{ item.objContent }}</small>
                </div>
                <span>{{ Number(item.achieveValue || 0).toFixed(3) }}</span>
              </div>
            </div>
            <div v-else class="empty-inline">暂无低于阈值的课程目标</div>
          </div>

          <div>
            <h3>薄弱考核项</h3>
            <div v-if="record.smartAnalysis.weakAssessItems?.length" class="weak-list">
              <div v-for="item in record.smartAnalysis.weakAssessItems" :key="item.assessItemId" class="weak-row">
                <div>
                  <strong>{{ item.itemName }}</strong>
                  <small>{{ item.itemTypeName }} · 已确认 {{ item.confirmedRows }} 条</small>
                </div>
                <span>{{ Number(item.avgRate || 0).toFixed(3) }}</span>
              </div>
            </div>
            <div v-else class="empty-inline">暂无明显低分考核项</div>
          </div>
        </div>
      </div>
    </PanelCard>

    <PanelCard v-if="hasChartData" title="图表看板">
      <div class="chart-grid">
        <div class="chart-block">
          <div class="chart-title">课程目标达成度</div>
          <ChartPanel :option="objectiveChartOption" height="300px" />
        </div>
        <div class="chart-block">
          <div class="chart-title">考核项平均得分率</div>
          <ChartPanel :option="assessRateChartOption" height="300px" />
        </div>
        <div class="chart-block">
          <div class="chart-title">目标达成差距</div>
          <ChartPanel :option="componentChartOption" height="280px" />
        </div>
        <div class="chart-block">
          <div class="chart-title">考核项表现与权重</div>
          <ChartPanel :option="coverageChartOption" height="280px" />
        </div>
      </div>
    </PanelCard>

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
              <th v-if="record.config.calcMethod !== 'threshold'">实验</th>
              <th v-if="record.config.calcMethod !== 'threshold'">考核</th>
              <th>{{ record.config.calcMethod === 'threshold' ? '班级达成率' : '达成值' }}</th>
              <th>目标权重</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in resultDisplayRows" :key="row.key" :class="{ 'detail-tr': row.type === 'detail' }">
              <template v-if="row.type === 'main'">
                <td>
                  <strong>{{ row.item.objCode }}</strong>
                  <small class="muted block-text">{{ row.item.objContent }}</small>
                </td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(row.item.normal).toFixed(3) }}</td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(row.item.mid).toFixed(3) }}</td>
                <td v-if="record.config.calcMethod !== 'threshold'">{{ Number(row.item.final).toFixed(3) }}</td>
                <td class="metric">
                  {{ record.config.calcMethod === 'threshold'
                    ? formatPercent(Number(row.item.achieveValue) * 100)
                    : Number(row.item.achieveValue).toFixed(3) }}
                </td>
                <td>{{ formatPercent(row.item.objectiveWeight) }}</td>
                <td>
                  <StatusBadge :text="row.item.isAchieved ? '达成' : '未达成'" :tone="row.item.isAchieved ? 'success' : 'danger'" />
                </td>
              </template>
              <template v-else>
                <td colspan="7">
                  <div class="detail-chip-grid">
                    <div v-for="detail in row.item.details" :key="detail.assessItemId" class="detail-chip">
                      <strong>{{ detail.itemName }}</strong>
                      <span>{{ detail.itemTypeName }} · 得分率 {{ Number(detail.scoreRate).toFixed(3) }}</span>
                      <span>贡献权重 {{ formatPercent(detail.contributionWeight) }} · 贡献值 {{ Number(detail.achieveValue).toFixed(3) }}</span>
                    </div>
                  </div>
                </td>
              </template>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <div class="page-bottom-actions">
      <button class="btn btn-primary" :disabled="running" @click="runCalculation">
        {{ running ? '核算中...' : '开始核算' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  getAchievementCalculation,
  getAchievementContentMapping,
  runAchievementCalculation,
  saveAchievementContentMapping
} from '@/api'
import ChartPanel from '@/components/common/ChartPanel.vue'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { showFeedback } from '@/utils/feedback'

const catalogs = reactive({
  courses: [],
  semesters: [],
  classes: []
})

const filters = reactive({
  courseId: '',
  semester: '',
  classId: ''
})

const objectives = ref([])

const record = reactive({
  config: {
    calcRuleId: null,
    calcMethod: 'weighted_avg',
    thresholdValue: 0.6,
    passThreshold: 0.7,
    retakeEnabled: false,
    customThresholds: {}
  },
  generatedAt: '',
  overallAchievement: 0,
  results: [],
  dataSummary: defaultDataSummary(),
  smartAnalysis: defaultSmartAnalysis(),
  chartData: defaultChartData()
})

const running = ref(false)
const currentOutlineId = ref(null)
const contentObjectives = ref([])
const contentColumns = ref([])
const contentRows = ref([])
const mappingSaving = ref(false)
const message = reactive({
  type: 'success',
  text: ''
})
const PIE_CHART_COLORS = [
  '#2563eb',
  '#16a34a',
  '#f59e0b',
  '#7c3aed',
  '#0891b2',
  '#db2777',
  '#65a30d',
  '#ea580c',
  '#0f766e',
  '#4f46e5',
  '#be123c',
  '#9333ea'
]

const hasSmartAnalysis = computed(() => Boolean(record.smartAnalysis?.summary))

const hasChartData = computed(() => {
  return Boolean(
    record.results?.length ||
    record.dataSummary?.assessItems?.length ||
    Number(record.dataSummary?.confirmedGradeRows || 0) + Number(record.dataSummary?.pendingGradeRows || 0) > 0
  )
})

const resultDisplayRows = computed(() => {
  const rows = []
  for (const item of record.results || []) {
    rows.push({ key: `${item.objectiveId}-main`, type: 'main', item })
    if (item.details?.length && record.config.calcMethod !== 'threshold') {
      rows.push({ key: `${item.objectiveId}-details`, type: 'detail', item })
    }
  }
  return rows
})

const contentBlocks = computed(() => {
  const blockMap = new Map()
  for (const content of contentColumns.value || []) {
    const key = String(content.assessItemId || content.assessItemName || 'unknown')
    if (!blockMap.has(key)) {
      blockMap.set(key, {
        assessItemId: key,
        assessItemName: content.assessItemName || '未命名考核项',
        assessItemTypeName: content.assessItemTypeName || '',
        assessItemWeight: Number(content.assessItemWeight || 0),
        contents: []
      })
    }
    blockMap.get(key).contents.push(content)
  }
  return Array.from(blockMap.values())
})

const riskLabel = computed(() => {
  const labels = {
    success: '良好',
    warning: '关注',
    danger: '预警',
    info: '待核算'
  }
  return labels[record.smartAnalysis?.riskLevel] || '分析'
})

const analysisTitle = computed(() => {
  if (record.smartAnalysis?.riskLevel === 'success') {
    return '整体达成情况稳定'
  }
  if (record.smartAnalysis?.riskLevel === 'danger') {
    return '存在低于阈值的达成风险'
  }
  if (record.smartAnalysis?.riskLevel === 'warning') {
    return '需要关注数据或薄弱环节'
  }
  return '等待形成完整核算结果'
})

const objectiveChartOption = computed(() => {
  const data = (record.chartData?.objectiveBars?.length ? record.chartData.objectiveBars : record.results).map((item) => ({
    name: item.name || item.objCode,
    value: rateToPercent(item.value ?? item.achieveValue),
    achieved: item.achieved ?? item.isAchieved
  }))
  return {
    color: ['#2f855a'],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => `${Number(value || 0).toFixed(1)}%`
    },
    grid: { left: 42, right: 24, top: 28, bottom: 42 },
    xAxis: {
      type: 'category',
      data: data.map((item) => item.name),
      axisTick: { alignWithLabel: true }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%' }
    },
    series: [{
      name: '达成度',
      type: 'bar',
      barMaxWidth: 34,
      data: data.map((item) => ({
        value: item.value,
        itemStyle: { color: item.achieved ? '#2f855a' : '#c2410c' }
      })),
      markLine: {
        symbol: 'none',
        lineStyle: { color: '#b7791f', type: 'dashed' },
        label: { formatter: '阈值' },
        data: [{ yAxis: rateToPercent(record.smartAnalysis?.thresholdValue || record.config.thresholdValue || 0.6) }]
      }
    }]
  }
})

const assessRateChartOption = computed(() => {
  const goodThreshold = rateToPercent(record.smartAnalysis?.goodThreshold || record.config.passThreshold || 0.7)
  const data = (record.chartData?.assessRates?.length ? record.chartData.assessRates : record.dataSummary.assessItems)
    .map((item) => ({
      name: item.name || item.itemName,
      typeName: item.typeName || item.itemTypeName,
      value: rateToPercent(item.value ?? item.avgRate)
    }))
    .sort((left, right) => left.value - right.value)
  return {
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => `${Number(value || 0).toFixed(1)}%`
    },
    grid: { left: 96, right: 24, top: 24, bottom: 32 },
    xAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%' }
    },
    yAxis: {
      type: 'category',
      data: data.map((item) => `${item.name}`),
      axisLabel: {
        width: 82,
        overflow: 'truncate'
      }
    },
    series: [{
      name: '平均得分率',
      type: 'bar',
      barMaxWidth: 22,
      data: data.map((item) => ({
        value: item.value,
        itemStyle: { color: item.value >= goodThreshold ? '#2b6cb0' : '#c2410c' }
      }))
    }]
  }
})

const componentChartOption = computed(() => {
  const threshold = rateToPercent(record.smartAnalysis?.thresholdValue || record.config.thresholdValue || 0.6)
  const data = (record.chartData?.objectiveBars?.length ? record.chartData.objectiveBars : record.results)
    .map((item) => {
      const value = rateToPercent(item.value ?? item.achieveValue)
      return {
        name: item.name || item.objCode,
        value,
        gap: Math.max(0, Number((threshold - value).toFixed(1))),
        achieved: item.achieved ?? item.isAchieved
      }
    })
    .sort((left, right) => right.gap - left.gap || left.value - right.value)
  const maxValue = Math.max(100, threshold, ...data.map((item) => item.value + item.gap))
  return {
    color: ['#2563eb', '#f59e0b'],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const item = data[params?.[0]?.dataIndex] || {}
        return [
          `<strong>${item.name || ''}</strong>`,
          `当前达成度：${Number(item.value || 0).toFixed(1)}%`,
          `达成阈值：${Number(threshold || 0).toFixed(1)}%`,
          item.gap > 0 ? `距离阈值：${Number(item.gap).toFixed(1)}%` : '已达到阈值'
        ].join('<br/>')
      }
    },
    legend: { top: 0, right: 8 },
    grid: { left: 72, right: 24, top: 36, bottom: 32 },
    xAxis: {
      type: 'value',
      min: 0,
      max: maxValue,
      axisLabel: { formatter: '{value}%' }
    },
    yAxis: {
      type: 'category',
      data: data.map((item) => item.name),
      axisLabel: {
        width: 58,
        overflow: 'truncate'
      }
    },
    series: [
      {
        name: '当前达成度',
        type: 'bar',
        stack: 'gap',
        barMaxWidth: 22,
        data: data.map((item) => ({
          value: item.value,
          itemStyle: { color: item.achieved ? '#2563eb' : '#c2410c' }
        })),
        markLine: {
          symbol: 'none',
          lineStyle: { color: '#b7791f', type: 'dashed' },
          label: { formatter: '阈值' },
          data: [{ xAxis: threshold }]
        }
      },
      {
        name: '距离阈值',
        type: 'bar',
        stack: 'gap',
        barMaxWidth: 22,
        data: data.map((item) => item.gap),
        itemStyle: { color: '#f59e0b' }
      }
    ]
  }
})

const coverageChartOption = computed(() => {
  const threshold = rateToPercent(record.smartAnalysis?.thresholdValue || record.config.thresholdValue || 0.6)
  const data = (record.dataSummary.assessItems || []).map((item) => ({
    name: item.itemName,
    typeName: item.itemTypeName,
    confirmedRows: Number(item.confirmedRows || 0),
    pendingRows: Number(item.pendingRows || 0),
    avgRate: rateToPercent(item.avgRate),
    weight: Number(item.weight || 0)
  }))
  const coloredData = assignDistinctPieColors(data)
  return {
    color: PIE_CHART_COLORS,
    tooltip: {
      trigger: 'item',
      formatter: ({ data: item }) => [
        `<strong>${item.name}</strong>`,
        `类型：${item.typeName || '--'}`,
        `平均得分率：${Number(item.avgRate || 0).toFixed(1)}%`,
        `已确认记录：${item.confirmedRows}`,
        `待确认记录：${item.pendingRows}`,
        `考核权重：${Number(item.weight || 0).toFixed(2)}%`,
        Number(item.avgRate || 0) >= threshold ? '状态：达到阈值' : '状态：低于阈值'
      ].join('<br/>')
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      left: 'center'
    },
    series: [{
      name: '考核项权重',
      type: 'pie',
      radius: ['42%', '70%'],
      center: ['50%', '43%'],
      minAngle: 8,
      avoidLabelOverlap: true,
      data: coloredData.map((item) => ({
        name: item.name,
        value: item.weight,
        ...item,
        itemStyle: {
          color: item.color,
          borderColor: pieStatusBorderColor(item, threshold),
          borderWidth: item.confirmedRows === 0 || item.avgRate < threshold ? 3 : 1,
          opacity: 0.9
        }
      })),
      label: {
        show: true,
        formatter: ({ data: item }) => `${item.name}\n${Number(item.value || 0).toFixed(1)}%`,
        color: '#334155',
        fontSize: 11
      },
      emphasis: {
        label: {
          show: true,
          fontWeight: 700
        }
      }
    }]
  }
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

function defaultDataSummary() {
  return {
    objectiveCount: 0,
    assessItemCount: 0,
    mappingCount: 0,
    contentMappingCount: 0,
    confirmedGradeRows: 0,
    pendingGradeRows: 0,
    assessItems: [],
    warnings: []
  }
}

function defaultSmartAnalysis() {
  return {
    summary: '',
    riskLevel: 'info',
    thresholdValue: 0.6,
    goodThreshold: 0.7,
    achievedCount: 0,
    objectiveCount: 0,
    highlights: [],
    weakObjectives: [],
    weakAssessItems: [],
    actions: []
  }
}

function defaultChartData() {
  return {
    objectiveBars: [],
    componentBars: [],
    assessRates: [],
    gradeCoverage: []
  }
}

function rateToPercent(value) {
  return Number((Number(value || 0) * 100).toFixed(1))
}

function assignDistinctPieColors(items) {
  const assigned = []
  return items.map((item, index) => {
    const color = pickPieColor(index, items.length, assigned)
    assigned.push(color)
    return { ...item, color }
  })
}

function pickPieColor(index, total, assigned) {
  const previous = assigned[index - 1]
  const first = assigned[0]
  for (let offset = 0; offset < PIE_CHART_COLORS.length; offset += 1) {
    const color = PIE_CHART_COLORS[(index + offset) % PIE_CHART_COLORS.length]
    if (color === previous) {
      continue
    }
    if (total > 1 && index === total - 1 && color === first) {
      continue
    }
    return color
  }
  return PIE_CHART_COLORS[index % PIE_CHART_COLORS.length]
}

function pieStatusBorderColor(item, threshold) {
  if (item.confirmedRows === 0) {
    return '#64748b'
  }
  return Number(item.avgRate || 0) >= threshold ? '#ffffff' : '#991b1b'
}

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function applyRecord(payload = {}) {
  if (payload.config) {
    record.config.calcRuleId = payload.config.calcRuleId || null
    record.config.calcMethod = payload.config.calcMethod || 'weighted_avg'
    record.config.thresholdValue = payload.config.thresholdValue || 0.6
    record.config.passThreshold = payload.config.passThreshold || 0.7
    record.config.retakeEnabled = Boolean(payload.config.retakeEnabled)
    record.config.customThresholds = payload.config.customThresholds || {}
  }
  record.generatedAt = payload.generatedAt || ''
  record.overallAchievement = payload.overallAchievement || 0
  record.results = payload.results || []
  record.dataSummary = { ...defaultDataSummary(), ...(payload.dataSummary || {}) }
  record.smartAnalysis = { ...defaultSmartAnalysis(), ...(payload.smartAnalysis || {}) }
  record.chartData = { ...defaultChartData(), ...(payload.chartData || {}) }
}

function formatPercent(value) {
  return `${Number(value || 0).toFixed(2)}%`
}

function formatScore(value) {
  return Number(value || 0).toFixed(2)
}

function contentRow(objectiveId) {
  return contentRows.value.find((row) => Number(row.objectiveId) === Number(objectiveId))
}

function contentMappingValue(objectiveId, contentId) {
  return contentRow(objectiveId)?.values?.[String(contentId)] ?? ''
}

function setContentMappingValue(objectiveId, contentId, value) {
  let row = contentRow(objectiveId)
  if (!row) {
    row = { objectiveId, values: {} }
    contentRows.value.push(row)
  }
  if (!row.values) {
    row.values = {}
  }
  row.values[String(contentId)] = value === '' ? '' : Number(value)
}

function contentAllocatedTotal(contentId) {
  return contentRows.value.reduce((sum, row) => {
    return sum + Number(row.values?.[String(contentId)] || 0)
  }, 0)
}

function objectiveAllocatedTotal(objectiveId) {
  const row = contentRow(objectiveId)
  if (!row?.values) {
    return 0
  }
  return Object.values(row.values).reduce((sum, value) => sum + Number(value || 0), 0)
}

function blockContentMaxTotal(block) {
  return (block?.contents || []).reduce((sum, content) => sum + Number(content.maxScore || 0), 0)
}

function blockAllocatedTotal(block) {
  return (block?.contents || []).reduce((sum, content) => {
    return sum + contentAllocatedTotal(content.assessContentId)
  }, 0)
}

function blockObjectiveAllocatedTotal(block, objectiveId) {
  return (block?.contents || []).reduce((sum, content) => {
    return sum + Number(contentMappingValue(objectiveId, content.assessContentId) || 0)
  }, 0)
}

function isBlockAllocationOk(block) {
  const target = blockContentMaxTotal(block)
  return target <= 0 || blockAllocatedTotal(block) <= target + 0.01
}

function isObjectiveAllocationOk(objective) {
  return Math.abs(objectiveAllocatedTotal(objective.id) - Number(objective.weight || 0)) <= 0.01
}

function objectiveAllocationGapText(objective) {
  const delta = objectiveAllocatedTotal(objective.id) - Number(objective.weight || 0)
  if (Math.abs(delta) <= 0.01) {
    return '已对齐'
  }
  return delta > 0 ? `超出 ${formatScore(delta)}` : `需补 ${formatScore(Math.abs(delta))}`
}

function normalizeContentMappingRows() {
  return contentRows.value.map((row) => ({
    objectiveId: row.objectiveId,
    values: { ...(row.values || {}) }
  }))
}

async function loadContentMapping() {
  if (!filters.courseId || !filters.semester) {
    contentObjectives.value = []
    contentColumns.value = []
    contentRows.value = []
    return
  }
  const data = await getAchievementContentMapping(filters)
  contentObjectives.value = data.objectives || []
  contentColumns.value = data.contents || []
  contentRows.value = data.rows || []
}

async function saveContentMapping() {
  if (!filters.courseId || !filters.semester) {
    setMessage('error', '请先选择课程和学期。')
    return
  }
  mappingSaving.value = true
  try {
    const data = await saveAchievementContentMapping({
      courseId: filters.courseId,
      semester: filters.semester,
      rows: normalizeContentMappingRows()
    })
    await loadContentMapping()
    setMessage('success', `分配矩阵已保存，共写入 ${data.count || 0} 条分值配置。`)
  } catch (error) {
    setMessage('error', error.message || '分配矩阵保存失败。')
  } finally {
    mappingSaving.value = false
  }
}

async function loadPage() {
  try {
    const data = await getAchievementCalculation(filters)
    catalogs.courses = data.courses
    catalogs.semesters = data.semesters
    catalogs.classes = data.classes || []
    filters.courseId = data.currentCourseId
    filters.semester = data.currentSemester
    filters.classId = data.currentClassId ? String(data.currentClassId) : (filters.classId || '')
    currentOutlineId.value = data.outlineId
    objectives.value = data.objectives || []
    applyRecord(data.record)
    await loadContentMapping()
  } catch (error) {
    setMessage('error', error.message || '达成度核算数据读取失败。')
  }
}

async function runCalculation() {
  running.value = true
  try {
    const payload = {
      courseId: filters.courseId,
      semester: filters.semester,
      classId: filters.classId || undefined,
      calcMethod: record.config.calcMethod,
      thresholdValue: record.config.thresholdValue,
      passThreshold: record.config.passThreshold,
      contentMappingRows: normalizeContentMappingRows()
    }
    if (record.config.calcMethod === 'custom') {
      payload.customThresholds = record.config.customThresholds
      payload.retakeEnabled = record.config.retakeEnabled
    }
    const data = await runAchievementCalculation(payload)
    currentOutlineId.value = data.outlineId
    objectives.value = data.objectives || []
    applyRecord(data.record)
    await loadContentMapping()
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

.content-map-block-stack {
  display: grid;
  gap: 16px;
}

.content-map-block {
  border: 1px solid #dfeaf0;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.content-map-block-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
  padding: 14px 16px;
  border-bottom: 1px solid #e8f0f4;
  background: #f8fbfd;
}

.content-map-block-head h3 {
  margin: 0;
  color: var(--color-primary-deep);
  font-size: 16px;
}

.content-map-block-head p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  font-size: 13px;
  line-height: 1.5;
}

.content-map-block-summary {
  display: grid;
  justify-items: end;
  gap: 6px;
  flex: 0 0 auto;
}

.content-map-block-summary small {
  color: var(--color-text-soft);
  font-size: 12px;
}

.content-map-shell {
  border: 0;
  border-radius: 0;
}

.content-map-table {
  min-width: 860px;
}

.content-map-table th,
.content-map-table td {
  white-space: nowrap;
}

.content-map-table tfoot td {
  font-weight: 700;
  background: #f8fbfd;
}

.content-map-total-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.content-map-total-strip div {
  padding: 12px;
  border: 1px solid #cfe2ea;
  border-radius: 8px;
  background: #fbfdfe;
}

.content-map-total-strip div.warning {
  border-color: #f2d39b;
  background: #fffaf0;
}

.content-map-total-strip span,
.content-map-total-strip strong,
.content-map-total-strip small {
  display: block;
}

.content-map-total-strip span {
  color: var(--color-text-soft);
  font-size: 13px;
}

.content-map-total-strip strong {
  margin-top: 6px;
  color: var(--color-primary-deep);
  font-size: 18px;
}

.content-map-total-strip small {
  margin-top: 4px;
  color: var(--color-text-soft);
  font-size: 12px;
}

.matrix-input {
  width: 96px;
  padding: 6px 8px;
}

.method-hint {
  font-size: 13px;
  line-height: 1.6;
}

.analysis-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.7fr);
  gap: 18px;
  align-items: stretch;
}

.analysis-main,
.weak-panel,
.chart-block,
.report-analysis {
  border: 1px solid #e3edf2;
  border-radius: 8px;
  background: #fbfdfe;
}

.analysis-main {
  padding: 18px;
}

.analysis-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--color-text);
}

.risk-badge {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
}

.risk-badge.success {
  color: #166534;
  background: #dcfce7;
}

.risk-badge.warning,
.risk-badge.info {
  color: #92400e;
  background: #fef3c7;
}

.risk-badge.danger {
  color: #991b1b;
  background: #fee2e2;
}

.analysis-summary-text {
  margin: 14px 0 0;
  color: var(--color-text);
  line-height: 1.8;
}

.analysis-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.analysis-columns h3,
.weak-panel h3,
.chart-title {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--color-text);
}

.analysis-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-soft);
  line-height: 1.7;
}

.weak-panel {
  display: grid;
  gap: 18px;
  padding: 18px;
}

.weak-list {
  display: grid;
  gap: 10px;
}

.weak-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 10px 0;
  border-bottom: 1px solid #e8f0f4;
}

.weak-row:last-child {
  border-bottom: 0;
}

.weak-row strong,
.weak-row small {
  display: block;
}

.weak-row small {
  margin-top: 4px;
  color: var(--color-text-soft);
  line-height: 1.5;
}

.weak-row span {
  font-weight: 700;
  color: var(--color-primary);
}

.empty-inline {
  padding: 12px;
  border: 1px dashed #d8e6ec;
  border-radius: 8px;
  color: var(--color-text-soft);
  background: #fff;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.chart-block {
  padding: 14px;
  overflow: hidden;
}

.export-section {
  display: grid;
  gap: 16px;
}

.meta-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.export-desc {
  margin: 0;
  color: var(--color-text-soft);
  line-height: 1.8;
}

.report-analysis {
  display: grid;
  gap: 10px;
  padding: 14px;
}

.report-analysis p {
  margin: 0;
  color: var(--color-text-soft);
  line-height: 1.8;
}

.report-actions {
  display: grid;
  gap: 8px;
}

.report-actions span {
  padding: 8px 10px;
  border-left: 3px solid var(--color-primary);
  background: #fff;
  color: var(--color-text);
  line-height: 1.6;
}

.export-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.page-bottom-actions {
  display: flex;
  justify-content: flex-end;
  padding: 4px 0 12px;
}

.mt-8 {
  margin-top: 8px;
}

@media (max-width: 980px) {
  .analysis-layout,
  .analysis-columns,
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
