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

  角色关系工作台（UI V3.0 重做）。

  与重做前的根本差别（不是换皮，是换骨架）：
    ① 之前：G6 力导向现场算位置 → 同一世界每次进来节点位置都变，用户找不到刚才那个人；
       现在：deterministicLayout 用固定种子算位置并缓存 → 同一份数据永远同一张图，布局切换有位移动画。
    ② 之前：关系为 0 时满屏孤立散点，看上去像坏了；
       现在：先按世界内核真实归属（中观组织/血缘）分圈，图算法只在确实有关系时补充 →
       即使 0 条关系也能看出「这个世界分成哪几拨人」。
    ③ 之前：一圈图例 + 一圈工具栏横在画布上方，画布被挤成一条；
       现在：画布铺满，工具条/图例/状态条浮在画布上，右侧详情栏可收起。
    ④ 之前：点节点弹右上角小卡片，信息与操作都塞不下；
       现在：右侧详情栏承载档案 + 关系清单（可点、可编辑、可 AI 精修、可跳转到对方）。
    ⑤ 之前：全靠手点；现在：搜索定位、图例即筛选、点击筛选、ego 半径、影响力排行、键盘导航、
       小地图、LOD、PNG 导出、SSE 实时关系变迁。

  兼容性：组件对外接口（props/emits/defineExpose）保持不变 —— 角色页 Tab 与全局拓扑页
  两种用法（variant=tab/page）无需改动。
