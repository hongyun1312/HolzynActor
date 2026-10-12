<template>
  <div class="prompt-eng">
    <div class="head-row">
      <h2 class="page-title">Prompt 工程 O4（分层上下文重构 / 前缀缓存 / 指令遵循度）</h2>
      <div class="toolbar">
        <el-button size="small" type="primary" @click="reloadAll">刷新</el-button>
        <span class="hint">六层结构 SYSTEM / ROLE_PROFILE / CURRENT_STATE / MEMORY_CONTEXT / CONVERSATION_HISTORY / TASK / OUTPUT_FORMAT；
          稳定前缀在前命中前缀缓存；角色类深度重写（单聊流式正文 / 群聊 DIALOG_JSON / 决策 ReAct &lt;decision&gt;）；其余模板渲染期规范化</span>
      </div>
    </div>

    <!-- ==================== 一、模板规格注册表 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">
          模板规格注册表（PromptSpecRegistry：场景 / 版本 / 输出契约 / 温度 / 输出上限）
        </div>
      </template>
      <el-table :data="specRows" size="small" empty-text="加载中…" max-height="420" row-key="scene">
        <el-table-column prop="scene" label="场景" width="220" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="80" align="center" sortable />
        <el-table-column label="输出契约" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="contractTag(row.contract)" effect="plain">{{ contractLabel(row.contract) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="temperature" label="温度" width="90" align="center">
          <template #default="{ row }"><span class="mono">{{ row.temperature }}</span></template>
        </el-table-column>
        <el-table-column prop="maxOutputTokens" label="输出上限" width="110" align="center">
          <template #default="{ row }"><span class="mono">{{ row.maxOutputTokens }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ==================== 二、场景指标（合规率 / 缓存 / 一致性） ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">
          场景指标（/api/debug/prompt-metrics/stats：调用 / 格式合规率 / 前缀缓存命中 / token / judge 一致性抽样）
        </div>
      </template>
      <el-table :data="statsRows" size="small" empty-text="暂无统计（AI 调用产生后上报）" max-height="420" row-key="scene">
        <el-table-column prop="scene" label="场景" width="200" show-overflow-tooltip />
        <el-table-column label="版本" width="90">
          <template #default="{ row }">
            <span class="mono">{{ (row.versions || []).join(',') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="calls" label="调用" width="80" align="center" sortable />
        <el-table-column label="格式合规率" width="110" align="center" sortable :sort-method="(a,b)=> (a.complianceRate||0)-(b.complianceRate||0)">
          <template #default="{ row }">
            <span v-if="row.complianceRate != null" class="mono">{{ row.complianceRate }}%</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="jsonOk" label="合规" width="70" align="center" sortable />
        <el-table-column prop="jsonFail" label="不合规" width="80" align="center" sortable />
        <el-table-column prop="fallback" label="回退" width="70" align="center" sortable />
        <el-table-column prop="blankOutputs" label="空输出" width="80" align="center" sortable />
        <el-table-column prop="degraded" label="降级" width="70" align="center" sortable />
        <el-table-column label="缓存命中 token" width="120" align="center" sortable :sort-method="(a,b)=> (a.cacheHitTokens||0)-(b.cacheHitTokens||0)">
          <template #default="{ row }"><span class="mono">{{ row.cacheHitTokens || 0 }}</span></template>
        </el-table-column>
        <el-table-column label="一致性(judge)" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.consistencyAvg != null" class="mono">{{ row.consistencyAvg }}（{{ row.consistencySamples }} 样本）</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ==================== 三、最近合规/告警事件 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">最近合规/告警事件（环形 200 条，新→旧）</div>
      </template>
      <el-table :data="alerts" size="small" empty-text="暂无事件" max-height="280" row-key="atMs">
        <el-table-column label="时间" width="180">
          <template #default="{ row }"><span class="dim mono">{{ row.time }}</span></template>
        </el-table-column>
        <el-table-column prop="scene" label="场景" width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="eventTag(row.type)" effect="plain">{{ eventLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="320" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get } from '../api'

const specRows = ref([])
const statsRows = ref([])
const alerts = ref([])

function contractTag(c) {
  return { PLAIN_TEXT: 'info', DIALOG_JSON: 'success', DECISION_JSON: 'warning', SCHEMA_JSON: '' }[c] || ''
}
function contractLabel(c) {
  return { PLAIN_TEXT: '正文', DIALOG_JSON: '对话JSON', DECISION_JSON: '决策ReAct', SCHEMA_JSON: 'Schema' }[c] || c
}
function eventTag(t) {
  return { fallback: 'warning', format_fail: 'danger', blank: 'danger', cache: 'success' }[t] || 'info'
}
function eventLabel(t) {
  return { fallback: '程序化回退', format_fail: '格式不合规', blank: '空输出', cache: '前缀缓存' }[t] || t
}

async function reloadAll() {
  try {
    const data = await get('/api/debug/prompt-metrics')
    specRows.value = data.specs || []
    statsRows.value = data.stats || []
    alerts.value = data.recentAlerts || []
  } catch (e) {
    console.error('Prompt 工程指标加载失败', e)
  }
}

onMounted(reloadAll)
</script>
