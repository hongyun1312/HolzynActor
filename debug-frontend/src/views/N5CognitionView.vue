<template>
  <div class="page">
    <div class="page-header">
      <h2>认知 LOD + 高频微决策（N5）· 认知 LOD 深化（N6）</h2>
      <div class="page-actions">
        <span class="pill" :class="attentionEnabled ? 'ok' : 'off'">
          注意力语义通道 {{ attentionEnabled ? '启用' : '关闭' }}
        </span>
        <el-input v-model="projectId" size="small" placeholder="项目 ID" style="width: 110px" />
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <!-- ① 注意力卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">① 注意力（每 {{ attentionConfig.periodTicks ?? 2 }} tick 全 NPC 批量 · 规则硬基线 + 决策模型语义通道）</div>
      </template>
      <div class="row">
        <el-table :data="tierRows" size="small" stripe max-height="280" class="tier-table">
          <el-table-column label="角色" min-width="140">
            <template #default="{ row }">{{ row.name }} <span class="mono muted">{{ row.npcRef }}</span></template>
          </el-table-column>
          <el-table-column label="LOD 档" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="tierType(row.tier)">{{ tierLabel(row.tier) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-table :data="attentionChanges" size="small" stripe max-height="280" class="tier-table">
          <el-table-column label="时间" width="90">
            <template #default="{ row }"><span class="mono">{{ shortTime(row.at) }}</span></template>
          </el-table-column>
          <el-table-column label="角色" prop="name" width="100" />
          <el-table-column label="注意力" width="80" align="center">
            <template #default="{ row }"><span class="mono">{{ levelLabel(row.level) }}</span></template>
          </el-table-column>
          <el-table-column label="档位变化" width="130" align="center">
            <template #default="{ row }">
              <span class="mono">{{ tierLabel(row.tierBefore) }} → {{ tierLabel(row.tierAfter) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="游戏秒" width="100">
            <template #default="{ row }"><span class="mono muted">{{ row.gameSecond }}</span></template>
          </el-table-column>
        </el-table>
      </div>
      <div class="tip">注意力档位变化轨迹（level=0 无 / 1 低 / 2 中 / 3 高；升档 focus 保持 30 游戏分钟防抖动）。</div>
    </el-card>

    <!-- ② 微决策卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">② 每 tick 即时意向（choice 批量 · 多数「继续当下事」保持行为连续性）</div>
      </template>
      <div class="row">
        <div class="counter-box">
          <div class="counter big">{{ microCounters.continue ?? 0 }}</div>
          <div class="counter-label">继续当下事</div>
        </div>
        <div class="counter-box">
          <div class="counter big alt">{{ microCounters.thought ?? 0 }}</div>
          <div class="counter-label">冒出念头（非继续）</div>
        </div>
        <div class="counter-box">
          <div class="counter big muted">{{ ratioText }}</div>
          <div class="counter-label">行为连续性占比</div>
        </div>
      </div>
      <el-table :data="microTrail" size="small" stripe max-height="320">
        <el-table-column label="时间" width="90">
          <template #default="{ row }"><span class="mono">{{ shortTime(row.at) }}</span></template>
        </el-table-column>
        <el-table-column label="角色" prop="name" width="110" />
        <el-table-column label="意向码" prop="intent" width="130" />
        <el-table-column label="意向" prop="intentText" min-width="220" />
        <el-table-column label="游戏秒" width="100">
          <template #default="{ row }"><span class="mono muted">{{ row.gameSecond }}</span></template>
        </el-table-column>
      </el-table>
      <div class="tip">非「继续」意向 → 低频记忆轨迹（写概率 {{ pct(attentionConfig.memoryWriteProbability) }}）+ 主动意向（搭话/起身）极低概率打断（{{ pct(attentionConfig.interruptProbability) }}）。</div>
    </el-card>

    <!-- ③ 重算触发卡 -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">③ 事件驱动重算触发（情绪漂移 / 关系跨档 / 面具情境切换 · 决策模型 boolean + 程序化兜底）</div>
      </template>
      <el-table :data="triggers" size="small" stripe max-height="320">
        <el-table-column label="时间" width="90">
          <template #default="{ row }"><span class="mono">{{ shortTime(row.at) }}</span></template>
        </el-table-column>
        <el-table-column label="触发码" width="110" prop="trigger" />
        <el-table-column label="含义" width="140" prop="label" />
        <el-table-column label="角色" prop="npcRef" min-width="120" />
        <el-table-column label="判定来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.decisionActive ? 'success' : 'info'">
              {{ row.decisionActive ? '决策模型' : '程序化兜底' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.worth ? 'warning' : 'info'" effect="plain">
              {{ row.worth ? '触发重算' : '不触发' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="游戏秒" width="100">
          <template #default="{ row }"><span class="mono muted">{{ row.gameSecond }}</span></template>
        </el-table-column>
      </el-table>
      <div class="tip">检测到变化信号才判定；空间重算带同码去重窗口（30 游戏分钟），失败零阻断。</div>
    </el-card>

    <!-- ④ N6 参与度卡（LOD×群聊双向联动·对话侧信号源） -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">④ 群聊参与度（N6 · 近 {{ attentionConfig.participationWindowGameHours ?? 6 }} 游戏小时窗口 · 发言 spoke×1 / 在场 attended×0.5 → 回灌注意力 + desire 加分）</div>
      </template>
      <div class="row">
        <div class="counter-box">
          <div class="counter small">{{ participationEnabled ? '启用' : '关闭' }}</div>
          <div class="counter-label">参与度统计</div>
        </div>
        <div class="counter-box">
          <div class="counter small">{{ participationBoostEnabled ? '开' : '关' }}</div>
          <div class="counter-label">回灌注意力（档≥2 时 tick +1）</div>
        </div>
        <div class="counter-box">
          <div class="counter small">{{ desireBoostEnabled ? '开' : '关' }}</div>
          <div class="counter-label">群聊 desire 加分（档≥2 / focus +1）</div>
        </div>
      </div>
      <el-table :data="participationRows" size="small" stripe max-height="300">
        <el-table-column label="角色" prop="npcRef" min-width="140" />
        <el-table-column label="加权次数" width="110" align="center">
          <template #default="{ row }"><span class="mono">{{ row.weighted }}</span></template>
        </el-table-column>
        <el-table-column label="参与度档" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level >= 2 ? 'success' : row.level === 1 ? 'warning' : 'info'">
              {{ row.level }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="窗口起点（游戏秒）" width="150">
          <template #default="{ row }"><span class="mono muted">{{ row.windowStart }}</span></template>
        </el-table-column>
        <el-table-column label="窗口终点（游戏秒）" width="150">
          <template #default="{ row }"><span class="mono muted">{{ row.windowEnd }}</span></template>
        </el-table-column>
      </el-table>
      <div class="tip">档位 = 加权次数 ÷ 步长 {{ attentionConfig.participationLevelStep ?? 2 }} 向下取整（封顶 3）；窗口超期自动重置——真人社交记忆节奏。</div>
    </el-card>

    <!-- ⑤ N6 相机注意力聚焦卡（程序化融合 · Web 无相机零副作用） -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">⑤ 相机注意力聚焦（N6 · 距离 ≤{{ attentionConfig.cameraNearMeters ?? 25 }}m→3 / ≤{{ attentionConfig.cameraMidMeters ?? 50 }}m→2 / ≤{{ attentionConfig.cameraFarMeters ?? 100 }}m→1）</div>
      </template>
      <div class="row">
        <span class="pill" :class="camera.enabled ? 'ok' : 'off'">相机聚焦 {{ camera.enabled ? '启用' : '关闭' }}</span>
        <span class="pill" :class="camera.hasActiveCamera ? 'ok' : 'off'">
          活动相机 {{ camera.hasActiveCamera ? '有（Unity 接入自动生效）' : '无（Web 零副作用跳过）' }}
        </span>
      </div>
      <el-table v-if="camera.levels" :data="cameraRows" size="small" stripe max-height="260">
        <el-table-column label="角色" prop="npcRef" min-width="140" />
        <el-table-column label="相机注意力档" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level >= 2 ? 'success' : row.level === 1 ? 'warning' : 'info'">
              {{ row.level === 0 ? '无（>远距）' : row.level }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip">程序化连续映射（近→高关注），与决策模型语义档位取 max（只抬不压）；规则升档兜底保留。</div>
    </el-card>

    <!-- ⑥ N6 记忆检索注意力加权卡（层间优先级修正 + 档位数量配额） -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">⑥ 记忆检索注意力加权（N6 · 全量注入路径：决策 / 单聊 / 群聊 TASK / 空间生成）</div>
      </template>
      <div class="row">
        <div class="counter-box">
          <div class="counter small">{{ memoryWeightEnabled ? '启用' : '关闭' }}</div>
          <div class="counter-label">记忆加权总开关</div>
        </div>
        <div class="counter-box">
          <div class="counter small">{{ memoryTierAdjustEnabled ? '开' : '关' }}</div>
          <div class="counter-label">层间优先级修正（focus 升 MID / dormant 降 MID）</div>
        </div>
      </div>
      <el-table :data="quotaRows" size="small" stripe max-height="260">
        <el-table-column label="注意力档" prop="tier" width="110" />
        <el-table-column label="L1 短期记忆条数上限" prop="l1" width="180" align="center" />
        <el-table-column label="L2 长期记忆 top-K" prop="l2" width="180" align="center" />
        <el-table-column label="空间生成记忆条数" prop="space" align="center" />
      </el-table>
      <div class="tip">同预算内重分配：高关注注入更丰富、低关注收窄（总输入 token 不增反降）；L1 段「[清晰]/[模糊]」标签已精简（N6 Prompt 优化）。</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { get } from '../api'

const attentionEnabled = ref(false)
const attentionConfig = ref({})
const tiers = ref({})
const attentionChanges = ref([])
const microCounters = ref({})
const microTrail = ref([])
const triggers = ref([])
const participation = ref({})
const camera = ref({})
const projectId = ref('1')

const tierRows = computed(() =>
  Object.entries(tiers.value || {}).map(([npcRef, tier]) => ({
    npcRef,
    name: npcRef.replace('npc:', ''),
    tier
  }))
)

// N6 参与度卡：快照 → 行（含按步长折算的档位展示）
const participationRows = computed(() => {
  const step = attentionConfig.value.participationLevelStep ?? 2
  return Object.entries(participation.value?.snapshot || {}).map(([key, v]) => ({
    npcRef: key,
    weighted: v.weighted,
    windowStart: v.windowStart,
    windowEnd: v.windowEnd,
    level: Math.min(3, Math.floor(v.weighted / step))
  }))
})

// N6 相机聚焦卡：档位表
const cameraRows = computed(() =>
  Object.entries(camera.value?.levels || {}).map(([npcRef, level]) => ({ npcRef, level }))
)

// N6 记忆加权卡：配额表
const quotaRows = computed(() => {
  const l1 = attentionConfig.value.memoryL1CapByTier || {}
  const l2 = attentionConfig.value.memoryL2TopKByTier || {}
  const sp = attentionConfig.value.spaceMemoryTopKByTier || {}
  return ['focus', 'ambient', 'dormant'].map(t => ({
    tier: { focus: '聚焦', ambient: '待机', dormant: '沉睡' }[t] || t,
    l1: l1[t] ?? '-',
    l2: l2[t] ?? '-',
    space: sp[t] ?? '-'
  }))
})

const participationEnabled = computed(() => !!participation.value?.enabled)
const participationBoostEnabled = computed(() => !!attentionConfig.value.participationBoostEnabled)
const desireBoostEnabled = computed(() => !!attentionConfig.value.desireBoostEnabled)
const memoryWeightEnabled = computed(() => !!attentionConfig.value.memoryWeightEnabled)
const memoryTierAdjustEnabled = computed(() => !!attentionConfig.value.memoryTierAdjustEnabled)

const ratioText = computed(() => {
  const c = microCounters.value.continue ?? 0
  const t = microCounters.value.thought ?? 0
  if (c + t === 0) return '-'
  return Math.round((c / (c + t)) * 100) + '%'
})

async function loadAll() {
  const s = await get('/debug/growth/n5', { projectId: projectId.value || null })
  attentionEnabled.value = !!s?.attentionEnabled
  attentionConfig.value = s?.attentionConfig || {}
  tiers.value = s?.tiers || {}
  attentionChanges.value = s?.attentionTierChanges || []
  microCounters.value = s?.micro?.counters || {}
  microTrail.value = s?.micro?.trail || []
  triggers.value = s?.triggers || []
  participation.value = s?.participation || {}
  camera.value = s?.camera || {}
}

function tierLabel(t) {
  return { dormant: '沉睡', ambient: '待机', focus: '聚焦' }[t] || t || '-'
}

function tierType(t) {
  return { dormant: 'info', ambient: 'warning', focus: 'success' }[t] || 'info'
}

function levelLabel(l) {
  return { 0: '无', 1: '低', 2: '中', 3: '高' }[l] ?? l
}

function pct(v) {
  if (v == null) return '-'
  return Math.round(v * 100) + '%'
}

function shortTime(s) {
  if (!s) return '-'
  return s.length > 19 ? s.substring(11, 19) : s
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
.row { display: flex; gap: 14px; flex-wrap: wrap; }
.tier-table { flex: 1 1 300px; }
.counter-box { flex: 1 1 160px; text-align: center; padding: 14px 8px; background: var(--bg-layer-1); border-radius: var(--radius-sm); }
.counter { font-size: 22px; font-weight: 700; color: var(--accent-text); }
.counter.small { font-size: 15px; }
.counter.alt { color: var(--state-warning); }
.counter.muted { color: var(--text-tertiary); }
.counter-label { margin-top: 4px; font-size: 12px; color: var(--text-tertiary); }
</style>
