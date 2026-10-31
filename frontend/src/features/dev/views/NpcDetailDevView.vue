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
    :title="npcName"
    :desc="headDesc"
  >
    <template #actions>
      <el-button size="small" @click="goBack">
        <el-icon><HIcon name="ArrowLeft" /></el-icon>&nbsp;返回列表
      </el-button>
      <el-tag v-if="page.lastAt" size="small" effect="plain">{{ page.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <!-- 块级降级提示：哪个块读失败、为什么，直接写清楚（而不是整页空白） -->
  <div v-if="blockErrors.length" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>
      <div v-for="e in blockErrors" :key="e.block">「{{ blockName(e.block) }}」读取失败：{{ e.message }}</div>
    </div>
  </div>
  <div v-else-if="page.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ page.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 总览 -->
    <el-tab-pane label="总览" name="overview" :lazy="true">
      <div class="dev-grid-2">
        <DevCard title="档案" desc="来自 actor_profile。人格 11 维在下方单独展开。">
          <DevKv :items="profileItems" />
        </DevCard>
        <DevCard title="此刻状态摘要" desc="判断「这个角色现在像不像一个活人」最快的一组数。">
          <DevKv :items="stateSummaryItems" />
          <div class="dev-subtitle">位置</div>
          <DevKv :items="positionItems" />
        </DevCard>
      </div>

      <div class="dev-metrics">
        <MetricCard label="需求紧迫" :value="urgencyText" :hint="state.urgencyText || 'urgencyLevel + urgencyText'" />
        <MetricCard label="主导情绪" :value="state.dominantEmotion || '—'" :unit="state.emotionIntensity != null ? `${state.emotionIntensity}` : ''" hint="dominantEmotion + emotionIntensity" />
        <MetricCard label="目标" :value="goals.count != null ? goals.count : '—'" unit="个" :breakdown="[{ label: '今日计划', value: goals.planToday ? '有' : '无' }]" hint="actor_character_goal 全部状态" />
        <MetricCard label="记忆" :value="memoryCount" unit="条" :breakdown="[{ label: '工作记忆', value: workingCount }]" hint="长期记忆（最多 30）+ 工作记忆" />
        <MetricCard label="对话会话" :value="dialogues.sessionCount != null ? dialogues.sessionCount : '—'" unit="个" hint="该角色参与过的会话总数" />
        <MetricCard label="刺激" :value="stimuli.length" unit="条" hint="最近收到的刺激与护栏判定（为什么没触发 AI）" />
      </div>

      <DevCard title="人格（personaJson）" desc="11 维人格解析后的对象；解析失败时按原文显示。">
        <DevJson :value="profile.persona" title="persona" :max-height="280" />
      </DevCard>
    </el-tab-pane>

    <!-- ② 位置与感知 -->
    <el-tab-pane label="位置与感知" name="perception" :lazy="true">
      <div class="dev-grid-2">
        <DevCard title="位置" desc="档案上的位置快照 + 感知服务解析出的地点名（两者不一致时说明位置还没同步）。">
          <DevKv :items="positionItems" />
        </DevCard>
        <DevCard title="感知摘要" desc="这一块就是「角色此刻眼里/耳里的世界」，也是每次 AI 决策的输入前半段。">
          <DevKv :items="perceptionItems" />
        </DevCard>
      </div>

      <DevCard title="感知到的可见事件" :desc="`${visibleEvents.length} 条。这些事件会进入 Prompt，决定角色「注意到什么」。`">
        <el-empty v-if="!visibleEvents.length" description="当前没有可见事件" :image-size="64" />
        <div v-else class="dev-event-list">
          <div v-for="(e, i) in visibleEvents" :key="i" class="dev-event-item">
            <span class="dev-event-index">{{ i + 1 }}</span>
            <span class="dev-event-text">{{ typeof e === 'string' ? e : JSON.stringify(e) }}</span>
          </div>
        </div>
      </DevCard>

      <DevCard title="感知原文" desc="关系/目标/计划/知识提示的原始拼装结果——Prompt 里长这样。">
        <div class="dev-grid-2">
          <DevJson :value="perception.relationSummary" title="relationSummary" :max-height="200" />
          <DevJson :value="perception.goalsSummary" title="goalsSummary" :max-height="200" />
          <DevJson :value="perception.planSummary" title="planSummary" :max-height="200" />
          <DevJson :value="perception.knowledgeHints" title="knowledgeHints" :max-height="200" />
        </div>
      </DevCard>

      <DevCard title="刺激与护栏判定" :desc="`${stimuli.length} 条。被护栏拦下的刺激不会触发 AI 调用——「角色不反应」的第一手证据。`" flush>
        <el-empty v-if="!stimuli.length" description="没有该角色的刺激记录" :image-size="64" />
        <el-table v-else :data="stimuli" class="dev-table" size="small" max-height="360">
          <el-table-column prop="gameSecond" label="游戏秒" width="100" />
          <el-table-column prop="type" label="类型" width="120" show-overflow-tooltip />
          <el-table-column label="刺激内容" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.stimulusText || '—' }}</template>
          </el-table-column>
          <el-table-column prop="result" label="判定" width="140" show-overflow-tooltip />
          <el-table-column label="详情" min-width="200" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.detail || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="真实时间" width="170">
            <template #default="{ row }">{{ formatTime(row.realTime) }}</template>
          </el-table-column>
        </el-table>
      </DevCard>
    </el-tab-pane>

    <!-- ③ 需求与 PAD -->
    <el-tab-pane label="需求与 PAD" name="needs" :lazy="true">
      <DevCard
        title="八项需求"
        :desc="`来自 actor_npc_state。数值 0~100，档位文案与引擎同源（needDescriptor），所以这里看到的就是引擎判定用的档位。`"
      >
        <div v-if="!needs.length" class="dev-toolbar-note">该角色还没有状态行（从未被引擎初始化）</div>
        <div v-else class="dev-needs">
          <div v-for="n in needs" :key="n.key" class="dev-need">
            <span class="dev-need-name">{{ n.name }}</span>
            <div class="dev-bar">
              <div class="dev-bar-track">
                <div class="dev-bar-fill" :class="n.cls" :style="{ width: `${n.pct}%` }"></div>
              </div>
              <span class="dev-bar-num">{{ n.value != null ? n.value : '—' }}</span>
            </div>
            <span class="dev-need-label">{{ n.label || '—' }}</span>
          </div>
        </div>
      </DevCard>

      <div class="dev-grid-2">
        <DevCard title="PAD 情绪" desc="内部情绪（决定行为倾向）与表达情绪（决定别人看到什么）分开看，不一致就是「口是心非」。">
          <div class="dev-subtitle">内部 PAD</div>
          <DevKv :items="padItems" />
          <div class="dev-subtitle">表达 PAD</div>
          <DevKv :items="expressedPadItems" />
          <div class="dev-subtitle">情绪强度</div>
          <DevKv :items="emotionItems" />
        </DevCard>
        <DevCard title="生理与作息" desc="最近进食/睡眠的游戏时刻 + 环境修正；这些数值直接决定需求衰减速度。">
          <DevKv :items="physioItems" />
        </DevCard>
      </div>

      <DevCard title="生理基线 / 每日计划 / 面具快照" desc="引擎内部结构（physioJson / dailyPlanJson / maskSnapshotJson），字段随版本变化，按原文展示。">
        <div class="dev-grid-3">
          <DevJson :value="state.physio" title="physio" :max-height="220" />
          <DevJson :value="state.physioCoef" title="physioCoef" :max-height="220" />
          <DevJson :value="state.dailyPlan" title="dailyPlan" :max-height="220" />
        </div>
        <div class="dev-mt">
          <DevJson :value="state.maskSnapshot" title="maskSnapshot" :max-height="220" />
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 目标与决策 -->
    <el-tab-pane label="目标与决策" name="goals" :lazy="true">
      <DevCard :title="`目标（${goals.items.length}）`" desc="active 目标进入 Prompt；finished 目标是「它做过什么」的历史证据。" flush>
        <el-empty v-if="!goals.items.length" description="该角色没有目标记录" :image-size="64" />
        <el-table v-else :data="goals.items" class="dev-table" size="small" max-height="360">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column label="描述" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'" effect="plain">{{ row.status || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="80" />
          <el-table-column label="完成时间" width="170">
            <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
          </el-table-column>
        </el-table>
      </DevCard>

      <DevCard title="今日计划" desc="planToday：当天的时间段安排，决定角色「什么时候该做什么」。">
        <DevJson :value="goals.planToday" title="planToday" :max-height="240" />
      </DevCard>

      <DevCard title="最近决策链路" desc="按 trace 聚合：一次决策 = 感知 → 思考 → 行动 → 学习。展开看每一步的输入输出。">
        <DevAuto v-if="decisions" :value="decisions" />
        <el-empty v-else description="没有决策链路记录（该角色可能还没触发过 AI 判定）" :image-size="64" />
      </DevCard>
    </el-tab-pane>

    <!-- ⑤ 记忆 -->
    <el-tab-pane label="记忆" name="memory" :lazy="true">
      <DevCard title="核心记忆块" desc="core：长期稳定的自我认知与关系认知（跨会话保留）。">
        <DevAuto v-if="memories.core" :value="memories.core" />
        <div v-else class="dev-toolbar-note">无核心记忆块</div>
      </DevCard>

      <DevCard :title="`长期记忆（${memories.longTermTotal != null ? memories.longTermTotal : longTerm.length}）`" :desc="`最多显示 30 条。${memories.longTermTotal === -1 ? '（服务层越权校验失败，已回退仓库直读）' : ''}`" flush>
        <el-empty v-if="!longTerm.length" description="没有长期记忆" :image-size="64" />
        <el-table v-else :data="longTerm" class="dev-table" size="small" max-height="380">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="type" label="类型" width="110" show-overflow-tooltip />
          <el-table-column label="内容" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">{{ row.content || '—' }}</template>
          </el-table-column>
          <el-table-column prop="importance" label="重要度" width="80" />
          <el-table-column prop="version" label="版本" width="70" />
          <el-table-column label="过期" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.stale" size="small" type="warning" effect="plain">stale</el-tag>
              <span v-else class="dev-toolbar-note">—</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </DevCard>

      <DevCard :title="`工作记忆（${working.length}）`" desc="working：当前场景内的短期上下文，通常随场景切换被清理。" flush>
        <DevAuto v-if="working.length" :value="{ working }" />
        <div v-else class="dev-toolbar-note dev-tab-pad">没有工作记忆</div>
      </DevCard>
    </el-tab-pane>

    <!-- ⑥ 对话 -->
    <el-tab-pane label="对话" name="dialogue" :lazy="true">
      <DevCard :title="`会话（最近 ${sessions.length} / 共 ${dialogues.sessionCount != null ? dialogues.sessionCount : sessions.length}）`" desc="按最近更新时间倒排。会话是冻结时刻的快照，里面才有历史消息。" flush>
        <el-empty v-if="!sessions.length" description="该角色还没有对话会话" :image-size="64" />
        <el-table v-else :data="sessions" class="dev-table" size="small">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column prop="type" label="类型" width="90" show-overflow-tooltip />
          <el-table-column prop="locationName" label="地点" width="130" show-overflow-tooltip />
          <el-table-column prop="frozenTimeText" label="冻结时刻" width="140" show-overflow-tooltip />
          <el-table-column prop="messageCount" label="消息" width="70" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
      </DevCard>

      <DevCard :title="`最新会话消息（${recentMessages.length}）`" desc="最新会话的最后 20 条消息，用于核对「角色说的话有没有人格一致性」。">
        <DevAuto v-if="recentMessages.length" :value="{ messages: recentMessages }" />
        <el-empty v-else description="没有消息" :image-size="64" />
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · NPC 全息详情（6 个子页）。
 *
 * <p>这是「让角色像真人」这个项目目标最直接的观测窗口：位置 / 需求 / PAD / 目标 / 决策 /
 * 记忆 / 对话 / 感知，八块数据一次拉齐（后端聚合接口 `/api/dev/worlds/{id}/npc-hologram`），
 * 单块失败只降级该块并写明原因。</p>
 *
 * <p>所有块都是只读：观测页面绝不改变被观测的角色（不触发 AI、不写库）。</p>
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import MetricCard from '@/shared/components/MetricCard.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevKv from '../components/DevKv.vue'
import DevJson from '../components/DevJson.vue'
import DevAuto from '../components/DevAuto.vue'
import { fetchDevNpcHologram } from '@/shared/api/dev'
import { useDevPage, formatTime } from '../useDevPage'

