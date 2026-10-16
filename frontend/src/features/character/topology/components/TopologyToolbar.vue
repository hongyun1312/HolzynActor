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

  TopologyToolbar：关系工作台的浮动工具条（画布左上角，不占布局高度）。

  为什么是浮动而不是页面顶部固定栏：图谱工作台的内容就是画布本身，
  顶部再压一条实心栏会永久吃掉 48px 的图面积；浮在画布上、鼠标离开自动变淡，
  是同类产品的通行做法，也让「画布 → 全屏」的过渡没有视觉断层。
-->
<template>
  <div class="topo-bar" :class="{ compact }">
    <!-- ==================== 布局切换 ==================== -->
    <el-dropdown trigger="click" @command="(c) => $emit('update:layout', c)">
      <button type="button" class="bar-btn" title="切换布局（同一份数据的不同观察角度）">
        <el-icon><HIcon name="DataAnalysis" /></el-icon>
        <span class="bar-btn-text">{{ layoutLabel }}</span>
        <span class="bar-caret">▾</span>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="id in layouts" :key="id" :command="id" :class="{ active: id === layout }">
            <span class="dd-name">{{ layoutLabels[id] }}</span>
            <span class="dd-desc">{{ layoutDesc[id] }}</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <span class="bar-sep"></span>

    <!-- ==================== 搜索定位 ==================== -->
    <div class="bar-search" :class="{ focus: searchFocused }">
      <el-icon class="bar-search-icon"><HIcon name="Search" /></el-icon>
      <input
        :value="search"
        class="bar-search-input"
        type="text"
        placeholder="搜索角色 / 职业 / 归属 / 圈子"
        @input="$emit('update:search', $event.target.value)"
        @focus="searchFocused = true"
        @blur="searchFocused = false"
      />
      <button v-if="search" type="button" class="bar-search-clear" title="清空" @click="$emit('update:search', '')">
        <el-icon><HIcon name="Close" /></el-icon>
      </button>
      <span v-if="search" class="bar-search-hit">{{ searchHitCount }} 人</span>
    </div>

    <span class="bar-sep"></span>

    <!-- ==================== 筛选 ==================== -->
    <el-popover placement="bottom-start" :width="348" trigger="click" popper-class="topo-filter-popper">
      <template #reference>
        <button type="button" class="bar-btn" :class="{ on: activeFilterCount > 0 }" title="筛选关系与角色">
          筛选
          <span v-if="activeFilterCount" class="bar-badge">{{ activeFilterCount }}</span>
        </button>
      </template>
      <div class="filter">
        <div class="filter-head">
          <span class="filter-title">筛选</span>
          <el-button link type="primary" size="small" @click="$emit('action', 'reset-filters')">全部重置</el-button>
        </div>

        <div v-if="egoCenterName" class="filter-sec">
          <div class="filter-label">以「{{ egoCenterName }}」为中心</div>
          <el-radio-group :model-value="filters.egoRadius" size="small"
            @update:model-value="(v) => $emit('patch-filters', { egoRadius: v })">
            <el-radio-button :value="0">不限</el-radio-button>
            <el-radio-button :value="1">1 跳</el-radio-button>
            <el-radio-button :value="2">2 跳</el-radio-button>
            <el-radio-button :value="3">3 跳</el-radio-button>
          </el-radio-group>
        </div>

        <div class="filter-sec">
          <div class="filter-label">关系类型<span v-if="filters.types.length" class="filter-count">{{ filters.types.length }}</span></div>
          <el-select :model-value="filters.types" multiple collapse-tags collapse-tags-tooltip size="small"
            placeholder="全部类型" class="filter-wide"
            @update:model-value="(v) => $emit('patch-filters', { types: v })">
            <el-option v-for="t in typeOptions" :key="t.value" :label="`${t.value}（${t.count}）`" :value="t.value">
              <span class="opt-dot" :style="{ background: t.color }"></span>{{ t.value }}（{{ t.count }}）
            </el-option>
          </el-select>
        </div>

        <div class="filter-sec">
          <div class="filter-label">关系状态</div>
          <el-select :model-value="filters.states" multiple collapse-tags size="small"
            placeholder="全部状态" class="filter-wide"
            @update:model-value="(v) => $emit('patch-filters', { states: v })">
            <el-option v-for="s in stateOptions" :key="s.value" :label="`${s.label}（${s.count}）`" :value="s.value" />
          </el-select>
        </div>

        <div class="filter-sec">
          <div class="filter-label">互动强度下限<span class="filter-value">{{ Math.round(filters.minStrength * 100) }}%</span></div>
          <el-slider :model-value="filters.minStrength" :min="0" :max="1" :step="0.05" size="small"
            @update:model-value="(v) => $emit('patch-filters', { minStrength: v })" />
          <div class="filter-hint">强度 = |好感| × 0.6 + 信任 × 0.4 —— 深仇与挚友同样「强」</div>
        </div>

        <div class="filter-sec">
          <div class="filter-label">角色类型</div>
          <el-checkbox-group :model-value="filters.kinds" size="small"
            @update:model-value="(v) => $emit('patch-filters', { kinds: v })">
            <el-checkbox value="npc">NPC</el-checkbox>
            <el-checkbox value="crowd">普通人群</el-checkbox>
            <el-checkbox value="ghost">待补充</el-checkbox>
          </el-checkbox-group>
        </div>

        <div v-if="clusterOptions.length" class="filter-sec">
          <div class="filter-label">圈子<span v-if="filters.clusterIds.length" class="filter-count">{{ filters.clusterIds.length }}</span></div>
          <div class="filter-clusters">
            <button
              v-for="c in clusterOptions"
              :key="c.id"
              type="button"
              class="cluster-chip"
              :class="{ on: filters.clusterIds.includes(c.id) }"
              :style="filters.clusterIds.includes(c.id) ? { borderColor: c.color, color: c.color, background: `color-mix(in srgb, ${c.color} 10%, transparent)` } : {}"
              @click="$emit('action', { name: 'toggle-cluster', id: c.id })"
            >
              <span class="dot" :style="{ background: c.color }"></span>{{ c.label }}（{{ c.size }}）
            </button>
          </div>
        </div>
      </div>
    </el-popover>

    <!-- ==================== 显示选项 ==================== -->
    <el-popover placement="bottom-start" :width="286" trigger="click">
      <template #reference>
        <button type="button" class="bar-btn" title="显示选项">显示</button>
      </template>
      <div class="filter">
        <div class="filter-head"><span class="filter-title">显示选项</span></div>
        <div class="switch-row">
          <span>圈子外框</span>
          <el-switch :model-value="options.showHulls" size="small"
            @update:model-value="(v) => $emit('patch-options', { showHulls: v })" />
        </div>
        <div class="switch-row">
          <span>小地图</span>
          <el-switch :model-value="options.showMinimap" size="small"
            @update:model-value="(v) => $emit('patch-options', { showMinimap: v })" />
        </div>
        <div class="switch-row">
          <span>点阵底纹</span>
          <el-switch :model-value="options.showGrid" size="small"
            @update:model-value="(v) => $emit('patch-options', { showGrid: v })" />
        </div>
        <div class="switch-row">
          <span>自由拖动节点</span>
          <el-switch :model-value="options.draggable" size="small"
            @update:model-value="(v) => $emit('patch-options', { draggable: v })" />
        </div>
        <div class="filter-sec">
          <div class="filter-label">关系文字</div>
          <el-radio-group :model-value="options.showEdgeLabels" size="small"
            @update:model-value="(v) => $emit('patch-options', { showEdgeLabels: v })">
            <el-radio-button value="smart">智能</el-radio-button>
            <el-radio-button value="all">全显</el-radio-button>
            <el-radio-button value="selected">选中时</el-radio-button>
            <el-radio-button value="none">不显</el-radio-button>
          </el-radio-group>
          <div class="filter-hint">智能档：边不多时显示关系名，边多时只显示选中/悬停的那条</div>
        </div>
      </div>
    </el-popover>

    <!-- ==================== 图例 ==================== -->
    <button type="button" class="bar-btn" :class="{ on: legendOpen }" title="图例（点击项可直接筛选）"
      @click="$emit('action', 'toggle-legend')">
      <el-icon><HIcon name="Reading" /></el-icon>
      <span class="bar-btn-text">图例</span>
    </button>

    <span class="bar-sep"></span>

    <!-- ==================== 视图操作 ==================== -->
    <button type="button" class="bar-btn icon-only" title="自适应视野（回到全图）" @click="$emit('action', 'fit')">
      <el-icon><HIcon name="Refresh" /></el-icon>
    </button>
    <button type="button" class="bar-btn icon-only" title="导出 PNG 图片" @click="$emit('action', 'export')">
      <el-icon><HIcon name="Download" /></el-icon>
    </button>
    <button type="button" class="bar-btn icon-only" :title="fullscreen ? '退出全屏' : '全屏'" @click="$emit('action', 'fullscreen')">
      <span class="fs-glyph" :class="{ on: fullscreen }">{{ fullscreen ? '⤡' : '⤢' }}</span>
    </button>
    <button type="button" class="bar-btn icon-only" title="关系管理（手工增删改 / AI 精修）" @click="$emit('action', 'manage')">
      <el-icon><HIcon name="Setting" /></el-icon>
    </button>

    <slot name="extra"></slot>

    <!-- ==================== 实时 ==================== -->
    <span class="bar-sep"></span>
    <button type="button" class="bar-btn" :class="{ on: options.realtime }"
      :title="options.realtime ? '实时开关：已开启（世界引擎的关系变迁会即时画到图上）' : '实时开关：已关闭'"
      @click="$emit('patch-options', { realtime: !options.realtime })">
      <el-icon><HIcon name="Connection" /></el-icon>
      <span class="bar-btn-text">实时</span>
      <span class="bar-live" :class="liveState"></span>
    </button>

    <span class="bar-sep"></span>

    <!-- ==================== AI 生成 ==================== -->
    <el-button class="bar-primary" size="small" type="primary" :loading="generating" @click="$emit('action', 'generate')">
      <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;{{ generateText }}
    </el-button>
  </div>
