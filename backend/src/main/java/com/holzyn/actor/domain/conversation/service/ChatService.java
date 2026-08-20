package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.domain.action.service.ActionEngine;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.memory.service.MemoryService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话编排服务（A-C4 核心，P1-4 单聊 SSE + P2 群聊/世界事件）。
 * <p>职责：实现设计文档 §6.2 SSE 流式对话链路——
 * 单聊：POST 消息先落库（user + assistant 占位 streaming），GET /stream 以 SseEmitter
 * 触发 AI 流式生成并逐 token 推送，完成后回填内容与用量；
 * 群聊：POST 消息仅落 user 消息，GET /stream 委托 GroupChatService 按发言欲望逐角色生成；
 * 世界事件（单聊）注入后自动生成角色回应。同一会话并发生成以内存标志互斥。</p>
 * <p>所属模块：service/conversation（对话子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** SSE 事件超时（毫秒）：300 秒无写入则断开（群聊回复上限可调到 1~20，且 deepseek-v4-flash 单条较慢） */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** 保留的历史消息轮数（控制 token 成本） */
    private static final int HISTORY_WINDOW = 20;

    /** 虚拟线程执行器：流式生成在虚拟线程运行，避免占用请求线程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 会话级生成互斥标志（防止同一会话并发多次生成） */
    private final ConcurrentHashMap<Long, AtomicBoolean> generating = new ConcurrentHashMap<>();

    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorMessageRepository messageRepository;
    private final AiProviderRouter aiProviderRouter;
    private final CurrentUserProvider currentUserProvider;
    private final GroupChatService groupChatService;
    private final UsageLogService usageLogService;
    private final ActionEngine actionEngine;
    private final ConversationContextService conversationContextService;
    private final MemoryService memoryService;
    private final LocalAccountService localAccountService;

    /**
     * 发送消息：单聊写入 user + assistant 占位；群聊仅写 user 消息（发言人由编排阶段动态确定）。
     *
     * @param conversationId   会话主键
     * @param content          用户消息内容
     * @param forceCharacterId 群聊指定发言人（可空）
     * @return Map：userMessageId / assistantMessageId（单聊）/ mode / group / forceCharacterId
     */
    @Transactional
    public Map<String, Object> sendMessage(Long conversationId, String content, Long forceCharacterId) {
        ActorConversation conv = conversationService.requireOwned(conversationId);
        // 会话结束兜底（懒检查）：距上条消息超过阈值（默认 30 分钟）视为上次会话已结束，
        // 在本轮开始前异步补提一次完整记忆（事实+摘要，门控过滤寒暄）——须在更新 lastMessageAt 之前调用
        memoryService.maybeExtractAfterIdleAsync(conv, currentUserProvider.currentUserId());
        // 清理历史遗留的 streaming 残留（如页面刷新中断），置为 failed 避免阻塞后续
        messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(m -> "assistant".equals(m.getRole()) && "streaming".equals(m.getStatus()))
                .forEach(m -> {
                    m.setStatus("failed");
                    m.setContent((m.getContent() == null ? "" : m.getContent()) + "（已中断）");
                    messageRepository.save(m);
                });

        ActorMessage user = new ActorMessage();
        user.setConversationId(conversationId);
        user.setRole("user");
        user.setType("text");
        user.setContent(content);
        user.setStatus("done");
        messageRepository.save(user);

        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        if ("group".equals(conv.getMode())) {
            // 群聊：不预建 assistant 占位，发言人由 GroupChatService 在流式阶段动态确定；
            // 注意：forceCharacterId 可能为 null，Map.of 不允许 null 值，需用可变 Map 组装
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userMessageId", user.getId());
            result.put("mode", "group");
            result.put("group", true);
            result.put("forceCharacterId", forceCharacterId);
            return result;
        }
        // 单聊：预建 assistant 占位（streaming），由 stream 触发生成
        ActorMessage assistant = new ActorMessage();
        assistant.setConversationId(conversationId);
        assistant.setRole("assistant");
        assistant.setType("text");
        assistant.setContent("");
        assistant.setStatus("streaming");
        // 单聊占位归属会话首个成员角色：否则 SSE token 事件 Map.of(characterId=null) 会抛 NPE，且角色归属缺失
        memberRepository.findByConversationId(conversationId).stream()
                .findFirst()
                .ifPresent(m -> assistant.setCharacterId(m.getCharacterId()));
        messageRepository.save(assistant);
        return Map.of("userMessageId", user.getId(), "assistantMessageId", assistant.getId(),
                "mode", "single", "group", false);
    }

    /**
     * AI 生成对话专属标题（前端「AI 重写」按钮调用）。
     * <p>根据角色名、对话所在地与世界时间，调用项目默认 AI 生成简短有画面感的中文标题；
     * 未配置可用 AI API 或生成结果异常时抛 BizException，由前端回退到规则模板标题。</p>
     *
     * @param projectId    项目 ID（AI 配置按 项目级优先/用户级回退）
     * @param characterId  参与角色 ID（可空）
     * @param location     对话所在地（可空，空=远程通讯）
     * @param gameTimeText 世界时间快照（可空）
     * @return 生成的标题（已清洗引号/换行，≤100 字）
     */
    public String generateTitle(Long projectId, Long characterId, String location, String gameTimeText) {
        Long userId = currentUserProvider.currentUserId();
        String charName = characterId == null ? null
                : characterRepository.findById(characterId).map(ActorCharacter::getName).orElse(null);
        String locText = (location == null || location.isBlank())
                ? "通过手机等远程通讯软件进行对话（远程通话/文字消息）" : location;
        List<AiChatRequest.ChatMessage> messages = List.of(
                new AiChatRequest.ChatMessage("system",
                        "你是一位善于提炼对话主题的世界观拟题助手。请根据给定的角色、对话所在地与世界时间，"
                                + "生成一个简短、有画面感、贴合世界设定的中文对话标题（不超过 20 字）。"
                                + "只输出标题本身，不要引号、不要标点修饰、不要任何解释。"),
                new AiChatRequest.ChatMessage("user",
                        "角色：" + (charName == null ? "（未知角色）" : charName)
                                + "\n对话所在地：" + locText
                                + "\n世界时间：" + (gameTimeText == null || gameTimeText.isBlank() ? "未指定" : gameTimeText)
                                + "\n请生成对话标题。"));
        long startMs = System.currentTimeMillis();
        try {
            AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null,
                    new AiChatRequest(null, messages, 0.8, 64));
            // 记录用量（scene=title_gen，与对话/角色卡等场景并列）
            usageLogService.record(userId, projectId, characterId, result.providerId(), result.model(),
                    "title_gen", result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));
            String title = result.content() == null ? "" : result.content();
            // 清洗：去掉首尾引号/书名号/花括号，压缩多余空白与换行
            title = title.replaceAll("^[\"'“”‘’「『【《]+|[\"'“”‘’」』】》]+$", "")
                    .replaceAll("[\\r\\n]+", " ").trim();
            if (title.isBlank()) {
                throw new BizException(400, "AI 生成标题为空，请重试或手动填写");
            }
            if (title.length() > 100) {
                title = title.substring(0, 100).trim();
            }
            return title;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("[标题生成] 失败：project={} : {}", projectId, e.getMessage());
            throw new BizException(400, "AI 标题生成失败：" + friendlyError(e));
        }
    }

    /**
     * 打开 SSE 流式通道：群聊委托编排；单聊取最新 streaming 的 assistant 消息触发 AI 生成。
     * <p>世界事件注入后（单聊无 user 消息触发），若不存在占位消息则自动为会话首个成员建回应占位。</p>
     *
     * @param conversationId   会话主键
     * @param forceCharacterId 群聊指定发言人（可空，来自 stream 查询参数）
     * @return SseEmitter（无进行中任务时直接 complete）
     */
    public SseEmitter stream(Long conversationId, Long forceCharacterId) {
        ActorConversation conv = conversationService.requireOwned(conversationId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 并发互斥：同一会话已有生成任务时拒绝重复流
        AtomicBoolean flag = generating.computeIfAbsent(conversationId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            emitter.completeWithError(new BizException(409, "该会话已有消息正在生成，请稍候"));
            return emitter;
        }
        // alive 标志：连接超时/完成/出错后置 false，群聊生成线程据此安静终止，避免在已关闭的
        // emitter 上继续发送导致 "ResponseBodyEmitter has already completed" 异常
        AtomicBoolean alive = new AtomicBoolean(true);
        emitter.onCompletion(() -> { alive.set(false); flag.set(false); });
        emitter.onTimeout(() -> { alive.set(false); flag.set(false); });
        emitter.onError(e -> { alive.set(false); flag.set(false); });

        // 关键：SecurityContext 不会随虚拟线程传播，必须在请求线程捕获当前用户 ID 再传入生成任务，
        // 否则 generate 内解析用户会错误落到演示用户（id=1），导致“未配置可用的 AI API”误报。
        Long userId = currentUserProvider.currentUserId();

        if ("group".equals(conv.getMode())) {
            // 群聊：委托 GroupChatService 逐角色编排生成
            executor.execute(() -> groupChatService.runRound(emitter, conv, userId, forceCharacterId, alive));
            return emitter;
        }

        // 单聊：取最新一条 streaming 的 assistant 消息作为生成目标
        ActorMessage pending = messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(m -> "assistant".equals(m.getRole()) && "streaming".equals(m.getStatus()))
                .findFirst().orElse(null);
        if (pending == null) {
            // 世界事件注入等无占位场景：自动为会话首个成员建回应占位，保证单聊也能回应事件
            List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
            if (!members.isEmpty()) {
                pending = new ActorMessage();
                pending.setConversationId(conversationId);
                pending.setCharacterId(members.get(0).getCharacterId());
                pending.setRole("assistant");
                pending.setType("text");
                pending.setContent("");
                pending.setStatus("streaming");
                messageRepository.save(pending);
            }
        }
        if (pending == null) {
            // 无进行中的生成任务：立即完成，前端据此刷新消息列表
            emitter.complete();
            return emitter;
        }

        ActorMessage target = pending;
        executor.execute(() -> generate(emitter, conv, target, userId));
        return emitter;
    }

    /**
     * 后台生成任务（单聊）：组装 Prompt → 流式调用 AI → 逐 token 推送 → 完成后落库 + 用量记录。
     *
     * @param emitter SSE 发射器
     * @param conv    会话实体
     * @param pending assistant 占位消息
     * @param userId  归属用户 ID（已捕获，不依赖虚拟线程 SecurityContext）
     */
    private void generate(SseEmitter emitter, ActorConversation conv, ActorMessage pending, Long userId) {
        StringBuilder full = new StringBuilder();
        long startMs = System.currentTimeMillis();
        // 解析角色名（用于控制台任务日志）
        String charName = pending.getCharacterId() == null ? null
                : characterRepository.findById(pending.getCharacterId()).map(ActorCharacter::getName).orElse(null);
        log.info("[对话] 任务开始：会话={} 角色ID={} 角色={}", conv.getId(), pending.getCharacterId(), charName);
        try {
            List<AiChatRequest.ChatMessage> messages = buildMessages(conv, pending, userId);
            // 流式调用（项目级优先/用户级回退默认 API；未配置时抛 AiCallException）
            aiProviderRouter.chatCompletionStream(userId, conv.getProjectId(), null,
                    new AiChatRequest(null, messages, 0.7, 2048),
                    delta -> {
                        full.append(delta);
                        // 用可变 Map 承载：characterId 可能为 null（会话无成员降级场景），Map.of 遇 null 会抛 NPE
                        Map<String, Object> tokenData = new LinkedHashMap<>();
                        tokenData.put("delta", delta);
                        tokenData.put("characterId", pending.getCharacterId());
                        try {
                            emitter.send(SseEmitter.event().name("token").data(tokenData));
                        } catch (java.io.IOException ex) {
                            throw new RuntimeException("SSE 连接已断开", ex);
                        }
                    },
                    usage -> {
                        pending.setTokenIn(usage.promptTokens());
                        pending.setTokenOut(usage.completionTokens());
                        pending.setCacheHitTokens(usage.cacheHitTokens());
                        pending.setCacheMissTokens(usage.cacheMissTokens());
                    });

            // 生成完成：回填内容与状态
            pending.setContent(full.toString());
            pending.setStatus("done");
            messageRepository.save(pending);
            emitter.send(SseEmitter.event().name("done")
                    .data(Map.of("assistantMessageId", pending.getId(), "characterId", pending.getCharacterId(),
                            "content", full.toString(),
                            "tokenIn", pending.getTokenIn(), "tokenOut", pending.getTokenOut())));
            // 用量日志（scene=dialog，带 project_id）
            log.info("[对话] 角色={}：{}", charName, full.toString());
            log.info("[对话] 任务结束：会话={} 角色={} 耗时={}ms tokens={}/{}", conv.getId(), charName,
                    System.currentTimeMillis() - startMs, pending.getTokenIn(), pending.getTokenOut());
            usageLogService.record(userId, conv.getProjectId(), pending.getCharacterId(), null, null, "dialog",
                    pending.getTokenIn(), pending.getTokenOut(),
                    pending.getCacheHitTokens(), pending.getCacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));
            // 对话完成后自动评估高重要度角色的行动（after_dialog 触发源，异步）
            actionEngine.evaluateAfterDialogAsync(conv.getId(), userId);
            // P4-1：对话完成后异步抽取长期记忆（关键事实 + 会话摘要，内存开关关闭时自动跳过）
            memoryService.extractAfterRoundAsync(conv.getId(), userId);
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 对话生成失败: conv={} : {}", conv.getId(), e.getMessage());
            pending.setStatus("failed");
            pending.setContent(full.length() > 0 ? full.toString() : null);
            messageRepository.save(pending);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", friendlyError(e))));
            } catch (Exception ignored) {
                // 前端已断开时忽略推送失败
            }
            emitter.complete();
        }
    }

    /**
     * 组装对话消息：system_prompt（角色卡渲染）+ 知识/环境上下文注入 + 最近 N 轮历史 + 最新用户消息。
     *
     * @param conv    会话实体
     * @param pending assistant 占位（跳过）
     * @param userId  归属用户 ID（RAG/embedding 凭据归属）
     * @return 消息序列
     */
    private List<AiChatRequest.ChatMessage> buildMessages(ActorConversation conv, ActorMessage pending, Long userId) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", resolveSystemPrompt(conv)));
        // 本地账户「NPC 个性化档案」注入：让角色基于对用户的了解（身份/职业/喜好/禁忌/个人档案）定制化回答；
        // 档案为空时不注入，不影响原有行为。
        String npcProfile = localAccountService.renderNpcProfile();
        if (!npcProfile.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", npcProfile));
        }
        // 对话场景注入：对话所在地（空=通过手机等远程通讯软件对话）+ 世界时间快照，
        // 让 NPC 基于「在哪里、什么时间」发生对话做出贴合场景的回应（直接影响回答内容与语气）。
        String scene = resolveSceneMessage(conv);
        if (scene != null) {
            messages.add(new AiChatRequest.ChatMessage("system", scene));
        }
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conv.getId());
        // 最近一条用户消息（作为 RAG 检索 query）
        String lastUserText = history.stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank())
                .reduce((first, second) -> second)
                .map(ActorMessage::getContent)
                .orElse(null);
        // P3/P4：注入记忆 + 知识 + 环境上下文（优先级：角色卡 > 记忆 > RAG > 人群环境；对话历史之前）
        String context = conversationContextService.buildContext(userId, conv.getProjectId(),
                pending.getCharacterId(), lastUserText);
        if (!context.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", context));
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        // P4-1 S2 窗口压缩联动：历史超窗时，超窗部分不再直接注入，以「会话摘要记忆」承接，
        // 让 AI 仍了解窗口之外发生的事（memoryContext 注入角色事实/项目级记忆，此处注入会话摘要，避免重复）
        if (from > 0) {
            String past = memoryService.conversationSummaryContext(conv.getProjectId(), pending.getCharacterId());
            if (!past.isBlank()) {
                messages.add(new AiChatRequest.ChatMessage("system", "【过往对话摘要】" + past));
            }
        }
        for (int i = from; i < history.size(); i++) {
            ActorMessage m = history.get(i);
            if (m.getId().equals(pending.getId())) continue; // 跳过占位消息
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                // 仅纳入已完成消息（含最新 user；assistant 需 done 状态）
                if ("user".equals(m.getRole()) || "done".equals(m.getStatus())) {
                    messages.add(new AiChatRequest.ChatMessage(m.getRole(),
                            m.getContent() == null ? "" : m.getContent()));
                }
            }
        }
        return messages;
    }

    /**
     * 解析单聊角色的 system_prompt（取第一个成员角色的最新角色卡渲染 Prompt）。
     *
     * @param conv 会话实体
     * @return 系统提示词（无角色卡时降级）
     */
    private String resolveSystemPrompt(ActorConversation conv) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conv.getId());
        if (members.isEmpty()) {
            return "你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。";
        }
        Long characterId = members.get(0).getCharacterId();
        return cardRepository.findTopByCharacterIdOrderByVersionDesc(characterId)
                .map(ActorCharacterCard::getSystemPrompt)
                .filter(s -> s != null && !s.isBlank())
                .orElse("你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。");
    }

    /**
     * 组装【当前对话场景】system 消息（所在地 + 世界时间，注入 NPC 回答）。
     * <p>所在地为空时按「通过手机等远程通讯软件进行对话」处理——这是创建对话未填地点的默认语义；
     * 场景为空（无所在地且无时间）时不注入，保持旧行为。</p>
     *
     * @param conv 会话实体（含 location / gameTimeText 快照）
     * @return 场景消息文本；无可注入内容时返回 null
     */
    private String resolveSceneMessage(ActorConversation conv) {
        String location = conv.getLocation();
        String time = conv.getGameTimeText();
        boolean hasLocation = location != null && !location.isBlank();
        boolean hasTime = time != null && !time.isBlank();
        if (!hasLocation && !hasTime) {
            return null;
        }
        String locText = hasLocation ? location : "通过手机等远程通讯软件进行对话（远程通话/文字消息，非面对面）";
        String timeText = hasTime ? time : "未指定";
        return "【当前对话场景】本次对话发生的地点：" + locText + "；发生时间（世界时间）：" + timeText
                + "。请严格按照该场景与时间进行角色扮演：若为面对面场景，请自然地描述周围环境、光线、声音、"
                + "在场人物等细节；若为远程通讯（手机/文字消息/语音通话），请体现通讯方式的质感与距离感，"
                + "不要凭空描述未出现在场景中的画面。";
    }

    /**
     * 生成失败的用户友好提示。
     *
     * @param e 异常
     * @return 中文提示
     */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) return be.getMessage();
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) return ae.getMessage();
        return "对话生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
    }
}
