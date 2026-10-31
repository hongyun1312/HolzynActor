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
    新用户开局教程中心（布局重构批次③）。

    设计目标：把「这个项目是什么、我怎么开始、每个域是干什么的、出事去哪看」
    写成一份**可执行**的清单——每一步都带一个真实可点的入口，而不是一段说明文字。
    为什么需要它：HolzynActor 的功能密度远高于普通 AI 聊天前端（六域 + 沙盘 + 调试台），
    新用户第一次打开很容易只用到「对话」一个功能，把整个「世界在运行」的部分浪费掉。

    与「界面导览」（GuidedTour）的分工：
      - 界面导览 = 一次性、跟着高亮走的 6 步，解释「界面为什么长这样」；
      - 教程中心 = 随时可回、按章推进的清单，解释「我该按什么顺序用它」。
    两者入口互通（本页可重放导览，导览结束后可跳本页）。
  -->
  <AppShell
    mode="global"
    brand-label="新手教程"
    breadcrumb="开局教程"
    :show-sidebar="false"
    :show-back="true"
    back-to="/"
  >
    <div class="content-col tutorial-page">
      <PageHeader title="新手上手教程" desc="按章节走一遍，10 分钟就能把「世界怎么跑起来」用明白。每章都有直达入口。">
        <template #actions>
          <el-button size="small" @click="replayTour">
            <el-icon><HIcon name="ic_public_highlight" /></el-icon>&nbsp;重看界面导览
          </el-button>
          <el-button size="small" @click="replayFirstRunGuide">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;重看新手引导
          </el-button>
          <el-button size="small" :disabled="!doneCount" @click="resetProgress">重置进度</el-button>
        </template>
      </PageHeader>

      <!-- 进度条 -->
      <div class="tut-progress">
        <el-progress :percentage="percent" :stroke-width="10" :show-text="false" />
        <span class="tut-progress-text">已完成 {{ doneCount }} / {{ chapters.length }} 章</span>
        <span v-if="percent === 100" class="tut-progress-done">全部完成，祝你玩得开心。</span>
      </div>

      <!-- 章节卡片 -->
      <div class="tut-grid">
        <section v-for="(ch, i) in chapters" :key="ch.key" class="tut-card" :class="{ done: isDone(ch.key) }">
          <header class="tut-card-head">
            <span class="tut-card-idx">{{ i + 1 }}</span>
            <el-icon :size="16"><HIcon :name="ch.icon" /></el-icon>
            <span class="tut-card-title">{{ ch.title }}</span>
            <el-tag v-if="isDone(ch.key)" size="small" type="success" effect="plain">已完成</el-tag>
          </header>
          <p class="tut-card-desc">{{ ch.desc }}</p>
          <ul class="tut-steps">
            <li v-for="(s, si) in ch.steps" :key="si">{{ s }}</li>
          </ul>
          <div class="tut-card-foot">
            <el-button
              v-for="a in ch.actions"
              :key="a.route || a.drawer"
              size="small"
              :type="a.primary ? 'primary' : 'default'"
              :plain="!a.primary"
              @click="go(a)"
            >{{ a.label }}</el-button>
            <span class="tut-spacer"></span>
            <el-button size="small" text @click="toggleDone(ch.key)">
              {{ isDone(ch.key) ? '取消完成' : '标记完成' }}
            </el-button>
          </div>
        </section>
      </div>

      <!-- 演示项目 -->
      <section class="tut-demo tech-card">
        <header class="tut-card-head">
          <el-icon :size="16"><HIcon name="ic_gallery_map_all" /></el-icon>
          <span class="tut-card-title">演示项目：浮生小镇 · 青屿镇</span>
        </header>
        <p class="tut-card-desc">
          不想从零建世界？项目 ID <code>1</code> 是随库内置的演示小镇（治愈系小岛生活模拟，12 名角色、
          真实感世界地图与完整关系网）。下面的入口直接打开它，怎么点都不会把数据弄坏——它本来就是给你试的。
        </p>
        <div class="tut-card-foot">
          <el-button size="small" type="primary" @click="go('/world/1/dashboard')">打开演示项目仪表盘</el-button>
          <el-button size="small" plain @click="go('/world/1/chat')">和村民对话</el-button>
          <el-button size="small" plain @click="go('/world/1/sandbox')">看世界地图</el-button>
          <el-button size="small" plain @click="go('/world/1/timeline')">翻时间线</el-button>
        </div>
      </section>

      <section class="tut-note">
        <p>
          <strong>这套界面为什么长这样：</strong>左侧是「按功能域分区」的常驻导航（世界 / 角色 / 心智 / 观测 / 设置），
          顶部一排图标是随时可开的抽屉（沙盘、对话、命令面板、AI 运行状态、帮助）。参考的是 SillyTavern（酒馆）
          的操作肌肉记忆：常用入口永远在同一个位置，页面内部再分层标签，不靠回退来换页。
        </p>
        <p>
          如果你习惯了酒馆：本项目**没有**自由拖动面板、也**不做**无前缀的单键快捷键（避免与输入框冲突），
          但保留了全部旧链接——老收藏夹里的地址不会 404。
        </p>
      </section>
    </div>
  </AppShell>
