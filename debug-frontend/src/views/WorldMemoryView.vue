<template>
  <div class="wm-page">
    <div class="head-row">
      <h3 class="card-title">项目记忆（编年史 / 实体状态）</h3>
      <div class="head-controls">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="reloadAll">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <template v-if="projectId">
      <!-- 概览卡片 -->
      <div v-if="overview" class="cards">
        <div class="card"><div class="label">编年史条目</div><div class="value mono">{{ overview.chronicleCount }}</div></div>
        <div class="card"><div class="label">实体当前态</div><div class="value mono">{{ overview.activeEntityCount }}</div></div>
        <div class="card"><div class="label">实体总版本行</div><div class="value mono">{{ overview.entityTotalCount }}</div></div>
        <div class="card"><div class="label">编年史归档</div><div class="value mono">{{ overview.archiveCount }}</div></div>
        <div class="card wide">
          <div class="label">最新编年史</div>
          <div class="value sm" :title="overview.latestChronicle?.content">
            {{ overview.latestChronicle ? `第${overview.latestChronicle.gameDay + 1}日 · ${overview.latestChronicle.title}` : '（暂无）' }}
          </div>
        </div>
        <div class="card wide">
          <div class="label">近期实体变化</div>
          <div class="value sm">
            <span v-for="e in overview.latestEntityChanges || []" :key="e.id" class="chip" :title="e.currentState">
              {{ typeLabel(e.entityType) }}·{{ e.name }}：{{ e.recentChange }}
            </span>
            <span v-if="!(overview.latestEntityChanges || []).length" class="dim">（暂无）</span>
          </div>
        </div>
      </div>

      <!-- Tab -->
      <el-tabs v-model="tab" class="wm-tabs">
        <el-tab-pane label="编年史" name="chronicle">
          <div class="filters">
            <el-input-number v-model="chronicleFrom" :min="0" placeholder="起始日" controls-position="right" class="f-day" />
            <span class="dim">—</span>
            <el-input-number v-model="chronicleTo" :min="0" placeholder="结束日" controls-position="right" class="f-day" />
            <el-select v-model="chronicleImp" clearable placeholder="重要度≥" class="f-imp" @change="loadChronicle">
              <el-option v-for="n in [2, 3, 4, 5]" :key="n" :label="`≥${n}`" :value="n" />
            </el-select>
            <el-button size="small" @click="loadChronicle">查询</el-button>
          </div>
          <el-table :data="chronicles" size="small" empty-text="暂无编年史条目" class="wm-table" max-height="52vh">
            <el-table-column label="日" width="70" align="center">
              <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
            </el-table-column>
            <el-table-column label="标题" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title }}</template>
            </el-table-column>
            <el-table-column label="内容" min-width="300" show-overflow-tooltip>
              <template #default="{ row }">{{ row.content }}</template>
            </el-table-column>
            <el-table-column label="重要度" width="70" align="center">
              <template #default="{ row }"><el-tag size="small" :type="impTag(row.importance)" effect="light">{{ row.importance }}</el-tag></template>
            </el-table-column>
            <el-table-column label="实体" width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ (row.involvedEntities || []).join('、') || '—' }}</template>
            </el-table-column>
            <el-table-column label="事件" width="110" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ (row.eventIds || []).join(',') || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="来源" width="90" align="center">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ sourceLabel(row.source) }}</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="实体状态" name="entity">
          <div class="filters">
            <el-select v-model="entityType" clearable placeholder="全部类型" class="f-imp" @change="loadEntities">
              <el-option label="地点" value="location" />
              <el-option label="人物" value="person" />
              <el-option label="势力" value="faction" />
            </el-select>
            <el-input-number v-model="asOfDay" :min="0" placeholder="as-of 游戏日（空=当前态）" controls-position="right" class="f-day" />
            <el-button size="small" @click="loadEntities">查询</el-button>
          </div>
          <el-table :data="entities" size="small" empty-text="暂无实体状态" class="wm-table" max-height="52vh">
            <el-table-column label="类型" width="70" align="center">
              <template #default="{ row }"><el-tag size="small" :type="typeTag(row.entityType)" effect="plain">{{ typeLabel(row.entityType) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="实体键" width="200" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.entityKey }}</span></template>
            </el-table-column>
            <el-table-column label="当前状态" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ row.currentState }}</template>
            </el-table-column>
            <el-table-column label="最近变化" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.recentChange || '—' }}</template>
            </el-table-column>
            <el-table-column label="有效窗口" width="140" align="center">
              <template #default="{ row }"><span class="mono">{{ row.validFromGameDay }}→{{ row.validToGameDay ?? '至今' }}</span></template>
            </el-table-column>
            <el-table-column label="来源" width="90" align="center">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ provLabel(row.provenance) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="版本链" width="90" align="center">
              <template #default="{ row }"><span class="mono">{{ row.supersedesId ?? '—' }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
    <div v-else class="dim">请选择项目查看项目记忆（编年史 L2 + 实体状态 L3）</div>
  </div>
