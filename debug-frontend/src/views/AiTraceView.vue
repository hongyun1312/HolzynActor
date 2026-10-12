<template>
  <div class="ai-trace">
    <h2 class="page-title">AI Trace · 决策链路</h2>

    <!-- 筛选工具条（D5：接 trace 聚合端点；「按角色」维度 = 每角色最近 N 次决策轨迹） -->
    <div class="toolbar">
      <el-input v-model="filters.projectId" size="small" placeholder="项目 ID" style="width: 100px" clearable @change="onProjectChange" />
      <el-select v-model="filters.scene" size="small" placeholder="场景" clearable style="width: 150px">
        <el-option v-for="s in sceneOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filters.status" size="small" placeholder="状态" clearable style="width: 110px">
        <el-option label="成功" value="success" />
        <el-option label="失败" value="failed" />
        <el-option label="降级" value="degraded" />
      </el-select>
      <el-input v-model="filters.keyword" size="small" placeholder="关键词（搜 Prompt/输出/错误）" clearable style="width: 190px" @keyup.enter="search" />
      <el-date-picker v-model="dateRange" size="small" type="daterange" start-placeholder="开始" end-placeholder="结束"
        value-format="YYYY-MM-DD" style="width: 230px" />
      <!-- 按角色：每角色最近 N 次决策轨迹（D5 F19） -->
      <el-select v-model="characterId" size="small" placeholder="按角色（最近N次轨迹）" clearable filterable style="width: 180px" @change="onCharacterChange">
        <el-option v-for="c in characters" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-if="characterId" v-model="traceLimit" size="small" style="width: 110px" @change="search">
        <el-option v-for="n in [5, 10, 20]" :key="n" :label="`最近 ${n} 次`" :value="n" />
      </el-select>
      <el-button size="small" type="primary" @click="search">查询</el-button>
      <el-button size="small" @click="reset">重置</el-button>
      <span v-if="characterId" class="mode-tip dim">正在查看「{{ characterNameOf }}」的最近决策轨迹</span>
    </div>

    <!-- 链路列表：一次决策一条链路，展开 = 感知→思考→行动→学习 环节卡片流 -->
    <el-table :data="chains" size="small" stripe class="ai-table" style="width: 100%" row-key="key"
      :expand-row-keys="expandedKeys" @expand-change="onExpand">
      <el-table-column type="expand" width="36">
        <template #default="{ row }">
          <div class="trace-steps">
            <div class="trace-steps-title">
              决策链路{{ row.traceId ? ' ' + shortTraceId(row.traceId) : '' }} · {{ row.stepCount }} 个环节
              <span class="dim">（点击环节卡片查看该调用的结构化详情）</span>
            </div>
            <!-- 环节卡片流：按发生顺序（感知→思考→行动→学习） -->
            <div class="step-cards">
              <div v-for="s in row.steps" :key="s.id" class="step-card" :class="'st-' + stageOf(s).key"
                @click="openCallDetail(s.id)">
                <div class="step-card-head">
                  <el-tag :type="stageOf(s).color" size="small" effect="dark">{{ stageOf(s).label }}</el-tag>
                  <span class="mono dim attempt" v-if="(s.attempt || 1) > 1">尝试 {{ s.attempt }}</span>
                  <span class="spacer"></span>
                  <el-tag :type="statusType(s.status)" size="small" effect="plain">{{ statusLabel(s.status) }}</el-tag>
                </div>
                <div class="step-card-body">
                  <div class="step-line" v-if="s.stepType === 'ai_call'">
                    <span class="mono dim">{{ s.sceneName || s.scene }}</span>
                    <span class="mono dim model">{{ s.model || '' }}</span>
                  </div>
                  <div class="step-line" v-else>
                    <span class="mono dim">{{ s.sceneName || s.scene || '—' }}</span>
                  </div>
                  <div class="step-tokens mono" v-if="s.stepType === 'ai_call'">
                    <span class="tok-in">▲{{ s.tokenIn }}</span>
                    <span class="tok-out">▼{{ s.tokenOut }}</span>
                    <span class="dur">{{ s.durationMs }}ms</span>
                  </div>
                  <div class="step-content" v-if="s.content">{{ s.content }}</div>
                  <div class="step-content" v-else-if="!s.content && s.stepType === 'ai_call'">（AI 调用，点此查看输入输出详情）</div>
                  <div class="step-content" v-else>—</div>
                </div>
                <div class="step-card-foot mono dim">{{ shortTime(s.createdAt) }} · #{{ s.id }}</div>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="150">
        <template #default="{ row }"><span class="mono">{{ shortTime(row.time) }}</span></template>
      </el-table-column>
      <el-table-column label="链路" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.traceId" size="small" effect="plain" type="info">{{ shortTraceId(row.traceId) }}</el-tag>
          <span v-else class="mono dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="环节数" width="70">
        <template #default="{ row }"><span class="mono">{{ row.stepCount }}</span></template>
      </el-table-column>
      <el-table-column label="场景" min-width="100">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.sceneName || row.scene }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="projectName" label="项目" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.projectName || `#${row.projectId ?? '-'}` }}</template>
      </el-table-column>
      <el-table-column prop="characterName" label="角色" min-width="100" show-overflow-tooltip>
        <template #default="{ row }">{{ row.characterName || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="90">
        <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="Token" min-width="110">
        <template #default="{ row }"><span class="mono">{{ row.tokenIn }}/{{ row.tokenOut }}</span></template>
      </el-table-column>
      <el-table-column label="耗时" min-width="90">
        <template #default="{ row }"><span class="mono">{{ row.durationMs }}ms</span></template>
      </el-table-column>
    </el-table>

    <!-- 分页（仅「全部/筛选」模式；按角色模式为最近 N 次无分页） -->
    <div class="pager" v-if="!characterId">
      <el-pagination layout="total, prev, pager, next, sizes" :total="total" :page-size="size" :current-page="page"
        :page-sizes="[10, 20, 50]" @current-change="(p) => { page = p; load() }"
        @size-change="(s) => { size = s; page = 1; load() }" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get } from '../api'

