<template>
  <div class="decision-coord">
    <div class="head-row">
      <h2 class="page-title">决策与协调 O2（前瞻推演 / state delta / 冲突检测 / ToM）</h2>
      <div class="toolbar">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
        <span class="hint">O2：P1-1 前瞻推演（同调用插段+宏观注入）/ P1-2 决策 delta / P1-3 冲突协商+ToM</span>
      </div>
    </div>

    <template v-if="projectId">
      <!-- ==================== 一、前瞻推演 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            前瞻推演（/decision/foresight，P1-1 候选行动 + 宏观注入）
            <el-input v-model="foresightRef" size="small" placeholder="角色 ref（可空）" clearable class="filter" style="width: 160px" @change="loadForesight" />
            <el-input-number v-model="foresightLimit" :min="1" :max="200" size="small" controls-position="right" class="filter" style="width: 100px" placeholder="limit" />
          </div>
        </template>
        <div class="preview-box">
          <span class="trace-jump-title">触发预览（不开会话不落库）：</span>
          <el-input v-model="previewText" size="small" placeholder="刺激文本（如：有人在争集市摊位）" clearable class="filter" style="width: 300px" />
          <el-input-number v-model="previewImportance" :min="0" :max="10" size="small" controls-position="right" class="filter" style="width: 90px" placeholder="重要度" />
          <el-switch v-model="previewUrgent" size="small" active-text="紧急" class="filter" />
          <el-button size="small" type="primary" @click="previewForesight">预览</el-button>
          <div v-if="foresightPreview" class="preview-result">
            <el-tag size="small" :type="foresightPreview.triggered ? 'success' : 'info'" effect="dark">
              {{ foresightPreview.triggered ? '触发' : '不触发' }}
            </el-tag>
            <span v-if="foresightPreview.reason" class="mono">{{ foresightPreview.reason }}</span>
            <pre v-if="foresightPreview.section" class="trace-json">{{ foresightPreview.section }}</pre>
          </div>
        </div>
        <el-table :data="foresightList" size="small" empty-text="暂无前瞻记录（STIMULUS 即时决策触发且判定命中后产生）" max-height="380" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail-wrap">
                <div class="trace-jump-title">刺激摘录</div>
                <pre class="trace-json">{{ row.situationExcerpt || '（无）' }}</pre>
                <div class="trace-jump-title">候选行动（candidates）</div>
                <pre class="trace-json">{{ pretty(row.candidatesJson) }}</pre>
                <div class="trace-jump-title">宏观快照摘要</div>
                <pre class="trace-json">{{ row.macroSummary || '（未注入）' }}</pre>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="npcRef" label="角色" width="120" show-overflow-tooltip />
          <el-table-column label="游戏时刻" width="110">
            <template #default="{ row }"><span class="mono">{{ gameSecondText(row.gameSecond) }}</span></template>
          </el-table-column>
          <el-table-column prop="triggerReason" label="触发原因" width="170" show-overflow-tooltip />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'warning'" effect="plain">{{ row.success ? '有候选' : '无候选' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="finalAction" label="最终行动" min-width="240" show-overflow-tooltip />
          <el-table-column label="时间" width="150">
            <template #default="{ row }"><span class="dim mono">{{ fmtTime(row.createdAt) }}</span></template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 二、state delta ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            决策 state delta（/decision/state-deltas，P1-2 显式状态变更 + 恢复校验）
            <el-input v-model="deltaRef" size="small" placeholder="角色 ref（可空）" clearable class="filter" style="width: 160px" @change="loadDeltas" />
          </div>
        </template>
        <el-table :data="deltaList" size="small" empty-text="暂无 delta（决策行动执行后产生，幂等键=plan id）" max-height="380" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail-wrap">
                <div class="trace-jump-title">changes_json</div>
                <pre class="trace-json">{{ pretty(row.changesJson) }}</pre>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="npcRef" label="角色" width="120" show-overflow-tooltip />
          <el-table-column label="decision_id" width="100" align="center"><template #default="{ row }"><span class="mono">{{ row.decisionId }}</span></template></el-table-column>
          <el-table-column label="游戏时刻" width="110">
            <template #default="{ row }"><span class="mono">{{ gameSecondText(row.gameSecond) }}</span></template>
          </el-table-column>
          <el-table-column label="变更字段" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="f in (row.changedFields || [])" :key="f" size="small" effect="plain" class="layer-tag">{{ f }}</el-tag>
              <span v-if="!(row.changedFields || []).length" class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="行动" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ actionOf(row.changesJson) }}</template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }"><span class="dim mono">{{ fmtTime(row.createdAt) }}</span></template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 三、冲突检测 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            冲突检测与协商（/decision/conflicts，P1-3 三路检测 + 优先级 + 协商/兜底）
            <el-input v-model="conflictRef" size="small" placeholder="角色 ref（可空）" clearable class="filter" style="width: 160px" @change="loadConflicts" />
          </div>
        </template>
        <el-table :data="conflictList" size="small" empty-text="暂无冲突记录（目标被占用且优先级判定后产生）" max-height="380" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail-wrap">
                <div class="trace-jump-title">协商产出（negotiate_json）</div>
                <pre class="trace-json">{{ row.negotiateJson ? pretty(row.negotiateJson) : '（未协商/确定性兜底）' }}</pre>
                <div class="trace-jump-title">行动变更</div>
                <div class="change-before">原：{{ row.actionBefore }}</div>
                <div class="change-after">后：{{ row.actionAfter }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="npcName" label="我方" width="90" show-overflow-tooltip />
          <el-table-column prop="opponentName" label="对手" width="90" show-overflow-tooltip />
          <el-table-column label="冲突类型" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="conflictTag(row.conflictType)">{{ conflictLabel(row.conflictType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resourceName" label="争抢目标" min-width="140" show-overflow-tooltip />
          <el-table-column label="优先级" width="110" align="center">
            <template #default="{ row }"><span class="mono">{{ row.myPriority }} vs {{ row.opponentPriority }}</span></template>
          </el-table-column>
          <el-table-column label="处置" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="resolutionTag(row.resolution)" effect="dark">{{ resolutionLabel(row.resolution) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="游戏时刻" width="110">
            <template #default="{ row }"><span class="mono">{{ gameSecondText(row.gameSecond) }}</span></template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 四、资源占用 + ToM ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            资源占用登记（/decision/occupancy，P1-3 到期自动失效/换目标自动释放）
            <span class="hint" style="margin-left:14px">ToM 档位预览：</span>
            <el-input v-model="tomJson" size="small" placeholder="角色卡 persona_json（可空）" clearable class="filter" style="width: 300px" @change="loadTom" />
            <el-tag v-if="tomResult.tier" size="small" :type="tomTag(tomResult.tier)" effect="dark" class="filter">{{ tomResult.label }}</el-tag>
          </div>
        </template>
        <el-table :data="occupancyList" size="small" empty-text="暂无资源占用登记（行动目标命中地点性资源后登记）" max-height="300" row-key="id">
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="resourceName" label="资源" min-width="140" show-overflow-tooltip />
          <el-table-column prop="npcName" label="占用者" width="110" show-overflow-tooltip />
          <el-table-column label="占用起" width="110">
            <template #default="{ row }"><span class="mono">{{ gameSecondText(row.sinceGameSecond) }}</span></template>
          </el-table-column>
          <el-table-column label="占用至" width="110">
            <template #default="{ row }"><span class="mono">{{ gameSecondText(row.untilGameSecond) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get } from '../api'

/**
 * 决策与协调 O2 调试页（2026-09-16，P1-1/P1-2/P1-3 一屏总览）。
 * 职责：前瞻推演记录+触发预览（含宏观注入段落）、决策 state delta 回看、
 * 冲突检测与协商记录（三路检测/优先级/处置）、资源占用登记、ToM 档位预览。
 * 数据源：/decision/foresight* / /decision/state-deltas / /decision/conflicts / /decision/occupancy / /decision/tom。
 */

const projects = ref([])
const projectId = ref(null)

const foresightList = ref([])
const foresightRef = ref('')
const foresightLimit = ref(50)
const previewText = ref('')
const previewImportance = ref(null)
const previewUrgent = ref(false)
const foresightPreview = ref(null)

const deltaList = ref([])
const deltaRef = ref('')

const conflictList = ref([])
const conflictRef = ref('')

const occupancyList = ref([])
const tomJson = ref('')
const tomResult = ref({})

onMounted(async () => {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
    if (projects.value.length === 1) {
      projectId.value = projects.value[0].id
      reloadAll()
    }
  } catch { projects.value = [] }
})

function onProjectChange() {
  foresightList.value = []
  deltaList.value = []
  conflictList.value = []
  occupancyList.value = []
  foresightPreview.value = null
  if (projectId.value) reloadAll()
}

async function reloadAll() {
  await Promise.all([loadForesight(), loadDeltas(), loadConflicts(), loadOccupancy()])
}

/** 解包 R 包装（{code,data,message}）或直出数组（调试端点惯例双兼容）。 */
function unwrap(v) {
  return Array.isArray(v) ? v : (v?.data || [])
}

async function loadForesight() {
  if (!projectId.value) return
  try {
    const params = { limit: foresightLimit.value || 50 }
    if (foresightRef.value?.trim()) params.npcRef = foresightRef.value.trim()
    foresightList.value = unwrap(await get(`/projects/${projectId.value}/decision/foresight`, params))
  } catch { foresightList.value = [] }
}

async function previewForesight() {
  if (!projectId.value) return
  try {
    const params = {}
    if (previewText.value?.trim()) params.stimulusText = previewText.value.trim()
    if (previewImportance.value != null) params.importance = previewImportance.value
    params.urgent = !!previewUrgent.value
    foresightPreview.value = unwrap(await get(`/projects/${projectId.value}/decision/foresight/preview`, params)) || null
  } catch { foresightPreview.value = null }
}

async function loadDeltas() {
  if (!projectId.value) return
  try {
    const params = {}
    if (deltaRef.value?.trim()) params.npcRef = deltaRef.value.trim()
    deltaList.value = unwrap(await get(`/projects/${projectId.value}/decision/state-deltas`, params))
  } catch { deltaList.value = [] }
}

async function loadConflicts() {
  if (!projectId.value) return
  try {
    const params = {}
    if (conflictRef.value?.trim()) params.npcRef = conflictRef.value.trim()
    conflictList.value = unwrap(await get(`/projects/${projectId.value}/decision/conflicts`, params))
  } catch { conflictList.value = [] }
}

async function loadOccupancy() {
  if (!projectId.value) return
  try {
    occupancyList.value = unwrap(await get(`/projects/${projectId.value}/decision/occupancy`))
  } catch { occupancyList.value = [] }
}

async function loadTom() {
  if (!projectId.value) return
  try {
    const params = {}
    if (tomJson.value?.trim()) params.personaJson = tomJson.value.trim()
    tomResult.value = unwrap(await get(`/projects/${projectId.value}/decision/tom`, params)) || {}
  } catch { tomResult.value = {} }
}

function gameSecondText(gs) {
  if (gs == null) return '—'
  const day = Math.floor(gs / 86400)
  const hour = Math.floor((gs % 86400) / 3600)
  const min = Math.floor((gs % 3600) / 60)
  return `D${day} ${String(hour).padStart(2, '0')}:${String(min).padStart(2, '0')}`
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
function pretty(s) {
  if (!s) return '（无）'
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}
function actionOf(changesJson) {
  try {
    const c = JSON.parse(changesJson || '{}')
    return c.action || '—'
  } catch { return '—' }
}
function conflictLabel(t) {
  return { location_occupied: '同地点占用', object_busy: '目标正忙', resource_occupied: '资源占用' }[t] || t
}
function conflictTag(t) { return t === 'resource_occupied' ? 'danger' : t === 'object_busy' ? 'warning' : 'primary' }
function resolutionLabel(r) {
  return { takeover: '强占', negotiated: '协商', fallback_wait: '等待兜底', fallback_redirect: '改道兜底', proceed: '照旧' }[r] || r
}
function resolutionTag(r) {
  return r === 'takeover' ? 'danger' : r === 'negotiated' ? 'success' : 'warning'
}
function tomTag(t) {
  return t === 'over_interpret' ? 'danger' : t === 'literal' ? 'info' : 'primary'
}
</script>

<style scoped>
.head-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.proj-select { width: 200px; }
.hint { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
.card { margin-bottom: 14px; }
.filter { margin-left: 10px; }
.empty { color: var(--text-tertiary, #8a8a8a); font-size: 12px; padding: 8px 0; }
.detail-wrap { padding: 4px 8px 10px; }
.trace-jump-title { font-size: 12px; color: var(--text-secondary, #666); margin: 8px 0 4px; font-weight: 600; }
.trace-json { background: var(--bg-layer-1, #f5f5f5); border: 1px solid var(--border-l2, #e8e8e8); border-radius: 6px; padding: 8px 10px; font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0 0 6px; }
.preview-box { padding: 10px; background: var(--bg-layer-1, #fafafa); border: 1px dashed var(--border-l2, #e0e0e0); border-radius: 6px; margin-bottom: 10px; }
.preview-result { margin-top: 8px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; }
.change-before { font-size: 12px; color: var(--text-tertiary, #8a8a8a); margin: 4px 0; }
.change-after { font-size: 12px; color: var(--text-primary, #333); }
.layer-tag { margin-right: 6px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.dim { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
</style>
