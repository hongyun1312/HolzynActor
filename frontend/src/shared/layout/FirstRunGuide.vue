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
  新用户 7 步交互式引导（全局挂载 App.vue）。

  <p>2026-10-23 重做，修复三件事：
  ① 计数一直显示「NaN / 7」、且除「跳过」外没有可继续的按钮——模板里用了未定义的 `step`，
     实际状态变量是 `index`（`undefined + 1 = NaN`，`undefined < 6` 恒假 → 下一步/开始使用都不渲染）；
  ② 步骤与当前产品脱节（旧文案讲 3D 沙盘/20 人示例世界，实际已是 2D 世界地图 + 世界运行开关 +
     浮生⇄对谈双界面 + 命令面板）；
  ③ 「跳过/看完后重启又出现」——完成状态只写 localStorage，而桌面端窗口地址
     `http://127.0.0.1:<随机端口>` 每次启动都变，localStorage 按 origin 隔离 → 标记失效。
     现完成状态落后端本地账户（{@link shouldStartGuide} / {@link completeGuide}），本地只留「待启动」。</p>

  <p>流程：项目画廊（①欢迎 → ②新增项目 → ③进入世界）→ 项目空间
  （④世界时钟与推进 → ⑤浮生⇄对谈 → ⑥域侧栏与命令面板）→ ⑦完成；完成后可在「教程中心」重看。</p>

  <p>健壮性：目标元素不存在（窄屏/元素被折叠/示例世界被删）时气泡居中不挖洞；
  用户中途离开某一步的作用域（例如从项目空间回画廊）时引导暂时隐藏、回到作用域自动接续，
  不会出现「指向空气的聚光灯」或「卡住不能动」。</p>
-->
<template>
  <teleport to="body">
    <div v-if="visible" class="frg-root" role="dialog" aria-modal="false" aria-label="新用户引导">
      <!-- 挖洞矩形：单矩形 + 超大 box-shadow = 全屏压暗且中间透明 -->
      <div class="frg-hole" :style="holeStyle"></div>

      <!-- 说明气泡 -->
      <div class="frg-pop" :style="popStyle">
        <div class="frg-pop-head">
          <span class="frg-step">{{ index + 1 }} / {{ STEPS.length }}</span>
          <span class="frg-title">{{ current.title }}</span>
        </div>
        <p class="frg-desc">{{ currentDesc }}</p>
        <div class="frg-dots" aria-hidden="true">
          <i v-for="(s, i) in STEPS" :key="s.id" class="frg-dot" :class="{ on: i === index, done: i < index }" />
        </div>
        <div class="frg-pop-foot">
          <el-button size="small" text @click="finish">跳过引导</el-button>
          <span class="frg-spacer"></span>
          <el-button size="small" :disabled="!canPrev" @click="prev">上一步</el-button>
          <!-- CTA（③进入世界 / ⑤去对谈）：跳转后由路由监听自动接续下一步 -->
          <el-button v-if="currentCta" size="small" type="primary" @click="doCta(currentCta)">
            {{ currentCta.label }}
          </el-button>
          <el-button v-if="canNext" size="small" :type="currentCta ? 'default' : 'primary'" @click="next">
            {{ isLast ? '开始使用' : '下一步' }}
          </el-button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
/**
 * 新用户 7 步交互式引导（列表见 {@link STEPS}）。
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
import { fetchProjects } from '@/shared/api'
import { disarmGuide, shouldStartGuide, completeGuide, firstRunGuideActive } from '@/shared/onboarding/firstRunGuide'

const route = useRoute()
const router = useRouter()
const account = useAccountStore()
const layout = useLayoutStore()

const active = ref(false)
const index = ref(0)
const rect = ref(null)

/** 引导要带去「逛逛」的项目（优先内置示例世界青禾村，其次最近项目；无项目时为空） */
const guideProject = ref(null)
/** 画廊项目是否已加载完（决定步骤③的文案：有项目=请点卡片；无项目=先建一个） */
const projectsLoaded = ref(false)

/** 路由判定（步骤作用域） */
const isGallery = (path) => path === '/'
const isProject = (path) => /^\/project\/\d+/.test(path)

/** 当前路由里的项目 ID（用于「去对谈界面」CTA） */
const currentProjectId = computed(() => {
  const id = Number(route.params?.id)
  if (Number.isFinite(id) && id > 0) return id
  return guideProject.value?.id || null
})

