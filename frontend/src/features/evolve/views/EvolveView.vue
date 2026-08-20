<template>
  <div class="evolve-view">
    <!-- 左栏：时间线缩略 + 演化会话列表 -->
    <div class="ev-side">
      <div class="side-header">
        <span>时间线</span>
        <el-button size="small" text type="primary" @click="$router.push(`/project/${projectId}/timeline`)">完整 →</el-button>
      </div>
      <div v-if="timelineItems.length === 0" class="side-empty">暂无时间线节点</div>
      <div v-else class="tl-thumb">
        <div class="tl-axis">
          <div class="tl-now-line">
            <span class="tl-now-label">{{ clock ? clock.gameTimeText || `${clock.periodText}·第${clock.day}日` : '现在' }}</span>
          </div>
          <div v-for="(n, i) in timelineItems" :key="i" class="tl-thumb-node" :class="`kind-${n.kind}`" @click="$router.push(`/project/${projectId}/timeline`)">
            <span class="tl-thumb-dot"></span>
            <span class="tl-thumb-text" :title="n.title || n.text || '事件'">
              <span v-if="n.gameTime" class="tl-thumb-time">{{ (n.gameTime + '').replace(/历\s/, '历 ') }}</span>
              {{ (n.title || n.text || '事件').slice(0, 14) }}
            </span>
          </div>
        </div>
      </div>
      <el-divider />
      <div class="side-header">
        <span>演化会话</span>
        <el-button size="small" text type="primary" @click="openStartDialog"><el-icon><HIcon name="Plus" /></el-icon>开始</el-button>
      </div>
      <div v-if="evolutions.length === 0" class="side-empty">暂无演化会话，点击「开始」创建</div>
      <div v-else class="ev-list">
        <div v-for="ev in evolutions" :key="ev.id" class="ev-item" :class="{ active: current?.id === ev.id }" @click="selectEvolution(ev)">
          <div class="ev-item-title">{{ ev.title || '演化' }}</div>
          <div class="ev-item-sub">
            <el-tag size="small" :type="ev.status === 'running' ? 'success' : 'info'" effect="plain">{{ ev.status === 'running' ? '进行中' : '已归档' }}</el-tag>
            <span class="ev-item-meta">{{ ev.sceneName }} · {{ ev.turnCount }} 轮</span>
            <el-button class="ev-item-del" size="small" text type="danger" :title="'删除演化会话「' + (ev.title || '演化') + '」'" @click.stop="doDeleteEvolution(ev)">
              <el-icon><HIcon name="Delete" /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 中栏：演化控制台 -->
    <div class="ev-main">
      <!-- 世界时钟控制条 -->
      <div class="clock-bar" v-if="clock">
        <span class="clock-time" :title="'暂停时刻记录：' + (clock.pausedAt ? (clock.pausedAt + '').slice(0, 19).replace('T', ' ') : '—')">
          🗓 {{ clock.gameTimeText || (clock.periodText + ' · 第' + clock.day + '日') }}
        </span>
        <span class="clock-rate">速率×{{ clock.rate }}</span>
        <el-input-number v-model="rateInput" :min="1" :max="240" size="small" :controls="false" style="width: 72px" @change="saveRate" />
        <el-button size="small" :type="clock.paused ? 'success' : 'warning'" text @click="togglePause">
          {{ clock.paused ? '▶ 恢复' : '⏸ 暂停' }}
        </el-button>
        <el-button size="small" type="primary" text :loading="advancing" @click="doAdvanceClock">⏩ 补推</el-button>
      </div>

      <!-- 当前演化控制区 -->
      <div v-if="current" class="ev-control">
        <div class="ev-control-head">
          <span class="ev-title">{{ current.title || '演化' }}</span>
          <el-tag size="small" :type="current.status === 'running' ? 'success' : 'info'" effect="light">
            {{ current.status === 'running' ? '进行中' : '已结束' }}
          </el-tag>
          <el-tag size="small" type="info" effect="plain">{{ current.sceneName }}</el-tag>
          <span class="ev-turn-count">已推进 {{ current.turnCount || 0 }} 节拍</span>
        </div>
        <div v-if="current.status === 'running'" class="ev-control-ops">
          <!-- 连续演化播放控制（群聊式：持续调度选人发言/行动，直到手动停止或场景只剩 1 人） -->
          <el-button v-if="!streaming" type="primary" size="small" @click="startPlayback">
            <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;开始播放
          </el-button>
          <el-button v-else type="danger" size="small" @click="stopPlayback">
            <el-icon><HIcon name="VideoPause" /></el-icon>&nbsp;停止播放
          </el-button>
          <span class="ev-stream-hint">{{ streaming ? '⏳ 连续演化播放中…' : '点击「开始播放」持续推演（调度选人 + 发言/行动）；场景只剩 1 人时自动收尾' }}</span>
          <el-select v-model="joinCharId" size="small" clearable placeholder="加入角色" style="width: 150px">
            <el-option v-for="ch in notInSceneChars" :key="ch.id" :label="ch.name" :value="ch.id" />
          </el-select>
          <el-button size="small" :disabled="!joinCharId" @click="doJoin">加入</el-button>
          <el-select v-model="leaveCharId" size="small" clearable placeholder="退场角色" style="width: 150px">
            <el-option v-for="p in activeParticipants" :key="p.characterId" :label="p.name" :value="p.characterId" />
          </el-select>
          <el-button size="small" type="warning" :disabled="!leaveCharId" @click="doLeave">退场</el-button>
          <el-button size="small" type="danger" plain :loading="finishing" @click="doFinish">结束并归档</el-button>
        </div>
        <div v-else-if="current.aiSummary" class="ev-archive-alert">
          <el-alert type="success" :closable="false" show-icon :title="'已归档为事件：' + (archiveEventTitle || '已归档')"
            :description="current.aiSummary" />
        </div>
      </div>
      <div v-else class="ev-control-empty">
        <el-empty description="还没有演化会话">
          <el-button type="primary" size="large" @click="openStartDialog">
            <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;开始世界演化
          </el-button>
          <div class="ev-start-hint">或点击左上角「开始」；可先「场景管理 → AI 自动填充」准备地点</div>
        </el-empty>
      </div>

      <!-- 演化消息流 -->
      <div class="ev-stream" ref="streamRef">
        <div v-if="!current || turns.length === 0" class="stream-empty">控制台已就绪——创建演化后点击「开始播放」持续推演，观察调度与角色言行。</div>
        <div v-for="t in turns" :key="t.id" class="stream-item" :class="t.type">
          <template v-if="t.type === 'system'">
            <div class="system-card"><span class="system-label">【系统】</span><span class="system-text">{{ t.content }}</span></div>
          </template>
          <template v-else-if="t.type === 'action'">
            <div class="action-row"><span class="action-name">{{ t.characterName }}</span><span class="action-text">{{ t.content }}</span></div>
          </template>
          <template v-else-if="t.type === 'schedule'">
            <div class="schedule-row"><span class="schedule-label">🎬 调度</span><span class="schedule-text">{{ t.content }}</span></div>
          </template>
          <template v-else>
            <div class="dialog-row"><span class="dialog-name">{{ t.characterName }}</span><span class="dialog-text">{{ t.content }}<span v-if="t.streaming" class="cursor">▍</span></span></div>
          </template>
        </div>
      </div>

      <!-- 自动收尾状态 -->
      <div class="auto-state" v-if="current">
        <span class="as-label">连续演化</span>
        <el-tag size="small" :type="current.status === 'running' ? 'success' : 'info'" effect="light">
          {{ current.status === 'running' ? '进行中' : '已收尾' }}
        </el-tag>
        <span class="as-summary">调度选人持续推演 · 场景只剩 1 人自动收尾归档；可随时「停止播放」或「结束并归档」</span>
      </div>
    </div>

    <!-- 右栏：场景角色信息 -->
    <div class="ev-info">
      <div class="info-header">
        <span>场景角色</span>
        <el-button size="small" text type="primary" @click="openSceneDialog"><el-icon><HIcon name="Location" /></el-icon>&nbsp;场景管理</el-button>
      </div>
      <div v-if="activeParticipants.length === 0" class="side-empty">当前场景无角色</div>
      <template v-else>
        <div v-for="p in activeParticipants" :key="p.characterId" class="scene-role" :class="{ active: sceneRoleId === p.characterId }" @click="selectSceneChar(p.characterId)">
          <el-avatar :size="32" class="role-avatar">{{ p.name?.charAt(0) }}</el-avatar>
          <div class="role-meta">
            <div class="role-name">{{ p.name }}</div>
            <div class="role-title">在场</div>
          </div>
        </div>
      </template>
      <el-divider />
      <div v-if="!sceneRole" class="side-empty">选择角色查看角色卡 / Prompt / 记忆</div>
      <template v-else>
        <div class="scene-role-head">
          <el-avatar :size="36" class="role-avatar">{{ sceneRole.name?.charAt(0) }}</el-avatar>
          <div class="role-meta">
            <div class="role-name">{{ sceneRole.name }}</div>
            <div class="role-title">{{ sceneRole.title || '—' }}</div>
          </div>
        </div>
        <el-tabs v-model="infoTab" class="info-tabs">
          <el-tab-pane v-if="roleCard" label="角色卡" name="card"><pre class="info-pre">{{ prettyJson(roleCard.personaJson) }}</pre></el-tab-pane>
          <el-tab-pane v-if="roleCard" label="Prompt" name="prompt"><pre class="info-pre">{{ roleCard.systemPrompt }}</pre></el-tab-pane>
          <el-tab-pane label="记忆" name="memory">
            <div v-if="memories.length === 0" class="side-empty">暂无记忆</div>
            <div v-for="m in memories" :key="m.id" class="mem-item">
              <div class="mem-head">
                <el-tag size="small" :type="m.kind === 'summary' ? 'primary' : 'info'" effect="plain">{{ m.kind === 'summary' ? '摘要' : '事实' }}</el-tag>
                <el-tag size="small" type="info" effect="light">重要度 {{ m.importance }}</el-tag>
              </div>
              <div class="mem-content">{{ m.content }}</div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <!-- 开始演化对话框 -->
    <el-dialog v-model="startDialog.visible" title="开始世界演化" width="640px">
      <el-form label-width="90px">
        <el-form-item label="选择方式">
          <el-radio-group v-model="startDialog.mode">
            <el-radio-button value="manual">手动选择</el-radio-button>
            <el-radio-button value="ai">AI 自动选择</el-radio-button>
          </el-radio-group>
          <span class="form-tip">AI 模式由 AI 根据世界观/角色自动选场景、背景与角色</span>
        </el-form-item>
        <el-form-item label="场景/地点">
          <el-select v-model="startDialog.sceneId" clearable placeholder="不选 = 全局（世界）演化" style="width: 100%">
            <el-option label="全局（世界）" :value="null" />
            <el-option v-for="s in scenes" :key="s.id" :label="s.name + (s.description ? '：' + s.description : '')" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="演化背景">
          <el-input v-model="startDialog.background" type="textarea" :rows="2" placeholder="选填：为这场演化设定情境（时间/氛围/为什么这些人在这里）" />
        </el-form-item>
        <el-form-item label="参与角色">
          <el-select v-model="startDialog.characterIds" multiple collapse-tags :disabled="startDialog.mode === 'ai'" placeholder="AI 模式自动选择；手动模式至少选 1 位" style="width: 100%">
            <el-option v-for="ch in characters" :key="ch.id" :label="ch.name + (ch.title ? ' · ' + ch.title : '')" :value="ch.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="starting" @click="doStart">开始演化</el-button>
      </template>
    </el-dialog>

    <!-- 场景管理对话框 -->
    <el-dialog v-model="sceneDialog.visible" title="场景管理（地点）" width="760px">
      <div class="scene-toolbar">
        <el-button size="small" type="primary" @click="openSceneCreate"><el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增场景</el-button>
        <el-button size="small" type="warning" :loading="sceneAiDialog.generating" @click="openSceneAiGen" title="AI 基于世界观+角色详情自动设计场景（含来源依据、逻辑自洽）">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 自动填充
        </el-button>
      </div>
      <el-table :data="scenes" size="small" border>
        <el-table-column prop="name" label="名称" min-width="110" />
        <el-table-column prop="location" label="地点" width="90" />
        <el-table-column prop="description" label="描述" min-width="130" show-overflow-tooltip />
        <el-table-column prop="background" label="背景" min-width="140" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.source" class="scene-source">{{ row.source }}</span>
            <span v-else class="scene-source-empty">手动创建</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="60">
          <template #default="{ row }"><el-tag size="small" :type="row.enabled === 1 ? 'success' : 'info'" effect="plain">{{ row.enabled === 1 ? '是' : '否' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openSceneEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="doDeleteScene(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 场景 AI 自动填充对话框 -->
    <el-dialog v-model="sceneAiDialog.visible" title="AI 自动填充场景" width="560px">
      <div class="scene-ai-desc">
        AI 将基于【世界观设定 + 角色档案】自动设计一批场景（地点），每个场景都带有<b>来源依据</b>
        （取自世界观/角色的哪部分设定），保证逻辑自洽、与已有场景不重复。生成后可手动编辑调整。
      </div>
      <el-form label-width="90px">
        <el-form-item label="生成数量">
          <el-input-number v-model="sceneAiDialog.count" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sceneAiDialog.visible = false">取消</el-button>
        <el-button type="warning" :loading="sceneAiDialog.generating" @click="doSceneAiGen">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 场景编辑对话框 -->
    <el-dialog v-model="sceneForm.visible" :title="sceneForm.id ? '编辑场景' : '新增场景'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="名称" required><el-input v-model="sceneForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="sceneForm.location" maxlength="200" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="sceneForm.description" maxlength="500" /></el-form-item>
        <el-form-item label="背景设定"><el-input v-model="sceneForm.background" type="textarea" :rows="4" placeholder="场景环境/氛围/默认在场人员等（世界演化 AI 注入用）" /></el-form-item>
        <el-form-item label="来源依据"><el-input v-model="sceneForm.source" type="textarea" :rows="2" placeholder="可选：记录该场景的依据（如取自世界观/角色的哪些设定）；AI 自动填充时自动填写" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="sceneForm.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sceneForm.visible = false">取消</el-button>
        <el-button type="primary" :loading="sceneForm.loading" @click="doSaveScene">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 世界演化页（前端布局重构 V1.0 §3.7 + V2.1 世界演化增强 + vP5-7.9 群聊式连续演化）。
 * <p>职责：世界模拟控制台（三栏）——
 * 左栏：时间线缩略 + 演化会话列表；中栏：世界时钟控制 + 演化控制区（开始/播放/加入/退场/结束归档）+
 * 演化消息流（调度/系统环境变化/角色行动/角色对话）+ 自动收尾状态；右栏：场景角色信息。
 * 演化能力：手动选择全局/场景/背景/角色 或 AI 自动选择；<b>连续演化</b>（vP5-7.9）——
 * 与群聊运行逻辑一致：AI 逐拍调度选出最有发言/行动欲望的角色 → 该角色流式发言或行动（结合场景环境），
 * 不按轮次计算，直到用户手动停止或场景只剩 1 人自动收尾归档（事件入时间线 + 角色级记忆隔离）。
 * 保留单轮推进接口（/turn）供兼容/调试。</p>
 * <p>数据来源：world-clock、scenes、evolutions、characters 等现有 + V2.1 新接口 + stream SSE。</p>
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchWorldClock, updateWorldClock, advanceWorldClock,
  fetchCharacters, fetchCharacterCard, fetchMemories, fetchProjectTimeline,
  fetchScenes, createScene, updateScene, deleteScene, aiGenerateScenes,
  fetchEvolutions, startEvolution, fetchEvolutionDetail,
  joinEvolution, leaveEvolution, finishEvolution, deleteEvolution
} from '@/shared/api'

