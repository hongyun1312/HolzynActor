/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { onBeforeUnmount } from 'vue'
import { fetchAiRuntimeStatus } from '@/shared/api'
import { useLayoutStore } from '@/shared/store/layout'

/** 轮询基础间隔（ms）；失败时指数退避，避免后端未启动时的请求风暴 */
const BASE_INTERVAL = 8000
const MAX_INTERVAL = 60000

/** 统计窗口（分钟）默认值：状态条看的是「刚刚发生了什么」，30 分钟足够且查询代价最低 */
export const WINDOW_MINUTES = 30

/** 可选统计窗口（面板上的时间范围切换；与后端钳制区间 1~10080 一致） */
export const RUNTIME_WINDOWS = [
  { value: 30, label: '30 分钟' },
  { value: 360, label: '6 小时' },
  { value: 1440, label: '24 小时' },
  { value: 10080, label: '7 天' }
]

/** 当前窗口（模块级：面板切换后，下一次轮询与手动刷新都用新窗口） */
let currentWindow = WINDOW_MINUTES

/**
 * 切换统计窗口并立即刷新一次。
 *
 * @param {number} minutes 窗口分钟数
 * @returns {Promise<void>} 刷新完成
 */
export function setRuntimeWindow(minutes) {
  const v = Number(minutes)
  if (Number.isFinite(v) && v >= 1) currentWindow = Math.round(v)
  return refreshAiRuntime()
}

/** 视为「进行中」的状态集合（后端 ActorAiCallLog.status 的取值） */
const IN_FLIGHT = new Set(['running', 'pending', 'streaming', 'queued'])

/**
 * 场景标识 → 中文名（覆盖度不足时原样展示标识，不做臆造翻译）。
 * <p>与后端 scene 字段同源；新增场景在此补一行即可。</p>
 */
const SCENE_LABELS = {
  dialog: '对话',
  group_chat: '群聊',
  action: '行动',
  action_gen: '决策行动',
  perception: '感知',
  reflection: '反思',
  interrupt_probe: '打断判定',
  decision_sample: '决策模型 判定',
  card_gen: '角色卡生成',
  fill_b: '档案补全',
  worldgen: '世界观生成',
  embedding: '向量化',
  macro: '宏观推演',
  knowledge_bridge: '知识桥接',
  proactive_retrieval: '主动检索',
  self_efficacy_update: '能力自评',
  muse_strategy_check: '策略复核',
  world_outlook: '世界预测',
  self_check: '自省校验'
}

/**
 * 场景中文名（未登记则原样返回标识）。
 *
 * @param {string} scene 场景标识
 * @returns {string} 展示名
 */
export function sceneLabel(scene) {
  if (!scene) return '未标注场景'
  return SCENE_LABELS[scene] || scene
}

/** 状态 → 中文标签与色板类型（Element Plus tag type） */
export const AI_STATUS_META = {
  success: { label: '成功', type: 'success' },
  failed: { label: '失败', type: 'danger' },
  error: { label: '失败', type: 'danger' },
  timeout: { label: '超时', type: 'warning' },
  degraded: { label: '降级', type: 'warning' },
  running: { label: '推理中', type: 'primary' },
  pending: { label: '排队中', type: 'info' },
  streaming: { label: '流式输出', type: 'primary' },
  queued: { label: '排队中', type: 'info' }
}

/** 状态元信息（未识别状态原样展示） */
export function statusMeta(status) {
  return AI_STATUS_META[status] || { label: status || '未知', type: 'info' }
}

/**
 * 后端 CallItem → 面板行（补场景中文名，保持模板字段名与批次① 一致，避免抽屉模板大改）。
 *
 * @param {object} it 后端返回的单条调用摘要
 * @returns {object} 面板行
 */
function toRow(it) {
  return {
    id: it?.id,
    traceId: it?.traceId,
    scene: it?.scene,
    sceneName: sceneLabel(it?.scene),
    model: it?.model,
    status: it?.status,
    characterId: it?.characterId,
    durationMs: it?.durationMs,
    tokenIn: it?.tokenIn,
    tokenOut: it?.tokenOut,
    cacheHit: !!it?.cacheHit,
    createdAt: it?.createdAt,
    errorMessage: it?.errorMessage
  }
}

/**
 * 模块级单例轮询句柄。
 * <p>为什么是单例：顶栏的活动点（AppShell）与「AI 运行状态」抽屉（AiRuntimePanel）需要同一份快照，
 * 抽屉里的「刷新」按钮也要能立刻触发一次拉取。做成模块级后，任何位置都能调用
 * {@link refreshAiRuntime} 而不必再挂一套轮询。</p>
 */
let poller = null

/**
 * 立即刷新一次（抽屉按钮 / 顶栏图标点击时调用）。无活动轮询时静默返回。
 * @returns {Promise<void>}
 */
export function refreshAiRuntime() {
  return poller ? poller.refreshNow() : Promise.resolve()
}

