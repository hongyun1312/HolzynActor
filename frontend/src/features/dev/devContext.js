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
 * 开发者控制台的运行上下文（全局单例）。
 *
 * <p><b>为什么要单例</b>：9 个域页都要知道「当前看的是哪个世界」「实时跟随开没开」。
 * 每个页面各查一次 `/api/worlds/current` 会得到 9 次重复请求与 9 份可能不一致的状态
 * （切世界时就地漂移）。这里把「世界上下文」收敛成一份，页面只订阅。</p>
 *
 * <p><b>实时跟随默认关闭</b>：世界默认静止（auto-resume=false），观测页面没有理由持续轮询。
 * 打开后由各页面自己的定时器按 1~2 秒刷新，切走页面即停——避免开发者页在后台空转消耗 CPU 与数据库连接。</p>
 */

import { reactive } from 'vue'
import { fetchCurrentWorld } from '@/shared/api'

/** 开发者控制台上下文 */
export const devContext = reactive({
  /** 当前挂载的世界 ID（null=未挂载） */
  worldId: null,
  /** 世界名 */
  worldName: '',
  /** 后端报告的挂载状态 */
  mounted: false,
  /** 是否已尝试解析过（避免重复请求） */
  resolved: false,
  /** 解析中 */
  loading: false,
  /** 解析错误信息 */
  error: '',
  /** 实时跟随开关（页面级定时器据此启停） */
  realtime: false,
  /**
   * 手动刷新信号：顶栏「刷新」按钮自增，页面 watch 到变化就重新拉数据。
   * <p>为什么不用 window.location.reload：刷新整页会丢掉当前 Tab、滚动位置与筛选条件，
   * 对一个「正在对照日志排查问题」的页面是不可接受的。</p>
   */
  refreshTick: 0
})

/**
 * 解析当前挂载的世界（幂等；force=true 时强制重查）。
 *
 * @param {boolean} [force=false] 是否强制重查
 * @returns {Promise<number|null>} 世界 ID（未挂载为 null）
 */
export async function resolveDevWorld(force = false) {
  if (devContext.resolved && !force) {
    return devContext.worldId
  }
  devContext.loading = true
  devContext.error = ''
  try {
    const current = await fetchCurrentWorld()
    devContext.worldId = current && current.id ? Number(current.id) : null
    devContext.worldName = (current && (current.name || current.worldName)) || ''
    devContext.mounted = !!(current && current.id && current.mounted !== false)
  } catch (e) {
    devContext.worldId = null
    devContext.worldName = ''
    devContext.mounted = false
    devContext.error = e?.message || '无法读取当前世界（后端未就绪？）'
  } finally {
    devContext.loading = false
    devContext.resolved = true
  }
  return devContext.worldId
}

/**
 * 切换实时跟随。
 *
 * @param {boolean} [value] 目标值（不传则取反）
 * @returns {boolean} 切换后的值
 */
export function setRealtime(value) {
  devContext.realtime = typeof value === 'boolean' ? value : !devContext.realtime
  return devContext.realtime
}

/**
 * 页面卸载时统一停掉实时跟随（避免离开页面后仍在轮询）。
 */
export function stopRealtime() {
  devContext.realtime = false
}

/**
 * 请求当前页刷新一次（顶栏刷新按钮 / 参数保存后回灌数据都走这里）。
 */
export function requestRefresh() {
  devContext.refreshTick += 1
}

/* ==================== 退出开发者模式的落点（防「返回后多出一个世界界面」） ==================== */

/** 进入开发者页前所在页面的 sessionStorage 键 */
const ENTRY_KEY = 'holzyn.dev.entry'

/** 「回到画廊时不要再被上次世界自动带走」的一次性标记键 */
const SKIP_REDIRECT_KEY = 'holzyn.dev.skip-startup-redirect'

/** 「本窗口是以 /dev 作为首个历史项打开的独立窗口」标记键（sessionStorage 按标签页隔离） */
const STANDALONE_KEY = 'holzyn.dev.standalone-window'