const route = useRoute()
const projectId = Number(route.params.id)

// ===== 左栏 =====
const timelineItems = ref([])
const evolutions = ref([])
const current = ref(null)

// ===== 中栏：时钟 =====
const clock = ref(null)
const rateInput = ref(24)
const advancing = ref(false)

// ===== 中栏：演化控制 =====
const turns = ref([])
const activeParticipants = ref([])
const joinCharId = ref(null)
const leaveCharId = ref(null)
const finishing = ref(false)
const archiveEventTitle = ref('')

// ===== 连续演化播放（SSE 流式，vP5-7.9 群聊式） =====
const streaming = ref(false)
let es = null

// ===== 中栏：消息流 =====
const streamRef = ref(null)

// ===== 右栏：场景角色 =====
const characters = ref([])
const sceneRoleId = ref(null)
const sceneRole = ref(null)
const roleCard = ref(null)
const memories = ref([])
const infoTab = ref('card')

// ===== 开始演化 =====
const startDialog = reactive({ visible: false, mode: 'manual', sceneId: null, background: '', characterIds: [] })
const starting = ref(false)

// ===== 场景管理 =====
const scenes = ref([])
const sceneDialog = reactive({ visible: false })
const sceneForm = reactive({ visible: false, loading: false, id: null, name: '', location: '', description: '', background: '', source: '', enabled: 1 })
// AI 自动填充场景（vP5-7.6）
const sceneAiDialog = reactive({ visible: false, generating: false, count: 3 })

