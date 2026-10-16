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
  <!-- V2.0：页面骨架化（PageShell width="wide"）——此前本页内联 max-width:1100px 居中，
       与对谈页的 1440、角色页的"铺满"并存，导致同一产品在 1920 宽下出现三种内容列左边界
       （实测 x=300 / 378 / 548）。现由骨架统一档位，页面不再自写限宽。 -->
  <PageShell width="wide" class="dashboard-view">
    <!-- 欢迎区：项目名 + 题材标签 + 快捷操作 -->
    <div class="welcome-card tech-card">
      <div class="welcome-left">
        <div class="welcome-title">
          {{ project?.name || '项目' }}
          <el-tag v-if="world?.genre" size="small" type="primary" effect="plain" class="genre-tag">{{ world.genre }}</el-tag>
          <el-tag size="small" :type="statusType(project?.status)" effect="light" class="genre-tag">{{ statusText(project?.status) }}</el-tag>
        </div>
        <div class="welcome-desc">{{ world?.name ? `世界观：${world.name}` : (project?.summary || '暂无概要') }}</div>
      </div>
      <div class="welcome-ops">
        <!-- V2.0：统一工具条（常驻动作预算 ≤2 + 其余进「⋯」，且窄屏自动降为 1 个而不是折行）。
             这里四个按钮原有相同视觉权重，用户不知道哪个是首要动作；现在只保留
             「进入对话」为主按钮 + 「世界地图」次级，其余收纳。 -->
        <Toolbar
          :max-inline="2"
          :narrow-max-inline="1"
          :actions="welcomeActions"
          @action="onWelcomeAction"
        />
      </div>
    </div>

    <!-- 世界概况卡片（横向） -->
    <div class="world-ov-card tech-card">
      <div class="wo-cell">
        <div class="wo-label">世界观</div>
        <div class="wo-value">{{ world?.name || '未创建' }}</div>
        <div class="wo-sub">{{ (world?.summary || world?.genre || '').slice(0, 40) || '点击「世界详情」创建世界观设定' }}</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">当前游戏时刻</div>
        <!-- 实时时钟（本地插值 + SSE clock 校准，暂停冻结；完整世界历到分） -->
        <div class="wo-value clock-live" v-if="clock">{{ clockText }}</div>
        <div class="wo-value" v-else>—</div>
        <div class="wo-sub" v-if="clock">速率×{{ clock.rate }}（每真实小时推进的游戏小时数）</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">世界模拟状态</div>
        <!-- V2.0：状态指示唯一化——改造前此处并排两个措辞近似的标签
             （「已暂停/推进中」+「世界运行中/世界静止（启动全静止，进入沙盘才运行）」），
             用户无法分辨两者差别。现在只保留一个状态胶囊（RuntimeStatusPill），
             实时决策是否激活降为 tooltip 说明；时间控制入口仍只在沙盘页。 -->
        <div class="wo-value">
          <RuntimeStatusPill
            :status="clock?.paused ? 'paused' : 'running'"
            :status-label="clock?.paused ? '已暂停' : '推进中'"
            primary-text=""
            advance-text=""
            :time-text="''"
          />
          <el-tooltip placement="top" :content="rtStatusHint">
            <span class="wo-rt-note">{{ rtDecision.active ? '实时决策已激活' : '实时决策未激活' }}</span>
          </el-tooltip>
        </div>
        <div class="wo-state-ops">
          <el-button size="small" text type="primary" @click="go('/sandbox')">前往世界地图（世界演化面板 / 时钟控制）→</el-button>
          <!-- 「手动常开」此前被挤成竖排 4 字并贴到卡片右缘（实测截图），
               现改为 nowrap + tooltip 说明语义 -->
          <span class="wo-switch">
            <el-switch size="small" :model-value="rtDecision.manualEnabled" :loading="rtBusy" @change="toggleRtManual" />
            <span class="wo-switch-label" title="常开：即使不打开世界地图页，世界也持续推进（手动优先于页面自动态）">手动常开</span>
          </span>
        </div>
      </div>
    </div>

    <!-- 宏观社会卡片 -->
    <div class="world-ov-card tech-card">
      <div class="wo-cell">
        <div class="wo-label">宏观社会</div>
        <div class="wo-value">{{ macro?.templateId ? templateLabel(macro.templateId) : '未初始化' }}</div>
        <!-- V2.0 §8.1：原先固定宽度下这句被截断成「AI 强度：标准 · 第 150…」且无 tooltip，
             用户既看不到完整值也不知道口径。现补全 tooltip（口径写清）+ 不再截断。 -->
        <div
          v-if="macro"
          class="wo-sub"
          :title="`AI 强度：${aiIntensityLabel} —— 智能体使用 AI 的密度档位（与运行模式正交）；世界已运行至第 ${(macro.gameDay || 0) + 1} 日`"
        >AI 强度：{{ aiIntensityLabel }} · 第 {{ (macro.gameDay || 0) + 1 }} 日</div>
        <div class="wo-sub" v-else>世界模拟推进后自动初始化（每日 00:05 宏观 tick）</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">人口</div>
        <div class="wo-value">{{ macro?.population ?? '—' }}</div>
        <div class="wo-sub">口（宏观人口池）</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">粮价</div>
        <div class="wo-value">{{ macro?.grainPrice != null ? Math.round(macro.grainPrice) : '—' }}</div>
        <div class="wo-sub">文/石 · 市集 {{ macro?.marketActivity != null ? Math.round(macro.marketActivity) : '—' }}</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">民怨</div>
        <div class="wo-value" style="display:flex; align-items:center; gap:8px;">
          <span>{{ macro?.grievance != null ? Math.round(macro.grievance) : '—' }}</span>
          <el-progress v-if="macro?.grievance != null" :percentage="Math.round(macro.grievance)" :stroke-width="6"
            :show-text="false" style="width: 80px" :color="grievanceColor(macro.grievance)" />
        </div>
        <div class="wo-sub">生计→舆论→势力因果链</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">季节 · 收成</div>
        <div class="wo-value">{{ seasonName(macro?.season) }}{{ macro?.harvestFactor ? ' · ' + macro.harvestFactor.toFixed(2) : '' }}</div>
        <!-- V2.0 §8.1：缺失值不能只给一个破折号，要说明「什么时候会有」 -->
        <div class="wo-sub">{{ macro?.season ? '收成系数（季节×天气×累积）' : '世界运行满 1 天后生成' }}</div>
      </div>
      <div class="wo-divider"></div>
      <!-- 宏观详情入口 → 沙盘宏观面板（实时民怨/事件流） -->
      <div class="wo-cell" style="cursor: pointer;" @click="go('/sandbox')">
        <div class="wo-label">宏观详情</div>
        <div class="wo-value" style="font-size: 13px; color: var(--brand-primary);">沙盘宏观面板 →</div>
        <div class="wo-sub">民怨 / 最近宏观事件</div>
      </div>
    </div>

    <!-- 统计行（四张指标卡）
         V2.0：改用 MetricCard（**一卡一指标** + 单位 + 口径 tooltip + 可解释缺失）。
         改造前每张卡并排两个同尺寸大数字（7/10、38/0、18/0、0/0），下方才是小字标签，
         用户无法判断是分数、对比还是两项独立指标；现在主值只有一个（总量），
         第二项数据降级为小字行（breakdown），缺失值给出原因而不是无解释的破折号。 -->
    <div class="stats-row">
      <MetricCard
        label="角色"
        :value="charCount.special + charCount.common"
        unit="位"
        hint="特殊型拥有独立 11 维档案与记忆；普通型按群体调度"
        :missing="charCount.special + charCount.common === 0"
        missing-reason="尚未创建角色"
        :breakdown="[
          { label: '特殊型', value: charCount.special },
          { label: '普通型', value: charCount.common }
        ]"
        action-text="前往角色详情"
        @action="go('/characters')"
      />
      <MetricCard
        label="知识库"
        :value="kb.docs"
        unit="篇文档"
        hint="世界设定、角色知识等文档；检索时按向量相似度召回"
        :missing="kb.docs === 0"
        missing-reason="尚未导入知识文档"
        :breakdown="[
          { label: '向量块', value: kb.chunks },
          { label: '已向量化', value: kb.vectorRate + '%' }
        ]"
        action-text="前往知识库"
        @action="go('/knowledge')"
      />
      <MetricCard
        label="记忆"
        :value="memory.character + memory.project"
        unit="条"
        hint="角色级=角色个人经历；项目级=世界编年史与大事记"
        :missing="memory.character + memory.project === 0"
        missing-reason="世界运行后开始沉淀"
        :breakdown="[
          { label: '角色级', value: memory.character },
          { label: '项目级', value: memory.project }
        ]"
        action-text="查看世界大事记"
        @action="go('/world-memory')"
      />
      <MetricCard
        label="AI 用量"
        :value="fmtTokens(usage.tokens)"
        unit="Tokens"
        hint="本项目累计推理消耗（含决策/对话/记忆等全部场景）"
        :missing="!usage.count"
        missing-reason="还没有调用过模型"
        :breakdown="[{ label: '调用次数', value: usage.count }]"
        action-text="前往 AI 用量"
        @action="go('/settings/usage')"
      />
    </div>

    <!-- 最近动态区 -->
    <div class="recent-card tech-card">
      <div class="recent-title">最近动态</div>
      <div v-if="recent.length === 0" class="recent-empty">暂无动态——开始对话、触发行动或推进世界模拟后，这里会展示事件/行动/对话记录。</div>
      <div v-else class="recent-list">
        <div v-for="(r, i) in recent" :key="i" class="recent-item" @click="jumpTo(r)">
          <span class="recent-time">{{ fmtTime(r.time) }}</span>
          <el-tag size="small" :type="recentTagType(r.type)" effect="plain">{{ recentTypeLabel(r.type) }}</el-tag>
          <span class="recent-text" :title="r.text">{{ r.text }}</span>
        </div>
      </div>
    </div>
  </PageShell>
