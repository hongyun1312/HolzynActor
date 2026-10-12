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

package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.entity.KnowledgeCandidate;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.repository.KnowledgeCandidateRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆-知识桥接层（P1 ②-3，ProactAgent R12 + T2 对齐）——反思洞察 → 可复用事实型知识。
 * <p><b>缺失现状</b>：反思洞察只写记忆流（kind=reflection），无「可复用事实型知识候选」
 * 抽取→确认→写入知识卡/世界知识库的中间层（知识文档写入方仅同步器/知识卡，防知识卡僵化）。</p>
 * <p><b>流程（用户 Q4/Q5 确认）</b>：</p>
 * <ol>
 * <li><b>独立 AI 判定抽取</b> {@link #extractAndPromote}：反思成功后另起一次独立 AI 调用
 * （scene=knowledge_bridge，SCHEMA_JSON）——输入=角色档案摘要 + 本次反思洞察列表，输出
 * {@code knowledge_candidates[]}（{content, importance, is_knowledge}），判定哪些洞察是
 * 「可复用客观事实型知识」（关于世界/他人/自身稳定的认知）而非临时情绪/一次性事件；</li>
 * <li><b>全自动确认</b>（规则）：内容非空且 5~300 字、is_knowledge=true、重要度 1-5；
 * <b>无人工确认端点</b>（用户 Q5 自定义「全自动」）；</li>
 * <li><b>去重</b>：与同角色已确认候选 / 既有「经历沉淀」文档内容比对，重复 → duplicate；</li>
 * <li><b>写入落点</b>：角色级「经历沉淀」知识文档（title=「经历沉淀」，characterId 非空）——
 * <b>不动模板化人物知识卡</b>（防知识卡僵化）；幂等 upsert（不存在新建/内容未含追加+更新+重向量化）
 * → 候选置 promoted → 文档置信度提升（②-2 生命周期联动）。</li>
 * </ol>
 * <p><b>挂载点</b>：{@code ReflectionService} 反思写阶段提交后（AfterCommit，独立 AI 调用绝不在
 * 锁存活期间执行）+ 手动端点（冒烟/运维直驱）。</p>
 * <p><b>架构不变量</b>：含 AI 方法不带 {@code @Transactional}（AI 在事务外）；依赖字段注入
 * required=false + @Lazy 破 Bean 环（构造器仅仓库+序列化器）；null=未装配[单测]按能力缺失降级
 * （返回零统计，fail-open 零阻断）。</p>
 */
@Slf4j
@Service
public class KnowledgeBridgeService {

    /** AI 判定场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_KNOWLEDGE_BRIDGE = "knowledge_bridge";

    /** 「经历沉淀」知识文档标题（角色级；识别前缀，感知/对话注入带来源标注）。 */
    public static final String TITLE_SETTLEMENT = "经历沉淀";

    /** 候选事实长度下限（字符，防碎片噪音）。 */
    static final int CONTENT_MIN_LEN = 5;
    /** 候选事实长度上限（字符，防整段洞察塞入）。 */
    static final int CONTENT_MAX_LEN = 300;

    /** 总开关（false=桥接不执行，反思挂钩与手动端点均零操作）。 */
    @Value("${holzyn.actor.knowledge-bridge.enabled:true}")
    private boolean enabled = true;

    /** 知识文档仓库（沉淀文档 upsert）。 */
    private final ActorKnowledgeDocRepository docRepository;

    /** 候选仓库（留档审计 + 去重）。 */
    private final KnowledgeCandidateRepository candidateRepository;

    /** Jackson 序列化器（AI 输出解析）。 */
    private final ObjectMapper objectMapper;

    /** 模板渲染（AI 判定 prompt）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（独立 AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /** 角色档案摘要（判定情境组成部分）。 */
    @Autowired(required = false)
    @Lazy
    private PlanningService planningService;

    /** 入库向量化（沉淀文档更新后重向量化；null=未装配跳过向量化）。 */
    @Autowired(required = false)
    @Lazy
    private KnowledgeVectorizerService vectorizerService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（knowledge_bridge 属批4 记忆链·角色视角）：AI 判定请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 构造器（仓库+序列化器全量构造注入；服务字段注入保持构造器不变）。
     */
    public KnowledgeBridgeService(ActorKnowledgeDocRepository docRepository,
                                  KnowledgeCandidateRepository candidateRepository,
                                  ObjectMapper objectMapper) {
        this.docRepository = docRepository;
        this.candidateRepository = candidateRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 桥接主流程（独立 AI 判定抽取 → 全自动确认 → 去重 → 落「经历沉淀」文档）。
     * <p><b>fail-open</b>：开关关/未装配/AI 失败/解析失败 → 返回零统计（零阻断，不抛错）。</p>
     * <p><b>事务</b>：本方法不带 {@code @Transactional}（含 AI 调用）；写阶段落库/文档更新由
     * 内部短事务方法逐个提交（不跨 AI 存活）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（npc:{id}）
     *
     * @param userId 归属用户 ID（AI 凭据；null=CurrentUserProvider 兜底）
     *
     * @param characterInfo 角色档案摘要文本（判定情境；可空=桥接内部取）
     *
     * @param insights 本次反思洞察列表（判定输入；空 → 零操作）
     *
     * @return 统计（promoted=写入沉淀文档数；duplicate=重复跳过数；rejected=规则拒绝数）
     */
    public BridgeResult extractAndPromote(Long projectId, String characterRef, Long userId,
                                          String characterInfo, List<String> insights) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || insights == null || insights.isEmpty()) {
            return BridgeResult.empty();
        }
        try {
            String info = characterInfo;
            if ((info == null || info.isBlank()) && planningService != null) {
                try {
                    info = planningService.characterInfoText(characterRef);
                } catch (Exception e) {
                    log.debug("[知识桥接] 角色档案摘要获取失败（降级为无档案输入）: {}", e.getMessage());
                }
            }
            List<CandidateDraft> drafts = aiJudge(projectId, characterRef, userId, info, insights);
            if (drafts.isEmpty()) {
                log.debug("[知识桥接] AI 判定无候选（零写入）: ref={}", characterRef);
                return BridgeResult.empty();
            }
            return confirmAndPromote(projectId, characterRef, userId, drafts);
        } catch (Exception e) {
            log.warn("[知识桥接] 桥接流程异常（fail-open 零写入）: ref={} : {}", characterRef, e.getMessage());
            return BridgeResult.empty();
        }
    }

    /**
     * 反思挂钩入口（ReflectionService 反思写阶段提交后经 AfterCommit 调用）：
     * 解析用户 ID + 组装洞察列表文本。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param userId 归属用户 ID（可空=CurrentUserProvider 兜底）
     *
     * @param characterInfo 角色档案摘要（判定情境）
     *
     * @param insights 洞察文本列表
     *
     * @return 统计（零阻断）
     */
    public BridgeResult runForReflection(Long projectId, String characterRef, Long userId,
                                         String characterInfo, List<String> insights) {
        return extractAndPromote(projectId, characterRef, userId, characterInfo, insights);
    }

    // ==================== AI 判定（独立调用，scene=knowledge_bridge） ====================

    /**
     * 独立 AI 判定：从反思洞察中抽取「可复用事实型知识候选」。
     * 未装配/失败 → 空列表（fail-open 零写入）。
     */
    private List<CandidateDraft> aiJudge(Long projectId, String characterRef, Long userId,
                                         String characterInfo, List<String> insights) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return List.of();
        }
        Long uid = userId != null ? userId
                : (currentUserProvider != null ? currentUserProvider.currentUserId() : 1L);
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("character_info", characterInfo == null || characterInfo.isBlank()
                ? "（无）" : characterInfo);
        placeholders.put("insights", formatInsights(insights));
        String prompt = promptTemplateService.render(uid, projectId, SCENE_KNOWLEDGE_BRIDGE, placeholders);
        // 批6 收口（knowledge_bridge 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
        // 「哪些反思值得沉淀为该角色知识」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
        if (aiContextInjector != null) {
            String ctx = aiContextInjector.memoryChainContext(projectId, characterRef, null);
            if (ctx != null && !ctx.isBlank()) {
                prompt = ctx + "\n\n" + prompt;
            }
        }
        String system = promptTemplateService.systemMessage(uid, projectId, SCENE_KNOWLEDGE_BRIDGE, null);
        if (system == null || system.isBlank()) {
            system = "你只输出有效 JSON，不要输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
        }
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", system),
                new AiChatRequest.ChatMessage("user", prompt)),
                PromptSpecRegistry.temperatureOf(SCENE_KNOWLEDGE_BRIDGE),
                PromptSpecRegistry.maxOutputTokensOf(SCENE_KNOWLEDGE_BRIDGE),
                true);
        AiChatResult result = AiCallContext.run(SCENE_KNOWLEDGE_BRIDGE, NpcRefs.idOf(characterRef),
                () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
        try {
            usageLogService.record(uid, projectId, NpcRefs.idOf(characterRef), result.providerId(), result.model(),
                    SCENE_KNOWLEDGE_BRIDGE, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
        } catch (Exception e) {
            log.debug("[知识桥接] 用量入账失败（忽略）: {}", e.getMessage());
        }
        String content = result.content();
        return content == null || content.isBlank() ? List.of() : parseCandidates(content);
    }

    /** 洞察列表文本（逐条带序号）。 */
    private static String formatInsights(List<String> insights) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String s : insights) {
            if (s == null || s.isBlank()) {
                continue;
            }
            sb.append(i++).append(". ").append(s.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 全自动确认 + 去重 + 落沉淀文档 ====================

    /**
     * 确认与写入（全自动）：规则确认（内容非空 5~300 字 + is_knowledge + 重要度合规）→
     * 去重（同角色已确认候选 / 既有沉淀文档内容）→ 落「经历沉淀」文档 + 候选留档。
     * <p><b>事务边界</b>：本方法不带 {@code @Transactional}——沉淀文档向量化含 embedding AI
     * 调用（「AI 在事务外」约束）；逐条确认/写入委托内部短事务方法（毫秒级提交）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param userId 归属用户 ID（向量化凭据）
     *
     * @param drafts AI 判定候选（未确认）
     *
     * @return 统计
     */
    private BridgeResult confirmAndPromote(Long projectId, String characterRef, Long userId,
                                           List<CandidateDraft> drafts) {
        Long charId = NpcRefs.idOf(characterRef);
        int promoted = 0;
        int duplicate = 0;
        int rejected = 0;
        List<String> promotedTexts = new ArrayList<>();
        for (CandidateDraft draft : drafts) {
            if (draft == null || !accept(draft)) {
                rejected++;
                saveCandidate(projectId, characterRef, charId, draft, KnowledgeCandidate.STATUS_REJECTED);
                continue;
            }
            if (isDuplicate(projectId, characterRef, promotedTexts, draft.content())) {
                duplicate++;
                saveCandidate(projectId, characterRef, charId, draft, KnowledgeCandidate.STATUS_DUPLICATE);
                continue;
            }
            // 写入「经历沉淀」文档（幂等 upsert + 追加 + 事务外向量化）
            int written = appendSettlementDocAndVectorize(userId, projectId, charId, draft.content());
            if (written == 0) {
                // 文档已含该事实（并发/兜底）→ 视为重复
                duplicate++;
                saveCandidate(projectId, characterRef, charId, draft, KnowledgeCandidate.STATUS_DUPLICATE);
                continue;
            }
            promotedTexts.add(draft.content());
            saveCandidate(projectId, characterRef, charId, draft, KnowledgeCandidate.STATUS_PROMOTED);
            promoted++;
        }
        log.info("[知识桥接] 完成：project={} ref={} promoted={} duplicate={} rejected={}",
                projectId, characterRef, promoted, duplicate, rejected);
        return new BridgeResult(promoted, duplicate, rejected);
    }

    /**
     * 规则确认（静态纯逻辑，可单测）：is_knowledge=true + 内容非空且长度 5~300。
     */
    static boolean accept(CandidateDraft draft) {
        if (draft == null || !draft.isKnowledge()) {
            return false;
        }
        String c = draft.content() == null ? "" : draft.content().trim();
        return c.length() >= CONTENT_MIN_LEN && c.length() <= CONTENT_MAX_LEN;
    }

    /**
     * 去重（静态纯逻辑，可单测）：与同角色已确认候选 / 本次已写入事实 / 既有沉淀文档内容
     * 比对——候选事实完整出现在任一既有内容中即重复。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用
     *
     * @param promotedTexts 本次已写入事实（进程内去重）
     *
     * @param content 候选事实
     *
     * @return true=重复
     */
    boolean isDuplicate(Long projectId, String characterRef, List<String> promotedTexts, String content) {
        String c = content == null ? "" : content.trim();
        if (c.isEmpty()) {
            return true;
        }
        // ① 本次已写入
        for (String t : promotedTexts) {
            if (t != null && t.contains(c)) {
                return true;
            }
        }
        // ② 历史已确认候选
        if (candidateRepository != null) {
            List<KnowledgeCandidate> confirmed =
                    candidateRepository.findByProjectIdAndCharacterRefAndStatus(
                            projectId, characterRef, KnowledgeCandidate.STATUS_PROMOTED);
            for (KnowledgeCandidate kc : confirmed) {
                if (kc.getContent() != null && kc.getContent().contains(c)) {
                    return true;
                }
            }
        }
        // ③ 既有「经历沉淀」文档内容
        if (docRepository != null) {
            List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
            for (ActorKnowledgeDoc d : docs) {
                if (d.getContent() != null && TITLE_SETTLEMENT.equals(d.getTitle())
                        && d.getContent().contains(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 追加事实到角色级「经历沉淀」文档（幂等 upsert，内部短事务）。
     * <p>文档不存在 → 新建（引导句 + 首条事实）；存在且内容未含该事实 → 追加 + 置信度提升 +
     * 重向量化；内容已含 → 0（重复，由调用方按 duplicate 处理）。</p>
     * <p><b>向量化</b>：经 {@link KnowledgeVectorizerService}（embedding AI 调用在事务外——
     * 本方法自身为短事务，向量化在事务提交前调用会违反「AI 在事务外」？——拆两段：
     * ① 本短事务内完成文档落库（新建/追加）拿到最新内容；② 向量化由调用方在事务外显式执行
     * （见 {@link #appendSettlementDocAndVectorize}）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（非空=角色级）
     *
     * @param fact 事实文本（已确认非重复）
     *
     * @return 1=已追加；0=内容已含/参数无效
     */
    @Transactional
    public int appendSettlementDoc(Long projectId, Long characterId, String fact) {
        if (projectId == null || characterId == null || fact == null || fact.isBlank()) {
            return 0;
        }
        ActorKnowledgeDoc doc = null;
        for (ActorKnowledgeDoc d : docRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (TITLE_SETTLEMENT.equals(d.getTitle()) && characterId.equals(d.getCharacterId())) {
                doc = d;
                break;
            }
        }
        String line = "- [重要事实] " + fact.trim();
        if (doc == null) {
            doc = new ActorKnowledgeDoc();
            doc.setProjectId(projectId);
            doc.setCharacterId(characterId);
            doc.setTitle(TITLE_SETTLEMENT);
            doc.setContent(ANCHOR_SETTLEMENT + "\n" + line);
            doc.setLifecycleState(KnowledgeLifecycleService.STATE_HOT);
            doc.setConfidence(0.6);
            docRepository.save(doc);
            log.info("[知识桥接] 沉淀文档新建：project={} char={} doc={}", projectId, characterId, doc.getId());
            return 1; // 已写入（ID 由持久层回填；单测 mock 无回填，语义以「写入完成」为准）
        }
        if (doc.getContent() != null && doc.getContent().contains(fact.trim())) {
            return 0; // 内容已含 → 重复
        }
        String merged = (doc.getContent() == null || doc.getContent().isBlank() ? ANCHOR_SETTLEMENT
                : doc.getContent()) + "\n" + line;
        doc.setContent(merged);
        doc.setLifecycleState(KnowledgeLifecycleService.STATE_HOT);
        doc.setConfidence(KnowledgeLifecycleService.boostConfidence(doc.getConfidence()));
        docRepository.save(doc);
        log.info("[知识桥接] 沉淀文档追加：project={} char={} doc={} 总行数≈{}", projectId, characterId,
                doc.getId(), merged.split("\n").length);
        return 1;
    }

    /** 「经历沉淀」文档正文引导句（含标题锚词，对齐世界设定同步的检索引导句实践）。 */
    static final String ANCHOR_SETTLEMENT = "【经历沉淀】以下为该角色在长期经历中沉淀的可复用事实型知识（由反思洞察经知识桥接确认写入）。";

    /**
     * 追加 + 向量化（事务外编排）：先落库（短事务），再向量化（embedding AI 在事务外）。
     *
     * @param userId 归属用户 ID（embedding 凭据）
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @param fact 事实文本
     *
     * @return 1=已追加并（尝试）向量化；0=重复/无效
     */
    public int appendSettlementDocAndVectorize(Long userId, Long projectId, Long characterId, String fact) {
        int written = appendSettlementDoc(projectId, characterId, fact);
        if (written == 1 && vectorizerService != null) {
            try {
                List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
                for (ActorKnowledgeDoc d : docs) {
                    if (TITLE_SETTLEMENT.equals(d.getTitle()) && characterId.equals(d.getCharacterId())) {
                        vectorizerService.vectorize(userId, projectId, d.getId(), d.getTitle(),
                                d.getCharacterId(), d.getContent());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[知识桥接] 沉淀文档向量化失败（文本检索降级兜底）: {}", e.getMessage());
            }
        }
        return written;
    }

    /** 候选留档（内部短事务；draft 为 null 时仅记 rejected 空档）。 */
    @Transactional
    void saveCandidate(Long projectId, String characterRef, Long characterId, CandidateDraft draft, String status) {
        KnowledgeCandidate kc = new KnowledgeCandidate();
        kc.setProjectId(projectId);
        kc.setCharacterRef(characterRef);
        kc.setCharacterId(characterId);
        kc.setContent(draft == null || draft.content() == null ? "" : draft.content().trim());
        kc.setImportance(draft == null ? 3 : draft.importance());
        kc.setStatus(status);
        kc.setConfirmedAt(LocalDateTime.now());
        candidateRepository.save(kc);
    }

    // ==================== AI 输出解析（静态可测） ====================

    /**
     * 解析 AI 判定输出 JSON（静态纯逻辑，可单测）：{@code {"knowledge_candidates":[{content,importance,is_knowledge}]}}。
     * 非法/垃圾 → 空列表（fail-open）。
     */
    static List<CandidateDraft> parseCandidates(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            String json = com.holzyn.actor.common.JsonUtil.extractJson(content);
            if (json == null) {
                return List.of();
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.isObject()) {
                return List.of();
            }
            JsonNode arr = node.path("knowledge_candidates");
            if (!arr.isArray()) {
                return List.of();
            }
            List<CandidateDraft> out = new ArrayList<>();
            for (JsonNode it : arr) {
                String c = it.path("content").asText("");
                if (c.isBlank()) {
                    continue;
                }
                int imp = it.path("importance").isNumber()
                        ? Math.max(1, Math.min(5, it.path("importance").asInt(3)))
                        : 3;
                boolean knowledge = it.path("is_knowledge").asBoolean(false);
                out.add(new CandidateDraft(c.trim(), imp, knowledge));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** AI 判定候选草稿（未确认）。 */
    record CandidateDraft(String content, int importance, boolean isKnowledge) {
    }

    /** 桥接结果统计。 */
    public record BridgeResult(int promoted, int duplicate, int rejected) {
        static BridgeResult empty() {
            return new BridgeResult(0, 0, 0);
        }
    }
}
