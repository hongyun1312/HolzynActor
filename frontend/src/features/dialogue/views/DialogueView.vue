<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <div class="dialogue-page">
    <div class="dialogue-body" :class="{ 'is-narrow': narrow, 'is-very-narrow': veryNarrow }">
      <!-- 窄屏遮罩：点空白关掉覆盖式侧栏（P1-3） -->
      <div v-if="(narrow && ctxOpen) || (veryNarrow && listOpen)" class="dlg-scrim" @click="closeOverlays"></div>

      <!-- 左栏：会话列表（<1024px 收成覆盖式抽屉） -->
      <DialogueSessionList
        :class="{ 'is-open': listOpen }"
        :sessions="sessions"
        :current-id="currentSession ? currentSession.id : null"
        @create="createDialog.visible = true"
        @create-branch="openBranchCreate"
        @select="selectSession"
        @rename="openRename"
        @close="confirmClose"
      />

      <!-- 中栏：聊天流 -->
      <main class="dlg-main">
        <!-- Hero 空态：未选择会话 -->
        <div v-if="!currentSession" class="dlg-hero">
          <div class="dlg-hero-card">
            <div class="dlg-hero-art"><el-icon :size="24"><HIcon name="ChatDotRound" /></el-icon></div>
            <h2 class="dlg-hero-title">与角色对谈</h2>
            <p class="dlg-hero-sub">对谈是独立的静止世界模式：创建会话即冻结世界（自动暂停 + 快照时刻），
              聊天期间世界零推进。选择一个会话，或直接从一位角色开始。</p>
            <div class="dlg-hero-facts">
              <span class="dlg-fact"><el-icon :size="12"><HIcon name="AlarmClock" /></el-icon>创建即冻结</span>
              <span class="dlg-fact"><el-icon :size="12"><HIcon name="VideoPause" /></el-icon>聊天期间世界零推进</span>
              <span class="dlg-fact"><el-icon :size="12"><HIcon name="VideoPlay" /></el-icon>可随时手动恢复</span>
            </div>
            <el-button type="primary" round class="dlg-hero-cta" @click="createDialog.visible = true">
              <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建对谈
            </el-button>
            <div v-if="characters.length" class="dlg-hero-chars">
              <div class="dlg-hero-chars-label">或直接从一位角色开始（世界将冻结于当前时刻）</div>
              <div class="dlg-hero-chars-row">
                <button v-for="c in characters.slice(0, 8)" :key="c.id" type="button" class="dlg-hero-char" @click="quickCreate(c)">
                  <el-avatar :size="22" class="dlg-hero-avatar">{{ c.name?.charAt(0) }}</el-avatar>
                  <span>{{ c.name }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <template v-else>
          <!-- 会话头部 -->
          <header class="dlg-chat-header">
            <button v-if="veryNarrow" type="button" class="dlg-head-toggle" title="会话列表" @click="listOpen = !listOpen">
              <el-icon :size="15"><HIcon name="ic_public_view_list" /></el-icon>
            </button>
            <el-avatar class="dlg-chat-avatar" :size="32">{{ (currentSession.characterName || '群').charAt(0) }}</el-avatar>
            <div class="dlg-chat-head-main">
              <div class="dlg-chat-title">{{ currentSession.title || '未命名对谈' }}</div>
              <div class="dlg-chat-sub">
                <span v-if="currentSession.type === 'group'">群聊 · {{ currentSession.locationName || '未选地点' }}</span>
                <span v-else-if="currentSession.characterName">与 {{ currentSession.characterName }}</span>
                <span class="dlg-sub-dim">共 {{ messages.length }} 条</span>
                <span v-if="currentSession.frozenTimeText" class="dlg-frozen" :title="'冻结游戏秒：' + (currentSession.frozenGameSecond ?? '—')">
                  <el-icon :size="11"><HIcon name="AlarmClock" /></el-icon>
                  世界已静止于 {{ currentSession.frozenTimeText }}
                </span>
              </div>
            </div>
            <div class="dlg-chat-ops">
              <el-button v-if="messages.length === 0" size="small" text type="primary" :loading="greetingLoading" @click="requestGreeting">
                <el-icon><HIcon name="ChatDotRound" /></el-icon>&nbsp;{{ currentSession.type === 'group' ? '群聊开场' : '开场白' }}
              </el-button>
              <el-button
                size="small" text type="primary"
                title="沉淀：把本次对话写成关系与角色记忆（先出确认卡片）"
                :disabled="messages.length < 2 || streaming" :loading="consolidating" @click="openConsolidate"
              >
                <el-icon><HIcon name="Highlight" /></el-icon>&nbsp;沉淀
              </el-button>
              <el-button size="small" text @click="openRename(currentSession)">
                <el-icon><HIcon name="Edit" /></el-icon>&nbsp;重命名
              </el-button>
              <button v-if="narrow" type="button" class="dlg-head-toggle is-panel" title="对谈详情（角色档案 / 关系 / 世界书）" @click="ctxOpen = !ctxOpen">
                <el-icon :size="15"><HIcon name="ic_public_file" /></el-icon>
                <span>详情</span>
              </button>
            </div>
          </header>

          <!-- 群聊场景条（M5：地点/成员/换场/拉人） -->
          <DialogueGroupBar
            v-if="currentSession.type === 'group'"
            :session="currentSession"
            :members="groupMembers"
            :locations="groupLocations"
            :characters="characters"
            @change-location="changeGroupLocation"
            @invite="inviteGroupMember"
            @remove="removeGroupMember"
          />

          <!-- 消息流 -->
          <div class="dlg-flow" ref="flowRef">
            <div v-if="messages.length === 0" class="dlg-flow-empty">
              <p v-if="currentSession.type === 'group'">
                开始群聊吧 —— 在场角色将逐一对你搭话（世界已冻结于快照时刻，你们的对话不会影响世界推进）。
              </p>
              <p v-else>开始与 {{ currentSession.characterName || '角色' }} 对谈吧 —— 世界已冻结于快照时刻，你们的对话不会影响世界推进。</p>
              <el-button size="small" type="primary" plain :loading="greetingLoading" @click="requestGreeting">
                {{ currentSession.type === 'group' ? '生成群聊开场' : '生成开场白' }}
              </el-button>
            </div>
            <template v-for="m in messages" :key="m._key || m.id">
              <DialogueMessageBubble :msg="m" @swipe="swipeMsg" @rewrite="rewriteMsg" @delete="deleteMsg" @edit="editMsg" />
            </template>
            <!-- 生成中状态 -->
            <div v-if="streaming" class="dlg-turn-status" role="status" aria-live="polite">
              <span class="dlg-ts-shimmer">{{ streamMode === 'group' ? '角色们正在接话…' : '角色思考中…' }}</span>
              <span v-if="elapsedSec >= 15" class="dlg-ts-clock">{{ fmtElapsed() }}</span>
            </div>
          </div>

          <!-- 输入区 -->
          <div class="dlg-composer">
            <div class="dlg-composer-card">
              <div class="dlg-composer-scroll">
                <textarea
                  ref="taRef"
                  v-model="input"
                  class="dlg-composer-input"
                  rows="1"
                  :placeholder="streaming ? '角色正在思考…' : '输入消息…'"
                  :disabled="streaming"
                  @input="autoResize"
                  @keydown.enter.exact.prevent="send"
                ></textarea>
              </div>
              <div class="dlg-composer-row">
                <span class="dlg-hint">
                  <template v-if="streaming">角色正在输入…</template>
                  <template v-else-if="input.trim()">{{ input.trim().length }} 字 · Enter 发送 · Shift+Enter 换行</template>
                  <template v-else>Enter 发送 · Shift+Enter 换行</template>
                </span>
                <button
                  v-if="streaming"
                  type="button"
                  class="dlg-stop-btn"
                  title="停止生成（不保留半截回复）"
                  @click="stop"
                >停止</button>
                <button
                  v-else
                  type="button"
                  class="dlg-send-btn"
                  title="发送 (Enter)"
                  :disabled="!input.trim()"
                  @click="send"
                >
                  <HIcon name="Send" :size="15" />
                </button>
              </div>
            </div>
          </div>
        </template>
      </main>

      <!-- 右栏：上下文面板（<1280px 收成覆盖式抽屉） -->
      <DialogueContextPanel
        :class="{ 'is-open': ctxOpen }"
        :session="currentSession"
        :card="roleCard"
        :messages-count="messages.length"
        :context-chars="contextChars"
        :resuming="resuming"
        @create="createDialog.visible = true"
        @resume-world="resumeWorld"
      />
    </div>

    <!-- 新建对谈对话框 -->
    <el-dialog v-model="createDialog.visible" title="新建对谈" width="480px">
      <el-form label-width="90px">
        <el-form-item label="对谈类型">
          <el-radio-group v-model="createDialog.type">
            <el-radio-button value="single">单聊</el-radio-button>
            <el-radio-button value="group">群聊</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="createDialog.type === 'single'" label="对谈角色" required>
          <el-select v-model="createDialog.characterRef" filterable placeholder="选择角色（世界将冻结于当前时刻）" style="width: 100%">
            <el-option v-for="c in characters" :key="'npc:' + c.id" :label="c.name" :value="'npc:' + c.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="场景地点">
          <el-select v-model="createDialog.locationId" filterable placeholder="选择群聊地点（同地点角色自动在场）" style="width: 100%">
            <el-option :value="null" label="自动（在场最多的地点）" />
            <el-option v-for="l in groupLocations" :key="l.locationId ?? l.locationName" :label="l.locationName" :value="l.locationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="createDialog.title" placeholder="留空自动生成" maxlength="100" />
        </el-form-item>
      </el-form>
      <div class="dlg-create-tip">⚠ 创建即冻结：若世界正在运行，将自动暂停并快照当前时刻；对谈期间世界零推进，离开保持暂停。
        {{ createDialog.type === 'group' ? '群聊默认成员 = 冻结时刻同地点在场的角色，可换场/拉人。' : '' }}</div>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="createDialog.type === 'single' && !createDialog.characterRef" @click="doCreate">创建并开始</el-button>
      </template>
    </el-dialog>

    <!-- 重命名对话框 -->
    <el-dialog v-model="renameDialog.visible" title="重命名对谈" width="420px">
      <el-input v-model="renameDialog.title" maxlength="100" @keyup.enter="doRename" />
      <template #footer>
        <el-button @click="renameDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="doRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 沉淀确认卡片（M6：展示将写入的关系与角色记忆） -->
    <DialogueConsolidateCard
      v-model:visible="consolidateDialog.visible"
      :loading="consolidating"
      :card="consolidateCard"
      @confirm="doConsolidate"
      @cancel="consolidateDialog.visible = false"
    />
  </div>
</template>

<script setup>
/**
 * 对谈页（M2 前端单聊闭环）。
 * <p>职责：与「浮生」主模式同级的静止世界聊天页——三栏布局（会话列表 / 聊天流 / 上下文面板）、
 * AI 动态开场白、SSE 流式单聊（meta→token→done|error）、消息操作（换一条/重写/编辑/删除/停止）、
 * 冻结世界语义提示（创建即冻结 + 手动恢复）。</p>
 * <p>数据来源：/api/projects/{id}/dialogue/*（M1 后端）。流式端点走统一
 * {@code streamFetch}（POST SSE；EventSource 仅支持 GET）。</p>
 * <p>停止语义：取消流 → 后端断流不落库（M2 后端增强），本地移除占位后重拉消息对齐。</p>
 *
 * <p><b>P1-3 响应式三档</b>：≥1280px 三栏并置；1024~1279px 上下文栏收为覆盖式抽屉
 * （表头「详情」按钮开关）；&lt;1024px 会话列表也收为覆盖式抽屉（表头「会话列表」按钮开关）。
 * 此前无任何断点——实测 900px 视口下中栏被挤到 2px 宽（等于无法对谈）。</p>
 */
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useShellLayout } from '@/shared/layout/useShellLayout'
import {
  fetchDialogueSessions, createDialogueSession, renameDialogueSession, closeDialogueSession,
  fetchDialogueMessages, generateDialogueGreeting,
  editDialogueMessage, deleteDialogueMessage, swipeDialogueMessage,
  dialogueChatUrl, dialogueRegenerateUrl,
  fetchDialogueGroupMembers, inviteDialogueGroupMember, removeDialogueGroupMember,
  changeDialogueGroupLocation, dialogueGroupChatUrl,
  fetchDialogueConsolidationPreview, applyDialogueConsolidation,
  fetchCharacters, fetchCharacterCard, updateWorldClock
} from '@/shared/api'
import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'
import { npcIdOf } from '@/shared/npcRef'
import DialogueSessionList from '@/features/dialogue/components/DialogueSessionList.vue'
import DialogueMessageBubble from '@/features/dialogue/components/DialogueMessageBubble.vue'
import DialogueContextPanel from '@/features/dialogue/components/DialogueContextPanel.vue'
import DialogueGroupBar from '@/features/dialogue/components/DialogueGroupBar.vue'
import DialogueConsolidateCard from '@/features/dialogue/components/DialogueConsolidateCard.vue'

