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

package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.route.SceneRouter;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.entity.ActorMovement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 途中可遇可交互服务（位置移动与地图生成系统 · M3 §4.6）——途中轻量交互统一入口。
 * <p>职责：移动者途中迎面遇到他角色时，<b>是否停下搭话由 AI 决定</b>——
 * 轻量即时决策 {@code scene=move_encounter}（SCHEMA_JSON，输出 {@code {greet:"一句话", stop:false}}）；
 * <b>冷却</b>（每角色每游戏小时 ≤N 次，{@code holzyn.actor.world-map.encounter-cooldown-game-hours}
 * 默认 1，防刷屏；进程内内存，对齐 {@code RealTimeDecisionService} 冷却模式）；
 * {@code stop=true} 才打断移动进入对话/群聊；<b>失败 fail-open</b>（不交互继续走）；普通 NPC 不主动
 * 发起 AI 交互。</p>
 * <p>⚠️ 本服务<b>含 AI 调用</b>——公开方法一律<b>不带 @Transactional</b>（AiCallOutsideTransactionTest
 * 守护）；AI 判定在虚拟线程异步执行（不阻塞 MovementService.tick 引擎热路径），落库分层由各仓库
 * 短事务承担。冷却存储进程内（ConcurrentHashMap），单实例运行约定下重启丢失可接受（零新表）。</p>
 * <p>触发接线：{@link MovementService#tick} 途中检测（移动者当前位置附近有其他角色，
 * {@code WorldMapQueryService.nearbyRefs} 命中）→ {@link #maybeEncounter}（异步判定）。
 * 交互结果：SSE {@code npc:encounter}（greet 文本）+ 事件落库（kind=move_encounter，§4.7）+ 可打断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoveEncounterService {

    /** AI 调用场景（trace/用量记账，对齐内置模板编码） */
    public static final String SCENE_MOVE_ENCOUNTER = "move_encounter";

    /** SSE 事件名：途中交互（npc:encounter） */
    public static final String EVENT_NPC_ENCOUNTER = "npc:encounter";

    /** 冷却 Map 条目上限（防长期运行内存膨胀） */
    private static final int COOLDOWN_CACHE_LIMIT = 5000;

    private final WorldMapProperties properties;
    private final MovementService movementService;
    private final WorldMapQueryService worldMapQueryService;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ObjectMapper objectMapper;

    /** 沙盘 SSE Hub（npc:encounter 广播；@Lazy 破环——Hub→沙盘服务→MovementService→本服务，null=跳过） */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldSandboxSseHub sandboxHub;

    /** 世界时钟（冷却时间基准=引擎推进位置；null=未装配冷却按 0 基准防同刻重复） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 统一 AI 上下文注入器（途中交互属「角色视角」判定——AI 以移动者视角判断是否停下搭话，
     * 前置世界观简报 + 移动者角色核心卡；null=未装配跳过注入，不影响原调用）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 场景路由（move_encounter 档位覆盖；null=未装配走默认档位） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SceneRouter sceneRouter;

    /** 异步虚拟线程执行器（AI 判定不阻塞引擎热路径） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 冷却 Map：projectId:npcRef → 上次触发游戏小时（每游戏小时 ≤N 次） */
    private final Map<String, Long> lastEncounterHour = new ConcurrentHashMap<>();

    // ==================== 触发入口 ====================

    /**
     * 途中遭遇触发（MovementService.tick 挂钩）：移动者当前位置附近有其他角色 → 异步 AI 判定
     * 是否停下搭话。冷却检查（每角色每游戏小时 ≤N 次）通过才触发；未装配/开关关/无附近角色/
     * 冷却期内 → 静默跳过（fail-open）。普通 NPC 不主动发起 AI 交互。
     *
     * @param projectId 项目 ID
     *
     * @param moverRef 移动者引用（统一 npc:{id}）
     *
     * @param sandboxX 移动者当前沙盘坐标 X
     *
     * @param sandboxZ 移动者当前沙盘坐标 Z
     *
     * @param gameSecond 当前游戏秒（引擎推进位置）
     */
    public void maybeEncounter(Long projectId, String moverRef, double sandboxX, double sandboxZ,
                               Long gameSecond) {
        if (projectId == null || moverRef == null || moverRef.isBlank()
                || !properties.isEncounterEnabled()) {
            return;
        }
        if (!com.holzyn.actor.common.NpcRefs.isNpcRef(moverRef)) {
            return;
        }
        try {
            // 附近角色（途中可见：移动者经过沿途区域 → 该区域在场角色可见；排除自身）
            List<String> nearby = worldMapQueryService.nearbyRefs(projectId, sandboxX, sandboxZ, moverRef, 30);
            if (nearby.isEmpty()) {
                return;
            }
            // 冷却检查（每角色每游戏小时 ≤N 次；同刻/冷却期内跳过）
            Long hour = currentGameHour(projectId, gameSecond);
            String key = cooldownKey(projectId, moverRef);
            Long last = lastEncounterHour.get(key);
            int cooldownHours = Math.max(1, properties.getEncounterCooldownGameHours());
            if (last != null && hour != null && hour - last < cooldownHours) {
                log.debug("[途中交互] 冷却期内跳过: project={} ref={} 距上次 {} 游戏小时",
                        projectId, moverRef, hour - last);
                return;
            }
            if (hour != null) {
                lastEncounterHour.put(key, hour);
                enforceCooldownLimit();
            }
            // 异步 AI 判定（虚拟线程；不阻塞引擎 tick）
            String otherRef = nearby.get(0);
            executor.execute(() -> evaluateAsync(projectId, moverRef, otherRef, gameSecond));
        } catch (Exception e) {
            log.warn("[途中交互] 触发失败（fail-open 继续走）: ref={} : {}", moverRef, e.getMessage());
        }
    }

    // ==================== 异步 AI 判定 ====================

    /**
     * 异步轻量 AI 判定（虚拟线程）：渲染 move_encounter 模板 → AI 输出 {greet, stop} →
     * 结果应用（SSE npc:encounter + 事件落库 kind=move_encounter + stop=true 打断移动）。
     * <p>⚠️ 全程无事务（AI 在事务外；落库走仓库短事务）；任何失败 → fail-open（不交互继续走）。</p>
     */
    private void evaluateAsync(Long projectId, String moverRef, String otherRef, Long gameSecond) {
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            ActorCharacter mover = characterRepository
                    .findById(com.holzyn.actor.common.NpcRefs.idOf(moverRef)).orElse(null);
            if (mover == null) {
                return;
            }
            ActorCharacter other = characterRepository
                    .findById(com.holzyn.actor.common.NpcRefs.idOf(otherRef)).orElse(null);
            String otherName = other == null ? otherRef : other.getName();
            String relationHint = relationHint(projectId, mover.getId(), other);
            String mySituation = mover.getCurrentActivity() == null || mover.getCurrentActivity().isBlank()
                    ? "正赶路" : mover.getCurrentActivity().trim();

            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("my_name", mover.getName());
            ph.put("my_situation", mySituation);
            ph.put("other_name", otherName == null ? otherRef : otherName);
            ph.put("relation_hint", relationHint);
            String prompt = promptTemplateService.render(userId, projectId,
                    PromptTemplateService.CODE_MOVE_ENCOUNTER, ph);
            // 上下文注入（2026-10-07）：途中交互属「角色视角」判定——AI 以移动者视角判断
            // 是否停下搭话，前置世界观简报 + 移动者角色核心卡（未装配/失败零阻断）
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.compose(projectId, moverRef);
            // 批3 决策与感知：途中交互补 段2D 当前世界动态 + 段2E 当前状态（移动者此刻
            // 时节天气与自身生理/情绪——赶时间的、身体不适的、饿着的决定自然不同；
            // 未装配/无数据 → 空串零阻断，行为与批3 前一致）
            String decisionPrefix = aiContextInjector == null ? ""
                    : aiContextInjector.decisionStatePrefix(projectId, moverRef, gameSecond);
            String finalPrompt;
            if (injected.isBlank() && decisionPrefix.isBlank()) {
                finalPrompt = prompt;
            } else {
                StringBuilder sb = new StringBuilder();
                if (!injected.isBlank()) {
                    sb.append(injected);
                }
                if (!decisionPrefix.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(decisionPrefix);
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(prompt);
                finalPrompt = sb.toString();
            }

            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system",
                            "你只输出严格的 JSON，不输出任何其他文字。"),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_MOVE_ENCOUNTER),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_MOVE_ENCOUNTER), true);
            // 路由档位应用（scene=move_encounter 覆盖调用方 maxTokens）
            AiChatRequest finalReq = req;
            if (sceneRouter != null) {
                finalReq = sceneRouter.applyToRequest(req, sceneRouter.resolve(SCENE_MOVE_ENCOUNTER));
            }
            AiChatRequest chatReq = finalReq;
            long startMs = System.currentTimeMillis();
            AiChatResult result = com.holzyn.actor.domain.debug.service.AiCallContext.run(
                    SCENE_MOVE_ENCOUNTER, mover.getId(), () -> aiChatGateway.chatCompletion(
                            userId, projectId, null, chatReq));
            usageLogService.record(userId, projectId, mover.getId(), result.providerId(), result.model(),
                    SCENE_MOVE_ENCOUNTER, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(),
                    (int) (System.currentTimeMillis() - startMs));

            String json = JsonUtil.extractJson(result.content());
            if (json == null) {
                log.warn("[途中交互] AI 未返回有效 JSON（fail-open 继续走）: ref={}", moverRef);
                return;
            }
            JsonNode node = objectMapper.readTree(json);
            String greet = node.path("greet").asText("");
            boolean stop = node.path("stop").asBoolean(false);
            log.info("[途中交互] {} 途中遇到 {}：greet={} stop={}", mover.getName(), otherName, greet, stop);
            // SSE npc:encounter（greet 文本；失败隔离）
            publishEncounter(projectId, moverRef, otherRef, otherName, greet, stop, gameSecond);
            // 事件落库（kind=move_encounter，§4.7；失败隔离）
            movementService.publishEncounterEvent(projectId, moverRef, otherRef, otherName, greet, stop, gameSecond);
            // stop=true → 打断移动进入对话/群聊（§4.6：stop 才打断；失败隔离）
            if (stop) {
                try {
                    movementService.cancelMove(projectId, moverRef, gameSecond);
                    log.debug("[途中交互] {} 停下与 {} 搭话（移动打断）", mover.getName(), otherName);
                } catch (Exception e) {
                    log.warn("[途中交互] 打断移动失败（隔离继续）: ref={} : {}", moverRef, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[途中交互] AI 判定失败（fail-open 不交互继续走）: ref={} : {}", moverRef, e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /** SSE npc:encounter 广播（失败隔离）。 */
    private void publishEncounter(Long projectId, String moverRef, String otherRef, String otherName,
                                  String greet, boolean stop, Long gameSecond) {
        try {
            if (sandboxHub != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("npcRef", moverRef);
                m.put("otherRef", otherRef);
                m.put("otherName", otherName);
                m.put("greet", greet);
                m.put("stop", stop);
                m.put("gameSecond", gameSecond);
                sandboxHub.publish(projectId, EVENT_NPC_ENCOUNTER, m);
            }
        } catch (Exception e) {
            log.warn("[途中交互] npc:encounter 广播失败（隔离继续）: ref={} : {}", moverRef, e.getMessage());
        }
    }

    /** 当前游戏小时（引擎推进位置优先；未装配/失败返回 null=按 0 基准防同刻重复）。 */
    private Long currentGameHour(Long projectId, Long gameSecond) {
        if (gameSecond != null) {
            return Math.floorDiv(gameSecond, 3600L);
        }
        try {
            if (worldClockService != null) {
                Long sec = worldClockService.currentEngineGameSecond(projectId);
                return sec == null ? null : Math.floorDiv(sec, 3600L);
            }
        } catch (Exception e) {
            log.debug("[途中交互] 引擎时刻读取失败（按未知）: {}", e.getMessage());
        }
        return null;
    }

    /** 冷却键（projectId:npcRef）。 */
    static String cooldownKey(Long projectId, String npcRef) {
        return projectId + ":" + npcRef;
    }

    /** 冷却 Map 上限保护（防长期运行内存膨胀）。 */
    private void enforceCooldownLimit() {
        if (lastEncounterHour.size() > COOLDOWN_CACHE_LIMIT) {
            lastEncounterHour.clear();
            log.warn("[途中交互] 冷却 Map 超上限清空（防膨胀）");
        }
    }

    /** 关系提示（移动者 ↔ 对方的关系类型/好感；无关系返回空串=路人）。 */
    String relationHint(Long projectId, Long moverId, ActorCharacter other) {
        if (moverId == null || other == null || other.getId() == null) {
            return "";
        }
        try {
            for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
                if (r == null) {
                    continue;
                }
                boolean involved = (r.getFromCharacterId() != null && moverId.equals(r.getFromCharacterId())
                        && other.getId().equals(r.getToCharacterId()))
                        || (r.getToCharacterId() != null && moverId.equals(r.getToCharacterId())
                        && other.getId().equals(r.getFromCharacterId()));
                if (involved) {
                    String type = r.getRelationType() == null || r.getRelationType().isBlank()
                            ? "相识" : r.getRelationType().trim();
                    String desc = r.getDescription() == null || r.getDescription().isBlank()
                            ? "" : "（" + r.getDescription().trim() + "）";
                    return type + desc;
                }
            }
        } catch (Exception e) {
            log.debug("[途中交互] 关系读取失败（按路人）: {}", e.getMessage());
        }
        return "";
    }
}