-->
<template>
  <div
    ref="rootRef"
    class="relation-topology"
    :class="[variant, { fullscreen }]"
    tabindex="0"
    @keydown="onKeydown"
  >
    <!-- 空态①：项目无任何角色/普通人群 -->
    <div v-if="state === 'no-characters'" class="topo-empty">
      <EmptyState
        size="page"
        icon="ic_public_contacts"
        title="这个项目还没有角色"
        desc="关系工作台至少需要一个角色才能画图。创建 NPC，或让 AI 依据世界观批量生成角色后回到本页。"
      />
    </div>

    <!-- 加载中 -->
    <div v-else-if="state === 'loading'" v-loading="true" class="topo-loading" />

    <!-- 加载失败（含重试） -->
    <div v-else-if="state === 'error'" class="topo-empty">
      <EmptyState
        size="page"
        tone="warn"
        icon="ic_public_ring"
        title="关系数据加载失败"
        :desc="errorMsg"
        primary-text="重试"
        @primary="load"
      />
    </div>

    <template v-else>
      <div class="topo-work">
        <!-- ==================== 画布区 ==================== -->
        <div ref="stageRef" class="topo-stage">
          <TopologyCanvas
            ref="canvasRef"
            :nodes="visibleNodes"
            :edges="visibleEdges"
            :positions="positions"
            :clusters="visibleClusters"
            :cluster-of="clusterOf"
            :degree="centrality.degree"
            :theme="theme"
            :selected-node-id="selectedNodeId"
            :selected-edge-id="selectedEdgeId"
            :hover-edge-id="hoverEdgeId"
            :highlight-ids="highlightIds"
            :show-hulls="hullsEnabled"
            :show-edge-labels="options.showEdgeLabels"
            :show-minimap="options.showMinimap"
            :show-grid="options.showGrid"
            :draggable="options.draggable"
            :insets="canvasInsets"
            :variant="variant"
            @node-click="onNodeClick"
            @edge-click="onEdgeClick"
            @blank-click="onBlankClick"
            @node-dblclick="onNodeDblClick"
            @node-menu="onNodeMenu"
            @edge-menu="onEdgeMenu"
            @blank-menu="onBlankMenu"
          >
            <template #empty>
              <EmptyState
                size="inline"
                icon="ic_public_search"
                title="当前筛选下没有可显示的角色"
                desc="放宽筛选条件或清空搜索词即可看到关系网络。"
                primary-text="重置筛选"
                @primary="resetFilters"
              />
            </template>
          </TopologyCanvas>

          <!-- 浮动：工具条（左上） -->
          <div class="float float-bar">
            <TopologyToolbar
              :layout="layoutId"
              :search="search"
              :search-hit-count="searchHitCount"
              :filters="filters"
              :type-options="typeOptions"
              :state-options="stateOptions"
              :cluster-options="legendClusters"
              :options="options"
              :live-state="liveState"
              :ego-center-name="egoCenterName"
              :legend-open="legendOpen"
              :fullscreen="fullscreen"
              :generating="generating"
              :generate-text="generateText"
              :compact="variant === 'tab'"
              @update:layout="(v) => (layoutId = v)"
              @update:search="(v) => (search = v)"
              @patch-filters="patchFilters"
              @patch-options="patchOptions"
              @action="onToolbarAction"
            />
          </div>

          <!-- 浮动：图例（左下）+ 实时事件（图例上方） -->
          <div class="float float-left">
            <transition-group v-if="liveEvents.length" name="el-fade-in" tag="div" class="live-list">
              <div v-for="ev in liveEvents" :key="ev.key" class="live-item">
                <span class="live-dot" :class="ev.delta >= 0 ? 'up' : 'down'"></span>
                <span class="live-text">
                  <b>{{ ev.fromName }}</b> → <b>{{ ev.toName }}</b>
                  <span class="live-type">{{ ev.relationStateLabel || '关系变化' }}</span>
                  <span class="live-delta" :class="ev.delta >= 0 ? 'up' : 'down'">{{ ev.delta >= 0 ? '+' : '' }}{{ ev.delta }}</span>
                  <span v-if="ev.reason" class="live-reason" :title="ev.reason">{{ ev.reason }}</span>
                </span>
              </div>
            </transition-group>
            <TopologyLegend
              :clusters="legendClusters"
              :types="legendTypes"
              :more-types="moreTypeCount"
              :active-clusters="filters.clusterIds"
              :active-types="filters.types"
              :open="legendOpen"
              @action="onToolbarAction"
            />
          </div>

          <!-- 浮动：状态条（右下） -->
          <div class="float float-status">
            <span class="st-item">{{ countLabel }}</span>
            <span class="st-item">
              <el-icon><HIcon name="DataAnalysis" /></el-icon>&nbsp;{{ layoutLabel }}
            </span>
            <span class="st-item" :title="`圈子 ${clusters.length} 个 · 按世界内核真实归属优先判定`">
              <el-icon><HIcon name="Reading" /></el-icon>&nbsp;{{ clusters.length }} 个圈子
            </span>
            <span v-if="search" class="st-item st-hit">搜索命中 {{ searchHitCount }} 人</span>
            <span v-if="egoCenterName" class="st-item st-ego">
              以「{{ egoCenterName }}」为中心 {{ filters.egoRadius === 0 ? '（不限跳数）' : `${filters.egoRadius} 跳` }}
              <button type="button" class="st-x" title="取消" @click="patchFilters({ egoCenter: null })">×</button>
            </span>
            <span class="st-item" :class="['st-live', liveState]">
              <span class="st-dot"></span>
              {{ liveText }}
            </span>
          </div>
        </div>

        <!-- ==================== 右侧详情栏 ==================== -->
        <RelationDetailDock
          :node="selectedNode"
          :cluster="selectedCluster"
          :source-label="selectedClusterSource"
          :rows="selectedRows"
          :ranking="ranking"
          :selected-edge-id="selectedEdgeId"
          :refining-id="refiningId"
          :collapsed="dockCollapsed"
          :relation-count="selectedDegree"
          @toggle="(v) => (dockCollapsed = v)"
          @locate="locateNode"
          @supplement="(name) => emit('navigate-supplement', name)"
          @add-relation="(fromKey) => openCreate(fromKey)"
          @edit="(row) => openEditRow(row)"
          @remove="(row) => handleDeleteRow(row)"
          @refine="(row) => handleAiRefineRow(row)"
          @pick-relation="onPickRelation"
          @hover-relation="onHoverRelation"
          @pick-node="locateNode"
        >
          <template #notice>
            <div v-if="model.edges.length === 0 && !hintDismissed" class="dock-notice">
              <div class="notice-title">还没有任何已登记的关系</div>
              <div class="notice-text">
                图上按世界内核的真实归属（组织 / 血缘 / 职业）分成了 <b>{{ clusters.length }}</b> 个圈子——
                先看「分成哪几拨人」，再补关系。关系一旦产生，连线会自动按互动强度与新鲜度画出来。
              </div>
              <div class="notice-ops">
                <el-button size="small" type="primary" @click="handleGenerate(variant === 'tab' ? 'character' : 'project')">
                  <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;让 AI 依据世界观识别关系
                </el-button>
                <el-button size="small" link @click="hintDismissed = true">知道了</el-button>
              </div>
            </div>
          </template>
        </RelationDetailDock>
      </div>
    </template>

    <!-- ==================== 生成结果预览确认弹窗（保留原有能力） ==================== -->
    <el-dialog v-model="preview.visible" title="AI 识别到的关系（预览确认后入库）" width="760px" top="6vh">
      <div class="preview-body">
        <div v-if="preview.items.length === 0" class="side-empty">AI 未识别到任何关系，可完善世界观/角色详细信息后重试。</div>
        <template v-else>
          <div class="preview-ops">
            <el-checkbox :model-value="allChecked" @change="toggleAll">全选 / 全不选</el-checkbox>
            <span class="preview-count">已选 {{ checkedCount }}/{{ preview.items.length }} 条</span>
          </div>
          <div class="preview-list">
            <div v-for="(it, i) in preview.items" :key="i" class="preview-item">
              <el-checkbox v-model="it.checked" />
              <span class="pv-from" :class="{ ghost: isGhostName(it.from) }" :title="isGhostName(it.from) ? '尚未创建该角色（待补充）' : ''">{{ it.from }}</span>
              <span class="pv-arrow">→</span>
              <span class="pv-to" :class="{ ghost: isGhostName(it.to) }" :title="isGhostName(it.to) ? '尚未创建该角色（待补充）' : ''">{{ it.to }}</span>
              <el-tag size="small" effect="plain" class="pv-type">{{ it.relationType }}</el-tag>
              <span class="pv-desc">{{ it.description || '' }}</span>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="preview.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="checkedCount === 0" @click="confirmSave">
          确认入库（{{ checkedCount }} 条）
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 关系管理抽屉（手工 CRUD） ==================== -->
    <el-drawer v-model="drawerVisible" title="角色关系管理" size="680px">
      <div class="rel-drawer">
        <div class="rel-drawer-ops">
          <el-button type="primary" size="small" @click="openCreate()">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增关系
          </el-button>
          <span class="rel-drawer-hint">好感随互动自动增减 · 支持手动编辑与 AI 精修</span>
        </div>
        <el-table :data="drawerRows" size="small" max-height="60vh" empty-text="暂无关系数据（可用「AI 识别关系」）">
          <el-table-column label="从" min-width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.fromName || '—' }}</template>
          </el-table-column>
          <el-table-column label="关系" width="132">
            <template #default="{ row }">
              <span class="rel-type" :style="{ color: colorForType(row.relationType) }">{{ row.relationType || '未知' }}</span>
              <div class="rel-state">{{ relStateText(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="到" min-width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.toName || '—' }}</template>
          </el-table-column>
          <el-table-column label="好感" width="64" align="center">
            <template #default="{ row }">
              <span class="rel-aff" :class="affinityTone(row.affinity)">{{ formatAffinity(row.affinity) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="信任" width="56" align="center">
            <template #default="{ row }">{{ row.trust ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="最近互动" width="84" align="center">
            <template #default="{ row }">{{ freshnessText(row.lastInteractionAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="172" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" size="small" :loading="refiningId === row.id" @click="handleAiRefine(row)">AI精修</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- ==================== 关系编辑弹窗 ==================== -->
    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑关系' : '新增关系'" width="540px" top="8vh">
      <el-form label-width="76px" size="default">
        <el-form-item label="从（起点）">
          <el-select v-model="form.fromKey" filterable placeholder="选择起点角色（NPC/普通人群/待补充）">
            <el-option v-for="n in model.nodes" :key="n.id" :label="nodeLabel(n)" :value="n.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到（目标）">
          <el-select v-model="form.toKey" filterable placeholder="选择目标角色">
            <el-option v-for="n in model.nodes" :key="n.id" :label="nodeLabel(n)" :value="n.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关系类型">
          <el-input v-model="form.relationType" placeholder="如：师徒 / 挚友 / 敌对（50 字内）" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="关系描述（可空）" maxlength="255" />
        </el-form-item>
        <el-form-item label="好感">
          <el-input-number v-model="form.affinity" :min="-100" :max="100" :step="1" controls-position="right" />
          <span class="rel-form-hint">-100 厌恶 ~ 100 喜爱</span>
        </el-form-item>
        <el-form-item label="信任">
          <el-input-number v-model="form.trust" :min="0" :max="100" :step="1" controls-position="right" />
          <span class="rel-form-hint">0~100</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.relationState" placeholder="自动按好感/信任计算">
            <el-option label="自动计算（按好感/信任）" value="auto" />
            <el-option label="陌生" value="stranger" />
            <el-option label="相识" value="acquaintance" />
            <el-option label="朋友" value="friend" />
            <el-option label="亲密" value="intimate" />
            <el-option label="对手" value="rival" />
            <el-option label="敌对" value="hostile" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!form.fromKey || !form.toKey" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 右键菜单 ==================== -->
    <div v-if="ctxMenu.visible" class="rel-ctxmenu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @contextmenu.prevent>
      <template v-if="ctxMenu.type === 'edge'">
        <div class="rel-ctx-title">{{ ctxMenu.edgeLabel }}</div>
        <div class="rel-ctx-item" @click="ctxEditEdge"><el-icon><HIcon name="Edit" /></el-icon>&nbsp;编辑关系</div>
        <div class="rel-ctx-item" @click="ctxRefineEdge"><el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 精修关系</div>
        <div class="rel-ctx-item danger" @click="ctxDeleteEdge"><el-icon><HIcon name="Delete" /></el-icon>&nbsp;删除关系</div>
      </template>
      <template v-else>
        <div class="rel-ctx-item" @click="ctxAddRelation">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;{{ ctxMenu.type === 'node' ? `以「${ctxNodeName}」为起点新增关系` : '新增关系' }}
        </div>
        <div v-if="ctxMenu.type === 'node'" class="rel-ctx-item" @click="ctxFocusNode">
          <el-icon><HIcon name="DataAnalysis" /></el-icon>&nbsp;以「{{ ctxNodeName }}」为中心查看
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 角色关系工作台。
 *
 * <p>分工：数据与状态在本组件（唯一数据源）；画布、工具条、图例、详情栏都是受控子组件，
 * 只负责显示与抛出意图。这样「画布上看到的」「图例里数的」「详情栏里列的」永远同源。</p>
 *
 * <p>数据源：GET /api/projects/{id}/character-relations（后端返回 nodes[npc/crowd/ghost] + relations
 * + 角色 meta：头像/性别/年龄/阶段/归属/职业/地点/家族）。</p>
 *
 * <p>实时：GET /api/projects/{id}/world-events/stream 的 kind=relation 事件 →
 * 边闪烁 + 明细刷新（好感/信任/状态随之变化，粗细与颜色自动跟随）。</p>
 */
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacterRelations, generateCharacterRelations, batchSaveCharacterRelations,
  createCharacterRelation, updateCharacterRelation, deleteCharacterRelation, aiRefineCharacterRelation,
  worldEventsStreamUrl
} from '@/shared/api'
import { createSubscription, SSE_EVENTS } from '@/shared/sse/sseClient'
// 统一身份引用
import { npcRef } from '@/shared/npcRef'
// 空态统一走 EmptyState（含失败态的重试 CTA）
import EmptyState from '@/shared/components/EmptyState.vue'
// 工作台子组件与纯逻辑层
import TopologyCanvas from '../topology/components/TopologyCanvas.vue'
import TopologyToolbar from '../topology/components/TopologyToolbar.vue'
import TopologyLegend from '../topology/components/TopologyLegend.vue'
import RelationDetailDock from '../topology/components/RelationDetailDock.vue'
import { buildTheme, themeKey, readTokens } from '../topology/lib/topologyTheme.js'
import { buildClusters, CLUSTER_SOURCE_LABEL } from '../topology/lib/communities.js'
import { computeLayout, LAYOUT_LABEL, HULL_LAYOUTS } from '../topology/lib/deterministicLayout.js'
import {
  normalizeGraph, applyFilters, defaultFilters, computeCentrality, relationRowsOf, datasetKey
} from '../topology/lib/relationGraphModel.js'
import { colorForCluster, colorForType, relStateLabel, clusterTypeLabel, relStateColor } from '../topology/lib/relationPalette.js'
import { formatAffinity, affinityTone, freshnessText, countText } from '../topology/lib/relationFormat.js'

const props = defineProps({
  /** 项目 ID（数据源归属） */
  projectId: { type: Number, required: true },
  /** 当前选中角色 ID（tab 变体：左侧列表选中 → 高亮 + 生成目标；page 变体不用） */
  selectedCharacterId: { type: [Number, String], default: null },
  /** 当前 Tab/页面是否激活（激活时重算尺寸/重建，处理隐藏容器尺寸为 0 的问题） */
  active: { type: Boolean, default: false },
  /** 刷新键：角色集合变化时由父级改变该值触发重新拉取 */
  refreshKey: { type: String, default: '' },
  /** 变体：tab=角色页 Tab（生成当前角色关系）；page=全局拓扑页（一键生成全项目） */
  variant: { type: String, default: 'tab' }
})
const emit = defineEmits(['select-character', 'navigate-supplement', 'clear-selection'])

// ==================== 数据状态 ====================
const state = ref('loading')
const errorMsg = ref('')
const rawNodes = ref([])
const rawRelations = ref([])

const rootRef = ref(null)
const stageRef = ref(null)
const canvasRef = ref(null)

const generating = ref(false)
const saving = ref(false)
const hintDismissed = ref(false)
const preview = reactive({ visible: false, mode: 'supplement', scope: 'character', characterId: null, items: [] })

// ==================== 工作台状态 ====================
/** 布局 ID */
const layoutId = ref('community')
/** 搜索词 */
const search = ref('')
/** 筛选条件 */
const filters = reactive(defaultFilters())
/** 显示选项 */
const options = reactive({
  showHulls: true,
  showMinimap: true,
  showGrid: true,
  /** 关系文字：smart（小图全显）/ all / selected / none */
  showEdgeLabels: 'smart',
  /** 自由拖动单个节点（默认关：拖动会破坏确定性布局） */
  draggable: false,
  /** SSE 实时关系变迁 */
  realtime: true
})
/** 选中 */
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
/** 详情栏悬停的行对应的边（仅视觉高亮，不改选中） */
const hoverEdgeId = ref('')
/** 详情栏收起 */
const dockCollapsed = ref(false)
/** 图例展开（默认收起：图例只在左下角留一个标题条，画布留给图；
 *  需要看圈子/关系类型配色时点工具栏「图例」，或点这个标题条展开） */
const legendOpen = ref(false)
/** 全屏 */
const fullscreen = ref(false)

// ==================== 关系 CRUD 状态 ====================
const drawerVisible = ref(false)
const formVisible = ref(false)
const formMode = ref('create')
const editingId = ref(null)
const refiningId = ref(null)
const ctxMenu = reactive({ visible: false, x: 0, y: 0, type: 'canvas', edgeLabel: '', edgeId: '', nodeKey: '' })
const form = reactive({
  fromKey: '', toKey: '',
  relationType: '', description: '',
  affinity: 0, trust: 0, relationState: 'auto'
})

// ==================== 主题 ====================
/** 主题包（画布配色唯一来源） */
const theme = ref(buildTheme())
/** 主题指纹（变化时重建画布） */
const currentThemeKey = ref(themeKey())
let themeObserver = null

/** 重新读取设计令牌（深/浅主题、个性化主色联动） */
function refreshTheme() {
  const t = readTokens()
  const k = themeKey(t)
  if (k === currentThemeKey.value) return
  currentThemeKey.value = k
  theme.value = buildTheme(t)
}

// ==================== 归一化模型与派生结构 ====================
/** 归一化模型（双向合并、权重、新鲜度） */
const model = computed(() => normalizeGraph({ nodes: rawNodes.value, relations: rawRelations.value }))

/** 圈子分区（**全量**：圈子是世界的结构，不随筛选而改变含义） */
const clusterInfo = computed(() => buildClusters({ nodes: model.value.nodes, edges: model.value.edges }))
const clusters = computed(() => clusterInfo.value.clusters)
const clusterOf = computed(() => clusterInfo.value.clusterOf)
/** 角色 ID → 圈子（便于详情栏取用） */
const clusterById = computed(() => new Map(clusters.value.map(c => [c.id, c])))

/** 中心性（PageRank + 度数；影响力排行与 radial 中心选取共用） */
const centrality = computed(() => computeCentrality(model.value.nodes, model.value.edges))

/** 关系类型汇总（图例 + 筛选选项；按条数降序，颜色与画布同源） */
const typeStats = computed(() => {
  const map = new Map()
  for (const e of model.value.edges) {
    for (const t of e.relationTypes.length ? e.relationTypes : [e.relationType]) {
      map.set(t, (map.get(t) || 0) + 1)
    }
  }
  return [...map.entries()]
    .map(([value, count]) => ({ value, count, color: colorForType(value) }))
    .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value))
})

