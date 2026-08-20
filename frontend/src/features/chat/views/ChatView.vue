<template>
  <div class="chat-view">
    <!-- 左栏：会话 + 角色 -->
    <div class="chat-side">
      <div class="side-header">
        <span>会话</span>
        <el-button size="small" type="primary" text @click="openCreateConv"><el-icon><HIcon name="Plus" /></el-icon>新建</el-button>
      </div>
      <div v-if="conversations.length === 0" class="side-empty">暂无会话</div>
      <div v-else class="conv-list">
        <div v-for="c in conversations" :key="c.id" class="conv-item" :class="{ active: c.id === currentConv?.id }" @click="switchConv(c)">
          <div class="conv-text">
            <div class="conv-title">{{ c.title || '未命名会话' }}
              <el-tag v-if="isGroup(c)" size="small" type="warning" effect="plain" class="conv-mode-tag">群聊</el-tag>
            </div>
            <div class="conv-sub">📍 {{ c.location || '远程通讯' }} · {{ isGroup(c) ? '群聊' : '单聊' }} · {{ convTime(c.updatedAt) }}</div>
          </div>
          <el-button class="conv-del" size="small" text type="danger" @click.stop="removeConv(c)" title="删除会话">
            <el-icon><HIcon name="Delete" /></el-icon>
          </el-button>
        </div>
      </div>
      <el-divider />
      <div class="side-header"><span>项目角色</span></div>
      <div v-if="characters.length === 0" class="side-empty">暂无角色</div>
      <div v-else class="conv-list">
        <div v-for="ch in characters" :key="ch.id" class="conv-item" @click="quickChat(ch)">
          <el-avatar :size="24" class="char-avatar">{{ ch.name?.charAt(0) }}</el-avatar>
          <span class="conv-title">{{ ch.name }}</span>
        </div>
      </div>
    </div>

    <!-- 中栏：对话流（DeepSeek Harness 风格：居中列 + 气泡 + DSH 输入框） -->
    <div class="chat-main">
      <header class="chat-header">
        <!-- 顶部：对话标题（单行省略、悬浮显示完整标题；操作区仅保留聊天专属功能） -->
        <div class="header-top">
          <span class="chat-title" :title="currentConv?.title || '对话'">{{ currentConv?.title || '对话' }}</span>
          <div class="header-ops">
            <!-- 群聊专属：指定发言人（空 = AI 调度）+ 每轮回复上限 -->
            <div v-if="isGroup(currentConv)" class="replies-ctl">
              <el-select v-model="forceSpeaker" size="small" clearable placeholder="AI 调度" style="width: 130px" :title="'指定本轮发言人（空 = AI 按发言欲望调度）'">
                <el-option v-for="r in roleList" :key="r.id" :label="r.name" :value="r.id" />
              </el-select>
              <span class="replies-label">每轮上限</span>
              <el-input-number v-model="groupReplies" :min="1" :max="20" size="small" :controls="false" style="width: 58px" :title="'群聊每轮回复上限（AI 最多回复角色数）'" @change="saveGroupReplies" />
            </div>
            <!-- 世界事件注入按钮（聊天专属功能，保留；世界时钟/速率已移至世界演化） -->
            <el-button v-if="currentConv" size="small" type="warning" text @click="openEventDialog">
              <el-icon><HIcon name="AlarmClock" /></el-icon>&nbsp;世界事件
            </el-button>
          </div>
        </div>
        <!-- 下方：对话详细信息（对话场景快照：所在地 + 世界时间，可编辑） -->
        <div v-if="currentConv" class="header-scene">
          <span class="scene-item" title="对话所在地（留空=通过手机等远程通讯软件对话）">📍 {{ currentConv.location || '远程通讯' }}</span>
          <span v-if="currentConv.gameTimeText" class="scene-item" title="对话发生时的世界时间（快照）">🕐 {{ currentConv.gameTimeText }}</span>
          <el-button size="small" text type="primary" @click="openSceneDialog">编辑场景</el-button>
        </div>
      </header>

      <!-- 对话流容器 -->
      <div class="chat-body" ref="bodyRef">
        <!-- Hero 空态：未选择会话 -->
        <div v-if="!currentConv" class="chat-hero">
          <h2 class="hero-title">与你的角色对话</h2>
          <p class="hero-sub">选择一个会话，或从下方角色快捷开始</p>
          <div v-if="characters.length" class="hero-chars">
            <button v-for="ch in characters.slice(0, 6)" :key="ch.id" class="hero-char" @click="quickChat(ch)">
              <el-avatar :size="26" class="hero-char-avatar">{{ ch.name?.charAt(0) }}</el-avatar>
              <span>{{ ch.name }}</span>
            </button>
          </div>
          <el-button type="primary" round @click="openCreateConv">新建会话</el-button>
        </div>
        <!-- Hero 空态：有会话无消息 -->
        <div v-else-if="messages.length === 0" class="chat-hero compact">
          <p class="hero-sub">开始和 {{ currentRole?.name || '角色' }} 对话吧</p>
          <div v-if="currentConv" class="hero-scene">
            <span>📍 {{ currentConv.location || '远程通讯' }}</span>
            <span v-if="currentConv.gameTimeText">🕐 {{ currentConv.gameTimeText }}</span>
            <el-button size="small" text type="primary" @click="openSceneDialog">编辑场景</el-button>
          </div>
        </div>
        <!-- 消息流（居中 748px 列） -->
        <div v-else class="chat-flow">
          <template v-for="m in messages" :key="m.id || m._key">
            <!-- 世界事件卡片：居中特殊样式 -->
            <div v-if="m.type === 'event'" class="event-row">
              <div class="event-card">
                <div class="event-label">📣 世界事件</div>
                <div class="event-content">{{ m.content }}</div>
              </div>
            </div>
            <!-- 普通消息：用户右气泡 / AI 左气泡（Markdown） -->
            <div v-else :class="['msg-row', m.role]">
              <div class="msg-bubble" :class="m.role">
                <div v-if="m.role === 'assistant' && m.characterName" class="msg-name">{{ m.characterName }}</div>
                <div v-if="m.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(m.content)"></div>
                <span v-else class="msg-text">{{ m.content }}</span>
                <span v-if="m.streaming" class="cursor">▍</span>
              </div>
            </div>
          </template>
          <!-- 生成中状态（DSH TurnStatus：流光 + 15s 后计时） -->
          <div v-if="streaming" class="turn-status" role="status" aria-live="polite">
            <span class="ts-shimmer">Deep diving...</span>
            <span v-if="elapsedSec >= 15" class="ts-clock">{{ fmtElapsed() }}</span>
          </div>
        </div>
      </div>

      <!-- 输入区（DSH 输入框：圆角卡片、自动增高、Enter 发送） -->
      <div v-if="currentConv" class="composer-wrap">
        <div class="composer-card">
          <div class="composer-scroll">
            <textarea
              ref="taRef"
              v-model="input"
              class="composer-input"
              rows="2"
              :placeholder="streaming ? '角色正在思考…' : '输入消息，Enter 发送，Shift+Enter 换行'"
              :disabled="streaming"
              @input="autoResize"
              @keydown.enter.exact.prevent="send"
            ></textarea>
          </div>
          <div class="composer-row">
            <div class="composer-tools">
              <el-tooltip content="注入世界事件" placement="top" :show-after="500">
                <button class="tool-btn" type="button" title="注入世界事件" @click="openEventDialog">
                  <el-icon :size="15"><HIcon name="AlarmClock" /></el-icon>
                </button>
              </el-tooltip>
            </div>
            <div class="composer-trailing">
              <span class="hint">{{ streaming ? '角色正在输入…' : 'Enter 发送' }}</span>
              <el-tooltip :content="'发送'" placement="top" :show-after="500">
                <button
                  class="send-btn"
                  type="button"
                  :title="'发送 (Enter)'"
                  :disabled="!input.trim() || streaming"
                  @click="send"
                >
                  <HIcon name="Send" :size="16" />
                </button>
              </el-tooltip>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 右栏：角色个人信息（角色卡 + Prompt），群聊支持角色切换 -->
    <div class="chat-info">
      <div class="info-header">
        <span>角色信息</span>
        <el-tag v-if="currentRole" size="small" effect="plain">{{ currentRole.name }}</el-tag>
      </div>
      <div v-if="!currentRole" class="side-empty">选择会话后展示角色卡与 Prompt</div>
      <template v-else>
        <!-- 群聊多角色切换 -->
        <div v-if="roleList.length > 1" class="role-tabs">
          <div v-for="r in roleList" :key="r.id" class="role-tab" :class="{ active: r.id === currentRole.id }" @click="selectRole(r)">
            {{ r.name }}
          </div>
        </div>
        <el-tabs v-model="infoTab" class="info-tabs" @tab-change="onInfoTabChange">
          <el-tab-pane v-if="roleCard" label="结构化角色卡" name="card">
            <!-- 角色卡 JSON 前端解析分节渲染（对齐 NPC 角色页形式，非原始 JSON 文本） -->
            <div v-if="!persona || Object.keys(persona).length === 0" class="side-empty">角色卡 JSON 无法解析</div>
            <template v-else>
              <div v-if="persona.identity" class="p-section">
                <div class="p-title">身份</div>
                <div class="p-grid">
                  <div class="p-cell"><span class="k">姓名</span><span class="v">{{ persona.identity?.name || '—' }}</span></div>
                  <div class="p-cell"><span class="k">头衔</span><span class="v">{{ persona.identity?.title || '—' }}</span></div>
                  <div class="p-cell"><span class="k">种族</span><span class="v">{{ persona.identity?.species || '—' }}</span></div>
                  <div class="p-cell"><span class="k">职业</span><span class="v">{{ persona.identity?.occupation || '—' }}</span></div>
                  <div class="p-cell"><span class="k">所属势力</span><span class="v">{{ persona.identity?.affiliation || '—' }}</span></div>
                  <div class="p-cell"><span class="k">年龄</span><span class="v">{{ persona.identity?.age ?? '—' }}</span></div>
                </div>
              </div>
              <div v-if="persona.personality" class="p-section">
                <div class="p-title">性格特质</div>
                <div class="p-text">特质：<el-tag v-for="(t, i) in list(persona.personality.traits)" :key="i" size="small" effect="plain" class="p-tag">{{ t }}</el-tag></div>
                <div class="p-label">价值观</div>
                <div class="p-tags"><el-tag v-for="(v, i) in list(persona.personality.values)" :key="i" size="small" type="success" effect="plain">{{ v }}</el-tag></div>
                <div class="p-label">怪癖/习惯</div>
                <div class="p-tags"><el-tag v-for="(q, i) in list(persona.personality.quirks)" :key="i" size="small" type="warning" effect="plain">{{ q }}</el-tag></div>
              </div>
              <div v-if="persona.background" class="p-section">
                <div class="p-title">背景与目标</div>
                <div class="p-text">{{ persona.background?.history || '—' }}</div>
                <div class="p-label">关键事件</div>
                <div class="p-tags"><el-tag v-for="(e, i) in list(persona.background.keyEvents)" :key="i" size="small" effect="plain">{{ e }}</el-tag></div>
                <div class="p-label">心结/创伤</div>
                <div class="p-tags"><el-tag v-for="(w, i) in list(persona.background.wounds)" :key="i" size="small" type="danger" effect="plain">{{ w }}</el-tag></div>
                <div class="p-label">目标</div>
                <div class="p-tags"><el-tag v-for="(g, i) in list(persona.background.goals)" :key="i" size="small" type="primary" effect="plain">{{ g }}</el-tag></div>
              </div>
              <div v-if="persona.speechStyle" class="p-section">
                <div class="p-title">说话风格</div>
                <div class="p-text">{{ persona.speechStyle?.tone || '—' }}</div>
                <div class="p-label">口头禅</div>
                <div class="p-tags"><el-tag v-for="(c, i) in list(persona.speechStyle.catchphrases)" :key="i" size="small" type="warning" effect="plain">{{ c }}</el-tag></div>
                <div class="p-label">禁忌</div>
                <div class="p-tags"><el-tag v-for="(t, i) in list(persona.speechStyle.taboos)" :key="i" size="small" type="danger" effect="plain">{{ t }}</el-tag></div>
              </div>
              <div v-if="persona.knowledge" class="p-section">
                <div class="p-title">知识边界</div>
                <div class="p-label">知晓</div>
                <div class="p-tags"><el-tag v-for="(k, i) in list(persona.knowledge.knows)" :key="i" size="small" type="success" effect="plain">{{ k }}</el-tag></div>
                <div class="p-label">不知道/不假装知道</div>
                <div class="p-tags"><el-tag v-for="(n, i) in list(persona.knowledge.notKnows)" :key="i" size="small" type="info" effect="plain">{{ n }}</el-tag></div>
              </div>
              <div v-if="list(persona.behaviorPatterns).length" class="p-section">
                <div class="p-title">行为模式（行动驱动）</div>
                <div class="p-blist">
                  <div v-for="(b, i) in list(persona.behaviorPatterns)" :key="i" class="p-bitem">· {{ b }}</div>
                </div>
              </div>
            </template>
          </el-tab-pane>
          <el-tab-pane v-if="roleCard" label="Prompt" name="prompt">
            <pre class="info-pre">{{ roleCard.systemPrompt }}</pre>
          </el-tab-pane>
          <!-- P4-1 长期记忆 Tab：角色记忆列表（fact/summary 分色、重要度标签、可删除）+ 手动抽取 -->
          <el-tab-pane label="记忆" name="memory">
            <div v-if="!roleCard" class="side-empty">该角色尚未生成角色卡（不影响长期记忆功能）</div>
            <div class="memory-ops">
              <span class="memory-hint">对话后 AI 自动抽取关键事实/摘要</span>
              <el-button size="small" text type="primary" :loading="extracting" @click="doExtractMemories">手动抽取</el-button>
            </div>
            <div v-loading="memoryLoading" class="memory-list">
              <div v-if="memories.length === 0" class="side-empty">暂无记忆，对话几轮后自动生成</div>
              <div v-for="m in memories" :key="m.id" class="memory-item" :class="m.kind">
                <div class="memory-head">
                  <el-tag size="small" :type="m.kind === 'summary' ? 'primary' : 'info'" effect="plain">
                    {{ m.kind === 'summary' ? '摘要' : '事实' }}
                  </el-tag>
                  <el-tag size="small" :type="impTag(m.importance)" effect="light">重要度 {{ m.importance }}</el-tag>
                  <el-button class="memory-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
                    <el-icon><HIcon name="Delete" /></el-icon>
                  </el-button>
                </div>
                <div class="memory-content">{{ m.content }}</div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <!-- 新建会话对话框：单聊 / 群聊（2026-08-18 恢复群聊 UI） -->
    <el-dialog v-model="createDialog.visible" :title="createDialog.mode === 'group' ? '新建群聊会话' : '新建单聊会话'" width="560px">
      <el-form label-width="82px">
        <el-form-item label="会话类型">
          <el-radio-group v-model="createDialog.mode" @change="onModeChange">
            <el-radio value="single">单聊（1 个角色）</el-radio>
            <el-radio value="group">群聊（多角色）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="createDialog.mode === 'group' ? '参与角色' : '角色'" required>
          <!-- 单聊：单选角色 -->
          <el-select v-if="createDialog.mode === 'single'" v-model="createDialog.characterId" placeholder="选择要对话的角色" style="width: 100%" @change="applyRuleTitle">
            <el-option v-for="ch in characters" :key="ch.id" :label="ch.name + (ch.title ? ' · ' + ch.title : '')" :value="ch.id" />
          </el-select>
          <!-- 群聊：多选角色（≥2） -->
          <el-select v-else v-model="createDialog.characterIds" multiple collapse-tags collapse-tags-tooltip placeholder="选择参与群聊的角色（至少 2 个）" style="width: 100%" @change="applyRuleTitle">
            <el-option v-for="ch in characters" :key="ch.id" :label="ch.name + (ch.title ? ' · ' + ch.title : '')" :value="ch.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="对话所在地">
          <el-select
            v-model="createDialog.location"
            filterable allow-create clearable default-first-option
            placeholder="选择或输入地点（如：王都酒馆、学院图书馆）"
            style="width: 100%"
            @change="applyRuleTitle"
          >
            <el-option v-for="loc in locationOptions" :key="loc" :label="loc" :value="loc" />
          </el-select>
          <div class="scene-tip">留空 = 默认通过手机等远程通讯软件进行对话（非面对面），直接影响 NPC 的回答</div>
        </el-form-item>
        <el-form-item label="当前时间">
          <div class="time-row">
            <el-input :model-value="createDialog.gameTimeText || '加载中…'" readonly placeholder="调用世界时间">
              <template #append>
                <el-button :loading="timeLoading" @click="refreshSceneTime">刷新</el-button>
              </template>
            </el-input>
          </div>
          <div class="scene-tip">调用世界时间（世界时钟），创建时快照保存，随对话注入 NPC 回答</div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="createDialog.title" placeholder="自动生成专属标题，可手动修改" maxlength="100" @input="createDialog.titleTouched = true">
            <template #append>
              <el-button :disabled="createDialog.mode === 'group'" :loading="createDialog.aiTitleLoading" @click="doAiRewriteTitle">AI 重写</el-button>
            </template>
          </el-input>
          <div class="scene-tip">根据 角色 + 所在地 + 世界时间 自动生成；群聊不支持单角色 AI 标题，可直接手改</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreateConv">创建并开始对话</el-button>
      </template>
    </el-dialog>

    <!-- 编辑对话场景对话框：修改所在地 / 世界时间（影响后续 NPC 回答） -->
    <el-dialog v-model="sceneDialog.visible" title="编辑对话场景" width="540px">
      <el-form label-width="82px">
        <el-form-item label="对话所在地">
          <el-select
            v-model="sceneDialog.location"
            filterable allow-create clearable default-first-option
            placeholder="选择或输入地点（如：王都酒馆、学院图书馆）"
            style="width: 100%"
          >
            <el-option v-for="loc in locationOptions" :key="loc" :label="loc" :value="loc" />
          </el-select>
          <div class="scene-tip">留空 = 通过手机等远程通讯软件进行对话（保存后影响后续 NPC 回答）</div>
        </el-form-item>
        <el-form-item label="当前时间">
          <div class="time-row">
            <el-input :model-value="sceneDialog.gameTimeText || '加载中…'" readonly placeholder="调用世界时间">
              <template #append>
                <el-button :loading="timeLoading" @click="refreshSceneEditTime">刷新</el-button>
              </template>
            </el-input>
          </div>
          <div class="scene-tip">调用世界时间，保存后作为对话场景快照</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sceneDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="sceneDialog.saving" @click="saveScene">保存场景</el-button>
      </template>
    </el-dialog>

    <!-- 世界事件注入对话框 -->
    <el-dialog v-model="eventDialog.visible" title="注入世界事件" width="520px">
      <el-form label-width="90px">
        <el-form-item label="事件内容">
          <el-input v-model="eventDialog.text" type="textarea" :rows="5" resize="none" placeholder="描述发生了什么（时间/地点/事件/对在场角色的影响）" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="AI 生成">
          <el-switch v-model="eventDialog.generate" />
          <span class="event-tip">开启后由 AI 按世界观模板生成事件；两者可同时（优先手填，AI 生成仅在文本为空时生效）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="eventDialog.visible = false">取消</el-button>
        <el-button type="warning" :loading="eventDialog.loading" @click="submitWorldEvent">注入并触发回应</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 对话页（P1 单聊 SSE + P2 群聊/世界事件，按桌面布局）。
 * <p>职责：实现设计文档 §7.3 ③ 对话——左会话/角色、中对话流（气泡 + SSE 打字机）、右角色信息。
 * 支持：单聊/群聊创建；群聊 AI 按发言欲望逐角色流式回复（message-start/token/done 事件）；
 * 世界事件注入（手填或 AI 生成）并触发在场角色回应；群聊「无玩家轮次」自主推进（P4 预留）。</p>
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchConversations, createConversation, fetchMessages, sendMessage, deleteConversation,
  fetchCharacters, fetchCharacterCard, conversationStreamUrl,
  injectWorldEvent, advanceConversation,
  fetchGroupChatConfig, saveGroupChatConfig,
  fetchMemories, deleteMemory, extractCharacterMemories,
  fetchWorldClock,
  fetchWorldSetting, updateConversationScene, generateConversationTitle,
  fetchWorldLocations
} from '@/shared/api'
import { renderMarkdown } from '@/shared/markdown'

