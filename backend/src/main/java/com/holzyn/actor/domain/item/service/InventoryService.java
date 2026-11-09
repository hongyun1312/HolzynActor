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

package com.holzyn.actor.domain.item.service;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.entity.ActorItemLedger;
import com.holzyn.actor.domain.item.entity.ActorItemStack;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.repository.ActorItemDefRepository;
import com.holzyn.actor.domain.item.repository.ActorItemLedgerRepository;
import com.holzyn.actor.domain.item.repository.ActorItemStackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物品域唯一写入口（§4.4.5 / §8.2–）。
 *
 * <p><b>单一写入口</b>：{@code actor_item_stack} / {@code actor_item_ledger} /
 * {@code actor_item_def}（懒建）三张表只由本类写；装备行由
 * {@code EquipmentService} 写（另一张表，另一个写者，互不越界）。其他模块
 * （裁决、提示词、效果）只读。</p>
 *
 * <p><b>事务边界</b>：本类零 AI、零 HTTP，方法带 {@code @Transactional}；
 * H2 行锁只在毫秒级，不会出现「AI 时长占着行锁」的既有事故。</p>
 *
 * <p><b>三条硬规则</b>：</p>
 * <ol>
 *   <li>严格「没有就是没有」：来源不足抛 {@link InsufficientItemException}，
 *       由 {@code EffectApplier} 整批回滚，永不写负库存；</li>
 *   <li>幂等：同一 {@code idempotencyKey} 只落一次账、只扣一次货；</li>
 *   <li>堆叠合并：同一 (持有者, 物品, 品质, 耐久) 只有一行，入账时累加而不是新增行。</li>
 * </ol>
 *
 * <p><b>层键变化</b>：层的身份从 (持有者, 物品, 品质) 扩成
 * (持有者, 物品, 品质, <b>耐久</b>)。磨损一件 = 从耐久 100 层挪 1 件到耐久 99 层，
 * 于是「5 把刀用钝 1 把」不再等于「5 把一起变钝」。</p>
 */
@Slf4j
@Service
public class InventoryService {

    /**
     * 无来源产出允许的原因。
     *
     * <p>刻意不含 {@code gift}/{@code trade}：有来处的转移必须走 {@code transfer}，
     * 否则「凭空送出」会绕开来源校验（Q5：没有就是没有）。</p>
     */
    private static final Set<String> CREATION_REASONS = Set.of(
            "craft", "loot", "spawn", "initial", "repair");

    /** 默认品质（之前无品质机制；制作产物仍恒 100） */
    private static final int DEFAULT_QUALITY = 100;

    /** 默认耐久（全新） */
    public static final int DEFAULT_DURABILITY = 100;

    /** 账本写者标识（BRAIN 侧） */
    private static final String WRITER_BRAIN = "brain";

    /** 腐坏结算单次最多处理的层数（防一次 tick 扫全库把事务拖长） */
    private static final int DEFAULT_DECAY_SCAN_LIMIT = 500;

    private final ActorItemDefRepository defRepository;

    private final ActorItemStackRepository stackRepository;

    private final ActorItemLedgerRepository ledgerRepository;

    private final ItemProperties properties;

    /**
     * 构造（新类允许构造器注入；既有服务不改签名）。
     *
     * @param defRepository    定义仓储
     * @param stackRepository  持有栈仓储
     * @param ledgerRepository 账本仓储
     * @param properties       物品域配置
     */
    public InventoryService(ActorItemDefRepository defRepository,
                            ActorItemStackRepository stackRepository,
                            ActorItemLedgerRepository ledgerRepository,
                            ItemProperties properties) {
        this.defRepository = defRepository;
        this.stackRepository = stackRepository;
        this.ledgerRepository = ledgerRepository;
        this.properties = properties;
    }

    // ==================== 写入口 ====================