/** 关系状态汇总（筛选选项） */
const stateStats = computed(() => {
  const map = new Map()
  for (const e of model.value.edges) {
    const k = e.relationState || ''
    map.set(k, (map.get(k) || 0) + 1)
  }
  return [...map.entries()].map(([value, count]) => ({
    value,
    count,
    label: value ? relStateLabel(value) : '未演化',
    color: relStateColor(value)
  })).sort((a, b) => b.count - a.count)
})

/** 中心人物（radial 布局中心） */
const radialCenterId = computed(() => centrality.value.top[0]?.id || '')

/** 搜索命中集合（画布高亮 + 状态条计数） */
const searchResult = computed(() => applyFilters(model.value, filters, {
  clusterOf: clusterOf.value,
  search: search.value,
  clusterLabelOf: (id) => clusterById.value.get(clusterOf.value[id])?.label || ''
}))
const highlightIds = computed(() => [...searchResult.value.matchedBySearch])
const searchHitCount = computed(() => highlightIds.value.length)

/** 可见节点/边（筛选 + 搜索 + ego） */
const visibleNodes = computed(() => searchResult.value.nodes)
const visibleEdges = computed(() => searchResult.value.edges)
const visibleIds = computed(() => new Set(visibleNodes.value.map(n => n.id)))

/** 可见圈子（把全量圈子投影到可见成员上，布局领地与图例计数才与实际一致） */
const visibleClusters = computed(() => clusters.value
  .map(c => {
    const members = c.members.filter(id => visibleIds.value.has(id))
    return { ...c, members, size: members.length }
  })
  .filter(c => c.members.length > 0)
  .sort((a, b) => b.size - a.size || String(a.label).localeCompare(String(b.label))))

