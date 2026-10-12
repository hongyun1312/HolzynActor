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

import com.holzyn.actor.domain.action.service.ActionSseHub;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 调试总览服务（调试前端总览页）。
 * <p>职责：聚合后端健康/版本/JVM 内存/线程/活跃 SSE 数 + 每个项目的摘要
 * （角色数/普通 NPC 数/对话数/世界时钟[时刻/速率/暂停]/世界模拟开关/人群定时开关/最近 AI 调用），
 * 供总览页一屏看全程序运行状态。全部只读，不引入任何写操作。</p>
 */
@Service
public class DebugOverviewService {

    /** 应用名 */
    private final String appName;

    /** 世界模拟总开关配置（application.yml holzyn.actor.world.sim-enabled） */
    private final boolean worldSimEnabled;

    /** 世界引擎扫描周期（真实毫秒，application.yml holzyn.actor.world.scan-interval-ms） */
    private final long scanIntervalMs;

    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    /** 运行态访问器：普通居民 state/lastAction 已迁 actor_state（主表不再承载） */
    private final NpcActivityStateService npcActivityStateService;
    private final ActorConversationRepository conversationRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository;
    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorWorldEngineRepository engineRepository;
    private final ActorEventRepository eventRepository;
    private final WorldClockService worldClockService;
    private final ActionSseHub actionSseHub;
    private final DebugLogService debugLogService;
    private final DebugEventHub debugEventHub;
    private final DebugLogBuffer debugLogBuffer;
    private final AiCallTraceService aiCallTraceService;
    /** 世界沙盘 SSE 广播中心（S2 调试概览统计 activeSse 计入沙盘订阅） */
    private final com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub worldSandboxSseHub;

    public DebugOverviewService(@Value("${spring.application.name:holzyn-actor}") String appName,
                                @Value("${holzyn.actor.world.sim-enabled:true}") boolean worldSimEnabled,
                                @Value("${holzyn.actor.world.scan-interval-ms:180000}") long scanIntervalMs,
                                ActorProjectRepository projectRepository,
                                ActorCharacterRepository characterRepository,
                                ActorCharacterRepository ordinaryNpcRepository,
                                NpcActivityStateService npcActivityStateService,
                                ActorConversationRepository conversationRepository,
                                ActorWorldClockRepository worldClockRepository,
                                ActorCrowdRuntimeRepository crowdRuntimeRepository,
                                ActorAiCallLogRepository aiCallLogRepository,
                                ActorWorldEngineRepository engineRepository,
                                ActorEventRepository eventRepository,
                                WorldClockService worldClockService,
                                ActionSseHub actionSseHub,
                                DebugLogService debugLogService,
                                DebugEventHub debugEventHub,
                                DebugLogBuffer debugLogBuffer,
                                AiCallTraceService aiCallTraceService,
                                com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub worldSandboxSseHub) {
        this.appName = appName;
        this.worldSimEnabled = worldSimEnabled;
        this.scanIntervalMs = scanIntervalMs;
        this.projectRepository = projectRepository;
        this.characterRepository = characterRepository;
        this.ordinaryNpcRepository = ordinaryNpcRepository;
        this.npcActivityStateService = npcActivityStateService;
        this.conversationRepository = conversationRepository;
        this.worldClockRepository = worldClockRepository;
        this.crowdRuntimeRepository = crowdRuntimeRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.engineRepository = engineRepository;
        this.eventRepository = eventRepository;
        this.worldClockService = worldClockService;
        this.actionSseHub = actionSseHub;
        this.debugLogService = debugLogService;
        this.debugEventHub = debugEventHub;
        this.debugLogBuffer = debugLogBuffer;
        this.aiCallTraceService = aiCallTraceService;
        this.worldSandboxSseHub = worldSandboxSseHub;
    }

