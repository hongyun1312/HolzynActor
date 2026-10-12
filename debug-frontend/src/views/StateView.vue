<template>
  <div class="state-view">
    <div class="head-row">
      <h2 class="page-title">NPC 状态曲线（M4）</h2>
      <div class="head-controls">
        <el-select v-model="projectId" clearable placeholder="选择项目" size="small" style="width: 180px" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="npcRef" clearable filterable placeholder="选择 NPC" size="small" style="width: 220px" @change="onNpcChange">
          <el-option v-for="n in npcs" :key="n.npcRef" :label="`${n.npcName || n.npcRef}（${n.npcType === 'special' ? '特殊' : '人群'}）`" :value="n.npcRef" />
        </el-select>
        <el-select v-model="curveNeed" size="small" style="width: 130px" @change="buildCurve">
          <el-option v-for="n in curveNeeds" :key="n.value" :label="n.label" :value="n.value" />
        </el-select>
        <el-button size="small" :loading="loading" :disabled="!projectId || !npcRef" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="!projectId || !npcRef" type="info" :closable="false" class="hint-bar"
      title="选择项目与 NPC 后查看内部状态实时数值与随时间曲线（历史快照来自 actor_state_history，引擎逐游戏小时采样）" />

    <template v-if="projectId && npcRef">
      <!-- 实时数值卡片（轮询刷新） -->
      <el-card v-if="state" shadow="never" class="block-card">
        <template #header>
          <div class="card-title">
            实时状态 · <span class="mono">{{ state.npcRef }}</span>
            <span class="dim" style="font-weight:400; margin-left:8px">轮询 {{ pollMs / 1000 }}s · 游戏时刻 {{ fmtGameClock(state.gameSecond) }}</span>
          </div>
        </template>
        <div v-if="state.urgencyLevel > 0" class="urgent" :class="'lv' + Math.min(3, state.urgencyLevel)">
          紧急状态 Lv{{ state.urgencyLevel }}：{{ state.urgencyText || '—' }}
        </div>
        <div class="state-grid">
          <div class="state-card">
            <div class="state-card-title">需求（9 项）</div>
            <div v-for="n in needList" :key="n.key" class="need-row">
              <span class="need-name">{{ n.label }}</span>
              <el-progress :percentage="n.value ?? 0" :stroke-width="12" :color="needColor(n.value)"
                :show-text="false" class="need-bar" />
              <span class="need-val" :class="needTone(n.value)">{{ n.value ?? '—' }}</span>
              <span class="need-desc dim">{{ n.desc || '' }}</span>
            </div>
          </div>
          <div class="state-card">
            <div class="state-card-title">心情与情绪</div>
            <div class="kv-row"><span>心情 mood</span><b>{{ state.mood ?? '—' }}<i class="dim">（{{ moodText(state.mood) }}）</i></b></div>
            <div class="kv-row"><span>主导情绪</span><b>{{ emotionLabel(state.dominantEmotion) }}<i v-if="state.emotionLabelExplicit === 1" class="explicit">AI 评估</i></b></div>
            <div class="kv-row"><span>情绪强度</span><b>{{ state.emotionIntensity ?? '—' }} / 100</b></div>
            <div class="kv-row"><span>愉悦 P</span><b :class="padTone(state.emotionPleasure)">{{ fmtPad(state.emotionPleasure) }}</b></div>
            <div class="kv-row"><span>唤醒 A</span><b :class="padTone(state.emotionArousal)">{{ fmtPad(state.emotionArousal) }}</b></div>
            <div class="kv-row"><span>支配 D</span><b :class="padTone(state.emotionDominance)">{{ fmtPad(state.emotionDominance) }}</b></div>
            <div class="kv-row"><span>疲劳（100-精力）</span><b>{{ state.fatigue ?? '—' }}</b></div>
          </div>
          <div class="state-card">
            <div class="state-card-title">元信息</div>
            <div class="kv-row"><span>NPC 类型</span><b>{{ state.npcType === 'special' ? '特殊 NPC' : '人群 NPC' }}</b></div>
            <div class="kv-row"><span>游戏时刻</span><b class="mono">{{ fmtGameClock(state.gameSecond) }}</b></div>
            <div class="kv-row"><span>最近进食</span><b class="mono">{{ fmtGameClock(state.lastEatGameSecond) }}</b></div>
            <div class="kv-row"><span>最近睡眠</span><b class="mono">{{ fmtGameClock(state.lastSleepGameSecond) }}</b></div>
            <div class="kv-row"><span>更新时间</span><b class="mono">{{ state.updatedAt || '—' }}</b></div>
          </div>
        </div>
      </el-card>
      <el-card v-else shadow="never" class="block-card"><div class="side-empty">暂无实时状态（世界引擎驱动后出现）</div></el-card>

      <!-- 历史曲线 -->
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-title">
            历史曲线 · {{ curveNeedLabel(curveNeed) }}
            <span class="dim" style="font-weight:400; margin-left:8px">{{ history.length }} 个采样点 · 引擎逐游戏小时采样 · 保留上限 168</span>
          </div>
        </template>
        <EChart v-if="history.length" :option="curveOption" height="340px" />
        <div v-else class="side-empty">暂无历史采样（推进世界引擎后生成曲线数据）</div>
      </el-card>

      <!-- 角色档案（11 维，只读）：与主前端同源组件（分片注入的数据源，便于「档案 ↔ 系数 ↔ 实际曲线」对照） -->
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-title">
            角色档案（11 维，只读）
            <span class="dim" style="font-weight:400; margin-left:8px">
              /api/characters/{id}/card · 渲染文本 {{ archiveHasRenderText ? '来自服务端 renderFull' : '缺失（降级展示原始 JSON）' }}
            </span>
            <!-- G1b：调试前端同样开放档案可视化编辑器（双前端镜像） -->
            <el-button size="small" text type="primary" style="float:right" :disabled="!archiveCard" @click="editorVisible = true">编辑</el-button>
            <el-button size="small" text type="primary" style="float:right" :loading="archiveLoading" @click="loadArchive">刷新</el-button>
          </div>
        </template>
        <div v-loading="archiveLoading" class="archive-body">
          <el-alert
            v-if="MOCK_MODE"
            type="info"
            :closable="false"
            class="hint-bar"
            title="本地 mock 模式（?personaMock=1）"
            description="以下档案来自仓库内基准夹具（沈砚秋），非后端实时数据——仅用于后端不可用时核对 11 维只读排版（编辑按钮在 mock 模式下不可用）。"
          />
          <el-alert
            v-if="archiveError"
            type="warning"
            :closable="false"
            class="hint-bar"
            :title="`档案接口未取到数据：${archiveError}`"
            description="后端此刻可能未运行或正在重建基线库（接口 404/连接失败不代表档案缺失）。也可用 ?personaMock=1 走本地基准样例（见 loadArchive）。"
          />
          <PersonaArchivePanel v-else :card="archiveCard" :character-name="npcName" />
        </div>
      </el-card>

      <!-- G1b：11 维档案可视化编辑器（表单为主 + JSON 兜底 + AI 补全缺失维 + 版本历史/回滚） -->
      <PersonaEditorDialog
        v-model:visible="editorVisible"
        :card="archiveCard"
        :character-name="npcName"
        @saved="onEditorSaved"
      />
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get } from '../api'
import EChart from '../components/EChart.vue'
import PersonaArchivePanel from '../components/PersonaArchivePanel.vue'
import PersonaEditorDialog from '../components/PersonaEditorDialog.vue'
import { npcIdOf } from '../utils/npcRef'