const route = useRoute()
const projectId = Number(route.params.id)

const conversations = ref([])
const currentConv = ref(null)
const messages = ref([])
const characters = ref([])
const input = ref('')
const streaming = ref(false)
const bodyRef = ref(null)
const es = ref(null)
const taRef = ref(null)
// 生成中计时（DSH TurnStatus：15s 后显示耗时）
const streamStartAt = ref(null)
const elapsedSec = ref(0)
let streamTimer = null
// 群聊指定发言人（可选，空 = AI 调度）
const forceSpeaker = ref(null)
// 群聊每轮回复上限（默认 5，前端可改）
const groupReplies = ref(5)
// 单聊本地流式占位
let aiMsg = null

const createDialog = reactive({ visible: false, mode: 'single', characterId: null, characterIds: [], title: '', titleTouched: false, location: '', gameTimeText: '', aiTitleLoading: false })
const creating = ref(false)
// 对话所在地候选（从世界观地理文本启发式提取）+ 世界时间刷新 loading
const locationOptions = ref([])
const timeLoading = ref(false)
// 编辑对话场景弹窗（创建后修改所在地/世界时间）
const sceneDialog = reactive({ visible: false, location: '', gameTimeText: '', saving: false })
// 世界事件对话框
const eventDialog = reactive({ visible: false, text: '', generate: false, loading: false })
// 右栏角色信息：群聊支持角色列表切换
const currentRole = ref(null)
const roleCard = ref(null)
const roleList = ref([])
const infoTab = ref('card')

