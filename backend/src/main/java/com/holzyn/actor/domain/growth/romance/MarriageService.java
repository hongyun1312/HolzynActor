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

package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineageRepository;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestone;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 婚育流程编排服务。
 * <p><b>流程（日检 {@link #dailyCheck} 驱动，挂 WorldEngine 日级任务）</b>：</p>
 * <pre>
 * 心动（AI 判定，7 游戏日冷却/对）
 * └→ 单向 ROMANTIC 边（actor_relation，description 存恋爱状态机元数据）
 * 双向心动 → 恋爱成立
 * 恋爱满 30 游戏日（每 15 游戏日检查）→ 求婚（AI 判定）
 * └→ 成功 → 婚礼（lineage 婚姻边 + 事件 + 里程碑 + AI 叙事 + 双方记忆）
 * 婚后每 30 游戏日 → 生育意愿（AI 判定，门控：适龄/冷却/人口）
 * └→ 想生 → PregnancyService 受孕 → 280 游戏日 → OffspringGenerator 分娩
 * </pre>
 * <p><b>程序化硬门控（零 AI，不可被 AI 覆盖）</b>：适龄（YOUTH~MIDDLE=18~60 岁）/ 在世 /
 * 同村 / 异性 / 无婚姻边 / <b>三代内血亲禁婚</b>（{@code LineageService.isBloodRelated}）/
 * 好感 ≥ 60 / 恋爱 ≥ 30 游戏日 / 生育冷却 180 游戏日 / 人口上限 50。</p>
 * <p><b>三插拔点（决策模型 预留）</b>：{@link RomanceDecisionSource} / {@link ProposalDecisionSource} /
 * {@link FertilityDecisionSource}——AI 主判定，程序化兜底，N 轨道换实现零重写主流程。</p>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}（日检含 AI 判定，事务内
 * 持锁会堵世界）；婚礼叙事在虚拟线程异步执行；任何一步失败只告警不阻断日检。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarriageService {

    /** 婚礼叙事场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板） */
    public static final String SCENE_WEDDING_NARRATIVE = "growth_wedding_narrative";

    /** 恋爱状态机元数据键（actor_relation.description 存 JSON） */
    private static final String ROMANCE_STATE_COURTING = "courting";
    private static final String ROMANCE_STATE_MARRIED = "married";

    /** 婚礼叙事异步池（不占日检线程；与阶段叙事同款） */
    private final ExecutorService narrativeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorGrowthLineageRepository lineageRepository;
    private final GrowthMilestoneRepository milestoneRepository;
    private final LineageService lineageService;
    private final GrowthProperties growthProperties;
    private final RomanceDecisionSource romanceDecisionSource;
    private final ProposalDecisionSource proposalDecisionSource;
    private final FertilityDecisionSource fertilityDecisionSource;
    private final PregnancyService pregnancyService;
    private final AgentMemoryService agentMemoryService;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final AiDecisionSourceSupport aiSupport;
    /** 项目（世界）仓库：婚礼叙事取世界名（通用世界观——不预设任何特定文化/聚落形态） */
    private final com.holzyn.actor.domain.project.repository.ActorProjectRepository projectRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    //
    // 日检
    //

    /**
     * 婚育日检：心动 → 求婚 → 生育意愿 → 分娩（全部零阻断，失败隔离）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     */
    public void dailyCheck(Long projectId, Long gameSecond) {
        if (!growthProperties.getReproduction().isEnabled()) {
            return;
        }
        try {
            checkAttractions(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[婚育] 心动检查失败（隔离继续）: projectId={} : {}", projectId, e.getMessage());
        }
        try {
            checkProposals(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[婚育] 求婚检查失败（隔离继续）: projectId={} : {}", projectId, e.getMessage());
        }
        try {
            checkFertility(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[婚育] 生育意愿检查失败（隔离继续）: projectId={} : {}", projectId, e.getMessage());
        }
        try {
            pregnancyService.checkBirths(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[婚育] 分娩检查失败（隔离继续）: projectId={} : {}", projectId, e.getMessage());
        }
    }

    //
    // ① 心动检查（每 7 游戏日/对，AI 判定；ROMANTIC 边 = 已心动）
    //

    /** 心动检查：对每对「适龄未婚 + 好感达标 + 非血亲 + 冷却到期」的候选做 AI 心动判定。 */
    void checkAttractions(Long projectId, Long gameSecond) {
        int attractionCooldown = growthProperties.getReproduction().getAttractionCooldownGameDays() * 86400;
        List<ActorCharacter> all = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter npc : all) {
            if (!romanceEligible(npc)) {
                continue;
            }
            // 候选池：异性、适龄、在世、无婚姻边、非血亲、好感 ≥ 门槛、无既有 ROMANTIC 边、冷却到期
            ActorCharacterRelation best = null;
            int bestAffinity = Integer.MIN_VALUE;
            for (ActorCharacterRelation rel : relationsOf(projectId, npc.getId())) {
                if (rel == null) {
                    continue;
                }
                String otherRef = npc.getId().equals(rel.getFromCharacterId()) ? rel.getToRef() : rel.getFromRef();
                if (otherRef == null || !otherRef.startsWith("npc:")) {
                    continue;
                }
                ActorCharacter other = characterRepository.findById(NpcRefs.idOf(otherRef)).orElse(null);
                if (other == null || !romanceEligible(other)) {
                    continue;
                }
                if (rel.getAffinity() == null || rel.getAffinity() < growthProperties.getReproduction()
                        .getRomanceAffinityThreshold()) {
                    continue;
                }
                if (!isOppositeGender(npc, other)) {
                    continue;
                }
                if (lineageService.isMarried(projectId, otherRef)
                        || lineageService.isBloodRelated(projectId, npc.npcRef(), otherRef)) {
                    continue;
                }
                if (hasRomanceEdge(projectId, npc.npcRef(), otherRef)) {
                    continue;
                }
                // 冷却：7 游戏日内判定过该对象 → 跳过
                Long lastCheck = lastCheckOf(rel.getDescription());
                if (lastCheck != null && gameSecond - lastCheck < attractionCooldown) {
                    continue;
                }
                if (rel.getAffinity() > bestAffinity) {
                    bestAffinity = rel.getAffinity();
                    best = rel;
                }
            }
            if (best == null) {
                continue;
            }
            String partnerRef = npc.getId().equals(best.getFromCharacterId()) ? best.getToRef() : best.getFromRef();
            ActorCharacter partner = characterRepository.findById(NpcRefs.idOf(partnerRef)).orElse(null);
            if (partner == null) {
                continue;
            }
            RomanceDecisions.AttractionContext ctx = new RomanceDecisions.AttractionContext(
                    projectId, npc.npcRef(), npc.getName(), partnerRef, partner.getName(),
                    interactionEvidenceOf(best), bestAffinity, gameSecond);
            RomanceDecisions.AttractionDecision decision;
            try {
                decision = romanceDecisionSource.decide(ctx);
            } catch (Exception e) {
                log.warn("[婚育] 心动判定异常（按不动心跳过）: {} → {} : {}", npc.getName(), partner.getName(),
                        e.getMessage());
                continue;
            }
            if (decision != null && decision.attracted()) {
                registerRomance(projectId, npc, partner, gameSecond);
            }
        }
    }

    /** 登记单向心动（ROMANTIC 边 + 事件 + 记忆；幂等）。 */
    private void registerRomance(Long projectId, ActorCharacter npc, ActorCharacter partner, Long gameSecond) {
        ActorCharacterRelation edge = findRelation(projectId, npc.getId(), partner.getId());
        if (edge == null) {
            edge = new ActorCharacterRelation();
            edge.setProjectId(projectId);
            edge.setFromCharacterId(npc.getId());
            edge.setToCharacterId(partner.getId());
            edge.setFromRef(npc.npcRef());
            edge.setToRef(partner.npcRef());
            edge.setFromName(npc.getName());
            edge.setToName(partner.getName());
            edge.setRelationType("ROMANTIC");
            edge.setAffinity(edge.getAffinity() == null ? 60 : edge.getAffinity());
            edge.setTrust(edge.getTrust() == null ? 40 : edge.getTrust());
        } else {
            edge.setRelationType("ROMANTIC");
        }
        edge.setDescription(metaOf(ROMANCE_STATE_COURTING, gameSecond, gameSecond));
        edge.setRelationState("intimate");
        relationRepository.save(edge);
        // 双向 → 恋爱成立：事件 + 双方记忆
        if (hasRomanceEdge(projectId, partner.npcRef(), npc.npcRef())) {
            agentMemoryService.recordObservation(projectId, npc.npcRef(),
                    "与" + partner.getName() + "两情相悦，开始交往", 4);
            agentMemoryService.recordObservation(projectId, partner.npcRef(),
                    "与" + npc.getName() + "两情相悦，开始交往", 4);
            publishEvent(projectId, "romance", "两情相悦",
                    npc.getName() + "与" + partner.getName() + "互生情愫，结为恋人。", 3,
                    List.of(npc.npcRef(), partner.npcRef()), "growth-romance:" + npc.getId() + ":" + partner.getId(),
                    gameSecond);
            log.info("[婚育] 恋爱成立：{} ↔ {}", npc.getName(), partner.getName());
        } else {
            log.info("[婚育] 心动（单向）：{} → {}", npc.getName(), partner.getName());
        }
    }

    //
    // ② 求婚检查（恋爱满 30 游戏日，每 15 游戏日 AI 判定；成功 → 婚礼）
    //

    /** 求婚检查：双向 ROMANTIC 且恋爱满 30 游戏日 → AI 判定求婚。 */
    void checkProposals(Long projectId, Long gameSecond) {
        int romanceMin = growthProperties.getReproduction().getRomanceDurationGameDays() * 86400;
        int proposalCycle = growthProperties.getReproduction().getProposalCheckGameDays() * 86400;
        for (ActorCharacter npc : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (!romanceEligible(npc)) {
                continue;
            }
            List<ActorCharacterRelation> myRomances = romanceEdgesOf(projectId, npc.npcRef());
            if (myRomances.isEmpty()) {
                continue;
            }
            // 候选 = 双向心动的对象
            List<String> candidates = new ArrayList<>();
            List<String> candidateNames = new ArrayList<>();
            for (ActorCharacterRelation rel : myRomances) {
                String partnerRef = rel.getToRef();
                if (partnerRef == null || !hasRomanceEdge(projectId, partnerRef, npc.npcRef())) {
                    continue;
                }
                Long stateSecond = stateSecondOf(rel.getDescription());
                if (stateSecond == null || gameSecond - stateSecond < romanceMin) {
                    continue;
                }
                Long lastCheck = lastCheckOf(rel.getDescription());
                if (lastCheck != null && gameSecond - lastCheck < proposalCycle) {
                    continue;
                }
                candidates.add(partnerRef);
                candidateNames.add(nameOf(projectId, partnerRef));
            }
            if (candidates.isEmpty()) {
                continue;
            }
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                summary.append(candidates.get(i)).append("（").append(candidateNames.get(i)).append("）");
                if (i < candidates.size() - 1) {
                    summary.append("、");
                }
            }
            RomanceDecisions.ProposalContext ctx = new RomanceDecisions.ProposalContext(
                    projectId, npc.npcRef(), npc.getName(), candidates, summary.toString(),
                    romanceDaysOf(projectId, npc, candidates, gameSecond), gameSecond);
            RomanceDecisions.ProposalDecision decision;
            try {
                decision = proposalDecisionSource.decide(ctx);
            } catch (Exception e) {
                log.warn("[婚育] 求婚判定异常（跳过）: {} : {}", npc.getName(), e.getMessage());
                continue;
            }
            // 记录检查时刻（无论结果；防每 15 日重复判定）
            markCheck(projectId, npc.npcRef(), candidates, gameSecond);
            if (decision != null && decision.propose() && decision.targetRef() != null
                    && candidates.contains(decision.targetRef())) {
                ActorCharacter partner = characterRepository.findById(NpcRefs.idOf(decision.targetRef())).orElse(null);
                if (partner != null) {
                    performWedding(projectId, npc, partner, gameSecond);
                }
            }
        }
    }

    /**
     * 婚礼流程（公开：日检求婚成功与调试接口共用）：谱系婚姻边 + 关系升格 + 里程碑 + 世界事件 +
     * AI 叙事 + 双方/亲属记忆。幂等（婚姻边已存在则跳过重复执行）。
     *
     * @param projectId 项目 ID
     *
     * @param a 当事人 A
     *
     * @param b 当事人 B
     *
     * @param gameSecond 婚礼时刻
     */
    public void performWedding(Long projectId, ActorCharacter a, ActorCharacter b, Long gameSecond) {
        // 配偶排序（男左女右惯例；lineage.marry(夫, 妻)）
        String husbandRef = "男".equals(a.getGender()) ? a.npcRef() : b.npcRef();
        String wifeRef = "男".equals(a.getGender()) ? b.npcRef() : a.npcRef();
        ActorGrowthLineage edge = lineageService.marry(projectId, husbandRef, wifeRef, gameSecond);
        if (edge == null) {
            log.info("[婚育] 婚姻边已存在，跳过婚礼重复执行：{} ↔ {}", a.getName(), b.getName());
            return;
        }
        ActorCharacter husband = "男".equals(a.getGender()) ? a : b;
        ActorCharacter wife = "男".equals(a.getGender()) ? b : a;
        // 关系边升格 MARRIAGE（双向）
        upgradeToMarriage(projectId, a, b, gameSecond);
        // 里程碑（双方各记一条）
        GrowthMilestone m1 = new GrowthMilestone();
        m1.setProjectId(projectId);
        m1.setNpcRef(a.npcRef());
        m1.setGameSecond(gameSecond);
        m1.setKind(GrowthMilestone.KIND_MARKER);
        m1.setTitle("成婚");
        m1.setImportance(4);
        m1.setDetail("程序化记录：" + a.getName() + " 与 " + b.getName() + " 成婚。");
        milestoneRepository.save(m1);
        GrowthMilestone m2 = new GrowthMilestone();
        m2.setProjectId(projectId);
        m2.setNpcRef(b.npcRef());
        m2.setGameSecond(gameSecond);
        m2.setKind(GrowthMilestone.KIND_MARKER);
        m2.setTitle("成婚");
        m2.setImportance(4);
        m2.setDetail("程序化记录：" + b.getName() + " 与 " + a.getName() + " 成婚。");
        milestoneRepository.save(m2);
        // 世界事件（importance=4，双当事人）
        publishEvent(projectId, "marriage", "成婚",
                husband.getName() + " 与 " + wife.getName() + " 成婚，亲友邻里道贺。", 4,
                List.of(a.npcRef(), b.npcRef()), "growth-marriage:" + husband.getId() + ":" + wife.getId(),
                gameSecond);
        // 双方记忆（importance=4/5）
        agentMemoryService.recordObservation(projectId, a.npcRef(), "与" + b.getName() + "成婚，结为夫妻", 5);
        agentMemoryService.recordObservation(projectId, b.npcRef(), "与" + a.getName() + "成婚，结为夫妻", 5);
        // AI 婚礼叙事（异步执行体：不占婚礼主线程——AI 延迟不阻塞端点/日检，失败零阻断，
        // 里程碑保留程序化详情；与架构不变量「AI 调用不进同步链路」一致）
        final GrowthMilestone m1Ref = m1;
        final GrowthMilestone m2Ref = m2;
        final ActorCharacter husbandCh = husband;
        final ActorCharacter wifeCh = wife;
        narrativeExecutor.submit(() -> {
            try {
                final String narrative = weddingNarrative(projectId, husbandCh, wifeCh);
                if (narrative != null && !narrative.isBlank()) {
                    m1Ref.setDetail(narrative);
                    m2Ref.setDetail("程序化记录：" + wifeCh.getName() + " 与 " + husbandCh.getName() + " 成婚。\n" + narrative);
                    milestoneRepository.save(m1Ref);
                    milestoneRepository.save(m2Ref);
                }
            } catch (Exception e) {
                log.warn("[婚育] 婚礼叙事异步写入失败（里程碑保留程序化详情）: {}", e.getMessage());
            }
        });
        // 亲属知晓记忆
        notifyKin(projectId, a, b, gameSecond);
        log.info("[婚育] 婚礼完成：{} ↔ {}（{} 游戏秒）", husband.getName(), wife.getName(), gameSecond);
    }

    /**
     * 离婚（治理批次 0 · 卡5-方案 A ②：离婚入口——关系 CRUD/状态机此前均无离婚操作）。
     * <p>离异语义：① 谱系婚姻边 active=0（{@link LineageService#terminateMarriage}，历史保留）；
     * ② 关系表 MARRIAGE 双向行终止；③ 双方记忆 + 世界事件。完成后离异者可再发起恋爱
     * （romanceEligible 依赖配偶查询为空——含 spouseOf 读侧 alive 过滤双保险）。失败隔离零阻断。</p>
     *
     * @param projectId 项目 ID
     *
     * @param aRef 当事人 A npcRef
     *
     * @param bRef 当事人 B npcRef
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     */
    public void divorce(Long projectId, String aRef, String bRef, Long gameSecond) {
        if (projectId == null || aRef == null || bRef == null) {
            return;
        }
        try {
            lineageService.terminateMarriage(projectId, aRef, "divorce");
        } catch (Exception e) {
            log.warn("[婚育] 离婚-婚姻边终止失败（跳过）: {} : {}", aRef, e.getMessage());
        }
        try {
            lineageService.terminateMarriage(projectId, bRef, "divorce");
        } catch (Exception e) {
            log.warn("[婚育] 离婚-婚姻边终止失败（跳过）: {} : {}", bRef, e.getMessage());
        }
        try {
            List<ActorCharacterRelation> toDelete = relationRepository.findByProjectId(projectId).stream()
                    .filter(r -> r != null && "MARRIAGE".equals(r.getRelationType())
                            && ((aRef.equals(r.getFromRef()) && bRef.equals(r.getToRef()))
                            || (bRef.equals(r.getFromRef()) && aRef.equals(r.getToRef()))))
                    .toList();
            if (!toDelete.isEmpty()) {
                relationRepository.deleteAll(toDelete);
            }
        } catch (Exception e) {
            log.warn("[婚育] 离婚-婚配关系行终止失败（跳过）: {} ↔ {} : {}", aRef, bRef, e.getMessage());
        }
        try {
            agentMemoryService.recordObservation(projectId, aRef, "与" + bRef + "离异，婚姻关系结束", 4);
            agentMemoryService.recordObservation(projectId, bRef, "与" + aRef + "离异，婚姻关系结束", 4);
        } catch (Exception e) {
            log.warn("[婚育] 离婚-记忆写入失败（跳过）: {} ↔ {}", aRef, bRef);
        }
        publishEvent(projectId, "divorce", "离异",
                "一对夫妻离异，婚姻关系结束。", 3, List.of(aRef, bRef),
                "growth-divorce:" + aRef + ":" + bRef, gameSecond);
        log.info("[婚育] 离婚完成：{} ↔ {}", aRef, bRef);
    }

    /** AI 婚礼叙事（PLAIN_TEXT，异步执行体；失败返回 null）。 */
    private String weddingNarrative(Long projectId, ActorCharacter husband, ActorCharacter wife) {
        try {
            return aiSupport.call(projectId, husband.getId(), SCENE_WEDDING_NARRATIVE, AiDecisionSourceSupport.ph(
                    "husband", husband.getName(),
                    "wife", wife.getName(),
                    "world_name", worldNameOf(projectId)));
        } catch (Exception e) {
            log.warn("[婚育] 婚礼叙事失败（里程碑保留程序化详情）: {}", e.getMessage());
            return null;
        }
    }

    /** 世界（项目）名称（通用世界观：取项目名；缺省中性兜底，不预设任何聚落形态）。 */
    private String worldNameOf(Long projectId) {
        try {
            return projectRepository.findById(projectId)
                    .map(com.holzyn.actor.domain.project.entity.ActorProject::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse("这个世界");
        } catch (Exception e) {
            return "这个世界";
        }
    }

    /** 亲属知晓（双方父母/子女/兄弟姐妹获得记忆，importance=3）。 */
    private void notifyKin(Long projectId, ActorCharacter a, ActorCharacter b, Long gameSecond) {
        List<String> kin = new ArrayList<>();
        kin.addAll(lineageService.childrenOf(projectId, a.npcRef()));
        kin.addAll(lineageService.childrenOf(projectId, b.npcRef()));
        for (ActorGrowthLineage pe : lineageService.parentEdgesOf(projectId, a.npcRef())) {
            kin.add(pe.getFromRef());
        }
        for (ActorGrowthLineage pe : lineageService.parentEdgesOf(projectId, b.npcRef())) {
            kin.add(pe.getFromRef());
        }
        for (String ref : kin) {
            if (ref == null || ref.equals(a.npcRef()) || ref.equals(b.npcRef())) {
                continue;
            }
            try {
                agentMemoryService.recordObservation(projectId, ref,
                        "家中的" + a.getName() + "与" + b.getName() + "成婚了", 3);
            } catch (Exception e) {
                log.warn("[婚育] 亲属记忆写入失败（隔离继续）: {}", e.getMessage());
            }
        }
    }

    //
    // ③ 生育意愿检查（婚后每 30 游戏日，AI 判定；想生 → 受孕）
    //

    /** 生育意愿检查：对每对存续婚姻夫妇，门控通过 → AI 判定 → 受孕。 */
    void checkFertility(Long projectId, Long gameSecond) {
        int fertilityCycle = growthProperties.getReproduction().getFertilityCheckGameDays() * 86400;
        long cooldownSeconds = (long) growthProperties.getReproduction().getCooldownGameDays() * 86400L;
        long population = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                .filter(c -> !"DECEASED".equalsIgnoreCase(c.getStage())).count();
        for (ActorCharacter wife : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            Optional<String> husbandRef = lineageService.spouseOf(projectId, wife.npcRef());
            if (husbandRef.isEmpty()) {
                continue;
            }
            ActorCharacter husband = characterRepository.findById(NpcRefs.idOf(husbandRef.get())).orElse(null);
            if (husband == null || "DECEASED".equalsIgnoreCase(husband.getStage())) {
                continue;
            }
            if (!fertile(wife) || !fertile(husband)) {
                continue;
            }
            ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, wife.npcRef()).orElse(null);
            if (state == null || state.getPregnancyFromRef() != null) {
                continue;
            }
            // 冷却：最近分娩 180 游戏日内不启动新孕期
            if (state.getLastBirthGameSecond() != null
                    && gameSecond - state.getLastBirthGameSecond() < cooldownSeconds) {
                continue;
            }
            // 人口上限
            if (population >= growthProperties.getReproduction().getPopulationCap()) {
                continue;
            }
            // 检查周期：30 游戏日（内存基准；重启后可能提前一次判定，可接受——判定幂等）
            Long lastCheck = lastFertilityCheck(projectId, wife.npcRef());
            if (lastCheck != null && gameSecond - lastCheck < fertilityCycle) {
                continue;
            }
            markFertilityCheck(projectId, wife.npcRef(), gameSecond);
            RomanceDecisions.FertilityContext ctx = new RomanceDecisions.FertilityContext(
                    projectId, wife.npcRef(), wife.getName(), husband.npcRef(), husband.getName(),
                    (gameSecond - marriageSecondOf(projectId, wife.npcRef(), husband.npcRef())) / 86400L,
                    lineageService.childrenOf(projectId, wife.npcRef()).size(),
                    wife.getAge() + " 岁 / " + husband.getAge() + " 岁", (int) population, gameSecond);
            RomanceDecisions.FertilityDecision decision;
            try {
                decision = fertilityDecisionSource.decide(ctx);
            } catch (Exception e) {
                log.warn("[婚育] 生育意愿判定异常（跳过）: {} : {}", wife.getName(), e.getMessage());
                continue;
            }
            if (decision != null && decision.want()) {
                boolean started = pregnancyService.startPregnancy(projectId, wife.npcRef(), husband.npcRef(),
                        gameSecond);
                if (started) {
                    agentMemoryService.recordObservation(projectId, wife.npcRef(),
                            "怀上了" + husband.getName() + "的孩子，满心期待", 4);
                    agentMemoryService.recordObservation(projectId, husband.npcRef(),
                            wife.getName() + "有了身孕，自己要当爹了", 4);
                    publishEvent(projectId, "pregnancy", "有喜",
                            wife.getName() + "有了身孕（" + husband.getName() + "家）。", 3,
                            List.of(wife.npcRef(), husband.npcRef()),
                            "growth-pregnancy:" + wife.getId() + ":" + gameSecond / 86400L, gameSecond);
                }
            }
        }
    }

    //
    // 工具（关系/元数据/门控）
    //

    private boolean romanceEligible(ActorCharacter ch) {
        if (ch == null || "DECEASED".equalsIgnoreCase(ch.getStage())
                || (ch.getDeleted() != null && ch.getDeleted() != 0)) {
            return false;
        }
        int age = ch.getAge() == null ? 0 : ch.getAge();
        return age >= growthProperties.getReproduction().getMarriageMinAge()
                && age <= growthProperties.getReproduction().getMarriageMaxAge()
                && !lineageService.isMarried(ch.getProjectId(), ch.npcRef());
    }

    private boolean fertile(ActorCharacter ch) {
        if (ch == null || "DECEASED".equalsIgnoreCase(ch.getStage())) {
            return false;
        }
        String stage = ch.getStage();
        return GrowthStage.YOUTH.name().equals(stage) || GrowthStage.PRIME.name().equals(stage)
                || GrowthStage.MIDDLE.name().equals(stage);
    }

    private boolean isOppositeGender(ActorCharacter a, ActorCharacter b) {
        if (a.getGender() == null || b.getGender() == null) {
            return true; // 性别未知不拦（避免程序化误判），AI 判定兜底
        }
        return !a.getGender().equals(b.getGender());
    }

    /** 本角色参与的全部关系（from 或 to）。 */
    private List<ActorCharacterRelation> relationsOf(Long projectId, Long profileId) {
        return relationRepository.findByProjectId(projectId).stream()
                .filter(r -> r != null && (profileId.equals(r.getFromCharacterId())
                        || profileId.equals(r.getToCharacterId())))
                .toList();
    }

    /** 本角色作为 from 的 ROMANTIC 边。 */
    private List<ActorCharacterRelation> romanceEdgesOf(Long projectId, String npcRef) {
        return relationRepository.findByProjectId(projectId).stream()
                .filter(r -> r != null && "ROMANTIC".equals(r.getRelationType())
                        && npcRef.equals(r.getFromRef()))
                .toList();
    }

    /** 是否存在 from→to 的 ROMANTIC 边。 */
    private boolean hasRomanceEdge(Long projectId, String fromRef, String toRef) {
        return relationRepository.findByProjectId(projectId).stream().anyMatch(r -> r != null
                && "ROMANTIC".equals(r.getRelationType()) && fromRef.equals(r.getFromRef())
                && toRef.equals(r.getToRef()));
    }

    private ActorCharacterRelation findRelation(Long projectId, Long fromId, Long toId) {
        return relationRepository.findByProjectId(projectId).stream()
                .filter(r -> r != null && fromId.equals(r.getFromCharacterId()) && toId.equals(r.getToCharacterId()))
                .findFirst().orElse(null);
    }

    /** 恋爱状态机元数据 JSON（description 承载：state + 起点时刻 + 上次判定时刻）。 */
    private String metaOf(String state, long stateGameSecond, Long lastCheck) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("romance_state", state);
        m.put("state_game_second", stateGameSecond);
        if (lastCheck != null) {
            m.put("last_check_game_second", lastCheck);
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private Long lastCheckOf(String description) {
        return readMeta(description, "last_check_game_second");
    }

    private Long stateSecondOf(String description) {
        return readMeta(description, "state_game_second");
    }

    private Long readMeta(String description, String key) {
        if (description == null || description.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(description);
            return node.has(key) && node.get(key).isNumber() ? node.get(key).asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String nameOf(Long projectId, String ref) {
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(ref)).orElse(null);
        return ch == null ? ref : ch.getName();
    }

    private String interactionEvidenceOf(ActorCharacterRelation rel) {
        StringBuilder sb = new StringBuilder();
        sb.append("好感 ").append(rel.getAffinity() == null ? 0 : rel.getAffinity());
        if (rel.getTrust() != null) {
            sb.append("，信任 ").append(rel.getTrust());
        }
        if (rel.getDescription() != null && !rel.getDescription().isBlank()) {
            sb.append("；关系备注：").append(rel.getDescription());
        }
        return sb.toString();
    }

    /** 恋爱时长（游戏日；取最早 ROMANTIC 边的起点）。 */
    private long romanceDaysOf(Long projectId, ActorCharacter npc, List<String> candidates, Long gameSecond) {
        long earliest = gameSecond;
        for (ActorCharacterRelation rel : romanceEdgesOf(projectId, npc.npcRef())) {
            if (candidates.contains(rel.getToRef())) {
                Long s = stateSecondOf(rel.getDescription());
                if (s != null && s < earliest) {
                    earliest = s;
                }
            }
        }
        return (gameSecond - earliest) / 86400L;
    }

    /** 记录求婚检查时刻（双方边的 description 更新 last_check）。 */
    private void markCheck(Long projectId, String npcRef, List<String> candidates, Long gameSecond) {
        for (ActorCharacterRelation rel : relationRepository.findByProjectId(projectId)) {
            if (rel == null || !npcRef.equals(rel.getFromRef()) || !candidates.contains(rel.getToRef())) {
                continue;
            }
            Long stateSecond = stateSecondOf(rel.getDescription());
            rel.setDescription(metaOf(ROMANCE_STATE_COURTING,
                    stateSecond == null ? gameSecond : stateSecond, gameSecond));
            relationRepository.save(rel);
        }
    }

    /** 关系边升格 MARRIAGE（双向；保留 ROMANTIC 语义历史）。 */
    private void upgradeToMarriage(Long projectId, ActorCharacter a, ActorCharacter b, Long gameSecond) {
        upsertMarriageEdge(projectId, a, b, gameSecond);
        upsertMarriageEdge(projectId, b, a, gameSecond);
    }

    private void upsertMarriageEdge(Long projectId, ActorCharacter from, ActorCharacter to, Long gameSecond) {
        ActorCharacterRelation edge = findRelation(projectId, from.getId(), to.getId());
        if (edge == null) {
            edge = new ActorCharacterRelation();
            edge.setProjectId(projectId);
            edge.setFromCharacterId(from.getId());
            edge.setToCharacterId(to.getId());
            edge.setFromRef(from.npcRef());
            edge.setToRef(to.npcRef());
            edge.setFromName(from.getName());
            edge.setToName(to.getName());
            edge.setAffinity(80);
            edge.setTrust(80);
        }
        edge.setRelationType("MARRIAGE");
        edge.setDescription(metaOf(ROMANCE_STATE_MARRIED, gameSecond, gameSecond));
        edge.setRelationState("intimate");
        relationRepository.save(edge);
    }

    /** 婚姻时刻（谱系婚姻边的 game_second；缺省当前时刻）。 */
    private long marriageSecondOf(Long projectId, String wifeRef, String husbandRef) {
        for (ActorGrowthLineage e : lineageRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (ActorGrowthLineage.EDGE_MARRIAGE.equals(e.getEdgeType())
                    && wifeRef.equals(e.getToRef()) && husbandRef.equals(e.getFromRef())
                    && e.getGameSecond() != null) {
                return e.getGameSecond();
            }
        }
        return 0;
    }

    /** 生育检查基准（内存 Map；重启丢基准=可能提前一次判定，判定幂等可接受）。 */
    private final Map<String, Long> lastFertilityCheckMap = new java.util.concurrent.ConcurrentHashMap<>();

    private Long lastFertilityCheck(Long projectId, String npcRef) {
        return lastFertilityCheckMap.get(projectId + ":" + npcRef);
    }

    private void markFertilityCheck(Long projectId, String npcRef, Long gameSecond) {
        lastFertilityCheckMap.put(projectId + ":" + npcRef, gameSecond);
    }

    /** 世界事件发布（婚育专用；失败隔离）。 */
    private void publishEvent(Long projectId, String kind, String title, String content, int importance,
                              List<String> refs, String sourceKey, Long gameSecond) {
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind(kind)
                            .source("growth")
                            .characterId(refs.isEmpty() ? null : NpcRefs.idOf(refs.get(0)))
                            .characterRefs(refs)
                            .visibility("global")
                            .title(title)
                            .content(content)
                            .importance(importance)
                            .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                            .sourceKey(sourceKey)
                            .build());
        } catch (Exception e) {
            log.warn("[婚育] 事件发布失败（隔离继续）: {} : {}", sourceKey, e.getMessage());
        }
    }
}
