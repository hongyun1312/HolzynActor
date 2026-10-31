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
 * 开发者模式控制台导航模型（9 个功能域 + 域内二级 Tab + 旧页面归属）。
 *
 * <p><b>唯一来源</b>：侧栏、页内二级导航、页面标题都从这里派生（与主应用 navModel 同一套纪律：
 * 不在组件里另写一份数组，否则「侧栏有、页内没有」的漂移迟早发生）。</p>
 *
 * <p><b>为什么是 9 域而不是把旧 25 页平铺</b>：旧 debug-frontend 有 25 个平级页面，
 * 平铺成 25 个 Tab 会在 1440 宽下直接横向溢出；按「开发者在排查什么」归成 9 组后，
 * 每个域内部再用二级 Tab 拆，既装得下也符合主应用「域 → 页内导航 → 面板 Tab」的三层语义。</p>
 *
 * <p>旧页面归属映射见 `LEGACY_PAGE_MAP`，用于验收时逐项核对「能力没丢」。</p>
 */

/** 9 个功能域 */
export const DEV_DOMAINS = [
  {
    key: 'overview',
    name: '总览',
    icon: 'Odometer',
    path: '/dev/overview',
    desc: '引擎/内容/成本/参数四组指标 + 前置条件自检 + 系统环境',
    tabs: [
      { key: 'runtime', label: '运行总览' },
      { key: 'health', label: '健康自检' },
      { key: 'errors', label: '最近异常' },
      { key: 'env', label: '系统环境' }
    ]
  },
  {
    key: 'world',
    name: '世界引擎',
    icon: 'Earth',
    path: '/dev/world',
    desc: '世界时钟与环境、引擎快照、宏观社会、地图地点、事件流',
    tabs: [
      { key: 'clock', label: '时钟与环境' },
      { key: 'snapshot', label: '世界快照' },
      { key: 'macro', label: '宏观社会' },
      { key: 'map', label: '地图与地点' },
      { key: 'events', label: '事件流' }
    ]
  },
  {
    key: 'npc',
    name: '角色与状态',
    icon: 'User',
    path: '/dev/npcs',
    desc: '角色列表 → 全息详情（位置/感知/需求/PAD/目标/决策/记忆/对话）+ 状态调参',
    tabs: [
      { key: 'list', label: '角色列表' },
      { key: 'tuning', label: '状态调参' }
    ]
  },
  {
    key: 'agent',
    name: '决策与认知',
    icon: 'MagicStick',
    path: '/dev/agent',
    desc: '决策链路、决策协调、刺激日志、决策模型 判定、认知 LOD、行为评估',
    tabs: [
      { key: 'traces', label: '决策链路' },
      { key: 'coord', label: '决策协调' },
      { key: 'stimulus', label: '刺激日志' },
      { key: 'decision', label: '决策模型 判定' },
      { key: 'lod', label: '认知 LOD' },
      { key: 'eval', label: '行为评估' }
    ]
  },
  {
    key: 'memory',
    name: '记忆',
    icon: 'Reading',
    path: '/dev/memory',
    desc: '记忆健康/内核、注入预览、项目记忆、漂移度量',
    tabs: [
      { key: 'core', label: '记忆内核' },
      { key: 'injection', label: '注入预览' },
      { key: 'world', label: '项目记忆' },
      { key: 'drift', label: '漂移度量' }
    ]
  },
  {
    key: 'ai',
    name: 'AI 调用与 Prompt',
    icon: 'ChatDotRound',
    path: '/dev/ai',
    desc: 'AI 调用列表与详情、用量成本、Prompt 工程、护栏与路由',
    tabs: [
      { key: 'calls', label: '调用列表' },
      { key: 'usage', label: '用量与成本' },
      { key: 'prompt', label: 'Prompt 工程' },
      { key: 'policy', label: '护栏与路由' }
    ]
  },
  {
    key: 'data',
    name: '数据',
    icon: 'FolderOpened',
    path: '/dev/data',
    desc: '白名单表清单、只读分页浏览、导出、迁移历史',
    tabs: [
      { key: 'tables', label: '表清单' },
      { key: 'browse', label: '表浏览' },
      { key: 'schema', label: '迁移历史' }
    ]
  },
  {
    key: 'config',
    name: '配置与调参',
    icon: 'Setting',
    path: '/dev/config',
    desc: '全量参数目录（可写/生效方式标注）、已改参数、审计日志、重启生效',
    tabs: [
      { key: 'params', label: '参数目录' },
      { key: 'changed', label: '已改参数' },
      { key: 'audit', label: '审计日志' },
      { key: 'effect', label: '生效与重启' }
    ]
  },
  {
    key: 'tools',
    name: '工具',
    icon: 'Key',
    path: '/dev/tools',
    desc: '实时日志、Unity 对接、通知与系统信息',
    tabs: [
      { key: 'logs', label: '实时日志' },
      { key: 'unity', label: 'Unity 对接' },
      { key: 'system', label: '系统信息' }
    ]
  }
]

