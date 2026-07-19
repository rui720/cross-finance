<script setup>
// 可视化图表封装组件：基于 ECharts，自动响应 resize
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: {
    type: Object,
    required: true
  },
  width: {
    type: String,
    default: '100%'
  },
  height: {
    type: String,
    default: '320px'
  }
})

const chartRef = ref(null)
let chartInstance = null

function initChart() {
  if (chartRef.value) {
    chartInstance = echarts.init(chartRef.value)
    chartInstance.setOption(props.option)
  }
}

function resize() {
  chartInstance && chartInstance.resize()
}

watch(
  () => props.option,
  (val) => {
    chartInstance && chartInstance.setOption(val, true)
  },
  { deep: true }
)

onMounted(async () => {
  await nextTick()
  initChart()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chartInstance && chartInstance.dispose()
  chartInstance = null
})
</script>

<template>
  <div ref="chartRef" :style="{ width, height }"></div>
</template>
