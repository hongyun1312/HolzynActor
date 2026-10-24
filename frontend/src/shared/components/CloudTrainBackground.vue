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
<!--
  云间列车（落日云间列车）WebGL 动态背景。
  <p>渲染器提取自 docs/参考文档/云间列车参考网页.html（原项目 Rice-dog/code-codex，shader 作者 mdb，
  无再分发许可，仅限本项目内部使用）。本组件只做挂载/卸载、参数注入与主题联动。</p>
  <p>主题联动（深浅双态 + 个性化主色）：监听 html[data-theme] 与主题主色变化——</p>
  <ul>
    <li><b>浅色</b>：正常「落日云间列车」配色（默认 skyTint #94b3ff 等）；</li>
    <li><b>深色</b>：切换为「夜空」配色（深蓝天空 + 降曝光/降饱和 + 偏冷色温，云海呈月光夜感），
        与液态玻璃的深色面板相协调；</li>
    <li><b>主色跟随</b>：天空与云烟雾按约 30% 比例混入个性化主色（--accent）——
        「适当跟随、不完全替换」，换肤/题材联动/取色即时生效；</li>
  </ul>
  <p>参数可通过 <code>overrides</code> prop 注入（与 CLOUD_TRAIN_DEFAULTS 同名键，优先于主题夜空参数）；
  渲染器共享 state 对象，改属性 + wake 即可热更新 uniform，无需重启 GL 上下文。</p>
  <p>定位：`position: fixed; z-index: -1` 铺满视口 —— 位于根层背景之上、所有页面内容之下，
  页面自身的半透明底与卡片 backdrop-filter 都能叠加出层次。</p>
  <p>性能：GPU 自适应降噪细节（detail 8→4）、内部像素预算上限（≈1080p）与 DPR 上限；
  窗口隐藏或失焦（被遮挡/切后台）自动停绘归零 GPU；prefers-reduced-motion 降级。</p>
-->
<template>
  <Teleport to="body">
    <canvas ref="canvasRef" class="ctbg-canvas" aria-hidden="true"></canvas>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/shared/theme'
import { CLOUD_TRAIN_DEFAULTS, startCloudTrain } from './cloudTrainRenderer'

const props = defineProps({
  /** 渲染参数覆盖（与 CLOUD_TRAIN_DEFAULTS 同名键）；变化时热更新渲染器 */
  overrides: { type: Object, default: () => ({}) }
})

const canvasRef = ref(null)
const wakeRef = { current: () => {} }
let cleanup = null
let themeObserver = null

/** 深色（夜空）渲染参数：深蓝天空 + 降低曝光/饱和 + 偏冷色温，云海呈月光夜感 */
const NIGHT_OVERRIDES = {
  skyTint: '#0d1f3c',
  smokeTint: '#b9c8e0',
  trainTint: '#5c2a30',
  exposure: 0.7,
  saturation: 0.78,
  temperature: -0.22
}

/* ===== 个性化主色（--accent）对天空/云雾的「适当跟随」强度 =====
   跟随 ≠ 完全替换：天空与云烟雾按该比例混入主色，保留原底色的大部分，
   既让背景与全站换肤主色和谐，又不失「落日云间 / 夜空」的固有观感。 */
const ACCENT_SKY_MIX = 0.3
const ACCENT_SMOKE_MIX = 0.3

/** 当前是否为深色模式（深浅由主题系统写入 html[data-theme]） */
const isDark = ref(document.documentElement.hasAttribute('data-theme'))

/** 当前个性化主色（响应式：主题换肤/项目题材联动/取色都会触发重渲染） */
const themeApi = useTheme()
const effectiveAccent = themeApi.effectiveAccent

/** 把 base 色按 ratio 比例混入 accent 色（ratio=0 不变，1=完全跟随） */
function mixHex(base, accent, ratio) {
  const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16))
  const toHex = arr => '#' + arr
    .map(x => Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0')).join('')
  const b = rgb(base)
  const a = rgb(accent)
  return toHex(b.map((v, i) => v * (1 - ratio) + a[i] * ratio))
}

/** 生效参数 = 默认 + 主题夜空参数 + 显式 overrides + 主色轻染（天空/云雾按比例混入个性化主色） */
function buildSettings() {
  const merged = { ...CLOUD_TRAIN_DEFAULTS }
  if (isDark.value) Object.assign(merged, NIGHT_OVERRIDES)
  Object.assign(merged, props.overrides)
  const accent = String(effectiveAccent.value || '')
  if (/^#[0-9a-f]{6}$/i.test(accent)) {
    merged.skyTint = mixHex(merged.skyTint, accent, ACCENT_SKY_MIX)
    merged.smokeTint = mixHex(merged.smokeTint, accent, ACCENT_SMOKE_MIX)
  }
  return merged
}

/** 渲染器共享状态：渲染器每帧读取 state.current，改属性 + wake 即热更新 uniform */
const state = { current: buildSettings() }

function applyState() {
  Object.assign(state.current, buildSettings())
  wakeRef.current()
}

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return
  try {
    cleanup = startCloudTrain(canvas, state, wakeRef, {})
  } catch (e) {
    console.error('[CloudTrainBackground] 渲染失败:', e)
  }
  // 主题切换（html[data-theme]）→ 夜空 / 正常色调热切换（applyState 触发 wake）
  themeObserver = new MutationObserver(() => {
    const dark = document.documentElement.hasAttribute('data-theme')
    if (dark !== isDark.value) {
      isDark.value = dark
      applyState()
    }
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

watch(() => props.overrides, () => applyState(), { deep: true })
// 个性化主色变化（换肤 / 题材联动 / 取色）→ 天空/云雾轻染主色
watch(effectiveAccent, () => applyState())

onBeforeUnmount(() => {
  if (themeObserver) { themeObserver.disconnect(); themeObserver = null }
  if (cleanup) {
    try { cleanup() } catch (_) { /* 忽略卸载期清理异常 */ }
    cleanup = null
  }
})
</script>

<style scoped>
/* 铺满视口、压到最底层：z-index:-1 位于根背景之上、页面内容之下（backdrop-filter 可采到它） */
.ctbg-canvas {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  display: block;
  z-index: -1;
  pointer-events: none;
  background: #05090c;
}
</style>
