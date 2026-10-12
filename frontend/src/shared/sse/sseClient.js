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

//
// 前端统一 SSE 客户端（阶段 D · SSE 生命周期统一）
// 路径：shared/sse/sseClient.js
// ------------------------------------------------------------
// 背景：后端 统一了 SSE 生命周期（common/sse/SseLifecycle）——
// 统一超时/终止清理/15s 心跳/事件名常量。前端此前各页面各自 new EventSource
// 或手写 fetch+ReadableStream 解析器（重复、无重连/心跳/超时/错误分类统一），
// 本模块提供三个统一入口覆盖全部 SSE 端点：
//
// ① createSubscription —— 订阅型流（世界事件/行动/会话围观）：
// 断线自动重连（指数退避，达到上限后放弃）；心跳监听（可选）；
// 统一取消/错误分类。适用于「被动接收、可随时断线重建」的长连接。
// ② createGeneration —— 生成型流（对话/演化等流式生成）：
// 断线标记 failed 不重连（防重复生成）；超时可配；收到终止事件自动清理；
// 统一取消/错误分类。适用于「一次请求 → 一次生成结果」的短生命周期流。
// ③ streamFetch —— fetch + ReadableStream 型（工作流/解析/人群/地点提取）：
// 程序持续输出、需要 POST 表单/JSON 体、后端以 \n\n 分帧的 SSE 文本；
// 统一解析/错误分类/取消（AbortController）/可选超时。
//
// 统一约定：
// - 事件名常量 SSE_EVENTS 与后端 common/sse/SseLifecycle.EVENT_* 一一对应；
// - data 载荷统一 JSON.parse（失败回退原文），经 onEvent(name, data) 分发；
// - ping 心跳单独回调 onPing（不经过 onEvent，避免污染业务事件流）；
// - 错误统一分类为 SseError{kind, message, data}（connection/timeout/event/http/cancel/give_up）。
//

// ==================== 事件名常量（对齐后端 SseLifecycle.EVENT_*，勿改） ====================
export const SSE_EVENTS = {
  /** 连接建立确认（订阅即推送） */
  CONNECTED: 'connected',
  /** 心跳（后端 15s 一条 ping 事件） */
  PING: 'ping',
  /** 流式 token 增量 */
  TOKEN: 'token',
  /** 群聊新角色开始发言（message-start） */
  MESSAGE_START: 'message-start',
  /** 单条消息/单个环节完成 */
  DONE: 'done',
  /** 整体流程完成（工作流 result / 人群 done / 地点 done 等） */
  RESULT: 'result',
  /** 系统消息（演化环境变化 / 会话围观 system 消息） */
  SYSTEM: 'system',
  /** 进度（解析 progress） */
  PROGRESS: 'progress',
  /** 阶段推进（工作流 stage） */
  STAGE: 'stage',
  /** 日志行（工作流控制台） */
  LOG: 'log',
  /** 演化解码/调度事件 */
  SCHEDULE: 'schedule',
  /** 整体完成（演化收尾） */
  FINISHED: 'finished',
  /** 错误（统一载荷 {message}） */
  ERROR: 'error',
  // ==================== 业务流专属事件（与后端 Hub/生成端点一致） ====================
  /** 会话围观系统消息（NPC 内群退场/收尾叙述，载荷含 role/type/content/gameTimeText） */
  MESSAGE: 'message',
  /** 世界事件广播（world-events/stream） */
  WORLD_EVENT: 'world-event',
  /** 行动广播（actions/stream） */
  ACTION: 'action',
  /** 人群/地点生成开始（start） */
  START: 'start',
  /** 人群生成单条草稿（npc） */
  NPC: 'npc',
  /** 地点提取单条（location） */
  LOCATION: 'location'
}

// ==================== 错误分类 ====================
export const SseErrorKind = {
  /** 连接断开/无法建立 */
  CONNECTION: 'connection',
  /** 超时（心跳超时 / 生成超时） */
  TIMEOUT: 'timeout',
  /** 业务 error 事件（后端显式失败） */
  EVENT: 'event',
  /** HTTP 状态错误（fetch 流型 res.ok=false） */
  HTTP: 'http',
  /** 主动取消 */
  CANCEL: 'cancel',
  /** 订阅型重连次数超限放弃 */
  GIVE_UP: 'give_up'
}