const route = useRoute()
const projectId = Number(route.params.id)

// ==================== 响应式档位（P1-3） ====================
/** 窄屏：<1280px → 上下文栏改为覆盖式抽屉 */
const narrow = ref(false)
/** 极窄：<1024px → 会话列表也改为覆盖式抽屉（外壳侧栏此时同样是覆盖式） */
const veryNarrow = ref(false)
/** 覆盖式抽屉开关 */
const ctxOpen = ref(false)
const listOpen = ref(false)

/**
 * 窄屏档位直接读外壳的权威状态（V2.0 修正）。
 *
 * <p>改造前本页自带两个野断点字符串（`max-width: 1279px` / `1023px`），与外壳的
 * `BREAKPOINT_MEDIUM=1440` / `BREAKPOINT_SMALL=1024` 不一致：在 1280–1439 区间里，
 * 外壳已把侧栏收成图标导轨，本页却仍按"宽屏"渲染三栏 → 中间对话列被挤压。
 * 现在改为订阅 {@link useShellLayout} 的 `shellMode`，与侧栏/抽屉降级永远同步：
 * `narrow` = 非 wide（&lt;1440，右栏转覆盖式抽屉）；`veryNarrow` = overlay（&lt;1024，会话列表也转抽屉）。</p>
 */
