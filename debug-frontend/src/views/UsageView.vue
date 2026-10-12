<template>
  <div class="usage">
    <h2 class="page-title">用量图</h2>

    <div class="toolbar">
      <el-radio-group v-model="days" size="small" @change="load">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
      <template v-if="total">
        <el-tag size="small" effect="plain">调用 {{ total.count }} 次</el-tag>
        <el-tag size="small" effect="plain">输入 {{ fmt(total.tokenIn) }} tokens</el-tag>
        <el-tag size="small" effect="plain">输出 {{ fmt(total.tokenOut) }} tokens</el-tag>
        <el-tag size="small" effect="plain" type="info">缓存命中 {{ total.cacheHit }} / 未命中 {{ total.cacheMiss }}</el-tag>
        <el-tag size="small" effect="plain" type="warning">平均耗时 {{ total.avgDurationMs }}ms</el-tag>
      </template>
    </div>

    <div class="charts">
      <el-card shadow="never" class="chart-card">
        <template #header><div class="card-title">按天趋势（token 柱状 + 调用次数折线）</div></template>
        <EChart :option="dayOption" height="300px" />
      </el-card>

      <div class="chart-grid">
        <el-card shadow="never" class="chart-card">
          <template #header><div class="card-title">按场景占比</div></template>
          <EChart :option="scenePieOption" height="300px" />
        </el-card>
        <el-card shadow="never" class="chart-card">
          <template #header><div class="card-title">按项目分布（token）</div></template>
          <EChart :option="projectBarOption" height="300px" />
        </el-card>
      </div>

      <el-card shadow="never" class="chart-card">
        <template #header><div class="card-title">平均耗时趋势（ms）</div></template>
        <EChart :option="durationOption" height="280px" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { get } from '../api'
import EChart from '../components/EChart.vue'

const days = ref(30)
const total = ref(null)
const byDay = ref([])
const byScene = ref([])
const byProject = ref([])

// 主题联动：监听 html[data-theme] / --accent 变化，触发图表配色重算（对齐 DSH 令牌）
const themeTick = ref(0)
let themeObserver = null

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

const axisColor = () => cssVar('--text-tertiary', '#8b9098')
const accentColor = () => cssVar('--accent', '#4176e6')
// 派生配色（与主色同色系，深浅可读）
const outColor = () => cssVar('--state-success', '#22c55e')
const countColor = () => cssVar('--state-warn', '#f59e0b')
const durColor = () => accentColor() // 耗时线沿用 accent

const dayOption = computed(() => {
  void themeTick.value // 依赖主题变化
  const dates = byDay.value.map((r) => r.date)
  const inData = byDay.value.map((r) => r.tokenIn)
  const outData = byDay.value.map((r) => r.tokenOut)
  const countData = byDay.value.map((r) => r.count)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['输入 token', '输出 token', '调用次数'], textStyle: { color: axisColor() } },
    grid: { left: 60, right: 60, top: 40, bottom: 50 },
    xAxis: { type: 'category', data: dates, axisLabel: { color: axisColor(), fontSize: 10 } },
    yAxis: [
      { type: 'value', name: 'token', axisLabel: { color: axisColor() } },
      { type: 'value', name: '次数', axisLabel: { color: axisColor() } }
    ],
    series: [
      { name: '输入 token', type: 'bar', data: inData, itemStyle: { color: accentColor() } },
      { name: '输出 token', type: 'bar', data: outData, itemStyle: { color: outColor() } },
      { name: '调用次数', type: 'line', yAxisIndex: 1, data: countData, itemStyle: { color: countColor() }, smooth: true }
    ]
  }
})

const scenePieOption = computed(() => {
  void themeTick.value
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 次 ({d}%)' },
    legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { color: axisColor(), fontSize: 11 } },
    color: [accentColor(), outColor(), countColor(), 'var(--accent-text)', '#c084fc', '#14b8a6', '#f472b6', '#60a5fa'],
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      center: ['40%', '50%'],
      label: { formatter: '{b}', fontSize: 10, color: axisColor() },
      data: byScene.value.map((r) => ({ name: r.sceneName || r.scene, value: r.count }))
    }]
  }
})

const projectBarOption = computed(() => {
  void themeTick.value
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['输入 token', '输出 token', '调用次数'], textStyle: { color: axisColor() } },
    grid: { left: 60, right: 60, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: byProject.value.map((r) => `#${r.projectId}`), axisLabel: { color: axisColor() } },
    yAxis: { type: 'value', axisLabel: { color: axisColor() } },
    series: [
      { name: '输入 token', type: 'bar', data: byProject.value.map((r) => r.tokenIn), itemStyle: { color: accentColor() } },
      { name: '输出 token', type: 'bar', data: byProject.value.map((r) => r.tokenOut), itemStyle: { color: outColor() } },
      { name: '调用次数', type: 'line', data: byProject.value.map((r) => r.count), itemStyle: { color: countColor() }, smooth: true }
    ]
  }
})

const durationOption = computed(() => {
  void themeTick.value
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 40, top: 30, bottom: 50 },
    xAxis: { type: 'category', data: byDay.value.map((r) => r.date), axisLabel: { color: axisColor(), fontSize: 10 } },
    yAxis: { type: 'value', name: 'ms', axisLabel: { color: axisColor() } },
    series: [{
      name: '平均耗时',
      type: 'line',
      smooth: true,
      data: byDay.value.map((r) => r.avgDurationMs),
      itemStyle: { color: durColor() },
      areaStyle: { opacity: 0.12 }
    }]
  }
})

function fmt(n) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

async function load() {
  try {
    const r = await get('/debug/usage/stats', { days: days.value })
    total.value = r.total || null
    byDay.value = r.byDay || []
    byScene.value = r.byScene || []
    byProject.value = r.byProject || []
  } catch (e) {
    console.error('用量加载失败', e)
  }
}

onMounted(() => {
  load()
  // 深浅主题/主色切换 → 图表配色跟随刷新
  themeObserver = new MutationObserver(() => { themeTick.value++ })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] })
})

onBeforeUnmount(() => themeObserver?.disconnect())
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.chart-card {
  margin-bottom: 14px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
}
.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.card-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
}
@media (max-width: 1100px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
