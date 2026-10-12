<template>
  <div class="injection-preview">
    <div class="head-row">
      <h2 class="page-title">记忆注入预览（M5）</h2>
      <div class="head-controls">
        <el-select v-model="projectId" clearable placeholder="全部项目" size="small" style="width: 180px" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="characterId" clearable placeholder="全部角色" size="small" style="width: 150px">
          <el-option v-for="c in characters" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="scenario" size="small" style="width: 130px">
          <el-option v-for="s in scenarios" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-input v-model="query" size="small" placeholder="检索 query（决策/感知情境文本，可空）" style="width: 240px" clearable />
        <el-button size="small" type="primary" :loading="loading" :disabled="!projectId || !characterId" @click="load">预览</el-button>
      </div>
    </div>

    <el-alert v-if="!projectId || !characterId" type="info" :closable="false" class="hint-bar"
      title="请选择项目与角色后预览——分场景注入组合（§9）：L3 核心恒在 + L1 热缓冲 + L2 评分 top-K + 世界大事记 + 过往摘要（对话）" />

    <!-- 预算统计 -->
    <el-card v-if="data" shadow="never" class="block-card">
      <template #header>
        <div class="card-title">
          注入预算（§9.5 token 经济学）
          <span class="dim mono" style="font-weight:400; margin-left:8px">合计 {{ fmtNum(data.totalTokens) }} / ≤2400 token · 场景 {{ scenarioLabel(data.scenario) }}</span>
        </div>
      </template>
      <div class="budget-bars">
        <div v-for="seg in data.segments" :key="seg.key" class="budget-row">
          <span class="budget-key mono">{{ seg.key }}</span>
          <span class="budget-title">{{ seg.title }}</span>
          <el-progress :percentage="budgetPct(seg)" :stroke-width="14" :show-text="false" class="budget-bar" />
          <span class="budget-tokens mono">{{ seg.tokens }} tok</span>
        </div>
      </div>
    </el-card>

    <!-- 各段文本 -->
    <el-card v-if="data" shadow="never" class="block-card">
      <template #header><div class="card-title">各段组装文本（渲染结果 = 各段按序拼接）</div></template>
      <div v-if="data.segments.length === 0" class="side-empty">无内容——该角色当前无可注入记忆（各段均空）</div>
      <div v-for="seg in data.segments" :key="seg.key" class="segment-block">
        <div class="segment-head">
          <span class="segment-tag mono">{{ seg.key }}</span>
          <span class="segment-title">{{ seg.title }}</span>
          <span class="dim mono">≈ {{ seg.tokens }} token</span>
        </div>
        <pre class="segment-text">{{ seg.text }}</pre>
        <el-table v-if="seg.hits && seg.hits.length" :data="seg.hits" size="small" stripe class="hit-table">
          <el-table-column label="id" width="70"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }">{{ typeLabel(row.type) }}</template></el-table-column>
          <el-table-column label="重要度" width="80" align="center"><template #default="{ row }"><span class="mono">{{ row.importance }}</span></template></el-table-column>
          <el-table-column label="得分" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.score }}</span></template></el-table-column>
          <el-table-column label="过时" width="70" align="center">
            <template #default="{ row }"><el-tag v-if="row.stale" size="small" type="warning" effect="plain">可能过时</el-tag><span v-else class="dim">-</span></template>
          </el-table-column>
          <el-table-column label="token" width="80" align="right"><template #default="{ row }"><span class="mono">{{ row.tokens }}</span></template></el-table-column>
          <el-table-column label="内容" min-width="240" show-overflow-tooltip><template #default="{ row }">{{ row.content }}</template></el-table-column>
        </el-table>
      </div>
    </el-card>

    <div v-else class="dim" style="font-size:12px">选择项目/角色/场景后点击「预览」，展示实际注入到对话/决策/感知/反思的组装组合与预算消耗。</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get } from '../api'
// 统一身份引用（G1a：special:/crowd: 已废弃，一律 npc:{id}）
import { npcRef } from '../utils/npcRef'

const data = ref(null)
const projects = ref([])
const characters = ref([])
const projectId = ref(null)
const characterId = ref(null)
const scenario = ref('dialog')
const query = ref('')
const loading = ref(false)

const scenarios = [
  { value: 'dialog', label: '对话 dialog' },
  { value: 'decision', label: '决策 decision' },
  { value: 'perception', label: '感知 perception' },
  { value: 'reflection', label: '反思 reflection' }
]

async function loadProjects() {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
  } catch (_) { /* 忽略 */ }
}

async function onProjectChange() {
  characterId.value = null
  characters.value = []
  data.value = null
  if (projectId.value) {
    try {
      const r = await get(`/projects/${projectId.value}/characters`)
      characters.value = r?.data || []
    } catch (_) { /* 忽略 */ }
  }
}

async function load() {
  if (!projectId.value || !characterId.value) return
  loading.value = true
  try {
    data.value = await get(`/projects/${projectId.value}/memory-injection/preview`, {
      characterRef: npcRef(characterId.value),
      scenario: scenario.value,
      query: query.value || undefined
    })
  } catch (e) {
    console.error('注入预览加载失败', e)
  } finally {
    loading.value = false
  }
}

function budgetPct(seg) {
  const caps = { core: 800, working: 400, l2: 1200, project: 1200, summary: 1200, reflection: 1200 }
  const cap = caps[seg.key] || 1200
  return Math.min(100, Math.round((seg.tokens / cap) * 100))
}

function scenarioLabel(v) {
  const s = scenarios.find(x => x.value === v)
  return s ? s.label : v
}

function typeLabel(t) {
  return ({ semantic: '语义', episodic: '情节', reflection: '反思', plan: '计划' })[t] || t
}

function fmtNum(v) {
  return (v || 0).toLocaleString()
}

onMounted(loadProjects)
</script>

<style scoped>
.page-title { margin: 0; font-size: 18px; color: var(--text-primary); }
.head-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.head-controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hint-bar { margin-bottom: 14px; }
.block-card { margin-bottom: 14px; background: var(--bg-layer-1); border: 1px solid var(--border-l1); border-radius: var(--radius-lg); box-shadow: var(--shadow-lv1); }
.card-title { font-weight: 600; color: var(--text-primary); }
.budget-bars { display: flex; flex-direction: column; gap: 8px; }
.budget-row { display: flex; align-items: center; gap: 10px; }
.budget-key { width: 90px; color: var(--text-tertiary); font-size: 12px; }
.budget-title { width: 150px; color: var(--text-secondary); font-size: 12px; }
.budget-bar { flex: 1; }
.budget-tokens { width: 70px; text-align: right; color: var(--text-secondary); font-size: 12px; }
.segment-block { border: 1px solid var(--border-l1); border-radius: var(--radius-md); padding: 10px 12px; margin-bottom: 10px; }
.segment-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.segment-tag { font-size: 11px; color: var(--accent-text); background: var(--accent-soft); padding: 2px 8px; border-radius: 4px; }
.segment-title { font-weight: 600; color: var(--text-primary); font-size: 13px; }
.segment-text { margin: 0; padding: 8px 10px; background: var(--bg-base); border: 1px dashed var(--border-l1); border-radius: 6px; font-size: 12px; line-height: 1.7; color: var(--text-regular); white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
.hit-table { margin-top: 8px; }
.side-empty { color: var(--text-tertiary); font-size: 13px; padding: 12px 0; }
.dim { color: var(--text-tertiary); font-size: 12px; }
.mono { font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
</style>
