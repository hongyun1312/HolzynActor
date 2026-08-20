<template>
  <div class="init-view">
    <!-- 顶部栏：返回 + 标题 + 模式选择 + 开始/重新运行 -->
    <header class="init-topbar">
      <div class="topbar-left">
        <el-button class="back-btn" text @click="goBack">
          <el-icon><HIcon name="ArrowLeft" /></el-icon>&nbsp;{{ finished || failed ? '返回' : '取消' }}
        </el-button>
        <span class="topbar-divider"></span>
        <div class="topbar-title">
          <span class="init-title">世界初始化</span>
          <span class="init-sub">独立全屏页 · 6 步自动初始化（全部 AI 自动，下方控制台实时输出后端日志）</span>
        </div>
      </div>
      <div class="topbar-right">
        <el-radio-group v-model="rebuild" size="small" :disabled="running">
          <el-radio-button :value="false">跳过已生成（幂等）</el-radio-button>
          <el-radio-button :value="true">全量重建</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="small" :loading="running" @click="start">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;{{ running ? '初始化中…' : (finished || failed ? '重新运行' : '开始初始化') }}
        </el-button>
      </div>
    </header>

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
</template>

<script setup>
/**
 * 专属世界初始化页（独立全屏，2026-08-19 新建项目解析重构）。
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

/** 返回：完成/失败后回项目空间仪表盘；未开始时回新增项目页 */
function goBack() {
  if (finished.value || failed.value) {
    router.replace(`/project/${projectId}/dashboard`)
  } else {
    router.replace('/project/new')
  }
}

// 进入页面自动开始（默认「跳过已生成（幂等）」模式）
onMounted(start)
</script>

<style scoped>
.init-view { height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: var(--bg-page, #eef1f6); }

/* ===== 顶部栏 ===== */
.init-topbar {
  height: 56px; background: var(--bg-nav); display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0; box-shadow: var(--shadow-lv1); z-index: 5; border-bottom: 1px solid var(--border-l1);
}
.topbar-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.back-btn { color: var(--text-regular); }
.back-btn:hover { color: var(--accent-text); background: var(--accent-soft); }
.topbar-divider { width: 1px; height: 20px; background: var(--border-l2); }
.topbar-title { display: flex; align-items: center; gap: 10px; min-width: 0; white-space: nowrap; }
.init-title { font-weight: 700; font-size: 1rem; color: var(--text-primary); }
.init-sub { font-size: 0.78rem; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; }
.topbar-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

/* ===== 上半屏：进度条 + 节点 ===== */
.init-progress-half { flex: 1; min-height: 0; padding: 20px 24px 10px; display: flex; flex-direction: column; }
.progress-card { padding: 18px 24px; flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 26px; }
.progress-title { display: flex; align-items: center; gap: 10px; font-weight: 700; color: var(--text-primary); font-size: 0.95rem; }
.init-steps { width: 100%; }
.init-steps :deep(.el-step__title) { font-size: 0.85rem; }
.init-steps :deep(.el-step__description) { font-size: 0.72rem; }
.result-summary { text-align: center; font-size: 0.85rem; color: var(--text-regular); }
.result-summary b { color: var(--brand-primary, #1d5cff); }

/* ===== 下半屏：控制台日志 ===== */
.init-console-half { height: 46%; min-height: 220px; padding: 0 24px 20px; flex-shrink: 0; }
</style>
