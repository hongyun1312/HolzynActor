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
    新布局导览（聚光灯 + 气泡）：一次性引导，帮助老用户与酒馆用户找到「原来在侧栏的东西现在在哪」。
    实现要点：用单个定位矩形 + 超大 box-shadow 做「挖洞」，比四块遮罩更简单也不会出现缝隙。
  -->
  <teleport to="body">
    <div v-if="active" class="tour-root">
      <!-- 挖洞矩形：box-shadow 扩散填满全屏（9999px 足够覆盖任何视口） -->
      <div class="tour-hole" :style="holeStyle"></div>

      <!-- 说明气泡 -->
      <div class="tour-pop" :style="popStyle">
        <div class="tour-pop-head">
          <span class="tour-step">{{ index + 1 }} / {{ total }}</span>
          <span class="tour-title">{{ current.title }}</span>
        </div>
        <p class="tour-desc">{{ current.desc }}</p>
        <div class="tour-pop-foot">
          <el-button size="small" text @click="finish">跳过</el-button>
          <span class="tour-spacer"></span>
          <el-button size="small" :disabled="index === 0" @click="prev">上一步</el-button>
          <el-button size="small" type="primary" @click="next">
            {{ index === total - 1 ? '开始使用' : '下一步' }}
          </el-button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
/**
 * 新界面导览组件。
 *
 * <p>职责：按 {@link TOUR_STEPS} 高亮界面关键区域（域侧栏、顶栏图标抽屉、命令面板入口、
 * 显示偏好、主工作区、帮助入口），并用气泡解释「这是什么、为什么变了」。</p>
 *
 * <p>触发时机：① 首次升级到新布局时自动出现一次（layout store 的 `tourSeen` 标记）；
 * ② 用户在帮助抽屉/设置页点「重看导览」。</p>
 *
 * <p>健壮性设计：目标元素不存在（例如窄屏下侧栏被收起）时**自动跳到下一步**，
 * 而不是显示一个指向空的聚光灯；窗口尺寸变化时重算位置。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { useLayoutStore } from '@/shared/store/layout'

const layout = useLayoutStore()
const active = computed(() => layout.tourActive)

/** 导览步骤（target 为 CSS 选择器；缺失则跳过） */
const TOUR_STEPS = [
  {
    target: '[data-tour="sidebar"]',
    title: '域侧栏：按功能域分区',
    desc: '导航不再是一长串平铺项，而是按「世界 / 角色 / 心智 / 观测 / 设置」五个域分组。点击组名可折叠；侧栏右缘可拖拽调宽，宽度会被记住。',
    placement: 'right'
  },
  {
    target: '[data-tour="pinned-nav"]',
    title: '主工作区固定在顶部',
    desc: '「世界地图」与「对话」是浮生界面最高频的两个入口，固定在侧栏最上方，任何页面都能一键回到。',
    placement: 'right'
  },
  {
    target: '[data-tour="interface-switch"]',
    title: '浮生 ⇄ 对谈：两个独立界面',
    desc: '左上角世界名右侧可在「浮生」（世界运行 · 角色 · 记忆 · 观测 · 设置）与「对谈」（与角色对谈 · 世界静止）两个界面间切换，各自拥有完全独立的侧边栏。',
    placement: 'bottom'
  },
  {
    target: '[data-tour="topbar-icons"]',
    title: '顶栏图标抽屉：顺手看一眼',
    desc: '点顶栏图标会展开面板，一次只开一个；再点一次关闭，点空白处也会关闭。想让面板常驻，点面板头部的钥匙图标固定即可。',
    placement: 'bottom'
  },
  {
    target: '[data-tour="search"]',
    title: '命令面板（Ctrl + K）',
    desc: '记不住页面在哪就用它：输入「角色」「密度」「主题」等关键词，直接跳到页面或执行动作。',
    placement: 'bottom'
  },
  {
    target: '[data-tour="display-prefs"]',
    title: '信息密度与主题',
    desc: '这里可以一键切换紧凑 / 舒适 / 宽松三档密度，以及浅色 / 深色主题。默认「舒适」档，觉得空就调紧凑。',
    placement: 'bottom'
  },
  {
    target: '[data-tour="help"]',
    title: '快捷键速查与教程',
    desc: '所有快捷键、界面导览重看、界面与布局设置都在这里。随时按 Ctrl + K 也能打开命令面板。',
    placement: 'bottom'
  }
]

