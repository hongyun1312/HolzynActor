<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<!--
  新用户 7 步交互式引导（v1.1.0-beta.2，全局挂载 App.vue）。
  <p>流程：项目画廊（①欢迎 → ②新增项目 → ③示例世界青禾村）→ 示例世界内
  （④开始推进时钟 → ⑤浮生⇄对谈切换 → ⑥和角色对话）→ ⑦完成。
  每步聚光灯 + 气泡；③⑤ 带 CTA 驱动路由跳转；任何一步可「跳过」；完成后可在「教程中心」重看。</p>
  <p>触发：FirstRunSetup 完成向导后写 pending 标记（localStorage holzyn.firstRunGuide.pending.v1）；
  本组件检测到 pending 且未 done 时在画廊自动启动。路由约束：①②③ 仅在画廊（/）显示，
  ④⑤⑥ 仅在项目空间（/project/…）显示——CTA 跳转后自动接续。</p>
  <p>与「界面导览」（GuidedTour）分工：界面导览解释「界面为什么长这样」（布局升级一次性）；
  新手引导是「新用户按顺序把世界跑起来」的第一次旅程（仅新安装后一次，可重看）。</p>
-->
<template>
  <teleport to="body">
    <div v-if="visible" class="frg-root">
      <!-- 挖洞矩形：单矩形 + 超大 box-shadow = 全屏压暗且中间透明 -->
      <div class="frg-hole" :style="holeStyle"></div>

      <!-- 说明气泡 -->
      <div class="frg-pop" :style="popStyle">
        <div class="frg-pop-head">
          <span class="frg-step">{{ step + 1 }} / {{ STEPS.length }}</span>
          <span class="frg-title">{{ current.title }}</span>
        </div>
        <p class="frg-desc">{{ current.desc }}</p>
        <div class="frg-pop-foot">
          <el-button size="small" text @click="finish">跳过引导</el-button>
          <span class="frg-spacer"></span>
          <el-button v-if="step > 0" size="small" @click="prev">上一步</el-button>
          <!-- CTA（③进示例世界 / ⑥去对谈）；nextAllowed 允许不点 CTA 直接推进 -->
          <el-button v-if="current.cta" size="small" type="primary" @click="doCta(current.cta)">
            {{ current.cta.label }}
          </el-button>
          <!-- nextAllowed 默认 true（允许直接推进）；步骤②（index 2）显式 false 必须先进世界 -->
          <el-button
            v-if="current.nextAllowed !== false && step < STEPS.length - 1"
            size="small"
            @click="next"
          >下一步</el-button>
          <el-button v-if="step === STEPS.length - 1" size="small" type="primary" @click="finish">
            开始使用
          </el-button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
/**
 * 新用户 7 步交互式引导。
 * <p>状态：pending（待引导）与 done（已完成/跳过）各一个 localStorage 键；done 置位后不再出现。
 * 「教程中心」可重看（置 pending、清 done、回画廊）。</p>
 * <p>健壮性：目标元素不存在（窄屏/未加载）时挖洞降级为全屏压暗、气泡居中；
 * 路由不匹配当前步骤约束时不渲染（CTA 跳转后自动接续）。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** pending / done 标记（与 FirstRunSetup / 教程中心共用键名） */
const GUIDE_PENDING_KEY = 'holzyn.firstRunGuide.pending.v1'
const GUIDE_DONE_KEY = 'holzyn.firstRunGuide.done.v1'

/** 示例世界（内置演示项目）ID：青禾村 */
const DEMO_PROJECT_ID = 1

const active = ref(false)
const index = ref(0)
const rect = ref(null)

/** 路由约束 */
function isGallery() { return route.path === '/' }
function isWorld() { return /^\/project\/\d+/.test(route.path) }

