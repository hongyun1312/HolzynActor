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

package com.holzyn.actor.domain.item.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.entity.ActorItemLedger;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.provision.ItemProvisionService;
import com.holzyn.actor.domain.item.provision.ProvisionProperties;
import com.holzyn.actor.domain.item.repository.ActorItemDefRepository;
import com.holzyn.actor.domain.item.repository.ActorItemLedgerRepository;
import com.holzyn.actor.domain.item.repository.ActorItemStackRepository;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品与装备只读 API（对接欠下的「物品面板」）。
 *
 * <p><b>为什么物品域之后必须有这一层</b>：物品域做完了，但除了开发者库浏览和 SQL，
 * 没有任何地方能看见「谁身上有什么、成色多少、最近进出过什么」。玩家看不见，就等于
 * 这些东西不存在；因果/事实台账也因此缺一条腿。</p>
 *
 * <p><b>只读，不新增写入口</b>：持有栈仍只由 {@code InventoryService} 写，装备行仍只由
 * {@code EquipmentService} 写。唯一带副作用的是 {@code /provision}（幂等补发），
 * 它调的也是 {@code InventoryService.add}，不是自己写表。</p>
 *
 * <p><b>为什么返回 Map 而不是 DTO 类</b>：与 {@code WorldFactController} 同风格——
 * 只读面板的数据形状跟着前端走，多一个字段不用改两个类。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}/items")
@RequiredArgsConstructor
public class ItemController {

