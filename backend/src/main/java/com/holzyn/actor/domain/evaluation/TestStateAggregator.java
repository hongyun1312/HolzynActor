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

package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.agent.roleplay.AutonomousChatService;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 状态聚合器。
 * <p>时间窗可配（默认近 1 游戏日=windowGameMinutes 1440；经世界时钟 rate 折算真实分钟——
 * 与引擎推进口径一致）。输出供调试前端状态面板与 test_run 快照落库（state_snapshot_json）。</p>
 * <ul>
 * <li><b>引擎推进</b>：引擎实际位置（lastGameSecond/lastGameHour/游戏日/当日小时）+ 暂停/持续运行/
 * 实时决策激活态（active/manual/sandbox/evolution）+ AI 强度档 + runMode + 每 tick 预算 +
 * 时间窗内 AI 调用成功/失败数；</li>
 * <li><b>群聊活跃</b>：进行中剧集数 + group 模式会话数 + 时间窗内群聊消息数与发言者分布 + 最近消息时刻；</li>
 * <li><b>预算</b>：BudgetGuard 引擎 tick 命名空间（remaining/used/total）+ 剧集命名空间
 * （hasWindow/remaining/used）；</li>
 * <li><b>用量归因</b>：时间窗内 AI 调用按 NPC × scene 聚合（调用数/token/失败数）——长跑可识别
 * 「谁在烧预算」（decideSpeaker 用量归属到人，）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestStateAggregator {

    private final WorldClockService worldClockService;
    private final RealtimeDecisionActivationService activationService;
    private final AiIntensityGate aiIntensityGate;
    private final ActorWorldEngineRepository engineRepository;
    private final BudgetGuard budgetGuard;
    private final AutonomousChatService autonomousChatService;
    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final EvaluationProperties properties;

    /**
     * 四块状态聚合。
     *
     * @param projectId 项目 ID
     *
     * @param windowGameMinutes 游戏时间窗（分钟；null/<=0 用配置默认 1440）
     *
     * @return 聚合 Map（引擎/群聊/预算/用量四块 + 时间窗说明）
     */
    public Map<String, Object> aggregate(Long projectId, Integer windowGameMinutes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        int gameMin = windowGameMinutes == null || windowGameMinutes <= 0
                ? properties.getDefaultWindowGameMinutes() : windowGameMinutes;
        long realMin = realWindowMinutes(projectId, gameMin);
        LocalDateTime from = LocalDateTime.now().minusMinutes(Math.max(1, realMin));
        out.put("window", Map.of("gameMinutes", gameMin, "realMinutes", realMin,
                "fromReal", from.toString(), "toReal", LocalDateTime.now().toString()));
        out.put("engine", engineBlock(projectId, from));
        out.put("chat", chatBlock(projectId, from));
        out.put("budget", budgetBlock(projectId));
        out.put("usage", usageBlock(projectId, from));
        return out;
    }

    // ==================== ① 引擎推进 ====================

    private Map<String, Object> engineBlock(Long projectId, LocalDateTime from) {
        Map<String, Object> m = new LinkedHashMap<>();
        ActorWorldClock clock = null;
        try {
            clock = worldClockService.requireClock(projectId);
        } catch (Exception ignored) {
            // 无时钟行 → 全空
        }
        long second = clock == null || clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
        long hour = second / 3600L;
        m.put("gameSecond", second);
        m.put("gameHour", hour);
        m.put("gameDay", hour / 24L + 1);
        m.put("hourOfDay", Math.floorMod(hour, 24L));
        m.put("paused", clock != null && Integer.valueOf(1).equals(clock.getPaused()) ? 1 : 0);
        m.put("worldRunning", clock != null && Integer.valueOf(1).equals(clock.getWorldRunning()) ? 1 : 0);
        m.put("rate", clock == null || clock.getRate() == null ? 24 : clock.getRate());
        m.put("lastEngineRunAt", clock == null ? null : String.valueOf(clock.getLastEngineRunAt()));
        m.put("lastSummary", clock == null ? null : clock.getLastSummary());
        m.put("active", activationService.isActive(projectId));
        m.put("manualEnabled", activationService.isManualEnabled(projectId));
        m.put("sandboxActive", activationService.isSandboxActive(projectId));
        m.put("evolutionActive", activationService.isEvolutionActive(projectId));
        m.put("aiIntensity", aiIntensityGate.intensityOf(projectId));
        m.put("aiIntensityLabel", AiIntensityGate.label(aiIntensityGate.intensityOf(projectId)));
        ActorWorldEngine engine = null;
        try {
            engine = engineRepository.findByProjectId(projectId).orElse(null);
        } catch (Exception ignored) {
            // 引擎行缺失 → null
        }
        m.put("runMode", engine == null ? "standard" : engine.getRunMode());
        m.put("budgetPerTick", engine == null || engine.getBudgetPerTick() == null ? 5 : engine.getBudgetPerTick());
        // 时间窗内 AI 调用成功/失败数（决策质量大背景）
        int total = 0, success = 0, failed = 0;
        try {
            List<ActorAiCallLog> calls = aiCallLogRepository
                    .findByProjectIdAndCreatedAtGreaterThanEqual(projectId, from);
            total = calls.size();
            for (ActorAiCallLog c : calls) {
                if ("success".equals(c.getStatus())) {
                    success++;
                } else {
                    failed++;
                }
            }
        } catch (Exception ignored) {
            // 聚合失败不阻断
        }
        m.put("aiCallTotal", total);
        m.put("aiCallSuccess", success);
        m.put("aiCallFailed", failed);
        return m;
    }

    // ==================== ② 群聊活跃 ====================

    private Map<String, Object> chatBlock(Long projectId, LocalDateTime from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runningEpisodes", autonomousChatService.runningEpisodeCount());
        List<ActorConversation> groups = new ArrayList<>();
        try {
            groups = conversationRepository.findByProjectIdAndMode(projectId, "group");
        } catch (Exception ignored) {
            // 查询失败 → 空
        }
        m.put("groupConversations", groups.size());
        List<Long> ids = groups.stream().map(ActorConversation::getId).toList();
        List<ActorMessage> msgs = ids.isEmpty() ? List.of()
                : messageRepository.findByConversationIdInAndCreatedAtGreaterThanEqualOrderByIdAsc(ids, from);
        m.put("groupMessages", msgs.size());
        // 发言者分布（characterId → 条数；名字解析）
        Map<Long, Long> byChar = msgs.stream()
                .filter(x -> x.getCharacterId() != null)
                .collect(Collectors.groupingBy(ActorMessage::getCharacterId, Collectors.counting()));
        List<Map<String, Object>> speakers = new ArrayList<>();
        byChar.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("npcRef", refOf(projectId, e.getKey()));
                    s.put("npcName", nameOf(projectId, e.getKey()));
                    s.put("count", e.getValue());
                    speakers.add(s);
                });
        m.put("speakers", speakers);
        LocalDateTime latest = msgs.stream().map(ActorMessage::getCreatedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        m.put("latestMessageAt", latest == null ? null : String.valueOf(latest));
        return m;
    }

    // ==================== ③ 预算 ====================

    private Map<String, Object> budgetBlock(Long projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        int tickRemaining = budgetGuard.remaining(projectId);
        int tickUsed = budgetGuard.used(projectId);
        int tickTotal = budgetGuard.budgetTotal(projectId);
        m.put("tick", Map.of(
                "hasWindow", tickTotal >= 0,
                "remaining", tickRemaining,
                "used", tickUsed,
                "total", tickTotal < 0 ? 0 : tickTotal));
        m.put("episode", Map.of(
                "hasWindow", budgetGuard.hasEpisodeWindow(projectId),
                "remaining", budgetGuard.remainingEpisode(projectId),
                "used", budgetGuard.episodeUsed(projectId)));
        return m;
    }

    // ==================== ④ 用量归因 ====================

    private Map<String, Object> usageBlock(Long projectId, LocalDateTime from) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<ActorAiCallLog> calls = new ArrayList<>();
        try {
            calls = aiCallLogRepository.findByProjectIdAndCreatedAtGreaterThanEqual(projectId, from);
        } catch (Exception ignored) {
            // 查询失败 → 空
        }
        int total = calls.size();
        long inTok = calls.stream().mapToLong(c -> c.getTokenIn() == null ? 0 : c.getTokenIn()).sum();
        long outTok = calls.stream().mapToLong(c -> c.getTokenOut() == null ? 0 : c.getTokenOut()).sum();
        long failed = calls.stream().filter(c -> !"success".equals(c.getStatus())).count();
        m.put("totalCalls", total);
        m.put("totalTokenIn", inTok);
        m.put("totalTokenOut", outTok);
        m.put("failed", failed);
        // 按 NPC × scene 聚合
        Map<String, List<ActorAiCallLog>> byNpc = calls.stream()
                .filter(c -> c.getCharacterId() != null)
                .collect(Collectors.groupingBy(c -> c.getCharacterId() + ":" + c.getScene()));
        List<Map<String, Object>> npcRows = new ArrayList<>();
        byNpc.forEach((key, list) -> {
            Long charId = Long.parseLong(key.substring(0, key.indexOf(':')));
            String scene = key.substring(key.indexOf(':') + 1);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("npcRef", refOf(projectId, charId));
            r.put("npcName", nameOf(projectId, charId));
            r.put("scene", scene);
            r.put("calls", list.size());
            r.put("tokenIn", list.stream().mapToLong(c -> c.getTokenIn() == null ? 0 : c.getTokenIn()).sum());
            r.put("tokenOut", list.stream().mapToLong(c -> c.getTokenOut() == null ? 0 : c.getTokenOut()).sum());
            r.put("failed", list.stream().filter(c -> !"success".equals(c.getStatus())).count());
            npcRows.add(r);
        });
        npcRows.sort(Comparator.comparingLong((Map<String, Object> r) ->
                ((Number) r.get("calls")).longValue()).reversed());
        m.put("byNpc", npcRows);
        // 按 scene 聚合
        Map<String, List<ActorAiCallLog>> byScene = calls.stream()
                .collect(Collectors.groupingBy(c -> c.getScene() == null ? "?" : c.getScene()));
        List<Map<String, Object>> sceneRows = new ArrayList<>();
        byScene.forEach((scene, list) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scene", scene);
            r.put("calls", list.size());
            r.put("tokenIn", list.stream().mapToLong(c -> c.getTokenIn() == null ? 0 : c.getTokenIn()).sum());
            r.put("tokenOut", list.stream().mapToLong(c -> c.getTokenOut() == null ? 0 : c.getTokenOut()).sum());
            sceneRows.add(r);
        });
        sceneRows.sort(Comparator.comparingLong((Map<String, Object> r) ->
                ((Number) r.get("calls")).longValue()).reversed());
        m.put("byScene", sceneRows);
        return m;
    }

    // ==================== 内部工具 ====================

    /** 游戏分钟 → 真实分钟（rate=每真实小时推进的游戏小时数；realMin = gameMin / rate）。 */
    private long realWindowMinutes(Long projectId, int gameMinutes) {
        int rate = 24;
        try {
            ActorWorldClock clock = worldClockService.requireClock(projectId);
            if (clock.getRate() != null && clock.getRate() > 0) {
                rate = clock.getRate();
            }
        } catch (Exception ignored) {
            // 时钟不可得按默认 rate=24
        }
        return Math.max(1, Math.round(gameMinutes / (double) rate));
    }

    /** characterId → ref（先特殊后普通）。 */
    private String refOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        if (characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        if (ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId()))
                .isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        return null;
    }

    /** characterId → 角色名（先特殊后普通）。 */
    private String nameOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        java.util.Optional<ActorCharacter> ch = characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()));
        if (ch.isPresent()) {
            return ch.get().getName();
        }
        java.util.Optional<ActorCharacter> npc = ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId()));
        return npc.map(ActorCharacter::getName).orElse(null);
    }
}
