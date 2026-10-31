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
  <!--
    新首页右栏「世界演化面板」（M4，features/home/components）。
    职责：事件流（实时）+ 宏观曲线（粮价/民怨，echarts）+ 中观组织/二阶涌现 + AI 运行状态。
    数据全部由父组件传入（本组件纯展示；事件流清空/曲线打开由父组件派发）。
  -->
  <div class="hm-evolution">
    <!-- AI 运行状态条（细，一行） -->
    <div class="hm-ai" :class="{ busy: aiRuntime?.inflight > 0 }" :title="aiHint">
      <span class="hm-ai-dot" />
      <span class="hm-ai-text">
        {{ aiRuntime?.inflight > 0 ? `AI 推理中（${aiRuntime.inflight}）` : 'AI 空闲' }}
      </span>
      <span v-if="aiRuntime?.recentTotal" class="hm-ai-meta">
        近 {{ aiWindow }} 分钟 {{ aiRuntime.recentTotal }} 次 · 失败 {{ aiRuntime.recentFailed || 0 }}
      </span>
    </div>

    <!-- 宏观社会（第 65 次：折叠小节；折叠态仍带关键指标，展开看曲线） -->
    <section class="hm-sec" :class="{ open: curveOpen }">
      <button type="button" class="hm-sec-head" :aria-expanded="curveOpen" @click="emit('toggle-curve')">
        <span class="hm-sec-title">宏观社会</span>
        <template v-if="macro">
          <span v-if="macro.season" class="hm-macro-sec" :class="'season-' + (macro.season || '')">{{ seasonName(macro.season) }}</span>
          <span class="hm-sec-metric">粮价 <b>{{ macro.grainPrice != null ? Math.round(macro.grainPrice) : '—' }}</b></span>
          <span class="hm-sec-metric" :class="grievanceCls(macro.grievance)">民怨 <b>{{ macro.grievance != null ? Math.round(macro.grievance) : '—' }}</b>/100</span>
          <span class="hm-sec-metric">收成 <b>{{ (macro.harvestFactor || 0).toFixed(2) }}</b></span>
        </template>
        <span v-else class="hm-sec-metric">未初始化</span>
        <span class="hm-sec-caret">{{ curveOpen ? '▾' : '▸' }}</span>
      </button>
      <div v-show="curveOpen" class="hm-sec-body">
        <div ref="curveEl" class="hm-curve-canvas" />
        <div v-if="!curveData.length" class="hm-curve-empty">暂无曲线数据（世界模拟推进后每日快照产生）</div>
      </div>
    </section>

    <!-- 中观组织 / 二阶涌现（折叠小节，默认收起：低频信息不占视线） -->
    <section v-if="mesoGroups.length || emergenceEvents.length" class="hm-sec" :class="{ open: orgOpen }">
      <button type="button" class="hm-sec-head" :aria-expanded="orgOpen" @click="orgOpen = !orgOpen">
        <span class="hm-sec-title">组织与涌现</span>
        <span class="hm-sec-metric">
          <b>{{ mesoGroups.length }}</b> 个组织 · <b>{{ emergenceEvents.length }}</b> 条涌现
        </span>
        <span class="hm-sec-caret">{{ orgOpen ? '▾' : '▸' }}</span>
      </button>
      <div v-show="orgOpen" class="hm-sec-body is-scroll">
        <div v-if="mesoGroups.length" class="hm-meso-section">
          <div class="hm-meso-title">中观组织</div>
          <div v-for="g in mesoGroups" :key="g.id" class="hm-meso-row">
            <span class="hm-g-name">{{ g.name }}</span>
            <el-tag size="small" effect="plain">{{ groupTypeLabel(g.type) }}</el-tag>
            <span class="hm-g-meta">影响 {{ g.influence != null ? Math.round(g.influence) : '—' }} · {{ g.memberCount ?? 0 }} 人</span>
          </div>
        </div>
        <div v-if="emergenceEvents.length" class="hm-meso-section">
          <div class="hm-meso-title">二阶涌现</div>
          <div v-for="ev in emergenceEvents" :key="ev.id" class="hm-meso-row">
            <el-tag size="small" :type="macroEvtTag(ev.severity)" effect="plain">{{ ev.emergenceTypeDisplay || ev.emergenceType }}</el-tag>
            <span class="hm-ev-text">{{ ev.narrative || '' }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 事件流（实时；占满剩余高度） -->
    <div class="hm-feed">
      <div class="hm-feed-head">
        <span class="hm-feed-title">世界实时事件流</span>
        <el-tag v-if="feed.length" size="small" effect="plain">{{ feed.length }} 条</el-tag>
        <el-button text size="small" class="hm-feed-clear" @click="emit('clear-feed')">清空</el-button>
      </div>
      <div ref="feedListEl" class="hm-feed-list">
        <div v-for="(it, i) in feed" :key="i" class="hm-feed-item" :class="it.kind">
          <span class="hm-feed-time">{{ it.time }}</span>
          <span class="hm-feed-badge">{{ it.badge }}</span>
          <span class="hm-feed-text">{{ it.text }}</span>
        </div>
        <div v-if="!feed.length" class="hm-feed-empty">
          世界运行时，角色的移动、对话、想法与世界事件会实时出现在这里
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 新首页右栏「世界演化面板」（M4）。
 * <p>职责：AI 运行状态 + 宏观曲线（echarts 折线：粮价/民怨/市场活跃度）+ 中观组织/二阶涌现
 * + 世界实时事件流。数据由父组件传入；曲线打开状态由父组件持有（本组件仅渲染）。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  /** 事件流（{kind, badge, text, time}[]） */
  feed: { type: Array, default: () => [] },
  /** 宏观状态 */
  macro: { type: Object, default: null },
  /** 宏观快照序列（曲线数据） */
  curveData: { type: Array, default: () => [] },
  /** 曲线展开 */
  curveOpen: { type: Boolean, default: false },
  /** 中观组织 */
  mesoGroups: { type: Array, default: () => [] },
  /** 二阶涌现 */
  emergenceEvents: { type: Array, default: () => [] },
  /** AI 运行状态摘要（useAiRuntime 快照） */
  aiRuntime: { type: Object, default: null },
  /** AI 统计窗口分钟 */
  aiWindow: { type: Number, default: 30 }
})
const emit = defineEmits(['toggle-curve', 'clear-feed'])

