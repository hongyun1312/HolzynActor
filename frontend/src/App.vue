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
<!-- HolzynActor 前端根组件：所有页面直接渲染（登录/注册/控制台/管理后台均自带布局） -->
<template>
  <!-- 全局更新横幅（桌面端 U1）：发现新版本时窗口顶部轻量提示，不打断任何页面操作 -->
  <UpdateBanner />
  <router-view />
  <!-- 新用户 7 步交互式引导（全局：画廊 → 示例世界 → 世界内；pending 标记由首次向导置位） -->
  <FirstRunGuide />
</template>

<script setup>
/**
 * 应用根组件。
 *
 * <p>除渲染路由外，还承担一件全局职责：开发者模式快捷键 `Ctrl+Shift+D`。
 * 放在根组件而不是 AppShell，是为了让快捷键在**所有**页面有效（设置页、向导页、
 * 世界画廊、设置页、向导页都没有 AppShell，但用户在那里按快捷键同样应该能唤出开发者控制台）。</p>
 */
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import UpdateBanner from '@/shared/components/UpdateBanner.vue'
import FirstRunGuide from '@/shared/layout/FirstRunGuide.vue'
import { isDevUnlocked, useDevMode } from '@/shared/composables/useDevMode'

const router = useRouter()
const { openDevConsole } = useDevMode()

/**
 * 全局快捷键：Ctrl+Shift+D 打开开发者控制台。
 *
 * <p>未解锁时给一次「怎么解锁」的提示而不是静默——否则用户会以为快捷键坏了。
 * 在输入框里也响应（Ctrl+Shift 组合不会与正常输入冲突）。</p>
 *
 * @param {KeyboardEvent} ev 键盘事件
 */
function onKeydown(ev) {
  if (!ev.ctrlKey || !ev.shiftKey || ev.altKey) return
  if (String(ev.key || '').toLowerCase() !== 'd') return
  ev.preventDefault()
  if (!isDevUnlocked()) {
    ElMessage.warning('开发者模式未解锁：设置 → 更新与版本 → 连点版本号 5 次')
    return
  }
  openDevConsole(router, '/dev/overview')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
