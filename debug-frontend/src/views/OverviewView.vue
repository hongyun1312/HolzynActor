<template>
  <div class="overview">
    <h2 class="page-title">总览</h2>

    <!-- 后端状态卡片（DSH 令牌：--bg-layer-1 卡片 + 阴影分层） -->
    <div class="cards">
      <el-card shadow="never" class="stat-card">
        <div class="stat-label">后端状态</div>
        <div class="stat-value" :style="{ color: online ? 'var(--state-success)' : 'var(--state-error)' }">
          {{ online ? 'UP' : 'DOWN' }}
        </div>
        <div class="stat-sub mono">{{ backend?.appName || '-' }} · v{{ backend?.version || '?' }}</div>
        <div class="stat-sub mono">启动 {{ fmtTime(backend?.startTime) }} · 运行 {{ fmtUptime(backend?.uptimeSeconds) }}</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">JVM 内存</div>
        <div class="stat-value mono">{{ backend?.jvm?.usedMb ?? '-' }} MB</div>
        <div class="stat-sub mono">已用 / 总量 / 上限：{{ backend?.jvm?.usedMb ?? '?' }}/{{ backend?.jvm?.totalMb ?? '?' }}/{{ backend?.jvm?.maxMb ?? '?' }} MB</div>
        <div class="stat-sub mono">线程 {{ backend?.threadCount ?? '-' }} · CPU {{ backend?.jvm?.processors ?? '-' }} 核</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">SSE 连接</div>
        <div class="stat-value mono">{{ backend?.activeSse ?? '-' }}</div>
        <div class="stat-sub">行动时间线 + 调试日志流 + 调试事件流</div>
      </el-card>

      <el-card shadow="never" class="stat-card">
        <div class="stat-label">调试模块</div>
        <div class="stat-sub">
          AI Trace 埋点：<el-tag :type="backend?.debug?.aiLogEnabled ? 'success' : 'danger'" size="small">{{ backend?.debug?.aiLogEnabled ? '开启' : '关闭' }}</el-tag>
        </div>
        <div class="stat-sub">日志缓冲：{{ backend?.debug?.logBufferSize ?? 0 }}/{{ backend?.debug?.logBufferCapacity ?? 0 }} 条（{{ backend?.debug?.logAppendEnabled ? '采集开启' : '采集关闭' }}）</div>
        <div class="stat-sub mono">Prompt 截断上限：{{ backend?.debug?.aiLogMaxPromptChars ?? 20000 }} 字符</div>
      </el-card>

      <!-- 世界引擎运行状态卡（B3 F3：world_running/预算/上次推进/逐时进度/扫描间隔） -->
      <el-card shadow="never" class="stat-card">
        <div class="stat-label">世界引擎</div>
        <div class="stat-value mono">{{ engine?.runningCount ?? '-' }}</div>
        <div class="stat-sub">持续运行项目 · 暂停 {{ engine?.pausedCount ?? 0 }} · 关闭 {{ engine?.stoppedCount ?? 0 }}</div>
        <div class="stat-sub">
          扫描间隔：{{ fmtInterval(engine?.scanIntervalMs) }}
          <el-tag :type="engine?.simEnabled ? 'success' : 'info'" size="small" style="margin-left:6px">{{ engine?.simEnabled ? '模拟开' : '模拟关' }}</el-tag>
        </div>
        <div class="stat-sub mono">引擎配置行：{{ engine?.engineConfigCount ?? 0 }}</div>
      </el-card>
    </div>

    <!-- 最近 AI 调用 -->
    <el-card shadow="never" class="block-card">
      <template #header><div class="card-title">最近 AI 调用</div></template>
      <template v-if="lastAiCall">
        <div class="last-ai">
          <el-tag :type="statusType(lastAiCall.status)" size="small">{{ statusLabel(lastAiCall.status) }}</el-tag>
          <span class="mono">scene={{ lastAiCall.scene }} · model={{ lastAiCall.model }}</span>
          <span class="mono">tokens={{ lastAiCall.tokenIn }}/{{ lastAiCall.tokenOut }} · {{ lastAiCall.durationMs }}ms</span>
          <span class="mono dim">{{ lastAiCall.time }}</span>
        </div>
      </template>
      <el-empty v-else description="暂无 AI 调用记录（触发一次对话/生成后出现）" :image-size="60" />
    </el-card>

    <!-- 项目摘要 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-title">项目摘要</div>
      </template>
      <el-table :data="projects" size="small" stripe>
        <el-table-column prop="name" label="项目" min-width="140">
          <template #default="{ row }">
            <router-link :to="{ path: '/ai', query: { projectId: row.id } }" class="link">{{ row.name }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="角色 / NPC / 对话" width="130">
          <template #default="{ row }" class="mono">{{ row.characterCount }} / {{ row.npcCount }} / {{ row.conversationCount }}</template>
        </el-table-column>
        <el-table-column label="世界时钟" min-width="180">
          <template #default="{ row }">
            <template v-if="row.clock">
              <div class="mono">{{ row.clock.gameTimeText }}</div>
              <div class="dim">速率 ×{{ row.clock.rate }}
                <el-tag v-if="row.clock.paused === 1" type="warning" size="small" style="margin-left:4px">暂停</el-tag>
              </div>
            </template>
            <span v-else class="dim">未初始化</span>
          </template>
        </el-table-column>
        <el-table-column label="世界引擎" min-width="180">
          <template #default="{ row }">
            <template v-if="row.clock">
              <div>
                <el-tag :type="row.clock.worldRunning === 1 ? 'success' : 'info'" size="small">{{ row.clock.worldRunning === 1 ? '持续运行' : '已关闭' }}</el-tag>
                <el-tag v-if="row.engine" type="primary" size="small" style="margin-left:4px">预算 {{ row.engine.budgetPerTick }}/tick</el-tag>
              </div>
              <div class="dim mono">上次推进 {{ fmtTime(row.clock.lastEngineRunAt) }} · 进度 h{{ row.clock.lastGameHour ?? '-' }}</div>
            </template>
            <span v-else class="dim">未初始化</span>
          </template>
        </el-table-column>
        <el-table-column label="模拟 / 人群调度" width="120">
          <template #default="{ row }">
            <el-tag :type="row.worldSimEnabled ? 'success' : 'info'" size="small">世界 {{ row.worldSimEnabled ? '开' : '关' }}</el-tag>
            <el-tag :type="row.crowdScheduled ? 'success' : 'info'" size="small" style="margin-left:4px">人群 {{ row.crowdScheduled ? '开' : '关' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近 AI 调用" min-width="200">
          <template #default="{ row }">
            <template v-if="row.lastAiCall">
              <el-tag :type="statusType(row.lastAiCall.status)" size="small">{{ statusLabel(row.lastAiCall.status) }}</el-tag>
              <span class="mono dim" style="margin-left:6px">{{ row.lastAiCall.scene }} · {{ row.lastAiCall.time }}</span>
            </template>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="jumpAi(row.id)">AI Trace</el-button>
            <el-button size="small" text type="primary" @click="jumpDb(row.id)">DB 浏览</el-button>
            <el-button size="small" text type="warning" @click="openSnapshot(row)">快照</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- W-2 世界引擎快照对话框（dump 关键表 JSON，最小回滚手段） -->
    <el-dialog v-model="snapshotDialog" :title="`世界引擎快照 · ${snapshotName || ''}`" width="760px" top="5vh">
      <el-alert type="info" :closable="false" show-icon
        title="按游戏日 dump 世界关键表（时钟/引擎配置/角色/居民/近期事件/近期 AI 调用），可导出作为回滚/排查对照基准。"
        description="快照时刻：{{ snapshotAt }}；纯只读，不会修改任何数据。" style="margin-bottom: 12px" />
      <div v-if="snapshotLoading" class="snapshot-loading mono">加载中…</div>
      <pre v-else class="snapshot-json mono">{{ snapshotJson || '（暂无数据）' }}</pre>
      <template #footer>
        <el-button @click="snapshotDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!snapshotJson" @click="downloadSnapshot">导出 JSON</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { get, health } from '../api'

const router = useRouter()
const backend = ref(null)
const projects = ref([])
const lastAiCall = ref(null)
const engine = ref(null)
const online = ref(false)

// W-2 世界快照（dump 关键表 JSON）
const snapshotDialog = ref(false)
const snapshotLoading = ref(false)
const snapshotJson = ref('')
const snapshotName = ref('')
const snapshotAt = ref('')
let snapshotRaw = null

async function load() {
  const h = await health()
  online.value = !!h
  try {
    const o = await get('/debug/overview')
    backend.value = o.backend || {}
    engine.value = o.worldEngine || {}
    projects.value = o.projects || []
    lastAiCall.value = o.lastAiCall || null
  } catch (e) {
    console.error('总览加载失败', e)
  }
}

function fmtTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}

function fmtUptime(sec) {
  if (sec === undefined || sec === null) return '-'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return h > 0 ? `${h}h${m}m` : `${m}m`
}

/** 毫秒 → 人类可读间隔（如 180000 → 3 分钟） */
function fmtInterval(ms) {
  if (ms === undefined || ms === null) return '-'
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s}s`
  return `${Math.round(s / 60)} 分钟`
}

function statusType(s) {
  if (s === 'success') return 'success'
  if (s === 'degraded') return 'warning'
  return 'danger'
}

function statusLabel(s) {
  return { success: '成功', failed: '失败', degraded: '降级' }[s] || s || '-'
}

function jumpAi(projectId) {
  router.push({ path: '/ai', query: { projectId } })
}

function jumpDb(projectId) {
  router.push({ path: '/db' })
}

/** 打开世界引擎快照（W-2，dump 关键表 JSON） */
async function openSnapshot(row) {
  if (!row?.id) return
  snapshotName.value = row.name || `项目${row.id}`
  snapshotDialog.value = true
  snapshotLoading.value = true
  snapshotJson.value = ''
  snapshotAt.value = ''
  try {
    const snap = await get('/debug/engine/snapshot', { projectId: row.id })
    snapshotRaw = snap
    snapshotAt.value = snap.snapshotAt || ''
    snapshotJson.value = JSON.stringify(snap, null, 2)
  } catch (e) {
    snapshotJson.value = `快照获取失败：${e.message}`
  } finally {
    snapshotLoading.value = false
  }
}

/** 导出快照 JSON 文件 */
function downloadSnapshot() {
  if (!snapshotRaw) return
  const blob = new Blob([JSON.stringify(snapshotRaw, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `world-snapshot-${snapshotRaw.projectId || 'unknown'}-${snapshotRaw.snapshotAt ? snapshotRaw.snapshotAt.replace(/[: ]/g, '-') : 'now'}.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(load)
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
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
.last-ai {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.link {
  color: var(--accent-text);
  text-decoration: none;
}
.dim {
  color: var(--text-tertiary);
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono, Consolas, 'Courier New', monospace);
}
.snapshot-loading {
  padding: 24px 0;
  text-align: center;
  color: var(--text-secondary);
}
.snapshot-json {
  max-height: 52vh;
  overflow: auto;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  background: var(--bg-layer-0);
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
