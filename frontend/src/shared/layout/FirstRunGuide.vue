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
  新用户引导（全局挂载 App.vue；11 步，可在教程中心重看）。

  <p><b>2026-10-25 修复（开局引导三处真实缺陷）</b>：</p>
  <ol>
    <li><b>气泡底部按钮挤出窗口</b>：`.frg-pop-foot` 原是不换行的 flex 行，第 3 步 4 个按钮
        （跳过引导/上一步/进入世界 CTA/下一步）最小宽度和 &gt; 气泡 340px，实测最后一个按钮
        右边界 912px 而气泡右边界 810px（越界 102px）。现在：气泡改 flex 纵向 +
        `max-height: calc(100vh - 24px)`，正文可滚，底部按钮区 `flex-wrap: wrap` 且动作组右对齐；
        气泡定位改用「渲染后实测高度」（{@link popH}）夹在视口内，不再用 250/280 这类写死估值。</li>
    <li><b>点「下一步」直接跳到末步</b>：原 `next()` 只认「当前路由能否匹配」，第 4~6 步都要求
        项目空间，在画廊下全不匹配 → 直接落到「你已经会玩了」，实测 3/7 点下一步变 7/7。
        现在：需要换界面的步骤（进入世界 / 去对谈 / 回到浮生）<b>只有 CTA 一个主按钮</b>，
        它就是真正的「下一步」；有 CTA 时不再渲染第二个「下一步」，`next()` 也拒绝越过 CTA。</li>
    <li><b>聚光灯指不到东西</b>：第 2、3 步的目标选择器（`fr-add-project` / `project-card` /
        `data-guide-demo`）在世界画廊改版后根本不存在，气泡只能居中不挖洞。现在由世界画廊补上
        `data-tour` / `data-guide-demo` 属性，示例世界选取规则收敛到 {@link pickGuideProject}，
        两处共用一份，避免「引导说点青屿镇、高亮却在别的卡片」。</li>
  </ol>

  <p><b>步骤（11）</b>：画廊 ①欢迎 ②新建世界 ③进入世界（CTA=进入）→ 浮生 ④世界时钟
  ⑤浮生⇄对谈（CTA=去对谈）→ 对谈 ⑥会话列表 ⑦输入框 ⑧对谈详情（CTA=回浮生）→
  浮生 ⑨域侧栏与命令面板 ⑩项目级 AI 助手 → ⑪完成。</p>

  <p>健壮性：目标元素不存在（窄屏/元素被折叠/示例世界被删）时气泡居中不挖洞；用户中途离开
  某一步的作用域（例如从项目空间回画廊）时引导暂时隐藏、回到作用域自动接续，
  不会出现「指向空气的聚光灯」或「卡住不能动」。</p>
