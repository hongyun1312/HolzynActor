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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryArchiveRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.memory.vo.ArchiveVO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 归档（冷存储）服务。
 * <p>职责：落地设计 §8（归档）与 §6.5（版本链）——<b>归档触发</b>（age 超龄自动扫描 /
 * superseded 覆盖版本链 / downgraded 反思降级 / evicted 预算淘汰[默认关] / manual 手动归档）、
 * <b>L2 版本链更新</b>（updateL2Memory：新条 version+1 + supersedes_id=旧条，旧条软删+归档 superseded）、
 * <b>还原</b>（restore：按 source_table 新建回来源表 + 标记 restored + 去重跳过）、
 * <b>检索/导出</b>（分页列表 + JSON/Markdown 双格式下载，按项目/角色）、
 * <b>保留策略</b>（每项目上限滚动软删 + 归档内软删超保留期物理清理）。</p>
 * <p>关键语义：归档<b>原文保留</b>（不摘要化，保 raw 记录）；<b>不参与日常注入</b>
 * （对话/决策/感知检索范围限定 L1+L2+L3，本服务仅按需检索/导出）。</p>
 * <p>超龄判定（对齐 §8.1 age）：重要度 ≥ {@code stale-importance-threshold}（默认 3，复用 R3 阈值）
 * 且 距上次复核超 {@code stale-after-game-days}（默认 3，复用 R3 阈值，last_access_at 优先/created_at 兜底）
 * 且 access_count ≤ {@code low-access-count}（默认 1，长期无访问）——仅角色级 semantic/episodic 参与
 * （项目级大事记与 reflection/plan 不参与超龄归档，防误删世界史/洞察）。</p>
 * <p>反思降级（对齐 §8.1 downgraded）：反思成功后其证据记忆中重要度 ≤ {@code downgrade-importance-threshold}
 * 的条目降级归档（证据已内化进洞察，原文归档保真）；高重要证据仍留 L2 参与检索。</p>
 * <p>预算淘汰（对齐 §8.1 evicted，默认关）：{@code evict-enabled=true} 时预算滚动淘汰的记忆额外写归档
 * （淘汰仍默认走软删，避免误归档高频低价值记忆）。</p>
 * <p>依赖注入约定：构造器=纯数据仓库（可单测直造）；worldClock/currentUser 字段注入（null 时跳过游戏日/
 * 归属校验，对齐 CoreMemoryService 风格）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.4/§6.5/§8/§10/§13。
 * 2026 联网调研对齐：Letta archival/recall 冷存储分层、Mem0 版本化 supersedes、记忆治理保留策略。</p>
 */
@Slf4j
@Service
public class MemoryArchiveService {

    // ==================== 归档原因 / 来源表常量（对齐设计 §4.4/§8.1） ====================

    /** 超龄归档（staleness 超期 + 长期无访问） */
    public static final String REASON_AGE = "age";
    /** 覆盖归档（新条 supersedes 旧条：同事实更新/反思版本化/核心块升级） */
    public static final String REASON_SUPERSEDED = "superseded";
    /** 反思降级（证据已内化进洞察，原文归档保真） */
    public static final String REASON_DOWNGRADED = "downgraded";
    /** 预算淘汰归档（默认关，淘汰默认仍走软删） */
    public static final String REASON_EVICTED = "evicted";
    /** 手动归档（用户在记忆页/管理端点手动归档） */
    public static final String REASON_MANUAL = "manual";

    /** 来源表：L2 长期记忆 */
    public static final String SOURCE_TABLE_MEMORY = "actor_memory";
    /** 来源表：L3 核心块（九模块规范化重构 表名 actor_core_memory → actor_memory_core，常量同步） */
    public static final String SOURCE_TABLE_CORE = "actor_memory_core";
    /** 来源表：L1 工作记忆 */
    public static final String SOURCE_TABLE_WORKING = "actor_memory_working";

    /** 软删标记：正常 */
    private static final int NOT_DELETED = 0;
    /** 软删标记：已删 */
    private static final int DELETED = 1;
    /** 还原标记：未还原 */
    private static final int NOT_RESTORED = 0;
    /** 还原标记：已还原 */
    private static final int RESTORED = 1;

    // ==================== 配置（application.yml，代码默认值对齐） ====================

    /** 归档服务总开关 */
    @Value("${holzyn.actor.memory.archive.enabled:true}")
    private boolean enabled;

    /** 每项目归档保留上限（默认 5000，超限按「重要度低+旧」滚动软删；0=不限制） */
    @Value("${holzyn.actor.memory.archive.max-per-project:5000}")
    private int maxPerProject;

    /** 归档内软删（deleted=1）条目物理二次清理保留期（天，默认 90） */
    @Value("${holzyn.actor.memory.archive.retain-days:90}")
    private int retainDays;

    /** 定时维护总开关（超龄扫描 + 保留滚动 + 二次清理；独立于 EngineScheduler） */
    @Value("${holzyn.actor.memory.archive.maintenance-enabled:true}")
    private boolean maintenanceEnabled;

    /** 定时维护间隔（毫秒，默认 6 小时） */
    @Value("${holzyn.actor.memory.archive.maintenance-interval-ms:21600000}")
    private long maintenanceIntervalMs;

