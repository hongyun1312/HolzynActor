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
  <div class="model-api-view" :class="{ embedded, compact }">
    <!-- 嵌入设置弹窗（embedded）或作为子页内容（compact）时标题区隐藏，由弹窗标题/页头承担，
         但保留右侧「新增供应商」操作区。 -->
    <PageHeader
      :class="{ 'is-embedded-ops': embedded || compact }"
      title="模型供应商"
      :desc="embedded ? '' : pageDesc"
    >
      <template #actions>
        <el-button type="primary" @click="openProviderDialog()">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增供应商
        </el-button>
      </template>
    </PageHeader>

    <!-- 三类模型一眼清：文本 / 向量 / 决策（点一下就地过滤，大供应商里不必翻找某一类）。
         决策模型与文本模型不是同一种调用——它输出概率分布而不是长文本，混在一起最容易填错位置。 -->
    <div class="role-bar">
      <button
        v-for="opt in roleFilterOptions"
        :key="opt.value || 'all'"
        type="button"
        class="role-chip"
        :class="[{ 'is-active': roleFilter === opt.value }, `is-${opt.value || 'all'}`]"
        :title="opt.hint"
        @click="roleFilter = roleFilter === opt.value ? '' : opt.value"
      >
        <span class="role-chip-name">{{ opt.label }}</span>
        <b class="role-chip-count">{{ opt.count }}</b>
      </button>
      <span class="role-bar-hint">{{ roleBarHint }}</span>
    </div>

    <!-- 供应商卡片列表：一个供应商一张卡，卡头是供应商，卡内紧跟它自己的模型（每个一行）。
         旧版是「左侧供应商导航 + 右侧详情表」，弹窗 900px 宽时模型表被压到操作列下重叠（实测截图），
         改成一列卡片后供应商与其模型天然贴在一起，也不再需要"先点左边再找右边"。 -->
    <div class="provider-list" v-loading="loading">
      <section v-for="p in providers" :key="providerKey(p)" class="pv-card">
        <header class="pv-head">
          <button type="button" class="pv-chev-btn" :title="isCollapsed(p) ? '展开模型' : '收起模型'"
            @click="toggleCollapsed(p)">
            <span class="pv-chev" :class="{ open: !isCollapsed(p) }"></span>
          </button>
          <div class="pv-id">
            <span class="pv-name">{{ p.name }}</span>
            <el-tag v-if="p.isDefault === 1" size="small" type="primary" effect="light">默认</el-tag>
            <el-tag v-if="scopeOfRow(p) === 'user' && projectMode" size="small" type="info" effect="plain">用户级</el-tag>
            <el-tag v-if="p.enabled !== 1" size="small" type="warning" effect="plain">已停用</el-tag>
          </div>
          <div class="pv-meta">
            <span class="mono">{{ hostOf(p.baseUrl) }}</span>
            <span class="sep">·</span>
            <span>API Key {{ p.apiKeyMasked || '未设置' }}</span>
            <span class="sep">·</span>
            <span>{{ modelList(p).length }} 个模型</span>
          </div>
          <div class="pv-actions">
            <el-button size="small" :loading="testing === providerKey(p)" @click="handleTestProvider(p)">
              <el-icon><HIcon name="Connection" /></el-icon>&nbsp;测试连接
            </el-button>
            <el-button size="small" @click="openProviderDialog(p)">编辑</el-button>
            <el-dropdown trigger="click" @command="cmd => handleProviderCommand(cmd, p)">
              <button type="button" class="icon-btn" title="更多操作">
                <el-icon><HIcon name="MoreFilled" /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="canSetDefault(p)" command="default">设为默认供应商</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除供应商</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <div v-show="!isCollapsed(p)" class="pv-body">
          <!-- 待导入区：「获取接口模型」成功后在这里展开，不再弹"添加模型"窗。
               每行可选类别（默认按名字猜文本/向量）与上下文容量，勾选后批量添加。 -->
          <div v-if="remoteOpen(p)" class="imp">
            <div class="imp-head">
              <span class="imp-title">
                接口返回 <b>{{ remoteState(p).ids.length }}</b> 个模型<template v-if="remotePending(p)">，待添加 {{ remotePending(p) }}</template>
              </span>
              <span class="imp-hint muted">· 上下文容量可填 128K / 1M，留空表示未知</span>
              <div class="imp-actions">
                <el-button link size="small" @click="toggleAll(p)">{{ allChecked(p) ? '取消全选' : '全选' }}</el-button>
                <el-button size="small" @click="closeRemote(p)">收起</el-button>
                <el-button size="small" type="primary" :loading="importing === providerKey(p)"
                  :disabled="!remoteChecked(p)" @click="handleImport(p)">
                  添加所选<template v-if="remoteChecked(p)"> {{ remoteChecked(p) }}</template>
                </el-button>
              </div>
            </div>
            <div class="imp-rows">
              <div v-for="r in remoteRows(p)" :key="r.name" class="imp-row">
                <el-checkbox v-model="r.checked" />
                <span class="imp-name mono" :title="r.name">{{ r.name }}</span>
                <el-select v-model="r.role" size="small" class="imp-role">
                  <el-option v-for="role in roleOrder" :key="role" :label="ROLE_META[role].label" :value="role" />
                </el-select>
                <el-input v-model="r.ctx" size="small" class="imp-ctx" placeholder="上下文，如 128K" />
              </div>
              <div v-if="!remotePending(p)" class="imp-none muted">接口返回的模型都已在下面了。</div>
            </div>
          </div>

          <!-- 单行模型表：模型 / 类别 / 上下文 / 启用 / 操作（操作收进 ⋯ 菜单，避免与信息重叠） -->
          <div class="pm-legend">
            <span>模型</span>
            <span>类别</span>
            <span>上下文</span>
            <span class="pm-enable">启用</span>
            <span></span>
          </div>
          <div class="pm-rows">
            <div v-for="m in visibleModels(p)" :key="m.id" class="pm-row">
              <span class="pm-name-cell">
                <span class="pm-name mono" :title="m.remark ? m.name + '（' + m.remark + '）' : m.name">{{ m.name }}</span>
                <el-tag v-if="m.isDefault === 1" size="small" type="primary" effect="light" class="pm-def">默认</el-tag>
                <span v-if="modelBusy(p, m)" class="pm-saving">保存中…</span>
              </span>
              <el-select :model-value="m.role || 'text'" size="small" class="pm-role"
                :disabled="!!modelBusy(p, m)" @change="role => handleRoleChange(p, m, role)">
                <el-option v-for="role in roleOrder" :key="role" :label="ROLE_META[role].label" :value="role" />
              </el-select>
              <el-input v-model="ctxDrafts[ctxKey(p, m)]" size="small" class="pm-ctx" placeholder="如 128K"
                :disabled="!!modelBusy(p, m)" @change="text => handleCtxChange(p, m, text)" />
              <el-switch :model-value="m.enabled === 1" size="small" :disabled="!!modelBusy(p, m)"
                @change="val => handleToggleModel(p, m, val)" />
              <el-dropdown trigger="click" @command="cmd => handleModelCommand(cmd, p, m)">
                <button type="button" class="icon-btn" title="更多操作"><span class="dots">···</span></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="test">测试连接</el-dropdown-item>
                    <el-dropdown-item v-if="m.isDefault !== 1" command="default">设为该类别默认</el-dropdown-item>
                    <el-dropdown-item command="edit" divided>编辑名称 / 备注</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除模型</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <div v-if="!modelList(p).length" class="pm-empty muted">
              还没有模型：点下方「获取接口模型」一次导入，或「添加模型」手动填。
            </div>
            <div v-else-if="!visibleModels(p).length" class="pm-empty muted">
              该供应商下没有「{{ roleMeta(roleFilter).label }}」，点上方「全部」查看其它类别。
            </div>
          </div>

          <footer class="pv-foot">
            <el-button size="small" :loading="fetching === providerKey(p)" @click="handleFetchRemote(p)">
              <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;获取接口模型
            </el-button>
            <el-button size="small" type="primary" @click="openModelDialog(null, p)">
              <el-icon><HIcon name="Plus" /></el-icon>&nbsp;添加模型
            </el-button>
            <span class="pv-foot-hint muted">{{ roleSummary(p) }}</span>
          </footer>
        </div>
      </section>

      <div v-if="!providers.length && !loading" class="pv-empty">
        <p class="emp-title">还没有供应商</p>
        <p class="muted">供应商是配置单位：一个供应商填一次 Base URL 与 API Key，其下再挂文本 / 向量 / 决策模型。</p>
        <el-button type="primary" @click="openProviderDialog()">添加第一个供应商</el-button>
      </div>
    </div>

    <!-- 供应商新增/编辑。append-to-body：本组件内嵌在 GlobalSettingsDialog 的 overflow 正文里，
         不逃出去会被裁掉下半截（历史事故）。 -->
    <el-dialog v-model="providerDialog.visible" :title="providerDialog.form.id ? '编辑供应商' : '新增供应商'"
      width="900px" align-center append-to-body class="h-dialog h-dialog-fit" :close-on-click-modal="false">
      <el-form ref="providerFormRef" :model="providerDialog.form" :rules="providerRules" label-width="96px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="providerDialog.form.name" placeholder="如：DeepSeek / 智谱 / 本地网关" maxlength="50" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="providerDialog.form.baseUrl"
            placeholder="OpenAI 兼容地址，如 https://api.deepseek.com/v1" maxlength="255" />
          <div class="form-hint muted">同一供应商下的所有模型共用这个地址与 Key；末尾会自动去掉多余斜杠。</div>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="providerDialog.form.apiKey" type="password" show-password
            :placeholder="providerDialog.form.id ? '留空表示保持原 Key 不变' : '填写该供应商的 API Key'" maxlength="512" />
        </el-form-item>
        <el-form-item label="流式支持">
          <el-switch v-model="providerDialog.form.supportsStream" />
          <span class="form-hint muted">该供应商是否支持 SSE 流式输出（对话页使用）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="providerDialog.form.remark" placeholder="用途 / 计费说明（选填）" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="providerDialog.saving" @click="handleSaveProvider">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型新增/编辑（名称、类别、上下文容量、默认、启用、备注） -->
    <el-dialog v-model="modelDialog.visible" :title="modelDialog.form.id ? '编辑模型' : '添加模型'"
      width="900px" align-center append-to-body class="h-dialog h-dialog-fit" :close-on-click-modal="false">
      <el-form ref="modelFormRef" :model="modelDialog.form" :rules="modelRules" label-width="96px">
        <el-form-item label="模型名" prop="name">
          <div class="model-name-row">
            <el-select v-model="modelDialog.form.name" filterable allow-create default-first-option
              placeholder="选择接口返回的模型，或直接输入模型名" class="model-name-select">
              <el-option v-for="m in selectableDialogModels" :key="m" :label="m" :value="m" />
            </el-select>
            <el-button :loading="dialogFetching" @click="handleFetchInDialog">
              <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;获取列表
            </el-button>
          </div>
          <div class="form-hint muted">「获取列表」读取该供应商接口的 /models；也可以手动输入接口未列出的模型名。</div>
        </el-form-item>
        <el-form-item label="类别" prop="role">
          <el-radio-group v-model="modelDialog.form.role">
            <el-radio v-for="r in roleOrder" :key="r" :value="r">{{ ROLE_META[r].label }}</el-radio>
          </el-radio-group>
          <div class="form-hint muted">{{ roleMeta(modelDialog.form.role).hint }}</div>
        </el-form-item>
        <el-form-item label="上下文容量">
          <div class="ctx-row">
            <el-input v-model="modelDialog.form.contextWindow" placeholder="如 128K / 1M" maxlength="12" />
            <span class="ctx-unit muted">tokens</span>
          </div>
          <div class="form-hint muted">留空表示未知。仅展示与记录，不参与运行时上下文裁剪——输入预算仍由各场景的 inject / budget 配置决定。</div>
        </el-form-item>
        <el-form-item label="默认模型">
          <div class="switch-row">
            <el-switch v-model="modelDialog.form.isDefault" />
            <span class="muted">设为该类别下的默认模型</span>
          </div>
          <div class="form-hint muted">同类别只能有一个默认；不设则自动用第一个启用的。</div>
        </el-form-item>
        <el-form-item label="启用">
          <div class="switch-row">
            <el-switch v-model="modelDialog.form.enabled" />
            <span class="muted">参与运行时选择</span>
          </div>
          <div class="form-hint muted">停用后配置保留，但不会被运行时选中。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="modelDialog.form.remark" placeholder="如：主力对话 / 便宜快速（选填）" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="modelDialog.saving" @click="handleSaveModel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 模型供应商设置页（供应商 → 多模型 → 类别）。
 *
 * <p>职责：用户级/项目级 AI 供应商的列表 / 新增 / 编辑 / 删除 / 设为默认 / 连通性测试，
 * 以及供应商下模型的增删改、类别分配（文本 / 向量 / 决策）、上下文容量标注、默认模型、启停、
 * 从接口拉取全部模型（拉完就地勾选导入，不再弹「添加模型」窗）。</p>
 *
 * <p>归属：projectId 传入=项目级（随 .holzyn 包导入导出），不传=用户级默认；项目空间合并展示
 * 项目级 + 用户级（来源用 _scope 标记，用户级默认仍由全局设置管理）。运行时规则「项目级优先、用户级回退」。</p>
 *
 * <p><b>布局（2026-10 重构）</b>：一列供应商卡片，卡头是供应商（名称 / 默认·用户级标记 / 主机 ·
 * Key 尾 4 位 · 模型数 / 测试·编辑·更多），卡内紧跟它自己的模型行（模型 · 类别 · 上下文 · 启用 · ⋯），
 * 模型行右侧只留一个「⋯」菜单。旧版左右分栏 + 表格 fixed-right 操作列，在 900px 设置弹窗里
 * 操作列会压到类别/备注上（用户实测反馈"模型信息和操作重合"），故废掉表格与分栏。</p>
 *
 * <p><b>上下文容量</b>只是元数据（见 ProviderModelVO#contextWindow）：不参与运行时裁剪，
 * 因为 OpenAI 兼容的 {@code GET /models} 不返回该字段，只能人工填。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchModelApis, createModelApi, updateModelApi, deleteModelApi,
  setDefaultModelApi, testSavedModelApi,
  addProviderModel, updateProviderModel, deleteProviderModel,
  setDefaultProviderModel, fetchRemoteProviderModels, testProviderModel
} from '@/shared/api'
import PageHeader from '@/shared/layout/PageHeader.vue'

