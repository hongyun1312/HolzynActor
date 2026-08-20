<template>
  <div class="timeline-view">
    <!-- 头部工具栏：精度切换 + 类型筛选 + 新增事件 + AI 识别 -->
    <div class="tl-toolbar">
      <div class="toolbar-left">
        <el-segmented v-model="precision" :options="precisionOptions" size="small" @change="load" />
        <el-checkbox-group v-model="typeFilter" class="type-filter" @change="load">
          <el-checkbox-button value="event">世界事件</el-checkbox-button>
          <el-checkbox-button value="action">角色行动</el-checkbox-button>
          <el-checkbox-button value="memory">记忆里程碑</el-checkbox-button>
        </el-checkbox-group>
      </div>
      <div class="toolbar-right">
        <el-tag v-if="clock" size="small" type="danger" effect="light" class="now-tag">
          ▬ 当前：{{ clock.gameTimeText || (`第 ${clock.day} 日 · ${clock.periodText}`) }}
        </el-tag>
        <el-button size="small" type="warning" plain :loading="aiGenerating" @click="doAiGenerate">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 识别事件
        </el-button>
        <el-button size="small" type="primary" @click="openAddEvent">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增事件
        </el-button>
      </div>
    </div>

    <!-- 主体 · 竖轴时间线 -->
    <div class="tl-body" v-loading="loading">
      <div v-if="nodes.length === 0" class="tl-empty">
        <el-empty description="暂无时间线节点——世界事件 / 角色行动 / 记忆里程碑将在此汇聚（可手动新增或 AI 识别事件）" />
      </div>
      <div v-else class="tl-axis-wrap">
        <!-- 竖轴 -->
        <div class="tl-axis">
          <!-- 分组刻度标签（按精度切换：年/月/日） -->
          <div v-for="(grp, gi) in groups" :key="gi" class="tl-group">
            <div class="tl-group-label">{{ grp.label }}</div>
            <div v-for="n in grp.items" :key="n.key" class="tl-node" :class="`kind-${n.kind}`" @click="selectNode(n)">
              <div class="tl-card">
                <div class="tl-card-head">
                  <el-tag size="small" :type="nodeTagType(n.kind)" effect="plain">{{ nodeKindLabel(n.kind) }}</el-tag>
                  <el-tag v-if="n.source" size="small" type="info" effect="plain" class="src-tag">{{ sourceLabel(n.source) }}</el-tag>
                  <span class="tl-card-time">{{ n.gameTime || fmtTime(n.time) }}</span>
                </div>
                <div v-if="n.gameTime" class="tl-card-real">现实：{{ fmtTime(n.time) }}</div>
                <div class="tl-card-title">{{ n.title }}</div>
                <div class="tl-card-text">{{ n.text }}</div>
              </div>
            </div>
          </div>
          <!-- 横红线：标记当前项目所处时间段（来自世界时钟） -->
          <div class="tl-now-line">
            <span class="tl-now-label">现在 · {{ clock?.gameTimeText || `第 ${clock?.day || 1} 日 ${clock?.periodText || ''}` }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 事件详情抽屉 -->
    <el-drawer v-model="detailDrawer" :title="selected?.title || '事件详情'" size="420px">
      <template v-if="selected">
        <div class="dt-grid">
          <div class="dt-item"><span class="k">类型</span><span class="v"><el-tag size="small" :type="nodeTagType(selected.kind)" effect="plain">{{ nodeKindLabel(selected.kind) }}</el-tag></span></div>
          <div class="dt-item"><span class="k">世界时间</span><span class="v">{{ selected.gameTime || '—' }}</span></div>
          <div class="dt-item"><span class="k">现实时间</span><span class="v">{{ fmtTime(selected.time) }}</span></div>
          <div class="dt-item"><span class="k">所属角色</span><span class="v">{{ selected.characterName || '世界' }}</span></div>
          <div class="dt-item"><span class="k">触发来源</span><span class="v">{{ selected.source ? sourceLabel(selected.source) : '—' }}</span></div>
        </div>
        <div class="dt-content">{{ selected.text }}</div>
      </template>
    </el-drawer>

    <!-- 新增事件对话框（手动持久化；AI 识别走上方按钮） -->
    <el-dialog v-model="addDialog.visible" title="新增事件（手动）" width="520px">
      <el-form label-width="80px">
        <el-form-item label="事件标题">
          <el-input v-model="addDialog.title" placeholder="如：帝国军队进驻边境" maxlength="100" />
        </el-form-item>
        <el-form-item label="事件内容" required>
          <el-input v-model="addDialog.content" type="textarea" :rows="4" placeholder="描述事件经过（含时间地点影响），将持久化到项目时间线" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="addDialog.loading" @click="doAddEvent">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 时间线页（前端布局重构 V1.0 §3.8 + V2.1 时间线聚合接口）。
 * <p>职责：以游戏时间为竖轴的全局事件视图（项目「编年史」）——
 * 头部工具栏（精度切换 + 类型筛选 + 手动新增事件[持久化] + AI 从世界观识别生成事件）；
 * 主体竖轴时间线（分组刻度 + 节点卡片 + 横红线标记当前时间段）；
 * 点击卡片打开详情抽屉。数据来自统一聚合接口 /api/projects/{id}/timeline
 * （actor_event + 行动 plan/log + 世界事件消息 + 记忆摘要）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchProjectTimeline, createProjectEvent, aiGenerateProjectEvent, fetchWorldClock } from '@/shared/api'