    /** 超龄扫描开关（在定时维护内；false=不自动超龄归档，仍可手动 API 触发） */
    @Value("${holzyn.actor.memory.archive.age-scan-enabled:true}")
    private boolean ageScanEnabled;

    /** 超龄判定：长期无访问阈值（access_count ≤ 该值视为长期无访问，默认 1） */
    @Value("${holzyn.actor.memory.archive.low-access-count:1}")
    private int lowAccessCount;

    /** 预算淘汰归档开关（默认 false：淘汰仍走软删，开启才额外写归档 evicted） */
    @Value("${holzyn.actor.memory.archive.evict-enabled:false}")
    private boolean evictEnabled;

    /** 反思降级归档开关（默认 true：反思后低重要度证据降级归档） */
    @Value("${holzyn.actor.memory.archive.downgrade-enabled:true}")
    private boolean downgradeEnabled;

    /** 反思降级重要度阈值（证据记忆重要度 ≤ 该值才降级归档，默认 3；高重要证据留 L2） */
    @Value("${holzyn.actor.memory.archive.downgrade-importance-threshold:3}")
    private int downgradeImportanceThreshold;

    /** 超龄判定复用 R3：重要度阈值（默认 3，与 AgentMemoryService stale-importance-threshold 对齐） */
    @Value("${holzyn.actor.memory.archive.stale-importance-threshold:3}")
    private int staleImportanceThreshold;

    /** 超龄判定复用 R3：超期游戏日阈值（默认 3，与 AgentMemoryService stale-after-game-days 对齐） */
    @Value("${holzyn.actor.memory.archive.stale-after-game-days:3}")
    private int staleAfterGameDays;

    // ==================== 依赖（构造器=纯数据仓库，可单测直造） ====================

    /** 归档仓库 */
    private final ActorMemoryArchiveRepository archiveRepository;
    /** L2 长期记忆仓库（版本链/超龄扫描/还原来源） */
    private final ActorMemoryRepository memoryRepository;
    /** L3 核心块仓库（升级归档/还原来源） */
    private final ActorCoreMemoryRepository coreRepository;
    /** L1 工作记忆仓库（来源记录，暂不主动归档） */
    private final ActorMemoryWorkingRepository workingRepository;
    /** 项目仓库（归属校验 + 定时维护遍历项目） */
    private final ActorProjectRepository projectRepository;

    /** 世界时钟（游戏日换算；字段注入，纯数据测试可置空跳过） */
    @Autowired(required = false)
    private WorldClockService worldClockService;
    /** 当前用户提供者（归属校验；字段注入，纯数据测试可置空跳过） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * 世界运行时门控（用户需求：启动全静止，进入沙盘才运行；null=未装配[单测]不过滤——
     * 未运行世界跳过归档维护，世界静止=数据静止）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService runtimeGate;

    /** Jackson 序列化器（导出 JSON，线程安全静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构造器（纯数据仓库依赖，供归档服务单测直接构造）。
     */
    public MemoryArchiveService(ActorMemoryArchiveRepository archiveRepository,
                                ActorMemoryRepository memoryRepository,
                                ActorCoreMemoryRepository coreRepository,
                                ActorMemoryWorkingRepository workingRepository,
                                ActorProjectRepository projectRepository) {
        this.archiveRepository = archiveRepository;
        this.memoryRepository = memoryRepository;
        this.coreRepository = coreRepository;
        this.workingRepository = workingRepository;
        this.projectRepository = projectRepository;
    }

    // ==================== 归档写入（内部核心：从各类源实体构建归档行） ====================

    /**
     * 把 L2 长期记忆写入归档（原文保留；reason 决定归档原因；不修改原记录——软删由调用方负责）。
     *
     * @return 归档实体（null=开关关/参数非法）
     */
    @Transactional
    public ActorMemoryArchive archiveL2Memory(ActorMemory m, String reason) {
        if (!enabled || m == null || m.getId() == null) {
            return null;
        }
        // 数据保真：旧存量行 character_ref 为 null 但 character_id 非空时，派生 npc:{id}
        // 回填到归档（否则还原回 L2 会丢失角色归属，成为项目级记忆）
        String ref = m.getCharacterRef();
        if ((ref == null || ref.isBlank()) && m.getCharacterId() != null) {
            // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（不再手写前缀字面量）
            ref = NpcRefs.of(m.getCharacterId());
        }
        return saveArchive(m.getProjectId(), ref, SOURCE_TABLE_MEMORY, m.getId(),
                m.getType(), m.getContent(), m.getContextJson(), m.getImportance(), reason);
    }

    /**
     * 把 L3 核心块写入归档（类型记为 core；reason=superseded 升级 / manual 手动归档）。
     * <p>块键/块名存入 context_json（{"block_key":...,"block_name":...}），供还原时正确重建块。</p>
     *
     * @return 归档实体（null=开关关/参数非法）
     */
    @Transactional
    public ActorMemoryArchive archiveCoreBlock(ActorCoreMemory b, String reason) {
        if (!enabled || b == null || b.getId() == null) {
            return null;
        }
        String ctx = null;
        try {
            ctx = MAPPER.writeValueAsString(Map.of(
                    "block_key", b.getBlockKey() == null ? "" : b.getBlockKey(),
                    "block_name", b.getBlockName() == null ? "" : b.getBlockName()));
        } catch (Exception ignored) {
            // 序列化失败则 context_json 留空，还原走兜底块键
        }
        return saveArchive(b.getProjectId(), b.getCharacterRef(), SOURCE_TABLE_CORE, b.getId(),
                "core", b.getContent(), ctx, b.getImportance(), reason);
    }

