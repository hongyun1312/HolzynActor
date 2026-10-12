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

// 知识库功能域 API：文档 / 上传 / 向量化 / RAG 检索
import http from './http'

export const fetchKnowledgeDocs = (projectId) => http.get(`/api/projects/${projectId}/knowledge-docs`)
export const createKnowledgeDoc = (projectId, data) => http.post(`/api/projects/${projectId}/knowledge-docs`, data)
export const fetchKnowledgeDoc = (id) => http.get(`/api/knowledge-docs/${id}`)
export const updateKnowledgeDoc = (id, data) => http.put(`/api/knowledge-docs/${id}`, data)
export const deleteKnowledgeDoc = (id) => http.delete(`/api/knowledge-docs/${id}`)
export const reindexKnowledgeDoc = (id) => http.post(`/api/knowledge-docs/${id}/reindex`)
// 上传 txt/md 新建知识文档（multipart；characterId 可空=项目级）
export const uploadKnowledgeDoc = (projectId, file, characterId) => {
  const fd = new FormData()
  fd.append('file', file)
  if (characterId) fd.append('characterId', characterId)
  return http.post(`/api/projects/${projectId}/knowledge-docs/upload`, fd, { timeout: 60000 })
}
// 检索预览（embedding 优先，文本关键词降级）
export const searchKnowledgeDoc = (projectId, data) => http.post(`/api/projects/${projectId}/knowledge-docs/search`, data)
