<template>
  <section class="panel-card">
    <header v-if="title || $slots.actions" class="panel-head">
      <div>
        <h3 v-if="title">{{ title }}</h3>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="panel-actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="panel-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
defineProps({
  title: {
    type: String,
    default: ''
  },
  subtitle: {
    type: String,
    default: ''
  }
})
</script>

<style scoped>
.panel-card {
  background: var(--bg-panel);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  padding: 16px 18px;
  border-bottom: 1px solid var(--color-border);
  background: var(--bg-panel-soft);
}

.panel-head h3 {
  margin: 0;
  font-size: 16px;
  color: var(--color-primary-deep);
}

.panel-head p {
  margin: 7px 0 0;
  font-size: 13px;
  color: var(--color-text-soft);
  line-height: 1.6;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.panel-body {
  padding: 18px;
}
</style>
