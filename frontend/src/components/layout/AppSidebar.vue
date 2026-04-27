<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark">CO</div>
      <div>
        <div class="brand-title">教学目标达成系统</div>
        <div class="brand-sub">教师端工作台</div>
      </div>
    </div>

    <div class="nav-stack">
      <section v-for="group in menuGroups" :key="group.title" class="nav-group">
        <h3>{{ group.title }}</h3>
        <router-link
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: isActive(item.to) }"
        >
          <span class="nav-dot"></span>
          <span>{{ item.label }}</span>
        </router-link>
      </section>
    </div>
  </aside>
</template>

<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()

// 侧边栏按照实际业务流程组织，便于用户理解“先管目标，再采集数据，最后分析改进”。
const menuGroups = [
  {
    title: '工作台',
    items: [{ label: '首页概览', to: '/dashboard' }]
  },
  {
    title: '教学目标管理',
    items: [
      { label: '课程管理', to: '/objectives/outlines' },
      { label: '智能解析导入', to: '/objectives/parse-import' }
    ]
  },
  {
    title: '数据采集',
    items: [
      { label: '成绩批量导入', to: '/collect/grades' },
      { label: '学生成绩管理', to: '/collect/grades/manage' }
    ]
  },
  {
    title: '结果分析与教学改进',
    items: [
      { label: '达成度核算与报告', to: '/analysis/calculation' }
    ]
  }
]

function isActive(path) {
  // 编辑页不单独出现在侧边栏，仍归到“课程管理”入口。
  if (
    path === '/objectives/outlines' &&
    route.path.startsWith('/objectives/edit')
  ) {
    return true
  }

  // '/collect/grades' must not match when on '/collect/grades/manage'
  if (path === '/collect/grades') {
    return route.path === '/collect/grades'
  }
  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<style scoped>
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  padding: 18px 14px;
  background: var(--color-primary-deep);
  color: #fff;
  overflow: auto;
  border-right: 1px solid rgba(255, 255, 255, 0.12);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 8px 20px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  letter-spacing: 0;
}

.brand-title {
  font-size: 16px;
  font-weight: 700;
}

.brand-sub {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.72);
}

.nav-stack {
  display: grid;
  gap: 16px;
}

.nav-group h3 {
  margin: 0 0 8px;
  padding: 0 8px;
  font-size: 12px;
  letter-spacing: 0;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.62);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 40px;
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  color: rgba(255, 255, 255, 0.84);
  transition: background 0.16s ease, color 0.16s ease;
}

.nav-item + .nav-item {
  margin-top: 4px;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
}

.nav-item.active {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}

.nav-dot {
  width: 3px;
  height: 18px;
  border-radius: 999px;
  background: transparent;
  flex-shrink: 0;
}

.nav-item.active .nav-dot {
  background: #9ee7d9;
}

@media (max-width: 1080px) {
  .sidebar {
    position: static;
    height: auto;
  }
}
</style>
