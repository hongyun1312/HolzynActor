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
    新首页左栏「角色活动流」（M4 建；第 65 次信息密度重构）。
    第 65 次：用户反馈「数据栏太多、太繁杂」。原实现每个角色卡固定渲染 4 条
    全宽进度条 + 心情 + 想法 + 紧急提示（12 个角色 = 约 1030px 长、满屏彩色条），
    且普通人群与特殊角色混排。现改为「列表只扫、详情才细看」：
      ① 行内只留「名字 + 状态 + 当前行动 + 最紧迫需求」四个信息点（行高 ~48px）；
      ② 4 条需求条只在**选中角色的详情区**出现（含数值与健康条）；
      ③ 特殊角色 / 普通人群分组，普通人群默认折叠（不占视线）；
      ④ 顶部加搜索，长名单也能定位。
    数据与操作均由父组件传入/派发（本组件纯展示 + 交互事件上抛）。
  -->
  <div class="hm-activity">
    <div class="hm-panel-head">
      <span class="hm-panel-title">角色活动流</span>
      <el-tag size="small" effect="plain">{{ props.npcs.length }}</el-tag>
      <span class="hm-head-right">
        <button
          type="button" class="hm-seg" :class="{ on: filter !== 'special' }"
          title="显示全部角色" @click="emit('change-filter', '')"
        >全部</button>
        <button
          type="button" class="hm-seg" :class="{ on: filter === 'special' }"
          title="只看特殊 NPC" @click="emit('change-filter', 'special')"
        >特殊</button>
      </span>
    </div>

    <div class="hm-search">
      <el-input v-model="keyword" size="small" clearable placeholder="搜索角色 / 行动">
        <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
      </el-input>
    </div>

    <div class="hm-activity-list">
      <!-- 特殊角色 -->
      <template v-if="specialList.length">
        <div class="hm-group-label">特殊 NPC<span>{{ specialList.length }}</span></div>
        <div
          v-for="n in specialList"
          :key="n.npcRef"
          class="hm-char"
          :class="{ selected: selectedRef === n.npcRef }"
          @click="emit('select', n.npcRef)"
        >
          <el-avatar class="hm-char-avatar" :size="24">{{ (n.npcName || n.npcRef).charAt(0) }}</el-avatar>
          <div class="hm-char-main">
            <div class="hm-char-head">
              <span class="hm-char-name">{{ n.npcName || n.npcRef }}</span>
              <span
                v-if="n.urgencyLevel > 0" class="hm-urgent-chip"
                :title="n.urgencyText || '有紧急需求'" :class="'lv' + Math.min(3, n.urgencyLevel)"
              >⚠</span>
              <span v-if="worst(n)" class="hm-need-chip" :class="worst(n).cls" :title="needTitle(n)">{{ worst(n).text }}</span>
              <span class="hm-char-state" :class="moveCls(n.moveState)">{{ moveLabel(n.moveState) }}</span>
              <button class="hm-ask-mini" title="问问助手：讲这个角色的情况" @click.stop="emit('ask', n.npcRef)">🤖</button>
            </div>
            <div v-if="subText(n)" class="hm-char-sub" :title="subText(n)">
              <span v-if="n.moveState === 'walking' && movingTarget(n)" class="hm-moving">
                → {{ movingTarget(n) }}<i v-if="travelProgress(n)" class="hm-move-pct">{{ travelProgress(n) }}</i>
              </span>
              <span v-else>{{ n.actionText }}</span>
            </div>
          </div>
        </div>
      </template>

      <!-- 普通人群（默认折叠：不参与视线竞争） -->
      <template v-if="crowdList.length">
        <button type="button" class="hm-group-label is-btn" @click="crowdOpen = !crowdOpen">
          <span class="hm-group-caret">{{ crowdOpen ? '▾' : '▸' }}</span>普通人群<span>{{ crowdList.length }}</span>
        </button>
        <template v-if="crowdOpen">
          <div
            v-for="n in crowdList"
            :key="n.npcRef"
            class="hm-char"
            :class="{ selected: selectedRef === n.npcRef }"
            @click="emit('select', n.npcRef)"
          >
            <el-avatar class="hm-char-avatar is-crowd" :size="24">{{ (n.npcName || n.npcRef).charAt(0) }}</el-avatar>
            <div class="hm-char-main">
              <div class="hm-char-head">
                <span class="hm-char-name">{{ n.npcName || n.npcRef }}</span>
                <span class="hm-char-state" :class="moveCls(n.moveState)">{{ moveLabel(n.moveState) }}</span>
              </div>
              <div v-if="subText(n)" class="hm-char-sub" :title="subText(n)">
                <span v-if="n.moveState === 'walking' && movingTarget(n)" class="hm-moving">→ {{ movingTarget(n) }}</span>
                <span v-else>{{ n.actionText }}</span>
              </div>
            </div>
          </div>
        </template>
      </template>

      <div v-if="!specialList.length && !crowdList.length" class="hm-empty">
        {{ props.npcs.length ? '无匹配的角色' : '暂无角色状态' }}<br />
        <span>{{ props.npcs.length ? '换个关键字试试' : '去「角色 / 普通人群」页创建后回到本页观察' }}</span>
      </div>
    </div>

    <!-- 选中角色：详细状态（需求条 + 健康 + 情绪）+ 插话 / 触发行动 -->
    <div v-if="selected" class="hm-intervene">
      <div class="hm-in-head">
        <span class="hm-in-title">{{ selected.npcName || selected.npcRef }}</span>
        <span v-if="selected.actionText" class="hm-in-action">{{ selected.actionText }}</span>
        <button type="button" class="hm-in-close" title="取消选中" @click="emit('select', selected.npcRef)">✕</button>
      </div>
      <div v-if="selected.dominantEmotion || selected.urgencyLevel > 0 || selected.health != null" class="hm-sd">
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
      <!-- 需求条（只在详情区出现，列表里不再重复 12 份） -->
      <div v-if="selected.hunger != null" class="hm-sd-needs">
        <div class="hm-sd-need">
          <span class="hm-sd-need-k">饥饿</span>
          <el-progress :percentage="selected.hunger" :show-text="false" :stroke-width="5" :color="needColor(selected.hunger)" />
          <b>{{ selected.hunger }}</b>
        </div>
        <div v-if="selected.bladder != null" class="hm-sd-need">
          <span class="hm-sd-need-k">如厕</span>
          <el-progress :percentage="selected.bladder" :show-text="false" :stroke-width="5" :color="needColor(selected.bladder)" />
          <b>{{ selected.bladder }}</b>
        </div>
        <div class="hm-sd-need">
          <span class="hm-sd-need-k">精力</span>
          <el-progress :percentage="selected.energy" :show-text="false" :stroke-width="5" :color="needColor(selected.energy)" />
          <b>{{ selected.energy }}</b>
        </div>
        <div class="hm-sd-need">
          <span class="hm-sd-need-k">心情</span>
          <el-progress :percentage="selected.mood" :show-text="false" :stroke-width="5" :color="moodColor(selected.mood)" />
          <b>{{ selected.mood }}</b>
        </div>
      </div>
      <div v-if="selected.thoughtText" class="hm-sd-thought">💭 {{ selected.thoughtText }}</div>
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
        <el-button size="small" class="hm-ask-btn" @click="emit('ask', selected.npcRef)">🤖 问问助手</el-button>
      </div>
    </div>
    <!-- 未选中：世界事件注入 -->
    <div v-else class="hm-intervene">
      <div class="hm-in-title">世界干预</div>
      <el-input v-model="eventText" size="small" placeholder="向世界注入一条事件…" @keyup.enter="doEvent">
        <template #append>
          <el-button :disabled="!eventText.trim()" :loading="injecting" @click="doEvent">注入</el-button>
        </template>
      </el-input>
      <div class="hm-in-hint">点击左侧角色可查看完整状态（需求/情绪/想法）并单独干预</div>
    </div>
  </div>