-->
<template>
  <teleport to="body">
    <div v-if="visible" class="frg-root" role="dialog" aria-modal="false" aria-label="新用户引导">
      <!-- 挖洞矩形：单矩形 + 超大 box-shadow = 全屏压暗且中间透明 -->
      <div class="frg-hole" :style="holeStyle"></div>

      <!-- 说明气泡：纵向 flex，正文可滚，按钮区永不越出气泡 -->
      <div ref="popRef" class="frg-pop" :style="popStyle">
        <div class="frg-pop-body">
          <div class="frg-pop-head">
            <span class="frg-step">{{ index + 1 }} / {{ STEPS.length }}</span>
            <span class="frg-title">{{ current.title }}</span>
          </div>
          <p class="frg-desc">{{ currentDesc }}</p>
          <div class="frg-dots" aria-hidden="true">
            <i v-for="(s, i) in STEPS" :key="s.id" class="frg-dot" :class="{ on: i === index, done: i < index }" />
          </div>
        </div>
        <div class="frg-pop-foot">
          <el-button size="small" text class="frg-skip" @click="finish">跳过引导</el-button>
          <div class="frg-actions">
            <el-button v-if="canPrev" size="small" @click="prev">上一步</el-button>
            <!--
              唯一主按钮：需要换界面的步骤（进入世界/去对谈/回浮生）它就是「下一步」，
              不再是「CTA + 下一步」两个按钮并列（实测并列会挤出气泡，且下一步会跳过整段作用域）。
            -->
            <el-button v-if="primaryAction" size="small" type="primary" :loading="ctaBusy" @click="runPrimary">
              {{ primaryAction.label }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
/**
 * 新用户引导（步骤列表见 {@link STEPS}）。
 *
 * <p>状态机：`active`（本组件是否显示引导）× `index`（当前步骤）。触发条件由
 * {@link shouldStartGuide} 决定——「待启动」标记 + 后端未标记完成；「完成/跳过」由
 * {@link completeGuide} 写后端，跨重启不再出现。</p>
 *
 * <p>与「界面导览」（GuidedTour）分工：界面导览解释「界面为什么长这样」（布局升级一次性）；
 * 新手引导是「新用户按顺序把世界跑起来」的第一次旅程（完成一次，可在教程中心重看）。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAccountStore } from '@/shared/store'
import { useLayoutStore } from '@/shared/store/layout'
import { fetchProjects, openWorld } from '@/shared/api'
import { disarmGuide, shouldStartGuide, completeGuide, firstRunGuideActive } from '@/shared/onboarding/firstRunGuide'
import { pickGuideProject } from '@/shared/onboarding/guideProject'

const route = useRoute()
const router = useRouter()
const account = useAccountStore()
const layout = useLayoutStore()

const active = ref(false)
const index = ref(0)
const rect = ref(null)
/** 气泡实测高度（渲染后量，定位用它夹视口，而不是猜一个 250px） */
const popH = ref(0)
const popRef = ref(null)
/** CTA 正在打开世界（按钮 loading，防连点） */
const ctaBusy = ref(false)

/** 引导要带去「逛逛」的项目（示例世界优先，其次最近项目；无项目时为空） */
const guideProject = ref(null)
/** 画廊项目是否已加载完（决定步骤③的文案：有项目=请点卡片；无项目=先建一个） */
const projectsLoaded = ref(false)

// ==================== 作用域判定（三种界面：世界画廊 / 浮生 / 对谈） ====================
/**
 * 作用域刻意写精确，不做「/world/ 一律算项目空间」：
 * 对谈页也有侧栏与顶栏，若共用项目空间判定，第 ④⑤ 步会在对谈页指向世界时钟/界面切换，
 * 而那两个元素在对谈页并不存在（改前就是这样「指向空气」）。 */
const isGallery = (path) => path === '/'
const isWorldHome = (path) => /^\/world\/\d+\/sandbox$/.test(path)
const isDialogue = (path) => /^\/world\/\d+\/dialogue(\/|$)/.test(path)

/** 当前路由里的项目 ID（用于「进入世界 / 去对谈 / 回浮生」CTA） */
const currentProjectId = computed(() => {
  const id = Number(route.params?.id)
  if (Number.isFinite(id) && id > 0) return id
  return guideProject.value?.id || null
})

/** 项目名过长时截断（CTA 按钮宽度有限，名字太长会把按钮撑出气泡） */
function shortName(name, max = 10) {
  const s = String(name || '这个世界')
  return s.length > max ? s.slice(0, max) + '…' : s
}

/**
 * 步骤定义。
 * <ul>
 *   <li>{@code route}：步骤只在满足该路径谓词的界面出现（不满足时向后寻找可用步骤，不会指向空气）；</li>
 *   <li>{@code targets}：聚光灯目标选择器（按顺序取第一个可见元素；空=居中气泡）；</li>
 *   <li>{@code desc}：支持函数形式，用于拼接动态文案（示例世界名、是否已有项目）；</li>
 *   <li>{@code cta}：换界面的主动作；有 CTA 的步骤不再渲染「下一步」（CTA 即下一步）。</li>
 * </ul>
 */
const STEPS = [
  {
    id: 'welcome',
    route: isGallery,
    title: '这里是「世界画廊」',
    desc: () => '每个世界都有自己的世界观、角色、时间与记忆，彼此互不干扰。下面三分钟，我们按「建世界 → 跑世界 → 聊角色」的顺序走一遍。'
  },
  {
    id: 'new-project',
    route: isGallery,
    targets: ['[data-tour="fr-add-project"]'],
    title: '新建一个世界',
    desc: () => '点这里「新建世界」，有四种建法：手动填写 / 创意发散生成 / 文件解析（上传小说或设定，AI 帮你拆成世界观）/ 导入 .holzyn 世界包。AI 生成与文件解析需要先接入模型——上方黄色提醒可以直接去配。'
  },
  {
    id: 'enter-world',
    route: isGallery,
    targets: ['[data-guide-demo="1"]', '[data-tour="project-card"]'],
    title: '进入一个世界',
    desc: () => {
      if (!projectsLoaded.value) return '正在读取你的世界…'
      if (!guideProject.value) return '画廊里还没有世界：先用「新建世界」建一个，再回来继续这一步。'
      const name = guideProject.value.name || '这个世界'
      return `点「${name}」的卡片就能进入世界。里面是世界地图：左边是功能域侧栏，顶上是世界时钟与运行控制，角色会按自己的日程生活。`
    },
    cta: () => {
      const p = guideProject.value
      if (!p) return { label: '去新建世界 →', route: '/world/new' }
      return { label: `进入「${shortName(p.name)}」→`, route: `/world/${p.id}/sandbox`, openWorldId: p.id }
    }
  },
  {
    id: 'clock',
    route: isWorldHome,
    targets: ['[data-tour="fr-clock"]'],
    title: '世界时钟：默认是静止的',
    desc: '左上角「世界时钟」显示游戏时间与运行状态。打开「世界运行」后世界才会自己推进（关掉页面也会继续，流速 ×N 可调）；主按钮随状态变化——开启世界运行 / 开始推进 / 暂停。「推进一轮」只走一个游戏小时，适合一步步看角色做了什么。'
  },
  {
    id: 'dialogue-switch',
    route: isWorldHome,
    targets: ['[data-tour="interface-switch"]'],
    title: '浮生 ⇄ 对谈：两个独立界面',
    desc: '世界名右侧的胶囊按钮可切换「浮生」（世界地图 / 角色 / 记忆 / 观测 / 设置）与「对谈」（世界停在当前时刻，只和角色聊天）。对谈不会改动世界进度，切回来还在原时刻。下一步我们去对谈界面看看。',
    cta: () => (currentProjectId.value
      ? { label: '去对谈界面 →', route: `/world/${currentProjectId.value}/dialogue` }
      : null)
  },
  {
    id: 'dialogue-list',
    route: isDialogue,
    targets: ['[data-tour="dlg-sessions"]'],
    title: '对谈：左栏是会话列表',
    desc: '每次和某个角色（或一群角色）的对话都是一个独立会话：可新建、重命名、关闭。创建会话即冻结世界——自动暂停并快照当前时刻，聊多久世界都不会往前走；需要时再手动恢复。'
  },
  {
    id: 'dialogue-input',
    route: isDialogue,
    // 有会话 → 指向输入框；还没有会话 → 指向 hero 的「新建对谈」（首次进入就是这个状态）
    targets: () => (hasEl('[data-tour="dlg-composer"]') ? ['[data-tour="dlg-composer"]'] : ['[data-tour="dlg-hero-new"]']),
    title: '对谈：怎么开口',
    desc: () => (hasEl('[data-tour="dlg-composer"]')
      ? '底部就是输入框：Enter 发送、Shift + Enter 换行。角色会带着自己的记忆、情绪与和你的关系回应；说过的话还能改写、重生成或删除。'
      : '首次进对谈通常还没有会话：点「新建对谈」选一位角色，世界会立刻冻结在当下时刻。会话建好后底部就是输入框——Enter 发送、Shift + Enter 换行，也可以先点「生成开场白」让角色先开口。')
  },
  {
    id: 'dialogue-context',
    route: isDialogue,
    targets: ['[data-tour="dlg-context"]'],
    title: '对谈：右侧「对谈详情」',
    desc: '右栏汇总这个角色此刻的档案、你与他的关系（好感 / 信任 / 过往）、相关世界书与记忆片段——角色为什么这样回应，答案通常在这里。窄屏下它收成顶部的「对谈详情」按钮。',
    cta: () => (currentProjectId.value
      ? { label: '回到浮生继续 →', route: `/world/${currentProjectId.value}/sandbox` }
      : null)
  },
  {
    id: 'sidebar',
    route: isWorldHome,
    targets: ['[data-tour="sidebar"]'],
    title: '左侧域侧栏 + 命令面板',
    desc: '侧栏按「世界 / 角色 / 心智 / 观测 / 设置」分组，点组名可折叠、右缘可拖宽；「世界地图」与「对谈」固定在最上面。记不住页面在哪就按 Ctrl + K 打开命令面板，输入「地图」「记忆」「密度」直接跳转或执行。'
  },
  {
    id: 'assistant',
    route: isWorldHome,
    targets: ['[data-tour="assistant-float"]'],
    title: '右下角：项目级 AI 助手',
    desc: '这个浮窗是你的世界讲解员（也可以按 Ctrl + Shift + A）：可以问「沈守拙现在在做什么」「青屿镇最近发生了什么」「他为什么做这个决定」，也能让它干预世界（改天气、推动事件）——涉及改动时会先给你一张确认卡。它只回答这一个世界的事。'
  },
  {
    id: 'done',
    route: () => true,
    title: '你已经会玩了',
    desc: '还有更多可以在侧栏里探索：关系拓扑、时间线、记忆归档、世界演化……右上角「?」帮助抽屉里有完整教程与快捷键，也能在那里「重看新手引导」。祝你玩得开心！'
  }
]

const current = computed(() => STEPS[index.value] || STEPS[0])

/** 动态文案（desc 支持字符串或函数） */
const currentDesc = computed(() => {
  const d = current.value?.desc
  return typeof d === 'function' ? d() : (d || '')
})

/** 动态 CTA（按运行期数据生成；无则本步用「下一步」推进） */
const currentCta = computed(() => {
  const c = current.value?.cta
  return typeof c === 'function' ? c() : (c || null)
})

const isLast = computed(() => index.value === STEPS.length - 1)

/**
 * 主按钮（气泡右下角唯一的主色按钮）。
 * <p>CTA 优先——换界面的步骤只有它，因此不存在「CTA 和下一步并列」导致的两个问题：
 * 按钮挤爆气泡宽度、以及下一步跳过整段作用域直接收尾。</p>
 */
const primaryAction = computed(() => {
  const cta = currentCta.value
  if (cta) return { label: cta.label, kind: 'cta' }
  if (isLast.value) return { label: '开始使用', kind: 'finish' }
  return { label: '下一步', kind: 'next' }
})

/** 当前步骤的作用域是否匹配当前路由 */
function matches(step, path = route.path) {
  return typeof step.route === 'function' ? step.route(path) : true
}

/** 引导是否可见：处于激活态且当前步骤作用域匹配（不匹配时暂时隐藏，回到作用域自动接续） */
const visible = computed(() => active.value && matches(current.value))

/** 是否可上一步（向前找第一个当前作用域可用的步骤） */
const canPrev = computed(() => {
  for (let i = index.value - 1; i >= 0; i--) if (matches(STEPS[i])) return true
  return false
})

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

/**
 * 气泡位置。
 * <p>用 {@link popH}（渲染后实测）而不是写死的高度估值：改前用 250/280 的估值判断
 * 「下面放不下就翻上去」，实测气泡高度的差异（184~230px）会把气泡顶出视口底部。
 * 这里先按「目标下方优先」算，再统一夹到 `[12, vh - popH - 12]`，配合 CSS 的
 * `max-height` 做到「无论文案多长都不出屏」。</p>
 */
const popStyle = computed(() => {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const w = Math.min(340, vw - 24)
  const h = popH.value || 200
  const clampTop = (t) => Math.max(12, Math.min(Math.round(t), Math.max(12, vh - h - 12)))
  const clampLeft = (l) => Math.min(Math.max(12, Math.round(l)), Math.max(12, vw - w - 12))

  // 无目标：居中（纵向也居中，而不是固定 30vh）
  if (!rect.value) {
    return {
      left: `${clampLeft((vw - w) / 2)}px`,
      top: `${clampTop((vh - h) / 2)}px`,
      width: `${w}px`
    }
  }
  const r = rect.value
  const gap = 14
  // 大目标（如整条域侧栏/对谈会话列表）：气泡放右侧或左侧，避免压住高亮区
  const isLarge = r.height > vh * 0.5 || r.width > vw * 0.45
  if (isLarge) {
    const rightLeft = r.right + gap
    const left = rightLeft + w <= vw - 12 ? rightLeft : r.left - w - gap
    return { left: `${clampLeft(left)}px`, top: `${clampTop(r.top + 40)}px`, width: `${w}px` }
  }
  // 小目标：下方优先；下方放不下且上方放得下 → 翻到上方
  const below = r.bottom + gap
  const above = r.top - gap - h
  let top = below
  if (below + h > vh - 12) top = above >= 12 ? above : below
  return { left: `${clampLeft(r.left + r.width / 2 - w / 2)}px`, top: `${clampTop(top)}px`, width: `${w}px` }
})

/** 某个引导目标当前是否在 DOM 里（步骤⑦借它区分「已有会话→输入框」与「还没会话→新建对谈」） */
function hasEl(sel) {
  try { return !!document.querySelector(sel) } catch (_) { return false }
}

/**
 * 解析当前步骤的目标选择器。
 * <p>`targets` 允许写成函数：对谈页首次进入时没有会话，输入框根本不存在
 * （composer 只在有 currentSession 时渲染），此时「怎么开口」这一步要改指 hero 的
 * 「新建对谈」按钮——否则聚光灯找不到目标只能居中，等于没指。</p>
 */
function resolveTargets(step) {
  const t = step?.targets
  const v = typeof t === 'function' ? t() : t
  if (Array.isArray(v) && v.length) return v
  return step?.target ? [step.target] : []
}

/**
 * 取当前步骤第一个「可见且在当前视口内」的目标元素。
 * <p>两个条件都必要：<br>
 * ① 宽高为 0（`v-if` 折叠、隐藏面板）不算；<br>
 * ② 完全在视口外也不算——对谈页窄屏时右栏是「移到屏幕外的覆盖式抽屉」（有尺寸但 translateX 到视口外），
 * 改前会把挖洞画在屏幕外，用户只看到整屏变暗、没有任何高亮，比居中气泡更像坏了。</p>
 */
function findTarget() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  for (const sel of resolveTargets(current.value)) {
    const nodes = document.querySelectorAll(sel)
    for (const el of nodes) {
      const r = el.getBoundingClientRect()
      if (r.width <= 0 || r.height <= 0) continue
      if (r.right <= 0 || r.bottom <= 0 || r.left >= vw || r.top >= vh) continue
      return el
    }
  }
  return null
}

