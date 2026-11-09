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

package com.holzyn.actor.domain.equip.service;

import com.holzyn.actor.domain.equip.entity.ActorItemEquipment;
import com.holzyn.actor.domain.equip.model.EquipmentRejectedException;
import com.holzyn.actor.domain.equip.model.EquipmentSlot;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.repository.ActorItemEquipmentRepository;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 装备唯一写入口。
 *
 * <p><b>单一写入口</b>：{@code actor_item_equipment} 只由本类写。品质/耐久/数量仍归
 * {@link InventoryService}（持有栈的唯一写者），本类<b>不直接</b>碰
 * {@code actor_item_stack}——装备只决定「谁在用哪件」，东西本身的损耗由物品域记账。</p>
 *
 * <p><b>自愈</b>：装备行只记 {@code item_key}。东西被卖掉、吃掉、烂掉之后，
 * 读的时候按 {@code item_key} 在背包里找不到那一层，就说明「没了」——
 * 当场删掉这条装备行并留日志，绝不让「他腰间别着一把已经不存在的刀」活过一轮读取。</p>
 *
 * <p><b>可见性</b>：装备变化同步一条 {@code equip:{ref}} 事实（默认 location 可见），
 * 于是旁人到场就能「看见他手里握着什么」——这是里「装备是世界的状态，不只是
 * 自己的属性」那一条的具体落点。</p>
 */
