package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长期记忆服务（P4-1 核心，A-C7 P2）。
 * <p>职责：实现「对话后 AI 全自动抽取关键事实与会话摘要 → 存储 → 按重要度注入后续对话」闭环——
 * ① 抽取：每轮对话回复完成后异步调用 memory_extract 模板抽取新事实（服务端文本重叠二次去重），
 * 每 N 轮（默认 10）额外生成一条 memory_summarize 会话摘要（S2 上下文压缩承接）；
 * ② 注入：memoryContext 按「重要度 desc + 较新优先」取角色事实 top-K + 项目级记忆 top-K，
 * conversationSummaryContext 取最近会话摘要供超窗压缩注入（优先级：角色卡 > 记忆 > RAG > 人群环境 > 历史）；
 * ③ 管理：超预算按「重要度×新旧」滚动淘汰低价值记忆、列表/软删（归属校验）。
 * 开关：{@code HOLOZYN_ACTOR_MEMORY_ENABLED}（默认 true；false 时行为与 P3 完全一致）。</p>
 * <p>所属模块：service/memory（记忆子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    /** 抽取/摘要时纳入的最近消息条数（控制输入 token 成本） */
    private static final int RECENT_DIALOG_LIMIT = 20;

    /** 会话摘要的固定重要度（摘要承接上下文压缩，需稳定注入） */
    private static final int SUMMARY_IMPORTANCE = 4;

    /** AI 抽取温度（低温度保证抽取稳定性） */
    private static final double EXTRACT_TEMPERATURE = 0.3;

    /** AI 抽取最大输出 token */
    private static final int EXTRACT_MAX_TOKENS = 1024;

    /** 虚拟线程执行器：异步抽取不阻塞对话主流程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorMemoryRepository memoryRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorProjectRepository projectRepository;
    private final ConversationService conversationService;
    private final PromptTemplateService promptTemplateService;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;

    /** 长期记忆总开关（false 时抽取/注入全部跳过，行为与 P3 完全一致） */
    @Value("${holzyn.actor.memory.enabled:true}")
    private boolean memoryEnabled;

    /** 每轮注入的角色级事实记忆条数（重要度 desc + 较新优先） */
    @Value("${holzyn.actor.memory.top-k:8}")
    private int topK;

    /** 每轮注入的项目级记忆条数（世界大事记） */
    @Value("${holzyn.actor.memory.project-top-k:4}")
    private int projectTopK;

    /** 每角色/项目记忆条数预算，超限按「重要度×新旧」滚动淘汰 */
    @Value("${holzyn.actor.memory.budget:50}")
    private int budget;

    /** 会话摘要生成间隔（每 N 轮生成一条 summary 记忆） */
    @Value("${holzyn.actor.memory.summary-interval:10}")
    private int summaryInterval;

    /** 去重阈值：新事实与已有记忆文本重叠度（Dice 二元组系数 0~1）高于该值则丢弃 */
    @Value("${holzyn.actor.memory.dedup-threshold:0.6}")
    private double dedupThreshold;

    /** 记忆门控总开关（true=寒暄/无实质信息的轮次跳过提取，连 AI 都不调用） */
    @Value("${holzyn.actor.memory.gate.enabled:true}")
    private boolean gateEnabled;

    /** 单聊寒暄判定最大字符数：最新用户消息含寒暄词且长度 ≤ 该值 → 视为寒暄跳过 */
    @Value("${holzyn.actor.memory.gate.greeting-max-len:20}")
    private int greetingMaxLen;

    /** 群聊寒暄判定最大字符数（群聊更容易产生实质信息，阈值更宽松：≤ 8 字且含寒暄词才算寒暄） */
    @Value("${holzyn.actor.memory.gate.group-greeting-max-len:8}")
    private int groupGreetingMaxLen;

    /** AI 复核开关（默认 false）：开启后启发式判定「可能实质」的轮次再让 AI 判断一次是否有值得记住的事实 */
    @Value("${holzyn.actor.memory.gate.ai-review:false}")
    private boolean gateAiReview;

    /** 会话结束懒检查阈值（真实分钟）：距上条消息超过该值视为上次会话已结束，下次对话开始前补提一次 */
    @Value("${holzyn.actor.memory.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /** 常见寒暄/客套词（用于启发式门控；命中且短消息 → 跳过提取） */
    private static final List<String> GREETING_WORDS = List.of(
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "hey", "在吗", "在不在",
            "早上好", "中午好", "下午好", "晚上好", "晚安", "早安",
            "嗯", "嗯嗯", "哦", "哦哦", "啊", "哈哈", "呵呵", "嘿嘿",
            "谢谢", "多谢", "不客气", "再见", "拜拜", "好的", "好", "行", "可以",
            "没事", "没什么");

    // ==================== 抽取 ====================

    /**
     * 异步抽取入口：对话每轮回复完成后调用，不阻塞 SSE 主流程。
     * 记忆开关关闭时直接返回（与 P3 行为一致）。
     *
     * @param conversationId 会话主键
     * @param userId         归属用户 ID（已从请求线程捕获，不依赖虚拟线程 SecurityContext）
     */
    public void extractAfterRoundAsync(Long conversationId, Long userId) {
        if (!memoryEnabled) {
            return;
        }
        executor.execute(() -> {
            try {
                extractAfterRound(conversationId, userId);
            } catch (Exception e) {
                // 抽取失败不阻断对话主流程，仅告警
                log.warn("[记忆抽取] 异步抽取失败: 会话={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 会话结束兜底（懒检查）：用户向会话发送新消息时调用。
     * 若该会话距上一条消息已超过 {@code sessionTimeoutMinutes}（默认 30 分钟），
     * 视为上次会话已结束——在本轮对话开始前异步补提一次完整记忆（事实 + 强制摘要），
     * 让上次会话的实质内容在中断后也能沉淀；若窗口内均为寒暄则门控跳过、不落库。
     * <p>天然防抖：补提后新对话会刷新 lastMessageAt，下次需再空闲超时才触发。</p>
     *
     * @param conv   会话实体（须在更新 lastMessageAt 之前传入，否则取到的是当前消息时间）
     * @param userId 归属用户 ID（请求线程捕获）
     */
    public void maybeExtractAfterIdleAsync(ActorConversation conv, Long userId) {
        if (!memoryEnabled) {
            return;
        }
        LocalDateTime last = conv.getLastMessageAt();
        if (last == null) {
            return;
        }
        if (last.plusMinutes(sessionTimeoutMinutes).isAfter(LocalDateTime.now())) {
            return; // 未超时，不是「会话结束」，逐轮门控已在每轮处理
        }
        log.info("[记忆懒检查] 会话={} 距上条消息已超 {} 分钟，异步补提上次会话记忆（完整重提）",
                conv.getId(), sessionTimeoutMinutes);
        executor.execute(() -> {
            try {
                extractAfterRound(conv.getId(), userId, true);
            } catch (Exception e) {
                log.warn("[记忆懒检查] 补提失败: 会话={} : {}", conv.getId(), e.getMessage());
            }
        });
    }

    /**
     * 同步抽取核心（默认模式：逐轮门控 + 按 summary-interval 生成摘要）。
     * 委托 {@link #extractAfterRound(Long, Long, boolean)}，forceSummary=false。
     *
     * @param conversationId 会话主键
     * @param userId         归属用户 ID
     */
    public void extractAfterRound(Long conversationId, Long userId) {
        extractAfterRound(conversationId, userId, false);
    }

    /**
     * 同步抽取核心：对会话最近一轮对话抽取新事实（去重后写入），并按需生成会话摘要、淘汰超预算记忆。
     * 归属角色：单聊=会话首个成员角色；群聊=最近一条 assistant 消息的归属角色（最近实际发言者）。
     * <p>记忆门控（2026-08-18 新增）：寒暄/无实质信息的轮次直接跳过（连 AI 都不调用，省 token）；
     * forceSummary=true 表示会话结束兜底补提——以整个最近窗口判断实质内容（结尾寒暄不影响整段有价值），
     * 且只要窗口有实质内容就强制生成一条会话摘要（忽略 summary-interval）。</p>
     * <p>归属校验使用入参 userId（请求线程捕获），不依赖虚拟线程 SecurityContext——
     * 否则异步线程解析会回退演示用户（id=1），真实用户会话被误判「不存在或无权访问」。</p>
     *
     * @param conversationId 会话主键
     * @param userId         归属用户 ID（异步调用方须从请求线程捕获传入）
     * @param forceSummary   会话结束兜底：true=窗口有实质即强制补摘要；false=按 summary-interval
     */
    public void extractAfterRound(Long conversationId, Long userId, boolean forceSummary) {
        if (!memoryEnabled) {
            return;
        }
        ActorConversation conv = conversationService.requireOwned(conversationId, userId);
        List<ActorMessage> history = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        if (history.isEmpty()) {
            return;
        }
        Long characterId = resolveMemoryCharacter(conv, history);
        if (characterId == null) {
            log.info("[记忆抽取] 会话={} 无归属角色，跳过抽取", conversationId);
            return;
        }
        // 记忆门控：寒暄/无实质信息的轮次跳过（连 AI 都不调用，省 token）
        boolean group = "group".equals(conv.getMode());
        if (gateEnabled) {
            if (forceSummary) {
                // 会话结束兜底：以整个最近窗口判断是否有实质内容（结尾寒暄不影响整段有价值）
                if (!segmentHasSubstance(history, group, greetingMaxLen, groupGreetingMaxLen)) {
                    log.info("[记忆抽取] 会话={} 角色={} 最近对话均为寒暄/无实质信息，跳过会话结束补提", conversationId, characterId);
                    return;
                }
            } else if (!lastRoundHasSubstance(history, group, greetingMaxLen, groupGreetingMaxLen)) {
                log.info("[记忆抽取] 会话={} 角色={} 本轮为寒暄/无实质信息，跳过提取", conversationId, characterId);
                return;
            }
            // AI 复核开关（默认关）：启发式通过后再让 AI 判断一次是否有值得记住的事实
            if (gateAiReview && !aiReviewHasSubstance(conv, history, userId)) {
                log.info("[记忆抽取] 会话={} 角色={} AI 复核判定无值得记忆内容，跳过提取", conversationId, characterId);
                return;
            }
        }
        String dialogText = buildDialogText(recentMessages(history));
        if (dialogText.isBlank()) {
            return;
        }
        // 已有事实清单（供模板去重提示 + 服务端二次去重；需可变列表，抽取后追加新事实）
        List<String> existing = new ArrayList<>(memoryRepository
                .findByProjectIdAndCharacterIdAndKindAndDeletedOrderByCreatedAtDesc(
                        conv.getProjectId(), characterId, "fact", 0)
                .stream().map(ActorMemory::getContent).toList());

        // ① AI 抽取新事实（服务端文本重叠二次去重）
        List<MemoryExtractParse.ExtractedMemory> extracted =
                extractFacts(userId, conv.getProjectId(), dialogText, existing);
        int added = 0;
        for (MemoryExtractParse.ExtractedMemory em : extracted) {
            // 二次去重：与已有记忆文本重叠度过高则丢弃（模板约束之外的服务端兜底）
            if (isDuplicate(em.content(), existing, dedupThreshold)) {
                continue;
            }
            saveMemory(conv.getProjectId(), characterId, "fact", em.content(), em.importance());
            existing.add(em.content());
            added++;
        }

        // ② 生成会话摘要：forceSummary（会话结束兜底）或每 N 轮（S2 上下文压缩承接）
        long completedAssistant = history.stream()
                .filter(m -> "assistant".equals(m.getRole()) && "done".equals(m.getStatus()))
                .count();
        if (forceSummary || shouldGenerateSummary(completedAssistant, summaryInterval)) {
            String summary = summarizeDialog(userId, conv.getProjectId(), characterId, dialogText);
            if (summary != null) {
                saveMemory(conv.getProjectId(), characterId, "summary", summary, SUMMARY_IMPORTANCE);
            }
        }

        // ③ 超预算滚动淘汰（角色级 + 项目级）
        evictOverBudget(conv.getProjectId(), characterId);
        log.info("[记忆抽取] 会话={} 角色={} 抽取新增 {} 条事实", conversationId, characterId, added);
    }

    /**
     * 手动触发抽取（调试/补抽）：对指定角色参与的最近一个会话执行一次抽取。
     * 归属校验以当前会话用户为准。
     *
     * @param characterId 角色主键
     * @return 本次抽取新增的事实条数（AI 失败或无会话返回 0）
     */
    public int extractForCharacter(Long characterId) {
        if (!memoryEnabled) {
            return 0;
        }
        Long userId = currentUserProvider.currentUserId();
        ActorCharacter ch = characterRepository.findById(characterId)
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
        requireProject(ch.getProjectId(), userId);
        // 定位该角色参与的最近会话（按会话最后消息时间倒序）
        List<ActorConversationMember> members = memberRepository.findByCharacterId(characterId);
        if (members.isEmpty()) {
            log.info("[记忆补抽] 角色={} 无参与会话，跳过", characterId);
            return 0;
        }
        ActorConversation target = null;
        LocalDateTime latest = null;
        for (ActorConversationMember m : members) {
            ActorConversation c = conversationService.requireOwned(m.getConversationId());
            if (latest == null || (c.getLastMessageAt() != null && c.getLastMessageAt().isAfter(latest))) {
                latest = c.getLastMessageAt();
                target = c;
            }
        }
        if (target == null) {
            return 0;
        }
        int before = countFacts(target.getProjectId(), characterId);
        extractAfterRound(target.getId(), userId);
        return countFacts(target.getProjectId(), characterId) - before;
    }

    // ==================== 注入 ====================

    /**
     * 生成「长期记忆」注入片段（优先级：角色卡 > 记忆 > RAG > 人群环境 > 历史）。
     * 角色级事实记忆按重要度 desc + 较新优先取 top-K；项目级记忆（世界大事记）取 top-K。
     * 无可用记忆时返回空串（由调用方跳过注入）。
     *
     * @param userId      归属用户 ID（未使用，保留签名一致性）
     * @param projectId   项目 ID
     * @param characterId 当前角色 ID（可空=仅项目级记忆）
     * @return 注入文本（无可用记忆返回空串）
     */
    public String memoryContext(Long userId, Long projectId, Long characterId) {
        if (!memoryEnabled) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // ① 角色级事实记忆（含会话摘要走独立窗口压缩注入，避免重复）
        if (characterId != null) {
            List<ActorMemory> charFacts = memoryRepository
                    .findByProjectIdAndCharacterIdAndKindAndDeletedOrderByCreatedAtDesc(
                            projectId, characterId, "fact", 0);
            List<ActorMemory> ranked = rankForInjection(charFacts, topK);
            if (!ranked.isEmpty()) {
                sb.append("【长期记忆】以下是该角色过往经历中值得记住的事实，回答时自然引用，不得遗忘或前后矛盾：\n");
                for (ActorMemory m : ranked) {
                    sb.append("- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append("\n");
                }
            }
        }
        // ② 项目级记忆（世界大事记，所有角色可见）
        List<ActorMemory> projMemories = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        List<ActorMemory> projRanked = rankForInjection(projMemories, projectTopK);
        if (!projRanked.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("【世界大事记】以下是本项目近期发生的重大事件，所有角色都知晓：\n");
            for (ActorMemory m : projRanked) {
                sb.append("- [重要度").append(m.getImportance()).append("] ").append(m.getContent()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 生成「过往对话摘要」注入片段（S2 窗口压缩承接）：取该角色最近一条会话摘要，
     * 对话历史超窗时由调用方注入，让 AI 仍了解窗口之外发生的事。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     * @return 摘要文本（无摘要返回空串）
     */
    public String conversationSummaryContext(Long projectId, Long characterId) {
        if (!memoryEnabled || characterId == null) {
            return "";
        }
        return memoryRepository
                .findByProjectIdAndCharacterIdAndKindAndDeletedOrderByCreatedAtDesc(
                        projectId, characterId, "summary", 0)
                .stream().findFirst()
                .map(ActorMemory::getContent)
                .orElse("");
    }

    // ==================== 管理 ====================

    /**
     * 记忆列表（归属校验）。characterId 为空返回项目全部记忆（含项目级+角色级），
     * 非空返回该角色记忆（角色需属于该项目）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID（可空）
     * @param page        页码（从 1 起）
     * @param size        每页条数
     * @return 分页记忆 VO
     */
    public PageResult<MemoryVO> list(Long projectId, Long characterId, int page, int size) {
        requireProject(projectId, currentUserProvider.currentUserId());
        if (characterId != null) {
            requireCharacterInProject(characterId, projectId);
        }
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<ActorMemory> p = characterId == null
                ? memoryRepository.findByProjectIdAndDeleted(projectId, 0, PageRequest.of(safePage, safeSize))
                : memoryRepository.findByProjectIdAndCharacterIdAndDeleted(
                        projectId, characterId, 0, PageRequest.of(safePage, safeSize));
        List<MemoryVO> vos = p.getContent().stream().map(MemoryVO::of).toList();
        return PageResult.of(vos, p.getTotalElements(), page, safeSize);
    }

    /**
     * 删除记忆（软删，归属校验：记忆归属项目须属于当前用户）。
     *
     * @param memoryId 记忆主键
     */
    public void delete(Long memoryId) {
        ActorMemory m = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new BizException(404, "记忆不存在"));
        if (m.getProjectId() == null) {
            throw new BizException(404, "记忆不存在或无权访问");
        }
        requireProject(m.getProjectId(), currentUserProvider.currentUserId());
        m.setDeleted(1);
        memoryRepository.save(m);
    }

    // ==================== 纯逻辑（静态，可单测） ====================

    /**
     * 按「重要度 desc + 较新优先」排序取 top-K（注入排序）。
     *
     * @param all   记忆列表（可为空）
     * @param topK  取前 N 条（<=0 返回空）
     * @return 排序后的前 topK 条
     */
    static List<ActorMemory> rankForInjection(List<ActorMemory> all, int topK) {
        if (all == null || all.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<ActorMemory> sorted = new ArrayList<>(all);
        // 排序：重要度 desc（主），同重要度下较新优先（次）——子比较器各自独立排序，避免 reversed 影响整条链
        sorted.sort(Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance()).reversed()
                .thenComparing(Comparator.comparing((ActorMemory m) -> m.getCreatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()));
        return sorted.size() <= topK ? sorted : new ArrayList<>(sorted.subList(0, topK));
    }

    /**
     * 超预算滚动淘汰：返回应软删的低价值记忆列表。
     * 保留策略：重要度高、较新的优先保留；删除重要度低、较旧的（「重要度×新旧」评分）。
     *
     * @param all    记忆列表（可为空）
     * @param budget 预算上限（<=0 视为不淘汰）
     * @return 应删除的记忆列表（空=无需淘汰）
     */
    static List<ActorMemory> evictToBudget(List<ActorMemory> all, int budget) {
        if (all == null || all.size() <= budget) {
            return List.of();
        }
        List<ActorMemory> sorted = new ArrayList<>(all);
        // 升序：重要度低 + 旧 在前 → 删除前 (size - budget) 个
        sorted.sort(Comparator
                .comparingInt((ActorMemory m) -> m.getImportance() == null ? 1 : m.getImportance())
                .thenComparing(m -> m.getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ArrayList<>(sorted.subList(0, sorted.size() - budget));
    }

    /**
     * 文本重叠度（Dice 二元组系数 0~1）：两个文本共现二元组比例，用于服务端二次去重。
     *
     * @param a 文本 a
     * @param b 文本 b
     * @return 重叠度（空文本返回 0）
     */
    static double overlapScore(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return 0;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String bg : bigramsA) {
            if (bigramsB.contains(bg)) {
                common++;
            }
        }
        return 2.0 * common / (bigramsA.size() + bigramsB.size());
    }

    /**
     * 新事实与已有记忆是否重复：任一条已有记忆达到阈值即判定重复。
     * <p>去重策略：① 整条文本重叠（Dice 二元组，对称系数，适合等长文本）；
     * ② 子句命中率——新事实按标点切分子句，若某子句的核心二元组大部分（≥70% 且至少 3 个）
     * 已出现在某条已有记忆中，说明该子句表达的事实已被覆盖，判重。
     * 该策略拦截「表述不同但事实相同」的重复（如「师傅叫玄铁」vs「林安的师傅叫玄铁隐居云雾山」）。</p>
     *
     * @param newContent 新事实内容
     * @param existing   已有记忆内容列表
     * @param threshold  整条重叠阈值（0~1）
     * @return true 表示重复应丢弃
     */
    static boolean isDuplicate(String newContent, List<String> existing, double threshold) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        // ① 整条文本重叠（Dice 二元组）
        for (String e : existing) {
            if (overlapScore(newContent, e) >= threshold) {
                return true;
            }
        }
        // ② 子句命中率：短子句 vs 长记忆用 Dice 会被稀释，改用「子句二元组在记忆中的命中率」
        for (String clause : splitClauses(newContent)) {
            Set<String> clauseBigrams = bigrams(clause);
            if (clauseBigrams.size() < 3) {
                continue; // 二元组过少无区分度，跳过避免误判
            }
            for (String e : existing) {
                Set<String> memBigrams = bigrams(e);
                int common = 0;
                for (String bg : clauseBigrams) {
                    if (memBigrams.contains(bg)) {
                        common++;
                    }
                }
                double hitRate = (double) common / clauseBigrams.size();
                if (common >= 3 && hitRate >= 0.7) {
                    return true; // 子句核心内容已由该记忆覆盖
                }
            }
        }
        return false;
    }

    /**
     * 将文本切分为子句（按中文/英文标点：。；，、！？；英文句号逗号分号）。
     *
     * @param text 文本
     * @return 子句列表（空文本返回空列表）
     */
    static List<String> splitClauses(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if ("。；，、！？.!;".indexOf(c) >= 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /**
     * 是否到达会话摘要生成点：已完成 assistant 消息数达到间隔的整数倍（每 N 轮一次）。
     *
     * @param completedAssistantCount 会话内已完成（done）的 assistant 消息数
     * @param interval                摘要间隔（每 N 轮）
     * @return true 表示本轮应生成摘要
     */
    static boolean shouldGenerateSummary(long completedAssistantCount, int interval) {
        return interval > 0 && completedAssistantCount >= interval
                && completedAssistantCount % interval == 0;
    }

    // ==================== 记忆门控（寒暄/无实质信息跳过） ====================

    /**
     * 判定一段文本是否为「寒暄/客套/无实质信息」。
     * <p>规则：空文本=寒暄；≤2 字符（嗯/哦/好/在 等单字回复）=寒暄；
     * 含寒暄词且长度 ≤ maxLen=寒暄；否则视为有实质内容。</p>
     *
     * @param text   待判定文本（通常为最新一条用户消息）
     * @param maxLen 寒暄最大字符数（单聊 20 / 群聊 8，群聊更宽松）
     * @return true=寒暄应跳过提取
     */
    static boolean isGreetingText(String text, int maxLen) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.length() <= 2) {
            return true; // 嗯/哦/好/在/嗯嗯 等单字极短回复
        }
        return t.length() <= maxLen && containsGreetingWord(t);
    }

    /**
     * 文本是否含寒暄/客套词。
     *
     * @param text 文本（可空）
     * @return true=含寒暄词
     */
    static boolean containsGreetingWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String w : GREETING_WORDS) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐轮门控：最近一轮（最新 user + 最新已完成 assistant）是否有实质内容。
     * <p>以最新用户消息为主判断（寒暄/极短 → 跳过）；无用户消息返回 false（无可提取）。
     * 群聊阈值更宽松（groupGreetingMaxLen=8，只有 ≤8 字且含寒暄词才算寒暄）。</p>
     *
     * @param history            会话全部消息（升序）
     * @param group              是否群聊（群聊阈值更宽松）
     * @param greetingMaxLen     单聊寒暄最大字符数
     * @param groupGreetingMaxLen 群聊寒暄最大字符数
     * @return true=最近一轮有实质内容应提取
     */
    static boolean lastRoundHasSubstance(List<ActorMessage> history, boolean group,
                                         int greetingMaxLen, int groupGreetingMaxLen) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        // 取最新一条 user 消息（升序从后往前）
        for (int i = history.size() - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if ("user".equals(m.getRole())) {
                int maxLen = group ? groupGreetingMaxLen : greetingMaxLen;
                return !isGreetingText(m.getContent(), maxLen);
            }
        }
        return false;
    }

    /**
     * 会话结束兜底门控：最近窗口内「任一」用户消息有实质内容即判定整段有价值
     * （结尾寒暄不影响整段会话的提取价值，避免整段实质对话因最后一句「再见」被跳过）。
     *
     * @param history            会话全部消息（升序）
     * @param group              是否群聊
     * @param greetingMaxLen     单聊寒暄最大字符数
     * @param groupGreetingMaxLen 群聊寒暄最大字符数
     * @return true=窗口内存在实质内容
     */
    static boolean segmentHasSubstance(List<ActorMessage> history, boolean group,
                                       int greetingMaxLen, int groupGreetingMaxLen) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int maxLen = group ? groupGreetingMaxLen : greetingMaxLen;
        for (ActorMessage m : history) {
            if ("user".equals(m.getRole()) && !isGreetingText(m.getContent(), maxLen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AI 复核（可选，默认关闭）：让 AI 判断最近对话是否有值得长期记住的事实。
     * <p>仅在 {@code holzyn.actor.memory.gate.ai-review=true} 时启用——启发式门控之后
     * 再加一层语义判断，减少「非寒暄但确实无信息量」轮次的无效抽取调用。</p>
     *
     * @param conv    会话实体
     * @param history 会话全部消息
     * @param userId  归属用户 ID
     * @return true=AI 判定有值得记住的事实
     */
    private boolean aiReviewHasSubstance(ActorConversation conv, List<ActorMessage> history, Long userId) {
        try {
            String dialogText = buildDialogText(recentMessages(history));
            if (dialogText.isBlank()) {
                return false;
            }
            String prompt = "请判断下面这段玩家与 NPC 的对话中，是否存在值得长期记住的信息"
                    + "（新事实、约定、关键事件、玩家偏好等）。若只是寒暄/闲聊/无实质信息，输出 false。\n\n"
                    + "只输出 JSON，格式：{\"has_memorable\": true 或 false}\n\n"
                    + "对话：\n" + dialogText;
            AiChatResult result = aiProviderRouter.chatCompletion(userId, conv.getProjectId(), null,
                    new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                            new AiChatRequest.ChatMessage("user", prompt)),
                            EXTRACT_TEMPERATURE, 32, true));
            String content = result.content() == null ? "" : result.content();
            return content.contains("\"has_memorable\"") && (content.contains("true")
                    || content.contains("True") || content.contains("TRUE"));
        } catch (Exception e) {
            log.warn("[记忆AI复核] 调用失败，回退为允许提取: {}", e.getMessage());
            return true; // 复核失败时回退为允许，避免误杀
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 解析记忆归属角色：群聊取最近一条 assistant 消息的归属角色，否则取会话首个成员角色。
     *
     * @param conv    会话实体
     * @param history 会话全部消息
     * @return 角色 ID（无归属返回 null）
     */
    private Long resolveMemoryCharacter(ActorConversation conv, List<ActorMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ActorMessage m = history.get(i);
            if ("assistant".equals(m.getRole()) && m.getCharacterId() != null) {
                return m.getCharacterId();
            }
        }
        return memberRepository.findByConversationId(conv.getId()).stream()
                .findFirst().map(ActorConversationMember::getCharacterId).orElse(null);
    }

    /**
     * 取最近消息子列表。
     *
     * @param history 全部消息（升序）
     * @return 最近 RECENT_DIALOG_LIMIT 条
     */
    private List<ActorMessage> recentMessages(List<ActorMessage> history) {
        int from = Math.max(0, history.size() - RECENT_DIALOG_LIMIT);
        return new ArrayList<>(history.subList(from, history.size()));
    }

    /**
     * 组装对话文本（抽取/摘要输入）：user 标「玩家：」，assistant 标角色名，事件标「【世界事件】」。
     *
     * @param recent 最近消息列表
     * @return 对话文本（空消息返回空串）
     */
    private String buildDialogText(List<ActorMessage> recent) {
        StringBuilder sb = new StringBuilder();
        for (ActorMessage m : recent) {
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("event".equals(m.getType())) {
                sb.append("【世界事件】").append(content).append("\n");
            } else if ("user".equals(m.getRole())) {
                sb.append("玩家：").append(content).append("\n");
            } else if ("assistant".equals(m.getRole()) && "done".equals(m.getStatus())) {
                sb.append(characterName(m.getCharacterId())).append("：").append(content).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 角色姓名解析（用于对话文本标注；缺失返回「角色」）。
     *
     * @param characterId 角色 ID
     * @return 角色姓名
     */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return "角色";
        }
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse("角色");
    }

    /**
     * AI 抽取新事实：渲染 memory_extract 模板 → json_object 调用 → 解析为事实数组。
     * AI 失败时返回空列表（不阻断推进，程序化兜底）。
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID
     * @param dialogText 最近对话文本
     * @param existing   已有事实清单（模板去重提示）
     * @return 解析出的事实列表
     */
    private List<MemoryExtractParse.ExtractedMemory> extractFacts(Long userId, Long projectId,
                                                                  String dialogText, List<String> existing) {
        try {
            String existingText = existing.isEmpty() ? "（暂无）"
                    : "- " + String.join("\n- ", existing);
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_MEMORY_EXTRACT,
                    Map.of("recent_dialog", dialogText, "existing_memories", existingText));
            AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null,
                    new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                            new AiChatRequest.ChatMessage("user", prompt)),
                            EXTRACT_TEMPERATURE, EXTRACT_MAX_TOKENS, true));
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "memory",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return MemoryExtractParse.parseExtract(result.content());
        } catch (Exception e) {
            log.warn("[记忆抽取] AI 调用失败，跳过本轮抽取: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * AI 生成会话摘要：渲染 memory_summarize 模板 → json_object 调用 → 解析摘要文本。
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID
     * @param characterId 角色 ID
     * @param dialogText 最近对话文本
     * @return 摘要文本（AI 失败返回 null）
     */
    private String summarizeDialog(Long userId, Long projectId, Long characterId, String dialogText) {
        try {
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_MEMORY_SUMMARIZE,
                    Map.of("recent_dialog", dialogText));
            AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null,
                    new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                            new AiChatRequest.ChatMessage("user", prompt)),
                            EXTRACT_TEMPERATURE, EXTRACT_MAX_TOKENS, true));
            usageLogService.record(userId, projectId, characterId, result.providerId(), result.model(), "memory",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            return MemoryExtractParse.parseSummarize(result.content());
        } catch (Exception e) {
            log.warn("[记忆摘要] AI 调用失败，跳过本轮摘要: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入一条记忆。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID（null=项目级记忆）
     * @param kind        类型（fact/summary）
     * @param content     内容
     * @param importance  重要度
     */
    private void saveMemory(Long projectId, Long characterId, String kind, String content, Integer importance) {
        ActorMemory m = new ActorMemory();
        m.setProjectId(projectId);
        m.setCharacterId(characterId);
        m.setKind(kind);
        m.setContent(content);
        m.setImportance(importance == null ? 1 : importance);
        memoryRepository.save(m);
    }

    /**
     * 超预算淘汰：角色级与项目级分别按预算滚动软删低价值记忆。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     */
    private void evictOverBudget(Long projectId, Long characterId) {
        List<ActorMemory> charMem = memoryRepository
                .findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(projectId, characterId, 0);
        softDelete(evictToBudget(charMem, budget));
        List<ActorMemory> projMem = memoryRepository
                .findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(projectId, 0);
        softDelete(evictToBudget(projMem, budget));
    }

    /**
     * 软删记忆列表。
     *
     * @param toDelete 应删除的记忆列表
     */
    private void softDelete(List<ActorMemory> toDelete) {
        for (ActorMemory m : toDelete) {
            m.setDeleted(1);
            memoryRepository.save(m);
        }
    }

    /**
     * 统计某角色事实记忆条数（补抽返回用）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     * @return 事实条数
     */
    private int countFacts(Long projectId, Long characterId) {
        return memoryRepository
                .findByProjectIdAndCharacterIdAndKindAndDeletedOrderByCreatedAtDesc(projectId, characterId, "fact", 0)
                .size();
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 校验角色属于该项目（越权抛 404）。
     *
     * @param characterId 角色 ID
     * @param projectId   项目 ID
     */
    private void requireCharacterInProject(Long characterId, Long projectId) {
        characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
    }

    /**
     * 提取文本的二元组集合（中文无空格分词，二元组近似语义重叠）。
     *
     * @param text 文本
     * @return 二元组集合
     */
    private static Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        String t = text.trim();
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }
}
