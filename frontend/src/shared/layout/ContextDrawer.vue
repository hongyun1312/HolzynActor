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
    右侧上下文抽屉（全局单例，挂在 AppShell 上）。
    内容由 useContextDrawer().openWith(组件, props, meta) 动态注入；
    非模态（可继续操作左侧列表），宽度走 --shell-context-w 令牌。
  -->
  <el-drawer
    v-model="contextOpen"
    direction="rtl"
    :modal="false"
    :size="drawerWidth"
    :with-header="false"
    class="ctx-drawer"
    @closed="onClosed"
  >
    <div class="ctx-head">
      <el-icon class="ctx-icon" :size="16"><HIcon :name="contextMeta.icon" /></el-icon>
      <span class="ctx-title">{{ contextMeta.title || '详情' }}</span>
      <span v-if="contextMeta.subtitle" class="ctx-subtitle">{{ contextMeta.subtitle }}</span>
      <button class="ctx-close" title="关闭（Esc）" @click="close">
        <el-icon :size="15"><HIcon name="Close" /></el-icon>
      </button>
    </div>
    <div class="ctx-body">
      <component :is="contextView" v-if="contextView" v-bind="contextProps" />
      <div v-else class="ctx-empty">未选择对象</div>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 右侧上下文抽屉（全局单例）。
 *
 * <p>职责：渲染 {@link useContextDrawer} 单例状态中的动态组件，提供统一头部（图标/标题/副标题/关闭）。
 * 非模态设计：用户可以在左侧列表继续点选，抽屉内容随之切换——这是「列表 + 详情」场景最顺手的形态。</p>
 */
import { computed } from 'vue'
import { useContextDrawer } from './useContextDrawer'

const { contextOpen, contextMeta, contextView, contextProps, close } = useContextDrawer()

/** 宽度：读 CSS 令牌，保证与设计规格一致（窄屏时收窄到 86vw） */
const drawerWidth = computed(() => {
  if (typeof window === 'undefined') return '360px'
  const vw = window.innerWidth
  if (vw < 1024) return `${Math.round(vw * 0.86)}px`
  return 'var(--shell-context-w, 360px)'
})

/** el-drawer 关闭动画结束后兜底清理组件引用（用户点遮罩/右上角关闭都走这里） */
function onClosed() {
  if (!contextOpen.value) return
  close()
}
</script>

<style scoped>
.ctx-head {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  height: 42px;
  padding: 0 var(--space-3, 12px);
  border-bottom: 1px solid var(--border-l1);
  background: var(--bg-layer-2);
}
.ctx-icon { color: var(--accent-text); }
.ctx-title { font-size: var(--density-font, 0.9rem); font-weight: 600; color: var(--text-primary); white-space: nowrap; }
.ctx-subtitle {
  flex: 1; min-width: 0;
  font-size: 0.74rem; color: var(--text-tertiary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ctx-close {
  margin-left: auto;
  width: 26px; height: 26px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: none; border-radius: var(--radius-sm, 8px);
  color: var(--text-secondary); cursor: pointer;
}
.ctx-close:hover { background: var(--bg-hover); color: var(--text-primary); }
.ctx-body { flex: 1; min-height: 0; overflow-y: auto; padding: var(--density-card-pad, 16px); }
.ctx-empty { padding: 24px; text-align: center; color: var(--text-tertiary); font-size: 0.82rem; }
</style>

<style>
/* 抽屉本体：与设计令牌统一（el-drawer 默认背景/边框由这里覆盖） */
.ctx-drawer .el-drawer__body { padding: 0; display: flex; flex-direction: column; overflow: hidden; }
.ctx-drawer { background: var(--bg-layer-1); border-left: 1px solid var(--border-l1); }
</style>
