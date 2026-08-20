// 世界功能域 API：世界时钟 / 时间线事件 / 场景 / 世界演化
import http from './http'

// ==================== 世界时钟 + 世界模拟 ====================
// 世界时钟状态（当前游戏时刻/速率/最近推进摘要）
export const fetchWorldClock = (projectId) => http.get(`/api/projects/${projectId}/world-clock`)
// 更新世界时钟（rate/paused/worldStartAt/worldStartGameHour；空字段保持原值）
export const updateWorldClock = (projectId, data) => http.put(`/api/projects/${projectId}/world-clock`, data)
// 手动补推一轮（调试/演示：立即按当前流逝推进角色/人群/事件）
export const advanceWorldClock = (projectId) => http.post(`/api/projects/${projectId}/world-clock/advance`)

// ==================== 时间线/事件 ====================
// 项目统一时间线（事件/行动/记忆聚合；params: types/characterId/startDate/endDate）
export const fetchProjectTimeline = (projectId, params) => http.get(`/api/projects/${projectId}/timeline`, { params })
// 手动新增事件（持久化 actor_event，source=manual）
export const createProjectEvent = (projectId, data) => http.post(`/api/projects/${projectId}/events`, data)
// AI 从世界观/项目概况识别生成事件（source=ai）
export const aiGenerateProjectEvent = (projectId) => http.post(`/api/projects/${projectId}/events/ai-generate`, null, { timeout: 60000 })

// ==================== 场景（世界演化场景/地点） ====================
export const fetchScenes = (projectId) => http.get(`/api/projects/${projectId}/scenes`)
export const createScene = (projectId, data) => http.post(`/api/projects/${projectId}/scenes`, data)
export const updateScene = (id, data) => http.put(`/api/scenes/${id}`, data)
export const deleteScene = (id) => http.delete(`/api/scenes/${id}`)
// 场景 AI 自动填充：基于世界观+角色详情自动生成场景（耗时长，覆盖默认超时）
export const aiGenerateScenes = (projectId, data) => http.post(`/api/projects/${projectId}/scenes/ai-generate`, data, { timeout: 120000 })

// ==================== 世界演化（场景化演化） ====================
export const fetchEvolutions = (projectId) => http.get(`/api/projects/${projectId}/evolutions`)
// 开始演化：{mode? manual/ai, sceneId?, background?, characterIds?}（AI 模式含选角，耗时长）
export const startEvolution = (projectId, data) => http.post(`/api/projects/${projectId}/evolutions`, data, { timeout: 120000 })
export const fetchEvolutionDetail = (id) => http.get(`/api/evolutions/${id}`)
// 推进一轮（AI 编排角色言行/加入退场/收尾；可能触发归档）
export const advanceEvolution = (id) => http.post(`/api/evolutions/${id}/turn`, null, { timeout: 120000 })
// 手动加入/退场角色
export const joinEvolution = (id, characterId) => http.post(`/api/evolutions/${id}/join`, { characterId })
export const leaveEvolution = (id, characterId) => http.post(`/api/evolutions/${id}/leave`, { characterId })
// 结束演化并归档（事件入时间线 + 参与者角色级记忆隔离）
export const finishEvolution = (id) => http.post(`/api/evolutions/${id}/finish`, null, { timeout: 120000 })
// 删除演化会话（级联清理参与者与轮次消息；归档事件保留在时间线）
export const deleteEvolution = (id) => http.delete(`/api/evolutions/${id}`)

// ==================== 世界观地点表（AI 提取 + 手动维护，项目级） ====================
// 查询项目全部地点（排序稳定）
export const fetchWorldLocations = (projectId) => http.get(`/api/projects/${projectId}/world-locations`)
// 新增地点 {name, type, intro, importance, sortOrder?}
export const createWorldLocation = (projectId, data) => http.post(`/api/projects/${projectId}/world-locations`, data)
// 修改地点
export const updateWorldLocation = (projectId, id, data) => http.put(`/api/projects/${projectId}/world-locations/${id}`, data)
// 删除地点
export const deleteWorldLocation = (projectId, id) => http.delete(`/api/projects/${projectId}/world-locations/${id}`)
// 编辑模式全量保存（增删改查一体，按数组顺序整体替换）
export const batchSaveWorldLocations = (projectId, items) => http.put(`/api/projects/${projectId}/world-locations/batch`, items)
// AI 重新提取地点（基于已存地理设定文本，与现有合并追加；耗时长，覆盖默认超时）
export const extractWorldLocations = (projectId) => http.post(`/api/projects/${projectId}/world-locations/extract`, null, { timeout: 120000 })
