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
  <!--
    新首页「对戏/演绎」抽屉（M4，features/home/components）。
    职责：旧演化页（EvolveView）废弃后保留的核心能力——演化会话列表 + 开始演化（手动/AI）+
    连续播放（SSE 流式） + 加入/退场 + 结束归档 + 删除。复用既有演化 API，零后端改动。
  -->
  <el-drawer
    v-model="open"
    title="对戏 / 演绎"
    size="520px"
    :destroy-on-close="false"
    class="hm-roleplay"
  >
    <div class="rp-body">
      <!-- 演化会话列表 -->
      <div class="rp-head">
        <span class="rp-title">演化会话</span>
        <el-button size="small" type="primary" @click="openStart()">开始演化</el-button>
      </div>
      <div class="rp-list">
        <div
          v-for="ev in evolutions" :key="ev.id"
          class="rp-item" :class="{ active: current?.id === ev.id }"
          @click="selectEvolution(ev)"
        >
          <div class="rp-item-head">
            <span class="rp-item-title">{{ ev.title || '演化' }}</span>
            <el-tag size="small" effect="plain">{{ ev.status }}</el-tag>
          </div>
          <div class="rp-item-meta">
            {{ ev.mode }} · {{ (ev.participantCount || 0) }} 人 · 第 {{ (ev.gameDay || 0) + 1 }} 日
            <el-button text size="small" class="rp-item-del" @click.stop="doDelete(ev)">删除</el-button>
          </div>
        </div>
        <div v-if="!evolutions.length" class="rp-empty">
          暂无演化会话<br /><span>「开始演化」新建一场（手动选角或 AI 自动选角）</span>
        </div>
      </div>

      <!-- 当前演化：参与者 + 播放控制 + 消息流 -->
      <template v-if="current">
        <div class="rp-current">
          <div class="rp-current-head">
            <span class="rp-title">{{ current.title || '演化' }}</span>
            <div class="rp-ops">
              <el-button
                v-if="current.status === 'running'"
                size="small" :type="streaming ? 'warning' : 'success'"
                @click="streaming ? stopPlayback() : startPlayback()"
              >{{ streaming ? '停止播放' : '开始播放' }}</el-button>
              <el-button size="small" :loading="finishing" @click="doFinish()">结束归档</el-button>
            </div>
          </div>
          <!-- 参与者 -->
          <div class="rp-participants">
            <span class="rp-sub">参与者（{{ activeParticipants.length }}）</span>
            <div class="rp-p-list">
              <el-tag
                v-for="p in activeParticipants" :key="p.characterId"
                size="small" closable @close="doLeave(p.characterId)"
              >{{ p.characterName || charName(p.characterId) }}</el-tag>
              <el-select
                v-model="joinCharId" size="small" placeholder="+ 加入" clearable class="rp-join"
                @change="doJoin"
              >
                <el-option
                  v-for="c in notInSceneChars" :key="c.id"
                  :label="c.name" :value="c.id"
                />
              </el-select>
            </div>
          </div>
          <!-- 消息流 -->
          <div ref="streamRef" class="rp-stream">
            <div v-for="t in turns" :key="t.id" class="rp-turn" :class="'type-' + t.type">
              <span v-if="t.type === 'schedule'" class="rp-schedule">{{ t.content }}</span>
              <span v-else-if="t.type === 'system'" class="rp-system">{{ t.content }}</span>
              <template v-else>
                <span class="rp-turn-name">{{ t.characterName || charName(t.characterId) }}</span>
                <span class="rp-turn-text">{{ t.content }}<span v-if="t.streaming" class="rp-cursor">▍</span></span>
              </template>
            </div>
            <div v-if="!turns.length" class="rp-empty">选择一场演化并「开始播放」查看实时对戏流</div>
          </div>
        </div>
      </template>

      <!-- 开始演化对话框 -->
      <el-dialog v-model="startDialog.visible" title="开始演化" width="460px" append-to-body>
        <el-form label-width="90px">
          <el-form-item label="模式">
            <el-radio-group v-model="startDialog.mode">
              <el-radio value="manual">手动选角</el-radio>
              <el-radio value="ai">AI 自动选角</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="startDialog.mode === 'manual'" label="参与角色">
            <el-select v-model="startDialog.characterIds" multiple placeholder="选择参与角色" style="width: 100%">
              <el-option v-for="c in characters" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="场景">
            <el-select v-model="startDialog.sceneId" clearable placeholder="（可选）选择场景" style="width: 100%">
              <el-option v-for="s in scenes" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="背景">
            <el-input v-model="startDialog.background" type="textarea" :rows="3" placeholder="（可选）描述这场演绎的背景" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="startDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="starting" @click="doStart()">开始</el-button>
        </template>
      </el-dialog>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 新首页「对戏/演绎」抽屉（M4）。
 * <p>职责：旧演化页废弃后保留的核心能力——会话列表/开始（手动/AI）/连续播放（SSE 流式）/
 * 加入退场/结束归档/删除。复用既有演化 API（world.js）与 createGeneration SSE 客户端。</p>
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createGeneration, SSE_EVENTS } from '@/shared/sse/sseClient'
import {
  fetchEvolutions, startEvolution, fetchEvolutionDetail,
  joinEvolution, leaveEvolution, finishEvolution, deleteEvolution,
  fetchCharacters, fetchScenes
} from '@/shared/api'