const { shellMode } = useShellLayout()
watch(shellMode, (mode) => {
  const n = mode !== 'wide'
  const vn = mode === 'overlay'
  if (narrow.value !== n || veryNarrow.value !== vn) {
    // 回到宽屏时关掉覆盖式抽屉，避免「布局变了抽屉还开着」
    if (!n) ctxOpen.value = false
    if (!vn) listOpen.value = false
  }
  narrow.value = n
  veryNarrow.value = vn
}, { immediate: true })

function closeOverlays() {
  ctxOpen.value = false
  listOpen.value = false
}

// ==================== 数据 ====================
const sessions = ref([])
const currentSession = ref(null)
const messages = ref([])
const characters = ref([])
const roleCard = ref(null)
const groupMembers = ref([])

/** 当前流式模式：single 单聊 / group 群聊（事件路由分叉） */
const streamMode = ref('single')

/** 群聊可选地点（角色所在地点去重；换场/创建用） */
const groupLocations = computed(() => {
  const map = new Map()
  for (const c of characters.value) {
    const key = c.locationId != null ? c.locationId : ('name:' + (c.location || ''))
    if (!c.location) continue
    if (!map.has(key)) map.set(key, { locationId: c.locationId ?? null, locationName: c.location })
  }
  return [...map.values()]
})

// ==================== 输入 / 流 ====================
const input = ref('')
const taRef = ref(null)
const flowRef = ref(null)
const streaming = ref(false)
const streamClient = ref(null)
const greetingLoading = ref(false)
const creating = ref(false)
const renaming = ref(false)
const resuming = ref(false)

let startTs = 0
const elapsedSec = ref(0)
let timer = null

// ==================== 对话框 ====================
const createDialog = reactive({ visible: false, type: 'single', characterRef: null, locationId: null, title: '' })
const renameDialog = reactive({ visible: false, session: null, title: '' })

