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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AgentRuntime 概览聚合服务（阶段 C：调试前端 AgentRuntime 概览视图）。
 * <p>职责：只读聚合「AgentRuntime 运行概览」——各角色 brainProfile（脑力档/模型档/决策频率/
 * 组件开关/记忆与 AI 预算）+ 决策频率与预算使用（近 30 天 AI 调用/决策调用/决策链路/Token 用量）+
 * 六组件级状态（thinking/action/roleplay/memory/runtime/learning + perception/brain 扩展，
 * 按 trace 场景与环节类型聚合 AI 调用与非 AI 环节计数）+ 引擎运行汇总 + 项目摘要，
 * 供调试前端 `/runtime` 页一屏看全六组件运行状态（路线图阶段 C 贯穿项）。</p>
 * <p>数据源：actor_agent_profile（brainProfile 权威配置）+ actor_ai_call_log（决策频率/预算使用/组件
 * 活动——scene 与 stepType 口径：决策 AI 调用 scene=action、对话/演化 scene=dialog/world_event、
 * 记忆 scene=memory、跨日润色/人群 scene=world_event_polish/crowd_ai，非 AI 环节经 recordStep
 * 以 stepType=perception/action/event 记行）+ actor_world_engine / actor_world_clock（引擎状态）。
 * 全部只读，不引入任何写操作。</p>
 */
@Service
public class DebugAgentOverviewService {

    /** 六组件元数据（阶段 收编口径：thinking/action/roleplay/memory/runtime/learning） */
    private static final List<Map<String, Object>> CORE_COMPONENTS = List.of(
            component("thinking", "思考", true, List.of("action"),
                    "决策 AI 调用（DecisionService，scene=action）"),
            component("action", "行动", true, List.of(),
                    "行动执行环节（AgentActionService，stepType=action/event 非 AI 步骤）"),
            component("roleplay", "扮演", true, List.of("dialog", "world_event"),
                    "对话/群聊/演化编排 AI（RoleplayService）"),
            component("memory", "记忆", true, List.of("memory"),
                    "记忆抽取/沉淀 AI（AgentMemoryService，scene=memory）"),
            component("runtime", "运行时", true, List.of("world_event_polish", "crowd_ai"),
                    "跨日事件润色 / 人群 AI 调度（WorldEngine 线）"),
            component("learning", "学习", true, List.of(),
                    "反思/关系/生态学习（LearningService 骨架，实现留阶段 D）"));

    /** 扩展组件（非六组件核心，辅助观察） */
    private static final List<Map<String, Object>> EXTRA_COMPONENTS = List.of(
            component("perception", "感知", false, List.of(),
                    "感知组装环节（PerceptionService，stepType=perception 非 AI 步骤）"),
            component("brain", "脑力配置", false, List.of(),
                    "BrainProfile（actor_agent_profile，无 AI 调用）"));

    private final ActorAgentProfileRepository agentProfileRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorWorldEngineRepository engineRepository;

    /** AgentRuntime 每决策循环 AI 预算缺省值（holzyn.actor.agent.budget-per-tick） */
    private final int defaultBudgetPerTick;

    /** 世界模拟总开关（holzyn.actor.world.sim-enabled） */
    private final boolean worldSimEnabled;

    /** 世界引擎扫描周期（真实毫秒，holzyn.actor.world.scan-interval-ms） */
    private final long scanIntervalMs;

    /** 概览聚合窗口（真实天数，默认 30） */
    private final int days;

    public DebugAgentOverviewService(ActorAgentProfileRepository agentProfileRepository,
                                     ActorCharacterRepository characterRepository,
                                     ActorCharacterRepository ordinaryNpcRepository,
                                     ActorAiCallLogRepository aiCallLogRepository,
                                     ActorProjectRepository projectRepository,
                                     ActorWorldClockRepository worldClockRepository,
                                     ActorWorldEngineRepository engineRepository,
                                     @Value("${holzyn.actor.agent.budget-per-tick:5}") int defaultBudgetPerTick,
                                     @Value("${holzyn.actor.world.sim-enabled:true}") boolean worldSimEnabled,
                                     @Value("${holzyn.actor.world.scan-interval-ms:180000}") long scanIntervalMs,
                                     @Value("${holzyn.actor.debug.agent-overview.days:30}") int days) {
        this.agentProfileRepository = agentProfileRepository;
        this.characterRepository = characterRepository;
        this.ordinaryNpcRepository = ordinaryNpcRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.projectRepository = projectRepository;
        this.worldClockRepository = worldClockRepository;
        this.engineRepository = engineRepository;
        this.defaultBudgetPerTick = defaultBudgetPerTick;
        this.worldSimEnabled = worldSimEnabled;
        this.scanIntervalMs = scanIntervalMs;
        this.days = Math.max(1, Math.min(days, 365));
    }

