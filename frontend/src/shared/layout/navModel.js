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
 * <p>双界面信息架构：项目空间拆为「浮生」与「对谈」两个**完全独立**的界面——
 * 浮生 = 除对谈外的一切功能（世界 / 角色 / 心智 / 观测 / 设置五域 + 置顶项）；
 * 对谈 = 独立的静止世界聊天界面（会话 / 玩家档案），拥有自己的侧边栏。
 * 左上角世界名右侧的切换按钮在两者间切换（见 {@link INTERFACES}）。</p>
 *
 * <p>信息架构依据：docs/设计文档/HolzynActor_前端布局重构设计规格_V1.0.md §2。</p>
 */

/**
 * 项目空间的两大界面（顶层切换）。
 * <p>`landing` 为该界面的默认落地相对路径；切换界面时跳转到对应落地页。</p>
 */
export const INTERFACES = [
  { key: 'fusheng', name: '浮生', icon: 'ic_public_worldclock', desc: '世界运行 · 角色 · 记忆 · 观测 · 设置', landing: 'sandbox' },
  { key: 'dialogue', name: '对谈', icon: 'ic_public_message', desc: '与角色对谈 · 世界静止', landing: 'dialogue' }
]

/** 五大功能域（顺序即侧栏分组顺序） */
export const DOMAINS = [
  { key: 'world', name: '世界', icon: 'ic_public_worldclock', order: 1, desc: '世界观、演化与时间轴' },
  { key: 'cast', name: '角色', icon: 'ic_public_contacts', order: 2, desc: 'NPC、人群、关系与谱系' },
  { key: 'mind', name: '心智', icon: 'ic_public_albums', order: 3, desc: '记忆、编年史与知识库' },
  { key: 'observe', name: '观测', icon: 'ic_public_view_list', order: 4, desc: '运行概览、图谱与用量' },
  { key: 'settings', name: '设置', icon: 'ic_public_settings', order: 5, desc: '通用、API、Prompt 与界面' }
]

/**
 * 置顶固定项（不属于任何域，永远排在侧栏最前，**仅属于「浮生」界面**）。
 * <p>world-sandbox 是项目绝对主界面（默认落地页，M4 起为 2D 世界地图新首页，旧 3D 沙盘已于 P0-4 删除），
 * chat 是最高频交互入口（世界联动聊天）。</p>
 * <p>「对谈」不再置顶于浮生侧栏——它已升级为与浮生同级的独立界面（见 {@link INTERFACES} 与
 * {@link DIALOGUE_ITEMS}），由左上角世界名右侧的切换按钮进入，拥有自己的侧边栏。</p>
 */
export const PINNED_ITEMS = [
  { key: 'sandbox', name: '世界地图', icon: 'ic_gallery_map_all', path: 'sandbox', desc: '2D 世界地图 · 双主角并置（角色活动流 + 世界演化）' },
  // V2.0（P3 命名消歧）：聊天有两套语义完全不同的模式，中文名仅一字之差，用户极易混淆——
  // 「对话」= 世界仍在运行时的单聊/群聊；「对谈」= 世界被冻结的静止对谈。
  // 因此在这两个入口上加副标签（sub），由 SideNav 渲染为弱化小字，主名不变（不破坏肌肉记忆）。
  { key: 'chat', name: '对话', sub: '世界运行中', icon: 'ic_public_message', path: 'chat', desc: '单聊与群聊（世界继续推进）' },
  // V2.0（P4 世界直播）：此前回答「现在世界在发生什么」只有沙盘右栏一小块事件流，
  // 必须先进入地图页、还要在画布旁边挤着看 8 行小字（§4.1 诊断第 4 条）。
  // 本页把「事件流 + 角色当前行动 + 宏观指标 + 世界时钟」聚合为只读观测页，按方案置顶第 3 项。
  { key: 'live', name: '世界直播', icon: 'ic_public_play', path: 'live', desc: '此刻世界正在发生什么：实时事件流 · 角色当前行动 · 宏观快照' }
]

/**
 * 「对谈」界面的侧边栏置顶项（独立界面，与浮生五域完全分开）。
 * <p>渲染为对谈界面的置顶列表；路径全部位于 /dialogue 子树下，
 * 由 {@link interfaceOfPath} 判定当前所处界面。</p>
 */
