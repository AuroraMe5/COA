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

const menuGroups = [
  {
    title: '工作台',
    items: [{ label: '首页概览', to: '/dashboard' }]
  },
  {
    title: '教学目标',
    items: [
      { label: '课程大纲管理', to: '/objectives/outlines' },
      { label: '教学目标管理', to: '/objectives/list' },
      { label: '目标考核映射', to: '/objectives/mapping' }
    ]
  },
  {
    title: '数据采集',
    items: [
      { label: '成绩批量导入', to: '/collect/grades' },
      { label: '学生评价录入', to: '/collect/evaluations' },
      { label: '教学反思录入', to: '/collect/reflections' },
      { label: '督导评价查看', to: '/collect/supervisors' }
    ]
  },
  {
    title: '结果分析与教学改进',
    items: [
      { label: '达成度核算', to: '/analysis/calculation' },
      { label: '多维分析报表', to: '/analysis/overview' },
      { label: '智能建议中心', to: '/analysis/suggestions' },
      { label: '改进措施跟踪', to: '/analysis/improvements' }
    ]
  }
]

function isActive(path) {
  if (path === '/objectives/list' && (
    route.path.startsWith('/objectives/edit') ||
    route.path.startsWith('/objectives/weights') ||
    route.path.startsWith('/objectives/parse-import')
  )) {
    return true
  }

  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<style scoped>
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  padding: 26px 18px;
  background:
    radial-gradient(circle at top, rgba(255, 255, 255, 0.12), transparent 30%),
    linear-gradient(180deg, #163f61 0%, #1d567f 45%, #0f7e73 100%);
  color: #fff;
  overflow: auto;
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 10px 26px;
}

.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.brand-title {
  font-size: 18px;
  font-weight: 700;
}

.brand-sub {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.72);
}

.nav-stack {
  display: grid;
  gap: 18px;
}

.nav-group h3 {
  margin: 0 0 10px;
  padding: 0 10px;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.62);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 14px;
  color: rgba(255, 255, 255, 0.84);
  transition: background 0.16s ease, transform 0.16s ease;
}

.nav-item + .nav-item {
  margin-top: 4px;
}

.nav-item:hover {
  transform: translateX(2px);
  background: rgba(255, 255, 255, 0.08);
}

.nav-item.active {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}

.nav-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
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