    /**
     * 把 L1 工作项写入归档（类型记为 working；供未来 L1 手动归档扩展，本轮保留能力）。
     *
     * @return 归档实体（null=开关关/参数非法）
     */
    @Transactional
    public ActorMemoryArchive archiveWorkingItem(ActorMemoryWorking w, String reason) {
        if (!enabled || w == null || w.getId() == null) {
            return null;
        }
        return saveArchive(w.getProjectId(), w.getCharacterRef(), SOURCE_TABLE_WORKING, w.getId(),
                "working", w.getContent(), null, w.getImportanceHint(), reason);
    }

    /**
     * 归档行构建 + 落库（统一填归档时间/游戏日/原文）。
     */
    private ActorMemoryArchive saveArchive(Long projectId, String characterRef, String sourceTable,
                                           Long sourceMemoryId, String type, String content,
                                           String contextJson, Integer importance, String reason) {
        ActorMemoryArchive a = new ActorMemoryArchive();
        a.setProjectId(projectId);
        a.setCharacterRef(characterRef);
        a.setSourceTable(sourceTable);
        a.setSourceMemoryId(sourceMemoryId);
        a.setType(type);
        a.setContent(content);
        a.setContextJson(contextJson);
        a.setImportanceAtArchive(clampImportance(importance));
        a.setArchiveReason(reason);
        a.setGameDayAtArchive(currentGameDay(projectId));
        archiveRepository.save(a);
        log.debug("[归档] 写入：project={} ref={} source={}#{} type={} reason={}",
                projectId, characterRef, sourceTable, sourceMemoryId, type, reason);
        return a;
    }

