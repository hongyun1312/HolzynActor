<template>
  <div class="drift-metrics">
    <div class="head-row">
      <h2 class="page-title">漂移度量（R4）</h2>
      <div class="head-controls">
        <el-select v-model="projectId" clearable placeholder="全部项目" size="small" style="width: 180px" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="characterId" clearable placeholder="全部角色" size="small" style="width: 150px" @change="load">
          <el-option v-for="c in characters" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="days" size="small" style="width: 110px" @change="load">
          <el-option v-for="n in [7, 14, 30, 90]" :key="n" :label="`近 ${n} 天`" :value="n" />
        </el-select>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <span class="dim mono">截至 {{ fmtTime(data?.asOf) }}</span>
      </div>
    </div>

    <!-- 三张指标卡：目标一致率 / 矛盾率 / Token 成本 -->
    <div class="cards">
      <el-card shadow="never" class="stat-card">
        <div class="stat-label">目标一致率</div>
        <div class="stat-value">
          {{ pct(goals?.consistencyRate) }}
          <span class="stat-sub" v-if="goals?.done !== undefined">（已完成 {{ goals.done }}/{{ goals.total }}）</span>
        </div>
        <div class="stat-sub">
          进行中 {{ goals?.active ?? 0 }} · 已完成 {{ goals?.done ?? 0 }} · 已放弃 {{ goals?.abandoned ?? 0 }}
        </div>
        <div class="stat-hint">目标频繁被放弃/更换 = 叙事漂移信号（R4）</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">矛盾率（反思）</div>
        <div class="stat-value">
          {{ pct(reflection?.contradictionRate) }}
          <span class="stat-sub" v-if="reflection?.total !== undefined">（{{ reflection.contradictionCount }}/{{ reflection.total }} 条）</span>
        </div>
        <div class="stat-sub">反思洞察中命中矛盾类关键词（矛盾/冲突/不一致…）的条数占比</div>
        <div class="stat-hint">反思反复识别与自身事实/目标矛盾 = 一致性漂移信号（R4）</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">Token 成本（{{ days }} 天）</div>
        <div class="stat-value mono">{{ fmtNum(token?.totalToken) }}</div>
        <div class="stat-sub">
          {{ token?.calls ?? 0 }} 次调用 · in {{ fmtNum(token?.tokenIn) }} / out {{ fmtNum(token?.tokenOut) }}
        </div>
        <div class="stat-hint">总耗时 {{ fmtDur(token?.durationMs) }} · 数据源 actor_ai_call_log</div>
      </el-card>
    </div>

    <!-- M5 记忆治理健康度（§11.3：归档量 / 版本链 / 分层分布）+ 记忆治理动作 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-title">记忆治理健康度（M5）
          <span class="dim" style="font-weight:400; margin-left:8px">归档量 / 版本链 / 分层分布——记忆治理信号（设计 §11.3）</span>
        </div>
      </template>
      <div class="health-grid">
        <div class="health-item">
          <div class="health-label">归档量</div>
          <div class="health-value mono">{{ mh?.archive?.total ?? 0 }}</div>
          <div class="health-sub">
            按原因：{{ reasonSummary(mh?.archive?.byReason) }}<br>
            已还原 {{ mh?.archive?.restored ?? 0 }}
          </div>
        </div>
        <div class="health-item">
          <div class="health-label">版本链数</div>
          <div class="health-value mono">{{ mh?.versionChain?.totalVersionChainCount ?? 0 }}</div>
          <div class="health-sub">L2 {{ mh?.versionChain?.l2VersionChainCount ?? 0 }} · L3 {{ mh?.versionChain?.l3CoreBlockChainCount ?? 0 }}</div>
        </div>
        <div class="health-item">
          <div class="health-label">分层分布 L2/L1/L3</div>
          <div class="health-value mono">{{ mh?.tier?.l2?.total ?? 0 }} / {{ mh?.tier?.l1?.activeCount ?? 0 }} / {{ mh?.tier?.l3CoreBlocks ?? 0 }}</div>
          <div class="health-sub">L2 semantic {{ mh?.tier?.l2?.semantic ?? 0 }} · episodic {{ mh?.tier?.l2?.episodic ?? 0 }} · reflection {{ mh?.tier?.l2?.reflection ?? 0 }} · plan {{ mh?.tier?.l2?.plan ?? 0 }} · 摘要 {{ mh?.tier?.l2?.summary ?? 0 }}</div>
        </div>
        <div class="health-item">
          <div class="health-label">记忆治理动作（M5 巩固编排）</div>
          <div class="health-actions">
            <el-input v-model="consolidateSession" size="small" placeholder="会话 key（可空）" style="width: 130px" clearable />
            <el-button size="small" :loading="consolidating" @click="doConsolidate(false)">会话巩固</el-button>
            <el-button size="small" type="warning" :loading="consolidating" @click="doConsolidate(true)">统一维护</el-button>
          </div>
          <div class="health-sub">会话巩固 = L1→L2 双缓冲提升；统一维护 = L1 清理/巩固扫描 + 归档超龄/保留/二次清理（scene=memory_manage trace）</div>
        </div>
      </div>
    </el-card>

    <!-- 趋势图：按日 token 用量 + 调用次数 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-title">Token 成本趋势（按日）
          <span class="dim" style="font-weight:400; margin-left:8px">柱状 = 输入/输出 token，折线 = 调用次数</span>
        </div>
      </template>
      <EChart :option="trendOption" height="300px" />
    </el-card>

    <!-- 双栏：目标状态分布 + 按角色 Token 排行 -->
    <div class="two-col">
      <el-card shadow="never" class="block-card">
        <template #header><div class="card-title">目标状态分布</div></template>
        <EChart :option="goalOption" height="240px" />
        <div class="dim" style="font-size:12px">数据源 actor_character_goal（active 进行中 / done 已完成 / abandoned 已放弃）</div>
      </el-card>

      <el-card shadow="never" class="block-card">
        <template #header><div class="card-title">按角色 Token 排行（前 10）</div></template>
        <el-table :data="(token?.byCharacter || []).slice(0, 10)" size="small" stripe>
          <el-table-column label="角色" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.characterName }}</template>
          </el-table-column>
          <el-table-column label="调用" width="70" align="right">
            <template #default="{ row }"><span class="mono">{{ row.calls }}</span></template>
          </el-table-column>
          <el-table-column label="Token 总量" min-width="110" align="right">
            <template #default="{ row }"><span class="mono">{{ fmtNum((row.tokenIn || 0) + (row.tokenOut || 0)) }}</span></template>
          </el-table-column>
          <el-table-column label="输入" width="90" align="right">
            <template #default="{ row }"><span class="mono">{{ fmtNum(row.tokenIn) }}</span></template>
          </el-table-column>
          <el-table-column label="输出" width="90" align="right">
            <template #default="{ row }"><span class="mono">{{ fmtNum(row.tokenOut) }}</span></template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api'
