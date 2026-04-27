<template>
  <div ref="pageRoot" class="app-page page-stack">
    <ModuleHeader
      title="教学目标管理"
      description="智能解析导入会基于真实上传的课程大纲文件提取课程目标与考核项，教师可以在写入前逐条复核、修改和补充。"
    >
      <template #actions>
        <button class="btn btn-light" @click="createObjective">手工新增目标</button>
      </template>
    </ModuleHeader>

    <div class="step-row">
      <div class="step-card" :class="{ active: currentStep >= 1 }">
        <div class="step-index">1</div>
        <strong class="mt-12">上传文件</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 2 }">
        <div class="step-index">2</div>
        <strong class="mt-12">智能解析</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 3 }">
        <div class="step-index">3</div>
        <strong class="mt-12">复核草稿</strong>
      </div>
      <div class="step-card" :class="{ active: currentStep >= 4 }">
        <div class="step-index">4</div>
        <strong class="mt-12">确认写入</strong>
      </div>
    </div>

    <div
      v-if="message.text && ['error', 'success-dialog'].includes(message.type)"
      ref="feedbackDialog"
      class="feedback-dialog"
      :class="message.type === 'error' ? 'feedback-error' : 'feedback-success'"
      :role="message.type === 'error' ? 'alertdialog' : 'status'"
      aria-live="assertive"
      tabindex="-1"
    >
      <div class="feedback-dialog-panel">
        <div>
          <strong>{{ message.type === 'error' ? '还有内容需要处理' : '操作已完成' }}</strong>
          <p>{{ message.text }}</p>
        </div>
        <button class="btn btn-light btn-mini" @click="clearMessage">知道了</button>
      </div>
    </div>

    <div v-if="message.text && !['error', 'success-dialog'].includes(message.type)" class="notice" :class="message.type">
      {{ message.text }}
    </div>

    <PanelCard
      title="上传解析文件"
      subtitle="支持上传 doc、docx、pdf 格式课程大纲。系统会依据章节标题、编号和关键词生成可编辑草稿。"
    >
      <div class="form-grid-2">
        <div class="form-field">
          <label>学期</label>
          <select v-model="form.semester" class="select-input">
            <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">
              {{ semester }}
            </option>
          </select>
        </div>
      </div>

      <div class="mt-16 info-strip">
        <div>当前文件：{{ selectedFileName || '尚未选择文件' }}</div>
        <div>支持格式：doc、docx、pdf，文件大小不超过 20MB</div>
      </div>

      <div class="actions-inline mt-16">
        <input type="file" accept=".pdf,.doc,.docx" @change="handleFileChange" />
        <button class="btn btn-secondary" :disabled="uploading" @click="handleUpload">
          {{ uploading ? '正在创建任务...' : '开始智能解析' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard
      v-if="task"
      title="解析任务状态"
      :subtitle="`任务编号：${task.taskId}，文件：${task.fileName || selectedFileName}`"
    >
      <div class="info-strip">
        <div>当前状态：{{ taskStatusLabel(task.status) }}</div>
        <div>提取目标：{{ task.objectives?.length || task.objExtractCount || 0 }}</div>
        <div>提取考核项：{{ task.assessItems?.length || task.assessExtractCount || 0 }}</div>
        <div>提取映射：{{ task.mappingSuggestions?.length || task.mappingExtractCount || 0 }}</div>
      </div>

      <div v-if="task.status === 'FAILED'" class="notice error mt-16">
        {{ task.errorMessage || '解析失败，请检查文件内容后重新上传。' }}
      </div>
    </PanelCard>

    <PanelCard
      v-if="showReviewPanel"
      title="课程信息确认"
      subtitle="系统已从文件中提取到以下课程基本信息，请选择处理方式并确认字段值后再写入正式数据。"
    >
      <div class="form-field mt-0">
        <label>处理方式</label>
        <div class="radio-group">
          <label class="radio-label">
            <input v-model="courseImportMode" type="radio" value="overwrite" />
            选择性覆盖当前课程字段
          </label>
          <label class="radio-label">
            <input v-model="courseImportMode" type="radio" value="new" />
            以提取信息新建课程
          </label>
        </div>
      </div>

      <template v-if="courseImportMode === 'overwrite'">
        <div class="form-field mt-16">
          <label>选择要覆盖的课程</label>
          <select v-model="overwriteTargetCourseId" class="select-input" data-confirm-target-course>
            <option value="">— 请选择课程 —</option>
            <option v-for="course in catalogs.courses" :key="course.id" :value="course.id">
              {{ course.name }}（{{ course.code }}）
            </option>
          </select>
        </div>

        <div class="course-info-grid grid-4col mt-16">
          <span class="grid-header muted">覆盖</span>
          <span class="grid-header">字段</span>
          <span class="grid-header">提取值（可编辑）</span>
          <span class="grid-header">当前值</span>
          <div v-for="field in courseFields" :key="field.key" class="grid-row-contents">
            <span class="grid-cell check-cell" :class="{ 'cell-selected': overwriteFields.has(field.key) }">
              <input
                type="checkbox"
                :checked="overwriteFields.has(field.key)"
                @change="toggleOverwriteField(field.key)"
              />
            </span>
            <span class="grid-cell field-label" :class="{ 'cell-selected': overwriteFields.has(field.key) }">
              {{ field.label }}
            </span>
            <span class="grid-cell field-parsed" :class="{ 'cell-selected': overwriteFields.has(field.key) }">
              <input
                v-model="parsedCourseEdits[field.key]"
                class="text-input cell-input"
                :placeholder="field.label"
                :data-course-input="field.key"
              />
            </span>
            <span class="grid-cell field-current" :class="{ 'cell-selected': overwriteFields.has(field.key) }">
              {{ formatCourseValue(selectedTargetCourse?.[field.catalogKey]) || '—' }}
            </span>
          </div>
        </div>
      </template>

      <template v-if="courseImportMode === 'new'">
        <div class="course-info-grid grid-2col mt-16">
          <span class="grid-header">字段</span>
          <span class="grid-header">提取值（可编辑）</span>
          <div v-for="field in courseFields" :key="field.key" class="grid-row-contents">
            <span class="grid-cell field-label">{{ field.label }}</span>
            <span class="grid-cell field-parsed">
              <input
                v-model="parsedCourseEdits[field.key]"
                class="text-input cell-input"
                :placeholder="field.label"
                :data-course-input="field.key"
                :class="{ 'input-required': field.key === 'courseCode' && !parsedCourseEdits.courseCode }"
              />
            </span>
          </div>
        </div>

        <div v-if="!parsedCourseEdits.courseCode" class="notice warning mt-16">
          新建课程时，课程代码为必填项。如提取值为空，请在上方手工填写后再写入。
        </div>
      </template>

      <label v-if="courseImportMode === 'overwrite'" class="mt-16 overwrite-option">
        <input v-model="overwriteExisting" type="checkbox" />
        覆盖所选课程本学期已有目标和考核项
      </label>
    </PanelCard>

    <PanelCard
      v-if="showReviewPanel"
      title="大纲扩展提取内容"
      subtitle="教学内容、成绩政策、考核要求和评价标准均可修改；确认写入前会一并保存。"
    >
      <template #actions>
        <button class="btn btn-light btn-mini" @click="addTeachingContent">新增教学内容</button>
        <button class="btn btn-light btn-mini" @click="addAssessmentDetail">新增考核要求</button>
        <button class="btn btn-primary btn-mini" @click="saveParsedCourseInfo()">保存扩展内容</button>
      </template>

      <div class="editable-section">
        <div class="section-head">
          <h3 class="section-subtitle">教学内容与学时安排</h3>
          <button class="btn btn-light btn-mini" @click="addTeachingContent">新增</button>
        </div>
        <div v-if="parsedTeachingContents.length" class="table-shell">
          <table class="data-table compact-table editable-table">
            <thead>
              <tr>
                <th>课程内容</th>
                <th>讲授</th>
                <th>实践</th>
                <th>教学方式</th>
                <th>涉及目标</th>
                <th>基本要求</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, index) in parsedTeachingContents" :key="index">
                <td><textarea v-model.trim="item.title" class="text-area mini-area" /></td>
                <td><input v-model="item.lectureHours" class="text-input compact-input number-input" type="number" min="0" step="0.5" /></td>
                <td><input v-model="item.practiceHours" class="text-input compact-input number-input" type="number" min="0" step="0.5" /></td>
                <td><input v-model.trim="item.teachingMethod" class="text-input compact-input" /></td>
                <td><input v-model.trim="item.relatedObjectives" class="text-input compact-input" /></td>
                <td><textarea v-model.trim="item.requirements" class="text-area mini-area" /></td>
                <td><button class="btn btn-danger btn-mini" @click="removeTeachingContent(index)">删除</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="notice warning">暂无教学内容，可点击新增补充。</div>
      </div>

      <div class="editable-section mt-16">
        <h3 class="section-subtitle">成绩记载与考核政策</h3>
        <div class="policy-grid editable-policy-grid">
          <div>
            <span>成绩记载方式</span>
            <input v-model.trim="parsedAssessmentPolicy.scoreRecordMode" class="text-input compact-input" />
          </div>
          <div>
            <span>最终成绩组成</span>
            <textarea v-model.trim="parsedAssessmentPolicy.finalGradeComposition" class="text-area mini-area" />
          </div>
          <div>
            <span>考核方式</span>
            <input v-model.trim="parsedAssessmentPolicy.assessmentMode" class="text-input compact-input" />
          </div>
          <div>
            <span>是否设置补考</span>
            <input v-model.trim="parsedAssessmentPolicy.makeupExam" class="text-input compact-input" />
          </div>
        </div>
      </div>

      <div class="editable-section mt-16">
        <div class="section-head">
          <h3 class="section-subtitle">考核要求与成绩评定</h3>
          <button class="btn btn-light btn-mini" @click="addAssessmentDetail">新增</button>
        </div>
        <div v-if="parsedAssessmentDetails.length" class="table-shell">
          <table class="data-table compact-table editable-table">
            <thead>
              <tr>
                <th>考核方式</th>
                <th>权重</th>
                <th>考核内容</th>
                <th>评价办法</th>
                <th>支撑要求</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, index) in parsedAssessmentDetails" :key="index">
                <td><input v-model.trim="item.name" class="text-input compact-input" /></td>
                <td><input v-model="item.weight" class="text-input compact-input number-input" type="number" min="0" max="100" step="0.01" /></td>
                <td><textarea v-model.trim="item.content" class="text-area mini-area" /></td>
                <td><textarea v-model.trim="item.evaluationMethod" class="text-area mini-area" /></td>
                <td><textarea v-model.trim="item.supports" class="text-area mini-area" /></td>
                <td><button class="btn btn-danger btn-mini" @click="removeAssessmentDetail(index)">删除</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="notice warning">暂无考核要求，可点击新增补充。</div>
      </div>

      <div class="editable-section mt-16">
        <div class="section-head">
          <h3 class="section-subtitle">考核与评价标准片段</h3>
          <button class="btn btn-light btn-mini" @click="addAssessmentStandard">新增</button>
        </div>
        <div v-if="parsedAssessmentStandards.length" class="standard-list">
          <div v-for="(item, index) in parsedAssessmentStandards" :key="index" class="standard-editor">
            <textarea v-model.trim="parsedAssessmentStandards[index]" class="text-area" />
            <button class="btn btn-danger btn-mini" @click="removeAssessmentStandard(index)">删除</button>
          </div>
        </div>
        <div v-else class="notice warning">暂无评价标准片段，可点击新增补充。</div>
      </div>
    </PanelCard>

    <div v-if="showReviewPanel" class="split-panel">
      <PanelCard title="原文片段" subtitle="左侧展示系统抓取的原文，右侧展示可编辑草稿，便于逐条核对。">
        <div class="detail-list">
          <div v-for="segment in task.originalSections" :key="segment.id" class="source-segment">
            <strong>{{ segment.label }}</strong>
            <p>{{ segment.text }}</p>
          </div>
        </div>
      </PanelCard>

      <div class="page-stack">
        <PanelCard title="目标草稿复核" subtitle="支持修改目标编号、内容、类型、权重，也支持标记为待定、忽略或直接删除。">
          <div class="actions-inline">
            <button class="btn btn-light" @click="addObjectiveDraft">新增目标草稿</button>
            <div class="muted">已确认目标权重合计：{{ confirmedObjectiveWeight.toFixed(2) }}</div>
          </div>

          <div class="detail-list mt-16">
            <div v-for="item in task.objectives" :key="item.id" class="draft-card" :class="objectiveStatusClass(item)">
              <div class="achievement-row">
                <div class="code-row">
                  <input v-model.trim="item.objCodeSuggest" class="text-input code-input" @blur="saveObjectiveDraft(item)" />
                  <StatusBadge :text="confidenceLabel(item.confidenceLevel)" :tone="confidenceTone(item.confidenceLevel)" />
                  <StatusBadge
                    class="review-status-badge"
                    :text="confirmStatusLabel(item.isConfirmed)"
                    :tone="confirmStatusTone(item.isConfirmed)"
                  />
                </div>
                <button class="btn btn-danger btn-mini" @click="removeObjectiveDraft(item)">删除</button>
              </div>

              <div class="form-field mt-12">
                <label>目标内容</label>
                <textarea v-model.trim="item.objContentFinal" class="text-area" @blur="saveObjectiveDraft(item)" />
              </div>

              <div class="form-grid-2 mt-12">
                <div class="form-field">
                  <label>目标类型</label>
                  <select v-model="item.objTypeFinal" class="select-input" @change="saveObjectiveDraft(item)">
                    <option :value="1">知识</option>
                    <option :value="2">能力</option>
                    <option :value="3">素养</option>
                  </select>
                </div>
                <div class="form-field">
                  <label>权重</label>
                  <input
                    v-model="item.weightFinal"
                    class="text-input"
                    type="number"
                    min="0"
                    max="100"
                    step="0.01"
                    @blur="saveObjectiveDraft(item)"
                  />
                </div>
              </div>

              <div class="form-grid-2 mt-12">
                <div class="form-field">
                  <label>支撑毕业要求</label>
                  <input
                    v-model.trim="item.gradReqIdFinal"
                    class="text-input"
                    placeholder="如 3.1"
                    @blur="saveObjectiveDraft(item)"
                  />
                </div>
                <div class="form-field">
                  <label>关联程度</label>
                  <select v-model="item.relationLevelFinal" class="select-input" @change="saveObjectiveDraft(item)">
                    <option value="">待定</option>
                    <option value="H">H</option>
                    <option value="M">M</option>
                    <option value="L">L</option>
                  </select>
                </div>
              </div>

              <div class="form-field mt-12">
                <label>毕业要求简述</label>
                <textarea v-model.trim="item.gradReqDescFinal" class="text-area" @blur="saveObjectiveDraft(item)" />
              </div>

              <div class="source-tip mt-12">
                <strong>原文：</strong>{{ item.originalText || '教师手工补充' }}
              </div>

              <div class="actions-inline mt-12">
                <button class="btn btn-light" @click="setObjectiveStatus(item, 0)">待定</button>
                <button class="btn btn-success" @click="setObjectiveStatus(item, 1)">确认</button>
                <button class="btn btn-danger" @click="setObjectiveStatus(item, 2)">忽略</button>
              </div>
            </div>
          </div>
        </PanelCard>

        <PanelCard title="考核项草稿复核" subtitle="系统会优先从考核方式和成绩构成中提取考核项，也支持手工补充。">
          <div class="actions-inline">
            <button class="btn btn-light" @click="addAssessDraft">新增考核项</button>
            <div class="muted">已确认考核项权重合计：{{ confirmedAssessWeight.toFixed(2) }}</div>
          </div>

          <div class="table-shell mt-16">
            <table class="data-table">
              <thead>
                <tr>
                  <th>考核项名称</th>
                  <th>类型</th>
                  <th>权重</th>
                  <th>置信度</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in task.assessItems" :key="item.id">
                  <td>
                    <input v-model.trim="item.itemNameFinal" class="text-input compact-input" @blur="saveAssessDraft(item)" />
                  </td>
                  <td>
                    <select v-model="item.itemTypeFinal" class="select-input compact-input" @change="saveAssessDraft(item)">
                      <option value="normal">平时</option>
                      <option value="mid">期中</option>
                      <option value="final">期末</option>
                      <option value="practice">实践</option>
                      <option value="report">报告</option>
                    </select>
                  </td>
                  <td>
                    <input
                      v-model="item.weightFinal"
                      class="text-input compact-input"
                      type="number"
                      min="0"
                      max="100"
                      step="0.01"
                      @blur="saveAssessDraft(item)"
                    />
                  </td>
                  <td>
                    <StatusBadge :text="confidenceLabel(item.confidenceLevel)" :tone="confidenceTone(item.confidenceLevel)" />
                  </td>
                  <td>
                    <StatusBadge
                      class="review-status-badge table-status-badge"
                      :text="confirmStatusLabel(item.isConfirmed)"
                      :tone="confirmStatusTone(item.isConfirmed)"
                    />
                  </td>
                  <td class="actions-cell">
                    <button class="btn btn-light btn-mini" @click="setAssessStatus(item, 0)">待定</button>
                    <button class="btn btn-success btn-mini" @click="setAssessStatus(item, 1)">确认</button>
                    <button class="btn btn-danger btn-mini" @click="removeAssessDraft(item)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </PanelCard>

        <PanelCard
          title="目标考核映射草稿复核"
          subtitle="同一条目标的各考核方式占比排列在同一行，支持手工修改后保存。写入时将同步生成映射矩阵。"
        >
          <template v-if="task.mappingMatrix?.rows?.length">
            <div class="table-shell">
              <table class="data-table mapping-table">
                <thead>
                  <tr>
                    <th>目标编号</th>
                    <th v-for="(name, i) in task.mappingMatrix.methodNames" :key="name">
                      {{ name }}
                      <span class="method-weight-hint">
                        （总成绩 {{ assessWeightForMethod(name, task.mappingMatrix.methodTypes?.[i]) }}%）
                      </span>
                    </th>
                    <th>占总成绩比例</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in task.mappingMatrix.rows" :key="row.objectiveCode">
                    <td class="obj-code-cell">{{ row.objectiveCode }}</td>
                    <td v-for="(pct, idx) in row.proportions" :key="idx">
                      <input
                        :value="pct"
                        class="text-input compact-input mapping-pct-input"
                        type="number"
                        min="0"
                        max="100"
                        step="0.01"
                        @input="updateMappingCell(row, idx, $event.target.value)"
                        @blur="saveMappingMatrix"
                      />
                    </td>
                    <td class="metric">
                      {{ computeRowTotal(row).toFixed(2) }}%
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="muted mt-8" style="font-size:12px">
              各列之和应等于 100%（每种考核方式的权重应全部分配到各目标中）
            </div>
          </template>
          <div v-else class="notice warning">
            暂未识别到目标考核映射表，写入后仍可在"目标考核映射"页面手动维护。
          </div>
        </PanelCard>

        <PanelCard title="确认写入" subtitle="全部复核完成后，可将已确认的目标和考核项一键写入正式数据表。">
          <div class="info-strip">
            <div>已确认目标：{{ confirmedObjectiveCount }} 条</div>
            <div>已确认考核项：{{ confirmedAssessCount }} 条</div>
            <div>待写入映射：{{ task.mappingMatrix?.rows?.length || 0 }} 条目标</div>
          </div>

          <div class="actions-inline mt-16">
            <button class="btn btn-primary" @click="confirmTask">确认并写入系统</button>
          </div>
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  confirmParseTask,
  createParseAssessDraft,
  createParseDraft,
  deleteParseAssessDraft,
  deleteParseDraft,
  getParseTaskDetail,
  getReferenceCatalogs,
  updateParseAssessDraft,
  updateParseCourseInfo,
  updateParseDraft,
  updateParseMappingMatrix,
  uploadParseFile
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'

