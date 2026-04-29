<template>
  <div class="app-page page-stack">
    <ModuleHeader
      title="学生成绩管理"
      description="大纲内已有信息只读确认，大纲外的考核内容、成绩导入和手动录入在这里完成。"
    />

    <div v-if="message.text && message.type !== 'success-dialog'" class="notice" :class="message.type">{{ message.text }}</div>

    <PanelCard title="查询条件">
      <div class="form-grid-4">
        <div class="form-field">
          <label>课程</label>
          <select v-model="filter.courseId" class="select-input">
            <option value="">请选择课程</option>
            <option v-for="course in catalogs.courses" :key="course.id" :value="String(course.id)">
              {{ course.name }}（{{ course.code }}）
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>学期</label>
          <select v-model="filter.semester" class="select-input">
            <option value="">请选择学期</option>
            <option v-for="semester in catalogs.semesters" :key="semester" :value="semester">{{ semester }}</option>
          </select>
        </div>
        <div class="form-field">
          <label>班级</label>
          <select v-model="filter.classId" class="select-input">
            <option value="">全部班级</option>
            <option v-for="item in catalogs.classes" :key="item.id" :value="String(item.id)">
              {{ item.className }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>考核项</label>
          <select v-model="filter.assessItemId" class="select-input">
            <option value="">全部考核项</option>
            <option v-for="item in assessmentBlocks" :key="item.id" :value="String(item.id)">
              {{ item.itemName }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label>学生</label>
          <input v-model.trim="filter.keyword" class="text-input" placeholder="学号或姓名" />
        </div>
      </div>
      <div class="actions-inline mt-16">
        <button class="btn btn-light" @click="resetFilter">重置</button>
      </div>
    </PanelCard>

    <PanelCard v-if="canUseCourse" title="开课配置总览" subtitle="系统展示已从教学大纲提取的信息，教师只补充考核内容、目标分值分配和成绩数据。">
      <div class="workflow-steps">
        <div v-for="step in workflowSteps" :key="step.key" class="workflow-step" :class="step.tone">
          <span>{{ step.index }}</span>
          <div>
            <strong>{{ step.title }}</strong>
            <small>{{ step.desc }}</small>
          </div>
        </div>
      </div>

      <div class="config-overview-grid mt-16">
        <section class="overview-card course-overview-card">
          <div class="overview-card-head">
            <span class="step-kicker">步骤0</span>
            <strong>大纲已提取信息</strong>
          </div>
          <div class="course-name-line">{{ currentCourse?.name || selectedCourseText }}</div>
          <div class="meta-grid compact-meta-grid">
            <div>
              <span>课程代码</span>
              <strong>{{ currentCourse?.code || '--' }}</strong>
            </div>
            <div>
              <span>学时</span>
              <strong>{{ currentCourse?.hours ?? '--' }}</strong>
            </div>
            <div>
              <span>学分</span>
              <strong>{{ currentCourse?.credits ?? '--' }}</strong>
            </div>
            <div>
              <span>开课单位</span>
              <strong>{{ currentCourse?.collegeName || currentCourse?.department || '--' }}</strong>
            </div>
          </div>
        </section>

        <section class="overview-card objective-overview-card">
          <div class="overview-card-head">
            <span class="step-kicker">只读确认</span>
            <strong>课程目标</strong>
            <em>{{ formatNumber(objectiveWeightTotal) }}%</em>
          </div>
          <div v-if="!objectives.length" class="empty-inline">当前课程学期尚未维护课程目标。</div>
          <div v-else class="objective-mini-list">
            <div v-for="objective in objectives" :key="objective.id" class="objective-mini-row">
              <div>
                <strong>{{ objective.objCode }}</strong>
                <small>{{ objective.objContent }}</small>
              </div>
              <span>{{ formatNumber(objective.weight) }}%</span>
            </div>
          </div>
        </section>

        <section class="overview-card readiness-card">
          <div class="overview-card-head">
            <span class="step-kicker">配置状态</span>
            <strong>当前进度</strong>
          </div>
          <div class="readiness-list">
            <div>
              <span>考核项权重</span>
              <strong>{{ formatNumber(assessmentWeightTotal) }}%</strong>
            </div>
            <div>
              <span>考核内容</span>
              <strong>{{ configuredContentCount }} 项</strong>
            </div>
            <div>
              <span>已导入表</span>
              <strong>{{ importTables.length }} 个</strong>
            </div>
            <div>
              <span>学生成绩</span>
              <strong>{{ total }} 人</strong>
            </div>
          </div>
        </section>
      </div>
    </PanelCard>

    <PanelCard title="步骤1：配置考核内容及方式" subtitle="按平时作业、实验项目、考核等小板块维护考核内容；每块右侧直接导入对应成绩。">
      <div v-if="!canUseCourse" class="notice info">请选择课程和学期。</div>
      <div v-else-if="loadingContents" class="notice info">正在加载考核内容...</div>
      <div v-else-if="!assessmentBlocks.length" class="notice warning">当前课程学期尚未配置考核项。</div>

      <div v-else class="assessment-block-grid">
        <section v-for="block in visibleAssessmentBlocks" :key="block.id" class="assessment-block">
          <header class="block-head">
            <div>
              <h3>{{ block.itemName }}</h3>
              <p>{{ block.itemTypeName || '考核项' }} · 考核项权重 {{ formatNumber(block.weight) }}</p>
            </div>
            <div class="block-head-actions">
              <span class="weight-pill" :class="{ warning: !isBlockWeightOk(block) }">
                内容合计 {{ formatNumber(block.contentWeight) }}
              </span>
              <button class="btn btn-light btn-mini" @click="openContentDialog(block)">编辑</button>
            </div>
          </header>

          <div class="table-shell content-preview-shell">
            <table class="data-table compact-table content-preview-table">
              <thead>
                <tr>
                  <th>序号</th>
                  <th>考核内容</th>
                  <th>类型</th>
                  <th>权重</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="content in block.contents" :key="content.id">
                  <td class="mono">{{ content.contentNo }}</td>
                  <td>{{ content.contentName }}</td>
                  <td>{{ content.contentTypeName }}</td>
                  <td class="metric">{{ formatNumber(content.weight) }}</td>
                </tr>
                <tr v-if="!(block.contents || []).length">
                  <td colspan="4" class="muted">暂无考核内容。</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="block-import">
            <input type="file" accept=".xlsx,.xls,.csv" @change="handleBlockFileChange(block.id, $event)" />
            <button class="btn btn-secondary" :disabled="uploadingAssessItemId === block.id" @click="handleUploadBlock(block)">
              {{ uploadingAssessItemId === block.id ? '导入中...' : '导入该项成绩' }}
            </button>
          </div>
        </section>
      </div>
    </PanelCard>

    <PanelCard v-if="canUseCourse" title="步骤2：课程目标分值分配" subtitle="按课程目标查看考核内容分配状态；具体分值矩阵在达成度核算页维护。">
      <template #actions>
        <router-link class="btn btn-secondary" to="/analysis/calculation">前往分值分配</router-link>
      </template>
      <div v-if="!objectives.length" class="notice info">暂无课程目标，完成大纲解析确认后可配置目标分值。</div>
      <div v-else class="mapping-card-grid">
        <section v-for="objective in objectives" :key="objective.id" class="mapping-card">
          <header>
            <div>
              <h3>{{ objective.objCode }}</h3>
              <p>{{ objective.objContent }}</p>
            </div>
            <span class="weight-pill" :class="{ warning: !isObjectiveAllocationOk(objective) }">
              {{ formatNumber(objectiveAllocatedTotal(objective.id)) }} / {{ formatNumber(objective.weight) }}
            </span>
          </header>
          <div v-if="objectiveMappedContents(objective.id).length" class="mapping-chip-list">
            <span v-for="item in objectiveMappedContents(objective.id)" :key="`${objective.id}-${item.contentId}`">
              {{ item.name }} · {{ formatNumber(item.score) }}
            </span>
          </div>
          <div v-else class="empty-inline">尚未分配考核内容。</div>
        </section>
      </div>
    </PanelCard>

    <PanelCard v-if="batch" title="步骤3：成绩导入复核">
      <div class="info-strip">
        <div>文件：{{ batch.fileName || '—' }}</div>
        <div>状态：{{ batchStatusLabel(batch.status) }}</div>
        <div>有效行：{{ batch.validRows || 0 }}</div>
        <div>异常行：{{ batch.errorRows || 0 }}</div>
      </div>

      <div v-if="invalidPreviewRows.length" class="notice warning import-warning mt-16">
        <strong>有 {{ invalidPreviewRows.length }} 行存在缺失或异常成绩，确认导入时只会写入有效成绩。</strong>
        <div class="import-warning-list">
          <span v-for="(line, index) in visibleInvalidPreviewLines" :key="`${index}-${line}`">{{ line }}</span>
          <span v-if="invalidPreviewOverflow">还有 {{ invalidPreviewOverflow }} 行，请在预览表中继续查看。</span>
        </div>
      </div>

      <div v-if="batch.status === 'PARSED'" class="table-shell grade-preview-shell mt-16">
        <table class="data-table grade-preview-table">
          <thead>
            <tr>
              <th class="sticky-col row-col">行号</th>
              <th class="sticky-col student-col">学号</th>
              <th class="sticky-col name-col">姓名</th>
              <th v-for="column in batch.previewColumns || []" :key="column.columnKey" class="score-col">
                <span>{{ column.assessItemName }}</span>
                <small>{{ columnScaleLabel(column) }}</small>
              </th>
              <th class="status-col">状态</th>
              <th class="action-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in batch.previewRows || []" :key="`${row.row}-${row.studentNo}`" :class="{ 'invalid-row': !row.valid }">
              <td class="sticky-col row-col">{{ row.row }}</td>
              <td class="sticky-col student-col">
                <input v-model.trim="row.studentNo" class="text-input preview-input student-input" />
              </td>
              <td class="sticky-col name-col">
                <input v-model.trim="row.studentName" class="text-input preview-input name-input" />
              </td>
              <td v-for="cell in row.cells" :key="cell.columnKey" class="score-cell">
                <input
                  v-if="cell.assessContentId"
                  v-model="cell.rawScore"
                  class="text-input preview-input score-input"
                  :class="{ invalid: !cell.valid }"
                  type="number"
                  min="0"
                  :max="cell.rawMaxScore || 100"
                  step="0.01"
                />
                <input
                  v-else
                  v-model="cell.score"
                  class="text-input preview-input score-input"
                  :class="{ invalid: !cell.valid }"
                  type="number"
                  min="0"
                  :max="scoreInputMax(cell)"
                  step="0.01"
                />
                <small v-if="cell.assessContentId" class="score-converted">
                  折算 {{ formatScore(convertedPreviewScore(cell)) }} / {{ formatNumber(cell.convertedMaxScore || cell.maxScore) }}
                </small>
                <small v-if="!cell.valid" class="cell-error">{{ cell.errorMsg }}</small>
              </td>
              <td class="status-col">
                <span v-if="!row.valid" class="status-text-error">异常</span>
                <span v-else class="metric">有效</span>
              </td>
              <td class="action-col">
                <button class="btn btn-secondary btn-compact" :disabled="savingRow === row.row" @click="savePreviewRow(row)">
                  {{ savingRow === row.row ? '保存中...' : '保存修改' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="batch.status === 'PARSED'" class="actions-inline confirm-import-actions mt-16">
        <div v-if="overwriteableDuplicateCount" class="score-mode-toggle" role="group" aria-label="重复成绩处理方式">
          <button
            v-for="option in confirmImportModeOptions"
            :key="option.value"
            type="button"
            class="mode-toggle-btn"
            :class="{ active: confirmImportMode === option.value }"
            @click="confirmImportMode = option.value"
          >
            {{ option.label }}
          </button>
        </div>
        <span v-if="overwriteableDuplicateCount" class="duplicate-count">可覆盖 {{ overwriteableDuplicateCount }} 条重复成绩</span>
        <button class="btn btn-danger" :disabled="discardingBatch" @click="discardPendingBatch(false)">
          {{ discardingBatch ? '丢弃中...' : '丢弃本次导入' }}
        </button>
        <button class="btn btn-primary" @click="confirmImport">
          {{ confirmImportMode === 'overwrite' ? '覆盖并确认导入' : '确认导入有效数据' }}
        </button>
      </div>
    </PanelCard>

    <PanelCard v-if="canUseCourse && importTables.length" title="已导入成绩明细">
      <template #subtitle>
        <span class="muted">每个导入文件单独成表，原始分按表格列满分保存，折算分按考核内容权重计算。</span>
      </template>
      <template #actions>
        <div class="score-mode-toggle" role="group" aria-label="成绩明细显示">
          <button
            v-for="option in importScoreModeOptions"
            :key="option.value"
            type="button"
            class="mode-toggle-btn"
            :class="{ active: importScoreMode === option.value }"
            @click="importScoreMode = option.value"
          >
            {{ option.label }}
          </button>
        </div>
      </template>

      <div class="import-table-stack">
        <section v-for="table in importTables" :key="table.batchNo" class="import-table-card">
          <header class="import-table-head">
            <div>
              <h3>{{ table.fileName }}</h3>
              <p>{{ table.assessItemName }} · {{ table.confirmedAt || table.createdAt }}</p>
            </div>
            <span class="weight-pill">学生 {{ table.total || 0 }}</span>
          </header>

          <div class="table-shell import-detail-shell">
            <table class="data-table import-detail-table">
              <thead>
                <tr>
                  <th class="sticky-col seq-col">序号</th>
                  <th class="sticky-col student-col">学号</th>
                  <th class="sticky-col name-col">姓名</th>
                  <th
                    v-for="column in importDisplayColumns(table)"
                    :key="column.key"
                    class="score-col"
                    :class="column.className"
                  >
                    <span>{{ column.assessItemName }}</span>
                    <small>{{ column.label }}</small>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in table.rows" :key="`${table.batchNo}-${row.studentNo}`">
                  <td class="sticky-col seq-col muted">{{ idx + 1 }}</td>
                  <td class="sticky-col student-col mono">{{ row.studentNo }}</td>
                  <td class="sticky-col name-col">{{ row.studentName }}</td>
                  <td
                    v-for="cell in importDisplayCells(row)"
                    :key="cell.key"
                    class="score-col metric"
                    :class="cell.className"
                  >
                    {{ formatScore(cell.value) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </PanelCard>

    <PanelCard title="步骤4：学生成绩总表">
      <template #subtitle>
        <span v-if="canUseCourse">
          共 <strong>{{ total }}</strong> 名学生
          <span v-if="loading" class="muted">（更新中...）</span>
        </span>
        <span v-else class="muted">请选择课程和学期</span>
      </template>
      <template #actions>
        <button class="btn btn-primary" :disabled="!canEditGrades" @click="startAddRow">手动录入</button>
      </template>

      <div v-if="!canUseCourse" class="notice info">请选择课程和学期后查看成绩。</div>
      <div v-else-if="loading && !rows.length" class="notice info">正在加载成绩...</div>
      <div v-else-if="!loading && !rows.length" class="notice warning">未查询到成绩数据。</div>

      <div v-if="draftRow" class="editor-card mt-16">
        <div class="editor-title">{{ draftMode === 'create' ? '新增学生成绩' : '编辑学生成绩' }}</div>
        <div class="form-grid-2 mt-12">
          <div class="form-field">
            <label>学号</label>
            <input v-model.trim="draftRow.studentNo" class="text-input" :disabled="draftMode === 'edit'" />
          </div>
          <div class="form-field">
            <label>姓名</label>
            <input v-model.trim="draftRow.studentName" class="text-input" />
          </div>
        </div>
        <div class="score-editor-grid mt-12">
          <div v-for="cell in draftCells" :key="cell.columnKey" class="form-field score-editor-cell">
            <label>{{ cell.assessItemName }}</label>
            <input
              v-if="cell.assessContentId"
              v-model="cell.rawScore"
              class="text-input"
              type="number"
              min="0"
              :max="cell.rawMaxScore || 100"
              step="0.01"
              :placeholder="`原始分 / ${formatNumber(cell.rawMaxScore || 100)}`"
            />
            <input
              v-else
              v-model="cell.score"
              class="text-input"
              type="number"
              min="0"
              :max="scoreInputMax(cell)"
              step="0.01"
              placeholder="成绩"
            />
            <small>{{ cell.parentAssessItemName || '考核项' }} · {{ columnScaleLabel(cell) }}</small>
          </div>
        </div>
        <div class="actions-inline mt-16">
          <button class="btn btn-secondary" :disabled="saving" @click="saveDraftRow">
            {{ saving ? '保存中...' : '保存' }}
          </button>
          <button class="btn btn-light" @click="cancelDraft">取消</button>
        </div>
      </div>

      <div v-if="canUseCourse && rows.length" class="table-shell grade-manage-shell mt-16">
        <table class="data-table grade-manage-table">
          <thead>
            <tr>
              <th class="sticky-col seq-col">序号</th>
              <th class="sticky-col student-col">学号</th>
              <th class="sticky-col name-col">姓名</th>
              <th v-for="column in componentColumns" :key="column.columnKey" class="score-col">
                <span>{{ column.assessItemName }}</span>
                <small>{{ column.parentAssessItemName }} · 权重 {{ formatNumber(column.maxScore) }}</small>
              </th>
              <th v-for="column in summaryColumns" :key="column.columnKey" class="score-col summary-score-col">
                <span>{{ column.assessItemName }}</span>
                <small>汇总</small>
              </th>
              <th class="score-col total-col">总成绩</th>
              <th class="score-col level-col">等级</th>
              <th class="action-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, idx) in rows" :key="row.studentNo">
              <td class="sticky-col seq-col muted">{{ idx + 1 }}</td>
              <td class="sticky-col student-col mono">{{ row.studentNo }}</td>
              <td class="sticky-col name-col">{{ row.studentName }}</td>
              <td v-for="cell in row.componentCells" :key="cell.columnKey" class="score-col metric">
                {{ formatScore(cell.score) }}
              </td>
              <td v-for="cell in row.summaryCells" :key="cell.columnKey" class="score-col metric summary-score-col">
                {{ formatScore(cell.score) }}
              </td>
              <td class="score-col metric total-col">{{ formatScore(row.totalScore) }}</td>
              <td class="score-col level-col">{{ row.gradeLevel || '--' }}</td>
              <td class="action-col">
                <button class="btn btn-light btn-compact" @click="startEditRow(row)">编辑</button>
                <button class="btn btn-danger btn-compact" @click="deleteRow(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <div v-if="message.text && message.type === 'success-dialog'" class="modal-backdrop" @click.self="closeFeedbackDialog">
      <section class="modal-panel feedback-panel" role="dialog" aria-modal="true" aria-label="成绩导入提醒">
        <header class="modal-head">
          <div>
            <h2>成绩导入成功</h2>
            <p>已更新下方“已导入成绩明细”和“学生成绩总表”。</p>
          </div>
          <button class="btn btn-light btn-mini" @click="closeFeedbackDialog">关闭</button>
        </header>
        <div class="modal-body feedback-body">
          <div class="notice success">导入已完成，请核对下方成绩明细。</div>
          <div v-if="feedbackLines.length" class="feedback-lines">
            <p v-for="(line, index) in feedbackLines" :key="`${index}-${line}`">{{ line }}</p>
          </div>
        </div>
      </section>
    </div>

    <div v-if="contentDialog.open" class="modal-backdrop" @click.self="closeContentDialog">
      <section class="modal-panel content-modal-panel" role="dialog" aria-modal="true" aria-label="考核内容及方式表">
        <header class="modal-head">
          <div>
            <h2>{{ contentDialog.blockName || '考核内容及方式表' }}</h2>
            <p>{{ selectedCourseText }} / {{ filter.semester }} · 考核项权重 {{ formatNumber(contentDialog.blockWeight) }}</p>
          </div>
          <button class="btn btn-light btn-mini" @click="closeContentDialog">关闭</button>
        </header>
        <div class="modal-body content-modal-body">
          <div v-if="contentDialogMessage.text" class="notice" :class="contentDialogMessage.type">
            {{ contentDialogMessage.text }}
          </div>

          <PanelCard title="内容维护">
            <template #actions>
              <button class="btn btn-light" @click="addContentRow">新增考核内容</button>
            </template>

            <div class="table-shell content-edit-shell">
              <table class="data-table compact-table content-edit-table">
                <thead>
                  <tr>
                    <th>序号</th>
                    <th>考核内容</th>
                    <th>类型</th>
                    <th>权重</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in visibleContentDrafts" :key="item.__rowKey">
                    <td>
                      <input v-model.trim="item.contentNo" class="text-input seq-input" />
                    </td>
                    <td>
                      <input v-model.trim="item.contentName" class="text-input content-name-input" />
                    </td>
                    <td>
                      <select v-model="item.contentType" class="select-input type-select">
                        <option value="assignment">作业</option>
                        <option value="experiment">实验</option>
                        <option value="exam">考核</option>
                      </select>
                    </td>
                    <td>
                      <input v-model="item.weight" class="text-input weight-input" type="number" min="0" step="0.01" />
                    </td>
                    <td>
                      <button class="btn btn-danger btn-mini" @click="removeContentRow(item)">删除</button>
                    </td>
                  </tr>
                  <tr v-if="!visibleContentDrafts.length">
                    <td colspan="5" class="muted">当前考核项暂无考核内容。</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </PanelCard>

          <div v-if="editingAssessmentBlock" class="content-weight-summary single">
            <div :class="{ warning: !isDraftWeightOk(editingAssessmentBlock) }">
              <span>{{ editingAssessmentBlock.itemName }}</span>
              <strong>
                {{ formatNumber(draftWeightByAssessItem(editingAssessmentBlock.id)) }} /
                {{ formatNumber(editingAssessmentBlock.weight) }}
              </strong>
            </div>
          </div>

          <div class="modal-footer-actions">
            <span class="muted">当前考核项下的内容权重合计应等于该考核项权重。</span>
            <button class="btn btn-primary" :disabled="savingContents" @click="saveContents">
              {{ savingContents ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  confirmGradeBatch,
  discardGradeBatch,
  deleteImportedGradeRow,
  getAssessmentContents,
  getAchievementContentMapping,
  getGradeBatchPreview,
  getImportedGrades,
  getReferenceCatalogs,
  saveAssessmentContents,
  saveImportedGradeRow,
  updateGradePreviewRow,
  uploadGradeFile
} from '@/api'
import ModuleHeader from '@/components/common/ModuleHeader.vue'
import PanelCard from '@/components/common/PanelCard.vue'
import { confirmFeedback, showFeedback } from '@/utils/feedback'

const catalogs = reactive({ courses: [], semesters: [], classes: [] })
const filter = reactive({ courseId: '', semester: '', classId: '', assessItemId: '', keyword: '' })
const assessmentBlocks = ref([])
const objectives = ref([])
const contentMappingContents = ref([])
const contentMappingRows = ref([])
const summaryColumns = ref([])
const componentColumns = ref([])
const importTables = ref([])
const importScoreMode = ref('all')
const confirmImportMode = ref('valid_only')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadingContents = ref(false)
const saving = ref(false)
const savingContents = ref(false)
const uploadingAssessItemId = ref(null)
const savingRow = ref(null)
const discardingBatch = ref(false)
const draftRow = ref(null)
const draftMode = ref('')
const batch = ref(null)
const selectedFiles = reactive({})
const contentDrafts = ref([])
const contentDialog = reactive({ open: false, assessItemId: '', blockName: '', blockWeight: 0 })
const contentDialogMessage = reactive({ type: 'info', text: '' })
const message = reactive({ type: 'info', text: '' })
const importScoreModeOptions = [
  { value: 'all', label: '全部分数' },
  { value: 'raw', label: '原始分数' },
  { value: 'converted', label: '折算后分数' }
]
const confirmImportModeOptions = [
  { value: 'valid_only', label: '跳过重复' },
  { value: 'overwrite', label: '覆盖已有' }
]
let keywordTimer = null
let pollingTimer = null

const canUseCourse = computed(() => Boolean(filter.courseId && filter.semester))
const canEditGrades = computed(() => canUseCourse.value && (componentColumns.value.length || summaryColumns.value.length))
const visibleAssessmentBlocks = computed(() => {
  if (!filter.assessItemId) return assessmentBlocks.value
  return assessmentBlocks.value.filter((item) => String(item.id) === String(filter.assessItemId))
})
const currentCourse = computed(() =>
  catalogs.courses.find((item) => String(item.id) === String(filter.courseId)) || null
)
const objectiveWeightTotal = computed(() =>
  objectives.value.reduce((sum, item) => sum + Number(item.weight || 0), 0)
)
const assessmentWeightTotal = computed(() =>
  assessmentBlocks.value.reduce((sum, item) => sum + Number(item.weight || 0), 0)
)
const configuredContentCount = computed(() =>
  assessmentBlocks.value.reduce((sum, item) => sum + (item.contents || []).length, 0)
)
const workflowSteps = computed(() => [
  {
    key: 'outline',
    index: '0',
    title: '大纲信息',
    desc: objectives.value.length ? '已提取并展示' : '等待目标信息',
    tone: objectives.value.length ? 'done' : 'pending'
  },
  {
    key: 'contents',
    index: '1',
    title: '考核内容',
    desc: configuredContentCount.value ? `${configuredContentCount.value} 项已配置` : '需要教师配置',
    tone: configuredContentCount.value ? 'done' : 'current'
  },
  {
    key: 'mapping',
    index: '2',
    title: '目标分值',
    desc: mappedObjectiveCount.value ? `${mappedObjectiveCount.value} 个目标已分配` : '待分配',
    tone: mappedObjectiveCount.value ? 'done' : 'pending'
  },
  {
    key: 'import',
    index: '3',
    title: '成绩导入',
    desc: importTables.value.length ? `${importTables.value.length} 个表已导入` : '待导入',
    tone: importTables.value.length ? 'done' : 'pending'
  },
  {
    key: 'summary',
    index: '4',
    title: '总表生成',
    desc: total.value ? `${total.value} 名学生` : '等待成绩',
    tone: total.value ? 'done' : 'pending'
  }
])
const mappedObjectiveCount = computed(() =>
  objectives.value.filter((item) => objectiveAllocatedTotal(item.id) > 0).length
)
const visibleContentDrafts = computed(() => {
  if (!contentDialog.assessItemId) return []
  return contentDrafts.value.filter((item) => String(item.assessItemId) === String(contentDialog.assessItemId))
})
const editingAssessmentBlock = computed(() => {
  if (!contentDialog.assessItemId) return null
  return assessmentBlocks.value.find((item) => String(item.id) === String(contentDialog.assessItemId)) || null
})
const overwriteableDuplicateCount = computed(() =>
  (batch.value?.previewRows || []).reduce((count, row) => (
    count + (row.cells || []).filter((cell) => isOverwriteableDuplicate(cell.errorMsg)).length
  ), 0)
)
const invalidPreviewRows = computed(() =>
  (batch.value?.previewRows || []).filter((row) => !row.valid)
)
const visibleInvalidPreviewLines = computed(() =>
  invalidPreviewRows.value.map(formatInvalidPreviewRow).slice(0, 6)
)
const invalidPreviewOverflow = computed(() =>
  Math.max(0, invalidPreviewRows.value.length - visibleInvalidPreviewLines.value.length)
)
const feedbackLines = computed(() =>
  String(message.text || '').split('\n').map((line) => line.trim()).filter(Boolean)
)
const selectedCourseText = computed(() => {
  const course = catalogs.courses.find((item) => String(item.id) === String(filter.courseId))
  return course ? `${course.name}（${course.code}）` : '当前课程'
})
const draftCells = computed(() => {
  if (!draftRow.value) return []
  return componentColumns.value.length ? draftRow.value.componentCells : draftRow.value.summaryCells
})

watch(
  [() => filter.courseId, () => filter.semester],
  async ([course, semester]) => {
    filter.assessItemId = ''
    cancelDraft()
    await discardPendingBatch(true, false)
    batch.value = null
    if (course && semester) {
      await Promise.all([loadAssessmentContents(), loadObjectiveContext()])
      await loadGrades()
    } else {
      assessmentBlocks.value = []
      objectives.value = []
      contentMappingContents.value = []
      contentMappingRows.value = []
      summaryColumns.value = []
      componentColumns.value = []
      importTables.value = []
      rows.value = []
      total.value = 0
    }
  }
)

watch(
  [() => filter.classId, () => filter.assessItemId],
  () => {
    if (canUseCourse.value) loadGrades()
  }
)

watch(
  () => filter.keyword,
  () => {
    if (!canUseCourse.value) return
    window.clearTimeout(keywordTimer)
    keywordTimer = window.setTimeout(loadGrades, 350)
  }
)

function setMessage(type, text) {
  message.type = type
  message.text = ''
  showFeedback(type, text)
}

function closeFeedbackDialog() {
  setMessage('', '')
}

function formatInvalidPreviewRow(row) {
  const student = `${row.studentName || '未命名'}（${row.studentNo || '未填学号'}）`
  const errors = (row.cells || [])
    .filter((cell) => !cell.valid)
    .map((cell) => `${cell.assessItemName || '成绩'}：${cell.errorMsg || '无效'}`)
  return `${student}：${errors.join('；') || row.errorMsg || '存在异常成绩'}`
}

function unresolvedInvalidPreviewRows() {
  return invalidPreviewRows.value.filter((row) => {
    if (confirmImportMode.value !== 'overwrite') return true
    return (row.cells || []).some((cell) => !cell.valid && !isOverwriteableDuplicate(cell.errorMsg))
  })
}

async function loadAssessmentContents() {
  if (!canUseCourse.value) return
  loadingContents.value = true
  try {
    const data = await getAssessmentContents({ courseId: filter.courseId, semester: filter.semester })
    assessmentBlocks.value = data.assessItems || []
    if (data.warnings?.length) {
      setMessage('warning', data.warnings.join('；'))
    }
  } catch (error) {
    assessmentBlocks.value = []
    setMessage('error', error.message || '考核内容加载失败。')
  } finally {
    loadingContents.value = false
  }
}

async function loadObjectiveContext() {
  if (!canUseCourse.value) return
  try {
    const data = await getAchievementContentMapping({ courseId: filter.courseId, semester: filter.semester })
    objectives.value = data.objectives || []
    contentMappingContents.value = data.contents || []
    contentMappingRows.value = data.rows || []
  } catch (error) {
    objectives.value = []
    contentMappingContents.value = []
    contentMappingRows.value = []
    setMessage('warning', error.message || '课程目标分值分配状态读取失败。')
  }
}

async function loadGrades() {
  if (!canUseCourse.value) return
  loading.value = true
  cancelDraft()
  try {
    const data = await getImportedGrades({
      courseId: filter.courseId,
      semester: filter.semester,
      classId: filter.classId || undefined,
      assessItemId: filter.assessItemId || undefined,
      keyword: filter.keyword || undefined
    })
    summaryColumns.value = data.summaryColumns || data.columns || []
    componentColumns.value = data.componentColumns || []
    importTables.value = data.importTables || []
    rows.value = data.rows || []
    total.value = data.total || rows.value.length
  } catch (error) {
    summaryColumns.value = []
    componentColumns.value = []
    importTables.value = []
    rows.value = []
    total.value = 0
    setMessage('error', error.message || '成绩查询失败。')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filter.courseId = ''
  filter.semester = ''
  filter.classId = ''
  filter.assessItemId = ''
  filter.keyword = ''
  window.clearTimeout(keywordTimer)
  cancelDraft()
  batch.value = null
  importTables.value = []
  objectives.value = []
  contentMappingContents.value = []
  contentMappingRows.value = []
  confirmImportMode.value = 'valid_only'
  setMessage('', '')
}

function handleBlockFileChange(assessItemId, event) {
  selectedFiles[assessItemId] = event.target.files?.[0] || null
}

async function handleUploadBlock(block) {
  const file = selectedFiles[block.id]
  if (!file) {
    setMessage('error', '请先选择成绩文件。')
    return
  }
  uploadingAssessItemId.value = block.id
  confirmImportMode.value = 'valid_only'
  try {
    const created = await uploadGradeFile({
      file,
      courseId: filter.courseId,
      classId: filter.classId || undefined,
      assessItemId: block.id,
      semester: filter.semester
    })
    batch.value = { batchId: created.batchId, status: created.status }
    stopPolling()
    pollingTimer = window.setInterval(() => pollBatch(created.batchId), 1000)
    await pollBatch(created.batchId)
  } catch (error) {
    setMessage('error', error.message || '成绩导入批次创建失败。')
  } finally {
    uploadingAssessItemId.value = null
  }
}

async function pollBatch(batchId) {
  const data = await getGradeBatchPreview(batchId)
  batch.value = data
  if (data.status === 'PARSED') {
    stopPolling()
    if (!overwriteableDuplicateCount.value) {
      confirmImportMode.value = 'valid_only'
    }
    setMessage('success', '成绩文件已解析完成，内容成绩已按权重折算，请复核后确认导入。')
  }
}

function stopPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function isPendingBatchStatus(status) {
  return status === 'PARSING' || status === 'PARSED'
}

async function discardPendingBatch(silent = false, reload = true) {
  const currentBatch = batch.value
  if (!currentBatch?.batchId || !isPendingBatchStatus(currentBatch.status)) {
    return false
  }
  const confirmed = silent || await confirmFeedback('确定丢弃本次待确认导入吗？解析出的缓冲成绩会被清除，正式成绩不会受影响。', {
    confirmText: '丢弃'
  })
  if (!confirmed) {
    return false
  }
  stopPolling()
  discardingBatch.value = true
  try {
    const result = await discardGradeBatch(currentBatch.batchId)
    if (batch.value?.batchId === currentBatch.batchId) {
      batch.value = null
    }
    if (!silent) {
      setMessage('success', `已丢弃本次待确认导入，清理 ${result.discardedRows || 0} 条缓冲数据。`)
    }
    if (reload && canUseCourse.value) {
      await loadGrades()
    }
    return true
  } catch (error) {
    if (!silent) {
      setMessage('error', error.message || '待确认导入丢弃失败。')
    }
    return false
  } finally {
    discardingBatch.value = false
  }
}

function batchStatusLabel(status) {
  if (status === 'PARSING') return '解析中'
  if (status === 'PARSED') return '待确认'
  if (status === 'CONFIRMED') return '已导入'
  if (status === 'FAILED') return '解析失败'
  return status || '--'
}

async function savePreviewRow(row) {
  if (!batch.value) return
  savingRow.value = row.row
  try {
    const updated = await updateGradePreviewRow(batch.value.batchId, {
      studentNo: row.studentNo,
      studentName: row.studentName,
      cells: row.cells.map((cell) => ({
        gradeId: cell.gradeId,
        assessContentId: cell.assessContentId,
        rawScore: cell.rawScore,
        score: cell.score,
        maxScore: cell.maxScore
      }))
    })
    batch.value = updated
    setMessage('success', `第 ${row.row} 行已重新校验。`)
  } catch (error) {
    setMessage('error', error.message || '预览行保存失败。')
  } finally {
    savingRow.value = null
  }
}

async function confirmImport() {
  try {
    const unresolvedRows = unresolvedInvalidPreviewRows()
    const result = await confirmGradeBatch(batch.value.batchId, { importMode: confirmImportMode.value })
    batch.value.status = 'CONFIRMED'
    const overwritten = Number(result.overwrittenCount || 0)
    const overwriteText = overwritten > 0 ? `，覆盖 ${overwritten} 条已有成绩` : ''
    await loadGrades()
    const skipped = Number(result.skippedCount || 0)
    const detailLines = unresolvedRows.map(formatInvalidPreviewRow)
    const attentionText = detailLines.length
      ? `\n需关注：以下学生存在缺失或异常成绩，本次未写入对应成绩。\n${detailLines.slice(0, 8).join('\n')}${detailLines.length > 8 ? `\n另有 ${detailLines.length - 8} 行请回到原文件核对。` : ''}`
      : ''
    setMessage('success-dialog', `已写入 ${result.importedCount} 条有效原始成绩${overwriteText}，跳过 ${skipped} 条异常数据。${attentionText}`)
  } catch (error) {
    setMessage('error', error.message || '成绩导入失败。')
  }
}

function openContentDialog(block) {
  if (!block) return
  contentDrafts.value = assessmentBlocks.value.flatMap((itemBlock) =>
    (itemBlock.contents || []).map((item) => ({
      ...item,
      assessItemId: item.assessItemId || itemBlock.id,
      __rowKey: `${item.id || 'new'}-${Math.random()}`
    }))
  )
  contentDialog.assessItemId = String(block.id)
  contentDialog.blockName = block.itemName
  contentDialog.blockWeight = block.weight || 0
  setContentDialogMessage('', '')
  contentDialog.open = true
}

function closeContentDialog() {
  contentDialog.open = false
  contentDialog.assessItemId = ''
  contentDialog.blockName = ''
  contentDialog.blockWeight = 0
}

function setContentDialogMessage(type, text) {
  contentDialogMessage.type = type
  contentDialogMessage.text = ''
  showFeedback(type, text)
}

function emptyContentRow() {
  const block = editingAssessmentBlock.value || assessmentBlocks.value.find((item) => String(item.id) === String(contentDialog.assessItemId))
  const index = visibleContentDrafts.value.length + 1
  return {
    id: null,
    assessItemId: block?.id || '',
    contentNo: String(index),
    contentName: `考核内容${index}`,
    contentType: 'assignment',
    weight: 0,
    sortOrder: index,
    __rowKey: `new-${Date.now()}-${index}`
  }
}

function addContentRow() {
  contentDrafts.value.push(emptyContentRow())
}

function removeContentRow(item) {
  const index = contentDrafts.value.findIndex((row) => row.__rowKey === item.__rowKey)
  if (index < 0) return
  contentDrafts.value.splice(index, 1)
}

function draftWeightByAssessItem(assessItemId) {
  return contentDrafts.value
    .filter((item) => String(item.assessItemId) === String(assessItemId))
    .reduce((sum, item) => sum + Number(item.weight || 0), 0)
}

function isBlockWeightOk(block) {
  return Math.abs(Number(block.contentWeight || 0) - Number(block.weight || 0)) <= 0.01
}

function isDraftWeightOk(block) {
  return Math.abs(draftWeightByAssessItem(block.id) - Number(block.weight || 0)) <= 0.01
}

async function saveContents() {
  if (!contentDrafts.value.length) {
    setContentDialogMessage('error', '至少需要保留 1 条考核内容。')
    return
  }
  savingContents.value = true
  try {
    const data = await saveAssessmentContents({
      courseId: filter.courseId,
      semester: filter.semester,
      contents: contentDrafts.value.map((item, index) => ({
        id: item.id,
        assessItemId: item.assessItemId,
        contentNo: item.contentNo,
        contentName: item.contentName,
        contentType: item.contentType,
        weight: item.weight,
        sortOrder: index + 1
      }))
    })
    assessmentBlocks.value = data.assessItems || []
    closeContentDialog()
    if (data.warnings?.length) {
      setMessage('warning', `考核内容及方式表已保存。${data.warnings.join('；')}`)
    } else {
      setMessage('success', '考核内容及方式表已保存。')
    }
    await loadObjectiveContext()
    await loadGrades()
  } catch (error) {
    setContentDialogMessage('error', error.message || '考核内容保存失败。')
  } finally {
    savingContents.value = false
  }
}

function startAddRow() {
  if (!canEditGrades.value) {
    setMessage('error', '当前课程学期尚未配置考核内容，无法录入成绩。')
    return
  }
  draftMode.value = 'create'
  draftRow.value = {
    studentNo: '',
    studentName: '',
    summaryCells: cloneCells(summaryColumns.value),
    componentCells: cloneCells(componentColumns.value)
  }
}

function cloneCells(source) {
  return source.map((column) => ({
    gradeId: null,
    columnKey: column.columnKey,
    assessItemId: column.assessItemId,
    assessContentId: column.assessContentId,
    assessItemName: column.assessItemName,
    parentAssessItemName: column.parentAssessItemName,
    rawScore: '',
    rawMaxScore: column.rawMaxScore || 100,
    score: '',
    convertedScore: '',
    convertedMaxScore: column.convertedMaxScore || column.maxScore || 100,
    maxScore: column.maxScore || 100
  }))
}

function startEditRow(row) {
  draftMode.value = 'edit'
  draftRow.value = {
    studentNo: row.studentNo,
    studentName: row.studentName,
    summaryCells: (row.summaryCells || row.cells || []).map((cell) => ({ ...cell })),
    componentCells: (row.componentCells || []).map((cell) => ({ ...cell }))
  }
}

function cancelDraft() {
  draftMode.value = ''
  draftRow.value = null
}

async function saveDraftRow() {
  if (!draftRow.value) return
  saving.value = true
  try {
    const useComponents = componentColumns.value.length > 0
    await saveImportedGradeRow({
      courseId: filter.courseId,
      semester: filter.semester,
      classId: filter.classId,
      studentNo: draftRow.value.studentNo,
      studentName: draftRow.value.studentName,
      cells: useComponents ? [] : draftRow.value.summaryCells,
      componentCells: useComponents ? draftRow.value.componentCells : []
    })
    setMessage('success', draftMode.value === 'create' ? '学生成绩已新增。' : '学生成绩已保存。')
    cancelDraft()
    await loadGrades()
  } catch (error) {
    setMessage('error', error.message || '成绩保存失败。')
  } finally {
    saving.value = false
  }
}

async function deleteRow(row) {
  const confirmed = await confirmFeedback(`确定删除 ${row.studentName}（${row.studentNo}）的本次查询范围内成绩吗？`, {
    confirmText: '删除'
  })
  if (!confirmed) return
  try {
    await deleteImportedGradeRow({
      courseId: filter.courseId,
      semester: filter.semester,
      studentNo: row.studentNo,
      assessItemId: filter.assessItemId || undefined
    })
    setMessage('success', '学生成绩已删除。')
    await loadGrades()
  } catch (error) {
    setMessage('error', error.message || '成绩删除失败。')
  }
}

function formatScore(value) {
  if (value === null || value === undefined || value === '') return '--'
  const number = Number(value)
  return Number.isNaN(number) ? value : number.toFixed(2)
}

function formatNumber(value) {
  if (value === null || value === undefined || value === '') return '0'
  const number = Number(value)
  if (Number.isNaN(number)) return value
  return Number.isInteger(number) ? String(number) : number.toFixed(2).replace(/\.?0+$/, '')
}

function objectiveMappingRow(objectiveId) {
  return contentMappingRows.value.find((row) => String(row.objectiveId) === String(objectiveId)) || null
}

function objectiveAllocatedTotal(objectiveId) {
  const row = objectiveMappingRow(objectiveId)
  if (!row?.values) return 0
  return Object.values(row.values).reduce((sum, value) => sum + Number(value || 0), 0)
}

function objectiveMappedContents(objectiveId) {
  const row = objectiveMappingRow(objectiveId)
  if (!row?.values) return []
  return Object.entries(row.values)
    .map(([contentId, score]) => {
      const content = contentMappingContents.value.find((item) => String(item.assessContentId) === String(contentId))
      return {
        contentId,
        score: Number(score || 0),
        name: content ? `${content.assessItemName}-${content.contentName}` : `考核内容${contentId}`
      }
    })
    .filter((item) => item.score > 0)
}

function isObjectiveAllocationOk(objective) {
  return Math.abs(objectiveAllocatedTotal(objective.id) - Number(objective.weight || 0)) <= 0.01
}

function hasScoreInput(value) {
  return value !== null && value !== undefined && String(value).trim() !== ''
}

function isOverwriteableDuplicate(message) {
  return String(message || '').includes('与已有成绩重复')
}

function shouldShowRawImportScore() {
  return importScoreMode.value === 'all' || importScoreMode.value === 'raw'
}

function shouldShowConvertedImportScore() {
  return importScoreMode.value === 'all' || importScoreMode.value === 'converted'
}

function importDisplayColumns(table) {
  return (table?.columns || []).flatMap((column) => {
    const columns = []
    if (shouldShowRawImportScore()) {
      const rawMax = formatNumber(column.rawMaxScore || 100)
      columns.push({
        key: `${column.columnKey}-raw`,
        assessItemName: column.assessItemName,
        label: `原始分 / ${rawMax}`,
        className: 'raw-score-col'
      })
    }
    if (shouldShowConvertedImportScore()) {
      columns.push({
        key: `${column.columnKey}-converted`,
        assessItemName: column.assessItemName,
        label: `折算分 / ${formatNumber(column.convertedMaxScore || column.maxScore)}`,
        className: 'converted-score-col'
      })
    }
    return columns
  })
}

function importDisplayCells(row) {
  return (row?.cells || []).flatMap((cell) => {
    const cells = []
    if (shouldShowRawImportScore()) {
      cells.push({
        key: `${cell.columnKey}-raw`,
        value: cell.rawScore,
        className: 'raw-score-col'
      })
    }
    if (shouldShowConvertedImportScore()) {
      cells.push({
        key: `${cell.columnKey}-converted`,
        value: cell.convertedScore,
        className: 'converted-score-col'
      })
    }
    return cells
  })
}

function columnScaleLabel(column) {
  const rawMax = formatNumber(column?.rawMaxScore || 100)
  return column?.assessContentId
    ? `原始分 / ${rawMax} · 折算权重 ${formatNumber(column.convertedMaxScore || column.maxScore)}`
    : `满分 ${formatNumber(column?.maxScore)}`
}

function scoreInputMax(cell) {
  return cell?.assessContentId ? undefined : cell?.maxScore
}

function convertedPreviewScore(cell) {
  if (!cell?.assessContentId) return cell?.score
  if (!hasScoreInput(cell.rawScore)) return cell.convertedScore
  const raw = Number(cell.rawScore)
  if (Number.isNaN(raw)) return cell.convertedScore
  const max = Number(cell.convertedMaxScore || cell.maxScore || 0)
  const rawMax = Number(cell.rawMaxScore || 100)
  return rawMax > 0 ? (raw / rawMax) * max : cell.convertedScore
}

async function loadCatalogs() {
  const data = await getReferenceCatalogs()
  catalogs.courses = data.courses || []
  catalogs.semesters = data.semesters || []
  catalogs.classes = data.classes || []
}

onMounted(loadCatalogs)
onBeforeUnmount(() => {
  stopPolling()
  window.clearTimeout(keywordTimer)
  void discardPendingBatch(true, false)
})
</script>

<style scoped>
.form-grid-4 {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.workflow-steps {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.workflow-step {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  min-height: 72px;
  padding: 12px;
  border: 1px solid #dfeaf0;
  border-radius: 8px;
  background: #fff;
}

.workflow-step > span {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 900;
  color: #fff;
  background: #94a3b8;
}

.workflow-step strong,
.workflow-step small {
  display: block;
}

.workflow-step small {
  margin-top: 4px;
  color: var(--color-text-soft);
  line-height: 1.4;
}

.workflow-step.done {
  border-color: #bfe5d5;
  background: #f5fbf8;
}

.workflow-step.done > span {
  background: var(--color-primary);
}

.workflow-step.current {
  border-color: #f0d49a;
  background: #fffaf0;
}

.workflow-step.current > span {
  background: var(--color-warning);
}

.config-overview-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.85fr) minmax(340px, 1.1fr) minmax(240px, 0.75fr);
  gap: 14px;
  align-items: stretch;
}

.overview-card,
.mapping-card {
  border: 1px solid #dfeaf0;
  border-radius: 8px;
  background: #fff;
  padding: 14px;
}

.overview-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.overview-card-head strong {
  color: var(--color-primary-deep);
}

.overview-card-head em {
  margin-left: auto;
  font-style: normal;
  font-weight: 900;
  color: var(--color-secondary);
}

.step-kicker {
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--color-secondary-soft);
  color: var(--color-secondary);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.course-name-line {
  font-size: 18px;
  font-weight: 900;
  color: var(--color-text);
  line-height: 1.4;
}

.meta-grid {
  display: grid;
  gap: 10px;
}

.compact-meta-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 14px;
}

.meta-grid div,
.readiness-list div {
  min-width: 0;
  padding: 10px;
  border-radius: 8px;
  background: #f8fbfd;
}

.meta-grid span,
.readiness-list span {
  display: block;
  margin-bottom: 5px;
  color: var(--color-text-soft);
  font-size: 12px;
}

.meta-grid strong,
.readiness-list strong {
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.objective-mini-list,
.readiness-list,
.mapping-chip-list {
  display: grid;
  gap: 8px;
}

.objective-mini-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 10px 0;
  border-bottom: 1px solid #edf3f6;
}

.objective-mini-row:last-child {
  border-bottom: 0;
}

.objective-mini-row small {
  display: block;
  margin-top: 4px;
  color: var(--color-text-soft);
  line-height: 1.5;
}

.objective-mini-row > span {
  font-weight: 900;
  color: var(--color-primary);
}

.mapping-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 14px;
}

.mapping-card header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.mapping-card h3 {
  margin: 0;
  color: var(--color-primary-deep);
  font-size: 16px;
}

.mapping-card p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  font-size: 13px;
  line-height: 1.5;
}

.mapping-chip-list {
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  margin-top: 12px;
}

.mapping-chip-list span {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fbfd;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.4;
}

.empty-inline {
  padding: 12px;
  border: 1px dashed #d8e6ec;
  border-radius: 8px;
  color: var(--color-text-soft);
  background: #fff;
}

.assessment-block-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 14px;
}

.assessment-block {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.block-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--bg-panel-soft);
}

.block-head h3 {
  margin: 0;
  font-size: 16px;
  color: var(--color-primary-deep);
}

.block-head p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  font-size: 13px;
}

.block-head-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-end;
}

.weight-pill {
  align-self: center;
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--color-secondary-soft);
  color: var(--color-secondary);
  font-weight: 800;
  white-space: nowrap;
}