/** 不在场景中的角色（可加入候选） */
const notInSceneChars = computed(() => {
  const inIds = new Set(activeParticipants.value.map(p => p.characterId))
  return characters.value.filter(c => !inIds.has(c.id))
})

/** 消息流滚动到底部 */
function scrollStream() {
  nextTick(() => { streamRef.value?.scrollTo({ top: streamRef.value.scrollHeight }) })
}

/** 加载基础数据 */
async function loadBase() {
  try { clock.value = await fetchWorldClock(projectId); rateInput.value = clock.value?.rate || 24 } catch (_) { /* 忽略 */ }
  try { characters.value = await fetchCharacters(projectId) } catch (_) { characters.value = [] }
  try { scenes.value = await fetchScenes(projectId) } catch (_) { scenes.value = [] }
  try {
    const tl = await fetchProjectTimeline(projectId, {})
    timelineItems.value = (tl || []).slice(0, 5)
  } catch (_) { timelineItems.value = [] }
  await loadEvolutions()
}

/** 加载演化会话列表 */
async function loadEvolutions() {
  try {
    evolutions.value = await fetchEvolutions(projectId)
    if (current.value) {
      const fresh = evolutions.value.find(e => e.id === current.value.id)
      if (fresh) current.value = fresh
    }
  } catch (_) { evolutions.value = [] }
}

