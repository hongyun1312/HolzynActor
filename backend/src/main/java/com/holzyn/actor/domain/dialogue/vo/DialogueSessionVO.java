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
 * 对谈会话视图。
 *
 * @param id                  会话 ID
 * @param projectId           项目 ID
 * @param title               会话标题
 * @param type                会话类型（single/group）
 * @param characterRef        单聊对谈角色（npc:{id}；群聊 NULL）
 * @param characterName       单聊角色名（查询时附带；群聊 NULL）
 * @param locationName        群聊场景地点名（单聊 NULL）
 * @param frozenGameSecond    冻结时刻（世界时间秒）
 * @param frozenTimeText      冻结时刻世界历文本（世界静止提示）
 * @param status              会话状态（active/closed/archived）
 * @param messageCount        消息数
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 */
public record DialogueSessionVO(Long id, Long projectId, String title, String type, String characterRef,
                                String characterName, String locationName, Long frozenGameSecond,
                                String frozenTimeText, String status, Long messageCount,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
}
