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

  RuntimeStatusPill：世界运行状态的**唯一**指示与控制（V2.0 · 批次一）。

  为什么要有它（实测依据）：改造前"世界是否在推进"这件事在沙盘页有**三个**指示器——
    ① 顶栏的「世界运行中」开关；
    ② 地图顶部浮层的「⏸ 已暂停 · 角色不会自主推进」+「开始推进」按钮；
    ③ 右侧事件流里的「新首页打开，世界已激活，继续推进」文案。
  三处相距整个屏幕、语义重叠，用户无法判断该点哪个（审计 UI-07）。
  另外「继续 / 推进一轮 / 开始推进」三个按钮语义相近却没有区分说明。

  规范（本组件把它收敛成一处）：
    · **状态徽标**：● 运行中 / ⏸ 已暂停（颜色 + 文案 + 点，三重编码）；
    · **主按钮**：状态相关的唯一主动作（已暂停 →「开始推进」；运行中 →「暂停」）；
    · **次按钮**：语义明确区分的次级动作（「推进一轮」= 单步，tooltip 说明与主按钮的差别）；
    · 状态改变只有这一个入口；其它地方（如地图浮层）只能作为"首次进入提示"存在，不再自带 CTA。

  用法：
    <RuntimeStatusPill
      :status="clock.paused ? 'paused' : 'running'"
      :time-text="clockText"
      primary-text="开始推进" pause-text="暂停"
      advance-text="推进一轮" :busy="clockBusy"
      @primary="togglePause" @advance="advanceOne" />
-->
<template>
  <div class="rsp" :class="`is-${status}`">
    <span class="rsp-badge" :title="badgeTitle">
      <i class="rsp-dot" aria-hidden="true"></i>
      <span class="rsp-status-text">{{ statusText }}</span>
    </span>

    <span v-if="timeText" class="rsp-time" :title="timeTooltip || timeText">{{ timeText }}</span>

    <el-button
      v-if="primaryText"
      class="rsp-primary"
      :type="status === 'paused' || status === 'stopped' ? 'success' : 'default'"
      size="small"
      :loading="busy"
      :title="primaryTitle"
      @click="emit('primary')"
    >
      <span aria-hidden="true">{{ status === 'paused' || status === 'stopped' ? '▶' : '⏸' }}</span>
      <span class="rsp-btn-label">{{ status === 'paused' || status === 'stopped' ? primaryText : (pauseText || '暂停') }}</span>
    </el-button>

    <el-button
      v-if="advanceText"
      class="rsp-advance"
      size="small"
      :disabled="busy"
      title="只推进一轮：走完当前这一拍后停下（与「开始推进」的持续运行不同）"
      @click="emit('advance')"
    >{{ advanceText }}</el-button>
  </div>
</template>

<script setup>
/**
 * 世界运行状态胶囊：状态徽标 + 唯一主动作 + 语义明确的次级动作。
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.5
 */
import { computed } from 'vue'

const props = defineProps({
  /** running | paused | stopped | blocked | idle */
  status: { type: String, default: 'running' },
  /** 状态文案覆盖（默认按 status 取词） */
  statusLabel: { type: String, default: '' },
  /** 世界时间文本（如「青禾村历 0420年05月15日 06时00分」） */
  timeText: { type: String, default: '' },
  /** 时间的完整口径说明（tooltip） */
  timeTooltip: { type: String, default: '' },
  /** 已暂停/已停止时的主按钮文案（默认「开始推进」；停止态父级应传「开启世界运行」） */
  primaryText: { type: String, default: '开始推进' },
  /** 运行中时的主按钮文案（默认「暂停」） */
  pauseText: { type: String, default: '暂停' },
  /** 次级动作文案（默认「推进一轮」；留空则不渲染） */
  advanceText: { type: String, default: '推进一轮' },
  /** 主/次按钮忙碌态 */
  busy: { type: Boolean, default: false }
})
const emit = defineEmits(['primary', 'advance'])

const DEFAULT_LABEL = { running: '运行中', paused: '已暂停', stopped: '已停止', blocked: '未就绪', idle: '未开始' }
const statusText = computed(() => props.statusLabel || DEFAULT_LABEL[props.status] || props.status)
const badgeTitle = computed(() => {
  if (props.status === 'paused') return '世界时间已暂停：角色不会自主推进'
  if (props.status === 'running') return '世界正在推进：角色会按自己的日程行动'
  if (props.status === 'stopped') return '世界运行已关闭：引擎完全不推进本项目'
  return statusText.value
})
const primaryTitle = computed(() => {
  if (props.status === 'stopped') return '世界运行已关闭：点击开启世界运行（引擎恢复推进本项目）'
  if (props.status === 'paused') return '让角色重新开始自主行动（恢复推进）'
  return '停下世界时间：角色立即停止自主推进'
})
</script>

<style scoped>
/* 状态 + 时间 + 唯一主动作 + 次级动作，同处一行且永不折行 */
.rsp { display: flex; align-items: center; gap: 8px; min-width: 0; white-space: nowrap; }

.rsp-badge {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 8px; border-radius: 999px;
  font-size: 0.74rem; font-weight: 600;
  background: var(--bg-layer-2); color: var(--text-secondary);
  border: 1px solid var(--border-l1);
}
.rsp-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--text-tertiary); flex-shrink: 0; }
.is-running .rsp-badge { color: var(--state-success, #22c55e); border-color: color-mix(in srgb, var(--state-success, #22c55e) 40%, transparent); }
.is-running .rsp-dot { background: var(--state-success, #22c55e); box-shadow: 0 0 0 3px color-mix(in srgb, var(--state-success, #22c55e) 18%, transparent); }
.is-paused .rsp-badge { color: var(--state-warn, #f59e0b); border-color: color-mix(in srgb, var(--state-warn, #f59e0b) 40%, transparent); }
.is-paused .rsp-dot { background: var(--state-warn, #f59e0b); }
.is-stopped .rsp-badge { color: var(--text-tertiary); border-color: var(--border-l1); }
.is-stopped .rsp-dot { background: var(--text-tertiary); }
.is-blocked .rsp-badge { color: var(--state-error, #ef4444); border-color: color-mix(in srgb, var(--state-error, #ef4444) 40%, transparent); }
.is-blocked .rsp-dot { background: var(--state-error, #ef4444); }

.rsp-time {
  font-size: 0.82rem; font-weight: 600; color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; min-width: 0;
}
.rsp-btn-label { margin-left: 3px; }
</style>