const projects = ref([])
const npcs = ref([])
const projectId = ref(null)
const npcRef = ref(null)
const state = ref(null)
const history = ref([])
const loading = ref(false)
const curveNeed = ref('hunger')
const pollMs = 5000

// ===== 角色档案（11 维，只读）=====
const archiveCard = ref(null)
const archiveLoading = ref(false)
const archiveError = ref('')
const archiveHasRenderText = computed(() => !!(archiveCard.value?.personaRenderText || '').trim())
/** 当前选中 NPC 的显示名（选择器里的 npcName） */
const npcName = computed(() => npcs.value.find(n => n.npcRef === npcRef.value)?.npcName || '')

// ===== G1b：档案可视化编辑器（双前端镜像；编辑器保存后刷新档案与状态） =====
const editorVisible = ref(false)
async function onEditorSaved(card) {
  archiveCard.value = card || archiveCard.value
  if (card) ElMessage.success(`角色档案已更新：v${card.version}`)
  await loadArchive()
  await load()
}

/**
 * 本地 mock 模式（dev-only）：URL 带 {@code personaMock=1} 时启用。
 * 后端未运行也能核对 11 维只读排版——注入一个假项目 + 假 NPC（沈砚秋基准样例），
 * 并短路所有真实接口调用。生产构建恒为 false（{@code import.meta.env.DEV}）。
 * 移除方式：删除本常量与各处的 {@code MOCK_MODE} 分支 + {@code utils/personaArchiveMock.js}。
 */
const MOCK_MODE = import.meta.env.DEV && typeof location !== 'undefined' && location.search.includes('personaMock=1')

const curveNeeds = [
  { value: 'hunger', label: '饥饿' },
  { value: 'thirst', label: '口渴' },
  { value: 'energy', label: '精力' },
  { value: 'bladder', label: '膀胱' },
  { value: 'health', label: '健康' },
  { value: 'mood', label: '心情' },
  { value: 'social', label: '社交' },
  { value: 'fun', label: '趣味' },
  { value: 'hygiene', label: '清洁' }
]

