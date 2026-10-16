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

// ==================== 世界时钟前端实时化共享工具 ====================
// 职责：与后端「单一连续权威 + 引擎高频追平」时间模型对齐的前端本地插值与格式化纯函数，
// 世界地图首页与总览仪表盘（DashboardView）共用。
// 核心协议：
// 流动时钟插值：gameSecond_now = anchorGameSecond + (now - refRealTime)/1000 × rate，暂停冻结不跳；
// 引擎位置插值：engine_now = engineGameSecond + min((now - engineRefRealTime)/1000 × rate,
// engineTickGameSeconds) —— 世界时钟显示「世界的实际时间」（引擎推进位置），AI 慢/引擎停顿不虚快；
// 显示格式：完整世界历到分「XX历 YYYY年MM月DD日 HH时MM分」（与后端 formatWorldTimeMinute 同源）；
// 历法名： 从 SSE clock 载荷的完整 gameTimeText（「XX历 YYYY年…」）解析前缀，避免后端加字段。

/** 1 游戏日 = 24 小时（秒）；与后端 WorldClockService.SECONDS_PER_DAY 同源 */
const SECONDS_PER_DAY = 86400
/** 1 游戏月 = 30 日 */
const SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY
/** 1 游戏年 = 12 月 = 360 日 */
const SECONDS_PER_YEAR = 12 * SECONDS_PER_MONTH

/** 缺省历法名（无解析结果/无文本时使用） */
const DEFAULT_CALENDAR = '世界历'

/**
 * 本地插值当前游戏秒：anchor + (now - refRealTime)/1000 × rate。
 * <p>暂停（paused=1）→ 冻结在锚点值不插值；无锚点/速率非法 → 返回锚点值（静态）。
 * 结果向下取整（对齐后端秒级位置语义，避免小数漂移）。</p>
 *
 * @param {number} anchorGameSecond 锚点游戏秒（SSE clock 的 gameSecond）
 *
 * @param {number} refRealTime 锚点对应真实毫秒（SSE clock 的 refRealTime）
 *
 * @param {number} rate 速率（每真实小时推进的游戏小时数，默认 24）
 *
 * @param {number|boolean} paused 暂停开关（1/0 或 true/false）
 *
 * @param {number} [now] 当前真实毫秒（缺省 Date.now()；测试可注入）
 *
 * @returns {number} 当前游戏秒（>=0）
 */
export function gameSecondNow(anchorGameSecond, refRealTime, rate, paused, now) {
  const base = Math.max(0, Math.floor(Number(anchorGameSecond) || 0))
  if (Number(paused)) return base
  const r = Number(rate) || 0
  // 锚点缺失（null/undefined/NaN）或速率为 0 时按静态兜底；
  // ⚠️ Number(null)===0，须先判断原始值再转换；ref=0 是合法毫秒时间戳（1970 纪元）仍参与插值
  if (refRealTime == null || Number.isNaN(Number(refRealTime)) || Number(refRealTime) < 0 || r <= 0) return base
  const ref = Number(refRealTime)
  const t = (now ?? Date.now()) - ref
  if (t <= 0) return base
  return base + Math.floor((t / 1000) * r)
}

/**
 * 引擎实际位置插值当前游戏秒：
 * <p>语义（对齐 「世界时钟要显示世界的实际时间，而不是换算时间」）：
 * 引擎按感知 tick 离散推进（默认 5 游戏分钟=300 游戏秒，扫描 12.5 真实秒）——AI 生成慢/引擎停顿
 * 时流动时钟换算时间会虚快，引擎位置则如实反映世界已推进到的时刻。前端插值钳制在引擎位置 +1 个
 * 感知 tick 内（engineTickGameSeconds）：单个扫描周期内平滑推进，超出即冻结等待下一次引擎推进；
 * 暂停（paused=1）→ 冻结在引擎位置不插值。</p>
 *
 * @param {number} engineGameSecond 引擎实际推进位置（SSE clock 的 engineGameSecond）
 *
 * @param {number} engineRefRealTime 引擎位置插值锚点真实毫秒（SSE clock 的 engineRefRealTime）
 *
 * @param {number} rate 速率（每真实小时推进的游戏小时数，默认 24）
 *
 * @param {number|boolean} paused 暂停开关（1/0 或 true/false）
 *
 * @param {number} [engineTickGameSeconds] 插值钳制值（游戏秒，缺省 300=5 游戏分钟）
 *
 * @param {number} [now] 当前真实毫秒（缺省 Date.now()；测试可注入）
 *
 * @returns {number} 当前引擎位置游戏秒（>=0；字段缺失回退旧流动时钟插值语义由调用方处理）
 */
