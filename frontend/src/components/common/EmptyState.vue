<template>
  <div class="empty-state">
    <div class="empty-mark">{{ mark }}</div>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
  </div>
</template>

<script setup>
defineProps({
  mark: {
    type: String,
    default: '空'
  },
  title: {
    type: String,
    required: true
  },
  description: {
    type: String,
    required: true
  }
})
</script>

<style scoped>
.empty-state {
  text-align: center;
  padding: 34px 20px;
  border-radius: var(--radius-lg);
  background: var(--bg-panel-soft);
  border: 1px dashed var(--color-border);
}

.empty-mark {
  width: 48px;
  height: 48px;
  margin: 0 auto 14px;
  border-radius: var(--radius-md);
  background: var(--bg-accent-soft);
  color: var(--color-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 800;
}

.empty-state h3 {
  margin: 0;
  color: var(--color-primary-deep);
}

.empty-state p {
  max-width: 460px;
  margin: 8px auto 0;
  line-height: 1.7;
  color: var(--color-text-soft);
}
</style>