    /**
     * 构建调试总览（后端状态 + 项目摘要列表 + 世界引擎状态）。
     *
     * @return 总览 Map
     */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", backendStatus());
        out.put("worldEngine", worldEngineStatus());
        out.put("projects", projectSummaries());
        out.put("lastAiCall", lastAiCall(null));
        return out;
    }

    /**
     * 世界引擎运行状态汇总（B3，调试前端总览状态卡）：
     * 扫描间隔 / 持续运行项目数 / 暂停数 / 关闭数 / 已创建引擎配置数。
     *
     * @return 状态 Map
     */
    private Map<String, Object> worldEngineStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scanIntervalMs", scanIntervalMs);
        m.put("simEnabled", worldSimEnabled);
        int running = 0, paused = 0, stopped = 0, engines = 0;
        for (ActorProject p : projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0)) {
            Optional<ActorWorldClock> c = worldClockRepository.findByProjectId(p.getId());
            if (c.isEmpty()) {
                stopped++; // 无时钟行=未初始化（不推进）
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
     * 后端运行状态（健康/版本/启动/JVM/线程/SSE）。
     *
     * @return 状态 Map
     */
    private Map<String, Object> backendStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
        long startTime = mx.getStartTime();
        long uptimeMs = System.currentTimeMillis() - startTime;

        m.put("appName", appName);
        m.put("version", "1.0.0");
        m.put("status", "UP");
        m.put("time", LocalDateTime.now().toString());
        m.put("startTime", java.time.Instant.ofEpochMilli(startTime).toString());
        m.put("uptimeSeconds", uptimeMs / 1000);

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("usedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        jvm.put("totalMb", runtime.totalMemory() / 1024 / 1024);
        jvm.put("maxMb", runtime.maxMemory() / 1024 / 1024);
        jvm.put("processors", runtime.availableProcessors());
        m.put("jvm", jvm);
        m.put("threadCount", Thread.activeCount());

        // 活跃 SSE 连接数（行动时间线 + 调试日志流 + 调试事件流 + 世界沙盘流）
        m.put("activeSse", actionSseHub.size() + debugLogService.size() + debugEventHub.size()
                + (worldSandboxSseHub == null ? 0 : worldSandboxSseHub.size()));

        // 调试模块状态
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("aiLogEnabled", aiCallTraceService.isEnabled());
        debug.put("aiLogMaxPromptChars", aiCallTraceService.getMaxPromptChars());
        debug.put("logAppendEnabled", debugLogBuffer.isEnabled());
        debug.put("logBufferSize", debugLogBuffer.size());
        debug.put("logBufferCapacity", debugLogBuffer.capacity());
        m.put("debug", debug);
        return m;
    }

    /**
     * 项目摘要列表（全部用户项目，未删除）。
     *
     * @return 项目摘要列表
     */
    private List<Map<String, Object>> projectSummaries() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActorProject p : projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0)) {
            list.add(projectSummary(p));
        }
        return list;
    }

    /**
     * 单个项目摘要：角色/普通NPC/对话/时钟/开关/最近 AI 调用。
     *
     * @param p 项目实体
     *
     * @return 摘要 Map
     */
    private Map<String, Object> projectSummary(ActorProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("code", p.getCode());
        m.put("status", p.getStatus());
        m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        // 统一主表：角色数与普通居民数按 actor_profile.type 分列统计
        // （characterCount=特殊型角色；npcCount=普通型 NPC，与页面口径一致）
        m.put("characterCount", characterRepository.countByProjectIdAndTypeAndDeleted(p.getId(), NpcRefs.TYPE_SPECIAL, 0));
        m.put("npcCount", ordinaryNpcRepository.countByProjectIdAndTypeAndDeleted(p.getId(), NpcRefs.TYPE_COMMON, 0));
        m.put("conversationCount", conversationRepository
                .findByProjectIdAndUserIdOrderByUpdatedAtDesc(p.getId(), 1L).size());

        // 世界时钟（含实时游戏时刻/速率/暂停/持续运行/引擎回补基准）
        Optional<ActorWorldClock> clockOpt = worldClockRepository.findByProjectId(p.getId());
        if (clockOpt.isPresent()) {
            WorldClockVO vo = worldClockService.toVO(clockOpt.get());
            Map<String, Object> clock = new LinkedHashMap<>();
            clock.put("rate", vo.getRate());
            clock.put("paused", vo.getPaused());
            clock.put("worldRunning", vo.getWorldRunning());
            clock.put("lastEngineRunAt", vo.getLastEngineRunAt() == null ? null : vo.getLastEngineRunAt().toString());
            clock.put("lastGameHour", vo.getLastGameHour());
            clock.put("gameTimeText", vo.getGameTimeText());
            clock.put("day", vo.getDay());
            clock.put("hourOfDay", vo.getHourOfDay());
            clock.put("periodText", vo.getPeriodText());
            clock.put("lastSummary", vo.getLastSummary());
            m.put("clock", clock);
        } else {
            m.put("clock", null);
        }

        // B3 世界引擎配置：每 tick AI 预算 / 单次步长封顶（actor_world_engine，懒创建前为 null）
        engineRepository.findByProjectId(p.getId()).ifPresent(engine -> {
            Map<String, Object> engineInfo = new LinkedHashMap<>();
            engineInfo.put("budgetPerTick", engine.getBudgetPerTick());
            engineInfo.put("maxStepHours", engine.getMaxStepHours());
            engineInfo.put("lastAiBudgetResetAt",
                    engine.getLastAiBudgetResetAt() == null ? null : engine.getLastAiBudgetResetAt().toString());
            m.put("engine", engineInfo);
        });

        m.put("worldSimEnabled", worldSimEnabled);
        crowdRuntimeRepository.findByProjectId(p.getId())
                .ifPresent(rt -> m.put("crowdScheduled", Integer.valueOf(1).equals(rt.getEnabled())));
        m.putIfAbsent("crowdScheduled", false);

        m.put("lastAiCall", lastAiCall(p.getId()));
        return m;
    }

    /**
     * 最近一条 AI 调用摘要（全局或按项目）。
     *
     * @param projectId 项目 ID（null=全局）
     *
     * @return 摘要 Map（无记录返回 null）
     */
    private Map<String, Object> lastAiCall(Long projectId) {
        Optional<ActorAiCallLog> opt = projectId == null
                ? aiCallLogRepository.findTop1ByOrderByIdDesc()
                : aiCallLogRepository.findTop1ByProjectIdOrderByIdDesc(projectId);
        if (opt.isEmpty()) {
            return null;
        }
        ActorAiCallLog log = opt.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("scene", log.getScene());
        m.put("model", log.getModel());
        m.put("status", log.getStatus());
        m.put("tokenIn", log.getTokenIn());
        m.put("tokenOut", log.getTokenOut());
        m.put("durationMs", log.getDurationMs());
        m.put("time", log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        return m;
    }

    /**
     * 世界引擎快照（W-2 最小回滚手段前置，B3）：按项目 dump 世界关键表为 JSON——
     * 时钟（world_running/暂停/推进位置/回补基准）+ 引擎配置（预算/步长）+ 特殊角色状态 +
     * 普通居民状态 + 近期事件 + 近期 AI 调用。
     * <p>用途：无人值守自转后需要回滚/排查时，导出「某一时刻的世界状态」作为对照基准；
     * 纯读不写，调试前端「世界快照」入口消费。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 快照 Map（项目不存在返回仅含项目ID的空壳）
     */
    public Map<String, Object> engineSnapshot(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("snapshotAt", LocalDateTime.now().toString());
        projectRepository.findById(projectId).ifPresent(p -> {
            out.put("projectName", p.getName());
            out.put("projectCode", p.getCode());
            out.put("projectStatus", p.getStatus());
        });

        // ① 世界时钟（含持续运行/暂停/回补基准/逐时进度）
        worldClockRepository.findByProjectId(projectId).ifPresent(clock -> {
            WorldClockVO vo = worldClockService.toVO(clock);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rate", vo.getRate());
            c.put("paused", vo.getPaused());
            c.put("worldRunning", vo.getWorldRunning());
            c.put("worldStartAt", vo.getWorldStartAt() == null ? null : vo.getWorldStartAt().toString());
            c.put("worldStartGameHour", vo.getWorldStartGameHour());
            c.put("lastGameHour", vo.getLastGameHour());
            c.put("lastEngineRunAt", vo.getLastEngineRunAt() == null ? null : vo.getLastEngineRunAt().toString());
            c.put("lastSimTime", vo.getLastSimTime() == null ? null : vo.getLastSimTime().toString());
            c.put("gameTimeText", vo.getGameTimeText());
            c.put("lastSummary", vo.getLastSummary());
            out.put("clock", c);
        });

        // ② 引擎配置（预算/步长/预算重置时刻）
        engineRepository.findByProjectId(projectId).ifPresent(engine -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("budgetPerTick", engine.getBudgetPerTick());
            e.put("maxStepHours", engine.getMaxStepHours());
            e.put("lastAiBudgetResetAt",
                    engine.getLastAiBudgetResetAt() == null ? null : engine.getLastAiBudgetResetAt().toString());
            out.put("engine", e);
        });

        // ③ 特殊角色状态
        List<Map<String, Object>> characters = new ArrayList<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                projectId, NpcRefs.TYPE_SPECIAL, 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ch.getId());
            m.put("name", ch.getName());
            m.put("importance", ch.getImportance());
            m.put("location", ch.getLocation());
            m.put("currentActivity", ch.getCurrentActivity());
            m.put("roleTitle", ch.getTitle());
            characters.add(m);
        }
        out.put("characters", characters);

        // ④ 普通居民状态（：统一主表 type=common；state/lastAction 来自 actor_state 批量读取，
        // 主表实体已不再承载这两个运行态字段）
        Map<String, NpcActivityStateService.ActivityUpdate> acts = npcActivityStateService.activityByRef(projectId);
        List<Map<String, Object>> npcs = new ArrayList<>();
        ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON, 0)
                .forEach(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    NpcActivityStateService.ActivityUpdate u = acts.get(NpcRefs.of(n.getId()));
                    m.put("id", n.getId());
                    m.put("name", n.getName());
                    m.put("state", u == null ? NpcActivityStateService.DEFAULT_ACTIVITY : u.state());
                    m.put("lastAction", u == null ? null : u.lastAction());
                    m.put("location", n.getLocation());
                    npcs.add(m);
                });
        out.put("npcs", npcs);

        // ⑤ 近期事件（最近 30 条，倒序）
        List<Map<String, Object>> events = new ArrayList<>();
        eventRepository.findByProjectIdOrderByIdDesc(projectId).stream().limit(30).forEach(ev -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.getId());
            m.put("kind", ev.getKind());
            m.put("source", ev.getSource());
            m.put("visibility", ev.getVisibility());
            m.put("gameHour", ev.getGameHour());
            m.put("title", ev.getTitle());
            m.put("content", ev.getContent());
            m.put("sourceKey", ev.getSourceKey());
            events.add(m);
        });
        out.put("events", events);

        // ⑥ 近期 AI 调用（最近 10 条，倒序）
        List<Map<String, Object>> aiCalls = new ArrayList<>();
        aiCallLogRepository.findTop10ByProjectIdOrderByIdDesc(projectId).forEach(call -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", call.getId());
            m.put("scene", call.getScene());
            m.put("status", call.getStatus());
            m.put("tokens", (call.getTokenIn() == null ? 0 : call.getTokenIn())
                    + "/" + (call.getTokenOut() == null ? 0 : call.getTokenOut()));
            m.put("time", call.getCreatedAt() == null ? null : call.getCreatedAt().toString());
            aiCalls.add(m);
        });
        out.put("recentAiCalls", aiCalls);
        return out;
    }
}
