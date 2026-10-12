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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 会话创建请求 DTO。
 * <p>职责：承载单聊/群聊会话的创建入参（mode 必填，成员可空）。
 * <b>B5（成员类型化）</b>：新增 memberRefs 统一成员引用（special:{id} / crowd:{id}，
 * 普通 NPC 用 crowd:{id} 进会话）；与 characterIds 二选一（memberRefs 优先，characterIds 兼容旧调用=special）。</p>
 *
 * @param mode 模式：single 单聊 / group 群聊
 *
 * @param title 会话标题（可选，缺省自动生成）
 *
 * @param characterIds 参与特殊角色 ID 列表（兼容旧调用；单聊传 1 个，群聊可多个）
 *
 * @param memberRefs 成员引用列表（special:{id} / crowd:{id}，B5；为空时用 characterIds）
 *
 * @param location 对话所在地（场景地点；可选，空=通过手机等远程通讯软件对话，直接影响 NPC 回答）
 *
 * @param gameTimeText 对话发生时的世界时间快照（可选，来自世界时钟 gameTimeText）
 */
public record ConversationDTO(
        @NotBlank(message = "会话模式不能为空") @Size(max = 10, message = "会话模式最长 10 字符") String mode,
        @Size(max = 100, message = "会话标题最长 100 字符") String title,
        List<Long> characterIds,
        List<String> memberRefs,
        @Size(max = 200, message = "对话所在地最长 200 字符") String location,
        @Size(max = 120, message = "世界时间快照最长 120 字符") String gameTimeText
) {
}