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

package com.holzyn.actor.domain.combat.model;

/**
 * 一次攻击的结算结果。
 *
 * <p>{@code reason} 非空表示这一击没打出去（目标不在/没家伙），此时
 * {@code damage=0}、{@code healthBefore==healthAfter}。让「为什么没打成」可追溯，
 * 而不是留一个看不出原因的全零结果。</p>
 *
 * @param attackerRef      攻击者 ref
 * @param targetRef        目标 ref
 * @param weaponItemKey    实际用的武器键（null=空手）
 * @param weaponName       武器展示名（null=空手）
 * @param damage           造成伤害（0=没打中/空手）
 * @param healthBefore     目标原健康
 * @param healthAfter      目标现健康
 * @param durabilityBefore 武器原耐久（-1=没有武器）
 * @param durabilityAfter  武器现耐久
 * @param weaponBroke      这一击把武器打断了
 * @param autoDrawn        是否是「顺手抽出武器」（原本手上没拿）
 * @param targetPresent    目标是否在跟前
 * @param reason           没打出去的原因码（null=打成了）
 * @param armorName        挡下这一击的护具名（null=没穿/没挡住）
 * @param armorAbsorbed    被护具吃掉多少伤害（0=没挡）
 */
public record CombatResult(String attackerRef, String targetRef, String weaponItemKey, String weaponName,
                           int damage, int healthBefore, int healthAfter,
                           int durabilityBefore, int durabilityAfter,
                           boolean weaponBroke, boolean autoDrawn, boolean targetPresent, String reason,
                           String armorName, int armorAbsorbed) {

    /**
     * 兼容旧调用点的 13 参构造（护具字段留空）。
     *
     * <p>为什么留着：{@code CombatResult} 是与冒烟共用的结果契约，已有十来个
     * 构造点。加两个分量就逼着所有既有调用点改写，属于「为了新功能把老代码全翻一遍」——
     * 旧构造委托到新构造，既有调用点零改动。</p>
     */
    public CombatResult(String attackerRef, String targetRef, String weaponItemKey, String weaponName,
                        int damage, int healthBefore, int healthAfter,
                        int durabilityBefore, int durabilityAfter,
                        boolean weaponBroke, boolean autoDrawn, boolean targetPresent, String reason) {
        this(attackerRef, targetRef, weaponItemKey, weaponName, damage, healthBefore, healthAfter,
                durabilityBefore, durabilityAfter, weaponBroke, autoDrawn, targetPresent, reason,
                null, 0);
    }

    /** 打成了（造成了伤害）。 */
    public boolean landed() {
        return reason == null && damage > 0;
    }

    /** 是否被护具挡掉了一部分（旁人能看出「他穿着东西」）。 */
    public boolean armored() {
        return armorAbsorbed > 0;
    }
}
