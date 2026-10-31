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

package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动记忆检索（ProactAgent「Ask Only When Needed」，P1 ②-1）。
 * <p><b>语义</b>：决策/反思入口处由 AI 按当前情境判定「此刻是否需要主动想起/查证某件事」
 * （{@code need=false} 是默认——真人不会每时每刻都在回忆），需要时产出<b>定向检索 query</b>，
 * 走既有检索设施三源召回（角色 L2 记忆 + 项目级世界大事记 + 人物知识卡），组装
 * <b>【主动想起】段</b>注入决策/反思 prompt（决策经 {@link AiCallContext} holder 传递——
 * AI 深思 {{proactive_memory}} 占位符 / 决策模型 快判情境文本；反思经 {{proactive_memory}} 占位符）。
 * 区别于既有被动检索（触发时携带 query 检索一次）：本组件是角色<b>主动发起的定向检索</b>，
 * 检索目标由角色自己的「认知缺口」驱动（ProactAgent 2025：被动检索常错失知识缺口）。</p>
 * <p><b>触发口径（用户 Q1-Q9 确认）</b>：决策全局挂载（含 SpaceChoice/决策模型 快判；programmatic
 * 档 0 AI 除外、生理危险区强制除外）+ 反思双入口；AI 按情境判断 need（scene=proactive_retrieval，
 * 输出 {"need":bool,"query":str,"reason":str}）；判定失败/超时/解析失败 → <b>fail-open</b>
 * （降级为直接跳过，零阻断）；need=true 后进入<b>冷却期</b>（默认 2 游戏小时，可配），
 * 冷却期内不再自问（间歇性回忆，像真人）；检索范围=角色记忆 + 世界大事记 + 人物知识卡；
 * 注入形态=独立【主动想起】段（按 id 跨源去重，token 预算默认 300）。</p>
 * <p><b>架构不变量</b>：含 AI 调用，公开方法<b>一律不带 {@code @Transactional}</b>；
 * 依赖全部字段注入 {@code @Autowired(required=false)}+{@code @Lazy} 破 Bean 环；
 * null=未装配[单测]按能力缺失降级（跳过自问，零影响）。</p>
 */
@Slf4j
@Component
public class ProactiveRetrievalService {

    /** AI 判定场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_PROACTIVE_RETRIEVAL = "proactive_retrieval";

    /** 决策链路 trace 环节类型（stepType=proactive_retrieval，冷却/审计依据）。 */
    public static final String STEP_TYPE_PROACTIVE_RETRIEVAL = "proactive_retrieval";

    /** 冷却判定内存表（projectId|ref → 最近一次 need=true 的真实时刻；进程内零竞态，重启由 trace 兜底）。 */
    private final Map<String, LocalDateTime> lastNeedTrueAt = new ConcurrentHashMap<>();

    /** 总开关。 */
    @Value("${holzyn.actor.proactive-retrieval.enabled:true}")
    private boolean enabled = true;

    /** 冷却期（游戏小时；need=true 后冷却期内不再自问）。 */
    @Value("${holzyn.actor.proactive-retrieval.cooldown-game-hours:2}")
    private long cooldownGameHours = 2;

    /** 每源检索条数上限。 */
    @Value("${holzyn.actor.proactive-retrieval.top-k:3}")
    private int topK = 3;

    /** 【主动想起】段 token 预算。 */
    @Value("${holzyn.actor.proactive-retrieval.max-tokens:300}")
    private int maxTokens = 300;

    /** AI 判定输入情境截断长度（字符）。 */
    @Value("${holzyn.actor.proactive-retrieval.max-situation-length:600}")
    private int maxSituationLength = 600;

    /** query 最大长度（字符，防 AI 输出失控）。 */
    @Value("${holzyn.actor.proactive-retrieval.max-query-length:100}")
    private int maxQueryLength = 100;

    /** Prompt 渲染（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（AI 判定；真实网关/测试 mock）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 判定记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /** 世界时钟（冷却换算：need=true 时刻 → 游戏小时）。 */
    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /** 记忆仓库（项目级世界大事记直查）。 */
    @Autowired(required = false)
    @Lazy
    private ActorMemoryRepository memoryRepository;

