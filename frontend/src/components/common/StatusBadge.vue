<template>
  <span class="status-badge" :class="`tone-${tone}`">{{ text }}</span>
</template>

<script setup>
defineProps({
  text: {
    type: String,
    required: true
  },
  tone: {
    type: String,
    default: 'primary'
  }
})
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 700;
}

.tone-primary {
  background: #eaf3fb;
  color: var(--color-primary);
}

.tone-success {
  background: #eaf9f2;
  color: var(--color-success);
}

.tone-warning {
  background: #fff6e6;
  color: var(--color-warning);
}

.tone-danger {
  background: #fff1f1;
  color: var(--color-danger);
}

.tone-neutral {
  background: #eef4f7;
  color: var(--color-text-soft);
}
</style>