    /**
     * 转移：来源 → 目标（幂等）。
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量（正数）
     * @param fromRef        来源持有者（不可空）
     * @param toRef          目标持有者（不可空）
     * @param reason         原因
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    @Transactional
    public void transfer(Long projectId, String itemKey, int qty, String fromRef, String toRef,
                         String reason, Long eventId, long gameSecond, String idempotencyKey) {
        String from = requireHolder(fromRef, "from");
        String to = requireHolder(toRef, "to");
        if (from.equals(to)) {
            log.debug("[物品] 来源与目标相同，忽略：{} {}", from, itemKey);
            return;
        }
        move(projectId, itemKey, qty, from, to, reason, eventId, gameSecond, idempotencyKey, null);
    }

    /**
     * 产出：无来源 → 目标（用于采集/制作/初始播种）。
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量
     * @param holderRef      目标持有者
     * @param reason         原因（必须在白名单内，如 craft/loot/initial）
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    @Transactional
    public void add(Long projectId, String itemKey, int qty, String holderRef, String reason,
                    Long eventId, long gameSecond, String idempotencyKey) {
        produceWithLayer(projectId, itemKey, qty, holderRef, reason, eventId, gameSecond,
                idempotencyKey, null);
    }

    /**
     * 产出并指定初始状态层（让制作产物能带品质/耐久出生）。
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量
     * @param holderRef      目标持有者
     * @param reason         原因（白名单内）
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     * @param layer          初始状态层（null=全新）
     */
    @Transactional
    public void produceWithLayer(Long projectId, String itemKey, int qty, String holderRef, String reason,
                                 Long eventId, long gameSecond, String idempotencyKey, Layer layer) {
        String to = requireHolder(holderRef, "to");
        String normalizedReason = normalizeReason(reason);
        if (!CREATION_REASONS.contains(normalizedReason)) {
            throw new IllegalArgumentException("无来源物品变更必须声明合法 reason（"
                    + String.join("/", CREATION_REASONS.stream().sorted().toList()) + "）：" + reason);
        }
        move(projectId, itemKey, qty, null, to, normalizedReason, eventId, gameSecond,
                idempotencyKey, layer == null ? Layer.fresh(gameSecond) : layer);
    }

    /**
     * 消耗/销毁：来源 → 无（用于吃饭、用药、腐坏）。
     *
     * @param projectId      项目 ID
     * @param itemKey        物品键
     * @param qty            数量
     * @param holderRef      来源持有者
     * @param reason         原因
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     */
    @Transactional
    public void remove(Long projectId, String itemKey, int qty, String holderRef, String reason,
                       Long eventId, long gameSecond, String idempotencyKey) {
        String from = requireHolder(holderRef, "from");
        move(projectId, itemKey, qty, from, null, reason, eventId, gameSecond, idempotencyKey, null);
    }

    // ==================== 耐久腐坏 ====================

    /**
     * 磨损一件：把「手上那件」的耐久扣掉若干点，扣到 0 就断。
     *
     * <p><b>磨损哪一件</b>：同物品里<b>最完好</b>的那一层（人不会特意挑快断的刀上阵）。
     * 于是「两把刀、断一把、手里还有一把」是自然结果，不需要额外状态。</p>
     *
     * <p><b>为什么不是整层一起掉</b>：层里放 N 件时，整层掉耐久等于「5 把刀一起变钝」。
     * 这里从源层挪 1 件到 (品质, 新耐久) 层，逐件真实；源层还剩 N-1 件保持原状。</p>
     *
     * <p><b>账本口径</b>：耐久变化不是数量变化，仍记一条 {@code qty_delta=0} 的
     * {@code wear} 账——它是幂等键的载体，也让「这把刀什么时候开始变钝」可追溯；
     * 断了再补一条 {@code qty_delta=-1} 的 {@code break} 账。</p>
     *
     * @param projectId      项目 ID
     * @param holderRef      持有者
     * @param itemKey        物品键
     * @param points         磨损点数（正数）
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     *
     * @return 磨损结果（找不到该物品时 after=-1、broke=false）
     */
    @Transactional
    public DurabilityChange wear(Long projectId, String holderRef, String itemKey, int points,
                                 Long eventId, long gameSecond, String idempotencyKey) {
        requireProject(projectId);
        String holder = requireHolder(holderRef, "holder");
        String key = normalizeItemKey(itemKey);
        int amount = Math.max(1, points);
        String idem = normalizeIdempotency(idempotencyKey);
        if (idem != null && ledgerRepository.existsByProjectIdAndIdempotencyKey(projectId, idem)) {
            log.info("[物品] 磨损幂等重放，跳过：project={} key={} item={}", projectId, idem, key);
            return new DurabilityChange(key, -1, -1, false, true);
        }
        List<ActorItemStack> layers = new ArrayList<>(
                stackRepository.lockByHolderAndItem(projectId, holder, key));
        if (layers.isEmpty()) {
            log.debug("[物品] 磨损目标不存在：{} {} {}", holder, key, amount);
            return new DurabilityChange(key, -1, -1, false, false);
        }
        // 最完好的那件（耐久高优先，其次品质高，最后 id 小）
        layers.sort(Comparator
                .comparingInt((ActorItemStack s) -> s.getDurability() == null
                        ? DEFAULT_DURABILITY : s.getDurability()).reversed()
                .thenComparing(Comparator.comparingInt((ActorItemStack s) -> s.getQuality() == null
                        ? DEFAULT_QUALITY : s.getQuality()).reversed())
                .thenComparingLong(s -> s.getId() == null ? 0L : s.getId()));
        ActorItemStack source = layers.get(0);
        int before = source.getDurability() == null ? DEFAULT_DURABILITY : source.getDurability();
        int quality = source.getQuality() == null ? DEFAULT_QUALITY : source.getQuality();
        Long freshAt = source.getFreshAtGameSecond();
        int after = Math.max(0, before - amount);

        // 从源层取走 1 件
        int left = (source.getQuantity() == null ? 0 : source.getQuantity()) - 1;
        if (left <= 0) {
            stackRepository.delete(source);
        } else {
            source.setQuantity(left);
            bumpVersion(source, eventId);
            stackRepository.save(source);
        }
        boolean broke = after <= 0;
        if (broke) {
            writeLedger(projectId, key, -1, holder, null, "break", eventId, gameSecond, idem);
        } else {
            credit(projectId, holder, key, 1, eventId,
                    new Layer(quality, after, freshAt == null ? gameSecond : freshAt));
            writeLedger(projectId, key, 0, holder, null, "wear", eventId, gameSecond, idem);
        }
        log.info("[物品] 磨损：{} {} 耐久 {}→{}{} idem={}", holder, key, before, after,
                broke ? "（断了）" : "", idem);
        return new DurabilityChange(key, before, after, broke, false);
    }

