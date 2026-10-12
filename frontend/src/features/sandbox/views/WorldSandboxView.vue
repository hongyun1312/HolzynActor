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
  <!--
    世界沙盘 S4 全屏舞台：Three.js 3D 场景（像素地形 + GLB 资产 + NPC 标记/名牌/气泡）
    + 三层悬浮（左上时钟条与天气 / 右侧 NPC 面板 / 底部事件流）+ 地点浮窗 + 干预
  -->
  <div class="sb-stage">
    <!-- 3D 场景画布（S4：点击拾取 NPC/建筑；名牌气泡；晨昏昼夜；天气粒子） -->
    <Sandbox3DScene
      :scene-size="sceneSize"
      :layout="sceneLayout"
      :placements="placements"
      :npcs="npcList"
      :selected-ref="selectedRef"
      :game-second="liveGameSecond"
      :weather="weather"
      :bubbles="bubbles"
      :location-bubbles="locationBubbles"
      @progress="onAssetProgress"
      @pick-npc="onPickNpc"
      @pick-placement="onPickPlacement"
      @pick-empty="onPickEmpty"
    />

    <!-- S3 补全：资产加载进度浮层（GLB 逐批加载中显示） -->
    <div v-if="assetLoading" class="asset-loading">
      <div class="al-card">
        <div class="al-title">场景资产加载中</div>
        <el-progress :percentage="assetPercent" :stroke-width="6" :color="'#52d68a'" />
        <div class="al-sub">{{ assetProgress.loaded }} / {{ assetProgress.total }} 个模型</div>
      </div>
    </div>

    <!-- 左上：时钟条（世界时间 + 暂停/推进 + 实时状态 + S4 天气控制 + 标记图例） -->
    <div class="clock-bar">
      <div class="clock-info">
        <div class="clock-title">
          世界时钟
          <span class="live-dot" :class="{ on: liveConnected }">{{ liveConnected ? '实时' : '重连中' }}</span>
        </div>
        <div class="clock-text">{{ clockText }}</div>
      </div>
      <div class="clock-ops">
        <!-- S3 补全：场景布局信息（河/路/摆放数，来自主场景 layout_json + 摆放表） -->
        <span v-if="sceneInfoText" class="scene-info" :title="sceneInfoTitle">{{ sceneInfoText }}</span>
        <!-- S4：标记图例（金=特殊 NPC / 蓝=普通人群，与几何标记/光环同色） -->
        <span class="scene-info legend" title="金色=特殊 NPC，蓝色=普通人群；点击 NPC 或建筑可查看详情">
          <span class="lg-dot gold" />特殊 <span class="lg-dot blue" />人群
        </span>
        <!--
          S4 + 时间季节系统：天气切换（晴/雨/雪/阴 → 后端世界帧就地更新 + NPC 感知；
          起按季节修正气温 + 反季节反常事件；季节 chip/时段/日出日落/体感展示）
        -->
        <div class="weather-box" :title="weatherHint">
          <span class="weather-label">天气</span>
          <span v-if="weather.seasonName" class="season-chip" :class="'season-' + (weather.season || '')"
                :title="`季节（按游戏月固定绑定）：${weather.seasonName} · ${weather.month}月`">
            {{ weather.seasonName }}·{{ weather.month }}月
          </span>
          <el-radio-group
            v-model="weather.condition"
            size="small"
            :disabled="weatherBusy"
            @change="onWeatherChange"
          >
            <el-radio-button value="晴">晴</el-radio-button>
            <el-radio-button value="雨">雨</el-radio-button>
            <el-radio-button value="雪">雪</el-radio-button>
            <el-radio-button value="阴">阴</el-radio-button>
          </el-radio-group>
          <span v-if="weather.feelsLikeC != null" class="weather-meta">
            {{ weather.phaseName || '' }} · 体感 {{ Math.round(weather.feelsLikeC) }}℃
            <template v-if="weather.sunrise && weather.sunset"> · {{ weather.sunrise }}~{{ weather.sunset }}</template>
          </span>
        </div>
        <!-- 宏观社会迷你条 -->
        <div class="macro-chip" :class="{ open: macroOpen }" @click="macroOpen = !macroOpen; macroOpen && loadMacro()" :title="macroHint">
          <span class="macro-label">宏观</span>
          <template v-if="macro">
            <span class="macro-sec" :class="'season-' + (macro.season || '')">{{ seasonName(macro.season) }}</span>
            <span class="macro-item">收成 {{ (macro.harvestFactor || 0).toFixed(2) }}</span>
            <span class="macro-item">粮价 {{ macro.grainPrice != null ? Math.round(macro.grainPrice) : '—' }}</span>
            <span class="macro-item" :class="grievanceCls(macro.grievance)">民怨 {{ macro.grievance != null ? Math.round(macro.grievance) : '—' }}/100</span>
          </template>
          <span v-else class="macro-item">未初始化</span>
        </div>
        <el-button size="small" :type="clock.paused ? 'success' : 'warning'" :loading="clockBusy" @click="togglePause">
          {{ clock.paused ? '继续' : '暂停' }}
        </el-button>
        <el-button size="small" :loading="advancing" @click="advanceOne">推进一轮</el-button>
        <!-- 世界运行时状态（进入本页已自动开启；离开后仅手动常开的世界保持运行；title=激活原因） -->
        <span class="runtime-chip" :class="rtDecision.active ? 'on' : 'off'" :title="rtHint">
          <span class="rt-dot" />
          {{ rtDecision.active ? '世界运行中' : '世界静止' }}
        </span>
        <!-- 实时决策手动常开开关（默认关；页面打开已自动激活，手动常开则离开页面世界也持续推进） -->
        <span class="rt-toggle" :title="rtHint">
          <span class="rt-label">实时决策</span>
          <el-switch
            size="small"
            :model-value="rtDecision.manualEnabled"
            :loading="rtBusy"
            @change="toggleRtManual"
          />
        </span>
      </div>
    </div>

    <!-- 宏观详情弹层（收成/民怨/指标 + 最近宏观事件流；点击「宏观」条展开） -->
    <div v-if="macroOpen" class="macro-pop" @click.stop>
      <div class="mp-head">
        <span class="mp-title">宏观社会 · {{ macro ? templateLabel(macro.templateId) : '—' }}</span>
        <el-tag size="small" effect="plain">{{ aiIntensityLabel }}</el-tag>
        <el-button text size="small" class="mp-close" @click="macroOpen = false">✕</el-button>
      </div>
      <div v-if="macro" class="mp-grid">
        <div class="mp-cell"><span class="mp-k">人口</span><span class="mp-v">{{ macro.population }} 口</span></div>
        <div class="mp-cell"><span class="mp-k">季节</span><span class="mp-v">{{ seasonName(macro.season) }}</span></div>
        <div class="mp-cell"><span class="mp-k">收成系数</span><span class="mp-v">{{ (macro.harvestFactor || 0).toFixed(2) }}</span></div>
        <div class="mp-cell"><span class="mp-k">粮价</span><span class="mp-v">{{ macro.grainPrice != null ? Math.round(macro.grainPrice) : '—' }} 文/石</span></div>
        <div class="mp-cell"><span class="mp-k">市集</span><span class="mp-v">{{ macro.marketActivity != null ? Math.round(macro.marketActivity) : '—' }}</span></div>
        <div class="mp-cell"><span class="mp-k">库存</span><span class="mp-v">{{ macro.grainStock != null ? Math.round(macro.grainStock) : '—' }} 石</span></div>
        <div class="mp-cell"><span class="mp-k">民怨</span>
          <span class="mp-v" :class="grievanceCls(macro.grievance)">{{ macro.grievance != null ? Math.round(macro.grievance) : '—' }}/100</span>
        </div>
        <div class="mp-cell"><span class="mp-k">当权势力</span><span class="mp-v">{{ macro.topFaction || '—' }}</span></div>
      </div>
      <div class="mp-events">
        <div class="mp-sub">最近宏观事件</div>
        <div v-for="ev in macroEvents" :key="ev.id" class="mp-event">
          <el-tag size="small" :type="macroEvtTag(ev.severity)" effect="plain">{{ ev.eventType }}</el-tag>
          <span class="mp-ev-text">{{ ev.narrative || ev.title || '' }}</span>
        </div>
        <div v-if="!macroEvents.length" class="mp-empty">暂无宏观事件（日常 tick 产出后显示）</div>
      </div>
      <!-- 二阶涌现（行为聚合超阈值后产出，最近几条） -->
      <div v-if="emergenceEvents.length" class="mp-events">
        <div class="mp-sub">二阶涌现（M6.6 聚合检测）</div>
        <div v-for="ev in emergenceEvents" :key="ev.id" class="mp-event">
          <el-tag size="small" :type="macroEvtTag(ev.severity)" effect="plain">{{ ev.emergenceTypeDisplay || ev.emergenceType }}</el-tag>
          <span class="mp-ev-text">{{ ev.narrative || '' }}</span>
          <span class="mp-ev-meta">第{{ ev.gameDay + 1 }}日 · {{ ev.memberCount }} 人</span>
        </div>
      </div>
      <!-- 中观组织（势力/自发群体，最近几条） -->
      <div v-if="mesoGroups.length" class="mp-events">
        <div class="mp-sub">中观组织（M6.5a 协调层）</div>
        <div v-for="g in mesoGroups" :key="g.id" class="mp-event">
          <span class="mp-g-name">{{ g.name }}</span>
          <el-tag size="small" effect="plain">{{ groupTypeLabel(g.type) }}</el-tag>
          <span class="mp-ev-meta">影响力 {{ g.influence != null ? Math.round(g.influence) : '—' }} · {{ g.memberCount ?? 0 }} 人</span>
        </div>
      </div>
    </div>

    <!-- S4：地点详情浮窗（点击建筑/地点弹出：名称/类型/重要度/简介 + 在场 NPC） -->
    <div v-if="locationPopup" class="loc-popup">
      <div class="loc-head">
        <span class="loc-name">{{ locationPopup.name }}</span>
        <el-tag v-if="locationPopup.type" size="small" effect="plain">{{ locationPopup.type }}</el-tag>
        <span v-if="locationPopup.importance" class="loc-imp">重要度 {{ locationPopup.importance }}</span>
        <el-button text size="small" class="loc-close" @click="locationPopup = null">✕</el-button>
      </div>
      <div v-if="locationPopup.intro" class="loc-intro">{{ locationPopup.intro }}</div>
      <div v-else class="loc-intro muted">（该位置暂无简介）</div>
      <div v-if="locationPopup.nearby && locationPopup.nearby.length" class="loc-nearby">
        <div class="loc-nearby-title">在场 NPC（{{ locationPopup.nearby.length }}）</div>
        <div
          v-for="n in locationPopup.nearby"
          :key="n.npcRef"
          class="loc-npc"
          :class="{ selected: selectedRef === n.npcRef }"
          @click="selectNpc(n.npcRef)"
        >
          <span class="loc-npc-dot" :class="n.npcType" />
          <span class="loc-npc-name">{{ n.npcName || n.npcRef }}</span>
          <span class="loc-npc-action">{{ n.actionText || '' }}</span>
        </div>
      </div>
    </div>

    <!-- 右侧：NPC 面板（列表 + 选中干预） -->
    <div class="npc-panel">
      <div class="panel-head">
        <span class="panel-title">沙盘 NPC</span>
        <el-tag size="small" effect="plain">{{ npcList.length }} 名</el-tag>
      </div>
      <div class="panel-list">
        <div
          v-for="n in npcList"
          :key="n.npcRef"
          class="panel-item"
          :class="{ selected: selectedRef === n.npcRef }"
          @click="selectNpc(n.npcRef)"
        >
          <div class="pi-name-row">
            <span class="pi-name">{{ n.npcName || n.npcRef }}</span>
            <el-tag size="small" :type="moveTagType(n.moveState)" effect="plain">{{ moveLabel(n.moveState) }}</el-tag>
          </div>
          <div class="pi-action">{{ n.actionText || '—' }}</div>
          <!-- 需求条（special：饥饿/精力/膀胱/心情；crowd：饥饿/精力/心情） -->
          <div v-if="n.hunger != null" class="pi-needs">
            <div class="need-line">
              <span class="need-label">饥饿</span>
              <el-progress :percentage="n.hunger" :show-text="false" :stroke-width="4" :color="needColor(n.hunger)" />
              <span class="need-val" :class="needTone(n.hunger)">{{ n.hunger }}</span>
            </div>
            <div v-if="n.npcType === 'special' || n.bladder != null" class="need-line">
              <span class="need-label">膀胱</span>
              <el-progress :percentage="n.bladder" :show-text="false" :stroke-width="4" :color="needColor(n.bladder)" />
              <span class="need-val" :class="needTone(n.bladder)">{{ n.bladder }}</span>
            </div>
            <div class="need-line">
              <span class="need-label">精力</span>
              <el-progress :percentage="n.energy" :show-text="false" :stroke-width="4" :color="needColor(n.energy)" />
              <span class="need-val" :class="needTone(n.energy)">{{ n.energy }}</span>
            </div>
            <div class="need-line">
              <span class="need-label">心情</span>
              <el-progress :percentage="n.mood" :show-text="false" :stroke-width="4" :color="moodColor(n.mood)" />
              <span class="need-val">{{ n.mood }}</span>
            </div>
          </div>
          <div v-if="n.urgencyLevel > 0" class="pi-urgent">⚠ {{ n.urgencyText || '有紧急需求' }}</div>
          <div v-if="n.thoughtText" class="pi-thought">💭 {{ n.thoughtText }}</div>
        </div>
        <div v-if="npcList.length === 0" class="panel-empty">
          沙盘暂无 NPC<br /><span>去「角色 / 普通人群」页创建后回到本页观察</span>
        </div>
      </div>
      <!-- 选中 NPC：状态详情 + 插话 + 触发行动；未选中：手动事件注入 -->
      <div v-if="selected" class="panel-intervene">
        <div class="pi-title">{{ selected.npcName || selected.npcRef }} · 状态 / 干预</div>
        <!-- 状态详情（PAD 三维条 + 主导情绪/强度 + urgency 预警 + 健康） -->
        <div v-if="selected.mood != null || selected.dominantEmotion != null" class="sd-block">
          <div v-if="selected.urgencyLevel > 0" class="sd-urgency" :class="'lvl' + selected.urgencyLevel">
            ⚠ {{ selected.urgencyText || '有紧急需求' }}
          </div>
          <div class="sd-grid">
            <div class="sd-cell">
              <div class="sd-k">心情</div>
              <el-progress :percentage="selected.mood || 0" :stroke-width="7" :color="moodColor(selected.mood)" />
            </div>
            <div class="sd-cell">
              <div class="sd-k">主导情绪</div>
              <div class="sd-emotion">
                {{ emotionLabel(selected.dominantEmotion) }}
                <span class="sd-em-int">强度 {{ selected.emotionIntensity ?? 0 }}</span>
              </div>
            </div>
            <div v-if="selected.health != null" class="sd-cell">
              <div class="sd-k">健康</div>
              <el-progress :percentage="selected.health || 0" :stroke-width="7" :color="healthColor(selected.health)" />
            </div>
            <div v-if="selected.emotionPleasure != null" class="sd-cell">
              <div class="sd-k">PAD 情绪空间</div>
              <div class="pad-bars">
                <div class="pad-row">
                  <span class="pad-name">愉悦 P</span>
                  <el-progress :percentage="padPercent(selected.emotionPleasure)" :show-text="false" :stroke-width="5" :color="padColor(selected.emotionPleasure)" />
                  <span class="pad-val">{{ fmtPad(selected.emotionPleasure) }}</span>
                </div>
                <div class="pad-row">
                  <span class="pad-name">唤醒 A</span>
                  <el-progress :percentage="padPercent(selected.emotionArousal)" :show-text="false" :stroke-width="5" :color="padColor(selected.emotionArousal)" />
                  <span class="pad-val">{{ fmtPad(selected.emotionArousal) }}</span>
                </div>
                <div class="pad-row">
                  <span class="pad-name">支配 D</span>
                  <el-progress :percentage="padPercent(selected.emotionDominance)" :show-text="false" :stroke-width="5" :color="padColor(selected.emotionDominance)" />
                  <span class="pad-val">{{ fmtPad(selected.emotionDominance) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <el-input v-model="talkText" size="small" placeholder="对 TA 说一句话…" @keyup.enter="doTalk">
          <template #append>
            <el-button :disabled="!talkText.trim()" :loading="talking" @click="doTalk">插话</el-button>
          </template>
        </el-input>
        <div class="pi-btns">
          <el-button
            v-if="selected.npcType === 'special'"
            size="small"
            :loading="acting"
            @click="doAction"
          >触发行动</el-button>
          <el-tag v-else size="small" type="info" effect="plain">人群无行动触发</el-tag>
        </div>
      </div>
      <div v-else class="panel-intervene">
        <div class="pi-title">世界干预</div>
        <el-input v-model="eventText" size="small" placeholder="向世界注入一条事件…" @keyup.enter="doEvent">
          <template #append>
            <el-button :disabled="!eventText.trim()" :loading="injecting" @click="doEvent">注入</el-button>
          </template>
        </el-input>
      </div>
    </div>

    <!-- 底部：世界实时事件流（10+1 事件契约的可视化汇聚） -->
    <div class="event-feed">
      <div class="feed-head">
        <span class="feed-title">世界实时</span>
        <el-tag v-if="feed.length" size="small" effect="plain">{{ feed.length }} 条</el-tag>
        <el-button text size="small" class="feed-clear" @click="feed = []">清空</el-button>
      </div>
      <div ref="feedListEl" class="feed-list">
        <div v-for="(it, i) in feed" :key="i" class="feed-item" :class="it.kind">
          <span class="feed-time">{{ it.time }}</span>
          <span class="feed-badge">{{ it.badge }}</span>
          <span class="feed-text">{{ it.text }}</span>
        </div>
      </div>
    </div>

    <!-- 初始加载态 -->
    <div v-if="!loaded" v-loading="true" class="stage-loading" />
  </div>
</template>

<script setup>
/**
 * 世界沙盘项目主页（S4 版，features/sandbox/views）——项目绝对主界面。
 * <p>职责：全屏 Three.js 3D 正方形场景渲染沙盘（侧边栏/顶栏由 ProjectLayout.is-fullbleed 保留），
 * 通过 SSE 实时流（{@code GET /api/projects/{id}/sandbox/stream}，10+1 事件契约：
 * sandbox:init / npc:moved / npc:arrived / npc:state / npc:thought / npc:talk /
 * npc:join / npc:leave / scene:event / clock + S4 新增 weather）驱动三层悬浮界面：</p>
 * <ul>
 * <li>左上时钟条：世界时间 + 暂停/继续 + 推进一轮 + 实时连接状态 + S4 天气切换（晴/雨/雪/阴）+ 标记图例；</li>
 * <li>右侧 NPC 面板：全部 NPC 状态列表（点击选中），选中后可「插话」（自动建单聊）
 * 与「触发行动」（仅特殊角色）；未选中可「手动事件」注入；</li>
 * <li>S4 地点浮窗：点击建筑/地点 → 名称/类型/重要度/简介 + 在场 NPC（就近匹配，点击可选中）；</li>
 * <li>底部事件流：10+1 事件实时汇聚（移动/到达/状态/想法/对话/进出场景/世界事件/时钟/天气）。</li>
 * </ul>
 * <p>S4 信息层：头顶名牌常显（名字）+ 动态气泡（动作/状态/想法/对话变化时弹出数秒淡出）
 * + 事件气泡（有当事人锚定 NPC 头顶，无当事人锚定地点坐标）+ 晨昏昼夜光照 + 雨/雪天气粒子。</p>
 * <p>干预端点统一委托后端既有能力（POST /api/projects/{id}/sandbox/intervene/*）+ S4 天气切换端点。</p>
 * <p>数据来源：GET /api/projects/{id}/sandbox/overview（初始）+ GET /world-frame（天气）
 * + GET /world-locations（地点详情）+ SSE 实时流（增量）。</p>
 */
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createSubscription } from '@/shared/sse/sseClient'
import { gameSecondNow, engineSecondNow, formatWorldTimeMinute, formatCompactDayTime, calendarNameOf } from '@/shared/worldClock'
import Sandbox3DScene from '../components/Sandbox3DScene.vue'
import {
  fetchSandboxOverview,
  fetchSandboxStates,
  sandboxStreamUrl,
  sandboxInterveneTalk,
  sandboxInterveneAction,
  sandboxInterveneEvent,
  sandboxInterveneClock,
  sandboxSwitchWeather,
  fetchWorldClock,
  fetchRealtimeDecision,
  setRealtimeDecision,
  fetchMacroState,
  fetchMacroEvents,
  fetchMacroGroups,
  fetchMacroEmergence,
  fetchWorldEngineConfig
} from '@/shared/api'
import { fetchWorldFrame, fetchWorldLocations, fetchEnvironmentContext } from '@/shared/api/world'

const route = useRoute()
const projectId = Number(route.params.id)

// ==================== 状态 ====================
const loaded = ref(false)
const liveConnected = ref(false)

/** 世界时钟 */
const clock = reactive({ gameDay: 1, gameHour: 0, gameSecond: 0, paused: 0, rate: 24, gameTimeText: '', refRealTime: null, calendarName: null })

/** 实时插值后的当前游戏秒（每 1 真实秒本地重算；暂停冻结在锚点值） */
const liveGameSecond = ref(0)
let clockTick = null

/** NPC 状态表：npcRef → 状态对象（含坐标/动作/想法/名称） */
const statesMap = reactive({})

/** 场景边长（坐标 → 画布百分比投影基准） */
const sceneSize = ref(500)

/** S3 补全：主场景布局数据（layout_json 解析：{scene:{size,style,seed}, rivers[], roads[]}）→ 像素地形 */
const sceneLayout = ref(null)

/** S3 补全：主场景资产摆放列表（ScenePlacementVO[]，含 GLB URL）→ 3D 落位 */
const placements = ref([])

/** S3 补全：GLB 资产加载进度（PlacementLayer 逐批上报） */
const assetProgress = reactive({ loaded: 0, total: 0 })
const assetLoading = computed(() => assetProgress.total > 0 && assetProgress.loaded < assetProgress.total)
const assetPercent = computed(() =>
  assetProgress.total > 0 ? Math.round((assetProgress.loaded / assetProgress.total) * 100) : 0
)

/** S3 补全：场景布局信息摘要（河/路/摆放数；无布局时不显示） */
const sceneInfoText = computed(() => {
  const lay = sceneLayout.value
  if (!lay || !placements.value.length) return ''
  const rivers = (lay.rivers || []).length
  const roads = (lay.roads || []).length
  return `布局·河${rivers} 路${roads} 摆放${placements.value.length}`
})
const sceneInfoTitle = computed(() => {
  const lay = sceneLayout.value
  if (!lay?.scene) return '主场景布局'
  return `场景布局：${lay.scene.style || '—'} 风格 · 种子 ${lay.scene.seed ?? '—'} · 边长 ${validSceneSize(sceneSize.value)}m`
})

// ==================== S4 天气 + 时间季节系统（后端世界帧 weather_json + environment/context；
// 雨/雪驱动 3D 粒子；季节/时段/日出日落/体感展示） ====================
const weather = reactive({
  condition: null, temperature: null, wind: null, region: null,
  // 时间季节：季节/月份/时刻阶段/日出日落/体感（WeatherVO + environment/context 载荷）
  season: null, seasonName: null, month: null, phase: null, phaseName: null,
  sunrise: null, sunset: null, feelsLikeC: null
})
const weatherBusy = ref(false)
const weatherHint = computed(() => {
  const w = weather
  if (!w.condition) return '天气数据未加载（尚无生效世界帧）'
  const t = w.temperature != null ? ` · ${w.temperature}℃` : ''
  const wi = w.wind ? ` · ${w.wind}` : ''
  const se = w.seasonName ? ` · ${w.seasonName}季（${w.month}月）` : ''
  const fl = w.feelsLikeC != null ? ` · 体感 ${Math.round(w.feelsLikeC)}℃` : ''
  return `当前天气：${w.condition}${t}${fl}${wi}${se}（区域：${w.region || '主区域'}）；切换将同步世界帧并让 NPC 感知，反季节天气会修正气温并记为异象`
})

// ==================== S4：气泡队列（CSS2D 名牌上方动态气泡；seq 递增去重） ====================
const bubbles = ref([])
const locationBubbles = ref([])
let bubbleSeq = 0

/** 地点详情浮窗（点击建筑/地点；null=关闭） */
const locationPopup = ref(null)

/** 世界观地点表（S4 地点浮窗数据源：名称/类型/简介/重要度） */
const worldLocations = ref([])

/** 事件流 */
const feed = ref([])
const feedListEl = ref(null)

/** 选中 NPC 与干预输入 */
const selectedRef = ref(null)
const talkText = ref('')
const eventText = ref('')
const talking = ref(false)
const acting = ref(false)
const injecting = ref(false)
const clockBusy = ref(false)
const advancing = ref(false)

/** 实时决策激活状态（默认关：active=手动常开 ∪ 沙盘页打开 ∪ 演化会话进行） */
const rtDecision = reactive({ active: false, manualEnabled: false, sandboxActive: false, evolutionActive: false })
const rtBusy = ref(false)

/** 实时决策开关提示（激活原因说明） */
const rtHint = computed(() => {
  if (!rtDecision.active) return '实时决策未激活：世界暂停（打开本页已自动激活，或手动常开）'
  const parts = []
  if (rtDecision.manualEnabled) parts.push('手动常开')
  if (rtDecision.sandboxActive) parts.push('沙盘页打开')
  if (rtDecision.evolutionActive) parts.push('演化进行中')
  return `实时决策已激活（${parts.join(' + ')}）：世界持续推进`
})

/** 气泡去重缓存（动作/状态变化检测：npcRef → 上次值） */
const actionSeen = new Map()
const stateSeen = new Map()

// ==================== 派生 ====================

/** 全部 NPC 状态列表（按引用排序稳定展示） */
const npcList = computed(() =>
  Object.values(statesMap).sort((a, b) => (a.npcRef < b.npcRef ? -1 : 1))
)

/** 选中 NPC 对象 */
const selected = computed(() => (selectedRef.value ? statesMap[selectedRef.value] : null))

/**
 * 时钟文案：有插值锚点（refRealTime+rate）时基于本地插值秒拼装完整世界历到分
 * （历法名从 gameTimeText 解析，SSE clock 载荷未带独立 calendarName）；
 * 尚无锚点（SSE 未连/未推）时回退后端 gameTimeText，再回退紧凑「第X日 HH:MM」。
 */
const clockText = computed(() => {
  const gs = liveGameSecond.value
  if (clock.refRealTime != null && clock.rate) {
    return formatWorldTimeMinute(gs, clock.calendarName)
  }
  if (clock.gameTimeText) return clock.gameTimeText
  if (clock.gameDay != null) return formatCompactDayTime(gs || clock.gameSecond || 0)
  return '—'
})

// ====================：宏观社会迷你面板（收成/粮价/民怨/季节 + 最近事件弹层） ====================

/** 宏观状态摘要（/macro/state；60s 周期刷新 + 打开弹层时刷新） */
const macro = ref(null)
const macroEvents = ref([])
const macroOpen = ref(false)
const aiIntensityLabel = ref('—')
let macroTimer = null
// 中观组织 / 二阶涌现（弹层内展示最近几条）
const mesoGroups = ref([])
const emergenceEvents = ref([])

/** 宏观条悬停提示 */
const macroHint = computed(() => macro.value
  ? `宏观社会（${templateLabel(macro.value.templateId)}）：人口 ${macro.value.population} · 季节 ${seasonName(macro.value.season)} · 收成 ${(macro.value.harvestFactor || 0).toFixed(2)} · 粮价 ${Math.round(macro.value.grainPrice || 0)} 文/石 · 民怨 ${Math.round(macro.value.grievance || 0)}/100`
  : '宏观社会未初始化（世界模拟推进后每日 00:05 自动初始化）')

function seasonName(s) {
  return { spring: '春季', summer: '夏季', autumn: '秋季', winter: '冬季' }[s] || '—'
}
function templateLabel(t) {
  return {
    xianxia: '仙侠·农耕', ancient: '古代·农耕', wuxia: '高武·农耕',
    modern: '现代·工业', future: '未来·信息'
  }[t] || t || '—'
}
/** 民怨文案颜色（<50 绿 / <80 橙 / ≥80 红） */
function grievanceCls(g) {
  return g >= 80 ? 'gv-high' : g >= 50 ? 'gv-mid' : ''
}
/** 宏观事件严重度标签色（5 红 / 4 橙 / 3 黄 / 其余灰） */
function macroEvtTag(sev) {
  return sev >= 5 ? 'danger' : sev === 4 ? 'warning' : sev === 3 ? 'primary' : 'info'
}
/** 中观组织类型显示名（family/sect/guild/community/adhoc） */
function groupTypeLabel(t) {
  return { family: '宗族', sect: '门派', guild: '行会', community: '结社', adhoc: '自发' }[t] || t || '—'
}

/** 加载宏观状态 + AI 强度 + 最近宏观事件 + 中观组织 + 二阶涌现（各自失败静默，不影响沙盘） */
async function loadMacro() {
  try { macro.value = await fetchMacroState(projectId) } catch (_) { /* 未初始化静默 */ }
  try {
    const c = await fetchWorldEngineConfig(projectId)
    aiIntensityLabel.value = c.aiIntensityLabel || '标准'
  } catch (_) { /* 引擎行懒创建 */ }
  try {
    const evs = await fetchMacroEvents(projectId, { limit: 6 })
    macroEvents.value = Array.isArray(evs) ? evs : []
  } catch (_) { /* 静默 */ }
  try {
    const gs = await fetchMacroGroups(projectId)
    mesoGroups.value = Array.isArray(gs) ? gs.slice(0, 6) : []
  } catch (_) { mesoGroups.value = [] }
  try {
    const es = await fetchMacroEmergence(projectId, { limit: 5 })
    emergenceEvents.value = Array.isArray(es) ? es : []
  } catch (_) { emergenceEvents.value = [] }
}

// ==================== 初始化 + SSE ====================

let sub = null

onMounted(async () => {
  await loadOverview() // 初始快照（SSE init 会再次合并，取最新）
  loadWeatherAndLocations() // S4：天气 + 地点表（并行，失败静默）
  connectStream()
  startClockTick() // ：本地时钟插值 tick（每秒重算，暂停冻结）
  activateSandbox() // ：打开沙盘页自动激活实时决策（世界从上次进度继续推进）
  loadMacro() // ：宏观迷你面板
  macroTimer = setInterval(loadMacro, 60000) // 60s 周期刷新（世界持续推进时指标保持新鲜）
})

onBeforeUnmount(() => {
  if (sub) { sub.close(); sub = null }
  if (macroTimer) { clearInterval(macroTimer); macroTimer = null }
  stopClockTick()
  deactivateSandbox() // ：离开沙盘页自动去激活（世界暂停，进度保留）
})

/** 初始总览：场景尺寸 + 布局 + 摆放 + 全部 NPC 状态（SSE 断线/未连接时兜底） */
async function loadOverview() {
  try {
    const ov = await fetchSandboxOverview(projectId)
    if (ov?.mainScene?.sceneSize) sceneSize.value = ov.mainScene.sceneSize
    if (ov?.mainScene?.layout) sceneLayout.value = ov.mainScene.layout
    if (Array.isArray(ov?.placements)) placements.value = ov.placements
    applyStates(ov?.states || [])
  } catch (e) {
    ElMessage.error(e.message || '加载沙盘失败')
  } finally {
    loaded.value = true
  }
}

/**
 * S4 + 时间季节：并行加载天气（当前生效世界帧 weather_json + 统一环境上下文）与地点表；
 * 失败静默（天气保持空，粒子不启动）
 */
async function loadWeatherAndLocations() {
  try {
    const frame = await fetchWorldFrame(projectId)
    applyFrameWeather(frame)
  } catch (_) { /* 无生效帧/失败：天气保持空（粒子不启动），提示悬浮即可 */ }
  try {
    const ctx = await fetchEnvironmentContext(projectId)
    applyWeatherPayload(ctx)
  } catch (_) { /* 环境上下文失败静默：季节 chip 不显示 */ }
  try {
    const list = await fetchWorldLocations(projectId)
    worldLocations.value = Array.isArray(list) ? list : []
  } catch (_) { /* 失败静默：地点浮窗降级为仅摆放名 */ }
}

/** 解析世界帧 VO 的 weatherJson → weather 状态（区域取「主区域」优先/首个） */
function applyFrameWeather(frame) {
  if (!frame || !frame.weatherJson) return
  try {
    const parsed = JSON.parse(frame.weatherJson)
    const keys = Object.keys(parsed || {})
    if (!keys.length) return
    const region = keys.includes('主区域') ? '主区域' : keys[0]
    const entry = parsed[region] || {}
    weather.condition = entry.condition || null
    weather.temperature = entry.temperature != null ? entry.temperature : null
    weather.wind = entry.wind || null
    weather.region = region
  } catch (_) { /* JSON 非法静默 */ }
}

/** 资产加载进度回调（Sandbox3DScene 上报；加载完成后自动隐藏浮层） */
function onAssetProgress(p) {
  if (!p) return
  assetProgress.loaded = p.loaded || 0
  assetProgress.total = p.total || 0
}

/** 订阅沙盘实时流（10+1 事件契约；断线自动重连[指数退避]，心跳超时自动重建） */
function connectStream() {
  try {
    sub = createSubscription({
      url: sandboxStreamUrl(projectId),
      events: [
        'sandbox:init', 'npc:moved', 'npc:arrived', 'npc:state', 'npc:thought',
        'npc:talk', 'npc:join', 'npc:leave', 'scene:event', 'clock', 'weather'
      ],
      heartbeatTimeoutMs: 45000, // 15s 心跳 ×3
      onConnected: () => { liveConnected.value = true },
      onEvent: handleSseEvent,
      onError: () => { liveConnected.value = false },
      onGiveUp: () => { liveConnected.value = false; ElMessage.warning('沙盘实时连接重试达上限，请刷新页面重连') }
    })
  } catch (_) { /* 连接失败静默：读接口已兜底渲染 */ }
}

/** 10+1 事件统一分发 */
function handleSseEvent(name, data) {
  if (!data) return
  switch (name) {
    case 'sandbox:init': {
      if (data.mainScene?.sceneSize) sceneSize.value = data.mainScene.sceneSize
      if (data.mainScene?.layout) sceneLayout.value = data.mainScene.layout
      if (Array.isArray(data.placements)) placements.value = data.placements
      if (data.clock) applyClockPayload(data.clock)
      applyStates(data.states || [])
      loaded.value = true
      break
    }
    case 'npc:moved': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        npc.moveState = 'walking'
        if (Array.isArray(data.to) && data.to.length >= 3) { npc.posX = data.to[0]; npc.posZ = data.to[2] }
        pushFeed('moved', '移动', `${npcName(npc)} 开始移动 → (${fmtPos(data.to)})`)
      }
      break
    }
    case 'npc:arrived': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        npc.moveState = 'idle'
        if (Array.isArray(data.pos) && data.pos.length >= 3) { npc.posX = data.pos[0]; npc.posZ = data.pos[2]; npc.heading = data.heading }
        pushFeed('arrived', '到达', `${npcName(npc)} 到达 (${fmtPos(data.pos)})`)
      }
      break
    }
    case 'npc:state': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        if (data.moveState != null) npc.moveState = data.moveState
        if (data.actionText != null) {
          // S4：动作变化 → 动作气泡（同内容不重复弹）
          if (actionSeen.get(npc.npcRef) !== data.actionText && data.actionText) {
            actionSeen.set(npc.npcRef, data.actionText)
            pushBubble(npc.npcRef, 'action', data.actionText)
          }
          npc.actionText = data.actionText
        }
        if (data.statusText != null) npc.statusText = data.statusText
        if (data.gameSecond != null) npc.gameSecond = data.gameSecond
        // ：内部状态合并（需求/心情/情绪/urgency → 需求条/状态详情实时刷新）
        const before = stateKeyOf(npc)
        mergeInternalState(npc, data)
        // S4：心情/主导情绪变化 → 状态气泡（同状态不重复弹）
        const after = stateKeyOf(npc)
        if (before !== after && after) {
          stateSeen.set(npc.npcRef, after)
          const mood = npc.mood != null ? `心情 ${npc.mood}` : ''
          const emo = npc.dominantEmotion ? ` · ${emotionLabel(npc.dominantEmotion)}` : ''
          if (mood || emo) pushBubble(npc.npcRef, 'state', `${mood}${emo}`)
        }
        pushFeed('state', '状态', `${npcName(npc)} · ${data.actionText || data.moveState || ''}`)
      }
      break
    }
    case 'npc:thought': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        npc.thoughtText = data.text
        npc.thoughtSource = data.source
        // S4：想法气泡（T0/T1 既有 AI 产物自然流入，零额外 AI 成本）
        if (data.text) pushBubble(npc.npcRef, 'thought', data.text)
        pushFeed('thought', '想法', `${npcName(npc)}：${data.text}`)
      }
      break
    }
    case 'npc:talk': {
      // S4：对话气泡（说话时显示）
      const text = data.text || ''
      if (text) pushBubble(data.npcRef, 'talk', text)
      pushFeed('talk', '对话', `${nameOf(data.npcRef)}：${text}`)
      break
    }
    case 'npc:join': {
      ensureNpc(data.npcRef, { npcType: data.npcType, npcName: data.name })
      pushFeed('join', '登场', `${data.name || data.npcRef} 进入场景`)
      refreshSingleState(data.npcRef) // join 载荷不含坐标，拉取状态行（含派生坐标）补齐位置
      break
    }
    case 'npc:leave': {
      delete statesMap[data.npcRef]
      if (selectedRef.value === data.npcRef) selectedRef.value = null
      actionSeen.delete(data.npcRef)
      stateSeen.delete(data.npcRef)
      pushFeed('leave', '退场', `${data.name || data.npcRef} 离开场景`)
      break
    }
    case 'scene:event': {
      const title = data.title || ''
      const content = data.content || ''
      // S4：事件气泡——有当事人 → 锚定 NPC 头顶；无当事人但有地点 → 锚定地点坐标
      showEventBubbles(data)
      // S4：每日帧事件（sourceKey frame: 前缀）→ 天气可能已变，轻量刷新（每日 1 次量级）
      if (data.kind === 'world_frame' && String(data.sourceKey || '').startsWith('frame:')) {
        refreshWeather()
      }
      pushFeed('event', '事件', `${title}${content ? ' — ' + content : ''}`)
      break
    }
    case 'clock': {
      const wasPaused = clock.paused
      applyClockPayload(data)
      if (wasPaused !== data.paused) {
        pushFeed('clock', '时钟', data.paused ? '世界暂停' : '世界继续运行')
      }
      break
    }
    case 'weather': {
      // S4：天气变化（后端 WeatherSwitchService 推送）
      applyWeatherPayload(data)
      pushFeed('clock', '天气', data.message || `天气变化：${data.condition || ''}`)
      break
    }
    default:
      break
  }
}