// 解析后的角色卡 persona 对象（供「结构化角色卡」分节渲染，对齐 NPC 角色页形式）
// 防御：旧后端/旧数据可能返回「双重编码」JSON，JSON.parse 得到字符串而非对象时再解析一次
const persona = computed(() => {
  try {
    let p = roleCard.value?.personaJson ? JSON.parse(roleCard.value.personaJson) : {}
    if (typeof p === 'string') {
      try { p = JSON.parse(p) } catch (_) { p = {} }
    }
    return p && typeof p === 'object' ? p : {}
  } catch (_) { return {} }
})
// P4-1 长期记忆：当前角色的记忆列表
const memories = ref([])
const memoryLoading = ref(false)
const extracting = ref(false)
// P4-2 世界时钟：当前项目游戏时刻与推进控制
const worldClock = ref(null)

/** 是否群聊会话 */
function isGroup(c) { return c && c.mode === 'group' }

/** 群聊成员角色对象列表 */
function groupMembers(c) {
  const ids = (c && c.characterIds) || []
  return ids.map(id => characters.value.find(x => x.id === id)).filter(Boolean)
}

/** 角色名（按 ID 从角色列表解析） */
function charName(cid) {
  const ch = characters.value.find(x => x.id === cid)
  return ch ? ch.name : null
}