const props = defineProps({
  projectId: { type: [String, Number], default: null },
  embedded: { type: Boolean, default: false },
  compact: { type: Boolean, default: false }
})

/** 是否项目空间模式（projectId 非空）：合并展示项目级 + 用户级 */
const projectMode = computed(() => props.projectId != null && String(props.projectId) !== '')

/** 页面描述（嵌入弹窗时不展示） */
const pageDesc = computed(() => projectMode.value
  ? '供应商是配置单位：一个供应商填一次 Base URL 与 Key，其下可添加多个模型并分配类别。本项目空间合并展示「项目级」（优先）与「用户级」（回退，来源已标记）；API Key 加密存储，仅显示末 4 位。'
  : '供应商是配置单位：一个供应商填一次 Base URL 与 Key，其下可添加文本 / 向量 / 决策模型并标注上下文容量。API Key 加密存储，仅显示末 4 位。')

/** 三种类别的展示元数据（顺序即界面顺序） */
const ROLE_META = {
  text: { label: '文本模型', tag: 'primary', hint: '文本＝主 AI：对话 / 角色卡 / 行动 / 事件 / 群聊 / 生成' },
  embedding: { label: '向量模型', tag: 'warning', hint: '向量＝知识库 RAG 与记忆向量化（OpenAI 兼容 /embeddings）' },
  decision: { label: '决策模型', tag: 'success', hint: '决策＝实时决策 / 判定类调用；未配置时自动回退到文本模型' }
}
const roleOrder = ['text', 'embedding', 'decision']

