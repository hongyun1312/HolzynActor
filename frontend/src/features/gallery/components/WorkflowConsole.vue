<template>
  <div class="wf-console">
    <!-- 控制台头部：标题 + 复制/清空 -->
    <div class="wf-console-head">
      <span class="wf-console-title">🖥 控制台日志</span>
      <span class="wf-console-sub" v-if="running">（实时输出后端日志…）</span>
      <div class="wf-console-ops">
        <el-button size="small" text @click="copyLogs">复制</el-button>
        <el-button size="small" text type="danger" @click="clear">清空</el-button>
      </div>
    </div>
    <!-- 控制台日志区（深色底，自动滚动到底部） -->
    <div class="wf-console-body" ref="bodyRef">
      <div v-if="lines.length === 0" class="wf-console-empty">等待后端输出日志…</div>
      <div v-for="(line, i) in lines" :key="i" class="wf-console-line">
        <span class="wf-line-time">{{ line.time || '' }}</span>
        <span class="wf-line-msg" :class="'wf-' + (line.level || 'info')">{{ line.message }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 工作流控制台日志组件（2026-08-19 新建项目解析 / 世界初始化）。
 * <p>职责：以「深色终端」风格实时展示后端工作流日志（SSE log 事件逐行追加，
 * 自动滚动到底部），支持复制 / 清空；供「文件解析」「世界初始化」两个页面复用。</p>
 * <p>事件约定：line={level: 'info'|'warn'|'error'|'success', message, time}。</p>
 */
import { ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'

const lines = ref([])      // 日志行列表
const bodyRef = ref(null)  // 日志区容器（自动滚动）
const running = ref(false) // 是否正在输出（头部小字提示）

/** 追加一行日志并自动滚动到底部 */
function push(line) {
  lines.value.push(line)
  autoScroll()
}

/** 追加多行（批量）并自动滚动 */
function pushAll(items) {
  if (items && items.length) {
    lines.value.push(...items)
    autoScroll()
  }
}

/** 设置运行状态（影响头部提示文案） */
function setRunning(v) {
  running.value = !!v
}

/** 清空日志 */
function clear() {
  lines.value = []
}

/** 复制全部日志到剪贴板 */
function copyLogs() {
  const text = lines.value.map((l) => `${l.time || ''} ${l.message}`).join('\n')
  if (!text) return ElMessage.info('暂无日志可复制')
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制')).catch(() => ElMessage.error('复制失败'))
  } else {
    ElMessage.info('当前环境不支持剪贴板')
  }
}

/** 滚动到底部（下一帧执行，确保 DOM 已更新） */
function autoScroll() {
  nextTick(() => {
    const el = bodyRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

defineExpose({ push, pushAll, setRunning, clear, lines })
</script>

<style scoped>
.wf-console {
  display: flex; flex-direction: column; height: 100%;
  background: #10141c; border: 1px solid #2a3242; border-radius: 10px; overflow: hidden;
}
.wf-console-head {
  display: flex; align-items: center; gap: 8px; padding: 8px 14px;
  background: #161c28; border-bottom: 1px solid #2a3242; flex-shrink: 0;
}
.wf-console-title { font-size: 0.85rem; font-weight: 600; color: #e6ecf5; }
.wf-console-sub { font-size: 0.72rem; color: #7a8aa5; }
.wf-console-ops { margin-left: auto; display: flex; }
.wf-console-ops :deep(.el-button) { color: #9fb0c9; }
.wf-console-ops :deep(.el-button:hover) { color: #fff; }
.wf-console-body {
  flex: 1; overflow-y: auto; padding: 10px 14px; font-family: 'JetBrains Mono', 'HarmonyOS Sans SC', Consolas, monospace;
  font-size: 12px; line-height: 1.7; color: #c6d2e2;
}
.wf-console-empty { color: #5a6a84; text-align: center; padding: 30px 0; font-family: inherit; }
.wf-console-line { display: flex; gap: 10px; white-space: pre-wrap; word-break: break-all; }
.wf-line-time { color: #4c5a73; flex-shrink: 0; }
.wf-info { color: #c6d2e2; }
.wf-warn { color: #e6b46b; }
.wf-error { color: #ef7f7f; }
.wf-success { color: #7fd8a4; }
.wf-console-body::-webkit-scrollbar { width: 6px; }
.wf-console-body::-webkit-scrollbar-thumb { background: #2a3242; border-radius: 3px; }
</style>
