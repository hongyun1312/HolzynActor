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

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家档案视图对象。
 * <p>职责：作为玩家档案接口（GET/PUT /api/player-profile）的返回载体——
 * {@code name}（玩家名）+ {@code persona}（六维文本：身份/背景/性格/说话风格/目标/能力，
 * 与特殊 NPC 档案 11 维<b>同构裁剪</b>，键名对齐 dims 各维，无记忆/无状态/无自主行动）
 * + {@code relationSettings}（每角色「与我的关系」设定：过往/身份关系/态度，键=角色引用）。</p>
 */
@Data
public class PlayerProfileVO {

    /** 主键 */
    private Long id;

    /** 归属用户 ID（本地单用户恒 1） */
    private Long userId;

    /** 玩家名（对谈中 NPC 称呼；空时兜底账户昵称→「你」） */
    private String name;

    /** 玩家 persona 六维（identity/background/personality/voice/goals/ability） */
    private Map<String, String> persona = new LinkedHashMap<>();

    /** 每角色关系设定：characterRef → {past 过往, identity 身份关系, attitude 态度} */
    private Map<String, Map<String, String>> relationSettings = new LinkedHashMap<>();

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