/** 按模型名猜类别（导入时给个合理默认，用户可当场改） */
const EMBEDDING_NAME = /(embed|bge|gte-|m3e|jina|e5-|voyage|rerank)/i

// ===== 列表状态 =====
const list = ref([])
const loading = ref(false)
const testing = ref('')
const fetching = ref('')
const importing = ref('')

/** 待导入面板：供应商 key -> { ids: string[], rows: [{name, checked, role, ctx}] } */
const remote = reactive({})
/** 折叠状态：供应商 key -> true 表示收起 */
const collapsed = reactive({})
/** 上下文容量输入草稿：`${供应商key}:${模型id}` -> 展示串（128K / 1M / 空） */
const ctxDrafts = reactive({})
/** 行内保存中的行：`${供应商key}:${模型id}` */
const rowBusy = ref('')

const providers = computed(() => list.value)

/** 供应商稳定 key（项目级/用户级各用各的，避免 id 撞车） */
function pkey(p) {
  return `${p?._scope || 'user'}:${p?.id}`
}

/** 组件模板里也直接用这个名字 */
function providerKey(p) {
  return pkey(p)
}

/** 供应商下的模型数组（接口可能不返回） */
function modelList(p) {
  return p?.models || []
}

/** 类别展示元数据（非法类别按文本处理） */
function roleMeta(role) {
  return ROLE_META[role] || ROLE_META.text
}