// ==================== 状态管理 ====================

/** 取/建 NPC 状态条目（SSE 事件缺字段时合并） */
function ensureNpc(npcRef, extra = {}) {
  if (!npcRef) return null
  if (!statesMap[npcRef]) {
    statesMap[npcRef] = reactive({
      npcRef,
      npcType: 'special',
      npcName: null,
      posX: null, posZ: null, heading: null,
      moveState: 'idle', actionText: '', statusText: '', thoughtText: '', thoughtSource: null, gameSecond: null,
      // ：内部状态字段（overview/SSE 富化）
      hunger: null, thirst: null, energy: null, bladder: null, health: null, mood: null,
      dominantEmotion: null, emotionIntensity: null,
      emotionPleasure: null, emotionArousal: null, emotionDominance: null,
      urgencyLevel: 0, urgencyText: '',
      ...extra
    })
  }
  if (extra.npcName) statesMap[npcRef].npcName = extra.npcName
  return statesMap[npcRef]
}

/** 内部状态字段清单（SSE/overview 合并用） */
const INTERNAL_STATE_KEYS = [
  'hunger', 'thirst', 'energy', 'bladder', 'health', 'mood',
  'dominantEmotion', 'emotionIntensity',
  'emotionPleasure', 'emotionArousal', 'emotionDominance',
  'urgencyLevel', 'urgencyText'
]