const router = useRouter()

const catalogs = reactive({
  courses: [],
  semesters: []
})

const form = reactive({
  semester: ''
})

const task = ref(null)
const uploading = ref(false)
const selectedFile = ref(null)
const selectedFileName = ref('')
const overwriteExisting = ref(false)
const courseImportMode = ref('overwrite')
const overwriteTargetCourseId = ref('')
const overwriteFields = reactive(new Set())
const parsedCourseEdits = reactive({})
const message = reactive({
  type: 'success',
  text: ''
})
const pageRoot = ref(null)
const feedbackDialog = ref(null)

const courseFields = [
  { key: 'courseCode',        catalogKey: 'code',               label: '课程代码' },
  { key: 'courseNameZh',      catalogKey: 'name',               label: '课程名称（中文）' },
  { key: 'courseNameEn',      catalogKey: 'nameEn',             label: '课程名称（英文）' },
  { key: 'courseType',        catalogKey: 'courseType',         label: '课程类型' },
  { key: 'targetStudents',    catalogKey: 'targetStudents',     label: '授课对象' },
  { key: 'teachingLanguage',  catalogKey: 'teachingLanguage',   label: '授课语言' },
  { key: 'collegeName',       catalogKey: 'collegeName',        label: '开课院系' },
  { key: 'hours',             catalogKey: 'hours',              label: '学时' },
  { key: 'credits',           catalogKey: 'credits',            label: '学分' },
  { key: 'prerequisiteCourse', catalogKey: 'prerequisiteCourse', label: '先修课程' },
  { key: 'courseOwner',       catalogKey: 'courseOwner',        label: '课程负责人' }
]

