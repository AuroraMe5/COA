import { defineStore } from 'pinia'
import { getCurrentUser, login, logout } from '@/api'

const AUTH_STORAGE_KEY = 'coa-teach-auth'

function loadPersistedAuth() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (error) {
    return null
  }
}

function persistAuth(payload) {
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(payload))
}

function clearAuth() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: '',
    refreshToken: '',
    expiresIn: 0,
    userInfo: null,
    initialized: false
  }),
  getters: {
    isAuthenticated(state) {
      return Boolean(state.accessToken && state.userInfo)
    }
  },
  actions: {
    hydrate() {
      const saved = loadPersistedAuth()
      if (saved) {
        this.accessToken = saved.accessToken || ''
        this.refreshToken = saved.refreshToken || ''
        this.expiresIn = saved.expiresIn || 0
        this.userInfo = saved.userInfo || null
      }
      this.initialized = true
    },
    syncStorage() {
      if (!this.accessToken || !this.userInfo) {
        clearAuth()
        return
      }

      persistAuth({
        accessToken: this.accessToken,
        refreshToken: this.refreshToken,
        expiresIn: this.expiresIn,
        userInfo: this.userInfo
      })
    },
    async loginAction(credentials) {
      const response = await login(credentials)
      this.accessToken = response.accessToken
      this.refreshToken = response.refreshToken
      this.expiresIn = response.expiresIn
      this.userInfo = response.userInfo
      this.syncStorage()
      return response
    },
    async fetchCurrentUser() {
      if (!this.accessToken) {
        return null
      }

      const user = await getCurrentUser(this.accessToken)
      this.userInfo = user
      this.syncStorage()
      return user
    },
    async logoutAction() {
      try {
        await logout()
      } finally {
        this.accessToken = ''
        this.refreshToken = ''
        this.expiresIn = 0
        this.userInfo = null
        clearAuth()
      }
    }
  }
})
