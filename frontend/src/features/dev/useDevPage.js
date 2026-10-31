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
 * 开发者页统一取数逻辑（9 个域共用）。
 *
 * <p><b>为什么抽出来</b>：每个域页都要做同一件事——挂载时解析当前世界 → 拉数据 →
 * 支持「刷新」信号 → 支持「实时跟随」定时器 → 卸载时停表 → 失败给可见错误。
 * 九份复制会让「实时跟随在某个页忘了停」这种泄漏无法统一修复。</p>
 *
 * <p><b>失败策略</b>：错误写进 state.error 并在页面上显示可重试提示，不弹全局 toast——
 * 开发者在排查「后端挂了」时，最不需要的就是一串自己会消失的弹窗。</p>
 */

import { onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import { devContext, resolveDevWorld } from './devContext'

/** 实时跟随的轮询间隔（毫秒）：1~2 秒是用户确认过的档位，再快只会打满后端日志 */
export const DEV_POLL_MS = 2000

/**
 * 让出一帧：先让骨架/页头画出来，再开始取数与建表。
 *
 * <p>为什么需要它：参数目录页有数百行、每行一个可编辑输入框，若 onMounted 里直接
 * `await load()`，浏览器要在同一帧内解析响应 + 建几百个表格行，用户看到的就是
 * 「点进配置页先卡一下」。用 requestIdleCallback（带 400ms 兜底超时）把取数挪到
 * 首帧之后：空闲就立刻跑，主线程忙也最多等这么久，不会无限推迟。</p>
 *
 * @returns {Promise<void>} 空闲帧到达即 resolve
 */
function yieldFrame() {
  return new Promise((resolve) => {
    if (typeof window !== 'undefined' && typeof window.requestIdleCallback === 'function') {
      window.requestIdleCallback(() => resolve(), { timeout: 400 })
      return
    }
    setTimeout(resolve, 16)
  })
}

/**
 * 注册一个开发者页的取数流程。
 *
 * @param {(ctx: {worldId: number|null}) => Promise<any>} loader 取数函数（返回该页需要的整个对象，或分块对象）
 * @param {object} [options] 配置
 * @param {boolean} [options.requireWorld=true] 是否要求已挂载世界（false 时 worldId 可能为 null）
 * @param {number} [options.intervalMs] 实时跟随间隔，默认 {@link DEV_POLL_MS}
 * @param {boolean} [options.defer=false] 首帧画完再取数（大目录页用：进入不卡，先出骨架）
 * @returns {{state: object, load: Function, reloadAfter: Function}} 页面状态与手动触发
 */
export function useDevPage(loader, options = {}) {
  const requireWorld = options.requireWorld !== false
  const intervalMs = options.intervalMs || DEV_POLL_MS
  const deferFirstLoad = options.defer === true

  const state = reactive({
    /** 首屏加载中（只有首屏才显示骨架，轮询刷新不闪） */
    loading: true,
    /** 后台刷新中 */
    refreshing: false,
    /** 错误文案（空=无错） */
    error: '',
    /** 取数结果 */
    data: null,
    /** 最近一次成功时间（HH:mm:ss） */
    lastAt: ''
  })

  let timer = null
  let disposed = false

  /**
   * 执行一次取数。
   *
   * @param {boolean} [silent=false] true=后台刷新（保留旧数据，不显示 loading）
   * @returns {Promise<void>} 完成即返回
   */
  async function load(silent = false) {
    if (disposed) return
    if (silent) state.refreshing = true
    else state.loading = true
    try {
      if (!devContext.resolved) await resolveDevWorld()
      if (requireWorld && !devContext.worldId) {
        state.data = null
        state.error = devContext.error || '当前没有挂载的世界：先打开一个世界，再回到本页查看数据。'
        return
      }
      const result = await loader({ worldId: devContext.worldId })
      if (disposed) return
      state.data = result
      state.error = ''
      state.lastAt = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    } catch (e) {
      if (disposed) return
      state.error = e?.response?.data?.message || e?.message || '请求失败'
    } finally {
      if (!disposed) {
        state.loading = false
        state.refreshing = false
      }
    }
  }

  /** 按当前开关同步定时器（关闭即清表） */
  function syncTimer() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    if (devContext.realtime && !disposed) {
      timer = setInterval(() => { load(true) }, intervalMs)
    }
  }

  // 顶栏「刷新」按钮 → 全部页面重拉
  watch(() => devContext.refreshTick, () => { load(!!state.data) })
  // 实时跟随开关 → 启停定时器
  watch(() => devContext.realtime, syncTimer)

  onMounted(async () => {
    if (deferFirstLoad) await yieldFrame()
    await load()
    syncTimer()
  })

  onBeforeUnmount(() => {
    disposed = true
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  })

  return { state, load, reloadAfter: () => load(!!state.data) }
}

/**
 * 通用表格时间格式化（后端返回 epoch 秒/毫秒或 ISO 字符串都兼容）。
 *
 * @param {*} value 原始值
 * @returns {string} 本地时间文本（无法解析返回 '—'）
 */
export function formatTime(value) {
  if (value == null || value === '') return '—'
  let d
  if (typeof value === 'number') d = new Date(value > 1e12 ? value : value * 1000)
  else if (/^\d+$/.test(String(value))) d = new Date(Number(value) > 1e12 ? Number(value) : Number(value) * 1000)
  else d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', { hour12: false })
}

/**
 * 金额/数字的紧凑展示（12345 → 12.3k），表格里避免长数字撑破列宽。
 *
 * @param {*} value 数值
 * @param {number} [digits=1] 小数位
 * @returns {string} 展示文本
 */
export function compactNumber(value, digits = 1) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  const abs = Math.abs(n)
  if (abs >= 1e9) return `${(n / 1e9).toFixed(digits)}B`
  if (abs >= 1e6) return `${(n / 1e6).toFixed(digits)}M`
  if (abs >= 1e4) return `${(n / 1e3).toFixed(digits)}k`
  return String(n)
}