/** 选择演化会话 */
async function selectEvolution(ev) {
  closePlayback()
  current.value = ev
  joinCharId.value = null
  leaveCharId.value = null
  archiveEventTitle.value = ''
  await loadDetail(ev.id)
}

/** 加载演化详情（turns + 参与者） */
async function loadDetail(id) {
  try {
    const detail = await fetchEvolutionDetail(id)
    current.value = detail
    turns.value = detail.turns || []
    activeParticipants.value = (detail.participants || []).filter(p => p.status === 'active')
    scrollStream()
  } catch (e) { ElMessage.error(e.message || '加载演化失败') }
}

/** 打开开始演化对话框 */
function openStartDialog() {
  Object.assign(startDialog, { visible: true, mode: 'manual', sceneId: null, background: '', characterIds: [] })
}

/** 开始演化 */
async function doStart() {
  if (startDialog.mode === 'manual' && startDialog.characterIds.length === 0) {
    return ElMessage.warning('手动模式请至少选择 1 位参与角色')
  }
  starting.value = true
  try {
    const ev = await startEvolution(projectId, {
      mode: startDialog.mode,
      sceneId: startDialog.sceneId || undefined,
      background: startDialog.background || undefined,
      characterIds: startDialog.mode === 'ai' ? undefined : startDialog.characterIds
    })
    ElMessage.success(`演化「${ev.title || '演化'}」已开始`)
    startDialog.visible = false
    await loadEvolutions()
    await selectEvolution(ev)
    // 创建成功即自动开始连续播放（群聊式推演）
    if (ev.status === 'running') startPlayback()
  } catch (e) { ElMessage.error(e.message || '开始失败') }
  finally { starting.value = false }
}

