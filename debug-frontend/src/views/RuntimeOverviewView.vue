<template>
  <div class="runtime-overview">
    <div class="head-row">
      <h2 class="page-title">AgentRuntime 概览</h2>
      <div class="head-controls">
        <el-select v-model="projectId" clearable placeholder="全部项目" size="small" style="width: 200px"
          @change="load">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <span class="dim mono">窗口 {{ data?.days || 30 }} 天 · 截至 {{ fmtTime(data?.asOf) }}</span>
      </div>
    </div>

    <!-- 引擎运行汇总（六组件运行环境） -->
    <div class="cards">
      <el-card shadow="never" class="stat-card">
        <div class="stat-label">世界引擎</div>
        <div class="stat-value mono">{{ engine?.runningCount ?? '-' }}</div>
        <div class="stat-sub">持续运行项目 · 暂停 {{ engine?.pausedCount ?? 0 }} · 关闭 {{ engine?.stoppedCount ?? 0 }}</div>
        <div class="stat-sub mono">扫描间隔 {{ fmtInterval(engine?.scanIntervalMs) }} ·
          <el-tag :type="engine?.simEnabled ? 'success' : 'info'" size="small">{{ engine?.simEnabled ? '模拟开' : '模拟关' }}</el-tag>
        </div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">每 tick AI 预算（缺省）</div>
        <div class="stat-value mono">{{ data?.defaultBudgetPerTick ?? '-' }}</div>
        <div class="stat-sub">ActorAgentProfile.ai_budget_per_tick 缺省值（继承全局）</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">脑力配置行</div>
        <div class="stat-value mono">{{ agents?.length ?? '-' }}</div>
        <div class="stat-sub">特殊 NPC + 普通 NPC 的 actor_agent_profile</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">项目数</div>
        <div class="stat-value mono">{{ projects?.length ?? '-' }}</div>
        <div class="stat-sub">世界推进中的项目摘要（含引擎预算/时钟）</div>
      </el-card>
    </div>

    <!-- 组件级状态 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-title">组件级状态
          <span class="dim" style="font-weight:400; margin-left:8px">六组件 + 感知/脑力扩展，按 AI Trace scene/环节聚合（窗口内）</span>
        </div>
      </template>
      <div class="comp-grid">
        <div v-for="c in components" :key="c.key" class="comp-card" :class="{ 'comp-core': c.core }">
          <div class="comp-head">
            <span class="comp-name">{{ c.name }}</span>
            <el-tag v-if="c.core" type="primary" size="small" effect="plain">核心</el-tag>
            <el-tag v-else size="small" effect="plain" type="info">扩展</el-tag>
          </div>
          <div class="comp-stats">
            <div class="comp-stat"><span class="num mono">{{ c.aiCalls30d }}</span><span class="lbl">AI 调用</span></div>
            <div class="comp-stat"><span class="num mono">{{ c.steps30d }}</span><span class="lbl">环节</span></div>
            <div class="comp-stat"><span class="num mono">{{ (c.tokenIn30d || 0) + (c.tokenOut30d || 0) }}</span><span class="lbl">Token</span></div>
          </div>
          <div class="comp-scenes mono" v-if="c.scenes?.length">{{ c.scenes.join(' / ') }}</div>
          <div class="comp-desc">{{ c.desc }}</div>
        </div>
      </div>
    </el-card>

    <!-- 各角色 brainProfile / 决策频率 / 预算使用 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-title">各角色 brainProfile · 决策频率 · 预算使用
          <span class="dim" style="font-weight:400; margin-left:8px">决策调用 = scene=action 的 AI 调用；决策链路 = 去重 trace_id</span>
        </div>
      </template>
      <el-table :data="agents" size="small" stripe :empty-text="'暂无脑力配置角色（启动后自动补行）'">
        <el-table-column label="角色" min-width="120">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <el-tag :type="row.agentType === 'special' ? 'success' : 'info'" size="small" style="margin-left:6px">
              {{ row.agentType === 'special' ? '特殊' : '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="脑力档" width="90">
          <template #default="{ row }">
            <el-tag :type="brainType(row.brainLevel)" size="small">{{ brainLabel(row.brainLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型档" width="80">
          <template #default="{ row }">{{ modelLabel(row.modelTier) }}</template>
        </el-table-column>
        <el-table-column label="决策频率" width="90">
          <template #default="{ row }">{{ freqLabel(row.decisionFrequency) }}</template>
        </el-table-column>
        <el-table-column label="组件开关" width="110">
          <template #default="{ row }">
            <span :class="{ dim: row.thinkingEnabled !== 1 }">思{{ row.thinkingEnabled === 1 ? '●' : '○' }}</span>
            <span :class="{ dim: row.planningEnabled !== 1 }" style="margin-left:6px">规{{ row.planningEnabled === 1 ? '●' : '○' }}</span>
            <span :class="{ dim: row.reflectionEnabled !== 1 }" style="margin-left:6px">反{{ row.reflectionEnabled === 1 ? '●' : '○' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="记忆预算" width="80">
          <template #default="{ row }">{{ row.memoryBudget ?? '继承' }}</template>
        </el-table-column>
        <el-table-column label="AI 预算/tick" width="95">
          <template #default="{ row }">{{ row.aiBudgetPerTick ?? '继承' }}</template>
        </el-table-column>
        <el-table-column label="决策调用 30d" width="105" align="right">
          <template #default="{ row }"><span class="mono">{{ row.decisionCalls30d }}</span></template>
        </el-table-column>
        <el-table-column label="决策链路 30d" width="105" align="right">
          <template #default="{ row }"><span class="mono">{{ row.traces30d }}</span></template>
        </el-table-column>
        <el-table-column label="AI 调用 30d" width="105" align="right">
          <template #default="{ row }"><span class="mono">{{ row.aiCalls30d }}</span></template>
        </el-table-column>
        <el-table-column label="Token 30d" width="110" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtTokens(row) }}</span></template>
        </el-table-column>
        <el-table-column label="最近决策" min-width="150">
          <template #default="{ row }">
            <span class="dim">{{ fmtTime(row.lastDecisionAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="配置更新" min-width="150">
          <template #default="{ row }">
            <span class="dim">{{ fmtTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 项目摘要 -->
    <el-card shadow="never" class="block-card">
      <template #header><div class="card-title">项目摘要（世界引擎）</div></template>
      <el-table :data="projects" size="small" stripe>
        <el-table-column prop="name" label="项目" min-width="140" />
        <el-table-column label="世界引擎" min-width="170">
          <template #default="{ row }">
            <el-tag :type="row.worldRunning === 1 ? 'success' : 'info'" size="small">{{ row.worldRunning === 1 ? '持续运行' : '已关闭' }}</el-tag>
            <el-tag v-if="row.paused === 1" type="warning" size="small" style="margin-left:4px">暂停</el-tag>
            <span class="dim mono" style="margin-left:6px">进度 h{{ row.lastGameHour ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="引擎预算/tick" width="120">
          <template #default="{ row }"><span class="mono">{{ row.budgetPerTick ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column label="角色 / NPC" width="100">
          <template #default="{ row }" class="mono">{{ row.characterCount }} / {{ row.npcCount }}</template>
        </el-table-column>
        <el-table-column label="上次推进" min-width="150">
          <template #default="{ row }"><span class="dim">{{ fmtTime(row.lastEngineRunAt) }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { get } from '../api'

const data = ref(null)
const projectId = ref(null)
const loading = ref(false)

const projects = computed(() => data.value?.projects || [])
const agents = computed(() => data.value?.agents || [])
const components = computed(() => data.value?.components || [])
const engine = computed(() => data.value?.engine || {})

async function load() {
  loading.value = true
  try {
    data.value = await get('/debug/agent/runtime-overview', { projectId: projectId.value || undefined })
  } catch (e) {
    console.error('AgentRuntime 概览加载失败', e)
  } finally {
    loading.value = false
  }
}

function brainType(level) {
  return { full: 'success', light: 'warning', programmatic: 'info' }[level] || 'info'
}
function brainLabel(level) {
  return { full: 'full 全组件', light: 'light 精简', programmatic: 'programmatic 纯程序化' }[level] || level || '-'
}
function modelLabel(tier) {
  return { premium: 'premium', standard: 'standard', auto: 'auto 随档' }[tier] || tier || '-'
}
function freqLabel(f) {
  return { realtime: 'realtime 实时', per_day: 'per_day 每日', per_interval: 'per_interval 低频' }[f] || f || '-'
}
function fmtTokens(row) {
  const t = (row.tokenIn30d || 0) + (row.tokenOut30d || 0)
  return t.toLocaleString()
}
function fmtTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}
function fmtInterval(ms) {
  if (ms === undefined || ms === null) return '-'
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s}s`
  return `${Math.round(s / 60)} 分钟`
}

onMounted(load)
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}
.head-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin: 14px 0;
}
.stat-card {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
  transition: all 0.3s var(--dsw-ease);
}
.stat-card:hover {
  box-shadow: var(--shadow-lv3);
  border-color: var(--accent-border);
}
.stat-card :deep(.el-card__body) {
  padding: 14px 16px;
}
.stat-label {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 6px;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 6px;
  color: var(--text-primary);
}
.stat-sub {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.block-card {
  margin-bottom: 14px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
}
.card-title {
  font-weight: 600;
  color: var(--text-primary);
}
.comp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
}
.comp-card {
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  background: var(--bg-layer-0);
  padding: 12px 14px;
  transition: all 0.25s var(--dsw-ease);
}
.comp-card:hover {
  border-color: var(--accent-border);
  box-shadow: var(--shadow-lv2);
}
.comp-core {
  border-left: 3px solid var(--accent);
}
.comp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.comp-name {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 14px;
}
.comp-stats {
  display: flex;
  gap: 18px;
  margin-bottom: 8px;
}
.comp-stat {
  display: flex;
  flex-direction: column;
}
.comp-stat .num {
  font-size: 18px;
  font-weight: 700;
  color: var(--accent-text);
}
.comp-stat .lbl {
  font-size: 11px;
  color: var(--text-tertiary);
}
.comp-scenes {
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 6px;
  word-break: break-all;
}
.comp-desc {
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.6;
}
.dim {
  color: var(--text-tertiary);
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono, Consolas, 'Courier New', monospace);
}
</style>
