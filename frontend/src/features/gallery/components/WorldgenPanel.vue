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
  <div class="worldgen-panel">
    <!--
      ============================================================
      模式一：表单（题材创意 → 自动建项目 → 创建生成任务）
      ============================================================
    -->
    <template v-if="mode === 'form'">
      <!-- 嵌入向导时页头由向导统一提供（一页一头）；独立使用时面板自带页头 -->
      <PageHeader
        v-if="!props.embedded"
        size="sm"
        title="创意发散生成"
        desc="输入一段题材创意与题材类型，AI 按 6 维链式工作流自动生成完整世界观（核心法则 → 时空地理 → 社会势力 → 历史文明 → 存在基础 → 信息传播），完成后预览确认再落库（覆盖更新保留历史版本）。"
      >
        <template #actions>
          <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="doSubmit">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;开始生成
          </el-button>
        </template>
      </PageHeader>

      <!-- AI API 缺失预警（与文件解析面板同款预检） -->
      <div v-if="apiReady === false" class="api-warn">
        <el-alert type="warning" :closable="false" show-icon
          title="尚未配置 AI API — 创意发散生成需要调用 AI 能力"
          description="请先添加并启用「主 AI 对话」用途的用户级 API，配置完成后返回本页即可继续生成。">
          <template #default>
            <div class="api-warn-ops">
              <el-button size="small" type="primary" @click="goConfigApi">立即去配置 API</el-button>
              <el-button size="small" :loading="apiChecking" @click="checkApiReady">我已配置，刷新检测</el-button>
            </div>
          </template>
        </el-alert>
      </div>

      <div class="block-card tech-card">
        <div class="block-title">生成设定<span class="block-tip">（「开始生成」将先自动创建一个新项目，生成结果写入该项目世界观）</span></div>
        <el-form :model="form" label-width="90px" class="gen-form" @submit.prevent>
          <el-form-item label="项目名称">
            <el-input v-model="form.projectName" placeholder="不填则自动生成：{题材类型}·{题材创意前 20 字}" maxlength="100" />
          </el-form-item>
          <el-form-item label="题材创意" required>
            <el-input v-model="form.premise" type="textarea" :rows="5" maxlength="2000" show-word-limit
              placeholder="一段或多段话描述这个世界。如：灵气循雾脉流转的架空大陆，宗门垄断修炼资源，凡人在雾海中挣扎求生…" />
          </el-form-item>
          <el-form-item label="题材类型" required>
            <el-select v-model="form.genre" placeholder="从固定八类中选择" clearable style="width: 280px">
              <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.label" />
            </el-select>
            <span class="genre-hint">固定八类，决定生成与判定口径（生成后仍可在第 2 步改）</span>
          </el-form-item>
          <el-form-item label="输出语言">
            <el-input v-model="form.language" placeholder="默认中文" maxlength="20" style="width: 200px" />
          </el-form-item>
          <el-form-item label="工作流模板">
            <el-select v-model="form.workflowCode" style="width: 360px" :disabled="workflows.length === 0" placeholder="选择生成工作流">
              <el-option v-for="w in workflows" :key="w.code" :label="`${w.name}（${w.nodeCount} 节点）`" :value="w.code" />
            </el-select>
          </el-form-item>
          <!-- 生成模式——仅世界观（默认） / 完整项目（worldview+character+初始化）
               嵌入新建向导时不提供「完整项目」：向导自身会串接初始化，两个入口并存会重复生成 -->
          <el-form-item v-if="!props.embedded" label="生成模式">
            <el-radio-group v-model="form.mode">
              <el-radio value="worldview_only">仅世界观设定</el-radio>
              <el-radio value="full">完整项目（世界观 + 核心角色 + 初始化）</el-radio>
            </el-radio-group>
            <div class="mode-tip">
              完整项目：确认落库后自动串接「角色工作流（骨架/信念/深层/声线）→ 故事规划（三层力学 + 风格档案）→ 角色建档 → 普通 NPC / 关系 / 时钟 / 知识库」，
              一步创建可运行的完整世界（AI 调用较多，耗时随角色规模增长）。
            </div>
          </el-form-item>
        </el-form>
      </div>

      <!--
        嵌入向导时页头被隐藏，「开始生成」原本是页头里的按钮——必须在面板底部补一个，
        否则嵌入模式下用户找不到提交入口（实测：只有表单，没有任何可点的提交按钮）。
      -->
      <div v-if="props.embedded" class="embed-ops">
        <span class="embed-tip">「开始生成」会先建一个草稿世界，6 维世界观生成完成后回到本页第 2 步，可逐字段修改。</span>
        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="doSubmit">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;开始生成
        </el-button>
      </div>
    </template>

    <!--
      ============================================================
      模式二：任务控制台（节点列表 + SSE 日志 + 失败重试/跳过 + 6 维预览确认落库）
      ============================================================
    -->
    <template v-else-if="mode === 'console'">
      <PageHeader v-if="!props.embedded" size="sm">
        <template #default>
          任务控制台
          <el-tag v-if="taskStatusText" size="small" :type="taskStatusTagType" effect="light" class="status-tag">{{ taskStatusText }}</el-tag>
        </template>
        <template #desc>
          工作流「{{ workflowName }}」· 任务 #{{ taskId }} · 项目「{{ projectName }}」（#{{ projectId }}）
          <template v-if="task"> · Token {{ task.tokenIn || 0 }}/{{ task.tokenOut || 0 }} · 耗时 {{ fmtMs(task.durationMs) }}</template>
        </template>
        <template #actions>
          <el-button v-if="!busy" @click="backToForm">
            <el-icon><HIcon name="ArrowLeft" /></el-icon>&nbsp;返回设定
          </el-button>
        </template>
      </PageHeader>

      <!-- 节点列表：6 维 + checkpoint/render/persist 逐步完成 -->
      <div class="nodes-card tech-card">
        <div class="nodes-title">节点进度<span class="nodes-tip">（6 维链式生成 + 检查点 / 合并渲染 / 落库脚本，实时推进）</span></div>
        <div class="node-grid">
          <div v-for="(n, i) in displayNodes" :key="i" class="node-item" :class="'st-' + (n.status || 'pending')">
            <span class="node-dot"></span>
            <span class="node-label" :title="n.nodeId || ''">{{ n.label || n.nodeId || '等待执行' }}</span>
            <el-tag size="small" :type="nodeTagType(n)" effect="light">{{ nodeTagText(n) }}</el-tag>
            <span v-if="n.retryCount > 0" class="node-retry" title="该节点失败后的尝试次数">重试 {{ n.retryCount }} 次</span>
          </div>
        </div>
      </div>

      <!-- 失败卡片：失败节点 + 错误 + 重试本节点 / 跳过 -->
      <div v-if="failedNodes.length" class="fail-card tech-card">
        <div class="fail-title">任务中断：以下节点执行失败</div>
        <div v-for="fn in failedNodes" :key="fn.nodeId" class="fail-item">
          <div class="fail-head">
            <el-tag size="small" type="danger" effect="light">{{ fn.label }}</el-tag>
            <span class="fail-node-id">（{{ fn.nodeId }}）</span>
            <span class="fail-attempt">已尝试 {{ fn.attemptCount }} 次</span>
            <div class="fail-ops">
              <el-button size="small" type="primary" :loading="busy" :disabled="busy" @click="doRetry(fn)">重试本节点</el-button>
              <el-button size="small" :disabled="busy" @click="doSkip(fn)">跳过</el-button>
            </div>
          </div>
          <div class="fail-error">{{ fn.error || '未知错误' }}</div>
        </div>
        <div class="fail-tip">重试将重新执行该失败节点及其后续节点（已生成产物保留）；跳过将标记该节点后从下一节点继续——注意：跳过某维度后 6 维产物不完整，将无法预览 / 确认落库。</div>
      </div>

      <!-- SSE 日志控制台（复用 WorkflowConsole） -->
      <div class="console-wrap">
        <WorkflowConsole ref="consoleRef" />
      </div>

      <!-- 完成后：6 维产物结构化预览 + 确认落库 -->
      <div v-if="taskDone" class="preview-block">
        <div class="preview-head">
          <div class="preview-title">6 维产物预览<span class="preview-tip">（确认后按映射表写入世界观分节 + 地点表，覆盖更新保留历史版本）</span></div>
          <div class="preview-ops">
            <el-button size="small" :loading="previewLoading" @click="loadPreview">
              <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新预览
            </el-button>
            <el-button v-if="!props.embedded" size="small" type="primary" :loading="confirming" :disabled="!preview" @click="doConfirm">
              <el-icon><HIcon name="Check" /></el-icon>&nbsp;确认落库
            </el-button>
            <el-button v-else size="small" type="primary" :disabled="!preview" @click="emitGenerated">
              <el-icon><HIcon name="Back" /></el-icon>&nbsp;用这份世界观继续（下一步可改）
            </el-button>
          </div>
        </div>

        <!-- V2.0 §5.6：加载态与空态分开表达——加载用 v-loading（进度感），
             不再用「空态框里写正在加载」这种自相矛盾的写法。 -->
        <div v-if="previewLoading && !preview" v-loading="true" class="pv-empty tech-card" />
        <div v-else-if="!preview" class="pv-empty tech-card">
          <EmptyState
            size="page"
            tone="warn"
            icon="ic_public_ring"
            title="预览暂不可用"
            desc="可能某个维度被跳过导致产物不完整，或该任务的数据已被清理。"
            primary-text="重新加载预览"
            @primary="loadPreview"
          />
        </div>
        <template v-else>
          <!-- 核心定调：6 个 essence 定调句 -->
          <div v-if="essenceLines.length" class="pv-card tech-card">
            <div class="pv-title">核心定调（6 个维度 essence 定调句）</div>
            <div v-for="(e, i) in essenceLines" :key="i" class="pv-essence">「{{ e }}」</div>
          </div>

          <!-- 分节卡片：将写入 actor_world_setting -->
          <div class="pv-card tech-card">
            <div class="pv-title">将写入世界观分节</div>
            <div class="pv-grid">
              <div v-for="s in sectionList" :key="s.key" class="pv-cell">
                <div class="pv-label">{{ s.label }}</div>
                <div class="pv-text">{{ s.text || '—' }}</div>
              </div>
            </div>
          </div>

          <!-- 关键地点表：将写入 actor_world_location（按名去重合并） -->
          <div v-if="preview.locations && preview.locations.length" class="pv-card tech-card">
            <div class="pv-title">关键地点（{{ preview.locations.length }} 处，将写入地点表）</div>
            <el-table :data="preview.locations" size="small" border class="pv-table">
              <el-table-column prop="name" label="名称" width="170" />
              <el-table-column prop="type" label="地形 / 类型" width="130" />
              <el-table-column prop="intro" label="简介（特征 / 风险 / 控制势力）" show-overflow-tooltip />
            </el-table>
          </div>

          <!-- 各维度原始产物（JSON 折叠） -->
          <div class="pv-card tech-card">
            <div class="pv-title">各维度原始产物</div>
            <el-collapse class="pv-collapse">
              <el-collapse-item v-for="d in preview.dimensions" :key="d.key" :name="d.key">
                <template #title>
                  <span class="dim-title">{{ d.label }}（{{ d.key }}）</span>
                  <span v-if="d.essence" class="dim-essence">「{{ d.essence }}」</span>
                </template>
                <pre class="dim-json">{{ prettyJson(d.data) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </template>
      </div>
    </template>

    <!--
      ============================================================
      模式三：完整项目生成流水线（：confirm(mode=full) 后订阅 pipeline SSE）
      ============================================================
    -->
    <template v-else>
      <PageHeader size="sm">
        <template #default>
          完整项目生成
          <el-tag v-if="pipelineStatusText" size="small" :type="pipelineStatusTag" effect="light" class="status-tag">{{ pipelineStatusText }}</el-tag>
        </template>
        <template #desc>
          世界观已落库，正在自动生成完整项目：核心角色（骨架/信念/深层/声线）→ 故事规划（三层力学 + 风格档案）→ 角色建档 → 普通 NPC / 关系 / 时钟 / 知识库。
          全程自动，关闭页面不中断（可在任务记录中回看）。
        </template>
      </PageHeader>

      <div class="nodes-card tech-card">
        <div class="nodes-title">生成链路<span class="nodes-tip">（build 世界观 ✅ → 角色工作流 → 故事规划 → 世界初始化 6 步，全程自动）</span></div>
        <div class="node-grid">
          <div v-for="(s, i) in pipelineSteps" :key="i" class="node-item" :class="'st-' + s.status">
            <span class="node-dot"></span>
            <span class="node-label">{{ s.label }}</span>
            <el-tag size="small" :type="pipelineStepTag(s.status)" effect="light">{{ pipelineStepText(s.status) }}</el-tag>
          </div>
        </div>
      </div>

      <div class="console-wrap">
        <WorkflowConsole ref="pipelineConsoleRef" />
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 创意发散生成面板。
 * <p>职责：新建项目页三/四入口之一——输入题材创意 premise + 题材类型 genre + 输出语言，
 * 提交时<b>自动创建新项目</b>（项目名可选输入，否则按「{题材}·{创意前 20 字}」派生）→
 * 创建 worldgen 任务（POST /api/worldgen/tasks）→ 面板内联切换为「任务控制台」：
 * 节点列表（轮询 GET /tasks/{taskId} 刷新 6 维 + checkpoint/render/persist 状态徽标）+
 * WorkflowConsole（SSE log/stage/result/error 实时日志，断线自动重连）+
 * 失败卡片（失败节点 + 错误 + 重试本节点 / 跳过，CAS expectedAttemptCount）+
 * 完成后 6 维产物结构化预览（GET /tasks/{taskId}/preview：essence 定调 / 分节卡片 / 地点表 /
 * 维度 JSON 折叠，参考调试前端 AI 调用详情渲染逻辑）→ 确认落库（POST confirm，防误覆盖）→
 * 跳转项目空间世界详情（/world/:id/world，来源 worldgen 标记可回看）。</p>
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchModelApis, createProject,
  fetchWorldgenWorkflows, createWorldgenTask, fetchWorldgenTask,
  fetchWorldgenPreview, confirmWorldgenTask, retryWorldgenNode, skipWorldgenNode,
  worldgenStream, worldgenPipelineStream,
  fetchWorldOptions
} from '@/shared/api'
import { ensureWorldOpen } from '@/shared/worldSession'
import WorkflowConsole from './WorkflowConsole.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'

const router = useRouter()

// ==================== 表单状态 ====================
const mode = ref('form') //
// ：生成模式（worldview_only 默认=历史行为 / full=完整项目串接）
const form = reactive({ projectName: '', premise: '', genre: '', language: '中文', workflowCode: '', mode: 'worldview_only' })
const workflows = ref([])      // 可用工作流定义（GET /workflows）

/**
 * 题材类型可选项（固定八类）。
 *
 * <p>为什么从后端取：类别在落库时会被 {@code WorldCategory.require()} 严格校验，
 * 前端自己写一份清单迟早与枚举漂移。接口不可用时用兜底清单，保证下拉永远有东西可选。</p>
 */
const CATEGORY_FALLBACK = [
  { code: 'qihuan', label: '奇幻' }, { code: 'kehuan', label: '科幻' },
  { code: 'dushi', label: '都市' }, { code: 'lishi', label: '历史' },
  { code: 'moshi', label: '末世' }, { code: 'xianxia', label: '仙侠' },
  { code: 'richang', label: '日常' }, { code: 'xuanyi', label: '悬疑' }
]
const categories = ref(CATEGORY_FALLBACK)
const apiReady = ref(null)     // AI API 可用性预检
const apiChecking = ref(false)
const submitting = ref(false)

// ==================== 控制台状态 ====================
const taskId = ref(null)
const projectId = ref(null)
const projectName = ref('')
const workflowName = ref('')
const workflowNodeCount = ref(0)
const task = ref(null)         // 最近一次轮询到的任务 VO
const busy = ref(false)        // 节点操作进行中（防连点）
const taskDone = ref(false)    // 任务成功 → 展示预览区
const preview = ref(null)      // 6 维产物预览 VO
const previewLoading = ref(false)
const confirming = ref(false)

const consoleRef = ref(null)
let pollTimer = null           // 节点状态轮询定时器
let streamRunning = false      // SSE 订阅运行标记（防重复订阅）

// ====================：完整项目流水线状态（pipeline 视图） ====================
const pipelineConsoleRef = ref(null)
const pipelineRunning = ref(false)
const pipelineStatusText = ref('')      // 运行中 / 已完成 / 已中断
const pipelineStatusTag = ref('primary')
// 生成链路 5 步
const pipelineSteps = ref([
  { label: '世界观设定（6 维生成 + 落库）', status: 'succeeded' },
  { label: '核心角色生成（character 工作流）', status: 'running' },
  { label: '故事规划（三层力学 + 风格档案）', status: 'pending' },
  { label: '世界初始化 6 步（建档/居民/关系/时钟/知识）', status: 'pending' },
  { label: '世界诞生编年史记录', status: 'pending' }
])

// 分节中文标签（对齐 WorldGenPreviewVO.sections 键）
const SECTION_LABELS = {
  name: '世界观名称', genre: '题材', era: '时代背景', magicSystem: '规则体系',
  geography: '地理设定', factions: '势力阵营', culture: '文化风俗',
  history: '历史背景', freeText: '自由文本（完整世界观）'
}

// ==================== 计算属性 ====================
const canSubmit = computed(() => !!(form.premise.trim() && form.genre.trim()) && !submitting.value)

/** 控制台节点列表：已有节点 + 未开始节点占位（总数 = 工作流 nodeCount） */
const displayNodes = computed(() => {
  const list = (task.value?.nodes || []).slice()
  const total = workflowNodeCount.value || list.length
  for (let i = list.length; i < total; i++) {
    list.push({ nodeId: '', label: '', status: 'pending' })
  }
  return list
})

/** 失败节点（任务失败时展示失败卡片） */
const failedNodes = computed(() => {
  if (!task.value || task.value.status !== 'failed') return []
  return (task.value.nodes || []).filter((n) => n.status === 'failed')
})

const taskStatusText = computed(() => {
  const s = task.value?.status
  if (s === 'running') return '运行中'
  if (s === 'succeeded') return '已完成'
  if (s === 'failed') return '已中断'
  if (s === 'canceled') return '已取消'
  return ''
})
const taskStatusTagType = computed(() => {
  const s = task.value?.status
  if (s === 'succeeded') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'canceled') return 'info'
  return 'primary'
})

