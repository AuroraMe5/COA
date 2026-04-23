<template>
  <div class="app-page page-stack">
    <div class="page-title">
      <div>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
    </div>
    <PanelCard>
      <EmptyState
        :title="`${title}模块待扩展`"
        :description="`${description} 当前已保留完整路由和导航入口，后续可继续在此基础上接入真实业务页面。`"
      />
    </PanelCard>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PanelCard from '@/components/common/PanelCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()

const title = computed(() => route.meta.placeholderTitle || '占位页面')
const description = computed(
  () => route.meta.placeholderDescription || '该页面已接入布局与导航，但当前版本暂未展开详细业务实现。'
)
</script>
