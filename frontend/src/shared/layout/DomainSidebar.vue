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
    域侧栏：把 navModel（六域导航模型）接到 SideNav 表现层 + 路由。
    与上一版 ProjectLayout 内联侧栏的区别：
      ① 导航数据来自共享 navModel（顶栏图标抽屉、命令面板共用同一份，不再各自维护）；
      ② 宽度由 CSS 变量 --shell-side-w 驱动（可拖拽、可记忆、可折叠），不再硬编码 248px；
      ③ 右侧带拖拽手柄，双击复原默认宽度。
  -->
  <aside class="domain-sidebar" :class="{ 'is-icon-only': isIconOnly }">
    <!-- 侧栏头部：折叠/展开控制就放在侧栏自己身上（不再占用顶栏最左侧的位置） -->
    <div class="side-headbar" :class="{ 'is-icon-only': isIconOnly }">
      <span v-if="!isIconOnly" class="side-headbar-title">导航</span>
      <button
        type="button"
        class="side-collapse-btn"
        :title="isIconOnly ? '展开侧栏（Ctrl+B）' : '收起侧栏（Ctrl+B）'"
        @click="toggleSidebar"
      >
        <el-icon :size="16"><HIcon :name="isIconOnly ? 'Expand' : 'Fold'" /></el-icon>
      </button>
    </div>

    <SideNav
      :pinned="pinnedItems"
      :sections="sections"
      :active-key="activeKey"
      :collapsed="isIconOnly"
      @navigate="navigate"
      @context="onNavContext"
    >
      <template #foot>
        <!-- 项目状态小卡：草稿 / 已生成角色卡 / 进行中 + 角色数 -->
        <div class="side-status">
          <el-tag size="small" :type="statusType(project?.status)" effect="plain">
            {{ statusText(project?.status) }}
          </el-tag>
          <span v-if="project?.characterCount != null" class="status-count">
            {{ project.characterCount }} 角色
          </span>
        </div>
      </template>
    </SideNav>

    <!-- 宽度拖拽手柄（关闭「允许拖拽」时隐藏；双击恢复默认） -->
    <div
      v-if="!isIconOnly && layout.dragEnabled"
      class="side-resize"
      title="拖拽调整侧栏宽度（双击恢复默认）"
      @pointerdown="onResizeStart"
      @dblclick="onResizeReset"
    ></div>
  </aside>
</template>

<script setup>
/**
 * 域侧栏（项目空间）。
 *
 * <p>职责：① 依据 {@link navModel} 生成「置顶项 + 五域分组」导航（浮生界面），
 * 或「对谈界面独立项」导航（对谈界面）；② 处理点击导航（路由跳转 +
 * 窄屏覆盖式侧栏自动收起）；③ 提供宽度拖拽手柄；④ 展示项目状态小卡。</p>
 *
 * <p>导航顺序（对齐设计规格 §2.1）：浮生 = 置顶（世界地图 / 对话）+ 域（世界 / 角色 / 心智 / 观测 / 设置）；
 * 对谈 = 独立侧边栏（对谈会话 / 玩家档案），与浮生完全分开。</p>
 *
 * <p>高亮规则：由 {@link findItemByPath} 按当前路由反查命中的导航项 key，
 * 因此「旧路由（如 /world/edit）仍可直接访问，且高亮落在合并入口（世界详情）上」。</p>
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import SideNav from './SideNav.vue'
import { useShellLayout } from './useShellLayout'
import { openContextMenu } from './useContextMenu'
import { DOMAINS, PINNED_ITEMS, NAV_ITEMS, DIALOGUE_ITEMS, DIALOGUE_SECTIONS, itemPath, findItemByPath } from './navModel'

const props = defineProps({
  /** 项目 ID（用于拼装绝对路径） */
  projectId: { type: Number, required: true },
  /** 项目详情（状态小卡数据源） */
  project: { type: Object, default: null },
  /** 当前界面（浮生 / 对谈）：决定侧边栏内容——浮生 = 置顶项 + 五域；对谈 = 对谈界面独立项 */
  interfaceKey: { type: String, default: 'fusheng' },
  /** 是否图标窄栏形态（由 shell 传入） */
  isIconOnly: { type: Boolean, default: false }
})

const route = useRoute()
const router = useRouter()
const { startResize, resetWidth, toggleSidebar, layout } = useShellLayout()

const isDialogue = computed(() => props.interfaceKey === 'dialogue')

/** 置顶项（补全绝对路径，供 SideNav 展示与 title 提示）：对谈界面 = 对谈界面独立项 */
const pinnedItems = computed(() => (isDialogue.value ? DIALOGUE_ITEMS : PINNED_ITEMS).map(i => ({ ...i })))

/**
 * 域分组：浮生 = 五域；对谈 = 对谈分组（「设置」共享页收进一组，置顶项仍为对谈会话/玩家档案）。
 */
