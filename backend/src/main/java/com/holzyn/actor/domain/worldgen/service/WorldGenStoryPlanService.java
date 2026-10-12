/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorStoryPlan;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorStoryPlanRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorkflowDefinition;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * story-plan 工作流专用服务。
 * <p>职责（worldgen 子域内部分工，非对外门面）：</p>
 * <ul>
 * <li><b>输入注入</b>{@link #buildStoryInputs}：story-plan 工作流创建任务时自动拼装
 * worldSetting 9 分节摘要 + 角色结构化摘要（{@link #buildCharacterSummaries}，按重要度
 * 排序限流 {@code story-character-limit} 默认 12）+ genre——供故事引擎节点锚定
 * 「角色必须活在设定里」；完整模式与项目空间「生成故事规划」按钮共用；</li>
 * <li><b>故事引擎落库</b>{@link #persistStoryEngine}：脚本节点 persist_story_engine——
 * 重放引擎产物 → 渲染 Markdown → 写/复用 {@code actor_story_plan} 行（版本递增）；
 * 幂等：同任务已落库 → 复用行更新，不重复建行/不重复分配版本；</li>
 * <li><b>风格档案落库</b>{@link #persistStyleProfile}：脚本节点 persist_style_profile——
 * 重放风格产物 → 渲染 Markdown → <b>提取世界氛围片段</b>（{@link #extractAtmosphereSegment}
 * 过滤元叙事概念）→ 更新同版本行；</li>
 * <li><b>氛围片段</b>：供环境描述 / 宏观演化叙事 / 世界事件润色三处注入（角色可感知世界气质，
 * 不感知叙事概念，零 OOC）。</li>
 * </ul>
 * <p>事务说明：本服务方法均<b>不含 AI 调用</b>（纯读产物 + 写行），可带短事务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldGenStoryPlanService {

    /** story-plan 工作流编码（与 worldgen_story_plan_v1.json code 一致） */
    public static final String CODE_STORY_PLAN_V1 = "worldgen_story_plan_v1";

    /** 故事引擎节点输出变量（风格节点上游引用 + 落库数据源） */
    static final String VAR_STORY_ENGINE_OUTPUT = "agent_story_engine_output";

    /** 风格档案节点输出变量（落库数据源） */
    static final String VAR_STYLE_PROFILE_OUTPUT = "agent_style_profile_output";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单角色摘要最大字符（detail 降级提取时截断，防提示词膨胀） */
    private static final int CHAR_SUMMARY_MAX = 420;

    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final WorldGenSchemaValidator schemaValidator;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorStoryPlanRepository storyPlanRepository;
    private final WorldGenProperties props;

    /**
     * 世界氛围读取服务（story-plan 字段注入——保持构造器签名不变；直接 new 时为空 →
     * 落库后不主动失效氛围缓存，零破坏）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.StoryAtmosphereService storyAtmosphereService;

    // ==================== 输入注入 ====================

    /**
     * 组装 story-plan 工作流任务输入（worldSetting + characterSummaries + genre + language）。
     * <p>worldSetting = 项目最新世界设定分节摘要（复用 character 服务的摘要拼装）；
     * characterSummaries = 项目既有角色摘要（按重要度排序限流）；
     * 项目无设定/无角色时退化空串（降级路径，故事引擎仅凭 genre 生成）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 任务输入 Map（worldSetting/characterSummaries/genre/language）
     */
    public Map<String, String> buildStoryInputs(Long projectId) {
        Map<String, String> inputs = new LinkedHashMap<>();
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws != null) {
            inputs.put("worldSetting", WorldGenCharacterService.worldSettingText(ws));
            if (ws.getGenre() != null && !ws.getGenre().isBlank()) {
                inputs.put("genre", ws.getGenre());
            }
        }
        inputs.put("characterSummaries", buildCharacterSummaries(projectId));
        inputs.putIfAbsent("genre", "");
        inputs.putIfAbsent("language", "中文");
        return inputs;
    }

    /**
     * 角色结构化摘要（故事引擎「角色档案」输入）：按重要度降序 + 主角优先排序，
     * 限流 {@code story-character-limit}（默认 12）人。
     * <p>数据源：{@code actor_profile} 结构列 + {@code persona_json}（已建档时结构化提取：
     * dims.personality.traitsSummary / dims.values.coreDesires/coreFears/lifeGoals）或
     * {@code detail} 深度文本降级（截断 CHAR_SUMMARY_MAX）；<b>不含</b>角色记忆/状态等运行时数据。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 摘要文本（无角色返回空串）
     */
    String buildCharacterSummaries(Long projectId) {
        List<ActorCharacter> all = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (all.isEmpty()) {
            return "";
        }
        int limit = props == null || props.getStoryCharacterLimit() <= 0 ? 12 : props.getStoryCharacterLimit();
        List<ActorCharacter> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((ActorCharacter c) -> c.getIsProtagonist() != null ? c.getIsProtagonist() : 0)
                .thenComparingInt(c -> c.getImportance() == null ? 0 : c.getImportance())
                .reversed()
                .thenComparing(ActorCharacter::getName));
        if (sorted.size() > limit) {
            sorted = sorted.subList(0, limit);
        }
        StringBuilder sb = new StringBuilder();
        for (ActorCharacter c : sorted) {
            sb.append("【").append(trimTo(c.getName(), 50)).append("】");
            sb.append(importanceText(c.getImportance())).append("；");
            appendPart(sb, "定位", trimTo(c.getTitle(), 60));
            appendPart(sb, "性别", trimTo(c.getGender(), 10));
            appendPart(sb, "种族", trimTo(c.getRace(), 30));
            appendPart(sb, "年龄", c.getAge() == null ? null : String.valueOf(c.getAge()));
            appendPart(sb, "归属", trimTo(c.getAffiliation(), 60));
            appendPart(sb, "职业", trimTo(c.getOccupation(), 30));
            String persona = personaSummary(c.getPersonaJson());
            if (persona != null) {
                sb.append("\n").append(persona);
            } else if (c.getDetail() != null && !c.getDetail().isBlank()) {
                sb.append("\n").append(trimTo(c.getDetail(), CHAR_SUMMARY_MAX));
            }
            sb.append("\n\n");
        }
        String text = sb.toString().trim();
        log.info("[worldgen-story] 角色摘要注入：project={}, 角色 {} 位（限流 {}）", projectId, sorted.size(), limit);
        return text;
    }

    /** persona_json 结构化摘要（dims 各维提取；未建档/非法返回 null 走 detail 降级）。 */
    private String personaSummary(String personaJson) {
        if (personaJson == null || personaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(personaJson);
            JsonNode dims = root.path("dims");
            StringBuilder sb = new StringBuilder();
            appendTextLine(sb, "性格", textOf(dims, "personality", "temperamentType"),
                    textOf(dims, "personality", "traitsSummary"));
            appendListLine(sb, "欲望", textList(dims, "values", "coreDesires"));
            appendListLine(sb, "恐惧", textList(dims, "values", "coreFears"));
            appendListLine(sb, "人生目标", textList(dims, "values", "lifeGoals"));
            String t = sb.toString().trim();
            return t.isBlank() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 脚本节点：故事引擎落库（两段式第一阶段） ====================

    /**
     * 故事引擎落库（脚本节点 persist_story_engine）：
     * 重放引擎产物 → 渲染 Markdown → 写/复用 {@code actor_story_plan} 行（版本递增）。
     * <p>幂等：同任务已落库 → 复用行更新（重跑/续跑不重复建行、不重复分配版本）；
     * 版本分配 = 项目当前最大版本 + 1（首行 1）。</p>
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（version/created/updated + story_plan 摘要）
     */
    @Transactional
    public Map<String, Object> persistStoryEngine(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        String engineJson = latestSuccessOutputOf(task, VAR_STORY_ENGINE_OUTPUT);
        if (engineJson == null || engineJson.isBlank()) {
            throw new BizException(400, "故事引擎尚未成功生成（节点 agent_story_engine 无成功产物），无法落库");
        }
        // 再次校验（防残缺产物落库）
        String validated = schemaValidator.validateStoryPlan(engineJson);
        JsonNode plan = schemaValidator.readStoryPlan(validated);
        String md = renderStoryPlanMarkdown(plan);

        ActorStoryPlan row = storyPlanRepository.findByProjectIdAndTaskId(task.getProjectId(), taskId).orElse(null);
        boolean created = false;
        if (row == null) {
            row = new ActorStoryPlan();
            row.setProjectId(task.getProjectId());
            row.setTaskId(taskId);
            row.setVersion(nextVersion(task.getProjectId()));
            created = true;
        }
        row.setStoryPlanJson(validated);
        row.setStoryPlanMd(md);
        // 风格层两段式：本次未生成（引擎重跑）不清空已落库的风格档案
        storyPlanRepository.save(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", row.getVersion());
        out.put("created", created);
        out.put("storyPlan", plan);
        log.info("[worldgen-story] 故事引擎落库：task={}, project={}, version={}, created={}",
                taskId, task.getProjectId(), row.getVersion(), created);
        return out;
    }

    // ==================== 脚本节点：风格档案落库（两段式第二阶段 + 氛围提取） ====================

    /**
     * 风格档案落库（脚本节点 persist_style_profile）：
     * 重放风格产物 → 渲染 Markdown → 提取世界氛围片段 → 更新同版本行。
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（version/atmosphereLength + style_profile）
     */
    @Transactional
    public Map<String, Object> persistStyleProfile(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        String styleJson = latestSuccessOutputOf(task, VAR_STYLE_PROFILE_OUTPUT);
        if (styleJson == null || styleJson.isBlank()) {
            throw new BizException(400, "风格档案尚未成功生成（节点 agent_style_profile 无成功产物），无法落库");
        }
        String validated = schemaValidator.validateStyleProfile(styleJson);
        JsonNode profile = schemaValidator.readStyleProfile(validated);
        ActorStoryPlan row = storyPlanRepository.findByProjectIdAndTaskId(task.getProjectId(), taskId)
                .orElseThrow(() -> new BizException(400, "故事引擎尚未落库（无 actor_story_plan 行），无法补风格档案"));
        row.setStyleProfileJson(validated);
        row.setStyleProfileMd(renderStyleProfileMarkdown(profile));
        // 氛围片段：风格档案 + 引擎层过滤元概念
        JsonNode plan = schemaValidator.readStoryPlan(row.getStoryPlanJson());
        row.setAtmosphereSegment(extractAtmosphereSegment(plan, profile));
        storyPlanRepository.save(row);
        // 世界氛围缓存失效（注入点下次读取即取到新氛围；未装配忽略）
        if (storyAtmosphereService != null) {
            storyAtmosphereService.invalidate(task.getProjectId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", row.getVersion());
        out.put("atmosphereLength", row.getAtmosphereSegment() == null ? 0 : row.getAtmosphereSegment().length());
        out.put("styleProfile", profile);
        log.info("[worldgen-story] 风格档案落库：task={}, project={}, version={}, 氛围片段 {} 字",
                taskId, task.getProjectId(), row.getVersion(), out.get("atmosphereLength"));
        return out;
    }

    // ==================== 渲染 ====================

    /**
     * 故事引擎 → Markdown（前端展示/存档用）。
     */
    static String renderStoryPlanMarkdown(JsonNode plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 故事宏观规划（三层力学引擎）\n\n");
        appendSurface(sb, plan.path("surface"));
        appendEngine(sb, plan.path("engine"));
        appendPayoff(sb, plan.path("payoff"));
        appendIssues(sb, plan.path("issues"));
        return sb.toString().trim();
    }

    private static void appendSurface(StringBuilder sb, JsonNode s) {
        if (!s.isObject()) {
            return;
        }
        sb.append("## 一、显性层（世界气质）\n");
        sb.append("> ").append(s.path("essence").asText("")).append("\n\n");
        appendField(sb, "世界前提", s.path("expanded_premise"));
        appendField(sb, "核心冲突", s.path("core_conflict"));
        appendField(sb, "生活体验", s.path("world_experience"));
        appendField(sb, "气质锚点", s.path("tone_reference"));
        JsonNode tags = s.path("world_tags");
        if (tags.isArray() && !tags.isEmpty()) {
            sb.append("**气质标签**：").append(joinText(tags)).append("\n\n");
        }
    }

    private static void appendEngine(StringBuilder sb, JsonNode e) {
        if (!e.isObject()) {
            return;
        }
        sb.append("## 二、引擎层（结构性力量）\n");
        sb.append("> ").append(e.path("essence").asText("")).append("\n\n");
        appendField(sb, "居民困局", e.path("living_trap"));
        appendField(sb, "冲突力学", e.path("conflict_engine"));
        JsonNode layers = e.path("conflict_layers");
        if (layers.isObject()) {
            sb.append("**冲突分层**\n");
            appendField(sb, "- 外部", layers.path("external"));
            appendField(sb, "- 内部", layers.path("internal"));
            appendField(sb, "- 关系", layers.path("relational"));
            sb.append("\n");
        }
        appendField(sb, "世界之谜", e.path("mystery_box"));
        appendField(sb, "节律循环", e.path("rhythm_cycle"));
        JsonNode dual = e.path("dual_story");
        if (dual.isObject()) {
            sb.append("**双线结构**\n");
            appendField(sb, "- 前台生活", dual.path("foreground"));
            appendField(sb, "- 后台暗流", dual.path("background"));
            sb.append("\n");
        }
    }

    private static void appendPayoff(StringBuilder sb, JsonNode p) {
        if (!p.isObject()) {
            return;
        }
        sb.append("## 三、兑现层（张力兑现）\n");
        sb.append("> ").append(p.path("essence").asText("")).append("\n\n");
        JsonNode archetypes = p.path("tension_archetypes");
        if (archetypes.isArray() && !archetypes.isEmpty()) {
            sb.append("**张力原型**\n");
            int i = 1;
            for (JsonNode a : archetypes) {
                if (a.isTextual() && !a.asText().isBlank()) {
                    sb.append(i++).append(". ").append(a.asText().trim()).append("\n");
                }
            }
            sb.append("\n");
        }
        appendField(sb, "戏剧性时刻", p.path("dramatic_moments"));
        appendField(sb, "情绪节律", p.path("emotional_rhythm"));
        appendField(sb, "氛围要点", p.path("atmosphere_notes"));
    }

    private static void appendIssues(StringBuilder sb, JsonNode issues) {
        if (issues == null || !issues.isArray() || issues.isEmpty()) {
            return;
        }
        sb.append("## 待办标记（issues）\n");
        for (JsonNode it : issues) {
            if (it.isObject()) {
                sb.append("- [").append(it.path("type").asText("")).append("] ")
                        .append(it.path("description").asText("")).append("\n");
            }
        }
        sb.append("\n");
    }

    /**
     * 风格档案 → Markdown（前端展示/存档用）。
     */
    static String renderStyleProfileMarkdown(JsonNode profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 世界风格档案（风格宪法）\n\n");
        sb.append("**基调**：").append(profile.path("tone").asText("")).append("\n\n");
        JsonNode refs = profile.path("references");
        if (refs.isArray() && !refs.isEmpty()) {
            sb.append("**风格参照**\n");
            for (JsonNode r : refs) {
                if (r.isObject()) {
                    sb.append("- ").append(r.path("work").asText("")).append("（").append(r.path("tags").asText(""))
                            .append("）：学 ").append(r.path("learn").asText(""))
                            .append("；用 ").append(r.path("apply").asText("")).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("**叙事人格**：").append(profile.path("narrative_persona").asText("")).append("\n\n");
        JsonNode commandments = profile.path("commandments");
        if (commandments.isArray() && !commandments.isEmpty()) {
            sb.append("**表达边界**\n");
            int i = 1;
            for (JsonNode c : commandments) {
                if (c.isTextual() && !c.asText().isBlank()) {
                    sb.append(i++).append(". ").append(c.asText().trim()).append("\n");
                }
            }
            sb.append("\n");
        }
        String genreNotes = profile.path("genre_notes").asText("");
        if (!genreNotes.isBlank()) {
            sb.append("**题材笔法**：").append(genreNotes).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 世界氛围片段提取（三处注入素材） ====================

    /**
     * 世界氛围片段提取：从风格档案 + 故事引擎层过滤<b>元叙事概念</b>后的世界气质文本。
     * <p>设计（零 OOC 核心）：角色可以感知「这个世界的味道/氛围/张力底色」，但<b>绝不感知</b>
     * 「读者承诺」「爆点」「叙事人格」「创作边界」等元概念——故提取 tone / 参考作品落地 /
     * 显性层 essence / 引擎层 essence / 兑现层 essence / atmosphere_notes / 情绪节律 等
     * 世界内在气质素材，拼接为注入段（环境描述 / 宏观叙事 / 世界事件润色共用）。</p>
     *
     * @param plan 故事引擎对象（可 null）
     *
     * @param profile 风格档案对象（可 null）
     *
     * @return 氛围片段文本（素材缺失返回空串）
     */
    static String extractAtmosphereSegment(JsonNode plan, JsonNode profile) {
        StringBuilder sb = new StringBuilder();
        if (plan != null && plan.isObject()) {
            JsonNode surface = plan.path("surface");
            if (surface.isObject()) {
                appendPart(sb, "世界气质", surface.path("essence").asText(""));
                appendPart(sb, "生活质感", surface.path("world_experience").asText(""));
            }
            JsonNode engine = plan.path("engine");
            if (engine.isObject()) {
                appendPart(sb, "运行张力", engine.path("essence").asText(""));
            }
            JsonNode payoff = plan.path("payoff");
            if (payoff.isObject()) {
                appendPart(sb, "氛围要点", payoff.path("atmosphere_notes").asText(""));
                appendPart(sb, "情绪节律", payoff.path("emotional_rhythm").asText(""));
            }
        }
        if (profile != null && profile.isObject()) {
            appendPart(sb, "基调", profile.path("tone").asText(""));
            JsonNode refs = profile.path("references");
            if (refs.isArray() && !refs.isEmpty()) {
                List<String> applied = new ArrayList<>();
                for (JsonNode r : refs) {
                    if (r.isObject()) {
                        String work = r.path("work").asText("").trim();
                        String apply = r.path("apply").asText("").trim();
                        if (!work.isBlank() && !apply.isBlank()) {
                            applied.add(work + "：" + apply);
                        }
                    }
                }
                if (!applied.isEmpty()) {
                    sb.append("【气质参照】").append(String.join("；", applied)).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    // ==================== 查询 ====================

    /**
     * 项目故事规划列表（版本升序=历史链，供 VO/前端展示）。
     *
     * @param projectId 项目 ID
     *
     * @return 全部版本（含未补风格层的行）
     */
    public List<ActorStoryPlan> listByProject(Long projectId) {
        return storyPlanRepository.findByProjectIdOrderByVersionAsc(projectId);
    }

    /**
     * 项目最新完整故事规划（氛围注入/展示默认用；风格层未补返回该行，可空）。
     *
     * @param projectId 项目 ID
     *
     * @return 最新版本行（无返回 null）
     */
    public ActorStoryPlan latestOf(Long projectId) {
        return storyPlanRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
    }

    // ==================== 内部工具 ====================

    /** 项目下一版本号（当前最大版本 + 1；无记录返回 1）。 */
    private int nextVersion(Long projectId) {
        return storyPlanRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorStoryPlan::getVersion)
                .map(v -> v + 1)
                .orElse(1);
    }

    /** 重要度 → 文本。 */
    private String importanceText(Integer importance) {
        if (importance == null) {
            return "普通";
        }
        return switch (importance) {
            case 4 -> "主角";
            case 3 -> "重要";
            case 2 -> "次要";
            default -> "普通";
        };
    }

    /** 「标签：值；」片段（值为空跳过）。 */
    private static void appendPart(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append("：").append(value.trim()).append("；");
    }

    /** Markdown 字段（「**标签**：值」段）。 */
    private static void appendField(StringBuilder sb, String label, JsonNode v) {
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return;
        }
        sb.append("**").append(label).append("**：").append(v.isTextual() ? v.asText() : v.toString()).append("\n\n");
    }

    /** 单行文本（多源拼接，非空才写）。 */
    private static void appendTextLine(StringBuilder sb, String label, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (joined.length() > 0) {
                    joined.append("；");
                }
                joined.append(p.trim());
            }
        }
        if (joined.length() > 0) {
            sb.append(label).append("：").append(joined).append("\n");
        }
    }

    /** 列表行（「标签：a、b、c」）。 */
    private static void appendListLine(StringBuilder sb, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(label).append("：").append(String.join("、", items)).append("\n");
    }

    /** persona_json 多级路径提取文本列表（容错返回空列表）。 */
    private static List<String> textList(JsonNode root, String... path) {
        List<String> out = new ArrayList<>();
        JsonNode n = walk(root, path);
        if (n != null && n.isArray()) {
            for (JsonNode it : n) {
                if (it.isTextual() && !it.asText().isBlank()) {
                    out.add(it.asText().trim());
                }
            }
        }
        return out;
    }

    /** persona_json 多级路径提取文本（容错返回空串）。 */
    private static String textOf(JsonNode root, String... path) {
        JsonNode n = walk(root, path);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    /** 多级路径安全遍历（缺失返回 null）。 */
    private static JsonNode walk(JsonNode root, String... path) {
        JsonNode n = root;
        for (String p : path) {
            if (n == null || !n.isObject()) {
                return null;
            }
            n = n.get(p);
        }
        return n;
    }

    /** 数组元素拼接（「a、b、c」）。 */
    private static String joinText(JsonNode arr) {
        List<String> items = new ArrayList<>();
        for (JsonNode it : arr) {
            if (it.isTextual() && !it.asText().isBlank()) {
                items.add(it.asText().trim());
            }
        }
        return String.join("、", items);
    }

    /** 字符串截断（null 安全）。 */
    private static String trimTo(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 取任务某输出变量的最新成功产物（跨节点按输出变量匹配；无成功产物返回 null）。
     */
    private String latestSuccessOutputOf(ActorWorldgenTask task, String outputVariable) {
        WorkflowDefinition def = requireDefinition(task);
        String ownerNodeId = null;
        for (String nodeId : def.getExecutionOrder()) {
            var node = def.node(nodeId);
            if (node != null && outputVariable.equals(node.getOutputVariable())) {
                ownerNodeId = nodeId;
                break;
            }
        }
        if (ownerNodeId == null) {
            return null;
        }
        List<ActorWorldgenNodeAttempt> attempts = attemptRepository
                .findByTaskIdAndNodeIdOrderByIdAsc(task.getId(), ownerNodeId);
        for (int i = attempts.size() - 1; i >= 0; i--) {
            ActorWorldgenNodeAttempt a = attempts.get(i);
            if (WorldGenStatus.SUCCEEDED.equals(a.getStatus()) && a.getOutputJson() != null) {
                return a.getOutputJson();
            }
        }
        return null;
    }

    /** 解析任务对应工作流定义（缺失抛 404）。 */
    private WorkflowDefinition requireDefinition(ActorWorldgenTask task) {
        ActorWorldgenWorkflow workflow = workflowRepository.findById(task.getWorkflowId())
                .orElseThrow(() -> new BizException(404, "任务关联的工作流定义不存在：" + task.getWorkflowId()));
        return definitionParser.parse(workflow.getCode(), workflow.getName(), workflow.getVersion(),
                workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getVariablesJson());
    }

    /** 加载任务（缺失抛 404）。 */
    private ActorWorldgenTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(404, "任务不存在：" + taskId));
    }
}