    /** 当前游戏日（floorDiv(gameHour,24)；取不到返回 null） */
    private Long currentGameDay(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        try {
            Long gh = worldClockService.currentGameHour(projectId);
            return gh == null ? null : Math.floorDiv(gh, 24);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== L2 版本链更新（设计 §6.5：新条 version+1 + supersedes_id=旧，旧条归档 superseded） ====================

    /**
     * L2 记忆更新：创建新版本条目（version=旧+1、supersedes_id=旧 id、内容/重要度更新），
     * 旧条软删 + 写归档（reason=superseded）——缓解「自信地错」：读取命中旧版本后代时以最新为准，旧版仅归档可查。
     *
     * @param projectId 项目 ID（归属校验）
     *
     * @param memoryId 旧记忆主键
     *
     * @param content 新内容（必填、非空）
     *
     * @param importance 新重要度（可空=沿用旧值）
     *
     * @return 新版本记忆实体
     */
    @Transactional
    public ActorMemory updateL2Memory(Long projectId, Long memoryId, String content, Integer importance) {
        requireProject(projectId);
        if (content == null || content.isBlank()) {
            throw new BizException(400, "记忆内容不能为空");
        }
        ActorMemory old = memoryRepository.findById(memoryId)
                .filter(m -> projectId.equals(m.getProjectId()))
                .filter(m -> NOT_DELETED == (m.getDeleted() == null ? NOT_DELETED : m.getDeleted()))
                .orElseThrow(() -> new BizException(404, "记忆不存在或无权访问"));
        String text = content.trim();
        ActorMemory next = new ActorMemory();
        next.setProjectId(old.getProjectId());
        next.setCharacterId(old.getCharacterId());
        next.setCharacterRef(old.getCharacterRef());
        next.setType(old.getType());
        next.setContent(text);
        next.setImportance(clampImportance(importance == null ? old.getImportance() : importance));
        next.setContextJson(old.getContextJson());
        next.setSource(ActorMemoryTypes.SOURCE_MANUAL);
        next.setVersion((old.getVersion() == null ? 1 : old.getVersion()) + 1);
        next.setSupersedesId(old.getId());
        memoryRepository.save(next);
        // 旧条软删 + 归档（superseded）
        old.setDeleted(DELETED);
        old.setUpdatedAt(LocalDateTime.now());
        memoryRepository.save(old);
        archiveL2Memory(old, REASON_SUPERSEDED);
        log.info("[归档] L2 版本链更新：project={} ref={} type={} 旧id={} 新版本={}（旧条归档 superseded）",
                projectId, old.getCharacterRef(), old.getType(), old.getId(), next.getVersion());
        return next;
    }

    // ==================== 手动归档（设计 §8.1 manual） ====================

    /**
     * 手动归档 L2 长期记忆（多条，reason=manual）：逐条写归档 + 软删；越权/不存在的 id 跳过并计数。
     *
     * @return 实际归档条数
     */
    @Transactional
    public int manualArchiveL2(Long projectId, List<Long> ids) {
        requireProject(projectId);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int archived = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            ActorMemory m = memoryRepository.findById(id)
                    .filter(x -> projectId.equals(x.getProjectId()))
                    .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                    .orElse(null);
            if (m == null) {
                continue;
            }
            m.setDeleted(DELETED);
            m.setUpdatedAt(LocalDateTime.now());
            memoryRepository.save(m);
            archiveL2Memory(m, REASON_MANUAL);
            archived++;
        }
        if (archived > 0) {
            log.info("[归档] 手动归档 L2：project={} 条数={}", projectId, archived);
        }
        return archived;
    }

    /**
     * 手动归档 L3 核心块（多条，reason=manual）：逐块写归档 + 软删（内容冷存保真）。
     *
     * @return 实际归档条数
     */
    @Transactional
    public int manualArchiveCore(Long projectId, List<Long> blockIds) {
        requireProject(projectId);
        if (blockIds == null || blockIds.isEmpty()) {
            return 0;
        }
        int archived = 0;
        for (Long id : blockIds) {
            if (id == null) {
                continue;
            }
            ActorCoreMemory b = coreRepository.findById(id)
                    .filter(x -> projectId.equals(x.getProjectId()))
                    .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                    .orElse(null);
            if (b == null) {
                continue;
            }
            b.setDeleted(DELETED);
            b.setUpdatedAt(LocalDateTime.now());
            coreRepository.save(b);
            archiveCoreBlock(b, REASON_MANUAL);
            archived++;
        }
        if (archived > 0) {
            log.info("[归档] 手动归档 L3：project={} 条数={}", projectId, archived);
        }
        return archived;
    }

    /**
     * 批量归档统一入口（前端「归档」按钮用）：按来源表分发。
     *
     * @param sourceTable 来源表：actor_memory / actor_core_memory
     *
     * @param ids 待归档 id 列表
     *
     * @return 实际归档条数
     */
    @Transactional
    public int batchArchive(Long projectId, String sourceTable, List<Long> ids) {
        if (SOURCE_TABLE_CORE.equals(sourceTable)) {
            return manualArchiveCore(projectId, ids);
        }
        return manualArchiveL2(projectId, ids);
    }

    // ==================== 还原（设计 §8.1：归档表条目还原回 L2/L3，新建回来源表 + 标记 restored） ====================

    /**
     * 还原归档条目：按 source_table 在来源表<b>新建</b>一条记录（类型/内容/重要度/归属照搬，版本=1），
     * 归档条目标记 restored=1（保留可追溯，防重复还原）。
     * <p>去重语义：来源表已存在相同内容（Dice 重叠）→ 拒绝还原并提示；
     * L3 核心块还原时若块键已存在活动块 → 走版本链（新版本 supersedes 现活动块，现活动块归档 superseded），
     * 避免重建重复块；L1 工作项不支持还原（热缓冲易失，抛 400 提示）。</p>
     *
     * @param archiveId 归档主键
     */
    @Transactional
    public Map<String, Object> restore(Long archiveId) {
        ActorMemoryArchive a = archiveRepository.findById(archiveId)
                .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                .orElseThrow(() -> new BizException(404, "归档条目不存在"));
        requireProject(a.getProjectId());
        if (RESTORED == (a.getRestored() == null ? NOT_RESTORED : a.getRestored())) {
            throw new BizException(400, "该归档条目已还原过，不能重复还原");
        }
        Long createdId;
        if (SOURCE_TABLE_CORE.equals(a.getSourceTable())) {
            createdId = restoreToCore(a);
        } else if (SOURCE_TABLE_MEMORY.equals(a.getSourceTable())) {
            createdId = restoreToMemory(a);
        } else {
            throw new BizException(400, "L1 工作记忆为热缓冲易失层，不支持还原（可手动新建）");
        }
        a.setRestored(RESTORED);
        a.setUpdatedAt(LocalDateTime.now());
        archiveRepository.save(a);
        log.info("[归档] 还原：archiveId={} source={} createdId={}", archiveId, a.getSourceTable(), createdId);
        return Map.of("archiveId", archiveId, "restored", true,
                "sourceTable", a.getSourceTable(), "createdId", createdId);
    }

    /**
     * 还原到 L2：新建 ActorMemory（类型/内容/重要度/归属照搬，version=1，source=manual）；去重命中拒绝。
     */
    private Long restoreToMemory(ActorMemoryArchive a) {
        String content = a.getContent() == null ? "" : a.getContent().trim();
        if (content.isBlank()) {
            throw new BizException(400, "归档内容为空，无法还原");
        }
        String ref = a.getCharacterRef();
        List<String> existing = new ArrayList<>();
        if (ref != null && !ref.isBlank()) {
            existing = memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                    a.getProjectId(), ref, NOT_DELETED).stream().map(ActorMemory::getContent).toList();
        } else {
            existing = memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(
                    a.getProjectId(), NOT_DELETED).stream().map(ActorMemory::getContent).toList();
        }
        if (MemoryDedup.isDuplicate(content, existing, 0.6)) {
            throw new BizException(400, "长期记忆中已存在相同内容，无需还原");
        }
        ActorMemory m = new ActorMemory();
        m.setProjectId(a.getProjectId());
        m.setCharacterRef(ref);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——解析 actor_profile.id 统一走 NpcRefs.idOf
        m.setCharacterId(NpcRefs.idOf(ref));
        m.setType(a.getType() == null ? ActorMemoryTypes.TYPE_SEMANTIC : a.getType());
        m.setContent(content);
        m.setImportance(clampImportance(a.getImportanceAtArchive()));
        m.setContextJson(a.getContextJson());
        m.setSource(ActorMemoryTypes.SOURCE_MANUAL);
        m.setVersion(1);
        memoryRepository.save(m);
        return m.getId();
    }