// ==================== 沉淀（M6：确认卡片 → 应用） ====================
const consolidating = ref(false)
const consolidateCard = ref(null)
const consolidateDialog = reactive({ visible: false })

/** 沉淀：AI 抽取确认卡片（只读不落库），展示将写入的关系与角色记忆。 */
async function openConsolidate() {
  if (!currentSession.value || consolidating.value || streaming.value) return
  const sid = currentSession.value.id
  consolidating.value = true
  consolidateCard.value = null
  try {
    consolidateCard.value = await fetchDialogueConsolidationPreview(projectId, sid)
    if (currentSession.value && currentSession.value.id === sid) {
      consolidateDialog.visible = true
    }
  } catch (e) {
    ElMessage.error(e.message || '沉淀分析失败，请稍后重试')
  } finally {
    consolidating.value = false
  }
}

/** 确认沉淀：候选原样回传 → apply 落库（关系写回 + NPC 记忆 + 会话版本留痕）。 */
async function doConsolidate() {
  const sid = currentSession.value.id
  const card = consolidateCard.value
  if (!card || !card.characters || !card.characters.length || consolidating.value) return
  consolidating.value = true
  try {
    const characters = card.characters.map(c => ({
      characterRef: c.characterRef,
      affinityDelta: c.affinityDelta ?? 0,
      trustDelta: c.trustDelta ?? 0,
      stage: c.stage || undefined,
      note: c.note || undefined,
      stateSummary: c.stateSummary || undefined,
      memoryPoints: c.memoryPoints || []
    }))
    const result = await applyDialogueConsolidation(projectId, sid, characters)
    consolidateDialog.visible = false
    const n = result && result.characters ? result.characters.length : 0
    ElMessage.success(`已沉淀 ${n} 位角色的关系与记忆${result && result.version ? `（关系版本 v${result.version}）` : ''}`)
    await loadSessions()
  } catch (e) {
    ElMessage.error(e.message || '沉淀失败，请稍后重试')
  } finally {
    consolidating.value = false
  }
}

/** 全部正文累计字符（上下文用量 v1） */
const contextChars = computed(() => messages.value.reduce((n, m) => n + (m.content ? m.content.length : 0), 0))

// ==================== 加载 ====================
async function loadSessions() {
  try {
    sessions.value = await fetchDialogueSessions(projectId)
  } catch (e) {
    ElMessage.error(e.message || '对谈会话加载失败')
  }
}

async function loadCharacters() {
  try {
    characters.value = await fetchCharacters(projectId)
  } catch (_) { /* 角色列表失败不阻断对谈页 */ }
}

/** 消息归一化：metaJson → swipes/kind，并给稳定 _key */
function normalizeMessage(m) {
  let swipes = []
  let kind = null
  if (m.metaJson) {
    try {
      const meta = typeof m.metaJson === 'string' ? JSON.parse(m.metaJson) : m.metaJson
      if (Array.isArray(meta.swipes)) swipes = meta.swipes
      if (meta.kind) kind = meta.kind
    } catch (_) { /* 忽略损坏 meta */ }
  }
  return { ...m, swipes, kind, _key: `m${m.id}` }
}

// ==================== 会话 ====================
async function selectSession(s) {
  closeStream()
  closeOverlays()
  currentSession.value = s
  roleCard.value = null
  groupMembers.value = []
  messages.value = []
  try {
    messages.value = (await fetchDialogueMessages(projectId, s.id)).map(normalizeMessage)
    scrollBottom()
  } catch (e) {
    ElMessage.error(e.message || '消息加载失败')
  }
  // 群聊：加载场景化成员（地点/在场/拉人）
  if (s.type === 'group') {
    try { groupMembers.value = await fetchDialogueGroupMembers(projectId, s.id) } catch (_) { groupMembers.value = [] }
    // 空会话自动触发群聊开场（在场清醒角色按序）
    if (messages.value.length === 0) {
      requestGreeting()
    }
    return
  }
  // 单聊：角色卡（复用既有角色档案接口）
  const cid = npcIdOf(s.characterRef)
  if (cid) {
    try { roleCard.value = await fetchCharacterCard(cid) } catch (_) { roleCard.value = null }
  }
  // 空会话自动触发 AI 开场白（后端已有实质消息时自动跳过）
  if (messages.value.length === 0) {
    requestGreeting()
  }
}

/** 群聊：重拉成员列表（换场/拉人/移除后）。 */
async function loadGroupMembers() {
  if (!currentSession.value || currentSession.value.type !== 'group') return
  try {
    groupMembers.value = await fetchDialogueGroupMembers(projectId, currentSession.value.id)
  } catch (_) { /* 静默 */ }
}

/** 群聊：换场（切地点重算在场成员；manual 拉人保留）。 */
async function changeGroupLocation(locationId) {
  const sid = currentSession.value.id
  const res = await changeDialogueGroupLocation(projectId, sid, locationId)
  if (res.session && currentSession.value && currentSession.value.id === sid) {
    currentSession.value = { ...currentSession.value, ...res.session }
  }
  groupMembers.value = res.members || []
  await loadSessions()
}

/** 群聊：拉人（manual 标记）。 */
async function inviteGroupMember(characterRef) {
  groupMembers.value = await inviteDialogueGroupMember(projectId, currentSession.value.id, characterRef)
}

/** 群聊：移除成员（在场或拉人均可）。 */
async function removeGroupMember(characterRef) {
  await removeDialogueGroupMember(projectId, currentSession.value.id, characterRef)
  await loadGroupMembers()
}

