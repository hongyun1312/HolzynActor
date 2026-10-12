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
    「AI 运行状态」抽屉内容：一眼看到「现在有没有在推理 / 最近调用了什么 / 失败多少」。
    数据源为只读接口（批次① 复用 /api/debug/ai-calls；批次③ 切换为聚合只读接口），零 AI 成本。
    设计目标：本项目「不考虑 AI 成本、追求最真实的世界」——意味着后台会持续大量推理，
    用户需要一个不打断操作的状态窗口（顶栏活动点 + 本抽屉），而不是去调试台翻日志。
  -->
  <div class="arp">
    <!-- 摘要卡（窗口内：推理中 / 失败 / 调用总数 / Token / 缓存命中 / 时延） -->
    <div class="arp-summary">
      <div class="arp-metric">
        <span class="arp-metric-num" :class="{ busy: inFlight > 0 }">{{ inFlight }}</span>
        <span class="arp-metric-label">推理中</span>
      </div>
      <div class="arp-metric">
        <span class="arp-metric-num" :class="{ bad: failedTotal > 0 }">{{ failedTotal }}</span>
        <span class="arp-metric-label">失败</span>
      </div>
      <div class="arp-metric">
        <span class="arp-metric-num">{{ total }}</span>
        <span class="arp-metric-label">{{ windowText }}调用</span>
      </div>
    </div>
    <div class="arp-summary is-second">
      <div class="arp-metric">
        <span class="arp-metric-num">{{ tokenText }}</span>
        <span class="arp-metric-label">Token</span>
      </div>
      <div class="arp-metric">
        <span class="arp-metric-num">{{ cacheText }}</span>
        <span class="arp-metric-label">缓存命中</span>
      </div>
      <div class="arp-metric">
        <span class="arp-metric-num">{{ latencyText }}</span>
        <span class="arp-metric-label">均值 / P95</span>
      </div>
    </div>

    <div v-if="degraded" class="arp-warn">
      <el-icon :size="14"><HIcon name="ic_public_security" /></el-icon>
      <span>暂时读不到运行状态（后端未就绪或统计查询降级）。恢复后会自动刷新。</span>
    </div>
    <div v-else-if="!traceEnabled" class="arp-warn">
      <el-icon :size="14"><HIcon name="ic_public_security" /></el-icon>
      <span>AI 调用留痕未开启，此处看不到调用统计（开启 <code>holzyn.actor.debug.ai-log.enabled</code> 后生效）。世界仍在正常运行。</span>
    </div>

    <div class="arp-block-head">
      <span>AI 任务中心</span>
      <span class="arp-updated">{{ updatedText }}</span>
      <el-button class="arp-refresh" size="small" text @click="refreshNow">刷新</el-button>
    </div>

    <!-- 统计窗口切换：默认「近 30 分钟」看当下，需要回溯时可放到 7 天 -->
    <el-radio-group v-model="window" class="arp-window" size="small" @change="onWindowChange">
      <el-radio-button v-for="w in RUNTIME_WINDOWS" :key="w.value" :value="w.value">{{ w.label }}</el-radio-button>
    </el-radio-group>

    <div v-if="sampled" class="arp-hint">
      窗口内调用数超过扫描上限，以上统计基于<strong>最近 20000 次调用样本</strong>（不是窗口全量）。
    </div>

    <!-- 面板内标签：属于「同一抽屉内的分类」，故用 el-tabs（页内主导航才用 PageTabs） -->
    <el-tabs v-model="tab" class="arp-tabs">
      <el-tab-pane label="最近调用" name="recent">
        <div v-if="!recent.length" class="arp-empty">窗口内没有调用记录。</div>
        <div v-for="row in recent" :key="'r' + row.id" class="arp-row">
          <el-tag size="small" :type="meta(row.status).type" effect="plain">{{ meta(row.status).label }}</el-tag>
          <span class="arp-row-main">
            <span class="arp-row-title">{{ row.sceneName || row.scene || '未标注场景' }}</span>
            <span class="arp-row-sub">
              <span v-if="row.characterId">角色 #{{ row.characterId }} · </span>
              <span v-if="row.model">{{ row.model }} · </span>
              <span v-if="row.durationMs != null">{{ row.durationMs }}ms</span>
              <span v-if="row.tokenIn || row.tokenOut"> · {{ row.tokenIn || 0 }}↑{{ row.tokenOut || 0 }}↓</span>
              <span v-if="row.cacheHit"> · 缓存命中</span>
            </span>
          </span>
          <span class="arp-row-time">{{ shortTime(row.createdAt) }}</span>
        </div>
      </el-tab-pane>

      <el-tab-pane name="failures">
        <template #label>
          <span>失败{{ failedTotal ? ` (${failedTotal})` : '' }}</span>
        </template>
        <div v-if="!failures.length" class="arp-empty">窗口内没有失败调用。</div>
        <div v-for="row in failures" :key="'f' + row.id" class="arp-row is-fail">
          <el-tag size="small" :type="meta(row.status).type" effect="plain">{{ meta(row.status).label }}</el-tag>
          <span class="arp-row-main">
            <span class="arp-row-title">{{ row.sceneName || row.scene || '未标注场景' }}</span>
            <span class="arp-row-sub">{{ row.errorMessage || '无错误信息（可到调试台看该次调用详情）' }}</span>
          </span>
          <span class="arp-row-time">{{ shortTime(row.createdAt) }}</span>
        </div>
        <div v-if="failures.length" class="arp-hint">
          失败调用不会中断世界：决策链在 AI 失败时回退程序化路径（fail-open）。需要排查细节请到调试台 AI Trace。
        </div>
      </el-tab-pane>

      <el-tab-pane label="场景分布" name="scenes">
        <div v-if="!byScene.length" class="arp-empty">窗口内没有调用记录。</div>
        <div v-for="s in byScene" :key="'s' + s.scene" class="arp-scene">
          <span class="arp-scene-name">{{ sceneLabel(s.scene) }}</span>
          <span class="arp-scene-bar">
            <span class="arp-scene-fill" :style="{ width: sceneBarWidth(s.count) }"></span>
          </span>
          <span class="arp-scene-num">{{ s.count }} 次{{ s.failed ? ` · 失败 ${s.failed}` : '' }}</span>
        </div>
        <div v-if="byScene.length" class="arp-hint">
          场景分布反映「这一段时间角色们在忙什么」。AI 成本不作为本项目约束，调用的多少只用于观测世界是否在真实地运转。
        </div>
      </el-tab-pane>
    </el-tabs>

    <div v-if="lastError" class="arp-error">{{ lastError }}</div>

    <div class="arp-foot">
      <el-button size="small" @click="goUsage">前往 AI 用量</el-button>
      <el-button size="small" text @click="goDebug">打开调试台</el-button>
    </div>
  </div>