/** 6 个维度 essence 定调句（预览头部引用） */
const essenceLines = computed(() =>
  (preview.value?.dimensions || []).map((d) => d.essence).filter(Boolean))

/** 分节卡片列表（键序固定、仅展示有内容的分节） */
const sectionList = computed(() => {
  const secs = preview.value?.sections || {}
  return Object.keys(SECTION_LABELS)
    .filter((k) => secs[k] != null && secs[k] !== '')
    .map((k) => ({ key: k, label: SECTION_LABELS[k], text: secs[k] }))
})

// ==================== 生命周期 ====================
onMounted(() => { loadWorkflows(); checkApiReady(); loadWorldOptions() })
onBeforeUnmount(stopConsole)

/** 拉取固定八类（失败保留兜底清单，绝不因为下拉没数据就拦住用户） */
async function loadWorldOptions() {
  try {
    const opt = await fetchWorldOptions()
    if (opt && Array.isArray(opt.categories) && opt.categories.length) {
      categories.value = opt.categories
    }
  } catch (_) { /* 保留兜底清单 */ }
}

// ==================== 工作流 / API 预检 ====================
/** 加载可用工作流模板，默认选第一个（build_v1） */
async function loadWorkflows() {
  try {
    const list = await fetchWorldgenWorkflows()
    workflows.value = list || []
    if (!form.workflowCode && workflows.value.length) {
      form.workflowCode = workflows.value[0].code
    }
  } catch (_) { workflows.value = [] }
}

