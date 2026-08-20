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
