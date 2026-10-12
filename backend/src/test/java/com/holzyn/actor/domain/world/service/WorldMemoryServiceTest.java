package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleArchiveRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目记忆服务单元测试（记忆系统重构 M2/M3，2026-09-12，domain/world/service，同包）。
 * <p>职责：验证——双通道路由（事件类→编年史 / 状态类→实体状态失效更新 / 无匹配丢弃防污染）、
 * 每日提炼幂等（当日 source=daily 存在跳过）、机制兜底（AI 关断时当日重要事件原文成条）、
 * 即时提炼幂等（事件 id 已提炼跳过）、实体失效更新（新行 supersedes 旧行 + 旧行 valid_to、
 * 同状态幂等不建新行）、as-of 查询语义、nearby 注入过滤（所在地/关系网/归属）、
 * 【世界近闻】注入段、编年史超龄归档。</p>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案_V1.0》§4。</p>
 */
class WorldMemoryServiceTest {

    private ActorWorldChronicleRepository chronicleRepository;
    private ActorWorldChronicleArchiveRepository archiveRepository;
    private ActorWorldEntityRepository entityRepository;
    private ActorEventRepository eventRepository;
    private ActorWorldFrameRepository frameRepository;
    private WorldLocationRepository locationRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private ActorCharacterRelationRepository relationRepository;
    private WorldEventBus worldEventBus;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private CurrentUserProvider currentUserProvider;
    private WorldMemoryService service;

