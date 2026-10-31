<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <PageHeader
    title="决策与认知"
    desc="决策链路、刺激判定、认知状态与行为统计。"
  >
    <template #actions>
      <el-tag v-if="page.lastAt" size="small" effect="plain">数据于 {{ page.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <div v-if="page.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ page.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 决策链路 -->
    <el-tab-pane label="决策链路" name="traces" :lazy="true">
      <DevCard
        title="决策链路（按 trace 聚合）"
        :desc="`共 ${traceTotal} 条链路。一次决策 = 感知 → 思考 → 行动 → 学习；点「查看」看该链路每一步的输入输出。`"
        flush
      >
        <template #actions>
          <el-select v-model="traceStatus" size="small" clearable placeholder="全部状态" style="width: 130px" @change="reloadTraces">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="降级" value="degraded" />
          </el-select>
          <el-input v-model="traceId" size="small" placeholder="traceId" style="width: 200px" @keyup.enter="openTrace(traceId)">
            <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
          </el-input>
          <el-button size="small" :disabled="!traceId" @click="openTrace(traceId)">查看链路</el-button>
          <el-button size="small" @click="reloadTraces">刷新</el-button>
        </template>
        <DevAuto v-if="traces.items.length" :value="{ items: traces.items }" />
        <el-empty v-else description="没有决策链路（世界可能没在跑，或所有判定被护栏拦下）" :image-size="64" />
      </DevCard>

      <DevCard v-if="traceDetail" title="链路详情" desc="按发生顺序的全部环节（含非 AI 环节与重试 attempt）。">
        <template #actions>
          <el-button size="small" @click="traceDetail = null">关闭</el-button>
        </template>
        <DevJson :value="traceDetail" title="traceDetail" :max-height="420" />
      </DevCard>
    </el-tab-pane>

    <!-- ② 决策协调 -->
    <el-tab-pane label="决策协调" name="coord" :lazy="true">
      <DevCard title="引擎状态与决策统计" desc="tick / 预算 / 重试统计：判断「是不是被预算掐断了」。">
        <div class="dev-grid-2">
          <DevAuto v-if="worldEngine" :value="worldEngine" />
          <div v-else class="dev-toolbar-note">未返回引擎状态</div>
          <DevAuto v-if="decisionStats" :value="decisionStats" />
          <div v-else class="dev-toolbar-note">未返回决策统计</div>
        </div>
      </DevCard>
      <DevCard title="冲突与资源占用" desc="同场景多人抢同一资源时的裁决依据（谁先、谁占、谁让）。">
        <div class="dev-grid-2">
          <div>
            <div class="dev-subtitle">冲突</div>
            <DevAuto v-if="conflicts" :value="conflicts" />
            <div v-else class="dev-toolbar-note">无冲突数据</div>
          </div>
          <div>
            <div class="dev-subtitle">资源占用</div>
            <DevAuto v-if="occupancy" :value="occupancy" />
            <div v-else class="dev-toolbar-note">无占用数据</div>
          </div>
        </div>
      </DevCard>
      <DevCard title="前置推演（foresight）" desc="决策前的走向模拟结果。">
        <DevAuto v-if="foresight" :value="foresight" />
        <div v-else class="dev-toolbar-note">无推演数据</div>
      </DevCard>
    </el-tab-pane>

    <!-- ③ 刺激日志 -->
    <el-tab-pane label="刺激日志" name="stimulus" :lazy="true">
      <DevCard title="判定统计" desc="刺激总量与护栏命中分布：命中率高说明大量刺激被拦下，角色自然「不反应」。">
        <DevAuto v-if="stimulusStats" :value="stimulusStats" />
        <div v-else class="dev-toolbar-note">无统计数据</div>
      </DevCard>
      <DevCard :title="`最近刺激（${stimuli.length}）`" desc="每条刺激都带判定结果与详情；result 为拦下原因时不会产生 AI 调用。" flush>
        <el-empty v-if="!stimuli.length" description="没有刺激记录" :image-size="64" />
        <el-table v-else :data="stimuli" class="dev-table" size="small" max-height="460">
          <el-table-column prop="gameSecond" label="游戏秒" width="100" />
          <el-table-column label="角色" width="130" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.npcRef || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="120" show-overflow-tooltip />
          <el-table-column label="刺激" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.stimulusText || '—' }}</template>
          </el-table-column>
          <el-table-column prop="result" label="判定" width="150" show-overflow-tooltip />
          <el-table-column label="详情" min-width="220" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.detail || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="真实时间" width="170">
            <template #default="{ row }">{{ formatTime(row.realTime) }}</template>
          </el-table-column>
        </el-table>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 决策模型 判定 -->
    <el-tab-pane label="决策模型 判定" name="decision" :lazy="true">
      <DevCard title="判定层开关" desc="逐场景开关。全部关闭时判定层 fail-closed 静默——AI 零调用且日志无告警，是「角色不动」最常见的原因。">
        <DevAuto v-if="decisionStatus" :value="decisionStatus" />
        <div v-else class="dev-toolbar-note">未返回判定层状态</div>
      </DevCard>
      <DevCard title="判定分布" desc="top1Prob / 熵 / 采纳率：分布越平（熵越高）说明小模型越拿不准，此时应回退到大模型。">
        <DevAuto v-if="gateStats" :value="gateStats" />
        <div v-else class="dev-toolbar-note">无统计数据</div>
      </DevCard>
    </el-tab-pane>

    <!-- ⑤ 认知 LOD -->
    <el-tab-pane label="认知 LOD" name="lod" :lazy="true">
      <DevCard title="N5 认知与微决策" desc="认知 LOD 决定「多远的人只用粗粒度模拟」；微决策是细粒度行为选择。">
        <DevAuto v-if="n5" :value="n5" />
        <div v-else class="dev-toolbar-note">未返回认知数据</div>
      </DevCard>
      <DevCard title="成长模块" desc="人格/关系/技能的长期演进概览。">
        <DevAuto v-if="growth" :value="growth" />
        <div v-else class="dev-toolbar-note">未返回成长数据</div>
      </DevCard>
    </el-tab-pane>

    <!-- ⑥ 行为评估 -->
    <el-tab-pane label="行为评估" name="eval" :lazy="true">
      <!-- 未启用态：holzyn.test-enabled=false 时接口返回 403，这是配置门禁不是故障，
           页面必须先说清「怎么打开」，否则用户只会看到两行红字和空的表格 -->
      <DevCard
        v-if="evalBlocked"
        title="测试与评估未启用"
        danger
        desc="后端配置项 holzyn.actor.evaluation.test-enabled 当前为 false，测试会话与评估批跑接口一律返回 403。"
      >
        <div class="dev-notice">
          这是刻意的门禁：测试/评估会真实调用 AI（产生费用）并写入测试数据，因此默认关闭。
          需要时在「配置与调参 → 参数目录」搜索 <code class="dev-mono">test-enabled</code> 改为 true
          （需重启后端生效），或直接改 <code class="dev-mono">application.yml</code>。
        </div>
      </DevCard>
      <div v-else-if="evalError" class="dev-notice is-error">{{ evalError }}</div>
      <DevCard v-if="!evalBlocked" :title="`评估批跑（${evalRuns.length}）`" desc="⚠️ 运行评估会产生真实 AI 费用；这里只读历史结果，不触发新批跑。" flush>
        <el-empty v-if="!evalRuns.length" description="还没有评估批跑" :image-size="64" />
        <el-table v-else :data="evalRuns" class="dev-table" size="small" max-height="400">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="total" label="用例数" width="90" />
          <el-table-column prop="passed" label="通过" width="80" />
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="openEval(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </DevCard>
      <DevCard v-if="!evalBlocked && evalDetail" title="评估详情" desc="逐用例结果。">
        <template #actions><el-button size="small" @click="evalDetail = null">关闭</el-button></template>
        <DevJson :value="evalDetail" title="evalRun" :max-height="420" />
      </DevCard>
      <DevCard v-if="!evalBlocked" :title="`测试会话（${testRuns.length}）`" desc="行为测试留下的会话记录。">
        <DevAuto v-if="testRuns.length" :value="{ runs: testRuns }" />
        <div v-else class="dev-toolbar-note">没有测试会话</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 决策与认知域。
 *
 * <p>6 个子页对应「一次决策」的完整证据链：链路（发生了什么）→ 协调（为什么让它发生）
 * → 刺激（为什么它没发生）→ 判定（小模型怎么筛）→ LOD（多细粒度）→ 评估（像不像人）。</p>
 *
 * <p>本页全只读：评估批跑会烧钱，只展示历史，不提供触发入口。</p>
 */
