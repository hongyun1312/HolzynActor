<template>
  <div class="page">
    <div class="page-header">
      <h2>NanoJev 判定层（N3 · N4 空间候选 · N5 认知/触发/演化）</h2>
      <div class="page-actions">
        <span class="pill" :class="nanojevEnabled ? 'ok' : 'off'">NanoJev {{ nanojevEnabled ? '可用' : '未启用' }}</span>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <!-- ① 场景开关卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">逐场景开关（统一框架：开关 + 采样 + 升级策略 + fail-closed 回退）</div>
      </template>
      <el-table :data="scenes" size="small" stripe>
        <el-table-column label="场景" prop="scene" min-width="170">
          <template #default="{ row }">
            <b>{{ row.scene }}</b>
          </template>
        </el-table-column>
        <el-table-column label="配置" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '开' : '关' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实际生效" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.effective ? 'success' : 'danger'" effect="plain">
              {{ row.effective ? '生效' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采样" width="90" align="center" prop="sampling" />
        <el-table-column label="升级策略" min-width="200">
          <template #default="{ row }">
            <span v-if="row.escalation.enabled" class="mono">
              top1≥{{ row.escalation.top1Prob ?? '-' }}<template v-if="row.escalation.entropy != null"> · 熵≤{{ row.escalation.entropy }}</template>
            </span>
            <span v-else class="muted">直接采纳</span>
          </template>
        </el-table-column>
        <el-table-column label="调试切换" width="140" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              @change="v => toggle(row.scene, v)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="tip">切换为测试操作（需 HOLOZYN_ACTOR_EVALUATION_TEST_ENABLED=true），重启恢复 application.yml 配置。</div>
    </el-card>

    <!-- ② 判定统计卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">判定统计（按场景聚合）</div>
      </template>
      <el-table :data="sceneStats" size="small" stripe>
        <el-table-column label="场景" prop="scene" min-width="170" />
        <el-table-column label="次数" prop="count" width="80" align="center" />
        <el-table-column label="采纳" prop="adopted" width="80" align="center" />
        <el-table-column label="升级/回退" prop="escalated" width="100" align="center" />
        <el-table-column label="失败" prop="failed" width="80" align="center" />
        <el-table-column label="平均延迟(ms)" prop="latencyAvgMs" width="120" align="center" />
      </el-table>
    </el-card>

    <!-- ③ 最近判定记录卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">最近判定记录（分布 / top1Prob / 熵 / 处置）</div>
      </template>
      <el-table :data="recent" size="small" stripe max-height="420">
        <el-table-column label="时间" width="150">
          <template #default="{ row }"><span class="mono">{{ shortTime(row.at) }}</span></template>
        </el-table-column>
        <el-table-column label="场景" prop="scene" width="150" />
        <el-table-column label="类型" prop="questionType" width="80" align="center" />
        <el-table-column label="处置" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="dispType(row.disposition)">{{ dispLabel(row.disposition) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center" prop="chosen" />
        <el-table-column label="top1Prob" width="90" align="center">
          <template #default="{ row }"><span class="mono">{{ fmtP(row.top1Prob) }}</span></template>
        </el-table-column>
        <el-table-column label="熵" width="80" align="center">
          <template #default="{ row }"><span class="mono">{{ fmtP(row.entropy) }}</span></template>
        </el-table-column>
        <el-table-column label="分布" min-width="260">
          <template #default="{ row }">
            <span class="mono muted">{{ distText(row.probabilities) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="90" align="center">
          <template #default="{ row }"><span class="mono">{{ row.latencyMs }}ms</span></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get, post } from '../api'

const nanojevEnabled = ref(false)
const scenes = ref([])
const sceneStats = ref([])
const recent = ref([])

async function loadStatus() {
  const s = await get('/debug/nanojev/decision/status')
  nanojevEnabled.value = !!s?.nanojevEnabled
  scenes.value = s?.scenes || []
}

async function loadStats() {
  const s = await get('/debug/nanojev/decision/stats', { max: 50 })
  sceneStats.value = s?.sceneStats || []
  recent.value = s?.recent || []
}

function loadAll() {
  loadStatus().catch(e => console.error(e))
  loadStats().catch(e => console.error(e))
}

async function toggle(scene, enabled) {
  try {
    const r = await post('/debug/nanojev/decision/override', { scene, enabled })
    console.log('override:', r)
  } catch (e) {
    console.error(e)
  }
  loadStatus()
}

function shortTime(s) {
  if (!s) return '-'
  return s.length > 19 ? s.substring(11, 19) : s
}

function fmtP(v) {
  if (v == null) return '-'
  return Math.round(v * 1000) / 1000
}

function distText(d) {
  if (!d) return '-'
  return Object.entries(d).map(([k, v]) => `${k}=${Math.round(v * 100) / 100}`).join(' ')
}

function dispType(d) {
  return { adopted: 'success', escalated: 'warning', disabled: 'info', failed: 'danger' }[d] || 'info'
}

function dispLabel(d) {
  return { adopted: '采纳', escalated: '升级', disabled: '未启用', failed: '失败' }[d] || d
}

onMounted(loadAll)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.page-header { display: flex; align-items: center; justify-content: space-between; }
.page-header h2 { margin: 0; font-size: 18px; color: var(--text-primary); }
.page-actions { display: flex; align-items: center; gap: 10px; }
.pill { font-size: 12px; padding: 3px 10px; border-radius: 12px; }
.pill.ok { background: var(--accent-soft); color: var(--accent-text); }
.pill.off { background: var(--bg-layer-1); color: var(--text-tertiary); }
.card-title { font-weight: 600; color: var(--text-primary); }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.muted { color: var(--text-tertiary); }
.tip { margin-top: 8px; font-size: 12px; color: var(--text-tertiary); }
</style>