const route = useRoute()
const router = useRouter()

const filters = ref({ projectId: '', scene: '', status: '', keyword: '' })
const dateRange = ref(null)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

// D5 按角色维度：每角色最近 N 次决策轨迹
const characters = ref([])
const characterId = ref(null)
const traceLimit = ref(10)

const expandedKeys = ref([])

const characterNameOf = computed(() => {
  const c = characters.value.find(x => x.id === characterId.value)
  return c ? c.name : `#${characterId.value}`
})

/** 链路行（后端已按 trace_id 聚合：一次决策一条链路） */
const chains = computed(() => rows.value)

const sceneOptions = [
  { value: 'dialog', label: '对话' },
  { value: 'action', label: '行动' },
  { value: 'card_gen', label: '角色卡生成' },
  { value: 'relation_gen', label: '关系生成' },
  { value: 'memory', label: '记忆抽取' },
  { value: 'title_gen', label: '标题生成' },
  { value: 'location_extract', label: '地点提取' },
  { value: 'crowd_ai', label: '居民AI调度' },
  { value: 'crowd_category_gen', label: '字段字典拟定' },
  { value: 'ordinary_npc_gen', label: '居民批量生成' },
  { value: 'world_event', label: '世界事件' },
  { value: 'world_event_polish', label: '事件润色' },
  { value: 'world_segment', label: '世界观分段' },
  { value: 'world_segment_characters', label: '角色分离' },
  { value: 'world_time_infer', label: '世界时间推断' },
  { value: 'import', label: '导入解析' },
  { value: 'embedding', label: '向量化（RAG）' },
  { value: 'reflection', label: '反思' },
  { value: 'goal_gen', label: '目标生成' },
  { value: 'plan_day', label: '日计划' }
]

/**
 * 环节 → 决策阶段（感知→思考→行动→学习 五段，D5 F19）。
 * stepType=perception→感知；ai_call+scene=action→思考（决策）；action→行动；
 * event→事件（行动产出）；memory/reflection/plan 类→学习；其他 ai_call→扮演。
 */
function stageOf(s) {
  const st = s.stepType || 'ai_call'
  if (st === 'perception') return { key: 'perception', label: '感知', color: 'info' }
  if (st === 'action') return { key: 'action', label: '行动', color: 'warning' }
  if (st === 'event') return { key: 'event', label: '事件', color: 'success' }
  if (st === 'memory') return { key: 'learning', label: '学习', color: 'primary' }
  if (st === 'ai_call') {
    const sc = s.scene || ''
    if (sc === 'action') return { key: 'thinking', label: '思考', color: 'danger' }
    if (sc === 'memory' || sc === 'reflection' || sc === 'goal_gen' || sc === 'plan_day') {
      return { key: 'learning', label: '学习', color: 'primary' }
    }
    return { key: 'roleplay', label: '扮演', color: '' }
  }
  return { key: 'other', label: '其他', color: 'info' }
}

