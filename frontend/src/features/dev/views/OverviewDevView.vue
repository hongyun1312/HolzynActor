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
    title="总览"
    desc="当前世界的运行状态、自检结果与近期异常。"
  >
    <template #actions>
      <el-tag v-if="state.lastAt" size="small" effect="plain">数据于 {{ state.lastAt }}</el-tag>
      <el-tag v-if="state.refreshing" size="small" type="info" effect="plain">刷新中…</el-tag>
    </template>
  </PageHeader>

  <div v-if="state.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>
      <div>{{ state.error }}</div>
      <el-button link size="small" @click="load()">重试</el-button>
    </div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 运行总览 -->
    <el-tab-pane label="运行总览" name="runtime" :lazy="true">
      <div class="dev-metrics">
        <MetricCard
          label="世界时间"
          :value="engine.gameTimeText || '—'"
          hint="来源 actor_world_clock.last_game_second，经游戏历换算"
          :missing="!engine.mounted"
          missing-reason="世界未挂载"
        />
        <MetricCard
          label="时钟状态"
          :value="clockText"
          :unit="engine.rate != null ? `${engine.rate}×` : ''"
          hint="暂停 / 运行 / 静止（worldRunning）；倍率为引擎推进速度"
        />
        <MetricCard
          label="游戏时刻"
          :value="engine.lastGameSecond != null ? engine.lastGameSecond : '—'"
          unit="秒"
          hint="last_game_second 原始值，便于与数据库对照"
        />
        <MetricCard
          label="角色"
          :value="content.characterCount != null ? content.characterCount : '—'"
          unit="位"
          :breakdown="[{ label: '群众型', value: content.crowdCount != null ? content.crowdCount : '—' }]"
          hint="普通型 + 特殊型（actor_profile 未删除）"
        />
      </div>

      <div class="dev-metrics">
        <MetricCard
          label="今日 AI 调用"
          :value="cost.todayCalls != null ? cost.todayCalls : '—'"
          unit="次"
          :breakdown="[{ label: '失败/降级', value: cost.failedToday != null ? cost.failedToday : '—' }]"
          hint="actor_ai_call_log 当日条数；失败=status in (failed,degraded)"
        />
        <MetricCard
          label="今日 Token"
          :value="compactNumber(cost.todayTokens)"
          hint="token_in + token_out 当日合计"
        />
        <MetricCard
          label="本周 Token"
          :value="compactNumber(cost.weekTokens)"
          :breakdown="[{ label: '本周调用', value: cost.weekCalls != null ? cost.weekCalls : '—' }]"
          hint="近 7 天合计，用于估算费用趋势"
        />
        <MetricCard
          label="记忆条目"
          :value="content.memoryTotal != null ? content.memoryTotal : '—'"
          unit="条"
          hint="actor_memory 未删除总条数（全库口径）"
        />
      </div>

      <DevCard title="引擎与参数" desc="时钟细节 + 参数目录概况；参数改动后是否需要重启一目了然。">
        <DevKv :items="engineItems" />
        <div class="dev-toolbar dev-mt">
          <el-button size="small" @click="go('/dev/config')">去调参</el-button>
          <el-button size="small" @click="go('/dev/world')">世界引擎详情</el-button>
          <el-tag v-if="params.pendingRestart" size="small" type="warning">
            {{ params.pendingRestart }} 项待重启生效
          </el-tag>
          <span v-else class="dev-toolbar-note">无待重启参数</span>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ② 健康自检 -->
    <el-tab-pane label="健康自检" name="health" :lazy="true">
      <DevCard
        title="前置条件"
        :desc="`共 ${checks.length} 项检查，失败 ${health.failed != null ? health.failed : '—'} 项。红色项通常就是「功能没反应」的原因。`"
      >
        <el-empty v-if="!checks.length" description="未返回检查项" :image-size="72" />
        <el-table v-else :data="checks" class="dev-table" size="small">
          <el-table-column label="检查项" width="180">
            <template #default="{ row }"><span class="dev-kv-value">{{ row.name || row.key }}</span></template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.ok ? 'success' : 'danger'" effect="plain">
                {{ row.ok ? '正常' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="详情" min-width="320">
            <template #default="{ row }">
              <span class="dev-mono">{{ row.detail || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </DevCard>

      <DevCard title="怎么用这份自检" desc="按顺序排查，先看依赖再看功能。">
        <ol class="dev-ordered">
          <li><b>数据库 / 迁移</b>：失败意味着表结构不对，其他项都不用看了。</li>
          <li><b>世界挂载</b>：失败时本页大部分域都会显示「没有挂载的世界」。</li>
          <li><b>AI 供应商 / 决策模型</b>：失败时 AI 判定层会 fail-closed 静默——角色看起来「不动」，但日志里一条 AI 调用都没有。</li>
          <li><b>数据目录 / 日志文件</b>：失败通常是没有写权限，表现为「参数存不下来」。</li>
        </ol>
      </DevCard>
    </el-tab-pane>

    <!-- ③ 最近异常 -->
    <el-tab-pane label="最近异常" name="errors" :lazy="true">
      <DevCard
        title="最近失败的 AI 调用"
        :desc="`${errors.length} 条（最多 20 条）。这些是「角色行为不真实」最直接的证据：被护栏拦下、超时、JSON 解析失败都会落在这里。`"
        flush
      >
        <el-empty v-if="!errors.length" description="最近没有失败调用：这是好消息" :image-size="72" />
        <el-table v-else :data="errors" class="dev-table" size="small">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="场景" width="150">
            <template #default="{ row }">{{ row.scene || '—' }}</template>
          </el-table-column>
          <el-table-column label="模型" width="180">
            <template #default="{ row }"><span class="dev-mono">{{ row.model || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" type="danger" effect="plain">{{ row.status || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="320">
            <template #default="{ row }"><span class="dev-mono">{{ row.errorMessage || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }"><span class="dev-mono">{{ formatTime(row.createdAt) }}</span></template>
          </el-table-column>
        </el-table>
        <div class="dev-toolbar dev-mt dev-tab-pad">
          <el-button size="small" @click="go('/dev/ai')">去 AI 调用页看完整 Prompt</el-button>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 系统环境 -->
    <el-tab-pane label="系统环境" name="env" :lazy="true">
      <div class="dev-grid-2">
        <DevCard title="运行时" desc="排查「为什么这台机器上不一样」的第一手材料。">
          <DevKv :items="runtimeItems" />
        </DevCard>
        <DevCard title="目录与端口" desc="数据目录、日志、上传目录、覆盖文件的实际路径。">
          <DevKv :items="pathItems" />
        </DevCard>
      </div>
      <DevCard title="内存占用" desc="JVM 堆：已用 / 总量 / 上限（上限由启动参数决定）。">
        <div class="dev-metrics">
          <MetricCard label="堆已用" :value="mem.usedMb != null ? mem.usedMb : '—'" unit="MB" hint="Runtime.totalMemory - freeMemory" />
          <MetricCard label="堆上限" :value="mem.maxMb != null ? mem.maxMb : '—'" unit="MB" hint="Runtime.maxMemory（-Xmx）" />
          <MetricCard label="堆总量" :value="mem.totalMb != null ? mem.totalMb : '—'" unit="MB" hint="当前已向系统申请到的堆" />
          <MetricCard label="堆空闲" :value="mem.freeMb != null ? mem.freeMb : '—'" unit="MB" hint="totalMemory 内仍空闲的部分" />
        </div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 总览域。
 *
 * <p>四个子页各自回答一个问题：运行（在不在跑）、自检（能不能跑）、异常（哪里坏了）、
 * 环境（这台机器是什么）。刻意不做成「一屏全塞」——排查时最怕的是信息都在但找不到。</p>
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import MetricCard from '@/shared/components/MetricCard.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevKv from '../components/DevKv.vue'
import { fetchDevOverview, fetchDevHealth, fetchDevSystem } from '@/shared/api/dev'
import { useDevPage, compactNumber, formatTime } from '../useDevPage'

const router = useRouter()
const tab = ref('runtime')

const { state, load } = useDevPage(async ({ worldId }) => {
  const [overview, health, system] = await Promise.all([
    fetchDevOverview(worldId).catch((e) => ({ __error: e?.message })),
    fetchDevHealth(worldId).catch((e) => ({ __error: e?.message, checks: [], failed: -1 })),
    fetchDevSystem().catch((e) => ({ __error: e?.message }))
  ])
  return { overview, health, system }
})

const overview = computed(() => state.data?.overview || {})
const engine = computed(() => overview.value.engine || {})
const content = computed(() => overview.value.content || {})
const cost = computed(() => overview.value.cost || {})
const params = computed(() => overview.value.params || {})
const errors = computed(() => overview.value.recentErrors || [])
const health = computed(() => state.data?.health || {})
const checks = computed(() => health.value.checks || [])
const system = computed(() => state.data?.system || {})
const mem = computed(() => system.value.memory || {})

/** 时钟状态文案（暂停 / 运行 / 静止） */
const clockText = computed(() => {
  if (!engine.value.mounted) return '未挂载'
  if (engine.value.paused) return '已暂停'
  return engine.value.worldRunning ? '运行中' : '静止'
})

/** 引擎块键值行 */
const engineItems = computed(() => [
  { label: '世界', value: engine.value.projectName || engine.value.projectId },
  { label: '世界 ID', value: engine.value.projectId, mono: true },
  { label: '暂停', value: engine.value.paused },
  { label: '运行标记', value: engine.value.worldRunning },
  { label: '推进倍率', value: engine.value.rate },
  { label: '上次模拟', value: formatTime(engine.value.lastSimTime) },
  { label: '上次引擎跑', value: formatTime(engine.value.lastEngineRunAt) },
  { label: '参数总数', value: params.value.total },
  { label: '已改参数', value: params.value.overridden },
  { label: '覆盖文件', value: params.value.overrideFile, mono: true }
])

/** 运行时键值行 */
const runtimeItems = computed(() => [
  { label: '应用版本', value: system.value.appVersion },
  { label: 'Java', value: system.value.javaVersion },
  { label: '供应商', value: system.value.javaVendor },
  { label: '操作系统', value: system.value.os },
  { label: 'CPU 核数', value: system.value.cpuCores },
  { label: '运行时长', value: uptimeText.value },
  { label: '进程 PID', value: system.value.pid, mono: true },
  { label: '时区', value: system.value.timezone },
  { label: '激活 Profile', value: system.value.profile || '（默认）' },
  { label: '迁移版本', value: system.value.schemaVersion }
])

/** 目录键值行 */
const pathItems = computed(() => [
  { label: '数据目录', value: system.value.dataDir, mono: true },
  { label: '日志文件', value: system.value.logFile, mono: true },
  { label: '上传目录', value: system.value.uploadDir, mono: true },
  { label: '覆盖文件', value: system.value.overrideFile, mono: true },
  { label: '服务端口', value: system.value.serverPort },
  { label: '覆盖项数', value: system.value.overrideCount },
  { label: '待重启', value: system.value.pendingRestart }
])

/** 运行时长（秒 → 「3 天 4 小时 5 分」） */
const uptimeText = computed(() => {
  const s = Number(system.value.uptimeSeconds)
  if (!Number.isFinite(s) || s <= 0) return '—'
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  return [d ? `${d} 天` : '', h ? `${h} 小时` : '', `${m} 分`].filter(Boolean).join(' ')
})

/** 站内跳转 */
function go(path) { router.push(path) }
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
.dev-ordered {
  margin: 0;
  padding-left: 20px;
  font-size: var(--density-font-sm, 0.82rem);
  line-height: 1.9;
  color: var(--text-secondary);
}
.dev-ordered b { color: var(--text-primary); }
</style>
