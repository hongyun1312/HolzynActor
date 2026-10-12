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

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryArchiveRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆治理健康度服务 ——
 * 「记忆治理健康度」指标聚合（设计 §11.3 可选新增：归档量 / 版本链数作为治理健康度信号）。
 * <p>职责：为调试前端漂移页 + 主前端角色页记忆 Tab 提供记忆治理健康度指标——</p>
 * <ul>
 * <li><b>归档量</b>：项目未删归档总数 + 按原因（age/superseded/downgraded/evicted/manual）分组 +
 * 按来源表分组 + 已还原（restored）条数——归档治理健康信号（过多/异常增长=记忆治理欠佳）；</li>
 * <li><b>版本链数</b>：L2 已进入版本链（supersedes_id 非空）条数 + 核心块版本链（supersedes_id 非空）
 * 条数——版本演进活跃度（健康：版本链存在且收敛，非无限膨胀）；</li>
 * <li><b>分层分布</b>：L1 active 工作项数（按类型）、L2 记忆条数（按 type 四类 + 摘要子类）、
 * L3 活动核心块数（按项目）——四层记忆体量分布（体量失衡/膨胀预警）。</li>
 * </ul>
 * <p>口径：只读聚合；支持项目维度（全部）与角色维度（按 characterRef 过滤版本链/分层）。
 * 设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§11.3； 决策=漂移页健康度指标 + 主前端健康度卡片。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryHealthService {

    private final ActorMemoryRepository memoryRepository;
    private final ActorMemoryWorkingRepository workingRepository;
    private final ActorCoreMemoryRepository coreRepository;
    private final ActorMemoryArchiveRepository archiveRepository;

    /**
     * 记忆治理健康度（项目维度）。
     *
     * @param projectId 项目 ID
     *
     * @return 健康度 Map（archive/versionChain/tier 三块）
     */
    public Map<String, Object> health(Long projectId) {
        return health(projectId, null);
    }

    /**
     * 记忆治理健康度（可角色维度过滤——角色页/调试页用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（可空=全项目）
     *
     * @return 健康度 Map
     */
    public Map<String, Object> health(Long projectId, String characterRef) {
        if (projectId == null) {
            return empty();
        }
        String ref = characterRef == null ? null : characterRef.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("characterRef", ref);
        out.put("archive", archiveMetrics(projectId, ref));
        out.put("versionChain", versionChainMetrics(projectId, ref));
        out.put("tier", tierMetrics(projectId, ref));
        return out;
    }

    // ==================== ① 归档量 ====================

    /**
     * 归档指标：总数 + 按原因 + 按来源表 + 已还原。
     */
    private Map<String, Object> archiveMetrics(Long projectId, String characterRef) {
        List<ActorMemoryArchive> all = (characterRef == null || characterRef.isBlank())
                ? archiveRepository.findByProjectIdAndDeletedOrderByArchivedAtAsc(projectId, 0)
                : archiveRepository.findByProjectIdAndCharacterRefAndDeletedOrderByArchivedAtDesc(
                projectId, characterRef, 0);
        Map<String, Integer> byReason = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        int restored = 0;
        for (ActorMemoryArchive a : all) {
            String reason = a.getArchiveReason() == null ? "unknown" : a.getArchiveReason();
            byReason.merge(reason, 1, Integer::sum);
            String src = a.getSourceTable() == null ? "unknown" : a.getSourceTable();
            bySource.merge(src, 1, Integer::sum);
            if (a.getRestored() != null && a.getRestored() > 0) {
                restored++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("byReason", byReason);
        m.put("bySourceTable", bySource);
        m.put("restored", restored);
        return m;
    }

    // ==================== ② 版本链数 ====================

    /**
     * 版本链指标：L2 版本链条数 + L3 核心块版本链条数（supersedes_id 非空）。
     */
    private Map<String, Object> versionChainMetrics(Long projectId, String characterRef) {
        List<ActorMemory> l2 = (characterRef == null || characterRef.isBlank())
                ? memoryRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(projectId, 0)
                : memoryRepository.findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(
                projectId, characterRef, 0);
        List<ActorCoreMemory> l3 = (characterRef == null || characterRef.isBlank())
                ? coreRepository.findByProjectIdAndSupersedesIdIsNotNullAndDeleted(projectId, 0)
                : coreRepository.findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeleted(projectId, characterRef, 0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("l2VersionChainCount", l2.size());
        m.put("l3CoreBlockChainCount", l3.size());
        m.put("totalVersionChainCount", l2.size() + l3.size());
        return m;
    }

    // ==================== ③ 分层分布 ====================

    /**
     * 分层分布：L1 active 工作项（按类型）+ L2 记忆（按 type 四类 + 摘要子类）+ L3 活动核心块数。
     */
    private Map<String, Object> tierMetrics(Long projectId, String characterRef) {
        Map<String, Object> l1 = new LinkedHashMap<>();
        List<ActorMemoryWorking> working = (characterRef == null || characterRef.isBlank())
                ? workingRepository.findByProjectIdAndStatusAndDeleted(projectId, "active", 0)
                : workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                projectId, characterRef, "active", 0);
        Map<String, Integer> l1ByType = new LinkedHashMap<>();
        for (ActorMemoryWorking w : working) {
            l1ByType.merge(w.getItemType() == null ? "unknown" : w.getItemType(), 1, Integer::sum);
        }
        l1.put("activeCount", working.size());
        l1.put("byItemType", l1ByType);

        List<ActorMemory> l2All = (characterRef == null || characterRef.isBlank())
                ? memoryRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                : memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(projectId, characterRef, 0);
        int semantic = 0, episodic = 0, reflection = 0, plan = 0, summary = 0;
        for (ActorMemory m : l2All) {
            String t = m.getType() == null ? ActorMemoryTypes.TYPE_SEMANTIC : m.getType();
            switch (t) {
                case ActorMemoryTypes.TYPE_EPISODIC -> episodic++;
                case ActorMemoryTypes.TYPE_REFLECTION -> reflection++;
                case ActorMemoryTypes.TYPE_PLAN -> plan++;
                default -> {
                    semantic++;
                    if (ActorMemoryTypes.isSummary(t, m.getContextJson())) {
                        summary++;
                    }
                }
            }
        }
        Map<String, Object> l2 = new LinkedHashMap<>();
        l2.put("total", l2All.size());
        l2.put("semantic", semantic);
        l2.put("episodic", episodic);
        l2.put("reflection", reflection);
        l2.put("plan", plan);
        l2.put("summary", summary);

        long l3Blocks = (characterRef == null || characterRef.isBlank())
                ? coreRepository.countByProjectIdAndDeleted(projectId, 0)
                : coreRepository.countByProjectIdAndCharacterRefAndDeleted(projectId, characterRef, 0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("l1", l1);
        m.put("l2", l2);
        m.put("l3CoreBlocks", l3Blocks);
        return m;
    }

    /** 空健康度（非法参数）。 */
    private Map<String, Object> empty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archive", Map.of("total", 0, "byReason", Map.of(), "bySourceTable", Map.of(), "restored", 0));
        m.put("versionChain", Map.of("l2VersionChainCount", 0, "l3CoreBlockChainCount", 0, "totalVersionChainCount", 0));
        m.put("tier", Map.of("l1", Map.of("activeCount", 0, "byItemType", Map.of()),
                "l2", Map.of("total", 0, "semantic", 0, "episodic", 0, "reflection", 0, "plan", 0, "summary", 0),
                "l3CoreBlocks", 0L));
        return m;
    }
}
