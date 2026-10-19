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
  <div class="world-detail-view">
    <!-- 头部：名称 + 修改 + 版本标签 + worldgen 来源标记 -->
    <PageHeader desc="项目世界观设定（只读全文展示），修改走专属编辑页；保存为覆盖更新（不产生历史版本记录）。">
      <template #default>
        {{ world?.name || '世界详情' }}
        <el-tag v-if="world" size="small" effect="plain" class="version-tag">v{{ world.version || 1 }}</el-tag>
        <!-- worldgen 生成来源标记（可点击弹只读预览回看） -->
        <el-tag v-if="world?.worldgenTaskId" size="small" type="success" effect="light"
          class="source-tag" @click="openWorldgenPreview" title="点击回看生成产物预览">
          worldgen 生成 · 回看
        </el-tag>
      </template>
      <template #actions>
        <!-- character 工作流「生成角色」入口（按世界观自动生成核心角色，落 actor_profile） -->
        <el-button :loading="charGenStarting" @click="startCharacterGen">
          <el-icon><HIcon name="User" /></el-icon>&nbsp;生成角色
        </el-button>
        <!-- story-plan 工作流：「生成故事规划」入口（三层力学引擎 + 风格档案 + 世界氛围） -->
        <el-button :loading="storyGenStarting" @click="startStoryGen">
          <el-icon><HIcon name="Document" /></el-icon>&nbsp;生成故事规划
        </el-button>
        <el-button type="primary" @click="$router.push(`/project/${projectId}/world/edit`)">
          <el-icon><HIcon name="Edit" /></el-icon>&nbsp;修改
        </el-button>
      </template>
    </PageHeader>

    <!-- 世界观全文区（只读，无输入框） -->
    <div v-if="!world" class="empty-card tech-card">
      <EmptyState
        size="page"
        icon="ic_public_file"
        title="这个项目还没有世界观设定"
        desc="世界观决定角色会说什么、世界如何运转。可以从一段文字开始写，也可以让 AI 依据题材生成。"
        primary-text="创建世界观"
        @primary="$router.push(`/project/${projectId}/world/edit`)"
      />
    </div>
    <div v-else class="world-body">
      <!-- 左侧导航分块查看：各部分不再平铺，避免页面过长 -->
      <PageTabs v-model="activeSection" position="left">
        <el-tab-pane label="基本信息" name="basic">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">名称</div>
              <div class="wc-value">{{ world.name }}</div>
            </div>
            <el-divider />
            <div class="wc-grid">
              <div class="wc-cell">
                <div class="wc-label">题材</div>
                <div class="wc-md-value markdown-body" v-html="renderMarkdown(world.genre || '—')"></div>
              </div>
              <div class="wc-cell">
                <div class="wc-label">时代背景</div>
                <div class="wc-md-value markdown-body" v-html="renderMarkdown(world.era || '—')"></div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="地理设定" name="geography">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">地理设定</div>
              <div v-if="world.geography" class="wc-md markdown-body" v-html="renderMarkdown(world.geography)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 地点详情：世界观地点表（地理设定下，AI 提取 + 修改增删改查） -->
        <el-tab-pane label="地点详情" name="locations">
          <div class="world-card tech-card">
            <div class="wc-title-row">
              <span class="wc-title">世界观地点表</span>
              <el-tag size="small" type="info" effect="plain">从地理设定提取 · 对话所在地候选</el-tag>
            </div>
            <div class="loc-hint">由 AI 从「地理设定」文本识别地点并生成简介（也可在「新建项目解析」时自动提取）；
              点击「修改」进入增删改查；此处地点将作为对话创建「对话所在地」的候选下拉来源。</div>
            <WorldLocationTable :project-id="projectId" editable extractable />
          </div>
        </el-tab-pane>

        <el-tab-pane label="势力阵营" name="factions">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">势力阵营</div>
              <div v-if="world.factions" class="wc-md markdown-body" v-html="renderMarkdown(world.factions)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="规则体系" name="magic">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">规则体系</div>
              <div v-if="world.magicSystem" class="wc-md markdown-body" v-html="renderMarkdown(world.magicSystem)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文化风俗" name="culture">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">文化风俗</div>
              <div v-if="world.culture" class="wc-md markdown-body" v-html="renderMarkdown(world.culture)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="历史背景" name="history">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">历史背景</div>
              <div v-if="world.history" class="wc-md markdown-body" v-html="renderMarkdown(world.history)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="完整世界观" name="freeText">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">自由文本（完整世界观）</div>
              <div v-if="world.freeText" class="wc-md markdown-body" v-html="renderMarkdown(world.freeText)"></div>
              <div v-else class="wc-text">—</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="项目大事记" name="memories">
          <div class="world-card tech-card">
            <div class="wc-title-row">
              <span class="wc-title">项目级记忆（世界大事记）</span>
              <el-tag size="small" type="info" effect="plain">{{ projectMemories.length }} 条</el-tag>
            </div>
            <div class="mem-hint">由对话抽取 + 世界模拟自动生成，所有角色知晓。此区与「知识库 · 项目大事记」同源。</div>
            <div v-loading="memLoading" class="mem-list">
              <div v-if="projectMemories.length === 0" class="mem-empty">暂无项目级记忆</div>
              <div v-for="m in projectMemories" :key="m.id" class="mem-item">
                <div class="mem-head">
                  <el-tag size="small" :type="m.subtype === 'summary' ? 'primary' : 'info'" effect="plain">
                    {{ m.subtype === 'summary' ? '摘要' : '事实' }}
                  </el-tag>
                  <el-tag size="small" :type="m.importance >= 4 ? 'danger' : (m.importance >= 3 ? 'warning' : 'info')" effect="light">
                    重要度 {{ m.importance }}
                  </el-tag>
                  <span class="mem-time">{{ (m.createdAt || '').slice(0, 16).replace('T', ' ') }}</span>
                  <el-button class="mem-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
                    <el-icon><HIcon name="Delete" /></el-icon>
                  </el-button>
                </div>
                <div class="mem-content">{{ m.content }}</div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- story-plan 工作流：故事规划展示区（三层力学引擎 + 风格档案 + 历史版本切换） -->
        <el-tab-pane label="故事规划" name="storyPlan">
          <div class="world-card tech-card">
            <div class="wc-title-row">
              <span class="wc-title">故事宏观规划（导演视角档案）</span>
              <el-tag v-if="storyPlans.length" size="small" type="info" effect="plain">{{ storyPlans.length }} 个版本</el-tag>
              <span class="story-tip">世界叙事力学与风格档案——只定义「力的性质与规律」，不定义具体事件；角色不感知此档案（零 OOC）。其「世界氛围」已注入环境描述 / 宏观叙事 / 世界事件。</span>
            </div>
            <div v-loading="storyLoading" class="story-body">
              <EmptyState
                v-if="storyPlans.length === 0"
                size="page"
                icon="ic_public_albums"
                title="还没有故事宏观规划"
                desc="它定义世界叙事「力的性质与规律」（世界氛围、冲突倾向），不定义具体事件——角色不感知，因此不会造成 OOC。"
                :points="['按世界观 + 现有角色自动生成', '只影响世界氛围与事件倾向', '可保留多个版本对比']"
                primary-text="生成故事规划"
                @primary="startStoryGen"
              />
              <template v-else>
                <div class="story-versions">
                  <el-radio-group v-model="storyVersionIdx" size="small">
                    <el-radio-button v-for="(p, i) in storyPlans" :key="p.id" :value="i">
                      v{{ p.version }}<template v-if="i === 0">（最新）</template>
                    </el-radio-button>
                  </el-radio-group>
                  <span class="story-version-time">{{ (currentStoryPlan?.createdAt || '').slice(0, 16).replace('T', ' ') }}</span>
                </div>
                <el-collapse v-model="storyOpen" class="story-collapse">
                  <el-collapse-item name="engine" title="故事引擎（三层力学）">
                    <div class="story-md" v-html="mdToHtml(currentStoryPlan?.storyPlanMd || '')"></div>
                  </el-collapse-item>
                  <el-collapse-item name="style" title="风格档案（世界风格宪法）">
                    <div class="story-md" v-html="mdToHtml(currentStoryPlan?.styleProfileMd || '（风格档案未生成）')"></div>
                  </el-collapse-item>
                  <el-collapse-item name="atmosphere" title="世界氛围片段（已注入运行）">
                    <div class="story-atm">{{ currentStoryPlan?.atmosphereSegment || '（无氛围片段）' }}</div>
                  </el-collapse-item>
                </el-collapse>
              </template>
            </div>
          </div>
        </el-tab-pane>
      </PageTabs>
    </div>

    <!-- worldgen 生成产物只读预览 -->
    <el-dialog v-model="wgDialog" title="worldgen 生成预览（只读）" width="760px" top="6vh">
      <div v-loading="wgLoading" class="wg-body">
        <div v-if="!wgPreview && !wgLoading" class="wg-empty">
          <EmptyState
            size="inline"
            title="预览不可用——可能某个维度被跳过，或任务数据已清理"
          />
        </div>
        <template v-if="wgPreview">
          <div class="wg-sec">
            <div class="wg-sec-title">核心定调（6 个维度 essence 定调句）</div>
            <div v-for="(e, i) in wgEssence" :key="i" class="wg-essence">「{{ e }}」</div>
          </div>
          <div class="wg-sec">
            <div class="wg-sec-title">分节内容</div>
            <div v-for="s in wgSections" :key="s.key" class="wg-cell">
              <span class="wg-label">{{ s.label }}</span>
              <span class="wg-text">{{ s.text }}</span>
            </div>
          </div>
          <div v-if="wgPreview.locations && wgPreview.locations.length" class="wg-sec">
            <div class="wg-sec-title">关键地点（{{ wgPreview.locations.length }} 处）</div>
            <el-table :data="wgPreview.locations" size="small" border>
              <el-table-column prop="name" label="名称" width="150" />
              <el-table-column prop="type" label="地形 / 类型" width="110" />
              <el-table-column prop="intro" label="简介（特征 / 风险 / 控制势力）" show-overflow-tooltip />
            </el-table>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="wgDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- character 工作流「生成角色」任务进度弹窗（轮询任务状态 + 完成/失败提示） -->
    <el-dialog v-model="charGenDialog" title="角色生成任务" width="640px" :close-on-click-modal="false">
      <div v-loading="charGenStarting" class="cg-body">
        <div v-if="charGenTask" class="cg-desc">
          工作流「{{ charGenTask.workflowName }}」· 任务 #{{ charGenTask.id }} ·
          状态
          <el-tag size="small" :type="charGenStatusTag" effect="light">{{ charGenStatusText }}</el-tag>
          <template v-if="charGenTask.tokenIn != null"> · Token {{ charGenTask.tokenIn }}/{{ charGenTask.tokenOut }}</template>
        </div>
        <div v-if="charGenTask" class="cg-nodes">
          <div v-for="(n, i) in charGenNodes" :key="i" class="cg-node" :class="'st-' + (n.status || 'pending')">
            <span class="node-dot"></span>
            <span class="node-label">{{ n.label || n.nodeId || '等待执行' }}</span>
            <el-tag size="small" :type="charGenNodeTag(n)" effect="light">{{ charGenNodeText(n) }}</el-tag>
            <span v-if="n.retryCount > 0" class="node-retry">重试 {{ n.retryCount }} 次</span>
          </div>
        </div>
        <div v-if="charGenTask && charGenTask.status === 'failed'" class="cg-fail">
          <el-alert type="error" :closable="false" show-icon
            title="角色生成失败" :description="(charGenTask.nodes || []).filter((x) => x.status === 'failed').map((x) => `${x.label}：${x.error || ''}`).join('；') || '任务失败，详见服务端日志'" />
        </div>
        <div v-if="charGenTask && charGenTask.status === 'succeeded'" class="cg-done">
          <el-alert type="success" :closable="false" show-icon
            title="角色已生成并落库"
            description="核心角色已按世界观自动生成并写入角色列表（骨架/信念/深层/声线已作为建档锚定）。如需补齐角色档案、关系拓扑与世界时钟，请运行「世界初始化」。" />
        </div>
      </div>
      <template #footer>
        <el-button v-if="charGenTask && charGenTask.status === 'succeeded'" type="primary" @click="goWorldInit">
          去世界初始化
        </el-button>
        <el-button @click="charGenDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- story-plan 工作流：「生成故事规划」任务进度弹窗（轮询任务状态 + 完成/失败提示） -->
    <el-dialog v-model="storyGenDialog" title="故事规划生成任务" width="640px" :close-on-click-modal="false">
      <div v-loading="storyGenStarting" class="cg-body">
        <div v-if="storyGenTask" class="cg-desc">
          工作流「{{ storyGenTask.workflowName }}」· 任务 #{{ storyGenTask.id }} ·
          状态
          <el-tag size="small" :type="storyGenStatusTag" effect="light">{{ storyGenStatusText }}</el-tag>
          <template v-if="storyGenTask.tokenIn != null"> · Token {{ storyGenTask.tokenIn }}/{{ storyGenTask.tokenOut }}</template>
        </div>
        <div v-if="storyGenTask" class="cg-nodes">
          <div v-for="(n, i) in storyGenNodes" :key="i" class="cg-node" :class="'st-' + (n.status || 'pending')">
            <span class="node-dot"></span>
            <span class="node-label">{{ n.label || n.nodeId || '等待执行' }}</span>
            <el-tag size="small" :type="storyGenNodeTag(n)" effect="light">{{ storyGenNodeText(n) }}</el-tag>
            <span v-if="n.retryCount > 0" class="node-retry">重试 {{ n.retryCount }} 次</span>
          </div>
        </div>
        <div v-if="storyGenTask && storyGenTask.status === 'failed'" class="cg-fail">
          <el-alert type="error" :closable="false" show-icon
            title="故事规划生成失败"
            :description="(storyGenTask.nodes || []).filter((x) => x.status === 'failed').map((x) => `${x.label}：${x.error || ''}`).join('；') || '任务失败，可在任务控制台重试/跳过节点后续跑'" />
        </div>
        <div v-if="storyGenTask && storyGenTask.status === 'succeeded'" class="cg-done">
          <el-alert type="success" :closable="false" show-icon
            title="故事规划已生成并落库"
            description="故事引擎（三层力学）+ 风格档案 + 世界氛围片段已写入档案（导演视角，角色不感知）。世界氛围将自动注入环境描述 / 宏观叙事 / 世界事件。" />
        </div>
      </div>
      <template #footer>
        <el-button v-if="storyGenTask && storyGenTask.status === 'succeeded'" type="primary" @click="goStoryPlanTab">
          查看故事规划
        </el-button>
        <el-button @click="storyGenDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 世界详情页。
 * <p>职责：完整呈现项目世界观设定（只读全文渲染，不使用输入框）+ 项目级记忆（世界大事记）；
 * 头部提供「修改」入口（跳专属修改页 /world/edit）。保存为覆盖更新（后端不产生历史版本记录），
 * 版本标签 v{version} 仅展示当前版本号；世界观版本化存取留待后续阶段。</p>
 * <p>数据来源：/api/projects/{id}/world-setting、memories。</p>
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchWorldSetting, fetchMemories, deleteMemory, fetchWorldgenPreview,
  createWorldgenTask, fetchWorldgenTask, fetchStoryPlans
} from '@/shared/api'
import WorldLocationTable from '@/shared/components/WorldLocationTable.vue'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
// 世界观设定文本 Markdown 渲染（MarkdownIt，配合全局 .markdown-body 排版）
import { renderMarkdown } from '@/shared/markdown'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const world = ref(null)
const projectMemories = ref([])
const memLoading = ref(false)
// 左侧导航当前分块（基本信息/地理设定/地点详情/势力阵营/规则体系/文化风俗/历史背景/完整世界观/项目大事记）
const activeSection = ref('basic')