    /**
     * 还原到 L3：块键无活动块 → 新建（version=1）；块键已有活动块 → 版本链（新块 supersedes 现活动块，
     * 现活动块软删 + 归档 superseded），不重建重复块。
     */
    private Long restoreToCore(ActorMemoryArchive a) {
        String content = a.getContent() == null ? "" : a.getContent().trim();
        if (content.isBlank()) {
            throw new BizException(400, "归档内容为空，无法还原");
        }
        String ref = a.getCharacterRef();
        String blockKey = blockKeyOf(a.getContextJson());
        String blockName = blockNameOf(a.getContextJson(), blockKey);
        ActorCoreMemory existing = coreRepository
                .findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(a.getProjectId(), ref, blockKey, NOT_DELETED)
                .stream().findFirst().orElse(null);
        ActorCoreMemory b = new ActorCoreMemory();
        b.setProjectId(a.getProjectId());
        b.setCharacterRef(ref);
        b.setBlockKey(blockKey);
        b.setBlockName(blockName);
        b.setContent(content);
        b.setImportance(clampImportance(a.getImportanceAtArchive()));
        b.setEditableByAgent(1);
        b.setVersion(1);
        if (existing != null) {
            // 块键已占用：走版本链（新块取代现活动块）
            b.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
            b.setSupersedesId(existing.getId());
            coreRepository.save(b);
            existing.setDeleted(DELETED);
            existing.setUpdatedAt(LocalDateTime.now());
            coreRepository.save(existing);
            archiveCoreBlock(existing, REASON_SUPERSEDED);
        } else {
            coreRepository.save(b);
        }
        return b.getId();
    }

    /** 还原 L3 时块键解析：优先取归档 context_json 的 block_key（archived 时已存），否则兜底 custom:restored */
    private static String blockKeyOf(String contextJson) {
        if (contextJson != null && contextJson.contains("\"block_key\"")) {
            try {
                tools.jackson.databind.JsonNode n = MAPPER.readTree(contextJson);
                String key = n.path("block_key").asText("");
                if (!key.isBlank()) {
                    return key;
                }
            } catch (Exception ignored) {
                // 解析失败走兜底
            }
        }
        return "custom:restored";
    }

    /** 还原 L3 时块显示名：优先取归档 context_json 的 block_name，否则标准键名兜底 */
    private static String blockNameOf(String contextJson, String blockKey) {
        if (contextJson != null && contextJson.contains("\"block_name\"")) {
            try {
                tools.jackson.databind.JsonNode n = MAPPER.readTree(contextJson);
                String name = n.path("block_name").asText("");
                if (!name.isBlank()) {
                    return name;
                }
            } catch (Exception ignored) {
                // 解析失败走兜底
            }
        }
        if (blockKey == null) {
            return "还原块";
        }
        return switch (blockKey) {
            case "identity" -> "身份";
            case "personality" -> "性格";
            case "beliefs" -> "核心信念";
            case "values" -> "价值观";
            case "goals" -> "长期目标";
            case "relations" -> "关键关系";
            default -> "还原块";
        };
    }

    // ==================== 超龄归档（设计 §8.1 age：staleness 超期 + 低访问） ====================

    /**
     * 超龄归档扫描（单项目，手动 API 触发 / 定时维护调用）：把「重要度≥阈值 且 超 N 游戏日未复核
     * 且 长期无访问（access_count ≤ low-access-count）」的角色级 semantic/episodic 记忆归档（reason=age）+ 软删。
     *
     * @return 归档条数
     */
    @Transactional
    public int scanAgeArchiving(Long projectId) {
        if (!enabled || projectId == null) {
            return 0;
        }
        if (worldClockService == null) {
            return 0; // 无时钟不可换算游戏日，跳过（纯数据测试上下文）
        }
        Long nowGameHour = worldClockService.currentGameHour(projectId);
        if (nowGameHour == null) {
            return 0;
        }
        long nowGameDay = Math.floorDiv(nowGameHour, 24);
        Page<ActorMemory> all = memoryRepository.findByProjectIdAndDeleted(
                projectId, NOT_DELETED, PageRequest.of(0, 10000));
        int archived = 0;
        for (ActorMemory m : all) {
            if (!isAgeArchivable(m, nowGameDay)) {
                continue;
            }
            m.setDeleted(DELETED);
            m.setUpdatedAt(LocalDateTime.now());
            memoryRepository.save(m);
            archiveL2Memory(m, REASON_AGE);
            archived++;
        }
        if (archived > 0) {
            log.info("[归档] 超龄扫描：project={} 归档 {} 条（age）", projectId, archived);
        }
        return archived;
    }

