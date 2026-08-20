package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 场景/地点服务（V2.1 世界演化）。
 * <p>职责：承载 /api/projects/{id}/scenes 系列接口——项目场景（地点）的增删改查，
 * 供世界演化「选择指定场景（地点）」与时间线事件关联使用。场景背景设定作为演化 AI 注入源。</p>
 * <p>vP5-7.6 新增：AI 自动填充场景（aiGenerate）——以「世界观设定 + 角色档案 + 已有场景」
 * 为数据源调用 AI 生成一批场景，每个场景记录 source（来源依据），保证有来源、逻辑自洽。</p>
 * <p>所属模块：service/world（世界演化子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneService {

    /** 场景仓库 */
    private final ActorSceneRepository sceneRepository;

    /** 项目仓库 */
    private final ActorProjectRepository projectRepository;

    /** 世界观设定仓库（AI 自动填充数据源） */
    private final ActorWorldSettingRepository worldSettingRepository;

    /** 角色仓库（AI 自动填充数据源） */
    private final ActorCharacterRepository characterRepository;

    /** AI Provider 路由（AI 自动填充调用） */
    private final AiProviderRouter aiProviderRouter;

    /** Prompt 模板服务（渲染 scene_generate 模板） */
    private final PromptTemplateService promptTemplateService;

    /** 用量日志服务（记录 AI 调用消耗） */
    private final UsageLogService usageLogService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** AI 场景生成温度（低温度保证结构稳定） */
    private static final double GEN_TEMPERATURE = 0.8;

    /** AI 场景生成最大输出 token */
    private static final int GEN_MAX_TOKENS = 2048;

    /** 单个角色档案送入 AI 的最大长度（控制输入 token 成本） */
    private static final int CHAR_DETAIL_LIMIT = 300;

    /**
     * 查询项目场景列表（启用优先、ID 升序）。
     *
     * @param projectId 项目 ID
     * @return 场景视图列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long projectId) {
        requireProject(projectId);
        return sceneRepository.findByProjectIdOrderByEnabledDescIdAsc(projectId).stream()
                .map(this::toVO).toList();
    }

    /**
     * 新增场景。
     *
     * @param projectId 项目 ID
     * @param body      入参：{name 必填, description, location, background}
     * @return 新增后的场景视图
     */
    @Transactional
    public Map<String, Object> create(Long projectId, Map<String, Object> body) {
        requireProject(projectId);
        String name = body == null ? null : str(body.get("name"));
        if (name == null || name.isBlank()) {
            throw new BizException(400, "场景名称不能为空");
        }
        ActorScene scene = new ActorScene();
        scene.setProjectId(projectId);
        scene.setName(name.trim());
        scene.setDescription(str(body.get("description")));
        scene.setLocation(str(body.get("location")));
        scene.setBackground(str(body.get("background")));
        scene.setSource(str(body.get("source")));
        scene = sceneRepository.save(scene);
        log.info("[场景] 新增：项目={} 场景={} 名称={}", projectId, scene.getId(), scene.getName());
        return toVO(scene);
    }

    /**
     * 编辑场景。
     *
     * @param id   场景主键
     * @param body 入参
     * @return 更新后的场景视图
     */
    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        ActorScene scene = requireScene(id);
        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name == null || name.isBlank()) {
                throw new BizException(400, "场景名称不能为空");
            }
            scene.setName(name.trim());
        }
        if (body.containsKey("description")) scene.setDescription(str(body.get("description")));
        if (body.containsKey("location")) scene.setLocation(str(body.get("location")));
        if (body.containsKey("background")) scene.setBackground(str(body.get("background")));
        if (body.containsKey("source")) scene.setSource(str(body.get("source")));
        if (body.containsKey("enabled")) {
            scene.setEnabled(Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0);
        }
        scene = sceneRepository.save(scene);
        return toVO(scene);
    }

    /**
     * 删除场景。
     *
     * @param id 场景主键
     */
    @Transactional
    public void delete(Long id) {
        ActorScene scene = requireScene(id);
        sceneRepository.delete(scene);
        log.info("[场景] 删除：场景={} 项目={}", id, scene.getProjectId());
    }

    /**
     * AI 自动填充场景：以「世界观设定 + 角色档案 + 已有场景」为数据源调用 AI 生成一批场景。
     * <p>要求：每个场景带 source（来源依据，取自世界观/角色的哪部分设定），保证有来源、逻辑自洽；
     * 与已有场景不重复。AI 失败或输出无有效场景时抛出 BizException（用户可见中文提示）。</p>
     *
     * @param projectId 项目 ID
     * @param userId    归属用户 ID（AI 调用凭据归属）
     * @param body      入参：{count? 生成数量 1~10，默认 3}
     * @return 本次新增的场景视图列表
     */
    @Transactional
    public List<Map<String, Object>> aiGenerate(Long projectId, Long userId, Map<String, Object> body) {
        requireProject(projectId);
        int count = body == null ? 3 : (body.get("count") instanceof Number n ? n.intValue() : 3);
        count = Math.max(1, Math.min(count, 10));

        // ① 组装 AI 输入：世界观设定 + 角色档案 + 已有场景（防重复）
        String worldSetting = buildWorldSettingText(projectId);
        String charactersText = buildCharactersText(projectId);
        String existingScenes = sceneRepository.findByProjectIdOrderByEnabledDescIdAsc(projectId).stream()
                .map(ActorScene::getName).collect(Collectors.joining("、"));
        if (existingScenes.isBlank()) {
            existingScenes = "（暂无）";
        }

        // ② 渲染 scene_generate 模板并调用 AI（json_object 结构化输出）
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_SCENE_GENERATE,
                Map.of("count", String.valueOf(count),
                        "world_setting", worldSetting,
                        "characters", charactersText,
                        "existing_scenes", existingScenes));
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), GEN_TEMPERATURE, GEN_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        AiChatResult result;
        try {
            result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
        } catch (Exception e) {
            // AI 不可用（未配置 API / 调用失败）：给出友好提示
            throw new BizException(400, "AI 生成失败：" + (e.getMessage() == null ? "请检查模型 API 配置" : e.getMessage()));
        }
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "action",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        // ③ 解析 AI 输出并落库（逐条校验名称，缺失跳过）
        List<Map<String, Object>> parsed = parseGenerated(result.content(), count);
        if (parsed.isEmpty()) {
            throw new BizException(400, "AI 未能生成有效场景，请重试或调整模型配置");
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> p : parsed) {
            ActorScene scene = new ActorScene();
            scene.setProjectId(projectId);
            scene.setName(str(p.get("name")));
            scene.setLocation(str(p.get("location")));
            scene.setDescription(str(p.get("description")));
            scene.setBackground(str(p.get("background")));
            scene.setSource(str(p.get("source")));
            if (scene.getName() == null || scene.getName().isBlank()) {
                continue;
            }
            scene = sceneRepository.save(scene);
            created.add(toVO(scene));
            log.info("[场景] AI 自动填充：项目={} 场景={} 名称={}", projectId, scene.getId(), scene.getName());
        }
        if (created.isEmpty()) {
            throw new BizException(400, "AI 输出无有效场景（名称缺失），请重试");
        }
        return created;
    }

    /**
     * 组装世界观设定文本（结构化字段 + 完整自由文本，AI 生成场景的数据源）。
     *
     * @param projectId 项目 ID
     * @return 世界观文本（无设定时返回占位）
     */
    private String buildWorldSettingText(Long projectId) {
        return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(this::worldText)
                .orElse("（未提供世界观设定）");
    }

    /**
     * 将世界观实体组装为带【分节标签】的文本（便于 AI 引用来源）。
     *
     * @param w 世界观实体
     * @return 世界观文本
     */
    private String worldText(ActorWorldSetting w) {
        StringBuilder sb = new StringBuilder();
        if (w.getName() != null && !w.getName().isBlank()) sb.append("名称：").append(w.getName()).append("\n");
        if (w.getGenre() != null && !w.getGenre().isBlank()) sb.append("题材：").append(w.getGenre()).append("\n");
        if (w.getEra() != null && !w.getEra().isBlank()) sb.append("时代：").append(w.getEra()).append("\n");
        if (w.getGeography() != null && !w.getGeography().isBlank())
            sb.append("【地理设定】").append(w.getGeography()).append("\n");
        if (w.getFactions() != null && !w.getFactions().isBlank())
            sb.append("【势力阵营】").append(w.getFactions()).append("\n");
        if (w.getMagicSystem() != null && !w.getMagicSystem().isBlank())
            sb.append("【规则体系】").append(w.getMagicSystem()).append("\n");
        if (w.getCulture() != null && !w.getCulture().isBlank())
            sb.append("【文化风俗】").append(w.getCulture()).append("\n");
        if (w.getHistory() != null && !w.getHistory().isBlank())
            sb.append("【历史背景】").append(w.getHistory()).append("\n");
        if (w.getFreeText() != null && !w.getFreeText().isBlank())
            sb.append("【完整世界观】").append(w.getFreeText()).append("\n");
        return sb.toString().trim();
    }

    /**
     * 组装角色档案文本（姓名/头衔/常驻位置/详细信息，AI 生成场景的数据源）。
     *
     * @param projectId 项目 ID
     * @return 角色档案文本（无角色时返回占位）
     */
    private String buildCharactersText(Long projectId) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            return "（暂无角色）";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorCharacter ch : chars) {
            sb.append("· ").append(ch.getName());
            if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
                sb.append("（").append(ch.getTitle()).append("）");
            }
            if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
                sb.append("，常驻：").append(ch.getLocation());
            }
            String detail = ch.getDetail();
            if (detail != null && !detail.isBlank()) {
                sb.append("\n  档案：").append(clip(detail, CHAR_DETAIL_LIMIT));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 解析 AI 生成的场景数组（静态纯逻辑，可单测）。
     * <p>规则：提取 JSON 数组 → 逐条取 name/location/description/background/source；
     * 名称缺失的条目跳过；最多取 count 条。</p>
     *
     * @param json  AI 输出文本
     * @param count 期望数量上限
     * @return 解析出的场景列表（无有效数组返回空）
     */
    static List<Map<String, Object>> parseGenerated(String json, int count) {
        if (json == null || count <= 0) {
            return List.of();
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return List.of();
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            if (!node.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (out.size() >= count) {
                    break;
                }
                String name = item.path("name").asText("").trim();
                if (name.isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("location", item.path("location").asText("").trim());
                m.put("description", item.path("description").asText("").trim());
                m.put("background", item.path("background").asText("").trim());
                m.put("source", item.path("source").asText("").trim());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 截断长文本（超长加省略号）。
     *
     * @param s   原始文本
     * @param max 最大长度
     * @return 截断后的文本（null 返回空串）
     */
    private String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * 场景实体 → 视图 Map。
     *
     * @param scene 场景实体
     * @return 视图 Map
     */
    private Map<String, Object> toVO(ActorScene scene) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", scene.getId());
        m.put("projectId", scene.getProjectId());
        m.put("name", scene.getName());
        m.put("description", scene.getDescription());
        m.put("location", scene.getLocation());
        m.put("background", scene.getBackground());
        m.put("source", scene.getSource());
        m.put("enabled", scene.getEnabled());
        m.put("createdAt", scene.getCreatedAt());
        m.put("updatedAt", scene.getUpdatedAt());
        return m;
    }

    /**
     * 按主键查询场景（归属校验）。
     *
     * @param id 场景主键
     * @return 场景实体
     */
    public ActorScene requireScene(Long id) {
        ActorScene scene = sceneRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "场景不存在或无权访问"));
        requireProject(scene.getProjectId());
        return scene;
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 取值辅助：Object 转字符串。
     *
     * @param v 原始对象
     * @return 字符串（null 返回 null）
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
