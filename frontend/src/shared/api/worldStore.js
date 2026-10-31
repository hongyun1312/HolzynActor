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
// 世界（存档）生命周期 API（V1.1.2-beta.1 M5 建立，M6 补导入/导出）
//
// 这一层的唯一职责：把后端 WorldLifecycleController（`/api/worlds`）的动作
// 映射成前端可用的函数（列表/详情/当前/新建/打开/关闭/改名删除/清单/导出/导入）。**不在这里拼项目/世界的领域语义**——列表只为世界画廊服务，
// 进入世界后一律走既有的 world / sandbox / character 等域 API。
//
// 命名说明：函数名用 World（世界）而不是 Project（项目）。后端 Java 实体、服务名、
// `project_id` 列按设计 §3.13 保留旧名，但前端对外词汇统一成"世界"，
// 避免世界画廊里一半"项目"一半"世界"。
//
import axios from 'axios'
import http from './http'

/** 世界列表（只读目录库摘要列，不挂载任何世界） */
export const fetchWorlds = () => http.get('/api/worlds')

/**
 * 当前挂载的世界（未挂载时后端返回 null，保留 null 语义，便于调用方判空）。
 *
 * <p>注意：不要把它写成 `|| {}`——"未挂载"和"挂载了一个没有字段的世界"必须可区分，
 * 否则启动守卫会把空对象当成已有世界而跳过世界画廊。</p>
 */
export const fetchCurrentWorld = () => http.get('/api/worlds/current').then((data) => data || null)

/** 单个存档详情（含 dbPresent / lockPresent / sizeBytes 等诊断字段） */
export const fetchWorld = (id) => http.get(`/api/worlds/${id}`)

/**
 * 新建世界（建目录 + Flyway 建库 + manifest + 索引行）。
 *
 * <p>后端在"当前没有挂载世界"时会自动挂载新世界并返回 `opened=true`，
 * 所以前端创建成功后可以直接进世界初始化，无需再调 {@link openWorld}。</p>
 */
export const createWorld = (name) => http.post('/api/worlds', { name }, { timeout: 120000 })

/** 打开（挂载）世界：成功后可访问该项目下所有世界内接口 */
export const openWorld = (id) => http.post(`/api/worlds/${id}/open`, null, { timeout: 120000 })

/** 关闭（卸载）世界：后端会压盘世界库、同步镜像、回写摘要；之后世界内接口不再可用 */
export const closeWorld = (id) => http.post(`/api/worlds/${id}/close`, null, { timeout: 120000 })

/** 归档删除世界（世界目录移入 archive/deleted-worlds/，索引行软删；可救回） */
export const deleteWorld = (id) => http.delete(`/api/worlds/${id}`, { timeout: 120000 })

/**
 * 改名 / 封面 / 概要（只写目录库，不打开世界）。
 *
 * @param id   目录库主键
 * @param data {@code {name?, summary?, coverUrl?}}，空串表示"不改该字段"
 */
export const updateWorldMeta = (id, data) => http.put(`/api/worlds/${id}`, data)

/**
 * 封面 URL：把索引里的相对文件名（如 {@code cover.png}）翻译成可访问的接口地址。
 *
 * <p>为什么索引不直接存 URL：世界目录是"拷走即带走"的单位，导出/导入会整目录搬运世界，
 * 存绝对 URL 会在导入重编号后指回旧世界。文件名 + 由 id 现拼 URL 才两头都对。</p>
 *
 * @param {{id?:number|string, coverUrl?:string}} world 存档卡片
 *
 * @returns {string} 封面地址；没有封面返回空串（调用方回落到预备背面）
 */
export const worldCoverUrl = (world) => {
  const id = world?.id
  const fileName = world?.coverUrl
  return id && fileName ? `/api/worlds/${id}/cover` : ''
}

/**
 * 上传 / 替换世界封面（PNG / JPG，≤8MB；后端会等比缩到最长边 1280）。
 *
 * @param {number} id   目录库主键
 * @param {File}   file 用户选中的图片
 *
 * @returns 更新后的存档卡片（coverUrl = 相对文件名）
 */
export const uploadWorldCover = (id, file) => {
  const fd = new FormData()
  fd.append('file', file, file.name || 'cover.png')
  return http.post(`/api/worlds/${id}/cover`, fd, { timeout: 0 })
}

/**
 * 移除世界封面（删文件 + 清空索引，卡片回落到预备背面）。
 *
 * @param {number} id 目录库主键
 *
 * @returns 更新后的存档卡片（coverUrl = null）
 */
export const removeWorldCover = (id) => http.delete(`/api/worlds/${id}/cover`)

/** 读取世界清单（manifest.json 原文，只读，用于"这个世界是哪个版本建的"） */
export const fetchWorldManifest = (id) => http.get(`/api/worlds/${id}/manifest`)

/**
 * 导出世界为 `.holzyn` 包（v3 = 世界目录 zip，后端流式直出）。
 *
 * <p>为什么这里自己用 axios 而不是共享的 {@link http}：共享实例的响应拦截器会解包
 * `R{code,data}` 并丢弃响应头，而下载需要 `Content-Disposition` 里的文件名。
 * 只有这一个下载入口，单独发请求比给拦截器开"blob 例外"更不容易误伤其他接口。</p>
 *
 * <p>失败时后端会回 JSON（例如 409 世界正在使用中），此时 blob 的 type 是
 * `application/json`——这里把它解回文字抛出去，前端才能显示"请先关闭世界"。</p>
 *
 * @param id 目录库主键
 *
 * @return `{blob, fileName}`（fileName 已按后端建议名解好，缺失时给兜底名）
 */
export const exportWorld = async (id) => {
  const resp = await axios.get(`/api/worlds/${id}/export`, {
    responseType: 'blob',
    timeout: 0 // 打包几 GB 资源时不能掐表
  })
  const blob = resp.data
  if (blob && blob.type && blob.type.indexOf('application/json') === 0) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      const parsed = JSON.parse(text)
      msg = parsed.message || msg
    } catch (_) { /* 非 JSON 就沿用默认文案 */ }
    throw new Error(msg)
  }
  const fileName = pickFileName(resp.headers && (resp.headers['content-disposition'] || resp.headers['Content-Disposition']))
  return { blob, fileName: fileName || `world-${id}.holzyn` }
}

/**
 * 导入 `.holzyn` 世界包（后端校验 + uid 冲突换新 + project_id 改写，返回新世界 id）。
 *
 * @param file 用户选中的包（File/Blob）
 *
 * @return `{worldId, worldUid, name, uidRewritten, gameSecond, characterCount, elapsedMs}`
 */
export const importWorld = (file) => {
  const fd = new FormData()
  fd.append('file', file, file.name || 'import.holzyn')
  return http.post('/api/worlds/import', fd, { timeout: 0 })
}

/**
 * 从 `Content-Disposition` 里取文件名。
 *
 * <p>后端写法是 `attachment; filename*=UTF-8''<urlencoded>`（世界名含中文，不能用裸 filename），
 * 这里优先解 `filename*`，再退 `filename`。</p>
 */
function pickFileName(header) {
  if (!header) return ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (star && star[1]) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch (_) {
      return star[1].trim()
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain && plain[1] ? plain[1].trim() : ''
}