const route = useRoute()
const router = useRouter()
const tab = ref('overview')

/** 角色 ID（路由参数） */
const characterId = computed(() => Number(route.params.id))

const { state: page } = useDevPage(async ({ worldId }) => fetchDevNpcHologram(worldId, `npc:${characterId.value}`))

/** 各信息块（块缺失时给空对象，模板永远不用判空） */
const profile = computed(() => page.data?.profile || {})
const state = computed(() => page.data?.state || {})
const position = computed(() => page.data?.position || {})
const perception = computed(() => page.data?.perception || {})
const goals = computed(() => page.data?.goals || { items: [], planToday: '', count: 0 })
const decisions = computed(() => page.data?.decisions || null)
const memories = computed(() => page.data?.memories || {})
const dialogues = computed(() => page.data?.dialogues || { sessions: [], recentMessages: [] })
const stimuli = computed(() => page.data?.stimuli || [])
const meta = computed(() => page.data?.meta || {})
const blockErrors = computed(() => meta.value.errors || [])

/** 与后端一致的块名（报错文案可读） */
const BLOCK_NAMES = {
  profile: '档案', state: '生理与情绪', position: '位置', perception: '感知',
  goals: '目标', decisions: '决策链路', memories: '记忆', dialogues: '对话', stimuli: '刺激'
}
function blockName(key) { return BLOCK_NAMES[key] || key }