// ==================== 布局（确定性 + 缓存） ====================
/** 位置缓存（键 = 布局 + 可见集合 + 中心；命中即复用，避免筛选时重复计算） */
const layoutCache = new Map()
const LAYOUT_CACHE_MAX = 16
let storageKey = ''

/** 计算（或复用）当前布局 */
const layoutResult = computed(() => {
  const nodes = visibleNodes.value
  const edges = visibleEdges.value
  if (!nodes.length) return { positions: {}, clusters: [], size: { width: 0, height: 0 } }
  const focusId = layoutId.value === 'radial'
    ? (selectedNodeId.value || radialCenterId.value)
    : null
  const key = datasetKey({ nodes, edges }, `${layoutId.value}|${focusId || ''}`)
  const hit = layoutCache.get(key)
  if (hit) return hit
  const res = computeLayout({
    nodes, edges, clusters: visibleClusters.value, layout: layoutId.value, options: { focusId }
  })
  layoutCache.set(key, res)
  if (layoutCache.size > LAYOUT_CACHE_MAX) {
    // 简单淘汰：清掉最早插入的一条（Map 保序）
    const first = layoutCache.keys().next().value
    layoutCache.delete(first)
  }
  persistLayout(key, res.positions)
  return res
})

const positions = computed(() => layoutResult.value.positions)

/** 位置持久化（跨会话稳定：同一世界下次进来还是同一张图） */
function persistLayout(key, pos) {
  if (!storageKey) return
  try {
    const raw = window.localStorage.getItem(storageKey)
    const store = raw ? JSON.parse(raw) : {}
    store[key] = pos
    const keys = Object.keys(store)
    // 只保留最近 8 份，避免无限增长
    while (keys.length > 8) {
      const k = keys.shift()
      delete store[k]
    }
    window.localStorage.setItem(storageKey, JSON.stringify(store))
  } catch (e) { /* 隐私模式/超额：静默降级为不缓存 */ }
}

/** 读取持久化位置到缓存 */
function restoreLayouts() {
  storageKey = `holzyn:topo:pos:${props.projectId}`
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) return
    const store = JSON.parse(raw)
    for (const [k, v] of Object.entries(store || {})) layoutCache.set(k, { positions: v, clusters: [], size: { width: 0, height: 0 } })
  } catch (e) { /* 忽略损坏缓存 */ }
}

/** 布局中文名（状态条） */
const layoutLabel = computed(() => LAYOUT_LABEL[layoutId.value] || layoutId.value)

// ==================== 选中与详情 ====================
const selectedNode = computed(() => model.value.nodes.find(n => n.id === selectedNodeId.value) || null)
const selectedCluster = computed(() => {
  const cid = clusterOf.value[selectedNodeId.value]
  return cid ? clusterById.value.get(cid) || null : null
})
const selectedClusterSource = computed(() => (selectedCluster.value ? selectedCluster.value.sourceLabel : ''))
const selectedDegree = computed(() => centrality.value.degree[selectedNodeId.value] || 0)

/** 选中者的关系行（详情栏清单；基于**全量**边，避免 ego 半径把清单也砍掉） */
const selectedRows = computed(() => {
  if (!selectedNodeId.value) return []
  const nodeById = new Map(model.value.nodes.map(n => [n.id, n]))
  return relationRowsOf(selectedNodeId.value, model.value.edges, nodeById, (id) => {
    const c = clusterById.value.get(clusterOf.value[id])
    return c ? colorForCluster(c) : '#94a3b8'
  })
})

/** 影响力排行（未选中时详情栏内容） */
const ranking = computed(() => centrality.value.top.slice(0, 30).map(n => {
  const c = clusterById.value.get(clusterOf.value[n.id])
  return {
    id: n.id,
    name: n.name,
    degree: centrality.value.degree[n.id] || 0,
    color: c ? colorForCluster(c) : '#94a3b8',
    clusterLabel: c ? c.label : ''
  }
}))

/** tab 变体当前选中角色名 */
const selectedName = computed(() => {
  const id = props.selectedCharacterId
  if (id == null) return ''
  const n = model.value.nodes.find(x => x.id === `npc-${id}`)
  return n ? n.name : ''
})

/** 生成按钮文案 */
const generateText = computed(() => {
  if (props.variant === 'tab') return selectedName.value ? `AI 补全「${selectedName.value}」的关系` : 'AI 补全关系'
  return model.value.edges.length ? 'AI 补充全项目关系' : 'AI 识别全项目关系'
})

/** ego 中心角色名 */
const egoCenterName = computed(() => {
  if (!filters.egoCenter) return ''
  const n = model.value.nodes.find(x => x.id === filters.egoCenter)
  return n ? n.name : ''
})

/** 图例里的圈子（全量；颜色与画布同源） */
const legendClusters = computed(() => clusters.value.map(c => ({
  id: c.id,
  label: c.label,
  typeLabel: c.typeLabel || clusterTypeLabel(c.type),
  sourceLabel: c.sourceLabel || CLUSTER_SOURCE_LABEL[c.source] || '',
  size: c.size,
  color: colorForCluster(c)
})))

/** 图例里的关系类型（最多 12 种，多的引导用筛选面板） */
const legendTypes = computed(() => typeStats.value.slice(0, 12))
const moreTypeCount = computed(() => Math.max(0, typeStats.value.length - 12))
/** 筛选面板的类型/状态选项 */
const typeOptions = computed(() => typeStats.value)
const stateOptions = computed(() => stateStats.value)

/** 状态条计数文案 */
const countLabel = computed(() => countText(
  visibleNodes.value.length, visibleEdges.value.length,
  model.value.nodes.length, model.value.edges.length))

/**
 * 画布安全边距（自适应视野时避开浮动面板）。
 *
 * <p>只把「整条边被占住」的浮层算进边距：顶部工具条、底部状态条/小地图——
 * 左下角的图例收起时只有一条标题栏（不到 40px 高），把整条左边让出来反而会让图变小、
 * 画面更空；但图例一旦展开就是一块 356px 宽的面板，这时必须让位，否则半个村的人藏在面板底下
 * （TopologyCanvas 内部还会把左边的让位收口到「内容至少留 320px 宽」）。</p>
 */
const canvasInsets = computed(() => ({
  // 顶部要同时让开工具条（68px）与圈子外框的圈名标签（浮在节点上方 ~20px）
  top: 100,
  left: legendOpen.value ? 372 : 24,
  right: 24,
  // 底部：小地图占右下 110px，状态条被抬到它上方（bottom:148 + 高 34）——
  // 边距必须按「最高的那块浮层」算，否则最下面一排人会被状态条压住（实测过）
  bottom: options.showMinimap ? 196 : 92
}))

/**
 * 当前布局是否画圈子外框。
 *
 * <p>用户的开关偏好照旧保留，但 concentric / radial 下强制不画：这两个布局按「重要度 / 跳数」
 * 重排，同一个圈子的人被打散到不同环上，外框会变成几个穿过整张图的大椭圆、圈名还叠在一起。</p>
 */
const hullsEnabled = computed(() => options.showHulls && HULL_LAYOUTS.includes(layoutId.value))

/** 抽屉数据（原始关系表，含未在图上合并的双向两条） */
const drawerRows = computed(() => rawRelations.value)