export function engineSecondNow(engineGameSecond, engineRefRealTime, rate, paused, engineTickGameSeconds, now) {
  const base = Math.max(0, Math.floor(Number(engineGameSecond) || 0))
  if (Number(paused)) return base
  const r = Number(rate) || 0
  if (engineRefRealTime == null || Number.isNaN(Number(engineRefRealTime)) || Number(engineRefRealTime) < 0 || r <= 0) return base
  const ref = Number(engineRefRealTime)
  const t = (now ?? Date.now()) - ref
  if (t <= 0) return base
  const tick = Number(engineTickGameSeconds) > 0 ? Number(engineTickGameSeconds) : 300
  const elapsed = Math.floor((t / 1000) * r)
  return base + Math.min(elapsed, tick)
}

/**
 * 世界历完整时间文本（到分）：「XX历 YYYY年MM月DD日 HH时MM分」。
 * <p>历法：1 日 = 24 时，1 月 = 30 日，1 年 = 12 月 = 360 日；gameSecond=0 → 0001年01月01日 00时00分。
 * 与后端 WorldClockService.formatWorldTimeMinute 同源（双端拼装结果一致），供前端本地实时拼装。</p>
 *
 * @param {number} gameSecond 自纪元起的游戏总秒数（负数按 0）
 *
 * @param {string} [calendarName] 历法名（如「青禾村历」；缺省「世界历」）
 *
 * @returns {string} 世界历到分文本
 */
export function formatWorldTimeMinute(gameSecond, calendarName) {
  const gs = Math.max(0, Math.floor(Number(gameSecond) || 0))
  const year = Math.floor(gs / SECONDS_PER_YEAR) + 1
  const month = Math.floor(gs / SECONDS_PER_MONTH) % 12 + 1
  const day = Math.floor(gs / SECONDS_PER_DAY) % 30 + 1
  const hour = Math.floor(gs / 3600) % 24
  const min = Math.floor(gs / 60) % 60
  const cal = (calendarName && String(calendarName).trim()) || DEFAULT_CALENDAR
  const p = (n) => String(n).padStart(2, '0')
  return `${cal} ${String(year).padStart(4, '0')}年${p(month)}月${p(day)}日 ${p(hour)}时${p(min)}分`
}

/**
 * 紧凑格式（兜底/无历法名时）：「第X日 HH:MM」。
 *
 * @param {number} gameSecond 自纪元起的游戏总秒数（负数按 0）
 *
 * @returns {string} 紧凑文本
 */
export function formatCompactDayTime(gameSecond) {
  const gs = Math.max(0, Math.floor(Number(gameSecond) || 0))
  const day = Math.floor(gs / SECONDS_PER_DAY) + 1
  const hour = Math.floor(gs / 3600) % 24
  const min = Math.floor(gs / 60) % 60
  const p = (n) => String(n).padStart(2, '0')
  return `第${day}日 ${p(hour)}:${p(min)}`
}

/**
 * 历法名解析：从 SSE clock 载荷的完整 gameTimeText（「XX历 YYYY年MM月DD日 …」）解析前缀。
 * <p>例：「青禾村历 0003年05月12日 14时00分」→「青禾村历」；无空格分隔时原样返回；
 * 空文本返回 null（由调用方回退紧凑格式）。</p>
 *
 * @param {string} gameTimeText 完整世界历文本（后端 gameTimeText 字段，可空）
 *
 * @returns {string|null} 历法名（如「青禾村历」）；不可解析返回 null
 */
export function calendarNameOf(gameTimeText) {
  if (!gameTimeText) return null
  const idx = String(gameTimeText).indexOf(' ')
  const prefix = (idx > 0 ? String(gameTimeText).slice(0, idx) : String(gameTimeText)).trim()
  return prefix || null
}
