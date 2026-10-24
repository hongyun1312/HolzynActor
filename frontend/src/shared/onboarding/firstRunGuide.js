/*
 * Copyright (c) 2026 HONGYUN
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * 新用户 7 步引导状态（唯一入口）。
 *
 * <p><b>为什么需要这个模块</b>：引导状态此前由 FirstRunSetup / FirstRunGuide / TutorialCenterView
 * 三处各写一套 localStorage 键（注释里也写着「避免两处各写一套导致不同步」），且完成状态只存在本地。
 * 桌面端窗口地址是 `http://127.0.0.1:<随机端口>`，而 localStorage 按 origin（scheme+host+port）隔离——
 * 每次启动端口都变 → origin 变 → 本地标记全部失效 → 「已经跳过/看完的引导重启又出现」。
 *
 * <p><b>两层状态</b>：
 * <ol>
 *   <li>armed（待启动）：localStorage，短生命周期——完成首次向导 / 教程中心点「重看」后置位，
 *       引导启动时消费；</li>
 *   <li>completed（已完成或跳过）：后端本地账户 `actor_local_account.guide_completed`，
 *       跨重启、跨端口、跨版本稳定，是「是否还会再出现」的唯一权威。</li>
 * </ol>
 *
 * <p>约定：「完成」与「跳过」同义——都写 completed=1，都不再自动出现；
 * 只有教程中心显式「重看」才会 reset 并重新置位（replay 标记可越过 completed 判定）。</p>
 */

import { ref } from 'vue'
import { markFirstRunGuideCompleted, resetFirstRunGuideCompleted } from '@/shared/api/account'

/**
 * 引导是否正在展示（响应式）。
 * <p>供 AppShell 抑制「界面导览」（GuidedTour）自动播放：两套聚光灯同时出现会互相遮挡，
 * 且都挂在 body 上、z-index 相同，用户实际点不到下面那一层。</p>
 */
export const firstRunGuideActive = ref(false)

/** 待启动标记（首次向导完成 / 教程中心重看后置位；引导启动即清除） */
const ARMED_KEY = 'holzyn.firstRunGuide.pending.v1'
/** 显式「重看」标记（允许越过后端 completed 判定一次） */
const REPLAY_KEY = 'holzyn.firstRunGuide.replay.v1'
/** 本地完成标记（同 origin 快速兜底；后端不可用时也不至于反复弹出） */
const LOCAL_DONE_KEY = 'holzyn.firstRunGuide.done.v1'

function readLS(key) {
  try { return localStorage.getItem(key) } catch (_) { return null }
}
function writeLS(key, value) {
  try { localStorage.setItem(key, value) } catch (_) { /* 隐私模式静默 */ }
}
function removeLS(key) {
  try { localStorage.removeItem(key) } catch (_) { /* 隐私模式静默 */ }
}

/** 是否处于「待启动」状态（首次向导完成或点了重看） */
export function isGuideArmed() {
  return readLS(ARMED_KEY) === '1'
}

/** 是否本次是用户显式「重看」（可越过后端 completed） */
export function isGuideReplay() {
  return readLS(REPLAY_KEY) === '1'
}

/** 同一 origin 内的快速完成兜底 */
export function isGuideDoneLocally() {
  return readLS(LOCAL_DONE_KEY) === '1'
}

/**
 * 置位「待启动」。
 *
 * @param {{ replay?: boolean }} [options] replay=true 表示教程中心显式重看（越过后端已完成判定）
 */
export function armGuide(options = {}) {
  writeLS(ARMED_KEY, '1')
  if (options.replay) writeLS(REPLAY_KEY, '1')
  // 显式重看时必须清掉本地完成标记，否则 maybeStart 会被 isGuideDoneLocally 拦住
  if (options.replay) removeLS(LOCAL_DONE_KEY)
}

/** 清除「待启动」与「重看」标记（引导启动/结束时调用） */
export function disarmGuide() {
  removeLS(ARMED_KEY)
  removeLS(REPLAY_KEY)
}

/**
 * 是否应自动启动引导。
 *
 * @param {object|null|undefined} account 本地账户 VO（含 guideCompleted）
 * @returns {boolean}
 */
export function shouldStartGuide(account) {
  if (isGuideDoneLocally() && !isGuideReplay()) return false
  if (!isGuideArmed()) return false
  // 后端已完成 → 不再自动出现（防「向导被重复触发 → 又置位 → 引导再现」）；
  // 唯一的例外是用户主动点「重看」。
  if (account && Number(account.guideCompleted) === 1 && !isGuideReplay()) return false
  return true
}

/**
 * 完成/跳过引导：先清本地待启动，立刻写本地完成兜底，再尽力写后端权威。
 * <p>后端写失败不阻塞关闭（离线/后端重启场景）：本地标记保证本 origin 不再弹，
 * 下次成功启动引导时再补写。</p>
 *
 * @returns {Promise<void>}
 */
export async function completeGuide() {
  disarmGuide()
  writeLS(LOCAL_DONE_KEY, '1')
  try {
    await markFirstRunGuideCompleted()
  } catch (_) {
    /* 后端暂不可用：本地已兜底，不阻塞用户关闭引导 */
  }
}

/**
 * 重看引导：清后端完成标记 + 本地完成标记，再置位待启动（带 replay 标记）。
 *
 * @returns {Promise<void>}
 */
export async function replayGuide() {
  removeLS(LOCAL_DONE_KEY)
  try {
    await resetFirstRunGuideCompleted()
  } catch (_) {
    /* 失败时仍允许本地重看一次（replay 标记会越过 completed 判定） */
  }
  armGuide({ replay: true })
}