.weight-pill.warning,
.content-weight-summary .warning {
  background: #fff7e8;
  color: var(--color-warning);
}

.content-preview-shell {
  border: 0;
  border-radius: 0;
  max-height: 220px;
}

.content-preview-table {
  min-width: 420px;
}

.block-import {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 16px 16px;
}

.block-import input {
  min-width: 0;
}

.import-table-stack {
  display: grid;
  gap: 14px;
}

.import-table-card {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.import-table-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--bg-panel-soft);
}

.import-table-head h3 {
  margin: 0;
  color: var(--color-primary-deep);
  font-size: 15px;
}

.import-table-head p {
  margin: 6px 0 0;
  color: var(--color-text-soft);
  font-size: 13px;
}

.score-mode-toggle {
  display: inline-flex;
  align-items: center;
  padding: 3px;
  border: 1px solid #d9e7ee;
  border-radius: 8px;
  background: #fff;
}

.mode-toggle-btn {
  min-height: 30px;
  padding: 5px 11px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-soft);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
}

.mode-toggle-btn.active {
  background: var(--color-primary);
  color: #fff;
  box-shadow: 0 1px 3px rgba(15, 138, 120, 0.18);
}

.confirm-import-actions {
  align-items: center;
}

.duplicate-count {
  color: var(--color-warning);
  font-size: 13px;
  font-weight: 700;
}