/** 量气泡自身高度（定位要用；内容变化后必须重量） */
async function syncPopSize() {
  await nextTick()
  const el = popRef.value
  if (!el) return
  const h = Math.round(el.getBoundingClientRect().height)
  if (h > 0 && h !== popH.value) popH.value = h
}

/**
 * 量测当前步骤目标；目标缺失时置空（气泡居中，不挖洞）。
 * <p>目标在屏幕外时先滚动到视野中部再量——否则聚光灯会落在看不见的地方，
 * 用户只看到「页面没动、按钮点不到」。</p>
 */
async function measure() {
  await nextTick()
  const el = findTarget()
  if (!el) {
    rect.value = null
    await syncPopSize()
    return
  }
  const r0 = el.getBoundingClientRect()
  const vh = window.innerHeight
  if (r0.top < 80 || r0.bottom > vh - 80) {
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }) } catch (_) { /* 老内核不支持参数，忽略 */ }
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  }
  const r = el.getBoundingClientRect()
  rect.value = { left: r.left, top: r.top, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
  await syncPopSize()
}

/**
 * 下一步：向前找当前作用域内的下一个步骤。
 * <p>有 CTA 的步骤直接 return——改前这里会一路跳过所有不匹配的作用域落到末步
 * （画廊第 3 步点下一步 → 直接「你已经会玩了」），因为末步 `route: () => true` 总能匹配。</p>
 */
