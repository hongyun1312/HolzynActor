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

/**
 * 前端导航模型（六域信息架构的**唯一权威**）。
 *
 * <p>职责：把「项目空间有哪些功能入口、归属哪个域、用什么图标、排在什么位置、是否全屏」
 * 集中成一份纯数据，供 `<DomainSidebar>` / `<ShellTopbar>` / `<CommandPalette>` 三方共用——
 * 避免上一版「侧栏自带一份 navGroups、页面各自再写一份入口」的重复与分叉。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>只描述**相对路径**（不含 /project/:id 前缀），由 {@link itemPath} 拼装，项目切换自动跟随。</li>
 *   <li>`aliases` 用于「同域合并入口」：旧路由（如 world/edit）仍可直接访问，但侧栏高亮仍落在合并入口上。</li>
 *   <li>图标名必须是 `harmonyIcons` 注册表内的名称（Element Plus 兼容名或 ic_public_* 文件名）；
 *       未注册的名字 HIcon 会渲染为空，因此本文件是图标名的白名单来源。</li>
 *   <li>本文件**零依赖**（不 import vue / router），可在任何环境安全引用。</li>
 * </ul>
 *
 * <p>信息架构依据：docs/设计文档/HolzynActor_前端布局重构设计规格_V1.0.md §2。</p>
 */

/** 五大功能域（顺序即侧栏分组顺序） */
export const DOMAINS = [
  { key: 'world', name: '世界', icon: 'ic_public_worldclock', order: 1, desc: '世界观、演化与时间轴' },
  { key: 'cast', name: '角色', icon: 'ic_public_contacts', order: 2, desc: 'NPC、人群、关系与谱系' },
  { key: 'mind', name: '心智', icon: 'ic_public_albums', order: 3, desc: '记忆、编年史与知识库' },
  { key: 'observe', name: '观测', icon: 'ic_public_view_list', order: 4, desc: '运行概览、图谱与用量' },
  { key: 'settings', name: '设置', icon: 'ic_public_settings', order: 5, desc: '通用、API、Prompt 与界面' }
]

/**
 * 置顶固定项（不属于任何域，永远排在侧栏最前）。
 * <p>world-sandbox 是项目绝对主界面（默认落地页，M4 起为 2D 世界地图新首页），
 * chat 是最高频交互入口。</p>
 */
export const PINNED_ITEMS = [
  { key: 'sandbox', name: '世界地图', icon: 'ic_gallery_map_all', path: 'sandbox', desc: '2D 世界地图 · 双主角并置（角色活动流 + 世界演化）' },
  { key: 'chat', name: '对话', icon: 'ic_public_message', path: 'chat', desc: '单聊与群聊' }
]

/**
 * 域内导航项。
 * <p>字段：key 唯一标识；domain 归属域；path 相对路径；aliases 旧路由等价路径（仅影响高亮与命令面板别名）；
 * fullbleed 是否需要全屏内容区（仅沙盘使用）；hiddenInSidebar 为 true 时不进侧栏（仅命令面板可达）。</p>
 */