</template>

<script setup>
/**
 * 项目记忆调试页（记忆系统重构 M2/M3，2026-09-12，debug-frontend/views）。
 * <p>职责：调试视角浏览<b>项目记忆</b>——编年史（即时/每日/巩固补漏/手动四来源，含涉及实体/事件 id）
 * 与实体状态（三域 + Zep 式有效窗口 + 版本链 supersedes + 来源），概览卡片显示治理规模。
 * 数据来源：/api/projects/{id}/world-memory/*（chronicle / entities / overview）。</p>
 */
import { ref, onMounted } from 'vue'
import { get } from '../api'

const projects = ref([])
const projectId = ref(null)
const tab = ref('chronicle')
const overview = ref(null)

const chronicles = ref([])
const chronicleFrom = ref(null)
const chronicleTo = ref(null)
const chronicleImp = ref(null)

const entities = ref([])
const entityType = ref(null)
const asOfDay = ref(null)

onMounted(async () => {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
  } catch { projects.value = [] }
})

async function reloadAll() {
  if (!projectId.value) return
  await Promise.all([loadOverview(), loadChronicle(), loadEntities()])
}

async function loadOverview() {
  try { overview.value = await get(`/projects/${projectId.value}/world-memory/overview`) } catch { overview.value = null }
}

async function loadChronicle() {
  if (!projectId.value) return
  try {
    const params = { page: 1, size: 100 }
    if (chronicleFrom.value != null) params.fromDay = chronicleFrom.value
    if (chronicleTo.value != null) params.toDay = chronicleTo.value
    if (chronicleImp.value != null) params.importance = chronicleImp.value
    const res = await get(`/projects/${projectId.value}/world-memory/chronicle`, params)
    chronicles.value = res.list || []
  } catch { chronicles.value = [] }
}

async function loadEntities() {
  if (!projectId.value) return
  try {
    const params = { page: 1, size: 100 }
    if (entityType.value) params.type = entityType.value
    if (asOfDay.value != null) params.asOfGameDay = asOfDay.value
    const res = await get(`/projects/${projectId.value}/world-memory/entities`, params)
    entities.value = res.list || []
  } catch { entities.value = [] }
}

function typeLabel(t) { return { location: '地点', person: '人物', faction: '势力' }[t] || t || '实体' }
function typeTag(t) { return { location: 'primary', person: 'success', faction: 'warning' }[t] || 'info' }
function impTag(i) { return i >= 4 ? 'danger' : i === 3 ? 'warning' : 'info' }
function sourceLabel(s) { return { instant: '即时', daily: '每日批量', consolidation: '巩固补漏', manual: '手动' }[s] || s || '—' }
function provLabel(p) { return { chronicle: '编年史', event: '事件', manual: '手动', consolidation: '巩固', dialog: '对话路由' }[p] || p || '—' }
</script>

<style scoped>
.wm-page { display: flex; flex-direction: column; gap: 12px; }
.head-row { display: flex; align-items: center; justify-content: space-between; }
.head-controls { display: flex; align-items: center; gap: 8px; }
.proj-select { width: 240px; }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.card { background: var(--bg-layer-1, #fff); border: 1px solid var(--border-light, #eee); border-radius: 10px; padding: 10px 14px; }
.card.wide { grid-column: span 2; }
.label { font-size: 0.75rem; color: var(--text-secondary, #888); }
.value { font-size: 1.3rem; font-weight: 700; margin-top: 2px; }
.value.sm { font-size: 0.8rem; font-weight: 400; margin-top: 4px; line-height: 1.5; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chip { display: inline-block; background: var(--primary-bg, #ecf5ff); border-radius: 4px; padding: 1px 6px; margin: 1px 6px 1px 0; font-size: 0.75rem; }
.dim { color: var(--text-placeholder, #aaa); font-size: 0.8rem; }
.mono { font-family: ui-monospace, Consolas, monospace; font-size: 0.78rem; }
.filters { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.f-day { width: 150px; }
.f-imp { width: 120px; }
.wm-table { width: 100%; }
</style>
