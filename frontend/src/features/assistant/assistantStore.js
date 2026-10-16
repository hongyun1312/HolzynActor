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

// 项目级 AI 助手前端状态（模块级单例，跨路由/页面保持）。
// 记忆态（落盘、刷新保持）：侧边栏展开与否、面板宽度。
// 会话态（仅内存，刷新回空态）：当前会话、消息列表、流式状态。
import { reactive, readonly } from 'vue'

const STORAGE_KEY = 'holzyn.ui.assistant.v1'

const ASSISTANT_WIDTH_DEFAULT = 400
const ASSISTANT_WIDTH_MIN = 320
const ASSISTANT_WIDTH_MAX = 640

/** 读取持久化偏好（损坏时静默回退默认）。 */
function loadPrefs() {
  const fallback = { sidebarOpen: false, width: ASSISTANT_WIDTH_DEFAULT }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return fallback
    const p = JSON.parse(raw)
    return {
      sidebarOpen: !!p.sidebarOpen,
      width: clamp(Number(p.width) || ASSISTANT_WIDTH_DEFAULT)
    }
  } catch (_) {
    return fallback
  }
}

function clamp(w) {
  return Math.min(ASSISTANT_WIDTH_MAX, Math.max(ASSISTANT_WIDTH_MIN, w))
}

/** 模块级响应式状态。 */
export const assistantState = reactive({
  ...loadPrefs(),
  /** 当前项目 ID（由路由驱动；null=非项目页，浮窗隐藏） */
  projectId: null,
  /** 会话列表（按更新时间倒序） */
  sessions: [],
  /** 当前会话 ID */
  activeSessionId: null,
  /** 当前会话默认模式 */
  defaultMode: 'narrative',
  /** 当前会话干预确认粒度（strict 严格 / standard 标准 / trust 信任） */
  confirmMode: 'standard',
  /** 消息列表（user/assistant） */
  messages: [],
  /** 是否正在流式回答 */
  streaming: false,
  /** 最近一次错误（展示用） */
  lastError: null,
  /** 面板加载态 */
  loading: false,
  /** 待发送问题队列（外部入口「问问助手」/调试页「解释这次决策」写入，会话就绪后消费） */
  pendingAsks: []
})

/** 只读导出（外部只经 action 修改）。 */
export const assistant = readonly(assistantState)

/** 持久化记忆态（展开/宽度）。 */
function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      sidebarOpen: assistantState.sidebarOpen,
      width: assistantState.width
    }))
  } catch (_) {
    // localStorage 不可用（隐私模式等）时静默忽略
  }
}

export const ASSISTANT_WIDTH_RANGE = { min: ASSISTANT_WIDTH_MIN, max: ASSISTANT_WIDTH_MAX }

/** 展开侧边栏。 */
export function openAssistant() {
  assistantState.sidebarOpen = true
  persist()
}

/** 收起侧边栏（回浮窗；会话与消息保留）。 */
export function closeAssistant() {
  assistantState.sidebarOpen = false
  assistantState.streaming = false
  persist()
}

/** 切换展开/收起。 */
export function toggleAssistant() {
  if (assistantState.sidebarOpen) closeAssistant()
  else openAssistant()
}

/** 拖拽改宽（记忆态）。 */
export function setAssistantWidth(w) {
  assistantState.width = clamp(w)
  persist()
}

/** 进入项目：重置项目会话态（跨项目隔离）。 */
export function enterProject(projectId) {
  if (assistantState.projectId !== projectId) {
    assistantState.projectId = projectId
    assistantState.sessions = []
    assistantState.activeSessionId = null
    assistantState.defaultMode = 'narrative'
    assistantState.confirmMode = 'standard'
    assistantState.messages = []
    assistantState.lastError = null
    assistantState.pendingAsks = []
  }
}

/**
 * 外部入口唤起助手并提问（沙盘「问问助手」/调试页「解释这次决策」等）。
 * 展开侧边栏 + 把问题加入待发送队列（会话加载就绪后由 Widget 消费发送）。
 */
export function askAssistant(question) {
  if (!question || !question.trim()) return
  openAssistant()
  assistantState.pendingAsks.push(question.trim())
}

/** 更新当前会话干预确认粒度（strict/standard/trust）。 */
export function setConfirmMode(mode) {
  assistantState.confirmMode = mode
}
