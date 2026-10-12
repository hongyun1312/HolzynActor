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

package com.holzyn.actor.domain.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * 对话场景更新请求 DTO（编辑对话所在地 / 世界时间）。
 * <p>职责：承载会话创建后对「对话场景」的修改入参——所在地与发生时间均为可选项，
 * 传 null 表示不修改，传空字符串表示清空（所在地清空后按「远程通讯」处理）。</p>
 *
 * @param location 对话所在地（可空；空串=清空为远程通讯）
 *
 * @param gameTimeText 对话发生时的世界时间快照（可空）
 */
public record ConversationSceneDTO(
        @Size(max = 200, message = "对话所在地最长 200 字符") String location,
        @Size(max = 120, message = "世界时间快照最长 120 字符") String gameTimeText
) {
}