/** 判断某供应商是否存在可用的文本模型（新结构看 models 角色，旧结构回退 purpose/embeddingEnabled） */
function isChatCapableApi(a) {
  if (!a) return false
  if (Array.isArray(a.models)) {
    return a.models.some(m => (m.role || 'text') === 'text' && m.enabled !== 0)
  }
  const p = a.purpose
  if (!p) return a.embeddingEnabled !== 1
  return p === 'chat' || p === 'both'
}

/** 预检用户级是否存在可用的主 AI API */
async function checkApiReady() {
  apiChecking.value = true
  try {
    const apis = await fetchModelApis()
    apiReady.value = (apis || []).some(isChatCapableApi)
  } catch (_) { apiReady.value = false }
  finally { apiChecking.value = false }
}

/** 打开全局设置弹窗并定位到「API 配置」Tab（父级通过事件注入） */
function goConfigApi() { emit('open-settings', 'apis') }

/** 请求失败统一处理：命中「未配置 API」时引导去配置 */
function handleApiError(e, fallback) {
  const msg = (e && e.message) ? e.message : fallback
  if (/未配置|没有可用的|请先.*API/.test(msg)) {
    apiReady.value = false
    ElMessageBox.confirm(`${msg}\n\n是否现在去「设置 → API 配置」添加用户级 API？配置后可返回本页继续。`, '需要配置 AI API', {
      confirmButtonText: '去配置 API',
      cancelButtonText: '稍后再说',
      type: 'warning'
    }).then(() => goConfigApi()).catch(() => { /* 用户取消 */ })
  } else {
    ElMessage.error(msg)
  }
}