/**
 * 步骤定义。
 * <ul>
 *   <li>{@code route}：步骤只在满足该路径谓词的页面出现（不满足时向后寻找可用步骤，不会指向空气）；</li>
 *   <li>{@code targets}：聚光灯目标选择器（按顺序取第一个可见元素；空=居中气泡）；</li>
 *   <li>{@code desc}：支持函数形式，用于拼接动态文案（示例世界名、是否已有项目）；</li>
 *   <li>{@code cta}：可选主动作；实际按钮由 {@link currentCta} 按运行期数据生成。</li>
 * </ul>
 */
const STEPS = [
  {
    id: 'welcome',
    route: isGallery,
    title: '这里是「项目画廊」',
    desc: () => '每个项目就是一个独立世界：有自己的世界观、角色、时间与记忆，彼此互不干扰。下面三分钟，我们按「建世界 → 跑世界 → 聊角色」的顺序走一遍。'
  },
  {
    id: 'new-project',
    route: isGallery,
    targets: ['[data-tour="fr-add-project"]'],
    title: '新建一个世界',
    desc: () => '点右上角「新增项目」，有四种建法：手动添加 / 创意发散生成 / 文件解析（上传小说或设定，AI 帮你拆成世界观）/ 项目导入（.holzyn 包）。AI 生成与文件解析需要先接入模型——画廊上方的黄色提醒可以直接去配。'
  },
  {
    id: 'enter-world',
    route: isGallery,
    targets: ['[data-guide-demo="1"]', '[data-tour="project-card"]'],
    title: '进入一个世界',
    desc: () => {
      if (!projectsLoaded.value) return '正在读取你的项目…'
      if (!guideProject.value) return '画廊里还没有项目：先用右上角「新增项目」建一个，再回来继续这步。'
      const name = guideProject.value.name || '这个项目'
      return `点「${name}」的卡片就能进入项目空间。里面是世界地图：左边是功能域侧栏，顶上是世界时钟与运行控制，角色会按自己的日程生活。`
    },
    cta: () => (guideProject.value
      ? { label: `进入「${guideProject.value.name || '项目'}」→`, route: `/project/${guideProject.value.id}/sandbox` }
      : null)
  },
  {
    id: 'clock',
    route: isProject,
    targets: ['[data-tour="fr-clock"]'],
    title: '世界时钟：默认是静止的',
    desc: '左上角「世界时钟」显示游戏时间与运行状态。打开「世界运行」后世界才会自己推进（关掉页面也会继续，流速 ×N 可调）；主按钮随状态变化——开启世界运行 / 开始推进 / 暂停。「推进一轮」只走一个游戏小时，适合一步步看角色做了什么。'
  },
  {
    id: 'dialogue',
    route: isProject,
    targets: ['[data-tour="interface-switch"]'],
    title: '浮生 ⇄ 对谈：两个独立界面',
    desc: '世界名右侧的胶囊按钮可切换「浮生」（世界地图 / 角色 / 记忆 / 观测 / 设置）与「对谈」（世界停在当前时刻，只和角色聊天）。对谈不会改动世界进度，切回来还在原时刻。',
    cta: () => (currentProjectId.value
      ? { label: '去对谈界面试试 →', route: `/project/${currentProjectId.value}/dialogue` }
      : null)
  },
  {
    id: 'sidebar',
    route: isProject,
    targets: ['[data-tour="sidebar"]'],
    title: '左侧域侧栏 + 命令面板',
    desc: '侧栏按「世界 / 角色 / 心智 / 观测 / 设置」分组，点组名可折叠、右缘可拖宽；「世界地图」与「对谈」固定在最上面。记不住页面在哪就按 Ctrl + K 打开命令面板，输入「地图」「记忆」「密度」直接跳转或执行。'
  },
  {
    id: 'done',
    route: () => true,
    title: '你已经会玩了',
    desc: '还有更多可以在侧栏里探索：AI 助手、关系拓扑、时间线、记忆归档、世界演化……右上角「?」帮助抽屉里有完整教程与快捷键，也能在那里「重看新手引导」。祝你玩得开心！'
  }
]

const current = computed(() => STEPS[index.value] || STEPS[0])

/** 动态文案（desc 支持字符串或函数） */
const currentDesc = computed(() => {
  const d = current.value?.desc
  return typeof d === 'function' ? d() : (d || '')
})

/** 动态 CTA（按运行期数据生成；无则只显示「下一步/开始使用」） */
const currentCta = computed(() => {
  const c = current.value?.cta
  return typeof c === 'function' ? c() : (c || null)
})

const isLast = computed(() => index.value === STEPS.length - 1)

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

/** 是否可下一步：末步显示「开始使用」；中间步若后面没有可匹配步骤则直接收尾 */
const canNext = computed(() => isLast.value || index.value < STEPS.length - 1)

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

