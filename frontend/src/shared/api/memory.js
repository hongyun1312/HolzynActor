// 记忆功能域 API：长期记忆（角色级/项目级）
import http from './http'

// 记忆列表（characterId 可空：空=项目全部记忆；非空=该角色记忆；page/size 分页）
export const fetchMemories = (projectId, params) => http.get(`/api/projects/${projectId}/memories`, { params })
// 删除单条记忆（软删）
export const deleteMemory = (id) => http.delete(`/api/memories/${id}`)