</template>

<script setup>
/**
 * AI 运行状态抽屉内容组件。
 *
 * <p>职责：只读渲染 {@link useAiRuntime} 写入 layout store 的快照（inFlight / recent / failedTotal），
 * 并提供手动刷新与「前往 AI 用量」入口。轮询生命周期由 AppShell 统一持有（顶栏活动点也要用）。</p>
 */
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLayoutStore } from '@/shared/store/layout'
import { statusMeta, refreshAiRuntime, sceneLabel, setRuntimeWindow, RUNTIME_WINDOWS } from './useAiRuntime'

const router = useRouter()
const route = useRoute()
const layout = useLayoutStore()

/** 面板内标签（最近调用 / 失败 / 场景分布） */
const tab = ref('recent')

/**
 * 统计窗口（分钟）。初值取快照里的窗口，用户切换后由 {@link setRuntimeWindow} 触发立即刷新；
 * 快照回来后 watch 会把本地值对齐后端实际生效的窗口（后端会钳制到 1~10080）。
 */
const window = ref(30)
watch(() => layout.aiRuntime?.windowMinutes, (v) => { if (v) window.value = v }, { immediate: true })

/**
 * 切换窗口。
 *
 * @param {number} v 新的窗口分钟数
 */
function onWindowChange(v) { setRuntimeWindow(v) }

const sampled = computed(() => !!snapshotRaw.value.sampled)

/** 原始快照（供 sampled 等字段读取；与 template 里的 snapshot 同源） */
const snapshotRaw = computed(() => layout.aiRuntime || {})

/** 手动刷新：调用模块级单例轮询（快照写入 store，全站共享） */
function refreshNow() { refreshAiRuntime() }

const snapshot = computed(() => layout.aiRuntime || {})
const inFlight = computed(() => Number(snapshot.value.inFlight || 0))
const failedTotal = computed(() => Number(snapshot.value.failedTotal || 0))
const total = computed(() => Number(snapshot.value.total || 0))
const cacheHitRate = computed(() => Number(snapshot.value.cacheHitRate || 0))
const avgDurationMs = computed(() => Number(snapshot.value.avgDurationMs || 0))
const p95DurationMs = computed(() => Number(snapshot.value.p95DurationMs || 0))
const byScene = computed(() => snapshot.value.byScene || [])
const traceEnabled = computed(() => snapshot.value.traceEnabled !== false)
const recent = computed(() => snapshot.value.recent || [])
const failures = computed(() => snapshot.value.failures || [])
const degraded = computed(() => !!snapshot.value.degraded)

/** 窗口文案（「近 30 分钟」） */
const windowText = computed(() => `近 ${snapshot.value.windowMinutes || 30} 分钟`)

/** Token 总量（万级缩写，避免抽屉里塞一长串数字） */
const tokenText = computed(() => {
  const v = Number(snapshot.value.tokenTotal || 0)
  if (v >= 10000) return (v / 10000).toFixed(1) + ' 万'
  return String(v)
})

/** 缓存命中率 */
const cacheText = computed(() => `${Math.round(cacheHitRate.value * 100)}%`)

/** 均值 / P95 时延 */
const latencyText = computed(() => `${avgDurationMs.value} / ${p95DurationMs.value}ms`)

