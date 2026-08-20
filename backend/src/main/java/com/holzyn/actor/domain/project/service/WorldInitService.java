package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.character.dto.RelationBatchDTO;
import com.holzyn.actor.domain.character.dto.RelationGenerateDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.service.CharacterCardService;
import com.holzyn.actor.domain.character.service.CharacterRelationService;
import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcDTO;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import com.holzyn.actor.domain.knowledge.service.KnowledgeService;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.dto.WorldLocationDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldLocationService;
import com.holzyn.actor.domain.world.vo.WorldLocationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界初始化工作流服务（2026-08-19 新建项目解析重构配套）。
 * <p>职责：按 6 步自动初始化一个已落库的世界（全部程序运行同时输出后端终端日志 + 工作流回调日志）：
 * ① 根据世界观地理设定生成结构化地点（复用地点 AI 提取 + 全量/合并入库）；
 * ② 生成角色结构化角色卡及其 Prompt（复用角色卡生成，默认跳过已生成 / 重建时全量）；
 * ③ 生成普通型 NPC 字段字典，再生成 5 个普通 NPC（复用字段字典拟定保存 + 居民生成入库）；
 * ④ 生成全局关系拓扑（复用关系生成预览 + 批量入库）；
 * ⑤ 根据世界观信息（历史脉络/时代背景）由 AI 推断当前世界历时间点并设置（不从零开始）；
 * ⑥ 将知识库中的世界观文件尝试向量化。</p>
 * <p>执行策略：全自动一次跑完；任一步骤失败不阻断后续（记录日志后跳过继续）。
 * rebuild=false 时对已生成数据采取幂等跳过（地点合并新增/角色卡补缺/字段字典与居民已存在则跳过/关系补充模式/已向量化跳过）；
 * rebuild=true 时全量重建（地点整表替换/角色卡全量重生成/字段字典与居民重建/关系重建模式/世界时间覆盖/向量化重跑）。</p>
 * <p>所属模块：service/project（新建项目工作流子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldInitService {

    /** 初始化生成的普通型 NPC 数量（默认 5 个） */
    static final int INIT_NPC_COUNT = 5;

    /** AI 世界时间推断最大输出 token */
    private static final int TIME_MAX_TOKENS = 1024;

    /** AI 调用最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 历史脉络送入 AI 推断时间的最大长度 */
    private static final int HISTORY_MAX = 8000;

    /** 默认时钟速率（worldStartAt 反推用；与 WorldClockService 默认一致） */
    private static final int DEFAULT_RATE = 24;

    private final CharacterCardService characterCardService;
    private final CharacterRelationService relationService;
    private final OrdinaryNpcService ordinaryNpcService;
    private final WorldLocationService worldLocationService;
    private final WorldClockService worldClockService;
    private final KnowledgeService knowledgeService;
    private final ActorCharacterRepository characterRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final AiProviderRouter aiProviderRouter;
    private final PromptTemplateService templateService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /**
     * 世界初始化主入口：依次执行 6 步，任一步失败跳过继续。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param rebuild   true=全量重建 / false=跳过已生成（幂等）
     * @param wf        工作流日志回调（可为 null；后端终端日志始终输出）
     * @return 初始化结果 VO
     */
    public WorldInitResultVO runInit(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        long taskStart = System.currentTimeMillis();
        info(wf, "[世界初始化] 任务开始：项目=" + projectId + "，模式=" + (rebuild ? "全量重建" : "跳过已生成（幂等）"));

        // ① 世界观地点
        stage(wf, "世界观地点", 1, 6);
        int locations = initLocations(userId, projectId, rebuild, wf);

        // ② 角色卡
        stage(wf, "角色卡", 2, 6);
        int cards = initCharacterCards(userId, projectId, rebuild, wf);

        // ③ 字段字典 + 5 个普通 NPC
        stage(wf, "字段字典与普通 NPC", 3, 6);
        int npcs = initFieldDictAndNpcs(userId, projectId, rebuild, wf);

        // ④ 全局关系拓扑
        stage(wf, "关系拓扑", 4, 6);
        int relations = initRelations(userId, projectId, rebuild, wf);

        // ⑤ 世界时间
        stage(wf, "世界时间", 5, 6);
        String gameTimeText = initWorldTime(userId, projectId, wf);

        // ⑥ 知识向量化
        stage(wf, "知识向量化", 6, 6);
        int vectorized = initKnowledgeVectorize(userId, projectId, wf);

        info(wf, "[世界初始化] 任务结束：耗时 " + (System.currentTimeMillis() - taskStart) + "ms，"
                + "地点 " + locations + " 个 / 角色卡 " + cards + " 张 / 普通 NPC " + npcs + " 个 / 关系 " + relations
                + " 条 / 世界时间 " + gameTimeText + " / 向量化 " + vectorized + " 条");
        return new WorldInitResultVO(projectId, locations, cards, npcs, relations, gameTimeText, vectorized);
    }

    // ==================== ① 世界观地点 ====================

    /**
     * 第 1 步：根据地理设定 AI 生成结构化地点并入库。
     * 无地理设定 → 跳过；rebuild 或原无地点 → 整表替换；否则按名称合并追加（幂等）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param rebuild   是否全量重建
     * @param wf        日志回调
     * @return 本次入库地点数
     */
    private int initLocations(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        String geography = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getGeography).orElse("");
        if (geography == null || geography.isBlank()) {
            log.warn("[世界初始化-地点] 项目 {} 暂无地理设定，跳过地点生成", projectId);
            info(wf, "[世界初始化-地点] 当前项目暂无「地理设定」，跳过地点生成");
            return 0;
        }
        try {
            info(wf, "[世界初始化-地点] 从地理设定 AI 提取地点…（输入 " + geography.length() + " 字）");
            List<WorldLocationDTO> extracted = worldLocationService.extractFromGeography(userId, geography);
            List<WorldLocationVO> existing = worldLocationService.list(projectId);
            List<WorldLocationDTO> toSave;
            if (existing.isEmpty() || rebuild) {
                toSave = extracted;
                info(wf, "[世界初始化-地点] AI 提取 " + extracted.size() + " 个地点，" + (rebuild ? "全量重建" : "首次生成") + " 入库");
            } else {
                // 幂等：合并去重（保留已有 + 追加新名称）
                List<WorldLocationDTO> merged = new ArrayList<>();
                Set<String> names = new LinkedHashSet<>();
                existing.forEach(l -> {
                    names.add(norm(l.getName()));
                    WorldLocationDTO dto = new WorldLocationDTO();
                    dto.setName(l.getName());
                    dto.setType(l.getType());
                    dto.setIntro(l.getIntro());
                    dto.setImportance(l.getImportance());
                    merged.add(dto);
                });
                int added = 0;
                for (WorldLocationDTO d : extracted) {
                    if (d == null || d.getName() == null || !names.add(norm(d.getName()))) {
                        continue;
                    }
                    merged.add(d);
                    added++;
                }
                toSave = merged;
                info(wf, "[世界初始化-地点] AI 提取 " + extracted.size() + " 个地点，合并新增 " + added + " 个（其余已存在）");
            }
            if (toSave.isEmpty()) {
                info(wf, "[世界初始化-地点] 未识别到明确地点（AI 返回空），跳过入库");
                return 0;
            }
            worldLocationService.batchReplace(projectId, toSave);
            info(wf, "[世界初始化-地点] 完成：入库地点 " + toSave.size() + " 个");
            return toSave.size();
        } catch (Exception e) {
            log.warn("[世界初始化-地点] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-地点] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    /** 地点名称归一（去空格，判重用）。 */
    private String norm(String name) {
        return name == null ? "" : name.trim();
    }

    // ==================== ② 角色卡 ====================

    /**
     * 第 2 步：生成角色结构化角色卡及其 Prompt。
     * rebuild=false → 只补生成未生成的角色卡；rebuild=true → 全量重新生成。
     *
     * @param userId    归属用户 ID（日志用）
     * @param projectId 项目 ID
     * @param rebuild   是否全量重建
     * @param wf        日志回调
     * @return 本次新生成角色卡数
     */
    private int initCharacterCards(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            info(wf, "[世界初始化-角色卡] 当前项目暂无角色，跳过角色卡生成");
            return 0;
        }
        try {
            info(wf, "[世界初始化-角色卡] 开始生成角色结构化角色卡（角色 " + chars.size() + " 位，"
                    + (rebuild ? "全量重新生成" : "仅补生成未生成的") + "）…");
            List<Map<String, Object>> results = rebuild
                    ? characterCardService.generateAllCards(projectId)
                    : characterCardService.generateMissingCards(projectId);
            int ok = 0;
            int skipped = 0;
            for (Map<String, Object> r : results) {
                if (Boolean.TRUE.equals(r.get("success"))) {
                    ok++;
                } else if (Boolean.TRUE.equals(r.get("skipped"))) {
                    skipped++;
                }
            }
            info(wf, "[世界初始化-角色卡] 完成：新生成 " + ok + " 张" + (skipped > 0 ? "（已存在跳过 " + skipped + " 张）" : "")
                    + "（共 " + chars.size() + " 位角色）");
            return ok;
        } catch (Exception e) {
            log.warn("[世界初始化-角色卡] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-角色卡] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ③ 字段字典 + 5 个普通 NPC ====================

    /**
     * 第 3 步：生成普通型 NPC 字段字典，再生成 5 个普通 NPC（复用现有逻辑）。
     * 字段字典已存在且非重建 → 跳过拟定；居民生成需字段字典（种族+归属）与世界观。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param rebuild   是否全量重建
     * @param wf        日志回调
     * @return 本次入库普通 NPC 数
     */
    private int initFieldDictAndNpcs(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        try {
            Map<String, List<FieldDictVO>> dict = ordinaryNpcService.fieldDict(projectId);
            boolean hasDict = !dict.getOrDefault("race", List.of()).isEmpty()
                    && !dict.getOrDefault("affiliation", List.of()).isEmpty();
            if (hasDict && !rebuild) {
                info(wf, "[世界初始化-字段字典] 字段字典已存在（race=" + dict.getOrDefault("race", List.of()).size()
                        + " / affiliation=" + dict.getOrDefault("affiliation", List.of()).size()
                        + "），跳过拟定");
            } else {
                info(wf, "[世界初始化-字段字典] AI 依据世界观拟定标准字段数据（种族/归属/职业）…");
                FieldDictPreviewVO preview = ordinaryNpcService.generateFieldDict(projectId);
                ordinaryNpcService.saveFieldDict(projectId, preview);
                info(wf, "[世界初始化-字段字典] 完成：race="
                        + preview.fields().getOrDefault("race", List.of()).size()
                        + " / affiliation=" + preview.fields().getOrDefault("affiliation", List.of()).size()
                        + " / occupation=" + preview.fields().getOrDefault("occupation", List.of()).size()
                        + "，主分类=" + preview.primaryField() + "，次分类=" + preview.secondaryField());
            }
        } catch (Exception e) {
            log.warn("[世界初始化-字段字典] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-字段字典] 失败（跳过继续）：" + e.getMessage());
        }

        // 生成 5 个普通 NPC（依赖字段字典，失败不阻断）
        try {
            info(wf, "[世界初始化-普通NPC] 开始 AI 生成 " + INIT_NPC_COUNT + " 个普通 NPC…");
            List<OrdinaryNpcDraftVO> drafts = new ArrayList<>();
            ordinaryNpcService.generateStream(projectId, INIT_NPC_COUNT, new OrdinaryNpcService.GenerateProgress() {
                @Override
                public void onStart(int total, int batchSize) {
                    info(wf, "[世界初始化-普通NPC] 生成开始：目标 " + total + " 个");
                }

                @Override
                public void onNpc(OrdinaryNpcDraftVO d, int index) {
                    drafts.add(d);
                    info(wf, "[世界初始化-普通NPC] 已生成 #" + index + "：" + d.name()
                            + "（" + (d.race() == null ? "" : d.race()) + " / "
                            + (d.affiliation() == null ? "" : d.affiliation()) + "）");
                }

                @Override
                public void onDone(int total, int generated, int failedBatches) {
                    info(wf, "[世界初始化-普通NPC] 生成结束：共 " + generated + " 个（失败批次 " + failedBatches + "）");
                }
            });
            if (drafts.isEmpty()) {
                info(wf, "[世界初始化-普通NPC] 未生成到有效居民，跳过入库");
                return 0;
            }
            List<OrdinaryNpcDTO> dtos = drafts.stream()
                    .map(d -> new OrdinaryNpcDTO(d.name(), d.gender(), d.race(), d.subRace(), d.age(),
                            d.affiliation(), d.location(), d.occupation(), d.detail()))
                    .toList();
            int saved = ordinaryNpcService.batchSave(projectId, dtos);
            info(wf, "[世界初始化-普通NPC] 完成：普通 NPC 已入库 " + saved + " 个");
            return saved;
        } catch (Exception e) {
            log.warn("[世界初始化-普通NPC] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-普通NPC] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ④ 全局关系拓扑 ====================

    /**
     * 第 4 步：生成全局关系拓扑（复用关系生成预览 + 批量入库）。
     * rebuild=false → 补充模式（追加）；rebuild=true → 重建模式（清空后写入）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param rebuild   是否全量重建
     * @param wf        日志回调
     * @return 本次写入关系数
     */
    private int initRelations(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        try {
            String mode = rebuild ? "rebuild" : "supplement";
            info(wf, "[世界初始化-关系] 开始 AI 生成全局关系拓扑（范围=全部角色，" + (rebuild ? "重建" : "补充") + "模式）…");
            List<RelationDraftVO> drafts = relationService.generate(projectId,
                    new RelationGenerateDTO("project", null, null, mode));
            if (drafts.isEmpty()) {
                info(wf, "[世界初始化-关系] AI 未识别到角色关系（可能角色过少），跳过入库");
                return 0;
            }
            Map<String, Object> saved = relationService.batchSave(projectId,
                    new RelationBatchDTO(mode, null, null, drafts));
            int added = saved == null ? 0 : ((Number) saved.getOrDefault("added", 0)).intValue();
            info(wf, "[世界初始化-关系] 完成：识别 " + drafts.size() + " 条，入库 " + added + " 条");
            return added;
        } catch (Exception e) {
            log.warn("[世界初始化-关系] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-关系] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ⑤ 世界时间 ====================

    /**
     * 第 5 步：根据世界观信息（历史脉络/时代背景）由 AI 推断当前世界历时间点并设置
     * （不从零开始计算）。换算为 gameSecond 后设置起始游戏时刻（worldStartGameHour）与真实锚点
     * （worldStartAt 反推补足时分秒），使「当前」世界时间即推断结果并继续流动。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param wf        日志回调
     * @return 设置后的世界历时间文本（失败返回「未设置」）
     */
    private String initWorldTime(Long userId, Long projectId, WorkflowLog wf) {
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws == null) {
            info(wf, "[世界初始化-时间] 当前项目无世界观设定，跳过世界时间设置");
            return "未设置";
        }
        long start = System.currentTimeMillis();
        log.info("[世界时间推断] 任务开始：项目={}", projectId);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String history = (ws.getHistory() == null || ws.getHistory().isBlank())
                        ? (ws.getFreeText() == null ? "" : ws.getFreeText()) : ws.getHistory();
                Map<String, Object> ph = Map.of(
                        "world_name", nvl(ws.getName()),
                        "genre", nvl(ws.getGenre()),
                        "era", nvl(ws.getEra()),
                        "history", truncate(history, HISTORY_MAX));
                String prompt = templateService.render(userId, projectId, PromptTemplateService.CODE_WORLD_TIME_INFER, ph);
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", templateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_WORLD_TIME_INFER, ph)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, TIME_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "world_time_infer",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                int[] t = parseWorldTime(result.content());
                long gameSecond = WorldClockService.calendarToGameSecond(t[0], t[1], t[2], t[3], t[4], t[5]);
                // 设置起始游戏时刻 + 反推真实锚点（补足时分秒），使「当前」世界时间 = 推断时间
                ActorWorldClock clock = worldClockService.requireClock(projectId);
                int rate = clock.getRate() == null ? DEFAULT_RATE : clock.getRate();
                long startGameHour = gameSecond / 3600L;
                long remaining = gameSecond % 3600L;
                LocalDateTime startAt = LocalDateTime.now().minusSeconds(remaining / Math.max(1, rate));
                worldClockService.updateClock(projectId, userId,
                        new WorldClockDTO(null, null, startAt, startGameHour));
                String text = String.format("%d 年 %d 月 %d 日 %02d 时 %02d 分", t[0], t[1], t[2], t[3], t[4]);
                log.info("[世界时间推断] 第 {} 次成功：耗时 {}ms，gameSecond={}，当前世界时间={}",
                        attempt, System.currentTimeMillis() - start, gameSecond, text);
                info(wf, "[世界初始化-时间] 完成：AI 依据世界观推断当前世界时间 = " + text
                        + "（世界时钟已锚定，不再从零开始计算）");
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("[世界时间推断] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[世界时间推断] 任务失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        info(wf, "[世界初始化-时间] 世界时间推断失败（跳过）：" + (lastError == null ? "未知错误" : lastError.getMessage()));
        return "未设置";
    }

    /**
     * 解析 AI 世界时间推断输出为 [年, 月, 日, 时, 分, 秒]（容错：字段缺失/非法回退默认值）。
     *
     * @param content AI 输出
     * @return 长度为 6 的数组
     */
    static int[] parseWorldTime(String content) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new BizException("AI 世界时间输出 JSON 解析失败");
        }
        return new int[]{
                clampInt(root, "year", 1, 1, 999999),
                clampInt(root, "month", 1, 1, 12),
                clampInt(root, "day", 1, 1, 30),
                clampInt(root, "hour", 0, 0, 23),
                clampInt(root, "minute", 0, 0, 59),
                clampInt(root, "second", 0, 0, 59)
        };
    }

    /** 取整数节点并夹取范围（缺省用 def）。 */
    private static int clampInt(JsonNode root, String field, int def, int min, int max) {
        int v = root.path(field).asInt(def);
        return Math.max(min, Math.min(max, v));
    }

    // ==================== ⑥ 知识向量化 ====================

    /**
     * 第 6 步：将知识库中的世界观文件尝试向量化（embedding 未配置时内部降级空数组，不抛错）。
     *
     * @param userId    归属用户 ID（日志用）
     * @param projectId 项目 ID
     * @param wf        日志回调
     * @return 本次完成向量化的文档数
     */
    private int initKnowledgeVectorize(Long userId, Long projectId, WorkflowLog wf) {
        try {
            info(wf, "[世界初始化-向量化] 开始尝试向量化知识库文档…");
            int done = knowledgeService.vectorizeAll(projectId);
            info(wf, "[世界初始化-向量化] 完成：" + done + " 条文档已向量化"
                    + (done == 0 ? "（无文档或已全部向量化/未配置 embedding）" : ""));
            return done;
        } catch (Exception e) {
            log.warn("[世界初始化-向量化] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-向量化] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== 工具 ====================

    /** 工作流日志（后端终端已输出）。 */
    private void info(WorkflowLog wf, String message) {
        if (wf != null) {
            try {
                wf.info(message);
            } catch (Exception e) {
                log.warn("工作流日志推送失败: {}", e.getMessage());
            }
        }
    }

    /** 工作流阶段（同时后端终端输出）。 */
    private void stage(WorkflowLog wf, String name, int index, int total) {
        log.info("[工作流] 阶段 {}/{}：{}", index, total, name);
        if (wf != null) {
            try {
                wf.stage(name, index, total);
            } catch (Exception e) {
                log.warn("工作流阶段推送失败: {}", e.getMessage());
            }
        }
    }

    /** null 安全取字符串。 */
    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 字符串截断（null 安全）。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
