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
 * 一轮对话的世界回写快照（domain/world/dialog）。
 * <p>两套聊天链路（新「对谈」{@code actor_talk_*}、旧「对话」{@code actor_dialogue*}）与
 * 无玩家自主群聊统一用本对象描述「这一轮世界该记住什么」，避免 {@code DialogWorldService}
 * 直接依赖任一聊天域的内部实体。</p>
 * <p><b>幂等</b>：{@link #lastMessageId} 是本轮最后一条消息 ID；{@code sourceKey} 由
 * {@link #channel}/{@link #sessionId}/{@link #lastMessageId} 派生，重放同一轮不会重复落事件。</p>
 */
@Data
@Builder
public class DialogRound {

    /** 聊天链路：dialogue=新对谈 / conversation=旧对话 */
    private String channel;

    /** 项目 ID */
    private Long projectId;

    /** 归属用户 ID（玩家角色创建、AI 用量归属；自主群聊可空） */
    private Long userId;

    /** 会话 ID（新对谈 actor_talk_session.id / 旧对话 actor_dialogue.id） */
    private Long sessionId;

    /** 会话稳定键（用于内存计数与 digest 幂等；调用方拼好） */
    private String sessionKey;

    /** 场景地点 ID（可空；旧对话 location_id） */
    private Long locationId;

    /** 场景地点名（可空；旧对话 location / 新对谈 location_name） */
    private String locationName;

    /** 参与者角色引用（npc:{id}；可含玩家） */
    private List<String> participantRefs;

    /** 玩家角色引用（可空；自主群聊无玩家） */
    private String playerRef;

    /** 本轮摘要（进事件标题/内容与 situation） */
    private String summary;

    /** 本轮最后一条消息 ID（事件幂等键；自主群聊可空） */
    private Long lastMessageId;

    /** 轮次序号（自主群聊按 beat；其余由服务内计数） */
    private Integer roundIndex;

    /** 当前游戏秒（可空=由服务读时钟） */
    private Long gameSecond;

    /** 当前游戏小时（可空=派生 gameSecond/3600） */
    private Long gameHour;

    /** 是否发生在世界冻结态（新对谈单聊） */
    private boolean frozen;

    /** 是否无玩家自主群聊（只发事件，不在每 6 轮抽取） */
    private boolean autonomous;

    /** 事件重要度（可空=2） */
    private Integer importance;

    /** 本轮是否真正产生了新消息（false=纯空转，不触发 digest） */
    @Builder.Default
    private boolean hasNewMessage = true;
}
