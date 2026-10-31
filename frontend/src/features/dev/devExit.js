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
 * 退出开发者模式（开发者页「返回」/「隐藏开发者入口」的唯一出口）。
 *
 * <p><b>为什么需要一处统一出口</b>：开发者控制台有三种宿主——桌面端独立窗口、
 * 浏览器新标签页、当前标签页内跳转。三者「返回」的正确落点并不相同：</p>
 * <ul>
 *   <li>桌面端独立窗口：主窗口本来就显示着世界，这个窗口再跳进世界 = 两个一模一样的世界界面。
 *       正确做法是<b>关掉本窗口</b>并回到主窗口；</li>
 *   <li>浏览器新标签页（直接以 /dev 打开、没有历史）：跳回世界同样会多出一份界面，
 *       因此退回世界画廊，并抹掉「画廊 → 上次世界」的一次性自动直达；</li>
 *   <li>当前标签页内跳转：原路回到进入开发者页之前的页面（同一标签页，不会多出窗口）。</li>
 * </ul>
 *
 * <p>历史落点 `router.replace('/settings')` 的问题：设置页顶栏的返回会回世界画廊，
 * 而画廊守卫对「世界还开着」有一次自动直达重定向——于是「返回开发者页」最终变成了
 * 「进入世界界面」，在独立窗口里就是两个相同的界面。</p>
 *
 * @param {import('vue-router').Router} router vue-router 实例
 */

import { isDesktopShell } from '@/shared/composables/useDevMode'
import { clearDevEntry, devEntry, isStandaloneDev, markSkipStartupRedirect } from './devContext'

export function exitDevMode(router) {
  const entry = devEntry()
  clearDevEntry()

  // ① 桌面端：开发者控制台是独立窗口，主窗口已持有世界界面 → 直接关窗，绝不在此窗口再开一份
  if (isDesktopShell() && window.holzynDesktop?.dev?.close) {
    try {
      window.holzynDesktop.dev.close()
    } catch (_) { /* 关窗失败不阻塞：下面按浏览器路径兜底 */ }
    return
  }

  // ② 独立窗口/标签（以自己的 /dev 地址作为首个历史项打开）：回画廊，且不要被自动带进世界
  if (isStandaloneDev() || !entry || entry.startsWith('/dev')) {
    markSkipStartupRedirect()
    router.replace('/')
    return
  }

  // ③ 当前标签页内跳转：原路退回进入前的页面
  if (entry === '/' || entry.startsWith('/?')) {
    markSkipStartupRedirect()
  }
  router.replace(entry)
}
