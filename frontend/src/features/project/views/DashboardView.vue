<template>
  <div class="dashboard-view">
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
        <el-button type="primary" @click="go('/chat')"><el-icon><HIcon name="ChatDotRound" /></el-icon>&nbsp;进入对话</el-button>
        <el-button @click="go('/evolve')"><el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;世界演化</el-button>
        <el-button plain @click="go('/characters')"><el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增角色</el-button>
        <!-- 世界初始化/重新初始化入口（2026-08-19：点击弹提醒确认后进入独立初始化页） -->
        <el-button plain type="warning" @click="openWorldInit"><el-icon><HIcon name="Setting" /></el-icon>&nbsp;世界初始化</el-button>
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
        <div class="wo-value" v-if="clock">{{ clock.periodText }} · 第 {{ clock.day }} 日</div>
        <div class="wo-value" v-else>—</div>
        <div class="wo-sub" v-if="clock">速率×{{ clock.rate }}（每真实小时推进的游戏小时数）</div>
      </div>
      <div class="wo-divider"></div>
      <div class="wo-cell">
        <div class="wo-label">世界模拟状态</div>
        <div class="wo-value">
          <el-tag :type="clock?.paused ? 'warning' : 'success'" effect="light" size="small">
            {{ clock?.paused ? '已暂停' : '推进中' }}
          </el-tag>
        </div>
        <el-button size="small" text type="primary" @click="go('/evolve')">前往世界演化控制 →</el-button>
      </div>
    </div>

    <!-- 统计行（四张统计卡） -->
    <div class="stats-row">
      <!-- 角色 -->
      <div class="stat-card tech-card" @click="go('/characters')">
        <div class="stat-title">角色</div>
        <div class="stat-nums">
          <div class="stat-num"><span class="num">{{ charCount.special }}</span><span class="lbl">特殊型</span></div>
          <div class="stat-num"><span class="num">{{ charCount.common }}</span><span class="lbl">普通型</span></div>
        </div>
        <div class="stat-link">前往角色详情 →</div>
      </div>
      <!-- 知识库 -->
      <div class="stat-card tech-card" @click="go('/knowledge')">
        <div class="stat-title">知识库</div>
        <div class="stat-nums">
          <div class="stat-num"><span class="num">{{ kb.docs }}</span><span class="lbl">文档</span></div>
          <div class="stat-num"><span class="num">{{ kb.chunks }}</span><span class="lbl">向量块</span></div>
        </div>
        <div class="stat-link">已向量化 {{ kb.vectorRate }}% →</div>
      </div>
      <!-- 记忆 -->
      <div class="stat-card tech-card" @click="go('/world')">
        <div class="stat-title">记忆</div>
        <div class="stat-nums">
          <div class="stat-num"><span class="num">{{ memory.character }}</span><span class="lbl">角色级</span></div>
          <div class="stat-num"><span class="num">{{ memory.project }}</span><span class="lbl">项目级</span></div>
        </div>
        <div class="stat-link">查看世界大事记 →</div>
      </div>
      <!-- 用量 -->
      <div class="stat-card tech-card" @click="go('/settings/usage')">
        <div class="stat-title">AI 用量</div>
        <div class="stat-nums">
          <div class="stat-num"><span class="num">{{ fmtTokens(usage.tokens) }}</span><span class="lbl">Tokens</span></div>
          <div class="stat-num"><span class="num">{{ usage.count }}</span><span class="lbl">调用次数</span></div>
        </div>
        <div class="stat-link">前往 AI 用量 →</div>
      </div>
    </div>

    <!-- 最近动态区 -->
    <div class="recent-card tech-card">
      <div class="recent-title">最近动态</div>
      <div v-if="recent.length === 0" class="recent-empty">暂无动态——开始对话、触发行动或推进世界模拟后，这里会展示事件/行动/对话记录。</div>
      <div v-else class="recent-list">
        <div v-for="(r, i) in recent" :key="i" class="recent-item" @click="jumpTo(r)">
          <span class="recent-time">{{ fmtTime(r.time) }}</span>
          <el-tag size="small" :type="recentTagType(r.type)" effect="plain">{{ recentTypeLabel(r.type) }}</el-tag>
          <span class="recent-text">{{ r.text }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 项目仪表盘（前端布局重构 V1.0，设计文档 §3.3）。
 * <p>职责：进入项目空间后的默认落地页，一屏总览——
 * 欢迎区（项目名/题材标签/快捷操作）+ 世界概况（世界观/当前游戏时刻/模拟状态）+
 * 统计行（角色/知识库/记忆/用量四卡，点击跳转）+ 最近动态（事件/行动/对话/记忆，点击定位）。</p>
 * <p>数据来源：projects、world-setting、world-clock、characters、knowledge-docs、memories、usage/stats、
 * actions/timeline、conversations 等现有接口。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  fetchProject, fetchWorldSetting, fetchWorldClock, fetchCharacters, fetchKnowledgeDocs,
  fetchMemories, fetchUsageStats, fetchProjectActionTimeline, fetchConversations
} from '@/shared/api'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const project = ref(null)
const world = ref(null)
const clock = ref(null)

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
 * 世界初始化/重新初始化入口（2026-08-19 新增）。
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
  try { clock.value = await fetchWorldClock(projectId) } catch (_) { /* 忽略 */ }
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
      items.push({ type: 'memory', time: m.createdAt, text: `记忆（${m.kind === 'summary' ? '摘要' : '事实'}）：${m.content}`.slice(0, 80) })
    })
  } catch (_) { /* 忽略 */ }
  recent.value = items.sort((a, b) => String(b.time || '').localeCompare(String(a.time || ''))).slice(0, 12)
}

onMounted(() => { loadBase(); loadStats(); loadRecent() })
</script>

<style scoped>
.dashboard-view { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }

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
.stat-card { padding: 16px 20px; cursor: pointer; transition: all 0.2s; }
.stat-card:hover { border-color: var(--brand-primary); box-shadow: var(--card-shadow-hover); }
.stat-title { font-size: 0.85rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 10px; }
.stat-nums { display: flex; gap: 28px; }
.stat-num { display: flex; flex-direction: column; }
.stat-num .num { font-size: 1.6rem; font-weight: 700; color: var(--brand-primary); line-height: 1.2; }
.stat-num .lbl { font-size: 0.75rem; color: var(--text-secondary); }
.stat-link { font-size: 0.75rem; color: var(--brand-primary); margin-top: 10px; }

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