</template>

<script setup>
/**
 * 新首页左栏「角色活动流」（M4）。
 * <p>职责：角色列表（名字/状态/当前行动/最紧迫需求）+ 分组（特殊 / 普通人群）+ 搜索
 * + 选中角色详情（需求条/健康/情绪/想法）与干预（插话/触发行动）+ 未选中时世界事件注入。
 * 纯展示组件：数据由父组件传入（含 filter 过滤后的列表），交互事件上抛。</p>
 */
import { computed, ref } from 'vue'

const props = defineProps({
  /** 角色状态列表（沙场状态数组，含需求/心情/想法/移动状态） */
  npcs: { type: Array, default: () => [] },
  /** 选中角色引用 */
  selectedRef: { type: String, default: null },
  /** 在途移动表（npcRef → {targetLocationName, progress}；移动中状态展示用） */
  movingMap: { type: Object, default: () => ({}) },
  /** 当前筛选（'' 全部 / 'special' 仅特殊） */
  filter: { type: String, default: '' }
})
const emit = defineEmits(['select', 'change-filter', 'talk', 'act', 'event-inject', 'ask'])

const talkText = ref('')
const eventText = ref('')
const talking = ref(false)
const acting = ref(false)
const injecting = ref(false)
const keyword = ref('')
/** 普通人群分组是否展开（默认折叠：把视线留给特殊角色） */
const crowdOpen = ref(false)

