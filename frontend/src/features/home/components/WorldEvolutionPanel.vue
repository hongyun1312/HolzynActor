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
    <!-- AI 运行状态条 -->
    <div class="hm-ai" :class="{ busy: aiRuntime?.inflight > 0 }" :title="aiHint">
      <span class="hm-ai-dot" />
      <span class="hm-ai-text">
        {{ aiRuntime?.inflight > 0 ? `AI 推理中（${aiRuntime.inflight}）` : 'AI 空闲' }}
      </span>
      <span v-if="aiRuntime?.recentTotal" class="hm-ai-meta">
        近 {{ aiWindow }} 分钟 {{ aiRuntime.recentTotal }} 次 · 失败 {{ aiRuntime.recentFailed || 0 }}
      </span>
    </div>

    <!-- 宏观曲线（粮价/民怨；点击标题展开曲线） -->
    <div class="hm-macro" @click="emit('toggle-curve')">
      <span class="hm-macro-title">宏观社会</span>
      <template v-if="macro">
        <span class="hm-macro-sec" :class="'season-' + (macro.season || '')">{{ seasonName(macro.season) }}</span>
        <span class="hm-macro-item">粮价 {{ macro.grainPrice != null ? Math.round(macro.grainPrice) : '—' }}</span>
        <span class="hm-macro-item" :class="grievanceCls(macro.grievance)">民怨 {{ macro.grievance != null ? Math.round(macro.grievance) : '—' }}/100</span>
        <span class="hm-macro-item">收成 {{ (macro.harvestFactor || 0).toFixed(2) }}</span>
      </template>
      <span v-else class="hm-macro-item">未初始化</span>
    </div>
    <!-- 宏观曲线展开区（echarts 折线：粮价/民怨/市场） -->
    <div v-if="curveOpen" class="hm-curve">
      <div ref="curveEl" class="hm-curve-canvas" />
      <div v-if="!curveData.length" class="hm-curve-empty">暂无曲线数据（世界模拟推进后每日快照产生）</div>
    </div>

    <!-- 中观组织 / 二阶涌现（折叠小节） -->
    <div v-if="mesoGroups.length || emergenceEvents.length" class="hm-meso">
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

    <!-- 事件流（实时） -->
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
import * as echarts from 'echarts'

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

const curveEl = ref(null)
const feedListEl = ref(null)
let chart = null

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
    legend: { data: ['粮价', '民怨', '市场'], textStyle: { color: '#9db8e8', fontSize: 10 }, top: 0 },
    xAxis: { type: 'category', data: days, axisLabel: { color: '#6b7fa8', fontSize: 9 } },
    yAxis: { type: 'value', axisLabel: { color: '#6b7fa8', fontSize: 9 }, splitLine: { lineStyle: { color: 'rgba(120,160,255,0.12)' } } },
    series: [
      line('grainPrice', '粮价', '#ffd45c'),
      line('grievance', '民怨', '#ff6b6b'),
      line('marketActivity', '市场', '#52d68a')
    ]
  }
}

