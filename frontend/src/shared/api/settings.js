// 设置功能域 API：AI 模型 API 配置 / Prompt 模板 / AI 用量统计
import http from './http'

// ==================== 用户级/项目级 AI 模型 API（/settings/apis） ====================
// 以下接口均支持可选 projectId：传=项目级配置，不传=用户级默认
export const fetchModelApis = (projectId) => http.get('/api/model-apis', { params: { projectId: projectId || undefined } })
// 新增 API 配置（name/baseUrl/apiKey/model/supportsStream/remark）
export const createModelApi = (data, projectId) => http.post('/api/model-apis', data, { params: { projectId: projectId || undefined } })
// 编辑 API 配置（apiKey 传空=保持原 Key 不变）
export const updateModelApi = (id, data, projectId) => http.put(`/api/model-apis/${id}`, data, { params: { projectId: projectId || undefined } })
// 删除 API 配置
export const deleteModelApi = (id, projectId) => http.delete(`/api/model-apis/${id}`, { params: { projectId: projectId || undefined } })
// 设为默认（同归属内互斥）
export const setDefaultModelApi = (id, projectId) => http.put(`/api/model-apis/${id}/default`, null, { params: { projectId: projectId || undefined } })
// 读取某归属默认 API（未设置时 data=null）
export const fetchDefaultModelApi = (projectId) => http.get('/api/model-apis/default', { params: { projectId: projectId || undefined } })
// 未保存前连通性测试（新增表单内使用，Key 不入库）
export const testModelApi = (data) => http.post('/api/model-apis/test', data)
// 已保存配置连通性测试（使用解密后的真实 Key）
export const testSavedModelApi = (id, projectId) => http.post(`/api/model-apis/${id}/test`, null, { params: { projectId: projectId || undefined } })

// ==================== Prompt 模板 ====================
// 有效列表（项目覆盖 ∪ 用户覆盖 ∪ 内置；projectId 传=项目级覆盖）
export const fetchPromptTemplates = (projectId) => http.get(`/api/prompt-templates`, { params: { projectId: projectId || undefined } })
// 保存覆盖模板（projectId 传=项目级覆盖，不传=用户级覆盖）
export const savePromptTemplate = (code, data, projectId) => http.put(`/api/prompt-templates/${code}`, data, { params: { projectId: projectId || undefined } })
// 重置为内置（删除覆盖，回退低一级）
export const resetPromptTemplate = (code, projectId) => http.delete(`/api/prompt-templates/${code}`, { params: { projectId: projectId || undefined } })

// ==================== AI 用量统计 ====================
// 用量统计（用户级聚合；params: projectId/scene/model/startDate/endDate）
export const fetchUsageStats = (params) => http.get(`/api/usage/stats`, { params })
