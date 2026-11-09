<template>
  <div class="temporal">
    <div class="head-row">
      <h2 class="page-title">时间性验证 V1.1.4（否定状态 / 因果 / 7 日分布 / 记忆衰减）</h2>
      <div class="toolbar">
        <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="reload">
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!projectId || running" :loading="running" @click="runProbes">
          跑一批探针
        </el-button>
        <el-button size="small" :disabled="!projectId" @click="reload">刷新报告</el-button>
        <span class="hint">前提：世界暂停；test.enabled=false 时本页 403</span>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="card" />

    <template v-if="projectId && report">
      <!-- 概览 -->
      <el-card shadow="never" class="card">
        <div class="cards">
          <div class="metric">
            <div class="k">世界时刻</div>
            <div class="v">{{ report.weekdayLabel }} · {{ report.weekend ? '周末' : '工作日' }}</div>
            <div class="s">gameSecond={{ report.gameSecond }}</div>
          </div>
          <div class="metric">
            <div class="k">探针判定</div>
            <div class="v">{{ passCount }} PASS / {{ failCount }} FAIL</div>
            <div class="s">共 {{ probeTotal }} 条（含对照组）</div>
          </div>
          <div class="metric">
            <div class="k">行为分布</div>
            <div class="v">{{ report.distribution?.characters?.length || 0 }} 个角色 · {{ report.distribution?.decisionCount || 0 }} 条决策</div>
            <div class="s">JSD + 10000 次置换检验</div>
          </div>
          <div class="metric">
            <div class="k">记忆衰减</div>
            <div class="v" :class="{ bad: !report.decay?.monotonic }">事件基准 ρ={{ fmt(report.decay?.eventSpearman) }}</div>
            <div class="s">{{ report.decay?.verdict }}</div>
          </div>
        </div>
      </el-card>

      <!-- 行为分布 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">7 日行为分布（工作日 vs 周末 / 昼 vs 夜 / 逐日一致性）</div>
        </template>
        <el-table :data="report.distribution?.characters || []" size="small" border>
          <el-table-column prop="characterName" label="角色" width="110" />
          <el-table-column prop="characterRef" label="ref" width="110" />
          <el-table-column prop="total" label="决策" width="70" />
          <el-table-column label="动作分布" min-width="150">
            <template #default="{ row }">
              <span v-for="(v, k) in row.actions" :key="k" class="chip">{{ k }} {{ v }}</span>
            </template>
          </el-table-column>
          <el-table-column label="熵" width="70">
            <template #default="{ row }">{{ fmt(row.entropy) }}</template>
          </el-table-column>
          <el-table-column label="坍缩度" width="80">
            <template #default="{ row }">{{ fmt(row.collapseRatio) }}</template>
          </el-table-column>
          <el-table-column label="昼/夜 JSD (p)" width="150">
            <template #default="{ row }">{{ pairText(row, 'day_night') }}</template>
          </el-table-column>
          <el-table-column label="工作日/周末 JSD (p)" width="180">
            <template #default="{ row }">{{ pairText(row, 'weekday_weekend') }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 探针明细 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">探针明细（full = 完整注入；no_state / ledger_mutated = 对照组与配对反事实）</div>
        </template>
        <el-table :data="probes" size="small" border max-height="420">
          <el-table-column prop="probeType" label="类型" width="130" />
          <el-table-column prop="variant" label="变体" width="120" />
          <el-table-column prop="characterRef" label="角色" width="100" />
          <el-table-column label="判定" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.verdict)">{{ row.verdict }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="断言说明" min-width="320">
            <template #default="{ row }">
              <div>{{ row.assertionDetail }}</div>
              <div class="dim small">Q: {{ row.question }}</div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 记忆衰减 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">记忆时距衰减（打分层双基准；真实记忆 {{ report.decay?.memories || 0 }} 条，旧事被刷新 {{ fmt(report.decay?.refreshRatio * 100, 1) }}%）</div>
        </template>
        <div class="decay">
          <div v-for="curve in report.decay?.curves || []" :key="curve.basis" class="curve-box">
            <div class="curve-title">{{ curve.basis }}（ρ={{ fmt(curve.spearman) }}）</div>
            <svg viewBox="0 0 340 110" class="spark">
              <polyline :points="curvePoints(curve)" fill="none" stroke="#409eff" stroke-width="2" />
            </svg>
            <div class="dim small">横轴 = 记忆年龄（对数刻度），纵轴 = 检索得分</div>
          </div>
          <div class="buckets">
            <div class="curve-title">真实记忆年龄分布</div>
            <div v-for="(v, k) in report.decay?.ageBuckets || {}" :key="k" class="bucket">
              <span>{{ k }}</span><span class="dim">{{ v }}</span>
            </div>
          </div>
        </div>
      </el-card>

      <!-- 因果断言 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">因果蕴含断言（R1 目标未解析 / R2 引用失效实体 / R3 路径未成立）</div>
        </template>
        <div class="dim small">扫描 {{ report.causal?.decisionsScanned || 0 }} 条决策；违规 {{ (report.causal?.violations || []).length }} 条</div>
        <el-table :data="report.causal?.violations || []" size="small" border max-height="240" class="card">
          <el-table-column prop="rule" label="规则" width="200" />
          <el-table-column prop="characterRef" label="角色" width="100" />
          <el-table-column prop="detail" label="说明" min-width="320" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { get, post } from '../api'