/** 合并内部状态（仅覆盖非空字段；SSE npc:state / overview 富化共用） */
function mergeInternalState(npc, data) {
  if (!npc || !data) return
  for (const k of INTERNAL_STATE_KEYS) {
    if (data[k] != null) npc[k] = data[k]
  }
}

/** S4：状态气泡去重键（心情 + 主导情绪 + urgency） */
function stateKeyOf(npc) {
  if (!npc) return ''
  return `${npc.mood ?? ''}|${npc.dominantEmotion ?? ''}|${npc.urgencyLevel ?? 0}`
}

/** 合并一批状态（overview/init 快照） */
function applyStates(states) {
  for (const s of states || []) {
    if (!s || !s.npcRef) continue
    ensureNpc(s.npcRef, { npcType: s.npcType, npcName: s.npcName })
    Object.assign(statesMap[s.npcRef], {
      posX: s.posX, posZ: s.posZ, heading: s.heading,
      moveState: s.moveState, actionText: s.actionText, statusText: s.statusText,
      thoughtText: s.thoughtText, thoughtSource: s.thoughtSource, gameSecond: s.gameSecond
    })
    mergeInternalState(statesMap[s.npcRef], s)
  }
}

/** 拉取单个 NPC 状态行并合并（join 事件补齐坐标等字段；失败静默） */
async function refreshSingleState(npcRef) {
  try {
    const list = await fetchSandboxStates(projectId)
    const row = (list || []).find((s) => s && s.npcRef === npcRef)
    if (row) applyStates([row])
  } catch (_) { /* 静默：后续状态事件仍会补全 */ }
}