let pollingTimer = null
let feedbackTimer = null

const currentStep = computed(() => {
  if (!task.value) return 1
  if (task.value.status === 'PARSING') return 2
  if (task.value.status === 'CONFIRMED') return 4
  return 3
})

const showReviewPanel = computed(() => task.value && ['DONE', 'CONFIRMED'].includes(task.value.status))
const parsedCourse = computed(() => ensureParsedCourseShape())
const parsedTeachingContents = computed(() => parsedCourse.value.teachingContents)
const parsedAssessmentDetails = computed(() => parsedCourse.value.assessmentDetails)
const parsedAssessmentStandards = computed(() => parsedCourse.value.assessmentStandards)
const parsedAssessmentPolicy = computed(() => parsedCourse.value.assessmentPolicy)

const selectedTargetCourse = computed(() =>
  catalogs.courses.find((c) => String(c.id) === String(overwriteTargetCourseId.value)) || null
)

const confirmedObjectiveWeight = computed(() =>
  (task.value?.objectives || [])
    .filter((item) => Number(item.isConfirmed) === 1)
    .reduce((sum, item) => sum + Number(item.weightFinal || 0), 0)
)

const confirmedAssessWeight = computed(() =>
  (task.value?.assessItems || [])
    .filter((item) => Number(item.isConfirmed) === 1)
    .reduce((sum, item) => sum + Number(item.weightFinal || 0), 0)
)

