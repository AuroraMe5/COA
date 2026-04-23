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
  padding: 22px 20px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 251, 252, 0.96));
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-card);
}

.stat-label {
  font-size: 13px;
  color: var(--color-text-soft);
}

.stat-value {
  margin-top: 14px;
  font-size: 34px;
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

.tone-secondary .stat-value {
  color: var(--color-secondary);
}

.tone-success .stat-value {
  color: var(--color-success);
}

.tone-warning .stat-value {
  color: var(--color-warning);
}
</style>