export const DIALOGUE_ITEMS = [
  { key: 'dialogue-chat', name: '对谈', sub: '世界静止', icon: 'ic_public_message', path: 'dialogue', desc: '与角色对谈 · 会话与聊天（世界冻结，零推进）' },
  { key: 'dialogue-player', name: '玩家档案', icon: 'ic_public_contacts', path: 'dialogue/player-profile', desc: '你的 persona 与「与我的关系」' }
]

/**
 * 设置子页清单（**唯一事实来源**）。
 *
 * <p>V2.0（P1 设置收敛）背景：改造前设置被复制成三份——`/settings/*`(8) +
 * `/project/:id/settings/*`(8) + `/project/:id/dialogue/settings/*`(7)，共 23 条路由指向同一批组件；
 * 且「对谈」界面侧栏 9 项里有 6 项是设置子页（占比 67%），侧栏被设置淹没。
 * 现在：设置子页清单只在本处声明一次，供三处复用——
 * ① 设置区自身的导航轨道（`SettingsRail`，项目/全局模式共用）；
 * ② 域侧栏的「设置」域（浮生界面）；
 * ③ 命令面板与全局搜索的导航条目。</p>
 *
 * <p>{@code rel} 为相对 `settings/` 的路径段，绝对路径由使用方按界面前缀拼接
 * （`/project/:id/settings/<rel>` 或 `/project/:id/dialogue/settings/<rel>`）。</p>
 */
export const SETTINGS_SUB_ITEMS = [
  { key: 'general', rel: 'general', name: '通用设置', icon: 'ic_public_settings', desc: '账户、主题外观、世界时钟、导入导出' },
  { key: 'apis', rel: 'apis', name: 'API 配置', icon: 'ic_public_connection', desc: '模型供应商与密钥' },
  { key: 'prompts', rel: 'prompts', name: 'Prompt 模板', icon: 'ic_public_edit', desc: '场景模板覆盖与版本' },
  { key: 'usage', rel: 'usage', name: 'AI 用量', icon: 'ic_public_view_list', desc: 'Token、场景与模型用量统计' },
  { key: 'appearance', rel: 'appearance', name: '界面与布局', icon: 'ic_public_highlight', desc: '面板宽度、信息密度、抽屉固定与导览' },
  { key: 'assistant', rel: 'assistant', name: '助手设置', icon: 'ic_public_message', desc: '项目级 AI 助手：说明、docs 索引与工具' },
  { key: 'player-profile', rel: 'player-profile', name: '玩家档案', icon: 'ic_public_contacts', desc: '玩家 persona 与角色关系设定' },
  { key: 'update', rel: 'update', name: '更新与版本', icon: 'ic_public_download', desc: '检查更新、下载与数据备份回滚' }
]

/**
 * 「对谈」界面的「设置」分组。
 *
 * <p>V2.0（P1）：**由 6 个子项收敛为 1 个入口**——点击进入设置区后，设置区自身渲染完整的
 * 子页导航轨道（见 {@link SETTINGS_SUB_ITEMS} 与 `SettingsRail`），因此功能一个不少，
 * 但侧栏不再被设置淹没（对谈侧栏 9 项 → 3 项）。</p>
 *
 * <p>路径仍走 `/dialogue/settings/*` 子树：这样 {@link interfaceOfPath} 判定当前界面仍是「对谈」，
 * 用户从对谈进设置不会突然被切到「浮生」的侧栏（界面语境不跳变）。</p>
 */
export const DIALOGUE_SETTINGS_ITEMS = [
  {
    key: 'dialogue-settings',
    name: '设置',
    icon: 'ic_public_settings',
    path: 'dialogue/settings/general',
    desc: '通用 / API / Prompt / 用量 / 界面 / 助手 / 玩家档案 / 更新'
  }
]

/** 「对谈」界面的分组（当前仅「设置」一组；置顶项见 {@link DIALOGUE_ITEMS}） */
export const DIALOGUE_SECTIONS = [
  { key: 'dialogue-settings', name: '设置', icon: 'ic_public_settings', items: DIALOGUE_SETTINGS_ITEMS }
]

/**
 * 域内导航项。
 * <p>字段：key 唯一标识；domain 归属域；path 相对路径；aliases 旧路由等价路径（仅影响高亮与命令面板别名）；
 * fullbleed 是否需要全屏内容区（仅沙盘使用）；hiddenInSidebar 为 true 时不进侧栏（仅命令面板可达）。</p>
 */