// ===== 连续演化播放（SSE 流式，vP5-7.9） =====

/** SSE 流式通道 URL（EventSource 消费；不经过 axios 解包） */
function evolutionStreamUrl(id) {
  return `/api/evolutions/${id}/stream`
}

/**
 * 开始连续播放：打开 SSE 流，后端循环「调度选人 → 角色流式发言/行动」，持续到手动停止或场景只剩 1 人。
 * 事件：schedule（调度决策，紧凑显示）/ message-start（角色开始）/ token（增量）/ done（本拍完成）/
 * system（场景变化/加入退场）/ finished（自动收尾归档）/ error。
 */
function startPlayback() {
  if (!current.value || current.value.status !== 'running') return
  if (streaming.value) return
  closePlayback()
  streaming.value = true
  const source = new EventSource(evolutionStreamUrl(current.value.id))
  es = source

  source.addEventListener('schedule', (ev) => {
    const d = JSON.parse(ev.data)
    const name = d.characterId ? charName(d.characterId) : '？'
    const kind = d.beatType === 'action' ? '行动' : '接话'
    turns.value.push({
      id: `sch-${Date.now()}-${Math.random()}`, type: 'schedule', streaming: false,
      content: `「${name}」想${kind}（欲望 ${d.desire}）：${d.reason || ''}`
    })
    scrollStream()
  })
  source.addEventListener('system', (ev) => {
    const d = JSON.parse(ev.data)
    turns.value.push({ id: `sys-${Date.now()}-${Math.random()}`, type: 'system', streaming: false, content: d.content })
    scrollStream()
  })
  source.addEventListener('message-start', (ev) => {
    const d = JSON.parse(ev.data)
    turns.value.push({
      id: `s-${Date.now()}`, type: d.type === 'action' ? 'action' : 'text', streaming: true,
      characterId: d.characterId, characterName: d.characterName, content: ''
    })
    scrollStream()
  })
  source.addEventListener('token', (ev) => {
    const d = JSON.parse(ev.data)
    const row = [...turns.value].reverse().find(t => t.streaming && t.characterId === d.characterId)
    if (row) { row.content += d.delta; scrollStream() }
  })
  source.addEventListener('done', (ev) => {
    const d = JSON.parse(ev.data)
    const row = [...turns.value].reverse().find(t => t.streaming && t.characterId === d.characterId)
    if (row) { row.streaming = false; row.content = d.content || row.content }
    scrollStream()
  })
  source.addEventListener('finished', async (ev) => {
    const d = JSON.parse(ev.data)
    closePlayback()
    archiveEventTitle.value = d.event?.title || ''
    ElMessage.success('场景只剩 1 人，演化已自动收尾并归档（参与者获得角色级记忆）')
    await loadEvolutions()
    await loadDetail(current.value.id)
    await loadTimelineThumb()
  })
  source.addEventListener('error', async (ev) => {
    let msg = '演化播放失败，请重试'
    try { msg = JSON.parse(ev.data).message || msg } catch (_) { /* 忽略 */ }
    closePlayback()
    ElMessage.error(msg)
    await loadDetail(current.value.id)
  })
  source.onerror = () => { /* EventSource 自身错误（连接关闭）已由 done/error 处理 */ }
}

/** 手动停止播放（关闭 SSE，后端循环感知连接断开后停止；演化保持 running 可再次播放） */
function stopPlayback() {
  closePlayback()
  ElMessage.info('已停止播放，演化保持进行中（可随时再次「开始播放」）')
}

