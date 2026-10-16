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
 * 关系工作台视觉语法（UI V3.0 · 纯函数层，可被 Node 直接断言）。
 *
 * <p>职责：把「关系类型 / 关系状态 / 互动强度 / 新鲜度 / 圈子类型」这些<b>真实数据维度</b>
 * 映射为唯一一套视觉编码，画布、图例、详情栏、悬浮卡共用同一份定义——这是「风格统一」的
 * 技术保证：配色只在这里定义一次，任何地方不得再写第二套色值。</p>
 *
 * <p>编码约定（大厂图谱惯例：颜色=性质、粗细=强度、虚实=有无方向、透明度=新鲜度）：</p>
 * <ul>
 *   <li><b>边色</b>：关系类型语义色（亲属/师徒/敌对/朋友/恋人/上下级…共 40+ 语义），
 *       未收录类型走哈希兜底色板（同一类型永远同色，不随数据顺序变化）；</li>
 *   <li><b>边粗</b>：互动强度（好感 + 信任的合成，1.2~5px）；</li>
 *   <li><b>边透明度</b>：新鲜度（最近互动距今越久越淡，0.35~1）；</li>
 *   <li><b>边虚实</b>：单向虚线 / 双向实线（双向关系在数据层已合并成一条）；</li>
 *   <li><b>圈子色</b>：按圈子类型（宗族/祭祀/行会/群落/自发/家族/职业/未归类）给固定色相，
 *       同类型同色，且与关系色区分（圈子是「块」的底色，关系是「线」的颜色）。</li>
 * </ul>
 */

// ==================== 关系类型语义色 ====================
/** 关系类型 → 语义色（内置常见类型；键为中文字面量，与 AI 生成/用户手填的常见写法对齐） */
export const RELATION_PALETTE = {
  亲属: '#e11d48', 家人: '#e11d48', 父女: '#e11d48', 父子: '#e11d48', 母女: '#e11d48', 母子: '#e11d48',
  兄弟: '#e11d48', 姐妹: '#e11d48', 兄妹: '#e11d48', 姐弟: '#e11d48', 叔侄: '#e11d48', 祖孙: '#e11d48',
  师徒: '#8b5cf6', 师父: '#8b5cf6', 弟子: '#8b5cf6', 师兄弟: '#8b5cf6', 同门: '#8b5cf6', 传人: '#8b5cf6',
  敌对: '#b91c1c', 死敌: '#b91c1c', 宿敌: '#b91c1c', 仇敌: '#b91c1c', 仇人: '#b91c1c', 对手: '#dc2626', 竞争: '#dc2626',
  朋友: '#10b981', 挚友: '#10b981', 好友: '#10b981', 知己: '#10b981', 伙伴: '#10b981', 同伴: '#10b981',
  战友: '#10b981', 盟友: '#059669', 同盟: '#059669',
  恋人: '#ec4899', 情侣: '#ec4899', 夫妻: '#ec4899', 爱人: '#ec4899', 未婚妻: '#ec4899', 未婚夫: '#ec4899',
  暗恋: '#f472b6', 爱慕: '#f472b6', 倾慕: '#f472b6',
  上下级: '#0ea5b7', 上司: '#0ea5b7', 下属: '#0ea5b7', 君臣: '#0ea5b7', 主仆: '#0ea5b7', 主人: '#0ea5b7',
  仆从: '#0ea5b7', 雇佣: '#0ea5b7', 领主: '#0ea5b7',
  同事: '#6366f1', 同僚: '#6366f1', 同学: '#6366f1', 同窗: '#6366f1', 同门师兄弟: '#6366f1',
  邻居: '#14b8a6', 同乡: '#14b8a6', 故交: '#14b8a6', 旧识: '#14b8a6', 故友: '#14b8a6', 乡邻: '#14b8a6',
  恩人: '#d97706', 恩师: '#d97706', 救命恩人: '#d97706', 贵人: '#d97706', 债主: '#d97706',
  医患: '#0891b2', 主顾: '#0891b2', 熟客: '#0891b2', 生意: '#0891b2',
  未知关系: '#94a3b8'
}

/** 未收录类型的兜底色板（哈希取模，保证同一类型永远同色） */
export const FALLBACK_PALETTE = ['#0d9488', '#7c3aed', '#f59e0b', '#0891b2', '#db2777', '#65a30d', '#ea580c', '#4f46e5']

/**
 * 关系类型 → 颜色（内置语义色优先，未收录走哈希兜底）。
 *
 * @param {string} type 关系类型
 * @returns {string} 颜色
 */