function next() {
  if (currentCta.value) return
  for (let i = index.value + 1; i < STEPS.length; i++) {
    if (matches(STEPS[i])) { index.value = i; measure(); return }
  }
  finish()
}

/** 上一步：往前找第一个当前作用域内可用的步骤 */
function prev() {
  for (let i = index.value - 1; i >= 0; i--) {
    if (matches(STEPS[i])) { index.value = i; measure(); return }
  }
}

/** 主按钮分派（CTA / 开始使用 / 下一步） */
function runPrimary() {
  const p = primaryAction.value
  if (p.kind === 'cta') doCta(currentCta.value)
  else if (p.kind === 'finish') finish()
  else next()
}

/**
 * 执行 CTA。
 * <p>「进入世界」必须先在画廊侧打开世界（单活动世界模型：`POST /api/worlds/{id}/open`），
 * 直接 push 到 `/world/{id}/sandbox` 会进一个没挂载的世界（改前就是这样）。
 * 打开失败（已在别处打开/状态不允许）也照样跳转，由页面自己提示，不把用户卡在引导里。</p>
 */
async function doCta(cta) {
  if (!cta || !cta.route) return
  if (cta.openWorldId) {
    ctaBusy.value = true
    try { await openWorld(cta.openWorldId) } catch (_) { /* 失败交由沙盘页提示 */ }
    finally { ctaBusy.value = false }
  }
  router.push(cta.route)
}

