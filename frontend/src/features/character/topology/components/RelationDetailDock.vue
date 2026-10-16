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

  RelationDetailDock：关系工作台右侧可收起的详情栏。

  「大厂图谱」的通用骨架是：主画布负责结构与导航，点开一个人之后，
  详情与操作都在侧栏完成（不弹窗、不跳页、不打断当前观察）。
  这里承载三件事：
    ① 这个人的档案（圈子/家族/归属/职业/地点/重要度/度数）——并如实标注圈子是怎么判出来的；
    ② 这个人的关系清单（方向、关系类型、好感/信任、互动新鲜度，可点可编辑可 AI 精修）；
    ③ 未选中任何人时的「影响力排行」——让侧栏永远有用，而不是一片空白。
-->
<template>
  <aside class="rel-dock" :class="{ collapsed }">
    <!-- ==================== 收起态：竖条（点击展开） ==================== -->
    <button v-if="collapsed" type="button" class="dock-rail" title="展开详情" @click="$emit('toggle', false)">
      <el-icon><HIcon name="ArrowLeft" /></el-icon>
      <span class="rail-text">详情</span>
    </button>

    <template v-else>
      <!-- ==================== 已选中某人 ==================== -->
      <template v-if="node">
        <header class="dock-head">
          <span class="dock-avatar" :style="{ background: avatarBg }">{{ initial }}</span>
          <div class="dock-title">
            <div class="dock-name">
              {{ node.name || '未命名' }}
              <el-tag v-if="node.isProtagonist" size="small" type="danger" effect="dark">主角</el-tag>
              <el-tag v-else-if="node.kind === 'crowd'" size="small" type="info" effect="plain">普通人群</el-tag>
              <el-tag v-else-if="node.kind === 'ghost'" size="small" type="warning" effect="plain">待补充</el-tag>
            </div>
            <div class="dock-sub">
              <span v-if="cluster" class="dock-cluster">
                <span class="dot" :style="{ background: clusterColor }"></span>{{ cluster.label }}
              </span>
              <span v-else class="dock-cluster muted">未归属任何圈子</span>
              <span class="dock-degree">{{ relationCount }} 条关系</span>
            </div>
          </div>
          <button type="button" class="dock-icon-btn" title="收起详情" @click="$emit('toggle', true)">
            <el-icon><HIcon name="ArrowRight" /></el-icon>
          </button>
        </header>

        <!-- 幽灵角色：不编造信息，直接给出补齐入口 -->
        <div v-if="node.kind === 'ghost'" class="dock-body">
          <div class="dock-note">
            关系表引用了「{{ node.name }}」，但角色档案与普通人群里都没有这个名字的数据。
            补齐资料后，后端会按姓名把它和现有关系自动关联起来。
          </div>
          <el-button type="primary" size="small" @click="$emit('supplement', node.name)">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;前往补充该角色
          </el-button>
        </div>

        <div v-else class="dock-body">
          <!-- 档案 -->
          <section class="dock-sec">
            <div class="sec-head">
              <span class="sec-title">档案</span>
              <button type="button" class="sec-act" title="在图中定位" @click="$emit('locate', node.id)">
                <el-icon><HIcon name="Aim" /></el-icon>&nbsp;定位
              </button>
            </div>
            <div class="meta-grid">
              <div v-for="m in metaRows" :key="m.k" class="meta-row">
                <span class="meta-k">{{ m.k }}</span>
                <span class="meta-v" :title="m.title || m.v">{{ m.v || '—' }}</span>
              </div>
            </div>
            <div v-if="node.detail" class="dock-detail">{{ node.detail }}</div>
            <div v-if="cluster" class="dock-source">
              圈子判定依据：{{ sourceLabel }}<span v-if="cluster.size"> · 共 {{ cluster.size }} 人</span>
            </div>
          </section>

          <!-- 关系清单 -->
          <section class="dock-sec dock-relations">
            <div class="sec-head">
              <span class="sec-title">关系清单（{{ rows.length }}）</span>
              <button type="button" class="sec-act" @click="$emit('add-relation', node.id)">
                <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增
              </button>
            </div>
            <div v-if="!rows.length" class="rel-empty">
              这个人目前没有任何已登记的关系。可以用「新增」手工登记，或让 AI 依据世界观识别。
            </div>
            <ul v-else class="rel-list">
              <li
                v-for="r in rows"
                :key="r.edgeId"
                class="rel-item"
                :class="{ active: r.edgeId === selectedEdgeId }"
                @mouseenter="$emit('hover-relation', r.edgeId)"
                @mouseleave="$emit('hover-relation', null)"
                @click="$emit('pick-relation', r)"
              >
                <span class="rel-dir" :title="dirTitle(r)">{{ dirGlyph(r) }}</span>
                <span class="rel-other">
                  <span class="dot" :style="{ background: r.otherColor }"></span>
                  <span class="rel-other-name" :class="{ ghost: r.otherKind === 'ghost' }">{{ r.otherName }}</span>
                </span>
                <span class="rel-types">
                  <span v-for="t in r.types" :key="t" class="type-chip" :style="{ borderColor: colorForType(t), color: colorForType(t) }">{{ t }}</span>
                </span>
                <span class="rel-nums">
                  <span class="rel-aff" :class="affinityTone(r.affinity)">{{ formatAffinity(r.affinity) }}</span>
                  <span class="rel-trust" :title="`信任 ${r.trust}`">{{ r.trust }}</span>
                </span>
                <span class="rel-bar" :title="`互动强度 ${formatStrength(r.weight)}`">
                  <span class="rel-bar-fill" :style="{ width: formatStrength(r.weight), background: colorForType(r.types[0]) }"></span>
                </span>
                <span class="rel-fresh">{{ freshnessText(r.lastInteractionAt) }}</span>
                <span class="rel-acts" @click.stop>
                  <button type="button" class="rel-act" title="编辑该关系" @click="$emit('edit', r)"><el-icon><HIcon name="Edit" /></el-icon></button>
                  <button type="button" class="rel-act" title="AI 精修（按最近互动重算好感/信任/状态）"
                    :disabled="refiningId === mainItemId(r)" @click="$emit('refine', r)">
                    <el-icon><HIcon :name="refiningId === mainItemId(r) ? 'Loading' : 'MagicStick'" /></el-icon>
                  </button>
                  <button type="button" class="rel-act danger" title="删除该关系" @click="$emit('remove', r)"><el-icon><HIcon name="Delete" /></el-icon></button>
                </span>
                <span v-if="r.items.length > 1" class="rel-badge" title="双向关系：两个方向各有一条记录，点开可分别查看">{{ r.items.length }} 条</span>
              </li>
            </ul>
          </section>
        </div>
      </template>

      <!-- ==================== 未选中：影响力排行（侧栏不留白） ==================== -->
      <template v-else>
        <header class="dock-head">
          <span class="dock-avatar ghost-avatar"><el-icon><HIcon name="DataAnalysis" /></el-icon></span>
          <div class="dock-title">
            <div class="dock-name">影响力排行</div>
            <div class="dock-sub">按 PageRank 排序 · 点击可在图中定位</div>
          </div>
          <button type="button" class="dock-icon-btn" title="收起详情" @click="$emit('toggle', true)">
            <el-icon><HIcon name="ArrowRight" /></el-icon>
          </button>
        </header>
        <div class="dock-body">
          <!-- 未选中时的说明区（由工作台注入：例如「还没有任何关系」时解释图为什么长这样） -->
          <slot name="notice"></slot>
          <div v-if="!ranking.length" class="rel-empty">这个世界还没有角色数据。</div>
          <ul v-else class="rank-list">
            <li v-for="(r, i) in ranking" :key="r.id" class="rank-item" @click="$emit('pick-node', r.id)">
              <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
              <span class="dot" :style="{ background: r.color }"></span>
              <span class="rank-name">{{ r.name }}</span>
              <span class="rank-cluster">{{ r.clusterLabel || '未归属' }}</span>
              <span class="rank-degree" :title="`度数 ${r.degree}`">{{ r.degree }}</span>
            </li>
          </ul>
          <div class="dock-hint">
            画布上看结构，这里看人：点上任意一个角色，侧栏会换成他的档案与关系清单。
          </div>
        </div>
      </template>
    </template>
  </aside>