import { computed, ref } from 'vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevAuto from '../components/DevAuto.vue'
import DevJson from '../components/DevJson.vue'
import {
  fetchDevSystem,
  fetchAiTraces, fetchAiTraceDetail, fetchDecisionStatus, fetchDecisionGateStats,
  fetchN5Cognition, fetchGrowthOverview, fetchStimulusLog, fetchStimulusStats,
  fetchWorldEngine, fetchDecisionStats, fetchDecisionConflicts, fetchDecisionOccupancy,
  fetchForesight, fetchEvalRuns, fetchEvalRunDetail, fetchTestRuns
} from '@/shared/api/dev'
import { useDevPage, formatTime } from '../useDevPage'
import { devContext } from '../devContext'

const tab = ref('traces')
const traceStatus = ref('')
const traceId = ref('')
const traceDetail = ref(null)
const evalDetail = ref(null)

const { state: page, load } = useDevPage(async ({ worldId }) => {
  /**
   * 单个接口失败不拖垮整页：把错误压成 `{__error:status}` 塞回该字段，
   * computed 里再判「是不是 403（功能未启用）」——否则「测试与评估没开」
   * 会被当成接口坏了，页面红一片，用户以为开发者控制台有问题。
   */
  const safe = (p) => p.catch((e) => ({
    __error: e?.response?.status || e?.status || 0,
    __message: e?.message || '读取失败'
  }))
  // 先问一次「功能门禁」，再决定要不要发测试/评估请求：
  // test-enabled=false 时那些接口恒 403，发了只会给控制台刷两条红字。
  const sys = await safe(fetchDevSystem())
  const testEnabled = sys?.features?.testEnabled === true
  const [traces, engine, dStats, conflicts, occupancy, foresight, stimLog, stimStats,
    gateStatus, gateStats, n5, growth, evalRuns, testRuns] = await Promise.all([
    safe(fetchAiTraces({ projectId: worldId, status: traceStatus.value || undefined, page: 1, size: 20 })),
    safe(fetchWorldEngine(worldId)),
    safe(fetchDecisionStats(worldId)),
    safe(fetchDecisionConflicts(worldId)),
    safe(fetchDecisionOccupancy(worldId)),
    safe(fetchForesight(worldId)),
    safe(fetchStimulusLog({ projectId: worldId, limit: 100 })),
    safe(fetchStimulusStats(worldId)),
    safe(fetchDecisionStatus()),
    safe(fetchDecisionGateStats(50)),
    safe(fetchN5Cognition()),
    safe(fetchGrowthOverview()),
    testEnabled ? safe(fetchEvalRuns(worldId)) : Promise.resolve(null),
    testEnabled ? safe(fetchTestRuns(worldId)) : Promise.resolve(null)
  ])
  return {
    testEnabled,
    traces, engine, dStats, conflicts, occupancy, foresight, stimLog, stimStats,
    gateStatus, gateStats, n5, growth, evalRuns, testRuns
  }
})

