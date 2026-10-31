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
// 世界会话（单活动世界）通用动作：离开世界 / 切到另一个世界。
//
// 为什么需要这一层：分档存储后世界库被挂载进程独占，**"离开世界"不再是纯前端跳转**——
// 必须先 POST /api/worlds/{id}/close 让后端压盘、同步镜像、回写摘要，再回世界画廊。
// 之前这类"回画廊"入口散落在顶栏返回、项目切换器、设置页删除等处，各写一遍必然漏。
// 本模块只做最小封装，不持有状态。
//
import { closeWorld, fetchCurrentWorld, openWorld } from '@/shared/api'
import { resetEnsuredWorld } from '@/shared/router'

/**
 * 确保指定世界处于挂载状态（单活动世界：需要时先关掉当前挂载的世界）。
 *
 * <p>用途：新建世界后要立刻写世界库（世界观设定、初始化流程），但后端 {@code POST /api/worlds}
 * 只在"没有世界挂载"时自动挂载——已有世界挂着时它会返回 {@code opened=false}，
 * 此时若直接调世界内接口，要么 409，要么把新世界的数据写进旧世界库（foreign row）。
 * 所以创建后的调用点必须先走这里。</p>
 *
 * @param {number} worldId 目标世界 id
 * @returns {Promise<{opened: boolean, closedPrevious: number|null}>}
 */
export async function ensureWorldOpen(worldId) {
  let current = null
  try {
    current = await fetchCurrentWorld()
  } catch (_) {
    current = null
  }
  if (current && current.id === worldId) {
    return { opened: false, closedPrevious: null }
  }
  let closedPrevious = null
  if (current && current.id) {
    await closeWorld(current.id)
    closedPrevious = current.id
  }
  try {
    await openWorld(worldId)
  } finally {
    resetEnsuredWorld()
  }
  return { opened: true, closedPrevious }
}

/**
 * 关闭当前挂载的世界（若有），并让路由守卫的"已挂载"缓存失效。
 *
 * @returns {Promise<{closed: boolean, id: number|null, error: Error|null}>} 关闭结果（不抛错）
 */
export async function closeMountedWorld() {
  let current = null
  try {
    current = await fetchCurrentWorld()
  } catch (e) {
    return { closed: false, id: null, error: e }
  }
  if (!current || !current.id) {
    resetEnsuredWorld()
    return { closed: false, id: null, error: null }
  }
  try {
    await closeWorld(current.id)
    return { closed: true, id: current.id, error: null }
  } catch (e) {
    return { closed: false, id: current.id, error: e }
  } finally {
    resetEnsuredWorld()
  }
}

/**
 * 离开世界回世界画廊：先关世界再跳转（顺序不能反，见上）。
 *
 * @param router vue-router 实例
 * @returns {Promise<void>}
 */
export async function leaveToLibrary(router) {
  const result = await closeMountedWorld()
  if (result.error) {
    // 关闭失败也照常回去：目的是"离开世界"，不是"必须关闭成功"
    console.warn('[worldSession] 关闭世界失败，仍返回世界画廊：', result.error)
  }
  await router.push('/')
  return result
}
