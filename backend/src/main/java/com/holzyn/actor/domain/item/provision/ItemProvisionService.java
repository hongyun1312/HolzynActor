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

package com.holzyn.actor.domain.item.provision;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.fact.ItemStockFacts;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 开局供给（对接第一处断点）：让「世界刚打开时每个人身上、每间铺子里真的有东西」。
 *
 * <p><b>为什么这是本轮最要紧的一件</b>：物品域把耐久、腐坏、装备、交易、制作全做完了，
 * 但真实世界里所有持有栈都是空的——没有东西可旧、可烂、可买、可抢。物品域等于没通电，
 * M 侧的感知/裁决/主动性也就永远读不到「他有把刀」。</p>
 *
 * <p><b>幂等怎么做</b>：每一笔发放都带固定幂等键 {@code provision:{holder}:{item}:{袋|穿}}，
 * 由 {@link InventoryService} 的账本去重。于是「每次开世界都跑一遍」是安全的——第一次
 * 真的发，之后全是 no-op；某个角色后来把面包吃了，也不会因为重开世界又被塞回来。</p>
 *
 * <p><b>为什么不建新表</b>：发放痕迹已经在物品账本里（{@code reason=spawn}），再立一张
 * 「已供给」表就是同一件事存两处。世界表集合被架构守卫冻结在 129 张，多一张要动三处断言，
 * 收益却只是「少查一次账本」。</p>
 *
 * <p><b>事务边界</b>：零 AI，方法带 {@code @Transactional}；单次开世界几百条插入是毫秒级。</p>
 */
@Slf4j
@Service
public class ItemProvisionService {

    /** 账本原因：开局发放（在 {@code CREATION_REASONS} 白名单内） */
    private static final String REASON = "spawn";

    private final InventoryService inventoryService;

    private final EquipmentService equipmentService;

    private final ActorCharacterRepository characterRepository;

    private final WorldLocationRepository locationRepository;

    private final ItemProperties itemProperties;

    private final ProvisionProperties properties;

    /** 世界时钟（取「现在是第几游戏秒」当发放时刻；未装配用 0） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 存量事实同步（货架铺完要让人「看得见」） */
    @Autowired(required = false)
    private ItemStockFacts stockFacts;

    /**
     * 构造（新类允许构造器注入；既有服务不改签名）。
     *
     * @param inventoryService    物品唯一写入口
     *
     * @param equipmentService    装备写入口（发下去的衣物顺手穿上）
     *
     * @param characterRepository 角色仓储
     *
     * @param locationRepository  地点仓储
     *
     * @param itemProperties      物品域配置（总开关）
     *
     * @param properties          供给配置
     */
    public ItemProvisionService(InventoryService inventoryService,
                                EquipmentService equipmentService,
                                ActorCharacterRepository characterRepository,
                                WorldLocationRepository locationRepository,
                                ItemProperties itemProperties,
                                ProvisionProperties properties) {
        this.inventoryService = inventoryService;
        this.equipmentService = equipmentService;
        this.characterRepository = characterRepository;
        this.locationRepository = locationRepository;
        this.itemProperties = itemProperties;
        this.properties = properties;
    }

    /**
     * 一次供给的报告（只用于日志与冒烟断言，不落库）。
     *
     * @param characters 处理的角色数
     *
     * @param locations  处理的地点（铺货）数
     *
     * @param grants     发放条目数（含幂等跳过）
     *
     * @param worn       真正穿上身的件数
     *
     * @param skipped    因开关/未启用而整体跳过的原因（空=真的跑了）
     */
    public record ProvisionReport(int characters, int locations, int grants, int worn, String skipped) {

        /** 空报告。 */
        public static ProvisionReport empty(String reason) {
            return new ProvisionReport(0, 0, 0, 0, reason);
        }

        /** 是否什么都没做（用于日志降噪）。 */
        public boolean noop() {
            return grants == 0;
        }
    }

