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

import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 漂移度量聚合服务（阶段 D 决策链路可视化 · 漂移度量视图[R4]，
 * domain/debug/service）。
 * <p>职责：基于现有数据为调试前端「漂移度量」页聚合三个指标（R4：目标一致率 / 矛盾率 /
 * Token 成本）——<ul>
 * <li><b>目标一致率</b>：actor_character_goal 状态分布（active 进行中/done 已完成/abandoned
 * 已放弃），一致率 = done / 全部目标（目标频繁被放弃/更换 = 叙事漂移信号）；</li>
 * <li><b>矛盾率</b>：actor_memory kind=reflection（反思洞察）中含矛盾类关键词
 * （矛盾/冲突/不一致/前后不一/自相矛盾/contradict/inconsistent 等）的条数占比——反思中反复
 * 识别到与自身事实/目标矛盾 = 一致性漂移信号；</li>
 * <li><b>Token 成本</b>：actor_ai_call_log 在统计窗口内（默认近 30 天）的调用次数 / token 用量 /
 * 耗时，按日趋势（ECharts 用）+ 按角色排行。</li>
 * </ul>全部只读；口径为「现有数据简单聚合」（R4 正式护栏在阶段 E4 落地）。</p>
 */
@Service
@RequiredArgsConstructor
public class DebugDriftMetricsService {

    /** 矛盾类关键词（命中即计为矛盾条，中文 + 英文小写兜底） */
    private static final List<String> CONTRADICTION_KEYWORDS = List.of(
            "矛盾", "冲突", "不一致", "前后不一", "自相矛盾", "背离", "contradict", "inconsistent", "discrepancy");

    /** 默认统计窗口（天） */
    private static final int DEFAULT_DAYS = 30;

    private final ActorCharacterGoalRepository goalRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorCharacterRepository characterRepository;

    /** 记忆治理健康度 */
    private final com.holzyn.actor.domain.memory.service.MemoryHealthService memoryHealthService;

