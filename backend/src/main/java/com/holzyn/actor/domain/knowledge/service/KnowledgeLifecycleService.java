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

package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识层生命周期服务（P1 ②-2，Evolve/T4 ZenBrain 对齐）——知识文档「获取→巩固→衰减」状态机。
 * <p><b>状态机（用户 Q1 确认：四态）</b>：hot（活跃：新写入/高频引用）→ warm（常规）→
 * stale（沉寂：长期未引用，检索命中标注「可能过时」）→ archived（归档：过期，不再参与检索，
 * 管理列表可见可还原）。对齐记忆层既有遗忘机制（MemoryArchiveService 超龄判定语义）。</p>
 * <p><b>驱动（用户 Q2 确认：定期扫描 + 引用率驱动）</b>：引用率 = {@code access_count}
 * （检索命中计数，{@link KnowledgeRetrievalService} 命中时经 {@link #recordAccess} 累计）+
 * {@code last_access_at}（最后被引用时间，兜底 {@code created_at}）；按<b>游戏日</b>差值判定
 * （世界时钟换算，对齐记忆层 staleByGameDays——世界冻结则知识不衰减）。阈值：
 * {@code hot-days}=3（超 3 游戏日未引用 hot→warm）、{@code stale-days}=7（warm→stale）、
 * {@code archive-days}=14（stale→archived）。</p>
 * <p><b>触发（用户 Q2 确认）</b>：{@code @Scheduled} 定期扫描全项目（对齐
 * {@code MemoryArchiveService.maintenance} 模式，默认 6 小时）+ 手动端点（冒烟/运维）。</p>
 * <p><b>访问复活</b>：{@link #recordAccess} 时刷新引用时间并计数，stale 文档被再次引用 → 复活 warm
 * （下次评估自然回 hot）——「被需要即复生」。</p>
 * <p><b>置信度</b>：{@code confidence} 0~1（默认 0.5）；衰减每级 -0.1（下限 0.1）；
 * ②-3 记忆-知识桥接确认写入时提升（{@link KnowledgeBridgeService}）；供复核/失败驱动更新消费。</p>
 * <p><b>依赖注入约定</b>：构造器=纯数据仓库（可单测直造）；worldClock 字段注入（null=未装配
 * [单测]跳过游戏日换算——不评估，对齐 MemoryArchiveService 风格）。</p>
 * <p>设计依据：《调研报告》②-2（知识文档增状态机 hot/warm/stale/archived：引用率/访问频率驱动
 * 衰减；反思/失败样本可提升知识置信度；对齐记忆层既有遗忘机制）；Evolve（R6）+ T2/T4。</p>
 */
@Slf4j
@Service
public class KnowledgeLifecycleService {

    /** 生命周期状态：活跃（新写入/高频引用）。 */
    public static final String STATE_HOT = "hot";
    /** 生命周期状态：常规（进入常规区间）。 */
    public static final String STATE_WARM = "warm";
    /** 生命周期状态：沉寂（长期未引用，检索标注「可能过时」）。 */
    public static final String STATE_STALE = "stale";
    /** 生命周期状态：归档（过期，不参与检索；管理列表可见可手动还原）。 */
    public static final String STATE_ARCHIVED = "archived";

    /** 全部状态（校验用）。 */
    static final List<String> ALL_STATES = List.of(STATE_HOT, STATE_WARM, STATE_STALE, STATE_ARCHIVED);

    /** 每级衰减的置信度损失（下限 {@code MIN_CONFIDENCE}）。 */
    private static final double CONFIDENCE_DECAY_STEP = 0.1;
    /** 置信度下限。 */
    private static final double MIN_CONFIDENCE = 0.1;

    /** 知识生命周期开关（总开关；false=不评估不计数，检索行为完全回退现状）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.enabled:true}")
    private boolean enabled = true;

    /** 定期扫描开关（对齐 MemoryArchiveService age-scan-enabled）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.scan-enabled:true}")
    private boolean scanEnabled = true;

    /** 定期扫描间隔（真实毫秒，默认 21600000=6 小时，对齐记忆归档维护周期）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.maintenance-interval-ms:21600000}")
    private long maintenanceIntervalMs = 21_600_000L;

    /** hot→warm 阈值（距上次引用超 N 游戏日）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.hot-days:3}")
    private int hotDays = 3;

    /** warm→stale 阈值（距上次引用超 N 游戏日）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.stale-days:7}")
    private int staleDays = 7;

    /** stale→archived 阈值（距上次引用超 N 游戏日）。 */
    @Value("${holzyn.actor.knowledge.lifecycle.archive-days:14}")
    private int archiveDays = 14;

    /** 知识文档仓库（评估/计数）。 */
    private final ActorKnowledgeDocRepository docRepository;

    /** 项目仓库（全项目扫描遍历用）。 */
    private final ActorProjectRepository projectRepository;

    /** 世界时钟（游戏日换算；null=未装配[单测]跳过评估）。 */
    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /**
     * 构造器（纯数据仓库，可单测直造；服务字段注入保持构造器不变）。
     */
    public KnowledgeLifecycleService(ActorKnowledgeDocRepository docRepository,
                                     ActorProjectRepository projectRepository) {
        this.docRepository = docRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * 记录一次检索引用（引用率驱动衰减的数据底座）。
     * <p><b>语义</b>：检索命中即引用——{@code access_count}+1、{@code last_access_at}=当前真实时间；
     * stale 文档被再次引用 → <b>复活</b> warm（下次评估按时间自然回 hot）。</p>
     * <p><b>事务</b>：短事务（无 AI，毫秒级；被高频检索调用也不跨锁存活）。</p>
     *
     * @param projectId 项目 ID（归属校验；空跳过）
     *
     * @param docIds 被引用文档 ID 集合（空跳过）
     *
     * @return 本次更新条数（0=开关关/无输入/仓库未装配）
     */
    @Transactional
    public int recordAccess(Long projectId, Collection<Long> docIds) {
        if (!enabled || projectId == null || docIds == null || docIds.isEmpty() || docRepository == null) {
            return 0;
        }
        List<ActorKnowledgeDoc> docs = docRepository.findAllById(docIds);
        if (docs.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (ActorKnowledgeDoc doc : docs) {
            if (doc == null || !projectId.equals(doc.getProjectId())) {
                continue;
            }
            doc.setAccessCount((doc.getAccessCount() == null ? 0 : doc.getAccessCount()) + 1);
            doc.setLastAccessAt(now);
            // 访问复活：stale → warm（下次评估按引用时间自然回 hot；archived 不自动复活）
            if (STATE_STALE.equals(doc.getLifecycleState())) {
                doc.setLifecycleState(STATE_WARM);
                doc.setConfidence(boostConfidence(doc.getConfidence()));
                log.info("[知识生命周期] 访问复活：doc={} stale→warm（被再次引用）", doc.getId());
            }
            n++;
        }
        docRepository.saveAll(docs);
        return n;
    }

    /**
     * 评估某项目全部知识文档的生命周期状态（衰减扫描核心，可单测）。
     * <p><b>判定</b>：距上次引用（last_access_at 兜底 created_at）的游戏日差 d——
     * d ≥ archive-days → archived；d ≥ stale-days → stale；d ≥ hot-days → warm；否则 hot。
     * archived 不参与自动迁移（手动还原）；世界时钟不可用/换算失败 → 跳过该文档（不误衰减）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 扫描统计（evaluated=评估数；migrated=迁移数；byState=各目标态迁移计数；失败数）
     */
    @Transactional
    public ScanResult scanProject(Long projectId) {
        if (!enabled || projectId == null || docRepository == null) {
            return ScanResult.empty();
        }
        List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
        Long nowGameDay = currentGameDay(projectId);
        Map<String, Integer> byState = new LinkedHashMap<>();
        int evaluated = 0;
        int migrated = 0;
        int skipped = 0;
        for (ActorKnowledgeDoc doc : docs) {
            if (doc == null) {
                continue;
            }
            // archived 不自动迁移（防已归档文档被评估反复「维持」；还原走手动）
            if (STATE_ARCHIVED.equals(doc.getLifecycleState())) {
                continue;
            }
            Long days = daysSinceAccess(projectId, doc, nowGameDay);
            if (days == null) {
                skipped++;
                continue;
            }
            String current = doc.getLifecycleState() == null ? STATE_HOT : doc.getLifecycleState();
            String target = evaluateState(days, hotDays, staleDays, archiveDays);
            evaluated++;
            if (!target.equals(current)) {
                migrate(doc, target);
                byState.merge(target, 1, Integer::sum);
                migrated++;
            } else {
                doc.setLastEvalAt(LocalDateTime.now());
            }
        }
        if (migrated > 0) {
            docRepository.saveAll(docs);
        }
        log.info("[知识生命周期] 扫描结束：project={} 评估={} 迁移={} 跳过={} byState={}",
                projectId, evaluated, migrated, skipped, byState);
        return new ScanResult(evaluated, migrated, skipped, byState);
    }

    /** 状态迁移（写目标态 + 置信度衰减 + 评估时间）。 */
    private void migrate(ActorKnowledgeDoc doc, String target) {
        String from = doc.getLifecycleState() == null ? STATE_HOT : doc.getLifecycleState();
        doc.setLifecycleState(target);
        doc.setConfidence(Math.max(MIN_CONFIDENCE,
                (doc.getConfidence() == null ? 0.5 : doc.getConfidence()) - CONFIDENCE_DECAY_STEP));
        doc.setLastEvalAt(LocalDateTime.now());
        log.info("[知识生命周期] 状态迁移：doc={} {}→{}（confidence={}）", doc.getId(), from, target, doc.getConfidence());
    }

    /**
     * 评估全部项目（维护任务/手动全量）。
     *
     * @return 各项目扫描统计
     */
    public Map<Long, ScanResult> scanAllProjects() {
        Map<Long, ScanResult> out = new LinkedHashMap<>();
        if (!enabled || projectRepository == null) {
            return out;
        }
        List<ActorProject> projects = projectRepository.findAll();
        for (ActorProject p : projects) {
            try {
                if (p != null && p.getId() != null) {
                    out.put(p.getId(), scanProject(p.getId()));
                }
            } catch (Exception e) {
                log.warn("[知识生命周期] 项目扫描失败（跳过）: project={} : {}", p == null ? "?" : p.getId(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * 定期维护扫描（对齐 MemoryArchiveService.maintenance：6 小时一次，全项目衰减评估）。
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.knowledge.lifecycle.maintenance-interval-ms:21600000}")
    public void maintenance() {
        if (!scanEnabled || !enabled) {
            return;
        }
        try {
            Map<Long, ScanResult> results = scanAllProjects();
            int total = results.values().stream().mapToInt(ScanResult::migrated).sum();
            log.info("[知识生命周期] 定期扫描完成：项目={} 迁移合计={}", results.size(), total);
        } catch (Exception e) {
            log.warn("[知识生命周期] 定期扫描异常（下一周期重试）: {}", e.getMessage());
        }
    }

    /**
     * 手动归档（管理端点/冒烟）。
     *
     * @param id 文档主键
     *
     * @return 1=已归档；0=不存在/已归档
     */
    @Transactional
    public int archiveDoc(Long id) {
        if (!enabled || id == null || docRepository == null) {
            return 0;
        }
        ActorKnowledgeDoc doc = docRepository.findById(id).orElse(null);
        if (doc == null || STATE_ARCHIVED.equals(doc.getLifecycleState())) {
            return 0;
        }
        migrate(doc, STATE_ARCHIVED);
        docRepository.save(doc);
        return 1;
    }

    /**
     * 手动还原（管理端点/冒烟）：archived → warm（还原后按引用时间自然演化）。
     *
     * @param id 文档主键
     *
     * @return 1=已还原；0=不存在/非 archived
     */
    @Transactional
    public int restoreDoc(Long id) {
        if (!enabled || id == null || docRepository == null) {
            return 0;
        }
        ActorKnowledgeDoc doc = docRepository.findById(id).orElse(null);
        if (doc == null || !STATE_ARCHIVED.equals(doc.getLifecycleState())) {
            return 0;
        }
        doc.setLifecycleState(STATE_WARM);
        doc.setConfidence(boostConfidence(doc.getConfidence()));
        doc.setLastEvalAt(LocalDateTime.now());
        docRepository.save(doc);
        return 1;
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 状态判定（静态纯逻辑，可单测）：距上次引用游戏日差 d → 目标态。
     * 阈值保护：hot/stale/archive 天数 ≤0 时按 1 处理（防除零/负窗口误迁移）。
     *
     * @param days 距上次引用的游戏日差（≥0）
     *
     * @param hotDays hot→warm 阈值
     *
     * @param staleDays warm→stale 阈值
     *
     * @param archiveDays stale→archived 阈值
     *
     * @return 目标状态
     */
    static String evaluateState(long days, int hotDays, int staleDays, int archiveDays) {
        int h = Math.max(1, hotDays);
        int s = Math.max(h + 1, staleDays);
        int a = Math.max(s + 1, archiveDays);
        if (days >= a) {
            return STATE_ARCHIVED;
        }
        if (days >= s) {
            return STATE_STALE;
        }
        if (days >= h) {
            return STATE_WARM;
        }
        return STATE_HOT;
    }

    /** 置信度提升（桥接确认/访问复活/手动还原；上限 1.0）。 */
    static double boostConfidence(Double confidence) {
        return Math.min(1.0, (confidence == null ? 0.5 : confidence) + CONFIDENCE_DECAY_STEP);
    }

    // ==================== 私有辅助 ====================

    /** 当前游戏日（世界时钟不可用返回 null——不评估，防误衰减）。 */
    private Long currentGameDay(Long projectId) {
        try {
            if (worldClockService == null) {
                return null;
            }
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            return sec == null ? null : Math.floorDiv(sec, 86400L);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 距上次引用（last_access_at；从未被引用的文档不衰减）的游戏日差。
     * <p><b>换算语义</b>：{@code 引擎游戏秒 - 真实流逝秒 × 速率} 直算（不依赖
     * {@code gameHourAt} 的「早于世界起点 clamp 到 0」语义——存量文档 createdAt/last_access_at
     * 常早于世界起点，clamp 会把所有早期时间等效成同一游戏日差导致衰减失真）；允许结果为负
     * （早于世界起点 = 更早游戏日），差值按 {@code floorDiv} 取整。</p>
     * <p><b>从未被引用语义</b>：last_access_at 为 null 且 access_count 为 0 → 返回 0
     * （视为 hot 不衰减——「从未被用过的知识谈不上过时」；也避免存量项目文档按 createdAt
     * 批量误迁移）。异常数据（access_count&gt;0 但 last_access_at null）→ createdAt 兜底。</p>
     * 时钟/速率缺失或换算失败 → null（跳过该文档，不误衰减）。
     */
    private Long daysSinceAccess(Long projectId, ActorKnowledgeDoc doc, Long nowGameDay) {
        if (nowGameDay == null || worldClockService == null) {
            return null;
        }
        try {
            if (doc.getLastAccessAt() == null
                    && (doc.getAccessCount() == null || doc.getAccessCount() == 0)) {
                return 0L; // 从未被引用：hot，不衰减
            }
            LocalDateTime base = doc.getLastAccessAt() != null ? doc.getLastAccessAt() : doc.getCreatedAt();
            if (base == null) {
                return null;
            }
            Long engine = worldClockService.currentEngineGameSecond(projectId);
            Integer rate = worldClockService.rateOf(projectId);
            if (engine == null || rate == null || rate <= 0) {
                return null;
            }
            long elapsed = java.time.Duration.between(base, LocalDateTime.now()).getSeconds();
            long secAt = engine - Math.max(0L, elapsed) * rate; // 允许负（早于世界起点）
            long baseDay = Math.floorDiv(secAt, 86400L);
            return Math.max(0L, nowGameDay - baseDay);
        } catch (Exception e) {
            return null;
        }
    }

    /** 单项目扫描统计。 */
    public record ScanResult(int evaluated, int migrated, int skipped, Map<String, Integer> byState) {
        static ScanResult empty() {
            return new ScanResult(0, 0, 0, new LinkedHashMap<>());
        }
    }
}
