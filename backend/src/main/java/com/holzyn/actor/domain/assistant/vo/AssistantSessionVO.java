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
 * 助手会话视图。
 *
 * @param id           会话 ID
 * @param projectId    项目 ID
 * @param title        会话标题
 * @param defaultMode  默认回答模式（narrative/fact）
 * @param confirmMode  干预确认粒度（strict/standard/trust，M4）
 * @param messageCount 消息数（含用户与助手；可空=未统计）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record AssistantSessionVO(Long id, Long projectId, String title, String defaultMode, String confirmMode,
                                 Long messageCount, java.time.LocalDateTime createdAt,
                                 java.time.LocalDateTime updatedAt) {
}