</template>

<script setup>
/**
 * 教程中心。
 *
 * <p>职责：渲染章节清单（纯前端静态内容）+ 章节完成进度（localStorage 持久化，不写后端）+
 * 直达入口按钮；并提供「重看界面导览」与进度重置。</p>
 *
 * <p>为什么进度存 localStorage 而不是后端：这是**界面级偏好**（同侧栏宽度、密度），
 * 与账户/项目数据无关，也不该随 .holzyn 包导出——换台机器重新看一遍教程没有坏处。</p>
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import { useLayoutStore } from '@/shared/store/layout'
import { replayGuide } from '@/shared/onboarding/firstRunGuide'

const router = useRouter()
const layout = useLayoutStore()

/** localStorage 键（版本化，改结构时换键避免读到旧格式） */
const STORE_KEY = 'holzyn.tutorial.v1'

/** 演示项目 ID（随库内置的「浮生小镇 · 青屿镇」） */
const DEMO_PROJECT_ID = 1

/** 已完成的章节 key 集合 */
const done = ref(loadDone())

/** 章节清单（内容为静态说明；每条 actions 都是真实存在的路由） */
const chapters = [
  {
    key: 'concept',
    icon: 'ic_public_albums',
    title: '它到底是什么',
    desc: '不是「和 AI 聊天」，而是「让一群 AI 角色在一个持续运行的世界里生活」。',
    steps: [
      '世界有自己的时钟：你关掉页面它也会继续走（真实流逝 × 倍率）。',
      '每个角色有自己的记忆、情绪、生理需求、关系网与长期目标。',
      'AI 成本不是本项目的约束——调用的多少只用来看「世界是否在真实地运转」。'
    ],
    actions: [{ label: '看世界画廊', route: '/', primary: true }]
  },
  {
    key: 'start',
    icon: 'ic_public_add',
    title: '三步把世界跑起来',
    desc: '新项目 → 世界初始化 → 恢复时钟，之后世界会自己运转。',
    steps: [
      '新建项目：可以手填世界观，也可以上传小说/设定文件让 AI 解析成结构化世界。',
      '世界初始化：生成地点、角色卡、关系网与初始事件（进度可在页面里实时看到）。',
      '时钟默认是「暂停」的，进入项目后按顶部「开始推进」世界才开始跑。'
    ],
    actions: [
      { label: '新建项目', route: '/world/new', primary: true },
      { label: '用演示项目跳过这步', route: `/world/${DEMO_PROJECT_ID}/dashboard` }
    ]
  },
  {
    key: 'domains',
    icon: 'ic_public_view_list',
    title: '六个域分别管什么',
    desc: '左侧导航按「你此刻想干什么」分区，而不是按代码模块分区。',
    steps: [
      '世界：详情 / 演化 / 沙盘 / 时间线——世界本身在发生什么。',
      '角色：NPC 角色卡 / 普通人群 / 关系拓扑 / 家族谱系——人是谁、和谁有关系。',
      '心智：项目记忆 / 记忆归档 / 知识库——世界与角色的「脑子里」有什么。',
      '观测：项目仪表盘 / AI 用量——世界跑得健不健康。',
      '设置：通用 / API 配置 / Prompt 模板 / 界面与布局。',
      '置顶的「世界地图」「对话」是最高频入口，任何页面一键可达。'
    ],
    actions: [
      { label: '去世界详情', route: `/world/${DEMO_PROJECT_ID}/world`, primary: true },
      { label: '去角色列表', route: `/world/${DEMO_PROJECT_ID}/characters` }
    ]
  },
  {
    key: 'interact',
    icon: 'ic_public_connection',
    title: '怎么和世界互动',
    desc: '三种玩法：看、说、出手干预。',
    steps: [
      '看：世界地图里看角色此刻在哪、在做什么；时间线里回看已经发生的事。',
      '说：进入对话页，私聊某个角色或开群聊；角色会带着记忆、情绪与生理状态回复你。',
      '干预：在世界演化 / 仪表盘里推进时间、注入事件，或直接改写世界设定。'
    ],
    actions: [
      { label: '和村民对话', route: `/world/${DEMO_PROJECT_ID}/chat`, primary: true },
      { label: '看世界地图', route: `/world/${DEMO_PROJECT_ID}/sandbox` }
    ]
  },
  {
    key: 'observe',
    icon: 'ic_public_view_list',
    title: '看它是不是真的在跑',
    desc: '顶栏「AI 运行状态」是主观测口；要查单次调用细节去调试台。',
    steps: [
      '顶栏活动点/运行状态抽屉：近 30 分钟的调用数、Token、缓存命中、均值与 P95 时延、场景分布。',
      '失败调用不会让世界停摆：决策链在 AI 失败时回退程序化路径（fail-open）。',
      '调试台（/debug/）是排查用的独立前端：AI Trace 全文、DB 浏览、实时日志、事件流。'
    ],
    actions: [
      { label: '打开 AI 用量', route: `/world/${DEMO_PROJECT_ID}/settings/usage`, primary: true },
      { label: '打开调试台', route: '/debug/' }
    ]
  },
  {
    key: 'keys',
    icon: 'ic_public_highlight',
    title: '快捷键与界面偏好',
    desc: '页面多、功能深，这几个键能省掉大量来回点击。',
    steps: [
      'Ctrl+K：命令面板，同时全局搜索角色 / 地点 / 事件 / 知识（回车直达）。',
      'Ctrl+B：收起/展开侧栏；Ctrl+Shift+L：切换深浅主题。',
      'Ctrl+Shift+T：重看界面导览；Alt+1~5：快速切到第 1~5 个功能域。',
      '侧栏宽度可拖拽、「界面与布局」里可改信息密度（三档）、可固定常用面板。'
    ],
    actions: [
      { label: '打开界面与布局设置', route: `/world/${DEMO_PROJECT_ID}/settings/appearance`, primary: true },
      { label: '看全部快捷键', drawer: 'help' }
    ]
  }
]

