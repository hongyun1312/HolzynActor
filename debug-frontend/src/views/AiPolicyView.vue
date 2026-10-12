<template>
  <div class="ai-policy">
    <div class="head-row">
      <h2 class="page-title">护栏与路由 O3（统一护栏策略 P2-4 / 场景-模型路由 P2-5）</h2>
      <div class="toolbar">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目（仅路由档位验证用）" class="proj-select" @change="reloadAll">
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" @click="reloadAll">刷新</el-button>
        <span class="hint">P2-4：空输出/复读机检测在网关统一拦截=视为失败+告警+程序化兜底；P2-5：按场景类别档位（决策 0.3/对话 0.8/记忆 0.2/世界 0.7/人群 0.6）补温度与长度，运行全走本地 Qwen3.6-35B</span>
      </div>
    </div>

    <!-- ==================== 一、场景-模型路由生效表 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">
          场景-模型路由生效表（/api/debug/scene-routing/effective，来源 runtime&gt;scene&gt;tier&gt;default）
          <el-tag v-if="routingStatus.enabled != null" size="small" :type="routingStatus.enabled ? 'success' : 'info'" effect="dark" class="filter">
            {{ routingStatus.enabled ? '路由开启' : '路由关闭' }}
          </el-tag>
          <div class="override-form">
            <el-input v-model="routeForm.scene" size="small" placeholder="场景（如 dialog）" class="filter" style="width: 150px" />
            <el-input-number v-model="routeForm.temperature" :min="0" :max="2" :step="0.1" size="small" controls-position="right" class="filter" style="width: 110px" placeholder="温度（空=沿用）" />
            <el-input-number v-model="routeForm.maxTokens" :min="1" :max="16384" :step="128" size="small" controls-position="right" class="filter" style="width: 120px" placeholder="长度（空=沿用）" />
            <el-input-number v-model="routeForm.providerId" :min="1" size="small" controls-position="right" class="filter" style="width: 110px" placeholder="供应商 id（空=默认）" />
            <el-button size="small" type="warning" :disabled="!routeForm.scene" @click="putRouteOverride">运行时覆盖</el-button>
            <el-button size="small" :disabled="!routeForm.scene" @click="removeRouteOverride">清除覆盖</el-button>
            <span class="hint" style="margin-left:8px">运行时覆盖即时生效，重启还原 yml</span>
          </div>
        </div>
      </template>
      <el-table :data="routeRows" size="small" empty-text="加载中…" max-height="460" row-key="scene">
        <el-table-column prop="scene" label="场景" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'default-row': row.scene === '(default)' }">{{ row.scene }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="类别" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.category !== '-'" size="small" effect="plain">{{ row.category }}</el-tag>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="温度" width="90" align="center">
          <template #default="{ row }"><span class="mono">{{ row.temperature ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="输出长度" width="100" align="center">
          <template #default="{ row }"><span class="mono">{{ row.maxTokens ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="供应商" width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.providerId" class="mono">#{{ row.providerId }}</span>
            <span v-else class="dim">默认</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceTag(row.source)" effect="plain">{{ sourceLabel(row.source) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ==================== 二、护栏状态 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">
          护栏状态（/api/debug/guard-rails/status，网关统一执行：空输出/复读机拦截 + 失败统计 + 告警）
          <el-tag v-if="guardStatus.enabled != null" size="small" :type="guardStatus.enabled ? 'success' : 'info'" effect="dark" class="filter">
            {{ guardStatus.enabled ? '护栏开启' : '护栏关闭' }}
          </el-tag>
          <div class="override-form">
            <el-input v-model="guardForm.scene" size="small" placeholder="场景（如 action）" class="filter" style="width: 150px" />
            <el-input-number v-model="guardForm.maxRetries" :min="0" :max="20" size="small" controls-position="right" class="filter" style="width: 100px" placeholder="重试上限" />
            <el-input-number v-model="guardForm.minOutputChars" :min="0" :max="100" size="small" controls-position="right" class="filter" style="width: 100px" placeholder="最短输出" />
            <el-input-number v-model="guardForm.duplicateWindow" :min="1" :max="20" size="small" controls-position="right" class="filter" style="width: 100px" placeholder="重复窗口" />
            <el-button size="small" type="warning" :disabled="!guardForm.scene" @click="putGuardOverride">护栏覆盖</el-button>
            <el-button size="small" :disabled="!guardForm.scene" @click="removeGuardOverride">清除覆盖</el-button>
          </div>
        </div>
      </template>
      <div class="guard-defaults">
        <span class="trace-jump-title">生效默认参数：</span>
        <el-tag v-if="guardStatus.defaults" size="small" effect="plain" class="layer-tag">重试上限 {{ guardStatus.defaults.maxRetries }}</el-tag>
        <el-tag v-if="guardStatus.defaults" size="small" effect="plain" class="layer-tag">最短输出 {{ guardStatus.defaults.minOutputChars }}</el-tag>
        <el-tag v-if="guardStatus.defaults" size="small" effect="plain" class="layer-tag">重复窗口 {{ guardStatus.defaults.duplicateWindow }}（Dice≥{{ guardStatus.defaults.duplicateDiceThreshold }}）</el-tag>
        <el-tag v-if="guardStatus.defaults" size="small" effect="plain" class="layer-tag">连续失败上限 {{ guardStatus.defaults.maxConsecutiveFailures }}</el-tag>
      </div>
      <el-table :data="guardStatsRows" size="small" empty-text="暂无统计（网关调用产生后上报）" max-height="300" row-key="scene">
        <el-table-column prop="scene" label="场景" width="200" show-overflow-tooltip />
        <el-table-column prop="failures" label="失败" width="80" align="center" sortable />
        <el-table-column prop="timeouts" label="超时" width="80" align="center" sortable />
        <el-table-column prop="blankOutputs" label="空输出" width="90" align="center" sortable />
        <el-table-column prop="duplicates" label="复读命中" width="90" align="center" sortable />
        <el-table-column prop="retries" label="重试" width="80" align="center" sortable />
        <el-table-column prop="degraded" label="降级" width="80" align="center" sortable />
      </el-table>
      <div class="trace-jump-title" style="margin-top:12px">最近护栏告警（环形 200 条，新→旧）：</div>
      <el-table :data="guardAlerts" size="small" empty-text="暂无告警" max-height="260" row-key="atMs">
        <el-table-column label="时间" width="160">
          <template #default="{ row }"><span class="dim mono">{{ fmtTime(row.atMs) }}</span></template>
        </el-table-column>
        <el-table-column prop="scene" label="场景" width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="alertTag(row.type)" effect="plain">{{ alertLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="300" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { get, put, del } from '../api'

/**
 * 护栏与路由 O3 调试页（2026-09-21，P2-4 统一护栏 + P2-5 场景-模型路由一屏总览）。
 * 职责：① 路由生效表（场景/类别/温度/长度/供应商 + 来源标记）+ 运行时覆盖热更新；
 * ② 护栏状态（开关/生效默认参数/每场景统计/最近告警）+ 运行时覆盖热更新。
 * 数据源：/api/debug/scene-routing/effective|overrides + /api/debug/guard-rails/status|overrides。
 */

const projects = ref([])
const projectId = ref(null)

const routingStatus = ref({})
const routeRows = ref([])
const routeOverrides = ref({})
const routeForm = ref({ scene: '', temperature: null, maxTokens: null, providerId: null })

const guardStatus = ref({})
const guardAlerts = ref([])
const guardForm = ref({ scene: '', maxRetries: null, minOutputChars: null, duplicateWindow: null })

const guardStatsRows = computed(() => {
  const s = guardStatus.value.stats || {}
  return Object.keys(s).map((scene) => ({ scene, ...s[scene] })).sort((a, b) => b.failures - a.failures)
})

onMounted(async () => {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
  } catch { projects.value = [] }
  await Promise.all([loadRouting(), loadGuard()])
})

function reloadAll() {
  return Promise.all([loadRouting(), loadGuard()])
}

async function loadRouting() {
  try {
    const r = await get('/debug/scene-routing/effective')
    routingStatus.value = { enabled: r.enabled }
    routeRows.value = r.rows || []
    routeOverrides.value = await get('/debug/scene-routing/overrides')
  } catch { routingStatus.value = {}; routeRows.value = [] }
}

async function loadGuard() {
  try {
    guardStatus.value = await get('/debug/guard-rails/status')
    guardAlerts.value = guardStatus.value.recentAlerts || []
  } catch { guardStatus.value = {}; guardAlerts.value = [] }
}

async function putRouteOverride() {
  if (!routeForm.value.scene) return
  const body = { scene: routeForm.value.scene }
  if (routeForm.value.temperature != null) body.temperature = routeForm.value.temperature
  if (routeForm.value.maxTokens != null) body.maxTokens = routeForm.value.maxTokens
  if (routeForm.value.providerId != null) body.providerId = routeForm.value.providerId
  await put('/debug/scene-routing/overrides', body)
  await loadRouting()
}

async function removeRouteOverride() {
  if (!routeForm.value.scene) return
  await del(`/debug/scene-routing/overrides/${routeForm.value.scene}`)
  await loadRouting()
}

async function putGuardOverride() {
  if (!guardForm.value.scene) return
  const body = { scene: guardForm.value.scene }
  if (guardForm.value.maxRetries != null) body.maxRetries = guardForm.value.maxRetries
  if (guardForm.value.minOutputChars != null) body.minOutputChars = guardForm.value.minOutputChars
  if (guardForm.value.duplicateWindow != null) body.duplicateWindow = guardForm.value.duplicateWindow
  await put('/debug/guard-rails/overrides', body)
  await loadGuard()
}

async function removeGuardOverride() {
  if (!guardForm.value.scene) return
  await del(`/debug/guard-rails/overrides/${guardForm.value.scene}`)
  await loadGuard()
}

function sourceLabel(s) {
  return { runtime: '运行时覆盖', scene: '场景覆盖', tier: '类别档位', default: '全局默认' }[s] || s
}
function sourceTag(s) {
  return s === 'runtime' ? 'warning' : s === 'scene' ? 'danger' : s === 'tier' ? 'primary' : 'info'
}
function alertLabel(t) {
  return { duplicate: '复读命中', duplicate_guard: '复读拦截', blank_output: '空输出', timeout: '超时' }[t] || t
}
function alertTag(t) {
  return t.includes('duplicate') ? 'danger' : t === 'blank_output' ? 'warning' : 'info'
}
function fmtTime(ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<style scoped>
.head-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.proj-select { width: 220px; }
.hint { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
.card { margin-bottom: 14px; }
.filter { margin-left: 10px; }
.card-title { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.override-form { display: flex; align-items: center; flex-wrap: wrap; margin-top: 6px; width: 100%; }
.guard-defaults { padding: 8px 0 12px; font-size: 12px; }
.trace-jump-title { font-size: 12px; color: var(--text-secondary, #666); margin: 8px 0 4px; font-weight: 600; }
.layer-tag { margin-right: 6px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.dim { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
.default-row { font-weight: 700; }
</style>