</template>

<script setup>
/**
 * 关系详情栏。
 *
 * <p>纯展示 + 事件上抛：所有数据（选中的人、他的关系行、影响力排行）都由工作台算好传进来，
 * 本组件不取数据、不改数据，只负责「让用户看清楚并点到想点的地方」。</p>
 */
import { computed } from 'vue'
import { colorForType, colorForCluster, relStateLabel, groupRoleLabel } from '../lib/relationPalette.js'
import { formatAffinity, affinityTone, freshnessText, formatStrength } from '../lib/relationFormat.js'

const props = defineProps({
  /** 选中角色（normalizeGraph 形状；null=未选中） */
  node: { type: Object, default: null },
  /** 该角色所属圈子 */
  cluster: { type: Object, default: null },
  /** 圈子判定依据文案（如实告知「这个圈怎么来的」） */
  sourceLabel: { type: String, default: '' },
  /** 关系行（relationRowsOf 输出） */
  rows: { type: Array, default: () => [] },
  /** 影响力排行 [{id,name,color,clusterLabel,degree}] */
  ranking: { type: Array, default: () => [] },
  /** 当前选中的边 ID */
  selectedEdgeId: { type: String, default: '' },
  /** AI 精修中的关系 id */
  refiningId: { type: [Number, String], default: null },
  /** 是否收起 */
  collapsed: { type: Boolean, default: false },
  /** 关系总数（头部展示「N 条关系」用；rows 受 ego 半径影响会变少） */
  relationCount: { type: Number, default: 0 }
})

