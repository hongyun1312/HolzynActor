<template>
  <div class="project-usage-settings">
    <!-- 项目级/全局用量说明（双模式；内嵌设置弹窗时由弹窗顶部说明承担） -->
    <el-alert v-if="!embedded && projectId" type="success" :closable="false" show-icon class="usage-alert"
      title="AI 用量为项目级统计（随 .holzyn 包导入导出）。"
      description="本页默认统计当前项目的用量（usage/stats 按 projectId 过滤，明细含角色维度），支持近 7/30 天与自定义时间范围筛选。" />
    <el-alert v-else-if="!embedded" type="success" :closable="false" show-icon class="usage-alert"
      title="AI 用量为全部项目的总用量（用户级聚合）。"
      description="本页统计当前用户所有项目的 AI 用量合计（不按项目过滤），支持按项目 / 场景 / 模型 / 日期维度查看，支持近 7/30 天与自定义时间范围筛选。" />

    <!-- 统计概览卡片 -->
    <div v-loading="loading" class="usage-cards">
      <div class="usage-card"><div class="num">{{ usage.summary?.count ?? 0 }}</div><div class="lbl">调用次数</div></div>
      <div class="usage-card"><div class="num">{{ usage.summary?.tokenIn ?? 0 }}</div><div class="lbl">输入 Token</div></div>
      <div class="usage-card"><div class="num">{{ usage.summary?.tokenOut ?? 0 }}</div><div class="lbl">输出 Token</div></div>
      <div class="usage-card"><div class="num">{{ usage.summary?.cacheHitRate ?? 0 }}%</div><div class="lbl">缓存命中率</div></div>
      <div class="usage-card"><div class="num">{{ usage.summary?.cacheHit ?? 0 }}</div><div class="lbl">命中 Token</div></div>
      <div class="usage-card"><div class="num">{{ usage.summary?.cacheMiss ?? 0 }}</div><div class="lbl">未命中 Token</div></div>
      <div class="usage-card"><div class="num">{{ ((usage.summary?.durationMs || 0) / 1000).toFixed(1) }}s</div><div class="lbl">总耗时</div></div>
    </div>

    <!-- 时间范围筛选 -->
    <div class="usage-filter">
      <el-radio-group v-model="rangePreset" size="small" @change="applyRangePreset">
        <el-radio-button value="7">近 7 天</el-radio-button>
        <el-radio-button value="30">近 30 天</el-radio-button>
        <el-radio-button value="custom">自定义</el-radio-button>
      </el-radio-group>
      <el-date-picker v-if="rangePreset === 'custom'" v-model="customRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" size="small" style="width: 240px" @change="loadUsage" />
      <el-button size="small" type="primary" @click="loadUsage">查询</el-button>
      <span class="filter-tip">场景：dialog=对话 / card_gen=角色卡 / action=行动 / crowd=人群 / title_gen=标题 / location_extract=地点 / memory=记忆 / import=导入 / embedding=向量化</span>
    </div>

    <!-- 聚合 Tab -->
    <div class="usage-group tech-card">
      <el-tabs v-model="groupTab">
        <el-tab-pane v-if="!projectId" label="按项目" name="project">
          <el-table :data="usage.byProject || []" border size="small">
            <el-table-column prop="label" label="项目" min-width="140" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column prop="cacheHitRate" label="命中率" width="90" />
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="按场景" name="scene">
          <el-table :data="usage.byScene || []" border size="small">
            <el-table-column prop="label" label="场景" min-width="140" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column prop="cacheHitRate" label="命中率" width="90" />
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="按模型" name="model">
          <el-table :data="usage.byModel || []" border size="small">
            <el-table-column prop="label" label="模型" min-width="140" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column prop="cacheHitRate" label="命中率" width="90" />
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="按日期" name="date">
          <el-table :data="usage.byDate || []" border size="small">
            <el-table-column prop="label" label="日期" min-width="140" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column prop="cacheHitRate" label="命中率" width="90" />
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="按角色" name="character">
          <el-table :data="byCharacter" border size="small">
            <el-table-column prop="label" label="角色" min-width="140" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
          </el-table>
          <div class="char-tip">按角色聚合由明细（detail 的 characterName）本地汇总，角色名由后端解析</div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 明细 -->
    <div class="usage-detail tech-card">
      <div class="detail-title">明细</div>
      <el-table :data="usage.detail || []" border size="small" max-height="320">
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ (row.createdAt || '').slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <!-- 角色列：显示角色名（后端已解析 characterName；悬浮显示原角色ID便于排查） -->
        <el-table-column label="角色" min-width="120">
          <template #default="{ row }">
            <span :title="row.characterId ? `角色ID: ${row.characterId}` : ''">{{ row.characterName || '项目级' }}</span>
          </template>
        </el-table-column>
        <!-- 场景列：显示中文场景名（后端已解析 sceneName；悬浮显示场景编码便于排查） -->
        <el-table-column label="场景" min-width="110">
          <template #default="{ row }">
            <span :title="row.scene ? `场景: ${row.scene}` : ''">{{ row.sceneName || row.scene || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="120" />
        <el-table-column prop="tokenIn" label="输入" width="80" />
        <el-table-column prop="tokenOut" label="输出" width="80" />
        <el-table-column prop="cacheHitTokens" label="命中" width="80" />
        <el-table-column prop="cacheMissTokens" label="未命中" width="80" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
/**
 * 设置-AI 用量（前端布局重构 V1.0，设计文档 §3.10.4；双模式改造 V1.2）。
 * <p>职责：项目/全局双模式 AI 用量统计——概览卡片（调用次数/输入输出 Tokens/缓存命中率/总耗时）+
 * 多维度聚合（项目[全局]/场景/模型/日期/角色）+ 时间范围筛选（近7/30天/自定义）+ 明细。
 * 项目模式：默认过滤当前项目（fetchUsageStats 传 projectId）；
 * 全局模式（首页顶栏「设置」→ AI 用量）：不传 projectId = 全部项目的总用量（用户级聚合）。</p>
 * <p>数据来源：/api/usage/stats（现有接口，支持 projectId/scene/model/startDate/endDate，projectId 可空）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchUsageStats } from '@/shared/api'

const route = useRoute()
// 当前项目 ID：项目空间内存在；全局设置（首页 /settings）为 null（= 全部项目总用量）
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

// embedded：内嵌到设置弹窗（GlobalSettingsDialog）时隐藏顶部模式说明，避免与弹窗说明重复
defineProps({ embedded: { type: Boolean, default: false } })

const loading = ref(false)
const usage = ref({})
const groupTab = ref('scene')
const rangePreset = ref('30')
const customRange = ref(null)

/** 按角色聚合（从明细本地汇总；label 用后端解析的角色名 characterName） */
const byCharacter = computed(() => {
  const map = new Map()
  for (const d of usage.value.detail || []) {
    const key = d.characterName || (d.characterId ? `角色 ${d.characterId}` : '项目级')
    const cur = map.get(key) || { label: key, count: 0, tokenIn: 0, tokenOut: 0, durationMs: 0 }
    cur.count += 1
    cur.tokenIn += d.tokenIn || 0
    cur.tokenOut += d.tokenOut || 0
    cur.durationMs += d.durationMs || 0
    map.set(key, cur)
  }
  return [...map.values()].sort((a, b) => b.count - a.count)
})

/** 应用预设时间范围 */
function applyRangePreset() {
  if (rangePreset.value === 'custom') return
  loadUsage()
}

/** 加载用量统计（项目模式=当前项目；全局模式=全部项目总用量） */
async function loadUsage() {
  loading.value = true
  try {
    // 全局模式（首页 /settings）：不传 projectId → 统计全部项目的总用量
    const params = projectId.value ? { projectId: projectId.value } : {}
    const today = new Date()
    if (rangePreset.value === '7') {
      const start = new Date(today.getTime() - 6 * 86400000)
      params.startDate = fmtDate(start)
      params.endDate = fmtDate(today)
    } else if (rangePreset.value === '30') {
      const start = new Date(today.getTime() - 29 * 86400000)
      params.startDate = fmtDate(start)
      params.endDate = fmtDate(today)
    } else if (customRange.value && customRange.value.length === 2) {
      params.startDate = customRange.value[0]
      params.endDate = customRange.value[1]
    }
    usage.value = await fetchUsageStats(params)
  } catch (e) { ElMessage.error(e.message || '用量加载失败') }
  finally { loading.value = false }
}

/** 日期格式化 YYYY-MM-DD */
function fmtDate(d) {
  const pad = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

onMounted(loadUsage)
</script>

<style scoped>
.project-usage-settings { }
.usage-alert { margin-bottom: 14px; }
.usage-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 14px; }
.usage-card { background: var(--bg-layer-2); border: 1px solid var(--border-light); border-radius: var(--radius-md); padding: 14px; text-align: center; }
.usage-card .num { font-size: 1.4rem; font-weight: 700; color: var(--primary, #409eff); }
.usage-card .lbl { font-size: 0.75rem; color: var(--text-secondary); margin-top: 4px; }
.usage-filter { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.filter-tip { font-size: 0.72rem; color: var(--text-placeholder); }
.usage-group { padding: 12px 16px; margin-bottom: 14px; }
.usage-detail { padding: 14px 16px; }
.detail-title { font-weight: 700; color: var(--text-primary); margin-bottom: 10px; }
.char-tip { font-size: 0.72rem; color: var(--text-placeholder); margin-top: 6px; }
</style>