/** 滚动到底部 */
function scrollBottom() {
  nextTick(() => { bodyRef.value?.scrollTo({ top: bodyRef.value.scrollHeight }) })
}

/** 输入框自动增高（DSH 输入框行为：最多撑到 --composer-text-max-height） */
function autoResize() {
  const el = taRef.value
  if (!el) return
  el.style.height = 'auto'
  const max = 336 // --composer-text-max-height
  el.style.height = Math.min(el.scrollHeight, max) + 'px'
  nextTick(() => scrollBottom())
}

/** 生成耗时格式化 mm:ss */
function fmtElapsed() {
  const s = Math.max(0, elapsedSec.value)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

/** 开始生成计时（流光 + 时钟） */
function startStreamTimer() {
  stopStreamTimer()
  streamStartAt.value = Date.now()
  elapsedSec.value = 0
  streamTimer = setInterval(() => {
    elapsedSec.value = Math.floor((Date.now() - streamStartAt.value) / 1000)
  }, 1000)
}

/** 停止生成计时 */
function stopStreamTimer() {
  if (streamTimer) { clearInterval(streamTimer); streamTimer = null }
  streamStartAt.value = null
  elapsedSec.value = 0
}

/** 删除会话（连同消息）并刷新列表 */
async function removeConv(c) {
  try {
    await ElMessageBox.confirm(`确认删除会话「${c.title || '未命名会话'}」？相关消息将一并删除。`, '删除确认', { type: 'warning' })
    await deleteConversation(c.id)
    ElMessage.success('会话已删除')
    conversations.value = await fetchConversations(projectId)
    if (currentConv.value?.id === c.id) {
      currentConv.value = null
      messages.value = []
      currentRole.value = null
      roleCard.value = null
      roleList.value = []
    }
  } catch (_) { /* 用户取消或失败 */ }
}

/** 加载会话列表与角色（2026-08-18 恢复群聊：不再过滤 group 会话） */
async function loadAll() {
  try {
    conversations.value = await fetchConversations(projectId)
    characters.value = await fetchCharacters(projectId)
  } catch (e) { ElMessage.error(e.message || '加载失败') }
}

/** 加载当前会话角色信息（右栏：角色卡 + Prompt），群聊支持多角色切换 */
async function loadRoleInfo(c) {
  currentRole.value = null
  roleCard.value = null
  roleList.value = []
  const ids = c && c.characterIds
  if (!ids || ids.length === 0) return
  roleList.value = ids.map(id => characters.value.find(x => x.id === id) || { id, name: '角色' + id })
  await selectRole(roleList.value[0])
}

/** 选择右栏查看的角色 */
async function selectRole(ch) {
  currentRole.value = ch
  roleCard.value = null
  try { roleCard.value = await fetchCharacterCard(ch.id) } catch (_) { /* 无角色卡时保持空 */ }
  await loadMemories()
}

// ==================== P4-1 长期记忆 ====================

/** 重要度颜色映射（记忆标签） */
function impTag(i) { return i >= 4 ? 'danger' : (i >= 3 ? 'warning' : 'info') }

/** 加载当前角色的记忆列表（fact/summary） */
async function loadMemories() {
  if (!currentRole.value) { memories.value = []; return }
  memoryLoading.value = true
  try {
    const page = await fetchMemories(projectId, { characterId: currentRole.value.id, page: 1, size: 50 })
    memories.value = page.list || []
  } catch (e) { ElMessage.error(e.message || '加载记忆失败') }
  finally { memoryLoading.value = false }
}

/**
 * 右栏 Tab 切换回调：切到「记忆」Tab 时重新拉取记忆列表。
 * <p>背景：记忆为「对话回复完成后异步抽取」（后端约数秒落库），而记忆列表仅在
 * 选中角色时加载一次——若用户停留在对话页连续多轮对话，Tab 会一直显示陈旧数据
 * （如最初的「暂无记忆」）。切 Tab 时重新拉取保证所见即最新。</p>
 *
 * @param name 切换到的 Tab 名称（card/prompt/memory）
 */
function onInfoTabChange(name) {
  if (name === 'memory') loadMemories()
}

/** 删除单条记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条${m.kind === 'summary' ? '摘要' : '记忆'}？删除后不再注入后续对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch (_) { /* 用户取消 */ }
}

/** 手动触发当前角色记忆抽取（调试/补抽） */
async function doExtractMemories() {
  if (!currentRole.value) return
  extracting.value = true
  try {
    const res = await extractCharacterMemories(currentRole.value.id)
    ElMessage.success(`抽取完成，新增 ${res.added || 0} 条事实`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '抽取失败') }
  finally { extracting.value = false }
}