const traces = computed(() => {
  const t = page.data?.traces || {}
  return { items: t.items || [], total: t.total || 0 }
})
const traceTotal = computed(() => traces.value.total)
const worldEngine = computed(() => page.data?.engine)
const decisionStats = computed(() => page.data?.dStats)
const conflicts = computed(() => page.data?.conflicts)
const occupancy = computed(() => page.data?.occupancy)
const foresight = computed(() => page.data?.foresight)
const stimuli = computed(() => {
  const raw = page.data?.stimLog
  const list = Array.isArray(raw) ? raw : (raw?.items || raw?.list || [])
  return Array.isArray(list) ? list : []
})
const stimulusStats = computed(() => page.data?.stimStats)
const decisionStatus = computed(() => page.data?.gateStatus)
const gateStats = computed(() => page.data?.gateStats)
const n5 = computed(() => page.data?.n5)
const growth = computed(() => page.data?.growth)
const evalRuns = computed(() => {
  const raw = page.data?.evalRuns
  const list = Array.isArray(raw) ? raw : (raw?.items || raw?.list || [])
  return Array.isArray(list) ? list : []
})
const testRuns = computed(() => {
  const raw = page.data?.testRuns
  const list = Array.isArray(raw) ? raw : (raw?.items || raw?.list || [])
  return Array.isArray(list) ? list : []
})
/** 测试/评估接口是否被后端关掉（holzyn.actor.evaluation.test-enabled=false，属预期而非故障） */
const evalBlocked = computed(() =>
  page.data?.testEnabled === false
  || page.data?.evalRuns?.__error === 403
  || page.data?.testRuns?.__error === 403)
/** 其它失败原因（非 403）时才提示「读取失败」，避免把「未启用」误报成错误 */
const evalError = computed(() => {
  const a = page.data?.evalRuns?.__error
  const b = page.data?.testRuns?.__error
  const code = (a && a !== 403) ? a : ((b && b !== 403) ? b : 0)
  return code ? `读取失败（HTTP ${code}）` : ''
})

/** 重新拉链路（状态筛选变化时） */
async function reloadTraces() {
  await load(true)
}

/** 打开链路详情 */
async function openTrace(traceId) {
  try {
    traceDetail.value = await fetchAiTraceDetail(traceId)
  } catch (e) {
    traceDetail.value = { error: e?.message || '读取失败' }
  }
}

/** 打开评估详情 */
async function openEval(id) {
  try {
    evalDetail.value = await fetchEvalRunDetail(devContext.worldId, id)
  } catch (e) {
    evalDetail.value = { error: e?.message || '读取失败' }
  }
}
</script>

<style scoped>
/* 链路详情入口：DevAuto 渲染的表格里没有操作列，这里用 tooltip 提示用户去「AI 调用」页看完整 Prompt */
</style>