</template>

<script setup>
/**
 * 项目仪表盘。
 * <p>职责：进入项目空间后的默认落地页，一屏总览——
 * 欢迎区（项目名/题材标签/快捷操作）+ 世界概况（世界观/当前游戏时刻/模拟状态）+
 * 统计行（角色/知识库/记忆/用量四卡，点击跳转）+ 最近动态（事件/行动/对话/记忆，点击定位）。</p>
 * <p>数据来源：projects、world-setting、world-clock、characters、knowledge-docs、memories、usage/stats、
 * actions/timeline、conversations 等现有接口。</p>
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import PageShell from '@/shared/layout/PageShell.vue'
import MetricCard from '@/shared/components/MetricCard.vue'
import RuntimeStatusPill from '@/shared/components/RuntimeStatusPill.vue'
import Toolbar from '@/shared/components/Toolbar.vue'
import { createSubscription } from '@/shared/sse/sseClient'
import { gameSecondNow, engineSecondNow, formatWorldTimeMinute, calendarNameOf } from '@/shared/worldClock'
import {
  fetchProject, fetchWorldSetting, fetchWorldClock, fetchCharacters, fetchKnowledgeDocs,
  fetchMemories, fetchUsageStats, fetchProjectActionTimeline, fetchConversations,
  sandboxStreamUrl, fetchRealtimeDecision, setRealtimeDecision,
  fetchMacroState, fetchWorldEngineConfig
} from '@/shared/api'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const project = ref(null)
const world = ref(null)
const clock = ref(null)

// ：宏观社会状态（/macro/state 摘要 + AI 强度标签；60s 周期刷新）
const macro = ref(null)
const aiIntensityLabel = ref('—')
let macroTimer = null

/** 季节显示名 */
function seasonName(s) {
  return { spring: '春季', summer: '夏季', autumn: '秋季', winter: '冬季' }[s] || '—'
}
/** 模板显示名 */
function templateLabel(t) {
  return {
    xianxia: '仙侠·农耕', ancient: '古代·农耕', wuxia: '高武·农耕',
    modern: '现代·工业', future: '未来·信息'
  }[t] || t || '—'
}
/** 民怨进度条颜色（<50 绿 / <80 橙 / ≥80 红） */
function grievanceColor(g) {
  return g >= 80 ? '#e74c3c' : g >= 50 ? '#e6a23c' : '#52c41a'
}