/** 组织与涌现小节是否展开（默认收起：低频信息不参与视线竞争） */
const orgOpen = ref(false)

const curveEl = ref(null)
const feedListEl = ref(null)
let chart = null
/** echarts 模块命名空间（首次真正要画曲线时动态 import，见 renderCurve） */
let echartsLib = null

/** 按需加载 echarts（模块级只加载一次，重复调用复用同一 Promise 结果）。 */
async function ensureEcharts() {
  if (!echartsLib) echartsLib = await import('echarts')
  return echartsLib
}

const aiHint = computed(() => {
  const r = props.aiRuntime || {}
  return `AI 运行状态：${r.inflight > 0 ? `${r.inflight} 推理中` : '空闲'}；近 ${props.aiWindow} 分钟 ${r.recentTotal || 0} 次调用，失败 ${r.recentFailed || 0} 次`
})

function seasonName(s) {
  return { spring: '春季', summer: '夏季', autumn: '秋季', winter: '冬季' }[s] || '—'
}
function grievanceCls(g) {
  return g >= 80 ? 'gv-high' : g >= 50 ? 'gv-mid' : ''
}
function macroEvtTag(sev) {
  return sev >= 5 ? 'danger' : sev === 4 ? 'warning' : sev === 3 ? 'primary' : 'info'
}
function groupTypeLabel(t) {
  return { family: '宗族', sect: '门派', guild: '行会', community: '结社', adhoc: '自发' }[t] || t || '—'
}