/** 统一 SSE 错误对象（kind 分类 + 可读 message + 原始载荷） */
export class SseError extends Error {
  constructor(kind, message, data) {
    super(message || 'SSE 连接错误')
    this.name = 'SseError'
    this.kind = kind
    this.data = data
  }
}

/** 默认重连参数（订阅型：指数退避上限后放弃） */
const DEFAULT_RETRY = {
  maxRetries: 10,
  baseDelay: 1500,
  maxDelay: 30000
}

/** 内部：安全调用回调（回调异常不破坏客户端内部状态机/连接） */
function safeCall(fn, ...args) {
  if (typeof fn !== 'function') return
  try { fn(...args) } catch (_) { /* 回调异常忽略 */ }
}

/** 载荷解析：JSON 优先，失败回退原文 */
function parseData(raw) {
  if (raw == null || raw === '') return null
  try { return JSON.parse(raw) } catch (_) { return raw }
}

/** 内部：对 SSE 文本帧按 \n\n 切分并提取 event:/data: 行（fetch+ReadableStream 型共用解析器） */
function parseSseFrame(part) {
  let event = ''
  let data = ''
  for (const line of part.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!event && !data) return null
  return { event, data }
}

/**
 * ① 订阅型 SSE 客户端（世界事件流 / 行动流 / 会话围观 live-stream）。
 * 断线自动重连：指数退避（baseDelay × 2^n，上限 maxDelay，加随机抖动），
 * 达到 maxRetries 次后调用 onGiveUp 并停止（订阅型语义：上限后放弃，不无限重连）。
 * 可选心跳监听：heartbeatTimeoutMs>0 时，若超过该时长未收到任何事件（含 ping），
 * 视为连接已死 → 关闭当前连接并触发一次重连。
 */
export function createSubscription(opts = {}) {
  const { url, events = [], onEvent, onConnected, onPing, onError, onReconnect, onGiveUp } = opts
  const maxRetries = opts.maxRetries ?? DEFAULT_RETRY.maxRetries
  const baseDelay = opts.baseDelay ?? DEFAULT_RETRY.baseDelay
  const maxDelay = opts.maxDelay ?? DEFAULT_RETRY.maxDelay
  const heartbeatTimeoutMs = opts.heartbeatTimeoutMs ?? 0

  let source = null
  let closed = false
  let retries = 0
  let timer = null
  let heartbeatTimer = null
  let lastEventAt = Date.now()
  let connectedOnce = false

  /** 分发命名事件（ping 走独立回调，其余统一 onEvent） */
  function dispatch(name, raw) {
    lastEventAt = Date.now()
    if (name === SSE_EVENTS.PING) { onPing && onPing(); return }
    try { onEvent && onEvent(name, parseData(raw)) } catch (e) { /* 回调异常不中断流 */ }
  }

  /** 关闭当前 EventSource（不清理重连定时器） */
  function closeSource() {
    if (source) {
      try { source.close() } catch (_) { /* 忽略 */ }
      source = null
    }
  }

  /** 建立（或重建）连接 */
  function connect() {
    if (closed) return
    let es
    try {
      es = new EventSource(url)
    } catch (e) {
      // 构造失败（非法 URL 等）：按连接错误处理 → 重连/放弃
      handleFailure(new SseError(SseErrorKind.CONNECTION, '无法建立 SSE 连接：' + (e && e.message)))
      return
    }
    source = es
    es.onopen = () => {
      if (closed) { es.close(); return }
      lastEventAt = Date.now()
      retries = 0 // 连接成功即重置重连计数
      connectedOnce = true
      onConnected && onConnected()
    }
    // 命名事件注册（与后端事件名对齐）
    ;(events || []).forEach(name => es.addEventListener(name, ev => dispatch(name, ev.data)))
    // 默认事件（无事件名，如后端纯 data: 行）
    es.onmessage = ev => dispatch('message', ev.data)
    es.onerror = () => {
      if (closed) return
      closeSource()
      handleFailure(new SseError(SseErrorKind.CONNECTION, '连接已断开'))
    }
  }

  /** 失败处理：重连（指数退避）或放弃 */
  function handleFailure(error) {
    if (closed) return
    if (retries >= maxRetries) {
      // 达到重连上限：放弃（订阅型语义），通知调用方
      onGiveUp && onGiveUp()
      onError && onError(new SseError(SseErrorKind.GIVE_UP, '连接重试达上限已放弃', error))
      close()
      return
    }
    retries++
    // 指数退避 + 上限 + 抖动（防止多客户端同时重连打爆服务端）
    const delay = Math.min(baseDelay * Math.pow(2, retries - 1), maxDelay) + Math.random() * 500
    onReconnect && onReconnect(retries, delay, error)
    timer = setTimeout(() => { if (!closed) connect() }, delay)
  }

  /** 心跳监听：超过 heartbeatTimeoutMs 无任何事件 → 主动重连 */
  function startHeartbeatWatch() {
    if (heartbeatTimeoutMs <= 0) return
    heartbeatTimer = setInterval(() => {
      if (closed) return
      if (Date.now() - lastEventAt > heartbeatTimeoutMs) {
        closeSource()
        handleFailure(new SseError(SseErrorKind.TIMEOUT, '心跳超时（' + heartbeatTimeoutMs + 'ms 无数据）'))
      }
    }, Math.max(heartbeatTimeoutMs, 5000))
  }

  connect()
  startHeartbeatWatch()

  return {
    /** 当前状态：open（已连接）/ reconnecting（重连中）/ closed（已关闭） */
    get state() {
      if (closed) return 'closed'
      return source ? 'open' : 'reconnecting'
    },
    /** 主动关闭（页面离开/切换会话时调用，彻底停止重连） */
    close() {
      closed = true
      if (timer) { clearTimeout(timer); timer = null }
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null }
      closeSource()
    }
  }
}