const confirmedObjectiveCount = computed(() =>
  (task.value?.objectives || []).filter((item) => Number(item.isConfirmed) === 1).length
)

const confirmedAssessCount = computed(() =>
  (task.value?.assessItems || []).filter((item) => Number(item.isConfirmed) === 1).length
)

function assessWeightForMethod(methodName, methodType) {
  const assessItems = task.value?.assessItems || []
  const item = assessItems.find(
    (a) => (methodType && a.itemTypeFinal === methodType) || a.itemNameFinal === methodName
  )
  return item ? Number(item.weightFinal || 0) : 0
}

function objectiveWeightForMappingRow(row) {
  const objectives = task.value?.objectives || []
  const byCode = objectives.find((obj) => obj.objCodeSuggest === row.objectiveCode)
  if (byCode) return Number(byCode.weightFinal || 0)

  const objectiveNumber = Number(row.objectiveNumber || 0)
  if (objectiveNumber >= 1 && objectiveNumber <= objectives.length) {
    return Number(objectives[objectiveNumber - 1].weightFinal || 0)
  }

  return null
}

function computeRowTotal(row) {
  const objectiveWeight = objectiveWeightForMappingRow(row)
  if (objectiveWeight !== null) return objectiveWeight

  const matrix = task.value?.mappingMatrix
  if (!matrix?.methodNames?.length) return row.totalWeight ?? 0
  let total = 0
  let hasAnyWeight = false
  for (let i = 0; i < matrix.methodNames.length && i < row.proportions.length; i++) {
    const w = assessWeightForMethod(matrix.methodNames[i], matrix.methodTypes?.[i])
    if (w > 0) hasAnyWeight = true
    total += (row.proportions[i] / 100) * w
  }
  return hasAnyWeight ? total : (row.totalWeight ?? 0)
}

