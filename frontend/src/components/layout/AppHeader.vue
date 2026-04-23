<template>
  <header class="header">
    <div>
      <div class="header-eyebrow">教师端</div>
      <h1>{{ route.meta.title || '教学目标达成系统' }}</h1>
    </div>
    <div class="header-actions">
      <div class="user-card">
        <div class="user-name">{{ authStore.userInfo?.realName || '教师用户' }}</div>
        <div class="user-meta">
          {{ authStore.userInfo?.collegeName || '课程教学团队' }}
        </div>
      </div>
      <button class="btn btn-light" @click="handleLogout">退出登录</button>
    </div>
  </header>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

async function handleLogout() {
  await authStore.logoutAction()
  router.push('/login')
}
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 28px 0;
}

.header h1 {
  margin: 4px 0 0;
  font-size: 26px;
  color: var(--color-primary-deep);
}

.header-eyebrow {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-soft);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-card {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-soft);
}

.user-name {
  font-size: 14px;
  font-weight: 700;
}

.user-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-soft);
}

@media (max-width: 720px) {
  .header {
    padding: 18px 18px 0;
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