    /**
     * 构建 AgentRuntime 运行概览。
     *
     * @param projectId 项目过滤（null=全部项目）
     *
     * @return 概览 Map
     */
    public Map<String, Object> runtimeOverview(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", LocalDateTime.now().toString());
        out.put("days", days);
        out.put("defaultBudgetPerTick", defaultBudgetPerTick);

        // ① 项目摘要（含世界引擎预算/时钟推进位置）
        List<Map<String, Object>> projects = new ArrayList<>();
        for (ActorProject p : projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0)) {
            if (projectId != null && !p.getId().equals(projectId)) {
                continue;
            }
            projects.add(projectSummary(p));
        }
        out.put("projects", projects);

        // ② 聚合窗口内的 AI 调用（一次拉取，内存聚合——本地 H2 单用户规模可控）
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        List<ActorAiCallLog> calls = projectId == null
                ? aiCallLogRepository.findByCreatedAtGreaterThanEqual(from)
                : aiCallLogRepository.findByProjectIdAndCreatedAtGreaterThanEqual(projectId, from);

        // ③ 各角色 brainProfile + 决策频率/预算使用
        out.put("agents", agentRows(projectId, calls));

        // ④ 六组件级状态（含扩展组件）
        out.put("components", componentRows(calls));

        // ⑤ 世界引擎运行汇总
        out.put("engine", engineSummary());