function syncMappingRowTotals() {
  const rows = task.value?.mappingMatrix?.rows || []
  rows.forEach((row) => {
    const objectiveWeight = objectiveWeightForMappingRow(row)
    if (objectiveWeight !== null) {
      row.totalWeight = round2(objectiveWeight)
    }
  })
}

function updateMappingCell(row, idx, rawValue) {
  const val = parseFloat(rawValue)
  if (!isNaN(val)) {
    row.proportions[idx] = val
  }
}

async function saveMappingMatrix() {
  if (!task.value?.mappingMatrix || !task.value?.taskId) return
  try {
    syncMappingRowTotals()
    await updateParseMappingMatrix(task.value.taskId, task.value.mappingMatrix)
  } catch (error) {
    setMessage('error', error.message || '映射矩阵保存失败。')
  }
}

function setMessage(type, text) {
  clearFeedbackTimer()
  message.type = type
  message.text = text
  if (['error', 'success-dialog'].includes(type) && text) {
    focusFeedbackDialog()
    feedbackTimer = window.setTimeout(() => {
      clearMessage()
    }, 3000)
  }
}

function clearMessage() {
  clearFeedbackTimer()
  message.text = ''
}

function clearFeedbackTimer() {
  if (feedbackTimer) {
    window.clearTimeout(feedbackTimer)
    feedbackTimer = null
  }
}

async function focusFeedbackDialog() {
  await nextTick()
  feedbackDialog.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  feedbackDialog.value?.focus({ preventScroll: true })
}

async function setFieldError(text, selector) {
  message.type = 'error'
  message.text = text
  await nextTick()

  const target = pageRoot.value?.querySelector(selector)
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    window.setTimeout(() => target.focus?.({ preventScroll: true }), 260)
    return
  }

  focusFeedbackDialog()
}

function createObjective() {
  router.push({
    path: '/objectives/edit',
    query: { semester: form.semester }
  })
}

function emptyParsedCourseInfo() {
  return {
    teachingContents: [],
    assessmentDetails: [],
    assessmentStandards: [],
    assessmentPolicy: {
      scoreRecordMode: '',
      finalGradeComposition: '',
      assessmentMode: '',
      makeupExam: ''
    }
  }
}

function ensureParsedCourseShape() {
  if (!task.value) {
    return emptyParsedCourseInfo()
  }

  if (!task.value.parsedCourse || typeof task.value.parsedCourse !== 'object') {
    task.value.parsedCourse = {}
  }

  const course = task.value.parsedCourse
  if (!Array.isArray(course.teachingContents)) {
    course.teachingContents = []
  }
  if (!Array.isArray(course.assessmentDetails)) {
    course.assessmentDetails = []
  }
  if (!Array.isArray(course.assessmentStandards)) {
    course.assessmentStandards = []
  }
  if (!course.assessmentPolicy || typeof course.assessmentPolicy !== 'object' || Array.isArray(course.assessmentPolicy)) {
    course.assessmentPolicy = {}
  }

  if (course.assessmentPolicy.scoreRecordMode == null) {
    course.assessmentPolicy.scoreRecordMode = ''
  }
  if (course.assessmentPolicy.finalGradeComposition == null) {
    course.assessmentPolicy.finalGradeComposition = ''
  }
  if (course.assessmentPolicy.assessmentMode == null) {
    course.assessmentPolicy.assessmentMode = ''
  }
  if (course.assessmentPolicy.makeupExam == null) {
    course.assessmentPolicy.makeupExam = ''
  }
  return course
}

function initParsedCourseEdits(parsedCourse) {
  courseFields.forEach((field) => {
    parsedCourseEdits[field.key] = parsedCourse?.[field.key] != null ? String(parsedCourse[field.key]) : ''
  })
}

function addTeachingContent() {
  parsedTeachingContents.value.push({
    title: '',
    lectureHours: '',
    practiceHours: '',
    teachingMethod: '',
    relatedObjectives: '',
    requirements: ''
  })
}

function removeTeachingContent(index) {
  parsedTeachingContents.value.splice(index, 1)
}

function addAssessmentDetail() {
  parsedAssessmentDetails.value.push({
    name: '',
    weight: '',
    content: '',
    evaluationMethod: '',
    supports: ''
  })
}

function removeAssessmentDetail(index) {
  parsedAssessmentDetails.value.splice(index, 1)
}

function addAssessmentStandard() {
  parsedAssessmentStandards.value.push('')
}

function removeAssessmentStandard(index) {
  parsedAssessmentStandards.value.splice(index, 1)
}

function buildSubmittedCourseInfo() {
  const course = ensureParsedCourseShape()
  const baseInfo = courseFields.reduce((result, field) => {
    result[field.key] = parsedCourseEdits[field.key] ?? ''
    return result
  }, {})

  return {
    ...course,
    ...baseInfo,
    teachingContents: parsedTeachingContents.value.map((item) => ({
      title: item.title || '',
      lectureHours: item.lectureHours ?? '',
      practiceHours: item.practiceHours ?? '',
      teachingMethod: item.teachingMethod || '',
      relatedObjectives: item.relatedObjectives || '',
      requirements: item.requirements || ''
    })),
    assessmentDetails: parsedAssessmentDetails.value.map((item) => ({
      name: item.name || '',
      weight: item.weight ?? '',
      content: item.content || '',
      evaluationMethod: item.evaluationMethod || '',
      supports: item.supports || ''
    })),
    assessmentStandards: parsedAssessmentStandards.value.map((item) => String(item || '')),
    assessmentPolicy: {
      scoreRecordMode: parsedAssessmentPolicy.value.scoreRecordMode || '',
      finalGradeComposition: parsedAssessmentPolicy.value.finalGradeComposition || '',
      assessmentMode: parsedAssessmentPolicy.value.assessmentMode || '',
      makeupExam: parsedAssessmentPolicy.value.makeupExam || ''
    }
  }
}