// ==================== P4-2 世界时钟 ====================

/** 加载项目世界时钟状态（创建/编辑对话场景时读取世界时间快照） */
async function loadWorldClock() {
  try { worldClock.value = await fetchWorldClock(projectId) } catch (_) { /* 无权限/失败时静默 */ }
}

/** 切换会话并加载历史 */
async function switchConv(c) {
  closeStream()
  forceSpeaker.value = null
  currentConv.value = c
  await loadMessages()
  loadRoleInfo(c)
  scrollBottom()
}

/** 加载当前会话历史消息 */
async function loadMessages() {
  if (!currentConv.value) { messages.value = []; return }
  try {
    const list = await fetchMessages(currentConv.value.id)
    messages.value = list.map(m => ({
      ...m, characterName: m.characterId ? charName(m.characterId) : null, _key: `h${m.id}`
    }))
  } catch (e) { ElMessage.error(e.message || '加载消息失败') }
}

/** 打开新建会话对话框（初始化场景字段：所在地/世界时间/规则标题；默认单聊） */
function openCreateConv() {
  if (characters.value.length === 0) return ElMessage.warning('请先在项目设置中添加角色')
  createDialog.mode = 'single'
  createDialog.characterId = null
  createDialog.characterIds = []
  createDialog.title = ''
  createDialog.titleTouched = false
  createDialog.location = ''
  createDialog.gameTimeText = ''
  createDialog.visible = true
  // 并行初始化：世界观地点候选 + 世界时间（世界时钟）
  loadLocationOptions()
  refreshSceneTime()
}

/** 会话类型切换（单聊/群聊）：重置另一字段并联动规则标题 */
function onModeChange() {
  if (createDialog.mode === 'single') {
    createDialog.characterIds = []
  } else {
    createDialog.characterId = null
  }
  applyRuleTitle()
}

/** 创建会话（单聊/群聊；携带对话场景：所在地 + 世界时间快照） */
async function doCreateConv() {
  const isGrp = createDialog.mode === 'group'
  const ids = isGrp
    ? (createDialog.characterIds || [])
    : (createDialog.characterId ? [createDialog.characterId] : [])
  if (!ids.length) return ElMessage.warning(isGrp ? '请选择参与群聊的角色' : '请选择角色')
  if (isGrp && ids.length < 2) return ElMessage.warning('群聊至少选择 2 个角色')
  creating.value = true
  try {
    const conv = await createConversation(projectId, {
      mode: createDialog.mode,
      title: createDialog.title || null,
      characterIds: ids,
      location: createDialog.location?.trim() || null,
      gameTimeText: createDialog.gameTimeText?.trim() || null
    })
    ElMessage.success(isGrp ? '群聊已创建' : '会话已创建')
    createDialog.visible = false
    await loadAll()
    // 进入空会话 Hero 空态（无消息时展示所在地/时间与编辑入口）
    await switchConv(conv)
  } catch (e) { ElMessage.error(e.message || '创建失败') } finally { creating.value = false }
}

/** 点击左侧角色：快捷创建单聊会话（预选角色并生成规则标题） */
function quickChat(ch) {
  currentConv.value = null
  openCreateConv()
  createDialog.mode = 'single'
  createDialog.characterId = ch.id
  applyRuleTitle()
}

// ==================== 对话场景（所在地 + 世界时间） ====================

/**
 * 从世界观地理文本启发式提取地点候选（按换行/常见分隔符切分，去重，限长）。
 *
 * @param geography 世界观「地理/地图设定」文本
 * @return 候选地点数组（≤30 条）
 */
function parseLocationOptions(geography) {
  if (!geography) return []
  const set = new Set()
  String(geography).split(/[\n\r、，,；;。.]+/).forEach(s => {
    const t = (s || '').trim()
    if (t.length >= 2 && t.length <= 24) set.add(t)
  })
  return [...set].slice(0, 30)
}

/** 加载对话所在地候选：地点表优先（名称列表），地点表为空时回退启发式切分；保留自由输入 */
async function loadLocationOptions() {
  try {
    const locs = await fetchWorldLocations(projectId)
    const names = (locs || []).map(l => l && l.name).filter(Boolean)
    if (names.length) {
      locationOptions.value = names
      return
    }
  } catch (_) { /* 地点表不可用则回退 */ }
  try {
    const w = await fetchWorldSetting(projectId)
    locationOptions.value = parseLocationOptions(w?.geography)
  } catch (_) { locationOptions.value = [] }
}

/** 世界时间精简显示：'世界历 0025年03月12日 14时30分' → '03月12日 14时30分'（标题用） */
function shortGameTime(t) {
  if (!t) return ''
  const m = String(t).match(/(\d{2})月(\d{2})日\s*(\d{2})时(\d{2})分/)
  return m ? `${m[1]}月${m[2]}日 ${m[3]}时${m[4]}分` : String(t)
}

/** 规则模板生成专属标题：角色（群聊=多角色名）· 所在地（或远程通讯） · 世界时间 */
function buildRuleTitle() {
  let name
  if (createDialog.mode === 'group') {
    name = (createDialog.characterIds || []).map(id => charName(id)).filter(Boolean).join('、')
  } else {
    const ch = characters.value.find(x => x.id === createDialog.characterId)
    name = ch?.name
  }
  const loc = createDialog.location?.trim() || '远程通讯'
  const time = shortGameTime(createDialog.gameTimeText)
  return [name, loc, time].filter(Boolean).join(' · ')
}

/** 应用规则标题（仅当用户未手动修改标题时，避免覆盖用户输入） */
function applyRuleTitle() {
  if (createDialog.titleTouched) return
  createDialog.title = buildRuleTitle()
}

/** 刷新世界时间（创建弹窗）：调用世界时钟，联动规则标题 */
async function refreshSceneTime() {
  timeLoading.value = true
  try {
    await loadWorldClock()
    createDialog.gameTimeText = worldClock.value?.gameTimeText || ''
  } catch (_) { /* 世界时钟不可用时保持原值 */ }
  finally { timeLoading.value = false }
  applyRuleTitle()
}

/** AI 重写标题：调用后端 AI 生成，失败回退规则标题（群聊不支持，按钮已禁用） */
async function doAiRewriteTitle() {
  if (createDialog.mode === 'group') return ElMessage.info('群聊暂不支持单角色 AI 标题，可直接手动修改')
  if (!createDialog.characterId) return ElMessage.warning('请先选择角色')
  createDialog.aiTitleLoading = true
  try {
    const res = await generateConversationTitle(projectId, {
      characterId: createDialog.characterId,
      location: createDialog.location?.trim() || undefined,
      gameTimeText: createDialog.gameTimeText?.trim() || undefined
    })
    createDialog.title = res.title
    createDialog.titleTouched = true // AI 结果视为已确认，后续字段变化不再覆盖
    ElMessage.success('AI 标题已生成')
  } catch (e) { ElMessage.error(e.message || 'AI 标题生成失败，已保留当前标题') }
  finally { createDialog.aiTitleLoading = false }
}