const needList = [
  { key: 'hunger', label: '饥饿' }, { key: 'thirst', label: '口渴' },
  { key: 'energy', label: '精力' }, { key: 'bladder', label: '膀胱' },
  { key: 'health', label: '健康' }, { key: 'hygiene', label: '清洁' },
  { key: 'social', label: '社交' }, { key: 'fun', label: '趣味' },
  { key: 'comfort', label: '舒适' }
]

let pollTimer = null

const curveOption = computed(() => {
  const points = history.value
  const data = points.map(p => p[curveNeed.value] ?? null)
  const xs = points.map(p => fmtGameHour(p.gameSecond))
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        const idx = p.dataIndex
        const raw = points[idx]
        return `${xs[idx]}<br/>${curveNeedLabel(curveNeed.value)}：<b>${raw[curveNeed.value] ?? '—'}</b>`
          + (raw.urgencyLevel ? `<br/><span style="color:#ff6b6b">紧急 Lv${raw.urgencyLevel}</span>` : '')
      }
    },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: xs, axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [{
      type: 'line', data, smooth: true, symbol: 'circle', symbolSize: 5,
      lineStyle: { width: 2 },
      areaStyle: { opacity: 0.12 },
      markLine: {
        silent: true,
        data: [{ yAxis: 30, lineStyle: { color: '#ffb84d', type: 'dashed' }, label: { formatter: '轻度 30' } },
               { yAxis: 15, lineStyle: { color: '#ff6b6b', type: 'dashed' }, label: { formatter: '强烈 15' } }]
      }
    }]
  }
})

async function loadProjects() {
  // mock 模式：不依赖后端，注入假项目（便于在无后端时进入详情页核对 11 维展示）
  if (MOCK_MODE) {
    projects.value = [{ id: 1, name: '本地 mock（沈砚秋基准样例）' }]
    return
  }
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
  } catch (_) { /* 忽略 */ }
}

async function onProjectChange() {
  npcRef.value = null
  npcs.value = []
  state.value = null
  history.value = []
  // mock 模式：注入假 NPC（npcRef 用统一身份引用 npc:{id}，与真实接口口径一致）
  if (MOCK_MODE) {
    npcs.value = [{ npcRef: 'npc:1', npcName: '沈砚秋', npcType: 'special' }]
    return
  }
  if (projectId.value) {
    try {
      const r = await get(`/projects/${projectId.value}/sandbox/overview`)
      npcs.value = r?.data?.states || []
    } catch (_) { /* 忽略 */ }
  }
}

async function onNpcChange() {
  stopPoll()
  state.value = null
  history.value = []
  await load()
}

async function load() {
  if (!projectId.value || !npcRef.value) return
  if (MOCK_MODE) return // mock 模式无实时状态/曲线数据，仅展示档案；不发起真实请求
  loading.value = true
  try {
    const [stateRes, historyRes] = await Promise.all([
      get(`/projects/${projectId.value}/npc-state/${encodeURIComponent(npcRef.value)}`),
      get(`/projects/${projectId.value}/npc-state/history`, { npcRef: npcRef.value, limit: 168 })
    ])
    state.value = stateRes?.data || null
    history.value = historyRes?.data || []
  } catch (e) {
    console.error('状态加载失败', e)
  } finally {
    loading.value = false
    startPoll()
  }
}

// ==================== 角色档案（11 维，只读） ====================

/**
 * 加载当前 NPC 的 11 维档案（GET /api/characters/{id}/card）。
 *
 * <p><b>零阻断</b>：后端未运行/正在重建基线库时接口会 404 或连接失败——只提示、不判定为功能失败，
 * 面板保持空态（显示"该角色尚未生成角色档案"）。</p>
 *
 * <p><b>本地 mock 开关（dev-only）</b>：URL 带 {@code personaMock=1}（如
 * {@code http://localhost:5175/?personaMock=1#/state}）时改用仓库内基准夹具（沈砚秋）
 * 渲染，用于后端不可用时核对排版；生产构建不加载（{@code import.meta.env.DEV} + 动态 import）。
 * 移除方式：删掉本函数的 mock 分支与 {@code utils/personaArchiveMock.js} 整个文件。</p>
 */
async function loadArchive() {
  archiveCard.value = null
  archiveError.value = ''
  const id = npcIdOf(npcRef.value)
  if (!id) return
  archiveLoading.value = true
  try {
    if (MOCK_MODE) {
      const mod = await import('../utils/personaArchiveMock')
      archiveCard.value = mod.MOCK_PERSONA_CARD
      return
    }
    const r = await get(`/characters/${id}/card`)
    archiveCard.value = r?.data || null
  } catch (e) {
    // 接口不可用 ≠ 档案缺失：分别提示，避免把"后端没起来"误读成"角色没有档案"
    archiveError.value = e?.message || String(e)
  } finally {
    archiveLoading.value = false
  }
}