const projects = ref([])
const projectId = ref(null)
const report = ref(null)
const probes = ref([])
const running = ref(false)
const error = ref('')

const unwrap = (r, d) => (r?.data !== undefined ? r.data : (r ?? d))
const fmt = (n, d = 2) => (n === null || n === undefined || Number.isNaN(Number(n)) ? '-' : Number(n).toFixed(d))

const probeTotal = computed(() => probes.value.length)
const passCount = computed(() => probes.value.filter(p => p.verdict === 'PASS').length)
const failCount = computed(() => probes.value.filter(p => p.verdict === 'FAIL').length)

function tagType(v) {
  if (v === 'PASS') return 'success'
  if (v === 'FAIL') return 'danger'
  if (v === 'ERROR') return 'danger'
  return 'info'
}

function pairText(row, dim) {
  const p = (row.pairs || []).find(x => x.dimension === dim)
  if (!p) return '-'
  return `${fmt(p.jsd)} (p=${p.pValue < 0.001 ? '<0.001' : fmt(p.pValue, 3)})${p.significant ? ' *' : ''}`
}

function curvePoints(curve) {
  const pts = curve.points || []
  if (!pts.length) return ''
  const ys = pts.map(p => p.score)
  const min = Math.min(...ys)
  const max = Math.max(...ys)
  const span = max - min || 1
  const maxAge = Math.log(pts[pts.length - 1].ageHours + 1)
  return pts.map(p => {
    const x = (Math.log(p.ageHours + 1) / (maxAge || 1)) * 320 + 10
    const y = 100 - ((p.score - min) / span) * 90
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

async function loadProjects() {
  try {
    const r = await get('/worlds')
    projects.value = Array.isArray(r) ? r : (r?.data || [])
    if (projects.value.length && !projectId.value) projectId.value = projects.value[0]?.id
  } catch (e) {
    projects.value = []
  }
}

async function reload() {
  if (!projectId.value) return
  error.value = ''
  try {
    report.value = unwrap(await get(`/worlds/${projectId.value}/temporal-audit/report`), null)
    probes.value = unwrap(await get(`/worlds/${projectId.value}/temporal-audit/probes`), [])
  } catch (e) {
    error.value = `加载失败：${e.message}`
  }
}

async function runProbes() {
  if (!projectId.value) return
  running.value = true
  error.value = ''
  try {
    const r = await post(`/worlds/${projectId.value}/temporal-audit/probes`, {})
    const res = unwrap(r, {})
    error.value = `批次 ${res.runTag}：PASS ${res.pass} / FAIL ${res.fail} / SKIP ${res.skip} / 其它 ${res.inconclusive + res.error}`
    await reload()
  } catch (e) {
    error.value = `探针批次失败：${e.message}`
  } finally {
    running.value = false
  }
}

onMounted(async () => {
  await loadProjects()
  await reload()
})
</script>

<style scoped>
.temporal { padding: 12px 16px; }
.head-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.page-title { font-size: 18px; margin: 0; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.proj-select { width: 200px; }
.hint { font-size: 12px; color: var(--text-secondary); }
.card { margin-top: 12px; }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.metric { border: 1px solid var(--border-color, #e4e7ed); border-radius: 6px; padding: 10px 12px; }
.metric .k { font-size: 12px; color: var(--text-secondary); }
.metric .v { font-size: 16px; font-weight: 600; margin: 4px 0; }
.metric .v.bad { color: #e6a23c; }
.metric .s { font-size: 12px; color: var(--text-secondary); line-height: 1.4; }
.chip { display: inline-block; margin: 0 6px 2px 0; padding: 0 6px; border-radius: 4px; background: var(--bg-nav-hover, #f2f3f5); font-size: 12px; }
.dim { color: var(--text-secondary); }
.small { font-size: 12px; }
.decay { display: flex; gap: 20px; flex-wrap: wrap; }
.curve-box { width: 360px; }
.curve-title { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
.spark { width: 100%; height: 110px; background: var(--bg-nav, #fafafa); border-radius: 4px; }
.buckets { min-width: 200px; }
.bucket { display: flex; justify-content: space-between; font-size: 12px; padding: 3px 0; border-bottom: 1px dashed var(--border-color, #ebeef5); }
</style>