// ==================== 数据加载 ====================
/** 拉取关系图数据并重建模型 */
async function load() {
  state.value = 'loading'
  errorMsg.value = ''
  try {
    const data = await fetchCharacterRelations(props.projectId)
    const list = data?.nodes || []
    if (list.length === 0) {
      state.value = 'no-characters'
      return
    }
    rawNodes.value = list
    rawRelations.value = data?.relations || []
    // 选中的角色若已不在数据里（被删除/改名），清空选中
    if (selectedNodeId.value && !list.some(n => n.id === selectedNodeId.value)) {
      selectedNodeId.value = ''
      selectedEdgeId.value = ''
    }
    state.value = 'ready'
    lastLoadedKey = props.refreshKey
    await nextTick()
    refreshTheme()
  } catch (e) {
    state.value = 'error'
    errorMsg.value = e.message || '加载关系拓扑失败'
  }
}

// ==================== 交互 ====================
/** 选中一个角色（同时清空边选中） */
function selectNode(id) {
  selectedNodeId.value = id || ''
  selectedEdgeId.value = ''
  if (id) dockCollapsed.value = false
}

/** 画布点击节点 */
function onNodeClick(id) {
  if (!id) return
  const node = model.value.nodes.find(n => n.id === id)
  if (!node) return
  // 角色页 Tab：点 NPC 双向联动到左侧列表（保持旧行为）
  if (props.variant === 'tab' && node.kind === 'npc') {
    const numId = Number(String(id).replace('npc-', ''))
    if (!Number.isNaN(numId)) emit('select-character', numId)
  }
  selectNode(id)
}

/** 画布点击边：选中这条关系 → 详情栏切到「谁对谁」中关系更多的那一端 */
function onEdgeClick(id) {
  const edge = model.value.edges.find(e => e.id === id)
  if (!edge) return
  selectedEdgeId.value = id
  const a = centrality.value.degree[edge.source] || 0
  const b = centrality.value.degree[edge.target] || 0
  selectNode(a >= b ? edge.source : edge.target)
}

/** 画布点击空白 */
function onBlankClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  if (props.variant === 'tab') emit('clear-selection')
}

/** 双击节点：聚焦（平滑居中放大） */
function onNodeDblClick(id) {
  if (!id) return
  selectNode(id)
  canvasRef.value?.focusNode(id)
}

/** 节点右键 */
function onNodeMenu(id, ev) {
  if (!id) return
  ctxMenu.edgeId = ''
  ctxMenu.edgeLabel = ''
  ctxMenu.nodeKey = id
  ctxMenu.type = 'node'
  openCtxAt(ev)
}

/** 边右键 */
function onEdgeMenu(id, ev) {
  const edge = model.value.edges.find(e => e.id === id)
  if (!edge) return
  const main = edge.items && edge.items.length ? edge.items[0] : null
  ctxMenu.nodeKey = ''
  ctxMenu.edgeId = main ? main.id : ''
  ctxMenu.edgeLabel = main ? `${main.fromName} → ${main.toName}（${main.relationType}）` : '关系'
  ctxMenu.type = 'edge'
  openCtxAt(ev)
}

/** 空白右键 */
function onBlankMenu(ev) {
  ctxMenu.edgeId = ''
  ctxMenu.edgeLabel = ''
  ctxMenu.nodeKey = ''
  ctxMenu.type = 'canvas'
  openCtxAt(ev)
}

/** 详情栏点击某条关系：选中这条边并聚焦到对方 */
function onPickRelation(row) {
  selectedEdgeId.value = row.edgeId
  canvasRef.value?.focusNode(row.otherId)
}

/** 详情栏悬停某条关系：画布上把它临时高亮（鼠标离开自动复原，不改选中态） */
function onHoverRelation(edgeId) {
  hoverEdgeId.value = edgeId || ''
}

/** 在画布中定位某人 */
function locateNode(id) {
  if (!id) return
  selectNode(id)
  canvasRef.value?.focusNode(id)
}

/** 筛选条件补丁 */
function patchFilters(patch) {
  Object.assign(filters, patch)
  if (patch && patch.egoCenter !== undefined && patch.egoCenter) dockCollapsed.value = false
}

/** 显示选项补丁 */
function patchOptions(patch) {
  Object.assign(options, patch)
}

/** 重置筛选（保留 ego 中心？不保留——「重置」就该回到全量） */
function resetFilters() {
  Object.assign(filters, defaultFilters())
  search.value = ''
}

/** 工具条/图例动作分发 */
function onToolbarAction(action) {
  if (typeof action === 'object' && action) {
    if (action.name === 'toggle-cluster') {
      const list = filters.clusterIds.slice()
      const i = list.indexOf(action.id)
      if (i >= 0) list.splice(i, 1)
      else list.push(action.id)
      patchFilters({ clusterIds: list })
      return
    }
    if (action.name === 'toggle-type') {
      const list = filters.types.slice()
      const i = list.indexOf(action.value)
      if (i >= 0) list.splice(i, 1)
      else list.push(action.value)
      patchFilters({ types: list })
      return
    }
    return
  }
  switch (action) {
    case 'reset-filters':
      resetFilters()
      break
    case 'toggle-legend':
      legendOpen.value = !legendOpen.value
      break
    case 'fit':
      canvasRef.value?.fitView(true)
      break
    case 'export':
      void exportPNG()
      break
    case 'fullscreen':
      void toggleFullscreen()
      break
    case 'manage':
      drawerVisible.value = true
      break
    case 'generate':
      void handleGenerate(props.variant === 'tab' ? 'character' : 'project')
      break
    default:
      break
  }
}

// ==================== 视图操作 ====================
/** 导出 PNG（含点位提示） */
async function exportPNG() {
  const url = await canvasRef.value?.exportPNG()
  if (!url) {
    ElMessage.warning('导出失败：画布尚未就绪')
    return
  }
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = `关系拓扑-${Date.now()}.png`
    a.click()
    ElMessage.success('已导出 PNG')
  } catch (e) {
    ElMessage.error('导出失败：' + (e.message || '未知错误'))
  }
}

/** 全屏切换 */
async function toggleFullscreen() {
  try {
    if (!document.fullscreenElement) {
      await rootRef.value?.requestFullscreen?.()
    } else {
      await document.exitFullscreen?.()
    }
  } catch (e) {
    ElMessage.warning('当前环境不支持全屏')
  }
}

/** 全屏状态同步 */
function onFullscreenChange() {
  fullscreen.value = !!document.fullscreenElement
  nextTick(() => canvasRef.value?.fitView(false))
}

// ==================== 键盘导航 ====================
/**
 * 键盘：Esc 取消选中 · f 自适应 · +/- 缩放 · ←→↑↓ 沿关系走到相邻角色。
 *
 * <p>「用键盘在关系网里走」是大图产品里非常实用的一招：用鼠标在几百个点里找邻居很难，
 * 用方向键顺着关系走却能一层层把一个人的社交圈看全。</p>
 *
 * @param {KeyboardEvent} e 键盘事件
 */
function onKeydown(e) {
  if (e.target && ['INPUT', 'TEXTAREA'].includes(e.target.tagName)) return
  const key = e.key
  if (key === 'Escape') {
    onBlankClick()
    return
  }
  if (key === 'f' || key === 'F') {
    canvasRef.value?.fitView(true)
    return
  }
  if (key === '+' || key === '=') {
    canvasRef.value?.zoomBy(1.25)
    return
  }
  if (key === '-' || key === '_') {
    canvasRef.value?.zoomBy(0.8)
    return
  }
  if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(key)) {
    e.preventDefault()
    walkSelection(key)
  }
}

/** 沿关系走到相邻角色（在选中者的关系行里按方向键循环） */
function walkSelection(key) {
  const rows = selectedRows.value
  if (!rows.length) {
    // 未选中：选中排行第一的人
    if (ranking.value.length) locateNode(ranking.value[0].id)
    return
  }
  const idx = rows.findIndex(r => r.otherId === lastWalkId)
  const step = (key === 'ArrowRight' || key === 'ArrowDown') ? 1 : -1
  const next = rows[(((idx < 0 ? 0 : idx + step) % rows.length) + rows.length) % rows.length]
  lastWalkId = next.otherId
  locateNode(next.otherId)
}
let lastWalkId = ''