async function saveParsedCourseInfo(successText = '扩展提取内容已保存。', throwOnError = false) {
  if (!task.value?.taskId) return buildSubmittedCourseInfo()

  const courseInfo = buildSubmittedCourseInfo()
  try {
    const result = await updateParseCourseInfo(task.value.taskId, courseInfo)
    task.value.parsedCourse = result.parsedCourse || courseInfo
    ensureParsedCourseShape()
    initParsedCourseEdits(task.value.parsedCourse)
    if (successText) {
      setMessage('success', successText)
    }
    return task.value.parsedCourse
  } catch (error) {
    setMessage('error', error.message || '扩展提取内容保存失败。')
    if (throwOnError) {
      throw error
    }
    return courseInfo
  }
}

function toggleOverwriteField(key) {
  if (overwriteFields.has(key)) {
    overwriteFields.delete(key)
  } else {
    overwriteFields.add(key)
  }
}

function formatCourseValue(value) {
  if (value === null || value === undefined || value === '') return ''
  return String(value)
}

function confidenceTone(level) {
  if (level === 'HIGH') return 'success'
  if (level === 'MEDIUM') return 'warning'
  return 'danger'
}

function confidenceLabel(level) {
  if (level === 'HIGH') return '高'
  if (level === 'MEDIUM') return '中'
  return '低'
}

function confirmStatusTone(status) {
  if (Number(status) === 1) return 'success'
  if (Number(status) === 2) return 'danger'
  return 'warning'
}

function taskStatusLabel(status) {
  if (status === 'PARSING') return '解析中'
  if (status === 'DONE') return '待复核'
  if (status === 'CONFIRMED') return '已写入'
  if (status === 'FAILED') return '解析失败'
  return status || '--'
}

function confirmStatusLabel(status) {
  if (Number(status) === 1) return '已确认'
  if (Number(status) === 2) return '已忽略'
  return '待定'
}

function objectiveStatusClass(item) {
  const status = Number(item.isConfirmed)
  if (status === 1) return 'draft-confirmed'
  if (status === 2) return 'draft-ignored'
  return 'draft-pending'
}

function handleFileChange(event) {
  const file = event.target.files?.[0] || null
  selectedFile.value = file
  selectedFileName.value = file ? file.name : ''
}

function stopPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

async function pollTask(taskId) {
  const data = await getParseTaskDetail(taskId)
  task.value = data
  ensureParsedCourseShape()

  if (data.status === 'DONE') {
    stopPolling()
    initParsedCourseEdits(data.parsedCourse)
    return
  }

  if (data.status === 'FAILED') {
    stopPolling()
  }
}

async function refreshTask(successText = '') {
  if (!task.value?.taskId) return
  await pollTask(task.value.taskId)
  if (successText) {
    setMessage('success', successText)
  }
}

async function handleUpload() {
  if (!selectedFile.value) {
    setMessage('error', '请先选择需要解析的课程大纲文件。')
    return
  }

  const lowerName = selectedFile.value.name.toLowerCase()
  if (!['.doc', '.docx', '.pdf'].some((ext) => lowerName.endsWith(ext))) {
    setMessage('error', '仅支持上传 doc、docx、pdf 格式文件。')
    return
  }

  if (selectedFile.value.size > 20 * 1024 * 1024) {
    setMessage('error', '上传文件不能超过 20MB。')
    return
  }

  uploading.value = true
  setMessage('', '')

  try {
    const created = await uploadParseFile({
      file: selectedFile.value,
      semester: form.semester
    })

    task.value = {
      taskId: created.taskId,
      status: created.status,
      fileName: selectedFileName.value,
      objectives: [],
      assessItems: [],
      mappingSuggestions: [],
      originalSections: []
    }

    if (created.status === 'FAILED') {
      setMessage('error', created.message || '解析失败，请检查文件内容。')
      return
    }

    setMessage('success', '解析任务已创建，系统正在加载解析结果。')
    stopPolling()
    pollingTimer = window.setInterval(() => pollTask(created.taskId), 1000)
    await pollTask(created.taskId)

    if (task.value?.status === 'DONE') {
      setMessage('success', '智能解析已完成，请复核目标草稿和考核项草稿。')
    }
  } catch (error) {
    setMessage('error', error.message || '解析任务创建失败。')
  } finally {
    uploading.value = false
  }
}

async function saveObjectiveDraft(item, successText = '') {
  try {
    await updateParseDraft(item.id, {
      objCodeSuggest: item.objCodeSuggest,
      objContentFinal: item.objContentFinal,
      objTypeFinal: Number(item.objTypeFinal),
      weightFinal: Number(item.weightFinal),
      gradReqIdFinal: item.gradReqIdFinal || '',
      gradReqDescFinal: item.gradReqDescFinal || '',
      relationLevelFinal: item.relationLevelFinal || '',
      isConfirmed: Number(item.isConfirmed)
    })
    syncMappingRowTotals()
    if (successText) {
      setMessage('success', successText)
    }
  } catch (error) {
    setMessage('error', error.message || '目标草稿保存失败。')
    throw error
  }
}

async function setObjectiveStatus(item, status) {
  const previousStatus = Number(item.isConfirmed)
  item.isConfirmed = status

  try {
    await saveObjectiveDraft(item, `目标已标记为${confirmStatusLabel(status)}。`)
    await refreshTask()
    await renumberObjectives()
  } catch (error) {
    item.isConfirmed = previousStatus
  }
}

async function addObjectiveDraft() {
  if (!task.value) return

  try {
    await createParseDraft(task.value.taskId, {
      objCodeSuggest: `OBJ-${(task.value.objectives?.length || 0) + 1}`,
      objContentFinal: '',
      objTypeFinal: 1,
      weightFinal: 0,
      gradReqIdFinal: '',
      gradReqDescFinal: '',
      relationLevelFinal: '',
      isConfirmed: 0,
      confidenceLevel: 'LOW',
      confidenceScore: 0.5,
      originalText: '教师手工补充'
    })
    await refreshTask('已新增目标草稿。')
  } catch (error) {
    setMessage('error', error.message || '新增目标草稿失败。')
  }
}

async function removeObjectiveDraft(item) {
  const shouldRebalanceWeights = shouldAutoAverageObjectiveWeights(item)
  try {
    await deleteParseDraft(item.id)
    await refreshTask('目标草稿已删除。')
    await renumberObjectives()
    if (shouldRebalanceWeights) {
      await rebalanceObjectiveWeights()
    }
  } catch (error) {
    setMessage('error', error.message || '删除目标草稿失败。')
  }
}

