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

package com.holzyn.actor.domain.dialogue.vo;

import java.time.LocalDateTime;

/**
 * 对谈世界书条目视图（M4）。
 *
 * @param id        条目 ID
 * @param sessionId 归属会话 ID
 * @param projectId 归属项目 ID
 * @param keyword   触发关键词
 * @param content   注入的知识片段内容
 * @param enabled   启用标记
 * @param priority  命中排序权重（升序）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DialogueWorldbookVO(Long id, Long sessionId, Long projectId, String keyword, String content,
                                  Boolean enabled, Integer priority, LocalDateTime createdAt,
                                  LocalDateTime updatedAt) {
}