// ==================== AI 关系生成（预览确认后入库） ====================
/**
 * 点击生成：先判定范围是否已有数据 → 有则弹窗选「重建/补充」→ 调 AI 生成预览 → 弹预览确认弹窗。
 *
 * @param {string} scope character（当前选中角色）/ project（全项目）
 */
async function handleGenerate(scope) {
  if (scope === 'character' && props.selectedCharacterId == null) {
    ElMessage.warning('请先在左侧选择一个角色，或改用全局拓扑页生成全项目关系')
    return
  }
  const hasData = scope === 'project' ? model.value.edges.length > 0 : charHasRelations()
  let mode = 'supplement'
  if (hasData) {
    try {
      await ElMessageBox.confirm(
        scope === 'project'
          ? '该项目已存在关系数据，请选择生成方式：\n「重建」将清空整个项目的关系表后重新生成；\n「补充」保留已有关系，仅追加新识别到的。'
          : `角色「${selectedName.value}」已存在关系数据，请选择生成方式：\n「重建」将清空该角色的相关关系后重新生成；\n「补充」保留已有关系，仅追加新识别到的。`,
        '生成方式', { confirmButtonText: '重建并生成', cancelButtonText: '补充生成', type: 'warning', distinguishCancelAndClose: true }
      )
      mode = 'rebuild'
    } catch (action) {
      if (action === 'cancel') mode = 'supplement'
      else return // 点右上角 X 关闭：取消整个操作
    }
  }
  generating.value = true
  try {
    const drafts = await generateCharacterRelations(props.projectId, {
      scope,
      characterId: scope === 'character' ? Number(props.selectedCharacterId) : null,
      mode
    })
    preview.items = (drafts || []).map(d => ({ ...d, checked: true }))
    preview.mode = mode
    preview.scope = scope
    preview.characterId = scope === 'character' ? Number(props.selectedCharacterId) : null
    if (preview.items.length === 0) {
      ElMessage.info('AI 未识别到任何关系，可先完善世界观设定与角色详细信息后重试')
      preview.visible = false
    } else {
      preview.visible = true
    }
  } catch (e) {
    ElMessage.error(e.message || '关系生成失败')
  } finally {
    generating.value = false
  }
}

/** 当前选中角色是否已有关系 */
function charHasRelations() {
  const id = props.selectedCharacterId
  if (id == null) return false
  const key = `npc-${id}`
  return model.value.edges.some(e => e.source === key || e.target === key)
}

const checkedCount = computed(() => preview.items.filter(i => i.checked).length)
const allChecked = computed(() => preview.items.length > 0 && checkedCount.value === preview.items.length)

/** 全选/全不选 */
function toggleAll(v) {
  preview.items.forEach(i => { i.checked = !!v })
}

/** 该名字是否「幽灵」（角色表/普通人群都没有） */
function isGhostName(name) {
  return !model.value.nodes.some(n => n.name === name && n.kind !== 'ghost')
}

/** 确认入库 */
async function confirmSave() {
  const checked = preview.items.filter(i => i.checked)
  if (checked.length === 0) return
  saving.value = true
  try {
    const res = await batchSaveCharacterRelations(props.projectId, {
      mode: preview.mode,
      characterId: preview.characterId,
      items: checked.map(({ from, to, relationType, description }) => ({ from, to, relationType, description }))
    })
    ElMessage.success(`已入库 ${res?.added ?? checked.length} 条关系`)
    preview.visible = false
    await load()
    canvasRef.value?.fitView(true)
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    saving.value = false
  }
}

// ==================== 关系 CRUD ====================
/** 关系状态文案（抽屉「关系」列） */
function relStateText(row) {
  if (row.affinity == null && row.trust == null) return '未演化'
  return relStateLabel(row.relationState, row.relationStateLabel)
}

/** 节点下拉标签 */
function nodeLabel(n) {
  const tag = n.kind === 'crowd' ? '普通' : n.kind === 'ghost' ? '待补' : (n.type === 'common' ? 'NPC' : '特殊')
  return `${n.name}（${tag}）`
}

/** 节点 key → {ref, name}（npc-{id}/crowd-{id} → 统一身份引用 npc:{id}；ghost → 仅名称） */
function refOfNodeKey(key) {
  const n = model.value.nodes.find(x => x.id === key)
  if (!n) return { ref: null, name: '' }
  if (key.startsWith('npc-')) return { ref: npcRef(key.slice(4)), name: n.name }
  if (key.startsWith('crowd-')) return { ref: npcRef(key.slice(6)), name: n.name }
  return { ref: null, name: n.name }
}

/** 打开新增弹窗（preFromKey=右键起点可空） */
function openCreate(preFromKey) {
  formMode.value = 'create'
  editingId.value = null
  form.fromKey = preFromKey || ''
  form.toKey = ''
  form.relationType = ''
  form.description = ''
  form.affinity = 0
  form.trust = 0
  form.relationState = 'auto'
  formVisible.value = true
}

/** 打开编辑弹窗（预填行数据） */
function openEdit(row) {
  const edge = model.value.edges.find(e => (e.items || []).some(i => i.id === row.id))
  formMode.value = 'edit'
  editingId.value = row.id
  form.fromKey = row.fromKey || (edge ? edge.source : '')
  form.toKey = row.toKey || (edge ? edge.target : '')
  form.relationType = row.relationType || ''
  form.description = row.description || ''
  form.affinity = row.affinity ?? 0
  form.trust = row.trust ?? 0
  form.relationState = row.relationState || 'auto'
  formVisible.value = true
}

/** 详情栏的「编辑」：从关系行取主记录 */
function openEditRow(row) {
  const main = row.items && row.items.length ? row.items[0] : null
  if (main) openEdit(main)
}