export function colorForType(type) {
  const t = (type || '').trim()
  if (RELATION_PALETTE[t]) return RELATION_PALETTE[t]
  let h = 0
  for (let i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) >>> 0
  return FALLBACK_PALETTE[h % FALLBACK_PALETTE.length]
}

// ==================== 关系状态 ====================
/** 关系状态键 → 中文标签（与后端 RelationDynamicsService.stateLabel 同源；后端 label 优先） */
export const REL_STATE_LABEL = {
  stranger: '陌生', acquaintance: '相识', friend: '朋友',
  intimate: '亲密', rival: '对手', hostile: '敌对'
}

/** 关系状态 → 状态色（详情栏/徽标用；与边色区分，避免语义打架） */
export const REL_STATE_COLOR = {
  stranger: '#94a3b8', acquaintance: '#0ea5b7', friend: '#10b981',
  intimate: '#ec4899', rival: '#f59e0b', hostile: '#dc2626'
}

/**
 * 关系状态中文标签（后端 label → 本地映射 → 原键 → 破折号）。
 *
 * @param {string} state 状态键
 *
 * @param {string} [serverLabel] 后端给的中文标签（优先）
 *
 * @returns {string} 中文标签
 */
export function relStateLabel(state, serverLabel) {
  if (serverLabel) return serverLabel
  if (!state) return '未演化'
  return REL_STATE_LABEL[state] || state
}

/**
 * 关系状态 → 状态色。
 *
 * @param {string} state 状态键
 *
 * @returns {string} 颜色
 */
export function relStateColor(state) {
  return REL_STATE_COLOR[state] || '#94a3b8'
}

// ==================== 强度（边粗） ====================

/**
 * 互动强度 → 边宽（1.2~5px）。
 *
 * <p>强度 = 「好感绝对值的占比」与「信任的占比」的加权和：好感可正可负（敌对同样"强"），
 * 因此取绝对值——一段 -80 的仇怨在图上必须比 +5 的点头之交更粗，否则「关系强度」就失真了。</p>
 *
 * @param {number} affinity 好感 -100~100
 *
 * @param {number} trust 信任 0~100
 *
 * @returns {number} 线宽（px）
 */
export function edgeWidthOf(affinity, trust) {
  const aff = Math.min(100, Math.abs(Number(affinity) || 0)) / 100
  const tru = Math.min(100, Math.max(0, Number(trust) || 0)) / 100
  const strength = aff * 0.6 + tru * 0.4
  return Number((1.2 + strength * 3.8).toFixed(2))
}

/**
 * 最近互动时间 → 新鲜度（0.35~1）。
 *
 * <p>「关系是活的」在图上要看得见：刚打过交道的关系浓，久未往来的关系淡。
 * 无互动时间（从未演化）取最低档而不是 0——「淡」不等于「不存在」。</p>
 *
 * @param {string|number|Date|null} lastInteractionAt 最近互动时间
 *
 * @param {number} [nowMs] 当前时间（便于测试注入）
 *
 * @returns {number} 透明度系数
 */
export function freshnessOpacity(lastInteractionAt, nowMs) {
  if (!lastInteractionAt) return 0.45
  const t = lastInteractionAt instanceof Date ? lastInteractionAt.getTime() : Date.parse(lastInteractionAt)
  if (Number.isNaN(t)) return 0.45
  const now = nowMs == null ? Date.now() : nowMs
  const days = Math.max(0, (now - t) / 86400000)
  if (days <= 1) return 1
  if (days >= 30) return 0.4
  // 1 天 → 1.0，30 天 → 0.4，线性衰减
  const k = (days - 1) / 29
  return Number((1 - k * 0.6).toFixed(2))
}

/**
 * 双向/单向 → 线型（双向实线，单向虚线）。
 *
 * @param {boolean} bidirectional 是否双向（两端关系都存在）
 *
 * @returns {number[]|undefined} lineDash 数组（实线返回 undefined）
 */
export function edgeDashOf(bidirectional) {
  return bidirectional ? undefined : [5, 3]
}

// ==================== 节点视觉 ====================

/**
 * 几何口径版本号 —— <b>只要节点尺寸公式变了就要 +1</b>。
 *
 * <p>为什么需要：布局位置会按「节点直径 + 间距」预留空间并缓存（localStorage），
 * 尺寸公式改了如果缓存键不变，旧位置会与新尺寸混用，表现为"间距忽松忽紧"，
 * 而且这种不一致只在老用户身上出现、本地开发永远复现不了。把版本号并进布局缓存键，
 * 改尺寸即自动失效旧缓存，无需用户手动清缓存。</p>
 */
