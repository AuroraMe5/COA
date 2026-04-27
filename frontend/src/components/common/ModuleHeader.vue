<template>
  <div class="module-header">
    <div class="page-title">
      <div>
        <h1>{{ title }}</h1>
        <p v-if="description">{{ description }}</p>
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
  gap: 12px;
}

.module-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px;
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-soft);
}

.module-tab {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  color: var(--color-text-soft);
  font-weight: 600;
  transition: background 0.16s ease, color 0.16s ease;
}

.module-tab:hover {
  background: rgba(31, 95, 139, 0.06);
}

.module-tab.active {
  background: var(--bg-accent-soft);
  color: var(--color-primary-deep);
  box-shadow: inset 0 0 0 1px rgba(31, 95, 139, 0.12);
}
</style>