/** 加载宏观状态摘要 + AI 强度（404=未初始化静默） */
async function loadMacro() {
  try { macro.value = await fetchMacroState(projectId) } catch (_) { /* 未初始化/接口异常静默 */ }
  try {
    const c = await fetchWorldEngineConfig(projectId)
    aiIntensityLabel.value = c.aiIntensityLabel || '标准'
  } catch (_) { /* 引擎行懒创建，保持默认 */ }
}

// ：实时决策激活状态（默认关；active=手动常开 ∪ 沙盘页打开 ∪ 演化会话进行）
const rtDecision = reactive({ active: false, manualEnabled: false, sandboxActive: false, evolutionActive: false })
const rtBusy = ref(false)

// ：实时时钟（本地插值 + SSE clock 校准；暂停冻结）
const liveGameSecond = ref(0)
let clockTick = null
let clockSub = null

/** 实时时钟文案（完整世界历到分；历法名取 VO calendarName 或从 gameTimeText 解析） */
const clockText = computed(() => {
  if (!clock.value) return '—'
  return formatWorldTimeMinute(liveGameSecond.value, clock.value.calendarName || calendarNameOf(clock.value.gameTimeText))
})

// 统计
const charCount = reactive({ special: 0, common: 0 })
const kb = reactive({ docs: 0, chunks: 0, vectorRate: 0 })
const memory = reactive({ character: 0, project: 0 })
const usage = reactive({ tokens: 0, count: 0 })

