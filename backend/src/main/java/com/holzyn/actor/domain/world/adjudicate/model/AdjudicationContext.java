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

package com.holzyn.actor.domain.world.adjudicate.model;

import tools.jackson.databind.JsonNode;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次裁决的只读上下文（§4.5）。
 *
 * <p>数据由 {@code ActionAdjudicationService} 在事务外组装；规则引擎只读本对象，
 * 不再自己查库，保证规则单测无需 Spring。</p>
 */
@Data
@Builder
public class AdjudicationContext {

    /** 项目 ID */
    private Long projectId;

    /** 行动者 */
    private ActorCharacter actor;

    /** 行动计划 */
    private ActorActionPlan plan;

    /** 意图 */
    private IntentKind intent;

    /** 原始决策 JSON */
    private JsonNode decision;

    /** 决策参数（params） */
    private JsonNode args;

    /** 当前游戏秒 */
    private long gameSecond;

    /** 当前地点名 */
    private String locationName;

    /** 目标名（地点或角色） */
    private String targetName;

    /** 目标角色 ref（可空） */
    private String targetRef;

    /** 目标地点是否存在（null=未解析） */
    private Boolean targetLocationExists;

    /** 目标角色是否在场（null=未解析） */
    private Boolean targetPresent;

    /** 路线事实（route:{from}-{to}；null=无记录） */
    private ActorWorldFact routeFact;

    /** 目标地点设施事实（facility:loc:{id}；null=无记录） */
    private ActorWorldFact facilityFact;

    /** 与目标角色的 affinity（无关系=0） */
    private int relationToTarget;

    /** 目标角色 reputation 分值（null=无记录） */
    private Integer reputation;

    /** 行动重要度（决策 urgency 优先，回退角色 importance） */
    private int importance;

    /** 行动者精力（null=无状态记录） */
    private Integer energy;

    /** 已知事实（供 AI 裁决与 reputation 读取；可空） */
    private List<ActorWorldFact> knownFacts;

    /** 行动者自己的物品清单（接入；null=未知）。一个品质层一条，见 {@link ItemView} */
    private List<ItemView> inventory;

    /** 行动者物品数据是否可用（item.enabled=true 且端口已装配才为 true） */
    private boolean inventoryKnown;

    /**
     * 对方货架/柜台库存（恒空；交易阶段由货架读取填充）。
     *
     * <p>为什么不复用 {@link #inventory}：曾把「自己的背包」当成「店里的货」，
     * 结果物品域一开就会把所有人判成「掌柜摊手：卖完了」。两者必须分开。</p>
     */
    private List<ItemView> counterpartyStock;

    /** 对方库存是否已知（恒 false → TRADE 保持 UNSURE，不误判 NO_STOCK） */
    private boolean counterpartyStockKnown;

    /** 配方是否已知（起=true 表示已解析出具体配方；false → CRAFT 保持 UNSURE） */
    private boolean recipeKnown;

    // ====================交易上下文 ====================

    /**
     * 卖家 ref：买入时 = 目标地点 {@code loc:{id}}（柜台）/ 目标角色 {@code npc:{id}}；
     * 卖出时 = 行动者自己。null=未解析。
     */
    private String sellerRef;

    /** 买家 ref：买入时 = 行动者自己；卖出时 = 目标角色。null=未解析 */
    private String buyerRef;

    /** 交易物品键（决策 {@code params.itemKey}；null=决策没写具体货物，只判「有货可谈」） */
    private String tradeItemKey;

    /** 交易数量（0=未写，按 1 件处理） */
    private int tradeQty;

    /** 当前单价（null=无报价/市场未启用） */
    private Integer unitPrice;

    /** 计价货币物品键（默认 coin） */
    private String currencyKey;

    /** 买家身上的货币数量（null=未知） */
    private Integer funds;

    /** 是否赊账（决策 {@code params.credit} 或行动文本含「赊」） */
    private boolean credit;

    /**
     * 买方 reputation（赊账看的是<b>买方</b>信用）。
     *
     * <p> {@link #reputation} 是<b>对方</b>的声望，曾被误用于赊账判定——
     * 「掌柜的声望差」和「我赖账」是两件事，把它们分开。卖出时买方是目标角色，
     * 因此这里存的是「本次交易里付钱那一方」的信用。</p>
     */
    private Integer buyerReputation;

    // ====================制作上下文 ====================

    /**
     * 制作域是否可用（{@code craft.enabled=true} 且物品端口已装配）。
     *
     * <p>与 {@link #recipeKnown} 分开：前者是「这个世界的制作系统开着吗」，后者是
     * 「这一次到底要做哪件东西、配方找没找到」。混成一个布尔会让「系统关了」和
     * 「想做的东西没人会做」在日志里无法区分。</p>
     */
    private boolean craftDataKnown;

    /** 解析出的配方（null=没解析出来；规则层据此保持 UNSURE，不臆断） */
    private CraftRecipeView recipe;

    /** 这次干活要用的设施 ref（如 {@code facility:loc:65}；null=没有可用设施） */
    private String craftFacilityRef;

    /** 设施类型（来自设施事实的 attributes_json.type；null=未知） */
    private String craftFacilityType;

    /** 设施展示名（如「铁匠铺」；null=未知） */
    private String craftFacilityName;

    /** 建造落点地点 ID（可空；null 时结算侧按地点名兜底） */
    private Long craftLocationId;

    /** 一次做几份（已按 {@code craft.max-batches-per-job} 钳过，规则层直接用） */
    private int craftBatches;

    /** 手上还压着的活（配方键；null=手上没活）。有活时 R-CRAFT-01 判 BUSY */
    private String craftBusyRecipeKey;

    /** 设施当前被谁占着（null=空着）。与 {@link #craftFacilityRef} 同一次解析的结果 */
    private String craftOccupant;

    // ====================损耗装备战斗上下文 ====================

    /**
     * 损耗域是否可用（{@code wear.enabled=true} 且物品端口已装配）。
     *
     * <p>关着时 R-ATTACK-01 走 「身上有武器即算」旧路径、R-REPAIR-01 与
     * R-EQUIP-01 直接放行（{@code R-DEFAULT}），行为与逐字节一致。</p>
     */
    private boolean wearDataKnown;

    /** 当前装备（null=未知；空列表=身上没戴东西）。按槽位排序 */
    private List<EquipmentView> equipment;

    /** 装备数据是否可信（{@code equipmentKnown=false} 时规则层不臆断「空手」） */
    private boolean equipmentKnown;

    /** 手上那件（null=空手）。与 {@link #equipment} 同一次解析的结果 */
    private EquipmentView handEquipment;

    /** 目标角色的健康 0–100（null=无状态记录） */
    private Integer targetHealth;

    /** 决策里说要修的东西（null=没写；规则层据此不臆断） */
    private String repairItemKey;

    /** 解析出的待修那一层（null=身上没有这件） */
    private ItemView repairTarget;

    /** 决策里说要穿/戴的东西（null=没写） */
    private String equipItemKey;

    /** 决策里写明的槽位（null=按物品定义） */
    private String equipSlot;

    /** 是否「脱下/放下」而不是「穿上」 */
    private boolean equipUnequip;
}
