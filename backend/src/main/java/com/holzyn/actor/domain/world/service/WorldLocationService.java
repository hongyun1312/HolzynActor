package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dto.WorldLocationDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldLocationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 世界观地点表服务。
 * <p>职责：承载 /api/projects/{id}/world-locations 全部业务逻辑——
 * ① 项目级地点 CRUD（列表/新增/修改/删除/编辑模式全量保存）；
 * ② AI 地点提取：从世界观「地理设定」文本识别地点并生成名称/类型/简介/重要度
 * （供新建项目解析流程与「地点详情」手动重新提取复用）。</p>
 * <p>数据来源：geography 文本（AI 提取）+ 手动维护；归属项目级（随 .holzyn 导入导出）。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldLocationService {

    /** AI 提取输出最大 token（50 个地点 × 200 字简介 ≈ 10K+ token，故放宽） */
    private static final int EXTRACT_MAX_TOKENS = 16384;

    /** 提取失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 单次提取最大地点数（2026-08-17 提升：适配 50K+ 字详细世界观的批量地点） */
    private static final int MAX_LOCATIONS = 50;

    /** 送入 AI 的地理文本最大长度（字符） */
    private static final int GEO_SOURCE_MAX = 8000;

    /** 字段长度上限（与 DTO 校验一致，服务层兜底截断） */
    private static final int NAME_MAX = 100;
    private static final int TYPE_MAX = 50;
    private static final int INTRO_MAX = 2000;

    private final WorldLocationRepository repository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /**
     * 查询某项目全部地点（排序稳定）。
     *
     * @param projectId 项目 ID
     * @return 地点视图列表
     */
    public List<WorldLocationVO> list(Long projectId) {
        return repository.findByProjectIdOrderBySortOrderAscIdAsc(projectId).stream().map(this::toVO).toList();
    }

    /**
     * 新增地点。
     *
     * @param projectId 项目 ID
     * @param dto       地点数据（名称必填）
     * @return 新增后的视图
     */
    @Transactional
    public WorldLocationVO create(Long projectId, WorldLocationDTO dto) {
        if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(400, "地点名称不能为空");
        }
        ActorWorldLocation entity = toEntity(projectId, dto);
        // 新增默认排到末尾
        int maxSort = repository.findByProjectId(projectId).stream()
                .mapToInt(l -> l.getSortOrder() == null ? 0 : l.getSortOrder()).max().orElse(0);
        if (entity.getSortOrder() == null) entity.setSortOrder(maxSort + 1);
        return toVO(repository.save(entity));
    }

    /**
     * 修改地点。
     *
     * @param projectId 项目 ID
     * @param id        地点主键
     * @param dto       地点数据（名称必填）
     * @return 更新后的视图
     */
    @Transactional
    public WorldLocationVO update(Long projectId, Long id, WorldLocationDTO dto) {
        ActorWorldLocation entity = requireOwned(projectId, id);
        if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(400, "地点名称不能为空");
        }
        entity.setName(truncate(dto.getName(), NAME_MAX));
        entity.setType(truncate(dto.getType(), TYPE_MAX));
        entity.setIntro(truncate(dto.getIntro(), INTRO_MAX));
        if (dto.getImportance() != null) entity.setImportance(clampImportance(dto.getImportance()));
        if (dto.getSortOrder() != null) entity.setSortOrder(dto.getSortOrder());
        return toVO(repository.save(entity));
    }

    /**
     * 删除地点。
     *
     * @param projectId 项目 ID
     * @param id        地点主键
     */
    @Transactional
    public void delete(Long projectId, Long id) {
        ActorWorldLocation entity = requireOwned(projectId, id);
        repository.delete(entity);
    }

    /**
     * 编辑模式全量保存（增删改查一体）：按列表顺序整体替换该项目地点。
     * <p>前端「修改」进入编辑后，本地维护增删改，点保存时提交全量列表（排序=数组顺序）。</p>
     *
     * @param projectId 项目 ID
     * @param items     全量地点列表（含新增/修改/删除后的最终态）
     * @return 保存后的全部地点视图
     */
    @Transactional
    public List<WorldLocationVO> batchReplace(Long projectId, List<WorldLocationDTO> items) {
        repository.findByProjectId(projectId).forEach(repository::delete);
        repository.flush();
        List<ActorWorldLocation> saved = new ArrayList<>();
        int i = 0;
        for (WorldLocationDTO dto : items) {
            if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
                continue; // 跳过空名称行
            }
            ActorWorldLocation entity = toEntity(projectId, dto);
            entity.setSortOrder(i++);
            saved.add(repository.save(entity));
        }
        log.info("[地点表] 项目 {} 编辑模式全量保存 {} 条", projectId, saved.size());
        return saved.stream().map(this::toVO).toList();
    }

    /**
     * 从地理文本 AI 提取地点（不落库，供解析流程/预览使用）。
     * <p>2026-08-17 补充日志：任务开始 + 每条地点提取成功各输出一行，便于观察进度与排查。</p>
     *
     * @param userId    归属用户 ID（AI 用量归属）
     * @param geography 世界观「地理设定」文本
     * @return 地点列表（已清洗：去重/截断/重要度归一）
     */
    public List<WorldLocationDTO> extractFromGeography(Long userId, String geography) {
        if (geography == null || geography.isBlank()) {
            return List.of();
        }
        long start = System.currentTimeMillis();
        log.info("[地点提取] 任务开始：输入 {} 字", geography.length());
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = buildExtractPrompt(truncate(geography, GEO_SOURCE_MAX));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.2, EXTRACT_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, null, null, req);
                usageLogService.record(userId, null, null, result.providerId(), result.model(), "location_extract",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                List<WorldLocationDTO> locations = parseLocationText(result.content());
                logLocations(locations);
                log.info("[地点提取] 第 {} 次成功：耗时 {}ms，提取地点 {} 个", attempt, System.currentTimeMillis() - start, locations.size());
                return locations;
            } catch (Exception e) {
                lastError = e;
                log.warn("[地点提取] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[地点提取] 任务失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "地点提取失败：" + friendlyError(lastError) + "，请稍后重试或手动添加");
    }

    /**
     * 逐条输出地点提取日志（任务开始后，每生成一条地点输出一行）。
     *
     * @param locations 已解析的地点列表
     */
    private void logLocations(List<WorldLocationDTO> locations) {
        for (int i = 0; i < locations.size(); i++) {
            WorldLocationDTO d = locations.get(i);
            log.info("[地点提取] 已提取地点 #{}：名称={}，类型={}，重要度={}",
                    i + 1, d.getName(), d.getType(), d.getImportance());
        }
    }

    /**
     * AI 重新提取并合并（世界详情「地点详情」手动提取按钮）：
     * 基于已存地理文本提取新地点，仅追加「名称不存在」的地点，不删除手动维护的数据。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @return 合并后的全部地点
     */
    @Transactional
    public List<WorldLocationVO> extractAndMerge(Long userId, Long projectId) {
        String geography = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(w -> w.getGeography()).orElse("");
        if (geography == null || geography.isBlank()) {
            throw new BizException(400, "当前项目暂无「地理设定」文本，无法 AI 提取；请先在世界观中填写地理设定");
        }
        List<WorldLocationDTO> extracted = extractFromGeography(userId, geography);
        if (extracted.isEmpty()) {
            throw new BizException(400, "地理设定中未识别到明确地点（AI 返回空），可手动添加");
        }
        // 合并：跳过与现有地点重名的（去重），追加到末尾
        Set<String> existing = new LinkedHashSet<>();
        repository.findByProjectId(projectId).forEach(l -> existing.add(norm(l.getName())));
        List<WorldLocationDTO> toAdd = extracted.stream()
                .filter(dto -> !existing.contains(norm(dto.getName())))
                .toList();
        if (toAdd.isEmpty()) {
            return list(projectId);
        }
        int maxSort = repository.findByProjectId(projectId).stream()
                .mapToInt(l -> l.getSortOrder() == null ? 0 : l.getSortOrder()).max().orElse(0);
        int i = 0;
        for (WorldLocationDTO dto : toAdd) {
            ActorWorldLocation entity = toEntity(projectId, dto);
            entity.setSortOrder(maxSort + 1 + i++);
            repository.save(entity);
        }
        log.info("[地点提取] 项目 {} 合并新增 {} 条（已有 {} 条）", projectId, toAdd.size(), existing.size());
        return list(projectId);
    }

    /**
     * 地点 AI 流式提取并合并（SSE 用，「AI 提取」按钮主路径）：
     * 基于已存地理文本，流式调用 AI 并<b>逐条回调</b>每条已提取地点（后端逐条日志 + 前端逐条显示），
     * 完成后与现有地点按名称去重合并入库。
     *
     * @param userId    归属用户 ID（AI 用量归属）
     * @param projectId 项目 ID
     * @param progress  进度回调（可为 null）：onStart / onLocation(每条) / onDone(新增数, 提取总数)
     */
    public void extractAndMergeStream(Long userId, Long projectId, LocationProgress progress) {
        String geography = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getGeography).orElse("");
        if (geography == null || geography.isBlank()) {
            throw new BizException(400, "当前项目暂无「地理设定」文本，无法 AI 提取；请先在世界观中填写地理设定");
        }
        log.info("[地点提取] 任务开始：项目={}，输入地理文本 {} 字", projectId, geography.length());
        if (progress != null) progress.onStart();
        long start = System.currentTimeMillis();
        List<WorldLocationDTO> extracted = extractStream(userId, geography, progress);
        if (extracted.isEmpty()) {
            log.warn("[地点提取] 任务结束：项目={}，AI 未识别到明确地点", projectId);
            if (progress != null) progress.onDone(0, 0);
            return;
        }
        // 与现有地点按名称去重，追加到末尾（流内重复项已在提取阶段去重）
        Set<String> existing = new LinkedHashSet<>();
        repository.findByProjectId(projectId).forEach(l -> existing.add(norm(l.getName())));
        int maxSort = repository.findByProjectId(projectId).stream()
                .mapToInt(l -> l.getSortOrder() == null ? 0 : l.getSortOrder()).max().orElse(0);
        int i = 0;
        int added = 0;
        for (WorldLocationDTO dto : extracted) {
            if (!existing.add(norm(dto.getName()))) {
                log.info("[地点提取] 跳过重复地点：{}（已存在）", dto.getName());
                continue;
            }
            ActorWorldLocation entity = toEntity(projectId, dto);
            entity.setSortOrder(maxSort + 1 + i++);
            repository.save(entity);
            added++;
            log.info("[地点提取] 已入库地点：{}（类型={}，重要度={}）", dto.getName(), dto.getType(), dto.getImportance());
        }
        log.info("[地点提取] 任务结束：项目={}，提取 {} 个，新增 {} 个，耗时 {}ms",
                projectId, extracted.size(), added, System.currentTimeMillis() - start);
        if (progress != null) progress.onDone(added, extracted.size());
    }

    /**
     * 流式 AI 提取地点（不落库）：使用 chatCompletionStream 逐 token 接收，
     * 通过 {@link IncrementalJsonArrayParser} 识别<b>完整的地点对象</b>并逐条回调 + 逐条日志，
     * 实现「生成一条就输出一条」；结束后以完整流式文本权威重解析兜底（补全可能遗漏的元素）。
     *
     * @param userId    归属用户 ID
     * @param geography 世界观「地理设定」文本
     * @param progress  进度回调（可为 null）
     * @return 提取的地点列表（已去重）
     */
    public List<WorldLocationDTO> extractStream(Long userId, String geography, LocationProgress progress) {
        if (geography == null || geography.isBlank()) {
            return List.of();
        }
        // 注意：任务开始事件由调用方（extractAndMergeStream）统一发出，此处不再重复 onStart，避免 start 事件重复
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = buildExtractPrompt(truncate(geography, GEO_SOURCE_MAX));
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON 数组，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.2, EXTRACT_MAX_TOKENS, false);
                List<WorldLocationDTO> collected = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                StringBuilder fullText = new StringBuilder();
                AtomicInteger seq = new AtomicInteger(0);
                int[] tokens = new int[4]; // 0=prompt 1=completion 2=cacheHit 3=cacheMiss
                IncrementalJsonArrayParser parser = new IncrementalJsonArrayParser(elementRaw -> {
                    try {
                        WorldLocationDTO dto = parseLocationObject(elementRaw);
                        if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
                            return;
                        }
                        if (!seen.add(norm(dto.getName()))) {
                            return; // 流内同名去重
                        }
                        collected.add(dto);
                        int n = seq.incrementAndGet();
                        log.info("[地点提取] 已提取地点 #{}：名称={}，类型={}，重要度={}",
                                n, dto.getName(), dto.getType(), dto.getImportance());
                        if (progress != null) progress.onLocation(dto);
                    } catch (Exception ex) {
                        log.warn("[地点提取] 流式元素解析跳过：{}", ex.getMessage());
                    }
                });
                aiProviderRouter.chatCompletionStream(userId, null, null, req,
                        delta -> {
                            fullText.append(delta);
                            parser.feed(delta);
                        },
                        usage -> {
                            tokens[0] = usage.promptTokens();
                            tokens[1] = usage.completionTokens();
                            tokens[2] = usage.cacheHitTokens();
                            tokens[3] = usage.cacheMissTokens();
                        });
                usageLogService.record(userId, null, null, null, null, "location_extract",
                        tokens[0], tokens[1], tokens[2], tokens[3],
                        (int) (System.currentTimeMillis() - start));
                // 权威兜底：以完整流式文本重解析，补齐增量解析器可能遗漏的元素（如末尾未闭合对象/异常格式）
                try {
                    for (WorldLocationDTO dto : parseLocationText(fullText.toString())) {
                        if (seen.add(norm(dto.getName()))) {
                            collected.add(dto);
                            int n = seq.incrementAndGet();
                            log.info("[地点提取] 补全地点 #{}：名称={}，类型={}，重要度={}",
                                    n, dto.getName(), dto.getType(), dto.getImportance());
                            if (progress != null) progress.onLocation(dto);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[地点提取] 权威重解析跳过（流式文本可能被截断）：{}", e.getMessage());
                }
                log.info("[地点提取] 流式第 {} 次成功：耗时 {}ms，提取地点 {} 个，token {}/{}",
                        attempt, System.currentTimeMillis() - start, collected.size(), tokens[0], tokens[1]);
                return collected;
            } catch (Exception e) {
                lastError = e;
                log.warn("[地点提取] 流式第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[地点提取] 流式任务失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "地点提取失败：" + friendlyError(lastError) + "，请稍后重试或手动添加");
    }

    /**
     * 地点提取进度回调（供 SSE 流式推送：任务开始 / 每条地点 / 完成）。
     */
    public interface LocationProgress {
        /**
         * 任务开始（AI 即将生成）。
         */
        default void onStart() {
        }

        /**
         * 每提取完一个地点回调一次。
         *
         * @param dto 已提取并清洗后的地点
         */
        void onLocation(WorldLocationDTO dto);

        /**
         * 全部完成。
         *
         * @param added   本次新增（入库）地点数
         * @param extracted 本次提取地点总数
         */
        default void onDone(int added, int extracted) {
        }
    }

    /**
     * 流式 JSON 数组增量元素解析器：从模型逐 token 输出中识别<b>完整数组元素</b>（对象）并回调。
     * <p>目标输出形态：[ {...}, {...} ]。当某个元素的花括号配对闭合时，将该元素原始文本回调给
     * 业务层解析并逐条输出（后端逐条日志 + 前端逐条显示）。
     * 已处理：字符串内的花括号/转义、嵌套对象与嵌套数组、Markdown 代码块前后缀、顶层数组结尾。</p>
     */
    static final class IncrementalJsonArrayParser {
        private final Consumer<String> onElement;
        private final StringBuilder buf = new StringBuilder();
        private boolean started;   // 是否已开始收集元素内容
        private boolean arrayEnded; // 顶层数组已结束（不再接收）
        private int braceDepth = 0; // 当前元素内花括号嵌套深度
        private int arrayDepth = 0; // 数组嵌套深度（含顶层）
        private boolean inString;
        private boolean escaped;

        /**
         * @param onElement 每识别到一个完整数组元素（JSON 对象文本）时的回调
         */
        IncrementalJsonArrayParser(Consumer<String> onElement) {
            this.onElement = onElement;
        }

        /**
         * 喂入一段流式增量文本。
         *
         * @param chunk 模型增量输出（可拆行/拆字符，任意切分均可）
         */
        void feed(String chunk) {
            if (arrayEnded || chunk == null || chunk.isEmpty()) {
                return;
            }
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (arrayEnded) {
                    return;
                }
                if (inString) {
                    buf.append(c);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                switch (c) {
                    case '"' -> {
                        buf.append(c);
                        inString = true;
                        if (!started) started = true;
                    }
                    case '{' -> {
                        braceDepth++;
                        buf.append(c);
                        started = true;
                    }
                    case '}' -> {
                        braceDepth--;
                        buf.append(c);
                        if (braceDepth == 0 && started) {
                            emitElement();
                        }
                    }
                    case '[' -> {
                        arrayDepth++;
                        if (arrayDepth > 1 && started) {
                            // 元素内的嵌套数组：内容进入缓冲
                            buf.append(c);
                        }
                        // 顶层数组开始（arrayDepth==1）不进入缓冲
                    }
                    case ']' -> {
                        arrayDepth--;
                        if (arrayDepth <= 0 && started) {
                            // 顶层数组结束：丢弃未闭合残留
                            arrayEnded = true;
                            buf.setLength(0);
                            return;
                        }
                        if (started) {
                            buf.append(c);
                        }
                    }
                    default -> {
                        if (started) {
                            buf.append(c);
                        }
                    }
                }
            }
        }

        /** 当前元素已完整闭合：回调其文本并复位，准备收集下一个元素 */
        private void emitElement() {
            String raw = buf.toString().trim();
            buf.setLength(0);
            started = false;
            braceDepth = 0;
            if (!raw.isEmpty()) {
                onElement.accept(raw);
            }
        }
    }

    /**
     * 解析单个地点对象文本（流式增量元素的容错解析）。
     *
     * @param raw 单个 JSON 对象文本（如 {"name":...,"type":...,"intro":...,"importance":3}）
     * @return 清洗后的地点 DTO；文本非法或名称缺失时返回 null
     */
    private WorldLocationDTO parseLocationObject(String raw) {
        try {
            JsonNode n = objectMapper.readTree(raw.trim());
            if (n == null || !n.isObject()) {
                return null;
            }
            String name = n.path("name").asText("").trim();
            if (name.isBlank()) {
                return null;
            }
            WorldLocationDTO dto = new WorldLocationDTO();
            dto.setName(truncate(name, NAME_MAX));
            dto.setType(truncate(n.path("type").asText(""), TYPE_MAX));
            dto.setIntro(truncate(n.path("intro").asText(""), INTRO_MAX));
            dto.setImportance(clampImportance(n.path("importance").asInt(3)));
            return dto;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /** 组装 AI 提取提示词（明确 JSON Schema + 约束，降低非法输出概率） */
    private String buildExtractPrompt(String geography) {
        return """
                你是一位世界地理学家。请从以下世界观「地理设定」文本中，识别出所有有明确命名的地点，并为每个地点生成：
                - name：地点名称（必须来自原文，不得编造）
                - type：地点类型（如：城市/城镇/村庄/酒馆/森林/山脉/河流/王国/港口/学院/遗迹 等）
                - intro：详细简介（80-200 字：描述位置、风貌、功能、与世界观的关系）
                - importance：重要度（1-5 整数，5=对世界/主线至关重要，3=常见地点，1=次要）

                输出为严格 JSON 数组，最多 50 个：
                [ { "name": "地点名", "type": "类型", "intro": "简介", "importance": 3 } ]

                要求：只输出 JSON 数组本身，不要 Markdown 代码块、不要任何前后缀文字；
                若文本中没有明确地点，输出 []；同名地点只保留最重要的一个。

                —— 地理设定 ——
                %s
                """.formatted(geography);
    }

    /**
     * 解析 AI 输出为地点列表（增强容错：剥离说明/代码块、清洗尾部逗号、跳过空名、去重）。
     *
     * @param content 模型输出文本
     * @return 地点列表（≤30）
     */
    private List<WorldLocationDTO> parseLocationText(String content) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            node = objectMapper.readTree(stripTrailingComma(json));
        }
        // 兼容「数组」与「{locations:[...]}」两种输出形态
        JsonNode arr = node.isArray() ? node : node.path("locations");
        List<WorldLocationDTO> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String name = n.path("name").asText("").trim();
                if (name.isBlank() || !seen.add(norm(name))) {
                    continue; // 跳过空名与重名
                }
                WorldLocationDTO dto = new WorldLocationDTO();
                dto.setName(truncate(name, NAME_MAX));
                dto.setType(truncate(n.path("type").asText(""), TYPE_MAX));
                dto.setIntro(truncate(n.path("intro").asText(""), INTRO_MAX));
                dto.setImportance(clampImportance(n.path("importance").asInt(3)));
                out.add(dto);
                if (out.size() >= MAX_LOCATIONS) {
                    break;
                }
            }
        }
        return out;
    }

    /** 清洗 JSON 尾部多余逗号（如 [{...},] / {"a":1,}） */
    private static String stripTrailingComma(String json) {
        return json.replaceAll(",\\s*\\]", "]").replaceAll(",\\s*\\}", "}");
    }

    /** DTO → 实体（新建） */
    private ActorWorldLocation toEntity(Long projectId, WorldLocationDTO dto) {
        ActorWorldLocation entity = new ActorWorldLocation();
        entity.setProjectId(projectId);
        entity.setName(truncate(dto.getName(), NAME_MAX));
        entity.setType(truncate(dto.getType(), TYPE_MAX));
        entity.setIntro(truncate(dto.getIntro(), INTRO_MAX));
        entity.setImportance(clampImportance(dto.getImportance() == null ? 3 : dto.getImportance()));
        entity.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        return entity;
    }

    /** 实体 → 视图 */
    private WorldLocationVO toVO(ActorWorldLocation e) {
        WorldLocationVO vo = new WorldLocationVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setName(e.getName());
        vo.setType(e.getType());
        vo.setIntro(e.getIntro());
        vo.setImportance(e.getImportance());
        vo.setSortOrder(e.getSortOrder());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    /** 按项目+主键定位实体（不存在返回 404） */
    private ActorWorldLocation requireOwned(Long projectId, Long id) {
        return repository.findById(id)
                .filter(l -> projectId.equals(l.getProjectId()))
                .orElseThrow(() -> new BizException(404, "地点不存在或无权访问"));
    }

    /** 重要度归一（1-5） */
    private static int clampImportance(int v) {
        return Math.max(1, Math.min(5, v));
    }

    /** 名称归一（去空格/大小写，用于去重） */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 字符串截断（null 安全） */
    private String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 异常用户友好提示 */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) return be.getMessage();
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) return ae.getMessage();
        return e.getMessage() == null ? "未知错误" : e.getMessage();
    }
}