/** 打开编辑对话场景弹窗（会话创建后修改所在地/世界时间） */
function openSceneDialog() {
  if (!currentConv.value) return
  sceneDialog.location = currentConv.value.location || ''
  sceneDialog.gameTimeText = currentConv.value.gameTimeText || ''
  sceneDialog.visible = true
  // 候选与创建弹窗一致：地点表优先 + 自由输入
  loadLocationOptions()
  refreshSceneEditTime()
}

/** 刷新世界时间（编辑场景弹窗） */
async function refreshSceneEditTime() {
  timeLoading.value = true
  try {
    await loadWorldClock()
    sceneDialog.gameTimeText = worldClock.value?.gameTimeText || ''
  } catch (_) { /* 忽略 */ }
  finally { timeLoading.value = false }
}

/** 保存对话场景：更新会话（location 空串=清空为远程通讯），并刷新列表与头部展示 */
async function saveScene() {
  if (!currentConv.value) return
  sceneDialog.saving = true
  try {
    const conv = await updateConversationScene(currentConv.value.id, {
      location: (sceneDialog.location || '').trim(),
      gameTimeText: (sceneDialog.gameTimeText || '').trim()
    })
    currentConv.value = conv
    await loadAll()
    ElMessage.success('对话场景已更新，将影响后续 NPC 回答')
    sceneDialog.visible = false
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { sceneDialog.saving = false }
}

/** 发送消息：单聊预建占位；群聊交由 SSE 动态创建多角色消息 */
async function send() {
  const content = input.value.trim()
  if (!content || !currentConv.value || streaming.value) return
  input.value = ''
  streaming.value = true

  // 本地先推 user 消息
  const userMsg = { id: `u${Date.now()}`, role: 'user', type: 'text', content, streaming: false, _key: `u${Date.now()}` }
  messages.value.push(userMsg)
  scrollBottom()

  try {
    const data = await sendMessage(currentConv.value.id, { content, forceCharacterId: isGroup(currentConv.value) ? forceSpeaker.value || undefined : undefined })
    if (isGroup(currentConv.value)) {
      // 群聊：不预建占位，靠 SSE message-start 动态创建
      await openStream(data.forceCharacterId || undefined)
    } else {
      aiMsg = { id: data.assistantMessageId, role: 'assistant', type: 'text', content: '', streaming: true, _key: 'ai' }
      messages.value.push(aiMsg)
      scrollBottom()
      await openStream()
    }
  } catch (e) {
    streaming.value = false
    stopStreamTimer()
    ElMessage.error(e.message || '发送失败')
  }
}

/** 打开 SSE 流式通道（单聊/群聊共用） */
async function openStream(forceCharacterId) {
  closeStream()
  const convId = currentConv.value && currentConv.value.id
  if (!convId) {
    // 会话尚未就绪（如创建中/未选择）：直接终止，避免请求 /api/conversations/null/stream 触发后端 400
    streaming.value = false
    ElMessage.warning("会话尚未就绪，请先创建或选择会话")
    return
  }
  streaming.value = true
  startStreamTimer()
  const source = new EventSource(conversationStreamUrl(convId, forceCharacterId))
  es.value = source

  source.addEventListener('message-start', (ev) => {
    // 群聊：新角色消息动态创建
    const d = JSON.parse(ev.data)
    messages.value.push({
      id: d.assistantMessageId, characterId: d.characterId,
      characterName: d.characterName || charName(d.characterId),
      role: 'assistant', type: 'text', content: '', streaming: true, _key: `s${d.assistantMessageId}`
    })
    scrollBottom()
  })

  source.addEventListener('token', (ev) => {
    const d = JSON.parse(ev.data)
    const row = isGroup(currentConv.value)
      ? [...messages.value].reverse().find(m => m.streaming && m.characterId === d.characterId)
      : aiMsg
    if (row) { row.content += d.delta; scrollBottom() }
  })

  source.addEventListener('done', async (ev) => {
    const d = JSON.parse(ev.data)
    if (isGroup(currentConv.value)) {
      if (d.group) {
        // 整轮结束
        closeStream()
        await loadMessages()
      } else if (d.characterId) {
        // 单角色回复完成：回填最终内容
        const row = messages.value.find(m => m.id === d.assistantMessageId)
        if (row) { row.streaming = false; row.content = d.content || row.content }
        scrollBottom()
      }
    } else {
      closeStream()
      aiMsg.streaming = false
      try {
        const fresh = await fetchMessages(convId)
        const last = fresh[fresh.length - 1]
        if (last) { aiMsg.content = last.content || aiMsg.content; aiMsg.id = last.id }
      } catch (_) { /* 忽略刷新失败 */ }
      scrollBottom()
    }
    // P4-1：对话轮次完成后，后端会异步抽取长期记忆（AI 抽取约数秒后才落库），
    // 延迟刷新右栏「记忆」Tab，避免一直显示陈旧空数据（如最初的「暂无记忆」）。
    setTimeout(() => { if (currentRole.value) loadMemories() }, 3000)
  })

  source.addEventListener('error', async (ev) => {
    closeStream()
    let msg = '对话生成失败，请重试'
    try { msg = JSON.parse(ev.data).message || msg } catch (_) { /* 忽略 */ }
    if (!isGroup(currentConv.value) && aiMsg) { aiMsg.streaming = false; aiMsg.content = aiMsg.content || msg }
    ElMessage.error(msg)
    scrollBottom()
  })

  source.onerror = () => {
    // EventSource 自身错误（如连接关闭）：已 done 则忽略
  }
}

/** 关闭当前 SSE 流 */
function closeStream() {
  if (es.value) { es.value.close(); es.value = null }
  streaming.value = false
  stopStreamTimer()
}

/** 打开世界事件对话框 */
function openEventDialog() {
  eventDialog.text = ''
  eventDialog.generate = false
  eventDialog.visible = true
}

/** 注入世界事件并触发在场角色回应 */
async function submitWorldEvent() {
  if (!eventDialog.text.trim() && !eventDialog.generate) {
    return ElMessage.warning('请填写事件文本或开启 AI 生成')
  }
  eventDialog.loading = true
  try {
    await injectWorldEvent(currentConv.value.id, {
      text: eventDialog.text.trim() || undefined,
      generate: eventDialog.generate
    })
    ElMessage.success('世界事件已注入，角色开始回应…')
    eventDialog.visible = false
    eventDialog.text = ''
    eventDialog.generate = false
    await loadMessages()
    // 打开 SSE 触发在场角色回应（单聊/群聊共用链路）
    await openStream()
  } catch (e) { ElMessage.error(e.message || '事件注入失败') }
  finally { eventDialog.loading = false }
}

/** 「无玩家轮次」自主推进一轮（群聊，P4 预留） */
async function doAdvance() {
  if (!currentConv.value || streaming.value) return
  try {
    await advanceConversation(currentConv.value.id)
    await openStream()
  } catch (e) { ElMessage.error(e.message || '自主推进失败') }
}

/** 加载群聊每轮回复上限配置 */
async function loadGroupConfig() {
  try {
    const res = await fetchGroupChatConfig()
    groupReplies.value = res.maxReplies || 5
  } catch (_) { /* 默认 5 */ }
}

/** 保存群聊每轮回复上限（前端可改，持久化） */
async function saveGroupReplies(val) {
  try {
    const res = await saveGroupChatConfig({ maxReplies: val })
    groupReplies.value = res.maxReplies
    ElMessage.success(`每轮回复上限已设为 ${res.maxReplies}`)
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}

/** 角色卡 JSON 美化显示 */
function prettyJson(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch (_) { return text }
}

/** 数组安全取值（角色卡 persona 字段可能为数组/字符串/缺失，统一转数组供分节渲染） */
function list(v) {
  if (Array.isArray(v)) return v
  if (typeof v === 'string' && v.trim()) return v.split(/[、,，;；]/).filter(Boolean)
  return []
}

/** 会话时间显示 */
function convTime(t) {
  if (!t) return ''
  return String(t).slice(0, 16).replace('T', ' ')
}

onMounted(() => { loadAll(); loadGroupConfig(); loadWorldClock() })
onBeforeUnmount(closeStream)
</script>

<style scoped>
/* ===== 三栏布局（令牌化，随深浅主题） =====
   高度取父级 .proj-content 内容区 100%（自动适应顶栏/内边距），替代旧的 magic calc(100vh-140px)：
   保证消息流始终占满剩余高度，不会因头部被撑高而压成细条。 */
.chat-view { display: flex; gap: 16px; height: 100%; min-height: 0; }
.replies-ctl { display: inline-flex; align-items: center; gap: 4px; margin-right: 8px; }
.replies-label { font-size: 0.78rem; color: var(--text-secondary); }

/* ===== 左栏：会话 + 角色 ===== */
.chat-side {
  width: 260px; background: var(--bg-layer-1); border-radius: var(--radius-lg);
  border: 1px solid var(--border-l1); padding: 12px; display: flex; flex-direction: column;
  overflow-y: auto; box-shadow: var(--shadow-lv1); flex-shrink: 0;
}
.side-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.side-empty { color: var(--text-secondary); font-size: 0.82rem; padding: 12px 4px; }
.conv-list { display: flex; flex-direction: column; gap: 4px; }
.conv-item { padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; display: flex; align-items: center; gap: 8px; }
.conv-text { flex: 1; min-width: 0; }
.conv-del { flex-shrink: 0; opacity: 0; transition: opacity .15s; }
.conv-item:hover .conv-del { opacity: 1; }
.conv-item:hover { background: var(--bg-hover); }
.conv-item.active { background: var(--accent-soft); }
.conv-title { font-size: 0.85rem; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conv-mode-tag { margin-left: 4px; transform: translateY(-1px); }
.conv-sub { font-size: 0.75rem; color: var(--text-secondary); }
.char-avatar { flex-shrink: 0; }

/* ===== 中栏：对话流（DSH 风格） ===== */
.chat-main {
  flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg);
  border: 1px solid var(--border-l1); display: flex; flex-direction: column;
  overflow: hidden; box-shadow: var(--shadow-lv1); min-width: 0;
}
.chat-header {
  padding: 12px 20px; border-bottom: 1px solid var(--border-l1);
  display: flex; flex-direction: column; align-items: stretch; gap: 6px; flex-shrink: 0;
}
.header-top {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0;
}
/* 顶部：对话标题 —— flex:1 吃满剩余空间 + 单行省略（仅窗口极窄时才截断，悬浮可看全文） */
.chat-title {
  font-weight: 600; color: var(--text-primary); font-size: 1rem;
  flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* 头部操作区：不参与收缩（避免把标题挤没），空间不足时整体换行到第二行 */
.header-ops {
  display: flex; align-items: center; gap: 8px; flex-shrink: 0;
  flex-wrap: wrap; justify-content: flex-end;
}
/* 对话场景展示（头部第二行：所在地 + 世界时间 + 编辑入口） */
.header-scene { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.scene-item {
  font-size: 0.78rem; color: var(--text-secondary);
  background: var(--bg-layer-2); border: 1px solid var(--border-l1);
  border-radius: 999px; padding: 2px 10px; white-space: nowrap;
}
.chat-body { flex: 1; overflow-y: auto; min-height: 0; }
/* DSH 居中内容列 */
.chat-flow {
  max-width: var(--chat-content-width);
  margin: 0 auto; padding: 20px 16px;
  display: flex; flex-direction: column; gap: 16px;
}

/* --- 消息：用户右气泡 / AI 左气泡 --- */
.msg-row { display: flex; min-width: 0; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }
.msg-bubble {
  max-width: min(525px, 82%);
  border-radius: 22px; padding: 10px 16px;
  font-size: 16px; line-height: 24px;
  word-break: break-word; white-space: pre-wrap;
}
.msg-bubble.user {
  background: var(--accent); color: var(--accent-contrast);
  border-bottom-right-radius: 6px;
}
.msg-bubble.assistant {
  background: var(--bg-layer-3); color: var(--text-primary);
  border: 1px solid var(--border-l1);
  border-bottom-left-radius: 6px;
}
.msg-bubble.assistant :deep(.markdown-body) { white-space: normal; }
.msg-text { white-space: pre-wrap; }
.msg-name { font-size: 12px; color: var(--text-tertiary); margin-bottom: 3px; font-weight: 500; }
.cursor { animation: blink 1s infinite; color: var(--accent); }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* --- 世界事件卡片（居中） --- */
.event-row { display: flex; justify-content: center; }
.event-card {
  background: var(--accent-softer); border: 1px solid var(--accent-border);
  border-radius: 14px; padding: 10px 16px; max-width: min(560px, 90%);
}
.event-label { font-size: 12px; color: var(--accent-text); font-weight: 600; margin-bottom: 4px; }
.event-content { font-size: 14px; color: var(--text-secondary); white-space: pre-wrap; word-break: break-word; line-height: 1.6; }

/* --- 生成中状态（DSH TurnStatus：流光文字 + 计时） --- */
.turn-status {
  height: 26px; font-size: 14px; font-weight: 500; line-height: 24px;
  align-self: flex-start; display: inline-flex; align-items: center; flex: none;
}
.ts-shimmer {
  background: linear-gradient(90deg, var(--accent) 0%, var(--accent) 40%, color-mix(in srgb, var(--accent) 60%, var(--text-primary)) 50%, var(--accent) 60%, var(--accent) 100%);
  color: transparent;
  -webkit-background-clip: text; background-clip: text;
  background-position: 100% 0; background-size: 250% 100%;
  animation: ts-shimmer 1.8s linear infinite;
  white-space: nowrap;
}
@keyframes ts-shimmer { to { background-position: 0 0; } }
.ts-clock {
  font-size: 12px; font-variant-numeric: tabular-nums; color: var(--text-tertiary);
  margin-left: 8px; font-weight: 400;
}
@media (prefers-reduced-motion: reduce) { .ts-shimmer { animation: none; background: var(--text-tertiary); -webkit-text-fill-color: var(--text-tertiary); } }

/* --- Hero 空态（DSH 风格） --- */
.chat-hero {
  height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; padding: 0 24px; text-align: center;
}
.chat-hero.compact { height: 100%; }
.hero-title { font-size: 26px; font-weight: 500; line-height: 32px; color: var(--text-primary); }
.hero-sub { font-size: 14px; color: var(--text-tertiary); }
/* 空态场景胶囊：所在地 + 世界时间 + 编辑入口 */
.hero-scene {
  display: inline-flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: center;
  font-size: 0.85rem; color: var(--text-secondary);
  background: var(--bg-layer-2); border: 1px solid var(--border-l1);
  border-radius: 14px; padding: 8px 16px;
}
.hero-chars { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; max-width: 480px; margin: 6px 0 4px; }
.hero-char {
  display: inline-flex; align-items: center; gap: 6px;
  background: var(--bg-layer-2); border: 1px solid var(--border-l1); color: var(--text-secondary);
  border-radius: 999px; padding: 4px 12px 4px 4px; cursor: pointer;
  font-size: 13px; transition: all .15s var(--dsw-ease);
}
.hero-char:hover { border-color: var(--accent-border); color: var(--text-primary); background: var(--accent-soft); }
.hero-char-avatar { flex-shrink: 0; background: var(--accent); }

/* --- 输入区（DSH 输入框：居中卡片） --- */
.composer-wrap {
  flex-shrink: 0; padding: 10px var(--composer-side-clearance) 14px;
  display: flex; justify-content: center; position: relative; z-index: 2;
  background: linear-gradient(180deg, transparent 0px, var(--bg-layer-1) 36px);
}
.composer-card {
  width: 100%; max-width: var(--composer-max-width);
  background: var(--bg-layer-1); border: 1px solid var(--border-l2);
  border-radius: 22px; padding: 12px 14px 8px;
  box-shadow: var(--shadow-lv2);
  display: flex; flex-direction: column; gap: 6px;
  transition: border-color .15s var(--dsw-ease), box-shadow .15s var(--dsw-ease);
}
.composer-card:focus-within { border-color: var(--accent-border); box-shadow: var(--shadow-lv3); }
.composer-scroll { max-height: var(--composer-text-max-height); overflow-y: auto; }
.composer-input {
  width: 100%; border: none; outline: none; resize: none;
  background: transparent; color: var(--text-primary);
  font-size: 16px; line-height: 24px; font-family: inherit;
  padding: 2px 2px 4px;
}
.composer-input::placeholder { color: var(--text-placeholder); }
.composer-input:disabled { cursor: not-allowed; opacity: .6; }
.composer-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.composer-tools { display: flex; align-items: center; gap: 4px; }
.tool-btn {
  width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-tertiary); background: transparent; border: none; border-radius: 999px;
  cursor: pointer; transition: all .15s var(--dsw-ease);
}
.tool-btn:hover { background: var(--bg-hover); color: var(--text-secondary); }
.composer-trailing { display: flex; align-items: center; gap: 8px; }
.hint { font-size: 12px; color: var(--text-tertiary); }
.send-btn {
  width: 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center;
  background: var(--accent); color: var(--accent-contrast); border: none; border-radius: 999px;
  cursor: pointer; transition: all .15s var(--dsw-ease); box-shadow: var(--shadow-lv1);
}
.send-btn:hover:not(:disabled) { background: var(--accent-strong); transform: translateY(-1px); }
.send-btn:disabled { background: var(--bg-layer-3); color: var(--text-placeholder); cursor: not-allowed; }

/* ===== 右栏：角色信息 ===== */
.chat-info {
  width: 340px; background: var(--bg-layer-1); border-radius: var(--radius-lg);
  border: 1px solid var(--border-l1); padding: 12px; display: flex; flex-direction: column;
  overflow-y: auto; box-shadow: var(--shadow-lv1); flex-shrink: 0;
}
.info-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.info-tabs { flex: 1; min-height: 0; }
.info-pre { margin: 0; padding: 10px; background: var(--bg-layer-2); border: 1px solid var(--border-l1); border-radius: var(--radius-sm); font-family: var(--font-mono); font-size: 0.78rem; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: calc(100vh - 300px); overflow-y: auto; }
/* 结构化角色卡分节渲染（右栏紧凑版） */
.p-section { margin-bottom: 12px; }
.p-title { font-weight: 700; color: var(--text-primary); font-size: 0.82rem; margin-bottom: 6px; }
.p-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 10px; }
.p-cell { min-width: 0; }
.p-cell .k { font-size: 0.7rem; color: var(--text-secondary); display: block; }
.p-cell .v { font-size: 0.8rem; color: var(--text-primary); font-weight: 600; word-break: break-word; }
.p-label { font-size: 0.72rem; color: var(--text-secondary); margin: 6px 0 3px; }
.p-text { font-size: 0.8rem; color: var(--text-regular); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.p-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.p-tag { margin-left: 2px; }
.p-blist { display: flex; flex-direction: column; gap: 3px; }
.p-bitem { font-size: 0.8rem; color: var(--text-regular); line-height: 1.5; }
.role-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.role-tab { padding: 3px 10px; border-radius: 12px; font-size: 0.78rem; background: var(--bg-layer-2); color: var(--text-secondary); cursor: pointer; border: 1px solid var(--border-l1); }
.role-tab.active { background: var(--accent-soft); color: var(--accent-text); border-color: var(--accent-border); }

.event-tip { font-size: 0.75rem; color: var(--text-secondary); margin-left: 10px; }
/* 创建/编辑场景弹窗辅助样式 */
.scene-tip { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.5; margin-top: 4px; width: 100%; }
.time-row { width: 100%; }

/* P4-1 长期记忆 Tab */
.memory-ops { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.memory-hint { font-size: 0.72rem; color: var(--text-secondary); }
.memory-list { display: flex; flex-direction: column; gap: 8px; max-height: calc(100vh - 340px); overflow-y: auto; }
.memory-item { border: 1px solid var(--border-l1); border-radius: 8px; padding: 8px 10px; }
.memory-item.summary { background: color-mix(in srgb, var(--state-success) 8%, transparent); }
.memory-item.fact { background: var(--bg-layer-2); }
.memory-head { display: flex; align-items: center; gap: 6px; }
.memory-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-del { opacity: 1; }
.memory-content { font-size: 0.8rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* ===== 窄屏自适应：收缩左右固定列，避免把中栏对话流压成细竖条 ===== */
@media (max-width: 1320px) {
  .chat-side { width: 220px; }
  .chat-info { width: 300px; }
}
@media (max-width: 1160px) {
  /* 窗口过窄时隐藏右栏角色信息，保证对话流可用（角色卡在「NPC 角色」页仍可查看） */
  .chat-info { display: none; }
  .chat-side { width: 200px; }
}
</style>