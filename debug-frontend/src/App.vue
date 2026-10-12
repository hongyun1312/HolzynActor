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
    调试台根组件：只持有「后端健康 / 版本 / 活跃 SSE」状态并透传给外壳。
    顶栏 + 分组侧栏 + 内容区的结构与样式全部在 <DebugShell>（重构前 225 行内联在本文件）。
  -->
  <DebugShell :online="online" :version="version" :sse-count="sseCount" @toggle-theme="toggleMode">
    <router-view />
  </DebugShell>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { health, get } from './api'
import { useTheme } from './theme'
import DebugShell from './components/DebugShell.vue'

const online = ref(false)
const version = ref('')
const sseCount = ref(-1)
const { toggleMode } = useTheme()

let timer = null

async function refreshHealth() {
  const h = await health()
  online.value = !!h
  if (h?.name) version.value = 'v' + (h?.name || '')
  try {
    const o = await get('/debug/overview')
    const backend = o?.backend || {}
    sseCount.value = backend.activeSse ?? -1
  } catch (_) { /* 概览不可用时保持未知 */ }
}

onMounted(() => {
  refreshHealth()
  timer = setInterval(refreshHealth, 10000)
})

onBeforeUnmount(() => clearInterval(timer))
</script>