/** 行作用域：user / project */
function scopeOfRow(row) {
  return row._scope === 'user' ? 'user' : 'project'
}

/** 行（或当前页）对应的 projectId：项目级=项目 id，用户级/全局=null */
function rowProjectId(row) {
  return row._projectId !== undefined ? row._projectId : props.projectId
}

/** Base URL 的主机名（卡片摘要用，解析失败退回原文） */
function hostOf(baseUrl) {
  if (!baseUrl) return '—'
  try {
    return new URL(baseUrl).host
  } catch (_) {
    return baseUrl
  }
}

/** 「设为默认」仅非默认、且项目模式下用户级行不可（用户级默认由全局设置管理） */
function canSetDefault(row) {
  return row.isDefault !== 1 && !(projectMode.value && scopeOfRow(row) === 'user')
}

/** 某类别下的模型数（页脚摘要用） */
function countByRole(p, role) {
  return modelList(p).filter(m => (m.role || 'text') === role).length
}

/** 页脚一行摘要："文本 2 · 向量 1 · 决策 0" */
function roleSummary(p) {
  return roleOrder.map(r => `${ROLE_META[r].label.replace('模型', '')} ${countByRole(p, r)}`).join(' · ')
}

// ===== 三类模型过滤（文本 / 向量 / 决策） =====

/** 当前过滤类别；''=全部（默认）。点同一类别再次点击即取消过滤 */
const roleFilter = ref('')

/** 顶部类别条：全部 + 三类，各带当前可见总数 */
const roleFilterOptions = computed(() => {
  const total = providers.value.reduce((n, p) => n + modelList(p).length, 0)
  const opts = [{ value: '', label: '全部模型', count: total, hint: '显示所有类别的模型行' }]
  for (const r of roleOrder) {
    opts.push({
      value: r,
      label: ROLE_META[r].label,
      count: providers.value.reduce((n, p) => n + countByRole(p, r), 0),
      hint: `${ROLE_META[r].label}：${ROLE_META[r].hint}`
    })
  }
  return opts
})

/** 类别条右侧提示：说明过滤状态 + 决策模型未配置时的回退行为 */
const roleBarHint = computed(() => {
  if (!roleFilter.value) return '按类别过滤；决策模型未配置时判定层会回退到文本模型'
  return `仅显示「${roleMeta(roleFilter.value).label}」，再点一次取消过滤`
})

/** 某个供应商下当前应展示的模型行（受类别过滤影响） */
function visibleModels(p) {
  const list = modelList(p)
  if (!roleFilter.value) return list
  return list.filter(m => (m.role || 'text') === roleFilter.value)
}

// ===== 上下文容量：展示串 ↔ tokens =====

/**
 * tokens → 展示串（128000 → 128K；1048576 → 1M；0/空 → 空串）。
 * @param {number|null} n tokens
 * @returns {string} 展示串
 */
function formatCtx(n) {
  if (!n || n <= 0) return ''
  if (n >= 1000000) {
    const v = n / 1000000
    return `${Number.isInteger(v) ? v : v.toFixed(1)}M`
  }
  if (n >= 1000) {
    const v = n / 1000
    return `${Number.isInteger(v) ? v : v.toFixed(1)}K`
  }
  return String(n)
}

/**
 * 展示串 → tokens（支持 128K / 1M / 纯数字；空=未知）。
 * @param {string} text 输入串
 * @returns {number|null} tokens；null 表示格式非法，0 表示清空
 */
function parseCtx(text) {
  if (text === null || text === undefined) return 0
  const raw = String(text).trim()
  if (!raw) return 0
  const m = raw.match(/^([0-9]+(?:\.[0-9]+)?)\s*([kKmM]?)$/)
  if (!m) return null
  const unit = m[2].toLowerCase()
  const mul = unit === 'k' ? 1000 : unit === 'm' ? 1000000 : 1
  const n = Math.round(Number(m[1]) * mul)
  if (!Number.isFinite(n) || n < 0) return null
  return n
}

/** 某模型行的草稿 key */
function ctxKey(p, m) {
  return `${pkey(p)}:${m?.id}`
}