const route = useRoute()
const projectId = Number(route.params.id)

// ===== 工具栏 =====
const precisionOptions = [
  { label: '年', value: 'year' },
  { label: '月', value: 'month' },
  { label: '日', value: 'day' }
]
const precision = ref('month')
const typeFilter = ref(['event', 'action', 'memory'])

// ===== 数据 =====
const nodes = ref([])
const clock = ref(null)
const loading = ref(false)
const aiGenerating = ref(false)
const selected = ref(null)
const detailDrawer = ref(false)

// ===== 新增事件 =====
const addDialog = reactive({ visible: false, loading: false, title: '', content: '' })

/** 类型文案/颜色 */
function nodeKindLabel(kind) {
  return { event: '世界事件', action: '角色行动', memory: '记忆里程碑' }[kind] || kind
}
function nodeTagType(kind) {
  return { event: 'warning', action: 'primary', memory: 'success' }[kind] || 'info'
}

/** 来源文案 */
function sourceLabel(s) {
  return { manual: '手动', ai: 'AI 识别', simulation: '世界模拟', evolution: '演化归档', dialog: '对话' }[s] || s
}

/** 时间格式化 */
function fmtTime(t) {
  if (!t) return ''
  return String(t).slice(0, 16).replace('T', ' ')
}

/** 按精度对节点分组（年/月/日） */
const groups = computed(() => {
  const ordered = [...nodes.value].sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')))
  const groups = []
  let cur = null
  for (const n of ordered) {
    const label = groupLabel(n.time)
    if (!cur || cur.label !== label) {
      cur = { label, items: [] }
      groups.push(cur)
    }
    cur.items.push(n)
  }
  return groups
})

/** 按精度计算分组标签 */
function groupLabel(t) {
  const s = String(t || '')
  if (precision.value === 'year') return s.slice(0, 4) || '未知'
  if (precision.value === 'day') return s.slice(0, 10) || '未知'
  return s.slice(0, 7) || '未知'
}

/** 加载时间线（按类型筛选走后端聚合接口） */
async function load() {
  loading.value = true
  try {
    const params = {}
    if (typeFilter.value.length > 0) params.types = typeFilter.value.join(',')
    nodes.value = await fetchProjectTimeline(projectId, params)
  } catch (e) { ElMessage.error(e.message || '时间线加载失败') }
  finally { loading.value = false }
}

/** 选中节点打开详情 */
function selectNode(n) {
  selected.value = n
  detailDrawer.value = true
}

/** 打开新增事件对话框 */
function openAddEvent() {
  addDialog.title = ''
  addDialog.content = ''
  addDialog.visible = true
}

/** 手动新增事件（持久化到 actor_event，source=manual） */
async function doAddEvent() {
  if (!addDialog.content.trim()) return ElMessage.warning('请填写事件内容')
  addDialog.loading = true
  try {
    await createProjectEvent(projectId, {
      title: addDialog.title.trim() || '项目事件',
      content: addDialog.content.trim()
    })
    ElMessage.success('事件已添加到时间线')
    addDialog.visible = false
    await load()
  } catch (e) { ElMessage.error(e.message || '添加失败') }
  finally { addDialog.loading = false }
}