defineEmits([
  'toggle', 'locate', 'supplement', 'add-relation', 'edit', 'remove', 'refine',
  'pick-relation', 'hover-relation', 'pick-node'
])

/** 头像首字（姓名首字；空名兜底问号） */
const initial = computed(() => {
  const n = String(props.node?.name || '').trim()
  return n ? n.slice(0, 1) : '?'
})

/** 头像底色：所属圈子色（与画布节点同源，做到「侧栏里的人」和「图上的点」一眼对上） */
const avatarBg = computed(() => {
  const c = props.cluster ? colorForCluster(props.cluster) : null
  return c ? `linear-gradient(135deg, ${c} 0%, color-mix(in srgb, ${c} 70%, #4b5563) 100%)` : 'var(--brand-gradient)'
})

const clusterColor = computed(() => (props.cluster ? colorForCluster(props.cluster) : 'var(--text-placeholder)'))

/** 档案字段（只展示真实存在的字段，不拿占位符凑数） */
const metaRows = computed(() => {
  const n = props.node
  if (!n) return []
  const meta = n.meta || {}
  const rows = []
  if (meta.familyName) rows.push({ k: '家族', v: meta.familyName })
  if (meta.groupName) rows.push({ k: '归属', v: meta.groupName })
  if (meta.groupRole) rows.push({ k: '身份', v: groupRoleLabel(meta.groupRole) })
  if (meta.occupation) rows.push({ k: '职业', v: meta.occupation })
  if (n.title) rows.push({ k: '头衔', v: n.title })
  if (meta.location) rows.push({ k: '所在', v: meta.location })
  if (meta.stage) rows.push({ k: '人生阶段', v: meta.stage })
  if (meta.age != null) rows.push({ k: '年龄', v: `${meta.age}` })
  if (n.importance != null) rows.push({ k: '重要度', v: '★'.repeat(Math.max(0, Math.min(5, Number(n.importance) || 0))) })
  return rows
})

/** 方向图标：双向=往来，单向=指向 */
function dirGlyph(r) {
  if (r.direction === 'both') return '⇄'
  return r.direction === 'out' ? '→' : '←'
}

/** 方向说明 */
function dirTitle(r) {
  if (r.direction === 'both') return '双向关系（已合并）'
  return r.direction === 'out' ? '我对他' : '他对我'
}

/** 关系行对应的主记录 id（编辑/精修/删除都针对主记录） */
function mainItemId(r) {
  const main = r.items && r.items.length ? r.items[0] : null
  return main ? main.id : null
}

/** 状态文案（详情栏备用；关系行本身用数字表达） */
function stateText(r) {
  return relStateLabel(r.relationState, r.relationStateLabel)
}
</script>

<style scoped>
.rel-dock {
  display: flex;
  flex-direction: column;
  width: 336px;
  flex-shrink: 0;
  min-height: 0;
  height: 100%;
  background: var(--bg-layer-1);
  border-left: 1px solid var(--border-light);
  overflow: hidden;
}
.rel-dock.collapsed { width: 40px; }

/* 收起态竖条 */
.dock-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 100%;
  padding: 12px 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.dock-rail:hover { background: var(--bg-layer-2); color: var(--accent); }
.rail-text {
  writing-mode: vertical-rl;
  font-size: 0.72rem;
  letter-spacing: 2px;
}

/* 头部 */
.dock-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 12px 10px;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}
.dock-avatar {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 1.15rem;
  font-weight: 700;
  flex-shrink: 0;
}
.ghost-avatar { background: var(--bg-layer-2); color: var(--text-tertiary); }
.dock-title { flex: 1; min-width: 0; }
.dock-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.98rem;
  font-weight: 700;
  color: var(--text-primary);
}
.dock-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 2px;
  font-size: 0.72rem;
  color: var(--text-tertiary);
}
.dock-cluster { display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.dock-cluster.muted { color: var(--text-placeholder); }
.dock-degree { margin-left: auto; }
.dock-icon-btn {
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  flex-shrink: 0;
}
.dock-icon-btn:hover { background: var(--bg-layer-2); color: var(--accent); }

/* 主体滚动区 */
.dock-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dock-sec { display: flex; flex-direction: column; gap: 8px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.sec-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); }
.sec-act {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 0.72rem;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
}
.sec-act:hover { background: var(--bg-layer-2); }

