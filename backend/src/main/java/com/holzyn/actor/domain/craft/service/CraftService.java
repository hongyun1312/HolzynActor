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

package com.holzyn.actor.domain.craft.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.holzyn.actor.domain.craft.config.CraftProperties;
import com.holzyn.actor.domain.craft.entity.ActorCraftJob;
import com.holzyn.actor.domain.craft.facility.CraftFacilities;
import com.holzyn.actor.domain.craft.model.CraftJobResult;
import com.holzyn.actor.domain.craft.model.CraftOutputKind;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.model.CraftRejectedException;
import com.holzyn.actor.domain.craft.model.CraftStartRequest;
import com.holzyn.actor.domain.craft.model.InsufficientMaterialException;
import com.holzyn.actor.domain.craft.model.MaterialGap;
import com.holzyn.actor.domain.craft.model.RecipeInput;
import com.holzyn.actor.domain.craft.repository.ActorCraftJobRepository;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.entity.ActorItemRecipe;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.repository.ActorItemRecipeRepository;
import com.holzyn.actor.domain.item.service.InventoryService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 制作与建造唯一写入口（制作与建造）。
 *
 * <p><b>单一写入口</b>：{@code actor_craft_job} 只由本类写；{@code actor_item_recipe} 的
 * 全局模板由迁移 V214 播种、本项目覆盖行由本类写入（{@link #saveRecipe}）。物品数量变化仍走
 * {@link InventoryPort}（唯一写入口），设施占用写的是 {@code facility:*} 事实（事实层
 * {@code WorldFactService} 唯一写入口）——本类不直接碰 {@code actor_item_stack}。</p>
 *
 * <p><b>三个「真实」落点</b>：</p>
 * <ol>
 *   <li><b>开工即扣料</b>：材料一投进去就没了，半途放弃也拿不回来（不是「到点才扣」，
 *       否则两个角色能抢同一批料、做出两份）；</li>
 *   <li><b>到点才出活</b>：{@code finish_game_second} 之前什么都没有，设施被占着，
 *       旁人能看见「他正在打铁」；</li>
 *   <li><b>人走了活就废</b>：结算时人不在原地 → {@code failed/LEFT_SITE}，材料不退。</li>
 * </ol>
 *
 * <p><b>事务边界</b>：本类零 AI、零 HTTP。{@link #start} 带 {@code @Transactional}（与效果层
 * 同一事务，失败整批回滚，不会出现「料扣了活没开」）；{@link #finishDue} <b>刻意不加</b>
 * 事务——逐件结算，每件的产物写入都用固定幂等键（{@code craft:{jobId}:out}），
 * 因此「产物已出、状态没落」在下一轮会被幂等重放吸收，不会产出两份。</p>
 */
@Slf4j
@Service
public class CraftService {

    /** 账本原因（消耗与产出都用它，{@code InventoryService.CREATION_REASONS} 白名单内） */
    public static final String REASON_CRAFT = "craft";

    /** 幂等键基础段最大长度（给 {@code :mat:i} / {@code :out} 后缀留空间，账本列宽 80） */
    private static final int IDEM_BASE_MAX = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CraftProperties properties;

    private final ItemProperties itemProperties;

    private final ActorItemRecipeRepository recipeRepository;

    private final ActorCraftJobRepository jobRepository;

    /** 物品端口（item.enabled=true 且 provider=db 才装配；未装配时制作整体不生效） */
    @Autowired(required = false)
    private InventoryPort inventoryPort;

    /** 事实层（设施占用/建造产物；未装配时跳过，不影响结算） */
    @Autowired(required = false)
    private WorldFactService worldFactService;

    /** 事实查询（读设施现有属性，占用时只改 occupied 三项、不覆盖 open/operator） */
    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    /** 地点仓储（只读：把地点名翻成 id，给 facility:loc:{id} 与建造落点用） */
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    /** 角色仓储（只读：结算时判「人还在不在原地」） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 记忆服务（对接：完工后让做的人记住「我亲手打了把刀」；未装配跳过） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.memory.AgentMemoryService agentMemoryService;

    /**
     * 构造（新类允许构造器注入；既有服务不改签名）。
     *
     * @param properties       制作配置
     * @param itemProperties   物品域配置（单次数量上限）
     * @param recipeRepository 配方仓储
     * @param jobRepository    作业仓储
     */
    public CraftService(CraftProperties properties, ItemProperties itemProperties,
                        ActorItemRecipeRepository recipeRepository,
                        ActorCraftJobRepository jobRepository) {
        this.properties = properties;
        this.itemProperties = itemProperties;
        this.recipeRepository = recipeRepository;
        this.jobRepository = jobRepository;
    }

    // ==================== 开关与配方读入口 ====================

    /**
     * 制作是否生效：制作开关 + 物品端口已装配。
     *
     * @return true=可以开工/结算
     */
    public boolean enabled() {
        return properties != null && properties.isEnabled() && inventoryPort != null;
    }

    /**
     * 按配方键取配方（两级查找：本项目覆盖 → 全局模板）。
     *
     * @param projectId 项目 ID
     * @param recipeKey 配方键
     *
     * @return 配方视图；不存在/已停做返回 null
     */
    public CraftRecipeView recipeOf(Long projectId, String recipeKey) {
        if (projectId == null || recipeKey == null || recipeKey.isBlank()) {
            return null;
        }
        String key = recipeKey.trim();
        ActorItemRecipe row = recipeRepository.findByProjectIdAndRecipeKey(projectId, key).orElse(null);
        if (row == null) {
            row = recipeRepository.findByProjectIdAndRecipeKey(ActorItemDef.GLOBAL_PROJECT, key).orElse(null);
        }
        return toView(row);
    }

    /**
     * 解析「这次要做什么」：配方键 → 产出物品键 → 行动文本里的名字。
     *
     * <p>顺序是刻意的：显式配方键最可信；只知道「要打一把柴刀」时按产出反查；
     * 只剩自由文本时才做包含匹配，且<b>取最长命中</b>（「打柴刀」优先于「柴刀」）。</p>
     *
     * @param projectId  项目 ID
     * @param recipeKey  决策里的配方键（可空）
     * @param itemKey    决策里的物品键（可空）
     * @param actionText 行动自由文本（可空）
     *
     * @return 配方；解析不出返回 null（规则层据此保持 UNSURE，绝不臆断）
     */
    public CraftRecipeView findRecipe(Long projectId, String recipeKey, String itemKey, String actionText) {
        CraftRecipeView byKey = recipeOf(projectId, recipeKey);
        if (byKey != null) {
            return byKey;
        }
        String item = itemKey == null ? null : InventoryService.normalizeItemKey(itemKey);
        if (item != null) {
            CraftRecipeView byItem = firstEnabled(
                    recipeRepository.findByProjectIdAndOutputItemKeyOrderByIdAsc(projectId, item));
            if (byItem == null) {
                byItem = firstEnabled(recipeRepository
                        .findByProjectIdAndOutputItemKeyOrderByIdAsc(ActorItemDef.GLOBAL_PROJECT, item));
            }
            if (byItem != null) {
                return byItem;
            }
        }
        return matchByText(projectId, actionText);
    }

    /**
     * 本世界可用的全部配方（本项目覆盖优先；提示词「我会做什么」用）。
     *
     * @param projectId 项目 ID
     *
     * @return 配方视图（按 recipeKey 稳定排序）
     */
    public List<CraftRecipeView> catalogOf(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        Map<String, CraftRecipeView> merged = new LinkedHashMap<>();
        for (ActorItemRecipe row : recipeRepository
                .findByProjectIdAndEnabledOrderByIdAsc(ActorItemDef.GLOBAL_PROJECT, Boolean.TRUE)) {
            CraftRecipeView view = toView(row);
            if (view != null) {
                merged.put(view.recipeKey(), view);
            }
        }
        for (ActorItemRecipe row : recipeRepository
                .findByProjectIdAndEnabledOrderByIdAsc(projectId, Boolean.TRUE)) {
            CraftRecipeView view = toView(row);
            if (view != null) {
                merged.put(view.recipeKey(), view);
            }
        }
        List<CraftRecipeView> views = new ArrayList<>(merged.values());
        views.sort((a, b) -> a.recipeKey().compareTo(b.recipeKey()));
        return views;
    }

    /**
     * 材料缺口（空列表=齐了）。
     *
     * <p><b>刻意是静态方法</b>：规则层（{@code DefaultActionAdjudicator}）要用同一套口径判
     * 「材料齐不齐」，而规则层不装配 Spring。口径只留这一处，规则层不自己再写一遍
     * （AGENTS §8.1.6：同一判定入口不得复制）。</p>
     *
     * @param recipe    配方
     * @param inventory 身上的东西
     * @param batches   份数
     *
     * @return 缺口清单
     */
    public static List<MaterialGap> gapsOf(CraftRecipeView recipe, List<ItemView> inventory, int batches) {
        if (recipe == null || recipe.inputs() == null || recipe.inputs().isEmpty()) {
            return List.of();
        }
        List<MaterialGap> gaps = new ArrayList<>();
        for (RecipeInput input : recipe.inputs()) {
            if (input == null || input.itemKey() == null) {
                continue;
            }
            int need = input.totalFor(Math.max(1, batches));
            int have = ItemView.totalOf(inventory, input.itemKey());
            if (have < need) {
                gaps.add(new MaterialGap(input.itemKey(), need, have));
            }
        }
        return gaps;
    }

    /**
     * 某角色手上还没做完的活（一个角色同时只做一件）。
     *
     * @param projectId 项目 ID
     * @param actorRef  角色 ref
     *
     * @return 作业；没有返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<ActorCraftJob> activeJobOf(Long projectId, String actorRef) {
        if (projectId == null || actorRef == null || actorRef.isBlank()) {
            return Optional.empty();
        }
        List<ActorCraftJob> jobs = jobRepository.findByProjectIdAndActorRefAndStatusOrderByStartedGameSecondAsc(
                projectId, actorRef.trim(), ActorCraftJob.STATUS_IN_PROGRESS);
        return jobs == null || jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0));
    }

    /**
     * 某设施当前被谁占着（R-CRAFT-01 的 OCCUPIED 判定；排除自己）。
     *
     * @param projectId   项目 ID
     * @param facilityRef 设施 ref
     * @param selfRef     自己（可空）
     *
     * @return 占用者 ref；空闲返回 null
     */
    public String occupantOf(Long projectId, String facilityRef, String selfRef) {
        if (projectId == null || facilityRef == null || facilityRef.isBlank()) {
            return null;
        }
        List<ActorCraftJob> jobs = jobRepository.findByProjectIdAndFacilityRefAndStatusOrderByIdAsc(
                projectId, facilityRef.trim(), ActorCraftJob.STATUS_IN_PROGRESS);
        if (jobs == null) {
            return null;
        }
        for (ActorCraftJob job : jobs) {
            if (job == null || job.getActorRef() == null) {
                continue;
            }
            if (selfRef != null && selfRef.equalsIgnoreCase(job.getActorRef())) {
                continue;
            }
            return job.getActorRef();
        }
        return null;
    }

    // ==================== 开工 ====================

    /**
     * 开工（幂等）。
     *
     * <p>顺序固定：<b>幂等 → 结算旧活 → 唯一在制 → 配方 → 设施 → 材料 → 扣料 → 建作业 → 占设施</b>。
     * 任何一步抛错都由外层事务整批回滚（不会出现「料扣了活没开」）。</p>
     *
     * @param req 开工请求
     *
     * @return 开工结果（重放时 {@code replay=true}，不会再扣一次料）
     */
    @Transactional
    public CraftJobResult start(CraftStartRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("开工请求不能为空");
        }
        if (!enabled()) {
            throw new IllegalStateException("制作未启用（需要 item.enabled=true 且 craft.enabled=true）");
        }
        requireProject(req.projectId());
        String actor = ItemRefs.requireHolder(req.actorRef(), "actor");
        String idem = normalizeIdem(req.idempotencyKey());

        // ① 幂等：同一效果重放直接返回原作业，不再扣料
        if (idem != null) {
            Optional<ActorCraftJob> existing = jobRepository
                    .findByProjectIdAndIdempotencyKey(req.projectId(), idem);
            if (existing.isPresent()) {
                ActorCraftJob job = existing.get();
                log.info("[制作] 幂等重放，跳过：project={} idem={} job={}", req.projectId(), idem, job.getId());
                return new CraftJobResult(job.getId(), job.getRecipeKey(), job.getOutputItemKey(),
                        job.totalOutput(), intOf(job.getBatches(), 1), longOf(job.getFinishGameSecond(), 0L), true);
            }
        }

        // ② 先把这个人到点的旧活结算掉，BUSY 判定才是准的
        finishDueFor(req.projectId(), actor, req.gameSecond());

        // ③ 一个角色同时只做一件活
        Optional<ActorCraftJob> active = activeJobOf(req.projectId(), actor);
        if (active.isPresent()) {
            throw new CraftRejectedException("BUSY",
                    "手上还压着活：" + active.get().getRecipeKey() + "（job=" + active.get().getId() + "）");
        }

        // ④ 配方
        CraftRecipeView recipe = recipeOf(req.projectId(), req.recipeKey());
        if (recipe == null) {
            throw new CraftRejectedException("RECIPE_UNKNOWN", "不知道该怎么做：" + req.recipeKey());
        }
        int batches = requireBatches(req.batches());

        // ⑤ 设施门控（类型/开关/占用）
        ActorWorldFact facility = resolveFacility(req.projectId(), req.facilityRef(), req.locationName());
        checkFacility(recipe, facility, actor, req.facilityRef(), req.locationName());

        // ⑥ 材料
        List<ItemView> inventory = inventoryPort.list(req.projectId(), actor);
        List<MaterialGap> gaps = gapsOf(recipe, inventory, batches);
        if (!gaps.isEmpty()) {
            throw new InsufficientMaterialException(recipe.recipeKey(), gaps);
        }
        requireAmountsFit(recipe, batches);

        // ⑦ 扣料（一样材料一条账；幂等键 idem:mat:{i}，重放不重复扣）
        int index = 0;
        for (RecipeInput input : recipe.inputs()) {
            int need = input.totalFor(batches);
            index++;
            if (need <= 0) {
                continue;
            }
            inventoryPort.consume(req.projectId(), input.itemKey(), need, actor, REASON_CRAFT,
                    req.eventId(), req.gameSecond(), idem == null ? null : idem + ":mat:" + index);
        }

        // ⑧ 建作业（到点才出活）
        int minutes = clampMinutes(recipe.totalMinutes(batches));
        ActorCraftJob job = new ActorCraftJob();
        job.setProjectId(req.projectId());
        job.setActorRef(actor);
        job.setRecipeKey(recipe.recipeKey());
        job.setOutputItemKey(recipe.outputItemKey());
        job.setOutputKind(CraftOutputKind.normalize(recipe.outputKind()));
        job.setOutputQty(recipe.outputQty());
        job.setBatches(batches);
        job.setFacilityType(recipe.facilityType());
        job.setFacilityRef(facilityRefOf(req.facilityRef(), req.locationId(), req.locationName(), facility));
        job.setLocationId(req.locationId());
        job.setLocationName(req.locationName());
        job.setStartedGameSecond(req.gameSecond());
        job.setFinishGameSecond(req.gameSecond() + minutes * 60L);
        job.setStatus(ActorCraftJob.STATUS_IN_PROGRESS);
        job.setEventId(req.eventId());
        job.setIdempotencyKey(idem);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);

        // ⑨ 占设施（旁人能看见「他正在打铁」；失败只 WARN，不拖垮开工）
        occupy(job, true);

        log.info("[制作] 开工：{} 做 {} x{}（{} 分钟，设施={}）job={}",
                actor, recipe.recipeKey(), batches, minutes, job.getFacilityRef(), job.getId());
        return new CraftJobResult(job.getId(), recipe.recipeKey(), recipe.outputItemKey(),
                recipe.totalOutput(batches), batches, job.getFinishGameSecond(), false);
    }

    // ==================== 结算 ====================

    /**
     * 结算到点的活（世界引擎每游戏小时调一次；也可被「开工前顺手结算」触发）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：逐件结算，单件失败不影响其他角色；
     * 每件的产物写入用固定幂等键，因此半途失败在下一轮会被吸收，不会产出两份。</p>
     *
     * @param projectId  项目 ID
     * @param gameSecond 当前游戏秒
     *
     * @return 本轮处理完（含失败/离场）的件数
     */
    public int finishDue(Long projectId, long gameSecond) {
        if (!enabled() || projectId == null) {
            return 0;
        }
        List<ActorCraftJob> due = jobRepository.findDue(projectId, ActorCraftJob.STATUS_IN_PROGRESS,
                gameSecond, PageRequest.of(0, Math.max(1, properties.getMaxJobsPerSweep())));
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int settled = 0;
        for (ActorCraftJob job : due) {
            if (job == null) {
                continue;
            }
            try {
                if (settleOne(job, gameSecond)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.warn("[制作] 结算失败（隔离继续）：job={} recipe={} : {}",
                        job.getId(), job.getRecipeKey(), e.getMessage());
                markFailed(job, "SETTLE_ERROR:" + abbreviate(e.getMessage(), 48));
            }
        }
        if (settled > 0) {
            log.info("[制作] 本轮结算 {} 件（project={} gs={}）", settled, projectId, gameSecond);
        }
        return settled;
    }

    /**
     * 只结算某角色到点的活（开工前调用，保证 BUSY 判定准确）。
     *
     * @param projectId  项目 ID
     * @param actorRef   角色 ref
     * @param gameSecond 当前游戏秒
     *
     * @return 结算件数
     */
    public int finishDueFor(Long projectId, String actorRef, long gameSecond) {
        if (!enabled() || projectId == null || actorRef == null || actorRef.isBlank()) {
            return 0;
        }
        List<ActorCraftJob> mine = jobRepository
                .findByProjectIdAndActorRefAndStatusOrderByStartedGameSecondAsc(
                        projectId, actorRef.trim(), ActorCraftJob.STATUS_IN_PROGRESS);
        if (mine == null || mine.isEmpty()) {
            return 0;
        }
        int settled = 0;
        for (ActorCraftJob job : mine) {
            if (job == null || job.getFinishGameSecond() == null || job.getFinishGameSecond() > gameSecond) {
                continue;
            }
            try {
                if (settleOne(job, gameSecond)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.warn("[制作] 结算失败（隔离继续）：job={} : {}", job.getId(), e.getMessage());
                markFailed(job, "SETTLE_ERROR:" + abbreviate(e.getMessage(), 48));
            }
        }
        return settled;
    }

    /**
     * 取消一件活（主动放弃；材料不退，见类注释「开工即扣料」）。
     *
     * @param projectId 项目 ID
     * @param jobId     作业 ID
     * @param reason    原因
     *
     * @return true=已取消；false=作业不存在/已结束
     */
    @Transactional
    public boolean cancel(Long projectId, Long jobId, String reason) {
        if (projectId == null || jobId == null) {
            return false;
        }
        ActorCraftJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !projectId.equals(job.getProjectId()) || !job.inProgress()) {
            return false;
        }
        job.setStatus(ActorCraftJob.STATUS_CANCELLED);
        job.setReason(abbreviate(reason == null ? "cancelled" : reason, 64));
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
        release(job);
        log.info("[制作] 取消：job={} recipe={} reason={}", jobId, job.getRecipeKey(), job.getReason());
        return true;
    }

    /**
     * 某角色的作业历史（排障/前端）。
     *
     * @param projectId 项目 ID
     * @param actorRef  角色 ref
     * @param limit     上限
     *
     * @return 作业（新的在前）
     */
    @Transactional(readOnly = true)
    public List<ActorCraftJob> jobsOf(Long projectId, String actorRef, int limit) {
        if (projectId == null || actorRef == null || actorRef.isBlank()) {
            return List.of();
        }
        List<ActorCraftJob> jobs = jobRepository.findByProjectIdAndActorRefOrderByIdDesc(
                projectId, actorRef.trim(), PageRequest.of(0, Math.max(1, limit)));
        return jobs == null ? List.of() : jobs;
    }

    /**
     * 某状态下的作业数（冒烟/看板断言）。
     *
     * @param projectId 项目 ID
     * @param status    状态
     *
     * @return 数量
     */
    public long countByStatus(Long projectId, String status) {
        if (projectId == null || status == null) {
            return 0L;
        }
        return jobRepository.countByProjectIdAndStatus(projectId, status);
    }

    // ==================== 结算核心 ====================

    /**
     * 结算一件活。
     *
     * @param job        作业
     * @param gameSecond 当前游戏秒
     *
     * @return true=处理了（完成或判失败）
     */
    private boolean settleOne(ActorCraftJob job, long gameSecond) {
        // 人走了：活就废了（材料不退——已经投进炉子里了）
        if (leftSite(job)) {
            markFailed(job, "LEFT_SITE");
            return true;
        }
        String kind = CraftOutputKind.normalize(job.getOutputKind());
        if (CraftOutputKind.FACILITY.equals(kind) || CraftOutputKind.PROP.equals(kind)) {
            buildOutput(job, kind, gameSecond);
        } else {
            // 产物写入用固定幂等键：半途失败后重放不会产出两份
            inventoryPort.produce(job.getProjectId(), job.getOutputItemKey(), job.totalOutput(),
                    job.getActorRef(), REASON_CRAFT, job.getEventId(), gameSecond,
                    "craft:" + job.getId() + ":out");
        }
        job.setStatus(ActorCraftJob.STATUS_DONE);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
        release(job);
        rememberCraft(job);
        log.info("[制作] 完工：job={} {} x{} → {}（{}）", job.getId(), job.getRecipeKey(),
                job.getBatches(), job.getOutputItemKey(), job.getActorRef());
        return true;
    }

    /**
     * 完工后写一条观察记忆（对接）。
     *
     * <p>「我亲手打了把刀」是人会记住的事，尤其是打了半天的那件。只留作业行的话，
     * 三天后 NPC 被问到「你的刀哪来的」只能编。失败只 WARN，不影响已经落地的产物。</p>
     */
    private void rememberCraft(ActorCraftJob job) {
        if (agentMemoryService == null || job == null || job.getActorRef() == null) {
            return;
        }
        try {
            String text = "做成了 " + job.totalOutput() + " 件" + job.getOutputItemKey()
                    + "（" + job.getRecipeKey() + "）";
            agentMemoryService.recordObservation(job.getProjectId(), job.getActorRef(), text, 2, null);
        } catch (RuntimeException e) {
            log.warn("[制作] 完工记忆写入失败（不影响产物）：job={} : {}", job.getId(), e.getMessage());
        }
    }

    /**
     * 建造产物落地：设施 → {@code facility:loc:{id}}，物件 → {@code prop:loc:{id}:{key}}。
     *
     * <p>为什么建造不走物品栈：一间铺子不是「背包里的一件东西」，它得占地方、能被人看见、
     * 能被 R-USE-01 门控。写成事实才进入「看得见的」世界层（§0.4）。</p>
     */
    private void buildOutput(ActorCraftJob job, String kind, long gameSecond) {
        if (properties == null || !properties.isBuildFactSync() || worldFactService == null) {
            log.info("[制作] 建造事实同步关闭，仅在作业表留痕：job={}", job.getId());
            return;
        }
        String name = job.getOutputItemKey();
        Long locationId = job.getLocationId();
        // 落点的地名以「地点 ID 查出来的名字」为准：作业行里的 location_name 存的是
        // 「开工时人在哪」（用于判中途离场），不一定等于建造落点（可能是村东头的新工地）。
        String locationName = locationNameOfId(locationId);
        if (locationName == null) {
            locationName = job.getLocationName();
        }
        boolean facility = CraftOutputKind.FACILITY.equals(kind);
        String factKey = facility
                ? (locationId != null ? "facility:loc:" + locationId : "facility:" + nullSafe(locationName))
                : (locationId != null ? "prop:loc:" + locationId + ":" + name
                : "prop:" + nullSafe(locationName) + ":" + name);
        ObjectNode attrs = MAPPER.createObjectNode();
        if (facility) {
            attrs.put("type", name);
            attrs.put("open", true);
            attrs.put("occupied", false);
        } else {
            attrs.put("itemKey", name);
            attrs.put("qty", job.totalOutput());
        }
        attrs.put("builtBy", nullSafe(job.getActorRef()));
        attrs.put("builtAtGameSecond", gameSecond);
        attrs.put("recipeKey", nullSafe(job.getRecipeKey()));
        try {
            FactCommand cmd = new FactCommand(job.getProjectId(), facility ? "facility" : "prop",
                    factKey, name, "新落成的" + name, attrs.toString(), locationId, locationName,
                    properties.getBuildVisibility(), FactAuthority.BRAIN,
                    gameSecond, null, gameSecond, job.getActorRef(), job.getEventId(), 3,
                    "craft:" + job.getId() + ":build");
            worldFactService.upsert(cmd);
        } catch (RuntimeException e) {
            // 建造事实写不进去不该让「活干完了」回滚（与 MarketService 同步 stock 事实同口径）
            log.warn("[制作] 建造事实同步失败（不影响完工）：job={} key={} : {}",
                    job.getId(), factKey, e.getMessage());
        }
    }

    /** 结算时判定「人还在不在原地」；任一侧未知按「在」（不冤枉人）。 */
    private boolean leftSite(ActorCraftJob job) {
        if (characterRepository == null || job.getLocationName() == null || job.getLocationName().isBlank()) {
            return false;
        }
        Long characterId = characterIdOf(job.getActorRef());
        if (characterId == null) {
            return false;
        }
        ActorCharacter actor;
        try {
            actor = characterRepository.findById(characterId).orElse(null);
        } catch (RuntimeException e) {
            return false;
        }
        if (actor == null || actor.getLocation() == null || actor.getLocation().isBlank()) {
            return false;
        }
        return !actor.getLocation().trim().equalsIgnoreCase(job.getLocationName().trim());
    }

    // ==================== 设施 ====================

    /**
     * 设施门控（R-CRAFT-01 的「设施可用」）：类型对得上、开着、没被别人占着。
     *
     * @param recipe       配方
     * @param facility     设施事实（可空）
     * @param actorRef     行动者
     * @param facilityRef  设施 ref（可空）
     * @param locationName 地点名（可空）
     */
    private void checkFacility(CraftRecipeView recipe, ActorWorldFact facility, String actorRef,
                               String facilityRef, String locationName) {
        if (!recipe.needsFacility()) {
            return;
        }
        if (facility == null) {
            throw new CraftRejectedException("NO_FACILITY",
                    "这儿没有「" + recipe.facilityType() + "」（ref=" + facilityRef + " 地点=" + locationName + "）");
        }
        String factType = CraftFacilities.factTypeOf(facility.getAttributesJson());
        String name = facility.getName() == null ? facility.getLocationName() : facility.getName();
        if (!CraftFacilities.matches(recipe.facilityType(), factType, firstNonBlank(name, locationName))) {
            throw new CraftRejectedException("NO_FACILITY",
                    "这处设施是「" + firstNonBlank(factType, name) + "」，做不了「" + recipe.facilityType() + "」的活");
        }
        if ("false".equalsIgnoreCase(attrsOf(facility).path("open").asText("true"))) {
            throw new CraftRejectedException("NO_FACILITY", "设施关着：" + name);
        }
        String occupant = occupantOf(facility.getProjectId(), facility.getFactKey(), actorRef);
        if (occupant != null) {
            throw new CraftRejectedException("OCCUPIED", "设施被别人占着：" + name + "（" + occupant + "）");
        }
    }

    /**
     * 找到这次要用的设施事实。
     *
     * <p>优先用调用方给的 ref；没给就用地点名反查 {@code facility:loc:{id}}。两者都查不到时
     * 再退回 {@code facility:{名字}}（世界生成早期可能没写 loc: 前缀）。</p>
     *
     * @param projectId    项目 ID
     * @param facilityRef  设施 ref（可空）
     * @param locationName 地点名（可空）
     *
     * @return 设施事实；查不到返回 null
     */
    public ActorWorldFact resolveFacility(Long projectId, String facilityRef, String locationName) {
        if (projectId == null || worldFactQuery == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>(2);
        if (facilityRef != null && !facilityRef.isBlank()) {
            String ref = facilityRef.trim();
            candidates.add(ref.startsWith("facility:") ? ref : "facility:" + ref);
        }
        Long locationId = locationIdOf(projectId, locationName);
        if (locationId != null) {
            candidates.add("facility:loc:" + locationId);
        }
        if (locationName != null && !locationName.isBlank()) {
            candidates.add("facility:" + locationName.trim());
        }
        for (String key : candidates) {
            try {
                Optional<ActorWorldFact> fact = worldFactQuery.byKey(projectId, key);
                if (fact.isPresent()) {
                    ActorWorldFact f = fact.get();
                    if (f.getStatus() == null || "active".equalsIgnoreCase(f.getStatus())) {
                        return f;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("[制作] 读设施事实失败（继续试下一个）：{} : {}", key, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 占用/释放设施（写 {@code facility:*} 事实的 occupied 三项，保留 open/operator 等原属性）。
     *
     * <p>为什么是事实而不是新表：设施状态本来就住在 {@code actor_world_fact(facility)}，
     * 再开一张「设施占用表」就是两套真相。R-USE-01 读的也是这三个属性。</p>
     *
     * @param job     作业
     * @param occupied true=占用，false=释放
     */
    private void occupy(ActorCraftJob job, boolean occupied) {
        if (worldFactService == null || worldFactQuery == null || properties == null) {
            return;
        }
        String ref = job.getFacilityRef();
        if (ref == null || ref.isBlank()) {
            return;
        }
        String factKey = ref.startsWith("facility:") ? ref : "facility:" + ref;
        try {
            ActorWorldFact existing = worldFactQuery.byKey(job.getProjectId(), factKey).orElse(null);
            if (existing == null) {
                return;
            }
            ObjectNode attrs = MAPPER.createObjectNode();
            JsonNode old = attrsOf(existing);
            if (old != null && old.isObject()) {
                old.properties().forEach(entry -> attrs.set(entry.getKey(), entry.getValue()));
            }
            attrs.put("occupied", occupied);
            if (occupied) {
                attrs.put("occupiedBy", nullSafe(job.getActorRef()));
                attrs.put("occupiedUntilGameSecond", longOf(job.getFinishGameSecond(), 0L));
                attrs.put("occupiedForJob", longOf(job.getId(), 0L));
            } else {
                attrs.remove("occupiedBy");
                attrs.remove("occupiedUntilGameSecond");
                attrs.remove("occupiedForJob");
            }
            FactCommand cmd = new FactCommand(job.getProjectId(), "facility", factKey,
                    existing.getName(), occupied ? "有人正在用" : "空着",
                    attrs.toString(), existing.getLocationId(), existing.getLocationName(),
                    existing.getVisibility(), FactAuthority.BRAIN,
                    existing.getValidFromGameSecond(), existing.getValidToGameSecond(),
                    job.getStartedGameSecond(), job.getActorRef(), job.getEventId(),
                    3, "craft:" + job.getId() + (occupied ? ":occ" : ":rel"));
            worldFactService.upsert(cmd);
        } catch (RuntimeException e) {
            log.warn("[制作] 设施占用事实同步失败（不影响开工/完工）：job={} ref={} : {}",
                    job.getId(), factKey, e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /** 实体 → 只读视图（停做/坏配方返回 null）。 */
    private CraftRecipeView toView(ActorItemRecipe row) {
        if (row == null || Boolean.FALSE.equals(row.getEnabled())) {
            return null;
        }
        return new CraftRecipeView(row.getRecipeKey(), row.getOutputItemKey(),
                intOf(row.getOutputQty(), 1), CraftOutputKind.normalize(row.getOutputKind()),
                row.getFacilityType(), intOf(row.getDurationMinutes(), defaultDuration()),
                parseInputs(row.getInputsJson()));
    }

    /** 解析 {@code inputs_json}；坏 JSON 按空清单处理（配方坏掉不等于世界崩掉）。 */
    static List<RecipeInput> parseInputs(String inputsJson) {
        if (inputsJson == null || inputsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(inputsJson);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<RecipeInput> inputs = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                String key = node.path("itemKey").asText("").trim();
                int qty = node.path("qty").asInt(0);
                if (key.isEmpty() || qty <= 0) {
                    continue;
                }
                inputs.add(new RecipeInput(InventoryService.normalizeItemKey(key), qty));
            }
            return inputs;
        } catch (RuntimeException e) {
            log.warn("[制作] inputs_json 解析失败，按无材料处理：{} : {}", inputsJson, e.getMessage());
            return List.of();
        }
    }

    /** 按行动文本匹配配方（最长命中优先）。 */
    private CraftRecipeView matchByText(Long projectId, String actionText) {
        if (actionText == null || actionText.isBlank()) {
            return null;
        }
        String text = actionText.trim();
        CraftRecipeView best = null;
        int bestScore = 0;
        for (CraftRecipeView view : catalogOf(projectId)) {
            int score = 0;
            if (view.recipeKey() != null && text.contains(view.recipeKey())) {
                score = view.recipeKey().length();
            }
            if (view.outputItemKey() != null && text.contains(view.outputItemKey())) {
                score = Math.max(score, view.outputItemKey().length());
            }
            if (score > bestScore) {
                best = view;
                bestScore = score;
            }
        }
        return best;
    }

    /** 取第一条可用配方。 */
    private CraftRecipeView firstEnabled(List<ActorItemRecipe> rows) {
        if (rows == null) {
            return null;
        }
        for (ActorItemRecipe row : rows) {
            CraftRecipeView view = toView(row);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    /** 设施 ref 归一化（没给 ref 时用地点 id 兜底）。 */
    private String facilityRefOf(String facilityRef, Long locationId, String locationName,
                                 ActorWorldFact facility) {
        if (facilityRef != null && !facilityRef.isBlank()) {
            String ref = facilityRef.trim();
            return ref.startsWith("facility:") ? ref : "facility:" + ref;
        }
        if (facility != null && facility.getFactKey() != null) {
            return facility.getFactKey();
        }
        if (locationId != null) {
            return "facility:loc:" + locationId;
        }
        return null;
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

    /** 地点 ID → 地点名（查不到返回 null，不编地名）。 */
    private String locationNameOfId(Long locationId) {
        if (locationId == null || locationRepository == null) {
            return null;
        }
        try {
            return locationRepository.findById(locationId).map(ActorWorldLocation::getName).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code npc:123} → 123（解析不出返回 null）。 */
    private static Long characterIdOf(String actorRef) {
        if (actorRef == null) {
            return null;
        }
        String ref = actorRef.trim();
        if (!ref.startsWith("npc:")) {
            return null;
        }
        try {
            return Long.parseLong(ref.substring("npc:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 标失败（材料不退）+ 释放设施。 */
    private void markFailed(ActorCraftJob job, String reason) {
        try {
            job.setStatus(ActorCraftJob.STATUS_FAILED);
            job.setReason(abbreviate(reason, 64));
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
            release(job);
            log.info("[制作] 判失败：job={} recipe={} reason={}", job.getId(), job.getRecipeKey(), reason);
        } catch (RuntimeException e) {
            log.warn("[制作] 标记失败时出错（下轮再试）：job={} : {}", job.getId(), e.getMessage());
        }
    }

    /** 释放设施占用。 */
    private void release(ActorCraftJob job) {
        occupy(job, false);
    }

    /** 材料/产出总量必须落在单次转移上限内（否则扣料中途会被拒绝）。 */
    private void requireAmountsFit(CraftRecipeView recipe, int batches) {
        int max = itemProperties == null ? 999 : Math.max(1, itemProperties.getMaxQtyPerTransfer());
        for (RecipeInput input : recipe.inputs()) {
            int need = input.totalFor(batches);
            if (need > max) {
                throw new CraftRejectedException("TOO_MANY",
                        "一次要用的「" + input.itemKey() + "」超过单次上限（" + max + "）：" + need);
            }
        }
        if (recipe.totalOutput(batches) > max) {
            throw new CraftRejectedException("TOO_MANY",
                    "一次产出超过单次上限（" + max + "）：" + recipe.totalOutput(batches));
        }
    }

    private int requireBatches(int batches) {
        if (batches <= 0) {
            throw new IllegalArgumentException("份数必须为正数：" + batches);
        }
        int max = Math.max(1, properties.getMaxBatchesPerJob());
        if (batches > max) {
            throw new IllegalArgumentException("一次最多做 " + max + " 份：" + batches);
        }
        return batches;
    }

    /**
     * 把「想做的份数」钳到合法区间（规则层用它，避免超出上限的决策直接炸掉整批效果）。
     *
     * @param requested 决策里的份数（≤0 按 1）
     *
     * @return 合法份数
     */
    public int clampBatches(int requested) {
        int max = properties == null ? 1 : Math.max(1, properties.getMaxBatchesPerJob());
        return Math.min(Math.max(1, requested), max);
    }

    private int clampMinutes(int minutes) {
        int value = minutes <= 0 ? defaultDuration() : minutes;
        return Math.min(value, Math.max(1, properties.getMaxDurationMinutes()));
    }

    private int defaultDuration() {
        return properties == null ? 30 : Math.max(1, properties.getDefaultDurationMinutes());
    }

    /** 解析事实属性 JSON；坏 JSON 返回空对象（不阻断）。 */
    private static JsonNode attrsOf(ActorWorldFact fact) {
        if (fact == null || fact.getAttributesJson() == null || fact.getAttributesJson().isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(fact.getAttributesJson());
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (RuntimeException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static void requireProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("制作操作缺少 projectId");
        }
    }

    private static String normalizeIdem(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim();
        if (value.length() > IDEM_BASE_MAX) {
            value = value.substring(0, IDEM_BASE_MAX);
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim().replaceAll("\\s+", " ");
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static int intOf(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long longOf(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}