export const NAV_ITEMS = [
  // ===== 域① 世界 =====
  { key: 'world-detail', domain: 'world', name: '世界详情', icon: 'ic_public_file', path: 'world', aliases: ['world/edit'], desc: '世界观全文与结构化设定' },
  // 旧演化页（EvolveView）已废弃并移除（M4：对戏/演绎能力保留为新首页右栏「世界演化面板」+ 对戏抽屉，
  // 时钟/速率/演化控制条在新首页与仪表盘），路由 /evolve 已一并移除（P2 顺手清理，2026-10-15）。
  // { key: 'evolve', domain: 'world', name: '世界演化', icon: 'ic_public_refresh', path: 'evolve', desc: '世界模拟控制台' },
  { key: 'timeline', domain: 'world', name: '时间线', icon: 'ic_public_clock', path: 'timeline', desc: '事件竖轴与行动记录' },
  // V2.0 §4.2 P5（孤立页归位）：/project/:id/init（世界初始化 6 步工作流）**刻意不在此登记**。
  // 它的定位是「项目创建向导的续接流程页」，只在两种情形出现：① 新建项目后自动进入；
  // ② 世界地图页没有世界数据时给出「去初始化」按钮。把它放进常驻导航会让「一次性流程」
  // 与「日常页面」混在一起（这也是审计里「导航项语义混杂」的来源之一）。
  // 路由本身保留并可用（/project/:id/init，见 router/index.js 的 project-init），旧深链不失效。

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
  // V2.0（P1 设置收敛）：设置**不再作为一个"域"铺 5 个同级子项**，而是收敛为 1 个入口；
  // 进入设置区后由设置区自身的导航轨道（SettingsRail，源自 SETTINGS_SUB_ITEMS 的 8 个子页）承载子导航。
  // 收益：① 侧栏不再被设置项占据（浮生 17→13 项）；② 「更新与版本」这类此前只能靠 URL 到达的
  // 页面终于有了正式入口；③ 浮生/对谈两个界面的设置呈现方式一致（都由设置区自己负责）。
  // aliases 让设置区内的子页仍能高亮「设置」这一项（findItemByPath 走路径前缀/别名匹配）。
  { key: 'settings-entry', domain: 'settings', name: '设置', icon: 'ic_public_settings', path: 'settings/general', aliases: ['settings/apis', 'settings/prompts', 'settings/appearance', 'settings/assistant', 'settings/update', 'settings/player-profile'], desc: '通用 / API / Prompt / 用量 / 界面 / 助手 / 玩家档案 / 更新' }
]

/**
 * 抽屉面板清单（**唯一事实来源**）。
 *
 * <p>AppShell 用它构建全部抽屉（`DrawerPanel` + 内容组件），ShellTopbar 用它渲染顶栏「面板」下拉；
 * 两处共用同一份元数据，避免"顶栏列了一组、侧栏列了另一组"的漂移。</p>
 */
export const DRAWER_PANELS = [
  { id: 'pinned', title: '主工作区', icon: 'ic_gallery_map_all', hint: '沙盘与对话', side: 'left', width: 300 },
  { id: 'cast', title: '角色', icon: 'ic_public_contacts', hint: '角色卡 / 人群 / 关系 / 谱系', side: 'left', width: 320 },
  { id: 'mind', title: '记忆与知识', icon: 'ic_public_albums', hint: '项目记忆 / 归档 / 知识库', side: 'left', width: 320 },
  { id: 'world', title: '世界设定', icon: 'ic_public_worldclock', hint: '世界详情 / 时间线', side: 'left', width: 320 },
  { id: 'ai-runtime', title: 'AI 运行状态', icon: 'ic_public_spinner', hint: '推理中 / 最近调用 / 失败', side: 'right', width: 380 },
  { id: 'help', title: '帮助与快捷键', icon: 'ic_public_file', hint: '上手教程与速查', side: 'right', width: 360 }
]

