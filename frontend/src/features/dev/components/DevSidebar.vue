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
    开发者控制台侧栏：9 个功能域的独立轨道。

    刻意复用 `domain-sidebar` 这个类名（而不是另起 .dev-sidebar）：
    主应用的悬浮岛侧栏、宽度令牌、窄屏覆盖式规则（AppShell 里的 :deep(.domain-sidebar)）
    都绑定在这个类上。换名字意味着「开发者页在窄屏下不滑入、在深浅色下描边不一致」，
    这正是「两套界面」的起点。
  -->
  <aside class="domain-sidebar dev-sidebar">
    <SideNav
      :pinned="items"
      :active-key="activeKey"
      @navigate="navigate"
    >
      <template #foot>
        <div class="dev-side-foot">
          <div class="dev-side-world" :title="devContext.error || devContext.worldName || '当前未挂载世界'">
            <el-icon class="dev-side-world-icon"><HIcon name="Earth" /></el-icon>
            <span class="dev-side-world-name">
              {{ devContext.loading ? '读取中…' : (devContext.worldName || '未挂载世界') }}
            </span>
          </div>
          <div class="dev-side-meta">
            <span>v{{ version }}</span>
            <span class="dev-side-dot">·</span>
            <span>{{ isDesktopShell() ? '独立窗口' : '浏览器' }}</span>
          </div>
          <el-button link size="small" class="dev-side-lock" @click="onLock">
            隐藏开发者入口
          </el-button>
        </div>
      </template>
    </SideNav>
  </aside>
</template>

<script setup>
/**
 * 开发者侧栏。
 *
 * <p>导航数据唯一来源 = {@link DEV_DOMAINS}（devNav.js）；本组件只做「域 key → 路由」的转发。</p>
 *
 * <p>底部不放「运行状态」而放「当前世界 + 版本 + 隐藏入口」：开发者页最容易犯的错是
 * 「以为在看 A 世界，其实看的是 B」，把世界名钉在侧栏底部是最便宜的对策。</p>
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import SideNav from '@/shared/layout/SideNav.vue'
import HIcon from '@/shared/components/HIcon.vue'
import { DEV_DOMAINS } from '../devNav'
import { devContext, resolveDevWorld } from '../devContext'
import { exitDevMode } from '../devExit'
import { isDesktopShell, useDevMode } from '@/shared/composables/useDevMode'
import { useUpdaterStore } from '@/shared/store/updater'

const route = useRoute()
const router = useRouter()
const { lock } = useDevMode()
const updater = useUpdaterStore()

/** 9 个域（一域一行，不折叠：全部可见才能形成「这是 9 个域」的空间记忆） */
const items = computed(() => DEV_DOMAINS.map((d) => ({
  key: d.key,
  name: d.name,
  icon: d.icon,
  desc: d.desc
})))

/** 当前域 key（按路径前缀匹配，NPC 详情等子路由也会落在「角色与状态」上） */
const activeKey = computed(() => {
  const hit = DEV_DOMAINS.find((d) => route.path === d.path || route.path.startsWith(d.path + '/'))
  return hit?.key || ''
})

/** 版本号（与设置页「当前版本」同源：桌面壳 updater 状态；浏览器下退回固定值） */
const version = computed(() => updater.currentVersion || '1.0.0')

/** 点击域 → 跳该域入口页 */
function navigate(item) {
  const def = DEV_DOMAINS.find((d) => d.key === item.key)
  if (def && route.path !== def.path) router.push(def.path)
}

/** 重新隐藏入口：清掉解锁标记并退回设置页（可再次连点版本号打开） */
async function onLock() {
  try {
    await ElMessageBox.confirm(
      '将隐藏设置页中的开发者入口，并退出当前页面。再次需要时，去「设置 → 关于 → 当前版本」连点 5 次即可重新打开。',
      '隐藏开发者入口',
      { type: 'warning', confirmButtonText: '隐藏并退出', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  lock()
  ElMessage.success('已隐藏开发者入口')
  // 与顶栏返回共用同一出口：桌面端直接关掉开发者独立窗口（不在本窗口再开一份世界界面）
  exitDevMode(router)
}

onMounted(() => {
  resolveDevWorld()
  updater.init()
})
</script>

<style scoped>
/*
  宽度与「悬浮岛」外观都必须在本组件复刻一遍。
  为什么不能省：`.domain-sidebar` 的宽度/背景/圆角写在 DomainSidebar.vue 的 scoped 样式里
  （`.domain-sidebar[data-v-xxx]`），而 scoped 属性只作用于该组件自己的模板，
  本组件的 <aside> 拿不到那些规则——于是：
  ① 外壳 `.shell-sidebar-slot`（按内容定宽）把宽度交给 max-content：「AI 调用与 Prompt」
     这一页比别的页宽 4px，切页时内容列左右跳 4px；
  ② 侧栏没有卡片底与描边，四边留白一加就变成「一块透明的浮空区」。
  这里复刻同一组令牌，观感与宽度就与主应用侧栏完全同源。
*/
.dev-sidebar {
  width: var(--shell-side-w, 232px);
  flex: 0 0 auto;
  min-width: 0;
  margin: var(--shell-side-margin, 12px);
  background: var(--bg-nav);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv1);
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}

.dev-side-foot {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dev-side-world {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-secondary);
  min-width: 0;
}
.dev-side-world-icon { font-size: 14px; flex-shrink: 0; color: var(--text-tertiary); }
.dev-side-world-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dev-side-meta { font-size: 0.72rem; color: var(--text-tertiary); }
.dev-side-dot { margin: 0 4px; }
.dev-side-lock { padding: 0; height: auto; font-size: 0.72rem; color: var(--text-tertiary); justify-content: flex-start; }
.dev-side-lock:hover { color: var(--accent-text); }
</style>