// ==================== S4：气泡 / 天气 / 拾取 ====================

/** 推一条 NPC 头顶动态气泡（seq 递增；保留最近 60 条防膨胀） */
function pushBubble(npcRef, kind, text) {
  if (!npcRef || !text) return
  bubbles.value.push({ seq: ++bubbleSeq, npcRef, kind, text: String(text).slice(0, 80) })
  if (bubbles.value.length > 60) bubbles.value.splice(0, bubbles.value.length - 60)
}

/** 推一条地点锚定气泡（无当事人事件；坐标为中心化世界坐标） */
function pushLocationBubble(x, z, kind, text) {
  if (x == null || z == null || !text) return
  locationBubbles.value.push({ seq: ++bubbleSeq, x, z, kind: kind || 'event', text: String(text).slice(0, 80) })
  if (locationBubbles.value.length > 40) locationBubbles.value.splice(0, locationBubbles.value.length - 40)
}

/** S4：事件气泡分发——有当事人 → NPC 头顶；无当事人但有地点名 → 地点坐标 */
function showEventBubbles(data) {
  const title = data.title || data.content || ''
  if (!title) return
  // ① 当事人（characterIds 为 characterRef 格式 npc:{id}，与 statesMap 键一致）
  let anchored = false
  const ids = Array.isArray(data.characterIds) ? data.characterIds : []
  for (const ref of ids) {
    if (statesMap[ref]) {
      pushBubble(ref, 'event', title)
      anchored = true
    }
  }
  if (anchored) return
  // ② 地点名 → 匹配摆放坐标 → 地点锚定气泡
  if (data.locationName) {
    const pos = placementPosByName(data.locationName)
    if (pos) {
      pushLocationBubble(pos.x, pos.z, 'event', title)
      return
    }
  }
  // ③ 均无 → 仅底部事件流（feed 已在调用方推送）
}

