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
  <!--
    世界初始化：不再自绘顶栏（原 56px 独立顶栏与全站外壳重复），改用统一 AppShell（global 模式）。
    模式选择 + 开始按钮留在顶栏（滚动时始终可见）；返回按钮走外壳 backTo（replace 回项目沙盘/新增项目页）。
  -->
  <AppShell
    mode="global"
    brand-label="世界初始化"
    :breadcrumb="finished || failed ? '初始化完成' : '正在初始化'"
    :show-sidebar="false"
    :show-back="true"
    :back-to="backTarget"
  >
    <template #topbar-actions>
      <el-radio-group v-model="rebuild" size="small" :disabled="running">
        <el-radio-button :value="false">跳过已生成（幂等）</el-radio-button>
        <el-radio-button :value="true">全量重建</el-radio-button>
      </el-radio-group>
      <el-button type="primary" size="small" :loading="running" @click="start">
        <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;{{ running ? '初始化中…' : (finished || failed ? '重新运行' : '开始初始化') }}
      </el-button>
    </template>

    <div class="content-col init-view">
      <!-- 页头：标题 + 说明 -->
      <PageHeader
        title="世界初始化"
        desc="6 步自动初始化（全部 AI 自动，下方控制台实时输出后端日志）；进入页面自动开始，默认「跳过已生成（幂等）」模式。"
      />

      <!-- 上半屏：横向进度条 + 6 步节点 -->
      <section class="init-progress-half">
      <div class="progress-card tech-card">
        <div class="progress-title">
          <span>初始化进度</span>
          <el-tag v-if="running" size="small" type="primary" effect="plain">正在执行：{{ currentStage || '准备中' }}</el-tag>
          <el-tag v-else-if="finished" size="small" type="success" effect="plain">初始化完成</el-tag>
          <el-tag v-else-if="failed" size="small" type="danger" effect="plain">初始化失败/中断</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">待开始</el-tag>
        </div>
        <el-steps :active="stepActive" align-center finish-status="success" class="init-steps">
          <el-step v-for="(s, i) in steps" :key="s.name" :title="s.name" :description="stepDesc(i)" />
        </el-steps>
        <!-- 结果摘要 -->
        <div v-if="result" class="result-summary">
          完成：地点 <b>{{ result.locations }}</b> · 角色卡 <b>{{ result.cards }}</b> · 普通 NPC <b>{{ result.npcs }}</b> ·
          关系 <b>{{ result.relations }}</b> · 世界时间 <b>{{ result.gameTimeText || '未设置' }}</b> · 向量化 <b>{{ result.vectorized }}</b>
        </div>
      </div>
    </section>

      <!-- 下半屏：控制台日志 -->
      <section class="init-console-half">
        <WorkflowConsole ref="consoleRef" />
      </section>
    </div>
  </AppShell>
</template>

<script setup>
/**
 * 专属世界初始化页（独立全屏， 新建项目解析重构）。
 * <p>职责：运行世界初始化 6 步工作流（世界观地点 → 角色卡 → 字段字典与普通 NPC → 关系拓扑 →
 * 世界时间 → 知识向量化），上方 1/2 横向进度条（6 步节点显示当前进度），下方 1/2 控制台日志
 * （SSE 实时输出后端初始化日志 = 工作流进度）。</p>
 * <p>进入方式：① 新建项目（手动添加/文件解析）完成后的弹窗；② 项目空间仪表盘「世界初始化」入口。
 * 支持「跳过已生成（幂等）/ 全量重建」两种模式，运行中不可切换；完成后可进入项目空间仪表盘。</p>
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { initWorkflowStream } from '@/shared/api'
import WorkflowConsole from '@/features/gallery/components/WorkflowConsole.vue'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

/** 6 步定义 */
const steps = [
  { name: '世界观地点' },
  { name: '角色卡' },
  { name: '字段字典与普通 NPC' },
  { name: '关系拓扑' },
  { name: '世界时间' },
  { name: '知识向量化' }
]

