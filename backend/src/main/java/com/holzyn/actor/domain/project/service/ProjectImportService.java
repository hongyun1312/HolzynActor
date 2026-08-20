package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.character.dto.CharacterDTO;
import com.holzyn.actor.domain.project.dto.ProjectDTO;
import com.holzyn.actor.domain.project.dto.ProjectImportDTO;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.project.vo.ProjectImportPreviewVO;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.character.service.CharacterService;
import com.holzyn.actor.domain.project.service.ProjectService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dto.WorldLocationDTO;
import com.holzyn.actor.domain.world.service.WorldLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件导入建项目服务（上传文件 → AI 解析 → 预览 → 确认创建）。
 * <p>职责：读取用户上传的 txt/md 文本（内存解析，不落盘），按 project_import 模板让 AI
 * 结构化提取项目/世界观/角色 → 返回预览；支持「AI 自动生成角色」补齐；确认后一次事务创建
 * 项目 + 世界观 + 角色档案（角色卡由用户在设置页一键生成，成本可控）。</p>
 * <p>所属模块：service/importer（导入子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectImportService {

    /** 解析输出最大 token */
    private static final int WORLD_MAX_TOKENS = 8192;
    /** 角色提取输出最大 token */
    private static final int CHAR_MAX_TOKENS = 8192;

    /** 角色生成输出最大 token */
    private static final int GEN_MAX_TOKENS = 8192;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 世界观详细字段最小长度（字）：不足则自动触发二次 AI 扩写保证内容充足 */
    private static final int WORLD_MIN_FIELD = 1000;

    /** 世界观详细字段最大长度（字）：AI 生成原生不得超过此值，超出按段落边界收尾 */
    private static final int WORLD_MAX_FIELD = 4000;

    /** 解析总阶段数：1 世界观初稿 + 6 详细字段（含地理）+ 1 地点提取 + 1 角色提取 */
    private static final int STAGE_TOTAL = 9;

    /** 地点提取阶段序号（地理设定之后） */
    private static final int STAGE_LOCATIONS = 8;

    /**
     * 单文件文本送入 AI 的最大长度（字符，控制 token 成本）。
     * <p>2026-08-17 重构：原 20000 会把「大文件世界观测试文档」这类 50K+ 字符的详细文件拦腰截断
     * （核心 NPC 章节恰好在 2 万字之后），导致角色/细节严重丢失；提高至 60000 让整份文件进入 AI 视野。
     * 中文约 0.6~1 token/字，60K 字 ≈ 40~60K token，主流模型 128K 上下文可容纳。</p>
     */
    private static final int FILE_MAX = 60000;

    /** 全部文件文本送入 AI 的最大总长（多文件场景兜底） */
    private static final int TOTAL_MAX = 160000;

    /** 分阶段字段深化时从原文截取「相关章节」的最大长度（字符） */
    private static final int SECTION_MAX = 8000;

    /** 自由文本字段深化时可用的原文总长（汇总性字段需要更宽的视野，覆盖到文化/历史章节） */
    private static final int FREE_TEXT_SOURCE_MAX = 20000;

    private final ProjectService projectService;
    private final CharacterService characterService;
    private final AiProviderRouter aiProviderRouter;
    private final PromptTemplateService templateService;
    private final UsageLogService usageLogService;
    private final WorldLocationService worldLocationService;
    private final ObjectMapper objectMapper;

    /**
     * 解析上传文件：AI 结构化提取项目/世界观/角色。
     *
     * @param userId     归属用户 ID
     * @param fileTexts  各文件文本内容
     * @param fileNames  各文件名
     * @return 预览 VO（含 hasCharacters 标记）
     */
    public ProjectImportPreviewVO parse(Long userId, List<String> fileTexts, List<String> fileNames) {
        return parse(userId, fileTexts, fileNames, null);
    }

    /**
     * 解析上传文件（支持进度回调，供 SSE 流式推送分阶段进度）。
     *
     * @param userId       归属用户 ID
     * @param fileTexts    文件文本列表
     * @param fileNames    文件名列表
     * @param progress     进度回调（可为 null）：done 已完成的阶段序号，total 阶段总数，label 阶段中文名，chars 当前阶段产出字数/数量
     * @return 预览 VO（含 hasCharacters 标记）
     */
    public ProjectImportPreviewVO parse(Long userId, List<String> fileTexts, List<String> fileNames, ImportProgress progress) {
        if (fileTexts == null || fileTexts.isEmpty()) {
            throw new BizException(400, "请至少上传一个文件");
        }
        String filesContent = buildFilesContent(fileTexts, fileNames);
        long taskStart = System.currentTimeMillis();
        log.info("[文件导入-解析] 任务开始：文件 {} 个，输入文本 {} 字符", fileNames == null ? 0 : fileNames.size(), filesContent.length());

        // 第一阶段：提取项目 + 世界观（初稿 + 分阶段逐字段深化，进度 1-7）
        WorldExtract world = extractWorld(userId, filesContent, progress);
        // 第二阶段：地点提取（地理设定之后，进度 8）——优先从原文「地理设定」章节识别地点（名称更忠实），
        // 原文无地理章节时回退深化后的地理文本；失败不阻断主流程
        List<ProjectImportDTO.LocationPart> locations = extractLocations(userId, world.worldSetting().geography(), filesContent, progress);
        // 第三阶段：提取全部角色（一个不遗漏，进度 9）
        List<ProjectImportDTO.CharacterPart> characters = extractCharacters(userId, filesContent, progress);

        ProjectImportPreviewVO preview = new ProjectImportPreviewVO(
                world.project(), world.worldSetting(), characters, locations, !characters.isEmpty(), fileNames);
        log.info("[文件导入-解析] 任务结束：耗时 {}ms，世界观={}，角色 {} 位，地点 {} 个",
                System.currentTimeMillis() - taskStart, world.worldSetting().name(), characters.size(), locations.size());
        return preview;
    }

    /**
     * 地点提取（进度 8）：基于「原文地理章节（优先）或深化后的地理文本」，AI 识别地点并生成简介。
     * <p>2026-08-17 重构：大文件（50K+ 字）的深化地理文本是 AI 对原文的重述，可能改写/遗漏地点名；
     * 改为优先取原文「地理设定」章节（含原始地点名如「妖灵会馆总馆（昆仑秘境）」等），
     * 识别更忠实于源文件。失败不阻断主流程：记录 WARN 并返回空列表（世界/角色仍可正常预览创建）。</p>
     *
     * @param userId       归属用户 ID
     * @param geography    深化后的地理设定文本
     * @param filesContent 上传文件原文（用于提取地理章节）
     * @param progress     进度回调（可为 null）
     * @return 地点列表（可能为空）
     */
    private List<ProjectImportDTO.LocationPart> extractLocations(Long userId, String geography, String filesContent, ImportProgress progress) {
        String source = resolveLocationSource(filesContent, geography);
        if (source == null || source.isBlank()) {
            if (progress != null) progress.onProgress(STAGE_LOCATIONS, STAGE_TOTAL, "地点提取", 0);
            return List.of();
        }
        try {
            List<WorldLocationDTO> list = worldLocationService.extractFromGeography(userId, source);
            if (progress != null) progress.onProgress(STAGE_LOCATIONS, STAGE_TOTAL, "地点提取", list.size());
            log.info("[文件导入-地点提取] 成功：输入 {} 字，提取地点 {} 个", source.length(), list.size());
            return list.stream()
                    .map(d -> new ProjectImportDTO.LocationPart(d.getName(), d.getType(), d.getIntro(), d.getImportance()))
                    .toList();
        } catch (Exception e) {
            log.warn("[文件导入-地点提取] 失败（不影响主流程）：{}", e.getMessage());
            if (progress != null) progress.onProgress(STAGE_LOCATIONS, STAGE_TOTAL, "地点提取", 0);
            return List.of();
        }
    }

    /**
     * 地点提取输入源决策：优先取上传文件原文的「地理设定」章节（含原始地点名，识别更忠实）；
     * 原文无地理章节或章节过短（&lt;200 字）时回退深化后的地理文本。
     *
     * @param filesContent 上传文件原文
     * @param geography    深化后的地理设定文本（回退源）
     * @return 用于 AI 提取的地理文本（可能为空）
     */
    private String resolveLocationSource(String filesContent, String geography) {
        String section = extractSection(filesContent, "geography");
        if (section != null && section.trim().length() >= 200) {
            return truncate(section, SECTION_MAX);
        }
        return geography;
    }

    /** 世界观提取结果（项目 + 世界观） */
    private record WorldExtract(ProjectImportDTO.ProjectPart project, ProjectImportDTO.WorldPart worldSetting) {
    }

    /**
     * 提取项目 + 世界观（project_import 模板，各详细字段不少于 1000 字）。
     */
    private WorldExtract extractWorld(Long userId, String filesContent, ImportProgress progress) {
        long start = System.currentTimeMillis();
        log.info("[文件导入-世界观解析] 任务开始");
        Exception lastError = null;
        ProjectImportDTO.ProjectPart project = null;
        ProjectImportDTO.WorldPart world = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_PROJECT_IMPORT,
                        Map.of("files_content", filesContent));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, WORLD_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "import",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                ProjectImportPreviewVO tmp = parsePreview(result.content(), List.of(), objectMapper);
                project = tmp.project();
                world = tmp.worldSetting();
                log.info("[文件导入-世界观初稿] 第 {} 次成功：耗时 {}ms，项目={}，世界观={}，初稿 geo={} fac={} magic={} cult={} hist={}",
                        attempt, System.currentTimeMillis() - start, project.name(), world.name(),
                        len(world.geography()), len(world.factions()), len(world.magicSystem()),
                        len(world.culture()), len(world.history()));
                if (progress != null) {
                    progress.onProgress(1, STAGE_TOTAL, "世界观初稿", 0);
                }
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("[文件导入-世界观初稿] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        if (project == null || world == null) {
            // 初稿解析失败：用项目名兜底，字段内容由分阶段生成保证 ≥1000 字
            String fallbackName = "导入项目";
            project = new ProjectImportDTO.ProjectPart(fallbackName, "");
            world = new ProjectImportDTO.WorldPart(fallbackName, "", "", "", "", "", "", "", "");
            log.warn("[文件导入-世界观初稿] 解析失败（{}），使用兜底项目名，继续分阶段生成",
                    lastError == null ? "未知错误" : lastError.getMessage());
        }
        // 分阶段逐字段深化：地理/势力/规则/文化/历史/自由文本 各自独立生成（每块 ≥1000 字，独立重试 + 程序化兜底）
        world = generateWorldFields(userId, filesContent, project, world, progress);
        log.info("[文件导入-世界观解析] 任务结束：总耗时 {}ms，项目={}，世界观={}，字段长度 geo={} fac={} magic={} cult={} hist={} free={}",
                System.currentTimeMillis() - start, project.name(), world.name(),
                len(world.geography()), len(world.factions()), len(world.magicSystem()),
                len(world.culture()), len(world.history()), len(world.freeText()));
        return new WorldExtract(project, world);
    }

    /**
     * 提取全部角色（project_import_characters 模板，一个不遗漏）。
     */
    private List<ProjectImportDTO.CharacterPart> extractCharacters(Long userId, String filesContent, ImportProgress progress) {
        long start = System.currentTimeMillis();
        log.info("[文件导入-角色解析] 任务开始");
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_PROJECT_IMPORT_CHARACTERS,
                        Map.of("files_content", filesContent));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, CHAR_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "import",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                List<ProjectImportDTO.CharacterPart> chars = parseCharacterArrayText(result.content(), objectMapper);
                log.info("[文件导入-角色解析] 第 {} 次成功：耗时 {}ms，角色 {} 位", attempt, System.currentTimeMillis() - start, chars.size());
                if (progress != null) {
                    progress.onProgress(STAGE_TOTAL, STAGE_TOTAL, "角色提取", chars.size());
                }
                return chars;
            } catch (Exception e) {
                lastError = e;
                log.warn("[文件导入-角色解析] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        throw new BizException(400, "角色解析失败：" + friendlyError(lastError) + "，请检查文件内容后重试");
    }

    /** 字符串长度（null 安全） */
    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 分阶段逐字段生成世界观详细内容（地理/势力/规则/文化/历史/自由文本）。
     * 每个字段独立一次 AI 调用（单字段 1000+ 字不易截断），失败字段用程序化兜底保证 ≥1000 字。
     *
     * @param userId       归属用户 ID
     * @param filesContent 上传文件原文
     * @param project      项目部分
     * @param world        世界观初稿（含各字段简要内容）
     * @return 深化后的世界观（每个详细字段 ≥1000 字，freeText 汇总 2000 字内）
     */
    private ProjectImportDTO.WorldPart generateWorldFields(Long userId, String filesContent,
                                                           ProjectImportDTO.ProjectPart project,
                                                           ProjectImportDTO.WorldPart world,
                                                           ImportProgress progress) {
        // 六个部分各自独立生成：每个字段 ≥1000 字、≤3000 字（提示词约束），进度序号 2-7
        String geography = generateWorldField(userId, filesContent, "地理设定", "geography",
                world.geography(), project.name(), world.name(), "不少于 1000 字，以 3000 字为佳，最多不超过 4000 字", progress, 2);
        String factions = generateWorldField(userId, filesContent, "势力阵营", "factions",
                world.factions(), project.name(), world.name(), "不少于 1000 字，以 3000 字为佳，最多不超过 4000 字", progress, 3);
        String magicSystem = generateWorldField(userId, filesContent, "规则体系", "magicSystem",
                world.magicSystem(), project.name(), world.name(), "不少于 1000 字，以 3000 字为佳，最多不超过 4000 字", progress, 4);
        String culture = generateWorldField(userId, filesContent, "文化风俗", "culture",
                world.culture(), project.name(), world.name(), "不少于 1000 字，以 3000 字为佳，最多不超过 4000 字", progress, 5);
        String history = generateWorldField(userId, filesContent, "历史背景", "history",
                world.history(), project.name(), world.name(), "不少于 1000 字，以 3000 字为佳，最多不超过 4000 字", progress, 6);
        String freeText = generateWorldField(userId, filesContent, "自由文本", "freeText",
                world.freeText(), project.name(), world.name(), "汇总世界观核心设定，以 1000-3000 字为佳，最多不超过 4000 字", progress, 7);
        return new ProjectImportDTO.WorldPart(world.name(), world.genre(), world.era(),
                geography, factions, magicSystem, culture, history, freeText);
    }

    /**
     * 生成单个世界观字段（独立 AI 调用，重试≤2；失败或不足时程序化兜底保证 ≥1000 字）。
     *
     * @param userId       归属用户 ID
     * @param filesContent 上传文件原文（截断控制 token）
     * @param fieldLabel   字段中文名（日志/模板用）
     * @param fieldKey     字段英文键
     * @param current      已有初稿
     * @param projectName  项目名
     * @param worldName    世界观名
     * @param requirement  模板中的字数要求
     * @return 生成后的字段内容
     */
    private String generateWorldField(Long userId, String filesContent, String fieldLabel, String fieldKey,
                                      String current, String projectName, String worldName, String requirement,
                                      ImportProgress progress, int done) {
        long start = System.currentTimeMillis();
        log.info("[世界观-{}] 任务开始：项目={}，初稿 {} 字", fieldLabel, projectName, len(current));
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_PROJECT_IMPORT_FIELD,
                        Map.of("field", fieldLabel, "requirement", requirement,
                                "project_name", nvl(projectName), "world_name", nvl(worldName),
                                "current", nvl(current), "files_content", resolveFieldSource(filesContent, fieldKey)));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, WORLD_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "import",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                String json = JsonUtil.extractJson(result.content());
                if (json == null) {
                    throw new BizException("AI 未返回有效 JSON");
                }
                JsonNode root = objectMapper.readTree(json);
                String text = root.path("content").asText("");
                // 长度不足（非 freeText 需 ≥1000 字）则程序化兜底补全
                if (text.length() < WORLD_MIN_FIELD && !"freeText".equals(fieldKey)) {
                    text = padWorldField(text, projectName, worldName, fieldLabel);
                }
                // 长度超上限（>4000 字）：按段落/句子边界收尾（不腰斩句子，保证内容相对完整）。
                // 注意：此分支独立于上面的补充分支——修复历史遗留的括号错位问题（原截断逻辑
                // 被错误嵌套在 <1000 字补充分支内，导致 AI 输出超长时从未触发收尾）。
                if (text.length() > WORLD_MAX_FIELD) {
                    int before = text.length();
                    text = truncateByParagraph(text, WORLD_MAX_FIELD);
                    log.warn("[世界观-{}] AI 输出 {} 字超过上限 {} 字，已按段落边界收尾到 {} 字",
                            fieldLabel, before, WORLD_MAX_FIELD, text.length());
                }
                log.info("[世界观-{}] 生成成功：耗时 {}ms，字数 {}", fieldLabel, System.currentTimeMillis() - start, text.length());
                if (progress != null) {
                    progress.onProgress(done, STAGE_TOTAL, fieldLabel, text.length());
                }
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("[世界观-{}] 第 {} 次失败: {}", fieldLabel, attempt, e.getMessage());
            }
        }
        // AI 失败：程序化兜底（基于初稿扩展到 ≥1000 字），保证功能可用
        String fallback = padWorldField(current, projectName, worldName, fieldLabel);
        log.warn("[世界观-{}] AI 生成失败（{}），使用程序化兜底：字数 {}", fieldLabel,
                lastError == null ? "未知错误" : lastError.getMessage(), fallback.length());
        if (progress != null) {
            progress.onProgress(done, STAGE_TOTAL, fieldLabel, fallback.length());
        }
        return fallback;
    }

    /**
     * 程序化兜底：基于已有内容 + 主题模板句扩展，保证达到最小长度（AI 不可用时保证功能可用）。
     *
     * @param base        已有初稿（可为空）
     * @param projectName 项目名
     * @param worldName   世界观名
     * @param fieldLabel  字段中文名
     * @return 扩展后的文本（≥1000 字）
     */
    static String padWorldField(String base, String projectName, String worldName, String fieldLabel) {
        StringBuilder sb = new StringBuilder();
        if (base != null && !base.isBlank()) {
            sb.append(base);
            char last = base.charAt(base.length() - 1);
            if (last != '。' && last != '，' && last != '.') {
                sb.append("。");
            }
        }
        String[] themes = new String[]{
                "、其演变过程记录着时代的变迁与重要事件的影响",
                "、以及它与角色们日常生活和重大抉择之间的深刻联系",
                "、从宏观格局到微观细节，皆有值得展开的丰富层次",
                "、既影响外部世界的运行，也塑造着每个人物的内心世界",
                "、这些设定共同织就了这个世界独一无二的气质与生命力",
                "、并在与地理、势力、规则等其他设定的呼应中不断深化"
        };
        String scope = (worldName == null || worldName.isBlank() ? "" : "在「" + worldName + "」这个")
                + (projectName == null || projectName.isBlank() ? "世界中" : "以「" + projectName + "」为名的世界中");
        int guard = 0;
        int ti = 0;
        while (sb.length() < WORLD_MIN_FIELD && guard < 100) {
            sb.append(scope).append("，").append(fieldLabel).append("的设定内容极为丰富，涵盖多个层面与维度")
              .append(themes[ti % themes.length]).append("。");
            ti++;
            guard++;
        }
        return sb.toString();
    }
    /**
     * 按段落/句子边界截断（不腰斩句子）：在 max 内找最后一个换行/句号/问号/感叹号/句点收尾。
     * <p>用于 AI 输出超长时兜底，保证截断后内容相对完整（保留完整句子，不从中腰斩）。</p>
     *
     * @param s   原文本
     * @param max 最大长度（字符）
     * @return 收尾后的文本（≤max，尽量保留完整句子）
     */
    static String truncateByParagraph(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        // 先在 max 位置找最后一个完整句子边界，若在合理区间（后半段）则在此收尾
        String cut = s.substring(0, max);
        int boundary = lastSentenceBoundary(cut);
        if (boundary > max * 0.6) {
            return cut.substring(0, boundary + 1);
        }
        // 前半段无合适边界：退回到 90% 位置再找，尽量不丢太多内容
        cut = s.substring(0, (int) (max * 0.9));
        boundary = lastSentenceBoundary(cut);
        if (boundary > 0) {
            return cut.substring(0, boundary + 1);
        }
        return cut; // 极端情况：90% 内无任何边界，返回 90% 内容（避免腰斩过短）
    }

    /** 找字符串中最后一个句子结束位置（换行/。？！.） */
    private static int lastSentenceBoundary(String s) {
        int nl = s.lastIndexOf('\n');
        int zj = s.lastIndexOf('。');
        int wy = s.lastIndexOf('？');
        int gw = s.lastIndexOf('！');
        int en = s.lastIndexOf('.');
        return Math.max(nl, Math.max(zj, Math.max(wy, Math.max(gw, en))));
    }

    /**
     * 分阶段字段深化时的输入源：按字段键取原文「相关章节」（自由文本取更长的文件整体）。
     * <p>大文件（50K+ 字）直接取文件头部会丢失位于后部的势力/规则/文化/历史章节，
     * 导致深化生成的字段与源文件严重脱节；章节感知抽取可让每个字段基于原文对应部分生成。</p>
     *
     * @param filesContent 上传文件原文
     * @param fieldKey     字段键：geography/factions/magicSystem/culture/history/freeText
     * @return 送入 AI 的原文片段（≤SECTION_MAX，freeText ≤FREE_TEXT_SOURCE_MAX）
     */
    private String resolveFieldSource(String filesContent, String fieldKey) {
        if ("freeText".equals(fieldKey)) {
            // 自由文本是汇总性字段：给更宽的原文视野，保证后续对话/角色卡能取到完整世界观
            return truncate(filesContent, FREE_TEXT_SOURCE_MAX);
        }
        return truncate(extractSection(filesContent, fieldKey), SECTION_MAX);
    }

    /**
     * 从世界观 Markdown 原文中按标题切分章节，并按字段关键词选取最相关的章节文本（拼接）。
     * <p>2026-08-17 新增（大文件适配）：用于分阶段字段深化与地点提取的输入源。
     * 选取规则：标题命中关键词权重更高（+2），正文命中较低（+1）；自由文本字段直接返回文件整体。
     * 未命中任何章节或命中内容过短（&lt;300 字）时回退文件头部，保证旧文件行为不变。</p>
     *
     * @param content  世界观原文（可为空）
     * @param fieldKey 字段键：geography/factions/magicSystem/culture/history/freeText
     * @return 相关章节拼接文本（≤SECTION_MAX，可能为空）
     */
    static String extractSection(String content, String fieldKey) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if ("freeText".equals(fieldKey)) {
            return truncateStatic(content, FREE_TEXT_SOURCE_MAX);
        }
        String[] keywords = sectionKeywords(fieldKey);
        List<String> sections = splitSections(content);
        if (sections.isEmpty()) {
            // 无任何 Markdown 标题：按原文头部处理（保持旧行为）
            return truncateStatic(content, SECTION_MAX);
        }
        StringBuilder sb = new StringBuilder();
        boolean matched = false;
        for (String sec : sections) {
            if (scoreSection(sec, keywords) > 0) {
                matched = true;
                sb.append(sec.trim()).append("\n\n");
                if (sb.length() >= SECTION_MAX) {
                    break;
                }
            }
        }
        if (!matched) {
            // 未命中任何章节：回退文件头部（保持旧行为，兼容无章节结构的小文件）
            return truncateStatic(content, SECTION_MAX);
        }
        return truncateByParagraph(sb.toString(), SECTION_MAX);
    }

    /**
     * 按 Markdown 标题（#~######）切分章节：返回每个「标题行 + 正文」的块。
     *
     * @param content 世界观原文
     * @return 章节块列表（每个块以标题行开头；无标题行时返回空列表）
     */
    static List<String> splitSections(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        // 统一换行符：Windows 文件为 \r\n，若保留 \r 会导致标题行形如 "## 地理设定\r"，
        // Java 正则 . 不匹配 \r（行终止符），标题检测全部失败 → 误判无标题/漏分段（2026-08-19 修复）
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = content.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (line.matches("^\\s*#{1,6}\\s+.*")) {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                }
                cur = new StringBuilder(line);
            } else {
                if (cur.length() > 0) {
                    cur.append('\n');
                }
                cur.append(line);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /** 字段键 → 章节关键词（用于标题/正文匹配） */
    private static String[] sectionKeywords(String fieldKey) {
        return switch (fieldKey) {
            case "geography" -> new String[]{"地理", "地图", "地貌", "生态", "版图", "世界构造"};
            case "factions" -> new String[]{"势力", "阵营", "组织", "格局", "政治"};
            case "magicSystem" -> new String[]{"规则", "体系", "能力", "法则", "修炼", "魔法", "科技"};
            case "culture" -> new String[]{"文化", "风俗", "民俗", "社会", "传统", "信仰", "宗教"};
            case "history" -> new String[]{"历史", "脉络", "纪元", "大事", "时间线", "时间"};
            default -> new String[]{"世界观", "设定", "补充"};
        };
    }

    /**
     * 章节相关度评分：标题行命中关键词 +2，正文命中 +1（返回 0 表示不相关）。
     * <p>包级可见：WorldParseService（新建项目解析重构）复用其做「严格格式」判定与分段提取。</p>
     */
    static int scoreSection(String section, String[] keywords) {
        String[] lines = section.split("\n", 2);
        String heading = lines[0];
        String body = lines.length > 1 ? lines[1] : "";
        int score = 0;
        for (String kw : keywords) {
            if (heading.contains(kw)) {
                score += 2;
            } else if (body.contains(kw)) {
                score += 1;
            }
        }
        return score;
    }

    /** 静态版字符串截断（null 安全；供静态工具方法使用） */
    private static String truncateStatic(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** null 安全取字符串（null 归一为空串） */
    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从模型输出文本解析角色数组（增强容错：剥离说明文字/代码块，readTree 失败时清洗尾部逗号重试）。
     *
     * @param content 模型输出文本
     * @param mapper  JSON 解析器
     * @return 角色档案列表
     * @throws Exception JSON 无法解析时抛出（由外层重试）
     */
    static List<ProjectImportDTO.CharacterPart> parseCharacterArrayText(String content, ObjectMapper mapper) throws Exception {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            // 容错：模型可能在数组末尾输出多余逗号（如 [{...},]），清洗后重试
            node = mapper.readTree(stripTrailingComma(json));
        }
        return parseCharacterArray(node);
    }

    /** 清洗 JSON 尾部多余逗号（如 [{...},] / {"a":1,} 中的末尾逗号） */
    private static String stripTrailingComma(String json) {
        return json.replaceAll(",\\s*\\]", "]").replaceAll(",\\s*\\}", "}");
    }


    /**
     * AI 自动生成符合世界观的角色档案（供预览「AI 自动生成」补充）。
     *
     * @param userId        归属用户 ID
     * @param projectName   项目名（可空）
     * @param worldFreeText 世界观自由文本（可空）
     * @param count         生成数量（默认 5，1-20，由用户在前端指定）
     * @return 角色档案列表
     */
    public List<ProjectImportDTO.CharacterPart> generateCharacters(Long userId, String projectName,
                                                                   String worldFreeText, Integer count) {
        int n = count == null ? 5 : Math.max(1, Math.min(20, count));
        long taskStart = System.currentTimeMillis();
        log.info("[文件导入-角色生成] 任务开始：数量 {}，项目={}，世界观输入 {} 字", n, projectName, worldFreeText == null ? 0 : worldFreeText.length());
        String situation = (projectName == null || projectName.isBlank() ? "" : "项目：「" + projectName + "」")
                + (worldFreeText == null || worldFreeText.isBlank() ? ""
                : "；世界观：" + truncate(worldFreeText, 6000));
        String prompt = """
                你是一位世界角色设计师。请基于给定的世界观，生成 %d 个符合世界设定的角色档案，输出为 JSON 数组。
                输出结构：[ { "type": "special", "name": "角色名", "title": "头衔", "detail": "角色详细信息（背景/性格/目标/关系，300字以内）", "isProtagonist": 0, "importance": 3 } ]
                要求：姓名与世界观一致；重要性分布合理（可含 1 个主角）；只输出 JSON 数组，不要 Markdown 代码块。

                —— 世界观情境 ——
                %s
                """.formatted(n, situation.isBlank() ? "（未提供，请给出合理泛化的奇幻世界设定）" : situation);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.8, GEN_MAX_TOKENS, true);
                long startMs = System.currentTimeMillis();
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "import",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - startMs));
                List<ProjectImportDTO.CharacterPart> chars = parseCharacterArrayText(result.content(), objectMapper);
                log.info("[文件导入-角色生成] 任务结束：耗时 {}ms，生成角色 {} 位", System.currentTimeMillis() - taskStart, chars.size());
                return chars;
            } catch (Exception e) {
                lastError = e;
                log.warn("AI 角色生成第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[文件导入-角色生成] 任务失败：耗时 {}ms", System.currentTimeMillis() - taskStart);
        throw new BizException(400, "角色生成失败：" + friendlyError(lastError) + "，请稍后重试");
    }

    /**
     * 确认创建：一次事务创建项目 + 世界观 + 角色档案。
     *
     * @param userId 归属用户 ID
     * @param dto    确认后的完整结构（项目/世界观/角色）
     * @return 创建后的项目 VO
     */
    @Transactional
    public ProjectVO confirmCreate(Long userId, ProjectImportDTO.Confirm dto) {
        if (dto == null || dto.project() == null || dto.project().name() == null || dto.project().name().isBlank()) {
            throw new BizException(400, "项目名称不能为空");
        }
        // 世界观名缺省回退项目名（保证 WorldSettingDTO.name 非空）
        long taskStart = System.currentTimeMillis();
        log.info("[文件导入-确认创建] 任务开始：项目={}，角色 {} 位", dto.project().name(), dto.characters() == null ? 0 : dto.characters().size());
        String worldName = resolveWorldName(dto.project().name(), dto.worldSetting() == null ? null : dto.worldSetting().name());
        ProjectImportDTO.WorldPart w = dto.worldSetting() == null
                ? new ProjectImportDTO.WorldPart(worldName, null, null, null, null, null, null, null, null)
                : new ProjectImportDTO.WorldPart(worldName, dto.worldSetting().genre(), dto.worldSetting().era(),
                dto.worldSetting().geography(), dto.worldSetting().factions(), dto.worldSetting().magicSystem(),
                dto.worldSetting().culture(), dto.worldSetting().history(), dto.worldSetting().freeText());

        // 1) 创建项目
        ProjectDTO projectDto = new ProjectDTO(truncate(dto.project().name(), 100), null, truncate(dto.project().summary(), 2000), null);
        ProjectVO project = projectService.create(projectDto);

        // 2) 保存世界观（覆盖新建）
        WorldSettingDTO worldDto = new WorldSettingDTO(
                truncate(w.name(), 100), truncate(w.genre(), 50), truncate(w.era(), 50),
                truncate(w.geography(), 5000), truncate(w.factions(), 5000), truncate(w.magicSystem(), 5000),
                truncate(w.culture(), 5000), truncate(w.history(), 5000), truncate(w.freeText(), 5000));
        projectService.saveWorldSetting(project.id(), worldDto);

        // 3) 批量创建角色档案（角色卡留待设置页一键生成）
        int charCount = 0;
        if (dto.characters() != null) {
            for (ProjectImportDTO.CharacterPart ch : dto.characters()) {
                if (ch == null || ch.name() == null || ch.name().isBlank()) {
                    continue; // 跳过无姓名角色
                }
                int importance = ch.importance() == null
                        ? (Integer.valueOf(1).equals(ch.isProtagonist()) ? 3 : 1) : ch.importance();
                CharacterDTO charDto = new CharacterDTO(
                        ch.type() == null || ch.type().isBlank() ? "special" : ch.type(),
                        truncate(ch.name(), 50), truncate(ch.title(), 50), truncate(ch.detail(), 20000),
                        null, Integer.valueOf(1).equals(ch.isProtagonist()) ? 1 : 0, Math.max(1, Math.min(5, importance)));
                characterService.create(project.id(), charDto);
                charCount++;
            }
        }
        // 3.5) 批量创建地点（解析流程「地点提取」结果，随项目入库；空名称行跳过）
        int locCount = 0;
        if (dto.locations() != null) {
            List<WorldLocationDTO> locDtos = dto.locations().stream()
                    .filter(loc -> loc != null && loc.name() != null && !loc.name().isBlank())
                    .map(loc -> {
                        WorldLocationDTO ld = new WorldLocationDTO();
                        ld.setName(truncate(loc.name(), 100));
                        ld.setType(truncate(loc.type(), 50));
                        ld.setIntro(truncate(loc.intro(), 2000));
                        ld.setImportance(loc.importance() == null ? 3 : Math.max(1, Math.min(5, loc.importance())));
                        return ld;
                    })
                    .toList();
            worldLocationService.batchReplace(project.id(), locDtos);
            locCount = locDtos.size();
        }
        log.info("[文件导入-确认创建] 任务结束：耗时 {}ms，项目={}，创建角色 {} 位，地点 {} 个",
                System.currentTimeMillis() - taskStart, project.name(), charCount, locCount);
        return project;
    }

    /**
     * 组装送入 AI 的文件内容（含文件名分隔，控制长度）。
     *
     * @param fileTexts 各文件文本
     * @param fileNames 各文件名
     * @return 拼接文本
     */
    private String buildFilesContent(List<String> fileTexts, List<String> fileNames) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < fileTexts.size(); i++) {
            String name = fileNames != null && i < fileNames.size() ? fileNames.get(i) : ("文件" + (i + 1));
            String text = fileTexts.get(i);
            String truncated = truncate(text == null ? "" : text, FILE_MAX);
            sb.append("--- 文件：").append(name).append(" ---\n").append(truncated).append("\n\n");
            total += truncated.length();
            if (total >= TOTAL_MAX) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 解析 AI 输出为预览 VO。
     *
     * @param content   模型输出
     * @param fileNames 文件名列表
     * @return 预览 VO
     */
    static ProjectImportPreviewVO parsePreview(String content, List<String> fileNames, ObjectMapper mapper) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode root = mapper.readTree(json);
        // 项目名回退：文件内容没有时取第一个文件名（去扩展名）
        String fallbackName = fileNames != null && !fileNames.isEmpty() ? stripExt(fileNames.get(0)) : "导入项目";
        String projectName = root.path("project").path("name").asText("");
        if (projectName.isBlank()) {
            projectName = fallbackName;
        }
        ProjectImportDTO.ProjectPart project = new ProjectImportDTO.ProjectPart(projectName,
                root.path("project").path("summary").asText(""));

        JsonNode ws = root.path("worldSetting");
        String worldName = ws.path("name").asText("");
        if (worldName.isBlank()) {
            worldName = projectName; // 世界观名回退项目名
        }
        ProjectImportDTO.WorldPart world = new ProjectImportDTO.WorldPart(
                worldName, ws.path("genre").asText(""), ws.path("era").asText(""),
                ws.path("geography").asText(""), ws.path("factions").asText(""),
                ws.path("magicSystem").asText(""), ws.path("culture").asText(""),
                ws.path("history").asText(""), ws.path("freeText").asText(""));

        List<ProjectImportDTO.CharacterPart> characters = parseCharacterArray(root.path("characters"));
        // 初稿解析阶段不包含地点（地点提取在深化后的地理文本之后单独进行）
        return new ProjectImportPreviewVO(project, world, characters, List.of(), !characters.isEmpty(), fileNames);
    }

    /**
     * 解析角色数组（JSON 数组节点或整体为数组的文本）。
     *
     * @param node JSON 节点（数组）
     * @return 角色档案列表（跳过无姓名项）
     */
    static List<ProjectImportDTO.CharacterPart> parseCharacterArray(JsonNode node) {
        List<ProjectImportDTO.CharacterPart> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        node.forEach(n -> {
            String name = n.path("name").asText("");
            if (name.isBlank()) {
                return;
            }
            int importance = n.path("importance").asInt(1);
            list.add(new ProjectImportDTO.CharacterPart(
                    n.path("type").asText("special"),
                    name, n.path("title").asText(""), n.path("detail").asText(""),
                    n.path("isProtagonist").asInt(0), Math.max(1, Math.min(5, importance))));
        });
        return list;
    }

    /**
     * 世界观名解析：worldName 为空时回退项目名（保证 WorldSettingDTO.name 非空）。
     *
     * @param projectName 项目名
     * @param worldName   世界观名（可空）
     * @return 有效世界观名
     */
    static String resolveWorldName(String projectName, String worldName) {
        return (worldName == null || worldName.isBlank()) ? projectName : worldName;
    }

    /**
     * 去掉文件扩展名。
     *
     * @param name 文件名
     * @return 去扩展名后的基名
     */
    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 字符串截断。
     *
     * @param s   原字符串
     * @param max 最大长度
     * @return 截断结果（null 归一为空串）
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 生成失败的用户友好提示。
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
        return e.getMessage() == null ? "未知错误" : e.getMessage();
    }

    /**
     * 导入解析进度回调（供 SSE 流式推送分阶段进度）。
     */
    @FunctionalInterface
    public interface ImportProgress {
        /**
         * 阶段进度通知。
         *
         * @param done  已完成的阶段序号（1 开始）
         * @param total 阶段总数
         * @param label 阶段中文名（如 地理设定/角色提取）
         * @param chars 当前阶段产出字数（角色阶段为角色数量）
         */
        void onProgress(int done, int total, String label, int chars);
    }
}