export const NODE_GEOMETRY_REV = 2

/**
 * 节点直径（重要度 1~5 → 21.4~31px，主角 +2.5，高连接度最多再 +5）。
 *
 * <p><b>为什么必须连"图有多满"一起考虑</b>：这张图是自适应铺满画布的，屏幕上看到的圆
 * = 世界直径 × 自适应缩放，而缩放又由内容跨度决定。所以世界直径单纯调小，跨度跟着小、
 * 缩放跟着大，屏幕上的圆几乎不变——真正决定"看起来大不大"的是
 * <b>直径 / 内容跨度</b> 这个比值。V3.0 实机反馈"节点偏大"后，除了下修直径，
 * 同时把布局留白 {@code nodeGap} 从 26 提到 34（见 deterministicLayout.computeLayout），
 * 两者的合力才是屏幕上肉眼可见的变小。</p>
 *
 * <p>下修后的档位：21.4 / 23.8 / 26.2 / 28.6 / 31（重要度 1~5），主角再 +2.5，
 * 连接最多的人最多 +5。重要度梯度依然一眼可辨，但同样的画布上节点不再互相压姓名。</p>
 *
 * <p><b>度数加成收进本函数</b>：布局模块要按"节点实际占位"预留间距，如果渲染时再加一层度数加成，
 * 预留与实绘就会错位（高度数的圆会挤到邻居的姓名上）。所以口径只在这里定义一次，
 * {@link deterministicLayout.nodeRadius} 直接复用本函数，杜绝第二套尺寸公式。</p>
 *
 * @param {object} node 归一化节点（含 importance / isProtagonist / kind）
 *
 * @param {number} [degree] 该角色在本次图数据里的连接数（0 = 未知/孤立）
 *
 * @returns {number} 直径（px）
 */
export function nodeSizeOf(node, degree = 0) {
  const deg = Math.max(0, Number(degree) || 0)
  const kind = node?.kind
  if (kind === 'ghost') return 16 + Math.min(4, deg * 0.6)
  const imp = Math.min(5, Math.max(1, Number(node?.importance) || 3))
  const base = 19 + imp * 2.4
  return base + (node?.isProtagonist ? 2.5 : 0) + Math.min(5, deg * 0.8)
}

/** 圈子类型 → 底色（块、外框、图例共用；与关系语义色分属两套色相区间，避免混淆） */
export const CLUSTER_PALETTE = {
  family: '#e11d48',
  cult: '#8b5cf6',
  guild: '#0ea5b7',
  community: '#10b981',
  adhoc: '#f59e0b',
  blood: '#be123c',
  occupation: '#6366f1',
  community_detected: '#14b8a6',
  unknown: '#94a3b8'
}

/**
 * 圈子 → 颜色（按圈子类型给固定色；未知类型走哈希兜底）。
 *
 * @param {object} cluster 圈子（含 type 字段）
 *
 * @returns {string} 颜色
 */
export function colorForCluster(cluster) {
  const t = (cluster?.type || 'unknown').trim()
  if (CLUSTER_PALETTE[t]) return CLUSTER_PALETTE[t]
  let h = 0
  for (let i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) >>> 0
  return FALLBACK_PALETTE[h % FALLBACK_PALETTE.length]
}

/** 圈子类型 → 中文说明（图例与详情栏展示用） */
export const CLUSTER_TYPE_LABEL = {
  family: '宗族',
  cult: '祭祀',
  guild: '行会',
  community: '群落',
  adhoc: '自发聚集',
  blood: '血缘家族',
  occupation: '同业',
  community_detected: '往来圈',
  unknown: '未归类'
}

/**
 * 圈子类型 → 中文说明。
 *
 * @param {string} type 圈子类型
 *
 * @returns {string} 中文
 */
export function clusterTypeLabel(type) {
  return CLUSTER_TYPE_LABEL[type] || CLUSTER_TYPE_LABEL.unknown
}

/** 组内角色 → 中文（势力/组织里的身份，节点徽标与详情栏展示用） */
export const GROUP_ROLE_LABEL = {
  leader: '首领', elder: '长老', core: '核心', member: '成员', affiliate: '外围'
}

/**
 * 组内角色 → 中文。
 *
 * @param {string} role 组内角色键
 *
 * @returns {string} 中文（空值返回空串）
 */
export function groupRoleLabel(role) {
  return role ? (GROUP_ROLE_LABEL[role] || role) : ''
}