// ==================== 提交：自动建项目 → 创建任务 → 控制台 ====================
/**
 * 提交生成：① 自动创建新项目（name 必填：用户输入优先，否则按「{题材}·{创意前 20 字}」派生）
 * → ② 创建 worldgen 任务（projectId/workflowCode/inputs）→ ③ 面板内联切控制台模式。
 */
async function doSubmit() {
  if (!form.premise.trim()) return ElMessage.warning('请填写题材创意')
  if (!form.genre.trim()) return ElMessage.warning('请填写题材类型')
  if (!form.workflowCode) return ElMessage.warning('请选择工作流模板')
  submitting.value = true
  try {
    // ① 自动创建项目
    const name = form.projectName.trim() || deriveProjectName()
    const proj = await createProject({
      name,
      code: null,
      summary: `由「创意发散生成」创建：${form.genre.trim()} · ${form.premise.trim().slice(0, 60)}`
    })
    projectId.value = proj?.id
    projectName.value = proj?.name || name
    // 分档存储：worldgen 任务与世界产物都落在世界库里，创建后必须先确保这个世界已挂载
    await ensureWorldOpen(proj.id)
    // ② 创建并异步执行任务
    const res = await createWorldgenTask({
      projectId: proj.id,
      workflowCode: form.workflowCode,
      inputs: {
        premise: form.premise.trim(),
        genre: form.genre.trim(),
        language: form.language.trim() || '中文'
      },
      mode: form.mode === 'full' ? 'full' : 'worldview_only'
    })
    taskId.value = res.taskId
    const wf = workflows.value.find((w) => w.code === form.workflowCode)
    workflowName.value = wf?.name || form.workflowCode
    workflowNodeCount.value = wf?.nodeCount || 0
    // ③ 切控制台并开始（SSE + 轮询）
    mode.value = 'console'
    await nextTick()
    startConsole()
  } catch (e) {
    handleApiError(e, '提交失败')
  } finally {
    submitting.value = false
  }
}