/** AI 从世界观识别生成事件 */
async function doAiGenerate() {
  aiGenerating.value = true
  try {
    const event = await aiGenerateProjectEvent(projectId)
    ElMessage.success(`AI 已生成事件：${event.title || '世界事件'}`)
    await load()
  } catch (e) { ElMessage.error(e.message || 'AI 识别失败') }
  finally { aiGenerating.value = false }
}

onMounted(async () => {
  try { clock.value = await fetchWorldClock(projectId) } catch (_) { clock.value = null }
  await load()
})
</script>

<style scoped>
.timeline-view { max-width: 1200px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; height: calc(100vh - 140px); }

/* 工具栏 */
.tl-toolbar { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-left { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.type-filter { display: inline-flex; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.now-tag { font-weight: 600; }

/* 主体 */
.tl-body { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 20px 28px; overflow-y: auto; min-height: 0; }
.tl-empty { display: flex; align-items: center; justify-content: center; height: 100%; }
.tl-axis-wrap { position: relative; }
.tl-axis { border-left: 2px solid var(--border-l2); margin-left: 12px; padding-left: 24px; display: flex; flex-direction: column; }
.tl-group { display: flex; flex-direction: column; }
.tl-group-label { font-size: 0.8rem; font-weight: 700; color: var(--text-secondary); margin: 14px 0 8px; padding: 2px 8px; background: var(--bg-light); border-radius: 4px; display: inline-block; width: fit-content; }
.tl-node { position: relative; margin-bottom: 12px; cursor: pointer; }
/* 节点横线指向时间轴 */
.tl-node::before { content: ''; position: absolute; left: -24px; top: 18px; width: 24px; height: 2px; background: var(--border-l2); }
.tl-node::after { content: ''; position: absolute; left: -28px; top: 14px; width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--bg-layer-1); box-shadow: 0 0 0 2px var(--text-placeholder); }
.tl-node.kind-event::after { background: #e6a23c; box-shadow: 0 0 0 2px #e6a23c; }
.tl-node.kind-action::after { background: #409eff; box-shadow: 0 0 0 2px #409eff; }
.tl-node.kind-memory::after { background: #67c23a; box-shadow: 0 0 0 2px #67c23a; }
.tl-card { border: 1px solid var(--border-light); border-radius: 10px; padding: 10px 14px; transition: box-shadow .2s, border-color .2s; max-width: 680px; }
.tl-card:hover { box-shadow: var(--card-shadow); border-color: var(--brand-primary); }
.tl-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.src-tag { margin-left: 0; }
.tl-card-time { font-size: 0.72rem; color: var(--text-placeholder); margin-left: auto; }
.tl-card-real { font-size: 0.68rem; color: var(--text-placeholder); margin-bottom: 2px; }
.tl-card-title { font-size: 0.92rem; font-weight: 600; color: var(--text-primary); }
.tl-card-text { font-size: 0.82rem; color: var(--text-secondary); line-height: 1.6; margin-top: 4px; white-space: pre-wrap; word-break: break-word; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
/* 横红线：标记当前项目所处时间段 */
.tl-now-line { position: relative; margin-top: 8px; margin-left: -26px; padding-left: 26px; }
.tl-now-line::before { content: ''; position: absolute; left: 0; right: 0; top: 6px; border-top: 2px solid var(--el-color-danger); }
.tl-now-label { position: relative; z-index: 1; display: inline-block; background: var(--el-color-danger); color: #fff; font-size: 0.72rem; padding: 2px 10px; border-radius: 4px; font-weight: 600; }

/* 详情抽屉 */
.dt-grid { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.dt-item { display: flex; gap: 8px; }
.dt-item .k { color: var(--text-secondary); font-size: 0.8rem; width: 70px; flex-shrink: 0; }
.dt-item .v { color: var(--text-primary); font-size: 0.85rem; }
.dt-content { font-size: 0.9rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; background: var(--bg-layer-2); border-radius: 8px; padding: 14px; }
</style>
