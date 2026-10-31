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
    title="世界引擎"
    desc="游戏时钟、宏观指标、地图场景与世界事件。"
  >
    <template #actions>
      <el-tag v-if="state.lastAt" size="small" effect="plain">数据于 {{ state.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <div v-if="state.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ state.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 时钟与环境 -->
    <el-tab-pane label="时钟与环境" name="clock" :lazy="true">
      <div class="dev-grid-2">
        <DevCard title="世界时钟" desc="唯一权威：actor_world_clock.last_game_second。改倍率前请先暂停世界。">
          <DevKv :items="clockItems" />
          <div class="dev-toolbar dev-mt">
            <el-button size="small" :type="clock.paused ? 'primary' : 'default'" @click="togglePause">
              {{ clock.paused ? '继续推进世界' : '暂停世界' }}
            </el-button>
            <el-input-number v-model="rateDraft" :min="0" :max="120" :step="1" size="small" controls-position="right" style="width: 120px" />
            <span class="dev-toolbar-note">倍率</span>
            <el-button size="small" :disabled="rateDraft === clock.rate" @click="saveRate">保存倍率</el-button>
          </div>
          <div class="dev-notice dev-mt">
            <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
            <span>世界默认全静止（auto-resume=false）。改倍率/暂停都会立刻改变所有角色的推进节奏，改动会写入审计日志。</span>
          </div>
        </DevCard>

        <DevCard title="决策激活与环境" desc="实时决策开关决定「引擎是否真的在跑 AI 判定」；环境上下文决定角色的感官与需求衰减。">
          <div class="dev-subtitle">实时决策</div>
          <DevKv :items="decisionItems" />
          <div class="dev-subtitle">环境上下文</div>
          <DevKv :items="envItems" />
        </DevCard>
      </div>
    </el-tab-pane>

    <!-- ② 世界快照 -->
    <el-tab-pane label="世界快照" name="snapshot" :lazy="true">
      <DevCard title="引擎快照" desc="时钟 + 引擎配置 + 角色状态 + 近期事件 + 近期 AI 调用的同刻快照（一次请求看到全局）。">
        <DevAuto v-if="state.data?.snapshot" :value="state.data.snapshot" />
        <div v-else class="dev-toolbar-note">未返回快照（世界可能未挂载）</div>
      </DevCard>
      <DevCard title="原始 JSON" desc="字段随迭代变化，这里始终显示后端真实返回。">
        <DevJson :value="state.data?.snapshot || {}" title="engine/snapshot" :max-height="360" />
      </DevCard>
    </el-tab-pane>

    <!-- ③ 宏观社会 -->
    <el-tab-pane label="宏观社会" name="macro" :lazy="true">
      <DevCard title="宏观状态" desc="核心指标 + 简报 + 五段 indicators + 民怨/季节/收成系数。">
        <DevAuto v-if="state.data?.macro" :value="state.data.macro" />
        <div v-else class="dev-toolbar-note">未返回宏观状态（可能尚未初始化）</div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 地图与地点 -->
    <el-tab-pane label="地图与地点" name="map" :lazy="true">
      <DevCard title="场景（Scenes）" :desc="`${sceneCount} 个场景。场景是「演化/对谈」发生的容器，也是角色位置的落点。`" flush>
        <DevAuto v-if="state.data?.scenes" :value="{ scenes: state.data.scenes }" />
        <div v-else class="dev-toolbar-note dev-tab-pad">未返回场景列表</div>
      </DevCard>
      <DevCard title="世界地点" :desc="`${locationCount} 个地点。角色 locationId 指向这里。`" flush>
        <DevAuto v-if="state.data?.locations" :value="{ locations: state.data.locations }" />
        <div v-else class="dev-toolbar-note dev-tab-pad">未返回地点列表</div>
      </DevCard>
    </el-tab-pane>

    <!-- ⑤ 事件流 -->
    <el-tab-pane label="事件流" name="events" :lazy="true">
      <DevCard title="世界事件（最近 50 条）" desc="按发生顺序倒排；「订阅实时」用 SSE 增量追加，适合盯着某个角色看它下一秒做什么。">
        <template #actions>
          <el-button size="small" :type="streaming ? 'danger' : 'default'" @click="toggleStream">
            {{ streaming ? '停止订阅' : '订阅实时' }}
          </el-button>
        </template>
        <el-table :data="events" class="dev-table" size="small" max-height="440">
          <el-table-column prop="gameHour" label="游戏时" width="90" />
          <el-table-column prop="kind" label="类型" width="140" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" width="110" show-overflow-tooltip />
          <el-table-column prop="locationId" label="地点" width="90" />
          <el-table-column prop="characterRef" label="角色" width="120" show-overflow-tooltip />
          <el-table-column label="摘要" min-width="280">
            <template #default="{ row }">
              {{ row.summary || row.text || row.content || row.title || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt || row.occurredAt) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="!events.length" class="dev-toolbar-note dev-tab-pad">暂无事件</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 世界引擎域。
 *
 * <p>时钟页允许「暂停/继续 + 改倍率」两个写操作：这是排查「世界为什么不推进」时
 * 最常需要的动作，且都能一键还原。写操作带二次确认 + 审计留痕。</p>
 *
 * <p>事件页额外提供 SSE 增量订阅：一次性拉取只能看到历史，而「角色刚做了什么」
 * 恰恰是判断行为真实性的第一现场。</p>
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevKv from '../components/DevKv.vue'
import DevAuto from '../components/DevAuto.vue'
import DevJson from '../components/DevJson.vue'
import {
  fetchWorldClock, fetchRealtimeDecision, fetchEnvironmentContext, fetchMacroState,
  fetchScenes, fetchWorldLocations, fetchWorldEvents, updateWorldClock, worldEventsStreamUrl
} from '@/shared/api'
import { fetchEngineSnapshot } from '@/shared/api/dev'
import { appendDevAudit } from '@/shared/api/dev'
import { useDevPage, formatTime } from '../useDevPage'
import { devContext, devOperator } from '../devContext'

const tab = ref('clock')

const { state, load } = useDevPage(async ({ worldId }) => {
  const safe = (p) => p.catch(() => null)
  const [clock, decision, env, snapshot, macro, scenes, locations, events] = await Promise.all([
    safe(fetchWorldClock(worldId)),
    safe(fetchRealtimeDecision(worldId)),
    safe(fetchEnvironmentContext(worldId)),
    safe(fetchEngineSnapshot(worldId)),
    safe(fetchMacroState(worldId)),
    safe(fetchScenes(worldId)),
    safe(fetchWorldLocations(worldId)),
    safe(fetchWorldEvents(worldId, { limit: 50 }))
  ])
  return { clock, decision, env, snapshot, macro, scenes, locations, events }
})

const clock = computed(() => state.data?.clock || {})
const decision = computed(() => state.data?.decision || {})
const env = computed(() => state.data?.env || {})
const sceneCount = computed(() => (state.data?.scenes || []).length)
const locationCount = computed(() => (state.data?.locations || []).length)

/** 倍率草稿（与后端值分离：避免保存失败时显示成已生效） */
const rateDraft = ref(1)
watch(clock, (c) => { if (c && c.rate != null) rateDraft.value = Number(c.rate) }, { immediate: true })

/** 时钟键值行 */
const clockItems = computed(() => [
  { label: '游戏时刻', value: clock.value.gameTimeText },
  { label: '秒数', value: clock.value.lastGameSecond, mono: true },
  { label: '暂停', value: clock.value.paused },
  { label: '运行标记', value: clock.value.worldRunning },
  { label: '倍率', value: clock.value.rate },
  { label: '上次模拟', value: formatTime(clock.value.lastSimTime) },
  { label: '上次引擎跑', value: formatTime(clock.value.lastEngineRunAt) }
])

/** 实时决策键值行 */
const decisionItems = computed(() => [
  { label: '已激活', value: decision.value.active },
  { label: '手动常开', value: decision.value.manualEnabled },
  { label: '沙盘页驱动', value: decision.value.sandboxActive },
  { label: '演化页驱动', value: decision.value.evolutionActive }
])

/** 环境上下文键值行（字段名按后端为准，缺失显示 —） */
const envItems = computed(() => {
  const e = env.value || {}
  return [
    { label: '季节', value: e.season },
    { label: '游戏月', value: e.gameMonth },
    { label: '时刻阶段', value: e.phase || e.timePhase },
    { label: '天气', value: e.weather },
    { label: '气温', value: e.temperature },
    { label: '体感', value: e.feelsLike },
    { label: '日出', value: e.sunrise },
    { label: '日落', value: e.sunset },
    { label: '环境文本', value: e.stateText || e.envText },
    { label: '感官标签', value: Array.isArray(e.senses) ? e.senses.join('、') : e.senses }
  ]
})

/** 事件行（初始拉取 + SSE 追加合并） */
const events = ref([])
const streamSource = ref(null)
const streaming = computed(() => !!streamSource.value)
watch(() => state.data?.events, (list) => {
  if (!Array.isArray(list)) return
  events.value = [...list]
  if (streaming.value) return
}, { immediate: true })

/** 暂停 / 继续世界（写操作：二次确认 + 审计） */
async function togglePause() {
  const next = !clock.value.paused
  try {
    await ElMessageBox.confirm(
      next
        ? '暂停后所有角色的需求衰减与世界推进都会停止（AI 判定不再触发）。确认暂停？'
        : '继续后世界会按当前倍率推进，角色会开始消耗需求并触发 AI 决策（可能产生费用）。确认继续？',
      next ? '暂停世界' : '继续世界',
      { type: 'warning', confirmButtonText: next ? '暂停' : '继续', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  try {
    await updateWorldClock(devContext.worldId, { paused: next })
    await appendDevAudit({
      action: 'world.pause', target: `world:${devContext.worldId}`, oldValue: String(clock.value.paused),
      newValue: String(next), effect: 'immediate', page: 'dev/world', operator: devOperator(),
      note: next ? '暂停世界' : '继续世界'
    }).catch(() => {})
    ElMessage.success(next ? '已暂停世界' : '已继续世界')
    load(true)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

/** 保存时间倍率 */
async function saveRate() {
  const worldId = devContext.worldId
  const old = clock.value.rate
  try {
    await ElMessageBox.confirm(
      `把时间倍率从 ${old} 改为 ${rateDraft.value}？倍率直接决定角色变饿、变困、事件发生的速度。`,
      '修改时间倍率',
      { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  try {
    await updateWorldClock(worldId, { rate: rateDraft.value })
    await appendDevAudit({
      action: 'world.rate', target: `world:${worldId}`, oldValue: String(old),
      newValue: String(rateDraft.value), effect: 'immediate', page: 'dev/world', operator: devOperator(),
      note: '修改时间倍率'
    }).catch(() => {})
    ElMessage.success('倍率已更新')
    load(true)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

/** 订阅 / 停止世界事件 SSE */
function toggleStream() {
  if (streamSource.value) {
    streamSource.value.close()
    streamSource.value = null
    return
  }
  const worldId = devContext.worldId
  if (!worldId) {
    ElMessage.warning('当前没有挂载的世界，无法订阅事件流')
    return
  }
  const src = new EventSource(worldEventsStreamUrl(worldId))
  src.addEventListener('world-event', onStreamEvent)
  src.onmessage = onStreamEvent
  src.onerror = () => { /* 断线由 EventSource 自动重连，不打断页面 */ }
  streamSource.value = src
  ElMessage.success('已开始订阅世界事件')
}

/** SSE 消息 → 列表顶部追加（上限 100 条，避免长时间挂着把内存吃满） */
function onStreamEvent(ev) {
  if (!ev?.data) return
  try {
    const parsed = JSON.parse(ev.data)
    events.value = [parsed, ...events.value].slice(0, 100)
  } catch (_) { /* 非 JSON 心跳忽略 */ }
}

onBeforeUnmount(() => {
  if (streamSource.value) {
    streamSource.value.close()
    streamSource.value = null
  }
})
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
</style>
