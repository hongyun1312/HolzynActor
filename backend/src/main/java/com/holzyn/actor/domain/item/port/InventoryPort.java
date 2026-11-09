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

package com.holzyn.actor.domain.item.port;

import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.ItemView;

import java.util.List;
import java.util.Optional;

/**
 * 物品域端口（§4.5）。
 *
 * <p><b>为什么是端口而不是直接依赖服务</b>： {@code EffectApplier} 只认这个接口；
 * 桌面端（BRAIN）装 {@code DbInventoryService}，接引擎（ENGINE）由 E1d 换
 * {@code EngineInventoryAdapter}，效果链路一行不改。</p>
 *
 * <p><b>未装配即降级</b>：{@code holzyn.actor.item.enabled=false}（默认）时没有任何
 * 实现 Bean，{@code EffectApplier} 把 {@code inventory.*} 记为
 * {@code SKIPPED_NO_INVENTORY} 并继续，不抛异常。</p>
 */
public interface InventoryPort {

    /**
     * 转移物品（幂等由 idempotencyKey 保证）。
     *
     * <p>来源不足时抛 {@link com.holzyn.actor.domain.item.model.InsufficientItemException}，
     * 由调用方整批回滚（决策 A：严格拒绝，不允许负库存）。</p>
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量（正数）
     * @param fromRef        来源持有者 ref
     * @param toRef          目标持有者 ref
     * @param reason         原因
     * @param eventId        来源事件 ID
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    void transfer(Long projectId, String itemKey, int qty, String fromRef, String toRef,
                  String reason, Long eventId, long gameSecond, String idempotencyKey);

    /**
     * 某持有者某物品的总量（跨品质求和）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 总量
     */
    int quantityOf(Long projectId, String holderRef, String itemKey);

    /**
     * 是否持有足够数量。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     * @param qty       需要数量
     *
     * @return true=够
     */
    boolean has(Long projectId, String holderRef, String itemKey, int qty);

    /**
     * 某持有者的全部物品（一个品质层一条）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 物品视图列表
     */
    List<ItemView> list(Long projectId, String holderRef);

    /**
     * 消耗/销毁（制作扣材料）：来源 → 无。
     *
     * <p><b>为什么不能拿 {@code transfer} 顶替</b>：消耗没有目标持有者。若硬塞一个
     * 虚拟持有者（{@code void:}），世界账本里就会多出一个谁也不认识的「仓库」，
     * 前端台账和「全库总量」都会被它污染。</p>
     *
     * <p>不足时抛 {@link com.holzyn.actor.domain.item.model.InsufficientItemException}，
     * 由调用方整批回滚。</p>
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量（正数）
     * @param holderRef      来源持有者
     * @param reason         原因（如 craft/consume）
     * @param eventId        来源事件 ID
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    void consume(Long projectId, String itemKey, int qty, String holderRef, String reason,
                 Long eventId, long gameSecond, String idempotencyKey);

    /**
     * 产出（制作出产物建造落成）：无来源 → 目标。
     *
     * <p>{@code reason} 必须在 {@code InventoryService.CREATION_REASONS} 白名单内
     * （craft/loot/spawn/initial/repair），否则抛 {@link IllegalArgumentException}——
     * 这是「凭空造物必须说明来由」的最后一道闸。</p>
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量（正数）
     * @param holderRef      目标持有者
     * @param reason         原因（白名单内）
     * @param eventId        来源事件 ID
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    void produce(Long projectId, String itemKey, int qty, String holderRef, String reason,
                 Long eventId, long gameSecond, String idempotencyKey);

    /**
     * 磨损一件：耐久扣点，扣到 0 就断（物品从持有栈移除）。
     *
     * <p>磨哪一件、怎么记账由实现决定；端口只保证「一次调用 = 一次磨损」，
     * 同一幂等键重放不重复扣。</p>
     *
     * @param projectId      项目 ID
     * @param holderRef      持有者
     * @param itemKey        物品键
     * @param points         磨损点数（正数）
     * @param eventId        来源事件 ID
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     *
     * @return 变化结果（目标不存在时 {@code before=-1}）
     */
    DurabilityChange wear(Long projectId, String holderRef, String itemKey, int points,
                          Long eventId, long gameSecond, String idempotencyKey);

    /**
     * 修复一件：耐久补回去，封顶 100。
     *
     * @param projectId      项目 ID
     * @param holderRef      持有者
     * @param itemKey        物品键
     * @param points         修复点数（正数）
     * @param eventId        来源事件 ID
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     *
     * @return 变化结果（目标不存在时 {@code before=-1}）
     */
    DurabilityChange restore(Long projectId, String holderRef, String itemKey, int points,
                             Long eventId, long gameSecond, String idempotencyKey);

    /**
     * 某持有者某物品「最完好的那一层」（装备解析用）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 最完好的一层；没有返回 empty
     */
    Optional<ItemView> bestLayerOf(Long projectId, String holderRef, String itemKey);
}