const npcName = computed(() => profile.value.name || `角色 ${characterId.value}`)
const headDesc = computed(() => {
  const p = profile.value
  const bits = [p.typeLabel, p.title, p.occupation, p.stage, p.location || position.value.location]
  return bits.filter(Boolean).join(' · ') || '全息详情'
})

const longTerm = computed(() => memories.value.longTerm || [])
const working = computed(() => memories.value.working || [])
const sessions = computed(() => dialogues.value.sessions || [])
const recentMessages = computed(() => dialogues.value.recentMessages || [])
const visibleEvents = computed(() => perception.value.visibleEvents || [])
const memoryCount = computed(() => (memories.value.longTermTotal != null && memories.value.longTermTotal >= 0
  ? memories.value.longTermTotal
  : longTerm.value.length))
const workingCount = computed(() => working.value.length)
const urgencyText = computed(() => {
  const lv = state.value.urgencyLevel
  const txt = state.value.urgencyText
  if (lv == null && !txt) return '—'
  return lv != null ? `${lv}${txt ? ' · ' + txt : ''}` : txt
})

/** 档案键值行 */
const profileItems = computed(() => {
  const p = profile.value
  return [
    { label: '引用', value: p.ref, mono: true },
    { label: '类型', value: p.typeLabel },
    { label: '头衔', value: p.title },
    { label: '性别', value: p.gender },
    { label: '种族', value: p.race },
    { label: '亚种族', value: p.subRace },
    { label: '阵营', value: p.affiliation },
    { label: '职业', value: p.occupation },
    { label: '年龄', value: p.age },
    { label: '阶段', value: p.stage },
    { label: '脑力档', value: p.brainTier },
    { label: '重要度', value: p.importance },
    { label: '主角', value: p.isProtagonist },
    { label: '状态', value: p.status },
    { label: '当前活动', value: p.currentActivity },
    { label: '人格版本', value: p.personaVersion },
    { label: '出生（游戏秒）', value: p.birthGameSecond, mono: true },
    { label: '详情', value: p.detail }
  ]
})

