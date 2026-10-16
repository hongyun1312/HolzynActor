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

  Toolbar：统一页内工具条（界面布局与 IA 人性化重构 V2.0 · 批次一）。

  为什么要有它（实测依据）：
    ① 沙盘页顶栏在 ≥1440 时一行有 **8 个视觉权重几乎相同**的控件
       （继续 / 推进一轮 / 世界运行中开关 / 天气 / 干预 / 对演·演绎 / 2 个图标），
       用户不知道先看哪个；@1024 这行折行，第二行右侧对齐、**左半整片留白**；
    ② PatternFly 工具条规范："工具栏内完整展示的动作不超过 3 个，其余进溢出菜单"；
       Apple HIG：分组最多 3 组；
    ③ 现状没有"优先级"概念，新增一个动作就往后排一个。

  规范（本组件用 API 形状强制）：
    常驻内联动作 ≤ maxInline（默认 3，宽屏档）；**空间不足时收进「⋯」而不是换行**——
    窄屏档（shellMode ≠ wide，即 <1440）内联数自动降到 narrowMaxInline（默认 1），
    其余动作仍然一键可达（溢出菜单），因此工具条**永不折行**。
    状态（如世界时钟/运行状态）走 #status 插槽固定在左侧，与动作区分层。

  用法：
    <Toolbar :actions="[
        { key:'advance', label:'推进一轮', kind:'primary' },
        { key:'weather', label:'天气', icon:'Sunny' },
        { key:'roleplay', label:'对演·演绎', icon:'VideoPlay' }
      ]" @action="onAction">
      <template #status><RuntimeStatusPill … /></template>
    </Toolbar>
-->
<template>
  <div class="tb">
    <!-- 左：状态区（世界时钟/运行状态等），与动作区分层 -->
    <div class="tb-status">
      <slot name="status"></slot>
    </div>

    <!-- 右：内联动作（≤ 预算）+ 溢出菜单 -->
    <div class="tb-actions">
      <slot name="before"></slot>

      <el-button
        v-for="a in inlineActions"
        :key="a.key"
        class="tb-act"
        :type="a.kind === 'primary' ? 'primary' : (a.kind === 'danger' ? 'danger' : 'default')"
        :size="a.size || 'default'"
        :disabled="a.disabled"
        :loading="a.loading"
        :title="a.title || a.label"
        @click="emit('action', a.key)"
      >
        <el-icon v-if="a.icon" :size="14"><HIcon :name="a.icon" /></el-icon>
        <span v-if="a.label" class="tb-act-label">{{ a.label }}</span>
      </el-button>

      <el-dropdown v-if="overflowActions.length" trigger="click" @command="k => emit('action', k)">
        <el-button class="tb-act tb-more" title="更多操作" :size="moreSize">
          <span class="tb-more-dots" aria-hidden="true">⋯</span>
          <span class="sr-only">更多操作</span>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="a in overflowActions" :key="a.key" :command="a.key" :disabled="a.disabled">
              <el-icon v-if="a.icon" :size="14"><HIcon :name="a.icon" /></el-icon>
              <span>{{ a.label }}</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <slot name="after"></slot>
    </div>
  </div>
</template>

<script setup>
/**
 * 统一页内工具条：状态区固定左侧，动作区固定右侧，并强制"常驻动作预算 + 溢出收纳"。
 *
 * <p>窄屏自动降低内联预算而不是折行——预算来自权威断点 {@link useShellLayout} 的 `shellMode`，
 * 不写任何媒体查询，保证与外壳的折叠档位永远一致。</p>
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.5
 */
import { computed } from 'vue'
import { useShellLayout } from '@/shared/layout/useShellLayout'

const props = defineProps({
  /** 动作数组：[{ key, label, icon, kind:'primary'|'default'|'danger', disabled, loading, title, size }] */
  actions: { type: Array, default: () => [] },
  /** 宽屏档常驻内联动作上限（PatternFly：工具条内完整展示不超过 3 个） */
  maxInline: { type: Number, default: 3 },
  /** 窄屏档（<1440）常驻内联动作上限（其余进溢出菜单，绝不折行） */
  narrowMaxInline: { type: Number, default: 1 },
  /** 溢出按钮尺寸（与页面其它按钮对齐用） */
  moreSize: { type: String, default: 'default' }
})
const emit = defineEmits(['action'])

const { shellMode } = useShellLayout()
const budget = computed(() => (shellMode.value === 'wide' ? props.maxInline : props.narrowMaxInline))
const inlineActions = computed(() => props.actions.slice(0, budget.value))
const overflowActions = computed(() => props.actions.slice(budget.value))
</script>

<style scoped>
.tb {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--toolbar-group-gap, 12px);
  min-height: var(--toolbar-h, 44px);
  padding: 6px 10px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 12px);
  /* 禁止折行：空间不足由「溢出菜单」消化，而不是换行（换行会留下整片空洞） */
  flex-wrap: nowrap;
  overflow: hidden;
}
.tb-status { display: flex; align-items: center; gap: var(--toolbar-gap, 8px); min-width: 0; flex: 1 1 auto; }
.tb-actions { display: flex; align-items: center; gap: var(--toolbar-gap, 8px); flex: 0 0 auto; }
.tb-act-label { margin-left: 2px; }
.tb-more-dots { font-weight: 700; letter-spacing: 1px; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>