// =====：worldgen 来源标记回看（只读预览弹窗） =====
const wgDialog = ref(false)
const wgLoading = ref(false)
const wgPreview = ref(null)
// 分节中文标签（对齐 WorldGenPreviewVO.sections 键）
const WG_SECTION_LABELS = {
  name: '世界观名称', genre: '题材', era: '时代背景', magicSystem: '规则体系',
  geography: '地理设定', factions: '势力阵营', culture: '文化风俗',
  history: '历史背景', freeText: '自由文本（完整世界观）'
}

/** 打开 worldgen 生成产物只读预览（GET /api/worldgen/tasks/{taskId}/preview） */
async function openWorldgenPreview() {
  const taskId = world.value?.worldgenTaskId
  if (!taskId) return
  wgDialog.value = true
  wgLoading.value = true
  wgPreview.value = null
  try {
    wgPreview.value = await fetchWorldgenPreview(taskId)
  } catch (e) {
    wgPreview.value = null
    ElMessage.warning(e.message || '预览加载失败')
  } finally {
    wgLoading.value = false
  }
}

/** 预览 essence 定调句列表 */
const wgEssence = computed(() =>
  (wgPreview.value?.dimensions || []).map((d) => d.essence).filter(Boolean))

/** 预览分节列表（键序固定、仅展示有内容的分节） */
const wgSections = computed(() => {
  const secs = wgPreview.value?.sections || {}
  return Object.keys(WG_SECTION_LABELS)
    .filter((k) => secs[k] != null && secs[k] !== '')
    .map((k) => ({ key: k, label: WG_SECTION_LABELS[k], text: secs[k] }))
})