.import-warning {
  display: grid;
  gap: 10px;
}

.import-warning-list {
  display: grid;
  gap: 6px;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.45;
}

.feedback-panel {
  width: min(620px, 94vw);
}

.feedback-body {
  display: grid;
  gap: 12px;
}

.feedback-lines {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
}

.feedback-lines p {
  margin: 0;
  line-height: 1.6;
}

.import-detail-shell {
  border: 0;
  border-radius: 0;
  max-height: 420px;
}

.import-detail-table {
  min-width: 1180px;
}

.import-detail-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #f7fbfd;
}

.import-detail-table th,
.import-detail-table td {
  padding: 10px;
  vertical-align: middle;
}

.import-detail-table td.sticky-col {
  z-index: 5;
  background: #fff;
}

.import-detail-table th.sticky-col {
  z-index: 6;
  background: #f7fbfd;
}

.import-detail-table tbody tr:hover td.sticky-col {
  background: #f7fbfd;
}

.raw-score-col {
  background: #fff;
}

.converted-score-col {
  background: #fbfdfe;
}

.editor-card {
  padding: 16px;
  border: 1px solid #dce8ef;
  border-radius: 8px;
  background: #fbfdfe;
}

.editor-title {
  font-weight: 800;
}

.score-editor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.score-editor-cell small {
  display: block;
  margin-top: 6px;
  color: var(--color-text-soft);
}