function renderCurve() {
  if (!props.curveOpen || !props.curveData.length) return
  nextTick(() => {
    if (!curveEl.value) return
    if (!chart) chart = echarts.init(curveEl.value)
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
  background: rgba(13, 20, 36, 0.85); border: 1px solid rgba(120, 160, 255, 0.22);
  font-size: 11.5px; color: #9db8e8; flex-shrink: 0; cursor: help;
}
.hm-ai-dot { width: 8px; height: 8px; border-radius: 50%; background: #5a6b85; flex-shrink: 0; }
.hm-ai.busy .hm-ai-dot { background: #ffd45c; box-shadow: 0 0 6px rgba(255, 212, 92, 0.8); animation: pulse 1.2s infinite; }
.hm-ai-text { font-weight: 600; white-space: nowrap; }
.hm-ai-meta { margin-left: auto; font-variant-numeric: tabular-nums; font-size: 10.5px; color: #6b7fa8; }
/* 宏观条 */
.hm-macro {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  padding: 7px 10px; border-radius: 8px;
  background: rgba(13, 20, 36, 0.85); border: 1px solid rgba(120, 160, 255, 0.22);
  cursor: pointer; flex-shrink: 0; transition: background 0.2s;
}
.hm-macro:hover { background: rgba(77, 159, 255, 0.12); }
.hm-macro-title { font-size: 12px; font-weight: 700; color: #ffd45c; }
.hm-macro-sec {
  font-size: 10.5px; font-weight: 700; padding: 1px 7px; border-radius: 6px;
  color: #eaf3ff; border: 1px solid rgba(255, 255, 255, 0.25); font-variant-numeric: tabular-nums;
}
.hm-macro-sec.season-spring { background: rgba(93, 211, 141, 0.25); border-color: rgba(93, 211, 141, 0.55); }
.hm-macro-sec.season-summer { background: rgba(255, 176, 61, 0.25); border-color: rgba(255, 176, 61, 0.55); }
.hm-macro-sec.season-autumn { background: rgba(255, 146, 71, 0.25); border-color: rgba(255, 146, 71, 0.55); }
.hm-macro-sec.season-winter { background: rgba(143, 202, 255, 0.25); border-color: rgba(143, 202, 255, 0.55); }
.hm-macro-item { font-size: 11px; font-weight: 600; color: #cfe0ff; font-variant-numeric: tabular-nums; }
.hm-macro-item.gv-high { color: #ff6b6b; }
.hm-macro-item.gv-mid { color: #ffb84d; }
/* 曲线 */
.hm-curve {
  border-radius: 8px; background: rgba(13, 20, 36, 0.85);
  border: 1px solid rgba(120, 160, 255, 0.22); overflow: hidden; flex-shrink: 0;
}
.hm-curve-canvas { width: 100%; height: 170px; }
.hm-curve-empty { font-size: 11px; color: #6b7fa8; text-align: center; padding: 30px 10px; }
/* 中观/涌现 */
.hm-meso {
  display: flex; flex-direction: column; gap: 8px;
  padding: 8px 10px; border-radius: 8px;
  background: rgba(13, 20, 36, 0.85); border: 1px solid rgba(120, 160, 255, 0.22);
  max-height: 30%; overflow-y: auto; flex-shrink: 0;
}
.hm-meso-title { font-size: 11.5px; font-weight: 700; color: #9db8e8; margin-bottom: 4px; }
.hm-meso-row { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #cfe0ff; margin-bottom: 3px; }
.hm-g-name { font-weight: 600; color: #ffd45c; white-space: nowrap; }
.hm-g-meta { margin-left: auto; font-size: 10px; color: #6b7fa8; white-space: nowrap; }
.hm-ev-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 事件流 */
.hm-feed {
  flex: 1; min-height: 0;
  display: flex; flex-direction: column;
  background: rgba(13, 20, 36, 0.85); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  overflow: hidden;
}
.hm-feed-head {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-bottom: 1px solid rgba(120, 160, 255, 0.18); flex-shrink: 0;
}
.hm-feed-title { font-size: 12px; font-weight: 700; color: #9db8e8; }
.hm-feed-clear { margin-left: auto; color: #6b7fa8; }
.hm-feed-list { flex: 1; overflow-y: auto; padding: 4px 12px; }
.hm-feed-item {
  display: flex; align-items: baseline; gap: 8px;
  font-size: 11.5px; color: #cfe0ff; line-height: 1.7; white-space: nowrap;
}
.hm-feed-time { font-size: 10px; color: #6b7fa8; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.hm-feed-badge {
  flex-shrink: 0; font-size: 10px; font-weight: 700; padding: 0 6px; border-radius: 4px;
  color: #0b1220; background: #9db8e8;
}
.hm-feed-item.talk .hm-feed-badge { background: #52d68a; }
.hm-feed-item.thought .hm-feed-badge { background: #ffd45c; }
.hm-feed-item.event .hm-feed-badge { background: #ff8f6b; }
.hm-feed-item.clock .hm-feed-badge { background: #8fb1ff; }
.hm-feed-item.join .hm-feed-badge { background: #52d68a; }
.hm-feed-item.leave .hm-feed-badge { background: #ff8f8f; }
.hm-feed-item.moved .hm-feed-badge, .hm-feed-item.arrived .hm-feed-badge, .hm-feed-item.state .hm-feed-badge { background: #9db8e8; }
.hm-feed-text { overflow: hidden; text-overflow: ellipsis; }
.hm-feed-list::-webkit-scrollbar { width: 5px; }
.hm-feed-list::-webkit-scrollbar-thumb { background: rgba(120, 160, 255, 0.3); border-radius: 3px; }

@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
</style>
