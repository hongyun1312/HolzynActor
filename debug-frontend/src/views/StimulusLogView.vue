<template>
  <div class="stimulus-log">
    <h2 class="page-title">刺激日志（M5）</h2>
    <p class="page-desc">
      即时决策通道刺激时间线 + 护栏命中统计（冷却 / 预算 / 每日预算 / 激活闸门 / 人群分层 / 空闲降频）。
      数据来自后端进程内环形缓冲（最近 500 条），业务事实仍以 actor_event / actor_ai_call_log / actor_plan 为准。
    </p>

    <div class="toolbar">
      <el-input-number v-model="projectId" :min="0" :controls="false" placeholder="项目 ID（空=全部）"
        style="width: 160px" size="small" />
      <el-select v-model="resultFilter" size="small" style="width: 150px" clearable placeholder="结果过滤">
        <el-option v-for="(label, key) in resultLabels" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button size="small" type="primary" @click="load">刷新</el-button>
      <el-button size="small" @click="autoRefresh = !autoRefresh">{{ autoRefresh ? '自动刷新：开' : '自动刷新：关' }}</el-button>
      <span class="conn-state mono" :class="{ ok: loaded }">
        {{ loaded ? `● 已加载 ${visible.length} 条` : '○ 未加载' }}
      </span>
    </div>

    <!-- 护栏统计卡片 -->
    <div class="stats-row" v-if="statsTotal > 0 || Object.keys(statsByResult).length > 0">
      <div class="stat-card total">
        <div class="stat-num">{{ statsTotal }}</div>
        <div class="stat-label">刺激记录总数</div>
      </div>
      <div v-for="(label, key) in resultLabels" :key="key" class="stat-card" :class="'r-' + key">
        <div class="stat-num">{{ statsByResult[key] || 0 }}</div>
        <div class="stat-label">{{ label }}</div>
      </div>
      <div v-if="!statsTotal" class="empty-hint">暂无记录（刺激触发/护栏命中后出现）</div>
    </div>

    <!-- 刺激时间线 -->
    <div class="log-list">
      <div v-for="e in visible" :key="e.id" class="log-item">
        <div class="log-head">
          <el-tag :type="typeTag(e.type)" size="small" effect="plain">{{ typeLabel(e.type) }}</el-tag>
          <el-tag :type="resultTag(e.result)" size="small" effect="dark">{{ resultLabels[e.result] || e.result }}</el-tag>
          <span class="mono dim">#{{ e.id }}</span>
          <span class="mono dim">{{ e.realTime ? formatTime(e.realTime) : '' }}</span>
          <span v-if="e.gameSecond != null" class="mono dim">游戏秒 {{ e.gameSecond }}</span>
          <span v-if="e.projectId" class="mono dim">项目 #{{ e.projectId }}</span>
          <span v-if="e.npcRef" class="mono dim">{{ e.npcRef }}</span>
        </div>
        <div class="log-body">
          <div class="log-text">{{ e.stimulusText || '（无文本）' }}</div>
          <div v-if="e.detail" class="log-detail mono dim">{{ e.detail }}</div>
        </div>
      </div>
      <div v-if="!visible.length && loaded" class="empty-hint">该过滤条件下无刺激记录</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { get } from '../api'

/** 结果编码 → 中文（护栏命中统计/标签共用） */
const resultLabels = {
  triggered: '已触发',
  cooldown: '冷却拦截',
  budget: '预算拦截',
  daily_budget: '每日预算拦截',
  not_active: '激活闸门拦截',
  disabled: '总开关关闭',
  crowd: '人群分层（0 AI）',
  idle_skip: '空闲降频',
  crowd_ai: '人群 AI 反应'
}

/** 刺激类型编码 → 中文 */
const typeLabels = {
  environment: '环境变化',
  state_urgency: '状态紧急',
  world_event: '世界事件',
  npc_action: '他角色行动',
  npc_approach: '他角色靠近',
  chain_reaction: '链式反应',
  wait_plan_due: '等待到期',
  idle_skip: '空闲降频'
}

const projectId = ref(undefined)
const resultFilter = ref('')
const autoRefresh = ref(true)
const loaded = ref(false)
const entries = ref([])
const statsByResult = ref({})
const statsTotal = ref(0)
let timer = null

const visible = computed(() =>
  entries.value.filter((e) => !resultFilter.value || e.result === resultFilter.value)
)

function typeLabel(t) { return typeLabels[t] || t }
function typeTag(t) {
  if (t === 'environment') return 'warning'
  if (t === 'idle_skip') return 'info'
  return 'primary'
}
function resultTag(r) {
  if (r === 'triggered' || r === 'crowd_ai') return 'success'
  if (r === 'cooldown' || r === 'budget' || r === 'daily_budget') return 'danger'
  if (r === 'not_active' || r === 'disabled') return 'info'
  if (r === 'idle_skip' || r === 'crowd') return 'warning'
  return 'info'
}
function formatTime(t) {
  return String(t).replace('T', ' ').slice(5, 19)
}

async function load() {
  try {
    const pid = projectId.value || undefined
    const [logResp, statsResp] = await Promise.all([
      get('/realtime-stimulus/log', { projectId: pid, limit: 200 }),
      get('/realtime-stimulus/stats', { projectId: pid })
    ])
    entries.value = (logResp?.data || []).map((e) => ({ ...e, realTime: e.realTime || e.time }))
    statsByResult.value = statsResp?.data?.byResult || {}
    statsTotal.value = statsResp?.data?.total || 0
    loaded.value = true
  } catch (e) {
    loaded.value = false
    // 静默失败（后端未启动/接口不可用时保持上次数据）
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => { if (autoRefresh.value) load() }, 5000)
})

onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.page-title { margin: 0 0 4px; }
.page-desc { color: var(--text-tertiary); font-size: 12px; margin: 0 0 12px; }

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.conn-state { font-size: 12px; }
.conn-state.ok { color: var(--state-success); }
.conn-state.err { color: var(--state-error); }

.stats-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.stat-card {
  min-width: 108px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
}
.stat-card.total { border-color: var(--accent); }
.stat-card.r-triggered { border-color: var(--state-success); }
.stat-card.r-cooldown, .stat-card.r-budget, .stat-card.r-daily_budget { border-color: var(--state-error); }
.stat-num { font-size: 20px; font-weight: 700; color: var(--text-primary); }
.stat-label { font-size: 12px; color: var(--text-tertiary); }

.log-list { display: flex; flex-direction: column; gap: 8px; }
.log-item {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}
.log-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; }
.log-body { font-size: 13px; color: var(--text-primary); }
.log-text { line-height: 1.5; }
.log-detail { font-size: 12px; margin-top: 2px; }

.mono { font-family: var(--font-mono, monospace); }
.dim { color: var(--text-tertiary); font-size: 12px; }
.empty-hint { color: var(--text-tertiary); font-size: 12px; padding: 10px 0; }
</style>
