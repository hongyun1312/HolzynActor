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
  <div class="relation-topology" :class="variant">
    <!-- 空态①：项目无任何角色/普通人群 -->
    <div v-if="state === 'no-characters'" class="topo-empty">
      <el-empty description="该项目暂无角色，请先新增角色">
        <template #image>
          <el-icon :size="64" class="topo-empty-icon"><HIcon name="User" /></el-icon>
        </template>
      </el-empty>
    </div>

    <!-- 加载中 -->
    <div v-else-if="state === 'loading'" v-loading="true" class="topo-loading" />

    <!-- 加载失败（含重试） -->
    <div v-else-if="state === 'error'" class="topo-empty">
      <el-empty :description="errorMsg">
        <el-button type="primary" @click="load">重试</el-button>
      </el-empty>
    </div>

    <template v-else>
      <!-- 工具栏：生成关系按钮 + 关系管理抽屉 + 节点/关系计数 -->
      <div class="topo-toolbar">
        <span class="topo-count">节点 {{ nodes.length }} · 关系 {{ relations.length }}</span>
        <div class="topo-actions">
          <el-button size="small" @click="openDrawer()">
            <el-icon><HIcon name="DataAnalysis" /></el-icon>&nbsp;关系管理
          </el-button>
          <el-button v-if="variant === 'tab'" size="small" type="primary" :loading="generating"
            :disabled="!selectedCharacterId" :title="selectedCharacterId ? '' : '请先在左侧选中一个角色'"
            @click="handleGenerate('character')">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;生成「{{ selectedName || '该角色' }}」的关系
          </el-button>
          <el-button v-else size="small" type="primary" :loading="generating" @click="handleGenerate('project')">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;一键生成全项目关系
          </el-button>
        </div>
      </div>

      <!-- 空态②：有角色但无关系数据（仍渲染节点） -->
      <el-alert v-if="relations.length === 0" class="topo-hint" type="info" :closable="false" show-icon
        title="暂无角色关系数据：当前展示全部角色/普通人群节点（灰色虚线=关系表引用但尚未补充的角色，点击节点可查看详情或补充）。可使用上方「生成」按钮让 AI 基于世界观与角色信息识别关系。" />

      <!-- 图例：关系类型配色 + 节点类型说明 + 操作提示 -->
      <div class="topo-legend">
        <div class="legend-group">
          <span class="legend-label">关系类型</span>
          <template v-if="legendTypes.length">
            <span v-for="t in legendTypes" :key="t" class="legend-item" :title="`${t}（${typeCount[t]} 条关系）`">
              <span class="legend-dot" :style="{ background: colorForType(t) }"></span>{{ t }}
            </span>
          </template>
          <span v-else class="legend-none">—</span>
        </div>
        <div class="legend-group">
          <span class="legend-label">节点</span>
          <span class="legend-item"><span class="legend-dot npc"></span>NPC</span>
          <span class="legend-item"><span class="legend-dot crowd"></span>普通人群</span>
          <span class="legend-item"><span class="legend-dot ghost"></span>待补充</span>
          <span class="legend-item"><span class="legend-dot size"></span>圆圈大小 = 重要度</span>
        </div>
        <div class="legend-tips">{{ variant === 'page' ? '点击节点查看右上角角色卡片 · ' : '' }}拖拽平移 · 滚轮缩放 · 悬停/点击高亮关联</div>
      </div>

      <!-- 画布 + 右上角角色卡片（page 变体） -->
      <div class="topo-canvas-wrap">
        <div ref="containerRef" class="topo-canvas"></div>
        <transition name="el-fade-in">
          <div v-if="variant === 'page' && cardNode" class="topo-card">
            <div class="card-head">
              <span class="card-name">{{ cardNode.data.name }}</span>
              <el-tag v-if="cardNode.data.kind === 'npc' && cardNode.data.isProtagonist" size="small" type="danger">主角</el-tag>
              <el-tag v-if="cardNode.data.kind === 'npc'" size="small" effect="plain" type="info">{{ cardNode.data.type === 'common' ? '普通型' : '特殊型' }}</el-tag>
              <el-button class="card-close" size="small" text @click="cardNode = null"><el-icon><HIcon name="Close" /></el-icon></el-button>
            </div>
            <template v-if="cardNode.data.kind === 'npc'">
              <div class="card-row"><span class="k">头衔</span><span class="v">{{ cardNode.data.title || '—' }}</span></div>
              <div class="card-row"><span class="k">重要度</span><span class="v">{{ '★'.repeat(cardNode.data.importance || 0) }}{{ '☆'.repeat(Math.max(0, 5 - (cardNode.data.importance || 0))) }}</span></div>
              <div class="card-detail">{{ cardNode.data.detail || '—' }}</div>
            </template>
            <template v-else-if="cardNode.data.kind === 'crowd'">
              <div class="card-row"><span class="k">归属</span><span class="v">{{ cardNode.data.crowdName || '—' }}</span></div>
              <div class="card-row"><span class="k">职业</span><span class="v">{{ cardNode.data.occupation || '—' }}</span></div>
              <div class="card-row"><span class="k">状态</span><span class="v">{{ stateLabel(cardNode.data.state) }}</span></div>
              <div class="card-detail">{{ cardNode.data.detail || cardNode.data.lastAction || '—' }}</div>
            </template>
            <template v-else>
              <div class="card-ghost-tip">暂无具体信息（角色表与普通人群均无该角色数据），请前往补充该角色资料，补充后会自动关联到关系图中。</div>
              <el-button size="small" type="primary" @click="$emit('navigate-supplement', cardNode.data.name)">前往补充</el-button>
            </template>
          </div>
        </transition>
      </div>
    </template>

    <!-- 生成结果预览确认弹窗 -->
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

    <!-- 关系列表抽屉（用户：手动增删改 CRUD） -->
    <el-drawer v-model="drawerVisible" title="角色关系管理" size="600px">
      <div class="rel-drawer">
        <div class="rel-drawer-ops">
          <el-button type="primary" size="small" @click="openCreate()">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增关系
          </el-button>
          <span class="rel-drawer-hint">好感随互动自动增减 · 支持手动编辑与 AI 精修</span>
        </div>
        <el-table :data="relations" size="small" max-height="calc(100vh - 200px)" empty-text="暂无关系数据（可使用上方「生成」让 AI 识别）">
          <el-table-column label="从" min-width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.fromName || '—' }}</template>
          </el-table-column>
          <el-table-column label="关系" width="120">
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
              <span :class="['rel-aff', (row.affinity || 0) > 0 ? 'pos' : (row.affinity || 0) < 0 ? 'neg' : '']">{{ fmtAff(row.affinity) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="信任" width="56" align="center">
            <template #default="{ row }">{{ row.trust ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="操作" width="168" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" size="small" :loading="refiningId === row.id" @click="handleAiRefine(row)">AI精修</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 关系编辑弹窗（新增/编辑共用，） -->
    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑关系' : '新增关系'" width="540px" top="8vh">
      <el-form label-width="76px" size="default">
        <el-form-item label="从（起点）">
          <el-select v-model="form.fromKey" filterable placeholder="选择起点角色（NPC/普通人群/待补充）">
            <el-option v-for="n in nodes" :key="n.id" :label="nodeLabel(n)" :value="n.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到（目标）">
          <el-select v-model="form.toKey" filterable placeholder="选择目标角色">
            <el-option v-for="n in nodes" :key="n.id" :label="nodeLabel(n)" :value="n.id" />
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

    <!-- 拓扑右键菜单（：边=编辑/删除；节点/画布=新增关系） -->
    <div v-if="ctxMenu.visible" class="rel-ctxmenu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @contextmenu.prevent>
      <template v-if="ctxMenu.type === 'edge'">
        <div class="rel-ctx-title">{{ ctxMenu.edge ? `${ctxMenu.edge.fromName} → ${ctxMenu.edge.toName}` : '' }}</div>
        <div class="rel-ctx-item" @click="ctxEditEdge"><el-icon><HIcon name="Edit" /></el-icon>&nbsp;编辑关系</div>
        <div class="rel-ctx-item danger" @click="ctxDeleteEdge"><el-icon><HIcon name="Delete" /></el-icon>&nbsp;删除关系</div>
      </template>
      <template v-else>
        <div class="rel-ctx-item" @click="ctxAddRelation"><el-icon><HIcon name="Plus" /></el-icon>&nbsp;{{ ctxMenu.type === 'node' ? `以「${ctxNodeName}」为起点新增关系` : '新增关系' }}</div>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 角色关系拓扑图。
 * <p>三大能力：</p>
 * <ul>
 * <li><b>生命周期管理</b>：onMounted 建图 → onBeforeUnmount 销毁；ResizeObserver 跟随容器尺寸；
 * 激活（Tab 切回/路由进入）时重算尺寸 + fitView；主题/主色变化（MutationObserver 监听 html[data-theme]/--accent）
 * 自动按新令牌重建图；大图动画关闭保证 50~200+ 节点流畅。</li>
 * <li><b>主题令牌映射</b>：读 DSH 令牌（--accent/--bg-layer-1/--text-primary/…）映射为 画布色，
 * 深浅双主题与个性化主色/世界题材联动自动跟随。</li>
 * <li><b>三类节点 + 关系类型配色/图例</b>：NPC（主色系，大小=重要度）/ 普通人群成员（青绿色系）/
 * 幽灵·待补充（灰色虚线）；关系类型内置 ~40 语义色 + 哈希兜底，图例区展示。</li>
 * <li><b>AI 关系生成（预览确认）</b>：variant=tab 生成当前选中角色的关系（需先选中）；variant=page
 * 一键生成全项目关系；已有数据时弹窗选择「重建/补充」；AI 结果弹预览列表（可勾选）确认后批量入库。</li>
 * <li><b>右上角角色卡片（variant=page）</b>：点击节点 → 右上角卡片显示角色信息（NPC=档案/普通人群=档案）；
 * 两表都没有（幽灵）→「暂无具体信息，请前往补充」→ emit navigate-supplement（父级跳新增角色页并预填名字）。</li>
 * <li><b>交互</b>：拖拽平移/滚轮缩放/拖拽节点；hover 高亮相邻节点（degree=1）其余降透明；
 * 点击高亮其直接关联、其余弱化（透明度 0.45 仍清晰可见）；点击空白画布取消选中、全部恢复全量显示；
 * Tooltip 悬浮详情。</li>
 * <li><b>空态提示</b>：无角色 / 加载失败（可重试）/ 有角色无关系三种空态。</li>
 * </ul>
 * <p>数据源：GET /api/projects/{id}/character-relations（后端返回 nodes[npc/crowd/ghost] + relations[fromKey/toKey]）。</p>
 */
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Graph } from '@antv/g6'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacterRelations, generateCharacterRelations, batchSaveCharacterRelations,
  createCharacterRelation, updateCharacterRelation, deleteCharacterRelation, aiRefineCharacterRelation
} from '@/shared/api'
// 统一身份引用
import { npcRef } from '@/shared/npcRef'

