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

// 世界初始化 API：初始化状态（唯一判定）、单步执行（SSE）、跳过可选步、世界名与简介建议/落库。
// 事件约定与 workflow.js 一致：log={level,message,time}、stage={name,index,total}、result=本步结果、error={message}
import http from './http'
import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'

/**
 * 查询世界初始化状态（画廊角标 / 详情页横幅 / 初始化向导共用）。
 *
 * @param projectId 项目 ID
 *
 * @return Promise<WorldInitStatusVO>
 *         {projectId, worldName, initialized, complete, degraded, doneCount, totalCount,
 *          requiredDoneCount, requiredTotalCount, currentStepCode, message, steps:[{code,index,label,
 *          required,optional,wizardOnly,status,satisfied,itemCount,message,attemptCount,finishedAt}]}
 */
export const fetchWorldInitStatus = (projectId) => http.get(`/api/worlds/${projectId}/init-status`)

/**
 * 执行单个初始化步骤（SSE 流式，只跑这一步）。
 *
 * @param projectId 项目 ID
 *
 * @param stepCode  步骤编码（后端 WorldInitStep.code，如 LOCATIONS / CHARACTERS）
 *
 * @param handlers  事件回调：{ onLog(line), onStage(stage), onResult(result) }
 *
 * @param rebuild   true=全量重建 / false=跳过已生成（幂等）
 *
 * @return Promise<WorldInitResultVO>
 */
export const runInitStepStream = async (projectId, stepCode, handlers = {}, rebuild = false) => {
  let lastErr = null
  const { promise } = streamFetch({
    url: `/api/worlds/${projectId}/init/step/stream`,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ stepCode, rebuild: !!rebuild }),
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.LOG) handlers.onLog?.(data)
      else if (name === SSE_EVENTS.STAGE) handlers.onStage?.(data)
      else if (name === SSE_EVENTS.RESULT) handlers.onResult?.(data)
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  return result
}

/**
 * 跳过某个可选初始化步骤（必需步骤后端会 400 拒绝）。
 *
 * @param projectId 项目 ID
 *
 * @param stepCode  步骤编码
 *
 * @return Promise<WorldInitStatusVO> 跳过后的最新状态
 */
export const skipInitStep = (projectId, stepCode) =>
  http.post(`/api/worlds/${projectId}/init/skip`, { stepCode })

/**
 * 根据当前世界观设定生成世界名与世界简介（AI，不写库）。
 *
 * <p>含 AI 调用，超时放到 120s（与 generateCards 同档）；返回值只用于回填表单，
 * 用户可改写后再调 {@link saveWorldMeta} 落库。</p>
 *
 * @param projectId 项目 ID
 *
 * @return Promise<{name, summary}>
 */
export const suggestWorldMeta = (projectId) =>
  http.post(`/api/worlds/${projectId}/meta-suggest`, null, { timeout: 120000 })

/**
 * 保存世界名与世界简介（同时写世界库项目、世界观名称与目录库画廊快照）。
 *
 * @param projectId 项目 ID
 *
 * @param data      {name, summary}
 *
 * @return Promise<ProjectVO>
 */
export const saveWorldMeta = (projectId, data) => http.post(`/api/worlds/${projectId}/meta`, data)

/**
 * 世界类别（固定八类）与世界程度（四档）的可选项。
 *
 * <p>取后端枚举而不是前端硬编码：类别在保存时会被 {@code WorldCategory.require()} 严格校验，
 * 前端另存一份清单迟早会与后端漂移（用户选了一个必然被 400 拒掉的值）。</p>
 *
 * @return Promise<{categories:[{code,label}], tones:[{code,label,summary,boundary}]}>
 */
export const fetchWorldOptions = () => http.get('/api/worlds/world-options')

/** 世界名长度约束（与后端 WorldMetaSuggestService.NAME_MAX 一致） */
export const WORLD_NAME_MAX = 14
/** 世界名推荐最短字数（AI 生成口径；用户手改时不硬卡，仅提示） */
export const WORLD_NAME_MIN = 6

/** 世界简介长度上限（与后端 SUMMARY_MAX 一致） */
export const WORLD_SUMMARY_MAX = 100

/** 世界简介推荐最短字数（AI 生成口径） */
export const WORLD_SUMMARY_MIN = 60

/** 初始化步骤编码 → 中文名（前端兜底文案用，权威名称来自后端 steps[].label） */
export const INIT_STEP_LABELS = {
  worldview: '世界观设定',
  locations: '世界观地点',
  characters: '角色卡',
  npcs: '普通 NPC',
  relations: '关系拓扑',
  family: '家族与基因',
  world_map: '世界地图与住所',
  world_time: '世界时间',
  macro: '宏观社会模板',
  knowledge: '知识向量化'
}
