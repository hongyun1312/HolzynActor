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
 * 开发者模式解锁与入口（全局单例）。
 *
 * <p><b>设计要点</b>：</p>
 * <ol>
 *   <li><b>5 连击</b>：3 秒内连续点击「设置 → 更新与版本 → 当前版本号」5 次解锁；第 4 次给一次
 *       「再点击 1 次」提示（前 3 次完全无反馈——有反馈就等于告诉所有人这里藏着东西）。</li>
 *   <li><b>持久化</b>：解锁状态写 localStorage（跨重启保留）；页面内「重新隐藏」清掉。</li>
 *   <li><b>打开方式</b>：桌面端走 Electron IPC 开**独立窗口**；浏览器（开发/调试）退化为
 *       当前标签页跳转 `/dev`——同一套页面，两种宿主。</li>
 *   <li><b>快捷键</b>：`Ctrl+Shift+D` 在应用内可直接唤起（不注册系统级全局快捷键，避免抢占别的软件）。</li>
 * </ol>
 *
 * <p><b>这不是安全边界</b>：本地单用户产品，隐藏入口只是「别让普通玩家误入危险区」，
 * 不承担鉴权职责（与后端 /api/debug 口径一致）。真正的保护是每次写操作的二次确认与审计。</p>
 */

import { computed, ref } from 'vue'

/** localStorage 键（版本化：改语义时换键，避免读到旧状态） */
const UNLOCK_KEY = 'holzyn.dev.unlocked'

/** 连击窗口（毫秒）：超过这个间隔视为重新开始计数 */
const CLICK_WINDOW_MS = 3000

/** 需要点击的次数 */
const REQUIRED_CLICKS = 5

/** 全局单例状态（模块级 ref，跨组件共享） */
const unlocked = ref(readUnlocked())
const clickCount = ref(0)
const lastClickAt = ref(0)

/**
 * 读取持久化解锁状态。
 *
 * @returns {boolean} 是否已解锁
 */
function readUnlocked() {
  try {
    return window.localStorage.getItem(UNLOCK_KEY) === '1'
  } catch (_) {
    return false
  }
}

/**
 * 是否桌面端（有 preload 桥）。
 *
 * @returns {boolean} true=Electron 桌面端
 */
export function isDesktopShell() {
  return !!(typeof window !== 'undefined' && window.holzynDesktop)
}

/**
 * 开发者模式组合式函数。
 *
 * @returns {object} { unlocked, clickCount, onVersionClick, lock, openDevConsole, openDevWindow }
 */
export function useDevMode() {
  const remaining = computed(() => Math.max(0, REQUIRED_CLICKS - clickCount.value))

  /**
   * 版本号点击回调（5 连击计数）。
   *
   * @returns {'idle'|'progress'|'unlocked'} 本次点击后的状态
   */
  function onVersionClick() {
    if (unlocked.value) {
      return 'unlocked'
    }
    const now = Date.now()
    if (now - lastClickAt.value > CLICK_WINDOW_MS) {
      clickCount.value = 0
    }
    lastClickAt.value = now
    clickCount.value += 1
    if (clickCount.value >= REQUIRED_CLICKS) {
      clickCount.value = 0
      unlocked.value = true
      try {
        window.localStorage.setItem(UNLOCK_KEY, '1')
      } catch (_) { /* 隐私模式写不进 localStorage：本次会话内仍然可用 */ }
      return 'unlocked'
    }
    // 只在最后两次给提示（其余静默）
    if (clickCount.value >= REQUIRED_CLICKS - 2) {
      return 'progress'
    }
    return 'idle'
  }

  /** 重新隐藏开发者模式（清 localStorage + 计数） */
  function lock() {
    unlocked.value = false
    clickCount.value = 0
    try {
      window.localStorage.removeItem(UNLOCK_KEY)
    } catch (_) { /* 忽略 */ }
  }

  /**
   * 打开开发者控制台（桌面端=独立窗口；浏览器=当前标签跳转）。
   *
   * @param {object} router vue-router 实例
   * @param {string} [route='/dev/overview'] 目标路由
   * @returns {Promise<boolean>} 是否已发起打开
   */
  async function openDevConsole(router, route = '/dev/overview') {
    if (isDesktopShell() && window.holzynDesktop.dev?.open) {
      const res = await window.holzynDesktop.dev.open(route).catch(() => null)
      if (res && res.ok !== false) {
        return true
      }
    }
    if (router) {
      router.push(route)
      return true
    }
    window.open(route, '_blank', 'noopener')
    return true
  }

  /**
   * 仅用浏览器窗口打开（不依赖路由实例）。
   *
   * @param {string} [route='/dev/overview'] 目标路由
   */
  function openDevWindow(route = '/dev/overview') {
    window.open(route, '_blank', 'noopener')
  }

  return { unlocked, clickCount, remaining, onVersionClick, lock, openDevConsole, openDevWindow }
}

/**
 * 当前是否已解锁（供路由守卫直接读取，不依赖组合式上下文）。
 *
 * @returns {boolean} 是否已解锁
 */
export function isDevUnlocked() {
  return unlocked.value
}

/** 解锁状态响应式引用（路由守卫/组件共用） */
export const devUnlocked = unlocked