/** 场景分布条形宽度（以最大次数为 100%，最小 6% 保证可见） */
const maxSceneCount = computed(() => Math.max(1, ...byScene.value.map(s => Number(s.count || 0))))

/**
 * 场景条宽度。
 *
 * @param {number} count 次数
 * @returns {string} 百分比宽度
 */
function sceneBarWidth(count) {
  const pct = Math.round((Number(count || 0) / maxSceneCount.value) * 100)
  return Math.max(6, pct) + '%'
}

const updatedText = computed(() => {
  if (!snapshot.value.updatedAt) return '尚未刷新'
  const d = new Date(snapshot.value.updatedAt)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')} 更新`
})

const lastError = computed(() => (snapshot.value.lastError ? '读取失败：' + snapshot.value.lastError : ''))

const meta = statusMeta

/** 时间精简显示（HH:mm:ss；无值返回占位） */
function shortTime(v) {
  if (!v) return '—'
  const s = String(v)
  const m = s.match(/T(\d{2}:\d{2}:\d{2})/)
  return m ? m[1] : s.slice(-8)
}

/** 前往 AI 用量页（项目内优先） */
function goUsage() {
  layout.closeDrawer('ai-runtime')
  const m = route.path.match(/^\/project\/(\d+)/)
  router.push(m ? `/project/${m[1]}/settings/usage` : '/settings/appearance')
}

/** 打开调试台（新标签页；调试台独立打包在 /debug/） */
function goDebug() {
  window.open('/debug/#/ai', '_blank', 'noopener')
}
</script>

<style scoped>
.arp { display: flex; flex-direction: column; gap: var(--space-3, 12px); }

.arp-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-2, 8px); }
.arp-summary.is-second { margin-top: calc(-1 * var(--space-2, 8px)); }
.arp-metric {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 10px 6px;
  background: var(--bg-layer-2);
  border-radius: var(--radius-md, 12px);
}
.arp-metric-num { font-size: 1.4rem; font-weight: 700; color: var(--text-primary); line-height: 1.1; }
.arp-summary.is-second .arp-metric-num { font-size: 1.05rem; }
.arp-metric-num.busy { color: var(--accent-text); }
.arp-metric-num.bad { color: var(--state-error); }
.arp-metric-label { font-size: 0.72rem; color: var(--text-tertiary); }

.arp-tabs :deep(.el-tabs__header) { margin-bottom: 6px; }
.arp-tabs :deep(.el-tabs__item) { font-size: var(--density-font-sm, 0.82rem); height: 32px; line-height: 32px; }
.arp-window { width: 100%; }
.arp-window :deep(.el-radio-button) { flex: 1; }
.arp-window :deep(.el-radio-button__inner) { width: 100%; padding: 6px 4px; font-size: 0.74rem; }
.arp-hint {
  margin-top: 8px; padding: 8px 10px;
  font-size: 0.72rem; line-height: 1.6; color: var(--text-tertiary);
  background: var(--bg-layer-2); border-radius: var(--radius-sm, 8px);
}
.arp-row.is-fail .arp-row-sub { color: var(--state-error); opacity: .85; }

/* 场景分布条 */
.arp-scene { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.arp-scene-name { width: 84px; flex-shrink: 0; font-size: var(--density-font-sm, 0.82rem); color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.arp-scene-bar { flex: 1; height: 8px; background: var(--bg-layer-2); border-radius: 999px; overflow: hidden; }
.arp-scene-fill { display: block; height: 100%; background: var(--accent); border-radius: 999px; transition: width .2s var(--dsw-ease); }
.arp-scene-num { flex-shrink: 0; font-size: 0.72rem; color: var(--text-tertiary); font-family: var(--dsw-font-family-code); }

.arp-warn {
  display: flex; align-items: flex-start; gap: 6px;
  padding: 8px 10px;
  font-size: 0.76rem; line-height: 1.6;
  color: var(--state-warn);
  background: var(--state-warn-bg, var(--bg-layer-2));
  border-radius: var(--radius-sm, 8px);
}

.arp-block-head {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  font-size: 0.76rem; font-weight: 700; letter-spacing: .04em;
  color: var(--text-tertiary); text-transform: uppercase;
  padding-top: var(--space-2, 8px);
  border-top: 1px solid var(--border-l1);
}
.arp-updated { font-weight: 400; letter-spacing: 0; text-transform: none; }
.arp-refresh { margin-left: auto; }

.arp-empty { padding: 16px 0; text-align: center; font-size: 0.8rem; color: var(--text-tertiary); }

.arp-row {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  padding: 6px 0;
  border-bottom: 1px dashed var(--border-l1);
}
.arp-row:last-of-type { border-bottom: none; }
.arp-row-main { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.arp-row-title { font-size: var(--density-font-sm, 0.82rem); color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.arp-row-sub { font-size: 0.72rem; color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.arp-row-time { font-size: 0.72rem; color: var(--text-tertiary); flex-shrink: 0; font-family: var(--dsw-font-family-code); }

.arp-error { font-size: 0.72rem; color: var(--state-error); word-break: break-all; }
.arp-foot { margin-top: auto; padding-top: var(--space-2, 8px); }
</style>