async function renumberObjectives() {
  if (!task.value?.objectives) return
  const objPattern = /^OBJ[-_]?(\d+)$/i
  const active = task.value.objectives.filter(
    (obj) => Number(obj.isConfirmed) !== 2 && objPattern.test(obj.objCodeSuggest)
  )
  let seq = 1
  for (const obj of active) {
    const newCode = `OBJ-${seq++}`
    if (obj.objCodeSuggest !== newCode) {
      obj.objCodeSuggest = newCode
      await saveObjectiveDraft(obj)
    }
  }
}

function objectiveActiveItems() {
  return (task.value?.objectives || []).filter((obj) => Number(obj.isConfirmed) !== 2)
}

function evenWeights(count) {
  if (count <= 0) return []
  const values = []
  let sum = 0
  for (let index = 0; index < count; index++) {
    const value = round2(100 / count)
    values.push(value)
    sum += value
  }
  values[count - 1] = round2(values[count - 1] + round2(100 - sum))
  return values
}

function round2(value) {
  return Math.round((Number(value) + Number.EPSILON) * 100) / 100
}

function shouldAutoAverageObjectiveWeights(deletedItem) {
  if (Number(deletedItem.isConfirmed) === 2) return false
  const active = objectiveActiveItems()
  if (active.length <= 1) return false
  if (active.some(hasObjectiveWeightEvidence)) return false
  const expected = evenWeights(active.length)
  return active.every((obj, index) => Math.abs(Number(obj.weightFinal || 0) - expected[index]) <= 0.02)
}

function hasObjectiveWeightEvidence(obj) {
  const text = `${obj.originalText || ''} ${obj.objContentFinal || ''}`
  return /[%％]/.test(text) || /权重|比例|占总成绩/.test(text)
}

async function rebalanceObjectiveWeights() {
  const active = objectiveActiveItems()
  const weights = evenWeights(active.length)
  for (let index = 0; index < active.length; index++) {
    const obj = active[index]
    const newWeight = weights[index]
    if (Math.abs(Number(obj.weightFinal || 0) - newWeight) > 0.001) {
      obj.weightFinal = newWeight
      await saveObjectiveDraft(obj)
    }
  }
}

async function saveAssessDraft(item, successText = '') {
  try {
    await updateParseAssessDraft(item.id, {
      itemNameFinal: item.itemNameFinal,
      itemTypeFinal: item.itemTypeFinal,
      weightFinal: Number(item.weightFinal),
      isConfirmed: Number(item.isConfirmed)
    })
    if (successText) {
      setMessage('success', successText)
    }
  } catch (error) {
    setMessage('error', error.message || '考核项草稿保存失败。')
    throw error
  }
}

async function setAssessStatus(item, status) {
  const previousStatus = Number(item.isConfirmed)
  item.isConfirmed = status

  try {
    await saveAssessDraft(item, `考核项已标记为${confirmStatusLabel(status)}。`)
    await refreshTask()
  } catch (error) {
    item.isConfirmed = previousStatus
  }
}

async function addAssessDraft() {
  if (!task.value) return

  try {
    await createParseAssessDraft(task.value.taskId, {
      itemNameFinal: `考核项${(task.value.assessItems?.length || 0) + 1}`,
      itemTypeFinal: 'normal',
      weightFinal: 0,
      isConfirmed: 0,
      confidenceLevel: 'LOW',
      confidenceScore: 0.5,
      originalText: '教师手工补充'
    })
    await refreshTask('已新增考核项草稿。')
  } catch (error) {
    setMessage('error', error.message || '新增考核项失败。')
  }
}

async function removeAssessDraft(item) {
  try {
    await deleteParseAssessDraft(item.id)
    await refreshTask('考核项草稿已删除。')
  } catch (error) {
    setMessage('error', error.message || '删除考核项失败。')
  }
}

async function saveAllReviewDrafts() {
  const objectives = task.value?.objectives || []
  const assessItems = task.value?.assessItems || []

  for (const item of objectives) {
    await saveObjectiveDraft(item)
  }
  for (const item of assessItems) {
    await saveAssessDraft(item)
  }
  await saveMappingMatrix()
}

async function confirmTask() {
  if (!task.value) return

  if (confirmedObjectiveCount.value === 0) {
    setMessage('error', '至少需要确认 1 条课程目标才能写入。')
    return
  }

  if (Math.abs(confirmedObjectiveWeight.value - 100) > 0.01) {
    setMessage('error', `已确认目标权重合计为 ${confirmedObjectiveWeight.value.toFixed(2)}，必须等于 100。`)
    return
  }

  if (
    task.value.assessItems?.some((item) => Number(item.isConfirmed) === 1) &&
    Math.abs(confirmedAssessWeight.value - 100) > 0.01
  ) {
    setMessage('error', `已确认考核项权重合计为 ${confirmedAssessWeight.value.toFixed(2)}，必须等于 100。`)
    return
  }

  if (courseImportMode.value === 'overwrite' && !overwriteTargetCourseId.value) {
    setFieldError('请先在课程信息确认面板中选择要覆盖的课程。', '[data-confirm-target-course]')
    return
  }

  if (courseImportMode.value === 'new' && !parsedCourseEdits.courseCode?.trim()) {
    setFieldError('新建课程时课程代码不能为空，请在课程信息确认面板中填写后再写入。', '[data-course-input="courseCode"]')
    return
  }

  try {
    await saveAllReviewDrafts()
    const submittedCourseInfo = await saveParsedCourseInfo('', true)
    const result = await confirmParseTask(task.value.taskId, {
      outlineId: task.value.outlineId,
      overwrite: courseImportMode.value === 'overwrite' && overwriteExisting.value,
      courseImportMode: courseImportMode.value,
      targetCourseId: courseImportMode.value === 'overwrite' ? overwriteTargetCourseId.value : null,
      overwriteCourseFields: courseImportMode.value === 'overwrite' ? [...overwriteFields] : [],
      courseInfo: submittedCourseInfo
    })

    await refreshTask()
    if (task.value) {
      task.value.status = 'CONFIRMED'
    }
    setMessage('success-dialog', `写入成功：新增 ${result.importedObjectives} 条目标，写入 ${result.importedAssessItems} 个考核项，生成 ${result.importedMappings || 0} 条映射。`)
  } catch (error) {
    setMessage('error', error.message || '写入失败，请检查权重和确认状态。')
  }
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses
  catalogs.semesters = data.semesters
  form.semester = data.semesters[0] || ''
}

