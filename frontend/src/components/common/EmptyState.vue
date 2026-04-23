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
  padding: 48px 22px;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(240, 248, 250, 0.9), rgba(255, 255, 255, 0.95));
  border: 1px dashed var(--color-border);
}

.empty-mark {
  width: 62px;
  height: 62px;
  margin: 0 auto 18px;
  border-radius: 20px;
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
  margin: 10px auto 0;
  line-height: 1.8;
  color: var(--color-text-soft);
}
</style>
