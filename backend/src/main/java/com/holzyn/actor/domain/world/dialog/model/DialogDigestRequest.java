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

package com.holzyn.actor.domain.world.dialog.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 对话批量抽取（dialog_digest）请求。
 * <p>只承载「抽取所需的最小上下文」：会话标识、参与者、地点、触发事件。对话原文由
 * {@code DialogDigestService} 按 {@link #channel} 从对应消息表读取最近 40 条，避免调用方
 * 各拼一份导致输入不一致。</p>
 */
@Data
@Builder
public class DialogDigestRequest {

    /** 聊天链路：dialogue / conversation */
    private String channel;

    /** 项目 ID */
    private Long projectId;

    /** 归属用户 ID */
    private Long userId;

    /** 会话 ID */
    private Long sessionId;

    /** 会话稳定键 */
    private String sessionKey;

    /** 参与者角色引用 */
    private List<String> participantRefs;

    /** 玩家角色引用（可空） */
    private String playerRef;

    /** 场景地点名 */
    private String locationName;

    /** 当前游戏秒 */
    private Long gameSecond;

    /** 触发本轮 digest 的 dialog 事件 ID（写事实日志的 source_event_id） */
    private Long eventId;

    /** 本轮最后一条消息 ID（幂等键） */
    private Long lastMessageId;

    /** 是否新对谈（true=可写玩家关系投影；false=只写记忆/事实） */
    private boolean newDialogue;
}
