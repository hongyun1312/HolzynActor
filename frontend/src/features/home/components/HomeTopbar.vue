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
    新首页顶栏控制条（M4 建；第 65 次精简；V2.0 第 68 次按「工具条预算」重排）。

    V2.0 改造要点（依据界面审计 UI-07 与本轮实测）：
    ① 改造前一行有 9 个可点控件、视觉权重几乎相同（速率 / 继续暂停 / 推进一轮 / 运行态开关 /
       天气 / 干预 / 对戏 / 2 个布局图标），@1024 折行后第二行左半整片留白 —— 用户不知道先点哪个；
    ② 「世界是否在推进」有两个措辞近似的指示器（本组件的「世界运行中/世界静止」+ 地图浮层的
       「已暂停 · 角色不会自主推进」）→ 现在**状态只由时钟组的一个徽标表达**，且与唯一的
       时间控制按钮相邻（状态与其控制在同一处）；
    ③ 常驻动作收敛到 **1 主 + 2 次**（开始推进/暂停 · 推进一轮 · 干预），
       天气 / 对戏·演绎 / 实时决策手动常开 收进「⋯」（PatternFly：工具条内完整展示的动作 ≤3）；
    ④ 保留沙盘的 `--hm-*` 世界主题令牌——沙盘是世界页，遵循「外壳工具化、世界页沉浸化」原则，
       不套用通用外壳组件，只落地同一条规则。
  -->
  <div class="hm-topbar">
    <!-- 组 1：世界时钟（状态区）——历法文本 + 实时连接 + 世界运行状态徽标 + 速率 -->
    <div class="hm-group hm-clock-group">
      <div class="hm-clock">
        <div class="hm-clock-title">
          世界时钟
          <span class="hm-live" :class="{ on: liveConnected }">{{ liveConnected ? '实时' : '重连中' }}</span>
          <span class="hm-state" :class="clock.paused ? 'is-paused' : 'is-running'" :title="worldStateHint">
            <i class="hm-state-dot" aria-hidden="true" />
            {{ clock.paused ? '已暂停' : '运行中' }}
          </span>
        </div>
        <div class="hm-clock-text">{{ clockText }}</div>
      </div>
      <el-select
        v-model="rateValue" size="small" class="hm-rate"
        :disabled="clockBusy" title="世界时间流速：每真实小时推进多少游戏小时" @change="onRateChange"
      >
        <el-option v-for="r in [1, 2, 4, 8, 12, 24, 48, 96]" :key="r" :label="`×${r}`" :value="r" />
      </el-select>
    </div>

    <span class="hm-sep" />

    <!-- 组 2：时间控制（**唯一入口**）——主按钮（开始推进/暂停）+ 单步（推进一轮）+ 干预 -->
    <div class="hm-group hm-group-actions">
      <el-button
        size="small"
        :type="clock.paused ? 'success' : 'warning'"
        :loading="clockBusy"
        :title="clock.paused ? '让角色重新开始自主行动（开启持续运行）' : '停下世界时间：角色立即停止自主推进'"
        @click="emit('toggle-pause')"
      >
        {{ clock.paused ? '▶ 开始推进' : '⏸ 暂停' }}
      </el-button>
      <el-button
        size="small" :loading="advancing"
        title="只推进一个游戏小时（单步）；与「开始推进」的持续运行不同"
        @click="emit('advance')"
      >推进一轮</el-button>
      <el-button
        size="small" class="hm-intervene-btn"
        title="插话 / 触发行动 / 注入事件 / 调时钟"
        @click="emit('open-intervene')"
      >⚡ 干预</el-button>
    </div>

    <!-- 组 3：右侧——「⋯ 更多」（天气 / 对戏·演绎 / 实时决策手动常开）+ 布局收放（视图控制） -->
    <div class="hm-group hm-tail">
      <el-dropdown trigger="click" placement="bottom-end" @command="onMore">
        <button type="button" class="hm-more-btn" title="更多：天气 · 对戏 / 演绎 · 实时决策手动常开">
          <span aria-hidden="true">⋯</span>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="roleplay">
              🎭 对戏 / 演绎
            </el-dropdown-item>
            <el-dropdown-item command="rt-switch">
              {{ rtDecision.manualEnabled ? '✓ ' : '' }}实时决策·手动常开
            </el-dropdown-item>
            <el-dropdown-item divided disabled>
              <span class="hm-dd-title">天气（当前 {{ weather.condition || '—' }}）</span>
            </el-dropdown-item>
            <el-dropdown-item v-if="!weather.condition" disabled>
              <span class="hm-dd-title hm-dd-note">{{ weatherHint }}</span>
            </el-dropdown-item>
            <el-dropdown-item
              v-for="c in WEATHERS" :key="c" :command="'weather-' + c"
              :disabled="weatherBusy"
            >
              {{ WEATHER_ICONS[c] }} {{ c }}{{ weather.condition === c ? '（当前）' : '' }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <!-- 布局收放：视图控制（不是动作），保持图标化常驻，避免把地图让位藏在二级菜单里 -->
      <div class="hm-group hm-group-layout">
        <button
          type="button" class="hm-layout-btn" :class="{ off: leftCollapsed }"
          :title="leftCollapsed ? '展开角色活动流' : '收起角色活动流（把画面留给地图）'"
          @click="emit('toggle-left')"
        >
          <span class="hm-layout-glyph">{{ leftCollapsed ? '▏' : '◧' }}</span>
        </button>
        <button
          type="button" class="hm-layout-btn" :class="{ off: rightCollapsed }"
          :title="rightCollapsed ? '展开世界演化面板' : '收起世界演化面板（把画面留给地图）'"
          @click="emit('toggle-right')"
        >
          <span class="hm-layout-glyph">{{ rightCollapsed ? '▕' : '◨' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 新首页顶栏控制条（M4）。
 * <p>职责：世界时钟（历法/速率/暂停/推进一轮）+ 实时决策开关 + 世界运行态 + 天气切换 +
 * 干预入口 + 对戏/演绎抽屉入口 + 左右面板收放。纯展示组件：数据由父组件传入，交互事件上抛。</p>
 */
import { computed, ref, watch } from 'vue'

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
  weatherBusy: { type: Boolean, default: false },
  /** 左栏是否收起 */
  leftCollapsed: { type: Boolean, default: false },
  /** 右栏是否收起 */
  rightCollapsed: { type: Boolean, default: false }
})
const emit = defineEmits([
  'rate-change', 'toggle-pause', 'advance', 'rt-toggle', 'weather-change',
  'open-intervene', 'open-roleplay', 'toggle-left', 'toggle-right'
])

const WEATHERS = ['晴', '雨', '雪', '阴']
const WEATHER_ICONS = { 晴: '☀️', 雨: '🌧️', 雪: '❄️', 阴: '☁️' }

/** 速率（本地态：父组件回填 clock.rate 后同步，避免下拉显示滞后） */
const rateValue = ref(props.clock?.rate || 4)
watch(() => props.clock?.rate, (v) => { if (v != null) rateValue.value = v })

function onRateChange(v) { emit('rate-change', v) }

/**
 * 「⋯ 更多」命令分发。
 *
 * <p>V2.0：天气 / 对戏·演绎 / 实时决策手动常开三项从常驻位置降权到这里——
 * 它们都是低频或可替代入口（对戏抽屉在干预抽屉旁、天气更适合在世界帧面板看），
 * 而工具条常驻动作预算只有 3 个（见组件头注释）。</p>
 *
 * @param {string} cmd `roleplay` | `rt-switch` | `weather-<天气>`
 */
function onMore(cmd) {
  if (cmd === 'roleplay') { emit('open-roleplay'); return }
  if (cmd === 'rt-switch') { emit('rt-toggle', !props.rtDecision.manualEnabled); return }
  if (typeof cmd === 'string' && cmd.startsWith('weather-')) emit('weather-change', cmd.slice('weather-'.length))
}

/**
 * 世界运行状态徽标的完整说明。
 *
 * <p>为什么要合并措辞：改造前时钟暂停（`clock.paused`）与实时决策激活（`rtDecision.active`）
 * 各有一个措辞近似的指示器（「已暂停/继续」与「世界运行中/世界静止」），用户无法分辨两者差别。
 * 现在以**时钟暂停**为唯一主状态，实时决策是否激活只作为补充说明出现在 tooltip 里。</p>
 */
const worldStateHint = computed(() => {
  const base = props.clock?.paused
    ? '世界时间已暂停：角色不会自主推进（点「开始推进」恢复持续运行）'
    : '世界正在推进：角色会按自己的日程行动'
  const rt = props.rtDecision?.active ? '实时决策已激活' : '实时决策未激活'
  return `${base}；${rt}`
})

const rtHint = computed(() => {
  if (!props.rtDecision.active) return '实时决策未激活：世界暂停'
  const parts = []
  if (props.rtDecision.manualEnabled) parts.push('手动常开')
  if (props.rtDecision.sandboxActive) parts.push('世界运行中')
  if (props.rtDecision.evolutionActive) parts.push('演化进行中')
  return `实时决策已激活（${parts.join(' + ')}）：世界持续推进`
})

const weatherHint = computed(() => {
  const w = props.weather
  if (!w.condition) {
    // UI V2.0 §8.4 契约：GET /world-frame 恒 200，未生成时 generated=false + hint。
    // 「世界还没过完第一个游戏日」是有原因的正常状态，不该只显示「天气数据未加载」这种开发者口吻。
    if (w.framePending) return w.frameHint || '世界还没过完第一个游戏日，天气会在第一份世界帧生成后出现'
    return '天气数据未加载'
  }
  return `当前天气：${w.condition}${w.temperature != null ? ` · ${w.temperature}℃` : ''}${w.wind ? ` · ${w.wind}` : ''}（区域：${w.region || '主区域'}）· 点击切换`
})
</script>

<style scoped>
.hm-topbar {
  display: flex; align-items: center;
  /* V2.0：**禁止折行**（改造前 @1024 折行后第二行左半整片留白）。
     空间不足时靠时钟文本省略与「⋯ 更多」消化，而不是换行。 */
  flex-wrap: nowrap;
  gap: 10px;
  padding: 8px 12px;
  background: var(--hm-panel); backdrop-filter: blur(8px);
  border: 1px solid var(--hm-border); border-radius: 12px;
  color: var(--hm-text);
  overflow: hidden;
}
/* 分组：组内紧、组间用分隔线（从「一排按钮」变成「几组信息」） */
.hm-group { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
/* 时间控制组不参与压缩（主行动必须永远可点） */
.hm-group-actions { flex-shrink: 0; }
/* 右侧动作区（更多 + 布局）：整体靠右 */
.hm-tail { margin-left: auto; gap: 10px; flex-shrink: 0; }
.hm-sep { width: 1px; height: 22px; background: var(--hm-border-soft); flex-shrink: 0; }

/* 唯一断点（档位值）：窄屏只收掉非关键的「实时/重连中」小标签，保留状态徽标与全部动作 */
@media (max-width: 1024px) {
  .hm-topbar { gap: 8px; padding: 7px 10px; }
  .hm-sep { display: none; }
  .hm-live { display: none; }
}

.hm-clock { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.hm-clock-title { font-size: 11px; font-weight: 700; color: var(--hm-text-dim); display: flex; align-items: center; gap: 6px; }
.hm-live { font-size: 10px; font-weight: 600; color: var(--hm-amber); display: inline-flex; align-items: center; gap: 4px; }
.hm-live::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--hm-amber); }
.hm-live.on { color: var(--hm-green); }
.hm-live.on::before { background: var(--hm-green); box-shadow: 0 0 6px var(--hm-green); animation: pulse 1.2s infinite; }
.hm-clock-text {
  font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.hm-rate { width: 78px; flex-shrink: 0; }

/* V2.0 世界运行状态徽标：**唯一**的"世界是否在推进"指示器（与时间控制按钮同处一组） */
.hm-state {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 1px 7px; border-radius: 999px;
  font-size: 10.5px; font-weight: 700;
  border: 1px solid var(--hm-border); color: var(--hm-text-dim);
  cursor: help; white-space: nowrap;
}
.hm-state-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--hm-dot); }
.hm-state.is-running { color: var(--hm-green); border-color: color-mix(in srgb, var(--hm-green) 45%, transparent); }
.hm-state.is-running .hm-state-dot { background: var(--hm-green); box-shadow: 0 0 6px var(--hm-green); animation: pulse 1.2s infinite; }
.hm-state.is-paused { color: var(--hm-amber); border-color: color-mix(in srgb, var(--hm-amber) 45%, transparent); }
.hm-state.is-paused .hm-state-dot { background: var(--hm-amber); }

