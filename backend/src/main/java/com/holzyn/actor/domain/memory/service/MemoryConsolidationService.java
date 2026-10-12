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

import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆巩固编排服务 ——后台巩固统一编排层。
 * <p>职责：落地设计 §10「巩固与生命周期落地（write-manage-read）」——把分散的巩固/管理动作收编为
 * 统一编排入口 + scene=memory_manage trace 埋点（对齐 LangMem 后台记忆管理器 / 双缓冲巩固）：</p>
 * <ul>
 * <li><b>会话结束巩固</b>：{@link #consolidateConversation}——收编 AgentMemoryService 三路触发
 * （会话超时懒检查 / 会话归档 / 手动补抽）共用的 L1→L2 双缓冲巩固（委托
 * <li><b>定时批量巩固</b>：{@link #batchConsolidate}（记忆系统重构，sleep-time
 * compute 思想）——游戏日边界（世界引擎记忆周期钩子）+ 真实 cron 兜底双调度：
 * L1 草稿批量复核提升（项目级，委托 MemoryWorkingService.consolidateProject）/ 相关事实合并
 * （确定性 Dice 合并 + supersedes 版本链）/ 项目记忆巩固补漏与编年史归档（委托
 * WorldMemoryService.consolidateWorldMemory）；</li>
 * <li><b>定时维护统一调度</b>：{@link #maintenance()}——一个统一节奏编排 L1 维护
 * （TTL 清理 + 巩固扫描，委托 MemoryWorkingService）与归档维护
 * （超龄扫描 + 保留滚动 + 二次清理，委托 MemoryArchiveService），单步失败隔离；</li>
 * <li><b>trace/观测</b>：全部管理动作经 {@link AiCallContext#recordStep} 落 scene=memory_manage 非 AI 环节
 * （决策链路可见记忆治理动作），失败静默不阻断主流程。</li>
 * </ul>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案》§3.3/§9 （即时+定时批量巩固）；
 * 《记忆系统分层重构 L1/L2/L3/归档 设计方案》§10；用户 决策：调度点=游戏日边界为主
 * + 真实 cron 兜底（双保险覆盖引擎长暂停/低负载场景）。</p>
 */
@Slf4j
@Service
public class MemoryConsolidationService {

    /** 统一编排总开关（默认 true；false=本服务全部动作跳过，各服务独立调度仍按其自身开关运行） */
    @Value("${holzyn.actor.memory.consolidation.enabled:true}")
    private boolean enabled;

    /** 统一定时维护开关（默认 true；false=本编排跳过，各服务独立调度仍按其自身开关运行） */
    @Value("${holzyn.actor.memory.consolidation.maintenance-enabled:true}")
    private boolean maintenanceEnabled;

    /** 统一定时维护间隔（毫秒，默认 1800000=30 分钟，与 L1 维护节奏对齐） */
    @Value("${holzyn.actor.memory.consolidation.maintenance-interval-ms:1800000}")
    private long maintenanceIntervalMs;

    /** 记忆系统重构：批量巩固真实 cron 兜底开关（默认 true；false=仅游戏日边界触发） */
    @Value("${holzyn.actor.memory.consolidation.batch-cron-enabled:true}")
    private boolean batchCronEnabled;

    /** 记忆系统重构：批量巩固 cron 兜底间隔（真实毫秒，默认 21600000=6 小时） */
    @Value("${holzyn.actor.memory.consolidation.batch-interval-ms:21600000}")
    private long batchIntervalMs;

    /** 记忆系统重构：事实合并 Dice 阈值（默认 0.85：同主题强重复才合并，防误合） */
    @Value("${holzyn.actor.memory.consolidation.merge-threshold:0.85}")
    private double mergeThreshold;

    /** L1 工作记忆服务（巩固/维护委托） */
    @Autowired(required = false)
    private MemoryWorkingService workingService;

    /** 归档服务（归档维护委托：超龄扫描 + 保留滚动 + 二次清理） */
    @Autowired(required = false)
    private MemoryArchiveService archiveService;

    /** 项目仓库（统一维护按项目遍历） */
    @Autowired(required = false)
    private ActorProjectRepository projectRepository;

    /** L2 长期记忆仓库（事实合并且语义记忆查询） */
    @Autowired(required = false)
    private ActorMemoryRepository memoryRepository;

    /** 项目记忆服务 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldMemoryService worldMemoryService;

    /** 世界时钟（cron 兜底解析当前游戏小时） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /** L1 模糊引擎（游戏日边界艾宾浩斯模糊扫描 + 超清醒兜底；null=未装配跳过） */
    @Autowired(required = false)
    private L1FuzzificationService fuzzService;

    /** 跨层蒸馏服务（L1→L2→L3 阈值蒸馏 + 谱系；null=未装配跳过） */
    @Autowired(required = false)
    private MemoryDistillService distillService;

    /**
     * 世界运行时门控（用户需求：启动全静止，进入沙盘才运行；null=未装配[单测]不过滤——
     * 未运行世界的记忆蒸馏/批量巩固/L3 核心自编辑 cron 一律跳过，世界静止=数据静止）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService runtimeGate;

    /**
     * 会话结束巩固（统一编排入口）：L1→L2 双缓冲巩固 + scene=memory_manage trace。
     * <p>收编 AgentMemoryService 三路触发（超时懒检查 / 会话归档 / 手动补抽）共用语义——
     * 但既有 AgentMemoryService 的直调保持（避免循环依赖 + 存量单测兼容），本方法供
     * 统一入口/调试 API 使用（如手动触发巩固），两者均委托同一 {@code consolidateSession}。</p>
     *
     * @param sessionKey 会话 key（conversation_id 字符串）
     *
     * @return 提升进 L2 的条数
     */
    public int consolidateConversation(String sessionKey) {
        if (!enabled || sessionKey == null || sessionKey.isBlank()) {
            return 0;
        }
        int promoted = 0;
        long start = System.currentTimeMillis();
        if (workingService != null) {
            try {
                promoted = workingService.consolidateSession(sessionKey);
            } catch (Exception e) {
                log.warn("[记忆巩固编排] 会话={} L1 巩固失败（隔离）: {}", sessionKey, e.getMessage());
            }
        }
        AiCallContext.recordStep(null, "memory_manage", "consolidate",
                "会话巩固：session=" + sessionKey + " 提升=" + promoted,
                System.currentTimeMillis() - start);
        return promoted;
    }

    /**
     * 定时批量巩固 ——游戏日边界主调度
     * （世界引擎记忆周期 ③-d 钩子）+ 真实 cron 兜底（{@link #batchConsolidateCron}）双保险。
     * <p>动作（单步失败隔离，不阻断其他步）：</p>
     * <ul>
     * <li>① L1 草稿批量复核提升（项目级全部 active 工作项，委托 MemoryWorkingService.consolidateProject，
     * 对齐双缓冲巩固「草稿经复核才升长期」）；</li>
     * <li>② 相关事实合并（确定性：同角色 L2 semantic 两两 Dice ≥ merge-threshold 的强重复
     * → 保留新条 + 旧条软删归档 superseded 版本链，防「重复事实污染检索」）；</li>
     * <li>③ 项目记忆巩固（编年史补漏提炼[近 3 游戏日已过 01:00 无 daily 条目 → 补] + 超龄归档，
     * 委托 WorldMemoryService.consolidateWorldMemory）；</li>
     * <li>④ 反思生成与 TTL 过期清理由既有服务独立调度承担（ReflectionService.scheduleConsolidation /
     * MemoryWorkingService.cleanupExpired），本编排不重复触发。</li>
     * </ul>
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 当前游戏小时（世界引擎记忆周期传入；null=cron 兜底按世界时钟解析）
     *
     * @return 本次批量巩固执行的步骤数（观测用）
     */
    public int batchConsolidate(Long projectId, Long gameHour) {
        if (!enabled || projectId == null) {
            return 0;
        }
        // 世界运行时门控：cron 兜底路径（gameHour=null）只服务运行中的世界——
        // 未激活项目跳过（引擎记忆周期钩子路径由引擎激活闸门天然覆盖，此处防 cron 兜底越权）
        if (gameHour == null && runtimeGate != null && !runtimeGate.isActive(projectId)) {
            log.debug("[记忆批量巩固] cron 兜底跳过（世界未运行）: project={}", projectId);
            return 0;
        }
        // 游戏日解析：引擎传入优先；null（cron 兜底）按世界时钟解析，仍不可用跳过
        Long hour = gameHour;
        if (hour == null && worldClockService != null) {
            try {
                hour = worldClockService.currentEngineGameHour(projectId);
            } catch (Exception e) {
                log.warn("[记忆批量巩固] 世界时钟解析失败，跳过本轮: project={} : {}", projectId, e.getMessage());
                return 0;
            }
        }
        if (hour == null) {
            return 0;
        }
        long gameDay = Math.floorDiv(hour, 24L);
        int steps = 0;
        // ① L1 草稿批量复核提升（项目级）
        if (workingService != null) {
            long start = System.currentTimeMillis();
            try {
                int promoted = workingService.consolidateProject(projectId);
                AiCallContext.recordStep(projectId, "memory_manage", "batch_consolidate",
                        "批量巩固 L1：提升=" + promoted + " 游戏日=" + gameDay,
                        System.currentTimeMillis() - start);
                steps++;
            } catch (Exception e) {
                log.warn("[记忆批量巩固] L1 项目巩固失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ② 相关事实合并（确定性 Dice 合并 + 版本链）
        if (memoryRepository != null) {
            long start = System.currentTimeMillis();
            try {
                int merged = mergeDuplicateFacts(projectId);
                if (merged > 0) {
                    AiCallContext.recordStep(projectId, "memory_manage", "fact_merge",
                            "批量巩固事实合并：" + merged + " 条（Dice ≥ " + mergeThreshold + "）",
                            System.currentTimeMillis() - start);
                }
                steps++;
            } catch (Exception e) {
                log.warn("[记忆批量巩固] 事实合并失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ③ 项目记忆巩固（编年史补漏 + 超龄归档）
        if (worldMemoryService != null) {
            long start = System.currentTimeMillis();
            try {
                worldMemoryService.consolidateWorldMemory(projectId, gameDay, hour * 3600L);
                AiCallContext.recordStep(projectId, "memory_manage", "world_memory",
                        "批量巩固项目记忆：编年史补漏+归档 游戏日=" + gameDay,
                        System.currentTimeMillis() - start);
                steps++;
            } catch (Exception e) {
                log.warn("[记忆批量巩固] 项目记忆巩固失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ④ 记忆内核扩展（，游戏日边界）：艾宾浩斯到点模糊扫描 + 超清醒兜底
        if (fuzzService != null) {
            long start = System.currentTimeMillis();
            try {
                int fuzzed = fuzzService.scanDueFuzz(projectId, hour * 3600L);
                if (fuzzed > 0) {
                    AiCallContext.recordStep(projectId, "memory_manage", "l1_fuzz",
                            "L1 模糊扫描：模糊 " + fuzzed + " 条 游戏日=" + gameDay,
                            System.currentTimeMillis() - start);
                }
                steps++;
            } catch (Exception e) {
                log.warn("[记忆批量巩固] L1 模糊扫描失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ⑤ 跨层蒸馏（，游戏日边界）：L1→L2（fuzz≥0.6/检索≥3/重要≥0.7）+
        // L2→L3（7 游戏日 5 检/计划强相关）+ 谱系
        if (distillService != null) {
            long start = System.currentTimeMillis();
            try {
                int d1 = distillService.scanL1toL2(projectId, hour * 3600L);
                int d2 = distillService.scanL2toL3(projectId, hour * 3600L);
                if (d1 + d2 > 0) {
                    AiCallContext.recordStep(projectId, "memory_manage", "distill",
                            "跨层蒸馏：L1→L2 " + d1 + " 条 / L2→L3 " + d2 + " 条 游戏日=" + gameDay,
                            System.currentTimeMillis() - start);
                }
                steps++;
            } catch (Exception e) {
                log.warn("[记忆批量巩固] 跨层蒸馏失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        log.info("[记忆批量巩固] 完成：project={} 游戏日={} 执行 {} 步", projectId, gameDay, steps);
        return steps;
    }

    /**
     * 相关事实合并（确定性，零 AI）：同角色/项目级 L2 semantic 记忆两两 Dice 重叠 ≥ merge-threshold
     * 视为强重复——保留较新一条，旧条软删（deleted=1）+ 归档（reason=superseded，版本链可追溯）。
     * <p>设计 §3.3「相关事实合并（同主题重复条→合并为一条 semantic + supersedes 链）」；
     * 0.85 高阈值保守防误合（真人不会把「同村不同事」记成一条）。</p>
     *
     * @return 合并（软删归档）的旧条数
     */
    private int mergeDuplicateFacts(Long projectId) {
        List<ActorMemory> all = memoryRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                projectId, ActorMemoryTypes.TYPE_SEMANTIC, 0);
        if (all.size() < 2) {
            return 0;
        }
        // 按归属分组（characterRef null → 项目级分组键 "project"）
        Map<String, List<ActorMemory>> byOwner = new LinkedHashMap<>();
        for (ActorMemory m : all) {
            String key = m.getCharacterRef() == null || m.getCharacterRef().isBlank()
                    ? "project" : m.getCharacterRef();
            byOwner.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        int merged = 0;
        for (List<ActorMemory> group : byOwner.values()) {
            if (group.size() < 2) {
                continue;
            }
            // 按创建时间升序（旧在前）；两两比较
            group.sort((a, b) -> {
                LocalDateTime ta = a.getCreatedAt() == null ? LocalDateTime.MIN : a.getCreatedAt();
                LocalDateTime tb = b.getCreatedAt() == null ? LocalDateTime.MIN : b.getCreatedAt();
                int c = ta.compareTo(tb);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId());
            });
            for (int i = 0; i < group.size(); i++) {
                ActorMemory older = group.get(i);
                if (older.getDeleted() != null && older.getDeleted() == 1) {
                    continue; // 已被合并/失效
                }
                for (int j = i + 1; j < group.size(); j++) {
                    ActorMemory newer = group.get(j);
                    if (newer.getDeleted() != null && newer.getDeleted() == 1) {
                        continue;
                    }
                    if (older.getContent() == null || newer.getContent() == null
                            || older.getContent().isBlank() || newer.getContent().isBlank()) {
                        continue;
                    }
                    if (MemoryDedup.overlapScore(older.getContent(), newer.getContent()) >= mergeThreshold) {
                        // 强重复：软删旧条 + 归档（版本链）
                        older.setDeleted(1);
                        older.setUpdatedAt(LocalDateTime.now());
                        memoryRepository.save(older);
                        if (archiveService != null) {
                            try {
                                archiveService.archiveL2Memory(older, "superseded");
                            } catch (Exception e) {
                                log.warn("[记忆批量巩固] 合并归档失败（隔离）: memory={} : {}",
                                        older.getId(), e.getMessage());
                            }
                        }
                        merged++;
                        break; // 旧条已合并，跳出内层
                    }
                }
            }
        }
        if (merged > 0) {
            log.info("[记忆批量巩固] 事实合并完成：project={} 合并 {} 条", projectId, merged);
        }
        return merged;
    }

    /**
     * 批量巩固 cron 兜底（真实时间，默认每 6 小时）：覆盖引擎长暂停/低负载场景——引擎未推进到
     * 游戏日边界时也能定期巩固；按项目遍历，单项目失败隔离。幂等（各动作无副作用，编年史补漏
     * 有当日 daily 幂等键）。
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.memory.consolidation.batch-interval-ms:21600000}")
    public void batchConsolidateCron() {
        if (!enabled || !batchCronEnabled || projectRepository == null) {
            return;
        }
        List<ActorProject> projects;
        try {
            projects = projectRepository.findAll();
        } catch (Exception e) {
            log.warn("[记忆批量巩固] cron 兜底遍历项目失败（跳过本轮）: {}", e.getMessage());
            return;
        }
        for (ActorProject p : projects) {
            if (p.getId() == null) {
                continue;
            }
            // 世界运行时门控：未运行世界跳过批量巩固（世界静止=数据静止，
            // 不为其执行记忆蒸馏/批量巩固/L3 核心自编辑）
            if (runtimeGate != null && !runtimeGate.isActive(p.getId())) {
                log.debug("[记忆批量巩固] cron 兜底跳过（世界未运行）: project={}", p.getId());
                continue;
            }
            try {
                batchConsolidate(p.getId(), null);
            } catch (Exception e) {
                log.warn("[记忆批量巩固] cron 兜底单项目失败（隔离继续）: project={} : {}",
                        p.getId(), e.getMessage());
            }
        }
    }

    /**
     * 统一定时维护（统一编排层：L1 维护 + 归档维护，单步失败隔离）。
     * <p>注意：本方法不再加 @Scheduled——统一调度由既有各服务独立 @Scheduled 承担
     * （避免双调度重复执行），本方法为「手动/调试统一触发」入口 + 编排语义；
     * 若用户需要统一节奏，可经配置关闭各服务独立调度、由外部定时触发本方法。
     * 设计 §10「后台巩固管理器不阻塞主流程」由各服务既有的异步执行器承担。</p>
     *
     * @return 本次维护执行的步骤数（观测用）
     */
    public int maintenance() {
        if (!enabled || !maintenanceEnabled) {
            return 0;
        }
        int steps = 0;
        // 世界运行时门控：统一维护只处理运行中世界（gate 未装配=不过滤）
        java.util.Collection<Long> active = activeProjectIds();
        // ① L1 维护：TTL 清理 + 巩固扫描（三路触发之一；active=null 走无参版本，语义=不过滤）
        if (workingService != null) {
            long start = System.currentTimeMillis();
            try {
                int expired = active == null
                        ? workingService.cleanupExpired() : workingService.cleanupExpired(active);
                int swept = active == null
                        ? workingService.consolidateStaleSweep() : workingService.consolidateStaleSweep(active);
                AiCallContext.recordStep(null, "memory_manage", "working_maintenance",
                        "L1 维护：TTL 过期=" + expired + " 巩固扫描提升=" + swept,
                        System.currentTimeMillis() - start);
                steps++;
            } catch (Exception e) {
                log.warn("[记忆巩固编排] L1 维护失败（隔离继续）: {}", e.getMessage());
            }
        }
        // ② 归档维护：超龄扫描 + 保留滚动 + 二次清理（按项目遍历，委托 MemoryArchiveService 语义）
        if (archiveService != null) {
            long start = System.currentTimeMillis();
            try {
                int scanned = 0;
                int retained = 0;
                int cleaned = 0;
                List<ActorProject> projects = projectRepository == null ? List.of() : projectRepository.findAll();
                for (ActorProject p : projects) {
                    if (p.getId() == null) {
                        continue;
                    }
                    // 世界运行时门控：未运行世界跳过归档维护
                    if (runtimeGate != null && !runtimeGate.isActive(p.getId())) {
                        continue;
                    }
                    try {
                        scanned += archiveService.scanAgeArchiving(p.getId());
                    } catch (Exception e) {
                        log.warn("[记忆巩固编排] 归档超龄扫描失败（隔离）: project={} : {}", p.getId(), e.getMessage());
                    }
                    try {
                        retained += archiveService.enforceRetention(p.getId());
                    } catch (Exception e) {
                        log.warn("[记忆巩固编排] 归档保留滚动失败（隔离）: project={} : {}", p.getId(), e.getMessage());
                    }
                    try {
                        cleaned += archiveService.cleanupArchive(p.getId());
                    } catch (Exception e) {
                        log.warn("[记忆巩固编排] 归档二次清理失败（隔离）: project={} : {}", p.getId(), e.getMessage());
                    }
                }
                AiCallContext.recordStep(null, "memory_manage", "archive_maintenance",
                        "归档维护：超龄扫描=" + scanned + " 保留滚动=" + retained + " 二次清理=" + cleaned,
                        System.currentTimeMillis() - start);
                steps++;
            } catch (Exception e) {
                log.warn("[记忆巩固编排] 归档维护失败（整体隔离）: {}", e.getMessage());
            }
        }
        if (steps > 0) {
            log.info("[记忆巩固编排] 统一维护完成：执行 {} 步", steps);
        }
        return steps;
    }

    /**
     * 运行中项目 ID 集合（世界运行时门控过滤依据；gate/projectRepository 未装配返回 null=不过滤）。
     *
     * @return 运行中项目 ID 集合（null=不过滤）
     */
    private java.util.Collection<Long> activeProjectIds() {
        if (runtimeGate == null || projectRepository == null) {
            return null;
        }
        java.util.Set<Long> active = new java.util.HashSet<>();
        try {
            for (ActorProject p : projectRepository.findAll()) {
                if (p.getId() != null && runtimeGate.isActive(p.getId())) {
                    active.add(p.getId());
                }
            }
        } catch (Exception e) {
            log.warn("[记忆巩固编排] 运行中项目枚举失败（本轮不过滤）: {}", e.getMessage());
            return null;
        }
        return active;
    }
}