/** 快捷开始：选中角色 → 直接新建（世界冻结） */
async function quickCreate(c) {
  createDialog.type = 'single'
  createDialog.characterRef = `npc:${c.id}`
  createDialog.locationId = null
  createDialog.title = ''
  await doCreate()
}

/** 会话列表「分支 +」：按该角色（或群聊）预填新建对话框——同一角色可开多个会话（分支，M6 打磨）。 */
function openBranchCreate(g) {
  createDialog.type = g.type === 'group' ? 'group' : 'single'
  createDialog.characterRef = g.type === 'group' ? null : (g.characterRef || null)
  createDialog.locationId = null
  createDialog.title = ''
  createDialog.visible = true
}

async function doCreate() {
  if (creating.value) return
  if (createDialog.type === 'single' && !createDialog.characterRef) return
  creating.value = true
  try {
    const payload = {
      type: createDialog.type,
      title: createDialog.title ? createDialog.title.trim() : undefined
    }
    if (createDialog.type === 'single') {
      payload.characterRef = createDialog.characterRef
    } else {
      payload.locationId = createDialog.locationId
    }
    const s = await createDialogueSession(projectId, payload)
    createDialog.visible = false
    createDialog.characterRef = null
    createDialog.locationId = null
    createDialog.title = ''
    await loadSessions()
    await selectSession(s)
    ElMessage.success(createDialog.type === 'group' ? '群聊已创建，世界已冻结于当前时刻' : '对谈已创建，世界已冻结于当前时刻')
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    creating.value = false
  }
}

function openRename(s) {
  renameDialog.session = s
  renameDialog.title = s.title || ''
  renameDialog.visible = true
}

async function doRename() {
  const s = renameDialog.session
  if (!s) return
  renaming.value = true
  try {
    const vo = await renameDialogueSession(projectId, s.id, renameDialog.title ? renameDialog.title.trim() : '')
    if (currentSession.value && currentSession.value.id === s.id) {
      currentSession.value = { ...currentSession.value, ...vo }
    }
    renameDialog.visible = false
    await loadSessions()
  } catch (e) {
    ElMessage.error(e.message || '重命名失败')
  } finally {
    renaming.value = false
  }
}

async function confirmClose(s) {
  try {
    await ElMessageBox.confirm(
      `关闭对谈「${s.title || '未命名对谈'}」？历史保留可查阅；世界保持暂停，可在本页或沙盘手动恢复。`,
      '关闭对谈',
      { type: 'warning' }
    )
  } catch (_) { return }
  try {
    await closeDialogueSession(projectId, s.id)
    if (currentSession.value && currentSession.value.id === s.id) {
      currentSession.value = null
      messages.value = []
      roleCard.value = null
    }
    await loadSessions()
  } catch (e) {
    ElMessage.error(e.message || '关闭失败')
  }
}

/** 手动恢复世界（设计 §6.6：对谈页提供恢复入口，与沙盘同一操作） */
async function resumeWorld() {
  try {
    await ElMessageBox.confirm(
      '恢复世界后世界将继续推进（对谈会话冻结快照不变）。确定恢复？',
      '恢复世界',
      { type: 'warning' }
    )
  } catch (_) { return }
  resuming.value = true
  try {
    await updateWorldClock(projectId, { paused: false })
    ElMessage.success('世界已恢复运行')
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    resuming.value = false
  }
}

// ==================== 开场白 ====================
async function requestGreeting() {
  if (!currentSession.value || greetingLoading.value || streaming.value) return
  const sid = currentSession.value.id
  greetingLoading.value = true
  try {
    await generateDialogueGreeting(projectId, sid)
    // 会话可能已切换：仅当仍是同一会话时应用结果
    if (currentSession.value && currentSession.value.id === sid) {
      await syncMessages()
    }
  } catch (e) {
    ElMessage.warning('开场白生成失败，可直接开始对话')
  } finally {
    greetingLoading.value = false
  }
}

// ==================== 发送 / 重写 / 停止 ====================
function send() {
  const content = input.value.trim()
  if (!content || !currentSession.value || streaming.value) return
  input.value = ''
  autoResize()
  messages.value.push({
    id: `u${Date.now()}`, sessionId: currentSession.value.id, role: 'user',
    characterRef: null, characterName: null, content, metaJson: null,
    seq: null, streaming: false, swipes: [], kind: null, _key: `u${Date.now()}`
  })
  if (currentSession.value.type === 'group') {
    // 群聊：逐角色串行流式（group-member 事件按成员创建气泡）
    streamMode.value = 'group'
    scrollBottom()
    openStream(dialogueGroupChatUrl(projectId, currentSession.value.id), { content })
    return
  }
  streamMode.value = 'single'
  messages.value.push({
    id: `a${Date.now()}`, sessionId: currentSession.value.id, role: 'assistant',
    characterRef: currentSession.value.characterRef, characterName: currentSession.value.characterName,
    content: '', metaJson: null, seq: null, streaming: true, swipes: [], kind: null, _key: `a${Date.now()}`
  })
  scrollBottom()
  openStream(dialogueChatUrl(projectId, currentSession.value.id), { content })
}