/** 读取当前主题下的 CSS 变量值（echarts 渲染到 canvas，无法直接引用 CSS var，需取值） */
function cssVar(name, fallback) {
  if (typeof document === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 曲线数据 → echarts option（粮价/民怨/市场活跃度三条折线） */
function buildCurveOption() {
  const rows = props.curveData || []
  const days = rows.map((r) => `D${(r.gameDay ?? 0) + 1}`)
  const line = (key, name, color) => ({
    name, type: 'line', smooth: true, symbol: 'circle', symbolSize: 4,
    data: rows.map((r) => r[key] != null ? Math.round(r[key] * 10) / 10 : null),
    lineStyle: { width: 1.8, color }, itemStyle: { color },
    connectNulls: true
  })
  return {
    grid: { left: 36, right: 12, top: 24, bottom: 24 },
    tooltip: { trigger: 'axis' },
    legend: { data: ['粮价', '民怨', '市场'], textStyle: { color: cssVar('--hm-text-dim', '#9db8e8'), fontSize: 10 }, top: 0 },
    xAxis: { type: 'category', data: days, axisLabel: { color: cssVar('--hm-text-faint', '#6b7fa8'), fontSize: 9 } },
    yAxis: { type: 'value', axisLabel: { color: cssVar('--hm-text-faint', '#6b7fa8'), fontSize: 9 }, splitLine: { lineStyle: { color: cssVar('--hm-border-soft', 'rgba(120,160,255,0.12)') } } },
    series: [
      line('grainPrice', '粮价', cssVar('--hm-gold', '#ffd45c')),
      line('grievance', '民怨', cssVar('--hm-red', '#ff6b6b')),
      line('marketActivity', '市场', cssVar('--hm-green', '#52d68a'))
    ]
  }
}

function renderCurve() {
  if (!props.curveOpen || !props.curveData.length) return
  nextTick(async () => {
    if (!curveEl.value) return
    // echarts 按需加载（V2.0 §8.3）：曲线面板默认折叠，静态 import 会让**首屏**无条件下载
    // 约 1.1 MB（gzip 361 KB）——实测沙盘直落最大的一笔 JS。只有真正要画时才 import。
    const ec = await ensureEcharts()
    if (!ec || !curveEl.value) return   // 异步等待期间可能已卸载/折叠
    if (!chart) chart = ec.init(curveEl.value)
    chart.setOption(buildCurveOption(), true)
  })
}

watch(() => [props.curveOpen, props.curveData], renderCurve)
onMounted(() => {
  renderCurve()
  // 容器尺寸变化时自适应（面板宽度拖拽/折叠）
  if (typeof ResizeObserver !== 'undefined') {
    resizeObs = new ResizeObserver(() => chart && chart.resize())
    if (curveEl.value) resizeObs.observe(curveEl.value)
  }
})
let resizeObs = null
onBeforeUnmount(() => {
  if (resizeObs) { try { resizeObs.disconnect() } catch (_) { /* 忽略 */ } }
  if (chart) { chart.dispose(); chart = null }
})

/** 事件流滚动到底（父组件推新事件时由 watch feed 触发） */
watch(() => props.feed.length, () => {
  nextTick(() => {
    const el = feedListEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
})
</script>

<style scoped>
.hm-evolution {
  display: flex; flex-direction: column;
  height: 100%; min-height: 0;
  gap: 8px;
  overflow: hidden;
}
/* AI 运行状态条 */
.hm-ai {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 8px;
  background: var(--hm-panel); border: 1px solid var(--hm-border);
  font-size: 13px; color: var(--hm-text-dim); flex-shrink: 0; cursor: help;
}
.hm-ai-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--hm-dot); flex-shrink: 0; }
.hm-ai.busy .hm-ai-dot { background: var(--hm-gold); box-shadow: 0 0 6px color-mix(in srgb, var(--hm-gold) 80%, transparent); animation: pulse 1.2s infinite; }
.hm-ai-text { font-weight: 600; white-space: nowrap; }
.hm-ai-meta { margin-left: auto; font-variant-numeric: tabular-nums; font-size: 12px; color: var(--hm-text-faint); }
/* 宏观条（折叠小节统一外观：表头一行带关键指标，展开才占高度） */
.hm-sec {
  border-radius: 10px; background: var(--hm-panel);
  border: 1px solid var(--hm-border);
  flex-shrink: 0; overflow: hidden;
}
.hm-sec.open { border-color: var(--hm-border-strong, var(--hm-border)); }
.hm-sec-head {
  width: 100%; display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  padding: 7px 10px; cursor: pointer; text-align: left;
  border: none; background: transparent; color: inherit;
  transition: background .2s var(--dsw-ease);
}
.hm-sec-head:hover { background: var(--accent-soft); }
.hm-sec-title { font-size: 12px; font-weight: 700; color: var(--hm-text); }
.hm-sec-metric { font-size: 12.5px; color: var(--hm-text-dim); font-variant-numeric: tabular-nums; }
.hm-sec-metric b { color: var(--hm-text-soft); font-weight: 700; }
.hm-sec-metric.gv-high b { color: var(--hm-red); }
.hm-sec-metric.gv-mid b { color: var(--hm-amber); }
.hm-sec-caret { margin-left: auto; font-size: 12px; color: var(--hm-text-faint); }
.hm-sec-body { padding: 0 10px 10px; }
.hm-sec-body.is-scroll { max-height: 34vh; overflow-y: auto; }
.hm-macro-sec {
  font-size: 12px; font-weight: 700; padding: 1px 7px; border-radius: 6px;
  color: var(--hm-season-text); border: 1px solid var(--hm-border-strong, var(--hm-border)); font-variant-numeric: tabular-nums;
}
.hm-macro-sec.season-spring { background: color-mix(in srgb, var(--hm-green) 22%, transparent); border-color: color-mix(in srgb, var(--hm-green) 55%, transparent); }
.hm-macro-sec.season-summer { background: color-mix(in srgb, var(--hm-amber) 22%, transparent); border-color: color-mix(in srgb, var(--hm-amber) 55%, transparent); }
.hm-macro-sec.season-autumn { background: color-mix(in srgb, var(--hm-orange) 22%, transparent); border-color: color-mix(in srgb, var(--hm-orange) 55%, transparent); }
.hm-macro-sec.season-winter { background: color-mix(in srgb, var(--hm-blue) 22%, transparent); border-color: color-mix(in srgb, var(--hm-blue) 55%, transparent); }
/* 曲线 */
.hm-curve-canvas { width: 100%; height: 170px; }
.hm-curve-empty { font-size: 12.5px; color: var(--hm-text-faint); text-align: center; padding: 30px 10px; }
/* 中观/涌现 */
.hm-meso-section { margin-bottom: 8px; }
.hm-meso-title { font-size: 13px; font-weight: 700; color: var(--hm-text-dim); margin-bottom: 4px; }
.hm-meso-row { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--hm-text-soft); margin-bottom: 3px; }
.hm-g-name { font-weight: 600; color: var(--hm-gold); white-space: nowrap; }
.hm-g-meta { margin-left: auto; font-size: 12px; color: var(--hm-text-faint); white-space: nowrap; }
.hm-ev-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 事件流 */
.hm-feed {
  flex: 1; min-height: 0;
  display: flex; flex-direction: column;
  background: var(--hm-panel); backdrop-filter: blur(8px);
  border: 1px solid var(--hm-border); border-radius: 12px;
  overflow: hidden;
}
.hm-feed-head {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-bottom: 1px solid var(--hm-border-soft); flex-shrink: 0;
}
.hm-feed-title { font-size: 12px; font-weight: 700; color: var(--hm-text-dim); }
.hm-feed-clear { margin-left: auto; color: var(--hm-text-faint); }
.hm-feed-list { flex: 1; overflow-y: auto; padding: 4px 12px; }
.hm-feed-item {
  display: flex; align-items: baseline; gap: 8px;
  font-size: 13px; color: var(--hm-text-soft); line-height: 1.7; white-space: nowrap;
}
.hm-feed-time { font-size: 12px; color: var(--hm-text-faint); flex-shrink: 0; font-variant-numeric: tabular-nums; }
.hm-feed-badge {
  flex-shrink: 0; font-size: 12px; font-weight: 700; padding: 0 6px; border-radius: 4px;
  color: var(--hm-page-bg); background: var(--hm-blue);
}
.hm-feed-item.talk .hm-feed-badge { background: var(--hm-green); }
.hm-feed-item.thought .hm-feed-badge { background: var(--hm-gold); }
.hm-feed-item.event .hm-feed-badge { background: var(--hm-orange); }
.hm-feed-item.clock .hm-feed-badge { background: var(--hm-blue); }
.hm-feed-item.join .hm-feed-badge { background: var(--hm-green); }
.hm-feed-item.leave .hm-feed-badge { background: var(--hm-red-soft); }
.hm-feed-item.moved .hm-feed-badge, .hm-feed-item.arrived .hm-feed-badge, .hm-feed-item.state .hm-feed-badge { background: var(--hm-blue); }
.hm-feed-text { overflow: hidden; text-overflow: ellipsis; }
.hm-feed-empty { font-size: 12.5px; color: var(--hm-text-faint); padding: 18px 6px; line-height: 1.8; text-align: center; }
.hm-feed-list::-webkit-scrollbar { width: 5px; }
.hm-feed-list::-webkit-scrollbar-thumb { background: var(--hm-border); border-radius: 3px; }

@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
</style>
