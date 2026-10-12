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

package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;

/**
 * 决策链角色类型（P2-3 BrainStrategy）：按类型组链——
 * SPECIAL（核心角色）= 危险→空间→AI 深思→兜底；COMMON（普通 NPC）= 危险→快选择→概率化。
 */
public enum NpcKind {

    /** 核心角色（脑力档位门控，AI 深思链） */
    SPECIAL,

    /** 普通 NPC（概率化/快选择链，无 AI 深思） */
    COMMON;

    /**
     * 由角色实体判定类型（与 {@code NpcRefs.isCommonType} 同口径）。
     *
     * @param ch 角色实体（null → SPECIAL 宽松）
     *
     * @return 角色类型
     */
    public static NpcKind of(ActorCharacter ch) {
        return ch != null && NpcRefs.isCommonType(ch.getType()) ? COMMON : SPECIAL;
    }
}