function onExpand(row, expandedRows) {
  expandedKeys.value = expandedRows.map(r => r.key)
}

/** 打开 AI 调用详情（新页面 #/ai-call-detail?id=） */
function openCallDetail(id) {
  router.push({ path: '/ai-call-detail', query: { id } })
}

/** 项目切换 → 重载角色候选 */
async function onProjectChange() {
  characterId.value = null
  characters.value = []
  if (filters.value.projectId) {
    try {
      const r = await get(`/projects/${filters.value.projectId}/characters`)
      characters.value = r?.data || []
    } catch (_) { /* 角色列表不可用时忽略 */ }
  }
  search()
}

async function onCharacterChange() {
  page.value = 1
  load()
}

async function load() {
  try {
    if (characterId.value) {
      // 每角色最近 N 次决策轨迹（D5 F19）
      const r = await get(`/debug/ai-calls/character/${characterId.value}/traces`, { limit: traceLimit.value })
      rows.value = r.items || []
      total.value = (r.items || []).length
    } else {
      const params = {
        page: page.value,
        size: size.value,
        projectId: filters.value.projectId || undefined,
        scene: filters.value.scene || undefined,
        status: filters.value.status || undefined,
        keyword: filters.value.keyword || undefined,
        startDate: dateRange.value?.[0],
        endDate: dateRange.value?.[1]
      }
      const r = await get('/debug/ai-calls/traces', params)
      rows.value = r.items || []
      total.value = r.total || 0
    }
  } catch (e) {
    ElMessage.error('决策链路加载失败：' + e.message)
  }
}

function search() {
  page.value = 1
  load()
}

function reset() {
  filters.value = { projectId: '', scene: '', status: '', keyword: '' }
  dateRange.value = null
  characterId.value = null
  traceLimit.value = 10
  characters.value = []
  page.value = 1
  load()
}

/** trace_id 短显（前 12 位） */
function shortTraceId(id) {
  if (!id) return '—'
  return id.length > 12 ? id.slice(0, 12) : id
}

function shortTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return d.toLocaleString()
}

function statusType(s) {
  if (s === 'success') return 'success'
  if (s === 'degraded') return 'warning'
  return 'danger'
}

function statusLabel(s) {
  return { success: '成功', failed: '失败', degraded: '降级' }[s] || s || '-'
}

onMounted(() => {
  if (route.query.projectId) {
    filters.value.projectId = route.query.projectId
    onProjectChange()
  } else {
    load()
  }
})
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.mode-tip {
  font-size: 12px;
}
.ai-table {
  cursor: pointer;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.trace-steps {
  padding: 10px 14px 14px 34px;
  background: var(--bg-layer-2);
}
.trace-steps-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-regular);
  margin-bottom: 10px;
}
.step-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.step-card {
  width: 230px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  background: var(--bg-layer-1);
  padding: 8px 10px;
  cursor: pointer;
  transition: all 0.2s var(--dsw-ease);
  border-left: 3px solid var(--border-l2);
}
.step-card:hover {
  border-color: var(--accent-border);
  box-shadow: var(--shadow-lv2);
  transform: translateY(-1px);
}
.step-card.st-perception { border-left-color: var(--state-info, #909399); }
.step-card.st-thinking { border-left-color: var(--state-error, #f56c6c); }
.step-card.st-action { border-left-color: var(--state-warning, #e6a23c); }
.step-card.st-event { border-left-color: var(--state-success, #67c23a); }
.step-card.st-learning { border-left-color: var(--accent); }
.step-card.st-roleplay { border-left-color: var(--accent-border); }
.step-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.step-card-head .spacer { flex: 1; }
.step-card-body {
  font-size: 12px;
}
.step-line {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 4px;
}
.step-line .model {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}
.step-tokens {
  display: flex;
  gap: 10px;
  margin-bottom: 4px;
  font-size: 11px;
}
.step-tokens .tok-in { color: var(--state-success, #67c23a); }
.step-tokens .tok-out { color: var(--state-error, #f56c6c); }
.step-tokens .dur { color: var(--text-tertiary); }
.step-content {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  max-height: 60px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  word-break: break-all;
}
.step-card-foot {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-tertiary);
}
.dim {
  color: var(--text-secondary);
}
.mono {
  font-family: var(--font-mono, Consolas, 'Courier New', monospace);
}
</style>