    /**
     * 修复一件：把耐久补回去，封顶 {@link #DEFAULT_DURABILITY}。
     *
     * <p>修复不消耗材料、不产生新物品：磨刀要的是时间和力气。材料/工时成本由
     * 裁决层（{@code R-REPAIR-01}）决定要不要额外扣，本方法只负责状态。</p>
     *
     * @param projectId      项目 ID
     * @param holderRef      持有者
     * @param itemKey        物品键
     * @param points         修复点数（正数）
     * @param eventId        来源事件
     * @param gameSecond     游戏秒
     * @param idempotencyKey 幂等键
     *
     * @return 修复结果（找不到/已完好时 after=before）
     */
    @Transactional
    public DurabilityChange restore(Long projectId, String holderRef, String itemKey, int points,
                                    Long eventId, long gameSecond, String idempotencyKey) {
        requireProject(projectId);
        String holder = requireHolder(holderRef, "holder");
        String key = normalizeItemKey(itemKey);
        int amount = Math.max(1, points);
        String idem = normalizeIdempotency(idempotencyKey);
        if (idem != null && ledgerRepository.existsByProjectIdAndIdempotencyKey(projectId, idem)) {
            log.info("[物品] 修复幂等重放，跳过：project={} key={} item={}", projectId, idem, key);
            return new DurabilityChange(key, -1, -1, false, true);
        }
        List<ActorItemStack> layers = new ArrayList<>(
                stackRepository.lockByHolderAndItem(projectId, holder, key));
        if (layers.isEmpty()) {
            return new DurabilityChange(key, -1, -1, false, false);
        }
        // 修最破的那件（先修快断的，而不是给新刀抛光）
        layers.sort(Comparator
                .comparingInt((ActorItemStack s) -> s.getDurability() == null
                        ? DEFAULT_DURABILITY : s.getDurability())
                .thenComparingLong(s -> s.getId() == null ? 0L : s.getId()));
        ActorItemStack source = layers.get(0);
        int before = source.getDurability() == null ? DEFAULT_DURABILITY : source.getDurability();
        int quality = source.getQuality() == null ? DEFAULT_QUALITY : source.getQuality();
        Long freshAt = source.getFreshAtGameSecond();
        int after = Math.min(DEFAULT_DURABILITY, before + amount);
        int left = (source.getQuantity() == null ? 0 : source.getQuantity()) - 1;
        if (left <= 0) {
            stackRepository.delete(source);
        } else {
            source.setQuantity(left);
            bumpVersion(source, eventId);
            stackRepository.save(source);
        }
        credit(projectId, holder, key, 1, eventId,
                new Layer(quality, after, freshAt == null ? gameSecond : freshAt));
        writeLedger(projectId, key, 0, holder, null, "repair", eventId, gameSecond, idem);
        log.info("[物品] 修复：{} {} 耐久 {}→{} idem={}", holder, key, before, after, idem);
        return new DurabilityChange(key, before, after, false, false);
    }