/** 把服务端值同步进草稿（改完、拉完列表都调用；force=true 时覆盖用户正在输的内容） */
function syncCtxDrafts() {
  for (const p of list.value) {
    for (const m of modelList(p)) {
      const k = ctxKey(p, m)
      ctxDrafts[k] = formatCtx(m.contextWindow)
    }
  }
}

/** 该行是否正在保存 */
function modelBusy(p, m) {
  return rowBusy.value && rowBusy.value === ctxKey(p, m) ? rowBusy.value : ''
}

// ===== 折叠 / 待导入面板 =====

function isCollapsed(p) {
  return !!collapsed[pkey(p)]
}

function toggleCollapsed(p) {
  collapsed[pkey(p)] = !collapsed[pkey(p)]
}

function remoteState(p) {
  return remote[pkey(p)]
}

function remoteOpen(p) {
  return !!remote[pkey(p)]
}

/**
 * 待导入行：接口返回但该供应商下还没有的模型（同供应商同名模型不重复导入）。
 * @param {object} p 供应商
 * @returns {Array<object>} 行对象（与 remote[pkey].rows 同一引用，v-model 可直接改）
 */
function remoteRows(p) {
  const st = remote[pkey(p)]
  if (!st) return []
  const existing = new Set(modelList(p).map(m => m.name))
  return st.rows.filter(r => !existing.has(r.name))
}

function remotePending(p) {
  return remoteRows(p).length
}

function remoteChecked(p) {
  return remoteRows(p).filter(r => r.checked).length
}

function allChecked(p) {
  const rows = remoteRows(p)
  return rows.length > 0 && rows.every(r => r.checked)
}

function toggleAll(p) {
  const next = !allChecked(p)
  for (const r of remoteRows(p)) r.checked = next
}

function closeRemote(p) {
  delete remote[pkey(p)]
}

// ===== 加载 =====