@Slf4j
@Service
public class EquipmentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 事实类型：身上/手上带着什么 */
    public static final String FACT_TYPE = "equip";

    /** 事实键前缀 */
    public static final String FACT_KEY_PREFIX = "equip:";

    private final ActorItemEquipmentRepository repository;

    private final InventoryService inventoryService;

    private final ItemProperties itemProperties;

    private final WearProperties wearProperties;

    /** 事实层（装备可见性；未装配时只写库，不影响装备本身） */
    @Autowired(required = false)
    private WorldFactService worldFactService;

    /** 事实查询（expire 需要先读出既有行） */
    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    /** 角色仓储（只读：把装备事实挂到人所在地点） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 地点仓储（只读：地名 ↔ 地点 ID） */
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    /**
     * 构造（新类允许构造器注入；既有服务不改签名）。
     *
     * @param repository       装备仓储
     * @param inventoryService 物品唯一写入口（只读使用：解析「手上这件是哪件」）
     * @param itemProperties   物品域配置（判断物品域是否开启）
     * @param wearProperties   损耗域配置
     */
    public EquipmentService(ActorItemEquipmentRepository repository, InventoryService inventoryService,
                            ItemProperties itemProperties, WearProperties wearProperties) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.itemProperties = itemProperties;
        this.wearProperties = wearProperties;
    }

    // ==================== 开关 ====================

    /**
     * 装备是否生效：损耗域开关 + 物品域开关。
     *
     * <p>物品域关着时没有可信的持有栈，装备行会变成「凭空挂着」的假状态，所以一起关。</p>
     *
     * @return true=可以穿脱/解析装备
     */
    public boolean enabled() {
        return wearProperties != null && wearProperties.isEnabled()
                && itemProperties != null && itemProperties.isEnabled();
    }

    // ==================== 写入口 ====================

    /**
     * 穿/拿（幂等：同一槽位重复穿同一件不报错，只刷新起始时间）。
     *
     * @param projectId  项目 ID
     * @param holderRef  持有者（npc:{id} 等）
     * @param slot       槽位（空=按物品定义）
     * @param itemKey    物品键
     * @param gameSecond 游戏秒
     *
     * @return 装备视图
     *
     * @throws EquipmentRejectedException 未登记/不可装备/槽位不符/没带在身上
     */
    @Transactional
    public EquipmentView equip(Long projectId, String holderRef, String slot, String itemKey, long gameSecond) {
        String holder = ItemRefs.requireHolder(holderRef, "holder");
        String key = InventoryService.normalizeItemKey(itemKey);
        ActorItemDef def = inventoryService.findDef(projectId, key)
                .orElseThrow(() -> new EquipmentRejectedException(EquipmentRejectedException.NOT_FOUND,
                        "物品未登记：" + key));
        String defSlot = EquipmentSlot.normalize(def.getEquipSlot());
        if (defSlot == null) {
            throw new EquipmentRejectedException(EquipmentRejectedException.NOT_EQUIPPABLE,
                    "这件东西没有装备槽：" + key);
        }
        String wanted = EquipmentSlot.normalize(slot);
        if (wanted == null) {
            wanted = defSlot;
        }
        if (!wanted.equals(defSlot)) {
            throw new EquipmentRejectedException(EquipmentRejectedException.WRONG_SLOT,
                    "「" + key + "」该装在 " + defSlot + " 槽，不是 " + wanted);
        }
        ItemView layer = inventoryService.bestLayerOf(projectId, holder, key)
                .orElseThrow(() -> new EquipmentRejectedException(EquipmentRejectedException.NOT_CARRIED,
                        "身上没有这件东西：" + key));
        if (EquipmentSlot.HAND.equals(wanted) && !layer.weaponLike()) {
            throw new EquipmentRejectedException(EquipmentRejectedException.NOT_A_WEAPON,
                    "手里只能拿武器/工具：" + key);
        }
        ActorItemEquipment row = repository
                .findByProjectIdAndHolderRefAndSlot(projectId, holder, wanted)
                .orElseGet(ActorItemEquipment::new);
        boolean created = row.getId() == null;
        row.setProjectId(projectId);
        row.setHolderRef(holder);
        row.setSlot(wanted);
        row.setItemKey(layer.itemKey());
        row.setSinceGameSecond(gameSecond);
        repository.save(row);
        log.info("[装备] {} {} 槽 ← {}（{}，成色 {}）{}", holder, wanted, layer.itemKey(),
                layer.name(), layer.durability(), created ? "" : "（换下旧的）");
        syncFact(projectId, holder, gameSecond);
        return EquipmentView.of(wanted, layer, gameSecond);
    }

    /**
     * 脱/放下。
     *
     * @param projectId  项目 ID
     * @param holderRef  持有者
     * @param slot       槽位（空=手上）
     * @param gameSecond 游戏秒
     *
     * @return true=真的脱下了东西（原本就空着返回 false）
     */
    @Transactional
    public boolean unequip(Long projectId, String holderRef, String slot, long gameSecond) {
        String holder = ItemRefs.requireHolder(holderRef, "holder");
        String wanted = EquipmentSlot.normalize(slot);
        if (wanted == null) {
            wanted = EquipmentSlot.HAND;
        }
        Optional<ActorItemEquipment> row = repository
                .findByProjectIdAndHolderRefAndSlot(projectId, holder, wanted);
        if (row.isEmpty()) {
            return false;
        }
        repository.delete(row.get());
        log.info("[装备] {} {} 槽放下 {}", holder, wanted, row.get().getItemKey());
        syncFact(projectId, holder, gameSecond);
        return true;
    }

    // ==================== 读入口 ====================

    /**
     * 某持有者当前的全部装备（自愈：物品没了的行会被删掉）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 装备视图（按槽位排序）
     */
    @Transactional
    public List<EquipmentView> equipmentOf(Long projectId, String holderRef) {
        if (projectId == null || holderRef == null || holderRef.isBlank()) {
            return List.of();
        }
        String holder = holderRef.trim();
        List<ActorItemEquipment> rows = repository
                .findByProjectIdAndHolderRefOrderBySlotAsc(projectId, holder);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<EquipmentView> views = new ArrayList<>(rows.size());
        boolean healed = false;
        for (ActorItemEquipment row : rows) {
            Optional<ItemView> layer = inventoryService.bestLayerOf(projectId, holder, row.getItemKey());
            if (layer.isEmpty()) {
                // 自愈：东西已经不在身上了（卖了/吃了/烂了/断了），装备行没有意义
                repository.delete(row);
                healed = true;
                log.info("[装备] 自愈清理：{} {} 槽的「{}」已不在身上", holder, row.getSlot(), row.getItemKey());
                continue;
            }
            views.add(EquipmentView.of(row.getSlot(), layer.get(),
                    row.getSinceGameSecond() == null ? 0L : row.getSinceGameSecond()));
        }
        if (healed) {
            syncFact(projectId, holder, currentGameSecondOf(projectId));
        }
        return views;
    }

    /**
     * 手上那件（没有返回 empty）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 装备视图
     */
    @Transactional
    public Optional<EquipmentView> handOf(Long projectId, String holderRef) {
        for (EquipmentView view : equipmentOf(projectId, holderRef)) {
            if (EquipmentSlot.HAND.equals(view.slot())) {
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }

    /**
     * 身上某槽位的装备（没有返回 empty）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param slot      槽位
     *
     * @return 装备视图
     */
    @Transactional
    public Optional<EquipmentView> slotOf(Long projectId, String holderRef, String slot) {
        String wanted = EquipmentSlot.normalize(slot);
        if (wanted == null) {
            return Optional.empty();
        }
        for (EquipmentView view : equipmentOf(projectId, holderRef)) {
            if (wanted.equals(view.slot())) {
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }

    // ==================== 事实同步 ====================

    /**
     * 把当前装备写成 {@code equip:{ref}} 事实（让旁人看得见）。
     *
     * <p>写不进去只 WARN，不影响穿脱本身——与 {@code MarketService} 同步 stock 事实同口径。</p>
     *
     * @param projectId  项目 ID
     * @param holderRef  持有者
     * @param gameSecond 游戏秒
     */
    private void syncFact(Long projectId, String holderRef, long gameSecond) {
        if (wearProperties == null || !wearProperties.isEquipFactSync()
                || worldFactService == null || projectId == null || holderRef == null) {
            return;
        }
        try {
            List<EquipmentView> views = currentViews(projectId, holderRef);
            String factKey = FACT_KEY_PREFIX + holderRef;
            if (views.isEmpty()) {
                expireFact(projectId, factKey, gameSecond);
                return;
            }
            ObjectNode attrs = MAPPER.createObjectNode();
            ObjectNode slots = attrs.putObject("slots");
            ObjectNode conditions = attrs.putObject("conditions");
            StringBuilder text = new StringBuilder();
            for (EquipmentView view : views) {
                slots.put(view.slot(), view.itemKey());
                conditions.put(view.slot(), view.condition());
                if (text.length() > 0) {
                    text.append("；");
                }
                text.append(EquipmentSlot.HAND.equals(view.slot()) ? "手上" : "身上")
                        .append("是「").append(view.name()).append("」（").append(view.conditionLabel()).append("）");
            }
            String locationName = locationNameOf(projectId, holderRef);
            Long locationId = locationIdOf(projectId, locationName);
            FactCommand cmd = new FactCommand(projectId, FACT_TYPE, factKey, displayNameOf(projectId, holderRef),
                    text.toString(), attrs.toString(), locationId, locationName,
                    wearProperties.getEquipVisibility(), FactAuthority.BRAIN,
                    gameSecond, null, gameSecond, holderRef, null, 2, null);
            worldFactService.upsert(cmd);
        } catch (RuntimeException e) {
            log.warn("[装备] 装备事实同步失败（不影响穿脱）：{} {} : {}", projectId, holderRef, e.getMessage());
        }
    }

    /**
     * 直接读库拼视图（不触发自愈，避免 syncFact ↔ equipmentOf 递归）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 装备视图
     */
    private List<EquipmentView> currentViews(Long projectId, String holderRef) {
        List<ActorItemEquipment> rows = repository
                .findByProjectIdAndHolderRefOrderBySlotAsc(projectId, holderRef);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<EquipmentView> views = new ArrayList<>(rows.size());
        for (ActorItemEquipment row : rows) {
            inventoryService.bestLayerOf(projectId, holderRef, row.getItemKey())
                    .ifPresent(layer -> views.add(EquipmentView.of(row.getSlot(), layer,
                            row.getSinceGameSecond() == null ? 0L : row.getSinceGameSecond())));
        }
        return views;
    }

    /** 没有装备时把事实过期掉（而不是留一条「空手」事实污染台账）。 */
    private void expireFact(Long projectId, String factKey, long gameSecond) {
        if (worldFactQuery == null) {
            return;
        }
        ActorWorldFact existing = worldFactQuery.byKey(projectId, factKey).orElse(null);
        if (existing == null) {
            return;
        }
        FactCommand cmd = new FactCommand(projectId, existing.getFactType(), factKey, existing.getName(),
                existing.getStateText(), existing.getAttributesJson(), existing.getLocationId(),
                existing.getLocationName(), existing.getVisibility(), FactAuthority.BRAIN,
                existing.getValidFromGameSecond(), existing.getValidToGameSecond(),
                gameSecond, null, null, 3, null);
        worldFactService.expire(cmd, "unequipped");
    }

    /** 角色所在地点名（查不到返回 null）。 */
    private String locationNameOf(Long projectId, String holderRef) {
        if (characterRepository == null || holderRef == null) {
            return null;
        }
        Long id = ItemRefs.idOf(holderRef);
        if (id == null) {
            return null;
        }
        try {
            ActorCharacter actor = characterRepository.findById(id).orElse(null);
            return actor == null ? null : actor.getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 地点名 → 地点 ID（查不到返回 null，不猜）。 */
    private Long locationIdOf(Long projectId, String locationName) {
        if (projectId == null || locationName == null || locationName.isBlank() || locationRepository == null) {
            return null;
        }
        try {
            for (ActorWorldLocation location : locationRepository.findByProjectId(projectId)) {
                if (location != null && location.getName() != null
                        && location.getName().trim().equalsIgnoreCase(locationName.trim())) {
                    return location.getId();
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** 角色展示名（查不到就用 ref）。 */
    private String displayNameOf(Long projectId, String holderRef) {
        if (characterRepository != null) {
            Long id = ItemRefs.idOf(holderRef);
            if (id != null) {
                try {
                    ActorCharacter actor = characterRepository.findById(id).orElse(null);
                    if (actor != null && actor.getName() != null && !actor.getName().isBlank()) {
                        return actor.getName();
                    }
                } catch (RuntimeException e) {
                    // 落到 ref
                }
            }
        }
        return holderRef;
    }

    /** 当前游戏秒（自愈后重写事实用；取不到就 0，只影响事实的版本时间）。 */
    private long currentGameSecondOf(Long projectId) {
        // 装备自愈发生在读取路径上，没有可信的时钟参数；事实的 gameSecond 只用于排序/审计，
        // 这里取 0 而不是去猜一个时间（宁可时间戳粗糙，也不要凭空造时间）。
        return 0L;
    }
}
