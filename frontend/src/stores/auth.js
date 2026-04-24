import { defineStore } from 'pinia'
import { AUTH_STORAGE_KEY, getCurrentUser, login, logout } from '@/api'

function readPersistedSession() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (error) {
    return null
  }
}

function writePersistedSession(payload) {
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(payload))
}

function clearPersistedSession() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
}

function normalizeSession(payload = {}) {
  return {
    accessToken: payload.accessToken || '',
    refreshToken: payload.refreshToken || '',
    expiresIn: payload.expiresIn || 0,
    userInfo: payload.userInfo || null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: '',
    refreshToken: '',
    expiresIn: 0,
    userInfo: null,
    initialized: false,
    sessionValidated: false
  }),
  getters: {
    isAuthenticated(state) {
      return Boolean(state.accessToken && state.userInfo)
    }
  },
  actions: {
    applySession(payload, options = {}) {
      const session = normalizeSession(payload)
      this.accessToken = session.accessToken
      this.refreshToken = session.refreshToken
      this.expiresIn = session.expiresIn
      this.userInfo = session.userInfo
      this.sessionValidated = Boolean(options.validated)
    },
    resetSession() {
      this.applySession()
      clearPersistedSession()
    },
    persistSession() {
      // 只有在“令牌 + 用户信息”都完整时才落盘，避免把半截状态写进浏览器缓存。
      if (!this.accessToken || !this.userInfo) {
        clearPersistedSession()
        return
      }

      writePersistedSession({
        accessToken: this.accessToken,
        refreshToken: this.refreshToken,
        expiresIn: this.expiresIn,
        userInfo: this.userInfo
      })
    },
    hydrate() {
      const saved = readPersistedSession()
      if (saved) {
        // 从浏览器缓存恢复的数据只能说明“之前登录过”，不能说明 token 现在仍然有效。
        // 所以先恢复界面状态，再由路由守卫触发一次服务端校验。
        this.applySession(saved, { validated: false })
      }
      this.initialized = true
    },
    async loginAction(credentials) {
      const response = await login(credentials)
      this.applySession(response, { validated: true })
      this.persistSession()
      return response
    },
    async fetchCurrentUser() {
      if (!this.accessToken) {
        return null
      }

      const user = await getCurrentUser(this.accessToken)
      this.userInfo = user
      this.sessionValidated = true
      this.persistSession()
      return user
    },
    async ensureSession() {
      if (!this.accessToken) {
        return false
      }

      if (this.sessionValidated && this.userInfo) {
        return true
      }

      try {
        await this.fetchCurrentUser()
        return true
      } catch (error) {
        this.resetSession()
        return false
      }
    },
    async logoutAction() {
      try {
        await logout()
      } finally {
        this.resetSession()
      }
    }
  }
})