const sections = computed(() => isDialogue.value
  ? DIALOGUE_SECTIONS.map(s => ({ ...s, items: s.items.map(i => ({ ...i })) }))
  : DOMAINS.map(d => ({
      key: d.key,
      name: d.name,
      icon: d.icon,
      items: NAV_ITEMS.filter(i => i.domain === d.key).map(i => ({ ...i }))
    })).filter(s => s.items.length > 0))

/** 当前激活项 key：按路由反查（含 aliases 与子路径匹配） */
const activeKey = computed(() => {
  const hit = findItemByPath(route.path, props.projectId)
  return hit?.key || ''
})

/** 点击导航：拼绝对路径跳转；窄屏下顺带收起覆盖式侧栏（移动端直觉） */
function navigate(item) {
  const target = itemPath(props.projectId, item.path)
  if (!target || target === '#') return
  if (route.path !== target) router.push(target)
  layout.setOverlaySidebar(false)
}

/**
 * 右键导航项：弹出「与新标签页/复制链接相关」的桌面端习惯动作。
 *
 * <p>为什么只有这三项：右键菜单的价值是「把点击做不到的事变快」——新标签页打开、复制链接、
 * 重看导览。凡点击已经能一步完成的事（比如「打开」）不放进菜单，避免菜单成为第二套导航。</p>
 *
 * @param {MouseEvent} ev 右键事件
 * @param {object} item 导航项（navModel 条目）
 */
function onNavContext(ev, item) {
  const target = itemPath(props.projectId, item.path)
  const abs = target ? new URL(target, window.location.origin).href : ''
  openContextMenu(ev, {
    title: item.name,
    context: item,
    items: [
      {
        key: 'open-new-tab',
        label: '在新标签页打开',
        icon: 'ic_public_arrow_right',
        hint: 'Ctrl+点击',
        disabled: !abs,
        run: () => window.open(abs, '_blank', 'noopener')
      },
      {
        key: 'copy-link',
        label: '复制直达链接',
        icon: 'ic_public_copy',
        disabled: !abs,
        run: () => copyLink(abs)
      },
      {
        key: 'replay-tour',
        label: '重看界面导览',
        icon: 'ic_public_highlight',
        run: () => layout.startTour()
      }
    ]
  })
}

/**
 * 复制链接到剪贴板（失败不抛：非安全上下文/无权限时给一次可见提示，符合「降级不阻断」约定）。
 *
 * @param {string} text 要复制的文本
 * @returns {Promise<boolean>} 是否复制成功
 */
async function copyLink(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制链接')
    return true
  } catch (_) {
    ElMessage.warning('当前环境不允许写剪贴板，请手动复制地址栏')
    return false
  }
}

function onResizeStart(ev) { startResize(ev) }
function onResizeReset() { resetWidth() }

/** 项目状态文案（0 草稿 / 1 已生成角色卡 / 2 进行中） */
function statusText(s) {
  return s === 1 ? '已生成角色卡' : (s === 2 ? '进行中' : '草稿')
}
function statusType(s) {
  return s === 1 ? 'success' : (s === 2 ? 'primary' : 'info')
}
</script>

<style scoped>
/* 悬浮岛侧栏：圆角卡片 + 留白 + 柔和阴影；宽度由 --shell-side-w 驱动（可拖拽记忆） */
.domain-sidebar {
  width: var(--shell-side-w, 210px);
  flex-shrink: 0;
  margin: var(--shell-side-margin, 12px) 0 var(--shell-side-margin, 12px) var(--shell-side-margin, 12px);
  background: var(--bg-nav);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv1);
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
  transition: width .16s var(--dsw-ease);
}
/* 拖拽过程中禁用宽度过渡，避免跟手感变差 */
:global(body.is-resizing) .domain-sidebar { transition: none; }

/* 侧栏头部：左侧「导航」小标题 + 右侧折叠按钮；收起为图标栏时只留居中按钮 */
.side-headbar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 34px;
  padding: 0 8px 0 12px;
}
.side-headbar.is-icon-only { justify-content: center; padding: 0; }
.side-headbar-title {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: .08em;
  color: var(--text-tertiary);
  user-select: none;
}
.side-collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm, 8px);
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: background .15s var(--dsw-ease), color .15s var(--dsw-ease);
}
.side-collapse-btn:hover { background: var(--bg-hover); color: var(--text-primary); }

/* 宽度拖拽手柄（贴在右缘的 6px 热区） */
.side-resize {
  position: absolute;
  top: 0; right: -3px; bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 2;
}
.side-resize:hover { background: var(--accent-soft); }

.side-status { display: flex; align-items: center; gap: 8px; padding: 0 10px; flex-wrap: wrap; }
.status-count { font-size: 0.78rem; color: var(--text-tertiary); }
</style>