    /**
     * 漂移度量聚合。
     *
     * @param projectId 项目 ID（可空=全部项目）
     *
     * @param characterId 角色 ID（可空=全部角色；指定后目标/反思/Token 均按该角色聚合）
     *
     * @param days 统计窗口（天，默认 30，上限 365）
     *
     * @return 漂移度量 Map
     */
    public Map<String, Object> driftMetrics(Long projectId, Long characterId, int days) {
        int windowDays = Math.min(Math.max(1, days <= 0 ? DEFAULT_DAYS : days), 365);
        LocalDateTime from = LocalDateTime.now().minusDays(windowDays);
        LocalDateTime asOf = LocalDateTime.now();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf.toString());
        out.put("days", windowDays);
        out.put("projectId", projectId);
        out.put("characterId", characterId);
        out.put("goals", goalMetrics(projectId, characterId));
        out.put("reflection", reflectionMetrics(projectId, characterId));
        out.put("token", tokenMetrics(projectId, characterId, from));
        // 记忆治理健康度（设计 §11.3 可选新增：归档量/版本链数作为「记忆治理健康度」指标）
        try {
            String ref = characterId == null ? null : com.holzyn.actor.common.NpcRefs.of(characterId);
            out.put("memoryHealth", projectId == null ? emptyMemoryHealth() : memoryHealthService.health(projectId, ref));
        } catch (Exception e) {
            out.put("memoryHealth", emptyMemoryHealth());
        }
        return out;
    }

    /** 项目维度为空时的健康度占位（调试页项目未选时不报错）。 */
    private Map<String, Object> emptyMemoryHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archive", Map.of("total", 0, "byReason", Map.of(), "bySourceTable", Map.of(), "restored", 0));
        m.put("versionChain", Map.of("l2VersionChainCount", 0, "l3CoreBlockChainCount", 0, "totalVersionChainCount", 0));
        m.put("tier", Map.of("l1", Map.of("activeCount", 0, "byItemType", Map.of()),
                "l2", Map.of("total", 0, "semantic", 0, "episodic", 0, "reflection", 0, "plan", 0, "summary", 0),
                "l3CoreBlocks", 0L));
        return m;
    }

    // ==================== ① 目标一致率 ====================

    /**
     * 目标一致率：actor_character_goal 状态分布 + 一致率（done / 全部）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     */
    private Map<String, Object> goalMetrics(Long projectId, Long characterId) {
        List<ActorCharacterGoal> goals;
        if (characterId != null) {
            goals = goalRepository.findByAgentRefAndDeletedOrderByIdAsc(com.holzyn.actor.common.NpcRefs.of(characterId), 0);
        } else if (projectId != null) {
            goals = goalRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        } else {
            goals = goalRepository.findByDeletedOrderByIdAsc(0);
        }
        int active = 0, done = 0, abandoned = 0;
        for (ActorCharacterGoal g : goals) {
            String s = g.getStatus() == null ? "active" : g.getStatus();
            if ("done".equals(s)) {
                done++;
            } else if ("abandoned".equals(s)) {
                abandoned++;
            } else {
                active++;
            }
        }
        int total = goals.size();
        double consistencyRate = total == 0 ? 0d : (double) done / total;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("active", active);
        m.put("done", done);
        m.put("abandoned", abandoned);
        m.put("consistencyRate", round(consistencyRate));
        return m;
    }

    // ==================== ② 矛盾率 ====================

    /**
     * 矛盾率：type=reflection 反思记忆中含矛盾类关键词条数占比。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     */
    private Map<String, Object> reflectionMetrics(Long projectId, Long characterId) {
        List<ActorMemory> reflections;
        if (characterId != null) {
            reflections = memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByIdAsc(
                    projectId, characterId, ActorMemoryTypes.TYPE_REFLECTION, 0);
        } else if (projectId != null) {
            reflections = memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                    projectId, ActorMemoryTypes.TYPE_REFLECTION, 0);
        } else {
            reflections = memoryRepository.findByTypeAndDeletedOrderByIdAsc(ActorMemoryTypes.TYPE_REFLECTION, 0);
        }
        int contradictionCount = 0;
        for (ActorMemory m : reflections) {
            if (isContradiction(m.getContent())) {
                contradictionCount++;
            }
        }
        int total = reflections.size();
        double contradictionRate = total == 0 ? 0d : (double) contradictionCount / total;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("contradictionCount", contradictionCount);
        m.put("contradictionRate", round(contradictionRate));
        return m;
    }

    /**
     * 内容是否命中矛盾类关键词（英文小写归一）。
     *
     * @param content 记忆内容（可空）
     *
     * @return 命中 true
     */
    private boolean isContradiction(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String k : CONTRADICTION_KEYWORDS) {
            if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    // ==================== ③ Token 成本 ====================

    /**
     * Token 成本：窗口内 ai_call_log 聚合（调用次数/token 用量/耗时 + 按日趋势 + 按角色排行）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param from 统计窗口起点（含）
     */
    private Map<String, Object> tokenMetrics(Long projectId, Long characterId, LocalDateTime from) {
        List<ActorAiCallLog> calls = aiCallLogRepository.findByCreatedAtGreaterThanEqual(from);
        int totalCalls = 0, tokenIn = 0, tokenOut = 0, duration = 0;
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byCharacter = new LinkedHashMap<>();
        for (ActorAiCallLog c : calls) {
            if (projectId != null && !java.util.Objects.equals(c.getProjectId(), projectId)) {
                continue;
            }
            if (characterId != null && !java.util.Objects.equals(c.getCharacterId(), characterId)) {
                continue;
            }
            totalCalls++;
            tokenIn += nz(c.getTokenIn());
            tokenOut += nz(c.getTokenOut());
            duration += nz(c.getDurationMs());
            // 按日聚合
            String day = c.getCreatedAt() == null ? "未知" : c.getCreatedAt().toLocalDate().toString();
            Map<String, Object> d = byDay.computeIfAbsent(day, k -> {
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("date", k);
                md.put("calls", 0);
                md.put("tokenIn", 0);
                md.put("tokenOut", 0);
                return md;
            });
            d.put("calls", (Integer) d.get("calls") + 1);
            d.put("tokenIn", (Integer) d.get("tokenIn") + nz(c.getTokenIn()));
            d.put("tokenOut", (Integer) d.get("tokenOut") + nz(c.getTokenOut()));
            // 按角色聚合（仅特殊 NPC 有 character_id；普通 NPC/项目级=null 归「无角色」）
            Long cid = c.getCharacterId();
            Map<String, Object> cc = byCharacter.computeIfAbsent(cid, k -> {
                Map<String, Object> mc = new LinkedHashMap<>();
                mc.put("characterId", k);
                mc.put("characterName", k == null ? "无角色/普通NPC" : characterName(k));
                mc.put("calls", 0);
                mc.put("tokenIn", 0);
                mc.put("tokenOut", 0);
                return mc;
            });
            cc.put("calls", (Integer) cc.get("calls") + 1);
            cc.put("tokenIn", (Integer) cc.get("tokenIn") + nz(c.getTokenIn()));
            cc.put("tokenOut", (Integer) cc.get("tokenOut") + nz(c.getTokenOut()));
        }
        List<Map<String, Object>> byDayList = new ArrayList<>(byDay.values());
        byDayList.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
        List<Map<String, Object>> byCharacterList = new ArrayList<>(byCharacter.values());
        // 按 token 总量倒序排行
        byCharacterList.sort((a, b) -> Integer.compare(
                (Integer) b.get("tokenIn") + (Integer) b.get("tokenOut"),
                (Integer) a.get("tokenIn") + (Integer) a.get("tokenOut")));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", totalCalls);
        m.put("tokenIn", tokenIn);
        m.put("tokenOut", tokenOut);
        m.put("totalToken", tokenIn + tokenOut);
        m.put("durationMs", duration);
        m.put("byDay", byDayList);
        m.put("byCharacter", byCharacterList);
        return m;
    }

    // ==================== 工具 ====================

    /** 角色名解析（无则 null） */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return null;
        }
        return characterRepository.findById(characterId).map(c -> c.getName()).orElse(null);
    }

    /** 空值安全取整 */
    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 比率保留 4 位小数（前端显示百分比） */
    private double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