/**
 * 引导步骤定义。
 * <p>{@code route}：步骤仅在该路由约束下显示（不匹配时隐藏，CTA 跳转后自动接续）；
 * {@code target}：聚光灯目标 CSS 选择器（可空=居中气泡）；{@code cta}：主按钮跳转（可空）。</p>
 */
const STEPS = [
  {
    route: isGallery,
    title: '欢迎来到你的世界',
    desc: '这里是「项目画廊」——所有世界的入口。每个项目都是一个独立的世界：有自己的人物、时间、记忆与故事，彼此互不干扰。'
  },
  {
    route: isGallery,
    target: '[data-tour="fr-add-project"]',
    title: '新建你的世界',
    desc: '想造一个属于自己的世界？点右上角「新增项目」——可以手填世界观、上传小说让 AI 解析，或输入创意让 AI 生成。我们先用内置的示例世界熟悉玩法。'
  },
  {
    route: isGallery,
    target: '[data-tour="fr-demo-card"]',
    title: '示例世界：青禾村',
    desc: '下面这张「青禾村」卡片是内置的演示村庄（20 名角色的仙侠世界）。点它或下面的按钮进去逛逛——它是给你试的，怎么玩都不会把数据弄坏。',
    // ②必须进入示例世界才能接续世界内步骤，故不允许直接「下一步」（否则 ④ 会因不在项目空间而隐藏）
    nextAllowed: false,
    cta: { label: '进入示例世界 →', route: `/project/${DEMO_PROJECT_ID}/sandbox` }
  },
  {
    route: isWorld,
    target: '[data-tour="fr-clock"]',
    title: '让世界开始运转',
    desc: '世界时钟默认是「暂停」的。点工具条上的「▶ 开始推进」，角色们就会按自己的日程开始生活；随时点「⏸ 暂停」就冻结，恢复从冻结处接续。'
  },
  {
    route: isWorld,
    target: '[data-tour="interface-switch"]',
    title: '浮生 ⇄ 对谈',
    desc: '左上角世界名右侧可在「浮生」（世界运行 · 角色 · 记忆 · 观测 · 设置）与「对谈」（世界静止，只和角色聊天）两个独立界面间切换，各自拥有独立侧边栏。'
  },
  {
    route: isWorld,
    title: '和角色聊一句',
    desc: '想和谁说话？切到「对谈」界面，挑一位村民直接打字聊——角色会带着记忆、情绪与对你的了解回应你。（也可以先跳过，以后随时进「对谈」聊。）',
    cta: { label: '去对谈界面 →', route: `/project/${DEMO_PROJECT_ID}/dialogue` }
  },
  {
    route: () => true,
    title: '你已经会玩了',
    desc: '还有更多玩法：世界初始化、AI 助手、时间线、关系拓扑、记忆归档……都可以在「教程中心」继续了解（也能在那里「重看新手引导」）。祝你玩得开心！'
  }
]

const current = computed(() => STEPS[index.value] || STEPS[0])
const visible = computed(() => active.value && (current.value.route ? current.value.route() : true))

/** 挖洞样式（目标缺失时全屏压暗、气泡居中） */
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
  const w = 340
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (!rect.value) return { left: `${(vw - w) / 2}px`, top: '38vh', width: `${w}px` }
  const r = rect.value
  const gap = 14
  let left
  let top
  const placement = 'below'
  if (placement === 'right') {
    left = r.right + gap
    top = r.top
    if (left + w > vw - 12) left = Math.max(12, r.left - w - gap)
  } else {
    left = Math.min(Math.max(12, r.left + r.width / 2 - w / 2), vw - w - 12)
    top = r.bottom + gap
    if (top > vh - 210) top = Math.max(12, r.top - 200)
  }
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