//
const props = defineProps({
  /** 项目 ID（数据源归属） */
  projectId: { type: Number, required: true },
  /** 当前选中角色 ID（tab 变体：左侧列表选中 → 高亮 + 生成目标；page 变体不用） */
  selectedCharacterId: { type: [Number, String], default: null },
  /** 当前 Tab/页面是否激活（激活时重算尺寸/重建，处理隐藏容器尺寸为 0 的问题） */
  active: { type: Boolean, default: false },
  /** 刷新键：角色集合变化时由父级改变该值触发重新拉取 */
  refreshKey: { type: String, default: '' },
  /** 变体：tab=角色页 Tab（生成当前角色关系）；page=全局拓扑页（一键生成全项目 + 右上角卡片） */
  variant: { type: String, default: 'tab' }
})
const emit = defineEmits(['select-character', 'navigate-supplement', 'clear-selection'])

// ==================== 数据状态 ====================

const state = ref('loading')
const errorMsg = ref('')
const nodes = ref([])        // 节点 [{ id: key, data: {...} }]
const edges = ref([])        //
const relations = ref([])    // 原始关系（空态/计数/图例用）
const typeCount = reactive({}) //
const cardNode = ref(null)   // page 变体右上角卡片当前节点
const pageSelectedKey = ref('') // page 变体内部选中节点 key