    /**
     * 超龄归档判定（静态可测）：角色级（characterRef 非空）+ semantic/episodic + 重要度≥阈值 +
     * 距上次复核超 N 游戏日 + access_count ≤ 低访问阈值。
     */
    boolean isAgeArchivable(ActorMemory m, long nowGameDay) {
        if (m == null || m.getCharacterRef() == null || m.getCharacterRef().isBlank()) {
            return false; // 项目级大事记不参与超龄归档（世界史防误删）
        }
        String type = m.getType();
        if (!ActorMemoryTypes.TYPE_SEMANTIC.equals(type) && !ActorMemoryTypes.TYPE_EPISODIC.equals(type)) {
            return false; // reflection/plan 不参与（洞察权威/计划时效）
        }
        if (m.getImportance() == null || m.getImportance() < Math.max(1, staleImportanceThreshold)) {
            return false;
        }
        int access = m.getAccessCount() == null ? 0 : m.getAccessCount();
        if (access > Math.max(0, lowAccessCount)) {
            return false; // 长期无访问才归档（高频高价值记忆保留）
        }
        LocalDateTime base = m.getLastAccessAt() != null ? m.getLastAccessAt() : m.getCreatedAt();
        if (base == null) {
            return false;
        }
        Long baseGameHour = worldClockService.gameHourAt(m.getProjectId(), base);
        if (baseGameHour == null) {
            return false;
        }
        long baseGameDay = Math.floorDiv(baseGameHour, 24);
        return nowGameDay - baseGameDay >= Math.max(1, staleAfterGameDays);
    }

    /**
     * 全部项目超龄归档扫描（定时维护用）。
     */
    @Transactional
    public int scanAgeAllProjects() {
        if (!enabled) {
            return 0;
        }
        List<ActorProject> projects = projectRepository.findAll();
        int total = 0;
        for (ActorProject p : projects) {
            if (p.getId() == null) {
                continue;
            }
            try {
                total += scanAgeArchiving(p.getId());
            } catch (Exception e) {
                log.warn("[归档] 项目超龄扫描失败（隔离继续）: project={} : {}", p.getId(), e.getMessage());
            }
        }
        return total;
    }

    // ==================== 反思降级（设计 §8.1 downgraded：证据内化进洞察后低重要证据归档） ====================

    /**
     * 反思降级归档（ReflectionService 反思成功后挂钩）：把本次反思证据记忆中「重要度 ≤
     * downgrade-importance-threshold」的 semantic/episodic 条目降级归档（reason=downgraded）+ 软删；
     * 高重要证据仍留 L2 参与检索（证据已内化进洞察，原文归档保真）。
     *
     * @return 降级归档条数
     */
    @Transactional
    public int downgradeEvidence(Long projectId, String characterRef, List<Long> evidenceIds) {
        if (!enabled || !downgradeEnabled || projectId == null || evidenceIds == null || evidenceIds.isEmpty()) {
            return 0;
        }
        int archived = 0;
        for (Long id : evidenceIds) {
            if (id == null) {
                continue;
            }
            ActorMemory m = memoryRepository.findById(id)
                    .filter(x -> projectId.equals(x.getProjectId()))
                    .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                    .orElse(null);
            if (m == null) {
                continue;
            }
            String type = m.getType();
            if (!ActorMemoryTypes.TYPE_SEMANTIC.equals(type) && !ActorMemoryTypes.TYPE_EPISODIC.equals(type)) {
                continue; // 仅经历/事实可降级（reflection/plan 不动）
            }
            if (m.getImportance() != null && m.getImportance() > Math.max(1, downgradeImportanceThreshold)) {
                continue; // 高重要证据留 L2
            }
            m.setDeleted(DELETED);
            m.setUpdatedAt(LocalDateTime.now());
            memoryRepository.save(m);
            archiveL2Memory(m, REASON_DOWNGRADED);
            archived++;
        }
        if (archived > 0) {
            log.info("[归档] 反思降级：project={} ref={} 归档 {} 条（downgraded）", projectId, characterRef, archived);
        }
        return archived;
    }

    // ==================== 预算淘汰归档（设计 §8.1 evicted，默认关） ====================

    /**
     * 预算淘汰归档（AgentMemoryService 软删前挂钩，默认关）：evict-enabled=true 时把本次淘汰的记忆
     * 写归档（reason=evicted）；false 时零开销（淘汰仍走软删，行为不变）。
     *
     * @return 归档条数
     */
    @Transactional
    public int archiveEvicted(List<ActorMemory> toDelete) {
        if (!enabled || !evictEnabled || toDelete == null || toDelete.isEmpty()) {
            return 0;
        }
        int archived = 0;
        for (ActorMemory m : toDelete) {
            if (m == null || m.getId() == null) {
                continue;
            }
            archiveL2Memory(m, REASON_EVICTED);
            archived++;
        }
        if (archived > 0) {
            log.info("[归档] 预算淘汰归档：共 {} 条（evicted）", archived);
        }
        return archived;
    }

    // ==================== 检索 / 导出（设计 §8.2/§8.3：按需检索 + JSON/Markdown 导出，不参与日常注入） ====================

    /**
     * 归档分页列表（按项目 + 可选 角色/类型/原因/关键词 过滤；仅未删条目）。
     */
    public PageResult<ArchiveVO> list(Long projectId, String characterRef, String type, String reason,
                                      String keyword, int page, int size) {
        requireProject(projectId);
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        Specification<ActorMemoryArchive> spec = buildSpec(projectId, characterRef, type, reason, keyword);
        Page<ActorMemoryArchive> p = archiveRepository.findAll(spec, PageRequest.of(safePage, safeSize));
        List<ArchiveVO> vos = p.getContent().stream().map(ArchiveVO::of).toList();
        return PageResult.of(vos, p.getTotalElements(), page, safeSize);
    }

