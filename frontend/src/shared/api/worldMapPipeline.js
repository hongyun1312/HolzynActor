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

// 世界地图生成管线（V1.1 · 六阶段）数据源封装
//
// 与旧 home.js 的 /world-map 的区别（这是两代地图，不是两套接口）：
//   · 旧图：500 格 × 0.2m 的白板 + 圆心区域，坐标域是「场景格」；
//   · 新图：1024 格 × 1m（1km×1km）的米制底图，坐标**一律是米**，地形在 terrain.png 里，
//     矢量要素（建筑/农田/林地/广场/码头/道路）走 /bundle。
// 因此本文件不做任何坐标换算——米就是米，前端只在「米 → 屏幕像素」时乘缩放。
import http from './http'

/** 地形底图地址（后端下发的是相对路径；拼上版本号防止浏览器吃旧图）。 */
const terrainUrlOf = (overview) => overview?.terrainUrl || null

/**
 * 地图概览（状态/进度/统计/档案/地块数）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object|null>} 未生成过返回 null
 */
export const fetchMapOverview = (projectId) =>
  http.get(`/api/worlds/${projectId}/map`)

/**
 * 渲染包：调色板 + 道路 + 要素 + 组 + 街区（一次拿全，避免首屏多次往返）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object>} { overview, palette, roads[], features[], groups[], districts[] }
 */
export const fetchMapBundle = (projectId) =>
  http.get(`/api/worlds/${projectId}/map/bundle`, { timeout: 30000 })

/**
 * 角色快照（薄数据：40 个点 + 在途轨迹 + 世界时钟）。
 *
 * <p>为什么单独一个接口：bundle 是整张图的几何（1 MB 级），只在开页/改布局时拉；
 * 角色位置每几秒都在变，混在 bundle 里会逼着前端每 5 秒重拉 1 MB。</p>
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<{clock:{gameSecond:number,paused:boolean,rate:number}, actors:object[]}>}
 */
export const fetchMapActors = (projectId) =>
  http.get(`/api/worlds/${projectId}/map/actors`)

/**
 * 提交布局编辑（整批原子；有一条不合法就整批 409 并逐条给原因）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {{baseRevision:number, edits:Array<{code:string,x:number,z:number,rotation:number}>}} payload
 * @returns {Promise<object>} LayoutEditResultVO
 */
export const saveLayoutEdits = (projectId, payload) =>
  http.post(`/api/worlds/${projectId}/map/layout/edits`, payload, { timeout: 30000 })

/**
 * 列出手改（「已修改」角标 + 单条恢复）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<{revision:number, count:number, overrides:object[]}>}
 */
export const fetchLayoutOverrides = (projectId) =>
  http.get(`/api/worlds/${projectId}/map/layout/overrides`)

/**
 * 清除单条手改（立即恢复 AI 原版）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {string} code 要素 code
 * @returns {Promise<{cleared:number, revision:number}>}
 */
export const clearLayoutOverride = (projectId, code) =>
  http.delete(`/api/worlds/${projectId}/map/layout/overrides/${encodeURIComponent(code)}`)

/**
 * 触发生成（异步任务；进度走 SSE）。种子相同 ⇒ 结果相同（决定论）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {{seed?: number, size?: number}} opts 种子/边长（可空）
 * @returns {Promise<{mapId:number, status:string}>}
 */
export const generateWorldMap = (projectId, opts = {}) =>
  http.post(`/api/worlds/${projectId}/map/generate`, opts, { timeout: 30000 })

/**
 * 单阶段重跑（S0/S1/S2/S3/S4/S5），下游自动标记失效。
 *
 * @param {number|string} projectId 项目 ID
 * @param {string} stage 阶段名
 * @returns {Promise<object>}
 */
export const rerunMapStage = (projectId, stage) =>
  http.post(`/api/worlds/${projectId}/map/stages/${stage}/rerun`, {}, { timeout: 30000 })

/**
 * 生成进度 SSE 地址（原生 EventSource 用，故不走 axios）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {string} 相对 URL
 */
export const mapStreamUrl = (projectId) => `/api/worlds/${projectId}/map/stream`

/**
 * 地形底图地址（overview 里带的版本化路径）。
 *
 * @param {object} overview 概览
 * @returns {string|null} 相对 URL
 */
export const mapTerrainUrl = terrainUrlOf