const containerRef = ref(null)
let graph = null
let resizeObserver = null
let themeObserver = null
let lastThemeKey = ''
let lastLoadedKey = ''

// 生成/预览状态
const generating = ref(false)
const saving = ref(false)
const preview = reactive({ visible: false, mode: 'supplement', scope: 'character', characterId: null, items: [] })

// ==================== 关系手动 CRUD（抽屉/编辑弹窗/右键菜单） ====================
const drawerVisible = ref(false)          // 关系列表抽屉
const formVisible = ref(false)            // /编辑弹窗
const formMode = ref('create')            // create | edit
const editingId = ref(null)               // 编辑中的关系 id
const refiningId = ref(null)              // AI 精修中的关系 id
const ctxMenu = reactive({ visible: false, x: 0, y: 0, type: 'canvas', edge: null, nodeKey: '' })
/** /编辑表单（fromKey/toKey 为节点 key：npc-/crowd-/ghost-） */
const form = reactive({
  fromKey: '', toKey: '',
  relationType: '', description: '',
  affinity: 0, trust: 0, relationState: 'auto'
})

/** 关系状态中文标签（后端返回 label 优先，兜底本地映射） */
const REL_STATE_LABEL = {
  stranger: '陌生', acquaintance: '相识', friend: '朋友',
  intimate: '亲密', rival: '对手', hostile: '敌对'
}
function relStateLabel(state) {
  return REL_STATE_LABEL[state] || state || '—'
}
/** 抽屉「关系」列：状态标签（含动态字段时展示） */
function relStateText(row) {
  if (row.affinity == null && row.trust == null) return '未演化'
  return relStateLabel(row.relationState)
}
/** 好感格式化（0 显示 0；缺省显示 0） */
function fmtAff(v) {
  if (v == null) return 0
  return v > 0 ? `+${v}` : String(v)
}
/** 节点下拉标签：名称 + 类型徽标 */
function nodeLabel(n) {
  const d = n.data || {}
  const tag = d.kind === 'crowd' ? '普通' : d.kind === 'ghost' ? '待补' : (d.type === 'common' ? 'NPC' : '特殊')
  return `${d.name}（${tag}）`
}
/** 节点 key → {ref, name}（npc-{id}/crowd-{id} → 统一身份引用 npc:{id}；ghost → 仅名称） */
function refOfNodeKey(key) {
  const n = nodes.value.find(x => x.id === key)
  if (!n) return { ref: null, name: '' }
  // ：单主表后特殊型与普通型共用 actor_profile 一套 id 空间，端点引用统一 npc:{id}
  if (key.startsWith('npc-')) return { ref: npcRef(key.slice(4)), name: n.data.name }
  if (key.startsWith('crowd-')) return { ref: npcRef(key.slice(6)), name: n.data.name }
  return { ref: null, name: n.data.name }
}

/** 打开关系列表抽屉 */
function openDrawer() {
  drawerVisible.value = true
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
  const edge = edges.value.find(e => e.id === `rel-${row.id}`)
  formMode.value = 'edit'
  editingId.value = row.id
  form.fromKey = edge ? edge.source : ''
  form.toKey = edge ? edge.target : ''
  form.relationType = row.relationType || ''
  form.description = row.description || ''
  form.affinity = row.affinity ?? 0
  form.trust = row.trust ?? 0
  form.relationState = row.relationState || 'auto'
  formVisible.value = true
}