const selected = computed(() => props.npcs.find((n) => n.npcRef === props.selectedRef) || null)

/** 关键字过滤（名字 / 当前行动） */
const matched = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.npcs
  return props.npcs.filter((n) =>
    (n.npcName || n.npcRef || '').toLowerCase().includes(kw) ||
    (n.actionText || '').toLowerCase().includes(kw)
  )
})

const specialList = computed(() => matched.value.filter((n) => n.npcType === 'special'))
const crowdList = computed(() => matched.value.filter((n) => n.npcType !== 'special'))

/**
 * 最紧迫需求（列表行只显示一个，避免 12 行 × 4 条进度条的视觉噪声）。
 * 取值 &lt; 100 的最低项；全部饱满（≥85）时返回 null（没什么可说就不说）。
 */
function worst(n) {
  const items = [
    { k: '饥饿', v: n.hunger },
    { k: '如厕', v: n.bladder },
    { k: '精力', v: n.energy },
    { k: '心情', v: n.mood }
  ].filter((it) => it.v != null)
  if (!items.length) return null
  items.sort((a, b) => a.v - b.v)
  const w = items[0]
  if (w.v >= 85 && (n.urgencyLevel || 0) === 0) return null
  return { text: `${w.k} ${Math.round(w.v)}`, cls: w.v < 30 ? 'is-low' : (w.v < 60 ? 'is-mid' : 'is-ok') }
}

/** 需求行 tooltip（完整四项数值） */
function needTitle(n) {
  const parts = []
  if (n.hunger != null) parts.push(`饥饿 ${n.hunger}`)
  if (n.bladder != null) parts.push(`如厕 ${n.bladder}`)
  if (n.energy != null) parts.push(`精力 ${n.energy}`)
  if (n.mood != null) parts.push(`心情 ${n.mood}`)
  if (n.health != null) parts.push(`健康 ${n.health}`)
  return parts.join(' · ') || '无需求数据'
}

/** 行副标题（当前行动 / 目标地点）；与状态标签重复时返回空串（不重复显示「空闲 空闲」） */
function subText(n) {
  if (n.moveState === 'walking' && movingTarget(n)) return `→ ${movingTarget(n)}`
  const act = (n.actionText || '').trim()
  if (!act) return ''
  if (act === moveLabel(n.moveState)) return ''
  return act
}

function moveCls(s) {
  return ({ idle: 'is-idle', walking: 'is-walking', acting: 'is-acting', talking: 'is-talking', resting: 'is-resting' })[s] || 'is-idle'
}

/** 移动中角色 → 目标地点名（来自移动表；无则回退 null） */
function movingTarget(n) {
  const m = props.movingMap[n.npcRef]
  if (m && m.targetLocationName) return m.targetLocationName
  return null
}

/** 移动进度百分比文案（刚起步 <5% 不显示：那点进度没有信息量，只是噪声） */
function travelProgress(n) {
  const m = props.movingMap[n.npcRef]
  if (!m || m.progress == null) return ''
  const pct = Math.round(m.progress * 100)
  return pct >= 5 ? `${pct}%` : ''
}

