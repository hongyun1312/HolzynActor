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
// 前端 API 聚合入口（共享层）
// 按功能域拆分到各
// conversation / crowd / knowledge / memory / settings / misc。
// 本文件统一 re-export，保持「从 @/shared/api 导入」的既有写法兼容。
//
export { default as http } from './http'

// 本地账户
export * from './account'
// 项目
export * from './project'
// 世界（时钟/时间线/场景/演化）
export * from './world'
// 角色（NPC/角色卡/行动）
export * from './character'
// 对话（单聊/群聊/世界事件）
export * from './conversation'
// 对谈（M1/M2：静止世界聊天模式，独立于世界联动对话）
export * from './dialogue'
// 玩家档案（M3：玩家 persona + 玩家-角色关系设定/关系行）
export * from './playerProfile'
// 人群
export * from './crowd'
// 知识库
export * from './knowledge'
// 记忆
export * from './memory'
// 设置（模型 API/Prompt 模板/用量）
export * from './settings'
// 通用（健康/通知）
export * from './misc'
// 新建项目工作流（文件解析 / 世界初始化，SSE）
export * from './workflow'
// 工作流世界观生成
export * from './worldgen'
// 世界沙盘（S1：沙盘总览/场景/状态 + 上次项目记录）
export * from './sandbox'
// 项目记忆
export * from './worldMemory'
// 决策与协调
export * from './decision'
// 遗传婚育谱系
export * from './lineage'
// 观测域只读聚合（AI 运行时状态 / 全局搜索）
export * from './observability'
// 世界图谱（六维单域 + 三种聚合视角，只读）
export * from './worldGraph'
// 新首页（M4：2D 世界地图双主角并置）数据源统一出口
export * from './home'
