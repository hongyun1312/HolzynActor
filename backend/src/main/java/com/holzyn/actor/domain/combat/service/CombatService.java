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

package com.holzyn.actor.domain.combat.service;

import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.combat.model.CombatRequest;
import com.holzyn.actor.domain.combat.model.CombatResult;
import com.holzyn.actor.domain.equip.model.EquipmentSlot;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.item.wear.WearRules;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 一次攻击的结算：扣血 + 磨损武器 + 让被打的人记住。
 *
 * <p><b>不新增表</b>：这一击没有自己的账。伤害落在 {@code actor_npc_state.health}
 * （由 {@link NpcStateService} 写），磨损落在 {@code actor_item_stack.durability}
 * （由物品域写），记忆落在既有记忆系统。本类只做编排，<b>不直接写任何表</b>——
 * 于是「单一写入口」不被打破。</p>
 *
 * <p><b>幂等</b>：以「磨损」这一笔账作为整击的幂等载体——重放时物品域返回
 * {@code replayed=true}，这里立刻收手，不再扣一次血。为什么不用一张新表记这一击：
 * 磨损账本来就必须写（每次挥砍都掉耐久），借它当哨兵是零成本的。</p>
 *
 * <p><b>没有死亡</b>：{@code health} 最低到 0（卧床），本期不做死亡与继承
 * （§4.4.3 明确不在范围内）。</p>
 */
@Slf4j
@Service
public class CombatService {

    /** 原因码：请求非法 */
    public static final String REASON_BAD_REQUEST = "BAD_REQUEST";

    /** 原因码：目标不存在 */
    public static final String REASON_NO_TARGET = "NO_TARGET";

    /** 原因码：目标不在跟前 */
    public static final String REASON_TOO_FAR = "TOO_FAR";

    /** 原因码：空手 */
    public static final String REASON_NO_WEAPON = "NO_WEAPON";

    /** 原因码：家伙已经断了 */
    public static final String REASON_WEAPON_BROKEN = "WEAPON_BROKEN";

    /** 原因码：这一击已经结算过（幂等重放） */
    public static final String REASON_REPLAYED = "REPLAYED";

    private final WearProperties wearProperties;

    private final ItemProperties itemProperties;

    /** 物品端口（磨损/读背包；未装配时整条战斗链路降级） */
    @Autowired(required = false)
    private InventoryPort inventoryPort;

    /** 装备（手上那件/顺手抽刀） */
    @Autowired(required = false)
    private EquipmentService equipmentService;

    /** 角色状态（健康唯一写者） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 记忆（让被打的人记住这一下） */
    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;

    /** 角色仓储（只读：判在不在跟前） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 构造（新类允许构造器注入）。
     *
     * @param wearProperties 损耗域配置
     * @param itemProperties 物品域配置
     */
    public CombatService(WearProperties wearProperties, ItemProperties itemProperties) {
        this.wearProperties = wearProperties;
        this.itemProperties = itemProperties;
    }

    /**
     * 战斗是否生效：损耗域 + 物品域都开着（没有可信的持有栈就没法算武器）。
     *
     * @return true=可以结算攻击
     */
    public boolean enabled() {
        return wearProperties != null && wearProperties.isEnabled()
                && itemProperties != null && itemProperties.isEnabled()
                && inventoryPort != null;
    }

