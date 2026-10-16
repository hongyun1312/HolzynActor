<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <div ref="el" class="echart-box" :style="{ height }"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'

/**
 * ECharts 轻量封装。
 * - 传入 option（响应式）自动 setOption
 * - ResizeObserver 自适应容器
 * - 卸载释放 GPU/实例
 * - **echarts 按需加载**（V2.0 §8.3）：静态 `import 'echarts'` 会让每个引用本组件的页面
 *   首屏无条件下载约 1.1 MB（gzip 361 KB）——实测沙盘直落就吃掉了 360 KB。
 *   改为首次真正要画时再 `import()`：首屏不含 echarts，图表页只多一次带缓存的异步请求。
 */
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' }
})

const el = ref(null)
let chart = null
let resizeObserver = null
/** echarts 模块命名空间（动态 import 的结果，全局共用一次加载） */
let echartsLib = null

async function ensureEcharts() {
  if (!echartsLib) echartsLib = await import('echarts')
  return echartsLib
}

function render() {
  if (!chart) return
  chart.setOption(props.option, true)
}

onMounted(async () => {
  const ec = await ensureEcharts()
  // 异步等待期间组件可能已卸载（快速切页）：此时不得再 init，否则画布泄漏
  if (!el.value || !ec) return
  chart = ec.init(el.value, null, { renderer: 'canvas' })
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