/** 摆放名 → 中心化坐标（0..size 偏移 -size/2；匹配失败返回 null） */
function placementPosByName(name) {
  if (!name || !placements.value.length) return null
  const size = validSceneSize(sceneSize.value)
  for (const p of placements.value) {
    const pn = p.name || ''
    if (pn === name || pn.includes(name) || name.includes(pn)) {
      return { x: (p.posX ?? 0) - size / 2, z: (p.posZ ?? 0) - size / 2 }
    }
  }
  return null
}

/** S4：天气切换（晴/雨/雪/阴 → 后端世界帧就地更新 + NPC 感知 + SSE weather 回推） */
async function onWeatherChange(condition) {
  if (!condition) return
  weatherBusy.value = true
  try {
    const vo = await sandboxSwitchWeather(projectId, condition)
    applyWeatherPayload(vo)
    pushFeed('clock', '天气', vo?.message || `天气已切换为「${condition}」`)
    ElMessage.success(vo?.message || `天气已切换为「${condition}」`)
  } catch (e) {
    // 切换失败回滚选中值（后端未变更）
    refreshWeather()
    ElMessage.error(e.message || '天气切换失败')
  } finally {
    weatherBusy.value = false
  }
}

/**
 * 合并天气载荷（SSE weather / 切换响应 / world-frame 解析 / environment-context 共用；
 * 时间季节 起含季节/时段/日出日落/体感字段）
 */
function applyWeatherPayload(data) {
  if (!data) return
  if (data.condition != null) weather.condition = data.condition
  if (data.temperature != null) weather.temperature = data.temperature
  if (data.wind != null) weather.wind = data.wind
  if (data.region != null) weather.region = data.region
  // 时间季节字段（WeatherVO / EnvironmentContextVO）
  if (data.season != null) weather.season = data.season
  if (data.seasonName != null) weather.seasonName = data.seasonName
  if (data.month != null) weather.month = data.month
  if (data.phase != null) weather.phase = data.phase
  if (data.phaseName != null) weather.phaseName = data.phaseName
  if (data.sunrise != null) weather.sunrise = data.sunrise
  if (data.sunset != null) weather.sunset = data.sunset
  if (data.feelsLikeC != null) weather.feelsLikeC = data.feelsLikeC
}

/** 主动刷新天气与季节（帧事件后/切换失败回滚；失败静默） */
async function refreshWeather() {
  try {
    applyFrameWeather(await fetchWorldFrame(projectId))
  } catch (_) { /* 静默 */ }
  try {
    applyWeatherPayload(await fetchEnvironmentContext(projectId))
  } catch (_) { /* 静默 */ }
}

/** S4 拾取：点击 NPC → 选入右侧面板（名牌/光环高亮联动） */
function onPickNpc(npcRef) {
  if (!npcRef || !statesMap[npcRef]) return
  selectNpc(npcRef)
  locationPopup.value = null
}

