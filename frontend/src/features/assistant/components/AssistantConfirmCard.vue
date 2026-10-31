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
  <!-- 干预确认卡片（M4 + M5）：操作/影响/风险 + 确认执行 / 修改后执行 / 取消 -->
  <div class="assistant-confirm" :class="{ 'is-done': done }">
    <div class="assistant-confirm-head">
      <span class="assistant-confirm-type">🛠 干预确认</span>
      <span class="assistant-confirm-title">{{ plan.operationLabel || '世界干预' }}</span>
      <span class="assistant-confirm-risk" :class="riskClass">{{ plan.riskLabel || '低' }}风险</span>
    </div>
    <div class="assistant-confirm-body">
      <div class="assistant-confirm-row">
        <span class="k">操作</span>
        <span class="v">{{ plan.detail || '—' }}</span>
      </div>
      <div class="assistant-confirm-row">
        <span class="k">影响</span>
        <span class="v">{{ plan.impact || '—' }}</span>
      </div>
    </div>

    <!-- M5：修改后执行——按操作类型的内联编辑表单（params 来自后端 confirm 载荷预填） -->
    <div v-if="!done && editing" class="assistant-confirm-edit">
      <div class="as-edit-title">修改操作内容后执行</div>
      <!-- weather：天气条件下拉 -->
      <div v-if="plan.operation === 'weather'" class="as-edit-row">
        <span class="k">天气</span>
        <select v-model="editParams.condition" class="as-edit-input">
          <option v-for="c in ['晴', '雨', '雪', '阴']" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
      <!-- talk：插话对象 + 内容 -->
      <div v-else-if="plan.operation === 'talk'" class="as-edit-col">
        <div class="as-edit-row"><span class="k">对象</span><span class="v mono">{{ editParams.npcRef }}</span></div>
        <textarea v-model="editParams.content" rows="2" class="as-edit-text" placeholder="插话内容（必填）"></textarea>
      </div>
      <!-- action：触发行动对象 + 理由 -->
      <div v-else-if="plan.operation === 'action'" class="as-edit-col">
        <div class="as-edit-row"><span class="k">对象</span><span class="v mono">{{ editParams.npcRef }}</span></div>
        <input v-model="editParams.reason" class="as-edit-input" placeholder="触发理由（可选）" />
      </div>
      <!-- event：注入事件标题 + 内容 -->
      <div v-else-if="plan.operation === 'event'" class="as-edit-col">
        <input v-model="editParams.title" class="as-edit-input" placeholder="事件标题（可选）" />
        <textarea v-model="editParams.content" rows="2" class="as-edit-text" placeholder="事件内容（必填）"></textarea>
      </div>
      <!-- clock：推进 / 倍速 / 暂停 / 常驻 -->
      <div v-else-if="plan.operation === 'clock'" class="as-edit-col">
        <label class="as-edit-check"><input type="checkbox" v-model="advanceSel" /> 手动推进世界一轮</label>
        <div class="as-edit-row">
          <span class="k">倍速</span>
          <input v-model.number="rateSel" type="number" min="0" step="1" class="as-edit-input" placeholder="保持原值" />
        </div>
        <div class="as-edit-row">
          <span class="k">暂停</span>
          <select v-model="pausedSel" class="as-edit-input">
            <option :value="null">保持原值</option>
            <option :value="true">暂停推进</option>
            <option :value="false">恢复推进</option>
          </select>
        </div>
        <div class="as-edit-row">
          <span class="k">常驻</span>
          <select v-model="worldRunningSel" class="as-edit-input">
            <option :value="null">保持原值</option>
            <option :value="1">开启常驻运行</option>
            <option :value="0">关闭常驻运行</option>
          </select>
        </div>
      </div>
      <div class="as-edit-actions">
        <button class="assistant-confirm-btn primary small" :disabled="busy || !reviseValid" @click="submitRevise">修改并执行</button>
        <button class="assistant-confirm-btn small" :disabled="busy" @click="editing = false">收起</button>
      </div>
    </div>

    <div v-if="!done && !editing" class="assistant-confirm-actions">
      <button class="assistant-confirm-btn primary" :disabled="busy" @click="emit('confirm', plan)">确认执行</button>
      <button class="assistant-confirm-btn revise" :disabled="busy" @click="startRevise">修改后执行</button>
      <button class="assistant-confirm-btn" :disabled="busy" @click="emit('cancel', plan)">取消</button>
    </div>
    <div v-else-if="done" class="assistant-confirm-done">{{ resultText || '已完成' }}</div>
  </div>