/** 关闭当前 SSE 流 */
function closePlayback() {
  if (es) { es.close(); es = null }
  streaming.value = false
}

/** 手动加入角色 */
async function doJoin() {
  if (!joinCharId.value) return
  try {
    await joinEvolution(current.value.id, joinCharId.value)
    ElMessage.success('角色已加入')
    joinCharId.value = null
    await loadDetail(current.value.id)
  } catch (e) { ElMessage.error(e.message || '加入失败') }
}

/** 手动退场角色 */
async function doLeave() {
  if (!leaveCharId.value) return
  try {
    await leaveEvolution(current.value.id, leaveCharId.value)
    ElMessage.success('角色已退场')
    leaveCharId.value = null
    await loadDetail(current.value.id)
  } catch (e) { ElMessage.error(e.message || '退场失败') }
}

/** 结束并归档 */
async function doFinish() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('确认结束这场演化并归档为事件？参与者将获得该事件的角色级记忆（仅当事人知晓）。', '结束确认', { type: 'warning' })
  } catch (_) { return }
  finishing.value = true
  try {
    const res = await finishEvolution(current.value.id)
    archiveEventTitle.value = res.event?.title || ''
    ElMessage.success(`已归档：${res.event?.title || '世界演化'}（${res.memoryCount} 条角色级记忆，仅当事人）`)
    await loadEvolutions()
    await loadDetail(current.value.id)
    await loadTimelineThumb()
  } catch (e) { ElMessage.error(e.message || '归档失败') }
  finally { finishing.value = false }
}

