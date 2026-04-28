import axios from 'axios'

// 这个 key 会同时被接口层和登录状态仓库使用。
// 抽成常量后，后续如果想统一改 localStorage 的存储名，只需要改这一处。
export const AUTH_STORAGE_KEY = 'coa-teach-auth'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000
})

function readAuthCache() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (error) {
    return null
  }
}

function readAccessToken() {
  return readAuthCache()?.accessToken || ''
}

function clearAuthCache() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
}

function resolveErrorMessage(error) {
  return (
    error?.response?.data?.message ||
    error?.response?.data?.error ||
    error?.message ||
    '请求失败，请稍后重试'
  )
}

function isUnauthorized(error) {
  return Number(error?.response?.status) === 401
}

function redirectToLogin() {
  if (typeof window === 'undefined') {
    return
  }

  if (window.location.pathname === '/login') {
    return
  }

  const redirect = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const loginUrl = `/login?redirect=${encodeURIComponent(redirect)}`
  window.location.replace(loginUrl)
}

function appendFormField(formData, key, value) {
  if (value === undefined || value === null || value === '') {
    return
  }
  formData.append(key, value)
}

async function getData(url, config) {
  const { data } = await request.get(url, config)
  return data
}

async function postData(url, payload, config) {
  const { data } = await request.post(url, payload, config)
  return data
}

async function putData(url, payload, config) {
  const { data } = await request.put(url, payload, config)
  return data
}

async function patchData(url, payload, config) {
  const { data } = await request.patch(url, payload, config)
  return data
}

async function saveById(baseUrl, payload) {
  return payload?.id ? putData(`${baseUrl}/${payload.id}`, payload) : postData(baseUrl, payload)
}

async function postFormData(url, payload, fields) {
  const formData = new FormData()

  // fields 既可以传 'file' 这种字符串，也可以传 ['courseId', 'courseId'] 这种映射。
  // 这样做的目的是把“组装 FormData”的重复代码统一收口，页面层就不用一遍遍手写了。
  fields.forEach((field) => {
    if (Array.isArray(field)) {
      const [sourceKey, targetKey] = field
      appendFormField(formData, targetKey, payload?.[sourceKey])
      return
    }

    appendFormField(formData, field, payload?.[field])
  })

  return postData(url, formData)
}