.grade-manage-shell,
.grade-preview-shell {
  border: 1px solid #e2edf3;
  border-radius: 8px;
  max-height: 620px;
}

.grade-manage-table {
  min-width: 1280px;
}

.grade-preview-table {
  min-width: 980px;
}

.grade-manage-table th,
.grade-preview-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #f7fbfd;
}

.grade-manage-table th,
.grade-manage-table td,
.grade-preview-table th,
.grade-preview-table td {
  padding: 10px;
  vertical-align: middle;
  background: #fff;
}

.sticky-col {
  position: sticky;
  z-index: 3;
  box-shadow: 1px 0 0 #e8f0f4;
}

th.sticky-col {
  z-index: 4;
}

.seq-col,
.row-col {
  left: 0;
  width: 64px;
  min-width: 64px;
  text-align: center;
}

.student-col {
  left: 64px;
  width: 150px;
  min-width: 150px;
}

.name-col {
  left: 214px;
  width: 128px;
  min-width: 128px;
}

.score-col {
  min-width: 120px;
  text-align: right;
}

.score-col span {
  display: block;
  color: var(--color-text);
}

.score-col small {
  display: block;
  margin-top: 4px;
  color: var(--color-text-soft);
  font-weight: 500;
}

.summary-score-col {
  background: #fbfdfe;
}

