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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.memory.vo.MemoryWorkingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L1 短期（工作记忆）服务。
 * <p>职责：落地设计 §5（L1 工作记忆）——四类工作项（draft 待复核草稿 / task 进行中任务 /
 * context 上下文快照 / perception 感知快照）写入、会话热缓冲注入、<b>会话结束双缓冲巩固</b>
 * （L1→L2，source=consolidation，对齐 arXiv 2603.07670 双缓冲巩固）、TTL 过期清理、
 * 定时巩固扫描（防工作记忆表膨胀）、工作记忆列表 API。</p>
 * <p>关键语义（与 AgentMemoryService 的边界）：本服务只依赖 L1/L2 仓库与静态去重工具
 * （{@link MemoryDedup}），<b>不含 AI 调用</b>；对话抽取（AI 产出草稿）与会话摘要（直写 L2）
 * 由 AgentMemoryService 编排调用本服务；本服务内的定时维护（{@link #maintenance()}）
 * 为独立调度（自有开关 {@code maintenance-enabled}），不动 EngineScheduler 统一调度壳。</p>
 * <p>巩固提升规则：draft→L2 semantic（重要度=importance_hint）；task→L2 semantic、
 * context/perception→L2 episodic（重要度≥ {@code promote-importance-threshold} 才提升，
 * 否则直接过期）；全部经与 L2 既有记忆去重（命中则不重复写、仍标记 consolidated）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.1/§5/§10。</p>
 */
@Slf4j
@Service
public class MemoryWorkingService {

    // ==================== 工作项类型 / 状态 / 会话 key 常量（对齐设计 §4.1/§5.1） ====================

    /** 待复核草稿（对话抽取产出，双缓冲巩固的输入） */
    public static final String ITEM_DRAFT = "draft";
    /** 进行中任务/当前关注点 */
    public static final String ITEM_TASK = "task";
    /** 上下文快照（感知要点、情境知识命中摘要） */
    public static final String ITEM_CONTEXT = "context";
    /** 当前感知快照（所在地/可见事件摘要） */
    public static final String ITEM_PERCEPTION = "perception";

    /** 状态：热（进行中） */
    public static final String STATUS_ACTIVE = "active";
    /** 状态：已巩固进 L2 */
    public static final String STATUS_CONSOLIDATED = "consolidated";
    /** 状态：已过期 */
    public static final String STATUS_EXPIRED = "expired";

    /** 非会话来源（task/context/perception）的 session_key */
    public static final String SESSION_AUTO = "auto";

    /**
     * L1 短期事件记忆 item_type（短时经历记忆走模糊引擎生命周期，
     * 不参与工作项 TTL/巩固——引用 L1FuzzificationService.ITEM_SHORT_TERM 防字符串漂移）
     */
    public static final String ITEM_SHORT_TERM = com.holzyn.actor.domain.memory.service.L1FuzzificationService.ITEM_SHORT_TERM;

    /** 感知/上下文单槽：每角色同类型仅保留最近 1 条 active（替换语义防膨胀） */
    private static final int SINGLE_SLOT_LIMIT = 1;

    // ==================== 配置（application.yml，代码默认值对齐） ====================

    /** L1 工作记忆总开关 */
    @Value("${holzyn.actor.memory.working.enabled:true}")
    private boolean enabled;

    /** 工作项 TTL（小时，默认 48：远大于 30 分钟会话超时，保证会话结束巩固前不提前过期） */
    @Value("${holzyn.actor.memory.working.ttl-hours:48}")
    private int ttlHours;

    /** 会话热缓冲注入条数上限（默认 5，对齐设计 §9.1） */
    @Value("${holzyn.actor.memory.working.hot-top-k:5}")
    private int hotTopK;

    /** task/context/perception 提升进 L2 的重要度阈值（默认 3；低于该值直接过期） */
    @Value("${holzyn.actor.memory.working.promote-importance-threshold:3}")
    private int promoteImportanceThreshold;

    /** 每角色 active task 上限（默认 3：超限淘汰最旧 task） */
    @Value("${holzyn.actor.memory.working.max-task-active:3}")
    private int maxTaskActive;

    /** 定时维护开关（TTL 清理 + 巩固扫描，独立于 EngineScheduler） */
    @Value("${holzyn.actor.memory.working.maintenance-enabled:true}")
    private boolean maintenanceEnabled;

    /** 定时维护间隔（毫秒，默认 30 分钟） */
    @Value("${holzyn.actor.memory.working.maintenance-interval-ms:1800000}")
    private long maintenanceIntervalMs;

    /** 巩固扫描的超龄阈值（真实分钟，默认 120：active 超过该值未巩固即由扫描提升/过期） */
    @Value("${holzyn.actor.memory.working.sweep-min-age-minutes:120}")
    private int sweepMinAgeMinutes;

    /** 已终结（consolidated/expired）工作项物理清理保留期（天，默认 7） */
    @Value("${holzyn.actor.memory.working.retain-days:7}")
    private int retainDays;

    /** 去重阈值（复用 holzyn.actor.memory.dedup-threshold 语义，默认 0.6） */
    @Value("${holzyn.actor.memory.working.dedup-threshold:0.6}")
    private double dedupThreshold;

    /** L1 工作记忆仓库 */
    private final ActorMemoryWorkingRepository workingRepository;

    /** L2 长期记忆仓库（巩固提升写 L2 + 去重读 L2） */
    private final ActorMemoryRepository memoryRepository;

    /**
     * 构造器（仅仓库依赖，无 AI——L1 服务保持纯数据/调度职责）。
     */
    public MemoryWorkingService(ActorMemoryWorkingRepository workingRepository,
                                ActorMemoryRepository memoryRepository) {
        this.workingRepository = workingRepository;
        this.memoryRepository = memoryRepository;
    }

    /**
     * 世界运行时门控（用户需求：启动全静止，进入沙盘才运行；null=未装配[单测]不过滤——
     * 未运行世界的 L1 工作项不参与定时 TTL 清理/巩固扫描，世界静止=数据静止）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService runtimeGate;

    /** 项目仓库（定时维护按运行中项目过滤；null=未装配[单测]不过滤） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.project.repository.ActorProjectRepository projectRepository;

    // ==================== 写入（四类工作项） ====================

    /**
     * 写一条待复核草稿（draft，对话抽取产出；双缓冲巩固的输入）。
     * <p>同角色同会话已有 active 草稿做文本去重（Dice，复用 {@link MemoryDedup}）；
     * 去重命中返回 false（调用方不再计入新增）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（统一身份 {@code npc:{actor_profile.id}}）
     *
     * @param sessionKey 来源会话 key（conversation_id 字符串）
     *
     * @param content 草稿内容
     *
     * @param importanceHint 巩固重要度建议（1~5）
     *
     * @return true=已写入；false=开关关/参数非法/重复
     */
    @Transactional
    public boolean writeDraft(Long projectId, String characterRef, String sessionKey,
                              String content, Integer importanceHint) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || sessionKey == null || sessionKey.isBlank()
                || content == null || content.isBlank()) {
            return false;
        }
        String ref = characterRef.trim();
        // 同会话已有 active 草稿去重（防多轮重复草稿）
        List<ActorMemoryWorking> activeDrafts = workingRepository
                .findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, ref, STATUS_ACTIVE, 0);
        if (!activeDrafts.isEmpty()) {
            List<String> existing = activeDrafts.stream()
                    .filter(d -> sessionKey.equals(d.getSessionKey()))
                    .map(ActorMemoryWorking::getContent).toList();
            if (MemoryDedup.isDuplicate(content, existing, dedupThreshold)) {
                log.debug("[L1 工作记忆] draft 去重命中，跳过：project={} ref={} session={}", projectId, ref, sessionKey);
                return false;
            }
        }
        workingRepository.save(buildItem(projectId, ref, sessionKey, ITEM_DRAFT, content,
                ActorMemoryTypes.SOURCE_DIALOG, clampImportance(importanceHint)));
        log.debug("[L1 工作记忆] 写入 draft：project={} ref={} session={} 重要度={}",
                projectId, ref, sessionKey, clampImportance(importanceHint));
        return true;
    }

    /**
     * 写一条进行中任务/关注点（task，计划/行动/手动来源）。
     * <p>每角色 active task 超 {@code max-task-active} 时淘汰最旧一条（工作项有界防膨胀）。</p>
     * <p><b>世界运行抓修（同内容幂等）</b>：原实现对同内容不做任何判定，AI 连续多轮给出
     * 同一行动时每轮都新增一行——实测一次 15 分钟世界运行里「当前行动：披衣持灯，前往灵溪河畔查看
     * 水位与沿岸湿气情况（目标：灵溪河畔）」在同一角色下重复 4 行，task 行数 23/54（其中 12 行是
     * 淘汰后的 expired 垃圾）；注入上下文里同一句"当前行动"反复出现，既污染 prompt 又让
     * {@code actor_memory_working} 无意义膨胀。改为：<b>最新 task 内容完全相同 → 视为"同一件事仍在
     * 进行"，只刷新过期时间，不新增行</b>（与 context/perception 单槽幂等同一口径）。</p>
     *
     * @return true=已写入/已幂等刷新
     */
    @Transactional
    public boolean writeTask(Long projectId, String characterRef, String content, Integer importanceHint) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return false;
        }
        String ref = characterRef.trim();
        List<ActorMemoryWorking> activeTasks = workingRepository
                .findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, ref, ITEM_TASK, STATUS_ACTIVE, 0);
        // 同内容幂等（列表已按 createdAt 降序，get(0)=最新一条）
        if (!activeTasks.isEmpty() && content.trim().equals(activeTasks.get(0).getContent())) {
            ActorMemoryWorking newest = activeTasks.get(0);
            newest.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, ttlHours)));
            workingRepository.save(newest);
            log.debug("[L1 工作记忆] task 同内容幂等刷新：project={} ref={} id={}", projectId, ref, newest.getId());
            return true;
        }
        if (activeTasks.size() >= maxTaskActive) {
            ActorMemoryWorking oldest = activeTasks.get(activeTasks.size() - 1);
            oldest.setStatus(STATUS_EXPIRED);
            workingRepository.save(oldest);
            log.debug("[L1 工作记忆] task 超上限，淘汰最旧：project={} ref={} id={}", projectId, ref, oldest.getId());
        }
        workingRepository.save(buildItem(projectId, ref, SESSION_AUTO, ITEM_TASK, content,
                ActorMemoryTypes.SOURCE_ACTION, clampImportance(importanceHint)));
        return true;
    }

    /**
     * 写一条上下文快照（context，感知要点/情境知识命中摘要；单槽替换防膨胀）。
     *
     * @return true=已写入/已更新
     */
    @Transactional
    public boolean writeContext(Long projectId, String characterRef, String content, Integer importanceHint) {
        return writeSingleSlot(projectId, characterRef, ITEM_CONTEXT, content, importanceHint);
    }

    /**
     * 写一条当前感知快照（perception，所在地/可见事件摘要；单槽替换防膨胀）。
     *
     * @return true=已写入/已更新
     */
    @Transactional
    public boolean writePerception(Long projectId, String characterRef, String content, Integer importanceHint) {
        return writeSingleSlot(projectId, characterRef, ITEM_PERCEPTION, content, importanceHint);
    }

    /**
     * 单槽工作项写入（context/perception 共用）：同角色同类型已存在 active 项时原地更新
     * （内容/来源/重要度/过期时间刷新，不产生新行），否则新建——保证每角色该类恒 ≤1 条。
     */
    private boolean writeSingleSlot(Long projectId, String characterRef, String itemType,
                                    String content, Integer importanceHint) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()
                || content == null || content.isBlank()) {
            return false;
        }
        String ref = characterRef.trim();
        List<ActorMemoryWorking> existing = workingRepository
                .findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, ref, itemType, STATUS_ACTIVE, 0);
        ActorMemoryWorking slot = existing.isEmpty() ? null : existing.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (slot != null) {
            // 内容相同：仅刷新过期时间（幂等，不产生更新噪音）
            if (content.equals(slot.getContent())) {
                slot.setExpiresAt(now.plusHours(Math.max(1, ttlHours)));
                workingRepository.save(slot);
                return true;
            }
            slot.setContent(content);
            slot.setSource(ActorMemoryTypes.SOURCE_PERCEPTION);
            slot.setImportanceHint(clampImportance(importanceHint));
            slot.setExpiresAt(now.plusHours(Math.max(1, ttlHours)));
            slot.setUpdatedAt(now);
            workingRepository.save(slot);
            return true;
        }
        workingRepository.save(buildItem(projectId, ref, SESSION_AUTO, itemType, content,
                ActorMemoryTypes.SOURCE_PERCEPTION, clampImportance(importanceHint)));
        return true;
    }

    /**
     * 组装工作项实体（统一填 TTL 过期时间）。
     */
    private ActorMemoryWorking buildItem(Long projectId, String characterRef, String sessionKey,
                                         String itemType, String content, String source, int importanceHint) {
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setProjectId(projectId);
        w.setCharacterRef(characterRef);
        w.setSessionKey(sessionKey);
        w.setItemType(itemType);
        w.setContent(content);
        w.setSource(source);
        w.setStatus(STATUS_ACTIVE);
        w.setImportanceHint(importanceHint);
        w.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, ttlHours)));
        return w;
    }

    // ==================== 会话热缓冲注入（read：对话 + 感知，设计 §9.1/§9.3） ====================

    /**
     * 查某角色 active 工作项（新→旧，注入与列表用）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色
     *
     * @param limit 条数上限（≤0 用配置默认）
     *
     * @return active 工作项列表
     */
    public List<ActorMemoryWorking> listActive(Long projectId, String characterRef, int limit) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return List.of();
        }
        List<ActorMemoryWorking> all = workingRepository
                .findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                        projectId, characterRef.trim(), STATUS_ACTIVE, 0);
        // ：short_term 行不参与工作项热缓冲注入（归模糊引擎/路由管）
        List<ActorMemoryWorking> workingItems = all.stream()
                .filter(w -> !ITEM_SHORT_TERM.equals(w.getItemType())).toList();
        int n = limit > 0 ? limit : hotTopK;
        return workingItems.size() <= n ? workingItems : new ArrayList<>(workingItems.subList(0, n));
    }

    /**
     * 生成「【当前情境】L1 热缓冲」注入片段（默认条数=配置 hot-top-k）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色
     *
     * @return 注入文本（无 active 工作项/开关关返回空串）
     */
    public String injectWorkingSegment(Long projectId, String characterRef) {
        return injectWorkingSegment(projectId, characterRef, hotTopK);
    }

    /**
     * 生成「【当前情境】L1 热缓冲」注入片段（可指定条数）。
     * <p>格式：<pre>
     * 【当前情境】以下是该角色当前进行中的关注项（工作记忆），回答时自然参考：
     * - [草稿] {content}
     * - [任务] {content}
     * </pre>
     * 各工作项按类型标注；无内容返回空串（调用方跳过注入，保持旧行为）。</p>
     */
    public String injectWorkingSegment(Long projectId, String characterRef, int limit) {
        if (!enabled) {
            return "";
        }
        List<ActorMemoryWorking> items = listActive(projectId, characterRef, limit);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【当前情境】以下是该角色当前进行中的关注项（工作记忆），回答时自然参考：\n");
        for (ActorMemoryWorking w : items) {
            sb.append("- [").append(itemTypeLabel(w.getItemType())).append("] ").append(w.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 工作项类型显示名（注入标签）。
     */
    private static String itemTypeLabel(String itemType) {
        if (ITEM_TASK.equals(itemType)) {
            return "任务";
        }
        if (ITEM_CONTEXT.equals(itemType)) {
            return "上下文";
        }
        if (ITEM_PERCEPTION.equals(itemType)) {
            return "感知";
        }
        return "草稿";
    }

    // ==================== 会话结束双缓冲巩固（manage：L1→L2，设计 §5.3/§10） ====================

    /**
     * 会话结束巩固：把某会话 key 下全部 active 工作项按规则提升进 L2（source=consolidation）
     * 或标记过期，防止工作记忆滞留。
     *
     * @param sessionKey 会话 key（conversation_id 字符串）
     *
     * @return 提升进 L2 的条数
     */
    @Transactional
    public int consolidateSession(String sessionKey) {
        if (!enabled || sessionKey == null || sessionKey.isBlank()) {
            return 0;
        }
        List<ActorMemoryWorking> items = workingRepository
                .findBySessionKeyAndStatusAndDeletedOrderByCreatedAtDesc(sessionKey, STATUS_ACTIVE, 0);
        return consolidateItems(items);
    }

    /**
     * 定时巩固扫描（三路触发之一）：把「active 超 {@code sweep-min-age-minutes} 未巩固」的工作项
     * 提升/过期——覆盖会话结束后长期无人回访、未触发懒检查/归档的滞留项（防工作记忆表膨胀）。
     *
     * @return 提升进 L2 的条数
     */
    @Transactional
    public int consolidateStaleSweep() {
        return consolidateStaleSweep(null);
    }

    /**
     * 定时巩固扫描（按运行中项目过滤版）：只提升/过期运行中世界的滞留工作项——
     * 未运行世界的工作项不被定时维护触碰（世界静止=数据静止）。
     *
     * @param activeProjectIds 运行中项目 ID 集合（null=不过滤）
     *
     * @return 提升进 L2 的条数
     */
    @Transactional
    public int consolidateStaleSweep(java.util.Collection<Long> activeProjectIds) {
        if (!enabled) {
            return 0;
        }
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, sweepMinAgeMinutes));
        List<ActorMemoryWorking> stale = workingRepository
                .findByStatusAndDeletedAndCreatedAtBefore(STATUS_ACTIVE, 0, threshold).stream()
                .filter(w -> allowedByRuntime(w.getProjectId(), activeProjectIds))
                .toList();
        return consolidateItems(stale);
    }

    /**
     * 项目级批量巩固：把某项目全部 active 工作项提升/过期。
     * <p>区别于 {@link #consolidateStaleSweep}（按真实时间超龄扫描）：批量巩固按<b>游戏日边界</b>
     * 触发（世界引擎记忆周期），对齐「定时批量巩固=sleep-time compute」语义——不管是否超龄，
     * 每个游戏日把滞留工作项清一遍（会话结束巩固的兜底 + 防工作记忆表膨胀）。幂等：提升/过期后
     * status 变化，重复调用无副作用。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 提升进 L2 的条数
     */
    @Transactional
    public int consolidateProject(Long projectId) {
        if (!enabled || projectId == null) {
            return 0;
        }
        List<ActorMemoryWorking> items = workingRepository
                .findByProjectIdAndStatusAndDeleted(projectId, STATUS_ACTIVE, 0);
        return consolidateItems(items);
    }

    /**
     * 巩固一批工作项：逐条判定提升/过期，写 L2 或标记 status。
     * <p>提升规则（设计 §5.2/§5.3）：draft→L2 semantic（重要度=importance_hint，已过 AI 抽取，
     * 只做去重复核）；task/context/perception→重要度≥阈值才提升（task→semantic、context/perception→episodic），
     * 否则直接过期；与 L2 既有记忆去重命中→不重复写（仍标记 consolidated，防重复提升）。</p>
     */
    private int consolidateItems(List<ActorMemoryWorking> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ActorMemoryWorking w : items) {
            // ：short_term 行不走工作项双缓冲巩固（归模糊引擎生命周期：
            // 艾宾浩斯模糊 + 睡眠清洗 + 蒸馏；此处跳过防误提升/误过期）
            if (ITEM_SHORT_TERM.equals(w.getItemType())) {
                continue;
            }
            PromoteResult result;
            try {
                result = promoteToL2(w);
            } catch (Exception e) {
                log.warn("[L1 巩固] 工作项提升失败（按过期处理）: id={} : {}", w.getId(), e.getMessage());
                result = PromoteResult.REJECTED;
            }
            boolean ok = result != PromoteResult.REJECTED;
            w.setStatus(ok ? STATUS_CONSOLIDATED : STATUS_EXPIRED);
            w.setConsolidatedAt(ok ? now : null);
            w.setUpdatedAt(now);
            workingRepository.save(w);
            if (result == PromoteResult.PROMOTED) {
                promoted++;
            }
        }
        if (promoted > 0) {
            log.info("[L1 巩固] 完成：共 {} 条工作项，提升进 L2 {} 条", items.size(), promoted);
        }
        return promoted;
    }

    /**
     * 单条工作项巩固结果：PROMOTED=已写 L2；ALREADY_KNOWN=与 L2 既有记忆重复（标记已巩固，不重复写）；
     * REJECTED=重要度不足/无实质内容（直接过期）。
     */
    private enum PromoteResult {
        PROMOTED, ALREADY_KNOWN, REJECTED
    }

    /**
     * 单条工作项提升进 L2：判定 + 写 L2（source=consolidation）。
     *
     * @param w 工作项
     *
     * @return 巩固结果（见 {@link PromoteResult}）
     */
    private PromoteResult promoteToL2(ActorMemoryWorking w) {
        if (w.getContent() == null || w.getContent().isBlank()) {
            return PromoteResult.REJECTED;
        }
        int importance = clampImportance(w.getImportanceHint());
        String type;
        boolean mustPromote;
        if (ITEM_DRAFT.equals(w.getItemType())) {
            // 草稿已过 AI 抽取（memory_extract），复核仅做去重；重要度以抽取建议为准
            type = ActorMemoryTypes.TYPE_SEMANTIC;
            mustPromote = true;
        } else if (ITEM_TASK.equals(w.getItemType())) {
            type = ActorMemoryTypes.TYPE_SEMANTIC;
            mustPromote = importance >= promoteImportanceThreshold;
        } else if (ITEM_CONTEXT.equals(w.getItemType()) || ITEM_PERCEPTION.equals(w.getItemType())) {
            type = ActorMemoryTypes.TYPE_EPISODIC;
            mustPromote = importance >= promoteImportanceThreshold;
        } else {
            return PromoteResult.REJECTED;
        }
        if (!mustPromote) {
            log.debug("[L1 巩固] 工作项重要度不足，直接过期：id={} type={} 重要度={}", w.getId(), w.getItemType(), importance);
            return PromoteResult.REJECTED;
        }
        // 与 L2 既有记忆去重（命中→不重复写，视为已巩固）
        if (MemoryDedup.isDuplicate(w.getContent(), loadL2Contents(w.getProjectId(), w.getCharacterRef()), dedupThreshold)) {
            log.debug("[L1 巩固] 工作项与 L2 既有记忆重复，标记已巩固：id={}", w.getId());
            return PromoteResult.ALREADY_KNOWN;
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(w.getProjectId());
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        m.setCharacterId(NpcRefs.idOf(w.getCharacterRef()));
        m.setCharacterRef(w.getCharacterRef());
        m.setType(type);
        m.setContent(w.getContent());
        m.setImportance(importance);
        m.setSource(ActorMemoryTypes.SOURCE_CONSOLIDATION);
        memoryRepository.save(m);
        return PromoteResult.PROMOTED;
    }

    /**
     * 加载某角色 L2 既有记忆内容（巩固去重用；双轨并入仅 character_id 存量）。
     */
    private List<String> loadL2Contents(Long projectId, String characterRef) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<ActorMemory> byRef = memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                projectId, characterRef, 0);
        for (ActorMemory m : byRef) {
            if (m.getContent() != null) {
                out.add(m.getContent());
            }
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        Long charId = NpcRefs.idOf(characterRef);
        if (charId != null) {
            Set<Long> seen = new HashSet<>();
            for (ActorMemory m : byRef) {
                if (m.getId() != null) {
                    seen.add(m.getId());
                }
            }
            for (ActorMemory m : memoryRepository.findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(
                    projectId, charId, 0)) {
                if ((m.getId() == null || !seen.contains(m.getId())) && m.getContent() != null) {
                    out.add(m.getContent());
                }
            }
        }
        return out;
    }

    // ==================== TTL 清理 / 定时维护（设计 §5.2：TTL 过期 status=expired + 定期清理防膨胀） ====================

    /**
     * TTL 清理：expires_at 已过的 active 项 → status=expired；已终结项超保留期物理删除。
     * <p>世界运行时门控：无参版本=不过滤（单测/手动直调语义保持）。</p>
     *
     * @return 清理的 expired 项数
     */
    @Transactional
    public int cleanupExpired() {
        return cleanupExpired(null);
    }

    /**
     * TTL 清理（按运行中项目过滤版）：只处理属于运行中世界的行——未运行世界的工作项
     * 不被定时维护触碰（世界静止=数据静止）。
     *
     * @param activeProjectIds 运行中项目 ID 集合（null=不过滤）
     *
     * @return 清理的 expired 项数
     */
    @Transactional
    public int cleanupExpired(java.util.Collection<Long> activeProjectIds) {
        if (!enabled) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ActorMemoryWorking> expired = workingRepository
                .findByStatusAndDeletedAndExpiresAtBefore(STATUS_ACTIVE, 0, now).stream()
                .filter(w -> allowedByRuntime(w.getProjectId(), activeProjectIds))
                .toList();
        for (ActorMemoryWorking w : expired) {
            // ：short_term 行不走真实时间 TTL（归模糊引擎游戏时间生命周期）
            if (ITEM_SHORT_TERM.equals(w.getItemType())) {
                continue;
            }
            w.setStatus(STATUS_EXPIRED);
            w.setUpdatedAt(now);
            workingRepository.save(w);
        }
        LocalDateTime retainBefore = now.minusDays(Math.max(1, retainDays));
        List<ActorMemoryWorking> oldConsolidated = workingRepository
                .findByStatusAndDeletedAndUpdatedAtBefore(STATUS_CONSOLIDATED, 0, retainBefore).stream()
                .filter(w -> allowedByRuntime(w.getProjectId(), activeProjectIds))
                .toList();
        List<ActorMemoryWorking> oldExpired = workingRepository
                .findByStatusAndDeletedAndUpdatedAtBefore(STATUS_EXPIRED, 0, retainBefore).stream()
                .filter(w -> allowedByRuntime(w.getProjectId(), activeProjectIds))
                .toList();
        if (!oldConsolidated.isEmpty()) {
            workingRepository.deleteAll(oldConsolidated);
        }
        if (!oldExpired.isEmpty()) {
            workingRepository.deleteAll(oldExpired);
        }
        int cleaned = expired.size() + oldConsolidated.size() + oldExpired.size();
        if (cleaned > 0) {
            log.info("[L1 工作记忆] TTL 清理完成：过期标记 {} 条，物理清理终结项 {} 条（保留 {} 天）",
                    expired.size(), oldConsolidated.size() + oldExpired.size(), retainDays);
        }
        return cleaned;
    }

    /**
     * 定时维护（独立调度，不动 EngineScheduler）：TTL 清理 + 巩固扫描（三路触发之一）。
     * 单步失败不阻断另一步；开关关闭整体跳过。
     * <p>世界运行时门控：只处理运行中世界的工作项——未运行世界保持数据静止。</p>
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.memory.working.maintenance-interval-ms:1800000}")
    public void maintenance() {
        if (!maintenanceEnabled) {
            return;
        }
        gated("MemoryWorkingService.maintenance", this::maintainNow);
    }

    /** 维护体（经世界闸门之后才允许扫库/写库） */
    private void maintainNow() {
        java.util.Collection<Long> active = activeProjectIds();
        try {
            cleanupExpired(active);
        } catch (Exception e) {
            log.warn("[L1 工作记忆] 定时 TTL 清理失败（隔离继续）: {}", e.getMessage());
        }
        try {
            consolidateStaleSweep(active);
        } catch (Exception e) {
            log.warn("[L1 工作记忆] 定时巩固扫描失败（隔离继续）: {}", e.getMessage());
        }
    }

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：世界未挂载时维护任务<b>零 DB 写入、零 AI 调用</b>（AC12）。
     *
     * <p>未装配（单测直接 {@code new}）时退化为直接执行：既有测试断言的是"维护逻辑本身"，
     * 不该因为闸门存在而被跳过。字段注入 {@code required=false}，不改构造器（AGENTS §8.1.2）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /** 经世界闸门执行；未装配闸门（单测）时直接执行 */
    private void gated(String name, Runnable body) {
        if (worldGate == null) {
            body.run();
        } else {
            worldGate.tryMaintenance(name, body);
        }
    }

    /**
     * 运行中项目 ID 集合（世界运行时门控过滤依据）。
     *
     * @return 运行中项目 ID 集合（gate/projectRepository 未装配或枚举失败返回 null=不过滤）
     */
    private java.util.Collection<Long> activeProjectIds() {
        if (runtimeGate == null || projectRepository == null) {
            return null;
        }
        java.util.Set<Long> active = new HashSet<>();
        try {
            for (com.holzyn.actor.domain.project.entity.ActorProject p : projectRepository.findAll()) {
                if (p.getId() != null && runtimeGate.isActive(p.getId())) {
                    active.add(p.getId());
                }
            }
        } catch (Exception e) {
            log.warn("[L1 工作记忆] 运行中项目枚举失败（本轮不过滤）: {}", e.getMessage());
            return null;
        }
        return active;
    }

    /** 运行门控过滤：activeProjectIds=null（不过滤）或行归属项目在运行集合内才放行。 */
    private static boolean allowedByRuntime(Long projectId, java.util.Collection<Long> activeProjectIds) {
        return activeProjectIds == null || (projectId != null && activeProjectIds.contains(projectId));
    }

    // ==================== 工作记忆列表 API（read-only，供前端「工作记忆」分组） ====================

    /**
     * 工作记忆列表（角色级/项目级，status 可选，分页）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（可空=项目级工作项）
     *
     * @param status 状态过滤（可空=全部）
     *
     * @param page 页码（1 起）
     *
     * @param size 每页条数
     *
     * @return 分页 VO
     */
    public PageResult<MemoryWorkingVO> list(Long projectId, String characterRef, String status, int page, int size) {
        if (projectId == null) {
            return PageResult.of(List.of(), 0, page, size);
        }
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        String safeStatus = (status == null || status.isBlank()) ? null : status.trim();
        Page<ActorMemoryWorking> p;
        if (characterRef != null && !characterRef.isBlank()) {
            p = workingRepository.findByProjectAndRefOptionalStatus(
                    projectId, characterRef.trim(), safeStatus, PageRequest.of(safePage, safeSize));
        } else {
            p = workingRepository.findByProjectOptionalRefOptionalStatus(
                    projectId, safeStatus, PageRequest.of(safePage, safeSize));
        }
        List<MemoryWorkingVO> vos = p.getContent().stream().map(MemoryWorkingVO::of).toList();
        return PageResult.of(vos, p.getTotalElements(), page, safeSize);
    }

    // ==================== 私有辅助 ====================

    /**
     * 重要度钳制到 1~5（null 回退 1）。
     */
    private static int clampImportance(Integer importance) {
        if (importance == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, importance));
    }
}