const index = ref(0)
const total = TOUR_STEPS.length
const rect = ref(null)

/** 当前步骤（跳过不存在目标的步骤由 jumpToAvailable 处理） */
const current = computed(() => TOUR_STEPS[index.value] || TOUR_STEPS[0])

/** 聚光灯挖洞样式（目标缺失时全屏压暗、气泡居中） */
const holeStyle = computed(() => {
  if (!rect.value) return { left: '50%', top: '50%', width: 0, height: 0, boxShadow: '0 0 0 9999px var(--bg-scrim)' }
  const pad = 6
  return {
    left: `${rect.value.left - pad}px`,
    top: `${rect.value.top - pad}px`,
    width: `${rect.value.width + pad * 2}px`,
    height: `${rect.value.height + pad * 2}px`
  }
})

/** 气泡位置：按 placement 相对目标定位，并做视口收敛（不越界） */
const popStyle = computed(() => {
  const w = 330
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (!rect.value) return { left: `${(vw - w) / 2}px`, top: '40vh', width: `${w}px` }
  const r = rect.value
  const gap = 14
  let left
  let top
  if (current.value.placement === 'right') {
    left = r.right + gap
    top = r.top
    if (left + w > vw - 12) left = Math.max(12, r.left - w - gap)
  } else {
    left = Math.min(Math.max(12, r.left + r.width / 2 - w / 2), vw - w - 12)
    top = r.bottom + gap
    if (top > vh - 200) top = Math.max(12, r.top - 190)
  }
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

/** 量测当前步骤目标；目标不存在则前进到下一个可用步骤 */
async function measure() {
  await nextTick()
  for (let i = index.value; i < total; i++) {
    const el = document.querySelector(TOUR_STEPS[i].target)
    if (el) {
      index.value = i
      const r = el.getBoundingClientRect()
      rect.value = { left: r.left, top: r.top, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
      return
    }
  }
  rect.value = null
}

function next() {
  if (index.value >= total - 1) { finish(); return }
  index.value += 1
  measure()
}
function prev() {
  if (index.value === 0) return
  index.value -= 1
  measure()
}
function finish() { layout.endTour(true) }

let onResize = null
onMounted(() => {
  onResize = () => { if (active.value) measure() }
  window.addEventListener('resize', onResize, { passive: true })
})
onBeforeUnmount(() => { if (onResize) window.removeEventListener('resize', onResize) })

watch(active, (v) => { if (v) { index.value = 0; measure() } })
</script>

<style scoped>
.tour-root { position: fixed; inset: 0; z-index: var(--z-tour, 65); pointer-events: none; }

/* 挖洞：单矩形 + 超大扩散阴影 = 全屏压暗且中间透明；圆角让边缘不突兀 */
.tour-hole {
  position: fixed;
  border-radius: var(--radius-md, 12px);
  box-shadow: 0 0 0 9999px var(--bg-scrim);
  transition: all .22s var(--dsw-ease);
  pointer-events: none;
}

.tour-pop {
  position: fixed;
  pointer-events: auto;
  padding: var(--space-4, 16px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv3);
  transition: all .22s var(--dsw-ease);
}
.tour-pop-head { display: flex; align-items: baseline; gap: var(--space-2, 8px); }
.tour-step {
  font-family: var(--dsw-font-family-code);
  font-size: 0.72rem;
  color: var(--accent-text);
  background: var(--accent-soft);
  padding: 1px 6px;
  border-radius: 4px;
}
.tour-title { font-size: 0.95rem; font-weight: 700; color: var(--text-primary); }
.tour-desc { margin: 10px 0 14px; font-size: 0.82rem; line-height: 1.75; color: var(--text-secondary); }
.tour-pop-foot { display: flex; align-items: center; gap: var(--space-2, 8px); }
.tour-spacer { flex: 1; }
</style>
