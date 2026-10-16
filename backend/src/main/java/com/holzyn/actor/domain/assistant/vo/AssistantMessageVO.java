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

package com.holzyn.actor.domain.assistant.vo;

/**
 * 助手消息视图（前端会话列表渲染用；结构化载荷以字符串 JSON 传递，前端按需解析）。
 *
 * @param id             消息 ID
 * @param sessionId      会话 ID
 * @param role           消息角色（user/assistant）
 * @param content        正文
 * @param mode           回答模式（narrative/fact；用户消息为空）
 * @param cardJson       数据卡片 JSON（可空）
 * @param sourcesJson    来源标注 JSON（可空）
 * @param toolCallsJson  工具调用记录 JSON（可空）
 * @param quickFollowsJson 快速追问 JSON（可空）
 * @param createdAt      创建时间
 */
public record AssistantMessageVO(Long id, Long sessionId, String role, String content, String mode,
                                 String cardJson, String sourcesJson, String toolCallsJson,
                                 String quickFollowsJson, java.time.LocalDateTime createdAt) {
}
