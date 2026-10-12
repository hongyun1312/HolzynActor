<template>
  <div class="ai-call-detail">
    <div class="head-row">
      <h2 class="page-title">AI 调用详情（JSON 结构化渲染）</h2>
      <div class="raw-toggle">
        <span :class="{ dim: showRawJson }">结构化渲染</span>
        <el-switch v-model="showRawJson" size="small" />
        <span :class="{ dim: !showRawJson }">原始 JSON</span>
        <span class="dim mono" v-if="detail">#{{ detail.id }} · {{ detail.sceneName || detail.scene }}</span>
      </div>
    </div>

    <div class="split">
      <!-- 左侧：调用记录列表（组合标题 = 场景名 · 角色名 · 模型 + 时间等简要信息） -->
      <aside class="list-panel">
        <div class="filter-bar">
          <el-input v-model="filters.projectId" size="small" placeholder="项目 ID" clearable style="width: 88px" />
          <el-select v-model="filters.scene" size="small" placeholder="场景" clearable style="width: 110px">
            <el-option v-for="s in sceneOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-select v-model="filters.status" size="small" placeholder="状态" clearable style="width: 88px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="降级" value="degraded" />
          </el-select>
        </div>
        <div class="filter-bar">
          <el-input v-model="filters.keyword" size="small" placeholder="关键词" clearable style="flex:1" @keyup.enter="search" />
          <el-button size="small" type="primary" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>

        <div class="call-list">
          <div v-for="row in rows" :key="row.id" class="call-item" :class="{ active: row.id === selectedId }"
            @click="select(row.id)">
            <div class="call-title">
              <span class="scene-tag" :class="'sc-' + statusClass(row.status)">{{ row.sceneName || row.scene }}</span>
              <span class="call-name" :title="compositeTitle(row)">{{ compositeTitle(row) }}</span>
            </div>
            <div class="call-meta mono">
              <span>{{ shortTime(row.createdAt) }}</span>
              <span :class="'st-' + row.status">{{ statusLabel(row.status) }}</span>
              <span v-if="row.stepType !== 'ai_call'">{{ stepTypeLabel(row.stepType) }}</span>
              <span v-if="row.stepType === 'ai_call'">{{ row.tokenIn }}/{{ row.tokenOut }} tok</span>
              <span>{{ row.durationMs }}ms</span>
            </div>
          </div>
          <div v-if="!loading && rows.length === 0" class="list-empty">暂无调用记录</div>
          <div v-if="loading" class="list-empty">加载中…</div>
        </div>

        <div class="pager">
          <el-pagination small layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page"
            @current-change="(p) => { page = p; loadList() }" />
        </div>
      </aside>

      <!-- 右侧：结构化详情（参考结构化角色卡分节渲染逻辑；默认不显示代码） -->
      <section class="detail-panel">
        <template v-if="!detail">
          <div class="detail-empty">← 从左侧选择一条调用记录查看详情</div>
        </template>

        <!-- 原始 JSON 开关（默认关）：显示完整 detail_json 文本 -->
        <template v-else-if="showRawJson">
          <div class="raw-json-head">
            <span>完整详情 JSON（detail_json）</span>
            <el-button size="small" text type="primary" @click="copy(prettyRaw)">复制</el-button>
          </div>
          <pre class="raw-json mono">{{ prettyRaw }}</pre>
        </template>

        <!-- 结构化渲染（默认） -->
        <template v-else>
          <template v-if="dj">
            <!-- ① 时间轴 -->
            <div class="p-section">
              <div class="p-title">时间轴</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">开始时间</span><span class="v mono">{{ dj.time || '—' }}</span></div>
                <div class="p-cell"><span class="k">结束时间</span><span class="v mono">{{ dj.end_time || '—' }}</span></div>
                <div class="p-cell"><span class="k">耗时</span><span class="v mono">{{ dj.duration_ms }} ms</span></div>
              </div>
            </div>

            <!-- ② 业务上下文 -->
            <div class="p-section">
              <div class="p-title">业务上下文</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">场景</span><span class="v">{{ detail.sceneName || dj.scene }}</span></div>
                <div class="p-cell"><span class="k">项目</span><span class="v">{{ detail.projectName || (dj.project_id ?? '—') }}</span></div>
                <div class="p-cell"><span class="k">角色</span><span class="v">{{ detail.characterName || (dj.character_id ?? '无角色') }}</span></div>
                <div class="p-cell"><span class="k">决策链路 trace</span>
                  <span class="v mono">{{ dj.trace_id ? shortTrace(dj.trace_id) : '—' }}</span></div>
                <div class="p-cell"><span class="k">尝试</span><span class="v mono">{{ dj.attempt ?? 1 }}</span></div>
                <div class="p-cell"><span class="k">环节类型</span><span class="v">{{ stepTypeLabel(dj.step_type) }}</span></div>
              </div>
            </div>

            <!-- ③ 供应商与模型 -->
            <div class="p-section" v-if="dj.provider">
              <div class="p-title">供应商与模型</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">配置 ID</span><span class="v mono">{{ dj.provider.provider_id ?? '—' }}</span></div>
                <div class="p-cell"><span class="k">模型</span><span class="v mono">{{ dj.provider.model || '—' }}</span></div>
                <div class="p-cell" :span="2"><span class="k">Base URL</span><span class="v mono">{{ dj.provider.base_url || '—' }}</span></div>
              </div>
            </div>

            <!-- ④ 请求参数 -->
            <div class="p-section" v-if="dj.request_params">
              <div class="p-title">请求参数</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">温度</span><span class="v mono">{{ dj.request_params.temperature ?? '—' }}</span></div>
                <div class="p-cell"><span class="k">最大输出</span><span class="v mono">{{ dj.request_params.max_tokens ?? '—' }}</span></div>
                <div class="p-cell"><span class="k">流式</span>
                  <el-tag size="small" :type="dj.request_params.stream ? 'success' : 'info'" effect="plain">
                    {{ dj.request_params.stream ? '是' : '否' }}</el-tag></div>
                <div class="p-cell"><span class="k">JSON 模式</span>
                  <el-tag size="small" :type="dj.request_params.json_mode ? 'primary' : 'info'" effect="plain">
                    {{ dj.request_params.json_mode ? '是' : '否' }}</el-tag></div>
              </div>
            </div>

            <!-- ⑤ 输入 -->
            <div class="p-section" v-if="dj.input">
              <div class="p-title">输入</div>
              <div class="p-label">摘要</div>
              <div class="p-text">{{ dj.input.summary || '—' }}</div>
              <div class="p-label">结构化消息（{{ listLen(dj.input.messages) }} 条）</div>
              <div v-if="listLen(dj.input.messages)" class="msg-list">
                <el-collapse v-model="openMsgs">
                  <el-collapse-item v-for="(m, i) in dj.input.messages" :key="i" :name="'m' + i">
                    <template #title>
                      <span class="msg-role" :class="'role-' + (m.role || '')">{{ roleLabel(m.role) }}</span>
                      <span class="msg-preview">{{ preview(m.content) }}</span>
                    </template>
                    <pre class="msg-content mono">{{ m.content }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </div>
              <div class="p-label" v-if="dj.input.raw">原始请求体</div>
              <div v-if="dj.input.raw" class="raw-collapse">
                <el-collapse>
                  <el-collapse-item title="查看原始请求 JSON">
                    <pre class="raw-json mono">{{ prettyNode(dj.input.raw) }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </div>

            <!-- ⑥ 输出 -->
            <div class="p-section" v-if="dj.output">
              <div class="p-title">输出</div>
              <div class="p-label">摘要</div>
              <div class="p-text">{{ dj.output.summary || '—' }}</div>
              <div class="p-label" v-if="dj.output.text != null">回复全文</div>
              <div class="p-text" v-if="dj.output.text != null">{{ dj.output.text }}</div>
              <div class="p-label" v-if="dj.output.raw">原始响应体</div>
              <div v-if="dj.output.raw" class="raw-collapse">
                <el-collapse>
                  <el-collapse-item title="查看原始响应 JSON">
                    <pre class="raw-json mono">{{ prettyNode(dj.output.raw) }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </div>

            <!-- ⑦ 用量 -->
            <div class="p-section" v-if="dj.usage">
              <div class="p-title">Token 用量（不含成本）</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">输入 token</span><span class="v mono">{{ dj.usage.token_in }}</span></div>
                <div class="p-cell"><span class="k">输出 token</span><span class="v mono">{{ dj.usage.token_out }}</span></div>
                <div class="p-cell"><span class="k">缓存命中</span><span class="v mono">{{ dj.usage.cache_hit }}</span></div>
                <div class="p-cell"><span class="k">缓存未命中</span><span class="v mono">{{ dj.usage.cache_miss }}</span></div>
              </div>
            </div>

            <!-- ⑧ 状态与错误 -->
            <div class="p-section">
              <div class="p-title">状态与错误</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">状态</span>
                  <el-tag :type="statusType(dj.status)" size="small">{{ statusLabel(dj.status) }}</el-tag></div>
                <div class="p-cell"><span class="k">重试次数</span><span class="v mono">{{ dj.retry_count ?? 0 }}</span></div>
              </div>
              <div v-if="dj.error" class="err-box">
                <div class="err-title">错误信息{{ dj.error.code ? `（${dj.error.code}）` : '' }}</div>
                <div class="err-text">{{ dj.error.message }}</div>
              </div>
            </div>

            <!-- ⑨ RAG/记忆上下文（预留） -->
            <div class="p-section" v-if="dj.context">
              <div class="p-title">上下文（RAG/记忆引用）</div>
              <div class="p-text mono">{{ prettyNode(dj.context) }}</div>
            </div>

            <!-- ⑩ 备注 -->
            <div class="p-section" v-if="dj.remark">
              <div class="p-title">备注</div>
              <div class="p-text">{{ dj.remark }}</div>
            </div>
          </template>

          <!-- 无 detail_json 的旧记录（2026-08-24 前生成 / 非 AI 环节）：基础字段兜底渲染 -->
          <template v-else>
            <div class="no-detail-tip">
              <el-alert type="info" :closable="false" show-icon
                title="该记录无详情 JSON（2026-08-24 前生成，或为非 AI 环节行），以下为数据库基础字段。" />
            </div>
            <div class="p-section">
              <div class="p-title">基础信息</div>
              <div class="p-grid">
                <div class="p-cell"><span class="k">ID</span><span class="v mono">#{{ detail.id }}</span></div>
                <div class="p-cell"><span class="k">场景</span><span class="v">{{ detail.sceneName || detail.scene }}</span></div>
                <div class="p-cell"><span class="k">状态</span>
                  <el-tag :type="statusType(detail.status)" size="small">{{ statusLabel(detail.status) }}</el-tag></div>
                <div class="p-cell"><span class="k">模型</span><span class="v mono">{{ detail.model || '—' }}</span></div>
                <div class="p-cell"><span class="k">环节类型</span><span class="v">{{ stepTypeLabel(detail.stepType) }}</span></div>
                <div class="p-cell"><span class="k">创建时间</span><span class="v mono">{{ detail.createdAt }}</span></div>
              </div>
            </div>
            <div class="p-section" v-if="detail.content">
              <div class="p-title">环节内容（非 AI 环节）</div>
              <div class="p-text">{{ detail.content }}</div>
            </div>
            <div class="p-section" v-if="detail.systemPrompt">
              <div class="p-title">System Prompt</div>
              <pre class="msg-content mono">{{ detail.systemPrompt }}</pre>
            </div>
            <div class="p-section" v-if="detail.userPrompt">
              <div class="p-title">User Prompt</div>
              <pre class="msg-content mono">{{ detail.userPrompt }}</pre>
            </div>
            <div class="p-section" v-if="detail.response">
              <div class="p-title">模型输出</div>
              <pre class="msg-content mono">{{ detail.response }}</pre>
            </div>
            <div class="p-section" v-if="detail.errorMessage">
              <div class="p-title">错误信息</div>
              <div class="err-box"><div class="err-text">{{ detail.errorMessage }}</div></div>
            </div>
          </template>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get } from '../api'

const route = useRoute()

const filters = ref({ projectId: '', scene: '', status: '', keyword: '' })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const selectedId = ref(null)
const detail = ref(null)
const showRawJson = ref(false)
const openMsgs = ref([])

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

/** 组合标题：场景名 · 角色名 · 模型 */
function compositeTitle(row) {
  const scene = row.sceneName || row.scene || '未知场景'
  const role = row.characterName || '无角色'
  const model = row.model || '—'
  return `${scene} · ${role} · ${model}`
}

/** 解析 detail_json → 对象 */
const dj = computed(() => {
  if (!detail.value?.detailJson) return null
  try {
    const p = JSON.parse(detail.value.detailJson)
    return p && typeof p === 'object' ? p : null
  } catch (_) {
    return null
  }
})

/** 原始 JSON 文本（detail_json 美化；无则基础字段 JSON） */
const prettyRaw = computed(() => {
  if (detail.value?.detailJson) {
    try {
      return JSON.stringify(JSON.parse(detail.value.detailJson), null, 2)
    } catch (_) {
      return detail.value.detailJson
    }
  }
  return JSON.stringify({
    id: detail.value?.id,
    scene: detail.value?.scene,
    status: detail.value?.status,
    stepType: detail.value?.stepType,
    content: detail.value?.content,
    projectId: detail.value?.projectId,
    characterId: detail.value?.characterId,
    model: detail.value?.model,
    tokenIn: detail.value?.tokenIn,
    tokenOut: detail.value?.tokenOut,
    durationMs: detail.value?.durationMs,
    createdAt: detail.value?.createdAt,
    errorMessage: detail.value?.errorMessage
  }, null, 2)
})

async function loadList() {
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value,
      projectId: filters.value.projectId || undefined,
      scene: filters.value.scene || undefined,
      status: filters.value.status || undefined,
      keyword: filters.value.keyword || undefined
    }
    const r = await get('/debug/ai-calls', params)
    rows.value = r.items || []
    total.value = r.total || 0
  } catch (e) {
    ElMessage.error('调用列表加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function select(id) {
  selectedId.value = id
  try {
    detail.value = await get(`/debug/ai-calls/${id}`)
    showRawJson.value = false
    openMsgs.value = []
  } catch (e) {
    ElMessage.error('详情加载失败：' + e.message)
  }
}

function search() {
  page.value = 1
  loadList()
}

function reset() {
  filters.value = { projectId: '', scene: '', status: '', keyword: '' }
  page.value = 1
  loadList()
}

function copy(text) {
  navigator.clipboard?.writeText(text || '').then(() => ElMessage.success('已复制'))
}

// ===== 渲染工具（参考结构化角色卡分节渲染逻辑） =====

function roleLabel(role) {
  return { system: '系统', user: '用户', assistant: '助手', tool: '工具' }[role] || role || '消息'
}
function stepTypeLabel(t) {
  return { ai_call: 'AI 调用', perception: '感知', action: '行动', event: '事件', memory: '记忆' }[t] || t || 'AI 调用'
}
function statusType(s) {
  if (s === 'success') return 'success'
  if (s === 'degraded') return 'warning'
  return 'danger'
}
function statusLabel(s) {
  return { success: '成功', failed: '失败', degraded: '降级' }[s] || s || '-'
}
function statusClass(s) {
  return { success: 'ok', failed: 'bad', degraded: 'warn' }[s] || 'ok'
}
function shortTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}
function shortTrace(id) {
  if (!id) return '—'
  return id.length > 12 ? id.slice(0, 12) + '…' : id
}
function listLen(v) {
  return Array.isArray(v) ? v.length : 0
}
function preview(s) {
  if (!s) return ''
  const t = String(s)
  return t.length > 60 ? t.slice(0, 60) + '…' : t
}
function prettyNode(v) {
  if (v == null) return '—'
  if (typeof v === 'string') {
    try {
      return JSON.stringify(JSON.parse(v), null, 2)
    } catch (_) {
      return v
    }
  }
  return JSON.stringify(v, null, 2)
}

onMounted(() => {
  loadList()
  // 支持从链路卡片/其他入口直达：?id= 自动加载详情
  if (route.query.id) {
    select(Number(route.query.id))
  }
})

// 路由参数变化（同页再跳转）时重选
watch(() => route.query.id, (id) => {
  if (id) select(Number(id))
})
</script>

<style scoped>
.head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  color: var(--text-primary);
}
.raw-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-regular);
}
.split {
  display: flex;
  gap: 14px;
  height: calc(100vh - 130px);
  min-height: 480px;
}

