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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicleArchive;
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleArchiveRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldChronicleVO;
import com.holzyn.actor.domain.world.vo.WorldEntityVO;
import com.holzyn.actor.domain.world.vo.WorldMemoryOverviewVO;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 项目记忆服务 ——双系统并列之「项目记忆」。
 * <p>职责：落地《记忆系统重构与世界时间季节系统_设计方案》§4 项目记忆四层——</p>
 * <ul>
 * <li><b>L2 编年史</b>（actor_world_chronicle）：AI 提炼「世界发生了什么」，三重触发：
 * ① 重要事件即时（actor_event 重要度≥阈值，事件总线订阅，source=instant）
 * ② 每日批量（游戏日 01:00 错峰帧生成，chronicle_distill 模板，source=daily）
 * ③ 定时批量巩固补漏（source=consolidation）+ 手动补充（source=manual）；</li>
 * <li><b>L3 实体状态</b>（actor_world_entity）：地点/人物/势力三域「世界视角公共状态」，
 * Zep 式失效更新（新信息矛盾→写新状态行 supersedes 旧行 + 旧行 valid_to=当前游戏日，
 * 不删旧行，支持 as-of-date 按时间点查询）；</li>
 * <li><b>双通道路由</b>：对话抽取（memory_extract 契约 target=world/both）经
 * {@link #routeFromExtraction} 路由——事件类（kind=event）→ 编年史；状态类（kind=entity）
 * → 实体状态表（provenance=dialog）；</li>
 * <li><b>归档</b>：编年史超龄（默认保留 3 游戏年）→ actor_world_chronicle_archive（不参与注入）；</li>
 * <li><b>注入</b>：{@link #recentChronicleSegment}【世界近闻】+ {@link #entityDynamicsSegment}
 * 【世界动态】（nearby 过滤）供 MemoryInjectorService 注入所有角色。</li>
 * </ul>
 * <p><b>编年史联动（，第二轮时间季节系统）</b>：编年史条目全写入点（对话路由/
 * 即时提炼/每日提炼/手动补充）填充 <b>season</b>（按游戏日固定绑定派生，与 SeasonService
 * 同源）与 <b>weather_snapshot</b>（当日帧主区域天气压缩 JSON，缺帧/失败保持 NULL）；
 * 提炼 AI 输入加 {{season_context}} 段（季节/月/日出日落）——编年史带「年月流转」语义。</p>
 * <p><b>AI 成本护栏</b>（用户 决策：独立每日预算 + 预算耗尽转批量）：
 * 即时提炼/每日提炼/实体更新判定共用「独立每日 AI 预算」（{@code daily-ai-cap}，默认 20，内存
 * Map 计数，重启丢失可接受）——预算内即时提炼，耗尽后当日剩余候选自动转待每日提炼补漏；
 * AI 失败/预算耗尽一律机制兜底（不阻断引擎/对话）。</p>
 * <p>事件总线订阅（即时提炼）对订阅者异常隔离，不影响主广播（对齐 RealTimeDecisionService 模式）。</p>
 */
@Slf4j
@Service
public class WorldMemoryService implements WorldEventSubscriber {

    // ==================== 常量 ====================

    /** AI 调用场景（trace/用量记账 scene=chronicle_distill / entity_state_update） */
    private static final String SCENE_CHRONICLE = "chronicle_distill";
    private static final String SCENE_ENTITY = "entity_state_update";

    /** 事件类别：世界帧（即时提炼排除——帧宏观事件由每日提炼统一覆盖，防环） */
    private static final String KIND_WORLD_FRAME = "world_frame";

    /** AI 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 每日提炼输入：当日事件流条数上限（按重要度降序取前 N） */
    private static final int DAILY_EVENT_INPUT_LIMIT = 20;

    /** 实体更新判定：每次判定涉及的实体数上限（批量一次调用防超长输入） */
    private static final int ENTITY_UPDATE_LIMIT = 10;

    /** 保留 1 游戏年天数（归档阈值换算） */
    private static final int DAYS_PER_YEAR = 365;

    /** 游戏小时数（每日 01:00 错峰提炼时刻换算用） */
    private static final int HOURS_PER_DAY = 24;

    /** 即时提炼排除的自身事件：world_frame 由每日提炼覆盖 */
    private static final String[] EXCLUDED_KINDS = {KIND_WORLD_FRAME};

    /** 异步执行器（虚拟线程：即时提炼不阻塞事件发布方） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Jackson 解析器（线程安全，静态复用；JSON 序列化/反序列化工具） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每日 AI 预算计数：projectId → {gameDay, used}（进程内存态；单实例约定重启丢失可接受） */
    private final ConcurrentHashMap<Long, long[]> dailyAiBudget = new ConcurrentHashMap<>();

    // ==================== 配置（application.yml holzyn.actor.world-memory.*） ====================

    /** 项目记忆总开关 */
    @Value("${holzyn.actor.world-memory.enabled:true}")
    private boolean enabled;

    /** 编年史保留游戏年数（默认 3，超龄归档） */
    @Value("${holzyn.actor.world-memory.chronicle-retention-years:3}")
    private int retentionYears;

    /** 事件即时提炼重要度阈值（默认 4=世界级） */
    @Value("${holzyn.actor.world-memory.instant-importance-threshold:4}")
    private int instantImportanceThreshold;

    /** 每日批量提炼时刻（游戏日小时，默认 1=01:00 与帧生成 00:00 错峰） */
    @Value("${holzyn.actor.world-memory.daily-distill-hour:1}")
    private int dailyDistillHour;

    /** 项目记忆独立每日 AI 预算（即时提炼+每日提炼+实体更新判定合计上限，默认 20） */
    @Value("${holzyn.actor.world-memory.daily-ai-cap:20}")
    private int dailyAiCap;

    /** 每日提炼 AI 主通道开关（false=每日提炼直接走机制兜底，零 AI） */
    @Value("${holzyn.actor.world-memory.distill-ai-enabled:true}")
    private boolean distillAiEnabled;

    /** 实体状态更新判定开关（false=编年史不触发实体更新判定） */
    @Value("${holzyn.actor.world-memory.entity-update-enabled:true}")
    private boolean entityUpdateEnabled;

    /** 对话抽取路由开关 */
    @Value("${holzyn.actor.world-memory.route-from-dialog-enabled:true}")
    private boolean routeFromDialogEnabled;

    /** 【世界近闻】注入条数（默认 3） */
    @Value("${holzyn.actor.world-memory.inject-recent-count:3}")
    private int injectRecentCount;

    /** 实体注入范围（nearby=按所在地/关系过滤；all=全量） */
    @Value("${holzyn.actor.world-memory.entity-inject-scope:nearby}")
    private String entityInjectScope;

    /** 【世界动态】注入条数（默认 3） */
    @Value("${holzyn.actor.world-memory.entity-inject-count:3}")
    private int entityInjectCount;

    // ==================== 依赖（构造器注入仓库，保持服务可测；AI/时钟字段注入） ====================

    private final ActorWorldChronicleRepository chronicleRepository;
    private final ActorWorldChronicleArchiveRepository archiveRepository;
    private final ActorWorldEntityRepository entityRepository;
    private final ActorEventRepository eventRepository;
    private final ActorWorldFrameRepository frameRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final WorldEventBus worldEventBus;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 世界时钟（当前游戏秒/游戏日解析） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /** 季节服务 */
    @Autowired(required = false)
    private SeasonService seasonService;

    /** Prompt 模板服务（chronicle_distill / entity_state_update 渲染） */
    @Autowired(required = false)
    private PromptTemplateService promptTemplateService;

    /** AI 网关（spring AI 通道） */
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（scene=chronicle_distill / entity_state_update） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（chronicle_distill / entity_state_update 属「世界视角」）：AI 请求前置
     * 段1A【世界观】+ 段2D【当前世界动态】（`worldContextPrefix`，世界历时刻/季节/天气），
     * 提炼/判定贴合世界设定与当下世界；
     * null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * AI 强度闸门（微层接线， 编年史提炼/实体判定按项目档位生效——
     * programmatic=零 AI 全机制兜底[事件原文成条] / light=每日预算减半 / full=标准 / max=无预算上限；
     * 字段注入避免改构造器破坏存量测试）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.AiIntensityGate aiIntensityGate;

    /**
     * 构造器（仓库 + 事件总线）。
     */
    public WorldMemoryService(ActorWorldChronicleRepository chronicleRepository,
                              ActorWorldChronicleArchiveRepository archiveRepository,
                              ActorWorldEntityRepository entityRepository,
                              ActorEventRepository eventRepository,
                              ActorWorldFrameRepository frameRepository,
                              WorldLocationRepository locationRepository,
                              ActorCharacterRepository characterRepository,
                              ActorCharacterRepository npcRepository,
                              ActorCharacterRelationRepository relationRepository,
                              WorldEventBus worldEventBus) {
        this.chronicleRepository = chronicleRepository;
        this.archiveRepository = archiveRepository;
        this.entityRepository = entityRepository;
        this.eventRepository = eventRepository;
        this.frameRepository = frameRepository;
        this.locationRepository = locationRepository;
        this.characterRepository = characterRepository;
        this.npcRepository = npcRepository;
        this.relationRepository = relationRepository;
        this.worldEventBus = worldEventBus;
    }

    /**
     * 启动注册：注册为世界事件总线旁路订阅者（重要事件即时提炼；总线对订阅者异常隔离）。
     */
    @PostConstruct
    void register() {
        worldEventBus.subscribe(this);
        log.info("[项目记忆] 已注册世界事件总线订阅者（重要事件即时提炼）");
    }

    // ==================== 双通道路由 ====================

    /**
     * 对话抽取路由候选（从 memory_extract 契约解析出的 world/both 条目）。
     *
     * @param kind event（事件类→编年史）/ entity（状态类→实体状态表）
     *
     * @param content 内容（AI 提炼的公共视角描述）
     *
     * @param importance 重要度 1-5
     */
    public record RouteCandidate(String kind, String content, int importance) {
    }

    /**
     * 双通道路由（设计 §4.2）：对话抽取（memory_extract，target=world/both）的世界信息写入项目记忆。
     * <p>路由规则：</p>
     * <ul>
     * <li><b>事件类</b>（kind=event，「村口大树被雷劈了」）→ 编年史条目（source=instant，
     * 标题=内容截断，不重复调 AI——对话抽取已完成 AI 判定）；</li>
     * <li><b>状态类</b>（kind=entity，「张屠户改行卖布了」）→ 实体状态表失效更新
     * （provenance=dialog，名称匹配挂接实体，无匹配丢弃防污染）；</li>
     * <li>角色侧个人视角（target=both 的角色经历落位）由调用方（AgentMemoryService）负责。</li>
     * </ul>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 当前游戏日（对话时刻；null=按世界时钟解析）
     *
     * @param candidates 路由候选列表
     */
    @Transactional
    public void routeFromExtraction(Long userId, Long projectId, Long gameDay, List<RouteCandidate> candidates) {
        if (!enabled || !routeFromDialogEnabled || projectId == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        Long day = gameDay != null ? gameDay : currentGameDay(projectId);
        if (day == null) {
            day = 0L;
        }
        int events = 0;
        int entities = 0;
        for (RouteCandidate c : candidates) {
            try {
                if (c == null || c.content() == null || c.content().isBlank()) {
                    continue;
                }
                if ("entity".equals(c.kind())) {
                    if (upsertFromDialog(projectId, day, c)) {
                        entities++;
                    }
                } else {
                    // 事件类（含 kind 缺省回退 fact 的世界条目）：写编年史（source=instant）
                    saveChronicleFromCandidate(projectId, day, c);
                    events++;
                }
            } catch (Exception e) {
                log.warn("[项目记忆] 对话抽取路由单条失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        if (events > 0 || entities > 0) {
            log.info("[项目记忆] 对话抽取路由完成：project={} 编年史+{} 实体状态+{}", projectId, events, entities);
        }
    }

    /** 事件类候选 → 编年史条目。 */
    private void saveChronicleFromCandidate(Long projectId, Long day, RouteCandidate c) {
        ActorWorldChronicle ch = new ActorWorldChronicle();
        ch.setProjectId(projectId);
        ch.setGameDay(day);
        ch.setGameHour(null);
        // 编年史联动：季节=按游戏日固定绑定派生；天气快照=当日帧主区域（缺帧/解析失败保持 null）
        ch.setSeason(seasonOfDay(day));
        ch.setWeatherSnapshot(weatherSnapshotOf(projectId, day));
        ch.setTitle(abbreviate(c.content(), 40));
        ch.setContent(c.content());
        ch.setImportance(Math.max(1, Math.min(5, c.importance())));
        ch.setSource(ActorWorldChronicle.SOURCE_INSTANT);
        chronicleRepository.save(ch);
    }

    /** 状态类候选 → 实体状态失效更新（名称匹配挂接，无匹配丢弃）。 */
    private boolean upsertFromDialog(Long projectId, Long day, RouteCandidate c) {
        EntityRef ref = resolveEntityRef(projectId, c.content());
        if (ref == null) {
            log.debug("[项目记忆] 对话状态类无匹配实体，丢弃防污染: {}", c.content());
            return false;
        }
        upsertEntityState(projectId, ref.key(), ref.type(), ref.name(), c.content(), c.content(),
                day, ActorWorldEntity.PROVENANCE_DIALOG, null);
        return true;
    }

    // ==================== 即时提炼 ====================

    /**
     * 事件总线订阅回调：重要事件（重要度≥阈值）→ 异步即时提炼编年史条目（source=instant）。
     * <p>排除 world_frame 自身事件（帧宏观事件由每日提炼统一覆盖，防环）；幂等=事件 id
     * 已出现在既有编年史 event_ids 中则跳过（防重复提炼）。预算=独立每日预算，耗尽转待批量。</p>
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!enabled || event == null || event.getProjectId() == null || event.getImportance() == null) {
            return;
        }
        if (event.getImportance() < instantImportanceThreshold) {
            return;
        }
        for (String kind : EXCLUDED_KINDS) {
            if (kind.equals(event.getKind())) {
                return;
            }
        }
        Long projectId = event.getProjectId();
        Long day = currentGameDay(projectId);
        if (day == null) {
            day = Math.floorDiv(event.getGameHour() == null ? 0 : event.getGameHour(), HOURS_PER_DAY);
        }
        Long finalDay = day;
        executor.execute(() -> {
            try {
                instantDistill(projectId, finalDay, event);
            } catch (Exception e) {
                log.warn("[项目记忆] 即时提炼失败（隔离）: project={} event={} : {}", projectId, event.getId(), e.getMessage());
            }
        });
    }

    /**
     * 即时提炼单条：幂等检查（事件 id 已提炼跳过）→ 预算内 AI 提炼（chronicle_distill 单事件输入）
     * → 机制兜底（AI 失败/预算耗尽用事件原文成条）→ 落库。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——AI 提炼
     * （chronicle_distill，秒级）在事务外执行，落库由 {@link #saveChronicleEntry} 的仓库 save
     * 毫秒级短事务逐条承担（与 actor_memory 同根因：AI 跨事务持锁 → 连锁 LOCK_TIMEOUT）。</p>
     */
    public void instantDistill(Long projectId, Long day, ActorEvent event) {
        if (!enabled || projectId == null || event == null || event.getId() == null) {
            return;
        }
        // 幂等：该事件 id 已出现在任一编年史 event_ids 中 → 跳过
        if (eventAlreadyDistilled(projectId, event.getId())) {
            log.debug("[项目记忆] 事件已提炼过，跳过即时提炼: event={}", event.getId());
            return;
        }
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        List<ChronicleDistillParse.ChronicleEntry> entries = List.of();
        if (distillAiEnabled && tryConsumeDailyAi(projectId, day)) {
            entries = aiDistillInstant(userId, projectId, day, event);
        }
        // 机制兜底：AI 失败/预算耗尽 → 事件原文成条（重要度=事件重要度）
        if (entries.isEmpty()) {
            String content = event.getContent() != null && !event.getContent().isBlank()
                    ? event.getContent() : event.getTitle();
            if (content == null || content.isBlank()) {
                return;
            }
            entries = List.of(new ChronicleDistillParse.ChronicleEntry(
                    abbreviate(content, 40), content,
                    Math.max(1, Math.min(5, event.getImportance() == null ? 3 : event.getImportance())),
                    List.of(event.getId()), List.of(), List.of()));
        }
        int saved = 0;
        for (ChronicleDistillParse.ChronicleEntry e : entries) {
            if (saveChronicleEntry(projectId, day, e, ActorWorldChronicle.SOURCE_INSTANT, event.getGameHour(),
                    event.getKind()) != null) {
                saved++;
            }
        }
        log.info("[项目记忆] 即时提炼完成：project={} event={} 条目={}", projectId, event.getId(), saved);
    }

    // ==================== 每日批量提炼 ====================

    /**
     * 每日批量提炼入口（WorldEngine 游戏日 01:00 钩子 + 巩固补漏共用）：
     * 幂等（当日 source=daily 条目已存在 → 跳过）→ 预算内 AI 提炼（chronicle_distill，
     * 输入=当日事件流+当日天气帧）→ 机制兜底（当日重要事件原文成条）→ 落库 → 实体状态更新判定。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——本方法串起
     * 两段 AI（每日提炼 + {@link #applyEntityUpdates} 的实体状态判定），旧实现单事务下
     * 前段落库的编年史写锁会在后段 AI 期间持续持有。现 AI 全部在事务外，落库逐条短事务提交
     * （{@link #saveChronicleEntry} / {@link #upsertEntityState} 的仓库 save），后段 AI 启动时
     * 前段写锁已全部释放。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 当前游戏日
     *
     * @param gameSecond 当前游戏秒（审计）
     */
    public void dailyDistillIfNeeded(Long projectId, Long gameDay, Long gameSecond) {
        if (!enabled || projectId == null || gameDay == null) {
            return;
        }
        // 幂等：当日已存在 source=daily 条目 → 跳过（缓存 24h 语义，对齐世界帧）
        if (!chronicleRepository.findByProjectIdAndGameDayAndSource(projectId, gameDay,
                ActorWorldChronicle.SOURCE_DAILY).isEmpty()) {
            log.debug("[项目记忆] 当日编年史已提炼，跳过: project={} day={}", projectId, gameDay);
            return;
        }
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        List<ChronicleDistillParse.ChronicleEntry> entries = List.of();
        if (distillAiEnabled && tryConsumeDailyAi(projectId, gameDay)) {
            entries = aiDistillDaily(userId, projectId, gameDay);
        }
        // 机制兜底：AI 失败/预算耗尽 → 当日重要事件（重要度≥3）原文成条（≤3 条）
        if (entries.isEmpty()) {
            entries = mechanismDailyEntries(projectId, gameDay);
        }
        List<ActorWorldChronicle> saved = new ArrayList<>();
        for (ChronicleDistillParse.ChronicleEntry e : entries) {
            ActorWorldChronicle ch = saveChronicleEntry(projectId, gameDay, e,
                    ActorWorldChronicle.SOURCE_DAILY, null, null);
            if (ch != null) {
                saved.add(ch);
            }
        }
        if (!saved.isEmpty()) {
            log.info("[项目记忆] 每日批量提炼完成：project={} day={} 条目={}", projectId, gameDay, saved.size());
            // 实体状态更新判定：对当日条目涉及的实体批量判定（独立预算）
            applyEntityUpdates(userId, projectId, gameDay, saved);
        }
    }

    /** 机制兜底每日条目：当日重要事件（重要度≥3）原文成条，≤3 条。 */
    private List<ChronicleDistillParse.ChronicleEntry> mechanismDailyEntries(Long projectId, Long day) {
        List<ActorEvent> events = dailyEvents(projectId, day);
        List<ChronicleDistillParse.ChronicleEntry> out = new ArrayList<>();
        int n = 0;
        for (ActorEvent e : events) {
            if (n >= 3) {
                break;
            }
            if (e.getImportance() == null || e.getImportance() < 3) {
                continue;
            }
            String content = e.getContent() != null && !e.getContent().isBlank()
                    ? e.getContent() : e.getTitle();
            if (content == null || content.isBlank()) {
                continue;
            }
            out.add(new ChronicleDistillParse.ChronicleEntry(abbreviate(content, 40), content,
                    Math.max(1, Math.min(5, e.getImportance() == null ? 3 : e.getImportance())),
                    List.of(e.getId()), List.of(), List.of()));
            n++;
        }
        return out;
    }

    /** 落库单条编年史（event_ids 幂等：含已提炼事件 id 的条目跳过防重复）。 */
    private ActorWorldChronicle saveChronicleEntry(Long projectId, Long day,
                                                   ChronicleDistillParse.ChronicleEntry e, String source,
                                                   Long gameHour, String eventKind) {
        if (e == null || e.content() == null || e.content().isBlank()) {
            return null;
        }
        // 幂等：条目引用的事件 id 若已有编年史提炼过 → 跳过该条（防即时/每日双写）。
        // ⚠️ 冒烟抓修：仅当引用的事件 id 是「真实存在的 actor_event 主键」时才参与
        // 幂等判定——历史/异常数据里 event_ids 可能是流内序号（1/2/3…），若直接按主键比对会与既有
        // 条目撞车（如既有 event_ids=[3]）导致新条目被误判已提炼整条丢弃；真实主键才能表达
        // 「该事件已被提炼过」的语义。
        if (e.eventIds() != null) {
            for (Long eventId : e.eventIds()) {
                if (eventId != null && eventId > 0
                        && eventRepository.existsById(eventId)
                        && eventAlreadyDistilled(projectId, eventId)) {
                    log.debug("[项目记忆] 条目引用的部分事件已提炼，跳过该条: event={}", eventId);
                    return null;
                }
            }
        }
        ActorWorldChronicle ch = new ActorWorldChronicle();
        ch.setProjectId(projectId);
        ch.setGameDay(day);
        ch.setGameHour(gameHour);
        // 编年史联动：季节=按游戏日固定绑定派生；天气快照=当日帧主区域（缺帧/解析失败保持 null）
        ch.setSeason(seasonOfDay(day));
        ch.setWeatherSnapshot(weatherSnapshotOf(projectId, day));
        ch.setTitle(e.title() == null || e.title().isBlank() ? abbreviate(e.content(), 40) : e.title());
        ch.setContent(e.content());
        ch.setImportance(e.importance());
        ch.setInvolvedEntities(toJson(e.involvedEntities()));
        ch.setInvolvedCharacters(toJson(e.involvedCharacters()));
        ch.setEventIds(toJson(e.eventIds()));
        ch.setSource(source);
        // 编年史标签：宏观社会演化事件（kind=macro）成条 → 打「宏观」标（前端徽标）
        if (ActorWorldChronicle.TAG_MACRO.equals(eventKind)) {
            ch.setTags(toJson(List.of(ActorWorldChronicle.TAG_MACRO)));
        }
        return chronicleRepository.save(ch);
    }

    // ==================== 实体状态更新判定 ====================

    /**
     * 实体状态更新判定：对编年史条目涉及的实体（≤ENTITY_UPDATE_LIMIT）批量一次 AI 调用
     * （entity_state_update 模板）——changed=true 的实体写新状态行（supersedes 旧行 + 旧行
     * valid_to=当前游戏日）；AI 失败/预算耗尽 → 跳过实体更新（编年史不受影响）。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——AI 判定
     * （entity_state_update，秒级）在事务外执行，落库由 {@link #upsertEntityState} 的仓库
     * save 毫秒级短事务逐行承担（调用方 {@link #dailyDistillIfNeeded} 已同拆，两段 AI
     * 之间无任何未提交写锁）。</p>
     */
    public void applyEntityUpdates(Long userId, Long projectId, Long day, List<ActorWorldChronicle> chronicles) {
        if (!enabled || !entityUpdateEnabled || projectId == null || day == null
                || chronicles == null || chronicles.isEmpty()) {
            return;
        }
        // 收集条目涉及的实体键（去重保序，≤上限）
        List<String> entityKeys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ActorWorldChronicle c : chronicles) {
            List<String> keys = parseStringList(c.getInvolvedEntities());
            for (String k : keys) {
                if (seen.add(k) && entityKeys.size() < ENTITY_UPDATE_LIMIT) {
                    entityKeys.add(k);
                }
            }
        }
        if (entityKeys.isEmpty()) {
            return;
        }
        // 当前状态基线（已存在实体行）
        Map<String, ActorWorldEntity> current = new LinkedHashMap<>();
        for (String key : entityKeys) {
            entityRepository.findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(projectId, key)
                    .ifPresent(e -> current.put(key, e));
        }
        List<EntityStateUpdateParse.EntityUpdate> updates = List.of();
        if (distillAiEnabled && tryConsumeDailyAi(projectId, day)) {
            updates = aiEntityUpdate(userId, projectId, day, chronicles, entityKeys, current);
        }
        if (updates.isEmpty()) {
            log.debug("[项目记忆] 实体更新判定无结果（AI 失败/预算耗尽/无变化），跳过: project={} day={}",
                    projectId, day);
            return;
        }
        int changed = 0;
        for (EntityStateUpdateParse.EntityUpdate u : updates) {
            if (!u.changed() || u.entityKey() == null) {
                continue;
            }
            String key = normalizeEntityKey(u.entityKey());
            EntityRef ref = parseEntityKey(key);
            if (ref == null) {
                // AI 输出的键无法解析（无类型前缀）→ 名称匹配挂接；仍无 → 跳过
                ref = resolveEntityRef(projectId, u.currentState() == null ? key : key + u.currentState());
                if (ref == null) {
                    continue;
                }
            }
            // 版本链：新行 supersedes 旧行 + 旧行 valid_to=当前游戏日（不删旧行，Zep 式失效）
            Long provenanceRef = chronicles.isEmpty() ? null : chronicles.get(0).getId();
            upsertEntityState(projectId, ref.key(), ref.type(), ref.name(), u.currentState(),
                    u.recentChange(), day, ActorWorldEntity.PROVENANCE_CHRONICLE, provenanceRef);
            changed++;
        }
        if (changed > 0) {
            log.info("[项目记忆] 实体状态更新完成：project={} day={} 变化={}", projectId, day, changed);
        }
    }

    /**
     * 实体状态失效更新（Zep 式）：同键当前有效行存在且状态相同 → 仅刷新（幂等）；
     * 状态不同 → 写新行（supersedes 旧行）+ 旧行 valid_to=当前游戏日（不删旧行，版本链可追溯）；
     * 无当前行 → 直接新建（valid_from=当前游戏日）。
     */
    @Transactional
    public ActorWorldEntity upsertEntityState(Long projectId, String entityKey, String entityType, String name,
                                              String currentState, String recentChange, Long day,
                                              String provenance, Long provenanceRef) {
        ActorWorldEntity existing = entityRepository
                .findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(projectId, entityKey)
                .orElse(null);
        if (existing != null && currentState != null
                && currentState.equals(existing.getCurrentState())) {
            // 状态相同：幂等——仅在有新变化描述时刷新 recent_change（不产生新行）
            if (recentChange != null && !recentChange.isBlank()
                    && !recentChange.equals(existing.getRecentChange())) {
                existing.setRecentChange(recentChange);
                existing.setUpdatedAt(LocalDateTime.now());
                entityRepository.save(existing);
            }
            return existing;
        }
        ActorWorldEntity ne = new ActorWorldEntity();
        ne.setProjectId(projectId);
        ne.setEntityType(entityType);
        ne.setEntityKey(entityKey);
        ne.setName(name);
        ne.setCurrentState(currentState);
        ne.setRecentChange(recentChange);
        ne.setValidFromGameDay(day);
        ne.setValidToGameDay(null);
        ne.setProvenance(provenance);
        ne.setProvenanceRef(provenanceRef);
        if (existing != null) {
            ne.setSupersedesId(existing.getId());
            existing.setValidToGameDay(day);
            existing.setUpdatedAt(LocalDateTime.now());
            entityRepository.save(existing);
        }
        return entityRepository.save(ne);
    }

    // ==================== 巩固补漏 + 归档 ====================

    /**
     * 项目记忆巩固：
     * ① 近 3 游戏日「已过每日提炼时刻且无 daily 条目」→ 补漏提炼（覆盖即时提炼失败/预算耗尽的
     * 事件；当日无事件也无 daily 条目时 dailyDistillIfNeeded 走机制兜底空结果不落库——零副作用）；
     * ② 编年史超龄归档（保留 retention-years 游戏年）。
     * <p><b>记忆写并发治理</b>：不再带 {@code @Transactional}——① 委托的
     * {@link #dailyDistillIfNeeded} 含 AI 提炼（已拆事务）；② 归档由 {@link #archiveAged}
     * 自带短事务。旧实现单个事务跨整段补漏 AI 持锁，现已全部分层。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 当前游戏日
     *
     * @param gameSecond 当前游戏秒
     */
    public void consolidateWorldMemory(Long projectId, Long gameDay, Long gameSecond) {
        if (!enabled || projectId == null || gameDay == null) {
            return;
        }
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        // ① 补漏：近 3 游戏日（含当日）已过提炼时刻且无 daily 条目 → 补提炼
        for (long d = Math.max(0, gameDay - 2); d <= gameDay; d++) {
            if (!chronicleRepository.findByProjectIdAndGameDayAndSource(projectId, d,
                    ActorWorldChronicle.SOURCE_DAILY).isEmpty()) {
                continue;
            }
            try {
                dailyDistillIfNeeded(projectId, d, gameSecond);
            } catch (Exception e) {
                log.warn("[项目记忆] 巩固补漏提炼失败（隔离继续）: project={} day={} : {}",
                        projectId, d, e.getMessage());
            }
        }
        // ② 超龄归档
        try {
            int archived = archiveAged(projectId, gameDay);
            if (archived > 0) {
                log.info("[项目记忆] 编年史超龄归档完成：project={} 归档={}", projectId, archived);
            }
        } catch (Exception e) {
            log.warn("[项目记忆] 编年史归档失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 编年史超龄归档：game_day ≤ 当前游戏日 - 保留游戏年×365 的条目 → 复制入归档表 + 删除原行。
     *
     * @return 归档条数
     */
    @Transactional
    public int archiveAged(Long projectId, Long currentGameDay) {
        if (!enabled || projectId == null || currentGameDay == null) {
            return 0;
        }
        long threshold = currentGameDay - Math.max(1, retentionYears) * (long) DAYS_PER_YEAR;
        List<ActorWorldChronicle> aged = chronicleRepository
                .findByProjectIdAndGameDayLessThanEqualOrderByGameDayAsc(projectId, threshold);
        int archived = 0;
        for (ActorWorldChronicle c : aged) {
            try {
                ActorWorldChronicleArchive a = new ActorWorldChronicleArchive();
                a.setChronicleId(c.getId());
                a.setProjectId(c.getProjectId());
                a.setGameDay(c.getGameDay());
                a.setGameHour(c.getGameHour());
                a.setSeason(c.getSeason());
                a.setTitle(c.getTitle());
                a.setContent(c.getContent());
                a.setImportance(c.getImportance());
                a.setWeatherSnapshot(c.getWeatherSnapshot());
                a.setInvolvedEntities(c.getInvolvedEntities());
                a.setInvolvedCharacters(c.getInvolvedCharacters());
                a.setEventIds(c.getEventIds());
                a.setSource(c.getSource());
                a.setTags(c.getTags());
                archiveRepository.save(a);
                chronicleRepository.delete(c);
                archived++;
            } catch (Exception e) {
                log.warn("[项目记忆] 单条归档失败（隔离继续）: chronicle={} : {}", c.getId(), e.getMessage());
            }
        }
        return archived;
    }

    // ==================== AI 通道（chronicle_distill / entity_state_update） ====================

    /** AI 即时提炼。 */
    private List<ChronicleDistillParse.ChronicleEntry> aiDistillInstant(Long userId, Long projectId, Long day,
                                                                         ActorEvent event) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("mode", "instant");
        ph.put("game_day", String.valueOf(day + 1));
        ph.put("season_context", seasonContextForDistill(projectId, day));
        ph.put("event_stream", renderEvent(event));
        ph.put("weather_context", frameWeatherText(projectId, day));
        return callAiChronicle(userId, projectId, ph);
    }

    /** AI 每日批量提炼。 */
    private List<ChronicleDistillParse.ChronicleEntry> aiDistillDaily(Long userId, Long projectId, Long day) {
        List<ActorEvent> events = dailyEvents(projectId, day);
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("mode", "daily");
        ph.put("game_day", String.valueOf(day + 1));
        ph.put("season_context", seasonContextForDistill(projectId, day));
        ph.put("event_stream", events.isEmpty() ? "（当日无重要事件）" : renderEvents(events));
        ph.put("weather_context", frameWeatherText(projectId, day));
        return callAiChronicle(userId, projectId, ph);
    }

    /** AI 编年史提炼调用（chronicle_distill 模板，重试≤2；失败返回空列表）。 */
    private List<ChronicleDistillParse.ChronicleEntry> callAiChronicle(Long userId, Long projectId,
                                                                        Map<String, Object> placeholders) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return List.of();
        }
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return AiCallContext.run(SCENE_CHRONICLE, null, attempt, () -> {
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_CHRONICLE_DISTILL, placeholders);
                    // 批6 收口（编年史提炼属「世界视角」）：前置 段1A【世界观】+ 段2D 当前世界动态（零阻断）
                    String prefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_CHRONICLE_DISTILL, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            0.3, 2048, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_CHRONICLE, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    return ChronicleDistillParse.parse(result.content());
                });
            } catch (Exception e) {
                log.warn("[项目记忆] 编年史 AI 提炼第 {} 次失败: project={} : {}", attempt, projectId, e.getMessage());
            }
        }
        return List.of();
    }

    /** AI 实体状态更新判定（entity_state_update 模板，重试≤2；失败返回空列表）。 */
    private List<EntityStateUpdateParse.EntityUpdate> aiEntityUpdate(Long userId, Long projectId, Long day,
                                                                     List<ActorWorldChronicle> chronicles,
                                                                     List<String> entityKeys,
                                                                     Map<String, ActorWorldEntity> current) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return List.of();
        }
        // 输入：条目摘要 + 实体键清单 + 当前状态基线
        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < chronicles.size() && i < 5; i++) {
            ActorWorldChronicle c = chronicles.get(i);
            entriesText.append("- ").append(c.getTitle()).append("：").append(c.getContent()).append("\n");
        }
        StringBuilder currentText = new StringBuilder();
        for (String key : entityKeys) {
            ActorWorldEntity e = current.get(key);
            currentText.append("- ").append(key)
                    .append(e == null ? "（无既有状态记录）" : "：" + e.getCurrentState()).append("\n");
        }
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("game_day", String.valueOf(day + 1));
        ph.put("chronicle_entries", entriesText.toString().trim());
        ph.put("entity_keys", String.join("\n", entityKeys));
        ph.put("current_states", currentText.toString().trim());
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return AiCallContext.run(SCENE_ENTITY, null, attempt, () -> {
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_ENTITY_STATE_UPDATE, ph);
                    // 批6 收口（实体状态更新属「世界视角」）：前置 段1A【世界观】+ 段2D 当前世界动态（零阻断）
                    String prefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_ENTITY_STATE_UPDATE, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            0.3, 2048, true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_ENTITY, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    return EntityStateUpdateParse.parse(result.content());
                });
            } catch (Exception e) {
                log.warn("[项目记忆] 实体状态判定第 {} 次失败: project={} : {}", attempt, projectId, e.getMessage());
            }
        }
        return List.of();
    }

    // ==================== 查询 ====================

    /**
     * 编年史查询（分页/按日区间/按实体/按重要度）。
     */
    public PageResult<WorldChronicleVO> chroniclePage(Long projectId, Long fromDay, Long toDay,
                                                      String entityKey, Integer minImportance,
                                                      int page, int size) {
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pr = PageRequest.of(safePage, safeSize);
        Page<ActorWorldChronicle> p;
        if (minImportance != null) {
            p = chronicleRepository.findByProjectIdAndImportanceGreaterThanEqualOrderByGameDayDescIdDesc(
                    projectId, minImportance, pr);
        } else if (entityKey != null && !entityKey.isBlank()) {
            p = chronicleRepository.findByProjectIdAndEntityKeyContaining(projectId, entityKey.trim(), pr);
        } else if (fromDay != null || toDay != null) {
            p = chronicleRepository.findByProjectIdAndDayRange(projectId, fromDay, toDay, pr);
        } else {
            p = chronicleRepository.findByProjectIdOrderByGameDayDescIdDesc(projectId, pr);
        }
        return PageResult.of(p.getContent().stream().map(WorldChronicleVO::of).toList(),
                p.getTotalElements(), page, safeSize);
    }

    /**
     * 编年史条目详情。
     */
    public WorldChronicleVO chronicleDetail(Long projectId, Long chronicleId) {
        ActorWorldChronicle c = chronicleRepository.findById(chronicleId)
                .orElseThrow(() -> new BizException(404, "编年史条目不存在"));
        if (!projectId.equals(c.getProjectId())) {
            throw new BizException(404, "编年史条目不存在");
        }
        return WorldChronicleVO.of(c);
    }

    /**
     * 实体当前状态查询（type 过滤 + as-of 按时间点查询历史态）。
     */
    public PageResult<WorldEntityVO> entityPage(Long projectId, String type, Long asOfGameDay,
                                                int page, int size) {
        int safePage = Math.max(0, page - 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pr = PageRequest.of(safePage, safeSize);
        Page<ActorWorldEntity> p;
        if (asOfGameDay != null) {
            // as-of：按时间点查询——当前有效行不满足时无法直接分页（历史态按实体键聚合查询），
            // 简化：as-of 模式返回「该日有效行」分页（按更新时间倒序，量小场景可用）
            List<ActorWorldEntity> all = entityRepository.findByProjectIdOrderByIdDesc(projectId);
            List<ActorWorldEntity> valid = all.stream()
                    .filter(e -> (type == null || type.isBlank() || type.trim().equals(e.getEntityType()))
                            && e.getValidFromGameDay() <= asOfGameDay
                            && (e.getValidToGameDay() == null || e.getValidToGameDay() > asOfGameDay))
                    .toList();
            int from = Math.min(safePage * safeSize, valid.size());
            int to = Math.min(from + safeSize, valid.size());
            return PageResult.of(valid.subList(from, to).stream().map(WorldEntityVO::of).toList(),
                    valid.size(), page, safeSize);
        }
        p = (type == null || type.isBlank())
                ? entityRepository.findByProjectIdAndValidToGameDayIsNullOrderByUpdatedAtDescIdDesc(projectId, pr)
                : entityRepository.findByProjectIdAndEntityTypeAndValidToGameDayIsNullOrderByUpdatedAtDescIdDesc(
                projectId, type.trim(), pr);
        return PageResult.of(p.getContent().stream().map(WorldEntityVO::of).toList(),
                p.getTotalElements(), page, safeSize);
    }

    /**
     * 单实体状态版本链（失效历史，新→旧）。
     */
    public List<WorldEntityVO> entityHistory(Long projectId, String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            throw new BizException(400, "entityKey 不能为空");
        }
        return entityRepository.findByProjectIdAndEntityKeyOrderByIdDesc(projectId, entityKey.trim())
                .stream().map(WorldEntityVO::of).toList();
    }

    /**
     * 项目记忆概览（编年史条数/实体数/最新条目/近期实体变化）。
     */
    public WorldMemoryOverviewVO overview(Long projectId) {
        List<ActorWorldChronicle> recent = chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(projectId);
        List<ActorWorldEntity> changes = entityRepository
                .findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(projectId);
        WorldChronicleVO latest = recent.isEmpty() ? null : WorldChronicleVO.of(recent.get(0));
        List<WorldEntityVO> latestChanges = changes.stream().limit(Math.max(1, entityInjectCount))
                .map(WorldEntityVO::of).toList();
        return new WorldMemoryOverviewVO(
                chronicleRepository.countByProjectId(projectId),
                entityRepository.countByProjectIdAndValidToGameDayIsNull(projectId),
                entityRepository.countByProjectId(projectId),
                archiveRepository.countByProjectId(projectId),
                latest,
                latestChanges);
    }

    // ==================== 手动补充 ====================

    /**
     * 手动补充编年史条目（用户/管理员）。
     */
    @Transactional
    public WorldChronicleVO manualAddChronicle(Long projectId, Long gameDay, String title, String content,
                                               Integer importance, String involvedEntities, String gameHour) {
        if (content == null || content.isBlank()) {
            throw new BizException(400, "内容不能为空");
        }
        Long day = gameDay != null ? gameDay : currentGameDay(projectId);
        if (day == null) {
            day = 0L;
        }
        ActorWorldChronicle ch = new ActorWorldChronicle();
        ch.setProjectId(projectId);
        ch.setGameDay(day);
        ch.setGameHour(gameHour != null && !gameHour.isBlank() ? Long.parseLong(gameHour.trim()) : null);
        // 编年史联动：手动补充同样填充 season/weather_snapshot（口径与 AI/机制提炼一致）
        ch.setSeason(seasonOfDay(day));
        ch.setWeatherSnapshot(weatherSnapshotOf(projectId, day));
        ch.setTitle(title == null || title.isBlank() ? abbreviate(content, 40) : title.trim());
        ch.setContent(content.trim());
        ch.setImportance(Math.max(1, Math.min(5, importance == null ? 3 : importance)));
        ch.setInvolvedEntities(involvedEntities == null || involvedEntities.isBlank() ? null : involvedEntities.trim());
        ch.setSource(ActorWorldChronicle.SOURCE_MANUAL);
        return WorldChronicleVO.of(chronicleRepository.save(ch));
    }

    /**
     * 手动更新实体状态（触发失效更新）。
     */
    @Transactional
    public WorldEntityVO manualUpdateEntity(Long projectId, String entityKey, String entityType, String name,
                                            String currentState, String recentChange, Long gameDay) {
        if (entityKey == null || entityKey.isBlank() || currentState == null || currentState.isBlank()) {
            throw new BizException(400, "entityKey 与 currentState 不能为空");
        }
        String key = normalizeEntityKey(entityKey);
        EntityRef ref = parseEntityKey(key);
        if (ref == null) {
            throw new BizException(400, "entityKey 需带类型前缀（location:/person:/faction:）");
        }
        Long day = gameDay != null ? gameDay : currentGameDay(projectId);
        if (day == null) {
            day = 0L;
        }
        ActorWorldEntity saved = upsertEntityState(projectId, ref.key(), ref.type(),
                name == null || name.isBlank() ? ref.name() : name.trim(),
                currentState.trim(), recentChange == null ? null : recentChange.trim(), day,
                ActorWorldEntity.PROVENANCE_MANUAL, null);
        return WorldEntityVO.of(saved);
    }

    // ==================== 注入 ====================

    /**
     * 【世界近闻】注入段：编年史最近 K 条（按游戏日降序，注入时带「听说」语义与时效）。
     *
     * @param projectId 项目 ID
     *
     * @param count 条数上限（≤0 用配置）
     *
     * @return 注入文本（无条目返回空串）
     */
    public String recentChronicleSegment(Long projectId, int count) {
        if (!enabled || projectId == null) {
            return "";
        }
        int n = count > 0 ? count : injectRecentCount;
        List<ActorWorldChronicle> recent = chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(projectId);
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【世界近闻】以下是流传于世界中的消息（编年史，按近期排序），回答时自然引用：\n");
        int k = 0;
        for (ActorWorldChronicle c : recent) {
            // 适配（_05）：世界诞生记录（source=worldgen）是「世界视角背景存档」，
            // 不是世界内流传的消息——过滤掉，防角色 OOC 感知「世界被创建」。
            if (ActorWorldChronicle.SOURCE_WORLDGEN.equals(c.getSource())) {
                continue;
            }
            if (k >= n) {
                break;
            }
            // 2026-10-10 决策：不设字数硬边界，编年史条目全量注入（不再 abbreviate 截断）
            sb.append("- [第").append(c.getGameDay() + 1).append("日] ")
                    .append(c.getContent()).append("\n");
            k++;
        }
        // 全部被过滤（如仅 worldgen 条目）→ 零注入（空段），防「空前缀」误导
        return k == 0 ? "" : sb.toString().trim();
    }

    /**
     * 记录「世界诞生」编年史条目。
     * <p>语义：source=worldgen 的条目只作「世界视角背景存档」，<b>不进任何角色感知注入</b>
     * （{@link #recentChronicleSegment} 已过滤；亦不发布世界事件、不进角色记忆链）——
     * 世界创建是「创世者视角」事实，角色自出生起就活在既定世界里，不应感知到「世界被创建」。
     * 幂等：同项目已存在 worldgen 来源条目 → 跳过（串接重跑不重复记）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param title 标题（如「世界「青禾村」诞生」）
     *
     * @param content 内容（世界创建摘要）
     *
     * @return 是否新写入（true=写入；false=已存在跳过）
     */
    @Transactional
    public boolean recordWorldGenChronicle(Long projectId, String title, String content) {
        if (projectId == null || content == null || content.isBlank()) {
            return false;
        }
        boolean exists = chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(projectId).stream()
                .anyMatch(c -> ActorWorldChronicle.SOURCE_WORLDGEN.equals(c.getSource()));
        if (exists) {
            log.info("[编年史-世界诞生] 已存在世界诞生记录，跳过：project={}", projectId);
            return false;
        }
        Long day = currentGameDay(projectId);
        if (day == null) {
            day = 0L;
        }
        ActorWorldChronicle ch = new ActorWorldChronicle();
        ch.setProjectId(projectId);
        ch.setGameDay(day);
        ch.setTitle(title == null || title.isBlank() ? "世界诞生" : title.trim());
        ch.setContent(content.trim());
        ch.setImportance(3);
        ch.setSource(ActorWorldChronicle.SOURCE_WORLDGEN);
        chronicleRepository.save(ch);
        log.info("[编年史-世界诞生] 已记录（不进角色感知）：project={}, day={}, title={}", projectId, day, ch.getTitle());
        return true;
    }

    /**
     * 【世界动态】注入段：相关实体状态变化（默认 nearby 过滤——按注入角色的所在地/关系网过滤，
     * 普通 NPC 仅注入其归属地实体；无相关实体时零注入，最真实）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 注入角色引用（统一 {@code npc:{id}}）
     *
     * @param count 条数上限（≤0 用配置）
     *
     * @return 注入文本（无变化/无相关实体返回空串）
     */
    public String entityDynamicsSegment(Long projectId, String characterRef, int count) {
        if (!enabled || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        int n = count > 0 ? count : entityInjectCount;
        List<ActorWorldEntity> changes = entityRepository
                .findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(projectId);
        if (changes.isEmpty()) {
            return "";
        }
        boolean nearby = !"all".equalsIgnoreCase(entityInjectScope);
        Set<String> nearbyKeys = nearby ? nearbyEntityKeys(projectId, characterRef) : null;
        StringBuilder sb = new StringBuilder();
        sb.append("【世界动态】以下是与你相关的世界实体最新变化（近期变化，回答时自然参考）：\n");
        int k = 0;
        for (ActorWorldEntity e : changes) {
            if (k >= n) {
                break;
            }
            if (nearby && !nearbyKeys.contains(e.getEntityKey())) {
                continue;
            }
            sb.append("- ").append(typeLabel(e.getEntityType())).append("·").append(e.getName())
                    .append("：").append(e.getRecentChange()).append("\n");
            k++;
        }
        if (k == 0) {
            return "";
        }
        return sb.toString().trim();
    }

    // ==================== 私有辅助 ====================

    /** 实体类型显示名（注入标签）。 */
    private static String typeLabel(String entityType) {
        if (ActorWorldEntity.TYPE_LOCATION.equals(entityType)) {
            return "地点";
        }
        if (ActorWorldEntity.TYPE_PERSON.equals(entityType)) {
            return "人物";
        }
        if (ActorWorldEntity.TYPE_FACTION.equals(entityType)) {
            return "势力";
        }
        return entityType == null ? "实体" : entityType;
    }

    /**
     * nearby 实体键集合（nearby 过滤判定）：location 实体=注入角色所在地点名匹配；
     * person 实体=角色本人名 + 关系网对端角色名匹配；faction 实体=普通 NPC 归属匹配。
     */
    Set<String> nearbyEntityKeys(Long projectId, String characterRef) {
        Set<String> keys = new LinkedHashSet<>();
        if (projectId == null || characterRef == null) {
            return keys;
        }
        // ① 角色所在地点
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（统一前缀解析成 actor_profile 行）
        Long selfId = NpcRefs.idOf(characterRef);
        ActorCharacter self = selfId == null ? null
                : characterRepository.findById(selfId).orElse(null);
        if (self != null && self.getLocation() != null && !self.getLocation().isBlank()) {
            keys.add("location:" + self.getLocation().trim());
        }
        // ② 角色本人 + 关系网对端（person 实体）
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（实体键统一 person:npc:{id}，兼容存量 person:{name}）
        if (selfId != null) {
            keys.add("person:" + NpcRefs.of(selfId));
        }
        String selfName = characterName(projectId, characterRef);
        if (selfName != null) {
            keys.add("person:" + selfName);
        }
        List<ActorCharacterRelation> relations = relationRepository.findByProjectId(projectId);
        for (ActorCharacterRelation r : relations) {
            String other = null;
            String otherRef = null;
            if (characterRef.equals(r.getFromRef())) {
                other = r.getToName();
                otherRef = r.getToRef();
            } else if (characterRef.equals(r.getToRef())) {
                other = r.getFromName();
                otherRef = r.getFromRef();
            }
            if (otherRef != null && NpcRefs.isNpcRef(otherRef)) {
                keys.add("person:" + otherRef);
            }
            if (other != null && !other.isBlank()) {
                keys.add("person:" + other.trim());
            }
        }
        // ③ 普通型 NPC 归属（faction 实体）；：npc:{id} 不再编码类型，此处按意图 B 处理（行内 type 为准）
        if (self != null && NpcRefs.isCommonType(self.getType())
                && self.getAffiliation() != null && !self.getAffiliation().isBlank()) {
            keys.add("faction:" + self.getAffiliation().trim());
        }
        return keys;
    }

    /** 角色名解析。 */
    private String characterName(Long projectId, String characterRef) {
        if (characterRef == null) {
            return null;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（统一前缀解析成 actor_profile 行）
        Long id = NpcRefs.idOf(characterRef);
        return id == null ? null
                : characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * 实体引用解析（名称匹配挂接）：在内容中查找项目地点名/特殊 NPC 名/普通 NPC 名/既有实体名
     * （最长匹配优先）→ 返回实体引用；无命中返回 null。
     * <p>actor_profile 单表合并后 NPC 实体键统一 {@code person:npc:{id}}（类型由行内 type 体现）。</p>
     */
    EntityRef resolveEntityRef(Long projectId, String content) {
        if (projectId == null || content == null || content.isBlank()) {
            return null;
        }
        EntityRef best = null;
        // ① 既有实体当前行名称（优先——保持同一实体键演进）
        List<ActorWorldEntity> existing = entityRepository.findByProjectIdOrderByIdDesc(projectId);
        for (ActorWorldEntity e : existing) {
            if (e.getValidToGameDay() == null && e.getName() != null
                    && content.contains(e.getName())
                    && (best == null || e.getName().length() > best.name().length())) {
                best = new EntityRef(e.getEntityKey(), e.getEntityType(), e.getName());
            }
        }
        // ② 地点表名称
        for (var loc : locationRepository.findByProjectId(projectId)) {
            String name = loc.getName();
            if (name != null && content.contains(name)
                    && (best == null || name.length() > best.name().length())) {
                best = new EntityRef("location:" + name, ActorWorldEntity.TYPE_LOCATION, name);
            }
        }
        // ③ 特殊 NPC 名称
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            String name = ch.getName();
            if (name != null && content.contains(name)
                    && (best == null || name.length() > best.name().length())) {
                // ：npc:{id} 不再编码类型，此处按意图 A 处理（实体键统一 person:npc:{id}，对齐 parseEntityKey）
                best = new EntityRef("person:" + NpcRefs.of(ch.getId()), ActorWorldEntity.TYPE_PERSON, name);
            }
        }
        // ④ 普通 NPC 名称
        for (ActorCharacter npc : npcRepository.findByProjectIdOrderByIdAsc(projectId)) {
            String name = npc.getName();
            if (name != null && content.contains(name)
                    && (best == null || name.length() > best.name().length())) {
                best = new EntityRef("person:" + NpcRefs.of(npc.getId()), ActorWorldEntity.TYPE_PERSON, name);
            }
        }
        return best;
    }

    /** 实体键归一化（无类型前缀时尝试按名称解析；解析失败原样返回）。 */
    private String normalizeEntityKey(String raw) {
        if (raw == null) {
            return raw;
        }
        String trimmed = raw.trim();
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return trimmed;
    }

    /** 解析实体键（带类型前缀；无前缀/非法返回 null）。 */
    static EntityRef parseEntityKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        int idx = trimmed.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        String type = trimmed.substring(0, idx);
        String rest = trimmed.substring(idx + 1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        if (ActorWorldEntity.TYPE_LOCATION.equals(type)) {
            return new EntityRef(trimmed, type, rest);
        }
        if (ActorWorldEntity.TYPE_PERSON.equals(type)) {
            // person:npc:{id} 显示名取 id 段（前端可解析）；person:{name} 显示名=名字
            // ：统一身份 npc:{id} 单前缀（原 special:/crowd: 已废弃）
            Long personId = com.holzyn.actor.common.NpcRefs.idOf(rest);
            String name = personId != null ? String.valueOf(personId) : rest;
            return new EntityRef(trimmed, type, name);
        }
        if (ActorWorldEntity.TYPE_FACTION.equals(type)) {
            return new EntityRef(trimmed, type, rest);
        }
        return null;
    }

    /** 实体引用（键/类型/显示名）。 */
    record EntityRef(String key, String type, String name) {
    }

    /** 事件是否已提炼（幂等：任一编年史 event_ids 含该事件 id）。 */
    private boolean eventAlreadyDistilled(Long projectId, Long eventId) {
        List<ActorWorldChronicle> all = chronicleRepository.findByProjectIdOrderByGameDayDesc(projectId);
        for (ActorWorldChronicle c : all) {
            List<Long> ids = parseLongList(c.getEventIds());
            if (ids.contains(eventId)) {
                return true;
            }
        }
        return false;
    }

    /** 当日事件流（game_hour 在该日范围内，重要度降序，≤上限）。 */
    private List<ActorEvent> dailyEvents(Long projectId, Long day) {
        List<ActorEvent> all = eventRepository.findByProjectIdOrderByIdDesc(projectId);
        long from = day * HOURS_PER_DAY;
        long to = from + HOURS_PER_DAY;
        List<ActorEvent> out = new ArrayList<>();
        for (ActorEvent e : all) {
            if (out.size() >= DAILY_EVENT_INPUT_LIMIT) {
                break;
            }
            Long gh = e.getGameHour();
            if (gh == null || gh < from || gh >= to) {
                continue;
            }
            out.add(e);
        }
        out.sort((a, b) -> Integer.compare(
                b.getImportance() == null ? 3 : b.getImportance(),
                a.getImportance() == null ? 3 : a.getImportance()));
        return out.size() > DAILY_EVENT_INPUT_LIMIT ? new ArrayList<>(out.subList(0, DAILY_EVENT_INPUT_LIMIT)) : out;
    }

    /** 渲染事件列表（编年史提炼输入）。 */
    private String renderEvents(List<ActorEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (ActorEvent e : events) {
            sb.append(renderEvent(e)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 渲染单事件（编年史提炼输入：时刻/事件id/标题/内容/重要度/地点——事件id 供 AI 输出 event_ids 引用）。 */
    private String renderEvent(ActorEvent e) {
        String time = e.getGameHour() == null ? "（时刻未知）"
                : "第" + (Math.floorDiv(e.getGameHour(), HOURS_PER_DAY) + 1) + "日 "
                + String.format("%02d时", Math.floorMod(e.getGameHour(), HOURS_PER_DAY));
        String loc = e.getLocationName() == null || e.getLocationName().isBlank() ? "" : " @" + e.getLocationName();
        String title = e.getTitle() == null ? "" : e.getTitle();
        String content = e.getContent() == null ? "" : e.getContent();
        // ⚠️ 冒烟抓修：事件id 必须显式带出——此前模板只写「对应输入事件流的编号」，
        // AI 输出的是流内序号（1/2/3…），而落库幂等检查按 actor_event 主键判定 → 序号与既有条目撞车
        // （如既有 event_ids=[3]）时新条目被误判「已提炼」整条丢弃（编年史静默丢失）；带出真实 id 后
        // AI 引用真实主键，幂等语义成立。
        return time + "（事件id " + e.getId() + "） [" + (e.getImportance() == null ? 3 : e.getImportance()) + "]"
                + loc
                + (title.isBlank() ? "" : " " + title)
                + (content.isBlank() ? "" : "：" + content);
    }

    /** 当日帧天气文本（编年史提炼输入；无帧返回空串）。 */
    private String frameWeatherText(Long projectId, Long day) {
        if (frameRepository == null || projectId == null || day == null) {
            return "（无天气信息）";
        }
        try {
            return frameRepository.findByProjectIdAndGameDay(projectId, day)
                    .map(f -> {
                        String weather = f.getWeatherJson();
                        return weather == null || weather.isBlank() ? "（无天气信息）" : weather;
                    }).orElse("（无天气信息）");
        } catch (Exception e) {
            return "（无天气信息）";
        }
    }

    // ==================== 编年史联动（season / weather_snapshot / 提炼季节上下文） ====================

    /**
     * 游戏日 → 季节（固定绑定派生，与 SeasonService.seasonOf 同源）：
     * 季节=按游戏月（1 月=30 日）固定绑定，故 day 的月份 = (day/30)%12+1，季节即月份绑定。
     * 与 {@link SeasonService#seasonOf(Long)} 在 gameSecond=day*86400 处完全一致（月边界=30 日整日）。
     *
     * @param day 游戏日序号（自纪元起；null → null）
     *
     * @return 季节 code（spring/summer/autumn/winter）
     */
    private static String seasonOfDay(Long day) {
        if (day == null) {
            return null;
        }
        return SeasonService.seasonOf(day * 86400L);
    }

    /**
     * 当日天气快照（编年史 weather_snapshot 列）：取当日帧 weather_json「首区域」（帧 JSON 为
     * 区域→{condition,temperature,wind}；编年史按「主区域/首区域」口径与统一环境服务一致）→
     * 压缩为 {@code {"condition":"晴","temperature":5,"wind":"东风"}}。
     * 温度整数值输出整数（避免 5.0/15.0 类型抖动）；无帧/无区域/解析失败返回 null（列保持 NULL）。
     *
     * @param projectId 项目 ID
     *
     * @param day 游戏日序号
     *
     * @return 天气快照 JSON 文本（可空）
     */
    private String weatherSnapshotOf(Long projectId, Long day) {
        if (frameRepository == null || projectId == null || day == null) {
            return null;
        }
        try {
            return frameRepository.findByProjectIdAndGameDay(projectId, day).map(f -> {
                String weather = f.getWeatherJson();
                if (weather == null || weather.isBlank()) {
                    return null;
                }
                JsonNode root = MAPPER.readTree(weather);
                if (root == null || !root.isObject()) {
                    return null;
                }
                var it = root.properties().iterator();
                if (!it.hasNext()) {
                    return null;
                }
                JsonNode region = it.next().getValue();
                if (region == null || !region.isObject()) {
                    return null;
                }
                JsonNode condition = region.get("condition");
                if (condition == null || !condition.isTextual() || condition.asText().trim().isBlank()) {
                    return null;
                }
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("condition", condition.asText().trim());
                JsonNode temperature = region.get("temperature");
                if (temperature != null && temperature.isNumber()) {
                    double t = temperature.doubleValue();
                    // 整数值输出整数（防 15.0 类型抖动）；注意不能用三元——long/double 合并会被二元数值提升回 double
                    if (t == Math.floor(t) && !Double.isInfinite(t)) {
                        snap.put("temperature", (long) t);
                    } else {
                        snap.put("temperature", t);
                    }
                }
                JsonNode wind = region.get("wind");
                if (wind != null && wind.isTextual() && !wind.asText().isBlank()) {
                    snap.put("wind", wind.asText().trim());
                }
                return MAPPER.writeValueAsString(snap);
            }).orElse(null);
        } catch (Exception e) {
            log.warn("[项目记忆] 编年史天气快照解析失败（保持 null）: project={} day={} : {}",
                    projectId, day, e.getMessage());
            return null;
        }
    }

    /**
     * 编年史提炼输入的季节上下文段（chronicle_distill 模板 {{season_context}}）：
     * 「当前季节：冬（游戏月 11）；日出 06:55 日落 17:25」——供 AI 编年史描述带季节感；
     * seasonService 未装配时仅季节名+月份（日出日落降级省略）。
     *
     * @param projectId 项目 ID
     *
     * @param day 游戏日序号
     *
     * @return 季节上下文文本（恒非空；无 day 返回空串）
     */
    private String seasonContextForDistill(Long projectId, Long day) {
        if (day == null) {
            return "";
        }
        String season = seasonOfDay(day);
        if (season == null) {
            return "";
        }
        int month = (int) ((day / 30L) % 12L) + 1;
        StringBuilder sb = new StringBuilder("当前季节：").append(SeasonService.seasonDisplayName(season))
                .append("（游戏月 ").append(month).append("）");
        if (seasonService != null) {
            try {
                SeasonService.SeasonSkeleton sk = seasonService.skeletonOf(projectId, season);
                sb.append("；日出 ").append(SeasonService.minuteToText(
                                SeasonService.sunriseMinuteOf(sk, season, day)))
                        .append(" 日落 ").append(SeasonService.minuteToText(
                                SeasonService.sunsetMinuteOf(sk, season, day)));
            } catch (Exception e) {
                log.debug("[项目记忆] 季节上下文日出日落计算失败（降级省略）: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    /** 当前游戏日（世界时钟解析；失败返回 null）。 */
    private Long currentGameDay(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        try {
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            if (sec != null) {
                return Math.floorDiv(sec, 86400L);
            }
            Long hour = worldClockService.currentEngineGameHour(projectId);
            return hour == null ? null : Math.floorDiv(hour, HOURS_PER_DAY);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 独立每日 AI 预算：消耗 1 次（跨游戏日自动重置；超上限返回 false=转批量）。
     * 微层接线：生效上限按 AI 强度档位折算——light=ceil(原值×0.5) / max=不限 /
     * programmatic=0（零 AI，编年史全走机制兜底）/ full=原值。
     */
    private synchronized boolean tryConsumeDailyAi(Long projectId, Long gameDay) {
        int cap = aiIntensityGate == null ? dailyAiCap
                : Math.max(0, aiIntensityGate.effectiveCap(projectId, dailyAiCap));
        if (cap <= 0) {
            return false;
        }
        long[] cur = dailyAiBudget.computeIfAbsent(projectId, k -> new long[]{gameDay, 0});
        if (cur[0] != gameDay) {
            cur[0] = gameDay;
            cur[1] = 0;
        }
        if (cur[1] >= cap) {
            log.debug("[项目记忆] 独立每日 AI 预算耗尽，转批量: project={} day={} used={}",
                    projectId, gameDay, cur[1]);
            return false;
        }
        cur[1]++;
        return true;
    }

    /** 文本截断（中文按字符）。 */
    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    /** JSON 序列化（列表 → JSON 文本；空列表返回 null）。 */
    private static String toJson(List<?> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析字符串列表（JSON 文本 → 列表；非法/空返回空列表）。 */
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 解析 Long 列表（JSON 文本 → 列表；非法/空返回空列表）。 */
    public static List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