/** 进度百分比 */
const percent = computed(() => Math.round((doneCount.value / chapters.length) * 100))

/** 已完成章节数（仅统计当前仍存在的章节） */
const doneCount = computed(() => chapters.filter(c => done.value.includes(c.key)).length)

/** 读取进度 */
function loadDone() {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    return Array.isArray(parsed) ? parsed : []
  } catch (_) { return [] }
}

/** 写入进度 */
function persist() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(done.value)) } catch (_) { /* 隐私模式下静默 */ }
}

/**
 * 章节是否已完成。
 *
 * @param {string} key 章节 key
 * @returns {boolean} 是否完成
 */
function isDone(key) {
  return done.value.includes(key)
}

/**
 * 切换章节完成状态。
 *
 * @param {string} key 章节 key
 */
function toggleDone(key) {
  done.value = isDone(key) ? done.value.filter(k => k !== key) : [...done.value, key]
  persist()
}

/** 重置进度 */
function resetProgress() {
  done.value = []
  persist()
}

/**
 * 跳转 / 开面板（兼容三种写法）。
 *
 * @param {string|object} target 路由字符串，或 `{ route }` / `{ drawer }` 动作对象
 */
function go(target) {
  const t = typeof target === 'string' ? { route: target } : (target || {})
  if (t.drawer) {
    layout.openDrawer(t.drawer)
    return
  }
  if (!t.route) return
  if (t.route === '/debug/') {
    window.open('/debug/', '_blank', 'noopener')
    return
  }
  router.push(t.route)
}

