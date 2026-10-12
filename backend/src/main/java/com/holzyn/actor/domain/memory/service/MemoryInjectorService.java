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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.holzyn.actor.domain.memory.service.MemoryInjectionRouter.TIER_HIGH;
import static com.holzyn.actor.domain.memory.service.MemoryInjectionRouter.TIER_LOW;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆注入器 ——分场景注入统一入口。
 * <p>职责：落地设计 §9「检索与注入策略（分场景）」+ §6.3「分层权重」+ §9.5「上下文预算（token 经济学）」——</p>
 * <ul>
 * <li><b>分场景注入组合（§9.1~9.4）</b>：DIALOG 对话（L3 核心 + L1 热缓冲 + L2 top-K + 世界大事记 +
 * 过往摘要）、DECISION 决策（L3 核心 + L2 top-K 含反思洞察 + 计划）、PERCEPTION 感知（L3 核心 +
 * L1 快照 + L2 top-K）、REFLECTION 反思（L2 高重要 episodic+semantic + staleness 优先）；</li>
 * <li><b>统一评分制（§6.3 分层权重）</b>：L2 top-K 全部改用 Smallville 评分 + 按类型差异化权重
 * （episodic 2/3.5/1.5 · semantic 2/2/3 · reflection 3/2/1，可配置），替代旧的「重要度 desc + 较新优先」
 * rankForInjection（保留为降级兜底）；plan 类不参与评分（决策按最近计划直取）；</li>
 * <li><b>token 预算（§9.5）</b>：L3≤800 / L1≤400 / L2≤1200（可配置），用 {@link TokenEstimator}
 * 估算各段，超预算从低分丢起（注入预览 API 可观测各段预算消耗）；</li>
 * <li><b>结构化结果</b>：{@link InjectionResult} 含各段文本 + 命中记忆（得分/token）+ 预算统计，
 * 既供实际注入渲染，也供调试/主前端「注入预览」API 展示。</li>
 * </ul>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§6.3/§9/§9.5； 前注入分散在
 * AgentMemoryService.memoryContext/ByRef 与 PerceptionService.buildPerceptionText—— 收拢统一，
 * 但<b>对外方法签名保持不变</b>（存量调用方零改动，行为增强）。</p>
 */
@Slf4j
@Service
public class MemoryInjectorService {

    // ==================== 场景常量 ====================

    /** 对话场景（RoleplayService / ConversationContextService） */
    public static final String SCENE_DIALOG = "dialog";
    /** 决策场景（DecisionService / AgentRuntime） */
    public static final String SCENE_DECISION = "decision";
    /** 感知场景（PerceptionService） */
    public static final String SCENE_PERCEPTION = "perception";
    /** 反思场景（ReflectionService） */
    public static final String SCENE_REFLECTION = "reflection";

    // ==================== 配置（application.yml holzyn.actor.memory.inject.*） ====================

    /** 注入总开关 */
    @Value("${holzyn.actor.memory.inject.enabled:true}")
    private boolean enabled;

    /** 预算化注入路由开关（默认 true；false=build 走现状静态组合，完全回滚） */
    @Value("${holzyn.actor.memory.router.enabled:true}")
    private boolean routerEnabled;

    /** L3 核心段 token 预算（默认 800，对齐设计 §9.5） */
    @Value("${holzyn.actor.memory.inject.budget-core-tokens:800}")
    private int budgetCoreTokens;

    /** L1 热缓冲段 token 预算（默认 400，对齐设计 §9.5） */
    @Value("${holzyn.actor.memory.inject.budget-working-tokens:400}")
    private int budgetWorkingTokens;

    /** L2 长期段 token 预算（默认 1200，对齐设计 §9.5） */
    @Value("${holzyn.actor.memory.inject.budget-l2-tokens:1200}")
    private int budgetL2Tokens;

    /** L2 角色级注入条数上限（默认 8，超预算前先按条数截断） */
    @Value("${holzyn.actor.memory.inject.l2-top-k:8}")
    private int l2TopK;

    /** L2 项目级（世界大事记）注入条数上限（默认 4） */
    @Value("${holzyn.actor.memory.inject.project-top-k:4}")
    private int projectTopK;

    /** L1 热缓冲注入条数上限（默认 5，对齐设计 §9.1） */
    @Value("${holzyn.actor.memory.inject.working-top-k:5}")
    private int workingTopK;

    /** recency 指数衰减半程（小时，默认 24，与 AgentMemoryService 同口径） */
    @Value("${holzyn.actor.memory.recency-decay-hours:24}")
    private int recencyDecayHours;

    /** 注入去重阈值（默认 0.6） */
    @Value("${holzyn.actor.memory.dedup-threshold:0.6}")
    private double dedupThreshold;

    /** episodic 分层权重：{重要度, recency, relevance}（默认 2/3.5/1.5，设计 §6.3） */
    @Value("${holzyn.actor.memory.inject.weight-episodic-imp:2}")
    private double weightEpisodicImp;
    @Value("${holzyn.actor.memory.inject.weight-episodic-rec:3.5}")
    private double weightEpisodicRec;
    @Value("${holzyn.actor.memory.inject.weight-episodic-rel:1.5}")
    private double weightEpisodicRel;