.total-col {
  min-width: 110px;
  color: var(--color-secondary);
}

.level-col {
  min-width: 90px;
}

.action-col {
  min-width: 150px;
  white-space: nowrap;
}

.status-col {
  min-width: 110px;
}

.btn-compact,
.btn-mini {
  min-height: 30px;
  padding: 6px 10px;
  font-size: 12px;
  white-space: nowrap;
}

.preview-input {
  width: 100%;
  min-width: 0;
  padding: 8px 9px;
  border-radius: 6px;
}

.score-input {
  max-width: 96px;
}

.preview-input.invalid {
  border-color: var(--color-danger);
  background: #fff7f7;
}

.invalid-row td {
  background: #fffafa;
}

.cell-error {
  display: block;
  margin-top: 6px;
  color: var(--color-danger);
  font-size: 12px;
  line-height: 1.35;
}

.score-converted {
  display: block;
  margin-top: 6px;
  color: var(--color-text-soft);
  font-size: 12px;
  line-height: 1.35;
}

.status-text-error {
  color: var(--color-danger);
  font-weight: 700;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: 13px;
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

.content-modal-panel {
  width: min(1180px, 96vw);
}

.content-modal-body {
  display: grid;
  gap: 16px;
}

.content-edit-shell {
  max-height: calc(92vh - 390px);
  overflow: auto;
}

.content-edit-table {
  min-width: 760px;
}

.content-edit-table th {
  position: sticky;
  top: 0;
  z-index: 2;
}

.seq-input {
  min-width: 72px;
}

.content-name-input {
  min-width: 220px;
}

.type-select {
  min-width: 108px;
}

.weight-input {
  min-width: 96px;
}

.content-weight-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.content-weight-summary.single {
  grid-template-columns: minmax(220px, 360px);
}

.content-weight-summary > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
}

.modal-footer-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
}

@media (max-width: 1080px) {
  .form-grid-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workflow-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .config-overview-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .form-grid-4,
  .form-grid-2 {
    grid-template-columns: 1fr;
  }

  .workflow-steps {
    grid-template-columns: 1fr;
  }

  .compact-meta-grid,
  .mapping-card-grid {
    grid-template-columns: 1fr;
  }

  .assessment-block-grid {
    grid-template-columns: 1fr;
  }
}
</style>