/** 加载当前归属的供应商列表（项目模式并行加载项目级 + 用户级并合并）。 */
async function loadList() {
  loading.value = true
  try {
    if (projectMode.value) {
      const [projectList, userList] = await Promise.all([
        fetchModelApis(props.projectId),
        fetchModelApis(null)
      ])
      list.value = [
        ...(projectList || []).map(r => ({ ...r, _scope: 'project', _projectId: props.projectId })),
        ...(userList || []).map(r => ({ ...r, _scope: 'user', _projectId: null }))
      ]
    } else {
      list.value = (await fetchModelApis(null)) || []
    }
    syncCtxDrafts()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// ==================== 供应商弹窗 ====================
const providerFormRef = ref(null)
const providerDialog = reactive({
  visible: false,
  saving: false,
  form: { id: null, name: '', baseUrl: '', apiKey: '', supportsStream: true, remark: '' }
})
let providerScope = props.projectId ?? null

const providerRules = {
  name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
}

/** 打开新增（无参）或编辑（传供应商）弹窗 */
function openProviderDialog(row) {
  Object.assign(providerDialog.form, row
    ? {
        id: row.id, name: row.name, baseUrl: row.baseUrl, apiKey: '',
        supportsStream: row.supportsStream === 1, remark: row.remark || ''
      }
    : { id: null, name: '', baseUrl: '', apiKey: '', supportsStream: true, remark: '' })
  providerScope = row ? rowProjectId(row) : (props.projectId ?? null)
  providerDialog.visible = true
}

/** 保存供应商（Key 留空=保持原值） */
async function handleSaveProvider() {
  await providerFormRef.value.validate()
  if (!providerDialog.form.id && !providerDialog.form.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return
  }
  providerDialog.saving = true
  try {
    const payload = {
      name: providerDialog.form.name,
      baseUrl: providerDialog.form.baseUrl,
      apiKey: providerDialog.form.apiKey,
      supportsStream: providerDialog.form.supportsStream,
      remark: providerDialog.form.remark
    }
    if (providerDialog.form.id) {
      await updateModelApi(providerDialog.form.id, payload, providerScope)
      ElMessage.success('保存成功')
      providerDialog.visible = false
      await loadList()
      return
    }
    const created = await createModelApi(payload, providerScope)
    ElMessage.success('供应商已创建，接着添加模型')
    providerDialog.visible = false
    await loadList()
    const fresh = list.value.find(x => x._scope === (providerScope == null ? 'user' : 'project') && x.id === created?.id)
      || list.value.find(x => x.id === created?.id)
    if (fresh) openModelDialog(null, fresh)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    providerDialog.saving = false
  }
}

/** 供应商整体连通性测试（用默认文本模型发一次最小对话） */
async function handleTestProvider(row) {
  testing.value = pkey(row)
  try {
    const r = await testSavedModelApi(row.id, rowProjectId(row))
    if (r.connected) ElMessage.success(`连接成功（${r.method}，耗时 ${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连接失败')
  } catch (e) {
    ElMessage.error(e.message || '测试失败')
  } finally {
    testing.value = ''
  }
}

/** 设为默认供应商（同归属互斥） */
async function handleSetDefault(row) {
  try {
    await setDefaultModelApi(row.id, rowProjectId(row))
    ElMessage.success(`已将「${row.name}」设为默认供应商`)
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '设置失败')
  }
}

/** 删除供应商（二次确认；其下模型一并删除） */
async function handleDeleteProvider(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除供应商「${row.name}」？其下 ${(row.models || []).length} 个模型会一起删除，且不可恢复。`,
      '删除确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    await deleteModelApi(row.id, rowProjectId(row))
    ElMessage.success('删除成功')
    delete remote[pkey(row)]
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 供应商「⋯」菜单命令 */
function handleProviderCommand(cmd, row) {
  if (cmd === 'default') handleSetDefault(row)
  else if (cmd === 'delete') handleDeleteProvider(row)
}

// ==================== 模型：行内编辑 ====================

/**
 * 行内改参数（类别 / 上下文 / 启停）：整行回传，后端按"null=不改"合并。
 * @param {object} p 供应商
 * @param {object} m 模型行
 * @param {object} patch 要改的字段（role / contextWindow / enabled）
 */
async function patchModel(p, m, patch) {
  const busyKey = ctxKey(p, m)
  if (rowBusy.value) return
  rowBusy.value = busyKey
  try {
    await updateProviderModel(p.id, m.id, {
      name: m.name,
      role: patch.role || m.role || 'text',
      isDefault: m.isDefault === 1,
      enabled: patch.enabled !== undefined ? patch.enabled : m.enabled !== 0,
      remark: m.remark || '',
      contextWindow: patch.contextWindow !== undefined ? patch.contextWindow : (m.contextWindow || 0)
    }, rowProjectId(p))
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
    await loadList()
  } finally {
    rowBusy.value = ''
  }
}

/** 行内改类别 */
async function handleRoleChange(p, m, role) {
  if (!role || role === (m.role || 'text')) return
  await patchModel(p, m, { role })
}

/** 行内改上下文容量（输入串 → tokens；非法输入回退原值并提示） */
async function handleCtxChange(p, m, text) {
  const k = ctxKey(p, m)
  const n = parseCtx(text)
  if (n === null) {
    ElMessage.warning('上下文容量填数字即可，可用 128K / 1M 写法')
    ctxDrafts[k] = formatCtx(m.contextWindow)
    return
  }
  if ((m.contextWindow || 0) === n) {
    ctxDrafts[k] = formatCtx(n)
    return
  }
  await patchModel(p, m, { contextWindow: n })
}

/** 行内启用/停用 */
async function handleToggleModel(p, m, enabled) {
  await patchModel(p, m, { enabled: !!enabled })
}

/** 模型「⋯」菜单命令 */
function handleModelCommand(cmd, p, m) {
  if (cmd === 'test') handleTestModel(p, m)
  else if (cmd === 'default') handleSetDefaultModel(p, m)
  else if (cmd === 'edit') openModelDialog(m, p)
  else if (cmd === 'delete') handleDeleteModel(p, m)
}

// ==================== 模型：弹窗 ====================
const modelFormRef = ref(null)
const modelDialog = reactive({
  visible: false,
  saving: false,
  provider: null,
  form: { id: null, name: '', role: 'text', contextWindow: '', isDefault: false, enabled: true, remark: '' }
})
/** 弹窗内「获取列表」拉到的模型名（只做下拉候选，不自动下单） */
const dialogRemote = ref([])
const dialogFetching = ref(false)

const selectableDialogModels = computed(() => {
  const own = modelList(modelDialog.provider).map(m => m.name)
  return dialogRemote.value.filter(n => !own.includes(n) && n !== modelDialog.form.name)
})

const modelRules = {
  name: [{ required: true, message: '请选择或输入模型名', trigger: 'change' }],
  role: [{ required: true, message: '请选择类别', trigger: 'change' }]
}

/**
 * 打开新增（row=null）或编辑（传模型行）弹窗。
 * @param {object|null} row 模型行
 * @param {object} provider 所属供应商（必须显式传：不再有"当前选中供应商"）
 */
function openModelDialog(row, provider) {
  modelDialog.provider = provider || null
  if (row) {
    Object.assign(modelDialog.form, {
      id: row.id, name: row.name, role: row.role || 'text',
      contextWindow: formatCtx(row.contextWindow),
      isDefault: row.isDefault === 1, enabled: row.enabled !== 0, remark: row.remark || ''
    })
  } else {
    Object.assign(modelDialog.form, {
      id: null, name: '', role: 'text', contextWindow: '',
      isDefault: false, enabled: true, remark: ''
    })
    dialogRemote.value = []
  }
  modelDialog.visible = true
}

/** 弹窗内「获取列表」：只刷新下拉，不重开弹窗、不自动建模型 */
async function handleFetchInDialog() {
  const provider = modelDialog.provider
  if (!provider) return
  dialogFetching.value = true
  try {
    const ids = (await fetchRemoteProviderModels(provider.id, rowProjectId(provider))) || []
    dialogRemote.value = ids
    if (!ids.length) ElMessage.warning('该接口没有返回模型列表，可手动输入模型名')
    else if (!modelDialog.form.name) modelDialog.form.name = ids[0]
    else ElMessage.success(`已获取 ${ids.length} 个模型`)
  } catch (e) {
    ElMessage.error(e.message || '获取模型列表失败')
  } finally {
    dialogFetching.value = false
  }
}

/** 保存模型（新增/编辑） */
async function handleSaveModel() {
  await modelFormRef.value.validate()
  const provider = modelDialog.provider
  if (!provider) return
  const ctx = parseCtx(modelDialog.form.contextWindow)
  if (ctx === null) {
    ElMessage.warning('上下文容量填数字即可，可用 128K / 1M 写法')
    return
  }
  modelDialog.saving = true
  try {
    const payload = {
      name: modelDialog.form.name,
      role: modelDialog.form.role,
      isDefault: modelDialog.form.isDefault,
      enabled: modelDialog.form.enabled,
      remark: modelDialog.form.remark,
      contextWindow: ctx
    }
    if (modelDialog.form.id) {
      await updateProviderModel(provider.id, modelDialog.form.id, payload, rowProjectId(provider))
      ElMessage.success('保存成功')
    } else {
      await addProviderModel(provider.id, payload, rowProjectId(provider))
      ElMessage.success('模型已添加')
    }
    modelDialog.visible = false
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    modelDialog.saving = false
  }
}

/** 设为该类别下的默认模型 */
async function handleSetDefaultModel(p, m) {
  try {
    await setDefaultProviderModel(p.id, m.id, rowProjectId(p))
    ElMessage.success(`「${m.name}」已设为${roleMeta(m.role).label}的默认`)
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '设置失败')
  }
}

/** 测试某个具体模型 */
async function handleTestModel(p, m) {
  try {
    const r = await testProviderModel(p.id, m.id, rowProjectId(p))
    if (r.connected) ElMessage.success(`「${m.name}」连接成功（耗时 ${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连接失败')
  } catch (e) {
    ElMessage.error(e.message || '测试失败')
  }
}

/** 删除模型（二次确认） */
async function handleDeleteModel(p, m) {
  try {
    await ElMessageBox.confirm(`确认删除模型「${m.name}」？`, '删除确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    await deleteProviderModel(p.id, m.id, rowProjectId(p))
    ElMessage.success('删除成功')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// ==================== 获取接口模型 → 待导入列表 ====================

/**
 * 「获取接口模型」：拉到后在该供应商卡片里展开待导入列表（旧版是拉完直接弹"添加模型"窗，
 * 用户反馈"获取成功后还是会重复打开新增页面"——现在不再弹窗）。
 * @param {object} p 供应商
 */
async function handleFetchRemote(p) {
  const key = pkey(p)
  fetching.value = key
  try {
    const ids = (await fetchRemoteProviderModels(p.id, rowProjectId(p))) || []
    const existing = new Set(modelList(p).map(m => m.name))
    // 保留用户已经在这个面板里改过/勾过的行（重复点「获取」不重置选择）
    const prev = remote[key]
    const prevByName = new Map((prev?.rows || []).map(r => [r.name, r]))
    remote[key] = {
      ids,
      rows: ids
        .filter(name => !existing.has(name))
        .map(name => prevByName.get(name) || {
          name,
          checked: true,
          role: guessRole(name),
          ctx: ''
        })
    }
    if (!ids.length) {
      ElMessage.warning('该接口没有返回模型列表，可点「添加模型」手动填')
    } else if (!remotePending(p)) {
      ElMessage.info(`接口返回 ${ids.length} 个模型，都已在列表里了`)
    } else {
      ElMessage.success(`接口返回 ${ids.length} 个模型，其中 ${remotePending(p)} 个待添加`)
    }
  } catch (e) {
    ElMessage.error(e.message || '获取模型列表失败')
  } finally {
    fetching.value = ''
  }
}

/**
 * 按模型名猜类别（只用于导入时的默认值）。
 * @param {string} name 模型名
 * @returns {string} text / embedding
 */
function guessRole(name) {
  return EMBEDDING_NAME.test(name || '') ? 'embedding' : 'text'
}

/**
 * 批量添加勾选的模型（逐个 POST；主键在服务端生成，没有批量端点）。
 * @param {object} p 供应商
 */
async function handleImport(p) {
  const st = remote[pkey(p)]
  if (!st) return
  const picked = remoteRows(p).filter(r => r.checked)
  if (!picked.length) {
    ElMessage.warning('请先勾选要添加的模型')
    return
  }
  importing.value = pkey(p)
  let ok = 0
  let lastError = ''
  for (const r of picked) {
    const ctx = parseCtx(r.ctx)
    if (ctx === null) {
      lastError = `「${r.name}」上下文容量格式不对`
      continue
    }
    try {
      await addProviderModel(p.id, {
        name: r.name,
        role: r.role || 'text',
        isDefault: false,
        enabled: true,
        remark: '',
        contextWindow: ctx
      }, rowProjectId(p))
      ok += 1
      st.rows = st.rows.filter(x => x.name !== r.name)
    } catch (e) {
      lastError = e.message || '添加失败'
    }
  }
  importing.value = ''
  await loadList()
  if (ok) ElMessage.success(`已添加 ${ok} 个模型`)
  if (lastError) ElMessage.error(`部分未添加：${lastError}`)
  // 全部添加成功就收起待导入面板（列表里已经能看到这些模型了，留着只会占地方）；
  // 有失败项时保留面板，方便改完再试。
  if (ok && !lastError) closeRemote(p)
}

onMounted(loadList)
</script>

<style scoped>
.model-api-view { width: 100%; max-width: var(--page-w-content, 1200px); margin: 0 auto; }
.model-api-view.embedded,
.model-api-view.compact { max-width: none; }
.model-api-view.embedded .page-header,
.model-api-view.compact .page-header { margin-bottom: 0; }
.model-api-view.embedded .page-header.is-embedded-ops :deep(.ph-main),
.model-api-view.compact .page-header.is-embedded-ops :deep(.ph-main) { display: none; }
.model-api-view.embedded .page-header.is-embedded-ops,
.model-api-view.compact .page-header.is-embedded-ops { justify-content: flex-end; }

/* ===== 供应商卡片 ===== */
.provider-list { display: flex; flex-direction: column; gap: 12px; margin-top: 12px; min-height: 120px; }
.pv-card {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.pv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  flex-wrap: wrap;
}
.pv-chev-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--text-secondary);
}
/* 折叠箭头用 CSS 雪佛龙：HIcon 的 ArrowDown/ArrowUp 被映射成抽屉图标（AGENTS §8.3.9） */
.pv-chev {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-right: 1.6px solid currentColor;
  border-bottom: 1.6px solid currentColor;
  transform: rotate(-45deg);
  transition: transform 0.15s ease;
  margin-bottom: 2px;
}
.pv-chev.open { transform: rotate(45deg); margin-bottom: -2px; }
.pv-id { display: flex; align-items: center; gap: 6px; min-width: 0; }
.pv-name {
  font-weight: 600;
  color: var(--text-primary);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pv-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1 1 220px;
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-secondary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pv-meta .sep { opacity: 0.5; }
.pv-actions { display: flex; align-items: center; gap: 6px; margin-left: auto; flex-shrink: 0; }
.pv-body { padding: 0 12px 12px; }
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 8px);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  line-height: 1;
}
.icon-btn:hover { color: var(--brand-primary, #165dff); border-color: var(--brand-primary, #165dff); }
.icon-btn .dots { font-size: 0.95rem; letter-spacing: 1px; }

/* ===== 待导入列表 ===== */
/* 待导入区：用比卡片更实的面（--bg-layer-1）+ 品牌色虚线框，避免在浅色玻璃背景上
   一行行输入框和文字被背后的场景图吃掉对比度。 */
.imp {
  margin: 2px 0 10px;
  padding: 8px 10px 6px;
  border: 1px dashed var(--brand-primary, #165dff);
  border-radius: var(--radius-md, 8px);
  background: var(--bg-layer-1);
}
.imp-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.imp-title { font-size: var(--density-font-sm, 0.86rem); color: var(--text-primary); }
.imp-hint { flex: 1 1 200px; }
.imp-actions { display: flex; align-items: center; gap: 6px; margin-left: auto; }
.imp-rows { max-height: 232px; overflow: auto; margin-top: 6px; }
.imp-row {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 108px 132px;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}
.imp-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.84rem; }
.imp-none { padding: 4px 0 2px; }

/* ===== 模型单行表 ===== */
.pm-legend,
.pm-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px 112px 46px 30px;
  align-items: center;
  gap: 8px;
}
.pm-legend {
  padding: 6px 6px 4px;
  font-size: 0.76rem;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-l1);
}
.pm-enable { text-align: center; }
.pm-rows { display: flex; flex-direction: column; }
.pm-row {
  padding: 4px 6px;
  border-radius: var(--radius-md, 8px);
  min-height: 38px;
}
.pm-row:hover { background: var(--bg-layer-2, rgba(0, 0, 0, 0.03)); }
.pm-name-cell { display: flex; align-items: center; gap: 6px; min-width: 0; }
.pm-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.86rem;
  color: var(--text-primary);
}
.pm-def { flex-shrink: 0; }
.pm-saving { flex-shrink: 0; font-size: 0.72rem; color: var(--text-placeholder); }
.pm-role, .pm-ctx { width: 100%; }
.pm-empty { padding: 10px 6px 6px; }

/* ===== 页脚 ===== */
.pv-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border-l1);
  flex-wrap: wrap;
}
.pv-foot-hint { margin-left: auto; color: var(--text-secondary); }

/* ===== 空态 ===== */
.pv-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 16px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
}
.emp-title { margin: 0; font-weight: 600; color: var(--text-primary); }

/* ===== 三类模型过滤条 ===== */
.role-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 2px 0 10px;
}
.role-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-secondary);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: 999px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
}
.role-chip:hover { border-color: var(--border-l2, var(--border-l1)); color: var(--text-primary); }
.role-chip.is-active {
  color: var(--brand-primary, var(--text-primary));
  border-color: var(--brand-primary, var(--border-l2));
  background: var(--bg-layer-2, var(--bg-layer-1));
}
.role-chip-count {
  min-width: 1.2em;
  text-align: center;
  font-weight: 600;
  color: var(--text-primary);
}
.role-chip.is-active .role-chip-count { color: var(--brand-primary, var(--text-primary)); }
/* 过滤条右侧的说明：贴在壁纸背景上时纯文字几乎看不见，给它一个浅底胶囊 */
.role-bar-hint {
  margin-left: auto;
  padding: 3px 10px;
  color: var(--text-secondary);
  background: var(--bg-layer-1);
  border: 1px dashed var(--border-l1);
  border-radius: 999px;
  font-size: var(--density-font-sm, 0.82rem);
}

/* ===== 弹窗内小件 ===== */
.model-name-row { display: flex; gap: 8px; width: 100%; }
.model-name-select { flex: 1; min-width: 0; }
.ctx-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.ctx-row :deep(.el-input) { width: 220px; flex-shrink: 0; }
.ctx-unit { flex-shrink: 0; }
.switch-row { display: flex; align-items: center; gap: 8px; }
/* 表单说明必须独占一行：.el-form-item__content 是 flex 容器，行内 hint 会挤在控件右侧，
   实测「决策模型」radio 与类别说明、上下文输入与其说明会重叠成一条。 */
.form-hint { flex: 1 0 100%; width: 100%; margin-top: 4px; line-height: 1.6; }
.muted { color: var(--text-placeholder); font-size: var(--density-font-sm, 0.82rem); }
.mono { font-family: var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace); }
</style>
