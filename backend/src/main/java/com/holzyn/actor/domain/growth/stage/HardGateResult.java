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

package com.holzyn.actor.domain.growth.stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段硬门控结果。
 * <p>四类输出（对齐设计文档 §5.1「输出：允许能力码清单 / 禁止行为清单 / 生理越界清单 / 道德红线清单」）：</p>
 * <ul>
 * <li>{@code allowedAbilities}：允许能力码清单（能力门控表允许集，阶段生理事实）；</li>
 * <li>{@code forbidden}：禁止行为清单（HARD 约束 + 生理越界[体质弱点/健康告急] + 道德红线[⑤ 维底线]）；</li>
 * <li>{@code softConstraints}：软约束文案（SOFT 约束行，进 Prompt 注入段）；</li>
 * <li>{@code capacity} 与 {@code capacityModifiers}：机能曲线值与其派生修正（恢复/精力/饥渴/感官/疾病）。</li>
 * </ul>
 * <p><b>消费方</b>：空间生成（AI 只许在允许集内发明候选）、决策前置（把硬约束注入 Prompt）、
 * 护栏后置（stage_compliance / space_compliance 校验决策与空间是否越界）——三处共用同一策略源。</p>
 *
 * @param npcRef 统一角色引用 npc:{id}
 *
 * @param stage 当前阶段（GrowthStage.name()；null=档案无阶段，按宽松处理）
 *
 * @param age 当前年龄（游戏年；null=未知）
 *
 * @param capacity 机能曲线值 0~1（峰值 YOUTH/PRIME 附近，衰老后下降）
 *
 * @param allowedAbilities 允许能力码清单（GrowthAbility.name()）
 *
 * @param forbidden 禁止行为清单（what + reason；HARD 约束 + 生理越界 + 道德红线）
 *
 * @param softConstraints 软约束文案清单（进 Prompt 注入段）
 *
 * @param capacityModifiers 机能曲线派生的状态修正（energyScale/recoverScale/hungerScale/coldSens/
 * heatSens/painFactor/diseaseRisk 各倍率，供状态模块日级重算）
 */
public record HardGateResult(
        String npcRef,
        String stage,
        Integer age,
        double capacity,
        List<String> allowedAbilities,
        List<ForbiddenItem> forbidden,
        List<String> softConstraints,
        java.util.Map<String, Double> capacityModifiers) {

    /** 禁止行为单条（what=行为描述；reason=禁止原因，审计/告警用）。 */
    public record ForbiddenItem(String what, String reason) {
        @Override
        public String toString() {
            return what + "（" + reason + "）";
        }
    }

    /** 空门控结果（档案无阶段/行不存在时的宽松兜底：不禁止任何行为，机能 1.0——零阻断）。 */
    public static HardGateResult lenient(String npcRef) {
        List<String> all = new ArrayList<>();
        for (GrowthAbility a : GrowthAbility.values()) {
            all.add(a.name());
        }
        return new HardGateResult(npcRef, null, null, 1.0, all,
                List.of(), List.of(), java.util.Map.of());
    }

    /** 是否禁止某行为（what 精确匹配，大小写不敏感）。 */
    public boolean forbids(String what) {
        if (what == null || forbidden == null) {
            return false;
        }
        for (ForbiddenItem f : forbidden) {
            if (f.what() != null && f.what().equalsIgnoreCase(what.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 是否允许某能力（能力码，大小写不敏感；空列表=未装配，宽松放行）。 */
    public boolean allows(String abilityCode) {
        if (allowedAbilities == null || allowedAbilities.isEmpty()) {
            return true;
        }
        for (String a : allowedAbilities) {
            if (a != null && a.equalsIgnoreCase(abilityCode)) {
                return true;
            }
        }
        return false;
    }

    /** 取某能力是否被明确禁止（存在该能力码且不在允许集 = 禁止）。 */
    public boolean denies(String abilityCode) {
        return allowedAbilities != null && !allowedAbilities.isEmpty()
                && abilityCode != null && !allows(abilityCode);
    }

    /** 硬门控注入 Prompt 的紧凑文案（【硬约束】段；禁止行 + 软约束行，按字数上限截断）。 */
    public String renderConstraints(int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (forbidden != null) {
            for (ForbiddenItem f : forbidden) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("禁止：").append(f.what());
                if (f.reason() != null && !f.reason().isBlank()) {
                    sb.append('（').append(f.reason()).append('）');
                }
            }
        }
        if (softConstraints != null) {
            for (String s : softConstraints) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("受限：").append(s);
            }
        }
        String text = sb.toString();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
