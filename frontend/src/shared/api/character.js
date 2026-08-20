// 角色功能域 API：NPC 角色 / 角色卡 / 行动
import http from './http'

// ==================== 角色/角色卡 ====================
export const fetchCharacters = (projectId) => http.get(`/api/projects/${projectId}/characters`)
export const createCharacter = (projectId, data) => http.post(`/api/projects/${projectId}/characters`, data)
export const updateCharacter = (id, data) => http.put(`/api/characters/${id}`, data)
export const deleteCharacter = (id) => http.delete(`/api/characters/${id}`)
export const generateCharacterCard = (characterId) => http.post(`/api/characters/${characterId}/generate-card`, null, { timeout: 60000 })
export const fetchCharacterCard = (characterId) => http.get(`/api/characters/${characterId}/card`)
export const fetchCharacterCardVersions = (characterId) => http.get(`/api/characters/${characterId}/card/versions`)
export const editCharacterCard = (characterId, data) => http.put(`/api/characters/${characterId}/card`, data)

// ==================== 角色关系拓扑图（G6 v5 全角色网络图数据源） ====================
// 返回 { nodes: [{id(key),name,kind(npc|crowd|ghost),type,importance,isProtagonist,title,detail,crowdName(普通型NPC归属),occupation,state,lastAction}],
//         relations: [{id,fromKey,toKey,fromName,toName,relationType,description}] }
// 说明：普通人群重构后 crowd 节点 = 每个普通型 NPC 以具体人名独立成节点，crowdName 存其归属。
export const fetchCharacterRelations = (projectId) => http.get(`/api/projects/${projectId}/character-relations`)
// AI 生成关系预览（scope=character|project，不落库）→ [{from,to,relationType,description}]
export const generateCharacterRelations = (projectId, data) => http.post(`/api/projects/${projectId}/character-relations/generate`, data, { timeout: 120000 })
// 关系批量入库（预览确认后写入）→ {added, total}
export const batchSaveCharacterRelations = (projectId, data) => http.post(`/api/projects/${projectId}/character-relations/batch`, data)

// ==================== 行动 ====================
export const fetchActions = (characterId) => http.get(`/api/characters/${characterId}/actions`)
export const triggerAction = (characterId, data) => http.post(`/api/characters/${characterId}/actions/trigger`, data)
export const fetchActionTimeline = (characterId) => http.get(`/api/characters/${characterId}/actions/timeline`)
// 项目级行动时间线（聚合决策/世界事件/执行日志 + 角色/状态/触发源/时间范围筛选）
export const fetchProjectActionTimeline = (projectId, params) => http.get(`/api/projects/${projectId}/actions/timeline`, { params })
// 标记行动计划状态（done/cancelled/planned）
export const updateActionStatus = (planId, status) => http.put(`/api/actions/${planId}/status`, { status })
// SSE 行动事件流 URL（时间线实时刷新）
export const actionStreamUrl = () => `/api/actions/stream`

// ==================== 长期记忆（角色级抽取） ====================
// 手动触发角色记忆抽取（调试/补抽，返回本次新增条数）
export const extractCharacterMemories = (characterId) => http.post(`/api/characters/${characterId}/memories/extract`)