    /**
     * 打一下。
     *
     * @param request 请求
     *
     * @return 结算结果（{@code reason} 非空=没打出去）
     */
    @Transactional
    public CombatResult strike(CombatRequest request) {
        if (request == null || request.projectId() == null
                || request.attackerRef() == null || request.attackerRef().isBlank()
                || request.targetRef() == null || request.targetRef().isBlank()) {
            return failed(request, REASON_BAD_REQUEST);
        }
        Long projectId = request.projectId();
        String attacker = request.attackerRef().trim();
        String target = request.targetRef().trim();

        // ① 目标在不在跟前：两人位置都已知且不同 → 够不着
        ActorCharacter targetChar = characterOf(target);
        ActorCharacter attackerChar = characterOf(attacker);
        if (targetChar == null) {
            return failed(request, REASON_NO_TARGET);
        }
        if (attackerChar != null && targetChar.getLocation() != null && attackerChar.getLocation() != null
                && !targetChar.getLocation().trim().equalsIgnoreCase(attackerChar.getLocation().trim())) {
            return failed(request, REASON_TOO_FAR);
        }

        // ② 用哪件家伙：指定 → 手上那件 → 身上最好的武器（顺手抽出来）
        ResolvedWeapon weapon = resolveWeapon(projectId, attacker, request.weaponItemKey());
        if (weapon == null) {
            return failed(request, REASON_NO_WEAPON);
        }
        if (weapon.durability() <= 0) {
            return failed(request, REASON_WEAPON_BROKEN);
        }
        int damage = WearRules.damageOf(weapon.quality(), weapon.durability(),
                wearProperties.getBaseDamage());

        // ②b 护具（对接）：对方身上穿的那件真的挡一下。
        // 不挡的话，「装备」只剩「手上拿什么」一半——穿衣服和没穿衣服挨打一样疼，
        // 玩家一眼就能看出这是假的。按成色折算：新衣挡得多，烂衣挡得少，且至少留 1 点伤害
        // （挡到 0 会让「砍不动」变成一种无解的僵局）。
        Armor armor = resolveArmor(projectId, target);
        int absorbed = 0;
        if (armor != null) {
            double ratio = Math.max(0.0, Math.min(1.0, wearProperties.getArmorReduction()));
            absorbed = (int) Math.round(damage * ratio * (armor.condition() / 100.0));
            absorbed = Math.min(absorbed, Math.max(0, damage - 1));
            damage -= absorbed;
        }

        // ③ 磨损（同时是整击的幂等哨兵）：先磨损，重放就立刻收手
        int wear = Math.max(1, wearProperties.getAttackWear());
        DurabilityChange change = inventoryPort.wear(projectId, attacker, weapon.itemKey(), wear,
                request.eventId(), request.gameSecond(), suffix(request.idempotencyKey(), "wear"));
        if (change.replayed()) {
            log.info("[战斗] 重放，跳过：attacker={} target={} idem={}", attacker, target,
                    request.idempotencyKey());
            return failed(request, REASON_REPLAYED);
        }
        if (!change.present()) {
            // 磨损时东西已经不在了（并发卖掉/烂掉）：这一击不算数
            return failed(request, REASON_NO_WEAPON);
        }

        // ④ 顺手抽刀：原本手上没拿，打完就顺势握在手里（旁人从此看得见）
        boolean autoDrawn = false;
        if (equipmentService != null && equipmentService.enabled()) {
            try {
                Optional<EquipmentView> hand = equipmentService.handOf(projectId, attacker);
                if (hand.isEmpty()) {
                    equipmentService.equip(projectId, attacker, EquipmentSlot.HAND, weapon.itemKey(),
                            request.gameSecond());
                    autoDrawn = true;
                }
            } catch (RuntimeException e) {
                // 抽不出来不影响这一击（东西刚断时就会走到这里）
                log.debug("[战斗] 顺手抽刀失败（忽略）：{} {} : {}", attacker, weapon.itemKey(), e.getMessage());
            }
        }

        // ⑤ 扣血（健康唯一写者）
        int before = -1;
        int after = -1;
        if (npcStateService != null) {
            before = healthOf(projectId, target);
            after = npcStateService.applyHealthDelta(projectId, target, -damage, "combat");
            if (after < 0) {
                after = before;
            }
        }

        // ⑥ 让被打的人记住（既有记忆系统，不新增表）
        if (agentMemoryService != null) {
            try {
                agentMemoryService.recordObservation(projectId, target,
                        "被「" + displayName(attackerChar, attacker) + "」用" + weapon.name() + "砍伤（伤 " + damage + "）"
                                + (absorbed > 0 ? "，" + armor.name() + "挡了一下" : ""),
                        4, null);
            } catch (RuntimeException e) {
                log.warn("[战斗] 写入记忆失败（不影响伤害）：target={} : {}", target, e.getMessage());
            }
        }

        log.info("[战斗] {} 用{}（成色 {}）打 {}：伤害 {} 血 {}→{} 耐久 {}→{}{}{}",
                attacker, weapon.name(), WearRules.conditionOf(weapon.quality(), weapon.durability()),
                target, damage, before, after, change.before(), change.after(),
                change.broke() ? "（断了）" : "",
                absorbed > 0 ? "（" + armor.name() + " 挡掉 " + absorbed + "）" : "");
        return new CombatResult(attacker, target, weapon.itemKey(), weapon.name(), damage,
                before, after, change.before(), change.after(), change.broke(), autoDrawn, true, null,
                armor == null ? null : armor.name(), absorbed);
    }

