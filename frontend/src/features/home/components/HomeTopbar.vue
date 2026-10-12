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
    新首页顶栏控制条（M4，features/home/components）。
    职责：世界时钟（历法/rate/暂停）+ 实时决策开关 + 天气切换 + 干预入口 + 对戏/演绎抽屉入口。
    数据/操作全部由父组件传入/派发（纯展示 + 事件上抛）。
  -->
  <div class="hm-topbar">
    <!-- 世界时钟 -->
    <div class="hm-clock">
      <div class="hm-clock-title">
        世界时钟
        <span class="hm-live" :class="{ on: liveConnected }">{{ liveConnected ? '实时' : '重连中' }}</span>
      </div>
      <div class="hm-clock-text">{{ clockText }}</div>
    </div>

    <!-- 速率 + 暂停/推进 -->
    <div class="hm-ops">
      <el-select
        v-model="rateValue" size="small" class="hm-rate"
        :disabled="clockBusy" @change="emit('rate-change', rateValue)"
      >
        <el-option v-for="r in [1, 2, 4, 8, 12, 24, 48, 96]" :key="r" :label="`×${r}`" :value="r" />
      </el-select>
      <el-button size="small" :type="clock.paused ? 'success' : 'warning'" :loading="clockBusy" @click="emit('toggle-pause')">
        {{ clock.paused ? '继续' : '暂停' }}
      </el-button>
      <el-button size="small" :loading="advancing" @click="emit('advance')">推进一轮</el-button>
    </div>

    <!-- 实时决策开关 + 世界运行态 -->
    <span class="hm-rt" :title="rtHint">
      <span class="hm-rt-dot" :class="rtDecision.active ? 'on' : 'off'" />
      {{ rtDecision.active ? '世界运行中' : '世界静止' }}
      <el-switch
        size="small" class="hm-rt-switch"
        :model-value="rtDecision.manualEnabled"
        :loading="rtBusy"
        @change="(v) => emit('rt-toggle', v)"
      />
    </span>

    <!-- 天气 -->
    <div class="hm-weather" :title="weatherHint">
      <span class="hm-weather-label">天气</span>
      <el-radio-group v-model="weather.condition" size="small" :disabled="weatherBusy" @change="(c) => emit('weather-change', c)">
        <el-radio-button value="晴">晴</el-radio-button>
        <el-radio-button value="雨">雨</el-radio-button>
        <el-radio-button value="雪">雪</el-radio-button>
        <el-radio-button value="阴">阴</el-radio-button>
      </el-radio-group>
      <span v-if="weather.seasonName" class="hm-season" :class="'season-' + (weather.season || '')">
        {{ weather.seasonName }}·{{ weather.month }}月
      </span>
    </div>

    <!-- 干预入口（打开干预抽屉） -->
    <el-button size="small" class="hm-intervene-btn" @click="emit('open-intervene')">
      ⚡ 干预
    </el-button>

    <!-- 对戏/演绎抽屉入口 -->
    <el-button size="small" type="primary" plain class="hm-roleplay-btn" @click="emit('open-roleplay')">
      🎭 对戏 / 演绎
    </el-button>
  </div>
</template>

<script setup>
/**
 * 新首页顶栏控制条（M4）。
 * <p>职责：世界时钟（历法/rate/暂停）+ 实时决策开关 + 世界运行态 + 天气切换 +
 * 干预入口 + 对戏/演绎抽屉入口。纯展示组件：数据由父组件传入，交互事件上抛。</p>
 */
import { computed } from 'vue'

const props = defineProps({
  /** 世界时钟 */
  clock: { type: Object, default: () => ({}) },
  /** 本地插值后的时钟文案 */
  clockText: { type: String, default: '—' },
  /** 实时决策激活状态 */
  rtDecision: { type: Object, default: () => ({}) },
  /** 天气状态 */
  weather: { type: Object, default: () => ({}) },
  /** SSE 连接状态 */
  liveConnected: { type: Boolean, default: false },
  clockBusy: { type: Boolean, default: false },
  advancing: { type: Boolean, default: false },
  rtBusy: { type: Boolean, default: false },
  weatherBusy: { type: Boolean, default: false }
})
const emit = defineEmits(['rate-change', 'toggle-pause', 'advance', 'rt-toggle', 'weather-change', 'open-intervene', 'open-roleplay'])

/** 速率下拉（本地 ref 由父组件初始同步） */
const rateValue = computed({
  get: () => props.clock.rate || 4,
  set: (v) => v
})

const rtHint = computed(() => {
  if (!props.rtDecision.active) return '实时决策未激活：世界暂停'
  const parts = []
  if (props.rtDecision.manualEnabled) parts.push('手动常开')
  if (props.rtDecision.sandboxActive) parts.push('新首页打开')
  if (props.rtDecision.evolutionActive) parts.push('演化进行中')
  return `实时决策已激活（${parts.join(' + ')}）：世界持续推进`
})

const weatherHint = computed(() => {
  const w = props.weather
  if (!w.condition) return '天气数据未加载'
  return `当前天气：${w.condition}${w.temperature != null ? ` · ${w.temperature}℃` : ''}${w.wind ? ` · ${w.wind}` : ''}（区域：${w.region || '主区域'}）`
})
</script>

<style scoped>
.hm-topbar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 10px;
  padding: 8px 12px;
  background: rgba(13, 20, 36, 0.85); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  color: #e8f0ff;
}
.hm-clock { display: flex; flex-direction: column; gap: 2px; }
.hm-clock-title { font-size: 11px; font-weight: 700; color: #9db8e8; display: flex; align-items: center; gap: 6px; }
.hm-live { font-size: 10px; font-weight: 600; color: #ffb84d; display: inline-flex; align-items: center; gap: 4px; }
.hm-live::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: #ffb84d; }
.hm-live.on { color: #52d68a; }
.hm-live.on::before { background: #52d68a; box-shadow: 0 0 6px #52d68a; animation: pulse 1.2s infinite; }
.hm-clock-text { font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.hm-ops { display: flex; align-items: center; gap: 6px; }
.hm-rate { width: 78px; }
.hm-rt { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; font-weight: 600; white-space: nowrap; cursor: help; }
.hm-rt-dot { width: 8px; height: 8px; border-radius: 50%; background: #5a6b85; }
.hm-rt-dot.on { background: #52d68a; box-shadow: 0 0 6px #52d68a; animation: pulse 1.2s infinite; }
.hm-rt-switch { margin-left: 2px; }
.hm-weather { display: inline-flex; align-items: center; gap: 6px; font-size: 11.5px; color: #9db8e8; }
.hm-weather-label { font-weight: 600; }
.hm-weather :deep(.el-radio-button__inner) { font-size: 11px; padding: 4px 9px; }
.hm-season {
  font-size: 10.5px; font-weight: 700; padding: 1px 7px; border-radius: 6px;
  color: #eaf3ff; border: 1px solid rgba(255, 255, 255, 0.25); font-variant-numeric: tabular-nums;
}
.hm-season.season-spring { background: rgba(93, 211, 141, 0.25); border-color: rgba(93, 211, 141, 0.55); }
.hm-season.season-summer { background: rgba(255, 176, 61, 0.25); border-color: rgba(255, 176, 61, 0.55); }
.hm-season.season-autumn { background: rgba(255, 146, 71, 0.25); border-color: rgba(255, 146, 71, 0.55); }
.hm-season.season-winter { background: rgba(143, 202, 255, 0.25); border-color: rgba(143, 202, 255, 0.55); }
.hm-intervene-btn, .hm-roleplay-btn { white-space: nowrap; }

@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
</style>