const props = defineProps({
  /** 项目 ID */
  projectId: { type: Number, default: null },
  /** 抽屉开关（v-model） */
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

// ===== 演化会话 =====
const evolutions = ref([])
const current = ref(null)
const turns = ref([])
const activeParticipants = ref([])
const characters = ref([])
const scenes = ref([])
const joinCharId = ref(null)
const finishing = ref(false)
const starting = ref(false)
const startDialog = reactive({ visible: false, mode: 'manual', sceneId: null, background: '', characterIds: [] })

import { reactive } from 'vue'

/** 不在场景中的角色（可加入候选） */
const notInSceneChars = computed(() => {
  const inIds = new Set(activeParticipants.value.map((p) => p.characterId))
  return characters.value.filter((c) => !inIds.has(c.id))
})

const streamRef = ref(null)
const streaming = ref(false)
let genClient = null

/** 角色名 */
function charName(id) {
  const ch = characters.value.find((c) => c.id === id)
  return ch ? ch.name : '角色'
}

function scrollStream() {
  nextTick(() => { streamRef.value?.scrollTo({ top: streamRef.value.scrollHeight }) })
}

/** 打开抽屉时加载基础数据 */
watch(open, async (v) => {
  if (!v) { closePlayback(); return }
  loadBase()
})

async function loadBase() {
  try { characters.value = await fetchCharacters(props.projectId) } catch (_) { characters.value = [] }
  try { scenes.value = await fetchScenes(props.projectId) } catch (_) { scenes.value = [] }
  await loadEvolutions()
}

async function loadEvolutions() {
  try {
    evolutions.value = await fetchEvolutions(props.projectId)
    if (current.value) {
      const fresh = evolutions.value.find((e) => e.id === current.value.id)
      if (fresh) current.value = fresh
    }
  } catch (_) { evolutions.value = [] }
}

async function selectEvolution(ev) {
  closePlayback()
  current.value = ev
  joinCharId.value = null
  await loadDetail(ev.id)
}

async function loadDetail(id) {
  try {
    const detail = await fetchEvolutionDetail(id)
    current.value = detail
    turns.value = detail.turns || []
    activeParticipants.value = (detail.participants || []).filter((p) => p.status === 'active')
    scrollStream()
  } catch (e) { ElMessage.error(e.message || '加载演化失败') }
}

function openStart() {
  Object.assign(startDialog, { visible: true, mode: 'manual', sceneId: null, background: '', characterIds: [] })
}

async function doStart() {
  if (startDialog.mode === 'manual' && startDialog.characterIds.length === 0) {
    return ElMessage.warning('手动模式请至少选择 1 位参与角色')
  }
  starting.value = true
  try {
    const ev = await startEvolution(props.projectId, {
      mode: startDialog.mode,
      sceneId: startDialog.sceneId || undefined,
      background: startDialog.background || undefined,
      characterIds: startDialog.mode === 'ai' ? undefined : startDialog.characterIds
    })
    ElMessage.success(`演化「${ev.title || '演化'}」已开始`)
    startDialog.visible = false
    await loadEvolutions()
    await selectEvolution(ev)
    if (ev.status === 'running') startPlayback()
  } catch (e) { ElMessage.error(e.message || '开始失败') }
  finally { starting.value = false }
}

// ===== 连续播放（SSE 流式） =====

function evolutionStreamUrl(id) {
  return `/api/evolutions/${id}/stream`
}

function startPlayback() {
  if (!current.value || current.value.status !== 'running') return
  if (streaming.value) return
  closePlayback()
  streaming.value = true
  genClient = createGeneration({
    url: evolutionStreamUrl(current.value.id),
    events: [SSE_EVENTS.SCHEDULE, SSE_EVENTS.SYSTEM, SSE_EVENTS.MESSAGE_START,
      SSE_EVENTS.TOKEN, SSE_EVENTS.DONE, SSE_EVENTS.FINISHED, SSE_EVENTS.ERROR],
    isTerminal: (name) => name === SSE_EVENTS.FINISHED,
    onEvent: (name, d) => handlePlaybackEvent(name, d),
    onDone: (d) => handlePlaybackFinished(d),
    onError: (err) => handlePlaybackError(err)
  })
}

function handlePlaybackEvent(name, d) {
  if (!d || typeof d !== 'object') return
  if (name === SSE_EVENTS.SCHEDULE) {
    const nameText = d.characterId ? charName(d.characterId) : '？'
    const kind = d.beatType === 'action' ? '行动' : '接话'
    turns.value.push({
      id: `sch-${Date.now()}-${Math.random()}`, type: 'schedule', streaming: false,
      content: `「${nameText}」想${kind}（欲望 ${d.desire}）：${d.reason || ''}`
    })
    scrollStream()
  } else if (name === SSE_EVENTS.SYSTEM) {
    turns.value.push({ id: `sys-${Date.now()}-${Math.random()}`, type: 'system', streaming: false, content: d.content })
    scrollStream()
  } else if (name === SSE_EVENTS.MESSAGE_START) {
    turns.value.push({
      id: `s-${Date.now()}`, type: d.type === 'action' ? 'action' : 'text', streaming: true,
      characterId: d.characterId, characterName: d.characterName, content: ''
    })
    scrollStream()
  } else if (name === SSE_EVENTS.TOKEN) {
    const row = [...turns.value].reverse().find((t) => t.streaming && t.characterId === d.characterId)
    if (row) { row.content += d.delta; scrollStream() }
  } else if (name === SSE_EVENTS.DONE) {
    const row = [...turns.value].reverse().find((t) => t.streaming && t.characterId === d.characterId)
    if (row) { row.streaming = false; row.content = d.content || row.content }
    scrollStream()
  }
}

async function handlePlaybackFinished() {
  closePlayback()
  ElMessage.success('场景只剩 1 人，演化已自动收尾并归档（参与者获得角色级记忆）')
  await loadEvolutions()
  if (current.value) await loadDetail(current.value.id)
}

async function handlePlaybackError(err) {
  let msg = err?.message || '演化播放失败，请重试'
  if (err && err.kind === 'event' && err.data && err.data.message) msg = err.data.message
  closePlayback()
  ElMessage.error(msg)
  if (current.value) await loadDetail(current.value.id)
}

function stopPlayback() {
  closePlayback()
  ElMessage.info('已停止播放，演化保持进行中（可随时再次「开始播放」）')
}

function closePlayback() {
  if (genClient) { genClient.close(); genClient = null }
  streaming.value = false
}

async function doJoin() {
  if (!joinCharId.value || !current.value) return
  try {
    await joinEvolution(current.value.id, joinCharId.value)
    ElMessage.success('角色已加入')
    joinCharId.value = null
    await loadDetail(current.value.id)
  } catch (e) { ElMessage.error(e.message || '加入失败') }
}

async function doLeave(characterId) {
  if (!current.value) return
  try {
    await leaveEvolution(current.value.id, characterId)
    ElMessage.success('角色已退场')
    await loadDetail(current.value.id)
  } catch (e) { ElMessage.error(e.message || '退场失败') }
}

async function doFinish() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('确认结束这场演化并归档为事件？参与者将获得该事件的角色级记忆（仅当事人知晓）。', '结束确认', { type: 'warning' })
  } catch (_) { return }
  finishing.value = true
  try {
    const res = await finishEvolution(current.value.id)
    ElMessage.success(`已归档：${res.event?.title || '世界演化'}（${res.memoryCount} 条角色级记忆，仅当事人）`)
    await loadEvolutions()
    if (current.value) await loadDetail(current.value.id)
  } catch (e) { ElMessage.error(e.message || '归档失败') }
  finally { finishing.value = false }
}

