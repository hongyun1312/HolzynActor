/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { ref, computed } from 'vue'

/**
 * 命令注册表（命令面板 / 快捷键 / 右键菜单的公共底座）。
 *
 * <p>职责：让「任何页面都能把一条可执行动作」登记为全局可搜索的命令，而不需要把动作硬编码进
 * 命令面板。骨架层注册导航/主题/密度/抽屉等系统命令；业务页可在 onMounted 时注册自己的命令
 * （如「新增角色」「重跑世界初始化」），onBeforeUnmount 时注销。</p>
 *
 * <p>命令对象结构：</p>
 * <pre>
 * {
 *   id: 'nav.sandbox',          // 全局唯一
 *   title: '前往世界沙盘',        // 面板展示标题
 *   group: '导航',               // 分组（面板按组聚类）
 *   icon: 'ic_gallery_map_all',  // HIcon 名称
 *   keywords: ['沙盘', '3d'],    // 额外检索词（不区分大小写）
 *   hotkey: 'Ctrl+K',            // 可选，面板右侧展示
 *   run: (ctx) => {},            // 执行体（ctx 由调用方注入，如 router / projectId）
 *   enabled: () => true          // 可选：当前是否可用（false 时不展示）
 * }
 * </pre>
 *
 * <p>说明：注册表是**模块级单例**（跨组件共享），但只保存命令元数据，不保存组件状态；
 * 页面卸载必须注销自己的命令，避免指向已销毁组件的方法。</p>
 */

/** 命令列表（响应式数组；同 id 重复注册时后者覆盖前者） */
const commands = ref([])

/** 注册一条命令（幂等：同 id 覆盖） */
export function registerCommand(cmd) {
  if (!cmd || !cmd.id) return
  const idx = commands.value.findIndex(c => c.id === cmd.id)
  if (idx >= 0) {
    const next = commands.value.slice()
    next[idx] = cmd
    commands.value = next
  } else {
    commands.value = [...commands.value, cmd]
  }
}

/** 批量注册（页面用） */
export function registerCommands(list) {
  (list || []).forEach(registerCommand)
}

/** 注销一条命令 */
export function unregisterCommand(id) {
  commands.value = commands.value.filter(c => c.id !== id)
}

/** 注销一组命令（按前缀，便于页面整体清理：unregisterCommandsByPrefix('character.')） */
export function unregisterCommandsByPrefix(prefix) {
  commands.value = commands.value.filter(c => !c.id.startsWith(prefix))
}

/** 当前全部可见命令（过滤 enabled()===false） */
export const allCommands = computed(() => commands.value.filter(c => !c.enabled || c.enabled()))

/**
 * 关键词过滤（轻量打分：标题前缀 > 标题包含 > 关键词包含 > 分组包含）。
 * <p>批次③ 接入全局搜索后会叠加 fuse.js 模糊匹配；此处保持零依赖可用的确定性排序。</p>
 */
export function filterCommands(keyword, limit = 40) {
  const kw = String(keyword || '').trim().toLowerCase()
  const list = allCommands.value
  if (!kw) return list.slice(0, limit)
  const scored = []
  for (const c of list) {
    const title = (c.title || '').toLowerCase()
    const group = (c.group || '').toLowerCase()
    const keys = (c.keywords || []).map(k => String(k).toLowerCase())
    let score = -1
    if (title === kw) score = 100
    else if (title.startsWith(kw)) score = 80
    else if (title.includes(kw)) score = 60
    else if (keys.some(k => k === kw)) score = 55
    else if (keys.some(k => k.includes(kw))) score = 40
    else if (group.includes(kw)) score = 20
    else if ((c.id || '').toLowerCase().includes(kw)) score = 10
    if (score >= 0) scored.push({ c, score })
  }
  return scored.sort((a, b) => b.score - a.score).map(s => s.c).slice(0, limit)
}

/** 按 id 取命令 */
export function findCommand(id) {
  return allCommands.value.find(c => c.id === id) || null
}

/**
 * 执行命令（统一 try/catch：命令面板/快捷键/右键菜单共用入口，单条失败不影响其它调用方）。
 * @returns {boolean} 是否成功执行
 */
export function executeCommand(id, ctx) {
  const cmd = findCommand(id)
  if (!cmd || typeof cmd.run !== 'function') return false
  try {
    cmd.run(ctx)
    return true
  } catch (e) {
    console.error('[commandRegistry] 命令执行失败:', id, e)
    return false
  }
}
