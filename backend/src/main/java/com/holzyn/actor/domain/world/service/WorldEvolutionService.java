package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorEvolution;
import com.holzyn.actor.domain.world.entity.ActorEvolutionParticipant;
import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionParticipantRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionTurnRepository;
import com.holzyn.actor.domain.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.memory.service.MemoryService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 世界演化服务（V2.1 世界演化增强）。
 * <p>职责：实现「场景化世界演化」引擎——
 * ① 手动选择全局（世界）/指定场景（地点）/背景/参与角色，或 AI 自动选择场景/背景/角色；
 * ② 逐轮 AI 编排：角色对话/行动 + 场景环境变化 + 角色加入/退场（有理有据，符合世界观与角色设定）；
 * ③ 收尾控制：场景只剩一名角色 / AI 判定剧情收束 / 轮次上限时自动收尾，输出适当后续，不会无限演化；
 * ④ 结束归档：AI 生成事件（标题+内容）写入 actor_event（时间线），并给每个参与者
 * （含已退场者）写入<b>角色级事实记忆</b>（仅当事人知道，非项目级——防止不相干角色知晓该经历）。</p>
 * <p>数据来源：actor_evolution / participant / turn、actor_event、actor_memory、世界观、角色卡。</p>
 * <p>所属模块：service/world（世界演化子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEvolutionService {

    /** 演化编排输出最大 token */
    private static final int EVOLVE_MAX_TOKENS = 2048;

    /** 归档事件生成输出最大 token */
    private static final int ARCHIVE_MAX_TOKENS = 1024;

    /** 归档角色级记忆的重要度（高，优先注入该角色对话） */
    private static final int ARCHIVE_MEMORY_IMPORTANCE = 4;

    /** 演化编排 AI 温度（中等，保证剧情合理又不失随机） */
    private static final double EVOLVE_TEMPERATURE = 0.8;

    /** 归档事件 AI 温度 */
    private static final double ARCHIVE_TEMPERATURE = 0.4;

    /** 逐拍调度输出最大 token（只需一个 JSON） */
    private static final int SCHEDULE_MAX_TOKENS = 512;

    /** 单角色发言/行动输出最大 token */
    private static final int BEAT_REPLY_MAX_TOKENS = 1024;

    /** 发言/行动欲望阈值（>=3 判定该角色确实想动；低于阈值仍选最高者持续推进） */
    private static final int BEAT_DESIRE_THRESHOLD = 3;

    /** 逐拍调度最大重试次数（模型偶发空输出/JSON 损坏/选中不在场角色时重试） */
    private static final int SCHEDULE_MAX_RETRY = 2;

    /** 单角色发言/行动最大重试次数（模型偶发只输出思考导致正文为空时重试） */
    private static final int BEAT_MAX_RETRY = 2;

    /** 演化流式上下文窗口：最近纳入的消息条数（控制 token 成本） */
    private static final int EVOLUTION_CONTEXT_WINDOW = 40;

    /** 流式并发锁：防止同一演化被多个播放流同时推进（单实例内存锁） */
    private final ConcurrentHashMap<Long, AtomicBoolean> streamLocks = new ConcurrentHashMap<>();

    private final ActorEvolutionRepository evolutionRepository;
    private final ActorEvolutionParticipantRepository participantRepository;
    private final ActorEvolutionTurnRepository turnRepository;
    private final ActorSceneRepository sceneRepository;
    private final ActorEventRepository eventRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldClockService worldClockService;
    private final PromptTemplateService promptTemplateService;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    /** 演化最大轮次数（超过强制收尾，保证不会无限演化） */
    @Value("${holzyn.actor.evolution.max-turns:20}")
    private int maxTurns;

    // ==================== 创建 ====================

    /**
     * 开始一次世界演化。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param body      入参：{mode? manual/ai, sceneId?, background?, characterIds?}
     * @return 演化会话视图（含参与者）
     */
    @Transactional
    public Map<String, Object> start(Long userId, Long projectId, Map<String, Object> body) {
        requireProject(projectId);
        String mode = body == null || str(body.get("mode")) == null ? "manual" : str(body.get("mode"));
        Long requestedSceneId = asLong(body == null ? null : body.get("sceneId"));
        String requestedBackground = body == null ? null : str(body.get("background"));
        List<Long> requestedCharacterIds = asLongList(body == null ? null : body.get("characterIds"));

        // 用户指定了全部要素或显式 manual：直接用；否则 AI 自动选择缺失要素
        Long sceneId = requestedSceneId;
        String background = requestedBackground;
        String title = null;
        List<Long> characterIds = requestedCharacterIds;
        if ("ai".equals(mode) || characterIds == null || characterIds.isEmpty() || sceneId == null) {
            Map<String, Object> ai = aiSelect(userId, projectId, requestedSceneId, requestedBackground, requestedCharacterIds);
            sceneId = ai.get("sceneId") == null ? null : asLong(ai.get("sceneId"));
            if (background == null || background.isBlank()) background = str(ai.get("background"));
            if (title == null) title = str(ai.get("title"));
            if (characterIds == null || characterIds.isEmpty()) characterIds = asLongList(ai.get("characterIds"));
        }
        if (characterIds == null || characterIds.isEmpty()) {
            throw new BizException(400, "至少选择一位参与角色（或使用 AI 自动选择）");
        }
        if (background == null || background.isBlank()) {
            background = "默认情境：角色们因各自的原因聚集在这里，展开一段自然的故事。";
        }
        if (title == null || title.isBlank()) {
            title = sceneName(sceneId) + " · 世界演化";
        }

        // 校验角色属于项目
        for (Long cid : characterIds) {
            requireCharacterInProject(cid, projectId);
        }

        ActorEvolution evolution = new ActorEvolution();
        evolution.setProjectId(projectId);
        evolution.setSceneId(sceneId);
        evolution.setTitle(title.trim());
        evolution.setBackground(background.trim());
        evolution.setMode("ai".equals(mode) ? "ai" : "manual");
        evolution = evolutionRepository.save(evolution);

        for (Long cid : characterIds) {
            ActorEvolutionParticipant p = new ActorEvolutionParticipant();
            p.setEvolutionId(evolution.getId());
            p.setCharacterId(cid);
            p.setStatus("active");
            participantRepository.save(p);
        }
        // 初始系统消息
        addTurn(evolution.getId(), null, "system", "system",
                "演化开始：" + title + "\n场景：" + (sceneName(sceneId)) + "\n背景：" + background
                        + "\n在场角色：" + characterNames(characterIds));

        log.info("[演化] 开始：项目={} 演化={} 场景={} 角色={}", projectId, evolution.getId(),
                sceneName(sceneId), characterIds);
        return detail(evolution.getId());
    }

    /**
     * AI 自动选择场景/背景/角色（模式 ai 或缺失要素时）。
     *
     * @param userId              归属用户 ID
     * @param projectId           项目 ID
     * @param requestedSceneId    用户指定场景（可空）
     * @param requestedBackground 用户指定背景（可空）
     * @param requestedCharacterIds 用户指定角色（可空）
     * @return {sceneId, background, characterIds, title, reason}
     */
    private Map<String, Object> aiSelect(Long userId, Long projectId, Long requestedSceneId,
                                         String requestedBackground, List<Long> requestedCharacterIds) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        List<ActorScene> scenes = sceneRepository.findByProjectIdOrderByEnabledDescIdAsc(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        // 候选角色：有角色卡者优先（人设完整才适合 AI 演化）
        List<ActorCharacter> candidates = chars.stream()
                .filter(c -> cardRepository.findTopByCharacterIdOrderByVersionDesc(c.getId()).isPresent())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = chars;
        }
        String sceneText = scenes.isEmpty() ? "（无预设场景，可全局演化）"
                : scenes.stream().map(s -> "ID " + s.getId() + " " + s.getName()
                        + (s.getDescription() == null ? "" : "：" + s.getDescription()))
                        .collect(Collectors.joining("\n"));
        String charText = candidates.stream()
                .map(c -> "ID " + c.getId() + " " + c.getName() + (c.getTitle() == null ? "" : "（" + c.getTitle() + "）"))
                .collect(Collectors.joining("\n"));

        String prompt = """
                你是一位虚构世界的「选角导演」。请为一场世界演化选择场景、背景与参与角色，输出一个 JSON 对象，禁止输出其他文字。

                —— 输出结构 ——
                { "sceneId": 场景ID或null（null=全局/世界演化）, "background": "演化背景设定（一段话，含此刻的情境/氛围/为什么这些人在这里）",
                  "characterIds": [角色ID数组（2-4位，出场必须有剧情理由）], "title": "演化标题（一句话）", "reason": "选角理由（50字内）" }

                —— 要求 ——
                1. 若用户已指定场景/角色，必须优先沿用，不要擅自更换。
                2. 选角要符合世界观与角色人设：优先选有剧情关联、能产生互动的主要角色。
                3. background 要有具体情境（时间地点氛围），为角色互动提供自然动机。
                4. 场景/角色 ID 必须从下列列表中选取（sceneId 可为 null=全局）。

                —— 世界设定 ——
                %s

                —— 可选场景 ——
                %s

                —— 候选角色 ——
                %s
                """.formatted(worldSetting.isBlank() ? "（未提供）" : worldSetting, sceneText, charText);

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, EVOLVE_MAX_TOKENS, true);
        try {
            AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "action",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json != null) {
                JsonNode node = objectMapper.readTree(json);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("sceneId", node.path("sceneId").isNull() ? null : node.path("sceneId").asLong());
                out.put("background", node.path("background").asText(""));
                out.put("title", node.path("title").asText(""));
                out.put("reason", node.path("reason").asText(""));
                // 角色校验：只保留属于项目且在候选内的角色
                List<Long> picked = new ArrayList<>();
                for (JsonNode idNode : node.path("characterIds")) {
                    long cid = idNode.asLong(-1);
                    if (cid > 0 && candidates.stream().anyMatch(c -> c.getId().equals(cid)) && !picked.contains(cid)) {
                        picked.add(cid);
                    }
                }
                if (picked.isEmpty() && !candidates.isEmpty()) {
                    // 兜底：取前 3 位主角/高重要度角色
                    picked = candidates.stream()
                            .sorted(java.util.Comparator.comparingInt((ActorCharacter c) ->
                                    Integer.valueOf(1).equals(c.getIsProtagonist()) ? 0 : 1)
                                    .thenComparingInt(c -> c.getImportance() == null ? 1 : -c.getImportance()))
                            .limit(3).map(ActorCharacter::getId).toList();
                }
                out.put("characterIds", picked);
                // 控制台日志：AI 选角结果（调试用）
                log.info("[演化] AI 选角：项目={} 场景={} 角色={} 理由={}", projectId,
                        out.get("sceneId") == null ? "全局" : out.get("sceneId"), picked,
                        out.get("reason"));
                return out;
            }
        } catch (Exception e) {
            log.warn("[演化] AI 选角失败，使用兜底: {}", e.getMessage());
        }
        // 兜底：用户指定优先；场景取第一个；角色取前 3
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sceneId", requestedSceneId != null ? requestedSceneId : (scenes.isEmpty() ? null : scenes.get(0).getId()));
        out.put("background", requestedBackground);
        out.put("title", "");
        List<Long> picked = requestedCharacterIds;
        if (picked == null || picked.isEmpty()) {
            picked = candidates.stream().limit(3).map(ActorCharacter::getId).toList();
        }
        out.put("characterIds", picked);
        return out;
    }

    // ==================== 推进 ====================

    /**
     * 推进一轮世界演化（AI 编排角色言行 + 加入/退场 + 收尾判定）。
     *
     * @param userId       归属用户 ID
     * @param evolutionId  演化会话 ID
     * @return 本轮结果（turns/joins/leaves/finished/...）
     */
    @Transactional
    public Map<String, Object> turn(Long userId, Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法继续推进");
        }
        List<ActorEvolutionParticipant> active = participantRepository
                .findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .filter(p -> "active".equals(p.getStatus())).toList();
        if (active.isEmpty()) {
            throw new BizException(400, "场景内没有在场角色，无法推进（可加入角色或结束演化）");
        }
        int fromCount = evolution.getTurnCount() == null ? 0 : evolution.getTurnCount();
        long turnStartMs = System.currentTimeMillis();
        // 控制台日志（对齐 ChatService [对话] 格式）：推进开始，便于观察自动/手动推进节奏
        log.info("[演化] 任务开始：演化={} 第{}轮 在场角色={}个 标题={}", evolutionId, fromCount + 1,
                active.size(), evolution.getTitle());

        Map<String, Object> plan = orchestrate(userId, evolution, active);
        List<Map<String, Object>> turns = castList(plan.get("messages"));
        for (Map<String, Object> t : turns) {
            Long cid = asLong(t.get("characterId"));
            if (cid == null) continue;
            String type = "text".equals(str(t.get("type"))) ? "text" : "action";
            String content = str(t.get("content"));
            if (content == null || content.isBlank()) continue;
            addTurn(evolutionId, cid, "assistant", type, content.trim());
        }
        // 场景环境变化
        String sceneEvent = str(plan.get("sceneEvent"));
        if (sceneEvent != null && !sceneEvent.isBlank()) {
            addTurn(evolutionId, null, "system", "system", "【环境】" + sceneEvent.trim());
        }

        // 加入/退场（带理由，有理有据）
        List<Map<String, Object>> joins = castList(plan.get("joins"));
        for (Map<String, Object> j : joins) {
            Long cid = asLong(j.get("characterId"));
            if (cid == null) continue;
            requireCharacterInProject(cid, evolution.getProjectId());
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolutionId, cid).orElseGet(() -> {
                        ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                        np.setEvolutionId(evolutionId);
                        np.setCharacterId(cid);
                        return np;
                    });
            p.setStatus("active");
            p.setLeaveAt(null);
            participantRepository.save(p);
            String reason = str(j.get("reason"));
            addTurn(evolutionId, cid, "system", "system",
                    (charName(cid) + " 登场：" + (reason == null || reason.isBlank() ? "来到了现场。" : reason)));
        }
        List<Map<String, Object>> leaves = castList(plan.get("leaves"));
        for (Map<String, Object> l : leaves) {
            Long cid = asLong(l.get("characterId"));
            if (cid == null) continue;
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolutionId, cid).orElse(null);
            if (p != null && "active".equals(p.getStatus())) {
                p.setStatus("left");
                p.setLeaveAt(LocalDateTime.now());
                participantRepository.save(p);
                String reason = str(l.get("reason"));
                addTurn(evolutionId, cid, "system", "system",
                        (charName(cid) + " 退场：" + (reason == null || reason.isBlank() ? "离开了现场。" : reason)));
            }
        }

        // 轮次计数与收尾判定
        int nextCount = (evolution.getTurnCount() == null ? 0 : evolution.getTurnCount()) + 1;
        evolution.setTurnCount(nextCount);
        evolutionRepository.save(evolution);

        int remaining = (int) participantRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .filter(p -> "active".equals(p.getStatus())).count();
        boolean shouldFinish = shouldFinish(Boolean.TRUE.equals(plan.get("shouldFinish")), remaining, nextCount, maxTurns);
        String finishReason = str(plan.get("finishReason"));
        if (shouldFinish) {
            if (remaining <= 1 && (finishReason == null || finishReason.isBlank())) {
                finishReason = "场景中只剩下一位角色，对话难以继续，故事自然收束。";
            }
            if (nextCount >= maxTurns && (finishReason == null || finishReason.isBlank())) {
                finishReason = "本轮演化已达到剧情上限，就此收尾。";
            }
            addTurn(evolutionId, null, "system", "system",
                    "【收尾】" + (finishReason == null || finishReason.isBlank() ? "剧情自然收束。" : finishReason));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evolutionId", evolutionId);
        out.put("turnCount", nextCount);
        out.put("turns", turns);
        out.put("sceneEvent", sceneEvent);
        out.put("joins", joins);
        out.put("leaves", leaves);
        out.put("shouldFinish", shouldFinish);
        out.put("finishReason", finishReason);
        out.put("summary", str(plan.get("summary")));
        out.put("remainingCharacters", remaining);
        out.put("finished", false);
        if (shouldFinish) {
            Map<String, Object> archive = finish(userId, evolutionId);
            out.put("finished", true);
            out.put("archive", archive);
        }
        // 控制台日志（对齐 ChatService [对话] 格式）：推进结束，输出本轮统计便于调试
        log.info("[演化] 任务结束：演化={} 第{}轮 耗时={}ms 消息={}条 环境变化={} 加入={} 退场={} 收尾={} 摘要={}",
                evolutionId, nextCount, System.currentTimeMillis() - turnStartMs, turns.size(),
                sceneEvent == null || sceneEvent.isBlank() ? 0 : 1, joins.size(), leaves.size(),
                shouldFinish, str(plan.get("summary")));
        return out;
    }

    /**
     * AI 编排一轮（演化导演）。
     *
     * @param userId    归属用户 ID
     * @param evolution 演化会话
     * @param active    在场角色
     * @return 编排计划（messages/sceneEvent/joins/leaves/shouldFinish/finishReason/summary）
     */
    private Map<String, Object> orchestrate(Long userId, ActorEvolution evolution,
                                            List<ActorEvolutionParticipant> active) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(evolution.getProjectId())
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(evolution);
        String charactersText = active.stream()
                .map(p -> "ID " + p.getCharacterId() + " " + charName(p.getCharacterId())
                        + "\n" + personaSummary(p.getCharacterId()))
                .collect(Collectors.joining("\n\n"));
        String history = turnRepository.findByEvolutionIdOrderByIdAsc(evolution.getId()).stream()
                .limit(40)
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (history.isBlank()) history = "（暂无经过）";

        String prompt = promptTemplateService.render(userId, evolution.getProjectId(),
                PromptTemplateService.CODE_EVOLUTION, Map.of(
                        "world_setting", worldSetting.isBlank() ? "（未提供）" : worldSetting,
                        "scene_background", sceneBackground,
                        "characters", charactersText,
                        "history", history,
                        "evolution_background", evolution.getBackground() == null ? "" : evolution.getBackground()));

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, EVOLVE_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        AiChatResult result = aiProviderRouter.chatCompletion(userId, evolution.getProjectId(), null, req);
        usageLogService.record(userId, evolution.getProjectId(), null, result.providerId(), result.model(), "action",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("messages", List.of());
        plan.put("sceneEvent", "");
        plan.put("joins", List.of());
        plan.put("leaves", List.of());
        plan.put("shouldFinish", false);
        plan.put("finishReason", "");
        plan.put("summary", "");
        // 解析 AI 编排输出（纯逻辑，静态可测）
        Map<String, Object> parsed = parsePlan(result.content(), active.stream()
                .map(ActorEvolutionParticipant::getCharacterId).toList());
        if (parsed == null) {
            // AI 输出无 JSON：兜底——让一位在场角色说一句话保持推进
            if (!active.isEmpty()) {
                plan.put("messages", List.of(Map.of(
                        "characterId", active.get(0).getCharacterId(),
                        "type", "text",
                        "content", "……（沉默片刻）")));
            }
            return plan;
        }
        plan.putAll(parsed);
        return plan;
    }

    /**
     * 解析 AI 编排输出（静态纯逻辑，可单测）。
     * <p>规则：只保留在场角色的 messages；空 messages 时用首位在场角色兜底一句推进；
     * joins/leaves 输出 {characterId, reason}；shouldFinish/finishReason/summary 透传。</p>
     *
     * @param json      AI 输出文本
     * @param activeIds 当前在场角色 ID 列表
     * @return 编排计划 Map（无有效 JSON 返回 null）
     */
    static Map<String, Object> parsePlan(String json, List<Long> activeIds) {
        if (json == null) {
            return null;
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            Map<String, Object> plan = new LinkedHashMap<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (JsonNode m : node.path("messages")) {
                Long cid = m.path("characterId").asLong(-1);
                if (cid <= 0 || activeIds == null || !activeIds.contains(cid)) {
                    continue;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("characterId", cid);
                msg.put("type", "text".equals(m.path("type").asText("text")) ? "text" : "action");
                msg.put("content", m.path("content").asText(""));
                if (msg.get("content") != null && !String.valueOf(msg.get("content")).isBlank()) {
                    messages.add(msg);
                }
            }
            if (messages.isEmpty() && activeIds != null && !activeIds.isEmpty()) {
                messages.add(Map.of("characterId", activeIds.get(0), "type", "text", "content", "……"));
            }
            plan.put("messages", messages);
            plan.put("sceneEvent", node.path("sceneEvent").asText(""));
            plan.put("joins", nodeArrayOf(node, "joins"));
            plan.put("leaves", nodeArrayOf(node, "leaves"));
            plan.put("shouldFinish", node.path("shouldFinish").asBoolean(false));
            plan.put("finishReason", node.path("finishReason").asText(""));
            plan.put("summary", node.path("summary").asText(""));
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 joins/leaves 数组（静态，{characterId, reason}）。
     *
     * @param node  JSON 节点
     * @param field joins / leaves
     * @return 解析后的列表
     */
    private static List<Map<String, Object>> nodeArrayOf(JsonNode node, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("characterId", item.path("characterId").asLong(-1));
            m.put("reason", item.path("reason").asText(""));
            out.add(m);
        }
        return out;
    }

    // ==================== 手动加入/退场 ====================

    /**
     * 手动加入一位角色（用户操作，系统消息说明）。
     *
     * @param userId      归属用户 ID
     * @param evolutionId 演化会话 ID
     * @param characterId 角色 ID
     */
    @Transactional
    public void join(Long userId, Long evolutionId, Long characterId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法加入角色");
        }
        requireCharacterInProject(characterId, evolution.getProjectId());
        ActorEvolutionParticipant p = participantRepository
                .findByEvolutionIdAndCharacterId(evolutionId, characterId).orElseGet(() -> {
                    ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                    np.setEvolutionId(evolutionId);
                    np.setCharacterId(characterId);
                    return np;
                });
        p.setStatus("active");
        p.setLeaveAt(null);
        participantRepository.save(p);
        addTurn(evolutionId, characterId, "system", "system", charName(characterId) + " 受邀加入现场（玩家操作）。");
        log.info("[演化] 手动加入角色：演化={} 角色={}", evolutionId, characterId);
    }

    /**
     * 手动退场一位角色（用户操作，系统消息说明）。
     *
     * @param userId      归属用户 ID
     * @param evolutionId 演化会话 ID
     * @param characterId 角色 ID
     */
    @Transactional
    public void leave(Long userId, Long evolutionId, Long characterId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法操作角色");
        }
        ActorEvolutionParticipant p = participantRepository
                .findByEvolutionIdAndCharacterId(evolutionId, characterId)
                .orElseThrow(() -> new BizException(404, "该角色不在演化中"));
        if ("active".equals(p.getStatus())) {
            p.setStatus("left");
            p.setLeaveAt(LocalDateTime.now());
            participantRepository.save(p);
            addTurn(evolutionId, characterId, "system", "system", charName(characterId) + " 离开现场（玩家操作）。");
        }
        log.info("[演化] 手动退场角色：演化={} 角色={}", evolutionId, characterId);
    }

    /**
     * 删除演化会话（vP5-7.11）。
     * <p>级联清理参与者与轮次消息；已归档的事件（时间线节点）保留（保留编年史，避免事件孤儿化）。
     * 演化正在播放中禁止删除。</p>
     *
     * @param userId      归属用户 ID
     * @param evolutionId 演化会话 ID
     */
    @Transactional
    public void delete(Long userId, Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        AtomicBoolean lock = streamLocks.get(evolutionId);
        if (lock != null && lock.get()) {
            throw new BizException(400, "演化正在播放中，请先停止播放再删除");
        }
        // 级联清理：参与者 + 轮次消息 + 演化会话本体（归档事件保留在时间线）
        participantRepository.deleteByEvolutionId(evolutionId);
        turnRepository.deleteByEvolutionId(evolutionId);
        evolutionRepository.delete(evolution);
        streamLocks.remove(evolutionId);
        log.info("[演化] 删除：演化={} 项目={}", evolutionId, evolution.getProjectId());
    }

    // ==================== 群聊式连续演化（SSE 流式，vP5-7.9） ====================

    /**
     * 连续演化流式播放（与群聊运行逻辑一致）：
     * <p>循环「调度 → 选最有发言/行动欲望的角色 → 该角色流式发言或行动 → 可选场景变化/加入退场」，
     * 一拍一拍持续推进，<b>不按轮次计算</b>。停止条件仅两个：
     * ① 用户手动停止（SSE 连接关闭）；② 场景只剩 1 名角色（自动收尾归档）。
     * 每个调度决策与每条发言/行动都通过 SSE 事件推给前端（schedule/message-start/token/done/system/finished/error）。</p>
     *
     * @param emitter     SSE 发射器
     * @param evolutionId 演化会话 ID
     * @param userId      归属用户 ID（请求线程捕获传入，流式线程无 SecurityContext）
     * @param alive       连接存活标志（超时/断开后置 false，循环据此停止）
     */
    public void stream(SseEmitter emitter, Long evolutionId, Long userId, AtomicBoolean alive) {
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        if (!"running".equals(evolution.getStatus())) {
            pushEventSafe(emitter, "error", Map.of("message", "演化已结束，无法继续播放"));
            emitter.complete();
            return;
        }
        // 并发互斥：同一演化只允许一个播放流推进（防多 Tab/双击重复推进）
        AtomicBoolean lock = streamLocks.computeIfAbsent(evolutionId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            pushEventSafe(emitter, "error", Map.of("message", "该演化已在播放中，请先停止"));
            emitter.complete();
            return;
        }
        long streamStart = System.currentTimeMillis();
        int beats = 0;
        log.info("[演化-流] 任务开始：演化={} 标题={}", evolutionId, evolution.getTitle());
        try {
            while (alive.get()) {
                // 停止条件①：场景只剩 1 名角色 → 自动收尾归档（用户需求：除手动停止或只剩一人外不停止）
                List<ActorEvolutionParticipant> active = participantRepository
                        .findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                        .filter(p -> "active".equals(p.getStatus())).toList();
                if (active.size() <= 1) {
                    log.info("[演化-流] 场景只剩 {} 名角色，自动收尾归档：演化={}", active.size(), evolutionId);
                    Map<String, Object> archive = finish(userId, evolutionId);
                    pushEventSafe(emitter, "finished", archive == null ? Map.of() : archive);
                    break;
                }
                if (!alive.get()) {
                    break;
                }
                // ① 调度：选发言人 + 决定对话/行动 + 可选场景变化/加入退场（一次 AI 调用）
                Map<String, Object> decision = scheduleBeat(userId, evolution, active);
                if (decision == null || !alive.get()) {
                    break;
                }
                pushEvent(emitter, "schedule", decision);
                // ② 场景环境变化（低频：模型仅在有意义变化时输出）→ 系统消息
                String sceneEvent = str(decision.get("sceneEvent"));
                if (sceneEvent != null && !sceneEvent.isBlank()) {
                    String sys = "【环境】" + sceneEvent.trim();
                    addTurn(evolutionId, null, "system", "system", sys);
                    pushEvent(emitter, "system", Map.of("content", sys));
                }
                // ③ 加入/退场（低频、有理有据）→ 系统消息
                applyJoinsLeaves(userId, evolution, castList(decision.get("joins")), castList(decision.get("leaves")), emitter);
                // ④ 该角色发言/行动（流式，与群聊一致）
                Long cid = asLong(decision.get("characterId"));
                if (cid != null) {
                    String beatType = "action".equals(str(decision.get("beatType"))) ? "action" : "text";
                    boolean ok = generateBeat(emitter, evolution, cid, userId, beatType, alive, str(decision.get("reason")));
                    if (!ok) {
                        // 连接已断开或本拍生成失败：停止本流（避免对断开的连接继续推送/空转）
                        break;
                    }
                }
                // ⑤ 推进计数（节拍数，非轮次上限——演化不会因达到上限而停止）
                beats++;
                int nextCount = (evolution.getTurnCount() == null ? 0 : evolution.getTurnCount()) + 1;
                evolution.setTurnCount(nextCount);
                evolutionRepository.save(evolution);
            }
            if (alive.get()) {
                log.info("[演化-流] 任务结束：演化={} 共推进 {} 节拍 耗时={}ms", evolutionId, beats,
                        System.currentTimeMillis() - streamStart);
            } else {
                log.info("[演化-流] 任务被手动停止：演化={} 已推进 {} 节拍", evolutionId, beats);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (!errMsg.contains("SSE 连接已断开")) {
                log.warn("[演化-流] 任务异常：演化={} : {}", evolutionId, e.getMessage());
                pushEventSafe(emitter, "error", Map.of("message", friendlyEvolutionError(e)));
            }
        } finally {
            lock.set(false);
            emitter.complete();
        }
    }

    /**
     * 逐拍调度：AI 根据世界观/场景/在场角色/最近剧情，选出最有发言/行动欲望的角色，
     * 决定本拍类型（对话 text / 行为 action），并可选输出场景变化与加入/退场。
     * <p>健壮性：AI 调用或解析失败时重试 {@link #SCHEDULE_MAX_RETRY} 次（模型偶发空输出/JSON 损坏/
     * 选中不在场角色），每次失败打印原始输出与在场列表便于诊断；重试耗尽后按在场顺序轮询兜底。</p>
     *
     * @param userId    归属用户 ID
     * @param evolution 演化会话
     * @param active    在场角色
     * @return 调度决策；AI 不可用/解析失败时兜底选在场角色（轮询）
     */
    private Map<String, Object> scheduleBeat(Long userId, ActorEvolution evolution,
                                             List<ActorEvolutionParticipant> active) {
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(evolution.getProjectId())
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(evolution);
        String charactersText = active.stream()
                .map(p -> "ID " + p.getCharacterId() + " " + charName(p.getCharacterId())
                        + "\n" + personaSummary(p.getCharacterId()))
                .collect(Collectors.joining("\n\n"));
        String history = turnRepository.findByEvolutionIdOrderByIdAsc(evolution.getId()).stream()
                .limit(EVOLUTION_CONTEXT_WINDOW)
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (history.isBlank()) {
            history = "（剧情刚开始，还没有任何发展）";
        }
        List<Long> activeIds = active.stream().map(ActorEvolutionParticipant::getCharacterId).toList();

        String prompt = promptTemplateService.render(userId, evolution.getProjectId(),
                PromptTemplateService.CODE_EVOLUTION_SCHEDULE, Map.of(
                        "world_setting", worldSetting.isBlank() ? "（未提供）" : worldSetting,
                        "scene_background", sceneBackground,
                        "characters", charactersText,
                        "history", history));
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), EVOLVE_TEMPERATURE, SCHEDULE_MAX_TOKENS, true);

        Map<String, Object> decision = null;
        for (int attempt = 1; attempt <= SCHEDULE_MAX_RETRY; attempt++) {
            try {
                long startMs = System.currentTimeMillis();
                AiChatResult result = aiProviderRouter.chatCompletion(userId, evolution.getProjectId(), null, req);
                usageLogService.record(userId, evolution.getProjectId(), null, result.providerId(), result.model(),
                        "action", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - startMs));
                decision = parseSchedule(result.content(), activeIds);
                if (decision != null) {
                    break;
                }
                // 解析失败：打印原始输出与在场列表，便于定位（选中不在场角色 / JSON 损坏 / 空输出）
                log.warn("[演化-流] 第 {} 次调度输出无法解析（在场角色ID={}）：原始输出（前 300 字）={}",
                        attempt, activeIds, truncate(result.content(), 300));
            } catch (Exception e) {
                log.warn("[演化-流] 第 {} 次调度 AI 调用失败: {}", attempt, e.getMessage());
            }
        }
        if (decision == null) {
            log.warn("[演化-流] 调度重试耗尽，按在场顺序轮询兜底：在场角色ID={}", activeIds);
            return fallbackSchedule(evolution, active);
        }
        log.info("[演化-流] 调度：演化={} 选中角色ID={} desire={} 类型={} 理由={} 场景变化={} 加入={} 退场={}",
                evolution.getId(), decision.get("characterId"), decision.get("desire"),
                decision.get("beatType"), decision.get("reason"), decision.get("sceneEvent"),
                decision.get("joins"), decision.get("leaves"));
        return decision;
    }

    /**
     * 解析逐拍调度输出（静态纯逻辑，可单测）。
     * <p>规则：characterId 必须在场；desire 收敛 1~5；beatType 收敛 text/action；
     * sceneEvent/joins/leaves 透传。无效 JSON 或选中不在场角色返回 null。</p>
     *
     * @param json      AI 输出文本
     * @param activeIds 在场角色 ID 列表
     * @return 调度决策 Map；无有效 JSON 返回 null
     */
    static Map<String, Object> parseSchedule(String json, List<Long> activeIds) {
        if (json == null) {
            return null;
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            long cid = node.path("characterId").asLong(-1);
            if (cid <= 0 || activeIds == null || !activeIds.contains(cid)) {
                return null;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("characterId", cid);
            d.put("desire", Math.max(1, Math.min(5, node.path("desire").asInt(3))));
            d.put("reason", node.path("reason").asText(""));
            d.put("beatType", "action".equals(node.path("beatType").asText("text")) ? "action" : "text");
            d.put("sceneEvent", node.path("sceneEvent").asText(""));
            d.put("joins", nodeArrayOf(node, "joins"));
            d.put("leaves", nodeArrayOf(node, "leaves"));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 调度兜底：AI 不可用时按在场顺序<strong>轮询</strong>选角色（用 turnCount 做轮转偏移，
     * 避免兜底时永远同一角色发言变成独角戏），保证演化持续推进不中断。
     *
     * @param evolution 演化会话
     * @param active    在场角色
     * @return 兜底调度决策
     */
    private Map<String, Object> fallbackSchedule(ActorEvolution evolution, List<ActorEvolutionParticipant> active) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (active.isEmpty()) {
            d.put("characterId", null);
            d.put("desire", BEAT_DESIRE_THRESHOLD);
            d.put("reason", "无在场角色");
            d.put("beatType", "text");
            d.put("sceneEvent", "");
            d.put("joins", List.of());
            d.put("leaves", List.of());
            return d;
        }
        int idx = (evolution.getTurnCount() == null ? 0 : evolution.getTurnCount()) % active.size();
        d.put("characterId", active.get(idx).getCharacterId());
        d.put("desire", BEAT_DESIRE_THRESHOLD);
        d.put("reason", "调度暂不可用，按在场顺序轮询推进");
        d.put("beatType", "text");
        d.put("sceneEvent", "");
        d.put("joins", List.of());
        d.put("leaves", List.of());
        return d;
    }

    /**
     * 应用调度中的加入/退场（低频、有理有据），并推送系统消息。
     *
     * @param userId    归属用户 ID
     * @param evolution 演化会话
     * @param joins     加入列表
     * @param leaves    退场列表
     * @param emitter   SSE 发射器
     */
    private void applyJoinsLeaves(Long userId, ActorEvolution evolution, List<Map<String, Object>> joins,
                                  List<Map<String, Object>> leaves, SseEmitter emitter) {
        for (Map<String, Object> j : joins) {
            Long cid = asLong(j.get("characterId"));
            if (cid == null) {
                continue;
            }
            requireCharacterInProject(cid, evolution.getProjectId());
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolution.getId(), cid).orElseGet(() -> {
                        ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                        np.setEvolutionId(evolution.getId());
                        np.setCharacterId(cid);
                        return np;
                    });
            p.setStatus("active");
            p.setLeaveAt(null);
            participantRepository.save(p);
            String reason = str(j.get("reason"));
            String sys = charName(cid) + " 登场：" + (reason == null || reason.isBlank() ? "来到了现场。" : reason);
            addTurn(evolution.getId(), cid, "system", "system", sys);
            pushEvent(emitter, "system", Map.of("content", sys));
        }
        for (Map<String, Object> l : leaves) {
            Long cid = asLong(l.get("characterId"));
            if (cid == null) {
                continue;
            }
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolution.getId(), cid).orElse(null);
            if (p != null && "active".equals(p.getStatus())) {
                p.setStatus("left");
                p.setLeaveAt(LocalDateTime.now());
                participantRepository.save(p);
                String reason = str(l.get("reason"));
                String sys = charName(cid) + " 退场：" + (reason == null || reason.isBlank() ? "离开了现场。" : reason);
                addTurn(evolution.getId(), cid, "system", "system", sys);
                pushEvent(emitter, "system", Map.of("content", sys));
            }
        }
    }

    /**
     * 生成单个角色的发言/行动（流式，与群聊一致）：
     * 组装「角色卡 + 世界观 + 场景背景 + 长期记忆 + 最近剧情 + 本拍指令」→ 流式调用 AI →
     * 逐 token 推送 → 完成后落库为演化轮次消息。
     *
     * @param emitter        SSE 发射器
     * @param evolution      演化会话
     * @param characterId    发言/行动角色 ID
     * @param userId         归属用户 ID
     * @param beatType       text 对话 / action 行为
     * @param alive          连接存活标志
     * @param scheduleReason 调度理由（注入指令，可空）
     * @return true 成功；false 失败（连接断开/生成失败）
     */
    private boolean generateBeat(SseEmitter emitter, ActorEvolution evolution, Long characterId,
                                 Long userId, String beatType, AtomicBoolean alive, String scheduleReason) {
        String speaker = charName(characterId);
        log.info("[演化-流] 角色={}（ID={}）开始{}：演化={}", speaker, characterId,
                "action".equals(beatType) ? "行动" : "发言", evolution.getId());
        pushEvent(emitter, "message-start", Map.of("characterId", characterId, "characterName", speaker, "type", beatType));
        long startMs = System.currentTimeMillis();
        // 重试机制：DeepSeek 等模型偶发「只输出思考（reasoning_content）而正文 content 为空」，
        // 导致角色回复为空——空输出自动重试，重试耗尽用程序化兜底台词保证剧情不空档
        String content = "";
        int[] usage = new int[4]; // 0=prompt 1=completion 2=cacheHit 3=cacheMiss
        try {
            List<AiChatRequest.ChatMessage> msgs = buildBeatMessages(evolution, characterId, userId, beatType, scheduleReason);
            for (int attempt = 1; attempt <= BEAT_MAX_RETRY && alive.get(); attempt++) {
                StringBuilder full = new StringBuilder();
                usage[0] = usage[1] = usage[2] = usage[3] = 0;
                try {
                    aiProviderRouter.chatCompletionStream(userId, evolution.getProjectId(), null,
                            new AiChatRequest(null, msgs, 0.8, BEAT_REPLY_MAX_TOKENS),
                            delta -> {
                                if (!alive.get()) {
                                    throw new RuntimeException("SSE 连接已断开");
                                }
                                full.append(delta);
                                pushToken(emitter, delta, characterId);
                            },
                            usageInfo -> {
                                usage[0] = usageInfo.promptTokens();
                                usage[1] = usageInfo.completionTokens();
                                usage[2] = usageInfo.cacheHitTokens();
                                usage[3] = usageInfo.cacheMissTokens();
                            });
                } catch (Exception e) {
                    String errMsg = e.getMessage() == null ? "" : e.getMessage();
                    if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                        return false; // 连接断开，安静结束（不重试）
                    }
                    throw e; // 其他生成异常：抛出由外层处理（推送 error 事件）
                }
                content = full.toString().trim();
                if (!content.isBlank()) {
                    break;
                }
                log.warn("[演化-流] 第 {} 次角色={} 输出为空（completionTokens={}），重试生成", attempt, speaker, usage[1]);
            }
            if (content.isBlank()) {
                // 重试耗尽仍为空：程序化兜底台词（保证不出现空消息、剧情不断档）
                content = "（" + speaker + "沉默了片刻，似乎在酝酿着什么。）";
                log.warn("[演化-流] 角色={} 多次输出为空，使用程序化兜底台词", speaker);
            }
            addTurn(evolution.getId(), characterId, "assistant", beatType, content);
            pushEvent(emitter, "done", Map.of("characterId", characterId, "type", beatType, "content", content,
                    "tokenIn", usage[0], "tokenOut", usage[1]));
            usageLogService.record(userId, evolution.getProjectId(), characterId, null, null, "action",
                    usage[0], usage[1], usage[2], usage[3], (int) (System.currentTimeMillis() - startMs));
            log.info("[演化-流] 角色={}：{}", speaker, content);
            log.info("[演化-流] 角色={} {}完成：耗时={}ms tokens={}/{}", speaker,
                    "action".equals(beatType) ? "行动" : "发言",
                    System.currentTimeMillis() - startMs, usage[0], usage[1]);
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (errMsg.contains("SSE 连接已断开") || e instanceof IllegalStateException) {
                return false; // 连接断开，安静结束
            }
            // 非断开原因的生成失败：推送明确 error 事件，前端可显示真实错误
            log.warn("[演化-流] 角色生成失败：演化={} char={} : {}", evolution.getId(), characterId, e.getMessage());
            pushEventSafe(emitter, "error", Map.of("message", friendlyEvolutionError(e)));
            return false;
        }
    }

    /**
     * 组装单个角色的本拍消息：角色卡 system_prompt + 世界观/场景背景 + 长期记忆 + 最近剧情 + 本拍指令。
     * <p>核心：演化与场景环境息息相关（注入场景背景与世界观），并区分「对话」与「行为」两种节拍。</p>
     *
     * @param evolution      演化会话
     * @param characterId    发言/行动角色 ID
     * @param userId         归属用户 ID（记忆注入凭据归属）
     * @param beatType       text 对话 / action 行为
     * @param scheduleReason 调度理由（可空）
     * @return 消息序列
     */
    private List<AiChatRequest.ChatMessage> buildBeatMessages(ActorEvolution evolution, Long characterId,
                                                              Long userId, String beatType, String scheduleReason) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", resolveCharacterSystemPrompt(characterId)));

        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(evolution.getProjectId())
                .map(s -> s.getFreeText()).orElse("");
        String sceneBackground = buildSceneBackground(evolution);
        String gameTime = currentGameTimeText(evolution.getProjectId());
        StringBuilder ctx = new StringBuilder();
        ctx.append("这是一场场景化世界演化（非普通对话），发生在特定的场景/地点中。你的一举一动都在场景里发生、可被其他角色观察到；")
                .append("说话用第一人称，行动用第三人称描述行为（可夹在对话中，如：（她端起咖啡杯））。");
        if (!worldSetting.isBlank()) {
            ctx.append("\n\n【世界观】\n").append(worldSetting);
        }
        if (gameTime != null && !gameTime.isBlank()) {
            ctx.append("\n\n【当前游戏时刻】").append(gameTime);
        }
        ctx.append("\n\n【场景背景】\n").append(sceneBackground.isBlank() ? "（全局演化，无固定场景）" : sceneBackground);
        messages.add(new AiChatRequest.ChatMessage("system", ctx.toString()));

        // 长期记忆注入（角色级 + 项目级，让角色记住过往经历）
        String memory = memoryService.memoryContext(userId, evolution.getProjectId(), characterId);
        if (!memory.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", memory));
        }

        // 最近剧情（带角色名前缀）
        List<ActorEvolutionTurn> history = turnRepository.findByEvolutionIdOrderByIdAsc(evolution.getId());
        int from = Math.max(0, history.size() - EVOLUTION_CONTEXT_WINDOW);
        for (int i = from; i < history.size(); i++) {
            ActorEvolutionTurn t = history.get(i);
            String name = t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId());
            messages.add(new AiChatRequest.ChatMessage("system", name + "：" + t.getContent()));
        }

        // 本拍指令（群聊式接话：回应上一拍，区分对话/行为）
        String speaker = speakerName(characterId);
        String instruction = "当前轮到【" + speaker + "】。这一拍是【"
                + ("action".equals(beatType) ? "行为" : "对话") + "】。"
                + ("action".equals(beatType)
                ? "请主要用行动/行为推进剧情（如站起身、走向某物、拿起/放下、望向某人、做某个表情或动作），可辅以简短台词，用第三人称描述。"
                : "请主要用第一人称说话，自然接续最近剧情，回应在场角色的最新言行，可夹带动作描写（用括号）。")
                + "结合【场景背景】【世界观】与最近剧情，像真实社交中身处该场景的角色一样反应，有互动、有情绪、逻辑自洽，不要机械重复。"
                + "不要替其他角色发言，不要转述其他角色的台词，不要以【】或『某某说：』开头。";
        if (scheduleReason != null && !scheduleReason.isBlank()) {
            instruction = instruction + "\n（调度理由：" + scheduleReason + "）";
        }
        messages.add(new AiChatRequest.ChatMessage("system", instruction));
        return messages;
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
     * 当前项目游戏时刻文本（供本拍上下文注入；时钟读取失败返回空串）。
     *
     * @param projectId 项目 ID
     * @return 游戏时刻文本（如「清晨 · 第 3 日」）
     */
    private String currentGameTimeText(Long projectId) {
        try {
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            long hour = WorldClockService.gameHourOf(LocalDateTime.now(), clock.getWorldStartAt(),
                    clock.getWorldStartGameHour() == null ? 0L : clock.getWorldStartGameHour(),
                    clock.getRate() == null ? 24 : clock.getRate());
            return WorldClockService.formatGameTime(hour);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 推送 SSE 事件（连接断开时抛出 RuntimeException 终止循环）。
     *
     * @param emitter SSE 发射器
     * @param name    事件名
     * @param data    事件数据
     */
    private void pushEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            throw new RuntimeException("SSE 连接已断开", e);
        }
    }

    /**
     * 推送 SSE 事件（安全版：忽略连接断开异常，供开始/锁冲突等前置校验使用）。
     */
    private void pushEventSafe(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignored) {
            // 前端已断开时忽略推送失败
        }
    }

    /**
     * 推送 token 增量事件（带角色 ID）。
     */
    private void pushToken(SseEmitter emitter, String delta, Long characterId) {
        pushEvent(emitter, "token", Map.of("delta", delta, "characterId", characterId));
    }

    /**
     * 生成失败的用户友好提示。
     *
     * @param e 异常
     * @return 中文提示
     */
    private String friendlyEvolutionError(Exception e) {
        if (e instanceof BizException be) {
            return be.getMessage();
        }
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) {
            return ae.getMessage();
        }
        return "演化播放失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
    }

    // ==================== 结束归档 ====================

    /**
     * 结束演化并归档：AI 生成事件（标题+内容）写入 actor_event；给每位参与者（含已退场）
     * 写入<b>角色级事实记忆</b>（仅当事人知道，防止不相干角色知晓这段经历）。
     *
     * @param userId      归属用户 ID
     * @param evolutionId 演化会话 ID
     * @return {event, memoryCount}
     */
    @Transactional
    public Map<String, Object> finish(Long userId, Long evolutionId) {
        // 显式 userId 归属校验：本方法会被演化流式线程调用（无 SecurityContext，不能走 requireEvolution(id)）
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        if ("finished".equals(evolution.getStatus()) && evolution.getEventId() != null) {
            return Map.of("event", eventVO(eventRepository.findById(evolution.getEventId()).orElse(null)),
                    "memoryCount", 0, "already", true);
        }
        // ① AI 生成归档事件
        String archiveJson = archiveEvent(userId, evolution);
        ActorEvent event = new ActorEvent();
        event.setProjectId(evolution.getProjectId());
        event.setKind("evolution");
        event.setSceneId(evolution.getSceneId());
        event.setEvolutionId(evolution.getId());
        event.setSource("evolution");
        event.setTitle(str(JsonUtil.extractField(archiveJson, "title")));
        event.setContent(str(JsonUtil.extractField(archiveJson, "content")));
        if (event.getContent() == null || event.getContent().isBlank()) {
            event.setContent("（该次世界演化未留下明确记录）");
        }
        event.setGameHour(currentGameHour(evolution.getProjectId()));
        event = eventRepository.save(event);

        // ② 给每位参与者（含已退场）写角色级事实记忆（仅当事人）
        List<ActorEvolutionParticipant> participants = participantRepository
                .findByEvolutionIdOrderByIdAsc(evolutionId);
        int memoryCount = 0;
        String memoryContent = "【演化事件】" + (event.getTitle() == null ? "" : event.getTitle() + "：")
                + event.getContent();
        for (ActorEvolutionParticipant p : participants) {
            ActorMemory memory = new ActorMemory();
            memory.setProjectId(evolution.getProjectId());
            memory.setCharacterId(p.getCharacterId());
            memory.setKind("fact");
            memory.setContent(memoryContent);
            memory.setImportance(ARCHIVE_MEMORY_IMPORTANCE);
            memoryRepository.save(memory);
            memoryCount++;
        }

        // ③ 更新演化状态
        evolution.setStatus("finished");
        evolution.setEventId(event.getId());
        evolution.setAiSummary(event.getContent());
        evolution.setFinishedAt(LocalDateTime.now());
        evolutionRepository.save(evolution);

        log.info("[演化] 结束归档：演化={} 事件={} 记忆={} 条（仅当事人）", evolutionId, event.getId(), memoryCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", eventVO(event));
        out.put("memoryCount", memoryCount);
        return out;
    }

    /**
     * AI 生成归档事件（标题+内容）。
     *
     * @param userId    归属用户 ID
     * @param evolution 演化会话
     * @return JSON 文本 {title, content}
     */
    private String archiveEvent(Long userId, ActorEvolution evolution) {
        List<ActorEvolutionTurn> turns = turnRepository.findByEvolutionIdOrderByIdAsc(evolution.getId());
        String transcript = turns.stream()
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (transcript.length() > 6000) {
            transcript = transcript.substring(transcript.length() - 6000);
        }
        String prompt = """
                你是一位世界编年史官。请根据下列【演化经过】，将这次世界演化整理归档为一条「世界事件」，输出一个 JSON 对象，禁止输出其他文字。

                —— 输出结构 ——
                { "title": "事件标题（一句话，如：深夜咖啡馆里的密谈）", "content": "事件内容（120字内：时间地点、发生了什么、关键对话/行为、结果与后续）" }

                —— 要求 ——
                1. 只整理客观发生的事，不添加未发生的设定。
                2. content 要能让一个「当时在场的人」据此回忆并讲述，不必复述全部对话。

                —— 世界观 ——
                %s

                —— 演化经过 ——
                %s
                """.formatted(
                worldSettingRepository.findTopByProjectIdOrderByVersionDesc(evolution.getProjectId())
                        .map(s -> s.getFreeText()).orElse("（未提供）"),
                transcript.isBlank() ? "（无内容）" : transcript);

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), ARCHIVE_TEMPERATURE, ARCHIVE_MAX_TOKENS, true);
        try {
            AiChatResult result = aiProviderRouter.chatCompletion(userId, evolution.getProjectId(), null, req);
            usageLogService.record(userId, evolution.getProjectId(), null, result.providerId(), result.model(), "dialog",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json != null) {
                return json;
            }
        } catch (Exception e) {
            log.warn("[演化] 归档 AI 调用失败，使用摘要兜底: {}", e.getMessage());
        }
        // 兜底：用演化标题 + 最后一条摘要
        return "{\"title\":\"" + (evolution.getTitle() == null ? "世界演化" : evolution.getTitle())
                + "\",\"content\":\"" + (evolution.getBackground() == null ? "" : evolution.getBackground().replace("\"", ""))
                + "\"}";
    }

    // ==================== 查询 ====================

    /**
     * 项目演化会话列表（进行中优先）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @return 演化会话视图列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId, Long projectId) {
        requireProject(projectId);
        return evolutionRepository.findByProjectIdOrderByStatusAscIdDesc(projectId).stream()
                .map(this::summaryVO).toList();
    }

    /**
     * 演化会话详情（含参与者与轮次消息）。
     *
     * @param userId      归属用户 ID
     * @param evolutionId 演化会话 ID
     * @return 详情视图
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", evolution.getId());
        vo.put("projectId", evolution.getProjectId());
        vo.put("sceneId", evolution.getSceneId());
        vo.put("sceneName", sceneName(evolution.getSceneId()));
        vo.put("title", evolution.getTitle());
        vo.put("background", evolution.getBackground());
        vo.put("mode", evolution.getMode());
        vo.put("status", evolution.getStatus());
        vo.put("turnCount", evolution.getTurnCount());
        vo.put("eventId", evolution.getEventId());
        vo.put("aiSummary", evolution.getAiSummary());
        vo.put("finishedAt", evolution.getFinishedAt());
        vo.put("createdAt", evolution.getCreatedAt());
        vo.put("participants", participantRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .map(p -> {
                    // 必须用可变 Map：leaveAt 对在场参与者为 null，Map.of 遇 null 值会抛 NPE
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("characterId", p.getCharacterId());
                    m.put("name", charName(p.getCharacterId()));
                    m.put("status", p.getStatus());
                    m.put("joinAt", p.getJoinAt());
                    m.put("leaveAt", p.getLeaveAt());
                    return m;
                })
                .toList());
        vo.put("turns", turnRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("characterId", t.getCharacterId());
                    m.put("characterName", t.getCharacterId() == null ? null : charName(t.getCharacterId()));
                    m.put("role", t.getRole());
                    m.put("type", t.getType());
                    m.put("content", t.getContent());
                    m.put("createdAt", t.getCreatedAt());
                    return m;
                })
                .toList());
        return vo;
    }

    /**
     * 演化会话摘要视图（列表用）。
     *
     * @param evolution 演化会话
     * @return 摘要视图
     */
    private Map<String, Object> summaryVO(ActorEvolution evolution) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", evolution.getId());
        m.put("projectId", evolution.getProjectId());
        m.put("sceneId", evolution.getSceneId());
        m.put("sceneName", sceneName(evolution.getSceneId()));
        m.put("title", evolution.getTitle());
        m.put("mode", evolution.getMode());
        m.put("status", evolution.getStatus());
        m.put("turnCount", evolution.getTurnCount());
        m.put("eventId", evolution.getEventId());
        m.put("aiSummary", evolution.getAiSummary());
        m.put("finishedAt", evolution.getFinishedAt());
        m.put("createdAt", evolution.getCreatedAt());
        m.put("participantCount", participantRepository.findByEvolutionIdOrderByIdAsc(evolution.getId()).size());
        return m;
    }

    // ==================== 辅助 ====================

    /**
     * 收尾判定（静态纯逻辑，可单测）。
     * <p>规则：AI 判定收束 / 在场角色 ≤ 1 / 达到轮次上限（保证不会无限演化），任一成立即收尾。</p>
     *
     * @param aiFinish   AI 是否判定剧情收束
     * @param remaining  当前在场角色数
     * @param turnCount  已推进轮次数
     * @param maxTurns   轮次上限
     * @return true 表示应开始收尾
     */
    static boolean shouldFinish(boolean aiFinish, int remaining, int turnCount, int maxTurns) {
        return aiFinish || remaining <= 1 || (maxTurns > 0 && turnCount >= maxTurns);
    }

    /**
     * 写入一条演化轮次消息。
     */
    private void addTurn(Long evolutionId, Long characterId, String role, String type, String content) {
        ActorEvolutionTurn turn = new ActorEvolutionTurn();
        turn.setEvolutionId(evolutionId);
        turn.setCharacterId(characterId);
        turn.setRole(role);
        turn.setType(type);
        turn.setContent(content);
        turn.setGameHour(currentGameHourOf(evolutionId));
        turnRepository.save(turn);
    }

    /**
     * 当前游戏时刻（小时数）。
     */
    private Long currentGameHour(Long projectId) {
        try {
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            return WorldClockService.gameHourOf(LocalDateTime.now(), clock.getWorldStartAt(),
                    clock.getWorldStartGameHour() == null ? 0 : clock.getWorldStartGameHour(),
                    clock.getRate() == null ? 24 : clock.getRate());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 演化会话的当前游戏时刻（通过演化查项目）。
     */
    private Long currentGameHourOf(Long evolutionId) {
        try {
            ActorEvolution ev = evolutionRepository.findById(evolutionId).orElse(null);
            return ev == null ? null : currentGameHour(ev.getProjectId());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 角色人设摘要（从角色卡提取关键信息；无卡时用档案 detail）。
     *
     * @param characterId 角色 ID
     * @return 人设摘要文本
     */
    private String personaSummary(Long characterId) {
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null) return "";
        ActorCharacterCard card = cardRepository.findTopByCharacterIdOrderByVersionDesc(characterId).orElse(null);
        if (card != null && card.getPersonaJson() != null) {
            try {
                JsonNode p = objectMapper.readTree(card.getPersonaJson());
                StringBuilder sb = new StringBuilder();
                sb.append("身份：").append(p.path("identity").path("title").asText("无头衔"))
                        .append("；种族：").append(p.path("identity").path("species").asText("未知"));
                sb.append("\n性格特质：").append(joinArray(p, "personality", "traits"));
                sb.append("\n目标：").append(joinArray(p, "background", "goals"));
                sb.append("\n说话风格：").append(p.path("speechStyle").path("tone").asText("自然"));
                return sb.toString();
            } catch (Exception e) {
                // 忽略，降级
            }
        }
        return "档案：" + (ch.getDetail() == null ? "" : ch.getDetail());
    }

    /**
     * 拼接角色卡数组字段。
     */
    private String joinArray(JsonNode root, String first, String field) {
        JsonNode node = root.path(first).path(field);
        if (!node.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        node.forEach(n -> {
            if (sb.length() > 0) sb.append("、");
            sb.append(n.asText());
        });
        return sb.toString();
    }

    /**
     * 组装场景背景（演化场景 + 演化背景叠加）。
     */
    private String buildSceneBackground(ActorEvolution evolution) {
        StringBuilder sb = new StringBuilder();
        if (evolution.getSceneId() != null) {
            sceneRepository.findById(evolution.getSceneId()).ifPresent(s -> {
                sb.append("地点：").append(s.getName());
                if (s.getDescription() != null) sb.append("（").append(s.getDescription()).append("）");
                if (s.getBackground() != null) sb.append("\n场景背景：").append(s.getBackground());
            });
        } else {
            sb.append("全局（世界）演化，无固定地点。");
        }
        if (evolution.getBackground() != null && !evolution.getBackground().isBlank()) {
            sb.append("\n本场设定：").append(evolution.getBackground());
        }
        return sb.toString();
    }

    /**
     * 场景名解析。
     *
     * @param sceneId 场景 ID（可空）
     * @return 场景名（无则「全局」）
     */
    private String sceneName(Long sceneId) {
        if (sceneId == null) return "全局（世界）";
        return sceneRepository.findById(sceneId).map(ActorScene::getName).orElse("场景 " + sceneId);
    }

    /**
     * 角色名解析。
     *
     * @param characterId 角色 ID
     * @return 角色名（无则「角色」）
     */
    private String charName(Long characterId) {
        if (characterId == null) return "系统";
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse("角色 " + characterId);
    }

    /**
     * 发言人名字（用于本拍指令；无则「你」）。
     *
     * @param characterId 角色 ID
     * @return 角色名或「你」
     */
    private String speakerName(Long characterId) {
        String n = charName(characterId);
        return n == null || n.startsWith("角色") || "系统".equals(n) ? "你" : n;
    }

    /**
     * 角色名列表（中文顿号连接）。
     */
    private String characterNames(List<Long> ids) {
        return ids.stream().map(this::charName).collect(Collectors.joining("、"));
    }

    /**
     * 消息类型中文标签。
     */
    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "action" -> "行动";
            case "system" -> "系统";
            case "event" -> "事件";
            default -> "对话";
        };
    }

    /**
     * 事件视图。
     */
    private Map<String, Object> eventVO(ActorEvent e) {
        if (e == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("projectId", e.getProjectId());
        m.put("kind", e.getKind());
        m.put("title", e.getTitle());
        m.put("content", e.getContent());
        m.put("sceneId", e.getSceneId());
        m.put("evolutionId", e.getEvolutionId());
        m.put("source", e.getSource());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    /**
     * 查询演化会话（归属校验）。
     */
    private ActorEvolution requireEvolution(Long evolutionId) {
        ActorEvolution evolution = evolutionRepository.findById(evolutionId)
                .orElseThrow(() -> new BizException(404, "演化会话不存在"));
        requireProject(evolution.getProjectId());
        return evolution;
    }

    /**
     * 查询演化会话（显式 userId 归属校验，异步/流式线程安全）。
     *
     * @param evolutionId 演化会话 ID
     * @param userId      归属用户 ID（请求线程捕获传入）
     * @return 演化会话实体
     */
    private ActorEvolution requireEvolution(Long evolutionId, Long userId) {
        ActorEvolution evolution = evolutionRepository.findById(evolutionId)
                .orElseThrow(() -> new BizException(404, "演化会话不存在"));
        requireProject(evolution.getProjectId(), userId);
        return evolution;
    }

    /**
     * 校验项目归属当前用户。
     */
    private void requireProject(Long projectId) {
        requireProject(projectId, currentUserProvider.currentUserId());
    }

    /**
     * 校验项目归属指定用户（异步/流式线程用显式 userId 版本，规避无 SecurityContext 回退演示用户）。
     *
     * @param projectId 项目 ID
     * @param userId    归属用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 校验角色属于项目。
     */
    private void requireCharacterInProject(Long characterId, Long projectId) {
        characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
    }

    /**
     * Long 取值辅助。
     */
    private Long asLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Long 列表取值辅助。
     */
    private List<Long> asLongList(Object v) {
        if (v instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object o : list) {
                Long x = asLong(o);
                if (x != null) out.add(x);
            }
            return out;
        }
        return null;
    }

    /**
     * 字符串取值辅助。
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * List 转 Object 列表。
     */
    private List<Map<String, Object>> castList(Object v) {
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    m.forEach((k, val) -> map.put(String.valueOf(k), val));
                    out.add(map);
                }
            }
            return out;
        }
        return List.of();
    }

    /**
     * 字符串截断（null 安全，用于日志输出原始 AI 内容）。
     *
     * @param s   原始文本
     * @param max 最大长度
     * @return 截断后的文本（null 返回空串）
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
