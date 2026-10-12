<template>
  <div ref="el" class="echart-box" :style="{ height }"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 轻量封装（调试前端用量图等）。
 * - 传入 option（响应式）自动 setOption
 * - ResizeObserver 自适应容器
 * - 深浅主题切换时自动重设背景色（保持透明背景，由页面 CSS 控制）
 */
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' }
})

const el = ref(null)
let chart = null
let resizeObserver = null

function render() {
  if (!chart) return
  chart.setOption(props.option, true)
}

onMounted(() => {
  chart = echarts.init(el.value, null, { renderer: 'canvas' })
  render()
  resizeObserver = new ResizeObserver(() => chart && chart.resize())
  resizeObserver.observe(el.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })
</script>

<style scoped>
.echart-box {
  width: 100%;
  min-height: 200px;
}
</style>