        return out;
    }

    /**
     * 单个项目摘要（AgentRuntime 概览用）：id/名称/时钟推进/引擎预算/角色与居民数。
     *
     * @param p 项目实体
     *
     * @return 摘要 Map
     */
    private Map<String, Object> projectSummary(ActorProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        worldClockRepository.findByProjectId(p.getId()).ifPresent(clock -> {
            m.put("worldRunning", Integer.valueOf(1).equals(clock.getWorldRunning()) ? 1 : 0);
            m.put("paused", Integer.valueOf(1).equals(clock.getPaused()) ? 1 : 0);
            m.put("lastGameHour", clock.getLastGameHour());
            m.put("lastEngineRunAt", clock.getLastEngineRunAt() == null ? null : clock.getLastEngineRunAt().toString());
        });
        engineRepository.findByProjectId(p.getId()).ifPresent(engine ->
                m.put("budgetPerTick", engine.getBudgetPerTick()));
        m.putIfAbsent("budgetPerTick", defaultBudgetPerTick);
        m.put("characterCount", characterRepository.findByProjectIdAndDeletedOrderByIdAsc(p.getId(), 0).size());
        // 统一主表：普通居民数按 actor_profile.type=common 统计
        // （旧的 countByProjectId 已随统一仓库合并移除，普通型不再是一张独立表）
        m.put("npcCount", ordinaryNpcRepository.countByProjectIdAndTypeAndDeleted(p.getId(), NpcRefs.TYPE_COMMON, 0));
        return m;
    }

    /**
     * 各角色 AgentRuntime 概览行：brainProfile 配置 + 近窗口决策频率/预算使用统计。
     * <p>统计口径（actor_ai_call_log）：aiCalls30d=该角色全部 AI 调用；
     * decisionCalls30d=决策 AI 调用（scene=action 且 stepType=ai_call）；
     * traces30d=决策调用的去重决策链路（distinct trace_id，无 trace_id 时按调用计 1）；
     * tokenIn30d/tokenOut30d=近窗口 Token 用量；lastDecisionAt=最近一次决策调用时刻。</p>
     *
     * @param projectId 项目过滤（null=全部）
     *
     * @param calls 聚合窗口内调用（已按项目过滤）
     *
     * @return 角色行列表
     */
    private List<Map<String, Object>> agentRows(Long projectId, List<ActorAiCallLog> calls) {
        // 名字/归属解析映射（跳过已删除，跨项目一次性加载）。
        // 统一主表：actor_agent_profile.agentRefId 就是 actor_profile.id，
        // 特殊型与普通型同表（type 只作路由属性）——一个 id→实体 索引即可覆盖两类，
        // 不再需要"角色表 + 普通居民表"两次查询。
        Map<Long, ActorCharacter> owners = characterRepository.findByDeletedOrderByIdAsc(0).stream()
                .collect(Collectors.toMap(ActorCharacter::getId, Function.identity(), (a, b) -> a));

        // 按角色聚合窗口内调用
        Map<Long, AgentStat> stats = new LinkedHashMap<>();
        for (ActorAiCallLog call : calls) {
            if (call.getCharacterId() == null) {
                continue;
            }
            AgentStat st = stats.computeIfAbsent(call.getCharacterId(), k -> new AgentStat());
            if (isAiCall(call)) {
                st.aiCalls++;
                st.tokenIn += nvl(call.getTokenIn());
                st.tokenOut += nvl(call.getTokenOut());
                if ("action".equals(call.getScene())) {
                    st.decisionCalls++;
                    st.traceIds.add(call.getTraceId());
                    if (call.getCreatedAt() != null
                            && (st.lastDecisionAt == null || call.getCreatedAt().isAfter(st.lastDecisionAt))) {
                        st.lastDecisionAt = call.getCreatedAt();
                    }
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorAgentProfile profile : agentProfileRepository.findAll()) {
            Long refId = profile.getAgentRefId();
            String type = profile.getAgentType();
            // 统一主表：不区分 agentType，都从同一张 actor_profile 解析（type 仅用于展示 agentType）
            ActorCharacter owner = owners.get(refId);
            if (owner == null) {
                continue; // 角色已删除/不存在，不展示
            }
            Long ownerProjectId = owner.getProjectId();
            String name = owner.getName();
            if (projectId != null && !projectId.equals(ownerProjectId)) {
                continue;
            }

            AgentStat st = stats.getOrDefault(refId, new AgentStat());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentType", type);
            m.put("agentRefId", refId);
            m.put("name", name);
            m.put("projectId", ownerProjectId);
            m.put("brainLevel", profile.getBrainLevel());
            m.put("modelTier", profile.getModelTier());
            m.put("decisionFrequency", profile.getDecisionFrequency());
            m.put("thinkingEnabled", profile.getThinkingEnabled());
            m.put("planningEnabled", profile.getPlanningEnabled());
            m.put("reflectionEnabled", profile.getReflectionEnabled());
            m.put("memoryBudget", profile.getMemoryBudget());
            m.put("aiBudgetPerTick", profile.getAiBudgetPerTick());
            m.put("updatedAt", profile.getUpdatedAt() == null ? null : profile.getUpdatedAt().toString());
            // 决策频率/预算使用（近窗口实测）
            m.put("aiCalls30d", st.aiCalls);
            m.put("decisionCalls30d", st.decisionCalls);
            m.put("traces30d", st.traceIds.size());
            m.put("tokenIn30d", st.tokenIn);
            m.put("tokenOut30d", st.tokenOut);
            m.put("lastDecisionAt", st.lastDecisionAt == null ? null : st.lastDecisionAt.toString());
            rows.add(m);
        }
        return rows;
    }

    /**
     * 六组件级状态行（含扩展组件）：按 trace 场景/环节类型聚合窗口内 AI 调用与非 AI 环节。
     * <p>口径：thinking/roleplay/memory/runtime 按 AI 调用 scene 归属（scene 见元数据）；
     * action 按非 AI 环节 stepType=action/event（scene=action）；perception 按非 AI 环节
     * stepType=perception（任意 scene）；learning/brain 无调用（预留/配置）。未映射场景的
     * AI 调用（card_gen/embedding/relation_gen 等非 AgentRuntime 业务）归入「其他」行。</p>
     *
     * @param calls 聚合窗口内调用
     *
     * @return 组件行列表
     */
    private List<Map<String, Object>> componentRows(List<ActorAiCallLog> calls) {
        Map<String, ComponentStat> comps = new LinkedHashMap<>();
        for (Map<String, Object> def : CORE_COMPONENTS) {
            comps.put((String) def.get("key"), new ComponentStat());
        }
        for (Map<String, Object> def : EXTRA_COMPONENTS) {
            comps.put((String) def.get("key"), new ComponentStat());
        }
        ComponentStat other = new ComponentStat();

        for (ActorAiCallLog call : calls) {
            ComponentStat target = resolveComponent(call, comps, other);
            if (isAiCall(call)) {
                target.aiCalls++;
                target.tokenIn += nvl(call.getTokenIn());
                target.tokenOut += nvl(call.getTokenOut());
            } else {
                target.steps++;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> def : CORE_COMPONENTS) {
            rows.add(componentRow(def, comps.get(def.get("key"))));
        }
        for (Map<String, Object> def : EXTRA_COMPONENTS) {
            rows.add(componentRow(def, comps.get(def.get("key"))));
        }
        // 未映射场景（项目级操作等）
        Map<String, Object> otherRow = new LinkedHashMap<>();
        otherRow.put("key", "other");
        otherRow.put("name", "其他（项目级操作）");
        otherRow.put("core", false);
        otherRow.put("scenes", List.of("card_gen/embedding/relation_gen/import 等"));
        otherRow.put("desc", "未归属六组件的 AI 调用（角色卡/向量化/关系生成/导入解析等）");
        otherRow.put("aiCalls30d", other.aiCalls);
        otherRow.put("steps30d", other.steps);
        otherRow.put("tokenIn30d", other.tokenIn);
        otherRow.put("tokenOut30d", other.tokenOut);
        rows.add(otherRow);
        return rows;
    }

    /**
     * 解析一条 trace 归属的组件（AI 调用按 scene；非 AI 环节按 stepType）。
     *
     * @param call 调用行
     *
     * @param comps 组件统计容器
     *
     * @param other 未映射场景容器（项目级操作等）
     *
     * @return 归属组件
     */
    private ComponentStat resolveComponent(ActorAiCallLog call, Map<String, ComponentStat> comps,
                                           ComponentStat other) {
        if (isAiCall(call)) {
            String scene = call.getScene();
            return switch (scene == null ? "" : scene) {
                case "action" -> comps.get("thinking");          // 决策 AI 调用
                case "dialog", "world_event" -> comps.get("roleplay");
                case "memory" -> comps.get("memory");
                case "world_event_polish", "crowd_ai" -> comps.get("runtime");
                default -> other;
            };
        }
        String stepType = call.getStepType() == null ? "ai_call" : call.getStepType();
        return switch (stepType) {
            case "perception" -> comps.get("perception");
            case "action", "event" -> comps.get("action");
            case "thinking" -> comps.get("thinking");         // AgentRuntime 思考环节（非 AI）
            case "memory" -> comps.get("memory");             // AgentRuntime/AgentMemory 记忆环节（非 AI）
            default -> other;
        };
    }

    /**
     * 组装单个组件行。
     *
     * @param def 组件元数据
     *
     * @param stat 组件统计
     *
     * @return 组件行 Map
     */
    private Map<String, Object> componentRow(Map<String, Object> def, ComponentStat stat) {
        Map<String, Object> m = new LinkedHashMap<>(def);
        m.put("aiCalls30d", stat.aiCalls);
        m.put("steps30d", stat.steps);
        m.put("tokenIn30d", stat.tokenIn);
        m.put("tokenOut30d", stat.tokenOut);
        return m;
    }

    /**
     * 世界引擎运行汇总（与总览页世界引擎卡同源）：扫描间隔/持续运行/暂停/关闭/配置行数。
     *
     * @return 引擎状态 Map
     */
    private Map<String, Object> engineSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scanIntervalMs", scanIntervalMs);
        m.put("simEnabled", worldSimEnabled);
        int running = 0, paused = 0, stopped = 0, engines = 0;
        for (ActorProject p : projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0)) {
            Optional<ActorWorldClock> c = worldClockRepository.findByProjectId(p.getId());
            if (c.isEmpty()) {
                stopped++;
                continue;
            }
            if (Integer.valueOf(1).equals(c.get().getPaused())) {
                paused++;
            } else if (Integer.valueOf(1).equals(c.get().getWorldRunning())) {
                running++;
            } else {
                stopped++;
            }
            if (engineRepository.findByProjectId(p.getId()).isPresent()) {
                engines++;
            }
        }
        m.put("runningCount", running);
        m.put("pausedCount", paused);
        m.put("stoppedCount", stopped);
        m.put("engineConfigCount", engines);
        return m;
    }

    /**
     * 是否为 AI 调用行（stepType 缺省=ai_call；recordStep 的非 AI 环节有明确 stepType）。
     *
     * @param call 调用行
     *
     * @return true=AI 调用
     */
    private static boolean isAiCall(ActorAiCallLog call) {
        String stepType = call.getStepType();
        return stepType == null || stepType.isBlank() || "ai_call".equals(stepType);
    }

    /**
     * null 安全取值。
     *
     * @param v 值
     *
     * @return 非 null 值
     */
    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 构造组件元数据。
     *
     * @param key 组件键
     *
     * @param name 组件中文名
     *
     * @param core 是否六组件核心
     *
     * @param scenes 归属的 AI 调用场景（空=无 AI 调用，按非 AI 环节归属）
     *
     * @param desc 组件说明
     *
     * @return 元数据 Map
     */
    private static Map<String, Object> component(String key, String name, boolean core,
                                                 List<String> scenes, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("core", core);
        m.put("scenes", scenes);
        m.put("desc", desc);
        return m;
    }

    /**
     * 单角色近窗口统计容器。
     */
    private static final class AgentStat {
        int aiCalls;
        int decisionCalls;
        int tokenIn;
        int tokenOut;
        LocalDateTime lastDecisionAt;
        final java.util.Set<String> traceIds = new java.util.LinkedHashSet<>();
    }

    /**
     * 单组件近窗口统计容器。
     */
    private static final class ComponentStat {
        int aiCalls;
        int steps;
        int tokenIn;
        int tokenOut;
    }
}