/** 加载世界观全文 + 项目级记忆 + 故事规划 */
async function loadAll() {
  try { world.value = await fetchWorldSetting(projectId) } catch (_) { world.value = null }
  memLoading.value = true
  try {
    const page = await fetchMemories(projectId, { page: 1, size: 100 })
    projectMemories.value = (page.list || []).filter((m) => !m.characterId)
  } catch (_) { projectMemories.value = [] }
  finally { memLoading.value = false }
  loadStoryPlans()
}

/** 删除项目级记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条记忆？删除后不再注入所有角色对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    projectMemories.value = projectMemories.value.filter((x) => x.id !== m.id)
  } catch (_) { /* 用户取消 */ }
}

// ====================：character 工作流「生成角色」 ====================
const charGenDialog = ref(false)
const charGenStarting = ref(false)
const charGenTask = ref(null)
let charPollTimer = null

/** 节点列表（含未开始占位） */
const charGenNodes = computed(() => {
  const list = (charGenTask.value?.nodes || []).slice()
  const total = charGenTask.value?.workflowNodeCount || list.length
  for (let i = list.length; i < total; i++) {
    list.push({ nodeId: '', label: '', status: 'pending' })
  }
  return list
})

const charGenStatusText = computed(() => {
  const s = charGenTask.value?.status
  if (s === 'running') return '运行中'
  if (s === 'succeeded') return '已完成'
  if (s === 'failed') return '已中断'
  if (s === 'canceled') return '已取消'
  return ''
})
const charGenStatusTag = computed(() => {
  const s = charGenTask.value?.status
  if (s === 'succeeded') return 'success'
  if (s === 'failed') return 'danger'
  return 'primary'
})