</template>

<script setup>
/**
 * 浮动工具条。
 *
 * <p>本组件只表达「用户想干什么」，所有状态都由工作台持有（受控组件）：
 * 布局、搜索词、筛选条件、显示选项、实时开关一律 props 进、emit 出，
 * 这样刷新/切页/打开分享链接时状态可被完整还原与持久化。</p>
 */
import { ref, computed } from 'vue'
import { LAYOUT_IDS, LAYOUT_LABEL } from '../lib/deterministicLayout.js'

const props = defineProps({
  /** 当前布局 ID */
  layout: { type: String, default: 'community' },
  /** 搜索词 */
  search: { type: String, default: '' },
  /** 搜索命中人数 */
  searchHitCount: { type: Number, default: 0 },
  /** 筛选条件（defaultFilters 形状） */
  filters: { type: Object, required: true },
  /** 可选关系类型 [{value,count,color}] */
  typeOptions: { type: Array, default: () => [] },
  /** 可选关系状态 [{value,label,count}] */
  stateOptions: { type: Array, default: () => [] },
  /** 可选圈子 [{id,label,color,size}] */
  clusterOptions: { type: Array, default: () => [] },
  /** 显示选项 */
  options: { type: Object, required: true },
  /** 实时连接状态：on | off | connecting */
  liveState: { type: String, default: 'off' },
  /** ego 中心角色名（有值时展示半径切换） */
  egoCenterName: { type: String, default: '' },
  /** 图例是否展开 */
  legendOpen: { type: Boolean, default: false },
  /** 是否全屏中 */
  fullscreen: { type: Boolean, default: false },
  /** AI 生成按钮文案 */
  generateText: { type: String, default: 'AI 识别关系' },
  /** 生成中 */
  generating: { type: Boolean, default: false },
  /** 紧凑（内嵌变体下隐藏次要操作） */
  compact: { type: Boolean, default: false }
})