</template>

<script setup>
/**
 * 干预确认卡片（M4 能力域 F + M5「修改后执行」）。
 * 展示待确认干预计划（操作/影响/风险），提供确认执行 / 修改后执行 / 取消；
 * 修改后执行展开按操作类型的内联编辑表单（参数由后端 confirm 载荷 params 预填），
 * 提交后上抛 revise 事件（{ plan, params }），API 调用由父组件负责。
 * 纯展示 + 事件上抛，零 API 依赖。
 */
import { computed, reactive, ref } from 'vue'

const props = defineProps({
  /** 待确认计划载荷 {planId, sessionId, operation, operationLabel, detail, impact, risk, riskLabel, status, params} */
  plan: { type: Object, default: null },
  /** 执行/取消进行中（禁用按钮） */
  busy: { type: Boolean, default: false },
  /** 已处理（执行/取消完成） */
  done: { type: Boolean, default: false },
  /** 处理结果文本 */
  resultText: { type: String, default: '' }
})
const emit = defineEmits(['confirm', 'cancel', 'revise'])

const riskClass = computed(() => (props.plan?.risk === 'high' ? 'high' : 'low'))

// ==================== M5：修改后执行 ====================

const editing = ref(false)
const editParams = reactive({})
// clock 专用选择器（null=保持原值；与 params 松耦合，提交时按需写入）
const advanceSel = ref(false)
const rateSel = ref(null)
const pausedSel = ref(null)
const worldRunningSel = ref(null)

/** 各操作类型参数缺省骨架（confirm 载荷 params 缺失时兜底）。 */
function defaultParamsFor(op) {
  if (op === 'weather') return { condition: '晴' }
  if (op === 'talk' || op === 'action') return { npcRef: '' }
  if (op === 'event') return { content: '', title: '' }
  if (op === 'clock') return {}
  return {}
}

/** 展开编辑表单：用计划当前参数预填（talk/action 的 npcRef 只读展示）。 */
function startRevise() {
  const src = (props.plan && props.plan.params) || {}
  Object.assign(editParams, defaultParamsFor(props.plan?.operation), src)
  // clock 选择器初始化
  advanceSel.value = !!src.advance
  rateSel.value = src.rate != null ? Number(src.rate) : null
  pausedSel.value = src.paused != null ? !!src.paused : null
  worldRunningSel.value = src.worldRunning != null ? Number(src.worldRunning) : null
  editing.value = true
}

/** 必填项校验（talk/event 内容必填；weather 条件必选）。 */
const reviseValid = computed(() => {
  const op = props.plan?.operation
  if (op === 'talk' || op === 'event') return !!(editParams.content || '').trim()
  if (op === 'weather') return !!editParams.condition
  return true
})

/** 提交修改并执行：按操作类型收敛参数，上抛 revise 事件。 */
function submitRevise() {
  let out = {}
  const op = props.plan?.operation
  if (op === 'weather') {
    out = { condition: editParams.condition }
  } else if (op === 'talk') {
    out = { npcRef: editParams.npcRef, content: (editParams.content || '').trim() }
  } else if (op === 'action') {
    out = { npcRef: editParams.npcRef }
    if ((editParams.reason || '').trim()) out.reason = editParams.reason.trim()
  } else if (op === 'event') {
    out = { content: (editParams.content || '').trim() }
    if ((editParams.title || '').trim()) out.title = editParams.title.trim()
  } else if (op === 'clock') {
    if (advanceSel.value) out.advance = true
    if (rateSel.value != null && rateSel.value !== '') out.rate = Number(rateSel.value)
    if (pausedSel.value != null) out.paused = !!pausedSel.value
    if (worldRunningSel.value != null) out.worldRunning = Number(worldRunningSel.value)
  }
  emit('revise', { plan: props.plan, params: out })
}
</script>