    /**
     * 供给一个世界（幂等；重复调用不重复发放）。
     *
     * @param projectId  项目/世界 ID
     *
     * @param gameSecond 发放时刻（&le;0 时取世界时钟）
     *
     * @return 报告
     */
    @Transactional
    public ProvisionReport provision(Long projectId, long gameSecond) {
        if (projectId == null) {
            return ProvisionReport.empty("NO_PROJECT");
        }
        if (!properties.isEnabled()) {
            return ProvisionReport.empty("PROVISION_DISABLED");
        }
        if (!itemProperties.isEnabled()) {
            return ProvisionReport.empty("ITEM_DISABLED");
        }
        long gs = gameSecond > 0 ? gameSecond : currentGameSecond(projectId);
        int characters = 0;
        int grants = 0;
        int worn = 0;
        int budget = Math.max(1, properties.getMaxHolders());

        if (properties.isNpcKit() || properties.isPlayerKit()) {
            List<ActorCharacter> rows = characterRepository
                    .findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            for (ActorCharacter ch : rows) {
                if (ch == null || characters >= budget) {
                    break;
                }
                String ref = NpcRefs.of(ch.getId());
                if (ref == null) {
                    continue;
                }
                boolean player = ch.isPlayer();
                if (player && !properties.isPlayerKit()) {
                    continue;
                }
                if (!player && !properties.isNpcKit()) {
                    continue;
                }
                List<ProvisionKit.Entry> kit = player
                        ? ProvisionKit.forPlayer()
                        : ProvisionKit.forNpc(ch.getOccupation(),
                        ProvisionKit.rationFor(ch.getImportance()),
                        ProvisionKit.rationFor(ch.getImportance()));
                characters++;
                grants += grant(projectId, ref, kit, gs);
                worn += wearAll(projectId, ref, kit, gs);
            }
        }

        int locations = 0;
        if (properties.isLocationStock()) {
            List<ActorWorldLocation> rows = locationRepository
                    .findByProjectIdOrderBySortOrderAscIdAsc(projectId);
            boolean stockedShop = false;
            for (ActorWorldLocation loc : rows) {
                if (loc == null || loc.getId() == null) {
                    continue;
                }
                List<ProvisionKit.Entry> stock = ProvisionKit.forLocation(loc.getName());
                if (stock.isEmpty()) {
                    continue;
                }
                stockedShop = true;
                locations++;
                String ref = "loc:" + loc.getId();
                grants += grant(projectId, ref, stock, gs);
                if (stockFacts != null) {
                    stockFacts.syncAll(projectId, ref, gs);
                }
            }
            // 没有一间「铺子」时，退一步给这个世界最大的聚落铺货。
            // 真跑一测就发现很多世界的地点全是「青岳主峰 / 青禾村古井 / 临溪镇 / 灵溪渡口」，
            // 按「铺/店/集市」一个都命中不了——货架永远空、市场永远没货，等于白做。
            if (!stockedShop) {
                ActorWorldLocation settlement = null;
                int bestRank = 0;
                for (ActorWorldLocation loc : rows) {
                    if (loc == null || loc.getId() == null) {
                        continue;
                    }
                    int rank = ProvisionKit.settlementRank(loc.getName());
                    // 同等级取 id 小的那个：同一份数据每次跑都选同一个地点（幂等键才不会分叉）
                    if (rank > bestRank) {
                        bestRank = rank;
                        settlement = loc;
                    }
                }
                if (settlement != null) {
                    locations++;
                    String ref = "loc:" + settlement.getId();
                    grants += grant(projectId, ref, ProvisionKit.forLocation("集市"), gs);
                    if (stockFacts != null) {
                        stockFacts.syncAll(projectId, ref, gs);
                    }
                    log.info("[供给] 无店铺地点，改给最大聚落铺货：{}（loc:{}）",
                            settlement.getName(), settlement.getId());
                }
            }
        }

        if (grants > 0) {
            log.info("[供给] 开局发放完成: project={} 角色={} 地点={} 条目={} 穿上={}",
                    projectId, characters, locations, grants, worn);
        } else {
            log.debug("[供给] 已发放过（幂等 no-op）: project={} 角色={} 地点={}",
                    projectId, characters, locations);
        }
        return new ProvisionReport(characters, locations, grants, worn, "");
    }

    /**
     * 开世界时调用：任何异常都只记日志，绝不让「打开世界」失败。
     *
     * @param projectId 项目/世界 ID
     */
    public void provisionQuietly(Long projectId) {
        if (projectId == null) {
            return;
        }
        try {
            provision(projectId, 0L);
        } catch (RuntimeException e) {
            log.warn("[供给] 开局发放失败（继续开世界）：project={} : {}", projectId, e.toString());
        }
    }

    /** 逐个条目发放（幂等键固定，重复调用不重复发）。 */
    private int grant(Long projectId, String holderRef, List<ProvisionKit.Entry> entries, long gameSecond) {
        int count = 0;
        for (ProvisionKit.Entry entry : entries) {
            if (entry == null || entry.qty() <= 0) {
                continue;
            }
            try {
                inventoryService.add(projectId, entry.itemKey(), entry.qty(), holderRef, REASON,
                        null, gameSecond, idemOf(holderRef, entry));
                count++;
            } catch (RuntimeException e) {
                log.warn("[供给] 发放失败（隔离继续）: holder={} item={} : {}",
                        holderRef, entry.itemKey(), e.getMessage());
            }
        }
        return count;
    }

    /** 把要穿的衣物真的穿到身上（失败只告警：背包里那件还在）。 */
    private int wearAll(Long projectId, String holderRef, List<ProvisionKit.Entry> entries, long gameSecond) {
        if (!properties.isAutoWear() || equipmentService == null || !equipmentService.enabled()) {
            return 0;
        }
        int worn = 0;
        for (ProvisionKit.Entry entry : entries) {
            if (entry == null || !entry.equip()) {
                continue;
            }
            try {
                equipmentService.equip(projectId, holderRef, null, entry.itemKey(), gameSecond);
                worn++;
            } catch (RuntimeException e) {
                log.debug("[供给] 穿衣失败（留在背包）：holder={} item={} : {}",
                        holderRef, entry.itemKey(), e.getMessage());
            }
        }
        return worn;
    }

    /** 固定幂等键：同一持有者同一物品只发一次（袋装与穿戴分开计）。 */
    private static String idemOf(String holderRef, ProvisionKit.Entry entry) {
        return "provision:" + holderRef + ":" + entry.itemKey() + ":" + (entry.equip() ? "worn" : "bag");
    }

    /** 当前游戏秒（未装配时钟返回 0，供给照样能跑）。 */
    private long currentGameSecond(Long projectId) {
        if (worldClockService == null) {
            return 0L;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * 某持有者是不是地点 ref（冒烟/测试用，避免测试自己拼前缀）。
     *
     * @param holderRef 持有者
     *
     * @return true=地点
     */
    public static boolean isLocationHolder(String holderRef) {
        return ItemRefs.isLocation(holderRef);
    }
}