/* ---- 左侧列表 ---- */
.list-panel {
  width: 400px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  background: var(--bg-layer-1);
  box-shadow: var(--shadow-lv1);
  overflow: hidden;
}
.filter-bar {
  display: flex;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-l2);
  flex-wrap: wrap;
}
.call-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.call-item {
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s var(--dsw-ease);
}
.call-item:hover {
  background: var(--bg-nav-hover);
}
.call-item.active {
  background: var(--accent-soft);
  border-color: var(--accent-border);
}
.call-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.scene-tag {
  flex-shrink: 0;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--bg-layer-2);
  color: var(--text-secondary);
}
.scene-tag.sc-ok { color: var(--state-success); }
.scene-tag.sc-bad { color: var(--state-error); }
.scene-tag.sc-warn { color: var(--state-warning); }
.call-name {
  font-size: 12px;
  color: var(--text-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.call-meta {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--text-tertiary);
  flex-wrap: wrap;
}
.call-meta .st-success { color: var(--state-success); }
.call-meta .st-failed { color: var(--state-error); }
.call-meta .st-degraded { color: var(--state-warning); }
.list-empty {
  padding: 30px 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}
.pager {
  padding: 8px 10px;
  border-top: 1px solid var(--border-l2);
  display: flex;
  justify-content: center;
}

/* ---- 右侧详情（结构化角色卡分节渲染风格） ---- */
.detail-panel {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  background: var(--bg-layer-1);
  box-shadow: var(--shadow-lv1);
  padding: 18px 20px;
}
.detail-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  font-size: 13px;
}
.p-section {
  margin-bottom: 18px;
}
.p-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border-l2);
}
.p-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin: 10px 0 6px;
}
.p-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px 16px;
}
.p-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.p-cell .k {
  font-size: 11px;
  color: var(--text-tertiary);
}
.p-cell .v {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.p-text {
  font-size: 13px;
  color: var(--text-regular);
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-layer-0);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
}
.msg-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.msg-role {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 4px;
  margin-right: 8px;
  background: var(--bg-layer-2);
  color: var(--text-secondary);
}
.msg-role.role-system { color: var(--accent-text); }
.msg-role.role-user { color: var(--state-success); }
.msg-role.role-assistant { color: var(--state-warning); }
.msg-preview {
  font-size: 12px;
  color: var(--text-tertiary);
}
.msg-content {
  margin: 0;
  padding: 10px;
  background: var(--bg-layer-3);
  border-radius: var(--radius-sm);
  color: var(--text-regular);
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.7;
  max-height: 260px;
  overflow-y: auto;
}
.raw-json-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-regular);
}
.raw-json {
  margin: 0;
  padding: 12px;
  background: var(--bg-layer-3);
  border-radius: var(--radius-sm);
  color: var(--text-regular);
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
  max-height: 70vh;
  overflow-y: auto;
}
.raw-collapse {
  margin-top: 4px;
}
.err-box {
  margin-top: 8px;
  border: 1px solid var(--state-error);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: var(--state-danger-bg);
}
.err-title {
  color: var(--state-error);
  font-weight: 600;
  margin-bottom: 4px;
  font-size: 12px;
}
.err-text {
  color: var(--state-error);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.no-detail-tip {
  margin-bottom: 14px;
}
.dim {
  color: var(--text-secondary);
}
.mono {
  font-family: var(--font-mono, Consolas, 'Courier New', monospace);
}
</style>