const rebuild = ref(false)       // false=跳过已生成（幂等） / true=全量重建
const running = ref(false)
const finished = ref(false)
const failed = ref(false)
const currentStage = ref('')
const currentIndex = ref(-1)     // 当前阶段序号（1 开始，SSE stage 事件）
const result = ref(null)
const consoleRef = ref(null)

/** el-steps 激活索引（0 起；stage 事件 index 为 1 起 → active = index） */
const stepActive = computed(() => (running.value || finished.value || failed.value) ? currentIndex.value : 0)

/** 步骤描述：已完成/进行中/待执行/失败 */
function stepDesc(i) {
  if (!running.value && !finished.value && !failed.value) return '等待'
  if (i < currentIndex.value) return '已完成'
  if (i === currentIndex.value) return running.value ? '执行中' : (finished.value ? '已完成' : (failed.value ? '失败' : ''))
  return '待执行'
}

/** 当前时间 HH:mm:ss */
function now() {
  return new Date().toTimeString().slice(0, 8)
}

/** 开始/重新运行初始化 */
async function start() {
  if (running.value) return
  running.value = true
  finished.value = false
  failed.value = false
  result.value = null
  currentIndex.value = 0
  currentStage.value = ''
  consoleRef.value?.clear()
  consoleRef.value?.setRunning(true)
  consoleRef.value?.push({ level: 'info', time: now(), message: `[世界初始化] 任务开始：项目=${projectId}，模式=${rebuild.value ? '全量重建' : '跳过已生成（幂等）'}` })
  try {
    const res = await initWorkflowStream(projectId, rebuild.value, {
      onLog: (log) => consoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message }),
      onStage: (st) => {
        currentIndex.value = st.index || 0
        currentStage.value = st.name || ''
        consoleRef.value?.push({ level: 'info', time: now(), message: `────────── 阶段 ${st.index}/${st.total}：${st.name} ──────────` })
      }
    })
    if (!res) throw new Error('初始化未返回结果')
    result.value = res
    finished.value = true
    consoleRef.value?.setRunning(false)
    consoleRef.value?.push({ level: 'success', time: now(), message: `[世界初始化] 完成：地点 ${res.locations} · 角色卡 ${res.cards} · 普通 NPC ${res.npcs} · 关系 ${res.relations} · 世界时间 ${res.gameTimeText || '未设置'} · 向量化 ${res.vectorized}` })
    ElMessage.success('世界初始化完成')
  } catch (e) {
    failed.value = true
    consoleRef.value?.setRunning(false)
    consoleRef.value?.push({ level: 'error', time: now(), message: `[世界初始化] 失败：${e.message || '初始化失败'}` })
    ElMessage.error(e.message || '世界初始化失败')
  } finally {
    running.value = false
  }
}

/**
 * 顶栏返回目标：完成/失败后回项目空间世界沙盘（S1 项目主页）；未开始时回新增项目页。
 * <p>由 AppShell 的 `backTo` 消费（内部用 replace，避免浏览器后退又落回本页并再次自动初始化）。</p>
 */
const backTarget = computed(() => (finished.value || failed.value ? `/project/${projectId}/sandbox` : '/project/new'))

// 进入页面自动开始（默认「跳过已生成（幂等）」模式）
onMounted(start)
</script>

<style scoped>
/* 页面容器：外壳已提供滚动与内边距；这里只做两段式纵向布局（进度区自适应 + 控制台固定 46%） */
.init-view { display: flex; flex-direction: column; height: 100%; min-height: 0; gap: 12px; }

/* ===== 上半屏：进度条 + 节点 ===== */
.init-progress-half { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.progress-card { padding: 18px 24px; flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 26px; }
.progress-title { display: flex; align-items: center; gap: 10px; font-weight: 700; color: var(--text-primary); font-size: 0.95rem; }
.init-steps { width: 100%; }
.init-steps :deep(.el-step__title) { font-size: 0.85rem; }
.init-steps :deep(.el-step__description) { font-size: 0.72rem; }
.result-summary { text-align: center; font-size: 0.85rem; color: var(--text-regular); }
.result-summary b { color: var(--brand-primary, #1d5cff); }

/* ===== 下半屏：控制台日志 ===== */
.init-console-half { height: 46%; min-height: 220px; flex-shrink: 0; }
</style>