/** 重看界面导览（先清 tourSeen 再启动，保证在项目页里也会出现） */
function replayTour() {
  layout.resetTour()
  layout.startTour()
}

/**
 * 重看新用户 7 步引导：清后端完成标记 + 置位「待启动（replay）」，回到画廊由 FirstRunGuide 接续。
 * <p>状态统一走 {@link replayGuide}（与首次向导/FirstRunGuide 共用一套键与后端字段），
 * 不再在本页手写 localStorage —— 历史上三处各写一套正是状态漂移的根源。</p>
 */
async function replayFirstRunGuide() {
  await replayGuide()
  router.push('/')
}
</script>

<style scoped>
/* 2026-10 布局审计：本页与「全局设置」同属无侧栏的全局页，但内容列吃到 1440 档
   （实测 x=24 / w=1384），设置页是 1200 档（x=116 / w=1200），顶栏来回切换会横向跳 92px。
   统一到 content 档：3 列卡片栅格（minmax 360）在 1200 下仍是 3 列。 */
.tutorial-page { display: flex; flex-direction: column; gap: 14px; width: 100%; max-width: var(--page-w-content, 1200px); margin: 0 auto; }

.tut-progress { display: flex; align-items: center; gap: 12px; }
.tut-progress :deep(.el-progress) { flex: 1; }
.tut-progress-text { font-size: 0.8rem; color: var(--text-secondary); white-space: nowrap; }
.tut-progress-done { font-size: 0.8rem; color: var(--state-success); }

.tut-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 14px; }
.tut-card {
  display: flex; flex-direction: column; gap: 10px;
  padding: 16px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
}
.tut-card.done { border-color: var(--state-success); }
.tut-card-head { display: flex; align-items: center; gap: 8px; }
.tut-card-idx {
  width: 22px; height: 22px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 0.76rem; font-weight: 700;
  color: var(--accent-text); background: var(--accent-soft);
  border-radius: 50%;
}
.tut-card-title { flex: 1; font-weight: 600; color: var(--text-primary); }
.tut-card-desc { margin: 0; font-size: var(--density-font-sm, 0.82rem); color: var(--text-secondary); line-height: 1.7; }
.tut-steps { margin: 0; padding-left: 1.1em; display: flex; flex-direction: column; gap: 6px; }
.tut-steps li { font-size: var(--density-font-sm, 0.82rem); color: var(--text-regular); line-height: 1.7; }
.tut-card-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: auto; }
.tut-spacer { flex: 1; }

.tut-demo { display: flex; flex-direction: column; gap: 10px; }
.tut-note {
  padding: 14px 16px;
  font-size: var(--density-font-sm, 0.82rem); line-height: 1.8; color: var(--text-secondary);
  background: var(--bg-layer-2); border-radius: var(--radius-lg);
}
.tut-note p { margin: 0 0 8px; }
.tut-note p:last-child { margin-bottom: 0; }
.tut-note code { padding: 1px 5px; background: var(--bg-layer-1); border-radius: 4px; font-family: var(--dsw-font-family-code); }
</style>