request.interceptors.request.use((config) => {
  const accessToken = readAccessToken()

  // 登录后自动补 Bearer Token，页面侧就不用每个请求都手动传。
  if (accessToken && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  return config
})

request.interceptors.response.use(
  (response) => response,
  (error) => {
    // 浏览器里残留了过期 token 时，后端会返回 401。
    // 这里统一清理本地登录态并跳回登录页，避免每个页面都因为 Promise 未捕获而报运行时错误。
    if (isUnauthorized(error)) {
      clearAuthCache()
      redirectToLogin()
    }

    return Promise.reject(new Error(resolveErrorMessage(error)))
  }
)

// 认证相关接口
export function login(payload) {
  return postData('/auth/login', payload)
}

export function logout() {
  return postData('/auth/logout')
}

export function getCurrentUser(accessToken) {
  const headers = accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined
  return getData('/auth/me', { headers })
}

// 公共目录数据
export function getReferenceCatalogs() {
  return getData('/reference/catalogs')
}

export function getCourseDetail(id, params) {
  return getData(`/courses/${id}`, { params })
}

export function updateCourse(id, payload) {
  return putData(`/courses/${id}`, payload)
}

export function updateCourseTeachingContents(id, semester, payload) {
  return putData(`/courses/${id}/teaching-contents`, payload, { params: { semester } })
}

export function updateCourseAssessItems(id, semester, payload) {
  return putData(`/courses/${id}/assess-items`, payload, { params: { semester } })
}

export function getDashboardData(params) {
  return getData('/analysis/dashboard', { params })
}

// 课程大纲
export function getOutlines(params) {
  return getData('/outlines', { params })
}

export function saveOutline(payload) {
  return saveById('/outlines', payload)
}

export function publishOutline(id) {
  return patchData(`/outlines/${id}/publish`)
}

// 教学目标
export function getObjectives(params) {
  return getData('/objectives', { params })
}

export function getObjectiveDetail(id) {
  return getData(`/objectives/${id}`)
}

export function saveObjective(payload) {
  return saveById('/objectives', payload)
}

export async function getObjectiveWeights(params) {
  const data = await getData('/objectives', { params })

  // 后端历史上可能返回 objectives，也可能返回 items。
  // 这里统一整理成 objectives，页面就只需要记一种字段名。
  return {
    ...data,
    objectives: data.objectives || data.items || []
  }
}

export function saveObjectiveWeights(payload) {
  return putData('/objectives/batch-weights', payload)
}

export function getObjectiveMapping(params) {
  return getData('/obj-assess-maps', { params })
}

export function saveObjectiveMapping(payload) {
  return putData('/obj-assess-maps', payload)
}

// 智能解析导入
export function uploadParseFile(payload) {
  return postFormData('/parse/upload', payload, ['file', 'courseId', 'semester', 'outlineId'])
}

export function getParseTaskDetail(taskId) {
  return getData(`/parse/tasks/${taskId}`)
}

export function updateParseDraft(id, payload) {
  return putData(`/parse/drafts/objectives/${id}`, payload)
}

export function createParseDraft(taskId, payload) {
  return postData(`/parse/tasks/${taskId}/drafts/objectives`, payload)
}

export function deleteParseDraft(id) {
  return request.delete(`/parse/drafts/objectives/${id}`).then(({ data }) => data)
}

export function updateParseAssessDraft(id, payload) {
  return putData(`/parse/drafts/assess-items/${id}`, payload)
}

export function createParseAssessDraft(taskId, payload) {
  return postData(`/parse/tasks/${taskId}/drafts/assess-items`, payload)
}

export function deleteParseAssessDraft(id) {
  return request.delete(`/parse/drafts/assess-items/${id}`).then(({ data }) => data)
}

export function updateParseMappingMatrix(taskId, payload) {
  return putData(`/parse/tasks/${taskId}/mapping`, payload)
}

export function updateParseCourseInfo(taskId, payload) {
  return putData(`/parse/tasks/${taskId}/course-info`, payload)
}

export function confirmParseTask(taskId, payload) {
  return postData(`/parse/tasks/${taskId}/confirm`, payload)
}

// 学生成绩管理
export function getImportedGrades(params) {
  return getData('/collect/grades', { params })
}

export function getAssessmentContents(params) {
  return getData('/collect/assessment-contents', { params })
}

export function saveAssessmentContents(payload) {
  return putData('/collect/assessment-contents', payload)
}

export function saveImportedGradeRow(payload) {
  return postData('/collect/grades/rows', payload)
}

export function deleteImportedGradeRow(params) {
  return request.delete('/collect/grades/rows', { params }).then(({ data }) => data)
}

// 成绩导入
export function uploadGradeFile(payload) {
  return postFormData('/collect/grades/upload', payload, ['file', 'courseId', 'classId', 'assessItemId', 'semester'])
}

export function getClasses(params) {
  return getData('/collect/classes', { params })
}

export function saveClass(payload) {
  return saveById('/collect/classes', payload)
}

export function getClassStudents(classId, params) {
  return getData(`/collect/classes/${classId}/students`, { params })
}

export function uploadStudents(classId, payload) {
  return postFormData(`/collect/classes/${classId}/students/upload`, payload, ['file'])
}

export function saveStudent(payload) {
  return saveById('/collect/students', payload)
}

export function deleteStudent(id) {
  return request.delete(`/collect/students/${id}`).then(({ data }) => data)
}

export function getClassCourses(params) {
  return getData('/collect/class-courses', { params })
}

export function saveClassCourse(payload) {
  return postData('/collect/class-courses', payload)
}

export function deleteClassCourse(id) {
  return request.delete(`/collect/class-courses/${id}`).then(({ data }) => data)
}

export function getGradeBatchPreview(batchId) {
  return getData(`/collect/grades/batches/${batchId}/preview`)
}

export function updateGradePreviewRow(batchId, payload) {
  return putData(`/collect/grades/batches/${batchId}/preview-rows`, payload)
}

export function confirmGradeBatch(batchId, payload) {
  return postData(`/collect/grades/batches/${batchId}/confirm`, payload)
}

export function discardGradeBatch(batchId) {
  return request.delete(`/collect/grades/batches/${batchId}`).then(({ data }) => data)
}

// 达成度核算与分析
export function getAchievementCalculation(params) {
  return getData('/achieve/results', { params })
}

export function runAchievementCalculation(payload) {
  return postData('/achieve/tasks', payload)
}

export function getAchievementContentMapping(params) {
  return getData('/achieve/content-maps', { params })
}

export function saveAchievementContentMapping(payload) {
  return postData('/achieve/content-maps', payload)
}

export function getReportPreviewMeta(outlineId, calcRuleId) {
  return getData('/report/preview-meta', { params: { outlineId, calcRuleId } })
}

export async function downloadReport(outlineId, calcRuleId) {
  const response = await request.get('/report/download', {
    params: { outlineId, calcRuleId },
    responseType: 'blob',
    timeout: 60000
  })
  const contentDisposition = response.headers['content-disposition'] || ''
  const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?(.+)/)
  const filename = match ? decodeURIComponent(match[1]) : '达成度报告.docx'
  const url = URL.createObjectURL(new Blob([response.data]))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

export default request