/** S4 拾取：点击建筑/地点 → 地点浮窗（摆放匹配地点表 + 在场 NPC 就近匹配） */
function onPickPlacement(pick) {
  if (!pick) return
  const p = (placements.value || []).find((x) => x.id === pick.placementId) || null
  const size = validSceneSize(sceneSize.value)
  const px = p ? (p.posX ?? 0) - size / 2 : null
  const pz = p ? (p.posZ ?? 0) - size / 2 : null
  const name = pick.name || (p && p.name) || '未知位置'
  // 摆放名 → 地点表匹配（精确优先，包含兜底）
  const loc = matchWorldLocation(name)
  // 在场 NPC：与地点坐标（中心化）距离 ≤ 10m 就近匹配
  const nearby = []
  if (px != null && pz != null) {
    for (const n of Object.values(statesMap)) {
      if (n.posX == null || n.posZ == null) continue
      if (Math.hypot(n.posX - px, n.posZ - pz) <= 10) nearby.push(n)
    }
    nearby.sort((a, b) => (a.npcRef < b.npcRef ? -1 : 1))
  }
  locationPopup.value = {
    name: loc ? loc.name : name,
    type: loc ? loc.type : (p && p.categoryName) || '',
    importance: loc ? loc.importance : null,
    intro: loc ? loc.intro : '',
    nearby
  }
}

/** 摆放名 → 地点表匹配（精确相等优先，其次互相包含） */
function matchWorldLocation(name) {
  const list = worldLocations.value || []
  if (!list.length || !name) return null
  const hit = list.find((l) => l.name === name)
  if (hit) return hit
  return list.find((l) => (l.name && name.includes(l.name)) || (l.name && l.name.includes(name))) || null
}

/** S4 拾取：点击空地 → 取消选中 + 关闭地点浮窗 */
function onPickEmpty() {
  selectedRef.value = null
  locationPopup.value = null
}

// ==================== 交互 ====================

function selectNpc(npcRef) {
  selectedRef.value = selectedRef.value === npcRef ? null : npcRef
  talkText.value = ''
}

/** 干预-插话：向选中 NPC 说一句话（自动建单聊，回复经 npc:talk 推回沙盘） */
async function doTalk() {
  const npc = selected.value
  const content = talkText.value.trim()
  if (!npc || !content) return
  talking.value = true
  try {
    const res = await sandboxInterveneTalk(projectId, { npcRef: npc.npcRef, content })
    pushFeed('talk', '插话', `你对${npcName(npc)}说：${content}`)
    talkText.value = ''
    ElMessage.success(res?.message || '插话已发送')
  } catch (e) {
    ElMessage.error(e.message || '插话失败')
  } finally {
    talking.value = false
  }
}

/** 干预-触发行动（仅特殊角色） */
async function doAction() {
  const npc = selected.value
  if (!npc) return
  acting.value = true
  try {
    const res = await sandboxInterveneAction(projectId, { npcRef: npc.npcRef, reason: '沙盘手动触发' })
    pushFeed('action', '行动', `${npcName(npc)} 触发行动（${res.status || 'planned'}）`)
    ElMessage.success(`已触发 ${npcName(npc)} 的行动（${res.status || 'planned'}）`)
  } catch (e) {
    ElMessage.error(e.message || '触发行动失败')
  } finally {
    acting.value = false
  }
}

/** 干预-手动事件注入 */
async function doEvent() {
  const content = eventText.value.trim()
  if (!content) return
  injecting.value = true
  try {
    const ev = await sandboxInterveneEvent(projectId, { content })
    pushFeed('event', '注入', `事件已注入：${content}`)
    eventText.value = ''
    ElMessage.success(`事件 #${ev?.id ?? ''} 已注入世界`)
  } catch (e) {
    ElMessage.error(e.message || '事件注入失败')
  } finally {
    injecting.value = false
  }
}

/** 时间控制-暂停/继续 */
async function togglePause() {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { paused: !clock.paused })
    applyClockPayload(res?.clock || {})
  } catch (e) {
    ElMessage.error(e.message || '时钟操作失败')
  } finally {
    clockBusy.value = false
  }
}

/** 时间控制-手动补推一轮 */
async function advanceOne() {
  advancing.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { advance: true })
    pushFeed('clock', '推进', `推进一轮（${res?.gameHour ?? ''} 游戏小时）`)
    refreshClockAnchor() // 推进后主动拉取时钟校准锚点（SSE clock 事件为兜底）
    refreshWeather() // 推进可能跨游戏日生成新帧（天气变化）
  } catch (e) {
    ElMessage.error(e.message || '推进失败')
  } finally {
    advancing.value = false
  }
}

// ====================：实时决策激活（页面打开自动激活 / 离开自动去激活 / 手动常开） ====================

/** 打开沙盘页 → 激活实时决策（世界开始推进）；失败静默（引擎侧按未激活跳过，页面照常可用） */
async function activateSandbox() {
  try {
    const st = await setRealtimeDecision(projectId, { action: 'open', reason: 'sandbox' })
    applyRtState(st)
    pushFeed('clock', '实时决策', '沙盘打开：世界已激活，继续推进')
  } catch (_) { /* 静默：读接口兜底 */ }
}

/** 离开沙盘页 → 去激活实时决策（世界暂停，进度保留）；失败静默 */
async function deactivateSandbox() {
  try {
    const st = await setRealtimeDecision(projectId, { action: 'close', reason: 'sandbox' })
    applyRtState(st)
  } catch (_) { /* 静默 */ }
}

/** 手动常开开关（手动优先于页面自动态：常开=离开页面世界也持续推进） */
async function toggleRtManual(enabled) {
  rtBusy.value = true
  try {
    const st = await setRealtimeDecision(projectId, { action: 'set', enabled })
    applyRtState(st)
    pushFeed('clock', '实时决策', enabled ? '手动常开：世界持续推进' : '手动关闭：仅页面打开时世界推进')
  } catch (e) {
    ElMessage.error(e.message || '实时决策开关操作失败')
  } finally {
    rtBusy.value = false
  }
}

/** 合并激活状态（GET/PUT 响应） */
function applyRtState(st) {
  if (!st) return
  rtDecision.active = !!st.active
  rtDecision.manualEnabled = !!st.manualEnabled
  rtDecision.sandboxActive = !!st.sandboxActive
  rtDecision.evolutionActive = !!st.evolutionActive
}

/** 主动拉取激活状态（初始化/断线兜底） */
async function refreshRtState() {
  try {
    applyRtState(await fetchRealtimeDecision(projectId))
  } catch (_) { /* 静默 */ }
}

// ====================：本地实时时钟（插值 + 校准） ====================

/**
 * 合并 clock 载荷并校准插值锚点：SSE clock / sandbox:init / 干预返回 / 主动拉取共用。
 * <p>载荷字段口径：SSE 版含 gameDay/gameHour/gameSecond/gameMinute/gameDayMinute/refRealTime/
 * paused/rate/worldRunning/gameTimeText；world-clock VO 版（干预返回）还含 calendarName。
 * 历法名优先取载荷自带 calendarName（VO 版），否则从 gameTimeText 解析（SSE 版）。</p>
 */
function applyClockPayload(data) {
  if (!data) return
  Object.assign(clock, data)
  const cal = data.calendarName || calendarNameOf(data.gameTimeText)
  if (cal) clock.calendarName = cal
  liveGameSecond.value = calcLiveGameSecond()
}

/**
 * 基于锚点本地插值当前游戏秒（起：载荷含引擎位置 engineGameSecond → 显示引擎实际推进位置
 * [世界的实际时间，AI 慢/引擎停顿不虚快]；载荷缺引擎字段 → 回退 流动时钟换算插值）。
 */
function calcLiveGameSecond() {
  if (clock.engineGameSecond != null) {
    return engineSecondNow(clock.engineGameSecond, clock.engineRefRealTime, clock.rate, clock.paused, clock.engineTickGameSeconds)
  }
  return gameSecondNow(clock.gameSecond, clock.refRealTime, clock.rate, clock.paused)
}

/** 启动本地时钟插值 tick（每 1 真实秒重算一次，驱动时钟条连续跳动） */
function startClockTick() {
  stopClockTick()
  clockTick = setInterval(() => { liveGameSecond.value = calcLiveGameSecond() }, 1000)
  liveGameSecond.value = calcLiveGameSecond()
}

/** 停止本地时钟插值 tick（组件卸载时清理） */
function stopClockTick() {
  if (clockTick) { clearInterval(clockTick); clockTick = null }
}

/** 主动拉取时钟校准锚点（手动推进/断线重连后兜底；失败静默，SSE clock 事件仍会校准） */
async function refreshClockAnchor() {
  try {
    const vo = await fetchWorldClock(projectId)
    applyClockPayload(vo)
  } catch (_) { /* 静默 */ }
}

// ==================== 事件流 ====================

const kindBadge = {
  moved: '移动', arrived: '到达', state: '状态', thought: '想法', talk: '对话',
  join: '登场', leave: '退场', event: '事件', clock: '时钟', action: '行动', injected: '注入'
}

/** 推一条事件流（保留最近 200 条） */
function pushFeed(kind, badge, text) {
  feed.value.push({
    kind,
    badge: badge || kindBadge[kind] || kind,
    text,
    time: nowTime()
  })
  if (feed.value.length > 200) feed.value.splice(0, feed.value.length - 200)
  scrollFeed()
}