/**
 * ② 生成型 SSE 客户端（对话流 / 演化流等「一次请求 → 一次生成」）。
 * 断线标记 failed 不重连（防重复生成/重复扣费）；收到终止事件（done/result/finished，
 * 经 terminalEvents 配置）自动关闭并 onDone；收到 error 事件按 EVENT 错误 onError。
 * 可选整体超时 timeoutMs（默认 0=不检测，生成型多为长耗时）。
 * isTerminal(name, data)：可选「终止判定」函数（优先级高于 terminalEvents 列表），
 * 用于同一事件名在多语义下区分终止（如对话流 DONE 既可能是单角色回复完成、
 * 也可能是整轮结束[group=true]——仅后者应视为终止）。
 */
export function createGeneration(opts = {}) {
  const { url, events = [], onEvent, onConnected, onPing, onDone, onError } = opts
  const isTerminal = opts.isTerminal || null
  const terminalEvents = opts.terminalEvents || [SSE_EVENTS.DONE, SSE_EVENTS.RESULT, SSE_EVENTS.FINISHED]
  const timeoutMs = opts.timeoutMs ?? 0

  let source = null
  let closed = false
  let terminalReceived = false
  let timeoutTimer = null

  /** 分发事件：ping 独立回调；error 统一失败；终止事件触发 onDone 并清理 */
  function dispatch(name, raw) {
    if (closed) return
    if (name === SSE_EVENTS.PING) { onPing && onPing(); return }
    if (name === SSE_EVENTS.ERROR) {
      const data = parseData(raw)
      const msg = (data && typeof data === 'object' && data.message) || '生成失败'
      fail(new SseError(SseErrorKind.EVENT, msg, data))
      return
    }
    const data = parseData(raw)
    onEvent && onEvent(name, data)
    // 收到终止事件：标记已终止并关闭（后端随后 complete，EventSource 会触发 onerror，
    // 由 terminalReceived 判定为「正常结束」而非「断线失败」）
    const terminal = isTerminal ? isTerminal(name, data) : terminalEvents.includes(name)
    if (terminal) {
      terminalReceived = true
      close()
      onDone && onDone(data)
    }
  }

  /** 连接断开/超时/错误统一入口 */
  function fail(error) {
    if (closed) return
    terminalReceived = true
    close()
    onError && onError(error)
  }

  /** 关闭连接与定时器 */
  function close() {
    if (closed) return
    closed = true
    if (timeoutTimer) { clearTimeout(timeoutTimer); timeoutTimer = null }
    if (source) {
      try { source.close() } catch (_) { /* 忽略 */ }
      source = null
    }
  }

  let es
  try {
    es = new EventSource(url)
  } catch (e) {
    fail(new SseError(SseErrorKind.CONNECTION, '无法建立 SSE 连接：' + (e && e.message)))
    return { close }
  }
  source = es
  es.onopen = () => { if (!closed) onConnected && onConnected() }
  ;(events || []).forEach(name => es.addEventListener(name, ev => dispatch(name, ev.data)))
  es.onmessage = ev => dispatch('message', ev.data)
  es.onerror = () => {
    // 生成型：断线标记 failed 不重连。若已收到终止事件（正常结束）则静默；
    // 否则视为中途断线失败（防重复生成）。
    if (closed) return
    if (terminalReceived) return
    fail(new SseError(SseErrorKind.CONNECTION, '生成连接已断开（未完成，不自动重连）'))
  }

  if (timeoutMs > 0) {
    timeoutTimer = setTimeout(() => {
      if (!closed && !terminalReceived) {
        fail(new SseError(SseErrorKind.TIMEOUT, '生成超时（' + timeoutMs + 'ms）'))
      }
    }, timeoutMs)
  }

  return { close }
}

