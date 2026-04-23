<template>
  <div class="auth-page">
    <section class="auth-hero">
      <div>
        <div class="auth-badge">CO</div>
        <h1 class="auth-title">高校课程教学目标达成统计及分析系统</h1>
        <p class="auth-subtitle">
          面向教师日常教学闭环，贯通课程目标管理、数据采集、达成度核算、结果分析与教学改进。
        </p>
      </div>

      <div class="auth-meta">
        <div class="auth-meta-item">
          <strong>核心能力</strong>
          <div class="mt-8">教学目标管理、智能解析导入、成绩采集、达成度分析、教学改进闭环。</div>
        </div>
        <div class="auth-meta-item">
          <strong>登录说明</strong>
          <div class="mt-8">请输入已分配的教师账号信息登录系统。</div>
        </div>
      </div>
    </section>

    <section class="auth-card">
      <div class="page-title">
        <div>
          <h1>系统登录</h1>
          <p>请输入教师账号信息进入工作台。</p>
        </div>
      </div>

      <form class="form-stack mt-16" @submit.prevent="handleLogin">
        <div class="form-field">
          <label for="username">用户名</label>
          <input id="username" v-model.trim="form.username" class="text-input" autocomplete="username" />
        </div>
        <div class="form-field">
          <label for="password">密码</label>
          <input
            id="password"
            v-model.trim="form.password"
            class="text-input"
            type="password"
            autocomplete="current-password"
          />
        </div>
        <div class="form-field">
          <label class="muted">
            <input v-model="form.remember" type="checkbox" />
            保持登录状态
          </label>
        </div>

        <div v-if="errorMessage" class="notice error">{{ errorMessage }}</div>

        <button class="btn btn-primary w-full" :disabled="submitting">
          {{ submitting ? '登录中...' : '进入系统' }}
        </button>
      </form>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const form = reactive({
  username: '',
  password: '',
  remember: true
})

const submitting = ref(false)
const errorMessage = ref('')

async function handleLogin() {
  errorMessage.value = ''
  submitting.value = true

  try {
    await authStore.loginAction(form)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.push(redirect)
  } catch (error) {
    errorMessage.value = error.message || '登录失败，请检查账号信息。'
  } finally {
    submitting.value = false
  }
}
</script>
