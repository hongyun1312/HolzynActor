<template>
  <div class="mem-o1">
    <div class="head-row">
      <h2 class="page-title">记忆内核 O1（模糊/谱系/路由）</h2>
      <div class="toolbar">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
        <span class="hint">O1：L1 艾宾浩斯模糊 / 睡眠清洗 / L1→L2→L3 蒸馏谱系 / 档位化注入路由</span>
      </div>
    </div>

    <template v-if="projectId">
      <!-- ==================== 一、L1 模糊状态 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            L1 模糊状态（/memories/fuzz，O1a 艾宾浩斯 + 睡眠清洗）
            <el-select v-model="fuzzStatus" size="small" class="filter" style="width: 120px" @change="loadFuzz">
              <el-option label="全部" value="" />
              <el-option label="active" value="active" />
              <el-option label="distilled" value="distilled" />
              <el-option label="archived" value="archived" />
            </el-select>
            <el-input v-model="fuzzRef" size="small" placeholder="角色 ref（可空）" clearable class="filter" style="width: 180px" @change="loadFuzz" />
          </div>
        </template>
        <el-table :data="fuzzList" size="small" empty-text="暂无模糊状态（经历类记忆写入 L1 short_term 后出现）" max-height="420" row-key="id" @expand-change="onFuzzExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail-wrap" v-loading="fuzzLoadingId === row.id">
                <div class="trace-jump-title">原始内容（永不丢失）</div>
                <pre class="trace-json">{{ row.originalContent }}</pre>
                <div class="trace-jump-title">当前模糊版本</div>
                <pre class="trace-json">{{ row.currentContent }}</pre>
                <div class="trace-jump-title">模糊历史（{{ (row._history || []).length }} 条）</div>
                <el-table :data="row._history || []" size="small" empty-text="暂无模糊历史">
                  <el-table-column label="游戏秒" width="110">
                    <template #default="{ row: h }"><span class="mono">{{ h.gameSecond }}</span></template>
                  </el-table-column>
                  <el-table-column label="from→to" width="120">
                    <template #default="{ row: h }"><span class="mono">{{ h.fromLevel }} → {{ h.toLevel }}</span></template>
                  </el-table-column>
                  <el-table-column label="通道" width="90">
                    <template #default="{ row: h }">
                      <el-tag size="small" :type="h.ai ? 'warning' : 'info'" effect="plain">{{ h.ai ? 'AI' : '规则' }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="prompt" label="来源" min-width="200" show-overflow-tooltip />
                </el-table>
                <div class="detail-actions">
                  <el-button size="small" type="primary" :loading="fuzzTriggerId === row.memoryWorkingId"
                             @click="triggerFuzz(row)">立即模糊一次</el-button>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }">{{ row.memoryWorkingId }}</template></el-table-column>
          <el-table-column prop="characterRef" label="角色" width="130" show-overflow-tooltip />
          <el-table-column label="模糊度" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="fuzzTag(row.fuzzLevel)" effect="light">{{ row.fuzzLevel.toFixed(2) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="重要度" width="80" align="center">
            <template #default="{ row }">{{ row.importanceScore.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="检索" width="70" align="center">
            <template #default="{ row }"><span class="mono">{{ row.retrievalCount }}</span></template>
          </el-table-column>
          <el-table-column label="模糊次数" width="80" align="center">
            <template #default="{ row }"><span class="mono">{{ row.fuzzCount }}</span></template>
          </el-table-column>
          <el-table-column label="噪声 σ" width="90" align="center">
            <template #default="{ row }"><span class="mono">{{ row.embeddingNoise.toFixed(3) }}</span></template>
          </el-table-column>
          <el-table-column label="下次触发" width="110">
            <template #default="{ row }">{{ gameSecondText(row.nextFuzzGameSecond) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="fuzzStatusTag(row.status)" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="蒸馏目标" width="90">
            <template #default="{ row }">
              <span v-if="row.distilledMemoryId" class="mono">L2 #{{ row.distilledMemoryId }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="当前内容" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.currentContent }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 二、记忆谱系 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            记忆谱系（/memories/lineage，O1b 反向链：L3 → L2 → L1 逐层溯源）
            <el-input-number v-model="lineageTargetId" :min="1" size="small" controls-position="right" class="filter" style="width: 140px" />
            <el-button size="small" type="primary" :disabled="!lineageTargetId" @click="loadLineage">展开</el-button>
            <span class="hint">输入 L2 记忆 id 或 L3 核心块 id（目标记忆）</span>
          </div>
        </template>
        <div v-if="lineage.length === 0" class="empty">暂无谱系（输入目标记忆 id 后展开）</div>
        <div v-else class="lineage-chain">
          <div v-for="(hop, i) in lineage" :key="i" class="lineage-hop">
            <div class="lineage-hop-head">
              <el-tag size="small" :type="tierTag(hop.sourceTier)" effect="dark">{{ hop.sourceTier }}</el-tag>
              <span class="mono">#{{ hop.sourceMemoryId }}</span>
              <span class="lineage-arrow">→</span>
              <el-tag size="small" :type="tierTag(hop.targetTier)" effect="dark">{{ hop.targetTier }}</el-tag>
              <span class="mono">#{{ hop.targetMemoryId }}</span>
              <span class="lineage-type">{{ hop.consolidateType }}</span>
              <span class="dim mono">游戏秒 {{ hop.gameSecond }} · 缩减率 {{ hop.reductionRatio != null ? hop.reductionRatio.toFixed(2) : '—' }}</span>
            </div>
            <div class="lineage-source" v-if="hop.sourceContent">「{{ hop.sourceContent }}」</div>
            <div class="lineage-source dim" v-else>（源内容已不可追溯）</div>
          </div>
        </div>
      </el-card>

      <!-- ==================== 三、注入路由 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            注入路由（/memories/inject-route，O1c tier 判定 + L1 候选排序）
            <el-select v-model="routeScenario" size="small" class="filter" style="width: 120px">
              <el-option v-for="s in scenarios" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-input v-model="routeQuery" size="small" placeholder="检索 query（可空）" clearable class="filter" style="width: 200px" />
            <el-input-number v-model="routeUrgency" :min="1" :max="5" size="small" controls-position="right" class="filter" style="width: 110px" placeholder="urgency" />
            <el-button size="small" type="primary" @click="loadRoute">预览</el-button>
          </div>
        </template>
        <template v-if="routePreview">
          <div class="route-sum">
            <span>档位：
              <el-tag size="small" :type="tierTag(routePreview.tier)" effect="dark">{{ routePreview.tier }}</el-tag>
            </span>
            <span>预算：<span class="mono">{{ routePreview.budgetTokens }}</span> token</span>
            <span>场景：{{ routePreview.scenario }}</span>
            <span>urgency：{{ routePreview.urgency ?? '—' }}</span>
          </div>
          <el-table :data="routePreview.l1 || []" size="small" empty-text="该角色当前无 L1 候选" max-height="260">
            <el-table-column label="workingId" width="100"><template #default="{ row }"><span class="mono">{{ row.memoryWorkingId }}</span></template></el-table-column>
            <el-table-column label="模糊度" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="fuzzTag(row.fuzzLevel)" effect="light">{{ row.fuzzLevel.toFixed(2) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="相关分" width="90" align="right">
              <template #default="{ row }"><span class="mono">{{ row.relevance.toFixed(3) }}</span></template>
            </el-table-column>
            <el-table-column label="权重" width="90" align="right">
              <template #default="{ row }"><span class="mono">{{ row.weight.toFixed(2) }}</span></template>
            </el-table-column>
            <el-table-column label="段" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.fuzzy ? 'warning' : 'success'" effect="plain">{{ row.fuzzy ? '模糊' : '清晰' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
          </el-table>
        </template>
        <div v-else class="empty">选择场景/query 后点击「预览」——展示档位判定与 L1 候选排序（清晰优先）</div>
      </el-card>

      <!-- ==================== 四、注入路由 trace ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            注入路由 trace（/memories/inject-route/trace，最近 200 条环形缓冲）
            <el-button size="small" @click="loadTraces">刷新</el-button>
          </div>
        </template>
        <el-table :data="routeTraces" size="small" empty-text="暂无路由 trace（对话/决策注入路由后产生）" max-height="360">
          <el-table-column label="seq" width="70" align="center"><template #default="{ row }"><span class="mono">{{ row.seq }}</span></template></el-table-column>
          <el-table-column prop="characterRef" label="角色" width="130" show-overflow-tooltip />
          <el-table-column prop="scenario" label="场景" width="100" />
          <el-table-column label="档位" width="80" align="center">
            <template #default="{ row }"><el-tag size="small" :type="tierTag(row.tier)" effect="dark">{{ row.tier }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="reason" label="判定理由" min-width="160" show-overflow-tooltip />
          <el-table-column label="各层" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="l in (row.layers || [])" :key="l.layer" size="small" effect="plain" class="layer-tag">
                {{ l.layer }} ×{{ l.count }} ≈{{ l.tokens }}tok
              </el-tag>
              <span v-if="!(row.layers || []).length" class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="游戏日" width="80" align="center">
            <template #default="{ row }">{{ row.gameDay ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get, post } from '../api'

/**
 * 记忆内核 O1 调试页（2026-09-16，O1a+b+c 一屏总览）。
 * 职责：L1 模糊状态（艾宾浩斯 + 睡眠清洗 + 手动触发）、记忆谱系（L1→L2→L3 反向链）、
 * 注入路由（tier 判定预览 + L1 候选排序 + 环形缓冲 trace 回放）。
 * 数据源：/memories/fuzz* / /memories/lineage / /memories/inject-route*。
 */

const projects = ref([])
const projectId = ref(null)

const fuzzList = ref([])
const fuzzStatus = ref('')
const fuzzRef = ref('')
const fuzzLoadingId = ref(null)
const fuzzTriggerId = ref(null)

const lineageTargetId = ref(null)
const lineage = ref([])

const scenarios = [
  { value: 'dialog', label: 'dialog' },
  { value: 'decision', label: 'decision' },
  { value: 'perception', label: 'perception' },
  { value: 'reflection', label: 'reflection' }
]
const routeScenario = ref('dialog')
const routeQuery = ref('')
const routeUrgency = ref(null)
const routePreview = ref(null)
const routeTraces = ref([])

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
  fuzzList.value = []
  lineage.value = []
  routePreview.value = null
  routeTraces.value = []
  if (projectId.value) reloadAll()
}

async function reloadAll() {
  await Promise.all([loadFuzz(), loadTraces()])
}

async function loadFuzz() {
  if (!projectId.value) return
  try {
    const params = {}
    if (fuzzStatus.value) params.status = fuzzStatus.value
    if (fuzzRef.value?.trim()) params.characterRef = fuzzRef.value.trim()
    fuzzList.value = await get(`/projects/${projectId.value}/memories/fuzz`, params)
  } catch { fuzzList.value = [] }
}

/** 展开行懒加载：模糊历史（fuzz_history JSON 展开）。 */
async function onFuzzExpand(row, expandedRows) {
  if (!expandedRows.length || row._loaded) return
  fuzzLoadingId.value = row.id
  try {
    const d = await get(`/projects/${projectId.value}/memories/fuzz/${row.memoryWorkingId}`)
    row._history = d.fuzzHistory || []
    row._loaded = true
  } catch { row._history = [] }
  finally { fuzzLoadingId.value = null }
}

/** 手动触发单条模糊（无视艾宾浩斯间隔立即执行一次）。 */
async function triggerFuzz(row) {
  fuzzTriggerId.value = row.memoryWorkingId
  try {
    await post(`/projects/${projectId.value}/memories/fuzz/trigger`, { memoryWorkingId: row.memoryWorkingId })
    await loadFuzz()
  } catch (e) { alert('触发失败：' + e.message) }
  finally { fuzzTriggerId.value = null }
}

async function loadLineage() {
  if (!projectId.value || !lineageTargetId.value) return
  try {
    lineage.value = await get(`/projects/${projectId.value}/memories/lineage`, { targetMemoryId: lineageTargetId.value })
  } catch { lineage.value = [] }
}

async function loadRoute() {
  if (!projectId.value) return
  try {
    const params = { scenario: routeScenario.value }
    if (routeQuery.value?.trim()) params.query = routeQuery.value.trim()
    if (routeUrgency.value != null) params.urgency = routeUrgency.value
    const r = await get(`/projects/${projectId.value}/memories/inject-route`, params)
    routePreview.value = r.preview || null
  } catch { routePreview.value = null }
}

async function loadTraces() {
  if (!projectId.value) return
  try { routeTraces.value = await get(`/projects/${projectId.value}/memories/inject-route/trace`) } catch { routeTraces.value = [] }
}

function gameSecondText(gs) {
  if (gs == null) return '—'
  const day = Math.floor(gs / 86400)
  const hour = Math.floor((gs % 86400) / 3600)
  return `D${day} ${String(hour).padStart(2, '0')}:00`
}
function fuzzTag(f) { return f >= 0.7 ? 'danger' : f >= 0.3 ? 'warning' : 'success' }
function fuzzStatusTag(s) { return s === 'distilled' ? 'success' : s === 'archived' ? 'info' : 'primary' }
function tierTag(t) { return t === 'L3' ? 'danger' : t === 'L2' ? 'warning' : 'info' }
</script>

<style scoped>
.head-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.proj-select { width: 200px; }
.hint { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
.card { margin-bottom: 14px; }
.filter { margin-left: 10px; width: 140px; }
.empty { color: var(--text-tertiary, #8a8a8a); font-size: 12px; padding: 8px 0; }
.detail-wrap { padding: 4px 8px 10px; }
.detail-actions { margin-top: 8px; }
.trace-jump-title { font-size: 12px; color: var(--text-secondary, #666); margin: 8px 0 4px; font-weight: 600; }
.trace-json { background: var(--bg-layer-1, #f5f5f5); border: 1px solid var(--border-l2, #e8e8e8); border-radius: 6px; padding: 8px 10px; font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0 0 6px; }
.lineage-chain { display: flex; flex-direction: column; gap: 8px; }
.lineage-hop { border: 1px solid var(--border-l2, #e8e8e8); border-radius: 6px; padding: 8px 12px; background: var(--bg-layer-1, #fafafa); }
.lineage-hop-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.lineage-arrow { color: var(--accent, #4a7dff); font-weight: 600; }
.lineage-type { color: var(--text-secondary, #666); font-size: 12px; }
.lineage-source { font-size: 12px; margin-top: 4px; color: var(--text-primary, #333); }
.route-sum { display: flex; gap: 20px; align-items: center; font-size: 13px; margin-bottom: 10px; }
.layer-tag { margin-right: 6px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.dim { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
</style>