/** 选中 NPC 变化时刷新档案（首次进入由 onNpcChange 触发） */
watch(npcRef, () => { if (npcRef.value) loadArchive() })

function startPoll() {
  stopPoll()
  if (!projectId.value || !npcRef.value) return
  pollTimer = setInterval(async () => {
    try {
      const r = await get(`/projects/${projectId.value}/npc-state/${encodeURIComponent(npcRef.value)}`)
      if (r?.data) state.value = r.data
    } catch (_) { /* 轮询失败静默 */ }
  }, pollMs)
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

// ===== 展示工具 =====
function needColor(v) {
  if (v == null) return '#909399'
  if (v < 15) return '#f56c6c'
  if (v < 30) return '#e6a23c'
  if (v < 60) return '#409eff'
  return '#67c23a'
}
function needTone(v) {
  if (v == null) return ''
  if (v < 15) return 'tone-danger'
  if (v < 30) return 'tone-warn'
  if (v < 60) return 'tone-info'
  return 'tone-ok'
}
function moodText(v) {
  if (v == null) return ''
  if (v >= 80) return '极好'
  if (v >= 60) return '良好'
  if (v >= 40) return '一般'
  if (v >= 20) return '低落'
  return '极差'
}
function emotionLabel(v) {
  return { calm: '平静', happy: '高兴', angry: '愤怒', sad: '悲伤', fearful: '恐惧',
    surprised: '惊讶', disgusted: '厌恶' }[v] || v || '—'
}
function padTone(v) {
  if (v == null) return ''
  return v >= 0 ? 'tone-ok' : 'tone-danger'
}
function fmtPad(v) {
  if (v == null) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(2)
}
function fmtGameClock(sec) {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600)
  return `第${Math.floor(h / 24) + 1}日 ${String(h % 24).padStart(2, '0')}:00`
}
function fmtGameHour(sec) {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600)
  return `${Math.floor(h / 24) + 1}日${String(h % 24).padStart(2, '0')}时`
}
function curveNeedLabel(v) {
  return curveNeeds.find(n => n.value === v)?.label || v
}

onMounted(async () => {
  await loadProjects()
  // mock 模式：自动选中假项目 + 假 NPC，页面一打开就能看到 11 维档案（无需任何后端数据）
  if (MOCK_MODE) {
    projectId.value = 1
    await onProjectChange()
    npcRef.value = 'npc:1'
  }
})
onBeforeUnmount(() => stopPoll())
</script>

<style scoped>
.page-title { margin: 0 0 14px; font-size: 18px; color: var(--text-primary); }
.head-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.head-controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hint-bar { margin-bottom: 12px; }
.block-card { margin-bottom: 14px; background: var(--bg-layer-1); border: 1px solid var(--border-l1); border-radius: var(--radius-lg); box-shadow: var(--shadow-lv1); }
.card-title { font-weight: 600; font-size: 13px; color: var(--text-primary); }
.dim { color: var(--text-tertiary); }
.mono { font-family: var(--font-mono, monospace); font-size: 12px; }
.side-empty { padding: 14px 0; color: var(--text-tertiary); font-size: 12px; }
.urgent { padding: 8px 12px; border-radius: 8px; margin-bottom: 12px; font-size: 13px; font-weight: 600; }
.urgent.lv1 { background: rgba(255, 184, 77, 0.10); color: #ffb84d; }
.urgent.lv2 { background: rgba(255, 107, 107, 0.10); color: #ff6b6b; }
.urgent.lv3 { background: rgba(214, 48, 48, 0.14); color: #d63030; }
.state-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.state-card { border: 1px solid var(--border-l1); border-radius: 10px; padding: 12px 14px; }
.state-card-title { font-weight: 700; font-size: 13px; color: var(--text-primary); margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid var(--border-l1); }
.need-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.need-name { width: 40px; font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
.need-bar { flex: 1; }
.need-val { width: 30px; text-align: right; font-weight: 700; font-size: 12px; font-family: var(--font-mono, monospace); }
.need-desc { width: 56px; font-size: 11px; }
.tone-danger { color: #f56c6c; }
.tone-warn { color: #e6a23c; }
.tone-info { color: #409eff; }
.tone-ok { color: #67c23a; }
.kv-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; padding: 4px 0; }
.kv-row span { color: var(--text-secondary); }
.kv-row b { color: var(--text-primary); font-weight: 600; }
.explicit { margin-left: 6px; font-size: 11px; color: #409eff; background: rgba(64, 158, 255, 0.12); padding: 1px 6px; border-radius: 4px; font-weight: 400; }
/* 角色档案（11 维，只读）区块 */
.archive-body { min-height: 40px; }
</style>
