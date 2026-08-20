package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.action.service.ActionEngine;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.memory.service.MemoryService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 群聊编排服务（P2 阶段一核心，P4「无玩家轮次」预留）。
 * <p>职责：为群聊会话实现「AI 按发言欲望选发言人」的编排与逐角色流式生成——
 * 玩家一条消息后可连续多角色回复（上限默认 5，用户可在前端「每轮回复上限」配置 1~20）：每轮由调度模型评估各成员发言欲望
 * 选出 desire 最高者（≥阈值才接话），生成该角色回复后继续评估剩余角色，直至无人接话或达上限。
 * 未配置 API 或 AI 失败时按成员顺序轮询兜底；前端可传 forceCharacterId 指定首轮发言人覆盖。
 * SSE 事件：message-start（新角色消息）/ token（增量）/ done（角色完成）/ 最终 done（整轮结束）。</p>
 * <p>所属模块：service/conversation（对话子域-群聊编排）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    /** 发言欲望阈值：AI 评估 desire >= 该值才判定「想接话」 */
    private static final int DESIRE_THRESHOLD = 3;

    /** 群聊上下文窗口：最多纳入最近 N 条消息（控制 token 成本） */
    private static final int HISTORY_WINDOW = 20;

    /** 单角色回复最大输出 token */
    private static final int REPLY_MAX_TOKENS = 1024;

    /** 发言人评估输出最大 token（只需一个 JSON） */
    private static final int DESIRE_MAX_TOKENS = 512;

    /** AI 调度评估最大重试次数（模型偶发空输出/JSON 损坏时重试） */
    private static final int MAX_RETRY = 2;

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorMessageRepository messageRepository;
    private final AiProviderRouter aiProviderRouter;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    private final ActionEngine actionEngine;
    private final ObjectMapper objectMapper;
    private final GroupChatConfigService groupChatConfigService;
    private final ConversationContextService conversationContextService;
    private final MemoryService memoryService;
    private final LocalAccountService localAccountService;

    /**
     * 每轮连续回复上限（读取用户配置，默认 5；用户可在前端「每轮回复上限」修改）。
     *
     * @param userId 归属用户 ID
     * @return 最大回复数
     */
    public int maxReplies(Long userId) {
        return groupChatConfigService.getMaxReplies(userId);
    }

    /**
     * 执行一轮群聊编排：评估发言人 → 逐角色流式生成 → SSE 推送。
     * <p>被 ChatService.stream() 在群聊模式下调用；forceCharacterId 指定首轮发言人覆盖。</p>
     *
     * @param emitter          SSE 发射器
     * @param conv             会话实体
     * @param userId           归属用户 ID（虚拟线程内无 SecurityContext，必须显式传入）
     * @param forceCharacterId 指定首轮发言人（可空）
     * @param alive            连接存活标志：SSE 超时/完成/出错后置 false，生成线程据此安静终止
     */
    public void runRound(SseEmitter emitter, ActorConversation conv, Long userId, Long forceCharacterId,
                         AtomicBoolean alive) {
        try {
            long roundStart = System.currentTimeMillis();
            List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
            if (members.isEmpty()) {
                throw new BizException(400, "群聊没有成员，请先添加角色");
            }
            int maxReplies = groupChatConfigService.getMaxReplies(userId);
            log.info("[群聊] 本轮开始：会话={} 成员={} 上限={} 条", conv.getId(), members.size(), maxReplies);
            Set<Long> used = new HashSet<>();
            // 成员名集合：用于剥离模型误输出的「角色名：」前缀（角色名掺杂防护）
            Set<String> memberNames = members.stream()
                    .map(m -> characterName(m.getCharacterId()))
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            int replies = 0;
            boolean first = true;
            while (replies < maxReplies && alive.get()) {
                Long next = pickNext(conv, userId, members, used, first ? forceCharacterId : null);
                if (next == null || !alive.get()) {
                    break; // AI 判定无人想接话 / 候选已用尽 / 连接已断开
                }
                first = false;
                used.add(next);
                replies++;
                if (!generateOne(emitter, conv, next, userId, alive, memberNames)) {
                    break; // 单角色生成失败已推送 error，结束本轮
                }
            }
            // 更新会话最后消息时间；对话完成后自动评估高重要度角色的行动（after_dialog 触发源，异步）
            conv.setLastMessageAt(LocalDateTime.now());
            conversationRepository.save(conv);
            actionEngine.evaluateAfterDialogAsync(conv.getId(), userId);
            // P4-1：整轮结束后异步抽取长期记忆（关键事实 + 会话摘要，内存开关关闭时自动跳过）
            memoryService.extractAfterRoundAsync(conv.getId(), userId);
            log.info("[群聊] 本轮结束：会话={} 共 {} 条回复，耗时={}ms", conv.getId(), replies, System.currentTimeMillis() - roundStart);
            if (alive.get()) {
                emitter.send(SseEmitter.event().name("done").data(Map.of("group", true, "count", replies)));
                emitter.complete();
            }
        } catch (Exception e) {
            log.warn("群聊编排失败: conv={} : {}", conv.getId(), e.getMessage());
            sendError(emitter, friendlyError(e));
        }
    }

    /**
     * 生成单个角色的回复：建占位消息 → 流式调用 AI → 回填落库 + 用量记录 + SSE 事件。
     *
     * @param emitter     SSE 发射器
     * @param conv        会话实体
     * @param characterId 发言角色 ID
     * @param userId      归属用户 ID
     * @param alive       连接存活标志（超时/关闭后安静终止）
     * @param memberNames 群聊成员名集合（用于剥离模型误输出的「角色名：」前缀）
     * @return true 成功；false 失败（已推送 error 或连接已断开）
     */
    private boolean generateOne(SseEmitter emitter, ActorConversation conv, Long characterId, Long userId,
                                AtomicBoolean alive, Set<String> memberNames) {
        // 建 streaming 占位消息（带 character_id，群聊角色标识）
        ActorMessage pending = new ActorMessage();
        pending.setConversationId(conv.getId());
        pending.setCharacterId(characterId);
        pending.setRole("assistant");
        pending.setType("text");
        pending.setStatus("streaming");
        messageRepository.save(pending);

        String charName = characterName(characterId);
        if (!alive.get()) {
            // 连接已关闭（超时/前端断开）：不落库占位、不推送，安静结束本轮
            log.info("[群聊] 连接已关闭，跳过角色={}（ID={}）的生成：会话={}", charName, characterId, conv.getId());
            pending.setStatus("failed");
            pending.setContent("（已中断）");
            messageRepository.save(pending);
            return false;
        }
        log.info("[群聊] 角色={}（ID={}）开始回复：会话={}", charName, characterId, conv.getId());
        try {
            emitter.send(SseEmitter.event().name("message-start").data(Map.of(
                    "assistantMessageId", pending.getId(), "characterId", characterId, "characterName", charName)));
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException：emitter 已 complete（如超时触发），按连接断开处理终止本轮
            throw new RuntimeException("SSE 连接已断开", e);
        }

        long startMs = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        int[] usage = new int[4]; // 0=prompt 1=completion 2=cacheHit 3=cacheMiss
        try {
            List<AiChatRequest.ChatMessage> msgs = buildCharacterMessages(conv, characterId, pending, userId);
            // 流式调用（项目级优先/用户级回退默认 API；未配置时抛 AiCallException 由调用方兜底）
            aiProviderRouter.chatCompletionStream(userId, conv.getProjectId(), null,
                    new AiChatRequest(null, msgs, 0.7, REPLY_MAX_TOKENS),
                    delta -> {
                        if (!alive.get()) {
                            // 连接已关闭（SSE 超时/前端断开）：立即终止，不再接收剩余 token 白耗
                            throw new RuntimeException("SSE 连接已断开");
                        }
                        full.append(delta);
                        sendToken(emitter, delta, characterId);
                    },
                    usageInfo -> {
                        usage[0] = usageInfo.promptTokens();
                        usage[1] = usageInfo.completionTokens();
                        usage[2] = usageInfo.cacheHitTokens();
                        usage[3] = usageInfo.cacheMissTokens();
                    });

            // 生成完成：剥离模型误输出的「角色名：」前缀（角色名掺杂防护）后回填落库
            String cleaned = stripSpeakerPrefix(full.toString(), memberNames);
            pending.setContent(cleaned);
            pending.setStatus("done");
            pending.setTokenIn(usage[0]);
            pending.setTokenOut(usage[1]);
            pending.setCacheHitTokens(usage[2]);
            pending.setCacheMissTokens(usage[3]);
            messageRepository.save(pending);
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "assistantMessageId", pending.getId(), "characterId", characterId,
                    "content", cleaned, "tokenIn", usage[0], "tokenOut", usage[1],
                    "cacheHitTokens", usage[2], "cacheMissTokens", usage[3])));
            log.info("[群聊] 角色={}：{}", charName, cleaned);
            log.info("[群聊] 角色={} 回复完成：耗时={}ms tokens={}/{}", charName,
                    System.currentTimeMillis() - startMs, usage[0], usage[1]);
            // 用量日志（scene=dialog，带 project_id）
            usageLogService.record(userId, conv.getProjectId(), characterId, null, null, "dialog",
                    usage[0], usage[1], usage[2], usage[3], (int) (System.currentTimeMillis() - startMs));
            return true;
        } catch (Exception e) {
            // 连接断开（SSE 超时/客户端离开）：安静结束本轮，不报错不推送（前端已离开）
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                pending.setStatus("failed");
                pending.setContent(full.length() > 0 ? full.toString() : "（已中断）");
                messageRepository.save(pending);
                return false;
            }
            log.warn("群聊角色生成失败: conv={} char={} : {}", conv.getId(), characterId, e.getMessage());
            pending.setStatus("failed");
            pending.setContent(full.length() > 0 ? full.toString() : null);
            messageRepository.save(pending);
            sendError(emitter, friendlyError(e));
            return false;
        }
    }

    /**
     * 选择下一位发言人：指定覆盖优先 → AI 发言欲望评估 → 失败时轮询兜底。
     *
     * @param conv             会话实体
     * @param userId           归属用户 ID
     * @param members          全体成员
     * @param used             已发言角色集合
     * @param forceCharacterId 指定覆盖（仅首轮）
     * @return 发言人角色 ID；无人接话返回 null
     */
    private Long pickNext(ActorConversation conv, Long userId, List<ActorConversationMember> members,
                          Set<Long> used, Long forceCharacterId) {
        // 允许选择已发言角色：真实社交中角色可被点名后再次接话（连续/多次发言），
        // 候选不再排除已发言成员，由调度模型结合上下文判断谁最自然接话；总条数由「每轮回复上限」控制
        List<ActorConversationMember> candidates = members;
        if (candidates.isEmpty()) {
            return null;
        }
        // 指定发言人覆盖（首轮）：直接返回该角色（必须是成员）
        if (forceCharacterId != null) {
            log.info("[群聊-调度] 指定发言人覆盖：角色ID={}", forceCharacterId);
            return candidates.stream()
                    .filter(m -> m.getCharacterId().equals(forceCharacterId))
                    .map(ActorConversationMember::getCharacterId)
                    .findFirst().orElse(null);
        }
        try {
            Map<String, Object> decision = decideSpeaker(conv, userId, members, candidates);
            if (decision != null && (int) decision.get("desire") >= DESIRE_THRESHOLD) {
                log.info("[群聊-调度] 发言欲望评估：选中角色ID={} desire={}（阈值{}）理由={}",
                        decision.get("characterId"), decision.get("desire"), DESIRE_THRESHOLD, decision.get("reason"));
                return (Long) decision.get("characterId");
            }
            if (decision != null) {
                log.info("[群聊-调度] 无人想接话：最高 desire={} < 阈值{}，本轮结束", decision.get("desire"), DESIRE_THRESHOLD);
            }
            // AI 评估失败（重试后仍失败，输出为空/解析失败）：轮询兜底选下一位，保证群聊继续接话而不中断
            log.info("[群聊-调度] AI 评估失败（输出为空/解析失败），转轮询兜底选下一位：角色ID={}", candidates.get(0).getCharacterId());
            return candidates.get(0).getCharacterId();
        } catch (Exception e) {
            // AI 不可用 / 评估失败：按成员顺序轮询兜底（保证群聊可用）
            log.info("[群聊-调度] AI 评估不可用，转轮询兜底：候选第一位角色ID={}", candidates.get(0).getCharacterId());
            log.warn("群聊发言人评估失败，转轮询兜底: conv={} : {}", conv.getId(), e.getMessage());
            return candidates.get(0).getCharacterId();
        }
    }

    /**
     * AI 评估各成员发言欲望，输出 {characterId, desire, reason} 选最高者。
     *
     * @param conv       会话实体
     * @param userId     归属用户 ID
     * @param members    全体成员
     * @param candidates 全体成员（允许已发言角色再次接话）
     * @return 决策 Map；解析失败或选中无效返回 null
     */
    private Map<String, Object> decideSpeaker(ActorConversation conv, Long userId,
                                              List<ActorConversationMember> members,
                                              List<ActorConversationMember> candidates) {
        String membersSummary = buildMembersSummary(conv, members);
        String context = buildContext(conv);
        String prompt = promptService.buildGroupOrchestratorPrompt(userId, conv.getProjectId(), membersSummary, context);
        // 显式声明未发言候选：模型摘要/上下文包含全部成员，若不加约束容易选中已发言角色，
        // 这里把候选角色 ID+名字追加进 prompt，要求 characterId 只能在候选内，降低调度失败率
        if (!members.isEmpty()) {
            String memberDesc = members.stream()
                    .map(m -> "角色ID " + m.getCharacterId() + "（" + characterName(m.getCharacterId()) + "）")
                    .collect(java.util.stream.Collectors.joining("、"));
            prompt = prompt + "\n—— 成员角色ID列表（characterId 只能从其中选择；允许选择已发言角色，剧情连贯时同一角色可继续接话）：——\n"
                    + memberDesc + "\n";
        }
        // AI 评估支持重试：模型偶发空输出/JSON 损坏时重新评估，提高调度成功率
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.7, DESIRE_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, conv.getProjectId(), null, req);
                usageLogService.record(userId, conv.getProjectId(), null, result.providerId(), result.model(), "dialog",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);

                String json = JsonUtil.extractJson(result.content());
                if (json == null) {
                    log.warn("[群聊-调度] 第 {} 次 AI 输出无法提取 JSON：原始输出（前 200 字）={}",
                            attempt, truncate(result.content(), 200));
                    continue;
                }
                JsonNode node = objectMapper.readTree(json);
                long cid = node.path("characterId").asLong(-1);
                int desire = node.path("desire").asInt(1);
                boolean valid = candidates.stream().anyMatch(m -> m.getCharacterId().equals(cid));
                if (!valid) {
                    log.warn("[群聊-调度] 第 {} 次 AI 选中角色ID={} 不在未发言候选内，判定无效；候选={}",
                            attempt, cid, candidates.stream().map(m -> String.valueOf(m.getCharacterId())).toList());
                    continue;
                }
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("characterId", cid);
                decision.put("desire", desire);
                decision.put("reason", node.path("reason").asText(""));
                return decision;
            } catch (Exception e) {
                log.warn("[群聊-调度] 第 {} 次评估失败: {}", attempt, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 组装单个角色的对话消息：该角色 system_prompt + 知识/环境上下文注入 + 最近上下文（其他角色发言带名字前缀）。
     *
     * @param conv        会话实体
     * @param characterId 发言角色 ID
     * @param skip        跳过占位消息
     * @param userId      归属用户 ID（RAG/embedding 凭据归属）
     * @return 消息序列
     */
    private List<AiChatRequest.ChatMessage> buildCharacterMessages(ActorConversation conv, Long characterId,
                                                                   ActorMessage skip, Long userId) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", resolveCharacterSystemPrompt(characterId)));
        // 本地账户「NPC 个性化档案」注入（与单聊一致）：让所有在场角色基于对用户的了解回应玩家
        String npcProfile = localAccountService.renderNpcProfile();
        if (!npcProfile.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", npcProfile));
        }
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        // 最近一条用户消息（作为 RAG 检索 query）
        String lastUserText = history.stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank())
                .reduce((first, second) -> second)
                .map(ActorMessage::getContent)
                .orElse(null);
        // P3/P4：注入记忆 + 知识 + 环境上下文（项目级 + 当前角色级知识 + 人群环境摘要；优先级：角色卡 > 记忆 > RAG > 人群环境）
        String context = conversationContextService.buildContext(userId, conv.getProjectId(),
                characterId, lastUserText);
        if (!context.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", context));
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        // P4-1 S2 窗口压缩联动：历史超窗时，超窗部分不再直接注入，以「会话摘要记忆」承接（当前发言角色的最近摘要）
        if (from > 0) {
            String past = memoryService.conversationSummaryContext(conv.getProjectId(), characterId);
            if (!past.isBlank()) {
                messages.add(new AiChatRequest.ChatMessage("system", "【过往对话摘要】" + past));
            }
        }
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (skip != null && m.getId().equals(skip.getId())) {
                continue;
            }
            if ("user".equals(m.getRole())) {
                messages.add(new AiChatRequest.ChatMessage("user", m.getContent() == null ? "" : m.getContent()));
            } else if ("event".equals(m.getType()) && m.getContent() != null && !m.getContent().isBlank()) {
                // 世界事件以系统消息注入，所有角色可见
                messages.add(new AiChatRequest.ChatMessage("system", "【世界事件】" + m.getContent()));
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                String name = characterName(m.getCharacterId());
                String content = (name == null ? "" : name + "：")
                        + (m.getContent() == null ? "" : m.getContent());
                messages.add(new AiChatRequest.ChatMessage("assistant", content));
            }
        }
        // 关键：显式指定当前发言者 + 强调接话/回应上一发言（群聊自然衔接，避免各自自说自话）
        String speakerName = characterName(characterId);
        String speaker = speakerName == null ? "你" : speakerName;
        messages.add(new AiChatRequest.ChatMessage("system",
                "当前轮到【" + speaker + "】发言。请先仔细阅读完整对话历史，特别关注上一位发言者（或玩家）刚说的话，"
                    + "像真实社交对话一样自然地接话、回应、追问、反驳或补充，随机应变，不要自说自话，不要机械重复已经讨论过的问题。"
                    + "对话历史中【角色名：】前缀代表其他角色的发言，请针对他们的最新发言做出针对性回应。"
                    + "严格只用第一人称扮演【" + speaker + "】：只说你自己的话，不要替其他角色发言，不要代答，"
                    + "不要转述或标注其他角色的台词。回复时直接以第一人称说出自己的话，开头不要带任何角色名字、方括号标注或『某某说：』之类的前缀。"));
        return messages;
    }

    /**
     * 生成全体成员角色摘要（供群聊编排系统提示）。
     *
     * @param conv    会话实体
     * @param members 成员列表
     * @return 成员摘要文本
     */
    private String buildMembersSummary(ActorConversation conv, List<ActorConversationMember> members) {
        StringBuilder sb = new StringBuilder();
        for (ActorConversationMember m : members) {
            ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
            if (ch == null) {
                continue;
            }
            sb.append("角色ID ").append(ch.getId()).append("：").append(ch.getName())
                    .append(ch.getTitle() == null || ch.getTitle().isBlank() ? "" : "（" + ch.getTitle() + "）")
                    .append("；重要度 ").append(ch.getImportance() == null ? 1 : ch.getImportance());
            // 从最新角色卡提取人设摘要（性格特质/目标/行为模式）
            String summary = cardRepository.findTopByCharacterIdOrderByVersionDesc(ch.getId())
                    .map(ActorCharacterCard::getPersonaJson).orElse(null);
            if (summary != null && !summary.isBlank()) {
                try {
                    JsonNode p = objectMapper.readTree(summary);
                    List<String> bits = new ArrayList<>();
                    appendJoined(bits, p.path("personality").path("traits"));
                    appendJoined(bits, p.path("background").path("goals"));
                    appendJoined(bits, p.path("behaviorPatterns"));
                    if (!bits.isEmpty()) {
                        sb.append("；人设：").append(String.join("、", bits));
                    }
                } catch (Exception ignored) {
                    // 角色卡解析失败时忽略人设摘要
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成最近对话上下文（供编排系统提示）。
     *
     * @param conv 会话实体
     * @return 上下文文本
     */
    private String buildContext(ActorConversation conv) {
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if ("user".equals(m.getRole())) {
                sb.append("玩家：").append(m.getContent() == null ? "" : m.getContent()).append("\n");
            } else if ("event".equals(m.getType()) && m.getContent() != null) {
                sb.append("【世界事件】").append(m.getContent()).append("\n");
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus()) && m.getContent() != null) {
                sb.append(characterName(m.getCharacterId())).append("：").append(m.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 解析角色对话系统 Prompt（最新角色卡渲染的 system_prompt，无卡时降级）。
     *
     * @param characterId 角色 ID
     * @return 系统提示词
     */
    private String resolveCharacterSystemPrompt(Long characterId) {
        return cardRepository.findTopByCharacterIdOrderByVersionDesc(characterId)
                .map(ActorCharacterCard::getSystemPrompt)
                .filter(s -> s != null && !s.isBlank())
                .orElse("你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。");
    }

    /**
     * 角色名（用于消息前缀与 SSE 事件）。
     *
     * @param characterId 角色 ID
     * @return 角色名（找不到时返回 null）
     */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return null;
        }
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * 追加数组字段到摘要片段（去重保护）。
     *
     * @param bits 片段集合
     * @param node 数组节点
     */
    private void appendJoined(List<String> bits, JsonNode node) {
        if (node.isArray()) {
            node.forEach(n -> {
                String v = n.asText("");
                if (!v.isBlank() && bits.size() < 6) {
                    bits.add(v);
                }
            });
        }
    }

    /**
     * SSE token 事件推送（带角色 ID）。
     *
     * @param emitter     SSE 发射器
     * @param delta       增量文本
     * @param characterId 角色 ID
     */
    private void sendToken(SseEmitter emitter, String delta, Long characterId) {
        try {
            emitter.send(SseEmitter.event().name("token").data(Map.of("delta", delta, "characterId", characterId)));
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException：emitter 已 complete（连接关闭），按断开处理终止本轮
            throw new RuntimeException("SSE 连接已断开", e);
        }
    }

    /**
     * SSE error 事件推送并结束流。
     *
     * @param emitter SSE 发射器
     * @param message 中文错误提示
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
        } catch (Exception ignored) {
            // 前端已断开时忽略推送失败
        }
        emitter.complete();
    }

    /**
     * 生成失败的用户友好提示（区分 AI 调用错误与业务错误）。
     *
     * @param e 异常
     * @return 中文提示
     */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) {
            return be.getMessage();
        }
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) {
            return ae.getMessage();
        }
        return "群聊生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
    }

    /**
     * 剥离模型误输出的「角色名：」前缀（角色名掺杂防护）。
     * 群聊 system prompt 已要求输出不带角色名前缀，但模型偶发仍输出「XX：」或重复前缀
     * （如「棉花糖：棉花糖：哇！...」），这里对生成结果循环剥离「成员名：」形式的开头，最多剥 3 层。
     *
     * @param text  模型原始输出
     * @param names 群聊成员名集合
     * @return 剥离前缀后的文本（无前缀时原样返回）
     */
    private String stripSpeakerPrefix(String text, Set<String> names) {
        if (text == null || names == null || names.isEmpty()) {
            return text;
        }
        String s = text;
        for (int i = 0; i < 3; i++) {
            String before = s;
            for (String name : names) {
                if (s.startsWith(name)) {
                    String after = s.substring(name.length());
                    if (after.startsWith("：") || after.startsWith(":")) {
                        s = after.substring(1).trim();
                        break;
                    }
                }
            }
            if (before.equals(s)) {
                break; // 本轮无前缀可剥，提前结束
            }
        }
        return s;
    }

    /** 字符串截断（null 安全，用于日志输出） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
