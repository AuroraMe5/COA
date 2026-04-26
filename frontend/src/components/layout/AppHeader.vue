<template>
  <header class="header">
    <div class="header-brand">
      <div class="header-eyebrow">教师端</div>
      <div class="header-title">高校课程教学目标达成统计及分析系统</div>
      <div class="header-caption">聚焦教学目标管理、数据采集、达成度核算与教学改进闭环</div>
    </div>

    <div class="header-actions">
      <div class="user-card">
        <div class="user-name">{{ authStore.userInfo?.realName || '教师用户' }}</div>
        <div class="user-meta">{{ authStore.userInfo?.collegeName || '课程教学团队' }}</div>
      </div>
      <button class="btn btn-light" @click="handleLogout">退出登录</button>
    </div>
  </header>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

async function handleLogout() {
  // 先清理本地登录状态，再回到登录页，避免界面残留上一位用户的信息。
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
  position: sticky;
  top: 0;
  z-index: 8;
  padding: 14px 24px;
  background: var(--bg-panel);
  border-bottom: 1px solid var(--color-border);
}

.header-brand {
  display: grid;
  gap: 6px;
}

.header-eyebrow {
  font-size: 12px;
  letter-spacing: 0;
  text-transform: uppercase;
  color: var(--color-text-soft);
}

.header-title {
  font-size: 17px;
  font-weight: 800;
  color: var(--color-primary-deep);
}

.header-caption {
  font-size: 13px;
  color: var(--color-text-soft);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-card {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--bg-panel-soft);
  border: 1px solid var(--color-border);
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
    padding: 14px 16px;
    flex-direction: column;
    align-items: flex-start;
  }

  .header-actions {
    width: 100%;
    justify-content: space-between;
  }

  .header-caption {
    display: none;
  }
}
</style>
