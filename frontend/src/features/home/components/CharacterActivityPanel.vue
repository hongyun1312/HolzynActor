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
    新首页左栏「角色活动流」（M4，features/home/components）。
    职责：角色卡列表（实时状态/需求条/心情/想法/移动中状态）+ 选中角色的插话/触发行动干预。
    数据与操作均由父组件传入/派发（本组件纯展示 + 交互事件上抛）。
  -->
  <div class="hm-activity">
    <div class="hm-panel-head">
      <span class="hm-panel-title">角色活动流</span>
      <el-tag size="small" effect="plain">{{ npcs.length }} 名</el-tag>
      <el-button v-if="filter === 'special'" text size="small" class="hm-filter-btn" @click="emit('change-filter', '')">全部</el-button>
      <el-button v-else text size="small" class="hm-filter-btn" @click="emit('change-filter', 'special')">仅特殊</el-button>
    </div>
    <div class="hm-activity-list">
      <div
        v-for="n in npcs"
        :key="n.npcRef"
        class="hm-char"
        :class="{ selected: selectedRef === n.npcRef }"
        @click="emit('select', n.npcRef)"
      >
        <div class="hm-char-head">
          <span class="hm-char-name">{{ n.npcName || n.npcRef }}</span>
          <el-tag size="small" :type="moveTagType(n.moveState)" effect="plain">{{ moveLabel(n.moveState) }}</el-tag>
        </div>
        <div v-if="n.moveState === 'walking' && movingTarget(n)" class="hm-char-moving">
          走向 {{ movingTarget(n) }} · {{ travelProgress(n) }}
        </div>
        <div class="hm-char-action">{{ n.actionText || '—' }}</div>
        <!-- 需求条（special：饥饿/膀胱/精力/心情；crowd：饥饿/精力/心情） -->
        <div v-if="n.hunger != null" class="hm-needs">
          <div class="hm-need">
            <span class="hm-need-k">饥</span>
            <el-progress :percentage="n.hunger" :show-text="false" :stroke-width="4" :color="needColor(n.hunger)" />
          </div>
          <div v-if="n.npcType === 'special' || n.bladder != null" class="hm-need">
            <span class="hm-need-k">盥</span>
            <el-progress :percentage="n.bladder" :show-text="false" :stroke-width="4" :color="needColor(n.bladder)" />
          </div>
          <div class="hm-need">
            <span class="hm-need-k">精</span>
            <el-progress :percentage="n.energy" :show-text="false" :stroke-width="4" :color="needColor(n.energy)" />
          </div>
          <div class="hm-need">
            <span class="hm-need-k">心</span>
            <el-progress :percentage="n.mood" :show-text="false" :stroke-width="4" :color="moodColor(n.mood)" />
          </div>
        </div>
        <div v-if="n.urgencyLevel > 0" class="hm-urgent">⚠ {{ n.urgencyText || '有紧急需求' }}</div>
        <div v-if="n.thoughtText" class="hm-thought">💭 {{ n.thoughtText }}</div>
      </div>
      <div v-if="!npcs.length" class="hm-empty">
        暂无角色状态<br /><span>去「角色 / 普通人群」页创建后回到本页观察</span>
      </div>
    </div>
    <!-- 选中角色：状态详情 + 插话 / 触发行动；未选中：世界干预 -->
    <div v-if="selected" class="hm-intervene">
      <div class="hm-in-title">{{ selected.npcName || selected.npcRef }} · 干预</div>
      <div v-if="selected.mood != null || selected.dominantEmotion != null" class="hm-sd">
        <div v-if="selected.urgencyLevel > 0" class="hm-sd-urgent">⚠ {{ selected.urgencyText || '有紧急需求' }}</div>
        <div v-if="selected.dominantEmotion" class="hm-sd-emotion">
          情绪：{{ emotionLabel(selected.dominantEmotion) }}
          <span class="hm-sd-em-int">强度 {{ selected.emotionIntensity ?? 0 }}</span>
        </div>
        <div v-if="selected.health != null" class="hm-sd-health">
          健康：{{ selected.health }}
          <el-progress :percentage="selected.health || 0" :show-text="false" :stroke-width="5" :color="healthColor(selected.health)" />
        </div>
      </div>
      <el-input v-model="talkText" size="small" placeholder="对 TA 说一句话…" @keyup.enter="doTalk">
        <template #append>
          <el-button :disabled="!talkText.trim()" :loading="talking" @click="doTalk">插话</el-button>
        </template>
      </el-input>
      <div class="hm-in-btns">
        <el-button
          v-if="selected.npcType === 'special'"
          size="small" :loading="acting" @click="emit('act', selected.npcRef)"
        >触发行动</el-button>
        <el-tag v-else size="small" type="info" effect="plain">人群无行动触发</el-tag>
      </div>
    </div>
    <div v-else class="hm-intervene">
      <div class="hm-in-title">世界干预</div>
      <el-input v-model="eventText" size="small" placeholder="向世界注入一条事件…" @keyup.enter="doEvent">
        <template #append>
          <el-button :disabled="!eventText.trim()" :loading="injecting" @click="doEvent">注入</el-button>
        </template>
      </el-input>
    </div>
  </div>
</template>

<script setup>
/**
 * 新首页左栏「角色活动流」（M4）。
 * <p>职责：角色卡列表（实时/需求/心情/想法/移动中状态）+ 选中角色干预（插话/触发行动）
 * + 未选中时世界事件注入。纯展示组件：数据由父组件传入，交互事件上抛。</p>
 */
import { computed, ref } from 'vue'