onMounted(loadCatalogs)
onBeforeUnmount(() => {
  stopPolling()
  clearFeedbackTimer()
})
</script>

<style scoped>
.split-panel {
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(0, 1.3fr);
  gap: 16px;
}

.feedback-dialog {
  position: sticky;
  top: 12px;
  z-index: 20;
  outline: none;
}

.feedback-dialog-panel {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid rgba(229, 62, 62, 0.35);
  border-left: 4px solid var(--color-danger, #e53e3e);
  border-radius: var(--radius-md);
  background: #fff7f7;
  color: var(--color-text);
  box-shadow: var(--shadow-card);
}

.feedback-success .feedback-dialog-panel {
  border-color: rgba(56, 161, 105, 0.35);
  border-left-color: var(--color-success, #38a169);
  background: #f3fff8;
  box-shadow: var(--shadow-card);
}

.feedback-error .feedback-dialog-panel {
  border-color: rgba(229, 62, 62, 0.35);
  border-left-color: var(--color-danger, #e53e3e);
  background: #fff7f7;
}

.feedback-dialog-panel strong {
  display: block;
  font-size: 16px;
}

.feedback-dialog-panel p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  line-height: 1.6;
}

.source-segment,
.draft-card {
  padding: 14px;
  border-radius: var(--radius-md);
  border: 1px solid #e6eef2;
  background: #fbfdfe;
}

.draft-card {
  position: relative;
  overflow: hidden;
  border-left: 4px solid #d8e5ec;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.draft-card:hover {
  box-shadow: var(--shadow-soft);
}

.draft-pending {
  border-left-color: #d69e2e;
  background: #fffdf7;
}

.draft-confirmed {
  border-left-color: #38a169;
  background: #fbfffd;
}

.draft-ignored {
  border-left-color: #e53e3e;
  background: #fffafa;
}

.source-segment p {
  margin: 10px 0 0;
  line-height: 1.8;
  color: var(--color-text-soft);
}

.code-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.review-status-badge {
  min-height: 30px;
  padding: 7px 14px;
  font-size: 14px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.65);
}

.table-status-badge {
  white-space: nowrap;
}

.code-input {
  width: 120px;
}

.compact-input {
  min-width: 110px;
}

.btn-mini {
  padding: 8px 12px;
  font-size: 12px;
}

.actions-cell {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.source-tip {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-soft);
}

.section-subtitle {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--color-text);
}

.compact-table th,
.compact-table td {
  vertical-align: top;
}

.editable-section {
  display: grid;
  gap: 10px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.editable-table {
  min-width: 980px;
}

.editable-table td {
  min-width: 120px;
}

.mini-area {
  min-height: 64px;
  padding: 8px 10px;
  font-size: 13px;
}

.number-input {
  min-width: 76px;
}

.standard-list {
  display: grid;
  gap: 8px;
}

.standard-editor {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
}

.standard-line {
  padding: 10px 12px;
  border: 1px solid #e6eef2;
  border-radius: 8px;
  background: #fbfdfe;
  color: var(--color-text-soft);
  line-height: 1.6;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.policy-grid > div {
  padding: 10px 12px;
  border: 1px solid #e6eef2;
  border-radius: 8px;
  background: #fbfdfe;
}

.editable-policy-grid > div {
  display: grid;
  gap: 6px;
}

.policy-grid span {
  display: block;
  margin-bottom: 4px;
  color: var(--color-text-muted, #718096);
  font-size: 12px;
}

.policy-grid strong {
  display: block;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

@media (max-width: 1080px) {
  .split-panel {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .policy-grid {
    grid-template-columns: 1fr;
  }

  .standard-editor {
    grid-template-columns: 1fr;
  }
}

.radio-group {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 8px;
}

.radio-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  cursor: pointer;
}

.overwrite-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid #d7e3ea;
  border-radius: var(--radius-sm);
  background: #fbfdfe;
  color: var(--color-text);
  font-weight: 600;
  cursor: pointer;
}

.course-info-grid {
  display: grid;
  gap: 0;
  border: 1px solid #e6eef2;
  border-radius: var(--radius-md);
  overflow: hidden;
  font-size: 13px;
}

.grid-4col {
  grid-template-columns: 32px 1fr 1.2fr 1fr;
}

.grid-2col {
  grid-template-columns: 1fr 1.5fr;
}

.grid-row-contents {
  display: contents;
}

.grid-header {
  padding: 10px 14px;
  background: #f4f7fa;
  font-weight: 600;
  color: var(--color-text-soft);
  border-bottom: 1px solid #e6eef2;
}

.grid-cell {
  padding: 8px 14px;
  border-bottom: 1px solid #f0f4f8;
  display: flex;
  align-items: center;
}

.grid-row-contents:last-child .grid-cell {
  border-bottom: none;
}

.cell-selected {
  background: #f0f8ff;
}

.check-cell {
  justify-content: center;
}

.field-label {
  font-weight: 500;
}

.field-current {
  color: var(--color-text-soft);
}

.cell-input {
  width: 100%;
  font-size: 13px;
  padding: 4px 8px;
  min-width: 0;
}

.input-required {
  border-color: var(--color-danger, #e53e3e);
}

.btn-success {
  background: #38a169;
  color: #fff;
  border-color: #38a169;
}

.btn-success:hover:not(:disabled) {
  background: #2f855a;
  border-color: #2f855a;
}

.mapping-table th,
.mapping-table td {
  white-space: nowrap;
}

.mapping-table th:first-child,
.mapping-table td:first-child {
  white-space: normal;
}

.obj-code-cell {
  font-weight: 600;
  min-width: 64px;
}

.mapping-pct-input {
  min-width: 72px;
  max-width: 90px;
}

.method-weight-hint {
  display: block;
  font-size: 11px;
  font-weight: 400;
  color: var(--color-text-muted, #888);
  margin-top: 2px;
}
</style>