/** 保存（新增/编辑）：端点变更时才提交 ref/名称，避免误改端点 */
async function saveForm() {
  if (!form.fromKey || !form.toKey) return
  saving.value = true
  try {
    const f = refOfNodeKey(form.fromKey)
    const t = refOfNodeKey(form.toKey)
    const isEdit = formMode.value === 'edit'
    const editing = relations.value.find(r => r.id === editingId.value)
    // 端点是否发生变更：与现有行的 fromRef/toRef 比较（ghost 行 ref=null）
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

/** 两端 ref 是否相同（null 与 null 视为相同——ghost 未变更时不重提） */
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

// ==================== 右键菜单（拓扑右键 CRUD） ====================

/** 关闭右键菜单 */
function closeCtxMenu() {
  ctxMenu.visible = false
  ctxMenu.edge = null
  ctxMenu.nodeKey = ''
}

/** 右键菜单当前节点名（节点右键「以 X 为起点」文案） */
const ctxNodeName = computed(() => {
  const n = nodes.value.find(x => x.id === ctxMenu.nodeKey)
  return n ? n.data.name : ''
})

/** 边右键 → 编辑 */
function ctxEditEdge() {
  const edge = ctxMenu.edge
  closeCtxMenu()
  if (!edge) return
  const row = relations.value.find(r => r.id === edge.data?.id)
  if (row) openEdit(row)
}

/** 边右键 → 删除 */
function ctxDeleteEdge() {
  const edge = ctxMenu.edge
  closeCtxMenu()
  if (!edge) return
  const row = relations.value.find(r => r.id === edge.data?.id)
  if (row) handleDelete(row)
}

/** 节点/画布右键 → 新增关系（节点右键以其为起点） */
function ctxAddRelation() {
  const fromKey = ctxMenu.nodeKey || ''
  closeCtxMenu()
  openCreate(fromKey)
}

// ==================== 关系类型配色（内置常见类型 + 哈希兜底） ====================
const RELATION_PALETTE = {
  '亲属': '#e11d48', '家人': '#e11d48', '父女': '#e11d48', '父子': '#e11d48', '母女': '#e11d48', '母子': '#e11d48',
  '兄弟': '#e11d48', '姐妹': '#e11d48', '兄妹': '#e11d48', '姐弟': '#e11d48', '叔侄': '#e11d48',
  '师徒': '#8b5cf6', '师父': '#8b5cf6', '弟子': '#8b5cf6', '师兄弟': '#8b5cf6', '同门': '#8b5cf6',
  '敌对': '#b91c1c', '死敌': '#b91c1c', '宿敌': '#b91c1c', '仇敌': '#b91c1c', '仇人': '#b91c1c', '对手': '#dc2626', '竞争': '#dc2626',
  '朋友': '#10b981', '挚友': '#10b981', '好友': '#10b981', '知己': '#10b981', '伙伴': '#10b981', '同伴': '#10b981',
  '战友': '#10b981', '盟友': '#059669', '同盟': '#059669',
  '恋人': '#ec4899', '情侣': '#ec4899', '夫妻': '#ec4899', '爱人': '#ec4899', '未婚妻': '#ec4899', '未婚夫': '#ec4899', '暗恋': '#f472b6', '爱慕': '#f472b6',
  '上下级': '#0ea5b7', '上司': '#0ea5b7', '下属': '#0ea5b7', '君臣': '#0ea5b7', '主仆': '#0ea5b7', '主人': '#0ea5b7', '仆从': '#0ea5b7', '雇佣': '#0ea5b7',
  '同事': '#6366f1', '同僚': '#6366f1', '同学': '#6366f1', '同窗': '#6366f1',
  '邻居': '#14b8a6', '同乡': '#14b8a6', '故交': '#14b8a6', '旧识': '#14b8a6', '故友': '#14b8a6',
  '恩人': '#d97706', '恩师': '#d97706', '救命恩人': '#d97706', '贵人': '#d97706'
}
const FALLBACK_PALETTE = ['#0d9488', '#7c3aed', '#f59e0b', '#0891b2', '#db2777', '#65a30d', '#ea580c', '#4f46e5']

function colorForType(type) {
  const t = (type || '').trim()
  if (RELATION_PALETTE[t]) return RELATION_PALETTE[t]
  let h = 0
  for (let i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) >>> 0
  return FALLBACK_PALETTE[h % FALLBACK_PALETTE.length]
}

// ==================== 主题令牌映射 ====================
function readTokens() {
  const root = document.documentElement
  const cs = getComputedStyle(root)
  const v = (name, fb) => (cs.getPropertyValue(name) || '').trim() || fb
  return {
    isDark: root.getAttribute('data-theme') === 'dark',
    accent: v('--accent', '#4176e6'),
    bgLayer1: v('--bg-layer-1', '#ffffff'),
    bgLayer2: v('--bg-layer-2', '#f1f3f7'),
    textPrimary: v('--text-primary', '#17191d'),
    textSecondary: v('--text-secondary', '#61666e'),
    textPlaceholder: v('--text-placeholder', '#b2b6bd'),
    borderL1: v('--border-l1', 'rgba(15,17,21,.16)')
  }
}

function themeKey() {
  const t = readTokens()
  return `${t.isDark ? 'd' : 'l'}:${t.accent}`
}

function parseColor(c) {
  const s = (c || '').trim()
  if (s.startsWith('#')) {
    const h = s.slice(1)
    if (h.length === 3) return h.split('').map(x => parseInt(x + x, 16))
    if (h.length === 6) return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16))
  }
  const m = s.match(/rgba?\(([^)]+)\)/)
  if (m) {
    const p = m[1].split(',').map(x => parseFloat(x.trim()))
    return [p[0] || 0, p[1] || 0, p[2] || 0]
  }
  return [128, 128, 128]
}