function rewriteMsg(msg) {
  if (!currentSession.value || streaming.value || typeof msg.id !== 'number') return
  if (currentSession.value.type === 'group') return // 群聊重写暂不支持（M5 边界）
  // 本地截断：目标及其后作废（对齐后端语义），随后流式占位
  const idx = messages.value.findIndex(m => m.id === msg.id)
  if (idx < 0) return
  messages.value.splice(idx)
  messages.value.push({
    id: `a${Date.now()}`, sessionId: currentSession.value.id, role: 'assistant',
    characterRef: currentSession.value.characterRef, characterName: currentSession.value.characterName,
    content: '', metaJson: null, seq: null, streaming: true, swipes: [], kind: null, _key: `a${Date.now()}`
  })
  scrollBottom()
  openStream(dialogueRegenerateUrl(projectId, currentSession.value.id), { messageId: msg.id })
}

function stop() {
  // 取消流：后端断流不落库；本地移除占位后重拉对齐
  if (streamClient.value) {
    try { streamClient.value.cancel() } catch (_) { /* 忽略 */ }
  }
  const idx = messages.value.findIndex(m => m.streaming)
  if (idx >= 0) messages.value.splice(idx, 1)
  endStreaming()
  syncMessages()
}

// ==================== 消息操作 ====================
async function editMsg(msg, content) {
  if (!currentSession.value || typeof msg.id !== 'number') return
  const idx = messages.value.findIndex(m => m.id === msg.id)
  if (idx < 0) return
  const old = messages.value[idx].content
  messages.value[idx].content = content
  messages.value.splice(idx + 1) // 本地作废后续链
  try {
    await editDialogueMessage(projectId, currentSession.value.id, msg.id, content)
    await loadSessions()
  } catch (e) {
    messages.value[idx].content = old
    ElMessage.error(e.message || '编辑失败')
  }
}

