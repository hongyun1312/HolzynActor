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
  <div :class="['dlg-msg', msg.role]" :data-key="msg._key">
    <!-- 角色头像（说话人身份一眼可辨；玩家消息不加头像，靠右对齐 + 主色气泡区分） -->
    <el-avatar v-if="isNpc" class="dlg-avatar" :size="28">{{ initial }}</el-avatar>

    <div class="dlg-col">
      <!-- 说话人 + 标记（移到气泡外：与主流 IM 一致，气泡内只留正文） -->
      <div v-if="isNpc" class="dlg-msg-head">
        <span class="dlg-name">{{ msg.characterName || '角色' }}</span>
        <el-tag v-if="msg.kind === 'greeting'" size="small" type="success" effect="plain">开场白</el-tag>
        <el-tag v-if="swipes.length" size="small" type="info" effect="plain">备选 {{ swipes.length + 1 }}</el-tag>
      </div>

      <div class="dlg-bubble" :class="[msg.role, { 'is-streaming': msg.streaming }]">
        <!-- 内容：编辑态 textarea / 用户纯文本 / 角色 Markdown -->
        <div v-if="editing" class="dlg-edit">
          <el-input v-model="draft" type="textarea" :rows="3" resize="none" maxlength="2000" />
          <div class="dlg-edit-ops">
            <el-button size="small" text @click="editing = false">取消</el-button>
            <el-button size="small" type="primary" :disabled="!draft.trim()" @click="saveEdit">保存</el-button>
          </div>
        </div>
        <div v-else-if="isNpc" class="markdown-body" v-html="renderMarkdown(msg.content || '')"></div>
        <span v-else class="dlg-user-text">{{ msg.content }}</span>

        <!-- 打字机光标 -->
        <span v-if="msg.streaming" class="dlg-cursor">▍</span>
      </div>

      <!--
        操作条（P1-2）：原来是 hover 才 display:none → 触屏与不熟用户完全发现不了。
        现改为常显 + 低对比（opacity .5，悬停该条消息升到 1），既不抢视线又可发现/可点按。
      -->
      <div v-if="!msg.streaming" class="dlg-ops">
        <template v-if="msg.role === 'assistant'">
          <el-tooltip content="换一条（轮换备选回复）" :show-after="500">
            <button type="button" class="dlg-op" :disabled="swipes.length === 0" @click="$emit('swipe', msg)" title="换一条">
              <el-icon :size="13"><HIcon name="ic_public_refresh" /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="重写这条回复（其后消息作废并重新生成）" :show-after="500">
            <button type="button" class="dlg-op text" @click="$emit('rewrite', msg)" title="重写">重写</button>
          </el-tooltip>
        </template>
        <template v-else-if="msg.role === 'group'">
          <!-- 群聊重写是已知边界（多角色串行回复，重写会打断整轮）：不给"点不动"的按钮，只做说明 -->
          <el-tooltip content="群聊暂不支持重写：一轮回复包含多位角色，重写需整轮重来（单聊可用）" :show-after="300">
            <button type="button" class="dlg-op text is-off" disabled title="群聊暂不支持重写">重写</button>
          </el-tooltip>
        </template>
        <template v-else>
          <el-tooltip content="编辑这条发言（其后消息作废）" :show-after="500">
            <button type="button" class="dlg-op" @click="startEdit" title="编辑">
              <el-icon :size="13"><HIcon name="Edit" /></el-icon>
            </button>
          </el-tooltip>
        </template>
        <el-tooltip content="复制内容" :show-after="500">
          <button type="button" class="dlg-op" @click="copy" title="复制">
            <el-icon :size="13"><HIcon name="CopyDocument" /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="删除这条发言（连同其后所有消息）" :show-after="500">
          <button type="button" class="dlg-op danger" @click="$emit('delete', msg)" title="删除">
            <el-icon :size="13"><HIcon name="Delete" /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 对谈消息气泡（单条）。
 * <p>职责：角色/玩家气泡渲染（角色 Markdown + 玩家纯文本）、打字机光标、
 * 开场白/备选标记、操作条（换一条/重写/复制/编辑/删除）；编辑为组件内联态，
 * 提交经 emit 由视图调后端接口。</p>
 *
 * <p><b>P1-2 可发现性</b>：操作条原来只在 hover 时 `display:none → inline-flex`，
 * 触屏与不熟用户无从发现。现改为常显 + 低对比（默认 opacity .5，悬停该条消息升到 1）：
 * 静止时不抢视线，但图标始终在那儿可点。群聊的「重写」是已知边界（多角色串行回复），
 * 给禁用按钮 + 明确原因提示，而不是"按了没反应"。</p>
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { renderMarkdown } from '@/shared/markdown'

