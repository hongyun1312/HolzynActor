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

  TopologyLegend：图例（画布左下角浮动，可收起）。

  与旧实现的区别：图例不再是「给颜色看的装饰」，而是**可直接点击的筛选器**——
  点一个圈子 = 只看这一拨人，点一个关系类型 = 只看这类关系。
  图例里同时如实写出每个圈子的判定依据（组织/血缘/社区检测/职业聚合），
  用户能知道「这个圈是怎么得出来的」，而不是接受一个黑箱分区。
-->
<template>
  <div class="topo-legend" :class="{ open }">
    <button type="button" class="legend-toggle" @click="$emit('action', 'toggle-legend')">
      <el-icon><HIcon name="Reading" /></el-icon>
      <span>图例与筛选</span>
      <span class="legend-caret">{{ open ? '▾' : '▴' }}</span>
    </button>

    <div v-show="open" class="legend-body">
      <!-- 圈子：社会结构的入口 -->
      <section v-if="clusters.length" class="legend-sec">
        <div class="legend-title">圈子（点击只看这一拨人）</div>
        <div class="legend-chips">
          <button
            v-for="c in clusters"
            :key="c.id"
            type="button"
            class="legend-chip"
            :class="{ on: activeClusters.includes(c.id) }"
            :style="{ '--chip': c.color }"
            :title="`${c.label} · ${c.typeLabel} · 判定依据：${c.sourceLabel}${c.size ? ' · ' + c.size + ' 人' : ''}`"
            @click="$emit('action', { name: 'toggle-cluster', id: c.id })"
          >
            <span class="chip-dot" :style="{ background: c.color }"></span>
            <span class="chip-name">{{ c.label }}</span>
            <span class="chip-size">{{ c.size }}</span>
          </button>
        </div>
        <div class="legend-note">圈子的来源写在这里：{{ clusterSourceSummary }}</div>
      </section>

      <!-- 关系类型：颜色 + 条数 -->
      <section v-if="types.length" class="legend-sec">
        <div class="legend-title">关系类型（点击只看这一类）</div>
        <div class="legend-chips">
          <button
            v-for="t in types"
            :key="t.value"
            type="button"
            class="legend-chip"
            :class="{ on: activeTypes.includes(t.value) }"
            @click="$emit('action', { name: 'toggle-type', value: t.value })"
          >
            <span class="chip-dot" :style="{ background: t.color }"></span>
            <span class="chip-name">{{ t.value }}</span>
            <span class="chip-size">{{ t.count }}</span>
          </button>
          <span v-if="moreTypes > 0" class="legend-more">还有 {{ moreTypes }} 种（用「筛选」查看）</span>
        </div>
      </section>

      <!-- 编码说明 -->
      <section class="legend-sec">
        <div class="legend-title">怎么读这张图</div>
        <div class="legend-keys">
          <span class="key"><span class="k-node npc"></span>NPC（实线圆）</span>
          <span class="key"><span class="k-node crowd"></span>普通人群（细线圆）</span>
          <span class="key"><span class="k-node ghost"></span>待补充（虚线圆，仅存在于关系表）</span>
          <span class="key"><span class="k-size"></span>圆越大 = 越重要 / 关系越多</span>
          <span class="key"><span class="k-edge"></span>线越粗 = 互动越强（深仇与挚爱都粗）</span>
          <span class="key"><span class="k-edge faint"></span>线越淡 = 越久没往来</span>
          <span class="key"><span class="k-edge both"></span>双箭头 = 双向关系（已合并为一条线）</span>
        </div>
      </section>

      <section class="legend-sec legend-tips">
        拖动平移 · 滚轮缩放 · 单击选中看详情 · 双击定位 · 右键编辑/删除 · Esc 取消选中
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 图例 + 快捷筛选。
 *
 * <p>受控组件：当前筛选状态由工作台传入，点击只发事件（保证画布、图例、计数同源）。</p>
 */
import { computed } from 'vue'