/** 状态摘要键值行 */
const stateSummaryItems = computed(() => {
  const s = state.value
  if (!s.exists) return [{ label: '状态行', value: '不存在（角色从未被引擎初始化）' }]
  return [
    { label: '活动状态', value: s.activityState },
    { label: '上一动作', value: s.lastAction },
    { label: '心情', value: s.mood },
    { label: '健康', value: s.health },
    { label: '主导情绪', value: s.dominantEmotion },
    { label: '紧急度', value: s.urgencyLevel },
    { label: '紧急描述', value: s.urgencyText },
    { label: '状态时刻', value: s.gameSecond, mono: true },
    { label: '更新时间', value: formatTime(s.updatedAt) }
  ]
})

/** 位置键值行 */
const positionItems = computed(() => {
  const p = position.value
  return [
    { label: '位置名', value: p.location },
    { label: '位置 ID', value: p.locationId, mono: true },
    { label: '感知地点', value: p.perceivedLocationName },
    { label: '当前活动', value: p.currentActivity },
    { label: '世界时间', value: p.gameTimeText },
    { label: '游戏秒', value: p.gameSecond, mono: true },
    { label: '自身状态文本', value: p.ownState }
  ]
})

/** 感知键值行 */
const perceptionItems = computed(() => {
  const p = perception.value
  if (!p.available) return [{ label: '感知', value: p.reason || '不可用' }]
  return [
    { label: '世界时间', value: p.gameTimeText },
    { label: '小时', value: p.gameHour },
    { label: '地点', value: p.locationName },
    { label: '自身状态', value: p.ownState },
    { label: '可见事件数', value: visibleEvents.value.length }
  ]
})

