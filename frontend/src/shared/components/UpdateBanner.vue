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
    全局更新横幅（U1 更新地基）：发现新版本 → 顶部轻量提示「真实世界再进化」。
    不打断当前对谈/沙盘操作；「查看」进入设置-更新与版本页处理，「稍后」本次会话内隐藏。
  -->
  <transition name="uv-banner">
    <div v-if="visible" class="uv-banner">
      <div class="uv-banner-inner">
        <span class="uv-banner-pulse" />
        <span class="uv-banner-text">
          <template v-if="store.portable">发现新版本 v{{ store.newVersion }} · 前往下载页更新</template>
          <template v-else-if="store.downloaded">新版 v{{ store.newVersion }} 已就绪，真实世界再进化</template>
          <template v-else>发现新版本 v{{ store.newVersion }} · 真实世界再进化</template>
        </span>
        <el-button size="small" type="primary" @click="goUpdateSettings">查看</el-button>
        <el-button size="small" text @click="dismiss">稍后</el-button>
      </div>
    </div>
  </transition>
</template>

<script setup>
/**
 * 全局更新横幅：订阅 updater store，发现新版本（可用/下载中/已下载）时在窗口顶部展示。
 * <p>「稍后」仅在本次会话内隐藏（刷新/重启后重新提示）。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUpdaterStore } from '@/shared/store/updater'

const store = useUpdaterStore()
const route = useRoute()
const router = useRouter()
const dismissed = ref(false)

const visible = computed(() => store.ready && store.hasUpdate && !dismissed.value && !store.isDev)

onMounted(() => store.init())

function dismiss() {
  dismissed.value = true
}

function goUpdateSettings() {
  dismissed.value = true
  const projectId = route.params?.id
  const target = projectId ? `/world/${projectId}/settings/update` : '/settings/update'
  router.push(target)
}
</script>

<style scoped>
.uv-banner {
  position: fixed;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2600;
  max-width: min(640px, calc(100vw - 24px));
  background: var(--bg-nav, rgba(15, 23, 42, 0.92));
  border: 1px solid var(--border-l1, #22304a);
  border-radius: 999px;
  box-shadow: var(--shadow-lv2, 0 10px 30px rgba(0, 0, 0, 0.35));
  backdrop-filter: blur(12px);
  padding: 6px 10px 6px 14px;
}
.uv-banner-inner {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}
.uv-banner-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent, #4f7cff);
  box-shadow: 0 0 0 0 rgba(79, 124, 255, 0.6);
  animation: uv-pulse 1.6s infinite;
  flex-shrink: 0;
}
.uv-banner-text {
  font-size: 13px;
  color: var(--text-primary, #e6ebf5);
  overflow: hidden;
  text-overflow: ellipsis;
}
@keyframes uv-pulse {
  0% { box-shadow: 0 0 0 0 rgba(79, 124, 255, 0.6); }
  70% { box-shadow: 0 0 0 8px rgba(79, 124, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(79, 124, 255, 0); }
}
.uv-banner-enter-active,
.uv-banner-leave-active {
  transition: all 0.25s ease;
}
.uv-banner-enter-from,
.uv-banner-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}
</style>