/** 保存（新增/编辑） */
async function saveForm() {
  if (!form.fromKey || !form.toKey) return
  saving.value = true
  try {
    const f = refOfNodeKey(form.fromKey)
    const t = refOfNodeKey(form.toKey)
    const isEdit = formMode.value === 'edit'
    const editing = rawRelations.value.find(r => r.id === editingId.value)
    const fChanged = !isEdit || !sameRef(f.ref, editing && editing.fromRef)
    const tChanged = !isEdit || !sameRef(t.ref, editing && editing.toRef)
    const payload = {
      relationType: form.relationType || null,
      description: form.description || null,
      affinity: form.affinity,
      trust: form.trust,
      relationState: form.relationState === 'auto' ? null : form.relationState
    }
    if (fChanged) { payload.fromRef = f.ref; payload.fromName = f.name || null }
    if (tChanged) { payload.toRef = t.ref; payload.toName = t.name || null }
    if (isEdit) {
      await updateCharacterRelation(editingId.value, payload)
      ElMessage.success('关系已更新')
    } else {
      await createCharacterRelation(props.projectId, payload)
      ElMessage.success('关系已新增')
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 两端 ref 是否相同（null 与 null 视为相同） */
function sameRef(a, b) {
  return (a || '') === (b || '')
}

/** 删除关系（确认后） */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除关系「${row.fromName || '?'} → ${row.toName || '?'}（${row.relationType || '未知'}）」吗？`,
      '删除关系', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (_) {
    return
  }
  try {
    await deleteCharacterRelation(row.id)
    ElMessage.success('关系已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 详情栏的「删除」：双向边一次删两条（先问清楚） */
async function handleDeleteRow(row) {
  if (!row.items || row.items.length <= 1) {
    if (row.items && row.items[0]) handleDelete(row.items[0])
    return
  }
  try {
    await ElMessageBox.confirm(
      `这是一条双向关系（含 ${row.items.length} 条记录）：${row.items.map(i => `${i.fromName} → ${i.toName}（${i.relationType}）`).join('、')}。删除后两个方向都会消失。`,
      '删除双向关系', { type: 'warning', confirmButtonText: '全部删除', cancelButtonText: '取消' })
  } catch (_) {
    return
  }
  try {
    for (const item of row.items) await deleteCharacterRelation(item.id)
    ElMessage.success(`已删除 ${row.items.length} 条关系`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** AI 精修单条关系 */
async function handleAiRefine(row) {
  refiningId.value = row.id
  try {
    const res = await aiRefineCharacterRelation(props.projectId, row.id)
    ElMessage.success(`AI 精修完成：好感 ${res?.affinity ?? row.affinity ?? 0} · 信任 ${res?.trust ?? row.trust ?? 0} · ${relStateLabel(res?.relationState || row.relationState)}`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || 'AI 精修失败')
  } finally {
    refiningId.value = null
  }
}

/** 详情栏的「AI 精修」 */
function handleAiRefineRow(row) {
  const main = row.items && row.items.length ? row.items[0] : null
  if (main) handleAiRefine(main)
}

// ==================== 右键菜单 ====================
function closeCtxMenu() {
  ctxMenu.visible = false
  ctxMenu.edgeId = ''
  ctxMenu.edgeLabel = ''
  ctxMenu.nodeKey = ''
}

const ctxNodeName = computed(() => {
  const n = model.value.nodes.find(x => x.id === ctxMenu.nodeKey)
  return n ? n.name : ''
})

/** 右键菜单定位（视口边缘内收） */
function openCtxAt(ev) {
  let px = 0
  let py = 0
  const c = ev && ev.client
  if (c && (c.x != null || c[0] != null)) {
    px = c.x ?? c[0]
    py = c.y ?? c[1]
  } else if (ev && ev.clientX != null) {
    px = ev.clientX
    py = ev.clientY
  } else if (stageRef.value) {
    const rect = stageRef.value.getBoundingClientRect()
    px = rect.left + ((ev && ev.x) || 0)
    py = rect.top + ((ev && ev.y) || 0)
  }
  ctxMenu.x = Math.max(4, Math.min(px, window.innerWidth - 240))
  ctxMenu.y = Math.max(4, Math.min(py, window.innerHeight - 160))
  ctxMenu.visible = true
}

function ctxEditEdge() {
  const id = ctxMenu.edgeId
  closeCtxMenu()
  const row = rawRelations.value.find(r => r.id === id)
  if (row) openEdit(row)
}

function ctxRefineEdge() {
  const id = ctxMenu.edgeId
  closeCtxMenu()
  const row = rawRelations.value.find(r => r.id === id)
  if (row) handleAiRefine(row)
}

function ctxDeleteEdge() {
  const id = ctxMenu.edgeId
  closeCtxMenu()
  const row = rawRelations.value.find(r => r.id === id)
  if (row) handleDelete(row)
}

function ctxAddRelation() {
  const fromKey = ctxMenu.nodeKey || ''
  closeCtxMenu()
  openCreate(fromKey)
}

/** 右键「以 TA 为中心」：设 ego 中心 + 选中 + 定位 */
function ctxFocusNode() {
  const id = ctxMenu.nodeKey
  closeCtxMenu()
  if (!id) return
  patchFilters({ egoCenter: id, egoRadius: 1 })
  locateNode(id)
}

// ==================== SSE 实时关系变迁 ====================
const liveState = ref('off')
const liveEvents = ref([])
let subscription = null
let reloadTimer = null
let lastLoadedKey = ''

/** 实时状态文案 */
const liveText = computed(() => {
  if (!options.realtime) return '实时已关闭'
  if (liveState.value === 'on') return '实时已连接'
  if (liveState.value === 'connecting') return '实时连接中…'
  return '实时未连接'
})

/** 订阅世界事件流 */
function connectLive() {
  if (subscription || !options.realtime) return
  liveState.value = 'connecting'
  subscription = createSubscription({
    url: worldEventsStreamUrl(props.projectId),
    events: [SSE_EVENTS.WORLD_EVENT, SSE_EVENTS.ERROR],
    onConnected: () => { liveState.value = 'on' },
    onEvent: (name, data) => {
      if (name !== SSE_EVENTS.WORLD_EVENT || !data) return
      handleLiveEvent(data)
    },
    onError: () => { liveState.value = 'off' },
    onGiveUp: () => { liveState.value = 'off' }
  })
}

/** 断开订阅 */
function disconnectLive() {
  if (subscription) {
    try { subscription.close() } catch (e) { /* 忽略 */ }
    subscription = null
  }
  liveState.value = 'off'
}

/**
 * 处理世界事件：只关心 kind=relation（关系变迁），解析 payloadJson 后闪烁对应边并刷新明细。
 *
 * @param {object} ev 世界事件载荷（WorldEventViews.toMap）
 */
function handleLiveEvent(ev) {
  if (ev.kind !== 'relation') return
  let payload = null
  try {
    payload = typeof ev.payloadJson === 'string' ? JSON.parse(ev.payloadJson) : ev.payloadJson
  } catch (e) {
    payload = null
  }
  if (!payload) return
  const fromName = payload.fromName || '?'
  const toName = payload.toName || '?'
  const delta = Number(payload.delta) || 0
  // 找到对应的边（按 relationId 精确匹配，退化为按姓名对匹配）
  const hit = model.value.edges.find(e => (e.items || []).some(i => i.id === payload.relationId))
    || model.value.edges.find(e => {
      const names = new Set([fromName, toName])
      const a = model.value.nodes.find(n => n.id === e.source)
      const b = model.value.nodes.find(n => n.id === e.target)
      return a && b && names.has(a.name) && names.has(b.name)
    })
  const key = `${ev.id || ev.eventId || Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  liveEvents.value.unshift({
    key,
    fromName,
    toName,
    delta,
    reason: payload.reason || '',
    relationStateLabel: relStateLabel(payload.relationState)
  })
  if (liveEvents.value.length > 4) liveEvents.value = liveEvents.value.slice(0, 4)
  // 边闪烁 + 明细刷新（好感/信任变化会改变边的粗细与颜色）
  if (hit) void canvasRef.value?.flashEdges([hit.id])
  scheduleReload()
  window.setTimeout(() => {
    liveEvents.value = liveEvents.value.filter(e => e.key !== key)
  }, 9000)
}

/** 合并短时间内的多次事件，避免连续重拉 */
function scheduleReload() {
  if (reloadTimer) return
  reloadTimer = window.setTimeout(async () => {
    reloadTimer = null
    await load()
  }, 900)
}

// ==================== 生命周期 ====================
onMounted(async () => {
  restoreLayouts()
  await load()
  themeObserver = new MutationObserver(() => refreshTheme())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] })
  document.addEventListener('click', closeCtxMenu)
  document.addEventListener('scroll', closeCtxMenu, true)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  if (props.active) connectLive()
})

onBeforeUnmount(() => {
  disconnectLive()
  if (reloadTimer) { window.clearTimeout(reloadTimer); reloadTimer = null }
  if (themeObserver) { themeObserver.disconnect(); themeObserver = null }
  document.removeEventListener('click', closeCtxMenu)
  document.removeEventListener('scroll', closeCtxMenu, true)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
})

/** tab 变体：父级选中变化 → 图上高亮并选中 */
watch(() => props.selectedCharacterId, (id) => {
  const key = id == null ? '' : `npc-${id}`
  if (key) selectNode(key)
  else if (selectedNodeId.value.startsWith('npc-')) selectNode('')
})

/** 刷新键变化：重新拉取 */
watch(() => props.refreshKey, () => { if (props.active || props.variant === 'page') load() })

