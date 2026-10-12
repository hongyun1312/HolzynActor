<template>
  <div class="eval-test">
    <div class="head-row">
      <h2 class="page-title">行为评估 O2.5（P2-1 评分报告 + 专门测试接口五能力）</h2>
      <div class="toolbar">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
        <el-tag v-if="testEnabledMsg" size="small" :type="testEnabledMsg.ok ? 'success' : 'warning'" effect="plain">
          {{ testEnabledMsg.text }}
        </el-tag>
        <span class="hint">O2.5：① 阶段控制 / ② 状态聚合 / ③ 干预驱动 / ④ 评估批跑 / ⑤ test_run_log</span>
      </div>
    </div>

    <template v-if="projectId">
      <!-- ==================== ① 阶段控制 + ③ 干预驱动（命令台） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            ① 阶段控制 + ③ 干预驱动（POST /test/runs/{id}/command）
            <el-tag size="small" type="info" effect="plain" class="filter">每命令自动落时间线 + 状态快照</el-tag>
          </div>
        </template>
        <div class="preview-box">
          <div class="trace-jump-title">当前 test run（⑤ 时间线载体）</div>
          <el-select v-model="activeRunId" clearable filterable placeholder="选择 test run（无则先创建）" class="filter" style="width: 260px">
            <el-option v-for="r in runs" :key="r.id" :label="`#${r.id} ${r.name || ''}（${r.status}）`" :value="r.id" />
          </el-select>
          <el-input v-model="newRunName" size="small" placeholder="新 run 名称" clearable class="filter" style="width: 180px" />
          <el-input v-model="newRunPurpose" size="small" placeholder="目的说明（可空）" clearable class="filter" style="width: 220px" />
          <el-button size="small" type="success" @click="createRun">创建 run</el-button>
          <el-button size="small" type="warning" :disabled="!activeRunId" @click="finishRun">收尾 run</el-button>
          <span class="dim filter" style="font-size: 12px">test.enabled=false 时本页全部接口 403</span>
        </div>

        <el-tabs v-model="cmdTab" size="small">
          <!-- 阶段控制 -->
          <el-tab-pane label="① 阶段控制" name="phase">
            <div class="cmd-row">
              <el-button size="small" :disabled="!activeRunId" @click="cmd('phase_pause', {})">⏸ 暂停</el-button>
              <el-button size="small" :disabled="!activeRunId" @click="cmd('phase_resume', {})">▶ 恢复</el-button>
              <el-button size="small" :disabled="!activeRunId" @click="cmd('phase_advance', {})">⏭ 补推一轮</el-button>
              <el-divider direction="vertical" />
              <span class="dim">快进（拨时钟，须世界静止）：</span>
              <el-input-number v-model="ffPlusDays" :min="0" :max="3650" size="small" controls-position="right" style="width: 100px" placeholder="日" />
              <el-input-number v-model="ffPlusHours" :min="0" :max="24" size="small" controls-position="right" style="width: 100px" placeholder="时" />
              <el-input-number v-model="ffPlusMinutes" :min="0" :max="1440" size="small" controls-position="right" style="width: 100px" placeholder="分" />
              <el-button size="small" type="warning" :disabled="!activeRunId" @click="cmdFastForward">快进（相对）</el-button>
              <el-input v-model="ffTarget" size="small" placeholder="绝对目标游戏秒（可空）" clearable style="width: 150px" />
              <el-button size="small" :disabled="!activeRunId" @click="cmdFastForwardTarget">快进（绝对）</el-button>
            </div>
            <div class="cmd-row">
              <span class="dim">AI 强度：</span>
              <el-radio-group v-model="intensity" size="small" @change="cmdIntensity">
                <el-radio-button label="programmatic">纯规则</el-radio-button>
                <el-radio-button label="light">轻 AI</el-radio-button>
                <el-radio-button label="full">标准</el-radio-button>
                <el-radio-button label="max">Max 全 AI</el-radio-button>
              </el-radio-group>
              <span class="dim" style="margin-left: 10px">（phase_intensity 需选中 run）</span>
            </div>
          </el-tab-pane>

          <!-- 干预驱动 -->
          <el-tab-pane label="③ 干预驱动" name="intervene">
            <div class="cmd-row">
              <span class="dim">注入事件：</span>
              <el-input v-model="evContent" size="small" placeholder="事件内容（必填）" clearable style="width: 320px" />
              <el-input v-model="evTitle" size="small" placeholder="标题（可空）" clearable style="width: 160px" />
              <el-input v-model="evKind" size="small" placeholder="kind（可空）" clearable style="width: 120px" />
              <el-button size="small" type="primary" :disabled="!activeRunId || !evContent" @click="cmdInterveneEvent">注入</el-button>
            </div>
            <div class="cmd-row">
              <span class="dim">触发行动：</span>
              <el-input v-model="actNpcRef" size="small" placeholder="npc:{id}" clearable style="width: 140px" />
              <el-input v-model="actReason" size="small" placeholder="理由（可空）" clearable style="width: 200px" />
              <el-button size="small" type="primary" :disabled="!activeRunId || !actNpcRef" @click="cmdInterveneAction">触发</el-button>
            </div>
            <div class="cmd-row">
              <span class="dim">群聊剧集触发：</span>
              <el-input v-model="chatLocation" size="small" placeholder="地点名（可空=自动挑首个合格地点）" clearable style="width: 240px" />
              <el-button size="small" type="primary" :disabled="!activeRunId" @click="cmdTriggerChat">启动无玩家群聊剧集</el-button>
              <span class="dim">（与概率闲聊同源异步剧集，走清醒过滤/护栏/自然收束）</span>
            </div>
          </el-tab-pane>
        </el-tabs>
        <div v-if="lastResult" class="preview-result">
          <el-tag size="small" type="success" effect="plain">最近命令结果</el-tag>
          <pre class="trace-json">{{ pretty(lastResult.resultJson || lastResult) }}</pre>
        </div>
      </el-card>

      <!-- ==================== ② 状态聚合 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            ② 状态聚合（GET /test/state：引擎推进 / 群聊活跃 / 预算 / 用量归因）
            <el-input-number v-model="stateWindow" :min="60" :max="10080" size="small" controls-position="right" class="filter" style="width: 110px" placeholder="游戏分钟窗" />
            <el-button size="small" class="filter" @click="loadState">拉取</el-button>
          </div>
        </template>
        <template v-if="state">
          <el-descriptions :column="4" size="small" border class="state-desc">
            <el-descriptions-item label="游戏位置">{{ gameSecondText(state.engine?.gameSecond) }}</el-descriptions-item>
            <el-descriptions-item label="暂停/运行">
              <el-tag size="small" :type="state.engine?.paused ? 'warning' : 'success'" effect="plain">
                {{ state.engine?.paused ? '已暂停' : '推进中' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="激活">{{ state.engine?.active ? '✓' : '✗' }}（手动{{ state.engine?.manualEnabled ? '开' : '关' }}）</el-descriptions-item>
            <el-descriptions-item label="AI 强度">{{ state.engine?.aiIntensityLabel }}（{{ state.engine?.runMode }}）</el-descriptions-item>
            <el-descriptions-item label="窗内 AI 调用">{{ state.engine?.aiCallTotal }} 次（成功 {{ state.engine?.aiCallSuccess }} / 失败 {{ state.engine?.aiCallFailed }}）</el-descriptions-item>
            <el-descriptions-item label="进行中剧集">{{ state.chat?.runningEpisodes ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="群聊消息（窗内）">{{ state.chat?.groupMessages ?? 0 }} 条 / {{ state.chat?.groupConversations ?? 0 }} 会话</el-descriptions-item>
            <el-descriptions-item label="时间窗">{{ state.window?.gameMinutes }} 游戏分（≈{{ state.window?.realMinutes }} 真实分）</el-descriptions-item>
          </el-descriptions>
          <div class="trace-jump-title">预算（BudgetGuard 命名空间）</div>
          <pre class="trace-json">{{ pretty(state.budget) }}</pre>
          <div class="trace-jump-title">用量归因（按 NPC × 场景，谁在烧预算）</div>
          <el-table :data="state.usage?.byNpc || []" size="small" max-height="240" empty-text="窗内无按角色归因的 AI 调用">
            <el-table-column prop="npcName" label="角色" width="120" show-overflow-tooltip />
            <el-table-column prop="npcRef" label="ref" width="110" show-overflow-tooltip />
            <el-table-column prop="scene" label="场景" width="150" show-overflow-tooltip />
            <el-table-column prop="calls" label="调用数" width="80" align="center" />
            <el-table-column prop="tokenIn" label="入 token" width="100" align="right" />
            <el-table-column prop="tokenOut" label="出 token" width="100" align="right" />
            <el-table-column prop="failed" label="失败" width="70" align="center" />
          </el-table>
          <div v-if="(state.chat?.speakers || []).length" class="trace-jump-title">群聊发言者分布</div>
          <div v-if="(state.chat?.speakers || []).length" class="cmd-row">
            <el-tag v-for="s in state.chat.speakers" :key="s.npcRef" size="small" effect="plain" class="filter">{{ s.npcName || s.npcRef }} × {{ s.count }}</el-tag>
          </div>
        </template>
        <div v-else class="empty">未拉取（或 test.enabled=false 403）</div>
      </el-card>

      <!-- ==================== ④ 评估批跑（P2-1 LLM-as-Judge） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            ④ 评估批跑（POST /test/eval-runs：LLM-as-Judge 五维 1-5 绝对评分，云端 deepseek-v4-flash）
          </div>
        </template>
        <div class="preview-box">
          <div class="cmd-row">
            <span class="dim">样本源：</span>
            <el-radio-group v-model="evalSource" size="small">
              <el-radio-button label="db">DB 采样</el-radio-button>
              <el-radio-button label="jsonl">JSONL 导入（v2 长跑捕获）</el-radio-button>
            </el-radio-group>
            <el-input-number v-model="evalCount" :min="1" :max="100" size="small" controls-position="right" class="filter" style="width: 110px" placeholder="样本数" />
            <el-input-number v-model="evalWindow" :min="60" :max="10080" size="small" controls-position="right" class="filter" style="width: 130px" placeholder="游戏分钟窗" />
            <el-input v-model="evalNpcRef" size="small" placeholder="角色过滤（可空）" clearable class="filter" style="width: 130px" />
            <el-input v-model="evalJsonl" size="small" placeholder="ai-calls.jsonl 绝对路径（jsonl 必填）" clearable class="filter" style="width: 320px" />
            <el-button size="small" type="primary" @click="startEval">启动批跑</el-button>
            <span class="dim">（关联 run：{{ activeRunId ? `#${activeRunId}` : '未选（eval_ref 跳过）' }}）</span>
          </div>
        </div>
        <el-table :data="evalRuns" size="small" max-height="220" empty-text="暂无评估批次" row-key="id" @row-click="loadEvalDetail">
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="sampleSource" label="来源" width="70" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'done' ? 'success' : row.status === 'running' || row.status === 'pending' ? 'warning' : 'danger'" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sampleCount" label="样本" width="70" align="center" />
          <el-table-column prop="scoredCount" label="已评分" width="80" align="center" />
          <el-table-column prop="judgeModel" label="judge 模型" width="150" show-overflow-tooltip />
          <el-table-column label="时间" width="150"><template #default="{ row }"><span class="dim mono">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        </el-table>
        <div v-if="evalDetail" class="detail-wrap">
          <div class="trace-jump-title">批次 #{{ evalDetail.run?.id }} 汇总</div>
          <pre class="trace-json">{{ pretty(evalDetail.run?.summaryJson) }}</pre>
          <div class="trace-jump-title">逐样本评分</div>
          <el-table :data="evalDetail.samples || []" size="small" max-height="300" row-key="id">
            <el-table-column prop="seq" label="#" width="50" align="center" />
            <el-table-column prop="npcName" label="角色" width="110" show-overflow-tooltip />
            <el-table-column prop="scene" label="场景" width="150" show-overflow-tooltip />
            <el-table-column label="总分" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="scoreTag(scoreOf(row.id)?.totalScore)" effect="plain">{{ scoreOf(row.id)?.totalScore ?? '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="五维" min-width="260">
              <template #default="{ row }">
                <el-tag v-for="(v, k) in dimsOf(row.id)" :key="k" size="small" effect="plain" class="filter">{{ dimLabel(k) }} {{ v }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="刺激" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ short(row.stimulusText, 60) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>

      <!-- ==================== ⑤ test_run_log 回放 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">⑤ test_run_log 完整回放（GET /test/runs/{id}：命令/干预/快照时间线）</div>
        </template>
        <el-table :data="runs" size="small" max-height="240" empty-text="暂无 test run" row-key="id" @row-click="loadRunDetail">
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column prop="name" label="名称" width="160" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : row.status === 'finished' ? 'info' : 'warning'" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="commandCount" label="命令" width="70" align="center" />
          <el-table-column prop="interveneCount" label="干预" width="70" align="center" />
          <el-table-column label="起→止游戏位置" min-width="200">
            <template #default="{ row }">{{ gameSecondText(row.startGameSecond) }} → {{ gameSecondText(row.endGameSecond) }}</template>
          </el-table-column>
          <el-table-column prop="evalRunIdsJson" label="评估批次" width="120" show-overflow-tooltip />
          <el-table-column label="时间" width="150"><template #default="{ row }"><span class="dim mono">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        </el-table>
        <div v-if="runDetail" class="detail-wrap">
          <div class="trace-jump-title">run #{{ runDetail.run?.id }} 汇总</div>
          <pre class="trace-json">{{ pretty(runDetail.run?.summaryJson) }}</pre>
          <div class="trace-jump-title">时间线（{{ (runDetail.events || []).length }} 条）</div>
          <el-timeline v-if="(runDetail.events || []).length">
            <el-timeline-item v-for="ev in runDetail.events" :key="ev.id" :type="evTypeTag(ev.eventType)" size="small">
              <div class="ev-row">
                <el-tag size="small" effect="plain" :type="evTypeTag(ev.eventType)">{{ ev.eventType }}</el-tag>
                <span class="mono ev-action">{{ ev.action }}</span>
                <span class="dim mono">{{ gameSecondText(ev.gameSecond) }}</span>
                <span class="dim mono">{{ fmtTime(ev.createdAt) }}</span>
              </div>
              <pre v-if="ev.paramsJson && ev.paramsJson !== '{}'" class="trace-json ev-json">params: {{ ev.paramsJson }}</pre>
              <pre v-if="ev.resultJson" class="trace-json ev-json">result: {{ pretty(ev.resultJson) }}</pre>
              <pre v-if="ev.stateSnapshotJson" class="trace-json ev-json">snapshot: {{ short(ev.stateSnapshotJson, 400) }}</pre>
            </el-timeline-item>
          </el-timeline>
          <div v-else class="empty">无时间线事件</div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get, post } from '../api'

const projects = ref([])
const projectId = ref(null)
const testEnabledMsg = ref(null)

// ① 阶段控制
const activeRunId = ref(null)
const newRunName = ref('')
const newRunPurpose = ref('')
const cmdTab = ref('phase')
const ffPlusDays = ref(0)
const ffPlusHours = ref(0)
const ffPlusMinutes = ref(0)
const ffTarget = ref('')
const intensity = ref('full')
const lastResult = ref(null)

// ③ 干预驱动
const evContent = ref('')
const evTitle = ref('')
const evKind = ref('')
const actNpcRef = ref('')
const actReason = ref('')
const chatLocation = ref('')

// ② 状态聚合
const stateWindow = ref(1440)
const state = ref(null)

// ④ 评估批跑
const evalSource = ref('db')
const evalCount = ref(20)
const evalWindow = ref(1440)
const evalNpcRef = ref('')
const evalJsonl = ref('')
const evalRuns = ref([])
const evalDetail = ref(null)

// ⑤ test_run_log
const runs = ref([])
const runDetail = ref(null)

async function loadProjects() {
  try {
    const r = await get('/projects')
    projects.value = Array.isArray(r) ? r : (r?.data || [])
    if (projects.value.length && !projectId.value) projectId.value = projects.value[0]?.id
    if (projectId.value) onProjectChange()
  } catch { projects.value = [] }
}

function onProjectChange() {
  activeRunId.value = null
  runDetail.value = null
  evalDetail.value = null
  state.value = null
  if (projectId.value) reloadAll()
}

async function reloadAll() {
  testEnabledMsg.value = null
  await Promise.all([loadRuns(), loadEvalRuns(), loadState(), probeGate()])
}

/** 探测 test.enabled 开关（403 → 提示）。 */
async function probeGate() {
  try {
    await get(`/projects/${projectId.value}/test/runs`)
    testEnabledMsg.value = { ok: true, text: 'test.enabled=true' }
  } catch (e) {
    testEnabledMsg.value = { ok: false, text: 'test.enabled=false（接口 403）' }
  }
}

/** 解包 R 包装（{code,data,message}）或直出（调试端点双兼容）。 */
function unwrap(v, fallback) {
  if (v === null || v === undefined) return fallback
  return v?.data !== undefined ? v.data : v
}

// ==================== ⑤ test_run_log ====================

async function createRun() {
  try {
    const r = await post(`/projects/${projectId.value}/test/runs`, { name: newRunName.value, purpose: newRunPurpose.value })
    const run = unwrap(r)
    newRunName.value = ''
    newRunPurpose.value = ''
    activeRunId.value = run?.id ?? null
    await loadRuns()
  } catch (e) { lastResult.value = { error: e.message } }
}

async function finishRun() {
  if (!activeRunId.value) return
  try {
    const r = await post(`/projects/${projectId.value}/test/runs/${activeRunId.value}/finish`, {})
    lastResult.value = unwrap(r)
    await loadRuns()
  } catch (e) { lastResult.value = { error: e.message } }
}

async function loadRuns() {
  if (!projectId.value) return
  try {
    runs.value = unwrap(await get(`/projects/${projectId.value}/test/runs`), [])
  } catch { runs.value = [] }
}

async function loadRunDetail(row) {
  try {
    runDetail.value = unwrap(await get(`/projects/${projectId.value}/test/runs/${row.id || row}`), null)
  } catch (e) { runDetail.value = { error: e.message } }
}

// ==================== ① 阶段控制 + ③ 干预驱动 ====================

async function cmd(action, params) {
  if (!activeRunId.value) return
  try {
    const r = await post(`/projects/${projectId.value}/test/runs/${activeRunId.value}/command`, { action, params })
    lastResult.value = unwrap(r)
    await loadState()
    await loadRuns()
  } catch (e) { lastResult.value = { error: e.message } }
}

async function cmdFastForward() {
  const params = { plusGameDays: ffPlusDays.value || 0, plusGameHours: ffPlusHours.value || 0, plusGameMinutes: ffPlusMinutes.value || 0 }
  if (!(params.plusGameDays || params.plusGameHours || params.plusGameMinutes)) return
  await cmd('phase_fastforward', params)
}

async function cmdFastForwardTarget() {
  const t = Number(ffTarget.value)
  if (!t) return
  await cmd('phase_fastforward', { targetGameSecond: t })
}

async function cmdIntensity(level) {
  await cmd('phase_intensity', { level })
}

async function cmdInterveneEvent() {
  const params = { content: evContent.value }
  if (evTitle.value) params.title = evTitle.value
  if (evKind.value) params.kind = evKind.value
  await cmd('intervene_event', params)
}

async function cmdInterveneAction() {
  const params = { npcRef: actNpcRef.value }
  if (actReason.value) params.reason = actReason.value
  await cmd('intervene_action', params)
}

async function cmdTriggerChat() {
  const params = {}
  if (chatLocation.value) params.locationName = chatLocation.value
  await cmd('trigger_chat', params)
}

// ==================== ② 状态聚合 ====================

async function loadState() {
  if (!projectId.value) return
  try {
    state.value = unwrap(await get(`/projects/${projectId.value}/test/state`, { windowGameMinutes: stateWindow.value }), null)
  } catch (e) {
    state.value = null
    if (e.message?.includes('403')) testEnabledMsg.value = { ok: false, text: 'test.enabled=false（接口 403）' }
  }
}

// ==================== ④ 评估批跑 ====================

async function startEval() {
  try {
    const body = {
      source: evalSource.value,
      sampleCount: evalCount.value || 20,
      windowGameMinutes: evalWindow.value || 1440
    }
    if (evalNpcRef.value) body.npcRef = evalNpcRef.value
    if (evalSource.value === 'jsonl') body.jsonlPath = evalJsonl.value
    if (activeRunId.value) body.runId = activeRunId.value
    const r = await post(`/projects/${projectId.value}/test/eval-runs`, body)
    lastResult.value = unwrap(r)
    await loadEvalRuns()
  } catch (e) { lastResult.value = { error: e.message } }
}

async function loadEvalRuns() {
  if (!projectId.value) return
  try {
    evalRuns.value = unwrap(await get(`/projects/${projectId.value}/test/eval-runs`), [])
  } catch { evalRuns.value = [] }
}

async function loadEvalDetail(row) {
  try {
    evalDetail.value = unwrap(await get(`/projects/${projectId.value}/test/eval-runs/${row.id || row}`), null)
  } catch (e) { evalDetail.value = { error: e.message } }
}

function scoreOf(sampleId) {
  return (evalDetail.value?.scores || []).find(s => s.sampleId === sampleId) || null
}

function dimsOf(sampleId) {
  const sc = scoreOf(sampleId)
  if (!sc?.scoresJson) return {}
  try { return JSON.parse(sc.scoresJson) } catch { return {} }
}

// ==================== 工具 ====================

function gameSecondText(gs) {
  if (gs == null) return '—'
  const day = Math.floor(gs / 86400)
  const hour = Math.floor((gs % 86400) / 3600)
  const min = Math.floor((gs % 3600) / 60)
  return `D${day} ${String(hour).padStart(2, '0')}:${String(min).padStart(2, '0')}`
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
function short(s, n) {
  if (!s) return '—'
  const t = String(s).replace(/\s+/g, ' ')
  return t.length > n ? t.slice(0, n) + '…' : t
}
function pretty(s) {
  if (!s) return '（无）'
  if (typeof s !== 'string') { try { return JSON.stringify(s, null, 2) } catch { return String(s) } }
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}
function evTypeTag(t) {
  return { command: 'primary', intervene: 'warning', snapshot: 'success', eval_ref: 'info' }[t] || 'info'
}
function scoreTag(v) {
  if (v == null) return 'info'
  return v >= 40 ? 'success' : v >= 30 ? 'warning' : 'danger'
}
function dimLabel(k) {
  return { consistency: '一致性', reasonability: '合理性', context_usage: '情境利用', foresight: '前瞻性', naturalness: '自然度' }[k] || k
}

onMounted(loadProjects)
</script>

<style scoped>
.head-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.proj-select { width: 200px; }
.hint { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
.card { margin-bottom: 14px; }
.card-title { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.filter { margin-left: 8px; }
.cmd-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
.empty { color: var(--text-tertiary, #8a8a8a); font-size: 12px; padding: 8px 0; }
.detail-wrap { padding: 4px 8px 10px; }
.preview-box { padding: 10px; background: var(--bg-layer-1, #fafafa); border: 1px dashed var(--border-l2, #e0e0e0); border-radius: 6px; margin-bottom: 10px; }
.preview-result { margin-top: 8px; }
.trace-jump-title { font-size: 12px; color: var(--text-secondary, #666); margin: 8px 0 4px; font-weight: 600; }
.trace-json { background: var(--bg-layer-1, #f5f5f5); border: 1px solid var(--border-l2, #e8e8e8); border-radius: 6px; padding: 8px 10px; font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0 0 6px; }
.ev-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ev-action { font-weight: 600; }
.ev-json { margin-top: 4px; }
.state-desc { margin-bottom: 8px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.dim { color: var(--text-tertiary, #8a8a8a); font-size: 12px; }
</style>