/**
 * AI 运行状态轮询（顶栏活动点 + 「AI 运行状态」任务中心抽屉的数据源）。
 *
 * <p>职责：定时拉取**聚合只读接口** {@code GET /api/worlds/{projectId}/ai-runtime/status}
 * （批次③ 起启用），产出快照写入 layout store：</p>
 * <pre>
 * {
 *   inFlight, recent[],            // 推理中 / 最近调用流水
 *   failedTotal, failures[],       // 窗口内失败数与失败明细
 *   total, tokenTotal, cacheHitRate, avgDurationMs, p95DurationMs,
 *   byScene[], traceEnabled, degraded, updatedAt, lastError
 * }
 * </pre>
 *
 * <p>为什么是聚合接口而不是「分页 + 失败分页」两次请求：一次请求即可同时拿到总量、Token、
 * 缓存命中率、时延分位、场景分布与失败明细，请求数从 2 降到 1，且失败口径由后端统一
 * （error/timeout 均计失败），前端不必各自解释状态字面量。</p>
 *
 * <p>为什么不用 SSE：AI 调用记录是**历史事实**（每次调用落库），不是流式事件；轮询成本极低
 * （单表窗口查询、不触发任何 AI 调用）。</p>
 *
 * <p>生命周期：在 AppShell 中调用一次 `start()`；组件卸载自动停止（onBeforeUnmount）。</p>
 *
 * @param {() => (number|null)} getProjectId 取当前项目 ID 的函数
 */
export function useAiRuntime(getProjectId) {
  const layout = useLayoutStore()
  let timer = null
  let interval = BASE_INTERVAL
  let stopped = false

  /** 拉取一次快照 */
  async function tick() {
    if (stopped) return
    const projectId = typeof getProjectId === 'function' ? getProjectId() : null
    if (projectId == null) {
      // 未进入项目（画廊/全局页）：没有项目维度的 AI 运行状态可言，静默保持空快照
      layout.setAiRuntime({
        inFlight: 0,
        recent: [],
        failures: [],
        failedTotal: 0,
        total: 0,
        tokenTotal: 0,
        cacheHitRate: 0,
        avgDurationMs: 0,
        p95DurationMs: 0,
        byScene: [],
        windowMinutes: currentWindow,
        sampled: false,
        traceEnabled: true,
        degraded: false,
        updatedAt: new Date().toISOString(),
        lastError: ''
      })
      return
    }
    try {
      const status = await fetchAiRuntimeStatus(projectId, currentWindow)
      const recent = (status?.recent || []).map(toRow)
      layout.setAiRuntime({
        inFlight: recent.filter(i => IN_FLIGHT.has(i.status)).length,
        recent: recent.slice(0, 12),
        failures: (status?.failures || []).map(toRow),
        failedTotal: status?.failed ?? 0,
        total: status?.total ?? 0,
        tokenTotal: status?.tokenTotal ?? 0,
        tokenIn: status?.tokenIn ?? 0,
        tokenOut: status?.tokenOut ?? 0,
        cacheHitRate: status?.cacheHitRate ?? 0,
        avgDurationMs: status?.avgDurationMs ?? 0,
        p95DurationMs: status?.p95DurationMs ?? 0,
        byScene: status?.byScene || [],
        windowMinutes: status?.windowMinutes ?? currentWindow,
        sampled: !!status?.sampled,
        traceEnabled: status?.traceEnabled !== false,
        degraded: !!status?.degraded,
        updatedAt: new Date().toISOString(),
        lastError: status?.degraded ? '统计查询失败（后端已降级返回空窗口）' : ''
      })
      interval = BASE_INTERVAL
    } catch (e) {
      // 后端未就绪 / 接口不可用：标记降级（活动点不亮），但不打断用户操作
      layout.setAiRuntime({
        inFlight: 0,
        recent: [],
        failures: [],
        failedTotal: 0,
        total: 0,
        tokenTotal: 0,
        cacheHitRate: 0,
        avgDurationMs: 0,
        p95DurationMs: 0,
        byScene: [],
        windowMinutes: currentWindow,
        sampled: false,
        traceEnabled: true,
        degraded: true,
        updatedAt: new Date().toISOString(),
        lastError: e?.message || '未知错误'
      })
      interval = Math.min(MAX_INTERVAL, Math.round(interval * 1.6))
    } finally {
      if (!stopped) timer = setTimeout(tick, interval)
    }
  }

  /** 立即拉取一次（重置退避） */
  function refreshNow() {
    if (timer) { clearTimeout(timer); timer = null }
    interval = BASE_INTERVAL
    return tick()
  }

  function start() {
    if (stopped) return
    if (timer) clearTimeout(timer)
    tick()
  }

  function stop() {
    stopped = true
    if (timer) { clearTimeout(timer); timer = null }
    if (poller === instance) poller = null
  }

  const instance = { start, stop, refreshNow }
  poller = instance
  onBeforeUnmount(stop)

  return instance
}