import EChart from '../components/EChart.vue'

const data = ref(null)
const projects = ref([])
const characters = ref([])
const projectId = ref(null)
const characterId = ref(null)
const days = ref(30)
const loading = ref(false)
const consolidating = ref(false)
const consolidateSession = ref('')

const goals = computed(() => data.value?.goals || null)
const reflection = computed(() => data.value?.reflection || null)
const token = computed(() => data.value?.token || null)
const mh = computed(() => data.value?.memoryHealth || null)

async function loadProjects() {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
  } catch (_) { /* 忽略 */ }
}

async function onProjectChange() {
  characterId.value = null
  characters.value = []
  if (projectId.value) {
    try {
      const r = await get(`/projects/${projectId.value}/characters`)
      characters.value = r?.data || []
    } catch (_) { /* 忽略 */ }
  }
  load()
}

async function load() {
  loading.value = true
  try {
    data.value = await get('/debug/drift-metrics', {
      projectId: projectId.value || undefined,
      characterId: characterId.value || undefined,
      days: days.value
    })
  } catch (e) {
    console.error('漂移度量加载失败', e)
  } finally {
    loading.value = false
  }
}

/** M5 记忆治理动作：会话巩固（sessionKey）或统一维护（maintenance） */
async function doConsolidate(maintenance) {
  consolidating.value = true
  try {
    const body = {}
    if (!maintenance && consolidateSession.value) body.sessionKey = consolidateSession.value
    if (maintenance) body.maintenance = true
    const r = await post('/memory/consolidate', body)
    if (maintenance) {
      ElMessage.success(`统一维护完成：${r.maintenanceSteps} 步`)
    } else {
      ElMessage.success(`会话巩固完成：提升 ${r.consolidated} 条`)
    }
    if (maintenance) load()
  } catch (e) {
    ElMessage.error(e.message || '巩固触发失败')
  } finally {
    consolidating.value = false
  }
}