/** 删除演化会话（vP5-7.11：级联清理参与者与轮次消息；归档事件保留在时间线） */
async function doDeleteEvolution(ev) {
  try {
    await ElMessageBox.confirm(`确认删除演化会话「${ev.title || '演化'}」？相关参与者与轮次消息将一并删除（已归档事件保留在时间线）。`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteEvolution(ev.id)
    ElMessage.success('演化会话已删除')
    if (current.value?.id === ev.id) {
      closePlayback()
      current.value = null
      turns.value = []
      activeParticipants.value = []
    }
    await loadEvolutions()
    await loadTimelineThumb()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

/** 刷新时间线缩略 */
async function loadTimelineThumb() {
  try {
    const tl = await fetchProjectTimeline(projectId, {})
    timelineItems.value = (tl || []).slice(0, 5)
  } catch (_) { /* 忽略 */ }
}

/** 角色名 */
function charName(id) {
  const ch = characters.value.find(c => c.id === id)
  return ch ? ch.name : '角色'
}

// ===== 世界时钟 =====
async function saveRate(val) {
  try {
    clock.value = await updateWorldClock(projectId, { rate: val })
    ElMessage.success(`速率已设为 ×${clock.value.rate}`)
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}
/**
 * 切换世界模拟暂停/恢复。
 * <p>恢复时立即补推世界一轮（角色/人群/事件推进有摘要输出），避免「点了恢复没反应」；
 * 演化推演由「开始播放」SSE 流驱动，与时钟暂停互不干扰。</p>
 */
async function togglePause() {
  if (!clock.value) return
  const wasPaused = clock.value.paused
  try {
    clock.value = await updateWorldClock(projectId, { paused: !clock.value.paused })
  } catch (e) { ElMessage.error(e.message || '操作失败'); return }
  if (wasPaused) {
    ElMessage.success('世界已恢复')
    await doAdvanceClock()
  } else {
    ElMessage.success('世界已暂停')
  }
}

async function doAdvanceClock() {
  advancing.value = true
  try {
    const res = await advanceWorldClock(projectId)
    ElMessage.success(res.summary || `已推进 ${res.advancedHours} 游戏小时`)
    clock.value = await fetchWorldClock(projectId)
    await loadTimelineThumb()
  } catch (e) { ElMessage.error(e.message || '推进失败') }
  finally { advancing.value = false }
}

// ===== 右栏场景角色 =====
async function selectSceneChar(cid) {
  sceneRoleId.value = cid
  sceneRole.value = characters.value.find(c => c.id === cid) || null
  roleCard.value = null
  memories.value = []
  infoTab.value = 'card'
  if (!sceneRole.value) return
  try { roleCard.value = await fetchCharacterCard(cid) } catch (_) { roleCard.value = null }
  try {
    const page = await fetchMemories(projectId, { characterId: cid, page: 1, size: 20 })
    memories.value = page.list || []
  } catch (_) { memories.value = [] }
}

// ===== 场景管理 =====
function openSceneDialog() { sceneDialog.visible = true; loadScenes() }
async function loadScenes() {
  try { scenes.value = await fetchScenes(projectId) } catch (_) { scenes.value = [] }
}
function openSceneCreate() {
  Object.assign(sceneForm, { visible: true, loading: false, id: null, name: '', location: '', description: '', background: '', source: '', enabled: 1 })
}
function openSceneEdit(row) {
  Object.assign(sceneForm, { visible: true, loading: false, id: row.id, name: row.name, location: row.location, description: row.description, background: row.background, source: row.source || '', enabled: row.enabled })
}
async function doSaveScene() {
  if (!sceneForm.name.trim()) return ElMessage.warning('请填写场景名称')
  sceneForm.loading = true
  try {
    const payload = { name: sceneForm.name.trim(), location: sceneForm.location, description: sceneForm.description, background: sceneForm.background, source: sceneForm.source, enabled: sceneForm.enabled }
    if (sceneForm.id) await updateScene(sceneForm.id, payload)
    else await createScene(projectId, payload)
    ElMessage.success('场景已保存')
    sceneForm.visible = false
    await loadScenes()
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { sceneForm.loading = false }
}
async function doDeleteScene(row) {
  try {
    await ElMessageBox.confirm(`确认删除场景「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteScene(row.id)
    ElMessage.success('场景已删除')
    await loadScenes()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

// ===== 场景 AI 自动填充（vP5-7.6） =====

/** 打开 AI 自动填充对话框（重置数量） */
function openSceneAiGen() {
  Object.assign(sceneAiDialog, { visible: true, generating: false, count: 3 })
}

/**
 * 执行 AI 自动填充：调用后端接口（以世界观+角色详情为数据源生成场景），成功后刷新列表。
 */
async function doSceneAiGen() {
  sceneAiDialog.generating = true
  try {
    const created = await aiGenerateScenes(projectId, { count: sceneAiDialog.count })
    sceneAiDialog.visible = false
    ElMessage.success(`AI 已生成 ${created.length} 个场景（含来源依据）`)
    await loadScenes()
  } catch (e) { ElMessage.error(e.message || 'AI 生成失败') }
  finally { sceneAiDialog.generating = false }
}

/** JSON 美化 */
function prettyJson(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch (_) { return text }
}

onMounted(() => {
  loadBase()
})
// 离开页面时关闭演化播放流（后端感知连接断开后停止推进）
onBeforeUnmount(closePlayback)
</script>

<style scoped>
.evolve-view { display: flex; gap: 16px; height: calc(100vh - 140px); }

/* 左栏 */
.ev-side { width: 260px; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 12px; display: flex; flex-direction: column; overflow-y: auto; flex-shrink: 0; }
.side-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.side-empty { color: var(--text-secondary); font-size: 0.82rem; padding: 12px 4px; }
.tl-thumb { flex: 1; overflow-y: auto; position: relative; max-height: 180px; }
.tl-axis { display: flex; flex-direction: column; gap: 10px; border-left: 2px solid var(--border-l2); padding-left: 16px; padding-top: 14px; padding-bottom: 30px; position: relative; }
.tl-now-line { position: absolute; left: -2px; right: 0; top: 14px; border-top: 2px solid var(--el-color-danger); display: flex; justify-content: flex-end; }
.tl-now-label { background: var(--el-color-danger); color: #fff; font-size: 0.68rem; padding: 1px 6px; border-radius: 3px; transform: translateY(-8px); white-space: nowrap; }
.tl-thumb-node { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.tl-thumb-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-left: -21px; }
.tl-thumb-node.kind-event .tl-thumb-dot { background: #e6a23c; }
.tl-thumb-node.kind-action .tl-thumb-dot { background: #409eff; }
.tl-thumb-node.kind-memory .tl-thumb-dot { background: #67c23a; }
.tl-thumb-text { font-size: 0.75rem; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tl-thumb-time { display: block; font-size: 0.62rem; color: var(--text-placeholder); }
.ev-list { display: flex; flex-direction: column; gap: 6px; }
.ev-item { padding: 8px 10px; border-radius: 8px; cursor: pointer; border: 1px solid var(--border-light); transition: all .15s; }
.ev-item:hover { border-color: var(--brand-primary); }
.ev-item.active { border-color: var(--brand-primary); background: var(--brand-gradient-soft); }
.ev-item-title { font-size: 0.85rem; font-weight: 600; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ev-item-sub { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
.ev-item-meta { font-size: 0.72rem; color: var(--text-secondary); }
.ev-item-del { opacity: 0; transition: opacity .15s; margin-left: auto; flex-shrink: 0; }
.ev-item:hover .ev-item-del { opacity: 1; }

/* 中栏 */
.ev-main { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); display: flex; flex-direction: column; overflow: hidden; min-width: 0; }
.clock-bar { padding: 10px 16px; border-bottom: 1px solid var(--border-light); display: flex; align-items: center; gap: 12px; background: var(--bg-layer-2); flex-wrap: wrap; }
.clock-time { font-size: 0.9rem; color: var(--primary, #409eff); font-weight: 600; white-space: nowrap; }
.clock-rate { font-size: 0.75rem; color: var(--text-secondary); white-space: nowrap; }
.ev-control { padding: 12px 16px; border-bottom: 1px solid var(--border-light); display: flex; flex-direction: column; gap: 8px; background: var(--bg-layer-2); }
.ev-control-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ev-title { font-weight: 700; color: var(--text-primary); font-size: 1rem; }
.ev-turn-count { font-size: 0.75rem; color: var(--text-secondary); margin-left: auto; }
.ev-control-ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ev-control-empty { padding: 14px 16px; border-bottom: 1px solid var(--border-light); }
.ev-start-hint { margin-top: 6px; font-size: 0.8rem; color: var(--text-secondary); }
.ev-stream-hint { font-size: 0.75rem; color: var(--text-secondary); }
.ev-archive-alert { }
.form-tip { margin-left: 10px; font-size: 0.75rem; color: var(--text-secondary); }
.ev-stream { flex: 1; overflow-y: auto; padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.stream-empty { color: var(--text-secondary); font-size: 0.85rem; text-align: center; padding: 30px 0; }
.stream-item { display: flex; }
/* 系统消息：低频出现，字号小、弱化，避免喧宾夺主 */
.system-card { width: 100%; background: var(--bg-layer-2); border-left: 3px solid var(--border-l2); border-radius: 6px; padding: 4px 10px; }
.system-label { font-weight: 600; color: var(--text-tertiary); margin-right: 6px; font-size: 0.72rem; }
.system-text { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
/* 调度行：紧凑、弱化（每个调度决策一行） */
.schedule-row { width: 100%; display: flex; align-items: baseline; gap: 6px; padding: 2px 6px; }
.schedule-label { font-size: 0.7rem; color: #b88230; font-weight: 600; flex-shrink: 0; }
.schedule-text { font-size: 0.72rem; color: var(--text-secondary); line-height: 1.5; }
/* 角色行动/对话：NPC 内容字号大、突出（背景/正文令牌化，适配深浅双主题） */
.action-row { display: flex; gap: 10px; align-items: flex-start; background: var(--bg-layer-2); border-radius: 8px; padding: 10px 14px; width: 100%; }
.action-name { font-size: 0.85rem; font-weight: 700; color: #b88230; flex-shrink: 0; }
.action-text { font-size: 0.95rem; color: var(--text-regular); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.dialog-row { display: flex; gap: 10px; align-items: flex-start; }
.dialog-name { font-size: 0.85rem; font-weight: 700; color: var(--brand-primary); flex-shrink: 0; }
.dialog-text { font-size: 0.95rem; color: var(--text-primary); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
.auto-state { padding: 10px 16px; border-top: 1px solid var(--border-light); display: flex; align-items: center; gap: 10px; background: var(--bg-layer-2); }
.as-label { font-size: 0.78rem; color: var(--text-secondary); font-weight: 600; }
.as-summary { font-size: 0.78rem; color: var(--text-secondary); flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 右栏 */
.ev-info { width: 320px; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 12px; display: flex; flex-direction: column; overflow-y: auto; }
.info-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.scene-role { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
.scene-role:hover { background: var(--bg-light); }
.scene-role.active { background: var(--brand-gradient-soft); }
.role-avatar { background: var(--brand-gradient); color: #fff; flex-shrink: 0; }
.role-meta { min-width: 0; }
.role-name { font-weight: 600; color: var(--text-primary); font-size: 0.85rem; }
.role-title { font-size: 0.72rem; color: var(--text-secondary); }
.scene-role-head { display: flex; align-items: center; gap: 10px; padding: 6px 2px 10px; border-bottom: 1px solid var(--border-light); margin-bottom: 8px; }
.info-tabs { flex: 1; min-height: 0; }
.info-pre { margin: 0; padding: 10px; background: var(--bg-layer-2); border: 1px solid var(--border-light); border-radius: 6px; font-family: var(--font-mono, monospace); font-size: 0.75rem; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: calc(100vh - 360px); overflow-y: auto; }
.mem-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; background: var(--bg-layer-2); }
.mem-head { display: flex; gap: 6px; margin-bottom: 6px; }
.mem-content { font-size: 0.8rem; color: var(--text-primary); line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* 场景管理 */
.scene-toolbar { margin-bottom: 10px; display: flex; gap: 8px; }
.scene-source { font-size: 0.75rem; color: var(--text-regular); }
.scene-source-empty { font-size: 0.75rem; color: var(--text-secondary); }
.scene-ai-desc { font-size: 0.85rem; color: var(--text-regular); line-height: 1.7; margin-bottom: 14px; padding: 10px 12px; background: var(--bg-layer-2); border: 1px solid var(--border-l1); border-radius: 8px; }
.scene-ai-desc b { color: #b7791f; }
</style>
