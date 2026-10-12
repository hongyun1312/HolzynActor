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
    新首页「干预」抽屉（M4，features/home/components）。
    职责：对选中角色插话/触发行动 + 世界事件注入 + 时钟干预（速率/暂停/推进）。
    复用既有 sandbox intervene 端点；操作结果经父组件事件上抛（事件流可见）。
  -->
  <el-drawer
    v-model="open"
    title="世界干预"
    size="420px"
    :destroy-on-close="false"
    class="iv-drawer"
  >
    <div class="iv-body">
      <!-- 选中角色干预 -->
      <div class="iv-section">
        <div class="iv-title">角色干预</div>
        <el-select
          v-model="targetRef" size="small" placeholder="选择角色" filterable clearable style="width: 100%"
        >
          <el-option v-for="n in npcs" :key="n.npcRef" :label="n.npcName || n.npcRef" :value="n.npcRef">
            <span>{{ n.npcName || n.npcRef }}</span>
            <el-tag v-if="n.npcType === 'special'" size="small" type="warning" effect="plain" style="margin-left: 8px">特殊</el-tag>
          </el-option>
        </el-select>
        <div v-if="target" class="iv-target">
          {{ target.npcName || target.npcRef }} · {{ moveLabel(target.moveState) }} ·
          {{ target.actionText || '—' }}
        </div>
        <el-input v-model="talkText" size="small" placeholder="对 TA 说一句话…" @keyup.enter="doTalk">
          <template #append>
            <el-button :disabled="!talkText.trim() || !target" :loading="talking" @click="doTalk">插话</el-button>
          </template>
        </el-input>
        <div class="iv-btns">
          <el-button
            v-if="target && target.npcType === 'special'"
            size="small" :loading="acting" @click="doAction"
          >触发行动</el-button>
          <el-tag v-else-if="target" size="small" type="info" effect="plain">人群无行动触发</el-tag>
        </div>
      </div>

      <!-- 世界事件注入 -->
      <div class="iv-section">
        <div class="iv-title">世界事件注入</div>
        <el-input v-model="eventText" size="small" type="textarea" :rows="2" placeholder="向世界注入一条事件…" />
        <el-button size="small" :disabled="!eventText.trim()" :loading="injecting" @click="doEvent">注入事件</el-button>
      </div>

      <!-- 时钟干预 -->
      <div class="iv-section">
        <div class="iv-title">时钟干预</div>
        <div class="iv-clock-row">
          <el-select v-model="rateValue" size="small" style="width: 90px" @change="doRate">
            <el-option v-for="r in [1, 2, 4, 8, 12, 24, 48, 96]" :key="r" :label="`×${r}`" :value="r" />
          </el-select>
          <el-button size="small" :type="clock.paused ? 'success' : 'warning'" :loading="clockBusy" @click="doPause">
            {{ clock.paused ? '继续' : '暂停' }}
          </el-button>
          <el-button size="small" :loading="advancing" @click="doAdvance">推进一轮</el-button>
        </div>
        <div class="iv-hint">时钟干预与顶栏同步（世界时钟权威在后端 actor_world_clock）。</div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 新首页「干预」抽屉（M4）。
 * <p>职责：角色插话/触发行动 + 世界事件注入 + 时钟干预。复用既有 sandbox intervene 端点；
 * 操作结果经事件上抛，父组件 pushFeed 可见。</p>
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 角色列表 */
  npcs: { type: Array, default: () => [] },
  /** 世界时钟 */
  clock: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'talk', 'act', 'event-inject', 'rate-change', 'pause', 'advance'])

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const targetRef = ref(null)
const talkText = ref('')
const eventText = ref('')
const rateValue = ref(4)
const talking = ref(false)
const acting = ref(false)
const injecting = ref(false)
const clockBusy = ref(false)
const advancing = ref(false)

const target = computed(() => props.npcs.find((n) => n.npcRef === targetRef.value) || null)

watch(() => props.clock.rate, (v) => { if (v != null) rateValue.value = v }, { immediate: true })
watch(open, (v) => { if (v && props.clock.rate != null) rateValue.value = props.clock.rate })

function moveLabel(s) {
  return ({ idle: '空闲', walking: '移动中', acting: '行动中', talking: '交谈中', resting: '歇息中' })[s] || s || '—'
}

async function doTalk() {
  const content = talkText.value.trim()
  if (!target.value || !content) return
  talking.value = true
  try {
    await emit('talk', { npcRef: target.value.npcRef, content })
    talkText.value = ''
  } finally { talking.value = false }
}

async function doAction() {
  if (!target.value) return
  acting.value = true
  try { await emit('act', target.value.npcRef) } finally { acting.value = false }
}

async function doEvent() {
  const content = eventText.value.trim()
  if (!content) return
  injecting.value = true
  try { await emit('event-inject', content); eventText.value = '' } finally { injecting.value = false }
}

async function doRate(v) {
  clockBusy.value = true
  try { await emit('rate-change', v) } finally { clockBusy.value = false }
}
async function doPause() {
  clockBusy.value = true
  try { await emit('pause') } finally { clockBusy.value = false }
}
async function doAdvance() {
  advancing.value = true
  try { await emit('advance') } finally { advancing.value = false }
}
</script>

<style scoped>
.iv-body { display: flex; flex-direction: column; gap: 16px; }
.iv-section { display: flex; flex-direction: column; gap: 8px; }
.iv-title { font-size: 13px; font-weight: 700; color: var(--text-primary, #e8f0ff); }
.iv-target {
  font-size: 11.5px; color: var(--text-secondary, #9db8e8);
  padding: 6px 9px; border-radius: 7px;
  background: var(--brand-gradient-soft, rgba(77,159,255,0.1));
}
.iv-btns { display: flex; align-items: center; gap: 8px; }
.iv-clock-row { display: flex; align-items: center; gap: 8px; }
.iv-hint { font-size: 10.5px; color: var(--text-placeholder, #6b7fa8); }
</style>
