import axios from 'axios'

const AUTH_STORAGE_KEY = 'coa-teach-auth'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000
})

function readAccessToken() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) {
      return ''
    }
    const parsed = JSON.parse(raw)
    return parsed?.accessToken || ''
  } catch (error) {
    return ''
  }
}

function buildRequestError(error) {
  const message =
    error?.response?.data?.message ||
    error?.response?.data?.error ||
    error?.message ||
    '请求失败，请稍后重试'

  return Promise.reject(new Error(message))
}

function appendFormData(formData, key, value) {
  if (value === undefined || value === null || value === '') {
    return
  }
  formData.append(key, value)
}

request.interceptors.request.use((config) => {
  const accessToken = readAccessToken()

  if (accessToken && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  return config
})

request.interceptors.response.use((response) => response, buildRequestError)

export async function login(payload) {
  const { data } = await request.post('/auth/login', payload)
  return data
}

export async function logout() {
  const { data } = await request.post('/auth/logout')
  return data
}

export async function getCurrentUser(accessToken) {
  const headers = accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined
  const { data } = await request.get('/auth/me', { headers })
  return data
}

export async function getReferenceCatalogs() {
  const { data } = await request.get('/reference/catalogs')
  return data
}

export async function getDashboardData(params) {
  const { data } = await request.get('/analysis/dashboard', { params })
  return data
}

export async function getOutlines(params) {
  const { data } = await request.get('/outlines', { params })
  return data
}

export async function saveOutline(payload) {
  if (payload.id) {
    const { data } = await request.put(`/outlines/${payload.id}`, payload)
    return data
  }

  const { data } = await request.post('/outlines', payload)
  return data
}

export async function publishOutline(id) {
  const { data } = await request.patch(`/outlines/${id}/publish`)
  return data
}

export async function getObjectives(params) {
  const { data } = await request.get('/objectives', { params })
  return data
}

export async function getObjectiveDetail(id) {
  const { data } = await request.get(`/objectives/${id}`)
  return data
}

export async function saveObjective(payload) {
  if (payload.id) {
    const { data } = await request.put(`/objectives/${payload.id}`, payload)
    return data
  }

  const { data } = await request.post('/objectives', payload)
  return data
}

export async function getObjectiveWeights(params) {
  const { data } = await request.get('/objectives', { params })
  return {
    ...data,
    objectives: data.objectives || data.items || []
  }
}

export async function saveObjectiveWeights(payload) {
  const { data } = await request.put('/objectives/batch-weights', payload)
  return data
}

export async function getObjectiveMapping(params) {
  const { data } = await request.get('/obj-assess-maps', { params })
  return data
}

export async function saveObjectiveMapping(payload) {
  const { data } = await request.put('/obj-assess-maps', payload)
  return data
}

export async function uploadParseFile(payload) {
  const formData = new FormData()
  appendFormData(formData, 'file', payload.file)
  appendFormData(formData, 'courseId', payload.courseId)
  appendFormData(formData, 'semester', payload.semester)
  appendFormData(formData, 'outlineId', payload.outlineId)
  const { data } = await request.post('/parse/upload', formData)
  return data
}

export async function getParseTaskDetail(taskId) {
  const { data } = await request.get(`/parse/tasks/${taskId}`)
  return data
}

export async function updateParseDraft(id, payload) {
  const { data } = await request.put(`/parse/drafts/objectives/${id}`, payload)
  return data
}

export async function confirmParseTask(taskId, payload) {
  const { data } = await request.post(`/parse/tasks/${taskId}/confirm`, payload)
  return data
}

export async function uploadGradeFile(payload) {
  const formData = new FormData()
  appendFormData(formData, 'file', payload.file)
  appendFormData(formData, 'courseId', payload.courseId)
  appendFormData(formData, 'assessItemId', payload.assessItemId)
  appendFormData(formData, 'semester', payload.semester)
  const { data } = await request.post('/collect/grades/upload', formData)
  return data
}

export async function getGradeBatchPreview(batchId) {
  const { data } = await request.get(`/collect/grades/batches/${batchId}/preview`)
  return data
}

export async function confirmGradeBatch(batchId, payload) {
  const { data } = await request.post(`/collect/grades/batches/${batchId}/confirm`, payload)
  return data
}

export async function getStudentEvaluations(params) {
  const { data } = await request.get('/collect/student-evals', { params })
  return data
}

export async function getSupervisorEvaluations(params) {
  const { data } = await request.get('/collect/supervisor-evals', { params })
  return data
}

export async function saveStudentEvaluations(payload) {
  const { data } = await request.post('/collect/student-evals/batch', payload)
  return data
}

export async function getTeachingReflection(params) {
  const { data } = await request.get('/collect/teacher-reflections', { params })
  return data
}

export async function saveTeachingReflection(payload) {
  const { data } = await request.post('/collect/teacher-reflections', payload)
  return data
}

export async function getAchievementCalculation(params) {
  const { data } = await request.get('/achieve/results', { params })
  return data
}

export async function runAchievementCalculation(payload) {
  const { data } = await request.post('/achieve/tasks', payload)
  return data
}

export async function getCourseOverview(params) {
  const { data } = await request.get('/analysis/course-overview', { params })
  return data
}

export async function getTrendData(params) {
  const { data } = await request.get('/analysis/trend', { params })
  return data
}

export async function getSuggestions(params) {
  const { data } = await request.get('/intelligent-suggestions', { params })
  return data
}

export async function getSuggestionDetail(id) {
  const { data } = await request.get(`/intelligent-suggestions/${id}`)
  return data
}

export async function markSuggestionRead(id) {
  const { data } = await request.patch(`/intelligent-suggestions/${id}/read`)
  return data
}

export async function dismissSuggestion(id, payload) {
  const { data } = await request.patch(`/intelligent-suggestions/${id}/dismiss`, payload)
  return data
}

export async function createMeasureFromSuggestion(id) {
  const { data } = await request.post(`/intelligent-suggestions/${id}/create-measure`)
  return data
}

export async function getImprovementMeasures(params) {
  const { data } = await request.get('/improve/measures', { params })
  return data
}

export async function saveImprovementMeasure(payload) {
  if (payload.id) {
    const { data } = await request.put(`/improve/measures/${payload.id}`, payload)
    return data
  }

  const { data } = await request.post('/improve/measures', payload)
  return data
}

export default request