function moveLabel(s) {
  return ({ idle: '空闲', walking: '移动中', acting: '行动中', talking: '交谈中', resting: '歇息中' })[s] || s || '—'
}
function needColor(v) {
  if (v == null) return 'var(--hm-text-dim)'
  if (v < 30) return 'var(--hm-red)'
  if (v < 60) return 'var(--hm-amber)'
  return 'var(--hm-green)'
}
function moodColor(v) {
  if (v == null) return 'var(--hm-text-dim)'
  if (v < 40) return 'var(--hm-red)'
  if (v < 65) return 'var(--hm-amber)'
  return 'var(--hm-green)'
}
function healthColor(v) {
  if (v == null) return 'var(--hm-text-dim)'
  if (v < 25) return 'var(--hm-red)'
  if (v < 70) return 'var(--hm-amber)'
  return 'var(--hm-green)'
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
  background: var(--hm-panel); backdrop-filter: blur(8px);
  border: 1px solid var(--hm-border); border-radius: 12px;
  overflow: hidden;
}
.hm-panel-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px 8px;
}
.hm-panel-title { font-size: 13px; font-weight: 700; color: var(--hm-text); }
.hm-head-right { margin-left: auto; display: inline-flex; gap: 2px; }
.hm-seg {
  border: 1px solid transparent; background: transparent; cursor: pointer;
  color: var(--hm-text-faint); font-size: 12.5px; padding: 2px 8px; border-radius: 999px;
  transition: all .15s var(--dsw-ease);
}
.hm-seg:hover { color: var(--hm-text); background: var(--bg-hover); }
.hm-seg.on { color: var(--hm-text); background: var(--accent-soft); border-color: var(--accent-border); }

.hm-search { padding: 0 12px 8px; }
.hm-activity-list { flex: 1; overflow-y: auto; padding: 0 8px 8px; }

/* 分组标题 */
.hm-group-label {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 4px 4px; font-size: 12.5px; font-weight: 700; color: var(--hm-text-faint);
  letter-spacing: .02em;
}
.hm-group-label span { font-weight: 600; color: var(--hm-text-faint); opacity: .75; }
.hm-group-label.is-btn {
  width: 100%; border: none; background: transparent; cursor: pointer;
  text-align: left; border-radius: 6px;
}
.hm-group-label.is-btn:hover { background: var(--bg-hover); color: var(--hm-text-dim); }
.hm-group-caret { width: 10px; }

/* 角色行（列表态：只扫四个信息点） */
.hm-char {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 6px 8px; border-radius: 8px; cursor: pointer; margin-bottom: 2px;
  border: 1px solid transparent; transition: background .15s, border-color .15s;
}
.hm-char:hover { background: var(--accent-soft); }
.hm-char.selected { background: var(--accent-softer, var(--accent-soft)); border-color: var(--accent-border); }
.hm-char-avatar { background: var(--accent-soft); color: var(--accent-text); font-size: 12.5px; flex-shrink: 0; }
.hm-char-avatar.is-crowd { background: color-mix(in srgb, var(--hm-blue) 18%, transparent); color: var(--hm-blue); }
.hm-char-main { flex: 1; min-width: 0; }
.hm-char-head { display: flex; align-items: center; gap: 6px; }
.hm-char-name {
  font-size: 12.5px; font-weight: 700; color: var(--hm-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 46%;
}
.hm-char-state { font-size: 12px; color: var(--hm-text-faint); white-space: nowrap; }
.hm-char-state.is-walking { color: var(--hm-gold); font-weight: 600; }
.hm-char-state.is-acting { color: var(--hm-amber); font-weight: 600; }
.hm-char-state.is-talking { color: var(--hm-green); font-weight: 600; }
.hm-need-chip {
  font-size: 12px; font-weight: 700; padding: 0 6px; border-radius: 999px;
  font-variant-numeric: tabular-nums; white-space: nowrap; cursor: help;
  border: 1px solid transparent;
}
.hm-need-chip.is-low { color: var(--hm-red-soft); background: color-mix(in srgb, var(--hm-red) 16%, transparent); border-color: color-mix(in srgb, var(--hm-red) 40%, transparent); }
.hm-need-chip.is-mid { color: var(--hm-amber); background: color-mix(in srgb, var(--hm-amber) 14%, transparent); }
.hm-need-chip.is-ok { color: var(--hm-text-faint); background: var(--bg-hover); }
.hm-char-sub {
  font-size: 12.5px; color: var(--hm-text-dim); margin-top: 1px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hm-moving { color: var(--hm-gold); font-weight: 600; }
.hm-moving i { font-style: normal; color: var(--hm-text-faint); font-weight: 400; }
/* 移动进度：靠外边距拉开，别粘在地名后面（原「守脉堂正院0%」） */
.hm-moving .hm-move-pct { margin-left: 5px; }
/* 紧急需求：列表里只留一个告警标（文字进 tooltip / 详情区），避免 7 行重复同一句 */
.hm-urgent-chip {
  font-size: 12.5px; line-height: 1; cursor: help; flex-shrink: 0;
  color: var(--hm-red-soft);
  animation: hm-urgent-pulse 1.6s ease-in-out infinite;
}
.hm-urgent-chip.lv1 { opacity: .85; }
@keyframes hm-urgent-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .45; } }
.hm-ask-mini {
  margin-left: auto; width: 20px; height: 20px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--hm-border); border-radius: 6px;
  background: transparent; font-size: 12.5px; cursor: pointer;
  opacity: 0; transition: opacity .15s, background .15s;
}
.hm-char:hover .hm-ask-mini { opacity: .7; }
.hm-ask-mini:hover { opacity: 1 !important; background: var(--accent-soft); }
.hm-empty { font-size: 12px; color: var(--hm-text-dim); text-align: center; padding: 26px 10px; line-height: 1.8; }
.hm-empty span { font-size: 12.5px; color: var(--hm-text-faint); }