const props = defineProps({
  /** 本地消息对象（含 _key/swipes/kind 归一化字段） */
  msg: { type: Object, required: true }
})
const emit = defineEmits(['swipe', 'rewrite', 'delete', 'edit'])

const editing = ref(false)
const draft = ref('')

/** 是否角色方消息（单聊 assistant / 群聊 group 均显示头像与说话人） */
const isNpc = computed(() => props.msg.role === 'assistant' || props.msg.role === 'group')

/** 头像文字（角色名首字） */
const initial = computed(() => (props.msg.characterName || '角色').charAt(0))

/** 备选回复队列（metaJson.swipes，视图已归一化到 msg.swipes） */
const swipes = computed(() => (props.msg && Array.isArray(props.msg.swipes)) ? props.msg.swipes : [])

/** 开始编辑（仅玩家消息） */
function startEdit() {
  draft.value = props.msg.content || ''
  editing.value = true
}

/** 保存编辑 → 交视图调后端 */
function saveEdit() {
  if (!draft.value.trim()) return
  editing.value = false
  emit('edit', props.msg, draft.value.trim())
}

/** 复制内容 */
async function copy() {
  try {
    await navigator.clipboard.writeText(props.msg.content || '')
    ElMessage.success('已复制')
  } catch (_) {
    ElMessage.error('复制失败')
  }
}
</script>

<style scoped>
.dlg-msg { display: flex; gap: 8px; align-items: flex-start; }
.dlg-msg.user { flex-direction: row-reverse; }

.dlg-avatar {
  flex-shrink: 0; margin-top: 1px;
  background: var(--accent-soft); color: var(--accent-text);
  font-size: 0.74rem; font-weight: 600;
}

/* 气泡列：宽度上限放在列上（气泡自身不再各自算 max-width） */
.dlg-col { display: flex; flex-direction: column; min-width: 0; max-width: 84%; }
.dlg-msg.user .dlg-col { align-items: flex-end; }

.dlg-bubble {
  max-width: 100%;
  padding: 9px 13px;
  border-radius: 12px;
  font-size: 0.86rem;
  line-height: 1.65;
  word-break: break-word;
  white-space: pre-wrap;
}
.dlg-bubble.user {
  background: color-mix(in srgb, var(--state-primary) 14%, var(--bg-layer-1));
  border: 1px solid color-mix(in srgb, var(--state-primary) 30%, transparent);
  border-bottom-right-radius: 4px;
}
.dlg-bubble.assistant, .dlg-bubble.group {
  background: var(--bg-layer-2);
  border: 1px solid var(--border-l1);
  border-bottom-left-radius: 4px;
}
.dlg-bubble.group { border-left: 2px solid color-mix(in srgb, var(--state-primary) 45%, transparent); }
.dlg-bubble.is-streaming { box-shadow: 0 0 0 1px color-mix(in srgb, var(--state-primary) 30%, transparent); }

.dlg-msg-head { display: flex; align-items: center; gap: 6px; margin: 0 0 4px 2px; }
.dlg-name { font-weight: 600; color: var(--accent-text, var(--text-primary)); font-size: 0.78rem; }

.dlg-user-text { white-space: pre-wrap; }
.dlg-cursor { color: var(--accent); animation: dlg-blink 1s steps(2) infinite; }
@keyframes dlg-blink { 50% { opacity: 0; } }

/* 编辑态 */
.dlg-edit { width: 320px; max-width: 100%; }
.dlg-edit-ops { display: flex; justify-content: flex-end; gap: 4px; margin-top: 6px; }

/* 操作条（P1-2：常显低对比 → 悬停该条消息升为可用态） */
.dlg-ops {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  margin: 2px 0 0 2px;
  padding: 1px 2px;
  border-radius: 7px;
  opacity: .5;
  transition: opacity .16s var(--dsw-ease), background-color .16s var(--dsw-ease);
}
.dlg-msg:hover .dlg-ops, .dlg-ops:focus-within { opacity: 1; background: var(--bg-layer-2); border: 1px solid var(--border-l1); padding: 0 1px; }
.dlg-op {
  border: none; background: transparent; cursor: pointer;
  color: var(--text-secondary); border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 22px; padding: 0;
}
.dlg-op.text { width: auto; padding: 0 6px; font-size: 0.72rem; }
.dlg-op:hover { color: var(--text-primary); background: var(--bg-layer-1); }
.dlg-op.danger:hover { color: var(--state-error); }
.dlg-op:disabled { color: var(--text-placeholder); cursor: not-allowed; }
.dlg-op.is-off { text-decoration: line-through; }
</style>