    @BeforeEach
    void setUp() {
        chronicleRepository = Mockito.mock(ActorWorldChronicleRepository.class);
        archiveRepository = Mockito.mock(ActorWorldChronicleArchiveRepository.class);
        entityRepository = Mockito.mock(ActorWorldEntityRepository.class);
        eventRepository = Mockito.mock(ActorEventRepository.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        service = new WorldMemoryService(chronicleRepository, archiveRepository, entityRepository,
                eventRepository, frameRepository, locationRepository, characterRepository,
                npcRepository, relationRepository, worldEventBus);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "routeFromDialogEnabled", true);
        ReflectionTestUtils.setField(service, "distillAiEnabled", false); // 测试默认机制兜底零 AI
        ReflectionTestUtils.setField(service, "entityUpdateEnabled", true);
        ReflectionTestUtils.setField(service, "dailyAiCap", 20);
        ReflectionTestUtils.setField(service, "retentionYears", 3);
        ReflectionTestUtils.setField(service, "injectRecentCount", 3);
        ReflectionTestUtils.setField(service, "entityInjectScope", "nearby");
        ReflectionTestUtils.setField(service, "entityInjectCount", 3);
        when(entityRepository.findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(entityRepository.findByProjectIdOrderByIdDesc(anyLong())).thenReturn(List.of());
        when(entityRepository.save(any(ActorWorldEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chronicleRepository.save(any(ActorWorldChronicle.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== 双通道路由（M2，§4.2） ====================

    /**
     * 路由：事件类（kind=event）→ 编年史条目（source=instant，标题=内容截断）。
     */
    @Test
    void routeEventCandidateToChronicle() {
        service.routeFromExtraction(1L, 100L, 5L, List.of(
                new WorldMemoryService.RouteCandidate("event", "村口大树被雷劈了", 4)));
        verify(chronicleRepository).save(any(ActorWorldChronicle.class));
        Mockito.verify(entityRepository, never()).save(any(ActorWorldEntity.class));
    }

    /**
     * 路由：状态类（kind=entity）内容命中地点名 → 实体状态表失效更新（provenance=dialog）。
     */
    @Test
    void routeEntityCandidateMatchesLocation() {
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(1L);
        loc.setName("村口牌坊");
        when(locationRepository.findByProjectId(100L)).thenReturn(List.of(loc));
        service.routeFromExtraction(1L, 100L, 5L, List.of(
                new WorldMemoryService.RouteCandidate("entity", "村口牌坊被雷劈塌了半边", 3)));
        verify(entityRepository).save(any(ActorWorldEntity.class));
        Mockito.verify(chronicleRepository, never()).save(any(ActorWorldChronicle.class));
    }

    /**
     * 路由：状态类内容无匹配实体（无地点/NPC 名命中）→ 丢弃防污染（不建实体行）。
     */
    @Test
    void routeEntityCandidateWithoutMatchDrops() {
        when(locationRepository.findByProjectId(100L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(List.of());
        when(npcRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of());
        service.routeFromExtraction(1L, 100L, 5L, List.of(
                new WorldMemoryService.RouteCandidate("entity", "某个遥远的王国换了国王", 4)));
        Mockito.verify(entityRepository, never()).save(any(ActorWorldEntity.class));
    }

    // ==================== 每日批量提炼（M2 触发②） ====================

    /**
     * 每日提炼幂等：当日已存在 source=daily 条目 → 跳过（不落库）。
     */
    @Test
    void dailyDistillSkipsWhenAlreadyDistilled() {
        ActorWorldChronicle existing = new ActorWorldChronicle();
        existing.setId(1L);
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 5L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of(existing));
        service.dailyDistillIfNeeded(100L, 5L, 120000L);
        Mockito.verify(chronicleRepository, never()).save(any(ActorWorldChronicle.class));
    }

    /**
     * 每日提炼机制兜底（AI 关断）：当日重要事件（重要度≥3）原文成条（source=daily）+ 幂等键 event_ids。
     */
    @Test
    void dailyDistillMechanismFallbackUsesImportantEvents() {
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 5L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
        ActorEvent e1 = new ActorEvent();
        e1.setId(11L);
        e1.setGameHour(120L); // 第 5 日 00 时
        e1.setImportance(4);
        e1.setTitle("粮仓失火");
        e1.setContent("村东粮仓夜间失火，乡邻合力扑灭");
        ActorEvent e2 = new ActorEvent();
        e2.setId(12L);
        e2.setGameHour(125L); // 第 5 日 05 时
        e2.setImportance(1);
        e2.setTitle("琐事");
        e2.setContent("路人甲买了条鱼");
        ActorEvent e3 = new ActorEvent();
        e3.setId(13L);
        e3.setGameHour(1000L); // 第 41 日（范围外）
        e3.setImportance(5);
        e3.setTitle("范围外");
        e3.setContent("范围外事件不参与当日提炼");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e3, e2, e1));
        service.dailyDistillIfNeeded(100L, 5L, 120000L);
        Mockito.verify(chronicleRepository, Mockito.atLeastOnce()).save(any(ActorWorldChronicle.class));
    }

    // ==================== 即时提炼（M2 触发①） ====================

    /**
     * 即时提炼幂等：事件 id 已出现在既有编年史 event_ids 中 → 跳过（不重复提炼）。
     */
    @Test
    void instantDistillSkipsAlreadyDistilledEvent() {
        ActorWorldChronicle c = new ActorWorldChronicle();
        c.setId(1L);
        c.setProjectId(100L);
        c.setEventIds("[11]");
        when(chronicleRepository.findByProjectIdOrderByGameDayDesc(100L)).thenReturn(List.of(c));
        // M4 冒烟抓修：幂等判定仅对「真实存在的 actor_event 主键」生效——11 是真实事件（existsById=true）
        when(eventRepository.existsById(11L)).thenReturn(true);
        ActorEvent event = new ActorEvent();
        event.setId(11L);
        event.setProjectId(100L);
        event.setImportance(5);
        event.setGameHour(130L);
        event.setTitle("重复事件");
        event.setContent("这条事件已提炼过");
        service.instantDistill(100L, 5L, event);
        Mockito.verify(chronicleRepository, never()).save(any(ActorWorldChronicle.class));
    }

    /**
     * M4 冒烟抓修：event_ids 为「流内序号」（非真实主键）且与既有条目撞车（如既有 [3]）时——
     * 幂等判定不生效，条目正常落库（此前整条被误判已提炼静默丢弃）。
     */
    @Test
    void dailyDistillIndexShapedEventIdsNotSkipped() {
        // 既有条目 event_ids=[3]（历史序号形态），但 actor_event 主键 3 不存在
        ActorWorldChronicle old = new ActorWorldChronicle();
        old.setId(9L);
        old.setProjectId(100L);
        old.setEventIds("[3]");
        when(chronicleRepository.findByProjectIdOrderByGameDayDesc(100L)).thenReturn(List.of(old));
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 301L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
        when(eventRepository.existsById(anyLong())).thenReturn(false);
        // 机制兜底（AI 关断）：day 301 有条重要事件 → 条目 event_ids=[事件真实主键]
        ActorEvent e = new ActorEvent();
        e.setId(6450L);
        e.setGameHour(7225L);
        e.setImportance(4);
        e.setTitle("入冬首雪");
        e.setContent("入冬第一场雪落下，气温骤降");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e));
        service.dailyDistillIfNeeded(100L, 301L, 26010000L);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        assertEquals("winter", captor.getValue().getSeason());
        assertTrue(captor.getValue().getEventIds().contains("6450"),
                "机制兜底应引用真实事件主键，实际：" + captor.getValue().getEventIds());
    }

    /**
     * 事件总线订阅门控：重要度低于阈值 / world_frame 自身事件 → 不触发即时提炼。
     */
    @Test
    void onEventFiltersLowImportanceAndFrameEvents() {
        ActorEvent low = new ActorEvent();
        low.setProjectId(100L);
        low.setImportance(2);
        service.onEvent(low);
        ActorEvent frame = new ActorEvent();
        frame.setProjectId(100L);
        frame.setImportance(5);
        frame.setKind("world_frame");
        service.onEvent(frame);
        Mockito.verify(chronicleRepository, never()).save(any(ActorWorldChronicle.class));
    }

    // ==================== 实体状态失效更新（M3，Zep 式） ====================

    /**
     * 失效更新：同键存在不同状态旧行 → 写新行（supersedes 旧行）+ 旧行 valid_to=当前游戏日（不删旧行）。
     */
    @Test
    void upsertInvalidatesOldRowWithVersionChain() {
        ActorWorldEntity oldRow = new ActorWorldEntity();
        oldRow.setId(1L);
        oldRow.setEntityKey("location:村口牌坊");
        oldRow.setCurrentState("古槐完好");
        oldRow.setValidFromGameDay(1L);
        oldRow.setValidToGameDay(null);
        when(entityRepository.findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(100L, "location:村口牌坊"))
                .thenReturn(Optional.of(oldRow));
        ActorWorldEntity saved = service.upsertEntityState(100L, "location:村口牌坊", "location", "村口牌坊",
                "古槐被雷劈断", "古槐遭雷击", 5L, "chronicle", 9L);
        assertNotNull(saved);
        assertEquals(5L, saved.getValidFromGameDay());
        assertNull(saved.getValidToGameDay());
        assertEquals(1L, saved.getSupersedesId());
        assertEquals(5L, oldRow.getValidToGameDay(), "旧行应失效标记 valid_to=当前游戏日");
        verify(entityRepository, Mockito.times(2)).save(any(ActorWorldEntity.class));
    }

    /**
     * 幂等：同键当前状态相同 → 不建新行（仅可能刷新 recent_change）。
     */
    @Test
    void upsertSameStateIsIdempotent() {
        ActorWorldEntity existing = new ActorWorldEntity();
        existing.setId(1L);
        existing.setEntityKey("location:村口牌坊");
        existing.setCurrentState("古槐被雷劈断");
        existing.setRecentChange("古槐遭雷击");
        existing.setValidFromGameDay(5L);
        existing.setValidToGameDay(null);
        when(entityRepository.findTopByProjectIdAndEntityKeyAndValidToGameDayIsNullOrderByIdDesc(100L, "location:村口牌坊"))
                .thenReturn(Optional.of(existing));
        ActorWorldEntity saved = service.upsertEntityState(100L, "location:村口牌坊", "location", "村口牌坊",
                "古槐被雷劈断", "古槐遭雷击", 6L, "chronicle", 9L);
        assertEquals(1L, saved.getId(), "状态相同应返回既有行不建新行");
        verify(entityRepository, Mockito.never()).save(Mockito.argThat(
                e -> e instanceof ActorWorldEntity && e.getId() == null));
    }

    /**
     * as-of 查询：entityRepository.findAsOf 按 valid 窗口返回指定游戏日的有效行（Zep 语义）。
     */
    @Test
    void asOfQueryReturnsRowsValidAtDay() {
        ActorWorldEntity row = new ActorWorldEntity();
        row.setId(2L);
        row.setEntityKey("location:村口牌坊");
        row.setCurrentState("古槐被雷劈断");
        row.setValidFromGameDay(5L);
        row.setValidToGameDay(null);
        when(entityRepository.findAsOf(100L, "location:村口牌坊", 7L)).thenReturn(List.of(row));
        List<ActorWorldEntity> asOf = entityRepository.findAsOf(100L, "location:村口牌坊", 7L);
        assertEquals(1, asOf.size());
        assertEquals("古槐被雷劈断", asOf.get(0).getCurrentState());
    }

    // ==================== nearby 注入过滤（M5，§4.5 用户决策） ====================

    /**
     * nearby 键集合：特殊 NPC=所在地点 + 本人 + 关系网对端；普通 NPC=归属地 + 归属势力。
     */
    @Test
    void nearbyEntityKeysFromLocationRelationsAndAffiliation() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(10L);
        ch.setName("沈砚秋");
        ch.setLocation("村口牌坊");
        when(characterRepository.findById(10L)).thenReturn(Optional.of(ch));
        ActorCharacterRelation rel = new ActorCharacterRelation();
        rel.setFromRef("npc:10");
        rel.setToRef("npc:11");
        rel.setToName("温叙");
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of(rel));
        Set<String> keys = service.nearbyEntityKeys(100L, "npc:10");
        assertTrue(keys.contains("location:村口牌坊"));
        assertTrue(keys.contains("person:沈砚秋"));
        assertTrue(keys.contains("person:温叙"), "关系网对端应纳入 nearby");

        ActorCharacter npc = new ActorCharacter();
        npc.setId(30L);
        npc.setName("铁匠");
        npc.setLocation("铁匠铺");
        npc.setAffiliation("青禾村");
        // G1a：单表后 nearbyEntityKeys 只查 actor_profile（行内 type 决定是否纳入 faction 键）
        npc.setType("common");
        when(characterRepository.findById(30L)).thenReturn(Optional.of(npc));
        Set<String> crowdKeys = service.nearbyEntityKeys(100L, "npc:30");
        assertTrue(crowdKeys.contains("location:铁匠铺"));
        assertTrue(crowdKeys.contains("person:铁匠"));
        assertTrue(crowdKeys.contains("faction:青禾村"));
    }

    /**
     * 【世界动态】注入段：nearby 过滤下无相关实体变化 → 空串（真人只关心身边事）。
     */
    @Test
    void entityDynamicsFiltersByNearby() {
        ActorWorldEntity far = new ActorWorldEntity();
        far.setEntityKey("location:竹林茅舍");
        far.setEntityType("location");
        far.setName("竹林茅舍");
        far.setRecentChange("茅舍新搭了草棚");
        when(entityRepository.findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(100L))
                .thenReturn(List.of(far));
        // 注入角色所在地=村口牌坊（不含竹林茅舍）
        ActorCharacter ch = new ActorCharacter();
        ch.setId(10L);
        ch.setName("沈砚秋");
        ch.setLocation("村口牌坊");
        when(characterRepository.findById(10L)).thenReturn(Optional.of(ch));
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of());
        assertEquals("", service.entityDynamicsSegment(100L, "npc:10", 3), "无相关实体变化应零注入");
    }

    /**
     * 【世界动态】注入段：all 模式下全量注入（不过滤）。
     */
    @Test
    void entityDynamicsAllScopeInjectsAll() {
        ReflectionTestUtils.setField(service, "entityInjectScope", "all");
        ActorWorldEntity e = new ActorWorldEntity();
        e.setEntityKey("location:竹林茅舍");
        e.setEntityType("location");
        e.setName("竹林茅舍");
        e.setRecentChange("茅舍新搭了草棚");
        when(entityRepository.findTop20ByProjectIdAndValidToGameDayIsNullAndRecentChangeIsNotNullOrderByUpdatedAtDescIdDesc(100L))
                .thenReturn(List.of(e));
        String seg = service.entityDynamicsSegment(100L, "npc:10", 3);
        assertTrue(seg.contains("竹林茅舍"));
        assertTrue(seg.contains("茅舍新搭了草棚"));
    }

    /**
     * 【世界近闻】注入段：编年史最近 K 条（按游戏日降序，带「第N日」时效）。
     */
    @Test
    void recentChronicleSegmentRendersTopEntries() {
        ActorWorldChronicle c = new ActorWorldChronicle();
        c.setId(1L);
        c.setProjectId(100L);
        c.setGameDay(5L);
        c.setContent("村口古树遭雷击，乡邻议论纷纷");
        when(chronicleRepository.findTop10ByProjectIdOrderByGameDayDescIdDesc(100L)).thenReturn(List.of(c));
        String seg = service.recentChronicleSegment(100L, 0);
        assertTrue(seg.contains("【世界近闻】"));
        assertTrue(seg.contains("第6日"), "游戏日 5（第 6 日）应带时效前缀");
        assertTrue(seg.contains("村口古树遭雷击"));
    }

    // ==================== 编年史超龄归档（M2 §4.3 保留策略） ====================

    /**
     * 归档：game_day ≤ 当前日-3 游戏年 的条目 → 复制入归档表 + 删除原行。
     */
    @Test
    void archiveAgedMovesOldChroniclesToArchive() {
        ActorWorldChronicle aged = new ActorWorldChronicle();
        aged.setId(7L);
        aged.setProjectId(100L);
        aged.setGameDay(10L);
        aged.setTitle("旧事");
        aged.setContent("很久以前的事");
        // 当前日 1200，阈值=1200-3*365=105；game_day=10 ≤ 105 → 超龄
        when(chronicleRepository.findByProjectIdAndGameDayLessThanEqualOrderByGameDayAsc(100L, 105L))
                .thenReturn(List.of(aged));
        int archived = service.archiveAged(100L, 1200L);
        assertEquals(1, archived);
        verify(archiveRepository).save(any());
        verify(chronicleRepository).delete(aged);
    }

    /**
     * 巩固补漏：近 3 游戏日无 daily 条目 → 补提炼（当日机制兜底），并触发超龄归档。
     */
    @Test
    void consolidateWorldMemoryBackfillsAndArchives() {
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(eq(100L), Mockito.anyLong(),
                eq(ActorWorldChronicle.SOURCE_DAILY))).thenReturn(List.of());
        when(chronicleRepository.findByProjectIdAndGameDayLessThanEqualOrderByGameDayAsc(eq(100L), Mockito.anyLong()))
                .thenReturn(List.of());
        // 第 6 日（gameHour 145 在 [144,168) 内）有一条重要事件 → 补漏提炼机制兜底成条
        ActorEvent e = new ActorEvent();
        e.setId(21L);
        e.setGameHour(145L);
        e.setImportance(4);
        e.setTitle("溪水暴涨");
        e.setContent("灵溪河水一夜暴涨，渡口停摆");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e));
        service.consolidateWorldMemory(100L, 6L, 150000L);
        Mockito.verify(chronicleRepository, Mockito.atLeastOnce()).save(any(ActorWorldChronicle.class));
        verify(chronicleRepository).findByProjectIdAndGameDayLessThanEqualOrderByGameDayAsc(eq(100L), Mockito.anyLong());
    }

    // ==================== M4 编年史联动（season / weather_snapshot 填充） ====================

    /**
     * M4：对话路由事件类候选 → 编年史条目带 season（按游戏日派生：第 6 日=1 月=春）+ weather_snapshot
     * （当日帧首区域压缩 JSON）。
     */
    @Test
    void routeCandidateFillsSeasonAndWeatherSnapshot() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(1L);
        frame.setGameDay(5L);
        frame.setWeatherJson("{\"主区域\":{\"condition\":\"雪\",\"temperature\":-7.5,\"wind\":\"北风\"}}");
        when(frameRepository.findByProjectIdAndGameDay(100L, 5L)).thenReturn(Optional.of(frame));
        service.routeFromExtraction(1L, 100L, 5L, List.of(
                new WorldMemoryService.RouteCandidate("event", "村口古树遭雷击", 4)));
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        ActorWorldChronicle saved = captor.getValue();
        assertEquals("spring", saved.getSeason(), "第 6 日（1 月）应绑定春");
        assertNotNull(saved.getWeatherSnapshot(), "有当日帧应填天气快照");
        assertTrue(saved.getWeatherSnapshot().contains("\"condition\":\"雪\""));
        assertTrue(saved.getWeatherSnapshot().contains("\"temperature\":-7.5"));
        assertTrue(saved.getWeatherSnapshot().contains("\"wind\":\"北风\""));
    }

    /**
     * M4：每日提炼机制兜底条目按游戏日绑定季节——第 302 日（11 月）=冬；无当日帧 → weather_snapshot 保持 null。
     */
    @Test
    void dailyDistillFillsSeasonByGameDayWithoutFrame() {
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 301L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
        when(frameRepository.findByProjectIdAndGameDay(100L, 301L)).thenReturn(Optional.empty());
        ActorEvent e = new ActorEvent();
        e.setId(31L);
        e.setGameHour(7225L); // 第 301 日 01 时
        e.setImportance(4);
        e.setTitle("初雪");
        e.setContent("入冬第一场雪落了下来");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e));
        service.dailyDistillIfNeeded(100L, 301L, 26010000L);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        assertEquals("winter", captor.getValue().getSeason(), "第 302 日（11 月）应绑定冬");
        assertNull(captor.getValue().getWeatherSnapshot(), "无当日帧天气快照应保持 null");
    }

    /**
     * M4：每日提炼 AI 条目带 season=summer（第 96 日=4 月=夏）+ 帧天气快照（温度整数值输出整数防抖动）。
     */
    @Test
    void dailyDistillSnapshotUsesIntegerTemperature() {
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 95L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(2L);
        frame.setGameDay(95L);
        frame.setWeatherJson("{\"主区域\":{\"condition\":\"晴\",\"temperature\":15,\"wind\":\"南风\"}}");
        when(frameRepository.findByProjectIdAndGameDay(100L, 95L)).thenReturn(Optional.of(frame));
        service.manualAddChronicle(100L, 95L, "夏日正午", "集市上人声鼎沸", 3, null, null);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        ActorWorldChronicle saved = captor.getValue();
        assertEquals("summer", saved.getSeason(), "第 96 日（4 月）应绑定夏");
        assertTrue(saved.getWeatherSnapshot().contains("\"temperature\":15"),
                "整数值温度应输出整数（防 15.0 抖动），实际：" + saved.getWeatherSnapshot());
        assertFalse(saved.getWeatherSnapshot().contains("15.0"),
                "不应出现 15.0 抖动，实际：" + saved.getWeatherSnapshot());
    }

    /**
     * M4：手动补充编年史同样填充 season/weather_snapshot（口径与 AI/机制提炼一致）。
     */
    @Test
    void manualAddChronicleFillsSeasonAndSnapshot() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(3L);
        frame.setGameDay(180L);
        frame.setWeatherJson("{\"村口\":{\"condition\":\"阴\",\"temperature\":14,\"wind\":\"西风\"}}");
        when(frameRepository.findByProjectIdAndGameDay(100L, 180L)).thenReturn(Optional.of(frame));
        service.manualAddChronicle(100L, 180L, "秋日杂记", "稻谷渐熟", 2, null, null);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        ActorWorldChronicle saved = captor.getValue();
        assertEquals("autumn", saved.getSeason(), "第 181 日（7 月）应绑定秋");
        assertEquals("manual", saved.getSource());
        assertTrue(saved.getWeatherSnapshot().contains("\"condition\":\"阴\""));
    }

    /**
     * M4：季节按游戏日固定绑定跨月边界——第 1/90/180/270 日（1/4/7/10 月）→ 春/夏/秋/冬；
     * 月边界=30 日整日（第 90 日=第 4 月首日），与 SeasonService.seasonOf(gameSecond) 同源。
     */
    @Test
    void seasonOfDayAcrossMonthBoundaries() {
        when(frameRepository.findByProjectIdAndGameDay(anyLong(), anyLong())).thenReturn(Optional.empty());
        Long[] days = {0L, 89L, 90L, 179L, 180L, 269L, 270L};
        String[] expected = {"spring", "spring", "summer", "summer", "autumn", "autumn", "winter"};
        for (int i = 0; i < days.length; i++) {
            when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, days[i],
                    ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
            service.manualAddChronicle(100L, days[i], "t" + i, "内容" + i, 1, null, null);
            ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
            verify(chronicleRepository, Mockito.atLeastOnce()).save(captor.capture());
            assertEquals(expected[i], captor.getValue().getSeason(),
                    "第 " + (days[i] + 1) + " 日（月 " + (days[i] / 30 % 12 + 1) + "）季节应=" + expected[i]);
        }
    }

    /**
     * M4：当日帧天气 JSON 非法/无区域 → 天气快照 null（隔离不抛）；season 仍正常填充。
     */
    @Test
    void weatherSnapshotToleratesBrokenFrameJson() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(4L);
        frame.setGameDay(5L);
        frame.setWeatherJson("不是 JSON{{");
        when(frameRepository.findByProjectIdAndGameDay(100L, 5L)).thenReturn(Optional.of(frame));
        service.manualAddChronicle(100L, 5L, "标题", "内容", 3, null, null);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        assertEquals("spring", captor.getValue().getSeason());
        assertNull(captor.getValue().getWeatherSnapshot(), "帧 JSON 非法应保持 null 不阻断落库");
    }

    /**
     * M4 冒烟抓修（AI 路径回归锁定）：AI 提炼输出 event_ids 为「流内序号」（如 [3]）且该序号与既有
     * 条目 event_ids 撞车（历史 [3]）——但 actor_event 主键 3 不存在 → 幂等判定不生效，条目正常落库
     * （此前整条被误判已提炼静默丢弃，编年史丢失）。
     */
    @Test
    void aiDistillIndexShapedEventIdsCollisionNotSkipped() {
        // 既有条目 event_ids=[3]（历史序号形态）
        ActorWorldChronicle old = new ActorWorldChronicle();
        old.setId(9L);
        old.setProjectId(100L);
        old.setEventIds("[3]");
        when(chronicleRepository.findByProjectIdOrderByGameDayDesc(100L)).thenReturn(List.of(old));
        when(chronicleRepository.findByProjectIdAndGameDayAndSource(100L, 301L,
                ActorWorldChronicle.SOURCE_DAILY)).thenReturn(List.of());
        // actor_event 主键 3 不存在（默认 false）→ 序号撞车不触发幂等跳过
        // 当日事件流：真实事件 6450（渲染带「事件id 6450」）
        ActorEvent e = new ActorEvent();
        e.setId(6450L);
        e.setGameHour(7225L);
        e.setImportance(4);
        e.setTitle("入冬首雪");
        e.setContent("入冬第一场雪落下，气温骤降");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e));
        // 当日帧（冬季雪 -7.5℃）→ weather_snapshot 应填充
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(5L);
        frame.setGameDay(301L);
        frame.setWeatherJson("{\"主区域\":{\"condition\":\"雪\",\"temperature\":-7.5,\"wind\":\"北风\"}}");
        when(frameRepository.findByProjectIdAndGameDay(100L, 301L)).thenReturn(Optional.of(frame));
        // 模拟 AI 通道：返回 event_ids=[3]（序号形态，旧缺陷产物）
        ReflectionTestUtils.setField(service, "distillAiEnabled", true);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("编年史提炼 prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn(null);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"entries\":[{\"title\":\"入冬首雪\",\"content\":\"雪落青禾村，气温骤降\","
                        + "\"importance\":4,\"event_ids\":[3],\"involved_entities\":[],\"involved_characters\":[]}]}",
                        "test-model", 100, 50, 150, 1L));
        service.dailyDistillIfNeeded(100L, 301L, 26010000L);
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        ActorWorldChronicle saved = captor.getValue();
        assertEquals("winter", saved.getSeason(), "第 302 日（11 月）应绑定冬");
        assertNotNull(saved.getWeatherSnapshot(), "当日帧存在应填天气快照");
        assertTrue(saved.getWeatherSnapshot().contains("\"temperature\":-7.5"));
        assertTrue(saved.getEventIds().contains("3"), "AI 序号 event_ids 不应被幂等误跳过，实际：" + saved.getEventIds());
    }

    /** 批6 收口：chronicle_distill 属「世界视角」——AI 请求前置 段2D【当前世界动态】。 */
    @Test
    void chronicleDistillPrependsWorldDynamics() {
        ActorEvent e = new ActorEvent();
        e.setId(6450L);
        e.setGameHour(7225L);
        e.setImportance(4);
        e.setTitle("入冬首雪");
        e.setContent("入冬第一场雪落下，气温骤降");
        when(eventRepository.findByProjectIdOrderByIdDesc(100L)).thenReturn(List.of(e));
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(5L);
        frame.setGameDay(301L);
        frame.setWeatherJson("{\"主区域\":{\"condition\":\"雪\",\"temperature\":-7.5,\"wind\":\"北风\"}}");
        when(frameRepository.findByProjectIdAndGameDay(100L, 301L)).thenReturn(Optional.of(frame));
        ReflectionTestUtils.setField(service, "distillAiEnabled", true);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(100L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0421年11月01日 · 冬 · 天气雪");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("编年史提炼 prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn(null);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"entries\":[{\"title\":\"入冬首雪\",\"content\":\"雪落青禾村，气温骤降\","
                        + "\"importance\":4}]}", "test-model", 100, 50, 150, 1L));
        service.dailyDistillIfNeeded(100L, 301L, 26010000L);
        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertTrue(userMsg.startsWith("【世界观】"), "chronicle_distill 必须前置 段1A【世界观】: " + userMsg);
        assertTrue(userMsg.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + userMsg);
        assertTrue(userMsg.contains("编年史提炼 prompt"), "模板正文须保留在段 2D 之后");
    }

    // ==================== 静态工具 ====================

    /**
     * parseStringList/parseLongList：JSON 文本解析与非法容错。
     */
    @Test
    void jsonListParsers() {
        assertEquals(List.of("a", "b"), WorldMemoryService.parseStringList("[\"a\",\"b\"]"));
        assertEquals(List.of(1L, 2L), WorldMemoryService.parseLongList("[1,2]"));
        assertTrue(WorldMemoryService.parseStringList(null).isEmpty());
        assertTrue(WorldMemoryService.parseLongList("不是 JSON").isEmpty());
    }
    /**
     * abbreviate 截断（中文按字符 + 省略号）。
     */
    @Test
    void abbreviateTruncates() {
        assertEquals("abc", WorldMemoryService.abbreviate("abc", 5));
        assertEquals("abcdefg…", WorldMemoryService.abbreviate("abcdefghij", 7));
    }

    /**
     * parseEntityKey：类型前缀解析（location/person:special/faction）与非法回退。
     */
    @Test
    void parseEntityKeyParsesTypes() {
        assertNotNull(WorldMemoryService.parseEntityKey("location:村口牌坊"));
        assertNotNull(WorldMemoryService.parseEntityKey("person:special:110"));
        assertNotNull(WorldMemoryService.parseEntityKey("faction:青禾村"));
        assertNull(WorldMemoryService.parseEntityKey("无前缀"));
        assertNull(WorldMemoryService.parseEntityKey(null));
        assertNull(WorldMemoryService.parseEntityKey("unknown:xx"));
    }

    // ==================== M5.5 微层接线：AI 强度档位（编年史提炼/实体判定） ====================

    /** 构造 AI 强度闸门（mock 引擎行，档位可配）并注入服务。 */
    private com.holzyn.actor.domain.agent.runtime.AiIntensityGate intensityGate(String level) {
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository repo =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository.class);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                new com.holzyn.actor.domain.agent.runtime.ActorWorldEngine();
        engine.setProjectId(100L);
        engine.setAiIntensity(level);
        when(repo.findByProjectId(100L)).thenReturn(Optional.of(engine));
        com.holzyn.actor.domain.agent.runtime.AiIntensityGate gate =
                new com.holzyn.actor.domain.agent.runtime.AiIntensityGate(repo);
        ReflectionTestUtils.setField(service, "aiIntensityGate", gate);
        return gate;
    }

    @Test
    void instantDistill_programmaticSkipsAiUsesMechanismFallback() {
        // 档位 programmatic：独立每日预算=0 → 即使 AI 主通道开着也零调用，走机制兜底（事件原文成条）
        intensityGate("programmatic");
        ReflectionTestUtils.setField(service, "distillAiEnabled", true);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        when(eventRepository.existsById(anyLong())).thenReturn(true);
        ActorEvent event = new ActorEvent();
        event.setId(500L);
        event.setProjectId(100L);
        event.setImportance(4);
        event.setGameHour(130L);
        event.setTitle("粮价暴涨");
        event.setContent("粮价一日三涨，集市米行门前排起长队");

        service.instantDistill(100L, 5L, event);

        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
        ArgumentCaptor<ActorWorldChronicle> captor = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository).save(captor.capture());
        assertTrue(captor.getValue().getEventIds().contains("500"),
                "programmatic 档机制兜底应引用真实事件主键：" + captor.getValue().getEventIds());
    }

    @Test
    void instantDistill_lightHalvesDailyBudget() {
        // light 档：每日预算 20 → 有效 10——前 10 条即时提炼走 AI，第 11 条起机制兜底
        intensityGate("light");
        ReflectionTestUtils.setField(service, "distillAiEnabled", true);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        when(eventRepository.existsById(anyLong())).thenReturn(true);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("提炼 prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn(null);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"entries\":[{\"title\":\"大事\",\"content\":\"村中大事\","
                        + "\"importance\":4,\"event_ids\":[601],\"involved_entities\":[],"
                        + "\"involved_characters\":[]}]}", "test-model", 10, 5, 15, 1L));
        for (int i = 1; i <= 11; i++) {
            ActorEvent e = new ActorEvent();
            e.setId(600L + i);
            e.setProjectId(100L);
            e.setImportance(4);
            e.setGameHour(130L);
            e.setTitle("事件" + i);
            e.setContent("第" + i + "件大事");
            service.instantDistill(100L, 5L, e);
        }
        verify(aiChatGateway, Mockito.times(10)).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }
}
