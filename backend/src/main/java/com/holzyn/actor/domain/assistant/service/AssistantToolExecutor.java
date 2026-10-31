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

package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.assistant.vo.AssistantCardVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.npc.state.vo.NpcStateVO;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldGraphQueryService;
import com.holzyn.actor.domain.world.service.WorldOutlookService;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 助手工具注册表 + 执行器（能力域 A/B/C/D/E/F/G 的数据源落地）。
 * <p>职责：维护 12 个工具的「名称/描述/参数 schema」（供提示词内联）与执行逻辑——
 * 只读工具全部经既有只读门面/服务取数（{@link WorldGraphQueryService}/{@link NpcStateService}/
 * {@link WorldClockService}/{@link WorldOutlookService}/{@link KnowledgeRetrievalService}/
 * {@link AssistantDocsRagService}/{@link AssistantObservationService}），零 AI、零写入；
 * {@code world_intervene}（能力域 F）为<b>受控写入</b>——经 {@link AssistantInterventionService}
 * 按确认粒度（严格/标准/信任）待确认或执行，与沙盘既有干预同一批代码路径。
 * 单个工具失败仅降级该工具（返回失败说明文本），不阻断整个工具循环（设计「失败即降级」）。</p>
 * <p><b>工具清单</b>（12）：
 * world_overview 世界概览 / character_state 角色当前状态 / location_overview 地点 /
 * character_profile 角色深度档案 / macro_overview 宏观态势 / recent_events 事件流 /
 * project_guide 项目导览 / search_world_knowledge 世界知识检索 / search_project_docs 项目文档检索 /
 * trace_chain 决策链路（M3）/ ai_call_stats AI 调用统计（M3）/ world_intervene 世界干预（M4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolExecutor {

    /** 世界状态图谱门面（只读聚合）。 */
    private final WorldGraphQueryService worldGraph;

    /** 角色状态服务（actor_state 权威）。 */
    private final NpcStateService npcStateService;

    /** 世界时钟服务（游戏时间/运行状态）。 */
    private final WorldClockService worldClockService;

    /** 宏观趋势摘要服务。 */
    private final WorldOutlookService worldOutlookService;

    /** 世界知识检索服务（多路召回）。 */
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /** 项目文档检索服务（向量 + 关键词降级）。 */
    private final AssistantDocsRagService docsRagService;

    /** 角色主表仓库（persona_json 读取）。 */
    private final ActorCharacterRepository characterRepository;

    /** 当前用户解析（本地单用户恒 1）。 */
    private final CurrentUserProvider currentUserProvider;

    /** 助手配置（条数上限）。 */
    private final AssistantProperties properties;

    /**
     * 运行观测数据源（能力域 E，M3）——字段注入（不改变构造签名；未装配时 trace_chain/ai_call_stats 降级）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssistantObservationService observationService;

    /**
     * 世界干预服务（能力域 F，M4）——字段注入（不改变构造签名；未装配时 world_intervene 降级）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssistantInterventionService interventionService;

    /** 角色档案 persona_json 注入预算（字符）。 */
    private static final int PERSONA_BUDGET = 1600;

    /** 角色视角事件展示条数。 */
    private static final int PROFILE_EVENT_LIMIT = 5;

    // ==================== 工具定义（供提示词内联） ====================

    /**
     * 工具规格。
     *
     * @param name        工具名
     * @param description 描述（供模型理解何时调用）
     * @param params      参数 JSON Schema（{"type":"object","properties":{...},"required":[...]}）
     */
    public record ToolSpec(String name, String description, String params) {
    }

    /**
     * 模型声明的一次工具调用。
     *
     * @param name      工具名
     * @param arguments 参数字典（来自模型 JSON）
     */
    public record ToolCall(String name, Map<String, Object> arguments) {
    }

    /** 全部工具定义（顺序固定，模型按名称调用）。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> list = new ArrayList<>();
        list.add(new ToolSpec("world_overview",
                "查询世界全局概览：当前游戏时间与运行状态（暂停/运行/倍速）、人口/地点/角色/事件等计数、最近重要事件与编年史。用户问“世界现在怎么样了/今天发生了什么”时调用。",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        list.add(new ToolSpec("character_state",
                "查询角色当前状态：位置、活动、生理需求（饥饿/口渴/精力）、心情、情绪、健康、紧迫度。用户问“XX在做什么/状态怎么样”时调用。参数 character 为角色名或 npc:id。",
                "{\"type\":\"object\",\"properties\":{\"character\":{\"type\":\"string\",\"description\":\"角色名或 npc:id\"}},\"required\":[\"character\"]}"));
        list.add(new ToolSpec("location_overview",
                "查询地点：在场角色、近期事件。用户问“XX地方现在有什么人/发生了什么”时调用。参数 location 为地点名。",
                "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\",\"description\":\"地点名\"}},\"required\":[\"location\"]}"));
        list.add(new ToolSpec("character_profile",
                "查询角色深度档案：身份背景、性格特征、能力、关键关系（好感/信任）、所属势力、近期经历。用户问“介绍下XX/XX是个什么样的人/他和YY关系怎么样”时调用。",
                "{\"type\":\"object\",\"properties\":{\"character\":{\"type\":\"string\",\"description\":\"角色名或 npc:id\"}},\"required\":[\"character\"]}"));
        list.add(new ToolSpec("macro_overview",
                "查询世界宏观态势：人口、粮价、民怨、市场活跃度、势力影响力及其趋势摘要。用户问“粮价为什么涨/村里谁最有影响力/宏观怎么样”时调用。",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        list.add(new ToolSpec("recent_events",
                "查询近期事件流（可按角色或地点过滤）。用户问“最近发生了什么/XX最近经历了什么”时调用。",
                "{\"type\":\"object\",\"properties\":{\"character\":{\"type\":\"string\",\"description\":\"角色名（可空）\"},\"location\":{\"type\":\"string\",\"description\":\"地点名（可空）\"},\"limit\":{\"type\":\"integer\",\"description\":\"条数上限（默认 8，最大 20）\"}},\"required\":[]}"));
        list.add(new ToolSpec("project_guide",
                "查询 HolzynActor 项目功能导览：功能清单、入口位置、一句话说明。用户问“这个项目怎么用/XX功能在哪/记忆系统怎么工作”时调用。参数 keyword 为想了解的模块或功能关键词（可空=全量清单）。",
                "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\",\"description\":\"功能关键词（可空）\"}},\"required\":[]}"));
        list.add(new ToolSpec("search_world_knowledge",
                "从世界知识库检索知识（世界设定/人物知识卡/经历沉淀）。用户问“哪里提到过XX/关于XX的知识”时调用。参数 query 为检索关键词或问题。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"检索关键词或问题\"}},\"required\":[\"query\"]}"));
        list.add(new ToolSpec("search_project_docs",
                "从项目文档（docs/ 目录，主对接文档/设计文档/路线图等）检索内容。用户问“某功能怎么实现/某文档怎么写的/设计文档怎么说的”时调用。参数 query 为检索关键词或问题。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"检索关键词或问题\"}},\"required\":[\"query\"]}"));
        list.add(new ToolSpec("trace_chain",
                "查询/解释 AI 决策链路：某次决策的感知→思考→行动→学习完整环节，或某角色/某项目最近的决策链路。用户问“为什么XX做了这个决定/解释这次决策/最近的AI调用链路”时调用。参数 traceId 为链路 ID（可空，指定则直查单链路），character 为角色名或 npc:id（可空），limit 为链路条数上限（默认 3）。",
                "{\"type\":\"object\",\"properties\":{\"traceId\":{\"type\":\"string\",\"description\":\"决策链路 trace_id（可空）\"},\"character\":{\"type\":\"string\",\"description\":\"角色名或 npc:id（可空）\"},\"limit\":{\"type\":\"integer\",\"description\":\"链路条数上限（默认 3，最大 10）\"}},\"required\":[]}"));
        list.add(new ToolSpec("ai_call_stats",
                "查询 AI 调用统计：总调用/成功率/失败与降级、Token 用量、平均耗时、按场景分布。用户问“AI调用出了什么问题/统计一下调用/最近调用情况”时调用。参数 days 为统计窗口天数（默认 1，最大 90）。",
                "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\",\"description\":\"统计窗口天数（默认 1，最大 90）\"}},\"required\":[]}"));
        list.add(new ToolSpec("world_intervene",
                "世界干预（受限写操作）：把用户意图转化为沙盘既有干预操作——talk 插话（给某角色说一句话）/ action 触发行动（特殊角色一轮决策）/ event 注入事件 / clock 控制时间（暂停/倍速/推进）/ weather 切换天气（晴/雨/雪/阴）。参数 operation 为操作类型，params 为该操作的参数。用户明确要求改变世界时调用（如“让XX去茶馆”“把天气切成雨天”“推进时间”）。",
                "{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"description\":\"操作类型：talk 插话 / action 触发行动 / event 注入事件 / clock 控制时间 / weather 切换天气\"},\"params\":{\"type\":\"object\",\"description\":\"操作参数：talk:{npcRef,content} / action:{npcRef,reason?} / event:{content,title?,kind?} / clock:{advance?|paused?|rate?|worldRunning?} / weather:{condition:晴|雨|雪|阴}\"}},\"required\":[\"operation\",\"params\"]}"));
        return list;
    }

    /**
     * 执行单个工具。
     *
     * @param projectId 项目 ID
     * @param name      工具名（未注册返回失败说明）
     * @param args      参数字典（来自模型 JSON）
     *
     * @return 工具结果（detail 供模型阅读；sources 供来源标注；card 供前端卡片）
     */
    public ToolResult execute(Long projectId, String name, Map<String, Object> args) {
        if (name == null || name.isBlank()) {
            return fail("unknown", "未指定工具");
        }
        try {
            return switch (name) {
                case "world_overview" -> worldOverview(projectId);
                case "character_state" -> characterState(projectId, arg(args, "character"));
                case "location_overview" -> locationOverview(projectId, arg(args, "location"));
                case "character_profile" -> characterProfile(projectId, arg(args, "character"));
                case "macro_overview" -> macroOverview(projectId);
                case "recent_events" -> recentEvents(projectId, args);
                case "project_guide" -> projectGuide(arg(args, "keyword"));
                case "search_world_knowledge" -> searchWorldKnowledge(projectId, arg(args, "query"));
                case "search_project_docs" -> searchProjectDocs(projectId, arg(args, "query"));
                case "trace_chain" -> traceChain(projectId, args);
                case "ai_call_stats" -> aiCallStats(projectId, args);
                case "world_intervene" -> worldIntervene(projectId, args);
                default -> fail(name, "未知工具：" + name);
            };
        } catch (Exception e) {
            log.warn("[助手·工具] {} 执行失败（降级）: {}", name, e.getMessage());
            return fail(name, "查询失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ==================== 工具实现（能力域 B/C/D/A/G） ====================

    /** world_overview：世界概览。 */
    private ToolResult worldOverview(Long projectId) {
        String timeText = timeText(projectId);
        WorldGraphVO.Overview ov = worldGraph.overview(projectId);
        if (ov == null) {
            return fail("world_overview", "世界概览数据暂不可用");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("世界概览：").append(timeText).append('\n');
        WorldGraphVO.TimeNode t = ov.time();
        if (t != null) {
            sb.append("运行状态：").append(t.worldRunning() != null && t.worldRunning() == 1 ? "运行中" : "静止")
                    .append("；暂停=").append(t.paused() == null ? "?" : t.paused())
                    .append("；游戏第 ").append(t.gameDay()).append(" 天 第 ").append(t.gameHour() % 24).append(" 时\n");
        }
        WorldGraphVO.Counts c = ov.counts();
        if (c != null) {
            sb.append("六维计数：地点 ").append(c.locations()).append(" / 角色 ").append(c.characters())
                    .append(" / 事件 ").append(c.events()).append(" / 关系 ").append(c.relations())
                    .append(" / 势力 ").append(c.factions()).append('\n');
        }
        WorldGraphVO.MacroNode m = ov.macro();
        if (m != null) {
            sb.append("宏观：粮价 ").append(fmt(m.grainPrice())).append(" / 民怨 ").append(fmt(m.grievance()))
                    .append(" / 人口 ").append(m.population()).append(" / 季节 ").append(nullTo(m.season()))
                    .append(" / 库存 ").append(fmt(m.grainStock())).append('\n');
        }
        List<WorldGraphVO.EventNode> events = ov.recentEvents() == null ? List.of() : ov.recentEvents();
        if (!events.isEmpty()) {
            sb.append("最近事件（最多 5 条）：\n");
            events.stream().limit(5).forEach(e -> sb.append("- ")
                    .append(e.gameHour() / 24).append(" 日 ").append(e.gameHour() % 24).append(" 时 ")
                    .append(nullTo(e.title())).append('\n'));
        }
        List<WorldGraphVO.ChronicleNode> ch = ov.recentChronicles() == null ? List.of() : ov.recentChronicles();
        if (!ch.isEmpty()) {
            sb.append("最近编年史（最多 3 条）：\n");
            ch.stream().limit(3).forEach(x -> sb.append("- 第 ").append(x.gameDay()).append(" 天 · ")
                    .append(nullTo(x.title())).append('\n'));
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        if (t != null) {
            fields.add(f("游戏时间", nullTo(t.gameTimeText())));
            fields.add(f("运行状态", t.worldRunning() != null && t.worldRunning() == 1 ? "运行中" : "静止"));
        }
        if (c != null) {
            fields.add(f("角色 / 地点", c.characters() + " / " + c.locations()));
            fields.add(f("事件 / 关系", c.events() + " / " + c.relations()));
        }
        if (m != null) {
            fields.add(f("粮价 / 民怨", fmt(m.grainPrice()) + " / " + fmt(m.grievance())));
        }
        return new ToolResult("world_overview", "世界概览已获取",
                sb.toString(), sources("WorldGraphQueryService·全局概览", "更新于 " + timeText),
                new AssistantCardVO("world", "世界概览", fields));
    }

    /** character_state：角色当前状态（位置/活动/需求/心情/情绪/健康）。 */
    private ToolResult characterState(Long projectId, String param) {
        Long id = resolveCharacterId(projectId, param);
        if (id == null) {
            return fail("character_state", "未找到角色：" + nullTo(param) + "（请确认角色名或 npc:id）");
        }
        String timeText = timeText(projectId);
        String npcRef = NpcRefs.of(id);
        ActorCharacter ch = characterRepository.findByIdAndProjectId(id, projectId).orElse(null);
        String location = ch == null ? "未知" : nullTo(ch.getLocation());
        String activity = "（无记录）";
        NpcStateVO st = null;
        var entity = npcStateService.stateFor(projectId, npcRef);
        if (entity != null) {
            st = NpcStateVO.of(entity);
            activity = entity.getLastAction() == null || entity.getLastAction().isBlank()
                    ? "（无活动记录）" : entity.getLastAction();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(ch == null ? npcRef : ch.getName()).append("（").append(npcRef).append("）\n");
        sb.append("位置：").append(location).append('\n');
        sb.append("当前活动：").append(activity).append('\n');
        if (st != null) {
            sb.append("需求：饥饿 ").append(st.getHunger()).append("/100 · 口渴 ").append(st.getThirst())
                    .append("/100 · 精力 ").append(st.getEnergy()).append("/100 · 健康 ").append(st.getHealth()).append('\n');
            sb.append("心情：").append(st.getMood() == null ? "?" : st.getMood());
            if (st.getDominantEmotion() != null && !st.getDominantEmotion().isBlank()) {
                sb.append("；情绪：").append(st.getDominantEmotion())
                        .append(st.getEmotionIntensity() == null ? "" : "（强度 " + st.getEmotionIntensity() + "）");
            }
            sb.append('\n');
            if (st.getUrgencyText() != null && !st.getUrgencyText().isBlank()) {
                sb.append("紧迫度：").append(st.getUrgencyText()).append('\n');
            }
        } else {
            sb.append("需求/心情：暂无状态记录\n");
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        fields.add(f("位置", location));
        fields.add(f("活动", activity));
        if (st != null) {
            fields.add(f("饥饿 / 口渴", st.getHunger() + " / " + st.getThirst()));
            fields.add(f("精力 / 健康", st.getEnergy() + " / " + st.getHealth()));
            fields.add(f("心情", st.getMood() == null ? "?" : String.valueOf(st.getMood())));
            fields.add(f("情绪", st.getDominantEmotion() == null ? "—" : st.getDominantEmotion()));
        }
        List<AssistantSourceVO> src = new ArrayList<>();
        src.add(new AssistantSourceVO("NpcStateService·角色状态（actor_state）", "更新于 " + timeText));
        src.add(new AssistantSourceVO("WorldGraphQueryService·角色视角", null));
        return new ToolResult("character_state", "角色状态已获取：" + (ch == null ? npcRef : ch.getName()),
                sb.toString(), src, new AssistantCardVO("character", "角色状态 · " + (ch == null ? npcRef : ch.getName()), fields));
    }

    /** location_overview：地点在场角色 + 近期事件。 */
    private ToolResult locationOverview(Long projectId, String param) {
        Long id = resolveLocationId(projectId, param);
        if (id == null) {
            return fail("location_overview", "未找到地点：" + nullTo(param) + "（请确认地点名）");
        }
        String timeText = timeText(projectId);
        WorldGraphVO.LocationView view = worldGraph.locationView(projectId, id);
        WorldGraphVO.LocationNode node = view.location();
        StringBuilder sb = new StringBuilder();
        sb.append("地点：").append(nullTo(node.name())).append("（").append(nullTo(node.type())).append("）\n");
        List<WorldGraphVO.CharacterNode> present = view.characters() == null ? List.of() : view.characters();
        sb.append("在场角色（").append(present.size()).append(" 人）：");
        sb.append(present.isEmpty() ? "无" : String.join("、",
                present.stream().map(x -> nullTo(x.name())).toList()));
        sb.append('\n');
        List<WorldGraphVO.EventNode> events = view.events() == null ? List.of() : view.events();
        if (!events.isEmpty()) {
            sb.append("近期事件（最多 5 条）：\n");
            events.stream().limit(5).forEach(e -> sb.append("- ")
                    .append(e.gameHour() / 24).append(" 日 ").append(e.gameHour() % 24).append(" 时 ")
                    .append(nullTo(e.title())).append('\n'));
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        fields.add(f("类型", nullTo(node.type())));
        fields.add(f("在场角色", present.isEmpty() ? "无" : String.join("、",
                present.stream().map(x -> nullTo(x.name())).limit(6).toList())));
        fields.add(f("近期事件", events.isEmpty() ? "无" : events.size() + " 条"));
        return new ToolResult("location_overview", "地点已获取：" + nullTo(node.name()),
                sb.toString(), sources("WorldGraphQueryService·地点视角", "更新于 " + timeText),
                new AssistantCardVO("location", "地点 · " + nullTo(node.name()), fields));
    }

    /** character_profile：角色深度档案（persona + 关系 + 势力 + 近期经历）。 */
    private ToolResult characterProfile(Long projectId, String param) {
        Long id = resolveCharacterId(projectId, param);
        if (id == null) {
            return fail("character_profile", "未找到角色：" + nullTo(param) + "（请确认角色名或 npc:id）");
        }
        String timeText = timeText(projectId);
        ActorCharacter ch = characterRepository.findByIdAndProjectId(id, projectId).orElse(null);
        WorldGraphVO.CharacterView view = worldGraph.characterView(projectId, id);
        WorldGraphVO.CharacterNode node = view.character();
        StringBuilder sb = new StringBuilder();
        sb.append("角色档案：").append(nullTo(node.name()));
        if (node.title() != null && !node.title().isBlank()) {
            sb.append("（").append(node.title()).append("）");
        }
        sb.append(" [").append(node.ref()).append("]\n");
        sb.append("身份：类型=").append(nullTo(node.type())).append("；归属=").append(nullTo(node.affiliation()))
                .append("；职业=").append(nullTo(node.occupation())).append("；阶段=").append(nullTo(node.stage())).append('\n');
        if (ch != null && ch.getPersonaJson() != null && !ch.getPersonaJson().isBlank()) {
            String persona = ch.getPersonaJson();
            sb.append("11 维档案摘要：").append(persona.length() <= PERSONA_BUDGET ? persona : persona.substring(0, PERSONA_BUDGET) + "…")
                    .append('\n');
        }
        List<WorldGraphVO.RelationEdge> rels = view.relations() == null ? List.of() : view.relations();
        if (!rels.isEmpty()) {
            sb.append("关键关系（最多 8 条）：\n");
            rels.stream().limit(8).forEach(r -> sb.append("- ").append(nullTo(r.fromName())).append(" ↔ ")
                    .append(nullTo(r.toName())).append("：").append(nullTo(r.relationType()))
                    .append("（好感 ").append(fmt(r.affinity())).append("，信任 ").append(fmt(r.trust())).append("）\n"));
        }
        List<WorldGraphVO.FactionNode> factions = view.factions() == null ? List.of() : view.factions();
        if (!factions.isEmpty()) {
            sb.append("所属势力：").append(String.join("、", factions.stream()
                    .map(x -> nullTo(x.name())).toList())).append('\n');
        }
        List<WorldGraphVO.EventNode> events = view.events() == null ? List.of() : view.events();
        if (!events.isEmpty()) {
            sb.append("近期经历（最多 ").append(PROFILE_EVENT_LIMIT).append(" 条）：\n");
            events.stream().limit(PROFILE_EVENT_LIMIT).forEach(e -> sb.append("- ")
                    .append(e.gameHour() / 24).append(" 日 ").append(e.gameHour() % 24).append(" 时 ")
                    .append(nullTo(e.title())).append('\n'));
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        fields.add(f("身份", nullTo(node.title())));
        fields.add(f("类型 / 归属", nullTo(node.type()) + " / " + nullTo(node.affiliation())));
        fields.add(f("职业", nullTo(node.occupation())));
        fields.add(f("阶段", nullTo(node.stage())));
        fields.add(f("关系数", String.valueOf(rels.size())));
        return new ToolResult("character_profile", "角色档案已获取：" + nullTo(node.name()),
                sb.toString(), sources("WorldGraphQueryService·角色视角 + actor_profile", "更新于 " + timeText),
                new AssistantCardVO("character", "角色档案 · " + nullTo(node.name()), fields));
    }

    /** macro_overview：宏观态势 + 趋势摘要。 */
    private ToolResult macroOverview(Long projectId) {
        String timeText = timeText(projectId);
        WorldGraphVO.MacroNode m = worldGraph.macro(projectId);
        StringBuilder sb = new StringBuilder();
        sb.append("宏观态势：").append(timeText).append('\n');
        if (m == null) {
            sb.append("宏观数据暂不可用（未装配/无快照）\n");
        } else {
            sb.append("人口 ").append(m.population()).append("；季节 ").append(nullTo(m.season()))
                    .append("；收成系数 ").append(fmt(m.harvestFactor())).append('\n');
            sb.append("经济：粮价 ").append(fmt(m.grainPrice())).append("；库存 ").append(fmt(m.grainStock()))
                    .append("；产量 ").append(fmt(m.grainProduction())).append("；市场活跃 ").append(fmt(m.marketActivity()))
                    .append("；银行存款 ").append(fmt(m.bankDeposit())).append("；货币供给 ").append(fmt(m.currencySupply())).append('\n');
            sb.append("民怨：").append(fmt(m.grievance())).append('\n');
            sb.append("最有影响力势力：").append(nullTo(m.topFaction())).append("；最热议题：").append(nullTo(m.topIssue())).append('\n');
            if (m.summary() != null && !m.summary().isBlank()) {
                sb.append("宏观摘要：").append(m.summary()).append('\n');
            }
        }
        String trend = null;
        try {
            trend = worldOutlookService.trendSummary(projectId);
        } catch (Exception e) {
            log.warn("[助手·工具] 宏观趋势摘要失败（降级）: {}", e.getMessage());
        }
        if (trend != null && !trend.isBlank()) {
            sb.append("趋势摘要：").append(trend).append('\n');
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        if (m != null) {
            fields.add(f("人口", String.valueOf(m.population())));
            fields.add(f("粮价 / 民怨", fmt(m.grainPrice()) + " / " + fmt(m.grievance())));
            fields.add(f("季节 / 收成", nullTo(m.season()) + " / " + fmt(m.harvestFactor())));
            fields.add(f("最有影响力势力", nullTo(m.topFaction())));
        }
        List<AssistantSourceVO> src = new ArrayList<>();
        src.add(new AssistantSourceVO("WorldGraphQueryService·宏观物资 + MacroStateService", "更新于 " + timeText));
        if (trend != null && !trend.isBlank()) {
            src.add(new AssistantSourceVO("WorldOutlookService·趋势摘要", null));
        }
        return new ToolResult("macro_overview", "宏观态势已获取", sb.toString(), src,
                new AssistantCardVO("macro", "宏观态势", fields));
    }

    /** recent_events：事件流（角色/地点过滤）。 */
    private ToolResult recentEvents(Long projectId, Map<String, Object> args) {
        String charParam = arg(args, "character");
        String locParam = arg(args, "location");
        int limit = intArg(args, "limit", 8);
        limit = Math.max(1, Math.min(limit, 20));
        String timeText = timeText(projectId);
        List<WorldGraphVO.EventNode> events;
        String scope = "全局";
        if (locParam != null && !locParam.isBlank()) {
            Long lid = resolveLocationId(projectId, locParam);
            if (lid == null) {
                return fail("recent_events", "未找到地点：" + nullTo(locParam));
            }
            WorldGraphVO.LocationView view = worldGraph.locationView(projectId, lid);
            events = view.events() == null ? List.of() : view.events();
            scope = "地点「" + nullTo(view.location().name()) + "」";
        } else if (charParam != null && !charParam.isBlank()) {
            Long cid = resolveCharacterId(projectId, charParam);
            if (cid == null) {
                return fail("recent_events", "未找到角色：" + nullTo(charParam));
            }
            events = worldGraph.events(projectId, null, cid, limit);
            scope = "角色「" + nullTo(charParam) + "」";
        } else {
            events = worldGraph.events(projectId, null, null, limit);
        }
        if (events.isEmpty()) {
            return new ToolResult("recent_events", "近期事件为空", scope + "近期无事件记录",
                    sources("actor_event 事件流", "更新于 " + timeText), null);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(scope).append("近期事件（").append(events.size()).append(" 条）：\n");
        for (WorldGraphVO.EventNode e : events) {
            sb.append("- 第 ").append(e.gameHour() / 24).append(" 天 ").append(e.gameHour() % 24).append(" 时 · ")
                    .append(nullTo(e.title()));
            if (e.characterNames() != null && !e.characterNames().isEmpty()) {
                sb.append("（").append(String.join("、", e.characterNames()).substring(0,
                        Math.min(40, String.join("、", e.characterNames()).length()))).append("）");
            }
            sb.append('\n');
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        events.stream().limit(6).forEach(e -> fields.add(f("第 " + (e.gameHour() / 24) + " 天 " + (e.gameHour() % 24) + " 时",
                nullTo(e.title()))));
        return new ToolResult("recent_events", "已获取 " + events.size() + " 条事件", sb.toString(),
                sources("actor_event 事件流", "更新于 " + timeText), new AssistantCardVO("event", "近期事件 · " + scope, fields));
    }

    /** project_guide：项目功能导览（静态功能清单 + 关键词过滤）。 */
    private ToolResult projectGuide(String keyword) {
        List<GuideEntry> entries = guideCatalog();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<GuideEntry> filtered = kw.isEmpty() ? entries : entries.stream()
                .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(kw)
                        || e.desc().toLowerCase(Locale.ROOT).contains(kw)
                        || e.entry().toLowerCase(Locale.ROOT).contains(kw))
                .toList();
        if (filtered.isEmpty()) {
            return new ToolResult("project_guide", "未匹配到功能",
                    "未找到与「" + nullTo(keyword) + "」相关的功能项。可用关键词：世界、角色、关系、对话、行动、记忆、知识、人群、演化、图谱、沙盘、设置、导入、地图 等。",
                    sources("内置功能盘点（代码静态清单）", null), null);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HolzynActor 功能导览（匹配 ").append(filtered.size()).append(" 项）：\n");
        for (GuideEntry e : filtered) {
            sb.append("- ").append(e.name()).append("：「").append(e.entry()).append("」")
                    .append(e.desc().isBlank() ? "" : " —— " + e.desc()).append('\n');
        }
        List<AssistantCardVO.Field> fields = filtered.stream().limit(8)
                .map(e -> new AssistantCardVO.Field(e.name(), e.entry()))
                .toList();
        return new ToolResult("project_guide", "功能导览已获取（" + filtered.size() + " 项）", sb.toString(),
                sources("内置功能盘点（代码静态清单）", null), new AssistantCardVO("guide", "功能导览", fields));
    }

    /** search_world_knowledge：世界知识检索（多路召回）。 */
    private ToolResult searchWorldKnowledge(Long projectId, String query) {
        if (query == null || query.isBlank()) {
            return fail("search_world_knowledge", "检索关键词不能为空");
        }
        int topK = Math.max(1, properties.getWorldKnowledgeTopK());
        List<KnowledgeHit> hits = knowledgeRetrievalService.search(
                currentUserProvider.currentUserId(), projectId, null, query, topK);
        if (hits.isEmpty()) {
            return new ToolResult("search_world_knowledge", "知识库无命中",
                    "世界知识库未检索到与「" + query + "」相关的内容。",
                    sources("KnowledgeRetrievalService·世界知识库", null), null);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("世界知识命中（").append(hits.size()).append(" 条）：\n");
        for (KnowledgeHit h : hits) {
            sb.append("- 《").append(nullTo(h.title())).append("》：");
            String text = h.text() == null ? "" : h.text();
            sb.append(text.length() <= 300 ? text : text.substring(0, 300) + "…").append('\n');
        }
        List<AssistantSourceVO> src = hits.stream()
                .map(h -> new AssistantSourceVO("世界知识库·" + nullTo(h.title()), null))
                .toList();
        return new ToolResult("search_world_knowledge", "知识库命中 " + hits.size() + " 条", sb.toString(), src, null);
    }

    /** search_project_docs：项目文档检索（向量 + 关键词降级）。 */
    private ToolResult searchProjectDocs(Long projectId, String query) {
        if (query == null || query.isBlank()) {
            return fail("search_project_docs", "检索关键词不能为空");
        }
        List<AssistantDocsRagService.Hit> hits = docsRagService.search(projectId, query);
        if (hits.isEmpty()) {
            return new ToolResult("search_project_docs", "项目文档无命中",
                    "项目文档未检索到与「" + query + "」相关的内容（docs 索引未启用或内容不存在）。",
                    sources("docs/ 项目文档", null), null);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("项目文档命中（").append(hits.size()).append(" 条）：\n");
        for (AssistantDocsRagService.Hit h : hits) {
            sb.append("- ").append(h.source().detail()).append("：");
            String text = h.text() == null ? "" : h.text();
            sb.append(text.length() <= 400 ? text : text.substring(0, 400) + "…").append('\n');
        }
        List<AssistantSourceVO> src = hits.stream().map(AssistantDocsRagService.Hit::source).toList();
        return new ToolResult("search_project_docs", "项目文档命中 " + hits.size() + " 条", sb.toString(), src, null);
    }

    // ==================== 实体解析 / 工具函数 ====================

    /** trace_chain：AI 决策链路查询/解释（能力域 E，M3）。 */
    private ToolResult traceChain(Long projectId, Map<String, Object> args) {
        AssistantObservationService svc = observationService;
        if (svc == null) {
            return fail("trace_chain", "运行观测能力未装配");
        }
        String traceId = arg(args, "traceId");
        String charParam = arg(args, "character");
        Long charId = null;
        String charName = null;
        if (traceId == null && charParam != null && !charParam.isBlank()) {
            charId = resolveCharacterId(projectId, charParam);
            if (charId == null) {
                return fail("trace_chain", "未找到角色：" + nullTo(charParam) + "（请确认角色名或 npc:id）");
            }
            ActorCharacter ch = characterRepository.findByIdAndProjectId(charId, projectId).orElse(null);
            charName = ch == null ? null : ch.getName();
        }
        int limit = intArg(args, "limit", 3);
        AssistantObservationService.ObsResult r = svc.traceChain(projectId, traceId, charId, charName, limit);
        return new ToolResult("trace_chain", r.summary(), r.detail(), r.sources(), r.card());
    }

    /** ai_call_stats：AI 调用统计（能力域 E，M3）。 */
    private ToolResult aiCallStats(Long projectId, Map<String, Object> args) {
        AssistantObservationService svc = observationService;
        if (svc == null) {
            return fail("ai_call_stats", "运行观测能力未装配");
        }
        int days = intArg(args, "days", 1);
        AssistantObservationService.ObsResult r = svc.aiCallStats(projectId, days);
        return new ToolResult("ai_call_stats", r.summary(), r.detail(), r.sources(), r.card());
    }

    /** world_intervene：世界干预（能力域 F，M4；按确认粒度待确认或立即执行）。 */
    private ToolResult worldIntervene(Long projectId, Map<String, Object> args) {
        AssistantInterventionService svc = interventionService;
        if (svc == null) {
            return fail("world_intervene", "世界干预能力未装配");
        }
        String sessionIdStr = arg(args, "_sessionId");
        Long sessionId = parseLong(sessionIdStr);
        if (sessionId == null) {
            return fail("world_intervene", "缺少会话上下文（内部错误）");
        }
        String op = arg(args, "operation");
        Map<String, Object> params = nestedMap(args, "params");
        AssistantInterventionService.PlanResult r = svc.plan(projectId, sessionId,
                currentUserProvider.currentUserId(), op, params);
        if (r.needsConfirm()) {
            List<AssistantCardVO.Field> fields = new ArrayList<>();
            fields.add(f("操作类型", opLabel(r.operation())));
            fields.add(f("操作详情", r.detail()));
            fields.add(f("影响范围", r.impact()));
            fields.add(f("风险等级", riskLabel(r.risk())));
            return new ToolResult("world_intervene", "待确认：" + r.detail(),
                    "需要用户确认的干预计划：操作=" + opLabel(r.operation()) + "，详情=" + r.detail()
                            + "，影响=" + r.impact() + "，风险=" + riskLabel(r.risk()) + "。等待用户确认后执行。",
                    sources("沙盘干预（确认后经沙盘既有端点）", null),
                    new AssistantCardVO("confirm", "干预确认 · " + opLabel(r.operation()), fields));
        }
        String summary = "executed".equals(r.status())
                ? "已执行：" + r.detail() : "干预未执行：" + nullTo(r.resultText());
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        fields.add(f("操作类型", opLabel(r.operation())));
        fields.add(f("操作详情", r.detail()));
        fields.add(f("影响范围", r.impact()));
        fields.add(f("风险等级", riskLabel(r.risk())));
        fields.add(f("结果", nullTo(r.resultText())));
        return new ToolResult("world_intervene", summary, summary,
                sources("沙盘干预（既有端点）", null),
                new AssistantCardVO("result", "干预结果 · " + opLabel(r.operation()), fields));
    }

    /** 操作类型中文标签（供 ChatService confirm SSE 载荷复用）。 */
    public static String opLabel(String op) {
        return switch (op == null ? "" : op) {
            case "talk" -> "插话";
            case "action" -> "触发行动";
            case "event" -> "注入事件";
            case "clock" -> "控制时间";
            case "weather" -> "切换天气";
            default -> nullTo(op);
        };
    }

    /** 风险等级中文标签（供 ChatService confirm SSE 载荷复用）。 */
    public static String riskLabel(String risk) {
        return "high".equals(risk) ? "高" : "低";
    }

    /** 参数读取（嵌套 Map）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> args, String key) {
        if (args == null) {
            return Map.of();
        }
        Object v = args.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /** 字符串 → Long（非法返回 null）。 */
    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 角色名或 npc:id → 角色 ID（按名单精确/包含匹配；找不到返回 null）。 */
    private Long resolveCharacterId(Long projectId, String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        String p = param.trim();
        if (NpcRefs.isNpcRef(p)) {
            Long id = NpcRefs.parseLenient(p);
            if (id != null) {
                return characterRepository.findByIdAndProjectId(id, projectId).isPresent() ? id : null;
            }
        }
        List<WorldGraphVO.CharacterNode> list = worldGraph.characters(projectId, null, null);
        String norm = p.replaceAll("\\s+", "");
        // 精确
        for (WorldGraphVO.CharacterNode c : list) {
            if (c.name() != null && c.name().replaceAll("\\s+", "").equals(norm)) {
                return c.id();
            }
        }
        // 包含
        for (WorldGraphVO.CharacterNode c : list) {
            if (c.name() != null && c.name().replaceAll("\\s+", "").contains(norm)) {
                return c.id();
            }
        }
        return null;
    }

    /** 地点名 → 地点 ID（按名单精确/包含匹配；找不到返回 null）。 */
    private Long resolveLocationId(Long projectId, String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        String p = param.trim();
        List<WorldGraphVO.LocationNode> list = worldGraph.locations(projectId);
        String norm = p.replaceAll("\\s+", "");
        for (WorldGraphVO.LocationNode l : list) {
            if (l.name() != null && l.name().replaceAll("\\s+", "").equals(norm)) {
                return l.id();
            }
        }
        for (WorldGraphVO.LocationNode l : list) {
            if (l.name() != null && l.name().replaceAll("\\s+", "").contains(norm)) {
                return l.id();
            }
        }
        return null;
    }

    /** 游戏时间文本（含历法；时钟缺失返回「时间未知」）。 */
    private String timeText(Long projectId) {
        try {
            var vo = worldClockService.getClock(projectId, currentUserProvider.currentUserId());
            if (vo == null || vo.getGameTimeText() == null) {
                return "时间未知";
            }
            return vo.getGameTimeText();
        } catch (Exception e) {
            return "时间未知";
        }
    }

    /** 参数读取（String）。 */
    private static String arg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 参数读取（int，缺省 defaultVal）。 */
    private static int intArg(Map<String, Object> args, String key, int defaultVal) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** 失败结果（detail 供模型说明；无来源无卡片）。 */
    private static ToolResult fail(String name, String message) {
        return new ToolResult(name, "查询失败", message, List.of(), null);
    }

    /** 来源列表。 */
    private static List<AssistantSourceVO> sources(String source, String detail) {
        List<AssistantSourceVO> list = new ArrayList<>();
        list.add(new AssistantSourceVO(source, detail));
        return list;
    }

    /** 卡片字段。 */
    private static AssistantCardVO.Field f(String label, String value) {
        return new AssistantCardVO.Field(label, value == null ? "—" : value);
    }

    /** null 安全字符串。 */
    private static String nullTo(Object s) {
        return s == null ? "—" : String.valueOf(s);
    }

    /** 数值格式化（null→—，浮点保留两位）。 */
    private static String fmt(Number n) {
        if (n == null) {
            return "—";
        }
        if (n instanceof Double d || n instanceof Float) {
            return String.format(Locale.ROOT, "%.2f", n.doubleValue());
        }
        return String.valueOf(n);
    }

    /**
     * 工具执行结果。
     *
     * @param name    工具名
     * @param summary 一句话摘要（SSE tool 事件展示）
     * @param detail  详细结果文本（注入模型上下文）
     * @param sources 来源标注
     * @param card    数据卡片（可空）
     */
    public record ToolResult(String name, String summary, String detail,
                             List<AssistantSourceVO> sources, AssistantCardVO card) {
    }

    /**
     * 功能导览条目。
     *
     * @param name  功能名
     * @param entry 入口位置
     * @param desc  一句话说明
     */
    private record GuideEntry(String name, String entry, String desc) {
    }

    /** 内置功能盘点（能力域 A 知识源；与前端实际功能对齐，不编造未交付功能）。 */
    private static List<GuideEntry> guideCatalog() {
        List<GuideEntry> list = new ArrayList<>();
        list.add(new GuideEntry("新首页 · 世界总览", "首页（/）", "2D 世界地图 + 角色活动 + 世界演化 + 干预抽屉"));
        list.add(new GuideEntry("世界生成", "新建项目（/projects/new）", "从世界观设定生成世界：时间线/社会/历史/角色清单等"));
        list.add(new GuideEntry("世界详情", "世界详情（/world/:id/world）", "查看与编辑世界观设定、时间线、地点与角色清单"));
        list.add(new GuideEntry("世界记忆 / 编年史", "世界记忆（/world/:id/world-memory）", "编年史、宏观演化、世界知识沉淀与检索"));
        list.add(new GuideEntry("角色管理", "角色（/world/:id/character）", "角色档案（11 维 persona）、状态、记忆、关系"));
        list.add(new GuideEntry("关系拓扑", "关系拓扑（/world/:id/topology）", "角色间关系网络（好感/信任/类型）可视化"));
        list.add(new GuideEntry("对话", "对话（/world/:id/chat）", "玩家与角色单聊/群聊，流式 AI 回复"));
        list.add(new GuideEntry("对谈", "对谈（/world/:id/dialogue）", "静止世界聊天模式：世界冻结于快照时刻，与角色对谈（零世界推进）"));
        list.add(new GuideEntry("行动时间线", "时间线（/world/:id/timeline）", "角色行动与事件的时间流回放"));
        list.add(new GuideEntry("人群管理", "人群（/world/:id/crowd）", "普通居民批量生成/作息调度/字段字典"));
        list.add(new GuideEntry("知识库", "知识（/world/:id/knowledge）", "世界知识（设定/人物知识卡/经历）向量化检索与生命周期"));
        list.add(new GuideEntry("演化", "演化（/world/:id/evolve）", "阶段机/人格演化/婚育谱系/死亡叙事等长期演化"));
        list.add(new GuideEntry("世界图谱", "图谱（/world/:id/graph）", "六维单域 + 角色/地点/势力视角 + 全局概览"));
        list.add(new GuideEntry("沙盘", "沙盘（/world/:id/sandbox）", "3D 世界场景、实时决策开关、干预（插话/行动/事件/时间/天气）"));
        list.add(new GuideEntry("设置", "设置（/world/:id/settings）", "模型 API、提示词模板、世界参数、外观、助手设置"));
        list.add(new GuideEntry("新手教程", "教程（/tutorial）", "项目能力导览与操作引导"));
        list.add(new GuideEntry("调试前端", "调试（/debug/）", "AI 调用 trace、运行状态、用量统计（开发者工具）"));
        list.add(new GuideEntry("项目级 AI 助手", "右下角浮窗（Ctrl+Shift+A）", "讲解项目/世界/角色/宏观，检索知识文档，双模式回答"));
        return list;
    }
}
