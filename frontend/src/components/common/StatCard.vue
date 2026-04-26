<template>
  <article class="stat-card" :class="`tone-${tone}`">
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value">{{ value }}</div>
    <div v-if="helper" class="stat-helper">{{ helper }}</div>
  </article>
</template>

<script setup>
defineProps({
  label: {
    type: String,
    required: true
  },
  value: {
    type: [String, Number],
    required: true
  },
  helper: {
    type: String,
    default: ''
  },
  tone: {
    type: String,
    default: 'primary'
  }
})
</script>

<style scoped>
.stat-card {
  min-height: 112px;
  padding: 16px 18px;
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
  border: 1px solid var(--color-border);
  border-left: 4px solid var(--color-primary);
  box-shadow: var(--shadow-card);
}

.stat-label {
  font-size: 13px;
  color: var(--color-text-soft);
}

.stat-value {
  margin-top: 12px;
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
}

.stat-helper {
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-text-soft);
}

.tone-primary .stat-value {
  color: var(--color-primary);
}

.tone-primary {
  border-left-color: var(--color-primary);
}

.tone-secondary .stat-value {
  color: var(--color-secondary);
}

.tone-secondary {
  border-left-color: var(--color-secondary);
}

.tone-success .stat-value {
  color: var(--color-success);
}

.tone-success {
  border-left-color: var(--color-success);
}

.tone-warning .stat-value {
  color: var(--color-warning);
}

.tone-warning {
  border-left-color: var(--color-warning);
}
</style>
