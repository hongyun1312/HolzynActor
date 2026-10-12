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

package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 人格偏移证据收集器。
 * <p><b>证据强度公式</b>：{@code score = importance × repeat_factor × social_validation × recency_factor}——
 * <ul>
 * <li>{@code importance}：记忆重要度 1-5（核心块按块重要度）；</li>
 * <li>{@code repeat_factor} = 1 + log2(1 + accessCount)：被反复检索的记忆强化（行为印证）；</li>
 * <li>{@code social_validation}：项目级记忆（多人共同见证的世界事件）×1.25，角色级 ×1.0；</li>
 * <li>{@code recency_factor}：评估窗口内的记忆 ×1.0，窗口外 ×0.5（老记忆衰减，防陈旧证据长期主导）。</li>
 * </ul></p>
 * <p><b>筛选</b>：总证据强度 &lt; {@code minEvidenceScore}（默认 6.0）→ 该角色本轮不评估（零 AI——
 * 「没有足够经历的人不会变」，设计 §5.5「阈值 T1 以下为 0」）；通过后取 Top N
 * （{@code evidenceTopN}，默认 20）条记忆送入 AI。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftEvidenceCollector {

    /** 评估窗口（游戏秒；以最近一次评估时刻为界，窗口内记忆权重 1.0）——30 游戏日由调用方换算传入 */
    private static final double WINDOW_OUTSIDE_FACTOR = 0.5;
    /** 项目级记忆的社会验证权重（多人共同见证） */
    private static final double SOCIAL_VALIDATION_PROJECT = 1.25;

    private final ActorMemoryRepository memoryRepository;
    private final ActorCoreMemoryRepository coreMemoryRepository;

    /** 一条筛选后的证据。 */
    public record Evidence(String refId, String source, String content, int importance, double score) {
    }

    /**
     * 收集并筛选证据（纯读，无 AI）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用
     *
     * @param windowStartGameSecond 评估窗口起点（游戏秒；null=全量）
     *
     * @param gameSecond 当前世界时刻（窗口终点）
     *
     * @param minScore 证据强度门槛（低于此值整体跳过）
     *
     * @param topN 送入 AI 的条数上限
     *
     * @return 收集结果（score&lt;门槛时 evidence 为空列表；零阻断）
     */
    public CollectResult collect(Long projectId, String npcRef, Long windowStartGameSecond,
                                 Long gameSecond, double minScore, int topN) {
        List<Evidence> all = new ArrayList<>();
        try {
            // ① L2 语义/情节/反思记忆（角色级 + 项目级）
            for (ActorMemory m : memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                    projectId, npcRef, 0)) {
                all.addAll(scoreL2(m, windowStartGameSecond, gameSecond, true));
            }
            for (ActorMemory m : memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(
                    projectId, 0)) {
                all.addAll(scoreL2(m, windowStartGameSecond, gameSecond, false));
            }
            // ② L3 核心记忆块（角色级）
            for (ActorCoreMemory c : coreMemoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
                    projectId, npcRef, 0)) {
                if (c == null || c.getContent() == null || c.getContent().isBlank()) {
                    continue;
                }
                double score = (c.getImportance() == null ? 3 : c.getImportance()) * 1.5;
                all.add(new Evidence("l3:" + c.getId(), "core", truncate(c.getContent(), 120),
                        c.getImportance() == null ? 3 : c.getImportance(), score));
            }
        } catch (Exception e) {
            log.warn("[偏移证据] 证据收集失败（按无证据处理，零阻断）: ref={} 原因={}", npcRef, e.getMessage());
            return new CollectResult(0, List.of());
        }
        // 排序取 Top N
        all.sort(Comparator.comparingDouble(Evidence::score).reversed());
        double total = all.stream().mapToDouble(Evidence::score).sum();
        if (total < minScore || all.isEmpty()) {
            return new CollectResult(round2(total), List.of());
        }
        List<Evidence> top = all.size() <= topN ? all : all.subList(0, topN);
        return new CollectResult(round2(total), top);
    }

    /** L2 记忆打分（角色级/项目级分权；plan 类与过低重要度记忆跳过——计划不是人格证据）。 */
    private List<Evidence> scoreL2(ActorMemory m, Long windowStart, Long gameSecond, boolean characterLevel) {
        List<Evidence> out = new ArrayList<>();
        if (m == null || m.getContent() == null || m.getContent().isBlank()) {
            return out;
        }
        String type = m.getType();
        if ("plan".equalsIgnoreCase(type)) {
            return out; // 计划不是人格证据
        }
        int imp = m.getImportance() == null ? 1 : m.getImportance();
        if (imp < 2) {
            return out; // 琐事不构成人格证据（设计：不用噪声大的低重要度记忆）
        }
        int access = m.getAccessCount() == null ? 0 : m.getAccessCount();
        double repeat = 1 + (access <= 0 ? 0 : Math.log(1 + access) / Math.log(2));
        double social = characterLevel ? 1.0 : SOCIAL_VALIDATION_PROJECT;
        double recency = windowStart != null && gameSecond != null ? WINDOW_OUTSIDE_FACTOR : 1.0;
        double score = imp * repeat * social * recency;
        out.add(new Evidence("l2:" + m.getId(), type, truncate(m.getContent(), 120), imp, round2(score)));
        return out;
    }

    /** 收集结果。 */
    public record CollectResult(double totalScore, List<Evidence> evidence) {
        /** 是否达到评估门槛（证据充分）。 */
        public boolean sufficient() {
            return totalScore >= 1 && !evidence.isEmpty();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
