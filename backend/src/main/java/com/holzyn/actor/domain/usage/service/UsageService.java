package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 用量统计服务（P2 管理后台「AI 用量」核心）。
 * <p>职责：按当前用户聚合 actor_usage_log（项目/模型/场景/日期维度 + 明细分页），
 * 供首页「AI 用量」Tab 展示；所有数据按 userId 归属隔离。</p>
 * <p>所属模块：service/usage（用量子域）</p>
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    /** 用量日志仓库 */
    private final ActorUsageLogRepository logRepository;

    /** 项目仓库（解析项目名） */
    private final ActorProjectRepository projectRepository;

    /** 角色仓库（解析角色名，明细「角色ID → 角色名」） */
    private final ActorCharacterRepository characterRepository;

    /**
     * 统计当前用户的 AI 用量（支持 projectId / scene / model / dateRange 筛选）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目筛选（可空）
     * @param scene     场景筛选（可空：card_gen/dialog/action）
     * @param model     模型筛选（可空）
     * @param startDate 起始日期（可空，yyyy-MM-dd）
     * @param endDate   结束日期（可空，yyyy-MM-dd）
     * @return {summary, byProject, byScene, byModel, byDate, detail}
     */
    public Map<String, Object> stats(Long userId, Long projectId, String scene, String model,
                                     String startDate, String endDate) {
        LocalDateTime start = parseDate(startDate, true);
        LocalDateTime end = parseDate(endDate, false);
        List<ActorUsageLog> logs;
        if (start != null && end != null) {
            logs = logRepository.findByUserIdAndCreatedAtBetweenOrderByIdDesc(userId, start, end);
        } else {
            logs = logRepository.findByUserIdOrderByIdDesc(userId);
        }
        // 内存筛选（项目/场景/模型）
        List<ActorUsageLog> filtered = logs.stream()
                .filter(l -> projectId == null || projectId.equals(l.getProjectId()))
                .filter(l -> scene == null || scene.isBlank() || scene.equals(l.getScene()))
                .filter(l -> model == null || model.isBlank() || model.equals(l.getModel()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary(filtered));
        result.put("byProject", groupBy(filtered, l -> String.valueOf(l.getProjectId() == null ? 0 : l.getProjectId()),
                id -> projectName(userId, id)));
        result.put("byScene", groupBy(filtered, l -> l.getScene() == null ? "unknown" : l.getScene(),
                s -> sceneName(s)));
        result.put("byModel", groupBy(filtered, l -> l.getModel() == null ? "unknown" : l.getModel(),
                m -> m));
        result.put("byDate", groupBy(filtered, l -> l.getCreatedAt() == null
                ? "" : l.getCreatedAt().toLocalDate().toString(), d -> d));
        // 明细：为每行补充 characterName（角色名）与 sceneName（场景中文名），
        // 避免前端直接展示 characterId 数字与 scene 编码（2026-08-18 增强）
        Set<Long> charIds = filtered.stream()
                .map(ActorUsageLog::getCharacterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> charNames = characterNameMap(userId, charIds);
        result.put("detail", filtered.stream()
                .map(l -> detailRow(l,
                        charNames.getOrDefault(l.getCharacterId(),
                                l.getCharacterId() == null ? "项目级" : "角色 " + l.getCharacterId()),
                        sceneName(l.getScene())))
                .toList());
        return result;
    }

    /**
     * 汇总指标（调用次数 / 输入输出 token / 总耗时）。
     *
     * @param logs 日志列表
     * @return 汇总 Map
     */
    static Map<String, Object> summary(List<ActorUsageLog> logs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", logs.size());
        m.put("tokenIn", logs.stream().mapToInt(l -> l.getTokenIn() == null ? 0 : l.getTokenIn()).sum());
        m.put("tokenOut", logs.stream().mapToInt(l -> l.getTokenOut() == null ? 0 : l.getTokenOut()).sum());
        int cacheHit = logs.stream().mapToInt(l -> l.getCacheHitTokens() == null ? 0 : l.getCacheHitTokens()).sum();
        int cacheMiss = logs.stream().mapToInt(l -> l.getCacheMissTokens() == null ? 0 : l.getCacheMissTokens()).sum();
        m.put("cacheHit", cacheHit);
        m.put("cacheMiss", cacheMiss);
        m.put("cacheHitRate", cacheHitRate(cacheHit, cacheMiss));
        m.put("durationMs", logs.stream().mapToInt(l -> l.getDurationMs() == null ? 0 : l.getDurationMs()).sum());
        return m;
    }

    /**
     * 计算输入缓存命中率（命中 / (命中 + 未命中)，保留 1 位小数，单位 %）。
     *
     * @param hit  命中缓存的输入 token 数
     * @param miss 未命中缓存的输入 token 数
     * @return 命中率（0-100）；无缓存数据时返回 0
     */
    static double cacheHitRate(int hit, int miss) {
        long total = (long) hit + miss;
        return total == 0 ? 0.0 : Math.round(hit * 1000.0 / total) / 10.0;
    }

    /**
     * 按分组键聚合（count / tokenIn / tokenOut / durationMs）。
     *
     * @param logs          日志列表
     * @param keyExtractor  分组键提取器
     * @param labelResolver 分组标签解析器
     * @return 分组聚合列表
     */
    static List<Map<String, Object>> groupBy(List<ActorUsageLog> logs, Function<ActorUsageLog, String> keyExtractor,
                                                Function<String, String> labelResolver) {
        Map<String, List<ActorUsageLog>> groups = logs.stream().collect(Collectors.groupingBy(keyExtractor));
        return groups.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", e.getKey());
                    m.put("label", labelResolver.apply(e.getKey()));
                    m.put("count", e.getValue().size());
                    m.put("tokenIn", e.getValue().stream().mapToInt(l -> l.getTokenIn() == null ? 0 : l.getTokenIn()).sum());
                    m.put("tokenOut", e.getValue().stream().mapToInt(l -> l.getTokenOut() == null ? 0 : l.getTokenOut()).sum());
                    int gHit = e.getValue().stream().mapToInt(l -> l.getCacheHitTokens() == null ? 0 : l.getCacheHitTokens()).sum();
                    int gMiss = e.getValue().stream().mapToInt(l -> l.getCacheMissTokens() == null ? 0 : l.getCacheMissTokens()).sum();
                    m.put("cacheHit", gHit);
                    m.put("cacheMiss", gMiss);
                    m.put("cacheHitRate", cacheHitRate(gHit, gMiss));
                    m.put("durationMs", e.getValue().stream().mapToInt(l -> l.getDurationMs() == null ? 0 : l.getDurationMs()).sum());
                    return m;
                })
                .sorted((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")))
                .toList();
    }

    /**
     * 解析项目名（id=0 视为「未归属项目」）。
     *
     * @param userId 归属用户 ID
     * @param idStr  项目 ID 字符串
     * @return 项目名
     */
    private String projectName(Long userId, String idStr) {
        if ("0".equals(idStr)) {
            return "未归属项目";
        }
        try {
            Long id = Long.parseLong(idStr);
            return projectRepository.findById(id)
                    .filter(p -> userId.equals(p.getUserId()))
                    .map(ActorProject::getName).orElse("项目 " + id);
        } catch (Exception e) {
            return "项目 " + idStr;
        }
    }

    /**
     * 批量解析角色名（角色ID → 角色名，仅限当前用户所属项目的角色）。
     * <p>角色归属经「角色→项目→用户」校验：先取当前用户的全部项目 ID，
     * 再过滤角色列表，只保留属于这些项目的角色——避免越权泄露其他用户角色名。</p>
     *
     * @param userId 归属用户 ID
     * @param ids    角色 ID 集合（可空）
     * @return 角色ID → 角色名 映射（找不到的角色不包含）
     */
    private Map<Long, String> characterNameMap(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        // 当前用户的项目集合（角色归属校验）
        Set<Long> ownedProjectIds = projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(userId, 0)
                .stream().map(ActorProject::getId).collect(Collectors.toSet());
        return characterRepository.findAllById(ids).stream()
                .filter(c -> c.getProjectId() != null && ownedProjectIds.contains(c.getProjectId()))
                .collect(Collectors.toMap(ActorCharacter::getId, ActorCharacter::getName, (a, b) -> a));
    }

    /**
     * 明细行转换：实体 → 前端可读 Map。
     * <p>保留原实体全部字段（时间/模型/token/缓存/耗时/角色ID/场景编码），
     * 额外补充 characterName（角色名，无角色=项目级）与 sceneName（场景中文名）。
     * 静态包私有便于单测。</p>
     *
     * @param l             用量日志实体
     * @param characterName 角色名（已解析，缺失时传入兜底文案）
     * @param sceneName     场景中文名（已解析，未知场景回退原始编码）
     * @return 明细行 Map
     */
    static Map<String, Object> detailRow(ActorUsageLog l, String characterName, String sceneName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("userId", l.getUserId());
        m.put("projectId", l.getProjectId());
        m.put("characterId", l.getCharacterId());
        m.put("providerId", l.getProviderId());
        m.put("model", l.getModel());
        m.put("scene", l.getScene());
        m.put("tokenIn", l.getTokenIn());
        m.put("tokenOut", l.getTokenOut());
        m.put("cacheHitTokens", l.getCacheHitTokens());
        m.put("cacheMissTokens", l.getCacheMissTokens());
        m.put("durationMs", l.getDurationMs());
        m.put("cost", l.getCost());
        m.put("createdAt", l.getCreatedAt());
        m.put("characterName", characterName);
        m.put("sceneName", sceneName);
        return m;
    }

    /**
     * 场景标签。
     * <p>2026-08-18 补齐：title_gen / location_extract / memory / import / embedding 等
     * 既有记录场景此前缺标签（按场景页显示原始编码）。静态包私有便于单测。</p>
     *
     * @param scene 场景编码
     * @return 中文标签
     */
    static String sceneName(String scene) {
        if (scene == null) {
            return "unknown";
        }
        return switch (scene) {
            case "card_gen" -> "角色卡生成";
            case "dialog" -> "对话";
            case "action" -> "行动";
            case "crowd" -> "人群";
            case "title_gen" -> "标题生成";
            case "location_extract" -> "地点提取";
            case "memory" -> "记忆抽取";
            case "import" -> "导入解析";
            case "embedding" -> "向量化（RAG）";
            case "relation_gen" -> "关系生成";
            case "crowd_category_gen" -> "字段字典拟定";
            case "ordinary_npc_gen" -> "居民批量生成";
            case "crowd_ai" -> "居民AI调度";
            case "world_segment" -> "世界观分段";
            case "world_segment_characters" -> "角色分离";
            case "world_time_infer" -> "世界时间推断";
            default -> scene;
        };
    }

    /**
     * 解析日期范围（isStart=true 取当天 00:00；false 取当天 23:59:59）。
     *
     * @param raw     原始日期字符串
     * @param isStart 是否为起始
     * @return 时间（可空）
     */
    private LocalDateTime parseDate(String raw, boolean isStart) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            return isStart ? d.atStartOfDay() : d.atStartOfDay().plusDays(1).minusNanos(1);
        } catch (Exception e) {
            return null;
        }
    }
}