/* 档案 */
.meta-grid { display: flex; flex-direction: column; gap: 4px; }
.meta-row { display: flex; gap: 8px; font-size: 0.76rem; align-items: baseline; }
.meta-k { color: var(--text-tertiary); width: 56px; flex-shrink: 0; }
.meta-v { color: var(--text-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dock-detail {
  margin-top: 4px;
  padding: 8px 10px;
  background: var(--bg-layer-2);
  border-radius: 8px;
  font-size: 0.74rem;
  line-height: 1.7;
  color: var(--text-regular);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 150px;
  overflow-y: auto;
}
.dock-source { font-size: 0.7rem; color: var(--text-placeholder); }
.dock-note {
  padding: 10px;
  background: var(--bg-layer-2);
  border-radius: 8px;
  font-size: 0.75rem;
  line-height: 1.7;
  color: var(--text-secondary);
}

/* 关系清单 */
.rel-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.rel-empty {
  padding: 10px;
  font-size: 0.74rem;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-layer-2);
  border-radius: 8px;
}
.rel-item {
  display: grid;
  grid-template-columns: 16px minmax(58px, auto) 1fr auto 34px auto;
  grid-template-areas:
    'dir other types nums bar fresh'
    'dir other types nums bar acts';
  align-items: center;
  gap: 4px 6px;
  padding: 6px 6px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  position: relative;
  font-size: 0.74rem;
}
.rel-item:hover { background: var(--bg-layer-2); }
.rel-item.active { border-color: var(--accent); background: color-mix(in srgb, var(--accent) 8%, var(--bg-layer-1)); }
.rel-dir { grid-area: dir; color: var(--text-placeholder); text-align: center; }
.rel-other { grid-area: other; display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.rel-other-name { color: var(--text-primary); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rel-other-name.ghost { color: var(--text-placeholder); font-weight: 400; text-decoration: underline dotted; }
.rel-types { grid-area: types; display: inline-flex; gap: 3px; flex-wrap: wrap; min-width: 0; }
.type-chip {
  border: 1px solid;
  border-radius: 999px;
  padding: 0 5px;
  font-size: 0.65rem;
  line-height: 15px;
  white-space: nowrap;
  opacity: 0.9;
}
.rel-nums { grid-area: nums; display: inline-flex; gap: 4px; align-items: baseline; font-variant-numeric: tabular-nums; }
.rel-aff { font-weight: 600; }
.rel-aff.pos { color: #10b981; }
.rel-aff.neg { color: #ef4444; }
.rel-trust { color: var(--text-tertiary); font-size: 0.68rem; }
.rel-bar {
  grid-area: bar;
  display: block;
  height: 4px;
  border-radius: 2px;
  background: var(--bg-layer-3, var(--bg-layer-2));
  overflow: hidden;
}
.rel-bar-fill { display: block; height: 100%; border-radius: 2px; }
.rel-fresh { grid-area: fresh; color: var(--text-placeholder); font-size: 0.66rem; white-space: nowrap; }
.rel-acts { grid-area: acts; display: none; gap: 1px; justify-content: flex-end; }
.rel-item:hover .rel-acts { display: inline-flex; }
.rel-item:hover .rel-fresh { display: none; }
.rel-act {
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 1px 3px;
  border-radius: 4px;
  font-size: 0.72rem;
}
.rel-act:hover { background: var(--bg-layer-3, var(--bg-layer-2)); color: var(--accent); }
.rel-act.danger:hover { color: #ef4444; }
.rel-badge {
  position: absolute;
  left: 0;
  top: -4px;
  font-size: 0.6rem;
  color: var(--text-placeholder);
}

/* 影响力排行 */
.rank-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.rank-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 6px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.78rem;
}
.rank-item:hover { background: var(--bg-layer-2); }
.rank-no {
  width: 18px;
  text-align: center;
  color: var(--text-placeholder);
  font-size: 0.7rem;
  font-variant-numeric: tabular-nums;
}
.rank-no.top { color: var(--accent); font-weight: 700; }
.rank-name { color: var(--text-primary); font-weight: 600; }
.rank-cluster { color: var(--text-tertiary); font-size: 0.7rem; margin-left: auto; }
.rank-degree {
  color: var(--text-secondary);
  font-size: 0.7rem;
  min-width: 20px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.dock-hint { font-size: 0.72rem; color: var(--text-placeholder); line-height: 1.7; padding: 0 2px; }

.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
</style>