const props = defineProps({
  /** 角色状态列表（沙盘状态数组，含需求/心情/想法/移动状态） */
  npcs: { type: Array, default: () => [] },
  /** 选中角色引用 */
  selectedRef: { type: String, default: null },
  /** 在途移动表（npcRef → {targetLocationName, progress}；移动中状态展示用） */
  movingMap: { type: Object, default: () => ({}) },
  /** 当前筛选（'' 全部 / 'special' 仅特殊） */
  filter: { type: String, default: '' }
})
const emit = defineEmits(['select', 'change-filter', 'talk', 'act', 'event-inject'])

const talkText = ref('')
const eventText = ref('')
const talking = ref(false)
const acting = ref(false)
const injecting = ref(false)

const selected = computed(() => props.npcs.find((n) => n.npcRef === props.selectedRef) || null)

/** 移动中角色 → 目标地点名（来自移动表；无则回退 actionText 截词） */
function movingTarget(n) {
  const m = props.movingMap[n.npcRef]
  if (m && m.targetLocationName) return m.targetLocationName
  return null
}

/** 移动进度百分比文案 */
function travelProgress(n) {
  const m = props.movingMap[n.npcRef]
  if (!m || m.progress == null) return ''
  return `${Math.round(m.progress * 100)}%`
}

function moveLabel(s) {
  return ({ idle: '空闲', walking: '移动中', acting: '行动中', talking: '交谈中', resting: '歇息中' })[s] || s || '—'
}
function moveTagType(s) {
  return ({ idle: 'info', walking: 'warning', acting: 'warning', talking: 'success', resting: 'info' })[s] || 'info'
}
function needColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 30) return '#ff6b6b'
  if (v < 60) return '#ffb84d'
  return '#52d68a'
}
function moodColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 40) return '#ff6b6b'
  if (v < 65) return '#ffb84d'
  return '#52d68a'
}
function healthColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 25) return '#ff6b6b'
  if (v < 70) return '#ffb84d'
  return '#52d68a'
}
function emotionLabel(e) {
  return ({ calm: '平静', happy: '高兴', angry: '愤怒', sad: '悲伤', fearful: '恐惧', surprised: '惊讶', disgusted: '厌恶' })[e] || e || '—'
}

async function doTalk() {
  const content = talkText.value.trim()
  if (!selected.value || !content) return
  talking.value = true
  try {
    await emit('talk', { npcRef: selected.value.npcRef, content })
    talkText.value = ''
  } finally {
    talking.value = false
  }
}
async function doEvent() {
  const content = eventText.value.trim()
  if (!content) return
  injecting.value = true
  try {
    await emit('event-inject', content)
    eventText.value = ''
  } finally {
    injecting.value = false
  }
}
</script>

<style scoped>
.hm-activity {
  display: flex; flex-direction: column;
  height: 100%; min-height: 0;
  background: rgba(13, 20, 36, 0.85); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  overflow: hidden;
}
.hm-panel-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; border-bottom: 1px solid rgba(120, 160, 255, 0.18); flex-shrink: 0;
}
.hm-panel-title { font-size: 13px; font-weight: 700; color: #e8f0ff; }
.hm-filter-btn { margin-left: auto; }
.hm-activity-list { flex: 1; overflow-y: auto; padding: 6px; }
.hm-char {
  padding: 8px 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
  border: 1px solid transparent; transition: background 0.15s, border-color 0.15s;
}
.hm-char:hover { background: rgba(77, 159, 255, 0.12); }
.hm-char.selected { background: rgba(77, 159, 255, 0.18); border-color: rgba(77, 159, 255, 0.5); }
.hm-char-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.hm-char-name { font-size: 12.5px; font-weight: 700; color: #e8f0ff; }
.hm-char-moving { font-size: 11px; color: #ffd45c; margin-top: 3px; font-weight: 600; }
.hm-char-action { font-size: 11px; color: #9db8e8; margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hm-needs { margin-top: 5px; display: flex; flex-direction: column; gap: 2px; }
.hm-need { display: flex; align-items: center; gap: 5px; font-size: 10px; color: #9db8e8; }
.hm-need .el-progress { flex: 1; }
.hm-need-k { flex-shrink: 0; width: 16px; color: #6b7fa8; }
.hm-urgent { font-size: 10.5px; color: #ff6b6b; margin-top: 3px; font-weight: 600; }
.hm-thought { font-size: 11px; color: #ffe9a8; margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hm-empty { font-size: 12px; color: #9db8e8; text-align: center; padding: 26px 10px; line-height: 1.8; }
.hm-empty span { font-size: 11px; color: #6b7fa8; }
.hm-intervene {
  padding: 10px 12px; border-top: 1px solid rgba(120, 160, 255, 0.18);
  display: flex; flex-direction: column; gap: 8px; flex-shrink: 0;
}
.hm-in-title { font-size: 12px; font-weight: 700; color: #9db8e8; }
.hm-sd { display: flex; flex-direction: column; gap: 5px; padding: 7px 9px; border-radius: 8px; background: rgba(77, 159, 255, 0.08); border: 1px solid rgba(120, 160, 255, 0.22); }
.hm-sd-urgent { font-size: 11px; font-weight: 700; color: #ff8f8f; }
.hm-sd-emotion { font-size: 12px; font-weight: 700; color: #ffe9a8; }
.hm-sd-em-int { font-size: 10px; font-weight: 400; color: #9db8e8; margin-left: 6px; }
.hm-sd-health { font-size: 11px; color: #9db8e8; }
.hm-in-btns { display: flex; align-items: center; gap: 8px; }
.hm-activity-list::-webkit-scrollbar { width: 5px; }
.hm-activity-list::-webkit-scrollbar-thumb { background: rgba(120, 160, 255, 0.3); border-radius: 3px; }
</style>
