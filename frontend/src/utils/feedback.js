import { reactive } from 'vue'

const titleMap = {
  success: '操作已完成',
  'success-dialog': '操作已完成',
  error: '操作未完成',
  warning: '需要注意',
  info: '提示'
}

let autoCloseTimer = null

export const feedbackState = reactive({
  open: false,
  type: 'info',
  title: '',
  message: '',
  confirmMode: false,
  confirmText: '确定',
  cancelText: '取消',
  resolver: null
})

function clearAutoCloseTimer() {
  if (autoCloseTimer) {
    window.clearTimeout(autoCloseTimer)
    autoCloseTimer = null
  }
}

function normalizedType(type) {
  return type === 'success-dialog' ? 'success' : (type || 'info')
}

function defaultTitle(type, confirmMode) {
  if (confirmMode) {
    return '请确认'
  }
  return titleMap[type] || titleMap.info
}

export function showFeedback(type = 'info', message = '', options = {}) {
  clearAutoCloseTimer()
  if (feedbackState.resolver) {
    feedbackState.resolver(false)
    feedbackState.resolver = null
  }
  if (!message) {
    closeFeedback()
    return
  }

  const currentType = normalizedType(type)
  feedbackState.open = true
  feedbackState.type = currentType
  feedbackState.title = options.title || defaultTitle(currentType, false)
  feedbackState.message = message
  feedbackState.confirmMode = false
  feedbackState.confirmText = options.confirmText || '知道了'
  feedbackState.cancelText = options.cancelText || '取消'
  feedbackState.resolver = null

  const autoClose = options.autoClose ?? (type === 'success-dialog' ? 0 : currentType === 'success' ? 2600 : 0)
  if (autoClose > 0) {
    autoCloseTimer = window.setTimeout(() => {
      closeFeedback()
    }, autoClose)
  }
}

export function confirmFeedback(message, options = {}) {
  clearAutoCloseTimer()
  if (feedbackState.resolver) {
    feedbackState.resolver(false)
  }
  return new Promise((resolve) => {
    feedbackState.open = true
    feedbackState.type = normalizedType(options.type || 'warning')
    feedbackState.title = options.title || defaultTitle(feedbackState.type, true)
    feedbackState.message = message
    feedbackState.confirmMode = true
    feedbackState.confirmText = options.confirmText || '确定'
    feedbackState.cancelText = options.cancelText || '取消'
    feedbackState.resolver = resolve
  })
}

export function closeFeedback(result = false) {
  clearAutoCloseTimer()
  const resolver = feedbackState.resolver
  feedbackState.open = false
  feedbackState.message = ''
  feedbackState.confirmMode = false
  feedbackState.resolver = null
  if (resolver) {
    resolver(Boolean(result))
  }
}