/** 完成/跳过：关闭并写后端完成标记（跨重启不再出现） */
function finish() {
  active.value = false
  firstRunGuideActive.value = false
  rect.value = null
  // 新用户引导已覆盖「侧栏 / 界面切换 / 命令面板」，不再叠加播放「界面导览」；
  // 想看的用户可在帮助抽屉或教程中心随时重看。
  layout.endTour(true)
  completeGuide()
}

/** 加载项目列表，决定步骤③指向哪个世界（示例世界优先，其次最近更新） */
async function loadGuideProject() {
  try {
    const res = await fetchProjects({ page: 1, size: 100 })
    const list = (res && res.list) ? res.list : (Array.isArray(res) ? res : [])
    guideProject.value = pickGuideProject(list)
  } catch (_) {
    guideProject.value = null
  } finally {
    projectsLoaded.value = true
  }
}

/**
 * 启动判定：等待账户加载完成后，按「待启动 + 后端未完成」决定是否启动。
 * <p>账户加载失败（后端未就绪）时按未完成处理并保持引导关闭——宁可这次不弹，
 * 也不要在用户已经看完引导后反复弹（前端可通过教程中心重看）。</p>
 */
async function maybeStart() {
  if (active.value) return
  try { await account.loadAccount() } catch (_) { /* 失败按未加载处理 */ }
  // 账户都没取到（后端未就绪/离线）时不启动：既无法判断是否已完成，也不该在坏状态下打扰用户
  if (!account.account) return
  if (!shouldStartGuide(account.account)) return
  disarmGuide()
  active.value = true
  firstRunGuideActive.value = true
  index.value = 0
  // 极端情况下「界面导览」已被自动打开：先关掉，避免两个聚光灯叠在一起
  if (layout.tourActive) layout.endTour(false)
  loadGuideProject()
  measure()
}