    /** semantic 分层权重：{重要度, recency, relevance}（默认 2/2/3，设计 §6.3） */
    @Value("${holzyn.actor.memory.inject.weight-semantic-imp:2}")
    private double weightSemanticImp;
    @Value("${holzyn.actor.memory.inject.weight-semantic-rec:2}")
    private double weightSemanticRec;
    @Value("${holzyn.actor.memory.inject.weight-semantic-rel:3}")
    private double weightSemanticRel;

    /** reflection 分层权重：{重要度, recency, relevance}（默认 3/2/1，设计 §6.3） */
    @Value("${holzyn.actor.memory.inject.weight-reflection-imp:3}")
    private double weightReflectionImp;
    @Value("${holzyn.actor.memory.inject.weight-reflection-rec:2}")
    private double weightReflectionRec;
    @Value("${holzyn.actor.memory.inject.weight-reflection-rel:1}")
    private double weightReflectionRel;

    // ==================== 依赖（字段注入保持构造器可测：仓库 + 时钟；L3/L1 服务可选） ====================

    private final ActorMemoryRepository memoryRepository;
    private final WorldClockService worldClockService;

    /** L3 核心记忆服务 */
    @Autowired(required = false)
    private CoreMemoryService coreService;

    /** L1 工作记忆服务 */
    @Autowired(required = false)
    private MemoryWorkingService workingService;

    /**
     * 项目记忆服务（记忆系统重构 /：注入【世界近闻】编年史段 +【世界动态】实体状态段；
     * null=未装配跳过，行为回到 前）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldMemoryService worldMemoryService;

    /** 项目记忆段 token 预算（【世界近闻】【世界动态】合计，默认 300，超限截断） */
    @Value("${holzyn.actor.memory.inject.budget-world-tokens:300}")
    private int budgetWorldTokens;

    /** 预算化注入路由（字段注入保持构造器不变；null=未装配走现状静态组合） */
    @Autowired(required = false)
    private MemoryInjectionRouter router;

    /** L1 模糊引擎（L1 short_term 段检索标记用；null=未装配跳过） */
    @Autowired(required = false)
    private L1FuzzificationService fuzzService;

    /** 记忆向量服务（L1 段混合检索 cosine 用；null=纯 Dice） */
    @Autowired(required = false)
    private MemoryVectorService memoryVectorService;

    /** L3 核心记忆仓库 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository coreRepository;

    /**
     * 构造器（仅仓库 + 时钟——L3/L1 服务经字段注入，存量单测零改动）。
     */
    public MemoryInjectorService(ActorMemoryRepository memoryRepository, WorldClockService worldClockService) {
        this.memoryRepository = memoryRepository;
        this.worldClockService = worldClockService;
    }

    // ==================== 分场景注入入口 ====================

    /**
     * 分场景注入组装。返回结构化结果（各段文本 + 命中记忆 + 预算）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{actor_profile.id}}）
     *
     * @param scenario 场景（dialog/decision/perception/reflection）
     *
     * @param query 检索 query（决策/感知可传情境文本；对话可空）
     *
     * @param excludeTexts 注入去重历史窗口文本（可空）
     *
     * @return 注入结果（开关关/参数非法返回空结果）
     */
    public InjectionResult build(Long projectId, String characterRef, String scenario,
                                 String query, List<String> excludeTexts) {
        return build(projectId, characterRef, scenario, query, null, excludeTexts);
    }