async function deleteMsg(msg) {
  if (!currentSession.value || typeof msg.id !== 'number') return
  try {
    await ElMessageBox.confirm(
      msg.role === 'assistant'
        ? '删除这条回复（连同其后所有消息）？'
        : '删除这条发言（连同其后所有消息）？',
      '删除消息',
      { type: 'warning' }
    )
  } catch (_) { return }
  const idx = messages.value.findIndex(m => m.id === msg.id)
  if (idx < 0) return
  messages.value.splice(idx)
  try {
    await deleteDialogueMessage(projectId, currentSession.value.id, msg.id)
    await loadSessions()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function swipeMsg(msg) {
  if (!currentSession.value || typeof msg.id !== 'number') return
  try {
    const vo = await swipeDialogueMessage(projectId, currentSession.value.id, msg.id)
    const idx = messages.value.findIndex(m => m.id === msg.id)
    if (idx >= 0) {
      messages.value[idx].content = vo.content
      messages.value[idx].metaJson = vo.metaJson
      messages.value[idx].swipes = parseSwipes(vo.metaJson)
    }
  } catch (e) {
    ElMessage.error(e.message || '换一条失败')
  }
}

function parseSwipes(metaJson) {
  if (!metaJson) return []
  try {
    const meta = typeof metaJson === 'string' ? JSON.parse(metaJson) : metaJson
    return Array.isArray(meta.swipes) ? meta.swipes : []
  } catch (_) {
    return []
  }
}

// ==================== 流式客户端（streamFetch：POST SSE） ====================
function openStream(url, body) {
  closeStream()
  streaming.value = true
  startTs = Date.now()
  timer = setInterval(() => { elapsedSec.value = Math.floor((Date.now() - startTs) / 1000) }, 1000)
  try {
    streamClient.value = streamFetch({
      url,
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      onEvent: handleStreamEvent,
      onError: handleStreamError,
      onDone: () => {
        endStreaming()
        // 完成后对齐后端（真实 id/seq/计数）
        syncMessages()
        loadSessions()
      }
    })
  } catch (e) {
    handleStreamError({ message: e.message })
  }
}

function handleStreamEvent(name, d) {
  if (!d || typeof d !== 'object') return
  if (streamMode.value === 'group') {
    handleGroupStreamEvent(name, d)
    return
  }
  if (name === SSE_EVENTS.TOKEN) {
    const row = [...messages.value].reverse().find(m => m.streaming)
    if (row) {
      row.content += d.delta || ''
      scrollBottom()
    }
  } else if (name === SSE_EVENTS.DONE) {
    const row = [...messages.value].reverse().find(m => m.streaming)
    if (row) {
      row.streaming = false
      if (d.messageId) row.id = d.messageId
      if (d.content) row.content = d.content
      if (d.seq != null) row.seq = d.seq
    }
  }
}

/** 群聊流式事件：group-member（新角色气泡）→ token（当前气泡）→ group-member-done（定稿）→ done（整轮结束）。 */
function handleGroupStreamEvent(name, d) {
  if (name === 'group-member') {
    messages.value.push({
      id: `g${Date.now()}`, sessionId: currentSession.value.id, role: 'group',
      characterRef: d.characterRef, characterName: d.characterName,
      content: '', metaJson: null, seq: null, streaming: true, swipes: [], kind: null,
      _key: `g${Date.now()}-${d.characterRef}`
    })
    scrollBottom()
  } else if (name === SSE_EVENTS.TOKEN) {
    const row = [...messages.value].reverse().find(m => m.streaming)
    if (row) {
      row.content += d.delta || ''
      scrollBottom()
    }
  } else if (name === 'group-member-done') {
    const row = [...messages.value].reverse().find(m => m.streaming)
    if (row) {
      row.streaming = false
      if (d.messageId) row.id = d.messageId
      if (d.content) row.content = d.content
      if (d.seq != null) row.seq = d.seq
    }
  } else if (name === SSE_EVENTS.DONE) {
    // 整轮结束 → 结束后台对齐（真实 id/seq/计数）
    endStreaming()
    syncMessages()
    loadSessions()
  }
}

function handleStreamError(err) {
  // 移除流式占位（error 时后端未落库回复）
  const idx = messages.value.findIndex(m => m.streaming)
  if (idx >= 0) messages.value.splice(idx, 1)
  endStreaming()
  let msg = '生成失败，请重试'
  if (err && err.kind === 'event' && err.data && err.data.message) msg = err.data.message
  else if (err && err.message) msg = err.message
  ElMessage.error(msg)
  // 重写失败/截断回滚：重拉后端对齐（后端可能未变更）
  syncMessages()
  scrollBottom()
}

function endStreaming() {
  streaming.value = false
  if (timer) { clearInterval(timer); timer = null }
  elapsedSec.value = 0
  streamClient.value = null
}

function closeStream() {
  if (streamClient.value) {
    try { streamClient.value.cancel() } catch (_) { /* 忽略 */ }
  }
  endStreaming()
}

// ==================== 工具 ====================
async function syncMessages() {
  if (!currentSession.value) return
  try {
    messages.value = (await fetchDialogueMessages(projectId, currentSession.value.id)).map(normalizeMessage)
    scrollBottom()
  } catch (_) { /* 重拉失败静默 */ }
}

function scrollBottom() {
  nextTick(() => {
    const el = flowRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function autoResize() {
  const ta = taRef.value
  if (!ta) return
  ta.style.height = 'auto'
  ta.style.height = Math.min(ta.scrollHeight, 160) + 'px'
}

function fmtElapsed() {
  const s = elapsedSec.value
  const m = Math.floor(s / 60)
  return m > 0 ? `${m} 分 ${s % 60} 秒` : `${s} 秒`
}

// ==================== 生命周期 ====================
onMounted(() => {
  // V2.0：窄屏档位改为订阅外壳 shellMode（见上方 watch），不再各自 matchMedia 野断点
  loadSessions()
  loadCharacters()
})
onBeforeUnmount(() => {
  closeStream()
})
</script>

<style scoped>
/* 对谈页：三栏聊天专注布局（高度取外壳内容区 100%；宽度令牌 --page-aside-w / --page-info-w） */
.dialogue-page { width: 100%; max-width: var(--content-max-w, 1440px); margin: 0 auto; height: 100%; min-height: 0; display: flex; flex-direction: column; }
.dialogue-body { position: relative; flex: 1; min-height: 0; display: flex; gap: 12px; }

/* 中栏 */
.dlg-main {
  flex: 1; min-width: 0;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  display: flex; flex-direction: column;
  overflow: hidden;
}

/* ===== Hero 空态 ===== */
.dlg-hero { flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px; overflow-y: auto; }
.dlg-hero-card {
  width: 100%; max-width: 560px;
  display: flex; flex-direction: column; align-items: center; text-align: center; gap: 12px;
  padding: 28px 24px;
  border: 1px solid var(--border-l1); border-radius: var(--radius-lg);
  background: linear-gradient(180deg, var(--accent-softer, transparent), transparent 60%), var(--bg-layer-1);
}
.dlg-hero-art {
  width: 52px; height: 52px; border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent-text);
}
.dlg-hero-title { font-size: 1.22rem; font-weight: 700; color: var(--text-primary); margin: 0; }
.dlg-hero-sub { max-width: 460px; color: var(--text-secondary); font-size: 0.82rem; line-height: 1.8; margin: 0; }
.dlg-hero-facts { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px 14px; margin: 2px 0; }
.dlg-fact { display: inline-flex; align-items: center; gap: 4px; font-size: 0.74rem; color: var(--text-tertiary); }
.dlg-hero-cta { margin-top: 2px; }
.dlg-hero-chars { width: 100%; margin-top: 8px; padding-top: 14px; border-top: 1px dashed var(--border-l1); }
.dlg-hero-chars-label { font-size: 0.74rem; color: var(--text-tertiary); margin-bottom: 8px; }
.dlg-hero-chars-row { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
.dlg-hero-char {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 12px 5px 6px; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--border-l1); background: var(--bg-layer-1);
  font-size: 0.8rem; color: var(--text-primary); transition: all .15s var(--dsw-ease);
}
.dlg-hero-char:hover { border-color: var(--accent-border); background: var(--accent-softer); color: var(--accent-text); }
.dlg-hero-avatar { background: var(--accent-soft); color: var(--accent-text); font-size: 0.66rem; flex-shrink: 0; }

/* ===== 会话头部 ===== */
.dlg-chat-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-bottom: 1px solid var(--border-l1);
}
.dlg-chat-avatar { background: var(--accent-soft); color: var(--accent-text); font-size: 0.82rem; font-weight: 600; flex-shrink: 0; }
.dlg-chat-head-main { flex: 1; min-width: 0; }
.dlg-chat-title { font-weight: 700; color: var(--text-primary); font-size: 0.94rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dlg-chat-sub { display: flex; align-items: center; gap: 10px; margin-top: 2px; font-size: 0.73rem; color: var(--text-secondary); flex-wrap: wrap; }
.dlg-sub-dim { color: var(--text-tertiary); }
.dlg-frozen { display: inline-flex; align-items: center; gap: 3px; color: var(--state-warn); }
.dlg-chat-ops { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }

/* 窄屏覆盖式抽屉的开关（会话列表 / 详情） */
.dlg-head-toggle {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--border-l1); background: var(--bg-layer-1);
  color: var(--text-secondary); cursor: pointer;
  height: 28px; padding: 0 8px; border-radius: 8px; font-size: 0.76rem;
  flex-shrink: 0;
}
.dlg-head-toggle:hover { color: var(--accent-text); border-color: var(--accent-border); background: var(--accent-softer); }

/* 窄屏遮罩 */
.dlg-scrim { position: absolute; inset: 0; z-index: calc(var(--z-drawer, 40) - 1); background: var(--bg-mask); border-radius: var(--radius-lg); }

/* ===== 消息流 ===== */
.dlg-flow { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 18px; display: flex; flex-direction: column; gap: 14px; }
.dlg-flow-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: var(--text-secondary); font-size: 0.84rem; text-align: center; }
.dlg-turn-status { display: flex; align-items: center; gap: 8px; color: var(--text-tertiary); font-size: 0.76rem; padding: 2px 4px; }
.dlg-ts-shimmer { color: var(--text-secondary); animation: dlg-pulse 1.6s ease-in-out infinite; }
@keyframes dlg-pulse { 50% { opacity: 0.4; } }
.dlg-ts-clock { color: var(--text-tertiary); }

/* ===== 输入区 ===== */
.dlg-composer { padding: 10px 14px 12px; border-top: 1px solid var(--border-l1); }
.dlg-composer-card {
  background: var(--bg-layer-2); border: 1px solid var(--border-l2);
  border-radius: 14px; padding: 6px 10px 4px;
  transition: border-color .16s var(--dsw-ease), box-shadow .16s var(--dsw-ease);
}
.dlg-composer-card:focus-within { border-color: var(--accent-border); box-shadow: 0 0 0 3px var(--accent-softer); }
.dlg-composer-scroll { max-height: 160px; overflow-y: auto; }
.dlg-composer-input {
  /* V2.0 a11y：不再写裸 outline:none（键盘聚焦时由 global.css 的
     textarea:focus-visible 提供内缩实心焦点环，且特异性高于本规则） */
  width: 100%; border: none; resize: none;
  background: transparent; color: var(--text-primary);
  font-size: 0.88rem; line-height: 1.6; font-family: inherit;
  min-height: 24px; padding: 2px 0;
}
.dlg-composer-input::placeholder { color: var(--text-placeholder); }
.dlg-composer-row { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 2px; }
.dlg-hint { color: var(--text-tertiary); font-size: 0.72rem; }
.dlg-send-btn, .dlg-stop-btn {
  border: none; cursor: pointer; border-radius: 9px;
  display: inline-flex; align-items: center; justify-content: center;
  height: 30px; padding: 0 14px; font-size: 0.78rem;
  transition: background-color .16s var(--dsw-ease), opacity .16s var(--dsw-ease);
}
.dlg-send-btn { background: var(--state-primary); color: var(--accent-contrast, #fff); width: 34px; padding: 0; }
.dlg-send-btn:hover:not(:disabled) { background: var(--accent-strong); }
.dlg-send-btn:disabled { opacity: .4; cursor: not-allowed; }
.dlg-stop-btn { background: var(--state-danger-bg); color: var(--state-error); border: 1px solid color-mix(in srgb, var(--state-error) 40%, transparent); }
.dlg-stop-btn:hover { background: color-mix(in srgb, var(--state-error) 20%, transparent); }

/* 新建提示 */
.dlg-create-tip { font-size: 0.74rem; color: var(--text-secondary); background: var(--bg-layer-2); border-radius: 8px; padding: 8px 12px; line-height: 1.6; }

/* ============================================================
   P1-3 响应式三档
   ≥1280px：会话列表 + 聊天 + 上下文 三栏并置
   1024~1279px：上下文栏改为覆盖式抽屉（表头「详情」开关）
   <1024px：会话列表也改为覆盖式抽屉（表头「会话列表」开关）
   说明：只重定位两个子组件的根元素（Vue 会把父作用域 id 加到子组件根节点上），
   内部样式不受影响。
   ============================================================ */
.dialogue-body.is-narrow > .dialogue-context,
.dialogue-body.is-very-narrow > .dialogue-context {
  position: absolute; top: 0; right: 0; bottom: 0; z-index: var(--z-drawer, 40);
  width: min(360px, 88%);
  box-shadow: var(--shadow-lv3);
  transform: translateX(calc(100% + 16px));
  transition: transform .22s var(--dsw-ease);
}
.dialogue-body.is-narrow > .dialogue-context.is-open,
.dialogue-body.is-very-narrow > .dialogue-context.is-open { transform: none; }

.dialogue-body.is-very-narrow > .dialogue-session-list {
  position: absolute; top: 0; left: 0; bottom: 0; z-index: var(--z-drawer, 40);
  width: min(300px, 88%);
  box-shadow: var(--shadow-lv3);
  transform: translateX(calc(-100% - 16px));
  transition: transform .22s var(--dsw-ease);
}
.dialogue-body.is-very-narrow > .dialogue-session-list.is-open { transform: none; }

/* 极窄屏：头部信息与操作挤在一行会互相顶，允许换行
   V2.0：断点值归并到档位 1024（原 1023 是野值，与外壳 BREAKPOINT_SMALL=1024 不一致） */
@media (max-width: 1024px) {
  .dlg-chat-header { flex-wrap: wrap; }
  .dlg-chat-head-main { order: 1; }
  .dlg-chat-ops { order: 2; width: 100%; justify-content: flex-end; }
}
</style>