    /**
     * 归档列表动态过滤（Specification；keyword 匹配内容 LIKE，多条件 AND）。
     */
    private Specification<ActorMemoryArchive> buildSpec(Long projectId, String characterRef, String type,
                                                        String reason, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("projectId"), projectId));
            ps.add(cb.equal(root.get("deleted"), NOT_DELETED));
            if (characterRef != null && !characterRef.isBlank()) {
                ps.add(cb.equal(root.get("characterRef"), characterRef.trim()));
            }
            if (type != null && !type.isBlank()) {
                ps.add(cb.equal(root.get("type"), type.trim()));
            }
            if (reason != null && !reason.isBlank()) {
                ps.add(cb.equal(root.get("archiveReason"), reason.trim()));
            }
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("content")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * 归档导出数据（JSON 全量 / Markdown 阅读版；按项目 + 可选角色；未删条目按归档时间倒序）。
     * <p>语义（设计 §8.3）：归档 = 「项目史档案」——用于回放（铺垫）、审计、迁移。</p>
     *
     * @return 导出文本
     */
    public String export(Long projectId, String characterRef, String format) {
        requireProject(projectId);
        List<ActorMemoryArchive> rows = (characterRef != null && !characterRef.isBlank())
                ? archiveRepository.findByProjectIdAndCharacterRefAndDeletedOrderByArchivedAtDesc(
                projectId, characterRef.trim(), NOT_DELETED)
                : archiveRepository.findByProjectIdAndDeletedOrderByArchivedAtDesc(projectId, NOT_DELETED);
        boolean md = "markdown".equalsIgnoreCase(format);
        return md ? exportMarkdown(rows, characterRef) : exportJson(rows);
    }

    /**
     * JSON 全量导出（结构化，含全部字段，供迁移/审计/回放）。
     */
    private String exportJson(List<ActorMemoryArchive> rows) {
        try {
            return MAPPER.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("[归档] JSON 导出序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Markdown 阅读版导出（人类可读「项目史档案」：按来源表分组 + 归档时间/原因/内容行）。
     */
    private String exportMarkdown(List<ActorMemoryArchive> rows, String characterRef) {
        if (rows.isEmpty()) {
            return "# 记忆归档\n\n（暂无归档条目）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 记忆归档（项目史档案）\n\n");
        sb.append("> 导出时间：").append(LocalDateTime.now()).append("\n");
        if (characterRef != null && !characterRef.isBlank()) {
            sb.append("> 范围：角色 ").append(characterRef).append("\n");
        } else {
            sb.append("> 范围：全项目\n");
        }
        sb.append("> 共 ").append(rows.size()).append(" 条\n\n");
        sb.append("---\n\n");
        int index = 1;
        for (ActorMemoryArchive a : rows) {
            sb.append("## ").append(index++).append(". ");
            sb.append("[").append(reasonLabel(a.getArchiveReason())).append("] ");
            sb.append(typeLabel(a)).append(" · ").append(a.getArchivedAt() == null ? "-" : a.getArchivedAt()).append("\n\n");
            sb.append("- 归属：").append(a.getCharacterRef() == null || a.getCharacterRef().isBlank()
                    ? "项目级" : a.getCharacterRef()).append("\n");
            sb.append("- 来源：").append(a.getSourceTable() == null ? "-" : a.getSourceTable());
            if (a.getSourceMemoryId() != null) {
                sb.append(" #").append(a.getSourceMemoryId());
            }
            sb.append("\n");
            if (a.getImportanceAtArchive() != null) {
                sb.append("- 重要度：").append(a.getImportanceAtArchive()).append("\n");
            }
            if (a.getGameDayAtArchive() != null) {
                sb.append("- 游戏日：").append(a.getGameDayAtArchive()).append("\n");
            }
            sb.append("- 状态：").append(RESTORED == (a.getRestored() == null ? NOT_RESTORED : a.getRestored())
                    ? "已还原" : "未还原").append("\n\n");
            sb.append(a.getContent() == null ? "（空内容）" : a.getContent()).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    /** 归档原因中文标签（Markdown 导出用） */
    private static String reasonLabel(String reason) {
        if (REASON_AGE.equals(reason)) {
            return "超龄";
        }
        if (REASON_SUPERSEDED.equals(reason)) {
            return "被覆盖";
        }
        if (REASON_DOWNGRADED.equals(reason)) {
            return "反思降级";
        }
        if (REASON_EVICTED.equals(reason)) {
            return "预算淘汰";
        }
        return "手动归档";
    }

    /** 归档类型中文标签（Markdown 导出用） */
    private static String typeLabel(ActorMemoryArchive a) {
        String t = a.getType();
        if ("core".equals(t)) {
            return "核心块";
        }
        if ("working".equals(t)) {
            return "工作项";
        }
        if (ActorMemoryTypes.TYPE_EPISODIC.equals(t)) {
            return "情节";
        }
        if (ActorMemoryTypes.TYPE_REFLECTION.equals(t)) {
            return "反思";
        }
        if (ActorMemoryTypes.TYPE_PLAN.equals(t)) {
            return "计划";
        }
        return "语义";
    }

    // ==================== 保留策略（设计 §14：每项目上限滚动软删 + 归档内软删二次清理） ====================

    /**
     * 每项目归档保留上限（单项目）：未删条数超 {@code max-per-project} 时按「重要度低 + 旧」滚动软删最旧部分。
     *
     * @return 软删条数
     */
    @Transactional
    public int enforceRetention(Long projectId) {
        if (!enabled || projectId == null || maxPerProject <= 0) {
            return 0;
        }
        long count = archiveRepository.countByProjectIdAndDeleted(projectId, NOT_DELETED);
        if (count <= maxPerProject) {
            return 0;
        }
        int excess = (int) (count - maxPerProject);
        List<ActorMemoryArchive> oldest = archiveRepository.findByProjectIdOrderByImportanceAtArchiveAscArchivedAtAsc(projectId);
        int softDeleted = 0;
        for (ActorMemoryArchive a : oldest) {
            if (softDeleted >= excess) {
                break;
            }
            if (NOT_DELETED == (a.getDeleted() == null ? NOT_DELETED : a.getDeleted())) {
                a.setDeleted(DELETED);
                a.setUpdatedAt(LocalDateTime.now());
                archiveRepository.save(a);
                softDeleted++;
            }
        }
        if (softDeleted > 0) {
            log.info("[归档] 保留上限：project={} 软删 {} 条（超上限 {}）", projectId, softDeleted, maxPerProject);
        }
        return softDeleted;
    }

    /**
     * 归档条目软删。
     */
    @Transactional
    public void softDeleteArchive(Long archiveId) {
        ActorMemoryArchive a = archiveRepository.findById(archiveId)
                .filter(x -> NOT_DELETED == (x.getDeleted() == null ? NOT_DELETED : x.getDeleted()))
                .orElseThrow(() -> new BizException(404, "归档条目不存在"));
        requireProject(a.getProjectId());
        a.setDeleted(DELETED);
        a.setUpdatedAt(LocalDateTime.now());
        archiveRepository.save(a);
        log.info("[归档] 软删归档条目：archiveId={}", archiveId);
    }

    /**
     * 归档内二次清理（单项目）：deleted=1 且更新超 {@code retain-days} 的条目物理删除。
     *
     * @return 物理清理条数
     */
    @Transactional
    public int cleanupArchive(Long projectId) {
        if (!enabled || projectId == null) {
            return 0;
        }
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, retainDays));
        List<ActorMemoryArchive> expired = archiveRepository
                .findByProjectIdAndDeletedAndUpdatedAtBefore(projectId, DELETED, before);
        if (!expired.isEmpty()) {
            archiveRepository.deleteAll(expired);
        }
        if (!expired.isEmpty()) {
            log.info("[归档] 二次清理：project={} 物理删除 {} 条（超保留期 {} 天）", projectId, expired.size(), retainDays);
        }
        return expired.size();
    }

    /**
     * 定时维护（独立调度，不动 EngineScheduler）：超龄扫描 + 保留滚动 + 二次清理；单步失败不阻断另一步。
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.memory.archive.maintenance-interval-ms:21600000}")
    public void maintenance() {
        if (!maintenanceEnabled || !enabled) {
            return;
        }
        gated("MemoryArchiveService.maintenance", this::maintainNow);
    }

    /** 维护体（经世界闸门之后才允许扫库/写库） */
    private void maintainNow() {
        try {
            List<ActorProject> projects = projectRepository.findAll();
            for (ActorProject p : projects) {
                if (p.getId() == null) {
                    continue;
                }
                // 世界运行时门控：未运行世界跳过归档维护（世界静止=数据静止）
                if (runtimeGate != null && !runtimeGate.isActive(p.getId())) {
                    continue;
                }
                if (ageScanEnabled) {
                    try {
                        scanAgeArchiving(p.getId());
                    } catch (Exception e) {
                        log.warn("[归档] 定时超龄扫描失败（隔离继续）: project={} : {}", p.getId(), e.getMessage());
                    }
                }
                try {
                    enforceRetention(p.getId());
                } catch (Exception e) {
                    log.warn("[归档] 定时保留滚动失败（隔离继续）: project={} : {}", p.getId(), e.getMessage());
                }
                try {
                    cleanupArchive(p.getId());
                } catch (Exception e) {
                    log.warn("[归档] 定时二次清理失败（隔离继续）: project={} : {}", p.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[归档] 定时维护失败（整体隔离）: {}", e.getMessage());
        }
    }

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：世界未挂载时维护任务<b>零 DB 写入、零 AI 调用</b>（AC12）。
     * 未装配（单测直接 {@code new}）时退化为直接执行；字段注入不改构造器（AGENTS §8.1.2）。
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

    // ==================== 私有辅助 ====================

    /**
     * 校验项目归属当前用户（越权抛 404；纯数据测试上下文跳过）。
     */
    private void requireProject(Long projectId) {
        if (currentUserProvider == null) {
            return;
        }
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /** 重要度钳制 1~5（null 回退 1） */
    private static int clampImportance(Integer importance) {
        if (importance == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, importance));
    }
}
