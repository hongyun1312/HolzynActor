<template>
  <div class="file-parse-panel">
    <!-- 顶部：选择文件 + 解析按钮 -->
    <div class="page-header">
      <div>
        <div class="page-title">文件解析</div>
        <div class="page-desc">上传世界观 txt / md 文件，AI 自动分段（地理/势力/规则/文化/历史/补充/角色）→ 扩写 → 建项目落库 → 角色分离入库，下方控制台实时输出后端解析日志（工作流进度）。</div>
      </div>
      <div class="header-ops">
        <el-button type="primary" plain @click="fileInputRef?.click()">
          <el-icon><HIcon name="UploadFilled" /></el-icon>&nbsp;选择文件
        </el-button>
        <el-button type="success" :loading="parsing" :disabled="files.length === 0" @click="doParse">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;解析
        </el-button>
      </div>
    </div>

    <!-- 扩写开关：不足 1500 字的分段是否自动 AI 扩写（默认关闭，由用户决定） -->
    <div class="expand-bar tech-card">
      <el-switch v-model="autoExpand" size="small" />
      <span class="expand-label">自动 AI 扩写不足 1500 字的分段</span>
      <span class="expand-tip">{{ autoExpand ? '开启：分段后不足 1500 字将调用 AI 扩写（更耗时）' : '关闭（默认）：分段后原样入库，不足 1500 字的分段保留原文' }}</span>
    </div>

    <!-- AI API 缺失预警 -->
    <div v-if="apiReady === false" class="api-warn">
      <el-alert type="warning" :closable="false" show-icon
        title="尚未配置 AI API — 解析文件需要调用 AI 能力"
        description="请先添加并启用「主 AI 对话」用途的用户级 API（如 DeepSeek），配置完成后返回本页即可继续解析；也可改用「手动添加」手动创建项目。">
        <template #default>
          <div class="api-warn-ops">
            <el-button size="small" type="primary" @click="goConfigApi">立即去配置 API</el-button>
            <el-button size="small" :loading="apiChecking" @click="checkApiReady">我已配置，刷新检测</el-button>
          </div>
        </template>
      </el-alert>
    </div>

    <!-- 隐藏的文件选择框 -->
    <input ref="fileInputRef" type="file" multiple accept=".txt,.md,.markdown" style="display: none" @change="onFileSelected" />

    <!-- 已选文件信息：文件名 / 格式 / 大小 -->
    <div v-if="files.length" class="file-info-card tech-card">
      <div class="file-info-title">已选文件（{{ files.length }}）</div>
      <div v-for="(f, i) in files" :key="i" class="file-info-row">
        <el-icon class="file-icon"><HIcon name="Document" /></el-icon>
        <span class="file-name" :title="f.name">{{ f.name }}</span>
        <el-tag size="small" effect="plain">{{ fileExt(f.name) }}</el-tag>
        <span class="file-size">{{ fileSize(f.size) }}</span>
        <el-button class="file-remove" size="small" text type="danger" @click="removeFile(i)"><el-icon><HIcon name="Delete" /></el-icon></el-button>
      </div>
    </div>

    <!-- 控制台日志区：实时输出后端解析日志（工作流进度） -->
    <div class="console-wrap">
      <WorkflowConsole ref="consoleRef" />
    </div>
  </div>
</template>

<script setup>
/**
 * 文件解析面板（2026-08-19 新建项目页重构）。
 * <p>职责：上传 txt/md 文件 → 调用「文件解析工作流」SSE 端点（分段→扩写→建项目落表→知识库存储→角色分离入库），
 * 下方控制台实时输出后端解析日志（工作流进度）；完成后弹窗询问是否进行默认世界初始化。</p>
 */
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchModelApis, parseWorkflowStream } from '@/shared/api'
import WorkflowConsole from './WorkflowConsole.vue'

const router = useRouter()

const fileInputRef = ref(null)
const files = ref([])      // 已选原始 File 对象
const parsing = ref(false)
const consoleRef = ref(null)
const autoExpand = ref(false) // 是否自动 AI 扩写不足 1500 字的分段（默认关闭，由用户决定）

// ===== AI API 可用性预检 =====
const apiReady = ref(null)
const apiChecking = ref(false)