/** 事件流滚动到底（新事件可见） */
function scrollFeed() {
  nextTick(() => {
    const el = feedListEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// ==================== 工具 ====================

function npcName(npc) { return npc?.npcName || npc?.npcRef || '' }
function nameOf(npcRef) { return statesMap[npcRef]?.npcName || npcRef || '' }
function fmtPos(pos) {
  if (!Array.isArray(pos) || pos.length < 3) return '—'
  return `${Math.round(pos[0])}, ${Math.round(pos[2])}`
}
function nowTime() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

/** 场景边长兜底（非法/过小用 500；场景信息展示用） */
function validSceneSize(v) {
  const n = Number(v)
  return Number.isFinite(n) && n >= 100 ? n : 500
}
function moveLabel(s) {
  return ({ idle: '空闲', walking: '移动中', acting: '行动中', talking: '交谈中', resting: '歇息中' })[s] || s || '—'
}
function moveTagType(s) {
  return ({ idle: 'info', walking: 'warning', acting: 'warning', talking: 'success', resting: 'info' })[s] || 'info'
}

// ====================：需求/情绪展示工具 ====================

/** 需求进度条颜色：低值（<30）红 / 中值（<60）橙 / 高值绿 */
function needColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 30) return '#ff6b6b'
  if (v < 60) return '#ffb84d'
  return '#52d68a'
}

/** 需求数值色调（低值预警） */
function needTone(v) {
  if (v == null) return ''
  if (v < 30) return 'low'
  if (v < 60) return 'mid'
  return ''
}

/** 心情颜色：低（<40）红 / 中（<65）橙 / 高绿 */
function moodColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 40) return '#ff6b6b'
  if (v < 65) return '#ffb84d'
  return '#52d68a'
}

/** 健康颜色：告急红 / 微恙橙 / 良好绿 */
function healthColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 25) return '#ff6b6b'
  if (v < 70) return '#ffb84d'
  return '#52d68a'
}

/** PAD -1~1 → 0~100 百分比（进度条展示） */
function padPercent(v) {
  if (v == null) return 50
  return Math.round(((Math.max(-1, Math.min(1, v)) + 1) / 2) * 100)
}

/** PAD 颜色：负值红 / 正值绿 */
function padColor(v) {
  if (v == null) return '#9db8e8'
  return v >= 0 ? '#52d68a' : '#ff6b6b'
}

/** PAD 数值格式化（保留两位，带符号） */
function fmtPad(v) {
  if (v == null) return '—'
  return (v >= 0 ? '+' : '') + v.toFixed(2)
}

/** 主导情绪中文标签 */
function emotionLabel(e) {
  return ({
    calm: '平静', happy: '高兴', angry: '愤怒', sad: '悲伤',
    fearful: '恐惧', surprised: '惊讶', disgusted: '厌恶'
  })[e] || e || '—'
}
</script>

<style scoped>
/* ==================== 全屏舞台（3D 画布 + 悬浮层） ==================== */
.sb-stage {
  position: relative; width: 100%; height: 100%;
  /*
   * overflow: visible —— 3D 画布需向左延伸出内容区以覆盖侧边栏区域（S3.1 修复）；
   * 最终由外层 .proj-content/.proj-body 在屏幕左缘裁剪，不会产生横向滚动
   */
  overflow: visible;
  background: #0b1220;
  font-family: inherit;
}
.stage-loading { position: absolute; inset: 0; z-index: 8; display: flex; align-items: center; justify-content: center; background: rgba(11, 18, 32, 0.5); }

