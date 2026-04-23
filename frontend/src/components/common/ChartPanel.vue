<template>
  <div ref="chartRef" class="chart-panel"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  option: {
    type: Object,
    required: true
  },
  height: {
    type: String,
    default: '320px'
  }
})

const chartRef = ref(null)
let chartInstance = null

function resizeChart() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

async function renderChart() {
  await nextTick()
  if (!chartRef.value) {
    return
  }

  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }

  chartInstance.setOption(props.option, true)
}

watch(
  () => props.option,
  () => {
    renderChart()
  },
  { deep: true }
)

onMounted(() => {
  if (chartRef.value) {
    chartRef.value.style.height = props.height
  }
  renderChart()
  window.addEventListener('resize', resizeChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.chart-panel {
  width: 100%;
}
</style>
