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

// 设置功能域 API：AI 模型 API 配置 / Prompt 模板 / AI 用量统计
import http from './http'

// ==================== 用户级/项目级 AI 模型 API（/settings/apis） ====================
// 以下接口均支持可选 projectId：传=项目级配置，不传=用户级默认
export const fetchModelApis = (projectId) => http.get('/api/model-apis', { params: { projectId: projectId || undefined } })
// API 配置（name/baseUrl/apiKey/model/supportsStream/remark）
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
// 全域 AI 就绪状态（{configured:boolean}）：合并「用户级 + 各世界项目级」供应商判定，
// 世界画廊「还没接上模型」横幅据此显示/隐藏（不再只看 localStorage 的关闭标记）
export const fetchAiReadiness = () => http.get('/api/model-apis/ready')

// ==================== 供应商下的模型（文本 / 向量 / 决策） ====================
// 新增模型（role: text/embedding/decision；isDefault=该角色默认）
export const addProviderModel = (providerId, data, projectId) => http.post(
  `/api/model-apis/${providerId}/models`, data, { params: { projectId: projectId || undefined } })
// 编辑模型（可改名 / 改角色 / 启停 / 改默认）
export const updateProviderModel = (providerId, modelId, data, projectId) => http.put(
  `/api/model-apis/${providerId}/models/${modelId}`, data, { params: { projectId: projectId || undefined } })
// 删除模型
export const deleteProviderModel = (providerId, modelId, projectId) => http.delete(
  `/api/model-apis/${providerId}/models/${modelId}`, { params: { projectId: projectId || undefined } })
// 设为该角色的默认模型（同角色互斥）
export const setDefaultProviderModel = (providerId, modelId, projectId) => http.put(
  `/api/model-apis/${providerId}/models/${modelId}/default`, null, { params: { projectId: projectId || undefined } })
// 拉取接口上的全部模型（已保存供应商）
export const fetchRemoteProviderModels = (providerId, projectId) => http.get(
  `/api/model-apis/${providerId}/models/remote`, { params: { projectId: projectId || undefined } })
// 拉取接口上的全部模型（未保存供应商：body 传 baseUrl + apiKey）
export const previewRemoteProviderModels = (data) => http.post('/api/model-apis/models/remote', data)
// 测试某个具体模型（不是供应商默认文本模型）
export const testProviderModel = (providerId, modelId, projectId) => http.post(
  `/api/model-apis/${providerId}/models/${modelId}/test`, null, { params: { projectId: projectId || undefined } })

// ==================== 决策模型本地部署（/settings/decision-models） ====================
// 本机（用户级）配置：与项目无关，全局设置与项目设置共用同一份。
// catalog = 三个适配器的清单 + 安装/运行状态 + 当前运行形态（api/local）。
export const fetchDecisionModelCatalog = () => http.get('/api/settings/decision-models/catalog', { timeout: 60000 })
// 单个适配器状态（轮询用，比 catalog 轻）
export const fetchDecisionModelStatus = (adapter) => http.get('/api/settings/decision-models/status', { params: { adapter } })
// 下载并安装（source: hf-mirror=国内镜像 / huggingface=官方源；后台异步，进度看 status）
export const installDecisionModel = (adapter, source) => http.post('/api/settings/decision-models/install', { adapter, source })
// 取消下载/安装
export const cancelDecisionModelInstall = (adapter) => http.post('/api/settings/decision-models/cancel', { adapter })
// 启动本地推理进程（port 传空=适配器默认端口）
export const startDecisionModel = (adapter, port) => http.post('/api/settings/decision-models/start', { adapter, port: port || undefined })
// 停止本地推理进程
export const stopDecisionModel = () => http.post('/api/settings/decision-models/stop')
// 卸载（先停进程再删权重与虚拟环境）
export const uninstallDecisionModel = (adapter) => http.post('/api/settings/decision-models/uninstall', { adapter })
// 切换运行形态：mode=api（调用远程 API）/ local（本机进程）
export const saveDecisionModelSelection = (data) => http.post('/api/settings/decision-models/selection', data)
// 保存自定义启动命令（适配器脚本与本机环境不兼容时兜底；传空=恢复默认命令）
export const saveDecisionModelCustomCommand = (adapter, command) => http.post('/api/settings/decision-models/custom-command', { adapter, command })

// ==================== Prompt 模板 ====================
// 有效列表（项目覆盖 ∪ 用户覆盖 ∪ 内置；projectId 传=项目级覆盖）
export const fetchPromptTemplates = (projectId) => http.get(`/api/prompt-templates`, { params: { projectId: projectId || undefined } })
// 模板功能分类（有序，含各分类模板数；设置页横向 Tab 分组展示用）
export const fetchPromptTemplateCategories = (projectId) => http.get(`/api/prompt-templates/categories`, { params: { projectId: projectId || undefined } })
// 保存覆盖模板（projectId 传=项目级覆盖，不传=用户级覆盖）
export const savePromptTemplate = (code, data, projectId) => http.put(`/api/prompt-templates/${code}`, data, { params: { projectId: projectId || undefined } })
// 重置为内置（删除覆盖，回退低一级）
export const resetPromptTemplate = (code, projectId) => http.delete(`/api/prompt-templates/${code}`, { params: { projectId: projectId || undefined } })

// ==================== AI 用量统计 ====================
// 用量统计（用户级聚合；params: projectId/scene/model/startDate/endDate）
export const fetchUsageStats = (params) => http.get(`/api/usage/stats`, { params })