/** 量测当前步骤目标；目标不存在时置空（降级居中） */
async function measure() {
  await nextTick()
  const target = current.value.target
  const el = target ? document.querySelector(target) : null
  if (el) {
    const r = el.getBoundingClientRect()
    rect.value = { left: r.left, top: r.top, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
  } else {
    rect.value = null
  }
}

function next() {
  if (index.value >= STEPS.length - 1) { finish(); return }
  index.value += 1
  measure()
}
function prev() {
  if (index.value === 0) return
  index.value -= 1
  measure()
}

/** 执行 CTA 跳转（index 2 进示例世界 / index 5 去对谈） */
function doCta(cta) {
  if (!cta || !cta.route) return
  router.push(cta.route)
  // index 2 CTA 后等待路由进入项目空间再自动接续 index 3（世界内步骤）；index 5 由用户「下一步」推进
  if (index.value === 2) waitingWorld.value = true
}

/** ③ 是否在等待进入示例世界 */
const waitingWorld = ref(false)

/** localStorage 读写（损坏安全） */
function getLS(key) { try { return localStorage.getItem(key) } catch (_) { return null } }
function setLS(key, v) { try { localStorage.setItem(key, v) } catch (_) { /* 隐私模式静默 */ } }

/** 启动引导（pending 且未 done） */
function maybeStart() {
  if (getLS(GUIDE_DONE_KEY) === '1') return
  if (getLS(GUIDE_PENDING_KEY) !== '1') return
  active.value = true
  index.value = 0
  measure()
}

/** 完成引导（标记 done、清 pending） */
function finish() {
  active.value = false
  setLS(GUIDE_DONE_KEY, '1')
  setLS(GUIDE_PENDING_KEY, '')
  waitingWorld.value = false
}

/** 路由变化：③ → ④ 接续；重看（教程中心 re-arm）后回画廊自动启动 */
function onRouteChange(path) {
  if (!active.value) {
    maybeStart()
    return
  }
  if (waitingWorld.value && /^\/project\/\d+/.test(path) && index.value === 2) {
    waitingWorld.value = false
    index.value = 3
    measure()
    return
  }
  // 步骤路由约束变化：目标元素可能新出现，重测量
  measure()
}

/** 供「教程中心」重看引导用的静态方法（re-arm：置 pending、清 done） */
// 注：教程中心直接写同一组 localStorage 键（pending=1 / done=''）后回画廊即可，无需经本组件引用。

let onResize = null
onMounted(() => {
  maybeStart()
  onResize = () => { if (active.value) measure() }
  window.addEventListener('resize', onResize, { passive: true })
})
onBeforeUnmount(() => { if (onResize) window.removeEventListener('resize', onResize) })

watch(() => route.fullPath, onRouteChange)
watch(visible, (v) => { if (v) measure() })
</script>

<style scoped>
.frg-root { position: fixed; inset: 0; z-index: var(--z-tour, 65); pointer-events: none; }

/* 挖洞：单矩形 + 超大扩散阴影 = 全屏压暗且中间透明；圆角让边缘不突兀 */
.frg-hole {
  position: fixed;
  border-radius: var(--radius-md, 12px);
  box-shadow: 0 0 0 9999px var(--bg-scrim);
  transition: all .22s var(--dsw-ease);
  pointer-events: none;
}

.frg-pop {
  position: fixed;
  pointer-events: auto;
  padding: var(--space-4, 16px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv3);
  transition: all .22s var(--dsw-ease);
}
.frg-pop-head { display: flex; align-items: baseline; gap: var(--space-2, 8px); }
.frg-step {
  font-family: var(--dsw-font-family-code);
  font-size: 0.72rem;
  color: var(--accent-text);
  background: var(--accent-soft);
  padding: 1px 6px;
  border-radius: 4px;
  white-space: nowrap;
}
.frg-title { font-size: 0.95rem; font-weight: 700; color: var(--text-primary); }
.frg-desc { margin: 10px 0 14px; font-size: 0.82rem; line-height: 1.75; color: var(--text-secondary); }
.frg-pop-foot { display: flex; align-items: center; gap: var(--space-2, 8px); }
.frg-spacer { flex: 1; }
</style>