/**
 * 启动角色生成：确认 → 创建 character 工作流任务（世界设定由后端自动注入）→ 弹窗轮询进度。
 * 完成后提示：建档/关系/时钟需「世界初始化」补齐（character 只产角色行 + 深度锚定文本）。
 */
async function startCharacterGen() {
  try {
    await ElMessageBox.confirm(
      `将按本项目世界观自动生成核心角色（角色阵容 + 逐角色深度档案：骨架/信念/深层/声线）并写入角色列表。\n\n生成后如需补齐角色档案、关系拓扑与世界时钟，请再运行「世界初始化」。`,
      '生成角色', { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch (_) { return }
  charGenStarting.value = true
  charGenDialog.value = true
  charGenTask.value = null
  try {
    const res = await createWorldgenTask({
      projectId,
      workflowCode: 'worldgen_character_v1',
      inputs: {},
      mode: 'worldview_only'
    })
    ElMessage.success('角色生成任务已启动')
    await refreshCharTask(res.taskId)
    startCharPolling(res.taskId)
  } catch (e) {
    charGenTask.value = null
    ElMessage.error(e.message || '角色生成任务创建失败')
  } finally {
    charGenStarting.value = false
  }
}

/** 轮询角色生成任务（每 2s；终态停止） */
function startCharPolling(taskId) {
  stopCharPolling()
  charPollTimer = setInterval(() => refreshCharTask(taskId), 2000)
}
function stopCharPolling() {
  if (charPollTimer) { clearInterval(charPollTimer); charPollTimer = null }
}
async function refreshCharTask(taskId) {
  try {
    const t = await fetchWorldgenTask(taskId)
    charGenTask.value = t
    if (t && (t.status === 'succeeded' || t.status === 'failed' || t.status === 'canceled')) {
      stopCharPolling()
    }
    return t
  } catch (_) { return charGenTask.value }
}

/** 角色生成完成 → 去世界初始化 */
function goWorldInit() {
  stopCharPolling()
  charGenDialog.value = false
  router.push(`/project/${projectId}/init`)
}

/** 节点徽标 */
function charGenNodeTag(n) {
  const s = n.status
  if (s === 'succeeded') return 'success'
  if (s === 'running') return 'primary'
  if (s === 'failed') return 'danger'
  return 'info'
}
function charGenNodeText(n) {
  const s = n.status
  if (s === 'succeeded') return '已完成'
  if (s === 'running') return '执行中'
  if (s === 'failed') return '失败'
  if (s === 'skipped') return '已跳过'
  return '等待执行'
}

// ==================== story-plan 工作流（_06）：「生成故事规划」 + 展示区 ====================
const storyGenDialog = ref(false)
const storyGenStarting = ref(false)
const storyGenTask = ref(null)
let storyPollTimer = null
const storyPlans = ref([])
const storyLoading = ref(false)
const storyVersionIdx = ref(0)
const storyOpen = ref(['engine', 'style'])

/** 当前选中的故事规划版本（默认最新=索引 0） */
const currentStoryPlan = computed(() => storyPlans.value[storyVersionIdx.value] || null)

/** 故事规划节点列表（含未开始占位） */
const storyGenNodes = computed(() => {
  const list = (storyGenTask.value?.nodes || []).slice()
  const total = storyGenTask.value?.workflowNodeCount || list.length
  for (let i = list.length; i < total; i++) {
    list.push({ nodeId: '', label: '', status: 'pending' })
  }
  return list
})

const storyGenStatusText = computed(() => {
  const s = storyGenTask.value?.status
  if (s === 'running') return '运行中'
  if (s === 'succeeded') return '已完成'
  if (s === 'failed') return '已中断'
  if (s === 'canceled') return '已取消'
  return ''
})
const storyGenStatusTag = computed(() => {
  const s = storyGenTask.value?.status
  if (s === 'succeeded') return 'success'
  if (s === 'failed') return 'danger'
  return 'primary'
})

/** 加载故事规划版本链（GET /api/worldgen/projects/{id}/story-plans） */
async function loadStoryPlans() {
  storyLoading.value = true
  try {
    storyPlans.value = await fetchStoryPlans(projectId)
    storyVersionIdx.value = 0
  } catch (_) { storyPlans.value = [] }
  finally { storyLoading.value = false }
}

/**
 * 启动故事规划生成：确认 → 创建 story-plan 工作流任务（世界设定 + 角色摘要由后端自动注入）
 * → 弹窗轮询进度；完成后刷新展示区。
 */
async function startStoryGen() {
  // V2.0：重入保护（空态按钮不再带 loading 视觉禁用，连点会叠出多个确认框与任务）
  if (storyGenStarting.value) return
  try {
    await ElMessageBox.confirm(
      `将按本项目世界观与核心角色自动生成「故事宏观规划」：三层力学引擎（显性层/引擎层/兑现层）+ 风格档案（世界风格宪法）。\n\n产出为导演视角档案（角色不感知，零 OOC），其「世界氛围片段」将注入环境描述 / 宏观叙事 / 世界事件。`,
      '生成故事规划', { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch (_) { return }
  storyGenStarting.value = true
  storyGenDialog.value = true
  storyGenTask.value = null
  try {
    const res = await createWorldgenTask({
      projectId,
      workflowCode: 'worldgen_story_plan_v1',
      inputs: {},
      mode: 'worldview_only'
    })
    ElMessage.success('故事规划生成任务已启动')
    await refreshStoryTask(res.taskId)
    startStoryPolling(res.taskId)
  } catch (e) {
    storyGenTask.value = null
    ElMessage.error(e.message || '故事规划任务创建失败')
  } finally {
    storyGenStarting.value = false
  }
}

/** 轮询故事规划任务（每 2s；终态停止并刷新展示区） */
function startStoryPolling(taskId) {
  stopStoryPolling()
  storyPollTimer = setInterval(() => refreshStoryTask(taskId), 2000)
}
function stopStoryPolling() {
  if (storyPollTimer) { clearInterval(storyPollTimer); storyPollTimer = null }
}
async function refreshStoryTask(taskId) {
  try {
    const t = await fetchWorldgenTask(taskId)
    storyGenTask.value = t
    if (t && (t.status === 'succeeded' || t.status === 'failed' || t.status === 'canceled')) {
      stopStoryPolling()
      if (t.status === 'succeeded') loadStoryPlans()
    }
    return t
  } catch (_) { return storyGenTask.value }
}

/** 故事规划完成 → 切到「故事规划」分块查看 */
function goStoryPlanTab() {
  stopStoryPolling()
  storyGenDialog.value = false
  activeSection.value = 'storyPlan'
}

/** 故事规划节点徽标 */
function storyGenNodeTag(n) {
  const s = n.status
  if (s === 'succeeded') return 'success'
  if (s === 'running') return 'primary'
  if (s === 'failed') return 'danger'
  return 'info'
}
function storyGenNodeText(n) {
  const s = n.status
  if (s === 'succeeded') return '已完成'
  if (s === 'running') return '执行中'
  if (s === 'failed') return '失败'
  if (s === 'skipped') return '已跳过'
  return '等待执行'
}

/** Markdown 极简渲染（标题/引用/列表/粗体/段落 → HTML，防注入转义） */
function mdToHtml(md) {
  if (!md) return ''
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const lines = esc(md).split('\n')
  let html = ''
  let listOpen = false
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (/^#{1,3} /.test(line)) {
      if (listOpen) { html += '</ul>'; listOpen = false }
      const level = line.match(/^(#+) /)[1].length
      html += `<h${Math.min(level, 3)}>${line.replace(/^#+ /, '')}</h${Math.min(level, 3)}>`
    } else if (/^> /.test(line)) {
      if (listOpen) { html += '</ul>'; listOpen = false }
      html += `<blockquote>${line.replace(/^> /, '')}</blockquote>`
    } else if (/^- /.test(line)) {
      if (!listOpen) { html += '<ul>'; listOpen = true }
      html += `<li>${line.replace(/^- /, '')}</li>`
    } else if (/^\d+\. /.test(line)) {
      if (!listOpen) { html += '<ol>'; listOpen = true }
      html += `<li>${line.replace(/^\d+\. /, '')}</li>`
    } else if (line.trim() === '') {
      if (listOpen) { html += '</ul>'; listOpen = false }
    } else {
      if (listOpen) { html += '</ul>'; listOpen = false }
      html += `<p>${line.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')}</p>`
    }
  }
  if (listOpen) html += '</ul>'
  return html
}

onMounted(loadAll)
onBeforeUnmount(() => { stopCharPolling(); stopStoryPolling() })
</script>

<style scoped>
/* 容器限宽改走统一令牌（--content-max-w 随「内容宽度」设置变化；此前写死 1200px） */
.world-detail-view { width: 100%; max-width: var(--content-max-w, 1440px); margin: 0 auto; }
/* 页头与左置标签样式由全局类/PageTabs 提供，本页不再重复定义 */
.version-tag { margin-left: 8px; vertical-align: middle; }
.source-tag { margin-left: 8px; vertical-align: middle; cursor: pointer; }
.source-tag:hover { filter: brightness(1.08); }
.empty-card { padding: 40px 0; }
.world-body { min-height: 360px; }
.world-card { padding: 20px 24px; }
.wc-section { margin-bottom: 4px; }
.wc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.wc-cell { min-width: 0; }
.wc-label { font-size: 0.78rem; color: var(--text-secondary); font-weight: 600; margin-bottom: 6px; }
.wc-value { font-size: 0.95rem; color: var(--text-primary); font-weight: 600; }
.wc-text { font-size: 0.92rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
/* 世界观设定 Markdown 渲染：复用全局 .markdown-body 排版；去掉 pre-wrap（换行由 MarkdownIt breaks 处理） */
.wc-md { font-size: 0.92rem; color: var(--text-regular); line-height: 1.8; word-break: break-word; }
.wc-md :deep(h1) { font-size: 1.25rem; }
.wc-md :deep(h2) { font-size: 1.1rem; }
.wc-md :deep(h3) { font-size: 1rem; }
.wc-md :deep(img) { max-width: 100%; }
/* 基本信息（题材/时代背景）等短值字段：Markdown 渲染但保持单行块状观感 */
.wc-md-value { font-size: 0.95rem; color: var(--text-primary); font-weight: 600; word-break: break-word; }
.wc-md-value :deep(p) { margin: 0; }
.wc-md-value :deep(strong) { font-weight: 700; }
/* 项目级记忆 */
.wc-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.wc-title { font-weight: 700; color: var(--text-primary); }
/* 地点详情提示 */
.loc-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; line-height: 1.6; }
.mem-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; }
.mem-list { max-height: 420px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.mem-empty { color: var(--text-secondary); text-align: center; padding: 24px 0; }
.mem-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; background: var(--bg-layer-2); }
.mem-head { display: flex; align-items: center; gap: 8px; }
.mem-time { font-size: 0.72rem; color: var(--text-secondary); }
.mem-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.mem-item:hover .mem-del { opacity: 1; }
.mem-content { font-size: 0.86rem; color: var(--text-primary); margin-top: 8px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
/* worldgen 预览弹窗 */
.wg-body { min-height: 120px; }
.wg-empty { padding: 10px 0; }
.wg-sec { margin-bottom: 16px; }
.wg-sec-title { font-weight: 700; color: var(--text-primary); font-size: 0.85rem; margin-bottom: 8px; }
.wg-essence { font-size: 0.85rem; color: var(--text-regular); line-height: 1.7; padding: 2px 0; }
.wg-cell { margin-bottom: 8px; }
.wg-label { font-size: 0.75rem; color: var(--text-secondary); font-weight: 600; display: block; margin-bottom: 2px; }
.wg-text { font-size: 0.85rem; color: var(--text-regular); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
/* 角色生成任务弹窗 */
.cg-body { min-height: 120px; }
.cg-desc { font-size: 0.82rem; color: var(--text-secondary); margin-bottom: 12px; line-height: 1.7; }
.cg-nodes { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.cg-node {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px;
  border: 1px solid var(--border-light); background: var(--bg-layer-2);
}
.cg-node .node-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.cg-node.st-succeeded { border-color: var(--success-border, #67c23a); }
.cg-node.st-succeeded .node-dot { background: #67c23a; }
.cg-node.st-running { border-color: var(--accent-border); }
.cg-node.st-running .node-dot { background: var(--accent); animation: wg-pulse 1.2s infinite; }
.cg-node.st-failed { border-color: var(--danger-border, #f56c6c); }
.cg-node.st-failed .node-dot { background: #f56c6c; }
.cg-node.st-pending { opacity: 0.55; }
.cg-node.st-pending .node-dot { background: var(--border-l2); }
.cg-node .node-label { font-size: 0.85rem; color: var(--text-primary); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cg-node .node-retry { font-size: 0.7rem; color: var(--text-tertiary); }
.cg-fail { margin-bottom: 12px; }
.cg-done { margin-top: 4px; }
/* story-plan 工作流（_06）：故事规划展示区 */
.story-tip { font-size: 0.8rem; color: var(--text-secondary); margin-left: 8px; }
.story-body { min-height: 120px; }
.story-versions { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.story-version-time { font-size: 0.8rem; color: var(--text-secondary); }
.story-collapse { border: none; }
.story-collapse :deep(.el-collapse-item__header) { font-weight: 600; }
.story-md { line-height: 1.7; font-size: 0.9rem; color: var(--text-primary); }
.story-md :deep(h1) { font-size: 1.1rem; margin: 8px 0; }
.story-md :deep(h2) { font-size: 1rem; margin: 8px 0; }
.story-md :deep(h3) { font-size: 0.95rem; margin: 6px 0; }
.story-md :deep(blockquote) { border-left: 3px solid var(--el-color-primary); margin: 6px 0; padding: 2px 10px; color: var(--text-secondary); }
.story-md :deep(ul), .story-md :deep(ol) { padding-left: 20px; margin: 4px 0; }
.story-md :deep(p) { margin: 6px 0; }
.story-atm { line-height: 1.7; font-size: 0.9rem; color: var(--text-primary); white-space: pre-wrap; }
@keyframes wg-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
</style>
