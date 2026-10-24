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
// HolzynActor · 主题系统（双主题分层 + 可换肤主色 + 世界题材联动）
// 职责：
// - 深浅模式：system / light / dark（跟随系统或手动）
// - 主色：个性化预设/自由取色，默认 DeepSeek 蓝
// - 世界题材联动：进入项目按世界观题材自动换主色（恋爱→粉、科幻→蓝青…），可覆盖
// - 持久化：localStorage
// 应用方式：documentElement.data-theme（深浅）+ documentElement.style.--accent（主色）
//
import { ref, watch, computed } from 'vue'

const THEME_KEY = 'holzyn-actor.theme.v1'

/** 预设主色（个性化色板） */
export const THEME_PRESETS = [
  { id: 'deepseek', label: 'DeepSeek 蓝', color: '#4176e6' },
  { id: 'cyan', label: '星云青', color: '#0ea5b7' },
  { id: 'violet', label: '紫罗兰', color: '#8b5cf6' },
  { id: 'pink', label: '恋爱粉', color: '#ec4899' },
  { id: 'rose', label: '玫瑰红', color: '#e11d48' },
  { id: 'green', label: '竹林绿', color: '#10b981' },
  { id: 'teal', label: '都市青绿', color: '#14b8a6' },
  { id: 'amber', label: '历史琥珀', color: '#d97706' },
  { id: 'red', label: '悬疑暗红', color: '#b91c1c' },
  { id: 'indigo', label: '霓虹靛', color: '#6366f1' }
]

/** 世界题材 → 主色（题材联动） */
export const GENRE_THEMES = {
  '恋爱': '#ec4899',
  '言情': '#ec4899',
  '科幻': '#0ea5b7',
  '赛博朋克': '#06b6d4',
  '奇幻': '#8b5cf6',
  '魔法': '#8b5cf6',
  '都市': '#14b8a6',
  '现代': '#14b8a6',
  '历史': '#d97706',
  '古风': '#b45309',
  '悬疑': '#b91c1c',
  '恐怖': '#7f1d1d',
  '武侠': '#10b981',
  '仙侠': '#0d9488',
  '末日': '#64748b'
}
export const DEFAULT_ACCENT = '#4176e6'

/** 读取持久化主题 */
function load() {
  try {
    const raw = localStorage.getItem(THEME_KEY)
    if (raw) return { ...defaults(), ...JSON.parse(raw) }
  } catch (_) { /* 忽略损坏数据 */ }
  return defaults()
}

/** 默认主题状态 */
function defaults() {
  return {
    mode: 'system',          // system | light | dark
    accent: DEFAULT_ACCENT,  // 用户默认主色
    genreFollow: true,       // 是否跟随世界题材换肤
    overrides: {},           // { [projectId]: hex } 项目级主色覆盖
    // 云间列车液态玻璃背景偏好（全站常驻背景）
    cloudTrain: {
      enabled: true,         // 是否渲染云间列车动态背景（关闭后为纯色背景，玻璃材质保留）
      resolution: 0.25       // 渲染分辨率（0.25~1；默认取最低档，对新用户/低端 GPU 更友好，可在设置页调高）
    }
  }
}

/** 当前主题状态（响应式，跨组件共享） */
const theme = ref(load())

/** 当前所在项目的题材（由 ProjectLayout 设置） */
const currentGenre = ref(null)

/** 当前所在项目的 id（由 ProjectLayout 设置） */
const currentProjectId = ref(null)

/** 计算当前生效的主色：项目覆盖 > 题材联动 > 用户默认 */
const effectiveAccent = computed(() => {
  if (currentProjectId.value && theme.value.overrides[currentProjectId.value]) {
    return theme.value.overrides[currentProjectId.value]
  }
  if (theme.value.genreFollow && currentGenre.value && GENRE_THEMES[currentGenre.value]) {
    return GENRE_THEMES[currentGenre.value]
  }
  return theme.value.accent
})

/** 将主题状态应用到 DOM（html[data-theme] + --accent） */
function apply() {
  const root = document.documentElement
  // 深浅模式
  const dark = theme.value.mode === 'dark'
    || (theme.value.mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  if (dark) root.setAttribute('data-theme', 'dark')
  else root.removeAttribute('data-theme')
  // 主色
  root.style.setProperty('--accent', effectiveAccent.value)
}

/** 持久化 */
function persist() {
  try { localStorage.setItem(THEME_KEY, JSON.stringify(theme.value)) } catch (_) { /* 忽略 */ }
}

// 主题变化时应用 + 持久化
watch(theme, () => { apply(); persist() }, { deep: true })

/**
 * 主题 composable。
 */
export function useTheme() {
  /** 设置深浅模式 */
  function setMode(mode) { theme.value.mode = mode }

  /** 快速切换深浅（顶栏按钮） */
  function toggleMode() {
    const isDark = document.documentElement.hasAttribute('data-theme')
    theme.value.mode = isDark ? 'light' : 'dark'
  }

  /** 设置默认主色（个性化） */
  function setAccent(color) { theme.value.accent = color }

  /** 开关题材联动 */
  function setGenreFollow(v) { theme.value.genreFollow = !!v }

  /** 设置某项目主色覆盖 */
  function setProjectOverride(projectId, color) {
    theme.value.overrides = { ...theme.value.overrides, [String(projectId)]: color }
  }

  /** 移除某项目主色覆盖 */
  function removeProjectOverride(projectId) {
    const next = { ...theme.value.overrides }
    delete next[String(projectId)]
    theme.value.overrides = next
  }

  /** 开关云间列车动态背景（关闭后玻璃材质保留，背景为纯色） */
  function setCloudTrainEnabled(v) {
    theme.value.cloudTrain = { ...(theme.value.cloudTrain || {}), enabled: !!v }
  }

  /** 设置云间列车渲染分辨率（0.25~1；调低更省性能） */
  function setCloudTrainResolution(v) {
    const n = Number(v)
    theme.value.cloudTrain = { ...(theme.value.cloudTrain || {}), resolution: Number.isFinite(n) ? Math.min(1, Math.max(0.25, n)) : 0.25 }
  }

  /** 进入项目：记录项目 id 与题材（触发题材联动） */
  function setProjectContext(projectId, genre) {
    currentProjectId.value = projectId
    currentGenre.value = genre || null
    apply()
  }

  /** 离开项目：清除上下文 */
  function clearProjectContext() {
    currentProjectId.value = null
    currentGenre.value = null
    apply()
  }

  /** 立即应用（启动时调用） */
  function mount() {
    apply()
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener?.('change', () => { if (theme.value.mode === 'system') apply() })
  }

  return {
    theme, effectiveAccent, setMode, toggleMode, setAccent,
    setGenreFollow, setProjectOverride, removeProjectOverride,
    setCloudTrainEnabled, setCloudTrainResolution,
    setProjectContext, clearProjectContext, mount
  }
}

/** 供 main.js 启动时一次性初始化（无组件环境） */
export function initTheme() {
  apply()
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  mq.addEventListener?.('change', () => { if (theme.value.mode === 'system') apply() })
}