/**
 * 路由变化：作用域仍匹配则重量；否则向后寻找可用步骤（画廊→浮生→对谈自动接续）。
 * <p>换界面时目标元素往往「晚一拍」才挂载（沙盘首屏要等项目详情/时钟接口），
 * 所以立刻量一次之后 400ms 再补量一次——否则聚光灯会短暂停在上一页的坐标上。</p>
 */
function onRouteChange() {
  if (!active.value) { maybeStart(); return }
  if (matches(current.value)) { measure(); remeasureSoon(); return }
  // 排除收尾步骤：用户中途回画廊时引导暂隐，回到作用域再自动接续，而不是直接结束
  for (let i = index.value + 1; i < STEPS.length - 1; i++) {
    if (matches(STEPS[i])) { index.value = i; measure(); remeasureSoon(); return }
  }
  // 无更靠后的可用步骤：保持当前步骤（visible=false 暂隐），等用户回到作用域
}

/** 延迟补量一次（新页面目标晚挂载） */
let remeasureTimer = null
function remeasureSoon() {
  if (remeasureTimer) clearTimeout(remeasureTimer)
  remeasureTimer = setTimeout(() => { if (active.value) measure() }, 400)
}

/** 键盘：←/→/Enter 推进（输入框内不拦截，避免干扰表单） */
function onKeydown(e) {
  if (!active.value) return
  const t = e.target
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  // 焦点在引导气泡内的按钮上时不再拦截：Enter/空格本就该触发该按钮，否则会「一次按键走两步」
  if (t && typeof t.closest === 'function' && t.closest('.frg-pop')) return
  if (e.key === 'ArrowRight' || e.key === 'Enter') { e.preventDefault(); runPrimary() }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); prev() }
}

