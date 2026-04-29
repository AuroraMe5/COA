<template>
  <router-view />
  <FeedbackDialog
    :open="feedbackState.open"
    :type="feedbackState.type"
    :title="feedbackState.title"
    :message="feedbackState.message"
    :confirm-mode="feedbackState.confirmMode"
    :confirm-text="feedbackState.confirmText"
    :cancel-text="feedbackState.cancelText"
    @confirm="closeFeedback(true)"
    @cancel="closeFeedback(false)"
  />
</template>

<script setup>
import FeedbackDialog from '@/components/common/FeedbackDialog.vue'
import { closeFeedback, feedbackState } from '@/utils/feedback'
</script>