/** 判断某 API 是否可作主 AI（chat/both）用途（与后端 ModelApiService.isChatCapable 对齐） */
function isChatCapableApi(a) {
  const p = a && a.purpose
  if (!p) return !a || a.embeddingEnabled !== 1
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

/** 文件选择回调：记录原始 File 对象（清空 input 支持重复选择） */
function onFileSelected(e) {
  files.value = Array.from(e.target.files || [])
  e.target.value = ''
}

/** 移除单个已选文件 */
function removeFile(i) {
  files.value.splice(i, 1)
}

/** 文件扩展名 */
function fileExt(name) {
  const dot = (name || '').lastIndexOf('.')
  return dot >= 0 ? name.slice(dot + 1).toUpperCase() : '未知'
}

/** 文件大小格式化 */
function fileSize(bytes) {
  if (bytes == null) return ''
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return bytes + ' B'
}

/** 解析失败错误处理：命中「未配置 API」时引导去配置 */
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

/** 执行文件解析工作流（SSE，控制台实时日志） */
async function doParse() {
  if (!files.value.length) return ElMessage.warning('请先选择文件')
  parsing.value = true
  consoleRef.value?.clear()
  consoleRef.value?.setRunning(true)
  consoleRef.value?.push({ level: 'info', time: now(), message: `[文件解析] 任务开始：文件 ${files.value.length} 个（${files.value.map(f => f.name).join('、')}），自动扩写=${autoExpand.value ? '开启' : '关闭（默认）'}` })
  try {
    const result = await parseWorkflowStream(files.value, autoExpand.value, {
      onLog: (log) => consoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message }),
      onStage: () => { /* 阶段由日志体现；进度条用于初始化页 */ }
    })
    consoleRef.value?.setRunning(false)
    if (!result) throw new Error('解析未返回结果')
    consoleRef.value?.push({ level: 'success', time: now(), message: `[文件解析] 完成：项目「${result.projectName}」已创建（世界观=${result.worldName}，角色 ${result.characterCount} 位，知识文档 ${result.knowledgeDocCount} 条）` })
    ElMessage.success(`项目「${result.projectName}」解析完成`)
    askInit(result.projectId, result.projectName)
  } catch (e) {
    consoleRef.value?.setRunning(false)
    consoleRef.value?.push({ level: 'error', time: now(), message: `[文件解析] 失败：${e.message || '解析失败'}` })
    handleApiError(e, '解析失败')
  } finally {
    parsing.value = false
  }
}

/** 弹窗询问是否进行默认世界初始化（是→初始化页 / 否→项目空间仪表盘） */
function askInit(projectId, projectName) {
  ElMessageBox.confirm(
    `项目「${projectName}」已解析并落库完成。是否进行默认世界初始化？\n\n默认初始化将自动执行 6 步：世界观地点 → 角色卡 → 字段字典与普通 NPC → 关系拓扑 → 世界时间 → 知识向量化（可在独立初始化页实时查看进度）。`,
    '默认世界初始化',
    {
      confirmButtonText: '进行初始化',
      cancelButtonText: '暂不初始化',
      distinguishCancelAndClose: true,
      type: 'info'
    }
  ).then(() => {
    router.replace(`/project/${projectId}/init`)
  }).catch((action) => {
    if (action === 'cancel') {
      router.replace(`/project/${projectId}/dashboard`)
    }
    // close（点 X）留在本页
  })
}

/** 当前时间 HH:mm:ss */
function now() {
  return new Date().toTimeString().slice(0, 8)
}

// 进入面板时自动预检 AI API
const emit = defineEmits(['open-settings'])
onMounted(checkApiReady)
watch(() => files.value.length, () => { if (apiReady.value === null) checkApiReady() })
</script>

<style scoped>
.file-parse-panel { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-title { font-size: 1.25rem; font-weight: 700; color: var(--text-primary); }
.page-desc { font-size: 0.82rem; color: var(--text-secondary); margin-top: 4px; line-height: 1.6; }
.header-ops { display: flex; gap: 8px; flex-shrink: 0; }
/* 扩写开关条 */
.expand-bar { display: flex; align-items: center; gap: 10px; padding: 12px 18px; }
.expand-label { font-size: 0.85rem; color: var(--text-primary); font-weight: 600; }
.expand-tip { font-size: 0.75rem; color: var(--text-secondary); }
.api-warn { }
.api-warn-ops { margin-top: 10px; display: flex; gap: 8px; }
/* 已选文件信息卡 */
.file-info-card { padding: 14px 18px; }
.file-info-title { font-weight: 600; color: var(--text-primary); margin-bottom: 10px; font-size: 0.9rem; }
.file-info-row { display: flex; align-items: center; gap: 10px; padding: 7px 6px; border-bottom: 1px solid var(--border-light); }
.file-info-row:last-child { border-bottom: none; }
.file-icon { color: var(--text-secondary); }
.file-name { font-size: 0.85rem; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.file-size { font-size: 0.75rem; color: var(--text-secondary); flex-shrink: 0; }
.file-remove { flex-shrink: 0; }
/* 控制台日志区（固定高度，撑满剩余空间） */
.console-wrap { height: 460px; }
</style>