/**
 * 顶部图标抽屉栏入口（酒馆式肌肉记忆的载体）。
 *
 * <p><b>V2.0（P2 顶栏分组 + 去重）</b>：改造前常驻 **10 个无标签图标**，其中
 * ① 3 个是「角色 / 记忆与知识 / 世界设定」域抽屉——与左侧域侧栏**同源重复**（同一功能两个入口）；
 * ② 1 个是「命令面板」放大镜——与顶栏中部搜索框**完全重复**。
 * 现在：删除重复的搜索图标、把 3 个域抽屉合并为 1 个「面板」下拉（4 组面板仍一键可达）→
 * 常驻无标签图标 **10 → 5**，并按 `group` 分 3 组（PatternFly：工具条内完整展示的动作 ≤3；Apple HIG：分组 ≤3）。</p>
 *
 * <p>分组语义：1 = 定位（面板）；2 = 运行观测（AI 状态·通知）；3 = 显示与帮助（密度·主题·帮助）。
 * 密度/主题/帮助三项在 ShellTopbar 中作为第 3 组渲染，故不在此重复声明。</p>
 */
export const TOPBAR_ENTRIES = [
  { key: 'panels', name: '面板', icon: 'ic_public_view_list', kind: 'panels', group: 1, hint: '主工作区 / 角色 / 记忆与知识 / 世界设定（点击展开）' },
  { key: 'ai-runtime', name: 'AI 运行状态', icon: 'ic_public_spinner', kind: 'panel', target: 'ai-runtime', group: 2, hint: '推理中 / 排队 / 今日失败' },
  { key: 'help', name: '帮助与快捷键', icon: 'ic_public_file', kind: 'panel', target: 'help', group: 3, hotkey: '?', hint: '快捷键速查、上手教程与导览' }
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
  const all = [...PINNED_ITEMS, ...NAV_ITEMS, ...DIALOGUE_ITEMS, ...DIALOGUE_SETTINGS_ITEMS]
  const hits = all.filter(i => isItemActive(i, currentPath, projectId))
  if (!hits.length) return null
  // 按「命中路径长度」降序：最具体的条目优先，避免前缀遮蔽更具体的条目
  // （例：/dialogue/player-profile 应高亮「玩家档案」，而非被 /dialogue 前缀的「对谈会话」抢先）
  hits.sort((a, b) => matchPathLen(b, currentPath, projectId) - matchPathLen(a, currentPath, projectId))
  return hits[0]
}

/** 条目命中的最长路径长度（用于 findItemByPath 的优先级排序） */
function matchPathLen(item, currentPath, projectId) {
  const paths = [item.path, ...(item.aliases || [])]
  let max = 0
  for (const p of paths) {
    const full = itemPath(projectId, p)
    if (full !== '#' && currentPath.startsWith(full)) max = Math.max(max, full.length)
  }
  return max
}

/** 取域元信息 */
export function domainOf(key) {
  return DOMAINS.find(d => d.key === key) || null
}

/** 取界面元信息（未知 key 回退到「浮生」） */
export function interfaceInfo(key) {
  return INTERFACES.find(i => i.key === key) || INTERFACES[0]
}

/**
 * 由当前路由路径判定所处界面（浮生 / 对谈）。
 * <p>规则：项目空间内以 /dialogue 开头（含子路径）视为「对谈」界面，其余均为「浮生」。
 * 项目空间外（画廊/设置/教程等）一律视为「浮生」。</p>
 */
export function interfaceOfPath(currentPath, projectId) {
  if (projectId != null && projectId !== '') {
    const prefix = `/project/${projectId}/dialogue`
    if (currentPath === prefix || currentPath.startsWith(prefix + '/')) return 'dialogue'
  }
  return 'fusheng'
}

/** 取某界面的导航项（浮生 = 置顶项 + 五域项；对谈 = 对谈界面项 + 对谈设置项） */
export function itemsOfInterface(interfaceKey) {
  return interfaceKey === 'dialogue'
    ? [...DIALOGUE_ITEMS, ...DIALOGUE_SETTINGS_ITEMS]
    : [...PINNED_ITEMS, ...NAV_ITEMS]
}

/** 取某界面的侧栏分组（浮生 = 五域；对谈 = 对谈分组；对谈分组已含 items 引用） */
export function sectionsOfInterface(interfaceKey) {
  if (interfaceKey === 'dialogue') return DIALOGUE_SECTIONS
  return DOMAINS.map(d => ({
    key: d.key,
    name: d.name,
    icon: d.icon,
    items: NAV_ITEMS.filter(i => i.domain === d.key)
  })).filter(s => s.items.length > 0)
}
