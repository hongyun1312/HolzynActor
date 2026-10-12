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

package com.holzyn.actor.domain.growth.space;

/**
 * 空间重算触发码枚举。
 * <p>事件驱动重算的触发来源——检测到有意义变化时调用
 * {@code GrowthSpaceService.triggerRegenerate}；{@code periodic} 是定期兜底（focus 每 1 游戏小时 /
 * ambient 每 6 游戏小时）；{@code MANUAL} 是调试接口强制重算。</p>
 * <p>将新增 {@code mask_context_change}（面具情境切换，随社会面具机制落地）。</p>
 */
public enum GrowthSpaceTrigger {

    /** 阶段跨越（年龄跨过阶段边界） */
    STAGE_TRANSITION("stage_transition", "阶段跨越"),
    /** 生理越界（urgency 升档 / health 跌破阈值 / 极端需求） */
    PHYSIO_BREACH("physio_breach", "生理越界"),
    /** 情绪漂移（|ΔPAD| 超阈值，默认 0.35） */
    EMOTION_SHIFT("emotion_shift", "情绪漂移"),
    /** 重大事件（事件 importance ≥ 4 且涉及本人） */
    MAJOR_EVENT("major_event", "重大事件"),
    /** 在场者变化（同地点参与者进入/离开） */
    PARTICIPANT_CHANGE("participant_change", "在场者变化"),
    /** 计划失效（计划失败/被打断/目标失效） */
    GOAL_INVALID("goal_invalid", "计划失效"),
    /** 关系跨档（好感/信任跨档，如 acquaintance→friend） */
    RELATION_JUMP("relation_jump", "关系跨档"),
    /** 面具情境切换 */
    MASK_CONTEXT_CHANGE("mask_context_change", "面具情境切换"),
    /** 环境剧变（天气/季节/宏观事件剧变） */
    ENV_SHOCK("env_shock", "环境剧变"),
    /** LOD 升档（认知 LOD 从低档升到高档） */
    LOD_PROMOTE("lod_promote", "认知升档"),
    /** 定期兜底（focus 每 1 游戏小时 / ambient 每 6 游戏小时） */
    PERIODIC("periodic", "定期兜底"),
    /** 手动（调试接口强制重算） */
    MANUAL("manual", "手动重算");

    /** 落库码（GrowthSpace.trigger_code） */
    private final String code;
    /** 中文名（日志/调试） */
    private final String label;

    GrowthSpaceTrigger(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 由落库码解析（大小写不敏感；未知回退 PERIODIC——零阻断）。 */
    public static GrowthSpaceTrigger of(String code) {
        if (code == null || code.isBlank()) {
            return PERIODIC;
        }
        for (GrowthSpaceTrigger t : values()) {
            if (t.code().equalsIgnoreCase(code.trim()) || t.name().equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        return PERIODIC;
    }
}