export const NAV_ITEMS = [
  // ===== 域① 世界 =====
  { key: 'world-detail', domain: 'world', name: '世界详情', icon: 'ic_public_file', path: 'world', aliases: ['world/edit'], desc: '世界观全文与结构化设定' },
  // 旧演化页（EvolveView）已废弃（M4：对戏/演绎能力保留为新首页内抽屉），侧栏不再展示；
  // 路由 /evolve 仍保留（旧深链不失效），命令面板不再收录该入口。
  // { key: 'evolve', domain: 'world', name: '世界演化', icon: 'ic_public_refresh', path: 'evolve', desc: '世界模拟控制台' },
  { key: 'timeline', domain: 'world', name: '时间线', icon: 'ic_public_clock', path: 'timeline', desc: '事件竖轴与行动记录' },

  // ===== 域② 角色 =====
  { key: 'characters', domain: 'cast', name: 'NPC 角色', icon: 'ic_public_contacts', path: 'characters', desc: '特殊 NPC 档案、记忆与人格面具' },
  { key: 'crowds', domain: 'cast', name: '普通人群', icon: 'ic_public_comments', path: 'crowds', desc: '普通型 NPC 名单与调度' },
  { key: 'topology', domain: 'cast', name: '关系拓扑', icon: 'ic_public_connection', path: 'topology', desc: '全角色关系网络图' },
  { key: 'lineage', domain: 'cast', name: '家族谱系', icon: 'ic_public_albums', path: 'lineage', desc: '血缘谱系与基因档案' },

  // ===== 域③ 心智 =====
  { key: 'world-memory', domain: 'mind', name: '项目记忆', icon: 'ic_public_time', path: 'world-memory', desc: '世界编年史与实体状态' },
  { key: 'archive', domain: 'mind', name: '记忆归档', icon: 'ic_public_folder', path: 'archive', desc: '超龄记忆冷存储' },
  { key: 'knowledge', domain: 'mind', name: '知识库', icon: 'ic_public_highlight', path: 'knowledge', desc: '知识文档、检索预览与大事记' },

  // ===== 域④ 观测 =====
  // 说明：「AI 用量」页物理上位于 /settings/usage（复用设置外壳），但语义属于观测域，
  // 因此**只在观测域登记一次**——若两个域登记同一路径，侧栏高亮会出现歧义（谁在先谁赢）。
  // 批次② 新增「世界图谱」页后，观测域再补一项。
  { key: 'dashboard', domain: 'observe', name: '项目仪表盘', icon: 'ic_public_home', path: 'dashboard', desc: '项目概况、统计与最近动态' },
  // 批次③ 新增：世界图谱（七个只读切面：时间/地点/角色/事件/关系/势力/宏观）
  { key: 'world-graph', domain: 'observe', name: '世界图谱', icon: 'ic_public_connection', path: 'graph', desc: '时间、地点、角色、事件、关系、势力、宏观七切面' },
  { key: 'usage', domain: 'observe', name: 'AI 用量', icon: 'ic_public_view_list', path: 'settings/usage', desc: 'Token、场景与模型用量统计' },

  // ===== 域⑤ 设置 =====
  { key: 'settings-general', domain: 'settings', name: '通用', icon: 'ic_public_settings', path: 'settings/general', desc: '本地账户、主题外观、世界时钟与导入导出' },
  { key: 'settings-apis', domain: 'settings', name: 'API 配置', icon: 'ic_public_connection', path: 'settings/apis', desc: '模型供应商与密钥' },
  { key: 'settings-prompts', domain: 'settings', name: 'Prompt 模板', icon: 'ic_public_edit', path: 'settings/prompts', desc: '场景模板覆盖与版本' },
  { key: 'settings-appearance', domain: 'settings', name: '界面与布局', icon: 'ic_public_highlight', path: 'settings/appearance', desc: '面板宽度、信息密度、抽屉固定与导览' }
]

/**
 * 顶部图标抽屉栏入口（酒馆式肌肉记忆的载体）。
 * <p>两类：`nav` 型点击后展开该主题的快捷入口清单；`panel` 型展开一个功能面板（帮助/运行状态）。</p>
 */
export const TOPBAR_ENTRIES = [
  { key: 'cast', name: '角色', icon: 'ic_public_contacts', kind: 'nav', target: 'cast', hint: '角色卡、人群、关系与谱系' },
  { key: 'mind', name: '记忆与知识', icon: 'ic_public_albums', kind: 'nav', target: 'mind', hint: '记忆、编年史与知识库' },
  { key: 'world', name: '世界设定', icon: 'ic_public_worldclock', kind: 'nav', target: 'world', hint: '世界观、演化与时间线' },
  { key: 'command', name: '命令面板', icon: 'ic_public_search', kind: 'panel', target: 'command', hotkey: 'Ctrl+K', hint: '搜索页面、角色与动作' },
  { key: 'ai-runtime', name: 'AI 运行状态', icon: 'ic_public_spinner', kind: 'panel', target: 'ai-runtime', hint: '推理中 / 排队 / 今日失败' },
  { key: 'help', name: '帮助与快捷键', icon: 'ic_public_file', kind: 'panel', target: 'help', hotkey: '?', hint: '快捷键速查、上手教程与导览' }
]

/** 取某域下的导航项（按 NAV_ITEMS 声明顺序） */
export function itemsOfDomain(domain) {
  return NAV_ITEMS.filter(i => i.domain === domain)
}

/** 判断某导航项是否对应当前路由路径（含 aliases 与子路径前缀匹配） */
export function isItemActive(item, currentPath, projectId) {
  const paths = [item.path, ...(item.aliases || [])]
  return paths.some(p => {
    const full = itemPath(projectId, p)
    return currentPath === full || currentPath.startsWith(full + '/')
  })
}

/** 拼装项目内绝对路径（`/project/:id/<rel>`）；projectId 为空时返回 `#`（调用方应避免渲染） */
export function itemPath(projectId, relPath) {
  if (projectId === null || projectId === undefined || projectId === '') return '#'
  return `/project/${projectId}/${relPath}`
}

/** 由当前路由路径反查命中的导航项（用于侧栏高亮与顶栏标题） */
export function findItemByPath(currentPath, projectId) {
  const all = [...PINNED_ITEMS, ...NAV_ITEMS]
  return all.find(i => isItemActive(i, currentPath, projectId)) || null
}

/** 取域元信息 */
export function domainOf(key) {
  return DOMAINS.find(d => d.key === key) || null
}
