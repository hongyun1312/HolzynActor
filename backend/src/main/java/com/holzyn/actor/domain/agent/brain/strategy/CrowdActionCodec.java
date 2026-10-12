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

import com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人群行动（{@link CrowdDecisionParse.CrowdAction}）↔ 策略链通用 Map 编解码。
 * <p>策略链统一以 {@code Map} 为载体（特殊链=ReAct 规范 Map；普通链=人群行动
 * Map{actionText/actionType/target/reason}），普通 NPC 链各策略产出/消费经
 * 本编解码器双向转换（调用方 {@code NpcStateService} 把链产物 Map 转回
 * {@code CrowdAction} 应用）。</p>
 */
public final class CrowdActionCodec {

    private CrowdActionCodec() {
    }

    /**
     * 人群行动 → 策略链 Map（键与 {@code CrowdDecisionParse.CrowdAction} 字段对应）。
     *
     * @param a 人群行动（null → 空 Map）
     *
     * @return Map{actionText, actionType, target, reason}
     */
    public static Map<String, Object> toMap(CrowdDecisionParse.CrowdAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null) {
            return m;
        }
        m.put("actionText", a.action());
        m.put("actionType", a.actionType());
        m.put("target", a.target());
        m.put("reason", a.reason());
        return m;
    }

    /**
     * 策略链 Map → 人群行动。
     *
     * @param m 策略链产物 Map（null/缺 actionText → null）
     *
     * @return 人群行动（null=无法转换）
     */
    public static CrowdDecisionParse.CrowdAction fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        Object text = m.get("actionText");
        if (text == null || String.valueOf(text).isBlank()) {
            return null;
        }
        Object type = m.get("actionType");
        Object target = m.get("target");
        Object reason = m.get("reason");
        return new CrowdDecisionParse.CrowdAction(
                String.valueOf(text),
                type == null ? null : String.valueOf(type),
                target == null ? null : String.valueOf(target),
                reason == null ? null : String.valueOf(reason));
    }
}