/* ==================== S3 补全：资产加载浮层 ==================== */
.asset-loading {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); z-index: 7;
  pointer-events: none;
}
.al-card {
  width: 260px; padding: 14px 16px; border-radius: 12px;
  background: rgba(13, 20, 36, 0.9); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.3); box-shadow: 0 4px 18px rgba(0, 0, 0, 0.4);
  display: flex; flex-direction: column; gap: 8px;
}
.al-title { font-size: 13px; font-weight: 700; color: #e8f0ff; }
.al-sub { font-size: 11px; color: #9db8e8; font-variant-numeric: tabular-nums; }

/* ==================== 左上时钟条 ==================== */
.clock-bar {
  position: absolute; top: 14px; left: 14px; z-index: 5;
  display: flex; align-items: center; gap: 14px;
  background: rgba(13, 20, 36, 0.85); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  padding: 10px 14px; color: #e8f0ff; box-shadow: 0 4px 18px rgba(0, 0, 0, 0.35);
}
.clock-title { font-size: 12px; font-weight: 700; color: #9db8e8; display: flex; align-items: center; gap: 6px; }
.clock-text { font-size: 15px; font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; }
.live-dot { font-size: 10px; font-weight: 600; color: #ffb84d; display: inline-flex; align-items: center; gap: 4px; }
.live-dot::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: #ffb84d; }
.live-dot.on { color: #52d68a; }
.live-dot.on::before { background: #52d68a; box-shadow: 0 0 6px #52d68a; animation: pulse 1.2s infinite; }
.clock-ops { display: flex; gap: 6px; align-items: center; }
.scene-info {
  font-size: 11px; font-weight: 600; color: #9db8e8; white-space: nowrap;
  padding: 2px 8px; border-radius: 6px; border: 1px solid rgba(120, 160, 255, 0.3);
  background: rgba(77, 159, 255, 0.1); cursor: help; font-variant-numeric: tabular-nums;
}
/* S4：标记图例 */
.legend { display: inline-flex; align-items: center; gap: 4px; cursor: help; }
.lg-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.lg-dot.gold { background: #ffd45c; box-shadow: 0 0 4px rgba(255, 212, 92, 0.7); }
.lg-dot.blue { background: #8fb1ff; box-shadow: 0 0 4px rgba(143, 177, 255, 0.7); }
/* S4：天气切换 */
.weather-box {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 2px 8px; border-radius: 8px; border: 1px solid rgba(120, 160, 255, 0.3);
  background: rgba(77, 159, 255, 0.08); cursor: help; white-space: nowrap;
}
.weather-label { font-size: 11px; font-weight: 600; color: #9db8e8; }
/* 时间季节系统：季节 chip（四季配色，随季节变化） */
.season-chip {
  font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 6px;
  color: #eaf3ff; border: 1px solid rgba(255, 255, 255, 0.25); font-variant-numeric: tabular-nums;
}
.season-chip.season-spring { background: rgba(93, 211, 141, 0.25); border-color: rgba(93, 211, 141, 0.55); }
.season-chip.season-summer { background: rgba(255, 176, 61, 0.25); border-color: rgba(255, 176, 61, 0.55); }
.season-chip.season-autumn { background: rgba(255, 146, 71, 0.25); border-color: rgba(255, 146, 71, 0.55); }
.season-chip.season-winter { background: rgba(143, 202, 255, 0.25); border-color: rgba(143, 202, 255, 0.55); }
/* 时间季节系统：天气 meta（时段/体感/日出日落） */
.weather-meta { font-size: 10.5px; color: #9db8e8; font-variant-numeric: tabular-nums; }
.weather-box :deep(.el-radio-button__inner) {
  font-size: 11px; padding: 4px 9px;
}
.rt-toggle { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: #9db8e8; cursor: help; }
.rt-toggle .rt-label { white-space: nowrap; }
/* 世界运行时状态指示（运行中=绿点脉冲；静止=灰点） */
.runtime-chip {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 11px; font-weight: 700; white-space: nowrap; cursor: help;
  padding: 2px 8px; border-radius: 6px; border: 1px solid rgba(120, 160, 255, 0.3);
  background: rgba(77, 159, 255, 0.08); font-variant-numeric: tabular-nums;
}
.runtime-chip .rt-dot { width: 8px; height: 8px; border-radius: 50%; background: #5a6b85; }
.runtime-chip.off { color: #9db8e8; }
.runtime-chip.on { color: #52d68a; border-color: rgba(82, 214, 138, 0.45); background: rgba(82, 214, 138, 0.08); }
.runtime-chip.on .rt-dot { background: #52d68a; box-shadow: 0 0 6px #52d68a; animation: pulse 1.2s infinite; }

/* ====================：宏观社会迷你条 + 详情弹层 ==================== */
.macro-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 2px 8px; border-radius: 8px; border: 1px solid rgba(120, 160, 255, 0.3);
  background: rgba(77, 159, 255, 0.08); cursor: pointer; white-space: nowrap;
  transition: all 0.2s;
}
.macro-chip:hover, .macro-chip.open { background: rgba(77, 159, 255, 0.18); border-color: rgba(120, 160, 255, 0.55); }
.macro-label { font-size: 11px; font-weight: 600; color: #ffd45c; }
.macro-sec {
  font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 6px;
  color: #eaf3ff; border: 1px solid rgba(255, 255, 255, 0.25); font-variant-numeric: tabular-nums;
}
.macro-sec.season-spring { background: rgba(93, 211, 141, 0.25); border-color: rgba(93, 211, 141, 0.55); }
.macro-sec.season-summer { background: rgba(255, 176, 61, 0.25); border-color: rgba(255, 176, 61, 0.55); }
.macro-sec.season-autumn { background: rgba(255, 146, 71, 0.25); border-color: rgba(255, 146, 71, 0.55); }
.macro-sec.season-winter { background: rgba(143, 202, 255, 0.25); border-color: rgba(143, 202, 255, 0.55); }
.macro-item { font-size: 11px; font-weight: 600; color: #cfe0ff; font-variant-numeric: tabular-nums; }
.macro-item.gv-high { color: #ff6b6b; }
.macro-item.gv-mid { color: #ffb84d; }
.macro-pop {
  position: absolute; left: 300px; top: 84px; width: 400px; z-index: 6;
  display: flex; flex-direction: column; gap: 10px;
  background: rgba(13, 20, 36, 0.94); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.35); border-radius: 12px;
  padding: 12px 14px; color: #e8f0ff; box-shadow: 0 6px 24px rgba(0, 0, 0, 0.5);
  max-height: 66%; overflow-y: auto;
}
.mp-head { display: flex; align-items: center; gap: 8px; }
.mp-title { font-size: 13px; font-weight: 700; color: #fff; }
.mp-close { margin-left: 0; color: #6b7fa8; }
.mp-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 12px; }
.mp-cell {
  display: flex; justify-content: space-between; gap: 8px; font-size: 12px;
  padding: 3px 8px; border-radius: 6px; background: rgba(77, 159, 255, 0.08);
}
.mp-k { color: #9db8e8; }
.mp-v { color: #eaf3ff; font-weight: 600; font-variant-numeric: tabular-nums; }
.mp-v.gv-high { color: #ff6b6b; }
.mp-v.gv-mid { color: #ffb84d; }
.mp-events { border-top: 1px solid rgba(120, 160, 255, 0.18); padding-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.mp-sub { font-size: 11px; font-weight: 700; color: #9db8e8; }
.mp-event { display: flex; align-items: flex-start; gap: 8px; font-size: 11.5px; color: #cfe0ff; line-height: 1.55; }
.mp-event :deep(.el-tag) { flex-shrink: 0; margin-top: 1px; }
.mp-ev-text { flex: 1; }
.mp-ev-meta { flex-shrink: 0; font-size: 10.5px; color: #6b7fa8; white-space: nowrap; }
.mp-g-name { font-weight: 600; color: #ffd45c; white-space: nowrap; }
.mp-empty { font-size: 11px; color: #6b7fa8; }

/* ==================== S4：地点详情浮窗（点击建筑/地点弹出） ==================== */
.loc-popup {
  position: absolute; left: 14px; top: 84px; width: 300px; z-index: 6;
  display: flex; flex-direction: column; gap: 8px;
  background: rgba(13, 20, 36, 0.92); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.35); border-radius: 12px;
  padding: 12px 14px; color: #e8f0ff; box-shadow: 0 6px 24px rgba(0, 0, 0, 0.5);
  max-height: 60%; overflow-y: auto;
}
.loc-head { display: flex; align-items: center; gap: 8px; }
.loc-name { font-size: 14px; font-weight: 700; color: #fff; }
.loc-imp { font-size: 10.5px; color: #ffd45c; margin-left: auto; white-space: nowrap; }
.loc-close { margin-left: 0; color: #6b7fa8; }
.loc-intro { font-size: 11.5px; color: #cfe0ff; line-height: 1.7; }
.loc-intro.muted { color: #6b7fa8; }
.loc-nearby { border-top: 1px solid rgba(120, 160, 255, 0.18); padding-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.loc-nearby-title { font-size: 11px; font-weight: 700; color: #9db8e8; }
.loc-npc {
  display: flex; align-items: center; gap: 7px; font-size: 12px; color: #cfe0ff;
  padding: 4px 8px; border-radius: 7px; cursor: pointer;
  border: 1px solid transparent; transition: background 0.15s;
}
.loc-npc:hover { background: rgba(77, 159, 255, 0.12); }
.loc-npc.selected { background: rgba(77, 159, 255, 0.18); border-color: rgba(77, 159, 255, 0.5); }
.loc-npc-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.loc-npc-dot.special { background: #ffd45c; box-shadow: 0 0 4px rgba(255, 212, 92, 0.7); }
.loc-npc-dot.crowd { background: #8fb1ff; box-shadow: 0 0 4px rgba(143, 177, 255, 0.7); }
.loc-npc-name { font-weight: 700; white-space: nowrap; }
.loc-npc-action { font-size: 11px; color: #6b7fa8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ==================== 右侧 NPC 面板 ==================== */
.npc-panel {
  position: absolute; top: 14px; right: 14px; bottom: 88px; width: 280px; z-index: 5;
  display: flex; flex-direction: column;
  background: rgba(13, 20, 36, 0.85); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.35); overflow: hidden;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-bottom: 1px solid rgba(120, 160, 255, 0.18);
}
.panel-title { font-size: 13px; font-weight: 700; color: #e8f0ff; }
.panel-list { flex: 1; overflow-y: auto; padding: 6px; }
.panel-item {
  padding: 8px 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
  border: 1px solid transparent; transition: background 0.15s, border-color 0.15s;
}
.panel-item:hover { background: rgba(77, 159, 255, 0.12); }
.panel-item.selected { background: rgba(77, 159, 255, 0.18); border-color: rgba(77, 159, 255, 0.5); }
.pi-name-row { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.pi-name { font-size: 12.5px; font-weight: 700; color: #e8f0ff; }
.pi-action { font-size: 11px; color: #9db8e8; margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pi-thought { font-size: 11px; color: #ffe9a8; margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ====================：需求条 ==================== */
.pi-needs { margin-top: 5px; display: flex; flex-direction: column; gap: 2px; }
.need-line { display: flex; align-items: center; gap: 5px; font-size: 10px; color: #9db8e8; }
.need-line .el-progress { flex: 1; }
.need-label { flex-shrink: 0; width: 22px; color: #6b7fa8; }
.need-val { flex-shrink: 0; width: 20px; text-align: right; font-variant-numeric: tabular-nums; color: #cfe0ff; }
.need-val.low { color: #ff6b6b; font-weight: 700; }
.need-val.mid { color: #ffb84d; }
.pi-urgent { font-size: 10.5px; color: #ff6b6b; margin-top: 3px; font-weight: 600; }

/* ====================：选中状态详情（PAD/心情/健康/urgency） ==================== */
.sd-block {
  display: flex; flex-direction: column; gap: 8px;
  padding: 8px 9px; border-radius: 8px; background: rgba(77, 159, 255, 0.08);
  border: 1px solid rgba(120, 160, 255, 0.22); max-height: 220px; overflow-y: auto;
}
.sd-urgency { font-size: 11px; font-weight: 700; color: #ff8f8f; }
.sd-urgency.lvl2, .sd-urgency.lvl3 { color: #ff6b6b; }
.sd-grid { display: flex; flex-direction: column; gap: 7px; }
.sd-cell { display: flex; flex-direction: column; gap: 2px; }
.sd-k { font-size: 10px; color: #6b7fa8; }
.sd-emotion { font-size: 12px; font-weight: 700; color: #ffe9a8; }
.sd-em-int { font-size: 10px; font-weight: 400; color: #9db8e8; margin-left: 6px; }
.pad-bars { display: flex; flex-direction: column; gap: 2px; }
.pad-row { display: flex; align-items: center; gap: 6px; font-size: 10px; color: #9db8e8; }
.pad-row .el-progress { flex: 1; }
.pad-name { flex-shrink: 0; width: 42px; color: #6b7fa8; }
.pad-val { flex-shrink: 0; width: 36px; text-align: right; font-variant-numeric: tabular-nums; color: #cfe0ff; }
.panel-empty { font-size: 12px; color: #9db8e8; text-align: center; padding: 26px 10px; line-height: 1.8; }
.panel-empty span { font-size: 11px; color: #6b7fa8; }
.panel-intervene {
  padding: 10px 12px; border-top: 1px solid rgba(120, 160, 255, 0.18);
  display: flex; flex-direction: column; gap: 8px;
}
.pi-title { font-size: 12px; font-weight: 700; color: #9db8e8; }
.pi-btns { display: flex; align-items: center; gap: 8px; }

/* ==================== 底部事件流 ==================== */
.event-feed {
  position: absolute; left: 14px; right: 14px; bottom: 14px; height: 70px; z-index: 5;
  display: flex; flex-direction: column;
  background: rgba(13, 20, 36, 0.8); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.25); border-radius: 12px;
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.35); overflow: hidden;
}
.feed-head {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 12px; border-bottom: 1px solid rgba(120, 160, 255, 0.18); flex-shrink: 0;
}
.feed-title { font-size: 12px; font-weight: 700; color: #9db8e8; }
.feed-clear { margin-left: auto; color: #6b7fa8; }
.feed-list { flex: 1; overflow-y: auto; padding: 4px 12px; }
.feed-item {
  display: flex; align-items: baseline; gap: 8px;
  font-size: 11.5px; color: #cfe0ff; line-height: 1.7; white-space: nowrap;
}
.feed-time { font-size: 10px; color: #6b7fa8; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.feed-badge {
  flex-shrink: 0; font-size: 10px; font-weight: 700; padding: 0 6px; border-radius: 4px;
  color: #0b1220; background: #9db8e8;
}
.feed-item.talk .feed-badge { background: #52d68a; }
.feed-item.thought .feed-badge { background: #ffd45c; }
.feed-item.event .feed-badge { background: #ff8f6b; }
.feed-item.clock .feed-badge { background: #8fb1ff; }
.feed-item.join .feed-badge { background: #52d68a; }
.feed-item.leave .feed-badge { background: #ff8f8f; }
.feed-item.moved .feed-badge, .feed-item.arrived .feed-badge, .feed-item.state .feed-badge { background: #9db8e8; }
.feed-text { overflow: hidden; text-overflow: ellipsis; }

/* 滚动条（暗色） */
.npc-panel::-webkit-scrollbar, .feed-list::-webkit-scrollbar { width: 5px; }
.npc-panel::-webkit-scrollbar-thumb, .feed-list::-webkit-scrollbar-thumb { background: rgba(120, 160, 255, 0.3); border-radius: 3px; }
</style>