/** 旧 debug-frontend 25 页 → 新归属（验收用：逐项确认「能力没丢」） */
export const LEGACY_PAGE_MAP = [
  { legacy: 'OverviewView', domain: 'overview', tab: 'runtime', label: '总览' },
  { legacy: 'RuntimeOverviewView', domain: 'agent', tab: 'traces', label: 'AgentRuntime 概览' },
  { legacy: 'LogsView', domain: 'tools', tab: 'logs', label: '实时日志' },
  { legacy: 'AiTraceView', domain: 'ai', tab: 'calls', label: 'AI Trace' },
  { legacy: 'AiCallDetailView', domain: 'ai', tab: 'calls', label: 'AI 调用详情' },
  { legacy: 'DriftMetricsView', domain: 'memory', tab: 'drift', label: '漂移度量' },
  { legacy: 'InjectionPreviewView', domain: 'memory', tab: 'injection', label: '记忆注入预览' },
  { legacy: 'StateView', domain: 'npc', tab: 'list', label: '状态曲线' },
  { legacy: 'NpcStateTuningView', domain: 'npc', tab: 'tuning', label: '状态调参' },
  { legacy: 'StimulusLogView', domain: 'agent', tab: 'stimulus', label: '刺激日志' },
  { legacy: 'WorldMemoryView', domain: 'memory', tab: 'world', label: '项目记忆' },
  { legacy: 'MacroView', domain: 'world', tab: 'macro', label: '宏观面板' },
  { legacy: 'MemoryO1View', domain: 'memory', tab: 'core', label: '记忆内核 O1' },
  { legacy: 'DecisionCoordView', domain: 'agent', tab: 'coord', label: '决策协调' },
  { legacy: 'EvaluationTestView', domain: 'agent', tab: 'eval', label: '行为评估' },
  { legacy: 'AiPolicyView', domain: 'ai', tab: 'policy', label: '护栏与路由' },
  { legacy: 'PromptEngView', domain: 'ai', tab: 'prompt', label: 'Prompt 工程' },
  { legacy: 'LifeView', domain: 'npc', tab: 'list', label: '生命与人格' },
  { legacy: 'UnityBridgeView', domain: 'tools', tab: 'unity', label: 'Unity 对接' },
  { legacy: 'DecisionGateView', domain: 'agent', tab: 'decision', label: '决策模型 判定' },
  { legacy: 'N5CognitionView', domain: 'agent', tab: 'lod', label: '认知 LOD N5' },
  { legacy: 'UsageView', domain: 'ai', tab: 'usage', label: '用量' },
  { legacy: 'DbBrowserView', domain: 'data', tab: 'browse', label: 'DB 浏览' },
  { legacy: 'EventsView', domain: 'world', tab: 'events', label: '事件流' },
  { legacy: 'ConfigView', domain: 'config', tab: 'params', label: '配置' }
]

/**
 * 按域 key 取域定义。
 *
 * @param {string} key 域 key
 * @returns {object|undefined} 域定义
 */
export function findDevDomain(key) {
  return DEV_DOMAINS.find((d) => d.key === key)
}

/**
 * 按路由路径取域定义（含子路径匹配）。
 *
 * @param {string} path 当前路由 path
 * @returns {object|undefined} 域定义
 */
export function findDevDomainByPath(path) {
  if (!path) return undefined
  return DEV_DOMAINS.find((d) => path === d.path || path.startsWith(d.path + '/'))
}

/**
 * 参数域 key → 中文名（参数目录分组表头用）。
 *
 * @param {string} key 参数域 key（后端 domainOf 的返回值）
 * @returns {string} 中文名
 */
export function paramDomainName(key) {
  const map = {
    world: '世界引擎',
    npc: '角色与状态',
    agent: '决策与认知',
    memory: '记忆',
    ai: 'AI 与 Prompt',
    tools: '工具与对接',
    system: '系统与基础设施',
    unknown: '已失效参数'
  }
  return map[key] || key
}

/**
 * 生效方式 → 展示文案与标签类型。
 *
 * @param {string} effect immediate | restart
 * @returns {{text: string, type: string}} 文案 + el-tag 类型
 */
export function effectInfo(effect) {
  return effect === 'restart'
    ? { text: '需重启', type: 'warning' }
    : { text: '即时生效', type: 'success' }
}