function mixColor(a, b, t) {
  const pa = parseColor(a)
  const pb = parseColor(b)
  const c = pa.map((x, i) => Math.round(x + (pb[i] - x) * t))
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`
}

// ==================== 数据加载 ====================
async function load() {
  state.value = 'loading'
  errorMsg.value = ''
  try {
    const data = await fetchCharacterRelations(props.projectId)
    const list = data?.nodes || []
    const rels = data?.relations || []
    if (list.length === 0) {
      state.value = 'no-characters'
      return
    }
    nodes.value = list.map(n => ({
      id: n.id,
      data: {
        id: n.id, name: n.name, kind: n.kind || 'ghost', type: n.type,
        importance: n.importance ?? 3, isProtagonist: n.isProtagonist === 1,
        title: n.title || '', detail: n.detail || '',
        crowdName: n.crowdName || '', occupation: n.occupation || '', state: n.state || 'idle', lastAction: n.lastAction || ''
      }
    }))
    edges.value = (rels || [])
      .filter(r => r.fromKey && r.toKey && r.fromKey !== r.toKey)
      .map(r => ({
        id: `rel-${r.id}`,
        source: r.fromKey,
        target: r.toKey,
        data: {
          id: r.id, fromName: r.fromName, toName: r.toName, relationType: r.relationType || '未知关系',
          description: r.description || '', fromRef: r.fromRef || null, toRef: r.toRef || null,
          affinity: r.affinity ?? 0, trust: r.trust ?? 0,
          relationState: r.relationState || '', relationStateLabel: r.relationStateLabel || '',
          lastInteractionAt: r.lastInteractionAt || null
        }
      }))
    relations.value = rels || []
    Object.keys(typeCount).forEach(k => delete typeCount[k])
    relations.value.forEach(r => { const t = r.relationType || '未知关系'; typeCount[t] = (typeCount[t] || 0) + 1 })

    state.value = 'ready'
    lastLoadedKey = props.refreshKey
    await nextTick()
    if (containerRef.value) rebuildGraph()
    else lastThemeKey = themeKey()
  } catch (e) {
    state.value = 'error'
    errorMsg.value = e.message || '加载关系拓扑失败'
  }
}

// ==================== 派生数据 ====================
/** 图例：关系类型按条数降序 */
const legendTypes = computed(() => Object.keys(typeCount).sort((a, b) => (typeCount[b] || 0) - (typeCount[a] || 0)))
/** 预览弹窗已选条数 */
const checkedCount = computed(() => preview.items.filter(i => i.checked).length)
/** 是否全选 */
const allChecked = computed(() => preview.items.length > 0 && checkedCount.value === preview.items.length)
/** tab 变体当前选中角色名 */
const selectedName = computed(() => {
  const id = props.selectedCharacterId
  if (id == null) return ''
  const n = nodes.value.find(x => x.id === `npc-${id}`)
  return n ? n.data.name : ''
})

/** 该名字是否「幽灵」（角色表/普通人群都没有；kind 存于 node.data 内） */
function isGhostName(name) {
  return !nodes.value.some(n => n.data.name === name && n.data.kind !== 'ghost')
}

/** 人群成员状态中文 */
function stateLabel(s) {
  return ({ idle: '空闲', walk: '行走', stop: '停留', talk: '交谈', rest: '休息' })[s] || s || '—'
}

// ==================== 节点样式（按类型） ====================
function nodeSizeOf(d) {
  return 26 + (d.data.importance || 3) * 4
}

/**
 * 节点基础样式（按 kind 区分：NPC 主色系 / 普通人群 青绿 / 幽灵 灰色虚线）。
 * <p>颜色可见性修复：此前普通 NPC/普通人群/幽灵的填充都向背景色混了 82%~86%，
 * 浅色主题下节点与角色名几乎不可见。现统一加深填充（混背景 45%~50%）、加粗描边（1.2/1.4→2）、
 * 标签全部改用主文本色（幽灵用次级文本色但仍清晰），三类配色体系不变。</p>
 */
function nodeStyleOf(d) {
  const t = readTokens()
  const labelBg = t.isDark ? 'rgba(27,27,28,.9)' : 'rgba(255,255,255,.92)'
  const base = {
    labelText: d.data.name,
    labelPlacement: 'bottom',
    labelOffsetY: 4,
    labelFontSize: 11,
    labelFill: t.textPrimary, // 角色名统一主文本色，深浅主题均清晰可见
    labelFontWeight: 500,
    // v5.1.1 updateStyle 合并缺陷修复：状态样式清除后，仅存在于状态样式（selected/inactive）
    // 而未在基样式声明的属性会残留在元素 attributes 里（见 §32.6）。这里基样式显式声明，
    // 使 inactive 的 opacity 0.45 / labelOpacity 0.75 在清除后必然被基值覆盖还原。
    opacity: 1,
    labelOpacity: 1,
    labelBackground: true,
    labelBackgroundFill: labelBg,
    labelBackgroundRadius: 4,
    labelPadding: [1, 4]
  }
  if (d.data.kind === 'crowd') {
    // 普通人群：青绿色系（填充 82%→45% 加深，描边加深色 2px）
    return { ...base, size: 22, fill: mixColor('#0d9488', t.bgLayer1, 0.45), stroke: '#0f766e', lineWidth: 2 }
  }
  if (d.data.kind === 'ghost') {
    // 幽灵·待补充：灰色系但保证可见（填充 50% 混背景、描边用次级文本色加粗虚线、标签次级文本色）
    return { ...base, size: 24, fill: mixColor(t.textSecondary, t.bgLayer1, 0.5), stroke: t.textSecondary, lineWidth: 2, lineDash: [4, 2], labelFill: t.textSecondary, labelOpacity: 1 }
  }
  // NPC
  const accentStrong = mixColor(t.accent, '#000', 0.22)
  return {
    ...base,
    size: nodeSizeOf(d),
    // 非主角填充 86%→45% 加深（仍有淡染质感但清晰可见）；主角保持实心强调色
    fill: d.data.isProtagonist ? t.accent : mixColor(t.accent, t.bgLayer1, 0.45),
    stroke: d.data.isProtagonist ? accentStrong : t.accent,
    lineWidth: d.data.isProtagonist ? 2.5 : 2,
    labelFontWeight: d.data.isProtagonist ? 600 : 500
  }
}

// ==================== 图构建 ====================
function buildGraph() {
  if (!containerRef.value || state.value !== 'ready') return null
  const el = containerRef.value
  const width = el.clientWidth || 600
  const height = el.clientHeight || 420
  if (width < 10 || height < 10) return null

  const t = readTokens()
  lastThemeKey = themeKey()
  const labelBg = t.isDark ? 'rgba(27,27,28,.9)' : 'rgba(255,255,255,.92)'

  const graph = new Graph({
    container: el,
    width,
    height,
    animation: false,
    data: { nodes: nodes.value, edges: edges.value },
    node: {
      type: 'circle',
      style: nodeStyleOf,
      state: {
        selected: {
          halo: true,
          haloStroke: t.accent,
          haloLineWidth: 14,
          haloOpacity: 0.35,
          stroke: t.accent,
          lineWidth: 2.5
        },
        active: {
          halo: true,
          haloStroke: t.accent,
          haloLineWidth: 10,
          haloOpacity: 0.28,
          stroke: t.accent,
          lineWidth: 2
        },
        inactive: {
          // 可见性：原 0.18/0.35 太暗（关系表为空时选中任意角色其余全暗到不可见）；
          // 放宽到 0.45/0.75，未选中节点仍清晰可见、只是弱化，选中态靠光晕+实心+加粗区分
          opacity: 0.45,
          labelOpacity: 0.75
        }
      }
    },
    edge: {
      type: 'line',
      style: {
        stroke: d => colorForType(d.data.relationType),
        lineWidth: 1.5,
        opacity: 0.85,
        endArrow: true,
        endArrowSize: 7,
        // v5.1.1 updateStyle 合并缺陷修复：selected 状态的 labelText 清除后不会从元素属性中移除，
        // 基样式显式 labelText:''（假值 → 不渲染标签）兜底，保证取消选中后边上文字必然消失。
        labelText: ''
      },
      state: {
        selected: {
          lineWidth: 2.5,
          opacity: 1,
          labelText: d => d.data.relationType,
          labelFontSize: 10,
          labelFill: d => colorForType(d.data.relationType),
          labelFontWeight: 500,
          labelBackground: true,
          labelBackgroundFill: labelBg,
          labelBackgroundRadius: 3,
          labelPadding: [1, 4],
          labelPlacement: 'center'
        },
        active: { lineWidth: 2.5, opacity: 1 },
        inactive: { opacity: 0.25 }
      }
    },
    layout: {
      type: 'force',
      // ⚠ 参数符号： v5 force 布局的 nodeStrength 源码里
      // weight = (factor / coulombDisScale²) × nodeStrength，正值=斥力（默认 1000）、负值=引力。
      // 此前误传 -200 → 所有节点互相吸引 → 全部堆到画布中心；删掉改回默认正值即可正常散开。
      // linkDistance 控制边两端距离；preventOverlap + nodeSize 防重叠。
      linkDistance: 130,
      preventOverlap: true,
      nodeSize: d => (d.data.kind === 'crowd' ? 22 : nodeSizeOf(d)),
      maxIteration: 500
    },
    behaviors: [
      'drag-canvas',
      'zoom-canvas',
      'drag-element',
      { type: 'hover-activate', degree: 1, state: 'active', inactiveState: 'inactive' }
    ],
    plugins: [{ type: 'tooltip', trigger: 'hover', getContent: (event) => tooltipContent(event) }],
    transforms: ['process-parallel-edges'],
    theme: t.isDark ? 'dark' : 'light',
    autoFit: 'view',
    padding: 48,
    zoomRange: [0.15, 3]
  })

  graph.on('node:click', (ev) => {
    const key = String(ev.target.id || '')
    if (key.startsWith('rel-')) return
    const node = nodes.value.find(x => x.id === key)
    if (!node) return
    if (props.variant === 'page') {
      // 全局页：点击节点 → 右上角卡片 + 图内高亮其关联
      pageSelectedKey.value = key
      cardNode.value = node
      applySelection()
    } else if (node.data.kind === 'npc') {
      // 角色页：点击 NPC → 左侧选中该角色（双向联动）
      const id = Number(key.replace('npc-', ''))
      if (!Number.isNaN(id)) emit('select-character', id)
    }
  })

  // 点击空白画布 → 取消选中：全部节点/边恢复清晰显示（修复「选中后无法取消、变暗状态残留」）
  graph.on('canvas:click', () => {
    if (props.variant === 'page') {
      // 全局拓扑页：清内部选中 + 关闭右上角角色卡片 + 恢复全部节点
      pageSelectedKey.value = ''
      cardNode.value = null
      applySelection()
    } else {
      // 角色页 Tab：通知父级取消左侧选中（selectedCharacterId → null → 自动恢复全部节点）
      emit('clear-selection')
    }
  })

  // ：拓扑右键菜单（边=编辑/删除；节点=以该节点为起点新增；画布=新增）
  graph.on('edge:contextmenu', (ev) => {
    const id = String(ev.target?.id || '')
    if (!id.startsWith('rel-')) return
    ev.preventDefault?.()
    ctxMenu.edge = edges.value.find(x => x.id === id) || null
    ctxMenu.nodeKey = ''
    ctxMenu.type = 'edge'
    openCtxAt(ev)
  })
  graph.on('node:contextmenu', (ev) => {
    const key = String(ev.target?.id || '')
    if (!key || key.startsWith('rel-')) return
    ev.preventDefault?.()
    ctxMenu.edge = null
    ctxMenu.nodeKey = key
    ctxMenu.type = 'node'
    openCtxAt(ev)
  })
  graph.on('canvas:contextmenu', (ev) => {
    ev.preventDefault?.()
    ctxMenu.edge = null
    ctxMenu.nodeKey = ''
    ctxMenu.type = 'canvas'
    openCtxAt(ev)
  })

  return graph
}

/**
 * 右键菜单定位：优先取 事件客户端坐标，兜底由画布容器 + 画布坐标换算；
 * 靠近视口边缘时向内收敛避免溢出。
 */
function openCtxAt(ev) {
  let px = 0
  let py = 0
  const c = ev.client
  if (c && (c.x != null || c[0] != null)) {
    px = c.x ?? c[0]
    py = c.y ?? c[1]
  } else if (ev.clientX != null) {
    px = ev.clientX
    py = ev.clientY
  } else if (containerRef.value) {
    const rect = containerRef.value.getBoundingClientRect()
    px = rect.left + (ev.x ?? 0)
    py = rect.top + (ev.y ?? 0)
  }
  ctxMenu.x = Math.max(4, Math.min(px, window.innerWidth - 240))
  ctxMenu.y = Math.max(4, Math.min(py, window.innerHeight - 140))
  ctxMenu.visible = true
}

/** Tooltip 内容（节点按 kind 展示；边展示关系 + 动态字段） */
function tooltipContent(event) {
  const id = String(event.target.id || '')
  if (id.startsWith('rel-')) {
    const e = edges.value.find(x => x.id === id)
    if (!e) return ''
    const d = e.data
    const aff = (d.affinity ?? 0) > 0 ? `+${d.affinity}` : (d.affinity ?? 0)
    return `<div class="topo-tooltip">
      <div class="tt-title">${esc(d.fromName)} → ${esc(d.toName)}</div>
      <div class="tt-row"><span class="tt-k">关系</span><span class="tt-dot" style="background:${colorForType(d.relationType)}"></span>${esc(d.relationType)}</div>
      <div class="tt-row"><span class="tt-k">好感</span><span>${aff}</span><span class="tt-k">信任</span><span>${d.trust ?? 0}</span><span class="tt-k">状态</span><span>${esc(d.relationStateLabel || relStateLabel(d.relationState))}</span></div>
      ${d.description ? `<div class="tt-row"><span class="tt-k">描述</span>${esc(d.description)}</div>` : ''}
      <div class="tt-ghost">右键可编辑/删除 · 好感随互动自动增减</div>
    </div>`
  }
  const n = nodes.value.find(x => x.id === id)
  if (!n) return ''
  const d = n.data
  if (d.kind === 'crowd') {
    return `<div class="topo-tooltip">
      <div class="tt-title">${esc(d.name)}<span class="tt-crowd">普通人群</span></div>
      ${d.crowdName ? `<div class="tt-row"><span class="tt-k">归属</span>${esc(d.crowdName)}</div>` : ''}
      ${d.occupation ? `<div class="tt-row"><span class="tt-k">职业</span>${esc(d.occupation)}</div>` : ''}
      <div class="tt-row"><span class="tt-k">状态</span>${esc(stateLabel(d.state))}</div>
    </div>`
  }
  if (d.kind === 'ghost') {
    return `<div class="topo-tooltip"><div class="tt-title">${esc(d.name)}</div>
      <div class="tt-ghost">关系表引用了该角色，但角色表/普通人群均无此数据。${props.variant === 'page' ? '点击节点后可在右上角卡片「前往补充」。' : ''}</div></div>`
  }
  return `<div class="topo-tooltip">
    <div class="tt-title">${esc(d.name)}${d.isProtagonist ? '<span class="tt-proto">主角</span>' : ''}</div>
    ${d.title ? `<div class="tt-row"><span class="tt-k">头衔</span>${esc(d.title)}</div>` : ''}
    <div class="tt-row"><span class="tt-k">类型</span>${d.type === 'common' ? '普通型' : '特殊型 NPC'}</div>
    <div class="tt-row"><span class="tt-k">重要度</span>${'★'.repeat(d.importance)}${'☆'.repeat(Math.max(0, 5 - d.importance))}</div>
  </div>`
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
}

// ==================== 图生命周期管理 ====================
function destroyGraph() {
  if (graph) {
    try { graph.destroy() } catch (_) { /* 忽略 */ }
    graph = null
  }
}

function rebuildGraph() {
  destroyGraph()
  const g = buildGraph()
  if (!g) return
  graph = g
  graph.render().then(() => {
    if (graph) {
      applySelection()
      graph.fitView({ when: 'always' }, false)
    }
  })
}

/** 高亮中心：page 变体=内部选中节点；tab 变体=父级 selectedCharacterId（npc-<id>） */
function highlightCenter() {
  if (props.variant === 'page') return pageSelectedKey.value
  const id = props.selectedCharacterId
  return id == null ? '' : `npc-${id}`
}

/**
 * 应用选中高亮：中心节点 + 其直接关联节点/边 → selected 状态（光晕+加粗+关系标签），
 * 其余无关节点/边 → inactive 状态（降低透明度变暗），无选中时全部恢复原样。
 * <p>旧实现用「节点 id 集合」去判断边（sel.has(e.id) 恒为 false），
 * 导致选中的直接关联边从不高亮、关系类型标签也不显示；现按「边的两端是否含中心」判断。</p>
 */
function applySelection() {
  if (!graph) return
  const center = highlightCenter()
  const selNodes = new Set() // 高亮节点：中心 + 其直接关联节点
  const selEdges = new Set() // 高亮边：直接连到中心的边
  if (center) {
    selNodes.add(center)
    for (const e of edges.value) {
      if (e.source === center || e.target === center) {
        selEdges.add(e.id)
        selNodes.add(e.source)
        selNodes.add(e.target)
      }
    }
  }
  const hasCenter = !!center
  const stateMap = {}
  for (const n of nodes.value) {
    stateMap[n.id] = hasCenter ? (selNodes.has(n.id) ? ['selected'] : ['inactive']) : []
  }
  for (const e of edges.value) {
    stateMap[e.id] = hasCenter ? (selEdges.has(e.id) ? ['selected'] : ['inactive']) : []
  }
  graph.setElementState(stateMap)
}

function resizeGraph() {
  if (!graph || !containerRef.value) return
  const el = containerRef.value
  const w = el.clientWidth || 600
  const h = el.clientHeight || 420
  if (w < 10 || h < 10) return
  graph.setSize(w, h)
  graph.fitView({ when: 'always' }, false)
}

function setupThemeObserver() {
  if (themeObserver) return
  themeObserver = new MutationObserver(() => {
    if (props.active && state.value === 'ready' && themeKey() !== lastThemeKey) rebuildGraph()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] })
}

function onActivated() {
  if (state.value !== 'ready') return
  if (props.refreshKey !== lastLoadedKey) { load(); return }
  if (!containerRef.value) return
  if (themeKey() !== lastThemeKey) {
    rebuildGraph()
  } else if (graph) {
    resizeGraph()
  } else {
    rebuildGraph()
  }
}

onMounted(async () => {
  await load()
  setupThemeObserver()
  resizeObserver = new ResizeObserver(() => { if (props.active) resizeGraph() })
  if (containerRef.value) resizeObserver.observe(containerRef.value)
  // ：全局点击关闭右键菜单（含菜单自身项点击后的冒泡）
  document.addEventListener('click', closeCtxMenu)
  document.addEventListener('scroll', closeCtxMenu, true)
})

onBeforeUnmount(() => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (themeObserver) { themeObserver.disconnect(); themeObserver = null }
  document.removeEventListener('click', closeCtxMenu)
  document.removeEventListener('scroll', closeCtxMenu, true)
  destroyGraph()
})

watch(() => props.selectedCharacterId, () => { if (graph) applySelection() })
watch(() => props.refreshKey, () => { if (props.active) load() })
watch(() => props.active, (v) => { if (v) onActivated() })

// ==================== AI 关系生成（预览确认后入库） ====================
/**
 * 点击生成：先判定范围是否已有数据 → 有则弹窗选「重建/补充」→ 调 AI 生成预览 → 弹预览确认弹窗。
 *
 * @param scope character（当前选中角色）/ project（全项目）
 */
async function handleGenerate(scope) {
  const hasData = scope === 'project' ? relations.value.length > 0 : charHasRelations()
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

/** 当前选中角色是否已有关系（tab 变体生成按钮的「已有数据」判定） */
function charHasRelations() {
  const id = props.selectedCharacterId
  if (id == null) return false
  const key = `npc-${id}`
  return edges.value.some(e => e.source === key || e.target === key)
}

/** 全选/全不选 */
function toggleAll(v) {
  preview.items.forEach(i => { i.checked = !!v })
}

/** 确认入库：把勾选的关系批量写入，成功后刷新图 */
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
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ reload: load })
</script>

<style scoped>
.relation-topology {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 420px;
}

/* 加载 / 空态 */
.topo-loading { height: 100%; min-height: 300px; }
.topo-empty { height: 100%; min-height: 300px; display: flex; align-items: center; justify-content: center; }
.topo-empty-icon { color: var(--text-placeholder); }

/* 工具栏 */
.topo-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-shrink: 0; }
.topo-count { font-size: 0.75rem; color: var(--text-tertiary); }

/* 无关系数据提示 */
.topo-hint { flex-shrink: 0; }

/* 图例 */
.topo-legend {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding: 8px 12px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-light);
  border-radius: 8px;
  font-size: 0.75rem;
  color: var(--text-secondary);
}
.legend-group { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.legend-label { color: var(--text-tertiary); margin-right: 2px; }
.legend-item { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.legend-dot.npc { background: var(--accent); }
.legend-dot.crowd { background: #0d9488; }
.legend-dot.ghost { background: var(--text-placeholder); border: 1px dashed var(--text-secondary); }
.legend-dot.size { width: 14px; height: 14px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 30%, var(--bg-layer-1)); border: 1px solid var(--accent); }
.legend-none { color: var(--text-placeholder); }
.legend-tips { margin-left: auto; color: var(--text-tertiary); font-size: 0.72rem; }

/* 画布容器（右上角卡片定位基准） */
.topo-canvas-wrap { position: relative; flex: 1; min-height: 0; }
.topo-canvas {
  height: calc(100vh - 500px);
  min-height: 380px;
  max-height: 720px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-layer-1);
  overflow: hidden;
}
/* page 变体：整页容器，画布更高 */
.relation-topology.page .topo-canvas { height: calc(100vh - 260px); min-height: 520px; max-height: none; }

/* 右上角角色卡片 */
.topo-card {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 300px;
  max-height: 70%;
  overflow-y: auto;
  z-index: 20;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: 12px;
  box-shadow: var(--shadow-lv3);
  padding: 12px 14px;
  font-size: 0.8rem;
  color: var(--text-primary);
}
.card-head { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.card-name { font-size: 1rem; font-weight: 700; margin-right: auto; }
.card-close { margin-left: 2px; }
.card-row { display: flex; gap: 8px; align-items: baseline; margin-bottom: 4px; }
.card-row .k { color: var(--text-tertiary); flex-shrink: 0; width: 44px; font-size: 0.72rem; }
.card-row .v { color: var(--text-regular); }
.card-detail { margin-top: 8px; padding: 8px 10px; background: var(--bg-layer-2); border-radius: 8px; font-size: 0.75rem; line-height: 1.7; color: var(--text-regular); white-space: pre-wrap; word-break: break-word; max-height: 180px; overflow-y: auto; }
.card-ghost-tip { color: var(--text-secondary); line-height: 1.7; margin-bottom: 10px; padding: 8px 10px; background: var(--bg-layer-2); border-radius: 8px; font-size: 0.75rem; }

/* 预览确认弹窗 */
.preview-body { min-height: 120px; }
.preview-ops { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.preview-count { font-size: 0.75rem; color: var(--text-tertiary); }
.preview-list { display: flex; flex-direction: column; gap: 6px; max-height: 46vh; overflow-y: auto; }
.preview-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--border-light); border-radius: 8px; font-size: 0.8rem; }
.pv-from, .pv-to { font-weight: 600; white-space: nowrap; }
.pv-from.ghost, .pv-to.ghost { color: var(--text-placeholder); font-weight: 400; text-decoration: underline dotted; }
.pv-arrow { color: var(--text-placeholder); flex-shrink: 0; }
.pv-type { flex-shrink: 0; }
.pv-desc { color: var(--text-secondary); font-size: 0.74rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ==================== 关系管理抽屉 / 编辑弹窗 / 右键菜单 ==================== */
.rel-drawer { display: flex; flex-direction: column; gap: 10px; }
.rel-drawer-ops { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.rel-drawer-hint { font-size: 0.72rem; color: var(--text-tertiary); }
.rel-type { font-weight: 600; font-size: 0.8rem; }
.rel-state { font-size: 0.68rem; color: var(--text-tertiary); margin-top: 1px; }
.rel-aff { font-weight: 600; font-size: 0.78rem; }
.rel-aff.pos { color: #10b981; }
.rel-aff.neg { color: #ef4444; }
.rel-form-hint { margin-left: 8px; font-size: 0.7rem; color: var(--text-tertiary); }

/* 右键菜单（fixed 定位覆盖在画布上方） */
.rel-ctxmenu {
  position: fixed;
  z-index: 3000;
  min-width: 180px;
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
  max-width: 220px;
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

<style>
/* 非 scoped： Tooltip 的 HTML 注入到 body/容器，需全局选择器才能命中 */
.topo-tooltip {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-primary);
  max-width: 260px;
  word-break: break-word;
}
.topo-tooltip .tt-title { font-weight: 600; margin-bottom: 2px; }
.topo-tooltip .tt-proto, .topo-tooltip .tt-crowd { margin-left: 6px; font-size: 10px; color: #fff; border-radius: 3px; padding: 0 4px; font-weight: 500; }
.topo-tooltip .tt-proto { background: var(--accent); }
.topo-tooltip .tt-crowd { background: #0d9488; }
.topo-tooltip .tt-ghost { color: var(--text-secondary); }
.topo-tooltip .tt-row { display: flex; gap: 6px; align-items: baseline; }
.topo-tooltip .tt-k { color: var(--text-tertiary); flex-shrink: 0; }
.topo-tooltip .tt-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; align-self: center; }
</style>