/** 激活状态变化：重连实时 + 尺寸自适应 */
watch(() => props.active, (v) => {
  if (v) {
    if (props.refreshKey !== lastLoadedKey) { load(); return }
    nextTick(() => canvasRef.value?.fitView(false))
    connectLive()
  } else {
    disconnectLive()
  }
})

/** 实时开关 */
watch(() => options.realtime, (on) => {
  if (on && (props.active || props.variant === 'page')) connectLive()
  else disconnectLive()
})

/** 选中者被筛掉时自动取消 ego/选中，避免「详情栏有内容但图上找不到人」 */
watch(visibleIds, (ids) => {
  if (selectedNodeId.value && !ids.has(selectedNodeId.value)) {
    // 若只是被 ego 半径排除，自动放宽到能看见他（ego 中心本身永远可见）
    if (!filters.egoCenter || selectedNodeId.value !== filters.egoCenter) selectNode('')
  }
})

/** 搜索命中首个结果时自动聚焦（搜就要立刻看到） */
watch(searchHitCount, (n) => {
  if (!search.value || n === 0) return
  const first = highlightIds.value[0]
  if (first) canvasRef.value?.focusNode(first)
})

/** radial 布局中心变化时重算（选中的角色成为中心，符合「以人为心」的语义） */
watch(selectedNodeId, () => {
  if (layoutId.value === 'radial') void nextTick(() => canvasRef.value?.fitView(true))
})

defineExpose({ reload: load, selectNode, setLayout: (v) => { layoutId.value = v } })
</script>

<style scoped>
.relation-topology {
  display: flex;
  flex-direction: column;
  min-height: 420px;
  outline: none;
}
/* page 变体：整高工作台（画布铺满，浮层压在画布上） */
.relation-topology.page { flex: 1; min-height: 0; height: 100%; }
.relation-topology.fullscreen { background: var(--bg-layer-1); }

/* 加载 / 空态 */
.topo-loading { height: 100%; min-height: 300px; }
.topo-empty { height: 100%; min-height: 300px; display: flex; align-items: center; justify-content: center; }

/* 工作台：画布 + 右侧详情栏 */
.topo-work {
  display: flex;
  flex: 1;
  min-height: 0;
  height: 100%;
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--border-light);
  background: var(--bg-layer-1);
}
.relation-topology.page .topo-work { border-radius: 0; border: none; }
.topo-stage { position: relative; flex: 1; min-width: 0; min-height: 0; }

/* 内嵌（tab）变体：画布高度由令牌给出，详情栏与画布同高 */
.relation-topology.tab .topo-work { min-height: 0; }
.relation-topology.tab :deep(.topo-canvas.tab) { max-height: none; border: none; border-radius: 0; }
.relation-topology.tab .topo-stage { height: var(--canvas-h-embedded, min(58vh, 680px)); min-height: 380px; }

/* 浮层 */
.float { position: absolute; z-index: 8; }
.float-bar { left: 10px; top: 10px; right: auto; max-width: calc(100% - 20px); }
.float-left {
  left: 10px;
  bottom: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
  max-width: calc(100% - 20px);
}
.float-status {
  right: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--bg-layer-1) 90%, transparent);
  backdrop-filter: blur(10px);
  border: 1px solid var(--border-l2);
  font-size: 0.72rem;
  color: var(--text-secondary);
  max-width: min(560px, calc(100% - 440px));
  flex-wrap: wrap;
}
/* 小地图与状态条都在右下：把状态条抬到小地图上方（:has 不可用时状态条会盖住小地图边缘，不影响功能） */
.topo-stage:has(.topo-canvas__minimap) .float-status { bottom: 148px; }
.st-item { display: inline-flex; align-items: center; white-space: nowrap; }
.st-hit { color: #f59e0b; }
.st-ego { color: var(--accent); }
.st-x { border: none; background: transparent; color: inherit; cursor: pointer; padding: 0 2px; }
.st-live { gap: 4px; }
.st-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-placeholder); }
.st-live.on .st-dot { background: #10b981; box-shadow: 0 0 0 3px color-mix(in srgb, #10b981 20%, transparent); }
.st-live.connecting .st-dot { background: #f59e0b; }

/* 实时事件条（图例上方） */
.live-list { display: flex; flex-direction: column; gap: 4px; max-width: 380px; }
.live-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--bg-layer-1) 92%, transparent);
  backdrop-filter: blur(8px);
  border: 1px solid var(--border-l2);
  font-size: 0.72rem;
  color: var(--text-secondary);
}
.live-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--text-placeholder); }
.live-dot.up { background: #10b981; }
.live-dot.down { background: #ef4444; }
.live-type { margin-left: 4px; color: var(--text-tertiary); }
.live-delta { font-weight: 700; font-variant-numeric: tabular-nums; }
.live-delta.up { color: #10b981; }
.live-delta.down { color: #ef4444; }
.live-reason { color: var(--text-placeholder); max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 预览确认弹窗 */
.preview-body { min-height: 120px; }
/* 详情栏里的「还没有任何关系」说明（放在侧栏而不是浮在画布上：浮层会盖住节点） */
.dock-notice {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, var(--accent) 26%, transparent);
  background: color-mix(in srgb, var(--accent) 7%, var(--bg-layer-1));
}
.notice-title { font-size: 0.8rem; font-weight: 700; color: var(--text-primary); }
.notice-text { font-size: 0.74rem; line-height: 1.7; color: var(--text-secondary); }
.notice-ops { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.preview-ops { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.preview-count { font-size: 0.75rem; color: var(--text-tertiary); }
.preview-list { display: flex; flex-direction: column; gap: 6px; max-height: 46vh; overflow-y: auto; }
.preview-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--border-light); border-radius: 8px; font-size: 0.8rem; }
.pv-from, .pv-to { font-weight: 600; white-space: nowrap; }
.pv-from.ghost, .pv-to.ghost { color: var(--text-placeholder); font-weight: 400; text-decoration: underline dotted; }
.pv-arrow { color: var(--text-placeholder); flex-shrink: 0; }
.pv-type { flex-shrink: 0; }
.pv-desc { color: var(--text-secondary); font-size: 0.74rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 关系管理抽屉 */
.rel-drawer { display: flex; flex-direction: column; gap: 10px; }
.rel-drawer-ops { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.rel-drawer-hint { font-size: 0.72rem; color: var(--text-tertiary); }
.rel-type { font-weight: 600; font-size: 0.8rem; }
.rel-state { font-size: 0.68rem; color: var(--text-tertiary); margin-top: 1px; }
.rel-aff { font-weight: 600; font-size: 0.78rem; }
.rel-aff.pos { color: #10b981; }
.rel-aff.neg { color: #ef4444; }
.rel-form-hint { margin-left: 8px; font-size: 0.7rem; color: var(--text-tertiary); }

/* 右键菜单 */
.rel-ctxmenu {
  position: fixed;
  z-index: 3000;
  min-width: 190px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: 10px;
  box-shadow: var(--shadow-lv3);
  padding: 5px;
  font-size: 0.8rem;
  color: var(--text-primary);
}
.rel-ctx-title {
  padding: 5px 10px;
  font-size: 0.72rem;
  color: var(--text-tertiary);
  border-bottom: 1px solid var(--border-light);
  margin-bottom: 4px;
  white-space: nowrap;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rel-ctx-item {
  display: flex;
  align-items: center;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-regular);
  white-space: nowrap;
}
.rel-ctx-item:hover { background: var(--bg-layer-2); }
.rel-ctx-item.danger { color: #ef4444; }
.rel-ctx-item.danger:hover { background: color-mix(in srgb, #ef4444 10%, var(--bg-layer-1)); }
</style>