/** 需求行（中文名 → 后端键） */
const NEED_DEFS = [
  { key: 'hunger', name: '饥饿' },
  { key: 'thirst', name: '口渴' },
  { key: 'energy', name: '精力' },
  { key: 'bladder', name: '膀胱' },
  { key: 'hygiene', name: '清洁' },
  { key: 'social', name: '社交' },
  { key: 'fun', name: '趣味' },
  { key: 'comfort', name: '舒适' }
]
const needs = computed(() => {
  const raw = state.value.needs || {}
  return NEED_DEFS.map(({ key, name }) => {
    const item = raw[key] || {}
    const v = item.value
    const pct = Number.isFinite(Number(v)) ? Math.max(0, Math.min(100, Number(v))) : 0
    return {
      key,
      name,
      value: v,
      label: item.label,
      pct,
      cls: pct >= 70 ? '' : pct >= 35 ? 'is-warn' : 'is-danger'
    }
  })
})

/** PAD 键值行 */
const padItems = computed(() => {
  const pad = state.value.pad || {}
  return [
    { label: 'P 愉悦', value: pad.pleasure },
    { label: 'A 唤醒', value: pad.arousal },
    { label: 'D 支配', value: pad.dominance }
  ]
})
const expressedPadItems = computed(() => {
  const pad = state.value.expressedPad || {}
  return [
    { label: 'P 愉悦', value: pad.pleasure },
    { label: 'A 唤醒', value: pad.arousal },
    { label: 'D 支配', value: pad.dominance }
  ]
})
const emotionItems = computed(() => {
  const s = state.value
  return [
    { label: '主导情绪', value: s.dominantEmotion },
    { label: '强度', value: s.emotionIntensity },
    { label: '环境修正', value: s.envModifier }
  ]
})

/** 生理键值行 */
const physioItems = computed(() => {
  const s = state.value
  return [
    { label: '上次进食', value: s.lastEatGameSecond, mono: true },
    { label: '上次睡眠', value: s.lastSleepGameSecond, mono: true },
    { label: '状态时刻', value: s.gameSecond, mono: true },
    { label: 'NPC 类型', value: s.npcType },
    { label: '活动状态', value: s.activityState },
    { label: '更新时间', value: formatTime(s.updatedAt) }
  ]
})

/** 返回列表 */
function goBack() { router.push('/dev/npcs') }
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }

/* 需求条：名称 — 进度 — 档位，三列对齐（8 行不错位） */
.dev-needs { display: flex; flex-direction: column; gap: 8px; }
.dev-need {
  display: grid;
  grid-template-columns: 56px minmax(120px, 1fr) 110px;
  align-items: center;
  gap: var(--space-3, 12px);
  font-size: var(--density-font-sm, 0.82rem);
}
.dev-need-name { color: var(--text-secondary); }
.dev-need-label { color: var(--text-tertiary); text-align: right; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 可见事件列表 */
.dev-event-list { display: flex; flex-direction: column; gap: 6px; max-height: 320px; overflow: auto; }
.dev-event-item { display: flex; gap: 8px; font-size: var(--density-font-sm, 0.82rem); line-height: 1.7; }
.dev-event-index {
  flex: 0 0 22px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.dev-event-text { flex: 1; min-width: 0; word-break: break-word; color: var(--text-primary); }
</style>