const props = defineProps({
  /** 圈子（含 label/typeLabel/sourceLabel/size/color） */
  clusters: { type: Array, default: () => [] },
  /** 关系类型 [{value,count,color}]（已按条数降序） */
  types: { type: Array, default: () => [] },
  /** 超过展示上限的类型数 */
  moreTypes: { type: Number, default: 0 },
  /** 已选中的圈子 ID */
  activeClusters: { type: Array, default: () => [] },
  /** 已选中的关系类型 */
  activeTypes: { type: Array, default: () => [] },
  /** 是否展开 */
  open: { type: Boolean, default: true }
})

defineEmits(['action'])

/** 圈子来源摘要（如「3 个来自组织归属、2 个来自社区检测」） */
const clusterSourceSummary = computed(() => {
  const by = new Map()
  for (const c of props.clusters) {
    const k = c.sourceLabel || '未归类'
    by.set(k, (by.get(k) || 0) + 1)
  }
  return [...by.entries()].map(([k, v]) => `${v} 个${k}`).join(' · ') || '—'
})
</script>

<style scoped>
.topo-legend {
  display: flex;
  flex-direction: column;
  gap: 0;
  width: 356px;
  max-width: min(356px, calc(100vw - 48px));
  border-radius: 12px;
  background: color-mix(in srgb, var(--bg-layer-1) 92%, transparent);
  backdrop-filter: blur(10px);
  border: 1px solid var(--border-l2);
  box-shadow: var(--shadow-lv2, 0 4px 16px rgba(15, 17, 21, 0.08));
  overflow: hidden;
  font-size: 0.72rem;
  color: var(--text-secondary);
}
.legend-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  background: transparent;
  color: var(--text-regular);
  font-size: 0.76rem;
  cursor: pointer;
}
.legend-toggle:hover { background: var(--bg-layer-2); }
.legend-caret { margin-left: auto; font-size: 0.62rem; opacity: 0.6; }

.legend-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 2px 10px 10px;
  max-height: min(46vh, 420px);
  overflow-y: auto;
}
.legend-sec { display: flex; flex-direction: column; gap: 6px; }
.legend-title { color: var(--text-tertiary); font-size: 0.7rem; }
.legend-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.legend-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border: 1px solid var(--border-light);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.7rem;
  cursor: pointer;
  max-width: 100%;
}
.legend-chip:hover { border-color: var(--accent); color: var(--accent); }
.legend-chip.on {
  border-color: var(--accent);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.chip-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.chip-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 108px; }
.chip-size { color: var(--text-placeholder); font-size: 0.64rem; }
.legend-more { color: var(--text-placeholder); font-size: 0.68rem; align-self: center; }
.legend-note { color: var(--text-placeholder); font-size: 0.66rem; line-height: 1.6; }

.legend-keys { display: flex; flex-direction: column; gap: 4px; }
.key { display: inline-flex; align-items: center; gap: 6px; font-size: 0.7rem; }
.k-node { width: 13px; height: 13px; border-radius: 50%; flex-shrink: 0; }
.k-node.npc { background: color-mix(in srgb, var(--accent) 45%, var(--bg-layer-1)); border: 2px solid var(--accent); }
.k-node.crowd { background: color-mix(in srgb, #0d9488 45%, var(--bg-layer-1)); border: 1px solid #0f766e; }
.k-node.ghost { background: color-mix(in srgb, var(--text-secondary) 40%, var(--bg-layer-1)); border: 1px dashed var(--text-secondary); }
.k-size { width: 16px; height: 16px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 30%, var(--bg-layer-1)); border: 2px solid var(--accent); flex-shrink: 0; }
.k-edge { width: 22px; height: 0; border-top: 3px solid #8b5cf6; flex-shrink: 0; }
.k-edge.faint { border-top-width: 3px; opacity: 0.35; }
.k-edge.both { border-top-width: 2px; border-top-style: solid; }
.legend-tips { color: var(--text-placeholder); font-size: 0.66rem; line-height: 1.7; }
</style>