/** 自动派生项目名：{题材类型}·{题材创意前 20 字}（按码点截断防截半；总长 ≤ 100） */
function deriveProjectName() {
  const g = (form.genre.trim() || '世界观').slice(0, 50)
  const p = form.premise.trim().replace(/\s+/g, '')
  const head = Array.from(p).slice(0, 20).join('') || '创意发散'
  return `${g}·${head}`
}

// ==================== 控制台：SSE + 轮询 ====================
function startConsole() {
  stopPolling()
  subscribeStream()
  startPolling()
}

/** 订阅任务 SSE 进度流（log/stage/result/error，断线且任务仍在运行则自动重连——SSE 回放补齐日志） */
async function subscribeStream() {
  if (!taskId.value || streamRunning) return
  streamRunning = true
  consoleRef.value?.clear()
  consoleRef.value?.setRunning(true)
  consoleRef.value?.push({ level: 'info', time: now(), message: `[世界观生成] 任务开始：项目「${projectName.value}」，工作流「${workflowName.value}」（任务 #${taskId.value}）` })
  try {
    await worldgenStream(taskId.value, {
      onLog: (log) => consoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message }),
      onStage: () => { /* 阶段由日志体现；进度条用于初始化页 */ },
      onResult: () => { /* 收尾由流结束后的任务刷新处理 */ }
    })
    streamRunning = false
    consoleRef.value?.setRunning(false)
    // 流正常结束（result 事件）→ 刷新任务收尾
    const t = await refreshTask()
    if (t && t.status === 'succeeded') {
      taskDone.value = true
      loadPreview()
    }
  } catch (e) {
    streamRunning = false
    consoleRef.value?.setRunning(false)
    consoleRef.value?.push({ level: 'error', time: now(), message: `[世界观生成] 进度流中断：${e.message || '连接失败'}` })
    const t = await refreshTask()
    // 任务仍在运行（断线 / 重试发起后）→ 自动重连订阅（SSE 回放补齐日志）；终态（failed）不重连
    if (t && t.status === 'running') {
      setTimeout(subscribeStream, 800)
    }
  }
}