defineEmits(['update:layout', 'update:search', 'patch-filters', 'patch-options', 'action'])

const searchFocused = ref(false)

/** 布局下拉项 */
const layouts = LAYOUT_IDS
const layoutLabels = LAYOUT_LABEL

/** 布局说明（一句话讲清"这个布局看什么"；后两个不画圈子外框，说明里讲明原因） */
const layoutDesc = {
  community: '按圈子分块，先看分成哪几拨人',
  force: '按亲疏拉扯，看整体关系结构',
  concentric: '按重要度同心，看关注度分布（不画圈子外框）',
  radial: '以度数最高者为中心按跳数分层（不画圈子外框）',
  lanes: '圈子横向泳道，关系密集时更好读'
}

const layoutLabel = computed(() => layoutLabels[props.layout] || layoutLabels.community)

/** 已生效的筛选条数（不含 ego，ego 在详情栏体现） */
const activeFilterCount = computed(() => {
  const f = props.filters || {}
  let n = 0
  if (f.types && f.types.length) n += f.types.length
  if (f.states && f.states.length) n += f.states.length
  if (f.kinds && f.kinds.length) n += f.kinds.length
  if (f.clusterIds && f.clusterIds.length) n += f.clusterIds.length
  if (f.minStrength > 0) n += 1
  if (f.egoCenter) n += 1
  return n
})
</script>