    private final InventoryService inventoryService;
    private final EquipmentService equipmentService;
    private final ActorItemStackRepository stackRepository;
    private final ActorItemDefRepository defRepository;
    private final ActorItemLedgerRepository ledgerRepository;
    private final ItemProvisionService provisionService;
    private final ProvisionProperties provisionProperties;
    private final ActorCharacterRepository characterRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 世界上「有东西的持有者」清单（左栏选择器）。
     *
     * @param projectId 项目 ID
     *
     * @return 持有者列表（ref/类型/名字/总件数）
     */
    @GetMapping("/holders")
    public R<List<Map<String, Object>>> holders(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String ref : stackRepository.findHolderRefs(projectId)) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("holderRef", ref);
            m.put("kind", ItemRefs.isLocation(ref) ? "location"
                    : ItemRefs.isNpc(ref) ? "npc" : "other");
            m.put("name", holderName(ref));
            int total = 0;
            for (ItemView item : inventoryService.list(projectId, ref)) {
                total += item.quantity();
            }
            m.put("totalQty", total);
            rows.add(m);
        }
        return R.ok(rows);
    }

    /**
     * 某持有者的背包 + 装备。
     *
     * @param projectId 项目 ID
     *
     * @param holder    持有者 ref（如 {@code npc:1} / {@code loc:65}）
     *
     * @return 明细
     */
    @GetMapping
    public R<Map<String, Object>> holder(@PathVariable("projectId") Long projectId,
                                         @RequestParam("holder") String holder) {
        requireProject(projectId);
        String ref = ItemRefs.requireHolder(holder, "holder");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holderRef", ref);
        out.put("kind", ItemRefs.isLocation(ref) ? "location" : ItemRefs.isNpc(ref) ? "npc" : "other");
        out.put("name", holderName(ref));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemView item : inventoryService.list(projectId, ref)) {
            items.add(itemView(item));
        }
        out.put("items", items);
        List<Map<String, Object>> equipment = new ArrayList<>();
        if (equipmentService.enabled()) {
            for (EquipmentView eq : equipmentService.equipmentOf(projectId, ref)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slot", eq.slot());
                m.put("itemKey", eq.itemKey());
                m.put("name", eq.name());
                m.put("condition", eq.condition());
                m.put("conditionLabel", eq.conditionLabel());
                m.put("sinceGameSecond", eq.sinceGameSecond());
                equipment.add(m);
            }
        }
        out.put("equipment", equipment);
        out.put("equipmentEnabled", equipmentService.enabled());
        return R.ok(out);
    }

    /**
     * 本项目可见的物品定义（本项目 + 全局）。
     *
     * @param projectId 项目 ID
     *
     * @return 定义列表
     */
    @GetMapping("/defs")
    public R<List<Map<String, Object>>> defs(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorItemDef def : defRepository
                .findByProjectIdInOrderByProjectIdAscItemKeyAsc(List.of(ActorItemDef.GLOBAL_PROJECT, projectId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemKey", def.getItemKey());
            m.put("name", def.getName());
            m.put("category", def.getCategory());
            m.put("stackMax", def.getStackMax());
            m.put("basePrice", def.getBasePrice());
            m.put("decayHours", def.getDecayHours());
            m.put("equipSlot", def.getEquipSlot());
            m.put("global", def.getProjectId() != null && def.getProjectId() == ActorItemDef.GLOBAL_PROJECT);
            rows.add(m);
        }
        return R.ok(rows);
    }

    /**
     * 最近物品流水（新→旧）。
     *
     * @param projectId 项目 ID
     *
     * @param holder    持有者（可空=全世界）
     *
     * @param limit     条数（默认 50，上限 200）
     *
     * @return 流水列表
     */
    @GetMapping("/ledger")
    public R<List<Map<String, Object>>> ledger(@PathVariable("projectId") Long projectId,
                                               @RequestParam(name = "holder", required = false) String holder,
                                               @RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireProject(projectId);
        int size = Math.max(1, Math.min(200, limit));
        PageRequest page = PageRequest.of(0, size);
        List<ActorItemLedger> rows = holder == null || holder.isBlank()
                ? ledgerRepository.findByProjectIdOrderByIdDesc(projectId, page).getContent()
                : ledgerRepository.findRecentByHolder(projectId, holder.trim(), page).getContent();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ActorItemLedger l : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("itemKey", l.getItemKey());
            m.put("name", nameOf(projectId, l.getItemKey()));
            m.put("qtyDelta", l.getQtyDelta());
            m.put("fromRef", l.getFromRef());
            m.put("toRef", l.getToRef());
            m.put("reason", l.getReason());
            m.put("gameSecond", l.getGameSecond());
            m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
            out.add(m);
        }
        return R.ok(out);
    }

    /**
     * 幂等补发开局供给（运维/排查用；正常由「打开世界」自动触发）。
     *
     * @param projectId 项目 ID
     *
     * @return 报告
     */
    @PostMapping("/provision")
    public R<Map<String, Object>> provision(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        ItemProvisionService.ProvisionReport report = provisionService.provision(projectId, 0L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("characters", report.characters());
        out.put("locations", report.locations());
        out.put("grants", report.grants());
        out.put("worn", report.worn());
        out.put("skipped", report.skipped());
        out.put("enabled", provisionProperties.isEnabled());
        return R.ok(out);
    }

    // ==================== 辅助 ====================

    /** 持有者展示名（人查角色名，货架查地名，查不到用 ref）。 */
    private String holderName(String ref) {
        Long id = ItemRefs.idOf(ref);
        if (id == null) {
            return ref;
        }
        try {
            if (ItemRefs.isLocation(ref)) {
                return locationRepository.findById(id).map(l -> l.getName()).orElse(ref);
            }
            if (ItemRefs.isNpc(ref)) {
                return characterRepository.findById(id)
                        .map(c -> c.getName() == null || c.getName().isBlank() ? ref : c.getName())
                        .orElse(ref);
            }
        } catch (RuntimeException e) {
            return ref;
        }
        return ref;
    }

    /** 物品展示名（查不到用键）。 */
    private String nameOf(Long projectId, String itemKey) {
        try {
            return inventoryService.findDef(projectId, itemKey)
                    .map(d -> d.getName() == null || d.getName().isBlank() ? itemKey : d.getName())
                    .orElse(itemKey);
        } catch (RuntimeException e) {
            return itemKey;
        }
    }

    private static Map<String, Object> itemView(ItemView item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemKey", item.itemKey());
        m.put("name", item.name());
        m.put("category", item.category());
        m.put("quantity", item.quantity());
        m.put("quality", item.quality());
        m.put("durability", item.durability());
        m.put("condition", item.condition());
        m.put("conditionLabel", item.conditionLabel());
        m.put("equipSlot", item.equipSlot());
        m.put("weaponLike", item.weaponLike());
        m.put("foodLike", item.foodLike());
        m.put("drinkLike", item.drinkLike());
        return m;
    }

    /** 校验项目归属当前用户（越权抛 404）。 */
    private void requireProject(Long projectId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, currentUserProvider.currentUserId(), 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }
}
