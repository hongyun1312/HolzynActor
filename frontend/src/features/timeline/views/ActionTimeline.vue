<template>
  <div class="timeline-view">
    <div class="tl-top">
      <!-- 左筛选栏 -->
      <div class="tl-side">
        <div class="tl-side-title">筛选</div>
        <el-form label-position="top" size="small">
          <el-form-item label="角色">
            <el-select v-model="filter.characterId" clearable placeholder="全部角色" style="width: 100%">
              <el-option v-for="ch in characters" :key="ch.id" :label="ch.name" :value="ch.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker v-model="filter.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width: 100%" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filter.status" clearable placeholder="全部状态" style="width: 100%">
              <el-option label="计划中" value="planned" />
              <el-option label="已完成" value="done" />
              <el-option label="已取消" value="cancelled" />
            </el-select>
          </el-form-item>
          <el-form-item label="触发类型">
            <el-select v-model="filter.triggerType" clearable placeholder="全部触发" style="width: 100%">
              <el-option label="手动" value="manual" />
              <el-option label="对话后" value="after_dialog" />
              <el-option label="定时" value="scheduled" />
              <el-option label="世界事件" value="event" />
            </el-select>
          </el-form-item>
          <el-button type="primary" :loading="loading" style="width: 100%" @click="load">查询</el-button>
          <el-button style="width: 100%; margin-left: 0" @click="resetFilter">重置</el-button>
        </el-form>
        <el-divider />
        <el-button type="warning" style="width: 100%" @click="openTrigger">手动触发行动</el-button>
      </div>

      <!-- 中纵向时间轴 -->
      <div class="tl-main">
        <div class="tl-header">
          <span class="page-title">行动时间线</span>
          <el-tag v-if="live" size="small" type="success">实时订阅中</el-tag>
        </div>
        <div v-if="timeline.length === 0" class="tl-empty">
          <el-empty description="暂无行动记录，可手动触发一次行动" />
        </div>
        <div v-else class="tl-list">
          <div v-for="n in timeline" :key="`${n.kind}-${n.id}`" class="tl-node" :class="`kind-${n.kind}`" @click="selectNode(n)">
            <div class="tl-time">{{ fmtTime(n.time) }}</div>
            <div class="tl-dot" />
            <div class="tl-body">
              <div class="tl-head">
                <el-tag size="small" :type="nodeTagType(n.kind)">{{ nodeKindLabel(n.kind) }}</el-tag>
                <span class="tl-char">{{ charName(n.characterId) || '世界' }}</span>
                <el-tag v-if="n.status" size="small" effect="plain" :type="statusTagType(n.status)">{{ statusLabel(n.status) }}</el-tag>
              </div>
              <div class="tl-text">{{ nodeText(n) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部行动详情面板 -->
    <div v-if="selected" class="tl-detail">
      <div class="detail-header">
        <span class="detail-title">行动详情</span>
        <el-button size="small" text @click="selected = null"><el-icon><HIcon name="Close" /></el-icon></el-button>
      </div>
      <div class="detail-grid">
        <div class="detail-item"><span class="k">类型</span><span class="v">{{ selected.type || '—' }}</span></div>
        <div class="detail-item"><span class="k">目标</span><span class="v">{{ selected.target || '—' }}</span></div>
        <div class="detail-item"><span class="k">决策理由</span><span class="v">{{ selected.reason || selected.detail || '—' }}</span></div>
        <div class="detail-item"><span class="k">优先级</span><span class="v">{{ selected.urgency != null ? selected.urgency : '—' }}</span></div>
        <div class="detail-item"><span class="k">状态</span><span class="v">{{ selected.status ? statusLabel(selected.status) : '—' }}</span></div>
        <div class="detail-item"><span class="k">耗时</span><span class="v">{{ selected.duration != null ? selected.duration + ' 分钟' : '—' }}</span></div>
        <div v-if="selected.params && Object.keys(selected.params).length" class="detail-item wide">
          <span class="k">参数</span><span class="v"><pre class="param-pre">{{ JSON.stringify(selected.params, null, 2) }}</pre></span>
        </div>
      </div>
      <div class="detail-ops">
        <template v-if="selected.kind === 'plan'">
          <el-button size="small" @click="regenerate(selected)">重新生成</el-button>
          <el-button size="small" type="warning" @click="markStatus(selected, 'done')">标记完成</el-button>
          <el-button size="small" type="danger" plain @click="markStatus(selected, 'cancelled')">取消</el-button>
          <el-button size="small" @click="exportNode(selected)">导出单条</el-button>
        </template>
        <el-button v-else size="small" @click="exportNode(selected)">导出单条</el-button>
      </div>
    </div>

    <!-- 手动触发行动对话框 -->
    <el-dialog v-model="triggerDialog.visible" title="手动触发行动" width="520px">
      <el-form label-width="90px">
        <el-form-item label="角色" required>
          <el-select v-model="triggerDialog.characterId" placeholder="选择角色" style="width: 100%">
            <el-option v-for="ch in characters" :key="ch.id" :label="ch.name" :value="ch.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发理由">
          <el-input v-model="triggerDialog.reason" type="textarea" :rows="2" placeholder="可选：为什么触发这次行动" />
        </el-form-item>
        <el-form-item label="计划时间">
          <el-date-picker v-model="triggerDialog.plannedTime" type="datetime" value-format="YYYY-MM-DD HH:mm" placeholder="留空 = 立即执行（填未来时间 = 定时执行）" style="width: 100%" />
        </el-form-item>
        <el-form-item label="情境">
          <el-input v-model="triggerDialog.situation" type="textarea" :rows="2" placeholder="可选：当前情境描述（缺省由系统组装）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triggerDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="triggerDialog.loading" @click="submitTrigger">触发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 行动时间线页（P2 阶段二，按桌面布局）。
 * <p>职责：左筛选栏（角色/时间范围/状态/触发类型 + 手动触发行动）；中纵向时间轴
 * （行动决策 / 世界事件 / 执行日志三类节点分色，时间倒序）；底部行动详情面板
 * （类型/目标/参数/决策理由/优先级/状态/耗时，支持重新生成、标记状态、导出单条）；
 * 订阅 SSE 行动事件流实现新行动实时刷新。</p>
 */
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacters, fetchProjectActionTimeline, triggerAction, updateActionStatus, actionStreamUrl
} from '@/shared/api'

const route = useRoute()
const projectId = Number(route.params.id)

const characters = ref([])
const plans = ref([])
const logs = ref([])
const events = ref([])
const timeline = ref([])
const selected = ref(null)
const loading = ref(false)
const live = ref(false)

const filter = reactive({ characterId: null, dateRange: null, status: null, triggerType: null })
const triggerDialog = reactive({ visible: false, characterId: null, reason: '', plannedTime: null, situation: '', loading: false })

/** 角色名 */
function charName(cid) {
  const ch = characters.value.find(x => x.id === cid)
  return ch ? ch.name : null
}

/** 时间格式化 */
function fmtTime(t) {
  if (!t) return ''
  return String(t).slice(0, 16).replace('T', ' ')
}

/** 节点类型文案 */
function nodeKindLabel(kind) {
  return kind === 'plan' ? '行动决策' : (kind === 'event' ? '世界事件' : '执行日志')
}

/** 节点类型标签颜色 */
function nodeTagType(kind) {
  return kind === 'plan' ? 'primary' : (kind === 'event' ? 'warning' : 'info')
}

/** 状态文案 */
function statusLabel(s) {
  return { planned: '计划中', executing: '执行中', done: '已完成', cancelled: '已取消' }[s] || s
}

/** 状态标签颜色 */
function statusTagType(s) {
  return { planned: 'info', executing: 'warning', done: 'success', cancelled: 'danger' }[s] || 'info'
}

/** 节点主文本 */
function nodeText(n) {
  if (n.kind === 'plan') return `${n.characterId ? charName(n.characterId) + '：' : ''}${n.action || ''}`
  if (n.kind === 'event') return n.content || ''
  return `${n.characterId ? charName(n.characterId) + '：' : ''}${n.summary || ''}`
}

/** 加载项目级时间线 */
async function load() {
  loading.value = true
  try {
    const params = {}
    if (filter.characterId) params.characterId = filter.characterId
    if (filter.status) params.status = filter.status
    if (filter.triggerType) params.triggerType = filter.triggerType
    if (filter.dateRange && filter.dateRange.length === 2) {
      params.startDate = filter.dateRange[0]
      params.endDate = filter.dateRange[1]
    }
    const data = await fetchProjectActionTimeline(projectId, params)
    plans.value = data.plans || []
    logs.value = data.logs || []
    events.value = data.events || []
    timeline.value = data.timeline || []
    selected.value = null
  } catch (e) { ElMessage.error(e.message || '加载失败') }
  finally { loading.value = false }
}

/** 重置筛选 */
function resetFilter() {
  filter.characterId = null
  filter.dateRange = null
  filter.status = null
  filter.triggerType = null
  load()
}

/** 选中节点（plan 从 plans 取全量详情） */
function selectNode(n) {
  if (n.kind === 'plan') {
    const full = plans.value.find(p => p.id === n.id)
    selected.value = { ...n, ...(full || {}) }
  } else {
    selected.value = { ...n }
  }
}

/** 打开手动触发对话框 */
function openTrigger() {
  triggerDialog.characterId = null
  triggerDialog.reason = ''
  triggerDialog.plannedTime = null
  triggerDialog.situation = ''
  triggerDialog.visible = true
}

/** 提交手动触发 */
async function submitTrigger() {
  if (!triggerDialog.characterId) return ElMessage.warning('请选择角色')
  triggerDialog.loading = true
  try {
    const body = { reason: triggerDialog.reason || undefined, situation: triggerDialog.situation || undefined }
    if (triggerDialog.plannedTime) body.plannedTime = triggerDialog.plannedTime
    await triggerAction(triggerDialog.characterId, body)
    ElMessage.success(triggerDialog.plannedTime ? '已创建定时行动计划' : '行动已触发并执行')
    triggerDialog.visible = false
    await load()
  } catch (e) { ElMessage.error(e.message || '触发失败') }
  finally { triggerDialog.loading = false }
}

/** 重新生成（基于原决策理由再触发一次） */
async function regenerate(node) {
  try {
    await triggerAction(node.characterId, { reason: node.reason || '重新生成' })
    ElMessage.success('已重新生成行动决策')
    await load()
  } catch (e) { ElMessage.error(e.message || '重新生成失败') }
}

/** 标记状态 */
async function markStatus(node, status) {
  try {
    await ElMessageBox.confirm(`确认将行动标记为「${statusLabel(status)}」？`, '标记状态', { type: 'warning' })
    await updateActionStatus(node.id, status)
    ElMessage.success('状态已更新')
    await load()
  } catch (_) { /* 用户取消或失败 */ }
}

/** 导出单条（JSON 下载） */
function exportNode(node) {
  const blob = new Blob([JSON.stringify(node, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `action-${node.kind || 'node'}-${node.id || Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}

/** 订阅 SSE 行动事件流：新行动实时刷新 */
function subscribeStream() {
  try {
    const es = new EventSource(actionStreamUrl())
    es.addEventListener('action', () => { load() })
    es.onopen = () => { live.value = true }
    es.onerror = () => { live.value = false }
    window.__actionEs = es
  } catch (_) { /* 订阅失败不阻塞页面 */ }
}

onMounted(async () => {
  try { characters.value = await fetchCharacters(projectId) } catch (_) { /* 忽略 */ }
  await load()
  subscribeStream()
})

onBeforeUnmount(() => {
  if (window.__actionEs) { window.__actionEs.close(); window.__actionEs = null }
})
</script>

<style scoped>
.timeline-view { display: flex; flex-direction: column; gap: 16px; height: calc(100vh - 140px); }
.tl-top { display: flex; gap: 16px; flex: 1; min-height: 0; }
.tl-side { width: 230px; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 14px; overflow-y: auto; flex-shrink: 0; }
.tl-side-title { font-weight: 600; color: var(--text-primary); margin-bottom: 10px; }
.tl-main { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 16px; display: flex; flex-direction: column; overflow: hidden; }
.tl-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.page-title { font-size: 1.15rem; font-weight: 700; color: var(--text-primary); }
.tl-empty { flex: 1; display: flex; align-items: center; justify-content: center; }
.tl-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; padding-right: 6px; }
.tl-node { display: flex; gap: 12px; padding: 10px 8px; border-left: 3px solid transparent; border-radius: 8px; cursor: pointer; }
.tl-node:hover { background: var(--bg-hover, var(--bg-layer-2)); }
.tl-node.kind-plan { border-left-color: #409eff; }
.tl-node.kind-event { border-left-color: #e6a23c; }
.tl-node.kind-log { border-left-color: var(--text-tertiary); }
.tl-time { width: 120px; flex-shrink: 0; font-size: 0.75rem; color: var(--text-secondary); padding-top: 4px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 7px; flex-shrink: 0; }
.kind-plan .tl-dot { background: #409eff; }
.kind-event .tl-dot { background: #e6a23c; }
.kind-log .tl-dot { background: var(--text-tertiary); }
.tl-body { flex: 1; min-width: 0; }
.tl-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tl-char { font-size: 0.78rem; font-weight: 600; color: var(--text-primary); }
.tl-text { font-size: 0.85rem; color: var(--text-secondary); white-space: pre-wrap; word-break: break-word; line-height: 1.5; }
.tl-detail { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 14px 16px; }
.detail-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.detail-title { font-weight: 600; color: var(--text-primary); }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 20px; }
.detail-item { display: flex; gap: 8px; }
.detail-item.wide { grid-column: 1 / -1; }
.detail-item .k { color: var(--text-secondary); font-size: 0.8rem; flex-shrink: 0; }
.detail-item .v { color: var(--text-primary); font-size: 0.85rem; word-break: break-word; }
.param-pre { margin: 0; font-size: 0.75rem; background: var(--bg-layer-2); padding: 8px; border-radius: 6px; white-space: pre-wrap; word-break: break-word; }
.detail-ops { margin-top: 12px; display: flex; gap: 8px; }
</style>