    // ==================== 武器解析 ====================

    /** 一件被选中上阵的武器。 */
    private record ResolvedWeapon(String itemKey, String name, int quality, int durability) {
    }

    /** 挡在身上的那件护具（body 槽）。 */
    private record Armor(String name, int condition) {
    }

    /**
     * 目标身上穿的护具（{@code body} 槽；没穿/穿坏了/未装配返回 null）。
     *
     * <p>只认 {@code body}：手上那件是武器，不是盾——把它算成防御会让「握着刀」比
     * 「空着手」更抗打，那是反直觉的。</p>
     */
    private Armor resolveArmor(Long projectId, String target) {
        if (equipmentService == null || !equipmentService.enabled()) {
            return null;
        }
        try {
            for (EquipmentView eq : equipmentService.equipmentOf(projectId, target)) {
                if (eq != null && "body".equalsIgnoreCase(eq.slot()) && eq.condition() > 0) {
                    return new Armor(eq.name(), eq.condition());
                }
            }
        } catch (RuntimeException e) {
            log.debug("[战斗] 读取护具失败（按未穿处理）：{} : {}", target, e.getMessage());
        }
        return null;
    }

    /**
     * 选武器：指定键 → 手上那件 → 背包里最好的武器。
     *
     * <p>「顺手抽出来」不是偷懒：NPC 的行动文本常常就是「拔刀砍过去」，
     * 要求他先单独执行一次「装备」再攻击，会让真实行为被流程卡死。</p>
     */
    private ResolvedWeapon resolveWeapon(Long projectId, String attacker, String wantedKey) {
        if (wantedKey != null && !wantedKey.isBlank()) {
            Optional<ItemView> layer = inventoryPort.bestLayerOf(projectId, attacker, wantedKey);
            return layer.map(this::of).orElse(null);
        }
        if (equipmentService != null && equipmentService.enabled()) {
            Optional<EquipmentView> hand = equipmentService.handOf(projectId, attacker);
            if (hand.isPresent()) {
                EquipmentView view = hand.get();
                return new ResolvedWeapon(view.itemKey(), view.name(), view.quality(), view.durability());
            }
        }
        List<ItemView> items = inventoryPort.list(projectId, attacker);
        ItemView best = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() <= 0 || !item.weaponLike()) {
                continue;
            }
            if (best == null || WearRules.conditionOf(item.quality(), item.durability())
                    > WearRules.conditionOf(best.quality(), best.durability())) {
                best = item;
            }
        }
        return best == null ? null : of(best);
    }

    private ResolvedWeapon of(ItemView item) {
        return new ResolvedWeapon(item.itemKey(), item.name(), item.quality(), item.durability());
    }

    // ==================== 辅助 ====================

    private CombatResult failed(CombatRequest request, String reason) {
        String attacker = request == null ? null : request.attackerRef();
        String target = request == null ? null : request.targetRef();
        boolean present = !REASON_NO_TARGET.equals(reason) && !REASON_BAD_REQUEST.equals(reason);
        return new CombatResult(attacker, target, null, null, 0, -1, -1, -1, -1, false, false, present, reason);
    }

    private ActorCharacter characterOf(String ref) {
        if (characterRepository == null || ref == null) {
            return null;
        }
        Long id = ItemRefs.idOf(ref);
        if (id == null) {
            return null;
        }
        try {
            return characterRepository.findById(id).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int healthOf(Long projectId, String ref) {
        if (npcStateService == null) {
            return -1;
        }
        try {
            var state = npcStateService.stateFor(projectId, ref);
            return state == null || state.getHealth() == null ? 100 : state.getHealth();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String displayName(ActorCharacter actor, String fallback) {
        return actor == null || actor.getName() == null || actor.getName().isBlank()
                ? fallback : actor.getName();
    }

    /** 幂等键加后缀（同一击里磨损与其它步骤分开记账）。 */
    private static String suffix(String base, String part) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String key = base.trim() + ":" + part;
        return key.length() > 80 ? key.substring(0, 80) : key;
    }
}
