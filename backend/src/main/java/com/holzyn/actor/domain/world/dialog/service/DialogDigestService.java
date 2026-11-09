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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.prompt.PromptResources;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.service.DialogueMessageService;
import com.holzyn.actor.domain.dialogue.service.PlayerProfileService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dialog.model.DialogClaim;
import com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.model.FactTypes;
import com.holzyn.actor.domain.world.fact.service.FactKnowledgeService;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.effect.service.EffectApplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 对话批量抽取（{@code dialog_digest}，一次 AI 双输出：记忆 + 强声明）。
 *
 * <p><b>为什么批量</b>：声明抽取与记忆抽取读同一段对话；逐轮各调一次等于把同样 2~3k tokens
 * 付两遍（设计 §5.1/§5.2）。本服务每 6 轮或会话关闭调用一次，输出同时写：
 * ① NPC 记忆（既有 {@link AgentMemoryService} 通道）；② 新对谈的关系投影；
 * ③ 承诺/已知事实陈述（{@link WorldFactService} + {@link FactKnowledgeService}）。</p>
 *
 * <p><b>事实边界（用户决策）</b>：承诺/约定落 {@code contract} 事实（private）；陈述类只有
 * 命中<b>已有 factKey</b> 才更新事实，否则只写“某人说过”的 knowledge——绝不把谎话、猜测、
 * 传言写成世界真相。</p>
 *
 * <p><b>架构不变量</b>：{@link #digest(DialogDigestRequest)} 含 AI 调用，<b>不得带
 * {@code @Transactional}</b>；落库全部走各领域短事务入口（WorldFactService /
 * FactKnowledgeService / AgentMemoryService / PlayerProfileService）。</p>
 */
@Slf4j
@Service
public class DialogDigestService {

    /** 场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE = "dialog_digest";

    /** 读取的最近对话条数。 */
    static final int WINDOW = 40;

    /** 每个参与者注入的已知事实条数。 */
    static final int KNOWN_FACTS_PER_ACTOR = 8;

    /** 事实清单上限。 */
    static final int KNOWN_FACTS_CAP = 20;

    /** 默认承诺期限：3 游戏日。 */
    static final int DEFAULT_DUE_MINUTES = 3 * 24 * 60;

    /** 关系 delta 钳制。 */
    static final int DELTA_CLAMP = 20;

    /** 记忆重要度（与既有沉淀一致：值得记的日常活动）。 */
    static final int MEMORY_IMPORTANCE = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    @Autowired(required = false)
    private DialogueMessageService dialogueMessageService;

    @Autowired(required = false)
    private ActorMessageRepository actorMessageRepository;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Autowired(required = false)
    private PlayerProfileService playerProfileService;

    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;

    @Autowired(required = false)
    private WorldFactService worldFactService;

    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    @Autowired(required = false)
    private FactKnowledgeService factKnowledgeService;

    /** 声明类事实统一经效果入口落地（null=旧直写兼容，单测可只装配事实服务）。 */
    @Autowired(required = false)
    private EffectApplier effectApplier;

    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 物品端口（对接：对话里的「这把刀给你」要真的把刀挪过去）。
     *
     * <p>未装配（{@code item.enabled=false}）时 GIFT 声明整条忽略——不落事实、不转移，
     * 与 I 线之前的行为一致（那时根本没有 GIFT 这一型）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.item.port.InventoryPort inventoryPort;

    /** 物品域配置（关着不处理 GIFT，保证关开关=回基线） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.item.config.ItemProperties itemProperties;

    /**
     * 执行一次批量抽取并落库（失败 fail-open：不落任何“半真”状态）。
     *
     * @param req 抽取请求
     *
     * @return true=至少落了一条事实/记忆/关系；false=跳过或 AI/解析失败
     */
    public boolean digest(DialogDigestRequest req) {
        if (req == null || req.getProjectId() == null || req.getSessionId() == null
                || req.getParticipantRefs() == null || req.getParticipantRefs().isEmpty()) {
            return false;
        }
        List<String> lines = readLines(req);
        if (lines.size() < 2 || aiChatGateway == null) {
            return false;
        }
        String playerName = playerName(req.getUserId());
        Map<String, String> names = namesOf(req.getParticipantRefs());
        List<ActorWorldFactView> knownFacts = knownFacts(req);
        String userPrompt = renderUser(req, names, playerName, knownFacts, lines);
        String systemPrompt = PromptResources.system("dialog_digest");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是世界记忆记录员：从对话中克制地抽取关系变化、记忆要点与强声明，"
                    + "严格只输出 JSON，不臆测、不把传言写成世界真相。";
        }
        AiChatRequest request = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", systemPrompt),
                new AiChatRequest.ChatMessage("user", userPrompt)),
                specTemperature(), specMaxTokens(), true);
        long startMs = System.currentTimeMillis();
        AiChatResult result;
        try {
            Long npcId = NpcRefs.idOf(req.getParticipantRefs().get(0));
            result = AiCallContext.run(SCENE, npcId,
                    (Supplier<AiChatResult>) () -> aiChatGateway.chatCompletion(
                            req.getUserId(), req.getProjectId(), null, request));
        } catch (Exception e) {
            log.warn("[对话抽取] AI 失败（fail-open，下次触发重试）: project={} session={} : {}",
                    req.getProjectId(), req.getSessionKey(), e.getMessage());
            return false;
        }
        String text = result == null || result.content() == null ? null : result.content().trim();
        if (text == null || text.isBlank()) {
            log.warn("[对话抽取] AI 输出为空（fail-open）: project={} session={}",
                    req.getProjectId(), req.getSessionKey());
            return false;
        }
        recordUsage(req, request, text, result, (int) (System.currentTimeMillis() - startMs));
        DigestResult parsed;
        try {
            parsed = parse(text);
        } catch (Exception e) {
            log.warn("[对话抽取] JSON 解析失败（fail-open，不落库）: project={} session={} : {}",
                    req.getProjectId(), req.getSessionKey(), e.getMessage());
            return false;
        }
        int applied = 0;
        // 先落声明事实（一次效果批次，失败整批回滚）；成功后再写关系/记忆，避免“事实失败但关系已变”。
        try {
            applied += applyClaims(req, parsed.claims(), knownFacts);
        } catch (Exception e) {
            log.warn("[对话抽取] 声明效果落地失败（整批回滚，本轮不写关系/记忆）: project={} session={} : {}",
                    req.getProjectId(), req.getSessionKey(), e.getMessage());
            return false;
        }
        applied += applyRelations(req, parsed.relations());
        applied += applyMemory(req, parsed.relations(), playerName);
        log.info("[对话抽取] 完成：project={} session={} 记忆/关系={} 声明={} 实际落库={}",
                req.getProjectId(), req.getSessionKey(), parsed.relations().size(),
                parsed.claims().size(), applied);
        return applied > 0;
    }

    // ==================== 输入 ====================

    /** 按链路读取最近 {@value #WINDOW} 条对话（user/assistant/group；system 事件保留首行）。 */
    List<String> readLines(DialogDigestRequest req) {
        try {
            if ("dialogue".equals(req.getChannel())) {
                if (dialogueMessageService == null) {
                    return List.of();
                }
                List<ActorDialogueMessage> rows = dialogueMessageService
                        .listEntities(req.getProjectId(), req.getSessionId());
                return tail(rows.stream().map(this::lineOf).filter(s -> s != null && !s.isBlank()).toList());
            }
            if (actorMessageRepository == null) {
                return List.of();
            }
            List<ActorMessage> rows = actorMessageRepository
                    .findByConversationIdOrderByIdAsc(req.getSessionId());
            return tail(rows.stream().map(this::lineOf).filter(s -> s != null && !s.isBlank()).toList());
        } catch (Exception e) {
            log.warn("[对话抽取] 读取对话失败（跳过本轮）: session={} : {}", req.getSessionKey(), e.getMessage());
            return List.of();
        }
    }

    /** 新对谈消息 → 文本行。 */
    private String lineOf(ActorDialogueMessage m) {
        if (m == null || m.getContent() == null || m.getContent().isBlank()) {
            return null;
        }
        String who = "user".equals(m.getRole()) ? "玩家" : nameOf(m.getCharacterRef());
        return (who == null ? "角色" : who) + "：" + oneLine(m.getContent());
    }

    /** 旧对话消息 → 文本行（系统事件保留，纯 OOC 跳过）。 */
    private String lineOf(ActorMessage m) {
        if (m == null || m.getContent() == null || m.getContent().isBlank()) {
            return null;
        }
        if ("system".equals(m.getRole())) {
            return "【世界事件】" + oneLine(m.getContent());
        }
        if ("user".equals(m.getRole())) {
            return "玩家：" + oneLine(m.getContent());
        }
        return nameOf(refOf(m.getCharacterId())) + "：" + oneLine(m.getContent());
    }

    private static List<String> tail(List<String> lines) {
        if (lines.size() <= WINDOW) {
            return lines;
        }
        return new ArrayList<>(lines.subList(lines.size() - WINDOW, lines.size()));
    }

    /** 已知事实（参与者各自 knownBy + 同地点，按 factKey 去重）。 */
    List<ActorWorldFactView> knownFacts(DialogDigestRequest req) {
        if (worldFactQuery == null) {
            return List.of();
        }
        long gs = req.getGameSecond() == null ? 0L : req.getGameSecond();
        Map<String, ActorWorldFactView> byKey = new LinkedHashMap<>();
        try {
            for (String ref : req.getParticipantRefs()) {
                for (var f : worldFactQuery.knownBy(req.getProjectId(), ref, gs, KNOWN_FACTS_PER_ACTOR)) {
                    byKey.putIfAbsent(f.getFactKey(), new ActorWorldFactView(f.getFactKey(), f.getFactType(),
                            f.getName(), f.getStateText(), f.getVisibility(), f.getAuthority()));
                }
            }
            if (req.getLocationName() != null && !req.getLocationName().isBlank()) {
                for (var f : worldFactQuery.atLocation(req.getProjectId(), req.getLocationName(), gs)) {
                    byKey.putIfAbsent(f.getFactKey(), new ActorWorldFactView(f.getFactKey(), f.getFactType(),
                            f.getName(), f.getStateText(), f.getVisibility(), f.getAuthority()));
                }
            }
        } catch (Exception e) {
            log.debug("[对话抽取] 已知事实读取失败（按空处理）: {}", e.getMessage());
        }
        return byKey.values().stream().limit(KNOWN_FACTS_CAP).toList();
    }

    /** 渲染 user 模板（占位符精确替换）。 */
    String renderUser(DialogDigestRequest req, Map<String, String> names, String playerName,
                      List<ActorWorldFactView> facts, List<String> lines) {
        String characters = names.entrySet().stream()
                .map(e -> "- " + e.getValue() + "（" + e.getKey() + "）")
                .reduce((a, b) -> a + "\n" + b).orElse("（无）");
        String factText = facts.isEmpty() ? "（无；本轮所有 STATEMENT 都只能写 knowledge，不得新建/更新事实）"
                : facts.stream()
                .map(f -> "- " + f.factKey() + " | " + nullSafe(f.name()) + " | " + nullSafe(f.stateText()))
                .reduce((a, b) -> a + "\n" + b).orElse("（无）");
        String dialog = String.join("\n", lines);
        String template = PromptResources.user("dialog_digest");
        return template
                .replace("{{characters_context}}", characters)
                .replace("{{player_name}}", nullSafe(playerName))
                .replace("{{location}}", nullSafe(req.getLocationName()))
                .replace("{{known_facts}}", factText)
                .replace("{{recent_dialog}}", dialog);
    }

    // ==================== 解析 ====================

    DigestResult parse(String text) throws Exception {
        JsonNode root = MAPPER.readTree(stripFence(text));
        List<RelationClaim> relations = new ArrayList<>();
        JsonNode rels = root.get("relations");
        if (rels != null && rels.isArray()) {
            for (JsonNode n : rels) {
                String ref = textOrNull(n.get("character_ref"));
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                relations.add(new RelationClaim(ref.trim(),
                        clamp(intOr0(n.get("affinity_delta"))), clamp(intOr0(n.get("trust_delta"))),
                        textOrNull(n.get("stage")), textOrNull(n.get("note")),
                        textOrNull(n.get("state_summary")), stringList(n.get("memory_points"))));
            }
        }
        List<DialogClaim> claims = new ArrayList<>();
        JsonNode arr = root.get("claims");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String type = textOrNull(n.get("claim_type"));
                String speaker = textOrNull(n.get("speaker_ref"));
                String state = textOrNull(n.get("state_text"));
                if (type == null || speaker == null || state == null || state.isBlank()) {
                    continue;
                }
                claims.add(new DialogClaim(type.trim().toUpperCase(), speaker.trim(),
                        textOrNull(n.get("subject_ref")), textOrNull(n.get("fact_key")),
                        textOrNull(n.get("name")), state.trim(), textOrNull(n.get("visibility")),
                        intOrNull(n.get("due_game_minutes")), intOrNull(n.get("confidence")),
                        intOrNull(n.get("qty"))));
            }
        }
        return new DigestResult(relations, claims);
    }

    // ==================== 落库 ====================

    /** 关系投影（仅新对谈；旧对话的关系由既有链路维护，避免双写）。 */
    int applyRelations(DialogDigestRequest req, List<RelationClaim> relations) {
        if (!req.isNewDialogue() || playerProfileService == null || req.getUserId() == null || relations.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (RelationClaim r : relations) {
            if (r.characterRef() == null || !req.getParticipantRefs().contains(r.characterRef())) {
                continue;
            }
            try {
                playerProfileService.applyConsolidatedRelation(req.getProjectId(), req.getUserId(),
                        r.characterRef(), r.affinityDelta(), r.trustDelta(), r.stage(), r.note(), r.stateSummary());
                n++;
            } catch (Exception e) {
                log.warn("[对话抽取] 关系写回失败（跳过）: ref={} : {}", r.characterRef(), e.getMessage());
            }
        }
        return n;
    }

    /** NPC 记忆「与玩家的活动/共识/承诺」。 */
    int applyMemory(DialogDigestRequest req, List<RelationClaim> relations, String playerName) {
        if (agentMemoryService == null || relations.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (RelationClaim r : relations) {
            if (r.memoryPoints() == null || r.memoryPoints().isEmpty()) {
                continue;
            }
            for (String point : r.memoryPoints()) {
                if (point == null || point.isBlank()) {
                    continue;
                }
                try {
                    agentMemoryService.recordObservation(req.getProjectId(), r.characterRef(),
                            "与" + playerName + "：" + point.trim(), MEMORY_IMPORTANCE);
                    n++;
                } catch (Exception e) {
                    log.warn("[对话抽取] 记忆写入失败（跳过）: ref={} : {}", r.characterRef(), e.getMessage());
                }
            }
        }
        return n;
    }

    /**
     * 把承诺/已知事实陈述组装成 effect ops，统一经 {@link EffectApplier} 落地。
     *
     * <p>一批对话的所有 fact 写入是一次效果批次：任一 op 失败整批回滚；
     * 事实日志的 {@code source_event_id} 取本轮 dialog 事件。
     * 未知陈述/引擎权威事实仍只写 knowledge，不碰世界真相。</p>
     */
    public int applyClaims(DialogDigestRequest req, List<DialogClaim> claims, List<ActorWorldFactView> knownFacts) {
        if (effectApplier == null) {
            return applyClaimsDirect(req, claims, knownFacts);
        }
        if (claims.isEmpty()) {
            return 0;
        }
        if (req.getEventId() == null) {
            log.warn("[对话抽取] 缺来源事件 ID，声明不落库（避免无因果的事实）: session={}", req.getSessionKey());
            return 0;
        }
        Set<String> knownKeys = new LinkedHashSet<>();
        for (ActorWorldFactView f : knownFacts) {
            knownKeys.add(f.factKey());
        }
        long gs = req.getGameSecond() == null ? 0L : req.getGameSecond();
        List<com.holzyn.actor.domain.world.adjudicate.model.EffectOp> ops = new ArrayList<>();
        List<ClaimEffect> effects = new ArrayList<>();
        List<KnowledgeOnly> knowledgeOnly = new ArrayList<>();
        List<GiftEffect> gifts = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            DialogClaim c = claims.get(i);
            if (c.speakerRef() == null || !req.getParticipantRefs().contains(c.speakerRef())) {
                continue;
            }
            if ("PROMISE".equals(c.claimType())) {
                String factKey = c.factKey() == null || c.factKey().isBlank()
                        ? "contract:" + shortHash(req.getSessionKey() + ":" + req.getLastMessageId() + ":" + i)
                        : c.factKey().trim();
                int due = c.dueGameMinutes() == null || c.dueGameMinutes() <= 0 ? DEFAULT_DUE_MINUTES : c.dueGameMinutes();
                ObjectNode body = promiseBody(req, c, factKey, due, gs);
                ops.add(new com.holzyn.actor.domain.world.adjudicate.model.EffectOp(
                        com.holzyn.actor.domain.world.effect.model.EffectOps.FACT_UPSERT,
                        body, idempotency(req, i)));
                effects.add(new ClaimEffect(c, ops.size() - 1, true));
            } else if ("STATEMENT".equals(c.claimType())) {
                String key = c.factKey() == null ? null : c.factKey().trim();
                if (key == null || key.isBlank()) {
                    continue;
                }
                Optional<com.holzyn.actor.domain.world.fact.entity.ActorWorldFact> existing =
                        worldFactQuery == null ? Optional.empty() : worldFactQuery.byKey(req.getProjectId(), key);
                boolean known = knownKeys.contains(key);
                if (!known) {
                    // 未进 Top-K：不更新世界真相，只补“某人说过”的 knowledge。
                    existing.ifPresent(f -> knowledgeOnly.add(new KnowledgeOnly(c, f.getId())));
                    continue;
                }
                if (existing.isEmpty()) {
                    continue;
                }
                var fact = existing.get();
                if ("engine".equalsIgnoreCase(fact.getAuthority())) {
                    knowledgeOnly.add(new KnowledgeOnly(c, fact.getId()));
                    continue;
                }
                ObjectNode body = statementBody(c, fact, gs);
                ops.add(new com.holzyn.actor.domain.world.adjudicate.model.EffectOp(
                        com.holzyn.actor.domain.world.effect.model.EffectOps.FACT_UPSERT,
                        body, idempotency(req, i)));
                effects.add(new ClaimEffect(c, ops.size() - 1, false));
            } else if (c.gift()) {
                // 对接：对话里说「给你」，东西就得真的过去。
                // 只用一次 inventory.transfer（幂等键带 :gift 后缀），与既有事实批次同一个
                // EffectApplier 事务：货没转成，整批回滚，不会出现「话说出去了、东西没动」。
                if (inventoryPort == null || (itemProperties != null && !itemProperties.isEnabled())) {
                    continue;
                }
                String itemKey = c.factKey() == null ? null : c.factKey().trim();
                String subject = c.subjectRef() == null ? null : c.subjectRef().trim();
                if (itemKey == null || itemKey.isBlank() || subject == null || subject.isBlank()
                        || subject.equals(c.speakerRef())
                        || !req.getParticipantRefs().contains(subject)) {
                    continue;
                }
                int qty = c.qty() == null || c.qty() <= 0 ? 1 : Math.min(99, c.qty());
                ObjectNode body = MAPPER.createObjectNode();
                body.put("itemKey", itemKey);
                body.put("qty", qty);
                body.put("from", c.speakerRef());
                body.put("to", subject);
                body.put("reason", "gift");
                ops.add(new com.holzyn.actor.domain.world.adjudicate.model.EffectOp(
                        com.holzyn.actor.domain.world.effect.model.EffectOps.INVENTORY_TRANSFER,
                        body, idempotency(req, i)));
                gifts.add(new GiftEffect(c, ops.size() - 1, itemKey, subject, qty));
            }
        }
        int applied = 0;
        com.holzyn.actor.domain.world.effect.model.EffectReport appliedReport = null;
        if (!ops.isEmpty()) {
            com.holzyn.actor.domain.world.effect.model.EffectReport report = effectApplier.applyWithReport(
                    ops, new com.holzyn.actor.domain.world.effect.model.EffectContext(
                            req.getProjectId(), req.getEventId(), null, null, null,
                            req.getLocationName(), gs));
            appliedReport = report;
            for (ClaimEffect e : effects) {
                Long factId = report.get(e.opIndex()) == null ? null : report.get(e.opIndex()).factId();
                if (factId == null) {
                    log.warn("[对话抽取] 效果未返回事实 ID（跳过 knowledge）: type={} key={}",
                            e.claim().claimType(), e.claim().factKey());
                    continue;
                }
                if (e.promise()) {
                    learnPromise(req, e.claim(), factId);
                } else {
                    learnStatement(req, e.claim(), factId);
                }
                applied++;
            }
        }
        for (KnowledgeOnly k : knowledgeOnly) {
            applied += learnStatement(req, k.claim(), k.factId());
        }
        // GIFT 没有 factId：判「真的转移了」看效果状态，再让双方各记一条
        for (GiftEffect g : gifts) {
            com.holzyn.actor.domain.world.effect.model.EffectResult r = reportOf(appliedReport, g.opIndex());
            if (r == null || !com.holzyn.actor.domain.world.effect.model.EffectResult.APPLIED
                    .equalsIgnoreCase(r.status())) {
                continue;
            }
            applied++;
            rememberGift(req, g);
        }
        return applied;
    }

    /** 一条赠予（说话者 → 收礼人，物品键 + 件数）。 */
    private record GiftEffect(DialogClaim claim, int opIndex, String itemKey, String toRef, int qty) {
    }

    /** 从效果报告里取第 i 条（报告为空/越界返回 null）。 */
    private static com.holzyn.actor.domain.world.effect.model.EffectResult reportOf(
            com.holzyn.actor.domain.world.effect.model.EffectReport report, int index) {
        return report == null ? null : report.get(index);
    }

    /**
     * 赠予双方各记一条观察记忆。
     *
     * <p>「他送了我一把刀」比「我买了三斤米」重要得多——礼物是关系事件，值得记 3 分。
     * 失败只 WARN：记忆写不进去不该把已经转移的物品回滚。</p>
     */
    private void rememberGift(DialogDigestRequest req, GiftEffect g) {
        if (agentMemoryService == null) {
            return;
        }
        try {
            agentMemoryService.recordObservation(req.getProjectId(), g.toRef(),
                    "收到了「" + nameOf(req.getProjectId(), g.claim().speakerRef()) + "」给的 "
                            + g.qty() + " 件" + g.itemKey(), MEMORY_IMPORTANCE);
        } catch (RuntimeException e) {
            log.warn("[对话抽取] 收礼记忆写入失败（不影响转移）：ref={} : {}", g.toRef(), e.getMessage());
        }
        try {
            agentMemoryService.recordObservation(req.getProjectId(), g.claim().speakerRef(),
                    "把 " + g.qty() + " 件" + g.itemKey() + "送给了"
                            + nameOf(req.getProjectId(), g.toRef()), MEMORY_IMPORTANCE);
        } catch (RuntimeException e) {
            log.warn("[对话抽取] 送礼记忆写入失败（不影响转移）：ref={} : {}",
                    g.claim().speakerRef(), e.getMessage());
        }
    }

    /** ref → 名字（查不到用 ref）。 */
    private String nameOf(Long projectId, String ref) {
        if (characterRepository == null || ref == null) {
            return ref;
        }
        Long id = com.holzyn.actor.domain.item.model.ItemRefs.idOf(ref);
        if (id == null) {
            return ref;
        }
        try {
            return characterRepository.findById(id)
                    .map(c -> c.getName() == null || c.getName().isBlank() ? ref : c.getName())
                    .orElse(ref);
        } catch (RuntimeException e) {
            return ref;
        }
    }

    /** 单条声明 ↔ effect 下标。 */
    private record ClaimEffect(DialogClaim claim, int opIndex, boolean promise) {
    }

    /** 只写 knowledge 的声明（未知事实/引擎权威）。 */
    private record KnowledgeOnly(DialogClaim claim, Long factId) {
    }

    private ObjectNode promiseBody(DialogDigestRequest req, DialogClaim c, String factKey, int due, long gs) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("factType", "contract");
        body.put("factKey", factKey);
        body.put("name", c.name() == null || c.name().isBlank() ? "承诺" : c.name().trim());
        body.put("stateText", c.stateText());
        body.put("attributes", attributesJson(req, c, gs + due * 60L));
        body.put("locationName", req.getLocationName());
        body.put("visibility", "private");
        body.put("authority", "brain");
        body.put("validFromGameSecond", gs);
        body.put("validToGameSecond", gs + due * 60L);
        body.put("importance", 3);
        return body;
    }

    private ObjectNode statementBody(DialogClaim c, com.holzyn.actor.domain.world.fact.entity.ActorWorldFact fact, long gs) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("factType", fact.getFactType());
        body.put("factKey", fact.getFactKey());
        body.put("name", c.name() == null || c.name().isBlank() ? fact.getName() : c.name().trim());
        body.put("stateText", c.stateText());
        body.put("attributes", fact.getAttributesJson());
        body.put("locationId", fact.getLocationId());
        body.put("locationName", fact.getLocationName());
        body.put("visibility", c.visibility() != null && !c.visibility().isBlank()
                ? c.visibility() : fact.getVisibility());
        body.put("authority", "brain");
        body.put("validFromGameSecond", fact.getValidFromGameSecond());
        body.put("validToGameSecond", fact.getValidToGameSecond());
        body.put("importance", 2);
        return body;
    }

    /** 承诺的双方 knowledge（own + told）。 */
    private int learnPromise(DialogDigestRequest req, DialogClaim c, Long factId) {
        int learned = 0;
        learned += learn(factId, req.getProjectId(), c.speakerRef(), "own", null, 100);
        if (c.subjectRef() != null && !c.subjectRef().isBlank()
                && !c.subjectRef().equals(c.speakerRef())) {
            learned += learn(factId, req.getProjectId(), c.subjectRef(), "told", c.speakerRef(), 100);
        }
        return learned;
    }

    /** 强声明：承诺落 contract 事实；陈述只更新已知事实，未知只写 knowledge。 */
    int applyClaimsDirect(DialogDigestRequest req, List<DialogClaim> claims, List<ActorWorldFactView> knownFacts) {
        if (worldFactService == null || claims.isEmpty()) {
            return 0;
        }
        if (req.getEventId() == null) {
            log.warn("[对话抽取] 缺来源事件 ID，声明不落库（避免无因果的事实）: session={}", req.getSessionKey());
            return 0;
        }
        Set<String> knownKeys = new LinkedHashSet<>();
        for (ActorWorldFactView f : knownFacts) {
            knownKeys.add(f.factKey());
        }
        long gs = req.getGameSecond() == null ? 0L : req.getGameSecond();
        int n = 0;
        for (int i = 0; i < claims.size(); i++) {
            DialogClaim c = claims.get(i);
            if (c.speakerRef() == null || !req.getParticipantRefs().contains(c.speakerRef())) {
                continue;
            }
            try {
                if ("PROMISE".equals(c.claimType())) {
                    n += applyPromise(req, c, i, gs);
                } else if ("STATEMENT".equals(c.claimType())) {
                    n += applyStatement(req, c, i, gs, knownKeys);
                } else if (c.gift()) {
                    n += applyGiftDirect(req, c, i, gs);
                }
            } catch (Exception e) {
                log.warn("[对话抽取] 声明落库失败（跳过该条）: type={} factKey={} : {}",
                        c.claimType(), c.factKey(), e.getMessage());
            }
        }
        return n;
    }

    /**
     * 赠予（无效果入口时的直连实现）：说话者 → 收礼人，真的挪物品。
     *
     * <p>只在 {@code EffectApplier} 未装配（单测/降级）时走到这里；正式链路走
     * {@link #applyClaims} 的效果批次，那边能保证「事实与物品同一事务」。</p>
     *
     * @return 1=转移成功；0=未装配/参数不全/货不够
     */
    private int applyGiftDirect(DialogDigestRequest req, DialogClaim c, int index, long gs) {
        if (inventoryPort == null || (itemProperties != null && !itemProperties.isEnabled())) {
            return 0;
        }
        String itemKey = c.factKey() == null ? null : c.factKey().trim();
        String subject = c.subjectRef() == null ? null : c.subjectRef().trim();
        if (itemKey == null || itemKey.isBlank() || subject == null || subject.isBlank()
                || subject.equals(c.speakerRef()) || !req.getParticipantRefs().contains(subject)) {
            return 0;
        }
        int qty = c.qty() == null || c.qty() <= 0 ? 1 : Math.min(99, c.qty());
        try {
            inventoryPort.transfer(req.getProjectId(), itemKey, qty, c.speakerRef(), subject,
                    "gift", req.getEventId(), gs, idempotency(req, index));
        } catch (RuntimeException e) {
            log.warn("[对话抽取] 赠予转移失败（跳过该条）: {} -> {} {} x{} : {}",
                    c.speakerRef(), subject, itemKey, qty, e.getMessage());
            return 0;
        }
        rememberGift(req, new GiftEffect(c, -1, itemKey, subject, qty));
        return 1;
    }

    /** 承诺 → contract 事实（private）+ 双方 knowledge。 */
    private int applyPromise(DialogDigestRequest req, DialogClaim c, int index, long gs) {        String factKey = c.factKey() == null || c.factKey().isBlank()
                ? "contract:" + shortHash(req.getSessionKey() + ":" + req.getLastMessageId() + ":" + index)
                : c.factKey().trim();
        int due = c.dueGameMinutes() == null || c.dueGameMinutes() <= 0 ? DEFAULT_DUE_MINUTES : c.dueGameMinutes();
        String attributes = attributesJson(req, c, gs + due * 60L);
        FactCommand cmd = new FactCommand(req.getProjectId(), "contract", factKey,
                c.name() == null || c.name().isBlank() ? "承诺" : c.name().trim(),
                c.stateText(), attributes, null, req.getLocationName(), "private", FactAuthority.BRAIN,
                gs, gs + due * 60L, gs, c.speakerRef(), req.getEventId(), 3,
                idempotency(req, index));
        FactChange change = worldFactService.upsert(cmd);
        if (change == null || change.fact() == null || change.fact().getId() == null) {
            return 0;
        }
        int learned = 0;
        learned += learn(change.fact().getId(), req.getProjectId(), c.speakerRef(), "own", null, 100);
        if (c.subjectRef() != null && !c.subjectRef().isBlank()
                && !c.subjectRef().equals(c.speakerRef())) {
            learned += learn(change.fact().getId(), req.getProjectId(), c.subjectRef(), "told", c.speakerRef(), 100);
        }
        log.info("[对话抽取] 承诺落事实: factKey={} 双方={}", factKey, learned);
        return 1;
    }

    /** 陈述 → 仅命中已知 factKey 才更新事实；否则只写在场者 knowledge。 */
    private int applyStatement(DialogDigestRequest req, DialogClaim c, int index, long gs, Set<String> knownKeys) {
        String key = c.factKey() == null ? null : c.factKey().trim();
        if (key == null || key.isBlank() || !knownKeys.contains(key)) {
            // 未知事实：不碰世界真相；若库里恰有同键（未进 Top-K），只补 knowledge
            if (worldFactQuery != null && key != null && !key.isBlank()) {
                Optional<com.holzyn.actor.domain.world.fact.entity.ActorWorldFact> existing =
                        worldFactQuery.byKey(req.getProjectId(), key);
                if (existing.isPresent() && existing.get().getId() != null) {
                    return learnStatement(req, c, existing.get().getId());
                }
            }
            log.debug("[对话抽取] 陈述未命中已知事实，仅保留在对话事件中: factKey={}", key);
            return 0;
        }
        Optional<com.holzyn.actor.domain.world.fact.entity.ActorWorldFact> existing =
                worldFactQuery == null ? Optional.empty() : worldFactQuery.byKey(req.getProjectId(), key);
        if (existing.isEmpty()) {
            return 0;
        }
        var fact = existing.get();
        if ("engine".equalsIgnoreCase(fact.getAuthority())) {
            log.debug("[对话抽取] 陈述命中引擎权威事实，后端不覆写: factKey={}", key);
            return learnStatement(req, c, fact.getId());
        }
        String visibility = c.visibility() != null && !c.visibility().isBlank()
                ? c.visibility() : fact.getVisibility();
        FactCommand cmd = new FactCommand(req.getProjectId(), fact.getFactType(), key,
                c.name() == null || c.name().isBlank() ? fact.getName() : c.name().trim(),
                c.stateText(), fact.getAttributesJson(), fact.getLocationId(), fact.getLocationName(),
                visibility, FactAuthority.BRAIN, fact.getValidFromGameSecond(), fact.getValidToGameSecond(),
                gs, c.speakerRef(), req.getEventId(), 2, idempotency(req, index));
        FactChange change = worldFactService.upsert(cmd);
        if (change == null || change.fact() == null || change.fact().getId() == null) {
            return 0;
        }
        return 1 + learnStatement(req, c, change.fact().getId());
    }

    /** 陈述的在场者 knowledge（told，置信度取声明值）。 */
    private int learnStatement(DialogDigestRequest req, DialogClaim c, Long factId) {
        if (factId == null || factKnowledgeService == null) {
            return 0;
        }
        int confidence = c.confidence() == null ? 80 : Math.max(0, Math.min(100, c.confidence()));
        int n = 0;
        for (String ref : req.getParticipantRefs()) {
            if (ref == null || ref.isBlank() || ref.equals(c.speakerRef())) {
                continue;
            }
            n += learn(factId, req.getProjectId(), ref, "told", c.speakerRef(), confidence);
        }
        return n;
    }

    private int learn(Long factId, Long projectId, String ref, String sourceType, String sourceRef, int confidence) {
        if (factKnowledgeService == null || factId == null || ref == null || ref.isBlank()) {
            return 0;
        }
        try {
            factKnowledgeService.learn(projectId, factId, ref, sourceType, sourceRef, confidence, null);
            return 1;
        } catch (Exception e) {
            log.warn("[对话抽取] knowledge 写入失败（跳过）: ref={} : {}", ref, e.getMessage());
            return 0;
        }
    }

    // ==================== 工具 ====================

    private String attributesJson(DialogDigestRequest req, DialogClaim c, long dueGameSecond) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            var parties = root.putArray("parties");
            parties.add(c.speakerRef());
            if (c.subjectRef() != null && !c.subjectRef().isBlank()) {
                parties.add(c.subjectRef());
            }
            root.put("terms", c.stateText());
            root.put("dueGameSecond", dueGameSecond);
            root.put("sessionKey", req.getSessionKey());
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    private static String idempotency(DialogDigestRequest req, int index) {
        String key = "dialog:" + shortHash(req.getSessionKey() == null ? "" : req.getSessionKey())
                + ":" + (req.getLastMessageId() == null ? 0L : req.getLastMessageId()) + ":c" + index;
        return key.length() <= 80 ? key : key.substring(0, 80);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String playerName(Long userId) {
        if (userId == null || playerProfileService == null) {
            return "玩家";
        }
        try {
            var p = playerProfileService.profileOf(userId);
            String name = p == null ? null : p.getName();
            return name == null || name.isBlank() ? "玩家" : name;
        } catch (Exception e) {
            return "玩家";
        }
    }

    private Map<String, String> namesOf(List<String> refs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String ref : refs) {
            map.put(ref, nameOf(ref));
        }
        return map;
    }

    private String nameOf(String ref) {
        if (ref == null || characterRepository == null) {
            return "角色";
        }
        Long id = NpcRefs.idOf(ref);
        if (id == null) {
            return "角色";
        }
        try {
            return characterRepository.findById(id).map(ActorCharacter::getName).orElse("角色");
        } catch (Exception e) {
            return "角色";
        }
    }

    private static String refOf(Long characterId) {
        return characterId == null ? null : NpcRefs.of(characterId);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String line = s.replaceAll("[\\r\\n]+", " ").trim();
        return line.length() <= 160 ? line : line.substring(0, 160) + "…";
    }

    private static String stripFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first >= 0 && last > first) {
                t = t.substring(first + 1, last).trim();
            }
        }
        return t;
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.isTextual() ? n.asText() : n.toString();
    }

    private static int intOr0(JsonNode n) {
        return n == null || !n.isNumber() ? 0 : n.asInt();
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asInt();
    }

    private static int clamp(int v) {
        return Math.max(-DELTA_CLAMP, Math.min(DELTA_CLAMP, v));
    }

    private static List<String> stringList(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) {
            if (e.isTextual() && !e.asText().isBlank()) {
                out.add(e.asText());
            }
        }
        return out;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private double specTemperature() {
        return PromptSpecRegistry.spec(SCENE).temperature();
    }

    private int specMaxTokens() {
        return PromptSpecRegistry.spec(SCENE).maxOutputTokens();
    }

    private void recordUsage(DialogDigestRequest req, AiChatRequest request, String answer,
                             AiChatResult result, int elapsedMs) {
        if (usageLogService == null) {
            return;
        }
        try {
            Long npcId = req.getParticipantRefs().isEmpty() ? null : NpcRefs.idOf(req.getParticipantRefs().get(0));
            usageLogService.record(req.getUserId(), req.getProjectId(), npcId,
                    result == null ? null : result.providerId(), result == null ? null : result.model(),
                    SCENE, result == null ? null : result.promptTokens(),
                    result == null ? null : result.completionTokens(),
                    result == null ? null : result.cacheHitTokens(),
                    result == null ? null : result.cacheMissTokens(), elapsedMs);
        } catch (Exception e) {
            log.debug("[对话抽取] 用量记账失败（忽略）: {}", e.getMessage());
        }
    }

    /** 解析结果。 */
    record DigestResult(List<RelationClaim> relations, List<DialogClaim> claims) {
    }

    /** 关系+记忆候选。 */
    record RelationClaim(String characterRef, int affinityDelta, int trustDelta, String stage,
                         String note, String stateSummary, List<String> memoryPoints) {
    }

    /** 事实清单视图（避免把实体带出读模型）。 */
    record ActorWorldFactView(String factKey, String factType, String name, String stateText,
                              String visibility, String authority) {
    }
}
