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

/**
 * 快捷键表（**唯一权威**：AppShell 的按键分发与 ShortcutHelp 速查面板共用同一份数据）。
 *
 * <p>设计取舍：酒馆把快捷键分散在三处脚本里、且大量使用单键（↑/Enter/ArrowLeft），
 * 本项目输入框（对话/Prompt/搜索）密集，单键极易误触——因此全局快捷键统一带 Ctrl/Alt 修饰，
 * 仅对话页的「Enter 发送 / ↑ 编辑最后一条」这类**输入框内**的键位按酒馆习惯保留，
 * 且由对话页自己处理（不在全局表内，避免页面外误触发）。</p>
 *
 * <p>`id` 与 AppShell 的分发 switch 一一对应；`when` 描述生效条件（仅供速查面板展示）。</p>
 */
export const SHORTCUTS = [
  { id: 'command', group: '全局', keys: ['Ctrl', 'K'], title: '命令面板', desc: '搜索页面、动作与命令', when: '任意页面' },
  { id: 'help', group: '全局', keys: ['?'], title: '快捷键速查', desc: '打开/关闭本面板', when: '不在输入框内' },
  { id: 'sidebar', group: '全局', keys: ['Ctrl', 'B'], title: '收起 / 展开侧栏', desc: '窄屏下为滑入 / 滑出', when: '任意页面' },
  { id: 'theme', group: '全局', keys: ['Ctrl', 'Shift', 'L'], title: '浅色 / 深色主题', desc: '与顶栏主题按钮等效', when: '任意页面' },
  { id: 'density', group: '全局', keys: ['Ctrl', 'Shift', 'D'], title: '循环信息密度', desc: '紧凑 → 舒适 → 宽松', when: '任意页面' },
  { id: 'escape', group: '全局', keys: ['Esc'], title: '逐层关闭', desc: '命令面板 → 右键菜单 → 未固定面板（固定的不关）', when: '任意页面' },
  { id: 'domain', group: '全局', keys: ['Alt', '1–5'], title: '跳到第 N 个功能域', desc: '世界 / 角色 / 心智 / 观测 / 设置', when: '项目空间内' },
  { id: 'tour', group: '全局', keys: ['Ctrl', 'Shift', 'T'], title: '重看界面导览', desc: '新布局上手引导', when: '任意页面' },

  // ===== 以下为「页面内键位」，由各页自行实现（列在此处只为速查面板可见） =====
  { id: 'chat-send', group: '对话页', keys: ['Enter'], title: '发送消息', desc: '输入框聚焦时（Shift+Enter 换行）', when: '对话页输入框' },
  { id: 'chat-regen', group: '对话页', keys: ['Ctrl', 'Enter'], title: '重新生成', desc: '对最后一条 AI 回复', when: '对话页' },
  { id: 'chat-continue', group: '对话页', keys: ['Alt', 'Enter'], title: '续写', desc: '让角色继续往下写', when: '对话页' },
  { id: 'chat-jump', group: '对话页', keys: ['Ctrl', 'Shift', '↑ / ↓'], title: '跳到上下文分隔行 / 底部', desc: '长对话快速定位', when: '对话页' },
  { id: 'chat-edit', group: '对话页', keys: ['↑ / Ctrl+↑'], title: '编辑最后一条消息', desc: '输入框为空时；Ctrl+↑ 仅最后一条用户消息', when: '对话页输入框为空' }
]

/**
 * 事件 → 快捷键 id 匹配。
 * @param {KeyboardEvent} ev
 * @returns {string|null} 命中的快捷键 id
 */
export function matchShortcut(ev) {
  const ctrl = ev.ctrlKey || ev.metaKey
  const key = ev.key
  const lower = typeof key === 'string' ? key.toLowerCase() : ''

  // ?（Shift+/ 在多数键盘布局下产生 '?'）
  if (!ctrl && !ev.altKey && key === '?') return 'help'
  if (ctrl && !ev.shiftKey && !ev.altKey && lower === 'k') return 'command'
  if (ctrl && !ev.shiftKey && !ev.altKey && lower === 'b') return 'sidebar'
  if (ctrl && ev.shiftKey && !ev.altKey && lower === 'l') return 'theme'
  if (ctrl && ev.shiftKey && !ev.altKey && lower === 'd') return 'density'
  if (ctrl && ev.shiftKey && !ev.altKey && lower === 't') return 'tour'
  if (ev.altKey && !ctrl && !ev.shiftKey && /^[1-5]$/.test(key)) return `domain-${key}`
  if (key === 'Escape') return 'escape'
  return null
}

/**
 * 判断事件是否来自可编辑控件（输入框/文本域/富文本）。
 * <p>用于：全局快捷键在用户打字时必须让位（Ctrl+K / Esc / Ctrl+B 等系统键除外）。</p>
 */
export function isEditableTarget(target) {
  if (!target || !target.tagName) return false
  const tag = target.tagName.toLowerCase()
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return true
  return !!target.isContentEditable
}

/** 渲染用：把 keys 数组格式化为展示串 */
export function formatKeys(keys) {
  return (keys || []).join(' + ')
}