    /**
     * 腐坏 tick（每游戏小时一次）：按 {@code decay_hours} 把品质推下去。
     *
     * <p><b>为什么质量是现算而不是累加</b>：质量 = 100 − floor(100 × 已过游戏秒 ÷
     * (decay_hours × 3600))。同一游戏秒重复结算得到同一质量，不会因为「每小时扣 1.38 点
     * 四舍五入」而漂移或停滞（这正是既有需求衰减踩过的坑）。</p>
     *
     * <p><b>烂透了怎么办</b>：整层删掉并记一条 {@code qty_delta=-qty} 的 {@code decay} 账。
     * 不做「腐坏物品」中间态——现实里馊掉的面包不会变成另一件有用的东西。</p>
     *
     * @param projectId  项目 ID
     * @param gameSecond 当前游戏秒
     *
     * @return 结算报告
     */
    @Transactional
    public DecayReport decayTick(Long projectId, long gameSecond) {
        if (projectId == null) {
            return DecayReport.EMPTY;
        }
        List<ActorItemStack> stacks = stackRepository.findByProjectIdOrderByIdAsc(projectId);
        if (stacks.isEmpty()) {
            return DecayReport.EMPTY;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (ActorItemStack stack : stacks) {
            keys.add(stack.getItemKey());
        }
        Map<String, ActorItemDef> defs = defsOf(projectId, keys);
        int moved = 0;
        int spoiled = 0;
        int scanned = 0;
        int limit = Math.max(1, DEFAULT_DECAY_SCAN_LIMIT);
        for (ActorItemStack stack : stacks) {
            if (scanned >= limit) {
                log.info("[物品] 腐坏结算达到单次上限 {}，剩余留到下一轮：project={}", limit, projectId);
                break;
            }
            scanned++;
            ActorItemDef def = defs.get(stack.getItemKey());
            Integer decayHours = def == null ? null : def.getDecayHours();
            if (decayHours == null || decayHours <= 0) {
                continue;
            }
            Long freshAt = stack.getFreshAtGameSecond();
            if (freshAt == null) {
                // 首次被腐坏逻辑碰到：从现在开始算新鲜（不追溯，避免「一开世界全烂了」）
                stack.setFreshAtGameSecond(gameSecond);
                stackRepository.save(stack);
                continue;
            }
            int current = stack.getQuality() == null ? DEFAULT_QUALITY : stack.getQuality();
            int target = qualityAt(freshAt, gameSecond, decayHours);
            if (target >= current) {
                continue;
            }
            int qty = stack.getQuantity() == null ? 0 : stack.getQuantity();
            if (qty <= 0) {
                continue;
            }
            String idem = "decay:" + projectId + ":" + stack.getId() + ":" + target;
            if (target <= 0) {
                stackRepository.delete(stack);
                writeLedger(projectId, stack.getItemKey(), -qty, stack.getHolderRef(), null,
                        "decay", null, gameSecond, idem);
                spoiled++;
                log.info("[物品] 腐坏到底：{} {} x{}（{} 小时）", stack.getHolderRef(),
                        stack.getItemKey(), qty, decayHours);
                continue;
            }
            int durability = stack.getDurability() == null ? DEFAULT_DURABILITY : stack.getDurability();
            String holder = stack.getHolderRef();
            String key = stack.getItemKey();
            stackRepository.delete(stack);
            credit(projectId, holder, key, qty, null, new Layer(target, durability, freshAt));
            writeLedger(projectId, key, 0, holder, null, "decay", null, gameSecond, idem);
            moved++;
        }
        if (moved > 0 || spoiled > 0) {
            log.info("[物品] 腐坏结算：project={} 降质 {} 层 / 烂透 {} 层（游戏秒 {}）",
                    projectId, moved, spoiled, gameSecond);
        }
        return new DecayReport(moved, spoiled);
    }

    /**
     * 按新鲜锚点算当前质量（静态、可单测）。
     *
     * @param freshAtGameSecond 新鲜锚点（游戏秒）
     * @param nowGameSecond     当前游戏秒
     * @param decayHours        从新鲜到烂透的小时数
     *
     * @return 质量 0–100
     */
    public static int qualityAt(long freshAtGameSecond, long nowGameSecond, int decayHours) {
        if (decayHours <= 0) {
            return DEFAULT_QUALITY;
        }
        long elapsed = Math.max(0L, nowGameSecond - freshAtGameSecond);
        long full = (long) decayHours * 3600L;
        if (elapsed >= full) {
            return 0;
        }
        int drop = (int) Math.floor(100.0d * elapsed / full);
        return Math.max(0, Math.min(DEFAULT_QUALITY, DEFAULT_QUALITY - drop));
    }

    // ==================== 读入口 ====================

    /**
     * 某持有者某物品的总量（跨品质/耐久求和）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 总量
     */
    @Transactional(readOnly = true)
    public int quantityOf(Long projectId, String holderRef, String itemKey) {
        if (projectId == null || holderRef == null || itemKey == null) {
            return 0;
        }
        Long sum = stackRepository.sumQuantity(projectId, holderRef.trim(), normalizeItemKey(itemKey));
        return sum == null ? 0 : sum.intValue();
    }

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
    @Transactional(readOnly = true)
    public boolean has(Long projectId, String holderRef, String itemKey, int qty) {
        if (qty <= 0) {
            return true;
        }
        return quantityOf(projectId, holderRef, itemKey) >= qty;
    }

    /**
     * 某持有者的全部物品（一个状态层一条，按物品键/品质排序）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 物品视图列表
     */
    @Transactional(readOnly = true)
    public List<ItemView> list(Long projectId, String holderRef) {
        if (projectId == null || holderRef == null || holderRef.isBlank()) {
            return List.of();
        }
        List<ActorItemStack> stacks = stackRepository
                .findByProjectIdAndHolderRefOrderByItemKeyAscQualityAsc(projectId, holderRef.trim());
        if (stacks.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (ActorItemStack stack : stacks) {
            keys.add(stack.getItemKey());
        }
        Map<String, ActorItemDef> defs = defsOf(projectId, keys);
        List<ItemView> views = new ArrayList<>(stacks.size());
        for (ActorItemStack stack : stacks) {
            ActorItemDef def = defs.get(stack.getItemKey());
            views.add(new ItemView(stack.getItemKey(),
                    def == null ? stack.getItemKey() : def.getName(),
                    def == null ? "material" : def.getCategory(),
                    stack.getQuantity() == null ? 0 : stack.getQuantity(),
                    stack.getQuality() == null ? DEFAULT_QUALITY : stack.getQuality(),
                    stack.getDurability() == null ? DEFAULT_DURABILITY : stack.getDurability(),
                    def == null ? null : def.getEquipSlot()));
        }
        return views;
    }

    /**
     * 某持有者某物品「最完好的那一层」（装备解析用）。
     *
     * <p>装备行只记 {@code item_key}，不复制品质/耐久——手上这件到底是哪件，
     * 每次读的时候现找：耐久最高优先，其次品质最高。找不到就是没了（装备行自愈删除）。</p>
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 最完好的一层；没有返回 empty
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ItemView> bestLayerOf(Long projectId, String holderRef, String itemKey) {
        if (projectId == null || holderRef == null || itemKey == null) {
            return java.util.Optional.empty();
        }
        String key = normalizeItemKey(itemKey);
        List<ActorItemStack> layers = stackRepository
                .findByProjectIdAndHolderRefOrderByItemKeyAscQualityAsc(projectId, holderRef.trim());
        ActorItemStack best = null;
        for (ActorItemStack layer : layers) {
            if (layer == null || !key.equalsIgnoreCase(layer.getItemKey())
                    || layer.getQuantity() == null || layer.getQuantity() <= 0) {
                continue;
            }
            if (best == null || betterLayer(layer, best)) {
                best = layer;
            }
        }
        if (best == null) {
            return java.util.Optional.empty();
        }
        ActorItemDef def = findDef(projectId, key).orElse(null);
        return java.util.Optional.of(new ItemView(best.getItemKey(),
                def == null ? best.getItemKey() : def.getName(),
                def == null ? "material" : def.getCategory(),
                best.getQuantity(),
                best.getQuality() == null ? DEFAULT_QUALITY : best.getQuality(),
                best.getDurability() == null ? DEFAULT_DURABILITY : best.getDurability(),
                def == null ? null : def.getEquipSlot()));
    }

    /**
     * 查定义（先本项目、再全局；都没有返回 empty）。
     *
     * @param projectId 项目 ID
     * @param itemKey   物品键
     *
     * @return 定义
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ActorItemDef> findDef(Long projectId, String itemKey) {
        String key = normalizeItemKey(itemKey);
        java.util.Optional<ActorItemDef> own = defRepository.findByProjectIdAndItemKey(projectId, key);
        if (own.isPresent()) {
            return own;
        }
        return defRepository.findByProjectIdAndItemKey(ActorItemDef.GLOBAL_PROJECT, key);
    }

    // ==================== 核心移动 ====================

    /**
     * 一次数量移动（来源可空=产出，目标可空=消耗）。
     *
     * @param projectId 项目 ID
     * @param itemKey   物品键
     * @param qty       数量
     * @param fromRef   来源（可空）
     * @param toRef     目标（可空）
     * @param reason    原因
     * @param eventId   事件
     * @param gameSecond 游戏秒
     * @param idempotencyKey 幂等键
     * @param creationLayer 产出时的初始层（仅 fromRef=null 时生效；null=全新）
     */
    private void move(Long projectId, String itemKey, int qty, String fromRef, String toRef,
                      String reason, Long eventId, long gameSecond, String idempotencyKey,
                      Layer creationLayer) {
        requireProject(projectId);
        String key = normalizeItemKey(itemKey);
        int amount = requireQty(qty);
        String normalizedReason = normalizeReason(reason);
        String idem = normalizeIdempotency(idempotencyKey);
        if (idem != null && ledgerRepository.existsByProjectIdAndIdempotencyKey(projectId, idem)) {
            log.info("[物品] 幂等重放，跳过：project={} key={} item={} qty={}", projectId, idem, key, amount);
            return;
        }
        // 懒建定义（宽松策略：任何字符串都能成为物品，但只建一次并留日志）
        resolveDef(projectId, key);

        if (fromRef == null) {
            credit(projectId, toRef, key, amount, eventId,
                    creationLayer == null ? Layer.fresh(gameSecond) : creationLayer);
        } else {
            List<Consumption> consumed = debit(projectId, fromRef, key, amount, eventId);
            if (toRef != null) {
                for (Consumption part : consumed) {
                    // 品质/耐久/新鲜度随货走：不把「八成新」抹平成全新，也不让面包转手就变新鲜
                    credit(projectId, toRef, key, part.qty(), eventId, part.layer());
                }
            }
        }
        writeLedger(projectId, key, toRef == null ? -amount : amount, fromRef, toRef,
                normalizedReason, eventId, gameSecond, idem);
        log.info("[物品] {} {} x{} {} -> {} reason={} idem={}",
                fromRef == null ? "产出" : "转出", key, amount,
                fromRef == null ? "-" : fromRef, toRef == null ? "-" : toRef, normalizedReason, idem);
    }

    /**
     * 扣减来源（按品质升序、耐久升序逐层消耗，不足抛异常且由事务整体回滚）。
     *
     * <p>「先吃快坏的、先用旧的」：低品质（更接近腐坏）与低耐久（更旧）先出。
     * 与装备解析的「用最好的」刻意相反——那是上阵，这是消耗。</p>
     *
     * @param projectId 项目 ID
     * @param holderRef 来源
     * @param itemKey   物品键
     * @param amount    数量
     * @param eventId   事件
     */
    private List<Consumption> debit(Long projectId, String holderRef, String itemKey, int amount, Long eventId) {
        List<ActorItemStack> stacks = new ArrayList<>(
                stackRepository.lockByHolderAndItem(projectId, holderRef, itemKey));
        // 低品质先消耗（锁语句不带 order by：H2 对 FOR UPDATE + ORDER BY 支持不稳定）
        stacks.sort(Comparator
                .comparingInt((ActorItemStack s) -> s.getQuality() == null ? DEFAULT_QUALITY : s.getQuality())
                .thenComparingInt(s -> s.getDurability() == null ? DEFAULT_DURABILITY : s.getDurability())
                .thenComparingLong(s -> s.getId() == null ? 0L : s.getId()));
        int available = 0;
        for (ActorItemStack stack : stacks) {
            available += stack.getQuantity() == null ? 0 : stack.getQuantity();
        }
        if (available < amount) {
            throw new InsufficientItemException(itemKey, holderRef, amount, available);
        }
        List<Consumption> consumed = new ArrayList<>(stacks.size());
        int remaining = amount;
        for (ActorItemStack stack : stacks) {
            if (remaining <= 0) {
                break;
            }
            int have = stack.getQuantity() == null ? 0 : stack.getQuantity();
            if (have <= 0) {
                continue;
            }
            int take = Math.min(have, remaining);
            int left = have - take;
            if (left == 0) {
                stackRepository.delete(stack);
            } else {
                stack.setQuantity(left);
                bumpVersion(stack, eventId);
                stackRepository.save(stack);
            }
            consumed.add(new Consumption(take, new Layer(
                    stack.getQuality() == null ? DEFAULT_QUALITY : stack.getQuality(),
                    stack.getDurability() == null ? DEFAULT_DURABILITY : stack.getDurability(),
                    stack.getFreshAtGameSecond())));
            remaining -= take;
        }
        return consumed;
    }

    /**
     * 入账到目标（同状态层合并；无则新建）。
     *
     * @param projectId 项目 ID
     * @param holderRef 目标
     * @param itemKey   物品键
     * @param amount    数量
     * @param eventId   事件
     * @param layer     状态层（品质/耐久/新鲜锚点）
     */
    private void credit(Long projectId, String holderRef, String itemKey, int amount, Long eventId, Layer layer) {
        if (holderRef == null || amount <= 0) {
            return;
        }
        Layer target = layer == null ? new Layer(DEFAULT_QUALITY, DEFAULT_DURABILITY, null) : layer;
        ActorItemStack stack = stackRepository
                .findLockedByProjectIdAndHolderRefAndItemKeyAndQualityAndDurability(
                        projectId, holderRef, itemKey, target.quality(), target.durability())
                .orElse(null);
        if (stack == null) {
            stack = new ActorItemStack();
            stack.setProjectId(projectId);
            stack.setHolderRef(holderRef);
            stack.setItemKey(itemKey);
            stack.setQuality(target.quality());
            stack.setDurability(target.durability());
            stack.setFreshAtGameSecond(target.freshAtGameSecond());
            stack.setQuantity(amount);
            stack.setVersion(1);
            stack.setSourceEventId(eventId);
            stack.setUpdatedAt(LocalDateTime.now());
            stackRepository.save(stack);
            return;
        }
        stack.setQuantity((stack.getQuantity() == null ? 0 : stack.getQuantity()) + amount);
        // 混批取「更老」的锚点：一批里混进旧的，整批按最老的算（保守，不会让旧货变新鲜）
        if (target.freshAtGameSecond() != null) {
            Long current = stack.getFreshAtGameSecond();
            if (current == null || target.freshAtGameSecond() < current) {
                stack.setFreshAtGameSecond(target.freshAtGameSecond());
            }
        }
        bumpVersion(stack, eventId);
        stackRepository.save(stack);
    }

    /** 一次扣减片段（数量 + 状态层），用于把品质/耐久/新鲜度随货带到目标。 */
    private record Consumption(int qty, Layer layer) {
    }

    /**
     * 状态层：品质 + 耐久 + 新鲜锚点。
     *
     * @param quality           品质 0–100
     * @param durability        耐久 0–100
     * @param freshAtGameSecond 新鲜锚点（可空）
     */
    public record Layer(int quality, int durability, Long freshAtGameSecond) {

        /** 全新一层（当前游戏秒开始算新鲜）。 */
        public static Layer fresh(long gameSecond) {
            return new Layer(DEFAULT_QUALITY, DEFAULT_DURABILITY, gameSecond);
        }
    }

    /**
     * 腐坏结算报告。
     *
     * @param movedLayers   降质层数
     * @param spoiledLayers 烂透并移除的层数
     */
    public record DecayReport(int movedLayers, int spoiledLayers) {

        /** 空报告 */
        public static final DecayReport EMPTY = new DecayReport(0, 0);

        /** 是否有变化 */
        public boolean changed() {
            return movedLayers > 0 || spoiledLayers > 0;
        }
    }

    // ==================== 辅助 ====================

    /** 版本号 +1 并记录来源事件（排障用）。 */
    private static void bumpVersion(ActorItemStack stack, Long eventId) {
        stack.setVersion(stack.getVersion() == null ? 1 : stack.getVersion() + 1);
        stack.setSourceEventId(eventId == null ? stack.getSourceEventId() : eventId);
    }

    /** 写一条账本（唯一写入口）。 */
    private void writeLedger(Long projectId, String itemKey, int qtyDelta, String fromRef, String toRef,
                             String reason, Long eventId, long gameSecond, String idempotencyKey) {
        ActorItemLedger ledger = new ActorItemLedger();
        ledger.setProjectId(projectId);
        ledger.setItemKey(itemKey);
        ledger.setQtyDelta(qtyDelta);
        ledger.setFromRef(fromRef);
        ledger.setToRef(toRef);
        ledger.setReason(normalizeReason(reason));
        ledger.setWriter(WRITER_BRAIN);
        ledger.setEventId(eventId);
        ledger.setGameSecond(gameSecond);
        ledger.setIdempotencyKey(normalizeIdempotency(idempotencyKey));
        ledgerRepository.save(ledger);
    }

    /** 批量取定义（先全局、再本项目覆盖）。 */
    private Map<String, ActorItemDef> defsOf(Long projectId, Set<String> keys) {
        Map<String, ActorItemDef> defs = new HashMap<>();
        defRepository.findByProjectIdAndItemKeyIn(ActorItemDef.GLOBAL_PROJECT, keys)
                .forEach(def -> defs.put(def.getItemKey(), def));
        defRepository.findByProjectIdAndItemKeyIn(projectId, keys)
                .forEach(def -> defs.put(def.getItemKey(), def));
        return defs;
    }

    /** a 是否比 b 更完好（耐久高优先，其次品质高，最后 id 小）。 */
    private static boolean betterLayer(ActorItemStack a, ActorItemStack b) {
        int da = a.getDurability() == null ? DEFAULT_DURABILITY : a.getDurability();
        int db = b.getDurability() == null ? DEFAULT_DURABILITY : b.getDurability();
        if (da != db) {
            return da > db;
        }
        int qa = a.getQuality() == null ? DEFAULT_QUALITY : a.getQuality();
        int qb = b.getQuality() == null ? DEFAULT_QUALITY : b.getQuality();
        if (qa != qb) {
            return qa > qb;
        }
        return (a.getId() == null ? 0L : a.getId()) < (b.getId() == null ? 0L : b.getId());
    }

    /**
     * 解析定义；缺失时按宽松策略懒建最小定义（material / 默认堆叠 / 0 价）。
     *
     * @param projectId 项目 ID
     * @param itemKey   物品键
     *
     * @return 定义（懒建失败时返回 null，调用方按默认值继续）
     */
    private ActorItemDef resolveDef(Long projectId, String itemKey) {
        java.util.Optional<ActorItemDef> existing = findDef(projectId, itemKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!properties.isLazyDef()) {
            throw new IllegalArgumentException("物品未登记（item.lazy-def=false）：" + itemKey);
        }
        ActorItemDef def = new ActorItemDef();
        def.setProjectId(projectId);
        def.setItemKey(itemKey);
        def.setName(itemKey);
        def.setCategory("material");
        def.setStackMax(Math.max(1, properties.getDefaultStackMax()));
        def.setBasePrice(0);
        try {
            ActorItemDef saved = defRepository.save(def);
            log.info("[物品] 懒建最小定义：project={} item={} category=material stackMax={}",
                    projectId, itemKey, def.getStackMax());
            return saved;
        } catch (RuntimeException e) {
            // 并发下另一个线程刚建过：唯一索引会拦一次，读回即可（单机单玩家极少走到）
            log.warn("[物品] 懒建定义失败（可能并发已建），按默认值继续：{} {}", itemKey, e.toString());
            return findDef(projectId, itemKey).orElse(null);
        }
    }

    // ==================== 校验/归一化 ====================

    private static void requireProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("物品变更缺少 projectId");
        }
    }

    private int requireQty(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("物品数量必须为正数：" + qty);
        }
        int max = Math.max(1, properties.getMaxQtyPerTransfer());
        if (qty > max) {
            throw new IllegalArgumentException("单次物品数量超过上限（" + max + "）：" + qty);
        }
        return qty;
    }

    /**
     * 归一化物品键：去首尾空白、内部空白折叠为下划线、ASCII 小写。
     *
     * @param itemKey 原始键
     *
     * @return 规范化键
     */
    public static String normalizeItemKey(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            throw new IllegalArgumentException("物品键不能为空");
        }
        String key = itemKey.trim().replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
        if (key.length() > 60) {
            key = key.substring(0, 60);
        }
        return key;
    }

    private static String requireHolder(String ref, String side) {
        // 前缀校验唯一源在 ItemRefs（市场也用它，避免两套命名空间口径）
        return ItemRefs.requireHolder(ref, side);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "other";
        }
        String value = reason.trim().toLowerCase(Locale.ROOT);
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static String normalizeIdempotency(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