<style scoped>
.assistant-confirm {
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 10px;
  background: var(--bg-elev, #ffffff);
  overflow: hidden;
}
.assistant-confirm-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--color-warning, #f0a020) 10%, transparent);
  border-bottom: 1px solid var(--border-l1, #eef1f6);
}
.assistant-confirm-type {
  font-size: 12.5px;
  color: var(--color-warning, #f0a020);
  background: color-mix(in srgb, var(--color-warning, #f0a020) 14%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}
.assistant-confirm-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong, #1f2a44);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assistant-confirm-risk {
  font-size: 12.5px;
  padding: 1px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.assistant-confirm-risk.high {
  color: var(--color-danger, #e5484d);
  background: color-mix(in srgb, var(--color-danger, #e5484d) 12%, transparent);
}
.assistant-confirm-risk.low {
  color: var(--color-success, #34c77b);
  background: color-mix(in srgb, var(--color-success, #34c77b) 12%, transparent);
}
.assistant-confirm-body {
  padding: 6px 12px 8px;
}
.assistant-confirm-row {
  display: flex;
  gap: 10px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border-l1, #eef1f6);
  font-size: 12.5px;
}
.assistant-confirm-row:last-child { border-bottom: none; }
.assistant-confirm-row .k {
  flex: 0 0 42px;
  color: var(--text-dim, #8a94a8);
}
.assistant-confirm-row .v {
  color: var(--text-main, #3a4660);
  word-break: break-all;
  line-height: 1.5;
}
/* ===== M5：修改后执行编辑表单 ===== */
.assistant-confirm-edit {
  margin: 0 12px 10px;
  padding: 8px 10px;
  border: 1px dashed var(--border-l2, #e5e9f2);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-primary, #4f6ef7) 4%, transparent);
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.as-edit-title { font-size: 12px; font-weight: 600; color: var(--color-primary, #4f6ef7); }
.as-edit-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.as-edit-row .k { flex: 0 0 42px; color: var(--text-dim, #8a94a8); }
.as-edit-col { display: flex; flex-direction: column; gap: 7px; }
.as-edit-check { font-size: 12.5px; color: var(--text-main, #3a4660); display: flex; align-items: center; gap: 6px; }
.as-edit-input {
  flex: 1;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 6px;
  background: var(--bg-input, #fff);
  color: var(--text-main, #3a4660);
  font-size: 12.5px;
}
.as-edit-text {
  width: 100%;
  resize: none;
  padding: 5px 8px;
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 6px;
  background: var(--bg-input, #fff);
  color: var(--text-main, #3a4660);
  font-size: 12.5px;
  line-height: 1.5;
}
.as-edit-input:focus, .as-edit-text:focus { outline: none; border-color: var(--color-primary, #4f6ef7); }
/* V2.0 a11y：键盘聚焦必须可见（鼠标仍走边框变色） */
.as-edit-input:focus-visible, .as-edit-text:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.as-edit-actions { display: flex; gap: 8px; margin-top: 2px; }
.assistant-confirm-actions {
  display: flex;
  gap: 8px;
  padding: 0 12px 10px;
}
.assistant-confirm-btn {
  flex: 1;
  padding: 7px 0;
  border-radius: 8px;
  border: 1px solid var(--border-l2, #e5e9f2);
  background: var(--bg-input, #fff);
  color: var(--text-main, #3a4660);
  font-size: 13px;
  cursor: pointer;
}
.assistant-confirm-btn.small { padding: 5px 0; font-size: 12.5px; }
.assistant-confirm-btn.primary {
  background: var(--color-primary, #4f6ef7);
  border-color: var(--color-primary, #4f6ef7);
  color: #fff;
}
.assistant-confirm-btn.revise {
  border-color: color-mix(in srgb, var(--color-primary, #4f6ef7) 40%, transparent);
  color: var(--color-primary, #4f6ef7);
}
.assistant-confirm-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.assistant-confirm-done {
  padding: 8px 12px 10px;
  font-size: 12.5px;
  color: var(--color-success, #34c77b);
  line-height: 1.5;
}
</style>
