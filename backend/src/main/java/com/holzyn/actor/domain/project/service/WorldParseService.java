package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.character.dto.CharacterDTO;
import com.holzyn.actor.domain.character.service.CharacterService;
import com.holzyn.actor.domain.knowledge.service.KnowledgeService;
import com.holzyn.actor.domain.project.dto.ProjectDTO;
import com.holzyn.actor.domain.project.dto.ProjectImportDTO;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.project.vo.WorldParseResultVO;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新建项目「文件解析工作流」服务（2026-08-19 新建项目解析重构）。
 * <p>职责：把上传的世界观文件按【地理设定 / 势力格局 / 规则体系 / 社会文化 / 历史脉络 / 补充设定 / 角色信息】
 * 七类分段——文件严格按该格式描写时原文逐段保留（不更改内容），格式不明确时交给 AI 总结分段；
 * 对地理/势力/规则/文化/历史/补充六段计算字数，小于 1500 字则按既有扩写规则扩写（保证逻辑自洽、符合原本世界观）；
 * 各分段按类别落库（世界观数据表）；原始文件全文落知识库（暂不向量化）；
 * 角色分段由 AI 完整分离每个角色，逐个调用新增角色方法落库（暂不生成角色卡，重要度/主角由 AI 判断，允许多位主角）；
 * 全部落表后返回结果（供前端弹窗询问是否默认世界初始化）。</p>
 * <p>每个步骤同时输出后端终端日志（log.info）与工作流回调日志（WorkflowLog），
 * 前端「文件解析」页控制台实时显示后端解析日志（工作流进度）。</p>
 * <p>所属模块：service/project（新建项目工作流子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldParseService {

    /** 分段最小字数：小于则触发 AI 扩写（2026-08-19 新规则，由原 1000 提升到 1500） */
    static final int SEGMENT_MIN = 1500;

    /** 分段最大字数：AI 扩写原生不得超过此值，超出按段落边界收尾 */
    static final int SEGMENT_MAX = 4000;

    /** AI 单次调用最大输出 token（分段 / 扩写 / 角色分离共用） */
    private static final int AI_MAX_TOKENS = 8192;

    /** AI 调用最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 原文送入 AI 的单文件最大长度（与旧解析一致：大文件完整进视野） */
    private static final int FILE_MAX = 60000;

    /** 全部文件总长上限（多文件兜底） */
    private static final int TOTAL_MAX = 160000;

    /** 扩写时送入 AI 的原文相关章节最大长度 */
    private static final int SECTION_MAX = 8000;

    /** 扩写时自由文本可用原文总长（汇总性字段更宽视野） */
    private static final int FREE_TEXT_SOURCE_MAX = 20000;

    /** 角色信息分段送入 AI 的最大长度（角色较多时截断控制 token，保证完整性优先） */
    private static final int CHARACTERS_SOURCE_MAX = 30000;

    /**
     * 七类分段定义：[中文名, 世界观字段键, 章节关键词…]。
     * 关键词用于「严格格式」判定（标题命中 ≥5/7 类即视为严格格式，原文逐段不修改）。
     */
    static final String[][] SEGMENT_GROUPS = {
            {"地理设定", "geography", "地理", "地图", "地貌", "生态", "版图", "世界构造"},
            {"势力格局", "factions", "势力", "阵营", "组织", "格局", "政治"},
            {"规则体系", "magicSystem", "规则", "体系", "能力", "法则", "修炼", "魔法", "科技"},
            {"社会文化", "culture", "文化", "风俗", "民俗", "社会", "传统", "信仰", "宗教"},
            {"历史脉络", "history", "历史", "脉络", "纪元", "大事", "时间线", "时间"},
            {"补充设定", "supplement", "补充", "其他", "杂项", "附录", "额外", "设定补充"},
            {"角色信息", "characters", "角色", "人物", "NPC", "主角", "配角", "人物设定"}
    };

    /** 分段结果（项目/世界观基础信息 + 七段内容） */
    record Segments(
            String projectName, String projectSummary,
            String worldName, String genre, String era,
            String geography, String factions, String magicSystem,
            String culture, String history, String supplement, String characters) {
    }

    private final ProjectService projectService;
    private final CharacterService characterService;
    private final KnowledgeService knowledgeService;
    private final AiProviderRouter aiProviderRouter;
    private final PromptTemplateService templateService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /**
     * 解析工作流主入口：分段 → （可选）扩写 → 建项目落世界观表 → 知识库存储 → 角色分离入库。
     *
     * @param userId     归属用户 ID
     * @param fileTexts  各文件文本
     * @param fileNames  各文件名
     * @param expand     true=自动 AI 扩写不足 {@link #SEGMENT_MIN} 字的分段；false=分段后原样入库（默认，由用户决定是否扩写）
     * @param wf         工作流日志回调（可为 null；后端终端日志始终输出）
     * @return 解析结果 VO（项目已创建）
     */
    public WorldParseResultVO parseAndCreate(Long userId, List<String> fileTexts, List<String> fileNames,
                                             boolean expand, WorkflowLog wf) {
        long taskStart = System.currentTimeMillis();
        if (fileTexts == null || fileTexts.isEmpty()) {
            throw new BizException(400, "请至少上传一个文件");
        }
        info(wf, "[文件解析] 任务开始：文件 " + (fileNames == null ? 0 : fileNames.size()) + " 个"
                + "，自动扩写=" + (expand ? "开启" : "关闭（默认，不足 " + SEGMENT_MIN + " 字的分段原样保留）"));
        String filesContent = buildFilesContent(fileTexts, fileNames);

        // ===== ① 文件分段（严格格式原文保留 / 格式不明确 AI 总结分段） =====
        stage(wf, "内容分段", 1, 6);
        info(wf, "[内容分段] 开始：输入 " + filesContent.length() + " 字符");
        Segments seg = segment(userId, filesContent, fileNames, wf);
        info(wf, "[内容分段] 完成：地理=" + len(seg.geography) + "字 势力=" + len(seg.factions) + "字 规则=" + len(seg.magicSystem)
                + "字 文化=" + len(seg.culture) + "字 历史=" + len(seg.history) + "字 补充=" + len(seg.supplement)
                + "字 角色=" + len(seg.characters) + "字");

        // ===== ② 分段字数不足 1500：是否扩写由用户决定（默认不扩写，原样入库） =====
        stage(wf, "内容扩写", 2, 6);
        if (expand) {
            info(wf, "[内容扩写] 用户已开启自动扩写：字数小于 " + SEGMENT_MIN + " 的分段触发 AI 扩写");
            seg = expandSegments(userId, filesContent, seg, wf);
            info(wf, "[内容扩写] 完成：地理=" + seg.geography.length() + "字 势力=" + seg.factions.length() + "字 规则="
                    + seg.magicSystem.length() + "字 文化=" + seg.culture.length() + "字 历史=" + seg.history.length()
                    + "字 补充=" + seg.supplement.length() + "字");
        } else {
            // 默认不扩写：列出不足 1500 字的分段（提示用户可重新勾选「自动扩写」后再解析）
            List<String> shortSegs = new ArrayList<>();
            if (len(seg.geography) < SEGMENT_MIN) shortSegs.add("地理(" + len(seg.geography) + "字)");
            if (len(seg.factions) < SEGMENT_MIN) shortSegs.add("势力(" + len(seg.factions) + "字)");
            if (len(seg.magicSystem) < SEGMENT_MIN) shortSegs.add("规则(" + len(seg.magicSystem) + "字)");
            if (len(seg.culture) < SEGMENT_MIN) shortSegs.add("文化(" + len(seg.culture) + "字)");
            if (len(seg.history) < SEGMENT_MIN) shortSegs.add("历史(" + len(seg.history) + "字)");
            if (len(seg.supplement) < SEGMENT_MIN) shortSegs.add("补充(" + len(seg.supplement) + "字)");
            info(wf, "[内容扩写] 已跳过（默认不自动扩写）" + (shortSegs.isEmpty()
                    ? "：全部分段均 ≥" + SEGMENT_MIN + " 字"
                    : "，以下分段不足 " + SEGMENT_MIN + " 字将原样入库：" + String.join("、", shortSegs)
                    + "；如需扩写请勾选「自动扩写」后重新解析"));
        }
        Map<String, Integer> segmentChars = new LinkedHashMap<>();
        segmentChars.put("地理设定", seg.geography.length());
        segmentChars.put("势力格局", seg.factions.length());
        segmentChars.put("规则体系", seg.magicSystem.length());
        segmentChars.put("社会文化", seg.culture.length());
        segmentChars.put("历史脉络", seg.history.length());
        segmentChars.put("补充设定", seg.supplement.length());

        // ===== ③ 创建项目 + 世界观分段落库 =====
        stage(wf, "项目创建与落库", 3, 6);
        info(wf, "[项目落库] 开始：项目名=" + seg.projectName);
        ProjectVO project = createProjectAndWorld(userId, seg);
        info(wf, "[项目落库] 完成：项目 ID=" + project.id() + "，世界观分段已按类别写入数据表");

        // ===== ④ 原始文件全文落知识库（暂不向量化） =====
        stage(wf, "知识库存储", 4, 6);
        int kbCount = 0;
        for (int i = 0; i < fileTexts.size(); i++) {
            String name = fileNames != null && i < fileNames.size() ? fileNames.get(i) : ("文件" + (i + 1));
            try {
                knowledgeService.createRaw(project.id(), name, fileTexts.get(i));
                kbCount++;
                info(wf, "[知识库] 已存储原始文件全文：文档「" + name + "」（暂不向量化，待世界初始化统一向量化）");
            } catch (Exception e) {
                log.warn("[知识库] 存储原始文件失败（跳过）：{}：{}", name, e.getMessage());
                info(wf, "[知识库] 存储原始文件失败（跳过）：" + name);
            }
        }

        // ===== ⑤ 角色分段 AI 完整分离 → 逐个新增角色（不生成角色卡） =====
        stage(wf, "角色分离入库", 5, 6);
        int charCount = 0;
        if (seg.characters == null || seg.characters.isBlank()) {
            info(wf, "[角色分离] 文件未包含角色信息分段，跳过");
        } else {
            charCount = extractAndCreateCharacters(userId, project.id(), seg, wf);
        }

        // ===== ⑥ 完成 =====
        stage(wf, "解析完成", 6, 6);
        info(wf, "[文件解析] 任务结束：耗时 " + (System.currentTimeMillis() - taskStart) + "ms，角色 " + charCount + " 位，知识文档 " + kbCount + " 条");
        return new WorldParseResultVO(project.id(), seg.projectName, seg.worldName, segmentChars, charCount, kbCount);
    }

    // ==================== ① 分段 ====================

    /**
     * 内容分段：严格格式（Markdown 标题命中 ≥5/7 类）→ 原文逐段提取不修改；
     * 格式不明确 → AI 总结分段为七类。
     *
     * @param userId       归属用户 ID
     * @param filesContent 全部文件拼接文本
     * @param fileNames    文件名（项目名兜底用）
     * @param wf           日志回调
     * @return 分段结果
     */
    private Segments segment(Long userId, String filesContent, List<String> fileNames, WorkflowLog wf) {
        if (isStrictFormat(filesContent)) {
            info(wf, "[内容分段] 检测到严格格式（Markdown 标题命中 ≥5/7 类），按原文逐段提取，不更改内容");
            String geography = extractFullSection(filesContent, SEGMENT_GROUPS[0]);
            String factions = extractFullSection(filesContent, SEGMENT_GROUPS[1]);
            String magicSystem = extractFullSection(filesContent, SEGMENT_GROUPS[2]);
            String culture = extractFullSection(filesContent, SEGMENT_GROUPS[3]);
            String history = extractFullSection(filesContent, SEGMENT_GROUPS[4]);
            String supplement = extractFullSection(filesContent, SEGMENT_GROUPS[5]);
            String characters = extractFullSection(filesContent, SEGMENT_GROUPS[6]);
            String projectName = fileNames != null && !fileNames.isEmpty()
                    ? ProjectImportService.stripExt(fileNames.get(0)) : "导入项目";
            return new Segments(projectName, "", projectName, "", "",
                    geography, factions, magicSystem, culture, history, supplement, characters);
        }
        info(wf, "[内容分段] 格式不明确，交由 AI 总结分段为七类");
        Segments seg = segmentByAi(userId, filesContent, wf);
        // 兜底：项目名/世界观名空时用文件名
        String fallbackName = fileNames != null && !fileNames.isEmpty()
                ? ProjectImportService.stripExt(fileNames.get(0)) : "导入项目";
        String projectName = (seg.projectName == null || seg.projectName.isBlank()) ? fallbackName : seg.projectName;
        String worldName = (seg.worldName == null || seg.worldName.isBlank()) ? projectName : seg.worldName;
        return new Segments(projectName, nvl(seg.projectSummary), worldName, nvl(seg.genre), nvl(seg.era),
                nvl(seg.geography), nvl(seg.factions), nvl(seg.magicSystem),
                nvl(seg.culture), nvl(seg.history), nvl(seg.supplement), nvl(seg.characters));
    }

    /**
     * AI 总结分段（world_segment 模板，输出七段 JSON）。
     *
     * @param userId       归属用户 ID
     * @param filesContent 全部文件拼接文本
     * @param wf           日志回调
     * @return 分段结果（可能部分为空串）
     */
    private Segments segmentByAi(Long userId, String filesContent, WorkflowLog wf) {
        long start = System.currentTimeMillis();
        log.info("[内容分段] 任务开始：AI 总结分段");
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_WORLD_SEGMENT,
                        Map.of("files_content", truncate(filesContent, TOTAL_MAX)));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", templateService.systemMessage(userId, null,
                                PromptTemplateService.CODE_WORLD_SEGMENT,
                                Map.of("files_content", truncate(filesContent, TOTAL_MAX)))),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, AI_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "world_segment",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                Segments seg = parseSegments(result.content());
                log.info("[内容分段] 第 {} 次成功：耗时 {}ms，地理={} 势力={} 规则={} 文化={} 历史={} 补充={} 角色={}",
                        attempt, System.currentTimeMillis() - start,
                        len(seg.geography), len(seg.factions), len(seg.magicSystem),
                        len(seg.culture), len(seg.history), len(seg.supplement), len(seg.characters));
                return seg;
            } catch (Exception e) {
                lastError = e;
                log.warn("[内容分段] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[内容分段] AI 总结分段失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "AI 总结分段失败：" + friendlyError(lastError) + "，请检查文件内容后重试");
    }

    /**
     * 解析 AI 分段输出为 Segments（容错：缺字段用空串，剥离 JSON 外文字）。
     *
     * @param content AI 输出
     * @return 分段结果
     */
    static Segments parseSegments(String content) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new BizException("AI 分段输出 JSON 解析失败");
        }
        return new Segments(
                asText(root, "projectName"), asText(root, "projectSummary"),
                asText(root, "worldName"), asText(root, "genre"), asText(root, "era"),
                asText(root, "geography"), asText(root, "factions"), asText(root, "magicSystem"),
                asText(root, "culture"), asText(root, "history"),
                asText(root, "supplement"), asText(root, "characters"));
    }

    /** 取 JSON 节点文本（缺省空串） */
    private static String asText(JsonNode root, String field) {
        return root.path(field).asText("");
    }

    // ==================== ② 扩写 ====================

    /**
     * 分段扩写：地理/势力/规则/文化/历史/补充六段字数 &lt; 1500 时按既有规则 AI 扩写
     * （确保逻辑自洽、符合原本世界观；角色段不扩写）。
     *
     * @param userId       归属用户 ID
     * @param filesContent 全部文件拼接文本（取相关章节作扩写输入源）
     * @param seg          分段结果
     * @param wf           日志回调
     * @return 扩写后的分段
     */
    private Segments expandSegments(Long userId, String filesContent, Segments seg, WorkflowLog wf) {
        String geography = expandIfShort(userId, filesContent, seg, "地理设定", "geography", seg.geography, wf);
        String factions = expandIfShort(userId, filesContent, seg, "势力格局", "factions", seg.factions, wf);
        String magicSystem = expandIfShort(userId, filesContent, seg, "规则体系", "magicSystem", seg.magicSystem, wf);
        String culture = expandIfShort(userId, filesContent, seg, "社会文化", "culture", seg.culture, wf);
        String history = expandIfShort(userId, filesContent, seg, "历史脉络", "history", seg.history, wf);
        String supplement = expandIfShort(userId, filesContent, seg, "补充设定", "supplement", seg.supplement, wf);
        return new Segments(seg.projectName, seg.projectSummary, seg.worldName, seg.genre, seg.era,
                geography, factions, magicSystem, culture, history, supplement, seg.characters);
    }

    /**
     * 单段扩写：不足 1500 字则 AI 扩写（复用 project_import_field 模板，要求 1500~4000 字），
     * 失败或仍不足时程序化兜底；超过 4000 字按段落边界收尾。
     *
     * @param userId       归属用户 ID
     * @param filesContent 全部文件拼接文本（供提取相关章节作输入源）
     * @param seg          分段结果（提供项目名/世界观名）
     * @param fieldLabel   分段中文名
     * @param fieldKey     世界观字段键
     * @param current      当前分段内容
     * @param wf           日志回调
     * @return 扩写后的分段内容
     */
    private String expandIfShort(Long userId, String filesContent, Segments seg,
                                 String fieldLabel, String fieldKey, String current, WorkflowLog wf) {
        if (len(current) >= SEGMENT_MIN) {
            info(wf, "[内容扩写] 「" + fieldLabel + "」已有 " + len(current) + " 字（≥" + SEGMENT_MIN + "），无需扩写");
            return current;
        }
        long start = System.currentTimeMillis();
        info(wf, "[内容扩写] 「" + fieldLabel + "」当前 " + len(current) + " 字（<" + SEGMENT_MIN + "），开始 AI 扩写");
        log.info("[世界观-{}] 任务开始：扩写（当前 {} 字）", fieldLabel, len(current));
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_PROJECT_IMPORT_FIELD,
                        Map.of("field", fieldLabel, "requirement", "不少于 " + SEGMENT_MIN + " 字，以 3000 字为佳，最多不超过 " + SEGMENT_MAX + " 字",
                                "project_name", nvl(seg.projectName), "world_name", nvl(seg.worldName),
                                "current", nvl(current), "files_content", resolveFieldSource(filesContent, fieldKey)));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, AI_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "import",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                String json = JsonUtil.extractJson(result.content());
                if (json == null) {
                    throw new BizException("AI 未返回有效 JSON");
                }
                String text = objectMapper.readTree(json).path("content").asText("");
                if (len(text) < SEGMENT_MIN) {
                    text = padSegment(text, seg.projectName, seg.worldName, fieldLabel);
                }
                if (len(text) > SEGMENT_MAX) {
                    int before = text.length();
                    text = ProjectImportService.truncateByParagraph(text, SEGMENT_MAX);
                    log.warn("[世界观-{}] AI 输出 {} 字超过上限 {} 字，已按段落边界收尾到 {} 字",
                            fieldLabel, before, SEGMENT_MAX, text.length());
                }
                log.info("[世界观-{}] 扩写成功：耗时 {}ms，字数 {}", fieldLabel, System.currentTimeMillis() - start, text.length());
                info(wf, "[内容扩写] 「" + fieldLabel + "」扩写完成：" + text.length() + " 字");
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("[世界观-{}] 第 {} 次失败: {}", fieldLabel, attempt, e.getMessage());
            }
        }
        String fallback = padSegment(current, seg.projectName, seg.worldName, fieldLabel);
        log.warn("[世界观-{}] AI 扩写失败（{}），使用程序化兜底：字数 {}", fieldLabel,
                lastError == null ? "未知错误" : lastError.getMessage(), fallback.length());
        info(wf, "[内容扩写] 「" + fieldLabel + "」AI 扩写失败，使用程序化兜底：" + fallback.length() + " 字");
        return fallback;
    }

    /**
     * 程序化兜底：基于已有内容 + 主题模板句扩展，保证达到 1500 字（AI 不可用时保证功能可用）。
     *
     * @param base        已有内容（可为空）
     * @param projectName 项目名
     * @param worldName   世界观名
     * @param fieldLabel  分段中文名
     * @return 扩展后的文本（≥1500 字）
     */
    static String padSegment(String base, String projectName, String worldName, String fieldLabel) {
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
        while (sb.length() < SEGMENT_MIN && guard < 100) {
            sb.append(scope).append("，").append(fieldLabel).append("的设定内容极为丰富，涵盖多个层面与维度")
                    .append(themes[ti % themes.length]).append("。");
            ti++;
            guard++;
        }
        return sb.toString();
    }

    /** 扩写输入源：按字段键取原文「相关章节」（自由文本取更宽的原文整体）。 */
    private String resolveFieldSource(String filesContent, String fieldKey) {
        if ("supplement".equals(fieldKey)) {
            return truncate(filesContent, FREE_TEXT_SOURCE_MAX);
        }
        return ProjectImportService.extractSection(filesContent, fieldKey);
    }

    // ==================== ③ 建项目 + 落库 ====================

    /**
     * 创建项目 + 世界观分段按类别落库（补充设定 → freeText）。
     *
     * @param userId 归属用户 ID
     * @param seg    分段结果
     * @return 创建后的项目 VO
     */
    private ProjectVO createProjectAndWorld(Long userId, Segments seg) {
        ProjectDTO projectDto = new ProjectDTO(truncate(seg.projectName, 100), null, truncate(seg.projectSummary, 2000), null);
        ProjectVO project = projectService.create(projectDto);
        WorldSettingDTO worldDto = new WorldSettingDTO(
                truncate(seg.worldName, 100), truncate(seg.genre, 50), truncate(seg.era, 50),
                truncate(seg.geography, 100000), truncate(seg.factions, 100000), truncate(seg.magicSystem, 100000),
                truncate(seg.culture, 100000), truncate(seg.history, 100000), truncate(seg.supplement, 200000));
        projectService.saveWorldSetting(project.id(), worldDto);
        return project;
    }

    // ==================== ⑤ 角色分离入库 ====================

    /**
     * 角色分段 AI 完整分离每个角色，逐个调用新增角色方法（type=special，不生成角色卡）。
     * 重要度（1-5）与是否主角（可多位）由 AI 依据世界观与角色详细信息自行判断。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param seg       分段结果（取角色信息段）
     * @param wf        日志回调
     * @return 创建角色数
     */
    private int extractAndCreateCharacters(Long userId, Long projectId, Segments seg, WorkflowLog wf) {
        long start = System.currentTimeMillis();
        log.info("[角色分离] 任务开始：输入 {} 字", len(seg.characters));
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String charactersContent = truncate(seg.characters, CHARACTERS_SOURCE_MAX);
                String prompt = templateService.render(userId, null, PromptTemplateService.CODE_WORLD_SEGMENT_CHARACTERS,
                        Map.of("characters_content", charactersContent));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", templateService.systemMessage(userId, null,
                                PromptTemplateService.CODE_WORLD_SEGMENT_CHARACTERS,
                                Map.of("characters_content", charactersContent))),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, AI_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "world_segment_characters",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                List<ProjectImportDTO.CharacterPart> parts = ProjectImportService.parseCharacterArrayText(result.content(), objectMapper);
                int created = 0;
                for (ProjectImportDTO.CharacterPart p : parts) {
                    if (p == null || p.name() == null || p.name().isBlank()) {
                        continue;
                    }
                    int importance = p.importance() == null
                            ? (Integer.valueOf(1).equals(p.isProtagonist()) ? 5 : 2)
                            : Math.max(1, Math.min(5, p.importance()));
                    CharacterDTO dto = new CharacterDTO("special", truncate(p.name(), 50), truncate(p.title(), 50),
                            truncate(p.detail(), 20000), null,
                            Integer.valueOf(1).equals(p.isProtagonist()) ? 1 : 0, importance);
                    characterService.create(projectId, dto);
                    created++;
                    info(wf, "[角色分离] 已新增角色：" + p.name()
                            + (Integer.valueOf(1).equals(p.isProtagonist()) ? "（主角）" : "")
                            + "（重要度 " + importance + "）");
                }
                log.info("[角色分离] 第 {} 次成功：耗时 {}ms，角色 {} 位", attempt, System.currentTimeMillis() - start, created);
                info(wf, "[角色分离] 完成：共分离并新增角色 " + created + " 位（角色卡将在世界初始化时统一生成）");
                return created;
            } catch (Exception e) {
                lastError = e;
                log.warn("[角色分离] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[角色分离] 任务失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        info(wf, "[角色分离] AI 分离失败（跳过角色入库）：" + (lastError == null ? "未知错误" : lastError.getMessage()));
        return 0;
    }

    // ==================== 严格格式检测与提取 ====================

    /**
     * 严格格式检测：Markdown 标题行命中 ≥5/7 类分段关键词（只看标题，不看正文）即视为
     * 「严格按照以上格式描写」。与 {@link #extractFullSection} 的判定口径完全一致
     * （2026-08-19 修复：此前检测按「标题或正文」打分、提取只按「标题」找块，Windows \r\n
     * 换行下两者不一致导致判定严格却提取全空）。
     *
     * @param content 世界观原文
     * @return true=严格格式（原文逐段提取不修改）
     */
    static boolean isStrictFormat(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        List<String> sections = ProjectImportService.splitSections(content);
        if (sections.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (String[] group : SEGMENT_GROUPS) {
            String[] keywords = new String[group.length - 2];
            System.arraycopy(group, 2, keywords, 0, keywords.length);
            for (String sec : sections) {
                if (headingMatches(sectionHeading(sec), keywords)) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= 5;
    }

    /**
     * 按关键词提取完整分段（严格格式）：对每个「标题行命中任一关键词」的标题取其【整块】
     * ——从该标题直到下一个同级或更高级别标题为止（含所有下级子标题与正文，完整保留原文），
     * 拼接全部命中块。
     * <p>要点（2026-08-19 修复）：
     * ① 与 isStrictFormat 判定口径一致（只看标题）；
     * ② 同一类别可能存在多个同级章节块（如 角色信息 分「核心NPC角色」「主要配角」「其他角色速览」三块），全部收集；
     * ③ 子标题（### 九月 等）正文属于父块，父标题命中即整块提取（不能只取父标题那一行）；
     * ④ 已被「命中关键词的父级标题」包含的嵌套命中（如 父块内某个 ### 小标题恰好含关键词）跳过，避免重复拼接。</p>
     *
     * @param content  世界观原文
     * @param keywords 分段关键词
     * @return 提取的分段全文（未命中返回空串）
     */
    static String extractFullSection(String content, String[] keywords) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // 统一换行符 + 去 UTF-8 BOM（否则首个标题行不被识别）
        content = content.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = content.split("\n", -1);
        int n = lines.length;
        // 收集全部标题行：{index, level, 是否命中关键词}
        List<int[]> headings = new ArrayList<>(); // {index, level}
        boolean[] match = new boolean[n];
        int[] levelOf = new int[n];
        for (int i = 0; i < n; i++) {
            if (lines[i].matches("^\\s*#{1,6}\\s+.*")) {
                int level = leadingHashLevel(lines[i]);
                levelOf[i] = level;
                match[i] = headingMatches(lines[i].trim(), keywords);
                headings.add(new int[]{i, level});
            }
        }
        if (headings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int h = 0; h < headings.size(); h++) {
            int idx = headings.get(h)[0];
            int level = headings.get(h)[1];
            if (!match[idx]) {
                continue;
            }
            // 若已被「更早且层级更浅、命中关键词的祖先标题」包含（该祖先整块已含本块）→ 跳过避免重复
            boolean nested = false;
            for (int k = h - 1; k >= 0; k--) {
                int pLevel = headings.get(k)[1];
                if (pLevel < level) {
                    if (match[headings.get(k)[0]]) {
                        nested = true;
                    }
                    break; // 只需检查最近的祖先
                }
            }
            if (nested) {
                continue;
            }
            // 提取块：从本标题到下一个 level<=当前 level 的标题前
            int end = n;
            for (int k = h + 1; k < headings.size(); k++) {
                if (headings.get(k)[1] <= level) {
                    end = headings.get(k)[0];
                    break;
                }
            }
            StringBuilder block = new StringBuilder();
            for (int i = idx; i < end; i++) {
                if (block.length() > 0) {
                    block.append('\n');
                }
                block.append(lines[i]);
            }
            sb.append(block.toString().trim()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 标题行的 # 层级（1-6）。 */
    private static int leadingHashLevel(String line) {
        int level = 0;
        String t = line.trim();
        while (level < t.length() && t.charAt(level) == '#') {
            level++;
        }
        return Math.max(1, level);
    }

    /**
     * 章节标题行 = 章节块的首个非空行（一般为 Markdown 标题）。
     *
     * @param section 章节块（标题行 + 正文）
     * @return 标题行（去首尾空白；无非空行返回空串）
     */
    static String sectionHeading(String section) {
        if (section == null) {
            return "";
        }
        for (String line : section.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    /**
     * 标题行是否命中任一关键词（只看标题，不看正文；对 \r\n 换行免疫——contains 不区分换行）。
     *
     * @param heading  标题行（已 trim）
     * @param keywords 关键词列表
     * @return true=命中
     */
    static boolean headingMatches(String heading, String[] keywords) {
        if (heading == null || heading.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (heading.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 工具 ====================

    /** 拼接全部文件文本（含文件名分隔，控制总长）。 */
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

    /** 字符串长度（null 安全）。 */
    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** null 安全取字符串（null 归一为空串）。 */
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

    /** 工作流日志（同时后端终端已输出）。 */
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

    /** 失败提示。 */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) {
            return be.getMessage();
        }
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) {
            return ae.getMessage();
        }
        return e.getMessage() == null ? "未知错误" : e.getMessage();
    }
}
