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
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * character 工作流专用服务。
 * <p>职责（对 workgen 子域内部分工，非对外门面）：</p>
 * <ul>
 * <li><b>世界设定注入</b>{@link #buildCharacterInputs}：character 工作流创建任务时若未提供
 * worldSetting，自动从项目最新 {@code actor_world_setting} 拼装（9 分节摘要），
 * 供阵容节点锚定「角色必须活在设定里」——完整模式（build confirm 后串接）与
 * 项目空间「补角色」按钮共用此入口；</li>
 * <li><b>阵容检查点</b>{@link #persistRoster}：脚本节点 persist_character_roster——
 * 从任务最新成功尝试重放阵容产物并再次校验（防残缺），写检查点；</li>
 * <li><b>角色落库</b>{@link #persistCharacters}：脚本节点 persist_character_profiles——
 * 把 depth 产物（每角色 4 维深度）合并 roster 结构信息 → 创建 {@code actor_profile}
 * 角色行（type=special，detail=深度文本作为两段式建档锚定）；<b>幂等</b>：同项目同名
 * 已存在角色跳过（重跑/续跑不重复建行）；建档本身由世界初始化第 2 步
 * {@code generateMissingCards} 幂等承接（两段式 bootstrap，锚定 detail）。</li>
 * </ul>
 * <p>事务说明：本服务方法均<b>不含 AI 调用</b>（纯读产物 + 建行），可带短事务；
 * 建档（AI）在初始化第 2 步执行，不在本服务内。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldGenCharacterService {

    /** character 工作流编码（与 worldgen_character_v1.json code 一致） */
    public static final String CODE_CHARACTER_V1 = "worldgen_character_v1";

    /** roster 节点输出变量（深度节点上游引用 + 落库结构信息来源） */
    static final String VAR_ROSTER_OUTPUT = "agent_roster_output";

    /** depth 节点输出变量（落库深度信息来源） */
    static final String VAR_DEPTH_OUTPUT = "agent_character_depth_output";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldgenTaskRepository taskRepository;
    private final ActorWorldgenWorkflowRepository workflowRepository;
    private final ActorWorldgenNodeAttemptRepository attemptRepository;
    private final WorldGenDefinitionParser definitionParser;
    private final WorldGenSchemaValidator schemaValidator;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;

    // ==================== 世界设定注入 ====================

    /**
     * 组装 character 工作流任务输入（worldSetting + genre + language）。
     * <p>worldSetting = 项目最新世界设定的分节摘要文本（角色生成锚定「角色须活在设定里」）；
     * 项目无世界观设定时退化为空串（阵容节点将仅凭 genre 生成，属降级路径）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 任务输入 Map（worldSetting/genre/language）
     */
    public Map<String, String> buildCharacterInputs(Long projectId) {
        Map<String, String> inputs = new LinkedHashMap<>();
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws != null) {
            inputs.put("worldSetting", worldSettingText(ws));
            if (ws.getGenre() != null && !ws.getGenre().isBlank()) {
                inputs.put("genre", ws.getGenre());
            }
        }
        inputs.putIfAbsent("genre", "");
        inputs.putIfAbsent("language", "中文");
        return inputs;
    }

    /**
     * 世界设定 → 角色生成锚定文本（9 分节摘要，防整段超长截断提示词）。
     *
     * @param ws 世界设定实体（最新版本）
     *
     * @return 摘要文本（各分节非空才拼接）
     */
    static String worldSettingText(ActorWorldSetting ws) {
        if (ws == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "世界名称", ws.getName(), 100);
        appendSection(sb, "题材类型", ws.getGenre(), 50);
        appendSection(sb, "时代背景", ws.getEra(), 500);
        appendSection(sb, "地理设定", ws.getGeography(), 2000);
        appendSection(sb, "势力阵营", ws.getFactions(), 2000);
        appendSection(sb, "规则体系", ws.getMagicSystem(), 2000);
        appendSection(sb, "文化风俗", ws.getCulture(), 2000);
        appendSection(sb, "历史背景", ws.getHistory(), 2000);
        appendSection(sb, "自由文本", ws.getFreeText(), 2000);
        return sb.toString().trim();
    }

    /** 分节（非空 + 截断）。 */
    private static void appendSection(StringBuilder sb, String label, String text, int max) {
        if (text == null || text.isBlank()) {
            return;
        }
        String t = text.trim();
        if (t.length() > max) {
            t = t.substring(0, max);
        }
        sb.append("【").append(label).append("】\n").append(t).append("\n\n");
    }

    // ==================== 脚本节点：阵容检查点 ====================

    /**
     * 阵容检查点（脚本节点 persist_character_roster）：
     * 从任务最新成功尝试重放 roster 产物 → 再次校验 → 汇总。
     *
     * @param taskId 任务 ID
     *
     * @return 汇总（rosterChecked=角色数）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> persistRoster(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        String rosterJson = latestSuccessOutputOf(task, VAR_ROSTER_OUTPUT);
        if (rosterJson == null || rosterJson.isBlank()) {
            throw new BizException(400, "角色阵容尚未成功生成（节点 agent_roster 无成功产物），无法检查点");
        }
        // 再次校验（防残缺产物进入后续深度生成）
        String validated = schemaValidator.validateRoster(rosterJson);
        int count = schemaValidator.readRoster(validated).size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rosterChecked", count);
        out.put("roster", MAPPER.readTree(validated));
        log.info("[worldgen-character] 阵容检查点通过：task={}, 角色 {} 位", taskId, count);
        return out;
    }

    // ==================== 脚本节点：角色落库（actor_profile） ====================

    /**
     * 角色落库（脚本节点 persist_character_profiles）：
     * depth 产物（每角色 4 维深度）合并 roster 结构信息 → 创建 actor_profile 角色行。
     * <p>幂等：同项目同名（deleted=0）已存在 → 跳过（续跑/重跑不重复建行）；
     * detail = 深度文本（两段式建档 {@code PersonaBootstrapService} 的锚定输入，
     * 建档由世界初始化第 2 步幂等承接）。</p>
     *
     * @param taskId 任务 ID
     *
     * @return 落库结果（created/skipped + 角色名列表）
     */
    @Transactional
    public Map<String, Object> persistCharacters(Long taskId) {
        ActorWorldgenTask task = requireTask(taskId);
        String rosterJson = latestSuccessOutputOf(task, VAR_ROSTER_OUTPUT);
        String depthJson = latestSuccessOutputOf(task, VAR_DEPTH_OUTPUT);
        if (depthJson == null || depthJson.isBlank()) {
            throw new BizException(400, "角色深度档案尚未成功生成（节点 agent_character_depth 无成功产物），无法落库");
        }
        JsonNode depthRoot = parseJson(depthJson, "角色深度产物");
        JsonNode depthArr = depthRoot.path("characters");
        if (!depthArr.isArray() || depthArr.isEmpty()) {
            throw new BizException(400, "角色深度产物 characters 数组为空，无法落库");
        }
        // roster 结构信息索引（name → 节点；缺失容忍）
        Map<String, JsonNode> rosterByName = new LinkedHashMap<>();
        if (rosterJson != null && !rosterJson.isBlank()) {
            JsonNode rosterRoot = parseJson(rosterJson, "角色阵容产物");
            for (JsonNode c : rosterRoot.path(WorldGenSchemaValidator.ROSTER_KEY)) {
                rosterByName.put(c.path("name").asText("").trim(), c);
            }
        }
        // 幂等索引：项目已有角色名集合
        Map<String, ActorCharacter> existing = new LinkedHashMap<>();
        for (ActorCharacter c : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(task.getProjectId(), 0)) {
            existing.put(normName(c.getName()), c);
        }

        int created = 0;
        int skipped = 0;
        List<Map<String, Object>> characters = new ArrayList<>();
        for (JsonNode item : depthArr) {
            String name = item.path("name").asText("").trim();
            JsonNode depth = item.path("depth");
            if (name.isBlank() || depth == null || !depth.isObject()) {
                log.warn("[worldgen-character] 深度产物缺少角色名/深度对象，跳过：task={}", taskId);
                continue;
            }
            if (existing.containsKey(normName(name))) {
                characters.add(Map.of("name", name, "created", false));
                skipped++;
                continue;
            }
            JsonNode rosterInfo = rosterByName.get(name);
            ActorCharacter ch = new ActorCharacter();
            ch.setProjectId(task.getProjectId());
            ch.setType("special");
            ch.setName(truncate(name, 50));
            ch.setTitle(truncate(rosterText(rosterInfo, "role"), 50));
            ch.setGender(truncate(rosterText(rosterInfo, "gender"), 20));
            ch.setRace(truncate(rosterText(rosterInfo, "race"), 50));
            ch.setSubRace(null);
            ch.setAffiliation(truncate(rosterText(rosterInfo, "affiliation"), 100));
            ch.setOccupation(truncate(rosterText(rosterInfo, "occupation"), 50));
            ch.setAge(parseAge(rosterText(rosterInfo, "age")));
            String importance = rosterText(rosterInfo, "importance");
            ch.setImportance(resolveImportance(importance));
            ch.setIsProtagonist("主角".equals(importance) ? 1 : 0);
            ch.setStatus(1);
            ch.setDetail(renderDepthDetail(name, rosterInfo, depth));
            characterRepository.save(ch);
            characters.add(Map.of("name", name, "created", true, "characterId", ch.getId()));
            created++;
            log.info("[worldgen-character] 角色落库：task={}, name={}, id={}", taskId, name, ch.getId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("characters", characters);
        log.info("[worldgen-character] 角色落库完成：task={}, 创建 {} 个，跳过 {} 个", taskId, created, skipped);
        return out;
    }

    // ==================== 内部工具 ====================

    /**
     * depth 深度对象 → 建档锚定文本（detail）：结构信息 + 4 维深度逐段渲染。
     *
     * @param name 角色名
     *
     * @param rosterInfo roster 信息节点（可 null）
     *
     * @param depth depth 对象（{"character_depth": {...}} 或裸深度对象）
     *
     * @return 锚定文本
     */
    static String renderDepthDetail(String name, JsonNode rosterInfo, JsonNode depth) {
        JsonNode d = depth.path(WorldGenSchemaValidator.DEPTH_KEY);
        if (!d.isObject()) {
            d = depth;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【角色档案锚定】").append(name);
        String role = rosterText(rosterInfo, "role");
        if (!role.isBlank()) {
            sb.append("（").append(role).append("）");
        }
        sb.append("\n\n");
        JsonNode skeleton = d.path("skeleton");
        if (skeleton.isObject()) {
            appendField(sb, "身份", skeleton.path("title"));
            appendField(sb, "性别", skeleton.path("gender"));
            appendField(sb, "种族", skeleton.path("race"));
            appendField(sb, "年龄", skeleton.path("age"));
            appendField(sb, "出身", skeleton.path("origin"));
            appendField(sb, "背景", skeleton.path("background"));
            appendField(sb, "外貌", skeleton.path("appearance"));
        }
        JsonNode beliefs = d.path("beliefs");
        if (beliefs.isObject()) {
            appendArrayField(sb, "核心价值", beliefs.path("core_values"));
            appendArrayField(sb, "欲望", beliefs.path("desires"));
            appendArrayField(sb, "恐惧", beliefs.path("fears"));
            appendArrayField(sb, "处世信条", beliefs.path("beliefs"));
        }
        JsonNode deep = d.path("deep");
        if (deep.isObject()) {
            appendField(sb, "秘密", deep.path("secret"));
            appendField(sb, "创伤", deep.path("trauma"));
            appendField(sb, "道德边界", deep.path("moral_boundary"));
            appendField(sb, "成长弧线", deep.path("growth_arc"));
        }
        JsonNode voice = d.path("voice");
        if (voice.isObject()) {
            appendField(sb, "说话风格", voice.path("speech_style"));
            appendArrayField(sb, "口头禅", voice.path("catchphrases"));
            appendField(sb, "语气基调", voice.path("tone"));
            appendField(sb, "用词习惯", voice.path("language_habits"));
        }
        return sb.toString().trim();
    }

    /** 单值字段（「标签：值」行）。 */
    private static void appendField(StringBuilder sb, String label, JsonNode v) {
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return;
        }
        sb.append(label).append("：").append(v.isTextual() ? v.asText() : v.toString()).append("\n");
    }

    /** 数组字段（「标签：a、b、c」行）。 */
    private static void appendArrayField(StringBuilder sb, String label, JsonNode v) {
        if (v == null || !v.isArray() || v.isEmpty()) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode it : v) {
            if (it.isTextual() && !it.asText().isBlank()) {
                items.add(it.asText().trim());
            }
        }
        if (items.isEmpty()) {
            return;
        }
        sb.append(label).append("：").append(String.join("、", items)).append("\n");
    }

    /** 取 roster 节点文本字段（缺失/空 → 空串）。 */
    private static String rosterText(JsonNode rosterInfo, String key) {
        if (rosterInfo == null || !rosterInfo.isObject()) {
            return "";
        }
        return rosterInfo.path(key).asText("").trim();
    }

    /** 重要度映射：主角→4 / 重要→3 / 次要→2（对齐 actor_profile.importance 语义 1-5）。 */
    private static Integer resolveImportance(String importance) {
        if (importance == null) {
            return 3;
        }
        return switch (importance.trim()) {
            case "主角" -> 4;
            case "重要" -> 3;
            case "次要" -> 2;
            default -> 3;
        };
    }

    /** 年龄解析：数字/区间取起始数字；无法解析 → null（日级派生列）。 */
    private static Integer parseAge(String age) {
        if (age == null || age.isBlank()) {
            return null;
        }
        String digits = age.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(digits.substring(0, Math.min(3, digits.length())));
            return Math.max(0, Math.min(999, v));
        } catch (Exception e) {
            return null;
        }
    }

    /** 名称归一（幂等判重用：去空格 + 小写）。 */
    private static String normName(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 字符串截断（null 安全）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 取任务某输出变量的最新成功产物（跨节点按输出变量匹配）。
     *
     * @param task 任务实体
     *
     * @param outputVariable 输出变量名
     *
     * @return 产物 JSON 文本（无成功产物返回 null）
     */
    private String latestSuccessOutputOf(ActorWorldgenTask task, String outputVariable) {
        WorkflowDefinition def = requireDefinition(task);
        // 输出变量 → 节点 ID（先找拥有者，再取最新成功尝试）
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

    /** 解析 JSON（失败抛 400）。 */
    private JsonNode parseJson(String json, String what) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException(400, what + " JSON 解析失败：" + e.getMessage());
        }
    }
}