let onResize = null
onMounted(() => {
  maybeStart()
  onResize = () => { if (active.value) measure() }
  window.addEventListener('resize', onResize, { passive: true })
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  if (onResize) window.removeEventListener('resize', onResize)
  if (remeasureTimer) clearTimeout(remeasureTimer)
  window.removeEventListener('keydown', onKeydown)
})

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

/* 气泡：纵向 flex + 视口高度上限。
   底部按钮区改前是不换行的 flex 行，文案一长就横向溢出（实测越界 102px）。 */
.frg-pop {
  position: fixed;
  pointer-events: auto;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 24px);
  padding: var(--space-4, 16px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv3);
  transition: all .22s var(--dsw-ease);
}
/* 正文区：唯一滚动容器（步骤文案长短差异大，长的内部滚，不撑破气泡） */
.frg-pop-body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
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
.frg-desc { margin: 10px 0 8px; font-size: 0.82rem; line-height: 1.75; color: var(--text-secondary); }
.frg-dots { display: flex; gap: 4px; }
.frg-dot { width: 12px; height: 3px; border-radius: 2px; background: var(--border-l2); }
.frg-dot.done { background: var(--accent-border); }
.frg-dot.on { background: var(--accent); }

/* 底部按钮区：可换行，动作组右对齐（跳过引导恒在左） */
.frg-pop-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2, 8px);
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-l1);
  flex-shrink: 0;
}
.frg-skip { margin-left: -8px; }
.frg-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--space-2, 8px);
  margin-left: auto;
}
</style>