    /** 记忆组件（角色 L2 记忆 Smallville 评分检索，复用既有门面）。 */
    @Autowired(required = false)
    @Lazy
    private AgentMemoryService agentMemoryService;

    /** 知识检索（人物知识卡/知识文档多路召回，复用既有门面）。 */
    @Autowired(required = false)
    @Lazy
    private KnowledgeRetrievalService knowledgeRetrievalService;

    /** AI 调用 trace 仓库（冷却持久兜底：重启后查最近 need=true 记录）。 */
    @Autowired(required = false)
    @Lazy
    private ActorAiCallLogRepository aiCallLogRepository;

    /**
     * 统一 AI 上下文注入器（主动检索属「角色视角」记忆操作——AI 以该角色视角判断
     * 「此刻是否需要想起什么」，前置世界观简报 + 角色核心卡；null=未装配跳过注入）
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== 主动检索入口 ====================

    /**
     * 主动检索（决策/反思入口调用）：AI 判定 need → 定向检索三源 → 【主动想起】段。
     * <p><b>fail-open 全程零阻断</b>：开关关/参数缺失/冷却中/AI 判定失败/解析失败/检索无命中/
     * 异常 → 返回 null（调用方跳过注入）。need=true 触发后写入冷却（内存 + trace 双落点）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code special:{id}} 或 {@code npc:{id}}）
     *
     * @param userId 归属用户 ID（知识检索/用量记账；可空=1 兜底）
     *
     * @param situation 当前情境文本（AI 判定输入；空白=跳过自问）
     *
     * @return 主动检索结果（段文本 + 命中）；无检索/降级 → null
     */
    public ProactiveResult maybeRetrieve(Long projectId, String characterRef, Long userId, String situation) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || situation == null || situation.isBlank()) {
            return null;
        }
        long startMs = System.currentTimeMillis();
        try {
            // ① 冷却检查（need=true 后冷却期内不再自问——间歇性回忆）
            if (inCooldown(projectId, characterRef)) {
                return null;
            }
            // ② AI 判定（scene=proactive_retrieval）：need + 定向 query + 理由
            ProactiveVerdict verdict = judge(projectId, characterRef, userId, situation);
            long durationMs = System.currentTimeMillis() - startMs;
            if (verdict == null) {
                // fail-open：AI 判定失败/超时/解析失败 → 跳过（不干扰主流程，不冷却）
                log.debug("[主动检索] AI 判定失败降级（跳过）: ref={}", characterRef);
                return null;
            }
            if (!verdict.need()) {
                // 判定不需要：留痕审计（不进入冷却——"不需要"不是"想起过"）
                recordTrace(projectId, characterRef, durationMs,
                        "need=false 判定（" + abbreviate(verdict.reason(), 40) + "）");
                return null;
            }
            String query = abbreviate(verdict.query(), Math.max(1, maxQueryLength));
            if (query == null || query.isBlank()) {
                // need=true 但 query 缺失：降级为情境文本检索（fail-open 兜底）
                query = abbreviate(situation, Math.max(1, maxQueryLength));
            }
            // ③ 三源检索（角色记忆 + 世界大事记 + 人物知识卡；跨源按 id 去重）
            List<Hit> hits = retrieveAll(projectId, characterRef, userId, query);
            if (hits.isEmpty()) {
                recordTrace(projectId, characterRef, durationMs,
                        "need=true query=「" + abbreviate(query, 30) + "」检索无命中");
                return null;
            }
            // ④ 组装【主动想起】段（token 预算截断）
            String segment = buildSegment(query, verdict.reason(), hits, maxTokensOf(maxTokens));
            // ⑤ 冷却落点（need=true 视为「想起过」）：内存 + trace 持久
            markNeedTrue(projectId, characterRef);
            // ⑥ 决策链路 trace（need=true + 命中清单，供审计与冷却持久兜底）
            StringBuilder ds = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) {
                    ds.append(",");
                }
                ds.append(hits.get(i).kind()).append("#").append(hits.get(i).id());
            }
            recordTrace(projectId, characterRef, durationMs,
                    "need=true query=「" + abbreviate(query, 30) + "」命中 " + hits.size()
                            + " 条（" + ds + "）");
            AiCallContext.addDataSource("主动检索:" + ds);
            log.info("[主动检索] 想起: ref={} query={} 命中 {} 条", characterRef,
                    abbreviate(query, 30), hits.size());
            return new ProactiveResult(segment, hits);
        } catch (Exception e) {
            // 旁路能力异常隔离：绝不干扰决策/反思主流程
            log.warn("[主动检索] 异常（fail-open）: ref={} : {}", characterRef, e.getMessage());
            return null;
        }
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 主动检索结果。
     *
     * @param segment 【主动想起】段文本（注入 prompt）
     *
     * @param hits 命中清单（调试/审计）
     */
    public record ProactiveResult(String segment, List<Hit> hits) {
    }

    /**
     * 命中条目（跨源统一：kind=mem 角色记忆 / proj 世界大事记 / doc 知识卡）。
     *
     * @param kind 来源（mem/proj/doc）
     *
     * @param id 来源 id（actor_memory.id / actor_knowledge_doc.id）
     *
     * @param text 内容文本
     */
    public record Hit(String kind, Long id, String text) {
    }

    /**
     * 判定结果（内部规范）。
     *
     * @param need true=需要主动检索
     *
     * @param query 定向检索 query（need=true 时）
     *
     * @param reason 判定理由（AI 输出；可空）
     */
    public record ProactiveVerdict(boolean need, String query, String reason) {
    }

    /** 字符串截断（摘要展示/输入收敛用）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    // ==================== 私有辅助 ====================

    /**
     * 冷却判定：进程内内存表优先（零竞态），trace 持久兜底（重启后沿用）。
     * 换算口径=游戏小时（{@code gameHourAt} 统一换算；世界时钟不可用回退真实小时）。
     */
    private boolean inCooldown(Long projectId, String characterRef) {
        String key = keyOf(projectId, characterRef);
        LocalDateTime last = lastNeedTrueAt.get(key);
        if (last == null) {
            last = lastNeedTrueFromTrace(projectId, characterRef);
            if (last != null) {
                lastNeedTrueAt.put(key, last);
            }
        }
        if (last == null) {
            return false;
        }
        // 冷却已过期 → 清内存并放行（避免表膨胀）
        if (!withinCooldownWindow(projectId, last)) {
            lastNeedTrueAt.remove(key);
            return false;
        }
        return true;
    }

    /** 冷却窗口判定（静态可测）：last 时刻距当前是否在 cooldownGameHours 游戏小时内。 */
    boolean withinCooldownWindow(Long projectId, LocalDateTime last) {
        if (last == null) {
            return false;
        }
        long cooldownHours = Math.max(0, cooldownGameHours);
        if (cooldownHours == 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (worldClockService != null) {
            try {
                Long lastGameHour = worldClockService.gameHourAt(projectId, last);
                Long nowGameHour = worldClockService.gameHourAt(projectId, now);
                if (lastGameHour != null && nowGameHour != null) {
                    return nowGameHour - lastGameHour < cooldownHours;
                }
            } catch (Exception e) {
                log.debug("[主动检索] 游戏小时换算失败（回退真实时间）: {}", e.getMessage());
            }
        }
        // 回退：真实小时
        return Duration.between(last, now).toHours() < cooldownHours;
    }

    /** 冷却持久兜底：查最近一条 need=true 的 proactive_retrieval trace（content 前缀 ref=… + need=true）。 */
    private LocalDateTime lastNeedTrueFromTrace(Long projectId, String characterRef) {
        if (aiCallLogRepository == null) {
            return null;
        }
        try {
            List<ActorAiCallLog> logs = aiCallLogRepository
                    .findTop10ByProjectIdAndStepTypeOrderByIdDesc(projectId, STEP_TYPE_PROACTIVE_RETRIEVAL);
            String prefix = "ref=" + characterRef + " ";
            for (ActorAiCallLog l : logs) {
                String content = l.getContent();
                if (content != null && content.startsWith(prefix) && content.contains("need=true")) {
                    return l.getCreatedAt();
                }
            }
        } catch (Exception e) {
            log.debug("[主动检索] trace 冷却查询失败（按无冷却）: {}", e.getMessage());
        }
        return null;
    }

    /** need=true 落点：内存表（进程内冷却）+ 依赖 trace 持久（重启后 lastNeedTrueFromTrace 兜底）。 */
    private void markNeedTrue(Long projectId, String characterRef) {
        lastNeedTrueAt.put(keyOf(projectId, characterRef), LocalDateTime.now());
    }

    private static String keyOf(Long projectId, String characterRef) {
        return projectId + "|" + characterRef;
    }

    /**
     * AI 判定：scene=proactive_retrieval，输入=当前情境摘要 → 输出
     * {"need":bool,"query":str,"reason":str}。失败/超时/解析失败 → null（fail-open）。
     */
    private ProactiveVerdict judge(Long projectId, String characterRef, Long userId, String situation) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long uid = userId != null ? userId
                    : (currentUserProvider != null ? currentUserProvider.currentUserId() : 1L);
            Map<String, Object> placeholders = new LinkedHashMap<>();
            // 2026-10-10 决策：不设字数硬边界，情境文本全量注入（此前按 maxSituationLength 截断
            // 常切在句中断，导致 AI 判定信息残缺）
            placeholders.put("situation", situation == null ? "" : situation.trim());
            String prompt = promptTemplateService.render(uid, projectId, SCENE_PROACTIVE_RETRIEVAL, placeholders);
            // 上下文注入（批 4 记忆与反思链·段 2F 对齐）：主动检索属「角色视角」记忆操作——
            // AI 以该角色视角判断此刻是否需要想起什么，前置段 1A 世界观 + 段 1C 角色核心卡 +
            // 段 2D 当前世界动态 + 段 2E 当前状态（未装配/失败零阻断）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.memoryChainContext(projectId, characterRef, null);
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            String system = promptTemplateService.systemMessage(uid, projectId, SCENE_PROACTIVE_RETRIEVAL, null);
            if (system == null || system.isBlank()) {
                system = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_PROACTIVE_RETRIEVAL),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_PROACTIVE_RETRIEVAL),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_PROACTIVE_RETRIEVAL, NpcRefs.idOf(characterRef), 1,
                    () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
            try {
                usageLogService.record(uid, projectId, NpcRefs.idOf(characterRef), result.providerId(),
                        result.model(), SCENE_PROACTIVE_RETRIEVAL, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[主动检索] 用量入账失败（忽略）: {}", e.getMessage());
            }
            return parseVerdict(result.content());
        } catch (Exception e) {
            log.debug("[主动检索] AI 判定失败（fail-open）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析判定 JSON（{"need":bool,"query":str,"reason":str}）。失败/非法 → null。
     */
    static ProactiveVerdict parseVerdict(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.has("need")) {
                return null;
            }
            boolean need = node.path("need").asBoolean(false);
            String query = node.path("query").asText(null);
            String reason = node.path("reason").asText(null);
            return new ProactiveVerdict(need, query, reason);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 三源检索（跨源按 id 去重；每源按自身相关性/评分降序 top-K）：
     * ① 角色 L2 记忆（Smallville 评分检索，复用 {@link AgentMemoryService#retrieve}）；
     * ② 项目级世界大事记（相关性分降序）；
     * ③ 人物知识卡/知识文档（多路召回，复用 {@link KnowledgeRetrievalService#search}）。
     */
    private List<Hit> retrieveAll(Long projectId, String characterRef, Long userId, String query) {
        List<Hit> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int k = Math.max(1, topK);
        // ① 角色 L2 记忆
        if (agentMemoryService != null) {
            try {
                List<ActorMemory> memories = agentMemoryService.retrieve(projectId, characterRef, query, k);
                if (memories != null) {
                    for (ActorMemory m : memories) {
                        if (m == null || m.getId() == null || m.getContent() == null || m.getContent().isBlank()) {
                            continue;
                        }
                        String key = "mem:" + m.getId();
                        if (seen.add(key)) {
                            out.add(new Hit("mem", m.getId(), m.getContent().trim()));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[主动检索] 角色记忆检索失败（跳过该源）: {}", e.getMessage());
            }
        }
        // ② 项目级世界大事记（角色无关、所有角色可见）
        if (memoryRepository != null) {
            try {
                List<ActorMemory> proj = memoryRepository
                        .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
                if (proj != null && !proj.isEmpty()) {
                    List<ActorMemory> ranked = new ArrayList<>(proj);
                    ranked.sort(Comparator
                            .comparingDouble((ActorMemory m) -> relevanceOf(query, m.getContent())).reversed());
                    int n = 0;
                    for (ActorMemory m : ranked) {
                        if (n >= k) {
                            break;
                        }
                        if (m.getId() == null || m.getContent() == null || m.getContent().isBlank()) {
                            continue;
                        }
                        String key = "proj:" + m.getId();
                        if (seen.add(key)) {
                            out.add(new Hit("proj", m.getId(), m.getContent().trim()));
                            n++;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[主动检索] 世界大事记检索失败（跳过该源）: {}", e.getMessage());
            }
        }
        // ③ 人物知识卡/知识文档
        if (knowledgeRetrievalService != null) {
            try {
                List<KnowledgeHit> docs = knowledgeRetrievalService.search(
                        userId != null ? userId : (currentUserProvider != null ? currentUserProvider.currentUserId() : 1L),
                        projectId, NpcRefs.idOf(characterRef), query, k);
                if (docs != null) {
                    for (KnowledgeHit d : docs) {
                        if (d == null || d.docId() == null || d.text() == null || d.text().isBlank()) {
                            continue;
                        }
                        String key = "doc:" + d.docId();
                        if (seen.add(key)) {
                            String title = d.title() == null || d.title().isBlank() ? "" : d.title().trim() + "：";
                            out.add(new Hit("doc", d.docId(), title + d.text().trim()));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[主动检索] 知识卡检索失败（跳过该源）: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 文本相关性分（Dice 二元组重叠，无 embedding 降级，与记忆检索同口径）。 */
    private static double relevanceOf(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        return MemoryDedup.overlapScore(query, content);
    }

    /**
     * 组装【主动想起】段（2026-10-10 决策：不设 token 预算硬边界，命中全量注入——
     * 此前按预算截断会丢失命中的往事细节，影响 AI 决策/对话的真实感）。
     *
     * @param query 定向检索 query（段内标注；可空）
     *
     * @param reason 判定理由（可空）
     *
     * @param hits 命中清单（已按相关性降序）
     *
     * @param budget token 预算（保留参数兼容调用点；不再用于截断）
     *
     * @return 段文本（无命中 → 空串）
     */
    static String buildSegment(String query, String reason, List<Hit> hits, int budget) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【主动想起】此刻你主动想起与当前情况相关的往事与见闻（可自然引用，不得遗忘或前后矛盾）：");
        for (Hit h : hits) {
            if (h == null || h.text() == null || h.text().isBlank()) {
                continue;
            }
            String line = "\n- [" + kindLabel(h.kind()) + "] " + h.text();
            sb.append(line);
        }
        return sb.toString();
    }

    /** 预算取值（静态可测：默认 300，0/负 → 300）。 */
    static int maxTokensOf(int configured) {
        return configured > 0 ? configured : 300;
    }

    /** 来源标签（段内展示）。 */
    static String kindLabel(String kind) {
        if ("mem".equals(kind)) {
            return "经历";
        }
        if ("proj".equals(kind)) {
            return "世界";
        }
        if ("doc".equals(kind)) {
            return "所知";
        }
        return "记忆";
    }

    /** 决策链路 trace（stepType=proactive_retrieval；content 前缀带 ref 供冷却/审计检索）。 */
    private void recordTrace(Long projectId, String characterRef, long durationMs, String detail) {
        AiCallContext.recordStep(projectId, "agent_decision", STEP_TYPE_PROACTIVE_RETRIEVAL,
                "ref=" + characterRef + " " + detail, durationMs);
    }
}
