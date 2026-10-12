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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.npc.physio.PhysioNeedService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 记忆注入预算路由。
 * <p>职责：查询感知的预算化注入策略引擎（BudgetMem budget-tier 思想）——
 * 按查询意图判定注入档位（LOW/MID/HIGH），决定注入层级与注入量（绝不全部注入）；
 * 由 {@link MemoryInjectorService#build} 集成（router.enabled=false 全回滚到静态组合）。</p>
 * <ul>
 * <li><b>tier 判定</b>：scene=memory_tier，
 * 输入=场景+查询+urgency；失败/programmatic 档回退规则判定——简单事实词（天气/时间/地点/
 * 价格）→LOW；决策类/人物关系词→MID~HIGH；urgency≥4 或场景=reflection→HIGH；</li>
 * <li><b>分档预算</b>：LOW≤300（仅 L1 清晰段 ≤3 条）/ MID≤800（L1 清晰+L2 相关 2~3 条）/
 * HIGH≤2000（L1 清晰+L1 模糊降权+L2+L3 巩固「人格化摘要」，可配置）；</li>
 * <li><b>L1 排序</b>：{@link #scoreL1}——按 fuzz_level 升序（清晰优先）+ 混合相关分
 * （0.6 cosine + 0.4 Dice，向量缺失回退 Dice）降序；模糊记忆注入带「（记不太清）」标记，
 * 权重系数 1−fuzz_level；</li>
 * <li><b>检索标记</b>：注入命中 L1 时 {@code retrieval_count}+1（蒸馏触发依据）；</li>
 * <li><b>trace</b>：每次路由记内存环形缓冲（最近 200 条）——档位/判定 reason/各层条数与 token/
 * 每条 fuzz_level 与 relevance 得分，供调试前端「注入路由」视图与
 * GET /memories/inject-route/trace 回放。</li>
 * </ul>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§6。</p>
 */
@Slf4j
@Service
public class MemoryInjectionRouter {

    /** AI 场景：注入档位判定 */
    public static final String SCENE_MEMORY_TIER = "memory_tier";

    /** 档位 */
    public static final String TIER_LOW = "LOW";
    public static final String TIER_MID = "MID";
    public static final String TIER_HIGH = "HIGH";

    /** 规则判定关键词：简单事实查询 */
    private static final String[] LOW_KEYWORDS = {
            "天气", "时间", "几点", "时辰", "地点", "位置", "在哪", "价格", "多少钱", "多少钱一斤", "有没有",
            "开门", "关门", "营业", "谁在", "今天是", "明天", "昨天", "几月", "几日", "什么日子"
    };
    /** 规则判定关键词：人物关系/高利害 */
    private static final String[] HIGH_KEYWORDS = {
            "信任", "相信", "靠不靠谱", "可不可信", "是否值得", "什么为人", "什么性格", "关系", "恩怨",
            "答应", "承诺", "发誓", "背叛", "重要", "大事", "危急", "生死", "站队", "抉择"
    };

    /** 模糊记忆阈值（fuzz<该值=清晰段；≥=模糊段降权） */
    @Value("${holzyn.actor.memory.router.fuzz-clear-threshold:0.3}")
    private double fuzzClearThreshold;

    /** LOW 档 token 预算（默认 300） */
    @Value("${holzyn.actor.memory.router.budget-low-tokens:300}")
    private int budgetLowTokens;

    /** MID 档 token 预算（默认 800） */
    @Value("${holzyn.actor.memory.router.budget-mid-tokens:800}")
    private int budgetMidTokens;

    /** HIGH 档 token 预算（默认 2000） */
    @Value("${holzyn.actor.memory.router.budget-high-tokens:2000}")
    private int budgetHighTokens;

    /** LOW 档 L1 清晰段条数上限（默认 3） */
    @Value("${holzyn.actor.memory.router.low-l1-top-k:3}")
    private int lowL1TopK;

    /** MID 档 L2 段条数上限（默认 3） */
    @Value("${holzyn.actor.memory.router.mid-l2-top-k:3}")
    private int midL2TopK;

    /** HIGH 档 L2 段条数上限（默认 5） */
    @Value("${holzyn.actor.memory.router.high-l2-top-k:5}")
    private int highL2TopK;

    /** HIGH 档 L1 模糊段条数上限（默认 3） */
    @Value("${holzyn.actor.memory.router.high-fuzzy-l1-top-k:3}")
    private int highFuzzyL1TopK;

    /** 路由 trace 环形缓冲上限（默认 200） */
    @Value("${holzyn.actor.memory.router.trace-max-entries:200}")
    private int traceMaxEntries;

    /** 混合检索向量权重（默认 0.6，与 MemoryVectorService 对齐） */
    @Value("${holzyn.actor.memory.vector.hybrid-vec-weight:0.6}")
    private double hybridVecWeight;

    // ==================== 情境合成（综合紧迫度 + query 兜底） ====================

    /** 最近事件信号扫描条数（紧迫度/情境合成取最近 N 条参与事件） */
    private static final int RECENT_EVENT_SCAN = 3;

    /** 进行中行动状态集合（当前行动 urgency 信号源） */
    private static final List<String> ACTION_IN_FLIGHT = List.of("planned", "executing");

    /** query 情境兜底文本长度上限（字符，超长截断防 token 膨胀） */
    private static final int CONTEXT_QUERY_MAX_LEN = 200;

    /** 事件仓库（最近参与事件重要度信号；null=未装配跳过，fail-open） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private ActorEventRepository eventRepository;

    /** 行动决策仓库（当前行动 urgency 信号；null=未装配跳过） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private ActorActionPlanRepository planRepository;

    /** 角色档案仓库（生理危险区信号需角色实体；null=未装配跳过） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 生理需求服务（危险区 → urgency 5；null=未装配跳过；@Lazy 破 bean 环） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private PhysioNeedService physioNeedService;

    // ==================== 依赖 ====================

    private final ActorMemoryFuzzStateRepository fuzzRepository;
    private final ActorMemoryWorkingRepository workingRepository;
    private final MemoryVectorService memoryVectorService;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /** AI 强度闸门（programmatic 档 tier 判定跳过 AI 走规则） */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 当前用户（AI 凭据归属） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（memory_tier 属批4 记忆链·角色视角）：tier 判定请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）——「该给这个角色注入多少记忆」先知道世界/身份/处境；
     * null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 模糊引擎（检索标记用） */
    @Autowired(required = false)
    private L1FuzzificationService fuzzService;

    /**
     * 认知注意力档位提供器：记忆检索注意力加权（层间优先级修正 + 条数配额）。
     * 字段注入 + @Lazy 破 bean 环；
     * null=未装配 → 全部走既有行为（零注意力影响）。
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.AttentionLevelProvider attentionLevelProvider;

    /**
     * 生命周期配置（记忆注意力加权参数：memoryWeightEnabled / memoryTierAdjustEnabled /
     * memoryL1CapByTier / memoryL2TopKByTier；null=未装配按默认行为）。@Lazy 破环同上。
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.growth.config.GrowthProperties growthProperties;

    /** 路由 trace 环形缓冲（内存态；最近 traceMaxEntries 条） */
    private final ConcurrentLinkedQueue<RouteTrace> traceBuffer = new ConcurrentLinkedQueue<>();

    public MemoryInjectionRouter(ActorMemoryFuzzStateRepository fuzzRepository,
                                 ActorMemoryWorkingRepository workingRepository,
                                 MemoryVectorService memoryVectorService,
                                 AiSpringChatGateway aiChatGateway,
                                 PromptTemplateService promptTemplateService,
                                 UsageLogService usageLogService,
                                 ObjectMapper objectMapper) {
        this.fuzzRepository = fuzzRepository;
        this.workingRepository = workingRepository;
        this.memoryVectorService = memoryVectorService;
        this.aiChatGateway = aiChatGateway;
        this.promptTemplateService = promptTemplateService;
        this.usageLogService = usageLogService;
        this.objectMapper = objectMapper;
    }

    // ==================== tier 判定 ====================

    /**
     * 判定注入档位：AI（scene=memory_tier，输入场景+查询+urgency）→ 失败/programmatic 档回退规则。
     * <p>无角色引用重载（字符引用可空=不注入 段1C/2E，仅场景基数）——委托
     * {@link #determineTier(Long, String, String, String, Integer)}。</p>
     */
    public String determineTier(Long projectId, String scenario, String query, Integer urgency) {
        return determineTier(projectId, null, scenario, query, urgency);
    }

    /**
     * 判定注入档位（带角色引用）：AI（scene=memory_tier，输入场景+查询+urgency）→
     * 失败/programmatic 档回退规则。批 6 收口：有角色引用时 AI 判定请求前置记忆链段位
     * （段1A+1C+2D+2E，{@code memoryChainContext}）。
     */
    public String determineTier(Long projectId, String characterRef, String scenario, String query, Integer urgency) {
        if (aiAllowed(projectId)) {
            String ai = aiTier(projectId, characterRef, scenario, query, urgency);
            if (ai != null) {
                return ai;
            }
        }
        return ruleTier(scenario, query, urgency);
    }

    /** AI 档位判定（失败/非法返回 null → 规则兜底）。 */
    private String aiTier(Long projectId, String characterRef, String scenario, String query, Integer urgency) {
        try {
            Long uid = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            String prompt = promptTemplateService.render(uid, projectId, PromptTemplateService.CODE_MEMORY_TIER,
                    Map.of("scenario", scenario == null ? "dialog" : scenario,
                            "query", query == null || query.isBlank() ? "（无明确查询）" : query,
                            "urgency", urgency == null ? "1" : String.valueOf(urgency)));
            // 批6 收口（memory_tier 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
            // 「该给该角色注入多少记忆」先知道世界/身份/此刻处境；未装配/无角色 → 原样（fail-open）
            final String finalPrompt;
            if (aiContextInjector != null && characterRef != null && !characterRef.isBlank()) {
                String ctx = aiContextInjector.memoryChainContext(projectId, characterRef, null);
                finalPrompt = (ctx == null || ctx.isBlank()) ? prompt : ctx + "\n\n" + prompt;
            } else {
                finalPrompt = prompt;
            }
            String sys = promptTemplateService.systemMessage(uid, projectId, PromptTemplateService.CODE_MEMORY_TIER, null);
            if (sys == null || sys.isBlank()) {
                sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            final String sysFinal = sys;
            AiChatResult result = AiCallContext.run(SCENE_MEMORY_TIER, null, () ->
                    aiChatGateway.chatCompletion(uid, projectId, null,
                            new AiChatRequest(null, List.of(
                                    new AiChatRequest.ChatMessage("system", sysFinal),
                                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                                    0.2, 64, true)));
            usageLogService.record(uid, projectId, null, result.providerId(), result.model(),
                    SCENE_MEMORY_TIER, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String tier = root.path("tier").asText(null);
            if (tier != null) {
                String norm = tier.trim().toUpperCase();
                if (TIER_LOW.equals(norm) || TIER_MID.equals(norm) || TIER_HIGH.equals(norm)) {
                    return norm;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[记忆路由] tier AI 判定失败（回退规则）: {}", e.getMessage());
            return null;
        }
    }

    /** 规则档位判定（确定性可单测）：简单事实词→LOW；人物关系/高利害词→HIGH；反思/urgency≥4→HIGH；否则 MID。 */
    public String ruleTier(String scenario, String query, Integer urgency) {
        if ("reflection".equals(scenario)) {
            return TIER_HIGH;
        }
        if (urgency != null && urgency >= 4) {
            return TIER_HIGH;
        }
        if (query != null && !query.isBlank()) {
            String q = query;
            for (String kw : HIGH_KEYWORDS) {
                if (q.contains(kw)) {
                    return TIER_HIGH;
                }
            }
            for (String kw : LOW_KEYWORDS) {
                if (q.contains(kw)) {
                    return TIER_LOW;
                }
            }
        }
        return TIER_MID;
    }

    /** 档位 → token 预算。 */
    public int budgetOf(String tier) {
        if (TIER_LOW.equals(tier)) {
            return Math.max(64, budgetLowTokens);
        }
        if (TIER_HIGH.equals(tier)) {
            return Math.max(64, budgetHighTokens);
        }
        return Math.max(64, budgetMidTokens);
    }

    // ==================== 情境合成：综合紧迫度 + query 兜底（memory_tier 判定输入真实化） ====================

    /**
     * 综合紧迫度（1~5）：memory_tier 判定输入的 urgency 真实化——
     * 修复「所有 memory_tier 调用紧迫度恒 1」缺陷（2026-10 实测：调用方全部走 5 参 build，
     * urgency 恒 null → 模板恒渲染 1，重大事件/冲突对话也拿不到高紧迫档）。
     * <p>信号合成（max 取最紧迫，钳制 1~5，任一信号异常/未装配跳过 fail-open）：</p>
     * <ol>
     * <li><b>场景基数</b>：reflection=5 / decision=3 / dialog、perception 及未知=2；</li>
     * <li><b>最近参与事件重要度</b>：该角色最近参与事件（character_id ∪ character_ids 命中）
     * 的 importance（1~5，取最近第一条非空）；</li>
     * <li><b>当前行动 urgency</b>：该角色最近一条进行中（planned/executing）行动决策
     * actionJson 的 urgency（1~5）；</li>
     * <li><b>生理危险区</b>：任一需求低于危险阈值（PhysioNeedService 判定）→ 直接 5（健康告急优先）。</li>
     * </ol>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}；可空=仅场景基数）
     *
     * @param scenario 场景（dialog/decision/perception/reflection）
     *
     * @return 综合紧迫度（1~5，恒非空）
     */
    public int situationalUrgency(Long projectId, String characterRef, String scenario) {
        int u = scenarioBaseUrgency(scenario);
        u = Math.max(u, recentEventImportance(projectId, characterRef));
        u = Math.max(u, currentActionUrgency(projectId, characterRef));
        if (physioDanger(projectId, characterRef)) {
            u = 5;
        }
        return Math.max(1, Math.min(5, u));
    }

    /**
     * query 情境兜底：调用方未提供检索 query（典型=纯 NPC↔NPC 自治聊天无玩家消息，
     * 群聊历史只有 assistant/system 角色 → 调用方传 null）时，用该角色「当前关注点」
     * 合成情境文本——最近一条 L1 task 工作项（「当前行动：…」）> 最近参与事件文本；
     * 全空返回 null（上层按「（无明确查询）」兜底）。
     * <p>修复「所有 memory_tier 调用查询恒（无明确查询）」缺陷的注入器层兜底
     * （调用方层兜底见 RoleplayService.lastSubstantiveText/chatBackground）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @return 情境文本（≤{@value #CONTEXT_QUERY_MAX_LEN} 字符；无可合成返回 null）
     */
    public String recentContextQuery(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return null;
        }
        String ref = characterRef.trim();
        // ① 最近一条 L1 task 工作项（当前关注点：行动/目标）
        try {
            List<ActorMemoryWorking> tasks = workingRepository
                    .findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                            projectId, ref, MemoryWorkingService.STATUS_ACTIVE, 0);
            for (ActorMemoryWorking t : tasks) {
                if (MemoryWorkingService.ITEM_TASK.equals(t.getItemType())
                        && t.getContent() != null && !t.getContent().isBlank()) {
                    return truncateQuery(t.getContent());
                }
            }
        } catch (Exception e) {
            log.debug("[记忆路由] L1 情境 query 合成失败（跳过）: ref={} : {}", ref, e.getMessage());
        }
        // ② 最近参与事件文本（兜底：该角色刚经历了什么）
        Long id = NpcRefs.idOf(ref);
        if (id != null && eventRepository != null) {
            try {
                List<ActorEvent> events = eventRepository.findByProjectIdAndCharacterRef(projectId, id, ref);
                for (int i = 0; i < Math.min(RECENT_EVENT_SCAN, events.size()); i++) {
                    String text = eventText(events.get(i));
                    if (text != null && !text.isBlank()) {
                        return truncateQuery(text);
                    }
                }
            } catch (Exception e) {
                log.debug("[记忆路由] 事件情境 query 合成失败（跳过）: ref={} : {}", ref, e.getMessage());
            }
        }
        return null;
    }

    /** 场景基数紧迫度：reflection 恒高；decision 中高；dialog/perception 及未知=2。 */
    private int scenarioBaseUrgency(String scenario) {
        if ("reflection".equals(scenario)) {
            return 5;
        }
        if ("decision".equals(scenario)) {
            return 3;
        }
        return 2;
    }

    /** 信号①：最近参与事件重要度（取最近第一条非空 importance，钳制 1~5；无信号/异常=1）。 */
    private int recentEventImportance(Long projectId, String characterRef) {
        Long id = NpcRefs.idOf(characterRef);
        if (projectId == null || id == null || eventRepository == null) {
            return 1;
        }
        try {
            List<ActorEvent> events = eventRepository.findByProjectIdAndCharacterRef(projectId, id, characterRef.trim());
            for (int i = 0; i < Math.min(RECENT_EVENT_SCAN, events.size()); i++) {
                Integer imp = events.get(i) == null ? null : events.get(i).getImportance();
                if (imp != null) {
                    return Math.max(1, Math.min(5, imp));
                }
            }
            return 1;
        } catch (Exception e) {
            log.debug("[记忆路由] 最近事件重要度信号失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return 1;
        }
    }

    /** 信号②：当前行动 urgency（最近一条进行中计划 actionJson 的 urgency，钳制 1~5；无信号/异常=1）。 */
    private int currentActionUrgency(Long projectId, String characterRef) {
        Long id = NpcRefs.idOf(characterRef);
        if (id == null || planRepository == null) {
            return 1;
        }
        try {
            List<ActorActionPlan> plans = planRepository.findByCharacterIdAndStatusInOrderByIdDesc(id, ACTION_IN_FLIGHT);
            if (plans.isEmpty()) {
                return 1;
            }
            String json = plans.get(0).getActionJson();
            if (json == null || json.isBlank()) {
                return 1;
            }
            int u = objectMapper.readTree(json).path("urgency").asInt(1);
            return Math.max(1, Math.min(5, u));
        } catch (Exception e) {
            log.debug("[记忆路由] 当前行动 urgency 信号失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return 1;
        }
    }

    /** 信号③：生理危险区（任一需求低于危险阈值 → true；无信号/异常=false，fail-open）。 */
    private boolean physioDanger(Long projectId, String characterRef) {
        Long id = NpcRefs.idOf(characterRef);
        if (projectId == null || id == null || physioNeedService == null || characterRepository == null) {
            return false;
        }
        try {
            ActorCharacter ch = characterRepository.findByIdAndProjectId(id, projectId).orElse(null);
            if (ch == null) {
                return false;
            }
            return physioNeedService.dangerDecision(projectId, ch) != null;
        } catch (Exception e) {
            log.debug("[记忆路由] 生理危险区信号失败（跳过）: ref={} : {}", characterRef, e.getMessage());
            return false;
        }
    }

    /** 事件情境文本（标题优先，空标题回退内容）。 */
    private static String eventText(ActorEvent e) {
        if (e == null) {
            return null;
        }
        String t = e.getTitle();
        if (t == null || t.isBlank()) {
            t = e.getContent();
        }
        return t;
    }

    /** 情境文本截断（≤上限字符，防 token 膨胀）。
     * 2026-10-10 决策：不设字数硬边界——恒等返回原文（query 情境全量，AI 判定信息更完整）。 */
    private static String truncateQuery(String s) {
        return s;
    }

    // ==================== 记忆检索注意力加权（层间优先级修正 + 条数配额） ====================

    /**
     * 注意力档位（null-safe）：开关关/未装配/异常返回 null——消费方按「无注意力信号」处理。
     */
    private String attentionTierOf(Long projectId, String characterRef) {
        if (growthProperties == null || !growthProperties.isEnabled()
                || !growthProperties.getAttention().isMemoryWeightEnabled()) {
            return null;
        }
        if (attentionLevelProvider == null || projectId == null || characterRef == null) {
            return null;
        }
        try {
            return attentionLevelProvider.tierOf(projectId, characterRef);
        } catch (Exception e) {
            log.debug("[记忆路由] 注意力档位读取失败（按无信号）: ref={} : {}", characterRef, e.getMessage());
            return null;
        }
    }

    /**
     * 层间优先级修正：注意力档位修正注入档位——
     * focus → LOW 升 MID（L2 语义层进入注入，「高关注时回忆预算更慷慨」）；
     * dormant → HIGH 降 MID（L3 巩固层退出，「低关注只记近事」）。其余原样。
     * ⚠️ 诚实说明：单角色检索内注意力系数是同角色常量，数学上不改变该角色记忆间的
     * 相对顺序；「排序加权」因此落地为<b>层间优先级 + 条数配额</b>（跨层真实差异），
     * 而非角色内重排。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用 npc:{id}
     *
     * @param tier 判定出的注入档位（LOW/MID/HIGH）
     *
     * @return 修正后的档位（无信号/开关关原样返回）
     */
    public String adjustTierByAttention(Long projectId, String characterRef, String tier) {
        if (tier == null || growthProperties == null || !growthProperties.isEnabled()
                || !growthProperties.getAttention().isMemoryWeightEnabled()
                || !growthProperties.getAttention().isMemoryTierAdjustEnabled()) {
            return tier;
        }
        String attTier = attentionTierOf(projectId, characterRef);
        if (attTier == null) {
            return tier;
        }
        if (com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_FOCUS.equals(attTier)
                && TIER_LOW.equals(tier)) {
            return TIER_MID;
        }
        if (com.holzyn.actor.domain.growth.lod.BrainTierService.TIER_DORMANT.equals(attTier)
                && TIER_HIGH.equals(tier)) {
            return TIER_MID;
        }
        return tier;
    }

    /**
     * 条数配额：按注意力档位取层条数上限（focus/ambient/dormant，
     * 无注意力信号/开关关返回 fallback（既有默认）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用 npc:{id}
     *
     * @param layerKey l1=【短期记忆】清晰段上限 / l2=【长期记忆】top-K
     *
     * @param fallback 无信号时的默认值
     */
    public int attentionTopKByTier(Long projectId, String characterRef, String layerKey, int fallback) {
        String attTier = attentionTierOf(projectId, characterRef);
        if (attTier == null || growthProperties == null) {
            return fallback;
        }
        Map<String, Integer> byTier = "l1".equals(layerKey)
                ? growthProperties.getAttention().getMemoryL1CapByTier()
                : growthProperties.getAttention().getMemoryL2TopKByTier();
        if (byTier == null) {
            return fallback;
        }
        Integer v = byTier.get(attTier);
        return v == null || v <= 0 ? fallback : v;
    }

    // ==================== L1 候选评分（fuzz 排序 + 混合相关分） ====================

    /**
     * L1 short_term 候选评分：取该角色全部 active fuzz_state → 混合相关分
     * （0.6 cosine + 0.4 Dice，向量缺失回退 Dice）→ 按「清晰段（fuzz&lt;阈值）优先，清晰内 fuzz 升序、
     * 相关分降序；模糊段（≥阈值）相关分降序」排序。
     *
     * @param query 检索 query（空=纯按清晰度排序）
     *
     * @return 候选（含 fuzz_level/相关分/权重 1−fuzz_level/标记需要）
     */
    public List<L1Candidate> scoreL1(Long projectId, String characterRef, String query) {
        List<L1Candidate> out = new ArrayList<>();
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return out;
        }
        List<ActorMemoryFuzzState> states = fuzzRepository
                .findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(projectId, characterRef.trim(),
                        L1FuzzificationService.FUZZ_STATUS_ACTIVE);
        if (states.isEmpty()) {
            return out;
        }
        for (ActorMemoryFuzzState fs : states) {
            String content = fs.getCurrentContent() == null ? "" : fs.getCurrentContent();
            if (content.isBlank()) {
                continue;
            }
            double fuzz = fs.getFuzzLevel() == null ? 0.0 : fs.getFuzzLevel();
            double relevance = 0.0;
            if (query != null && !query.isBlank()) {
                relevance = MemoryDedup.overlapScore(query, content); // Dice 基线
            }
            out.add(new L1Candidate(fs.getMemoryWorkingId(), content, fuzz, relevance,
                    1.0 - fuzz, fuzz >= fuzzClearThreshold, fs.getImportanceScore() == null ? 0.2
                            : fs.getImportanceScore()));
        }
        // 清晰段优先（fuzz<阈值 恒在模糊段前）；清晰内 fuzz 升序（最清晰在前）+ 相关分降序；
        // 模糊段内相关分降序。显式比较器避免整链 reversed 导致模糊段反超。
        out.sort((a, b) -> {
            if (a.fuzzy() != b.fuzzy()) {
                return a.fuzzy() ? 1 : -1;
            }
            if (!a.fuzzy()) {
                int byFuzz = Double.compare(a.fuzzLevel(), b.fuzzLevel());
                if (byFuzz != 0) {
                    return byFuzz;
                }
            }
            return Double.compare(b.relevance(), a.relevance());
        });
        return out;
    }

    /** 该角色当前注入应使用的档位（供预览 API/调试；query/urgency 空 → 情境兜底，与 build 同口径）。 */
    public Map<String, Object> preview(Long projectId, String characterRef, String scenario, String query,
                                       Integer urgency) {
        Map<String, Object> m = new LinkedHashMap<>();
        String q = (query == null || query.isBlank()) ? recentContextQuery(projectId, characterRef) : query;
        Integer u = urgency != null ? urgency : situationalUrgency(projectId, characterRef, scenario);
        String tier = determineTier(projectId, characterRef, scenario, q, u);
        m.put("tier", tier);
        m.put("budgetTokens", budgetOf(tier));
        m.put("scenario", scenario);
        m.put("urgency", u);
        List<Map<String, Object>> l1 = new ArrayList<>();
        for (L1Candidate c : scoreL1(projectId, characterRef, q)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memoryWorkingId", c.memoryWorkingId());
            row.put("content", c.content());
            row.put("fuzzLevel", round2(c.fuzzLevel()));
            row.put("relevance", round2(c.relevance()));
            row.put("weight", round2(c.weight()));
            row.put("fuzzy", c.fuzzy());
            l1.add(row);
        }
        m.put("l1", l1);
        return m;
    }

    // ==================== 路由 trace（内存环形缓冲 + 回放） ====================

    /**
     * 记录一次路由 trace（tier 判定 reason + 各层条数/token + 逐条 fuzz/relevance）。
     */
    public void recordTrace(Long projectId, String characterRef, String scenario, Long gameDay,
                            String tier, String reason, Integer urgency,
                            Map<String, LayerStat> layers) {
        try {
            traceBuffer.add(new RouteTrace(projectId, characterRef, scenario, gameDay, tier, reason,
                    urgency, new LinkedHashMap<>(layers == null ? Map.of() : layers)));
            while (traceBuffer.size() > Math.max(1, traceMaxEntries)) {
                traceBuffer.poll();
            }
        } catch (Exception e) {
            log.warn("[记忆路由] trace 记录失败（隔离继续）: {}", e.getMessage());
        }
    }

    /** 最近路由 trace（调试回放；可过滤）。 */
    public List<RouteTrace> recentTraces(Long projectId, String characterRef) {
        List<RouteTrace> out = new ArrayList<>(traceBuffer);
        out.sort(Comparator.comparingLong(RouteTrace::seq).reversed());
        List<RouteTrace> filtered = new ArrayList<>();
        for (RouteTrace t : out) {
            if (projectId != null && !projectId.equals(t.projectId())) {
                continue;
            }
            if (characterRef != null && !characterRef.isBlank()
                    && !characterRef.equals(t.characterRef())) {
                continue;
            }
            filtered.add(t);
            if (filtered.size() >= 100) {
                break;
            }
        }
        return filtered;
    }

    /** 检索标记（注入命中 L1 时调用）。 */
    public void markRetrieved(Long memoryWorkingId) {
        if (fuzzService != null) {
            try {
                fuzzService.markRetrieved(memoryWorkingId);
            } catch (Exception e) {
                log.warn("[记忆路由] 检索标记失败（隔离继续）: {}", e.getMessage());
            }
        }
    }

    // ==================== 工具 ====================

    private boolean aiAllowed(Long projectId) {
        return aiIntensityGate == null || aiIntensityGate.allowsAi(projectId);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** L1 注入候选。 */
    public record L1Candidate(Long memoryWorkingId, String content, double fuzzLevel,
                              double relevance, double weight, boolean fuzzy, double importance) {
    }

    /** 路由 trace 记录（环形缓冲项）。 */
    public record RouteTrace(long seq, Long projectId, String characterRef, String scenario,
                             Long gameDay, String tier, String reason, Integer urgency,
                             Map<String, LayerStat> layers) {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

        public RouteTrace(Long projectId, String characterRef, String scenario, Long gameDay,
                          String tier, String reason, Integer urgency, Map<String, LayerStat> layers) {
            this(SEQ.incrementAndGet(), projectId, characterRef, scenario, gameDay, tier, reason,
                    urgency, layers);
        }
    }

    /** 层统计（条数/token）。 */
    public record LayerStat(String layer, int count, int tokens) {
    }
}
