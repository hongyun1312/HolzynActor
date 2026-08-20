// 对话功能域 API：单聊/群聊会话 / 消息（SSE 流式）/ 世界事件注入 / 群聊配置
import http from './http'

// ==================== 对话 ====================
export const createConversation = (projectId, data) => http.post(`/api/projects/${projectId}/conversations`, data)
export const fetchConversations = (projectId) => http.get(`/api/projects/${projectId}/conversations`)
export const fetchConversationDetail = (id) => http.get(`/api/conversations/${id}`)
export const fetchMessages = (conversationId) => http.get(`/api/conversations/${conversationId}/messages`)
export const sendMessage = (conversationId, data) => http.post(`/api/conversations/${conversationId}/messages`, data)
// 删除会话（连同消息一并删除）
export const deleteConversation = (id) => http.delete(`/api/conversations/${id}`)
// SSE 流式通道 URL（EventSource 消费；不经过 axios 解包）
export const conversationStreamUrl = (conversationId, forceCharacterId) => {
  const q = forceCharacterId ? `?forceCharacterId=${forceCharacterId}` : ''
  return `/api/conversations/${conversationId}/stream${q}`
}

// ==================== 对话场景（所在地 + 世界时间） ====================
// 更新会话「对话场景」：{location?, gameTimeText?}（location 空串=清空为远程通讯）
export const updateConversationScene = (conversationId, data) => http.put(`/api/conversations/${conversationId}/scene`, data)
// AI 生成对话专属标题（创建弹窗「AI 重写」）：{characterId?, location?, gameTimeText?} → { title }
export const generateConversationTitle = (projectId, data) => http.post(`/api/projects/${projectId}/conversations/generate-title`, data)

// ==================== 世界事件 + 无玩家轮次 ====================
// 世界事件：手填文本或 AI 生成（generate:true），返回后前端打开 SSE 触发在场角色回应
export const injectWorldEvent = (conversationId, data) => http.post(`/api/conversations/${conversationId}/world-event`, data)
// 「无玩家轮次」自主推进（P4 世界模拟预留）
export const advanceConversation = (conversationId) => http.post(`/api/conversations/${conversationId}/advance`)

// ==================== 群聊配置（用户级） ====================
// 每轮回复上限：GET 读取 / PUT 修改（1~20，持久化）
export const fetchGroupChatConfig = () => http.get('/api/group-chat/config')
export const saveGroupChatConfig = (data) => http.put('/api/group-chat/config', data)
