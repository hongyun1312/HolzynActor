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

package com.holzyn.actor.domain.world.effect.model;

import java.util.Set;

/**
 * 效果 op 闭集（§4.6）。
 *
 * <p>闭集是“效果可审计”的前提：AI/规则输出未知 op 直接失败，而不是静默跳过
 * 让世界出现“看起来执行了但什么都没发生”的行动。</p>
 */
public final class EffectOps {

    public static final String FACT_UPSERT = "fact.upsert";
    public static final String FACT_EXPIRE = "fact.expire";
    public static final String INVENTORY_TRANSFER = "inventory.transfer";
    public static final String INVENTORY_CONSUME = "inventory.consume";
    public static final String MARKET_TRADE = "market.trade";
    /** 赊账结清（还款闭环）：只由规则层在「还钱/还账」时发出。 */
    public static final String MARKET_SETTLE = "market.settle";
    public static final String CRAFT_MAKE = "craft.make";
    public static final String ITEM_EQUIP = "item.equip";
    public static final String ITEM_REPAIR = "item.repair";
    public static final String COMBAT_STRIKE = "combat.strike";
    public static final String RELATION_DELTA = "relation.delta";
    public static final String MEMORY_ADD = "memory.add";
    public static final String INFO_PACKET = "info.packet";
    public static final String ENTITY_UPSERT = "entity.upsert";
    public static final String STATE_URGENCY = "state.urgency";
    public static final String SCHEDULE_PLAN = "schedule.plan";

    /**
     * 16 个合法 op。
     *
     * <p>{@code market.trade} 是新增：一笔成交要同时扣货、收钱、更新报价、记流水，
     * 拆成两条 {@code inventory.transfer} 会出现「货扣了钱没付」的中间态，且报价无法在
     * 同一处刷新。它<b>只由规则层发出</b>（{@code AdjudicationParse} 的 AI 白名单不含它），
     * 避免 AI 直接指挥钱包。</p>
     *
     * <p>{@code craft.make} 是新增，理由同族：制作要「按配方扣多份材料 + 开一件计时作业 +
     * 占用设施」，拆成若干条 {@code inventory.transfer} 会出现「料扣了活没开」或
     * 「料扣了一半」，且作业与占用无法原子建立。同样<b>只由规则层发出</b>。</p>
     *
     * <p>再加三个，都是「一步做不干净」的动作：</p>
     * <ul>
     *   <li>{@code item.equip}：换装要「校验物品 + 改槽位 + 重写 equip 事实」，拆开会出现
     *       「事实说戴着、库里没戴」；</li>
     *   <li>{@code item.repair}：修耐久要「找到那一层 + 拆层 + 并回新耐久层 + 记账」；</li>
     *   <li>{@code combat.strike}：一击要「扣血 + 磨损武器（可能打断）+ 顺手抽刀 + 让对方记住」，
     *       拆开会出现「血掉了刀没磨」或「刀断了人没事」。</li>
     * </ul>
     * <p>三者同样<b>只由规则层发出</b>：AI 白名单仍是 9 个，不许 AI 直接指挥伤害与装备。</p>
     *
     * <p>{@code inventory.consume} 是对接新增的第 15 个：吃饭/喝水是「从背包里拿掉
     * 一件、没有目标持有者」，用 {@code inventory.transfer} 顶替必须硬塞一个虚拟持有者
     * （{@code void:}），那会在账本里多出一个谁也不认识的「仓库」，把全库总量和前端台账
     * 一起污染。它同样<b>只由规则层发出</b>（R-REST-01：行动文本点名了身上那件吃的）。</p>
     */
    public static final Set<String> ALL = Set.of(
            FACT_UPSERT, FACT_EXPIRE, INVENTORY_TRANSFER, INVENTORY_CONSUME, MARKET_TRADE, MARKET_SETTLE,
            CRAFT_MAKE,
            ITEM_EQUIP, ITEM_REPAIR, COMBAT_STRIKE, RELATION_DELTA,
            MEMORY_ADD, INFO_PACKET, ENTITY_UPSERT, STATE_URGENCY, SCHEDULE_PLAN);

    private EffectOps() {
    }

    /**
     * 校验 op 是否在闭集内。
     *
     * @param op 原始 op
     * @return 去空白后的 op
     * @throws IllegalArgumentException 未知 op
     */
    public static String require(String op) {
        String value = op == null ? "" : op.trim();
        if (!ALL.contains(value)) {
            throw new IllegalArgumentException("未知效果 op：" + op + "（允许：" + ALL + "）");
        }
        return value;
    }
}