/** 轮询任务详情（每 2s 刷新节点状态；终态自动停止） */
function startPolling() {
  stopPolling()
  pollTimer = setInterval(refreshTask, 2000)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

/** 刷新任务详情；终态（succeeded/failed/canceled）停止轮询；成功时触发预览加载 */
async function refreshTask() {
  if (!taskId.value) return task.value
  try {
    const t = await fetchWorldgenTask(taskId.value)
    task.value = t
    if (t && (t.status === 'succeeded' || t.status === 'failed' || t.status === 'canceled')) {
      stopPolling()
    }
    if (t && t.status === 'succeeded' && !taskDone.value) {
      taskDone.value = true
      loadPreview()
    }
    return t
  } catch (_) { return task.value }
}

/** 停止控制台（返回表单/离开页面时清理定时器与订阅标记） */
function stopConsole() {
  stopPolling()
  streamRunning = false
}

/** 返回表单模式（保留已填内容，可再次生成） */
function backToForm() {
  stopConsole()
  mode.value = 'form'
  task.value = null
  taskId.value = null
  projectId.value = null
  projectName.value = ''
  workflowName.value = ''
  taskDone.value = false
  preview.value = null
  resetPipeline()
}

/** 重置完整项目流水线状态（返回表单/重新开始） */
function resetPipeline() {
  pipelineRunning.value = false
  pipelineStatusText.value = ''
  pipelineStatusTag.value = 'primary'
  pipelineSteps.value = [
    { label: '世界观设定（6 维生成 + 落库）', status: 'succeeded' },
    { label: '核心角色生成（character 工作流）', status: 'running' },
    { label: '故事规划（三层力学 + 风格档案）', status: 'pending' },
    { label: '世界初始化 6 步（建档/居民/关系/时钟/知识）', status: 'pending' },
    { label: '世界诞生编年史记录', status: 'pending' }
  ]
}

// ==================== 失败节点：重试 / 跳过（CAS） ====================
/** 重试失败节点：重新执行该节点及其后续节点（已生成产物保留） */
async function doRetry(node) {
  try {
    await ElMessageBox.confirm(
      `确认重试节点「${node.label}」（${node.nodeId}）？\n\n将重新执行该失败节点及其后续节点；已生成的产物会保留，可查看日志确认。`,
      '重试本节点', { confirmButtonText: '重试', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (_) { return }
  busy.value = true
  try {
    const t = await retryWorldgenNode(taskId.value, node.nodeId, node.attemptCount)
    task.value = t
    taskDone.value = false
    preview.value = null
    consoleRef.value?.push({ level: 'info', time: now(), message: `[世界观生成] 已发起重试：节点「${node.label}」（期望尝试 ${node.attemptCount}）` })
    startPolling()
    subscribeStream()
  } catch (e) {
    handleApiError(e, '重试失败')
  } finally {
    busy.value = false
  }
}

/** 跳过失败节点：标记 skipped 后从下一节点继续（注意：跳过维度将导致 6 维不完整，无法预览/落库） */
async function doSkip(node) {
  try {
    await ElMessageBox.confirm(
      `确认跳过节点「${node.label}」（${node.nodeId}）？\n\n该节点将被标记为「已跳过」，从下一节点继续执行。⚠ 若跳过的是某个维度，6 维产物将不完整，完成后无法预览 / 确认落库。`,
      '跳过节点', { confirmButtonText: '跳过', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (_) { return }
  busy.value = true
  try {
    const t = await skipWorldgenNode(taskId.value, node.nodeId, node.attemptCount)
    task.value = t
    taskDone.value = false
    preview.value = null
    consoleRef.value?.push({ level: 'info', time: now(), message: `[世界观生成] 已发起跳过：节点「${node.label}」（期望尝试 ${node.attemptCount}）` })
    startPolling()
    subscribeStream()
  } catch (e) {
    handleApiError(e, '跳过失败')
  } finally {
    busy.value = false
  }
}

// ==================== 6 维产物预览 / 确认落库 ====================
/** 加载 6 维产物预览（不写库；维度缺失时后端 400 拒绝防残缺） */
async function loadPreview() {
  if (!taskId.value) return
  previewLoading.value = true
  try {
    preview.value = await fetchWorldgenPreview(taskId.value)
  } catch (e) {
    preview.value = null
    ElMessage.warning(e.message || '预览加载失败（可能因某维度被跳过而不完整）')
  } finally {
    previewLoading.value = false
  }
}

/** 确认落库（覆盖更新保留历史版本，幂等防误覆盖）→ 仅世界观跳转世界详情 / 完整模式进入流水线视图 */
async function doConfirm() {
  const full = form.mode === 'full'
  try {
    await ElMessageBox.confirm(
      full
        ? `确认将本次生成的 6 维世界观写入项目「${projectName.value}」，并自动生成完整项目（核心角色 / 建档 / 普通 NPC / 关系 / 时钟 / 知识库）？\n\n完整模式 AI 调用较多，全程自动；确认后将合并 ${preview.value?.locations?.length || 0} 处地点到地点表。`
        : `确认将本次生成的 6 维世界观写入项目「${projectName.value}」？\n\n将覆盖该项目现有世界观分节（覆盖更新、保留历史版本），并合并 ${preview.value?.locations?.length || 0} 处地点到地点表。`,
      '确认落库', { confirmButtonText: full ? '确认并生成完整项目' : '确认落库', cancelButtonText: '再想想', type: 'info' }
    )
  } catch (_) { return }
  confirming.value = true
  try {
    const out = await confirmWorldgenTask(taskId.value)
    if (full && out.pipelineStarted) {
      // 完整模式：进入流水线视图（订阅 pipeline SSE 展示串接进度）
      ElMessage.success('世界观已落库，开始生成完整项目（角色/关系/时钟/知识）…')
      mode.value = 'pipeline'
      await nextTick()
      startPipeline()
      return
    }
    ElMessage.success(`已落库：世界观分节更新，地点新增 ${out.locationAdded} 处`)
    // 跳转项目空间世界详情（来源 worldgen 标记可回看）
    router.push(`/world/${projectId.value}/world`)
  } catch (e) {
    handleApiError(e, '落库失败')
  } finally {
    confirming.value = false
  }
}

// ====================：完整项目流水线（pipeline SSE） ====================

/**
 * 订阅完整项目流水线进度（key=build 任务 ID）：log/stage/result/error。
 * 阶段推进：
 * 日志「开始故事规划」→ 角色步 done + 故事步 running；「故事规划完成/未完成」→ 故事步 done；
 * 收到首个初始化 stage → 初始化步 running；流正常结束（result）→ 全步 done + 跳转世界详情；
 * error → 标记中断并提示手动补齐路径。
 */
async function startPipeline() {
  if (!taskId.value || pipelineRunning.value) return
  pipelineRunning.value = true
  pipelineStatusText.value = '运行中'
  pipelineStatusTag.value = 'primary'
  pipelineConsoleRef.value?.clear()
  pipelineConsoleRef.value?.setRunning(true)
  pipelineConsoleRef.value?.push({ level: 'info', time: now(), message: `[完整项目] 已确认落库，开始串接：角色工作流 → 故事规划 → 世界初始化 6 步（build 任务 #${taskId.value}）` })
  try {
    await worldgenPipelineStream(taskId.value, {
      onLog: (log) => {
        pipelineConsoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message })
        const msg = (log && log.message) || ''
        // 角色落库完成 → 角色步 done（故事规划紧随其后）
        if (msg.includes('角色落库完成') && pipelineSteps.value[1].status !== 'succeeded') {
          pipelineSteps.value[1].status = 'succeeded'
          pipelineSteps.value[2].status = 'running'
        }
        // 故事规划开始/完成/未完成 → 故事步状态推进
        // （失败不阻塞世界创建，但 UI 须如实显示「未完成（可补跑）」而非「已完成」）
        if (msg.includes('开始故事规划')) {
          pipelineSteps.value[2].status = 'running'
        }
        if (msg.includes('故事规划完成')) {
          pipelineSteps.value[2].status = 'succeeded'
          pipelineSteps.value[3].status = 'running'
        }
        if (msg.includes('故事规划未完成')) {
          pipelineSteps.value[2].status = 'warn'
          pipelineSteps.value[3].status = 'running'
        }
      },
      onStage: () => {
        // 收到初始化阶段事件 → 初始化步运行中（故事规划已收尾）
        if (pipelineSteps.value[3].status !== 'succeeded') {
          pipelineSteps.value[3].status = 'running'
        }
      }
    })
    pipelineRunning.value = false
    pipelineConsoleRef.value?.setRunning(false)
    pipelineStatusText.value = '已完成'
    pipelineStatusTag.value = 'success'
    // 故事步保留实际状态（succeeded=已完成 / warn=未完成可补跑），不再强制置为已完成
    pipelineSteps.value[3].status = 'succeeded'
    pipelineSteps.value[4].status = 'succeeded'
    const storyDone = pipelineSteps.value[2].status === 'succeeded'
    ElMessage.success(storyDone
      ? '完整项目已生成：世界观 + 核心角色 + 故事规划 + 居民 + 关系 + 时钟 + 知识库全部就绪'
      : '完整项目已生成（故事规划未完成、不阻塞运行，可到世界详情「生成故事规划」补跑）：世界观 + 核心角色 + 居民 + 关系 + 时钟 + 知识库已就绪')
    router.push(`/world/${projectId.value}/world`)
  } catch (e) {
    pipelineRunning.value = false
    pipelineConsoleRef.value?.setRunning(false)
    pipelineStatusText.value = '已中断'
    pipelineStatusTag.value = 'danger'
    const msg = (e && e.message) ? e.message : '连接中断'
    pipelineConsoleRef.value?.push({ level: 'error', time: now(), message: `[完整项目] 流水线中断：${msg}` })
    ElMessageBox.confirm(
      `完整项目生成中断：${msg}\n\n已生成部分保留。可返回本项目空间手动补齐：\n① 角色任务（任务控制台）\n② 故事规划（世界详情「生成故事规划」）\n③ 「世界初始化」按钮\n④ 重试「确认落库」续跑串接。`,
      '生成中断', { confirmButtonText: '去项目空间', cancelButtonText: '留在本页', type: 'warning' }
    ).then(() => router.push(`/world/${projectId.value}/world`)).catch(() => { /* 留在本页 */ })
  }
}

/** 流水线步骤徽标类型 */
function pipelineStepTag(s) {
  if (s === 'succeeded') return 'success'
  if (s === 'running') return 'primary'
  if (s === 'warn') return 'warning'
  return 'info'
}
/** 流水线步骤徽标文本 */
function pipelineStepText(s) {
  if (s === 'succeeded') return '已完成'
  if (s === 'running') return '进行中'
  if (s === 'warn') return '未完成'
  return '等待'
}

// ==================== 工具 ====================
/** 节点状态 → 徽标类型 */
function nodeTagType(n) {
  const s = n.status
  if (s === 'succeeded') return 'success'
  if (s === 'running') return 'primary'
  if (s === 'failed') return 'danger'
  if (s === 'skipped') return 'info'
  return 'info'
}
/** 节点状态 → 徽标文本 */
function nodeTagText(n) {
  const s = n.status
  if (s === 'succeeded') return '已完成'
  if (s === 'running') return '执行中'
  if (s === 'failed') return '失败'
  if (s === 'skipped') return '已跳过'
  return '等待执行'
}

/** 耗时格式化 */
function fmtMs(ms) {
  if (ms == null) return ''
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

/** 维度 JSON 美化输出 */
function prettyJson(obj) {
  try { return JSON.stringify(obj, null, 2) } catch (_) { return String(obj) }
}

/** 当前时间 HH:mm:ss */
function now() {
  return new Date().toTimeString().slice(0, 8)
}

const emit = defineEmits(['open-settings', 'generated'])

/**
 * 嵌入模式（新建世界向导的「创意」步）：生成完毕后不在这里落库跳转，
 * 而是把产物交给父级向导 → 父级把分节回填到「与手动填写同一份」的世界设定表单里让用户二次修改，
 * 再决定「直接进入世界」还是「继续世界初始化」（两条路径都会先落库）。
 */
const props = defineProps({
  /** true=由父级向导接管后续（隐藏「确认落库」，改为「用这份世界观继续」） */
  embedded: { type: Boolean, default: false }
})

/**
 * 把已生成好的 6 维产物交回父级向导。
 *
 * <p>为什么不是直接 confirm 落库：需求要求"生成完成后回填到可编辑表单"，而落库要等用户改完；
 * 落库动作（confirmWorldgenTask）由父级在保存时统一触发，保证"用户最终看到的就是落库的"。</p>
 */
function emitGenerated() {
  if (!preview.value) {
    ElMessage.warning('预览尚未就绪，请稍等或点「刷新预览」')
    return
  }
  emit('generated', {
    taskId: taskId.value,
    projectId: projectId.value,
    projectName: projectName.value,
    preview: preview.value,
    sections: preview.value?.sections || {}
  })
}
</script>

<style scoped>
/* V2.0 批次二：1100/820 硬编码 → 全站栅格令牌（新建项目向导内的面板，取正文/表单档） */
/*
 * width:100% 不能省：面板是新建向导（flex 纵向容器）的直接子元素，
 * 只写 `margin: 0 auto` 会让 auto 外边距吃掉全部剩余空间、把面板压成内容宽度
 * （实测 1200 的容器里面板只有 502 宽，右侧一大片空白）。
 */
.worldgen-panel { width: 100%; max-width: var(--page-w-content, 1200px); margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
/* 页头（size="sm"）样式由全局类提供 */
.status-tag { margin-left: 8px; vertical-align: middle; }
.api-warn { }
.api-warn-ops { margin-top: 10px; display: flex; gap: 8px; }
.block-card { padding: 18px 22px; }
.block-title { font-weight: 700; color: var(--text-primary); margin-bottom: 12px; }
.block-tip { font-size: 0.75rem; color: var(--text-tertiary); font-weight: 400; margin-left: 8px; }
.gen-form { max-width: var(--page-w-form, 720px); }
/* 下拉旁的补充说明：与 label 同排、不换行到下一行（下拉宽 280 固定） */
.genre-hint { margin-left: 10px; font-size: 0.78rem; color: var(--text-regular); }
.mode-tip { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.6; margin-top: 4px; max-width: 620px; }

/* ===== 节点列表 ===== */
.nodes-card { padding: 14px 18px; }
.nodes-title { font-weight: 700; color: var(--text-primary); margin-bottom: 12px; font-size: 0.9rem; }
.nodes-tip { font-size: 0.72rem; color: var(--text-tertiary); font-weight: 400; margin-left: 8px; }
.node-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 8px; }
.node-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 10px;
  border: 1px solid var(--border-l1); background: var(--bg-layer-2); min-width: 0;
}
.node-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.node-label { font-size: 0.85rem; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.node-retry { font-size: 0.7rem; color: var(--text-tertiary); flex-shrink: 0; }
.node-item.st-succeeded { border-color: var(--success-border, #67c23a); }
.node-item.st-succeeded .node-dot { background: #67c23a; }
.node-item.st-running { border-color: var(--accent-border); }
.node-item.st-running .node-dot { background: var(--accent); animation: wg-pulse 1.2s infinite; }
.node-item.st-failed { border-color: var(--danger-border, #f56c6c); }
.node-item.st-failed .node-dot { background: #f56c6c; }
.node-item.st-skipped { opacity: 0.7; }
.node-item.st-skipped .node-dot { background: var(--text-tertiary); }
.node-item.st-pending { opacity: 0.55; }
.node-item.st-warn { border-color: var(--warning-border, #e6a23c); }
.node-item.st-warn .node-dot { background: #e6a23c; }
.node-item.st-pending .node-dot { background: var(--border-l2); }
@keyframes wg-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }

/* ===== 失败卡片 ===== */
.fail-card { padding: 14px 18px; border-color: var(--danger-border, #f56c6c); }
.fail-title { font-weight: 700; color: var(--danger-text, #f56c6c); margin-bottom: 10px; font-size: 0.9rem; }
.fail-item { border: 1px solid var(--danger-border, #f56c6c); border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; background: var(--bg-layer-2); }
.fail-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fail-node-id { font-size: 0.75rem; color: var(--text-tertiary); }
.fail-attempt { font-size: 0.75rem; color: var(--text-secondary); }
.fail-ops { margin-left: auto; display: flex; gap: 8px; }
.fail-error { font-size: 0.82rem; color: var(--danger-text, #f56c6c); margin-top: 8px; line-height: 1.6; word-break: break-all; white-space: pre-wrap; }
.fail-tip { font-size: 0.75rem; color: var(--text-secondary); line-height: 1.6; }

/* ===== 日志控制台 ===== */
.console-wrap { height: 420px; }

/* ===== 预览区 ===== */
.preview-block { display: flex; flex-direction: column; gap: 12px; }
.preview-head { display: flex; justify-content: space-between; align-items: center; }
.preview-title { font-weight: 700; color: var(--text-primary); font-size: 0.95rem; }
.preview-tip { font-size: 0.72rem; color: var(--text-tertiary); font-weight: 400; margin-left: 8px; }
.preview-ops { display: flex; gap: 8px; }

/* 嵌入向导时的底部操作条（页头被隐藏后唯一的提交入口） */
.embed-ops { display: flex; align-items: center; gap: 12px; padding: 2px 2px 8px; }
/*
 * 提示文字不能裸放在页面底纹上：这一层是液态玻璃画布，越往下越暗（实测底部 rgb ~74,80,88），
 * 深色小字压上去等于看不见。给个近白药丸底，任何滚动位置都保持可读。
 */
.embed-tip {
  font-size: 0.82rem; color: #3f4a56; margin-right: auto; line-height: 1.6;
  background: #fdfefe; border: 1px solid rgba(0, 0, 0, 0.06); border-radius: 8px; padding: 7px 12px;
}
/* 未填必填项时按钮是 disabled 态，主题里白字压浅绿几乎看不清，这里统一成可读的灰底深字 */
.embed-ops :deep(.el-button.is-disabled) {
  color: #7b8794 !important;
  background: #eef1f3 !important;
  border-color: #d9dee3 !important;
}
.pv-empty { padding: 20px 0; min-height: 120px; }
.pv-card { padding: 16px 20px; }
.pv-title { font-weight: 700; color: var(--text-primary); margin-bottom: 10px; font-size: 0.88rem; }
.pv-essence { font-size: 0.85rem; color: var(--text-regular); line-height: 1.7; padding: 2px 0; }
.pv-essence::before { content: ''; }
.pv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.pv-cell { min-width: 0; }
.pv-label { font-size: 0.75rem; color: var(--text-secondary); font-weight: 600; margin-bottom: 4px; }
.pv-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.7; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow-y: auto; }
.pv-table { width: 100%; }
.pv-collapse :deep(.el-collapse-item__header) { font-size: 0.85rem; }
.dim-title { font-weight: 600; color: var(--text-primary); }
.dim-essence { font-size: 0.75rem; color: var(--text-secondary); margin-left: 10px; }
.dim-json {
  background: var(--bg-layer-3); border: 1px solid var(--border-l1); border-radius: 8px;
  padding: 10px 12px; font-family: 'JetBrains Mono', 'HarmonyOS Sans SC', Consolas, monospace;
  font-size: 12px; line-height: 1.6; color: var(--text-regular); overflow-x: auto; max-height: 320px; overflow-y: auto;
  white-space: pre-wrap; word-break: break-all;
}
</style>