/* 详情 / 干预区 */
.hm-intervene {
  padding: 10px 12px; border-top: 1px solid var(--hm-border-soft);
  display: flex; flex-direction: column; gap: 8px; flex-shrink: 0;
  max-height: 46%; overflow-y: auto;
}
.hm-in-head { display: flex; align-items: baseline; gap: 8px; }
.hm-in-title { font-size: 12.5px; font-weight: 700; color: var(--hm-text); }
.hm-in-action { font-size: 12.5px; color: var(--hm-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.hm-in-close { border: none; background: transparent; cursor: pointer; color: var(--hm-text-faint); font-size: 12.5px; padding: 0 2px; }
.hm-in-close:hover { color: var(--hm-text); }
.hm-sd { display: flex; flex-direction: column; gap: 5px; padding: 7px 9px; border-radius: 8px; background: var(--accent-softer, var(--accent-soft)); border: 1px solid var(--hm-border-soft); }
.hm-sd-urgent { font-size: 12.5px; font-weight: 700; color: var(--hm-red-soft); }
.hm-sd-emotion { font-size: 12px; font-weight: 700; color: var(--hm-gold-soft); }
.hm-sd-em-int { font-size: 12px; font-weight: 400; color: var(--hm-text-dim); margin-left: 6px; }
.hm-sd-health { font-size: 12.5px; color: var(--hm-text-dim); }
.hm-sd-needs { display: flex; flex-direction: column; gap: 4px; }
.hm-sd-need { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--hm-text-dim); }
.hm-sd-need .el-progress { flex: 1; }
.hm-sd-need-k { flex-shrink: 0; width: 26px; color: var(--hm-text-faint); }
.hm-sd-need b { width: 24px; text-align: right; color: var(--hm-text); font-variant-numeric: tabular-nums; }
.hm-sd-thought { font-size: 12.5px; color: var(--hm-gold-soft); line-height: 1.6; }
.hm-in-btns { display: flex; align-items: center; gap: 8px; }
.hm-ask-btn { margin-left: auto; }
.hm-in-hint { font-size: 12px; color: var(--hm-text-faint); line-height: 1.6; }
.hm-activity-list::-webkit-scrollbar, .hm-intervene::-webkit-scrollbar { width: 5px; }
.hm-activity-list::-webkit-scrollbar-thumb, .hm-intervene::-webkit-scrollbar-thumb { background: var(--hm-border); border-radius: 3px; }
</style>
