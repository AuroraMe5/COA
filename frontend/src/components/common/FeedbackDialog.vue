<template>
  <Teleport to="body">
    <div v-if="open" class="feedback-backdrop" @click.self="handleBackdrop">
      <section
        class="feedback-panel"
        :class="`feedback-${type}`"
        role="alertdialog"
        aria-modal="true"
        :aria-label="title"
      >
        <header class="feedback-head">
          <span class="feedback-icon">{{ iconText }}</span>
          <div>
            <h2>{{ title }}</h2>
            <p v-for="(line, index) in messageLines" :key="`${index}-${line}`">{{ line }}</p>
          </div>
        </header>

        <footer class="feedback-actions">
          <button v-if="confirmMode" class="btn btn-light" type="button" @click="$emit('cancel')">
            {{ cancelText }}
          </button>
          <button class="btn" :class="confirmMode ? 'btn-danger' : primaryButtonClass" type="button" @click="$emit('confirm')">
            {{ confirmMode ? confirmText : '知道了' }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  },
  type: {
    type: String,
    default: 'info'
  },
  title: {
    type: String,
    default: '提示'
  },
  message: {
    type: String,
    default: ''
  },
  confirmMode: {
    type: Boolean,
    default: false
  },
  confirmText: {
    type: String,
    default: '确定'
  },
  cancelText: {
    type: String,
    default: '取消'
  }
})

const emit = defineEmits(['confirm', 'cancel'])

const messageLines = computed(() => String(props.message || '').split(/\n+/).filter(Boolean))

const iconText = computed(() => {
  if (props.type === 'success') return 'OK'
  if (props.type === 'error') return '!'
  if (props.type === 'warning') return '!'
  return 'i'
})

const primaryButtonClass = computed(() => (props.type === 'error' ? 'btn-danger' : 'btn-primary'))

function handleBackdrop() {
  emit(props.confirmMode ? 'cancel' : 'confirm')
}
</script>

<style scoped>
.feedback-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.42);
  backdrop-filter: blur(2px);
}

.feedback-panel {
  width: min(460px, 100%);
  border-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  background: #ffffff;
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.24);
  overflow: hidden;
}

.feedback-head {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 14px;
  padding: 22px 22px 18px;
}

.feedback-icon {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0;
}

.feedback-head h2 {
  margin: 0;
  color: #102331;
  font-size: 18px;
}

.feedback-head p {
  margin: 8px 0 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.7;
}

.feedback-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 22px 20px;
  background: #f8fafc;
}

.feedback-success .feedback-icon {
  color: var(--color-success);
  background: #eaf9f2;
}

.feedback-error .feedback-icon {
  color: var(--color-danger);
  background: #fff1f1;
}

.feedback-warning .feedback-icon {
  color: var(--color-warning);
  background: #fff7e8;
}

.feedback-info .feedback-icon {
  color: var(--color-primary);
  background: #eaf4fb;
}

@media (max-width: 640px) {
  .feedback-backdrop {
    align-items: end;
    padding: 14px;
  }

  .feedback-panel {
    width: 100%;
  }

  .feedback-actions {
    flex-direction: column-reverse;
  }
}
</style>