/**
 * ③ fetch + ReadableStream 型 SSE 客户端（工作流/解析/人群/地点提取）。
 * 以 POST 发起（支持 FormData/JSON），后端以 \n\n 分帧推送 SSE 文本；
 * 统一解析 event:/data: 行，经 onEvent(name, data) 分发；
 * error 事件 → EVENT 错误并终止；流自然结束 → onDone(terminalPayload)；
 * 支持 AbortController 取消（cancel）与可选超时。
 */
export function streamFetch(opts = {}) {
  const { url, method = 'POST', headers, body, onEvent, onError, onDone } = opts
  const timeoutMs = opts.timeoutMs ?? 0
  const abortController = typeof AbortController !== 'undefined' ? new AbortController() : null
  let cancelled = false

  const promise = (async () => {
    let res
    try {
      res = await fetch(url, { method, headers, body, signal: abortController ? abortController.signal : undefined })
    } catch (e) {
      if (cancelled || (e && e.name === 'AbortError')) return null
      onError && onError(new SseError(SseErrorKind.CONNECTION, '无法发起流请求：' + (e && e.message)))
      return null
    }
    if (!res.ok || !res.body) {
      let msg = '流请求失败（HTTP ' + res.status + '）'
      try { msg = (await res.json())?.message || msg } catch (_) { /* 忽略非 JSON 错误体 */ }
      onError && onError(new SseError(SseErrorKind.HTTP, msg))
      return null
    }
    try {
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let terminalPayload = null
      while (true) {
        if (cancelled) return null
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const parts = buffer.split('\n\n')
        buffer = parts.pop() || ''
        for (const part of parts) {
          const frame = parseSseFrame(part)
          if (!frame) continue
          if (frame.event === SSE_EVENTS.ERROR) {
            const data = parseData(frame.data)
            const msg = (data && typeof data === 'object' && data.message) || '流执行失败'
            onError && onError(new SseError(SseErrorKind.EVENT, msg, data))
            return null
          }
          const data = parseData(frame.data)
          // 终止事件（result/done/finished）记录载荷，其余正常分发
          if (frame.event === SSE_EVENTS.RESULT || frame.event === SSE_EVENTS.DONE
              || frame.event === SSE_EVENTS.FINISHED) {
            terminalPayload = data
            onEvent && onEvent(frame.event, data)
          } else {
            onEvent && onEvent(frame.event, data)
          }
        }
      }
      if (!cancelled) onDone && onDone(terminalPayload)
      return terminalPayload
    } catch (e) {
      if (cancelled || (e && e.name === 'AbortError')) return null
      onError && onError(new SseError(SseErrorKind.CONNECTION, '流读取中断：' + (e && e.message)))
      return null
    }
  })()

  if (timeoutMs > 0) {
    setTimeout(() => {
      if (!cancelled && abortController) {
        cancelled = true
        abortController.abort()
        onError && onError(new SseError(SseErrorKind.TIMEOUT, '流请求超时（' + timeoutMs + 'ms）'))
      }
    }, timeoutMs)
  }

  return {
    /** 主动取消（AbortController.abort + 标记取消，onError 不再触发） */
    cancel() {
      cancelled = true
      if (abortController) { try { abortController.abort() } catch (_) { /* 忽略 */ } }
    },
    /** 完成 Promise（resolve=终止事件载荷 / null=取消或错误） */
    promise
  }
}