/**
 * 记录进入开发者模式前的页面（退出时原路返回用）。
 *
 * <p>为什么记绝对路径而不是靠 `router.back()`：开发者页可能是由快捷键从世界内进入的，
 * 也可能由设置页按钮进入；`history.back()` 在「开发者页被重复打开/刷新」后会把用户带到
 * 意料之外的历史项（实测会退回到上一次的 /dev 子页）。记一个明确的落点更可控。</p>
 *
 * @param {string} fullPath 进入前的路由 fullPath
 */
export function markDevEntry(fullPath) {
  if (!fullPath || String(fullPath).startsWith('/dev')) return
  try { window.sessionStorage.setItem(ENTRY_KEY, String(fullPath)) } catch (_) { /* 隐私模式忽略 */ }
}

/**
 * 读取进入开发者模式前的页面。
 *
 * @returns {string} 路由 fullPath（未记录时为空串）
 */
export function devEntry() {
  try { return window.sessionStorage.getItem(ENTRY_KEY) || '' } catch (_) { return '' }
}

/** 清除进入点记录（退出开发者模式后调用） */
export function clearDevEntry() {
  try { window.sessionStorage.removeItem(ENTRY_KEY) } catch (_) { /* 忽略 */ }
}

/**
 * 标记「下一次回到画廊不要被自动带进上次的世界」。
 *
 * <p>根因：路由守卫对 `/` 有一次「直达上次所在项目」的重定向（单活动世界模型下
 * 「世界还开着」是唯一的自动直达理由）。从开发者页退出时世界通常仍是挂载状态，
 * 于是「返回」会被这次重定向直接送进世界——用户看到的就是「点了返回，反而进了世界界面」，
 * 在开发者独立窗口里更会与主窗口形成两个一模一样的界面。</p>
 */
export function markSkipStartupRedirect() {
  try { window.sessionStorage.setItem(SKIP_REDIRECT_KEY, '1') } catch (_) { /* 忽略 */ }
}

/**
 * 消费一次性标记：返回 true 表示本次「画廊直达世界」应被跳过。
 *
 * @returns {boolean} 是否跳过
 */
export function consumeSkipStartupRedirect() {
  try {
    if (window.sessionStorage.getItem(SKIP_REDIRECT_KEY) !== '1') return false
    window.sessionStorage.removeItem(SKIP_REDIRECT_KEY)
    return true
  } catch (_) {
    return false
  }
}

/**
 * 标记本窗口是否为「独立开发者窗口/标签页」。
 *
 * <p>判据由调用方给（DevShell 用 `history.length`）：以 /dev 地址直接打开的窗口只有一条历史，
 * 而「从世界页跳进开发者页」的同一标签页至少有两条。sessionStorage 按标签页隔离，
 * 因此这个标记只影响当前窗口。</p>
 *
 * @param {boolean} standalone 是否独立窗口
 */
export function markStandaloneDev(standalone) {
  try { window.sessionStorage.setItem(STANDALONE_KEY, standalone ? '1' : '0') } catch (_) { /* 忽略 */ }
}

/**
 * 本窗口是否是独立开发者窗口/标签页。
 *
 * @returns {boolean} 独立窗口
 */
export function isStandaloneDev() {
  try { return window.sessionStorage.getItem(STANDALONE_KEY) === '1' } catch (_) { return false }
}

/**
 * 当前操作人（本地账户名；取不到时由后端回退 "local"）。
 *
 * @returns {string} 操作人
 */
export function devOperator() {
  try {
    const raw = window.localStorage.getItem('holzyn.account')
    if (raw) {
      const obj = JSON.parse(raw)
      if (obj && (obj.nickname || obj.username || obj.name)) {
        return String(obj.nickname || obj.username || obj.name)
      }
    }
  } catch (_) {
    /* 解析失败按匿名处理 */
  }
  return 'local'
}