// 最近动态
const recent = ref([])

/** 项目状态文案/类型 */
function statusText(s) { return s === 1 ? '已生成角色卡' : (s === 2 ? '进行中' : '草稿') }
function statusType(s) { return s === 1 ? 'success' : (s === 2 ? 'primary' : 'info') }

/** 快捷跳转（相对 /project/:id） */
function go(sub) { router.push(`/project/${projectId}${sub}`) }

/**
 * 世界初始化/重新初始化入口。
 * 点击弹提醒确认（可能重建/重新生成地点/角色卡/普通NPC/关系/世界时间/向量化），
 * 确认后进入独立全屏世界初始化页（/project/:id/init），可在页内选择「跳过已生成/全量重建」。
 */
function openWorldInit() {
  ElMessageBox.confirm(
    `即将进入「世界初始化」页面并自动运行 6 步工作流：\n` +
    `① 世界观地点 ② 角色卡 ③ 字段字典与普通 NPC ④ 关系拓扑 ⑤ 世界时间 ⑥ 知识向量化。\n\n` +
    `⚠ 提醒：若选择「全量重建」会重新生成并可能覆盖已有的地点/普通 NPC/关系等数据；` +
    `页面内默认使用「跳过已生成（幂等）」模式。是否继续？`,
    '世界初始化',
    {
      confirmButtonText: '进入初始化',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(() => {
    router.push(`/project/${projectId}/init`)
  }).catch(() => { /* 用户取消 */ })
}

/** Token 格式化（千分位/万） */
function fmtTokens(t) {
  const n = Number(t) || 0
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

/** 时间格式化 */
function fmtTime(t) {
  if (!t) return ''
  return String(t).slice(5, 16).replace('T', ' ')
}

/** 动态类型标签 */
function recentTypeLabel(t) {
  return { event: '世界事件', action: '角色行动', dialog: '对话', memory: '记忆' }[t] || t
}
function recentTagType(t) {
  return { event: 'warning', action: 'primary', dialog: 'success', memory: 'info' }[t] || 'info'
}

/** 记忆类型显示名 */
function memKindLabel(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? '摘要' : '事实'
  if (m.type === 'episodic') return '经历'
  if (m.type === 'reflection') return '反思'
  if (m.type === 'plan') return '计划'
  return '记忆'
}

/** 最近动态跳转（事件/行动 → 时间线；对话 → 对话页；记忆 → 世界详情/知识库） */
function jumpTo(r) {
  if (r.type === 'dialog') go('/chat')
  else if (r.type === 'memory') go('/world')
  else go('/timeline')
}

/** 加载项目/世界观/时钟 */
async function loadBase() {
  try { project.value = await fetchProject(projectId) } catch (_) { /* 忽略 */ }
  try { world.value = await fetchWorldSetting(projectId) } catch (_) { /* 忽略 */ }
  try { applyClockPayload(await fetchWorldClock(projectId)) } catch (_) { /* 忽略 */ }
}

// ====================：实时时钟（插值 + SSE 校准） ====================

/**
 * 合并 clock 载荷并校准插值锚点：world-clock VO（含 calendarName/gameSecond/refRealTime/rate/paused）
 * 或沙盘 SSE clock 事件（gameDay/gameSecond/refRealTime/rate/paused/gameTimeText，无独立 calendarName）。
 */
function applyClockPayload(data) {
  if (!data) return
  if (!clock.value) clock.value = {}
  Object.assign(clock.value, data)
  const cal = data.calendarName || calendarNameOf(data.gameTimeText)
  if (cal) clock.value.calendarName = cal
  liveGameSecond.value = calcLiveGameSecond()
}

/**
 * 基于锚点本地插值当前游戏秒（起：载荷含引擎位置 engineGameSecond → 显示引擎实际推进位置
 * [世界的实际时间，AI 慢/引擎停顿不虚快]；载荷缺引擎字段 → 回退 流动时钟换算插值）
 */
function calcLiveGameSecond() {
  const c = clock.value
  if (!c) return 0
  if (c.engineGameSecond != null) {
    return engineSecondNow(c.engineGameSecond, c.engineRefRealTime, c.rate, c.paused, c.engineTickGameSeconds)
  }
  return gameSecondNow(c.gameSecond, c.refRealTime, c.rate, c.paused)
}

/** 启动本地时钟插值 tick（每 1 真实秒重算一次，驱动时钟连续跳动） */
function startClockTick() {
  stopClockTick()
  clockTick = setInterval(() => { liveGameSecond.value = calcLiveGameSecond() }, 1000)
  liveGameSecond.value = calcLiveGameSecond()
}

/** 停止本地时钟插值 tick（组件卸载时清理） */
function stopClockTick() {
  if (clockTick) { clearInterval(clockTick); clockTick = null }
}

/**
 * 订阅沙盘实时流的 clock 事件校准锚点（：与沙盘页同协议——「收到 clock 事件 → 记锚点 → 本地插值」；
 * 仅消费 clock / sandbox:init 的 clock 段，其余事件忽略；断线/失败静默，一次性锚点仍可本地插值）。
 */
function connectClockStream() {
  try {
    clockSub = createSubscription({
      url: sandboxStreamUrl(projectId),
      events: ['sandbox:init', 'clock'],
      heartbeatTimeoutMs: 45000,
      onEvent: (name, data) => {
        if (!data) return
        if (name === 'sandbox:init') { if (data.clock) applyClockPayload(data.clock) }
        else if (name === 'clock') applyClockPayload(data)
      },
      onError: () => { /* 静默：world-clock 锚点 + 本地插值仍可用 */ },
      onGiveUp: () => { /* 静默 */ }
    })
  } catch (_) { /* 连接失败静默 */ }
}

/** 加载统计 */
async function loadStats() {
  try {
    const chars = await fetchCharacters(projectId)
    charCount.special = chars.filter(c => c.type !== 'common').length
    charCount.common = chars.filter(c => c.type === 'common').length
  } catch (_) { /* 忽略 */ }
  try {
    const docs = await fetchKnowledgeDocs(projectId)
    kb.docs = docs.length
    kb.chunks = docs.reduce((s, d) => s + (d.chunkCount || 0), 0)
    const vec = docs.filter(d => d.vectorized)
    kb.vectorRate = docs.length ? Math.round((vec.length / docs.length) * 100) : 0
  } catch (_) { /* 忽略 */ }
  try {
    const page = await fetchMemories(projectId, { page: 1, size: 100 })
    const list = page.list || []
    memory.project = list.filter(m => !m.characterId).length
    memory.character = list.length - memory.project
  } catch (_) { /* 忽略 */ }
  try {
    const s = await fetchUsageStats({ projectId })
    usage.tokens = (s?.summary?.tokenIn || 0) + (s?.summary?.tokenOut || 0)
    usage.count = s?.summary?.count || 0
  } catch (_) { /* 忽略 */ }
}

/** 加载最近动态（行动时间线 + 会话 + 记忆，合并按时间倒序取前 12） */
async function loadRecent() {
  const items = []
  try {
    const tl = await fetchProjectActionTimeline(projectId, {})
    ;(tl.timeline || []).forEach(n => {
      items.push({ type: n.kind === 'event' ? 'event' : 'action', time: n.time, text: n.kind === 'event' ? n.content : `${n.action || n.summary || ''}`.slice(0, 80) })
    })
  } catch (_) { /* 忽略 */ }
  try {
    const convs = await fetchConversations(projectId)
    ;(convs || []).forEach(c => {
      items.push({ type: 'dialog', time: c.updatedAt, text: `会话：${c.title || (c.mode === 'group' ? '群聊' : '单聊')}` })
    })
  } catch (_) { /* 忽略 */ }
  try {
    const page = await fetchMemories(projectId, { page: 1, size: 10 })
    ;(page.list || []).forEach(m => {
      items.push({ type: 'memory', time: m.createdAt, text: `记忆（${memKindLabel(m)}）：${m.content}`.slice(0, 80) })
    })
  } catch (_) { /* 忽略 */ }
  recent.value = items.sort((a, b) => String(b.time || '').localeCompare(String(a.time || ''))).slice(0, 12)
}

onMounted(() => {
  loadBase(); loadStats(); loadRecent(); startClockTick(); connectClockStream(); loadRtState(); loadMacro()
  macroTimer = setInterval(loadMacro, 60000) // ：宏观指标 60s 周期刷新（世界持续推进时保持新鲜）
})
onBeforeUnmount(() => {
  stopClockTick()
  if (macroTimer) { clearInterval(macroTimer); macroTimer = null }
  if (clockSub) { clockSub.close(); clockSub = null }
})

// ====================：实时决策激活（默认关；仅展示与手动常开，不驱动页面打开） ====================

/** 主动拉取激活状态（仪表盘仅展示 + 手动常开；沙盘/演化页才驱动页面自动开/关） */
async function loadRtState() {
  try {
    const st = await fetchRealtimeDecision(projectId)
    if (st) {
      rtDecision.active = !!st.active
      rtDecision.manualEnabled = !!st.manualEnabled
      rtDecision.sandboxActive = !!st.sandboxActive
      rtDecision.evolutionActive = !!st.evolutionActive
    }
  } catch (_) { /* 静默 */ }
}

/** 手动常开开关（手动优先于页面自动态：常开=不开页面世界也持续推进） */
async function toggleRtManual(enabled) {
  rtBusy.value = true
  try {
    const st = await setRealtimeDecision(projectId, { action: 'set', enabled })
    if (st) {
      rtDecision.active = !!st.active
      rtDecision.manualEnabled = !!st.manualEnabled
    }
  } catch (_) { /* 静默 */ } finally {
    rtBusy.value = false
  }
}

/**
 * 实时决策状态的完整口径说明（V2.0）。
 *
 * <p>改造前这里直接铺了一整句「世界静止（启动全静止，进入沙盘才运行）」当作标签，
 * 既挤占宽度又与旁边的「已暂停/推进中」语义重叠。现在标签只留「实时决策已激活/未激活」，
 * 完整口径进 tooltip。</p>
 */
const rtStatusHint = computed(() => {
  const parts = []
  if (rtDecision.manualEnabled) parts.push('手动常开')
  if (rtDecision.sandboxActive) parts.push('世界地图页已打开')
  if (rtDecision.evolutionActive) parts.push('演化会话进行中')
  const why = parts.length ? `当前来源：${parts.join(' + ')}` : '当前没有来源（世界时间可在世界地图页手动推进）'
  return `实时决策：让角色按自己的日程自动行动。${why}`
})

/** 欢迎区动作（V2.0：交给统一工具条做优先级与收纳，不再平铺 4 个同权重按钮） */
const welcomeActions = computed(() => [
  { key: 'chat', label: '进入对话', icon: 'ChatDotRound', kind: 'primary', title: '与角色单聊 / 群聊（世界继续推进）' },
  { key: 'sandbox', label: '世界地图', icon: 'MapLocation', title: '看世界正在发生什么，并控制世界时钟' },
  { key: 'characters', label: '新增角色', icon: 'Plus', title: '创建特殊 NPC 并生成 11 维档案' },
  { key: 'init', label: '世界初始化', icon: 'Setting', kind: 'danger', title: '重新生成世界设定与角色阵容（需二次确认）' }
])

/** 欢迎区动作分发 */
function onWelcomeAction(key) {
  if (key === 'chat') { go('/chat'); return }
  if (key === 'sandbox') { go('/sandbox'); return }
  if (key === 'characters') { go('/characters'); return }
  if (key === 'init') { openWorldInit(); return }
}
</script>

<style scoped>
/* V2.0：本页不再自写限宽（原 max-width:1100px 造成与其它页的内容列错位）——
   限宽与居中交给 PageShell width="wide"，这里只保留页面纵向节奏的兜底。 */
.dashboard-view { display: flex; flex-direction: column; gap: 16px; }

/* 欢迎区 */
.welcome-card { padding: 20px 24px; display: flex; align-items: center; justify-content: space-between; }
.welcome-title { font-size: 1.35rem; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.genre-tag { margin-left: 4px; }
.welcome-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; }
.welcome-ops { display: flex; gap: 8px; }

/* 世界概况 */
.world-ov-card { padding: 18px 24px; display: flex; align-items: center; gap: 20px; }
.wo-cell { flex: 1; min-width: 0; }
.wo-divider { width: 1px; height: 44px; background: var(--border-light); flex-shrink: 0; }
.wo-label { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 6px; }
.wo-value { font-size: 1.05rem; font-weight: 700; color: var(--text-primary); }
.wo-sub { font-size: 0.78rem; color: var(--text-secondary); margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 统计行 */
.stats-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
/* 旧 .stat-card/.stat-nums/.stat-num/.stat-link 样式在 V2.0 已被 MetricCard 组件取代
   （删掉以免成为第二套"指标卡"实现；保留 .stat-num 的单数字观感由组件内部承担）。 */
.stat-card { padding: 16px 20px; cursor: pointer; transition: all 0.2s; }
.stat-card:hover { border-color: var(--brand-primary); box-shadow: var(--card-shadow-hover); }
.stat-title { font-size: 0.85rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 10px; }
.stat-nums { display: flex; gap: 28px; }
.stat-num { display: flex; flex-direction: column; }
.stat-num .num { font-size: 1.6rem; font-weight: 700; color: var(--brand-primary); line-height: 1.2; }
.stat-num .lbl { font-size: 0.75rem; color: var(--text-secondary); }
.stat-link { font-size: 0.75rem; color: var(--brand-primary); margin-top: 10px; }

/* V2.0：世界模拟状态单元格——状态胶囊 + 一句补充说明 + 操作行（标签一律 nowrap，杜绝竖排） */
.wo-value { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wo-rt-note { font-size: 0.72rem; color: var(--text-tertiary); cursor: help; white-space: nowrap; }
.wo-state-ops { display: flex; align-items: center; gap: 10px; margin-top: 6px; flex-wrap: wrap; }
.wo-switch { display: inline-flex; align-items: center; gap: 4px; font-size: 0.75rem; color: var(--text-secondary); }
.wo-switch-label { white-space: nowrap; cursor: help; }

/* 最近动态 */
.recent-card { padding: 18px 24px; }
.recent-title { font-weight: 700; color: var(--text-primary); margin-bottom: 12px; }
.recent-empty { color: var(--text-secondary); font-size: 0.85rem; padding: 20px 0; text-align: center; }
.recent-list { display: flex; flex-direction: column; }
.recent-item { display: flex; align-items: center; gap: 12px; padding: 10px 8px; border-radius: 8px; cursor: pointer; transition: background 0.15s; border-bottom: 1px solid var(--border-light); }
.recent-item:last-child { border-bottom: none; }
.recent-item:hover { background: var(--bg-light); }
.recent-time { font-size: 0.75rem; color: var(--text-placeholder); width: 110px; flex-shrink: 0; }
.recent-text { flex: 1; font-size: 0.86rem; color: var(--text-regular); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
