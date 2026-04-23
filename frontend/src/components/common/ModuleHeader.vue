<template>
  <div class="module-header">
    <div class="page-title">
      <div>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="toolbar">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="tabs.length" class="module-tabs">
      <router-link
        v-for="tab in tabs"
        :key="tab.to"
        :to="tab.to"
        class="module-tab"
        :class="{ active: isActive(tab) }"
      >
        {{ tab.label }}
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'

defineProps({
  title: {
    type: String,
    required: true
  },
  description: {
    type: String,
    default: ''
  },
  tabs: {
    type: Array,
    default: () => []
  }
})

const route = useRoute()

function isActive(tab) {
  const matches = tab.matches || [tab.match || tab.to]
  return matches.some((match) => route.path === match || route.path.startsWith(match))
}
</script>

<style scoped>
.module-header {
  display: grid;
  gap: 14px;
}

.module-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-soft);
}

.module-tab {
  padding: 10px 16px;
  border-radius: 12px;
  color: var(--color-text-soft);
  font-weight: 600;
  transition: background 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.module-tab:hover {
  transform: translateY(-1px);
  background: rgba(31, 95, 139, 0.06);
}

.module-tab.active {
  background: linear-gradient(135deg, rgba(31, 95, 139, 0.12), rgba(15, 138, 120, 0.12));
  color: var(--color-primary-deep);
  box-shadow: inset 0 0 0 1px rgba(31, 95, 139, 0.12);
}
</style>