/** 气泡位置：目标下方优先，空间不够翻到上方；大目标（侧栏/整块面板）改放右侧，避免气泡盖住高亮区 */
const popStyle = computed(() => {
  const w = Math.min(340, window.innerWidth - 24)
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (!rect.value) return { left: `${Math.max(12, (vw - w) / 2)}px`, top: '30vh', width: `${w}px` }
  const r = rect.value
  const gap = 14
  // 大目标（如整条域侧栏）：气泡放不下也不该压在目标上，优先放它右侧/左侧
  const isLarge = r.height > vh * 0.5 || r.width > vw * 0.45
  if (isLarge) {
    const rightLeft = r.right + gap
    const left = rightLeft + w <= vw - 12 ? rightLeft : Math.max(12, r.left - w - gap)
    const top = Math.max(12, Math.min(r.top + 40, vh - 280))
    return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
  }
  const left = Math.min(Math.max(12, r.left + r.width / 2 - w / 2), vw - w - 12)
  let top = r.bottom + gap
  if (top + 250 > vh) top = Math.max(12, r.top - 250)
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

/** 取当前步骤第一个「可见」的目标元素（隐藏/零尺寸元素不算） */
function findTarget() {
  const selectors = current.value?.targets || (current.value?.target ? [current.value.target] : [])
  for (const sel of selectors) {
    const nodes = document.querySelectorAll(sel)
    for (const el of nodes) {
      const r = el.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) return el
    }
  }
  return null
}

/**
 * 量测当前步骤目标；目标缺失时置空（气泡居中，不挖洞）。
 * <p>目标在屏幕外时先滚动到视野中部再量——否则聚光灯会落在看不见的地方，
 * 用户只看到「页面没动、按钮点不到」。</p>
 */
async function measure() {
  await nextTick()
  const el = findTarget()
  if (!el) { rect.value = null; return }
  const r0 = el.getBoundingClientRect()
  const vh = window.innerHeight
  if (r0.top < 80 || r0.bottom > vh - 80) {
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }) } catch (_) { /* 老内核不支持参数，忽略 */ }
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  }
  const r = el.getBoundingClientRect()
  rect.value = { left: r.left, top: r.top, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
}

/** 下一步：优先找当前作用域内的下一个步骤；没有则直接进入收尾步骤 */
function next() {
  for (let i = index.value + 1; i < STEPS.length; i++) {
    if (i === STEPS.length - 1 || matches(STEPS[i])) { index.value = i; measure(); return }
  }
  finish()
}

/** 上一步：往前找第一个当前作用域内可用的步骤 */
function prev() {
  for (let i = index.value - 1; i >= 0; i--) {
    if (matches(STEPS[i])) { index.value = i; measure(); return }
  }
}

/** 执行 CTA 跳转；进入项目空间后由路由监听自动接续到世界内步骤 */
function doCta(cta) {
  if (!cta?.route) return
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
    const demo = list.find(p => /青屿|示例世界/.test(p.name || '')) || list.find(p => p && p.id === 1)
    guideProject.value = demo || list[0] || null
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

/** 路由变化：作用域仍匹配则重量；否则向后寻找可用步骤（画廊→项目空间自动接续） */
function onRouteChange() {
  if (!active.value) { maybeStart(); return }
  if (matches(current.value)) { measure(); return }
  // 排除收尾步骤：用户中途回画廊时引导暂隐，回到项目空间再自动接续，而不是直接结束
  for (let i = index.value + 1; i < STEPS.length - 1; i++) {
    if (matches(STEPS[i])) { index.value = i; measure(); return }
  }
  // 无更靠后的可用步骤：保持当前步骤（visible=false 暂隐），等用户回到作用域
}

/** 键盘：←/→/Enter 推进（输入框内不拦截，避免干扰表单） */
function onKeydown(e) {
  if (!active.value) return
  const t = e.target
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  // 焦点在引导气泡内的按钮上时不再拦截：Enter/空格本就该触发该按钮，否则会「一次按键走两步」
  if (t && typeof t.closest === 'function' && t.closest('.frg-pop')) return
  if (e.key === 'ArrowRight' || e.key === 'Enter') { e.preventDefault(); next() }
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
.frg-desc { margin: 10px 0 8px; font-size: 0.82rem; line-height: 1.75; color: var(--text-secondary); }
.frg-dots { display: flex; gap: 4px; margin-bottom: 12px; }
.frg-dot { width: 16px; height: 3px; border-radius: 2px; background: var(--border-l2); }
.frg-dot.done { background: var(--accent-border); }
.frg-dot.on { background: var(--accent); }
.frg-pop-foot { display: flex; align-items: center; gap: var(--space-2, 8px); }
.frg-spacer { flex: 1; }
</style>