/** 归档原因分组摘要（age/superseded/downgraded/evicted/manual） */
function reasonSummary(byReason) {
  if (!byReason || Object.keys(byReason).length === 0) return '-'
  return Object.entries(byReason).map(([k, v]) => `${k}:${v}`).join(' · ')
}

// ===== ECharts 选项 =====

/** 按日趋势：token in/out 堆叠柱 + 调用次数折线 */
const trendOption = computed(() => {
  const byDay = token.value?.byDay || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['输入 token', '输出 token', '调用次数'], textStyle: { color: 'var(--text-regular)' } },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: byDay.map(d => d.date), axisLabel: { color: 'var(--text-secondary)' } },
    yAxis: [
      { type: 'value', name: 'Token', nameTextStyle: { color: 'var(--text-tertiary)' }, axisLabel: { color: 'var(--text-secondary)' } },
      { type: 'value', name: '次数', nameTextStyle: { color: 'var(--text-tertiary)' }, axisLabel: { color: 'var(--text-secondary)' } }
    ],
    series: [
      { name: '输入 token', type: 'bar', stack: 't', data: byDay.map(d => d.tokenIn), itemStyle: { color: '#67c23a' } },
      { name: '输出 token', type: 'bar', stack: 't', data: byDay.map(d => d.tokenOut), itemStyle: { color: '#e6a23c' } },
      { name: '调用次数', type: 'line', yAxisIndex: 1, data: byDay.map(d => d.calls), itemStyle: { color: '#409eff' } }
    ]
  }
})

/** 目标状态分布：active/done/abandoned 柱状 */
const goalOption = computed(() => {
  const g = goals.value || { active: 0, done: 0, abandoned: 0 }
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 30, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: ['进行中 active', '已完成 done', '已放弃 abandoned'],
      axisLabel: { color: 'var(--text-secondary)' }
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: 'var(--text-secondary)' } },
    series: [{
      type: 'bar',
      barWidth: 44,
      data: [
        { value: g.active, itemStyle: { color: '#409eff' } },
        { value: g.done, itemStyle: { color: '#67c23a' } },
        { value: g.abandoned, itemStyle: { color: '#f56c6c' } }
      ],
      label: { show: true, position: 'top', color: 'var(--text-regular)' }
    }]
  }
})

// ===== 工具 =====

function pct(v) {
  if (v === undefined || v === null) return '—'
  return (v * 100).toFixed(1) + '%'
}
function fmtNum(v) {
  return (v || 0).toLocaleString()
}
function fmtDur(ms) {
  if (ms === undefined || ms === null) return '—'
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(1)}s`
  return `${Math.round(s / 60)} 分钟`
}
function fmtTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}

onMounted(() => {
  loadProjects()
  load()
})
</script>

<style scoped>
.page-title {
  margin: 0;
  font-size: 18px;
  color: var(--text-primary);
}
.head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}
.head-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.stat-card {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
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
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.stat-sub {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.stat-hint {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 6px;
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
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
@media (max-width: 1100px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
/* M5 记忆治理健康度网格 */
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.health-item {
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--bg-base);
}
.health-label {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 6px;
}
.health-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.health-sub {
  font-size: 11px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.health-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.dim {
  color: var(--text-tertiary);
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono, Consolas, 'Courier New', monospace);
}
</style>