<style scoped>
.topo-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border-radius: 12px;
  background: color-mix(in srgb, var(--bg-layer-1) 90%, transparent);
  backdrop-filter: blur(10px);
  border: 1px solid var(--border-l2);
  box-shadow: var(--shadow-lv2, 0 4px 16px rgba(15, 17, 21, 0.08));
  font-size: 0.76rem;
  color: var(--text-regular);
  max-width: 100%;
  flex-wrap: wrap;
}
.bar-sep { width: 1px; height: 18px; background: var(--border-light); margin: 0 3px; flex-shrink: 0; }

.bar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--text-regular);
  font-size: 0.76rem;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.bar-btn:hover { background: var(--bg-layer-2); color: var(--text-primary); }
.bar-btn.on { background: color-mix(in srgb, var(--accent) 12%, transparent); color: var(--accent); border-color: color-mix(in srgb, var(--accent) 30%, transparent); }
.bar-btn.icon-only { padding: 0 7px; }
.bar-btn-text { max-width: 92px; overflow: hidden; text-overflow: ellipsis; }
.bar-caret { font-size: 0.62rem; opacity: 0.6; }
.bar-badge {
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 0.62rem;
  line-height: 15px;
  text-align: center;
}
.fs-glyph { font-size: 0.95rem; line-height: 1; }

/* 搜索 */
.bar-search {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-layer-1);
  min-width: 190px;
}
.bar-search.focus { border-color: var(--accent); box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 18%, transparent); }
.bar-search-icon { color: var(--text-placeholder); }
.bar-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-primary);
  font-size: 0.76rem;
}
.bar-search-clear { border: none; background: transparent; color: var(--text-placeholder); cursor: pointer; padding: 0; display: inline-flex; }
.bar-search-clear:hover { color: var(--text-secondary); }
.bar-search-hit { color: var(--accent); font-size: 0.68rem; white-space: nowrap; }

/* 实时指示灯 */
.bar-live { width: 6px; height: 6px; border-radius: 50%; background: var(--text-placeholder); }
.bar-live.on { background: #10b981; box-shadow: 0 0 0 3px color-mix(in srgb, #10b981 22%, transparent); }
.bar-live.connecting { background: #f59e0b; animation: bar-pulse 1.2s infinite; }
@keyframes bar-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

.bar-primary { flex-shrink: 0; }

/* 筛选面板 */
.filter { display: flex; flex-direction: column; gap: 12px; font-size: 0.78rem; }
.filter-head { display: flex; align-items: center; justify-content: space-between; }
.filter-title { font-weight: 600; color: var(--text-primary); }
.filter-sec { display: flex; flex-direction: column; gap: 6px; }
.filter-label { display: flex; align-items: center; gap: 6px; color: var(--text-secondary); font-size: 0.74rem; }
.filter-count {
  background: var(--accent);
  color: #fff;
  border-radius: 999px;
  font-size: 0.62rem;
  padding: 0 5px;
  line-height: 14px;
}
.filter-value { margin-left: auto; color: var(--accent); font-variant-numeric: tabular-nums; }
.filter-hint { font-size: 0.68rem; color: var(--text-placeholder); line-height: 1.6; }
.filter-wide { width: 100%; }
.filter-clusters { display: flex; flex-wrap: wrap; gap: 4px; max-height: 132px; overflow-y: auto; }
.cluster-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--border-light);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.7rem;
  cursor: pointer;
}
.cluster-chip:hover { border-color: var(--accent); color: var(--accent); }
.switch-row { display: flex; align-items: center; justify-content: space-between; font-size: 0.78rem; color: var(--text-regular); }
.opt-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin-right: 6px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dd-name { display: inline-block; min-width: 76px; }
.dd-desc { color: var(--text-tertiary); font-size: 0.7rem; }
</style>