async function doDelete(ev) {
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
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

onBeforeUnmount(() => closePlayback())
</script>

<style scoped>
.rp-body { display: flex; flex-direction: column; gap: 12px; height: 100%; min-height: 0; }
.rp-head { display: flex; align-items: center; justify-content: space-between; }
.rp-title { font-size: 13px; font-weight: 700; color: var(--text-primary, #e8f0ff); }
.rp-list { display: flex; flex-direction: column; gap: 6px; max-height: 30%; overflow-y: auto; }
.rp-item { padding: 8px 10px; border-radius: 8px; cursor: pointer; border: 1px solid var(--border-light, rgba(120,160,255,0.22)); transition: all 0.15s; }
.rp-item:hover { border-color: var(--brand-primary, #409eff); }
.rp-item.active { border-color: var(--brand-primary, #409eff); background: var(--brand-gradient-soft, rgba(77,159,255,0.14)); }
.rp-item-head { display: flex; align-items: center; gap: 6px; }
.rp-item-title { font-size: 13px; font-weight: 600; color: var(--text-primary, #e8f0ff); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rp-item-meta { font-size: 11px; color: var(--text-secondary, #9db8e8); display: flex; align-items: center; gap: 6px; }
.rp-item-del { margin-left: auto; }
.rp-current { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }
.rp-current-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.rp-ops { display: flex; align-items: center; gap: 6px; }
.rp-participants { display: flex; flex-direction: column; gap: 4px; }
.rp-sub { font-size: 11px; font-weight: 700; color: var(--text-secondary, #9db8e8); }
.rp-p-list { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.rp-join { width: 120px; }
.rp-stream {
  flex: 1; min-height: 0; overflow-y: auto;
  border: 1px solid var(--border-light, var(--hm-border)); border-radius: 8px;
  padding: 10px; display: flex; flex-direction: column; gap: 6px;
  background: var(--bg-layer-1, var(--hm-panel));
}
.rp-turn { font-size: 12.5px; line-height: 1.7; }
.rp-schedule { color: var(--text-secondary, var(--hm-text-dim)); font-size: 11.5px; }
.rp-system { color: var(--hm-orange, #ff8f6b); font-size: 11.5px; }
.rp-turn-name { font-weight: 700; color: var(--hm-gold, #ffd45c); margin-right: 6px; }
.rp-turn-text { color: var(--text-primary, var(--hm-text)); word-break: break-word; }
.rp-cursor { color: var(--hm-green, #52d68a); animation: blink 0.8s infinite; }
.rp-empty { font-size: 11.5px; color: var(--text-placeholder, #6b7fa8); text-align: center; padding: 16px 8px; line-height: 1.8; }
.rp-empty span { font-size: 10.5px; }
.rp-stream::-webkit-scrollbar { width: 5px; }
.rp-stream::-webkit-scrollbar-thumb { background: var(--hm-border, rgba(120, 160, 255, 0.3)); border-radius: 3px; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.2; } }
</style>