/* 「⋯ 更多」：降权入口（天气 / 对戏·演绎 / 实时决策手动常开） */
.hm-more-btn {
  width: 28px; height: 26px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--hm-border); background: transparent; border-radius: 8px;
  cursor: pointer; color: var(--hm-text-dim); font-weight: 700; letter-spacing: 1px;
  transition: all .15s var(--dsw-ease);
}
.hm-more-btn:hover { color: var(--hm-text); background: var(--bg-hover); border-color: var(--hm-border-strong); }
.hm-dd-title { font-size: 0.72rem; color: var(--text-tertiary); }
/* 天气不可用时的原因说明（正常换行、给足行长——这是给用户看的解释，不是装饰） */
.hm-dd-note { display: block; max-width: 22em; white-space: normal; line-height: 1.5; }

.hm-intervene-btn, .hm-roleplay-btn { white-space: nowrap; }

/* 布局收放 */
.hm-group-layout { gap: 4px; }
.hm-layout-btn {
  width: 26px; height: 26px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--hm-border); background: transparent; border-radius: 8px;
  cursor: pointer; color: var(--hm-text-dim); transition: all .15s var(--dsw-ease);
}
.hm-layout-btn:hover { color: var(--hm-text); background: var(--bg-hover); border-color: var(--hm-border-strong); }
.hm-layout-btn.off { color: var(--hm-text-faint); border-style: dashed; }
.hm-layout-glyph { font-size: 12px; line-height: 1; }

@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
</style>