    /**
     * 分场景注入组装。
     * <p>router.enabled=true（默认）→ MemoryInjectionRouter 查询感知路由（AI 判定 tier +
     * 规则兜底 + L1 模糊排序/降权/标记 + 优先级截断 + 路由 trace）；关闭 → 现状静态组合（完全回滚）。</p>
     * <p><b>memory_tier 判定输入真实化（2026-10 根治）</b>：query 空 → 用该角色「当前关注点」
     * 情境兜底（{@link MemoryInjectionRouter#recentContextQuery}）；urgency 未显式传（null）→
     * 综合紧迫度（{@link MemoryInjectionRouter#situationalUrgency}，事件重要度/当前行动 urgency/
     * 生理危险区）——修复「所有 memory_tier 调用查询恒（无明确查询）+ 紧迫度恒 1」缺陷。
     * 显式传入的 query/urgency 具有覆盖优先级（调用方可精确控制）。</p>
     */
    public InjectionResult build(Long projectId, String characterRef, String scenario,
                                 String query, Integer urgency, List<String> excludeTexts) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return InjectionResult.empty(scenario);
        }
        if (routerEnabled && router != null) {
            try {
                String ref = characterRef.trim();
                String q = (query == null || query.isBlank()) ? router.recentContextQuery(projectId, ref) : query;
                Integer u = urgency != null ? urgency : router.situationalUrgency(projectId, ref, scenario);
                return buildRouted(projectId, ref, scenario, q, u, excludeTexts);
            } catch (Exception e) {
                log.warn("[记忆注入] 路由组装失败（回退静态组合）: {}", e.getMessage());
            }
        }
        return buildLegacy(projectId, characterRef, scenario, query, excludeTexts);
    }

    /**
     * 现状静态组合。
     */
    private InjectionResult buildLegacy(Long projectId, String characterRef, String scenario,
                                        String query, List<String> excludeTexts) {
        String ref = characterRef.trim();
        String sc = scenario == null || scenario.isBlank() ? SCENE_DIALOG : scenario.trim();
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, LocalDateTime.now());
        Map<String, Segment> segments = new LinkedHashMap<>();

        // L3 核心（恒在，§9.1~9.3 全部场景均注入；对话由记忆段统一注入，感知/决策经感知段注入）
        appendCore(projectId, ref, segments);

        if (SCENE_DIALOG.equals(sc)) {
            // §9.1 对话：L3 核心 + L1 热缓冲 + L2 top-K（评分）+ 世界大事记 + 过往摘要
            // （记忆系统重构 扩展：项目记忆【世界近闻】编年史 +【世界动态】实体状态，与旧大事记并存——
            // 设计 §4.5「旧 mechanism 沉淀保留兼容，新编年史为主」）
            appendWorking(projectId, ref, segments);
            appendL2(projectId, ref, query, excludeTexts, true, nowGameHour, segments);
            appendProject(projectId, segments);
            appendWorldNews(projectId, segments);
            appendWorldDynamics(projectId, ref, segments);
            appendSummary(projectId, ref, segments);
        } else if (SCENE_DECISION.equals(sc)) {
            // §9.2 决策：L3 核心 + L2 top-K（评分，含 reflection 洞察 top-N）
            appendL2(projectId, ref, query, excludeTexts, true, nowGameHour, segments);
        } else if (SCENE_PERCEPTION.equals(sc)) {
            // §9.3 感知：L3 核心 + L1 快照 + L2 相关 top-K
            appendWorking(projectId, ref, segments);
            appendL2(projectId, ref, query, excludeTexts, true, nowGameHour, segments);
        } else if (SCENE_REFLECTION.equals(sc)) {
            // §9.4 反思：L2 高重要 episodic+semantic（staleness 优先复核）+ 反思洞察
            appendReflectionInput(projectId, ref, segments);
        }
        return InjectionResult.from(segments, sc);
    }

    /**
     * 查询感知预算化路由组装（BudgetMem budget-tier 思想，设计 §6）。
     * <p>tier 判定（AI+规则兜底）→ 分档预算 → 优先级截断（L1 清晰 → L1 模糊降权 → L2 → L3
     * 巩固「人格化摘要」；L1 仍有高清晰相关记忆时不得跳 L3）→ fuzz 排序/降权/「（记不太清）」标记
     * → 检索标记（retrieval_count+1）→ 路由 trace。</p>
     */
    private InjectionResult buildRouted(Long projectId, String characterRef, String scenario,
                                        String query, Integer urgency, List<String> excludeTexts) {
        String ref = characterRef.trim();
        String sc = scenario == null || scenario.isBlank() ? SCENE_DIALOG : scenario.trim();
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, LocalDateTime.now());
        // ① tier 判定（AI scene=memory_tier + 规则兜底；失败/programmatic 档规则）
        // 批6 收口：带角色引用调用——memory_tier AI 判定请求前置记忆链段位（段1A+1C+2D+2E）
        String tier = router.determineTier(projectId, ref, sc, query, urgency);
        // 记忆检索注意力加权：层间优先级修正（focus→LOW 升 MID / dormant→HIGH 降 MID，
        // 「高关注回忆预算更慷慨、低关注只记近事」；无信号零影响）
        tier = router.adjustTierByAttention(projectId, ref, tier);
        String reason = aiTierReason(projectId, sc, query, urgency);
        int budget = Integer.MAX_VALUE; // 2026-10-10 决策：不设 token 预算硬边界（条数配额 top-K 仍生效）
        // 档位数量配额：L1 清晰段上限 / L2 top-K 按注意力档位缩放（focus 5/4、ambient 4/3、
        // dormant 2/2；无信号回退既有默认 5/3）
        int attL1Cap = router.attentionTopKByTier(projectId, ref, "l1", 5);
        int attL2TopK = router.attentionTopKByTier(projectId, ref, "l2", 3);
        Map<String, Segment> segments = new LinkedHashMap<>();
        Map<String, MemoryInjectionRouter.LayerStat> layers = new LinkedHashMap<>();
        int used = 0;
        // ② L3 核心（恒在；条数由 renderCoreSegment 的 injectTopK 约束，不设 token 预算）
        appendCore(projectId, ref, segments);
        if (segments.containsKey("core")) {
            used += segments.get("core").tokens();
            layers.put("core", new MemoryInjectionRouter.LayerStat("L3核心", 1, segments.get("core").tokens()));
        }
        // ③ L1 short_term 段（LOW 仅清晰 ≤3；MID 清晰；HIGH 清晰+模糊降权）——条数配额（不设 token 预算）
        List<MemoryInjectionRouter.L1Candidate> l1 = router.scoreL1(projectId, ref, query);
        List<MemoryInjectionRouter.L1Candidate> clear = new ArrayList<>();
        List<MemoryInjectionRouter.L1Candidate> fuzzy = new ArrayList<>();
        for (MemoryInjectionRouter.L1Candidate c : l1) {
            if (c.fuzzy()) {
                fuzzy.add(c);
            } else {
                clear.add(c);
            }
        }
        List<MemoryInjectionRouter.L1Candidate> l1Chosen = new ArrayList<>();
        if (!TIER_LOW.equals(tier)) {
            l1Chosen.addAll(clear);
            if (TIER_HIGH.equals(tier)) {
                l1Chosen.addAll(fuzzy); // 模糊段降权（1−fuzz）
            }
        } else {
            l1Chosen.addAll(clear);
        }
        if (!l1Chosen.isEmpty()) {
            int l1Tokens = 0;
            List<HitVO> l1Hits = new ArrayList<>();
            StringBuilder sb = new StringBuilder("【短期记忆】以下是该角色近期经历（模糊记忆带「记不太清」标记，细节可能失真）：");
            int addedL1 = 0;
            // 档位数量配额：L1 段总条数（清晰+模糊合计）按注意力档位封顶
            // （focus 5/ambient 4/dormant 2；LOW 档再与既有 3 条语义取 min）——统一配额语义，
            // 输入更可预测；模糊段另有 ≤3 次级约束
            int l1TotalCap = TIER_LOW.equals(tier) ? Math.min(3, attL1Cap) : attL1Cap;
            for (MemoryInjectionRouter.L1Candidate c : l1Chosen) {
                if (addedL1 >= l1TotalCap) {
                    break; // 注意力档位总条数配额
                }
                if (c.fuzzy() && addedL1 - countClear(l1Chosen) >= 3) {
                    break; // 模糊段 ≤3 条（次级约束）
                }
                int tokens = TokenEstimator.estimateTokens(c.content());
                // Prompt 精简：去掉「[清晰]/[模糊]」标签——标签无信息量
                // （模糊段已有「（记不太清）」标记），省 5~6 字符/条，格式更统一（稳定前缀友好）
                String mark = c.fuzzy() ? "（记不太清）" : "";
                sb.append("\n- ").append(c.content()).append(mark);
                l1Tokens += tokens;
                l1Hits.add(new HitVO(c.memoryWorkingId(), "l1-short-term", 1,
                        c.content(), round(c.relevance()), c.fuzzy(), tokens));
                // 检索标记（反复被检索=重要 → 蒸馏触发）
                router.markRetrieved(c.memoryWorkingId());
                addedL1++;
            }
            if (addedL1 > 0) {
                segments.put("l1", new Segment("l1", "【短期记忆】L1", sb.toString().trim(),
                        l1Tokens, l1Hits));
                used += l1Tokens;
                layers.put("l1", new MemoryInjectionRouter.LayerStat("L1短期", addedL1, l1Tokens));
            }
        }
        // ④ L2 语义段（MID/HIGH；条数配额 top-K，不设 token 预算）
        if (!TIER_LOW.equals(tier)) {
            // 档位数量配额：HIGH 保底 3 条、其余按注意力档位（focus 4/ambient 3/dormant 2）
            int l2TopK = TIER_HIGH.equals(tier) ? Math.max(attL2TopK, 3) : attL2TopK;
            appendL2(projectId, ref, query, excludeTexts, true, nowGameHour, segments, Integer.MAX_VALUE, l2TopK);
            if (segments.containsKey("l2")) {
                used += segments.get("l2").tokens();
                layers.put("l2", new MemoryInjectionRouter.LayerStat("L2语义", segments.get("l2").hits().size(),
                        segments.get("l2").tokens()));
            }
        }
        // ⑤ L3 巩固记忆「人格化摘要」段（仅 HIGH 档；条数配额，不设 token 预算）
        if (TIER_HIGH.equals(tier) && coreRepository != null) {
            appendL3Consolidated(projectId, ref, Integer.MAX_VALUE, segments);
            if (segments.containsKey("l3c")) {
                used += segments.get("l3c").tokens();
                layers.put("l3c", new MemoryInjectionRouter.LayerStat("L3巩固", segments.get("l3c").hits().size(),
                        segments.get("l3c").tokens()));
            }
        }
        // ⑥ 世界近闻/动态（对话场景保留；条数由 WorldMemoryService 各自注入配额，不设 token 预算）
        if (SCENE_DIALOG.equals(sc)) {
            appendWorldNews(projectId, segments, Integer.MAX_VALUE);
            appendWorldDynamics(projectId, ref, segments, Integer.MAX_VALUE);
            if (segments.containsKey("worldNews") || segments.containsKey("worldDynamics")) {
                int w = (segments.containsKey("worldNews") ? segments.get("worldNews").tokens() : 0)
                        + (segments.containsKey("worldDynamics") ? segments.get("worldDynamics").tokens() : 0);
                layers.put("world", new MemoryInjectionRouter.LayerStat("世界记忆", 1, w));
            }
        }
        // ⑦ 路由 trace（调试前端回放）
        Long gameDay = nowGameHour == null ? null : Math.floorDiv(nowGameHour, 24);
        router.recordTrace(projectId, ref, sc, gameDay, tier, reason, urgency, layers);
        return InjectionResult.from(segments, sc);
    }

    /** 已选 L1 中清晰条数（模糊段条数上限判定用）。 */
    private static int countClear(List<MemoryInjectionRouter.L1Candidate> chosen) {
        int n = 0;
        for (MemoryInjectionRouter.L1Candidate c : chosen) {
            if (!c.fuzzy()) {
                n++;
            }
        }
        return n;
    }

    /** tier 判定 reason（trace 展示用；简化=档位+规则/默认）。 */
    private static String aiTierReason(Long projectId, String scenario, String query, Integer urgency) {
        return "urgency=" + (urgency == null ? "-" : urgency) + " scenario=" + scenario;
    }

    /** L3 巩固记忆「人格化摘要」段。 */
    private void appendL3Consolidated(Long projectId, String ref, int budget, Map<String, Segment> segments) {
        try {
            List<com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory> all = coreRepository
                    .findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(projectId, ref, 0);
            if (all.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            List<HitVO> hits = new ArrayList<>();
            int tokens = 0;
            for (com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory c : all) {
                if (c.getBlockKey() == null || !c.getBlockKey().startsWith("consolidated:")) {
                    continue; // 仅巩固记忆（人格核心不重复注入）
                }
                if (c.getContent() == null || c.getContent().isBlank()) {
                    continue;
                }
                int t = TokenEstimator.estimateTokens(c.getContent());
                // 2026-10-10 决策：不设 token 预算硬边界，全量注入
                if (sb.length() == 0) {
                    sb.append("【长期沉淀】以下是长期沉淀下来的关系与倾向（人格化摘要）：");
                }
                sb.append("\n- ").append(c.getContent());
                tokens += t;
                hits.add(new HitVO(c.getId(), "l3-consolidated",
                        c.getImportance() == null ? 3 : c.getImportance(),
                        c.getContent(), 0, false, t));
            }
            if (sb.length() > 0) {
                segments.put("l3c", new Segment("l3c", "【长期沉淀】L3", sb.toString().trim(), tokens, hits));
            }
        } catch (Exception e) {
            log.warn("[记忆注入] L3 巩固段失败（跳过）: {}", e.getMessage());
        }
    }

    /** 带预算的 L2 段（路由调用；缺省委托既有 appendL2）。 */
    private void appendL2(Long projectId, String ref, String query, List<String> excludeTexts,
                          boolean includeReflection, Long nowGameHour, Map<String, Segment> segments,
                          int budget, int topK) {
        int savedBudget = budgetL2Tokens;
        int savedTopK = l2TopK;
        try {
            budgetL2Tokens = budget;
            l2TopK = topK;
            appendL2(projectId, ref, query, excludeTexts, includeReflection, nowGameHour, segments);
        } finally {
            budgetL2Tokens = savedBudget;
            l2TopK = savedTopK;
        }
    }

    /** 带预算的世界近闻段（路由调用；缺省委托既有 appendWorldNews）。 */
    private void appendWorldNews(Long projectId, Map<String, Segment> segments, int budget) {
        int saved = budgetWorldTokens;
        try {
            budgetWorldTokens = budget;
            appendWorldNews(projectId, segments);
        } finally {
            budgetWorldTokens = saved;
        }
    }

    /** 带预算的世界动态段（路由调用；缺省委托既有 appendWorldDynamics）。 */
    private void appendWorldDynamics(Long projectId, String ref, Map<String, Segment> segments, int budget) {
        int saved = budgetWorldTokens;
        try {
            budgetWorldTokens = budget;
            appendWorldDynamics(projectId, ref, segments);
        } finally {
            budgetWorldTokens = saved;
        }
    }

    /**
     * 渲染为注入文本（各段拼接；无内容返回空串——调用方跳过注入，保持旧行为）。
     */
    public String render(InjectionResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Segment s : result.segments().values()) {
            if (s.text() == null || s.text().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s.text());
        }
        return sb.toString().trim();
    }

    // ==================== 各段组装 ====================

    /**
     * L3 核心段（恒在上下文；委托 CoreMemoryService.renderCoreSegment，token 预算超限截断）。
     */
    private void appendCore(Long projectId, String ref, Map<String, Segment> segments) {
        if (coreService == null) {
            return;
        }
        try {
            String core = coreService.renderCoreSegment(projectId, ref);
            if (core == null || core.isBlank()) {
                return;
            }
            String trimmed = trimToBudget(core, budgetCoreTokens);
            segments.put("core", new Segment("core", "【核心记忆】L3", trimmed, TokenEstimator.estimateTokens(trimmed), List.of()));
        } catch (Exception e) {
            log.warn("[记忆注入] L3 核心段失败（跳过）: {}", e.getMessage());
        }
    }

    /**
     * L1 热缓冲段（【当前情境】；委托 MemoryWorkingService.injectWorkingSegment，token 预算截断）。
     */
    private void appendWorking(Long projectId, String ref, Map<String, Segment> segments) {
        if (workingService == null) {
            return;
        }
        try {
            String working = workingService.injectWorkingSegment(projectId, ref, workingTopK);
            if (working == null || working.isBlank()) {
                return;
            }
            String trimmed = trimToBudget(working, budgetWorkingTokens);
            segments.put("working", new Segment("working", "【当前情境】L1", trimmed, TokenEstimator.estimateTokens(trimmed), List.of()));
        } catch (Exception e) {
            log.warn("[记忆注入] L1 热缓冲段失败（跳过）: {}", e.getMessage());
        }
    }

    /**
     * L2 长期段（统一评分制 + 分层权重 + token 预算 + 去重 + R3 标记）。
     *
     * @param includeReflection 是否纳入反思洞察（决策场景纳，对话/感知不纳）
     */
    private void appendL2(Long projectId, String ref, String query, List<String> excludeTexts,
                          boolean includeReflection, Long nowGameHour, Map<String, Segment> segments) {
        List<ActorMemory> all = loadMemories(projectId, ref);
        if (all.isEmpty()) {
            return;
        }
        List<Scored> scored = new ArrayList<>();
        for (ActorMemory m : all) {
            String type = m.getType();
            boolean eligible = ActorMemoryTypes.TYPE_SEMANTIC.equals(type)
                    || ActorMemoryTypes.TYPE_EPISODIC.equals(type)
                    || (includeReflection && ActorMemoryTypes.TYPE_REFLECTION.equals(type));
            if (!eligible) {
                continue; // plan 不参与评分（决策按最近计划直取，见 appendPlan）
            }
            LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
            double recency = recencyScore(base, LocalDateTime.now(), recencyDecayHours);
            double relevance = (query == null || query.isBlank()) ? 0 : relevanceScore(query, m.getContent());
            double score = weightedScore(type, m.getImportance() == null ? 1 : m.getImportance(), recency, relevance);
            m.setStale(staleFlag(projectId, m, nowGameHour));
            scored.add(new Scored(m, score));
        }
        if (scored.isEmpty()) {
            return;
        }
        // 评分降序 → 去重 → 条数截断（2026-10-10 决策：不设 token 预算硬边界，只按 top-K 条数配额）
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> filtered = new ArrayList<>();
        for (Scored s : scored) {
            if (filterAgainstTexts(s.memory(), excludeTexts, dedupThreshold)) {
                filtered.add(s);
            }
            if (filtered.size() >= l2TopK) {
                break;
            }
        }
        if (filtered.isEmpty()) {
            return;
        }
        // 全量保留（token 预算不再截断；条数已由 l2TopK 控制）
        List<Scored> kept = filtered;
        int totalTokens = 0;
        for (Scored s : kept) {
            totalTokens += TokenEstimator.estimateTokens(s.memory().getContent());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆】以下是该角色过往经历中值得记住的事实，回答时自然引用，不得遗忘或前后矛盾：");
        List<HitVO> hits = new ArrayList<>();
        for (Scored s : kept) {
            ActorMemory m = s.memory();
            String stale = m.isStale() ? "（可能过时，需以最新情况为准）" : "";
            sb.append("\n- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append(stale);
            hits.add(new HitVO(m.getId(), m.getType(), m.getImportance() == null ? 1 : m.getImportance(),
                    m.getContent(), round(s.score()), m.isStale(), TokenEstimator.estimateTokens(m.getContent())));
        }
        segments.put("l2", new Segment("l2", "【长期记忆】L2", sb.toString().trim(), totalTokens, hits));
    }

    /**
     * 项目级（世界大事记）段：项目级 semantic top-K（所有角色可见，重要度 desc + 较新）。
     */
    private void appendProject(Long projectId, Map<String, Segment> segments) {
        List<ActorMemory> proj = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        if (proj.isEmpty()) {
            return;
        }
        List<ActorMemory> ranked = rankForInjection(proj, projectTopK);
        if (ranked.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【世界大事记】以下是本项目近期发生的重大事件，所有角色都知晓：");
        List<HitVO> hits = new ArrayList<>();
        int tokens = 0;
        for (ActorMemory m : ranked) {
            sb.append("\n- [重要度").append(m.getImportance()).append("] ").append(m.getContent());
            tokens += TokenEstimator.estimateTokens(m.getContent());
            hits.add(new HitVO(m.getId(), m.getType(), m.getImportance() == null ? 1 : m.getImportance(),
                    m.getContent(), 0, false, TokenEstimator.estimateTokens(m.getContent())));
        }
        segments.put("project", new Segment("project", "【世界大事记】项目级", sb.toString().trim(), tokens, hits));
    }

    /**
     * 【世界近闻】段：编年史最近 K 条（世界公共事件，所有角色可见；
     * 委托 WorldMemoryService.recentChronicleSegment，token 预算截断）。
     */
    private void appendWorldNews(Long projectId, Map<String, Segment> segments) {
        if (worldMemoryService == null) {
            return;
        }
        try {
            String news = worldMemoryService.recentChronicleSegment(projectId, 0);
            if (news == null || news.isBlank()) {
                return;
            }
            String trimmed = trimToBudget(news, budgetWorldTokens);
            segments.put("worldNews", new Segment("worldNews", "【世界近闻】项目记忆",
                    trimmed, TokenEstimator.estimateTokens(trimmed), List.of()));
        } catch (Exception e) {
            log.warn("[记忆注入] 世界近闻段失败（跳过）: {}", e.getMessage());
        }
    }

    /**
     * 【世界动态】段：相关实体状态变化（nearby 过滤——按注入角色的
     * 所在地/关系网过滤，普通 NPC 仅注入其归属地实体；委托 WorldMemoryService.entityDynamicsSegment）。
     */
    private void appendWorldDynamics(Long projectId, String ref, Map<String, Segment> segments) {
        if (worldMemoryService == null) {
            return;
        }
        try {
            String dynamics = worldMemoryService.entityDynamicsSegment(projectId, ref, 0);
            if (dynamics == null || dynamics.isBlank()) {
                return;
            }
            String trimmed = trimToBudget(dynamics, budgetWorldTokens);
            segments.put("worldDynamics", new Segment("worldDynamics", "【世界动态】实体状态",
                    trimmed, TokenEstimator.estimateTokens(trimmed), List.of()));
        } catch (Exception e) {
            log.warn("[记忆注入] 世界动态段失败（跳过）: {}", e.getMessage());
        }
    }

    /**
     * 过往对话摘要段（S2 窗口压缩承接）：取该角色最近一条会话摘要。
     */
    private void appendSummary(Long projectId, String ref, Map<String, Segment> segments) {        try {
            List<ActorMemory> summaries = memoryRepository
                    // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
                    .findSummariesByProjectIdAndCharacterId(projectId, NpcRefs.idOf(ref));
            if (summaries.isEmpty()) {
                return;
            }
            String content = summaries.get(0).getContent();
            if (content == null || content.isBlank()) {
                return;
            }
            String text = "【过往对话摘要】" + content;
            segments.put("summary", new Segment("summary", "【过往对话摘要】L2", text,
                    TokenEstimator.estimateTokens(text), List.of()));
        } catch (Exception e) {
            log.warn("[记忆注入] 过往摘要段失败（跳过）: {}", e.getMessage());
        }
    }

    /**
     * 反思输入段（§9.4）：L2 高重要 episodic+semantic top-N（staleness 优先复核）+ 反思洞察。
     * <p>反思输入不按严格 token 预算（反思 Prompt 自身有 recent-memories 条数上限），
     * 按「重要度 desc + 较新优先」取（对齐既有 ReflectionService.recentMemories 口径）。</p>
     */
    private void appendReflectionInput(Long projectId, String ref, Map<String, Segment> segments) {
        List<ActorMemory> all = loadMemories(projectId, ref);
        if (all.isEmpty()) {
            return;
        }
        Long nowGameHour = worldClockService == null ? null : worldClockService.gameHourAt(projectId, LocalDateTime.now());
        List<ActorMemory> eligible = new ArrayList<>();
        for (ActorMemory m : all) {
            String type = m.getType();
            if (ActorMemoryTypes.TYPE_SEMANTIC.equals(type) || ActorMemoryTypes.TYPE_EPISODIC.equals(type)
                    || ActorMemoryTypes.TYPE_REFLECTION.equals(type)) {
                m.setStale(staleFlag(projectId, m, nowGameHour));
                eligible.add(m);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }
        // staleness 优先（反思优先复核被标记事实），再按重要度 desc + 较新
        // （单测抓修：① 原误建空 staleFirst 列表仅对 eligible 排序，反思输入恒 0 命中——改为对副本排序；
        // ② 原链式 .reversed() 会反转整个已累积比较器，致重要度实为升序——改为各子比较器局部反转）
        List<ActorMemory> staleFirst = new ArrayList<>(eligible);
        staleFirst.sort(Comparator
                .comparingInt((ActorMemory m) -> Boolean.compare(m.isStale(), false)).reversed()
                .thenComparing(Comparator
                        .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance()).reversed())
                .thenComparing(Comparator
                        .comparing((ActorMemory m) -> m.getCreatedAt(),
                                Comparator.nullsFirst(Comparator.naturalOrder())).reversed()));
        StringBuilder sb = new StringBuilder();
        sb.append("【反思输入】以下是该角色近期值得反思的经历与事实（含被标记「可能过时」项，反思时优先复核）：");
        List<HitVO> hits = new ArrayList<>();
        int tokens = 0;
        int n = 0;
        for (ActorMemory m : staleFirst) {
            if (n >= Math.max(1, l2TopK)) {
                break;
            }
            String stale = m.isStale() ? "（可能过时，需以最新情况为准）" : "";
            sb.append("\n- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append(stale);
            tokens += TokenEstimator.estimateTokens(m.getContent());
            hits.add(new HitVO(m.getId(), m.getType(), m.getImportance() == null ? 1 : m.getImportance(),
                    m.getContent(), 0, m.isStale(), TokenEstimator.estimateTokens(m.getContent())));
            n++;
        }
        segments.put("reflection", new Segment("reflection", "【反思输入】L2", sb.toString().trim(), tokens, hits));
    }

    // ==================== 评分（分层权重，§6.3） ====================

    /**
     * 按类型差异化权重评分（§6.3）：episodic 侧重 recency、semantic 侧重 relevance、reflection 侧重 importance。
     */
    double weightedScore(String type, int importance, double recency, double relevance) {
        if (ActorMemoryTypes.TYPE_EPISODIC.equals(type)) {
            return weightEpisodicImp * importance + weightEpisodicRec * recency + weightEpisodicRel * relevance;
        }
        if (ActorMemoryTypes.TYPE_REFLECTION.equals(type)) {
            return weightReflectionImp * importance + weightReflectionRec * recency + weightReflectionRel * relevance;
        }
        // semantic（默认）
        return weightSemanticImp * importance + weightSemanticRec * recency + weightSemanticRel * relevance;
    }

    /**
     * recency 指数衰减分（0~1，与 AgentMemoryService.recencyScore 同口径）。
     */
    static double recencyScore(LocalDateTime base, LocalDateTime now, int decayHours) {
        if (base == null || now == null) {
            return 0;
        }
        long ageSeconds = Duration.between(base, now).getSeconds();
        if (ageSeconds <= 0) {
            return 1;
        }
        double decaySeconds = Math.max(1, decayHours) * 3600.0;
        return Math.exp(-ageSeconds / decaySeconds);
    }

    /**
     * 文本相关性分（Dice 二元组重叠，无 embedding 降级，与 AgentMemoryService 同口径）。
     */
    static double relevanceScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        return com.holzyn.actor.domain.memory.common.MemoryDedup.overlapScore(query, content);
    }

    /**
     * R3 staleness 判定（与 AgentMemoryService.staleFlag 同口径：重要度≥阈值 + 超游戏日未复核）。
     */
    boolean staleFlag(Long projectId, ActorMemory m, Long nowGameHour) {
        if (projectId == null || m == null || nowGameHour == null || worldClockService == null) {
            return false;
        }
        if (m.getImportance() == null || m.getImportance() < 3) {
            return false;
        }
        String type = m.getType();
        if (!ActorMemoryTypes.TYPE_SEMANTIC.equals(type) && !ActorMemoryTypes.TYPE_EPISODIC.equals(type)) {
            return false;
        }
        LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
        if (base == null) {
            return false;
        }
        Long baseGameHour = worldClockService.gameHourAt(projectId, base);
        if (baseGameHour == null) {
            return false;
        }
        long nowDay = Math.floorDiv(nowGameHour, 24);
        long baseDay = Math.floorDiv(baseGameHour, 24);
        return com.holzyn.actor.domain.agent.memory.AgentMemoryService.staleByGameDays(baseDay, nowDay, 3);
    }

    /**
     * 注入去重：记忆与历史窗口文本重叠度 ≥ 阈值则剔除。
     */
    static boolean filterAgainstTexts(ActorMemory m, List<String> excludeTexts, double threshold) {
        if (m == null || excludeTexts == null || excludeTexts.isEmpty()) {
            return true;
        }
        String content = m.getContent();
        if (content == null) {
            return false;
        }
        for (String t : excludeTexts) {
            if (t != null && !t.isBlank()
                    && com.holzyn.actor.domain.memory.common.MemoryDedup.overlapScore(content, t) >= threshold) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 token 预算截断文本（超预算从尾部截断；预算下限保护防空）。
     * <p>2026-10-10 决策：不设字数硬边界——本方法保持签名与调用点不变，
     * 但恒等返回原文（不再截断），确保 AI 请求输入完整。</p>
     */
    static String trimToBudget(String text, int budget) {
        return text == null ? "" : text;
    }

    // ==================== 私有辅助 ====================

    /**
     * 加载某角色全部记忆（双轨并入仅 character_id 存量）。
     */
    private List<ActorMemory> loadMemories(Long projectId, String characterRef) {
        List<ActorMemory> out = new ArrayList<>(memoryRepository
                .findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(projectId, characterRef, 0));
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long charId = NpcRefs.idOf(characterRef);
        if (charId != null) {
            List<ActorMemory> legacy = memoryRepository
                    .findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                            projectId, charId, List.of(ActorMemoryTypes.TYPE_SEMANTIC, ActorMemoryTypes.TYPE_EPISODIC), 0);
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (ActorMemory m : out) {
                if (m.getId() != null) {
                    seen.add(m.getId());
                }
            }
            for (ActorMemory m : legacy) {
                if (m.getId() == null || !seen.contains(m.getId())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * 按「重要度 desc + 较新优先」排序取 top-K（降级兜底 / 项目级大事记排序）。
     */
    static List<ActorMemory> rankForInjection(List<ActorMemory> all, int topK) {
        if (all == null || all.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<ActorMemory> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance()).reversed()
                .thenComparing(Comparator.comparing((ActorMemory m) -> m.getCreatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()));
        return sorted.size() <= topK ? sorted : new ArrayList<>(sorted.subList(0, topK));
    }

    /** 数值保留 2 位小数（得分展示）。 */
    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    // ==================== 内部记录 ====================

    /** 注入结果：各段 + 场景（空结果=无内容）。 */
    public record InjectionResult(Map<String, Segment> segments, String scenario) {
        /** 空结果（注入器不可用/无内容时使用）。 */
        public static InjectionResult empty(String scenario) {
            return new InjectionResult(Map.of(), scenario);
        }

        static InjectionResult from(Map<String, Segment> segments, String scenario) {
            return new InjectionResult(segments, scenario);
        }

        /** 总 token 估算（各段合计）。 */
        public int totalTokens() {
            int total = 0;
            for (Segment s : segments.values()) {
                total += s.tokens();
            }
            return total;
        }
    }

    /** 注入段（键/标题/文本/token/命中记忆）。 */
    public record Segment(String key, String title, String text, int tokens, List<HitVO> hits) {
    }

    /** 命中记忆 VO（预览展示：id/type/重要度/内容/得分/过时标记/token）。 */
    public record HitVO(Long id, String type, int importance, String content, double score,
                        boolean stale, int tokens) {
    }

    /** 评分记录。 */
    private record Scored(ActorMemory memory, double score) {
    }
}
