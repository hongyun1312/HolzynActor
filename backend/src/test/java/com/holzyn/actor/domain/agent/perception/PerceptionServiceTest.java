package com.holzyn.actor.domain.agent.perception;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.domain.agent.event.EventQuery;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

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
 * PerceptionService（B2 F2 感知上下文）单元测试（与实现同包，访问纯逻辑静态方法）。
 * <p>职责：验证感知上下文组装（游戏时间 / 自身状态[特殊型 current_activity、普通型 actor_state 运行态] /
 * 可见事件[经事件总线 visibility 过滤] / 关系摘要[按 id/按名]）与【当前所见】文本渲染（空态/分节跳过）。</p>
 * <p>O6 G1a 口径：统一身份引用 {@code npc:{id}}（不再编码类型，类型查 {@code actor_profile.type}）；
 * 普通型 {@code state}/{@code last_action} 的运行态唯一家是 {@code actor_state}（经
 * {@link NpcActivityStateService} 读写）。</p>
 */
class PerceptionServiceTest {

    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private ActorCharacterRelationRepository relationRepository;
    private WorldClockService worldClockService;
    private WorldEventBus worldEventBus;
    private ActorNpcStateRepository stateRepository;
    private PerceptionService service;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        stateRepository = Mockito.mock(ActorNpcStateRepository.class);
        // G1a：构造器末参为 NpcTypeResolver；普通型运行态访问器为 @Autowired(required=false) 字段注入
        service = new PerceptionService(characterRepository, npcRepository, relationRepository,
                worldClockService, worldEventBus, new NpcTypeResolver(characterRepository));
        ReflectionTestUtils.setField(service, "npcActivityStateService",
                new NpcActivityStateService(stateRepository));
        when(worldClockService.currentGameHour(1L)).thenReturn(888L);
        when(worldClockService.gameTimeTextOf(eq(1L), any())).thenReturn("罗小黑战记历 0001年01月19日 20时00分");
        when(worldEventBus.query(anyLong(), any(EventQuery.class))).thenReturn(List.of());
        when(relationRepository.findByProjectId(anyLong())).thenReturn(List.of());
    }

    // ==================== 组装（special） ====================

    /**
     * 特殊角色感知组装：游戏时间 + 自身状态（current_activity/location）+ 可见事件 + 关系摘要。
     */
    @Test
    void buildPerceptionForSpecialCharacter() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        ActorEvent action = new ActorEvent();
        action.setTitle("罗小黑：巡视妖灵会馆");
        action.setContent("在会馆大厅巡视\n排查可疑动静");
        action.setKind("action");
        when(worldEventBus.query(anyLong(), any(EventQuery.class))).thenReturn(List.of(action));

        ActorCharacterRelation rel = new ActorCharacterRelation();
        rel.setFromCharacterId(3L);
        rel.setToCharacterId(9L);
        rel.setFromName("罗小黑");
        rel.setToName("鹿野");
        rel.setRelationType("搭档");
        rel.setDescription("共同巡守妖灵会馆");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(rel));

        PerceptionVO vo = service.buildPerception(1L, "npc:3", null);
        assertNotNull(vo);
        assertEquals(888L, vo.gameHour());
        assertEquals("罗小黑战记历 0001年01月19日 20时00分", vo.gameTimeText());
        assertEquals("妖灵会馆大厅", vo.locationName());
        assertEquals("巡视妖灵会馆", vo.ownState());
        assertEquals(1, vo.visibleEvents().size());
        assertEquals("罗小黑：巡视妖灵会馆：在会馆大厅巡视", vo.visibleEvents().get(0));
        assertEquals(1, vo.relationSummary().size());
        assertTrue(vo.relationSummary().get(0).contains("鹿野"));
        assertTrue(vo.relationSummary().get(0).contains("搭档"));
    }

    /**
     * 普通 NPC：自身状态来自 actor_state 运行态（activity_state + last_action），关系按名称匹配。
     */
    @Test
    void buildPerceptionForCommonNpcRef() {
        ActorCharacter npc = new ActorCharacter();
        npc.setId(12L);
        npc.setName("石鳞");
        npc.setProjectId(1L);
        // G1a：单主表下普通型必须显式 type=common（实体默认值是 special）
        npc.setType("common");
        npc.setLocation("城东市场");
        when(characterRepository.findById(12L)).thenReturn(Optional.of(npc));
        when(npcRepository.findById(12L)).thenReturn(Optional.of(npc));
        // G1a：state/last_action 唯一家在 actor_state（主表实体已无这两列）
        ActorNpcState st = new ActorNpcState();
        st.setProjectId(1L);
        st.setNpcRef("npc:12");
        st.setNpcType("common");
        st.setActivityState("talk");
        st.setLastAction("在摊位前与商人交谈");
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:12")).thenReturn(Optional.of(st));

        ActorCharacterRelation rel = new ActorCharacterRelation();
        // G1a：统一引用 npc:{id} 后关系摘要按 from/to 的 actor_profile.id 匹配（按名匹配仅作解析失败回退），
        // 故普通型关系行必须携带 id（12）——这正是单表合并修好的"普通型关系行无 id 只能按名兜底"问题
        rel.setFromCharacterId(12L);
        rel.setToCharacterId(0L);
        rel.setFromName("石鳞");
        rel.setToName("老掌柜");
        rel.setRelationType("主顾");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(rel));

        PerceptionVO vo = service.buildPerception(1L, "npc:12", null);
        assertNotNull(vo);
        assertEquals("城东市场", vo.locationName());
        assertEquals("talk（在摊位前与商人交谈）", vo.ownState());
        assertEquals(1, vo.relationSummary().size());
        assertTrue(vo.relationSummary().get(0).contains("老掌柜"));
        assertTrue(vo.relationSummary().get(0).contains("主顾"));
    }

    /**
     * 非法 ref / 不存在角色 → 空视图（返回空视图而非崩溃，调用方安全跳过）。
     * <p>角色不存在时仍带游戏时间（时间始终可注入），但无自身状态/事件/关系——渲染不含状态句。</p>
     */
    @Test
    void buildPerceptionEmptyForMissingCharacter() {
        when(characterRepository.findById(99L)).thenReturn(Optional.empty());
        PerceptionVO vo = service.buildPerception(1L, "npc:99", null);
        assertNotNull(vo);
        assertNull(vo.locationName());
        assertNull(vo.ownState());
        String text = service.buildPerceptionText(1L, "npc:99", null);
        assertFalse(text.contains("你正在"));
        assertFalse(text.contains("你身处"));
        assertFalse(text.contains("最近你知晓"));
        assertTrue(text.contains("现在是"), "游戏时间始终可注入");
    }

    /**
     * 时钟不可用（无时间）且无任何可感知内容 → 空串（调用方跳过注入，保持旧行为）。
     */
    @Test
    void renderEmptyWhenClockUnavailable() {
        when(worldClockService.currentGameHour(1L)).thenReturn(null);
        when(worldClockService.gameTimeTextOf(eq(1L), any())).thenReturn(null);
        when(characterRepository.findById(99L)).thenReturn(Optional.empty());
        assertEquals("", service.buildPerceptionText(1L, "npc:99", null));
    }

    /**
     * 非法 characterRef → null（调用方安全跳过）。
     */
    @Test
    void buildPerceptionNullForInvalidRef() {
        assertNull(service.buildPerception(1L, null, null));
        assertNull(service.buildPerception(null, "npc:3", null));
    }

    // ==================== 文本渲染（纯静态） ====================

    /**
     * 完整渲染：时间 + 地点/状态 + 事件 + 关系。
     */
    @Test
    void renderFullText() {
        PerceptionVO vo = new PerceptionVO(888L, "罗小黑战记历 0001年01月19日 20时00分", "妖灵会馆大厅",
                "巡视妖灵会馆", List.of("城东市场发生骚乱"), List.of("与鹿野：搭档"), "【当前目标】- 修复与旧友的关系", "【今日计划】- 当前（8时）：前往城东市场采购药材",
                List.of("世界规则：禁灵咒：本世界禁止对人类使用禁灵咒"));
        String text = PerceptionText.render(vo);
        assertTrue(text.startsWith("【当前所见】"));
        assertTrue(text.contains("现在是罗小黑战记历 0001年01月19日 20时00分"));
        assertTrue(text.contains("你在妖灵会馆大厅，正在巡视妖灵会馆"));
        assertTrue(text.contains("城东市场发生骚乱"));
        assertTrue(text.contains("与鹿野：搭档"));
        // D2 F7：目标/计划段注入
        assertTrue(text.contains("【当前目标】"));
        assertTrue(text.contains("【今日计划】"));
        // D3 F8：情境知识段注入
        assertTrue(text.contains("你知晓的相关世界知识"));
        assertTrue(text.contains("禁灵咒"));
    }

    // ==================== D3 F8 情境知识 ====================

    /** F8：感知附带情境知识片段（知识检索命中 → knowledgeHints + 渲染注入） */
    @Test
    void buildPerceptionIncludesKnowledgeHints() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        KnowledgeRetrievalService knowledgeRetrievalService = Mockito.mock(KnowledgeRetrievalService.class);
        when(knowledgeRetrievalService.search(anyLong(), eq(1L), eq(3L), anyString(), eq(3)))
                .thenReturn(List.of(new KnowledgeHit(
                        9L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。违反者将受到严惩。", 8.0, null)));
        setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        setField(service, "knowledgeEnabled", true);

        PerceptionVO vo = service.buildPerception(1L, "npc:3", null);
        assertNotNull(vo);
        assertNotNull(vo.knowledgeHints());
        assertEquals(1, vo.knowledgeHints().size());
        assertTrue(vo.knowledgeHints().get(0).contains("禁灵咒"));
        assertTrue(vo.knowledgeHints().get(0).startsWith("世界规则："));

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(text.contains("你知晓的相关世界知识"));
        assertTrue(text.contains("禁灵咒"));
    }

    /** F8：检索未命中 → 空列表，感知正常（零成本降级） */
    @Test
    void knowledgeEmptyWhenNoHits() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        KnowledgeRetrievalService knowledgeRetrievalService = Mockito.mock(KnowledgeRetrievalService.class);
        when(knowledgeRetrievalService.search(anyLong(), eq(1L), eq(3L), anyString(), eq(3)))
                .thenReturn(List.of());
        setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        setField(service, "knowledgeEnabled", true);

        PerceptionVO vo = service.buildPerception(1L, "npc:3", null);
        assertNotNull(vo.knowledgeHints());
        assertTrue(vo.knowledgeHints().isEmpty());
        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertFalse(text.contains("你知晓的相关世界知识"));
    }

    /** F8：开关关闭 → 不检索不注入（行为与 D2 一致） */
    @Test
    void knowledgeSkippedWhenDisabled() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        KnowledgeRetrievalService knowledgeRetrievalService = Mockito.mock(KnowledgeRetrievalService.class);
        setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        setField(service, "knowledgeEnabled", false);

        PerceptionVO vo = service.buildPerception(1L, "npc:3", null);
        assertNotNull(vo.knowledgeHints());
        assertTrue(vo.knowledgeHints().isEmpty());
        verify(knowledgeRetrievalService, never()).search(anyLong(), anyLong(), anyLong(), anyString(), eq(3));
    }

    // ==================== D3 F10 感知在场过滤 ====================

    /** F10：感知可见事件查询传入角色归一化 location_id（location 事件仅同地点在场者可见） */
    @Test
    void visibleEventsPassesCharacterLocationId() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        ch.setLocationId(2L);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        service.buildPerception(1L, "npc:3", null);

        ArgumentCaptor<EventQuery> captor = ArgumentCaptor.forClass(EventQuery.class);
        verify(worldEventBus).query(eq(1L), captor.capture());
        assertEquals(2L, captor.getValue().locationId(), "感知应把角色归一化 location_id 传入事件查询（在场过滤）");
        assertEquals("npc:3", captor.getValue().characterRef());
    }

    /** 反射设置私有字段（测试专用；保持构造器不变，服务字段注入可独立验证） */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * M2 L1 工作记忆：感知文本构建时①写感知快照/情境知识上下文工作项（单槽写入）
     * ②追加【当前情境】热缓冲段。
     */
    @Test
    void buildPerceptionTextWritesWorkingSnapshotsAndInjectsSegment() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        KnowledgeRetrievalService knowledgeRetrievalService = Mockito.mock(KnowledgeRetrievalService.class);
        when(knowledgeRetrievalService.search(anyLong(), eq(1L), eq(3L), anyString(), eq(3)))
                .thenReturn(List.of(new KnowledgeHit(
                        9L, "世界规则", "禁灵咒：本世界禁止对人类使用禁灵咒。违反者将受到严惩。", 8.0, null)));
        setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        setField(service, "knowledgeEnabled", true);

        com.holzyn.actor.domain.memory.service.MemoryWorkingService workingService =
                Mockito.mock(com.holzyn.actor.domain.memory.service.MemoryWorkingService.class);
        setField(service, "memoryWorkingService", workingService);
        setField(service, "workingPerceptionEnabled", true);
        when(workingService.injectWorkingSegment(eq(1L), eq("npc:3")))
                .thenReturn("【当前情境】以下是该角色当前进行中的关注项（工作记忆），回答时自然参考：\n- [任务] 当前行动：巡视会馆");

        String text = service.buildPerceptionText(1L, "npc:3", null);

        // ① 感知快照 + 情境知识上下文工作项写入（单槽）
        verify(workingService).writePerception(eq(1L), eq("npc:3"),
                org.mockito.ArgumentMatchers.contains("妖灵会馆大厅"), eq(2));
        verify(workingService).writeContext(eq(1L), eq("npc:3"),
                org.mockito.ArgumentMatchers.contains("禁灵咒"), eq(2));
        // ② 【当前情境】热缓冲段追加到感知文本
        assertTrue(text.contains("【当前情境】"));
        assertTrue(text.contains("当前行动：巡视会馆"));
    }

    /**
     * M3 L3 核心记忆：buildPerceptionText 默认注入【核心记忆】恒在段（感知注入组合首部）；
     * includeCore=false（对话场景去重）时不注入。
     */
    @Test
    void buildPerceptionTextCoreSegmentIncludeControl() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        com.holzyn.actor.domain.memory.service.CoreMemoryService coreService =
                Mockito.mock(com.holzyn.actor.domain.memory.service.CoreMemoryService.class);
        setField(service, "coreMemoryService", coreService);
        when(coreService.renderCoreSegment(eq(1L), eq("npc:3")))
                .thenReturn("【核心记忆】以下是该角色恒定在上下文的核心身份信息（静态基线 + 核心块），回答时始终以此为准：\n—— 身份基线 ——\n身份：罗小黑");

        String withCore = service.buildPerceptionText(1L, "npc:3", null);
        String withoutCore = service.buildPerceptionText(1L, "npc:3", null, false);

        assertTrue(withCore.contains("【核心记忆】"), "默认应注入 L3 核心记忆段");
        assertTrue(withCore.indexOf("【核心记忆】") < withCore.indexOf("现在是"), "L3 段应在感知文本首部");
        assertFalse(withoutCore.contains("【核心记忆】"), "includeCore=false 时感知段不重复注入 L3");
    }

    /**
     * M3 【当前状态】段：buildPerceptionText 默认注入（核心后、当前所见前）；includeState=false 跳过；
     * buildDialogPerceptionText 按对话开关注入（双轨全量，M3 决策确认）。
     */
    @Test
    void buildPerceptionTextStateSegmentIncludeControl() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        com.holzyn.actor.domain.npc.state.service.NpcStateService stateService =
                Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcStateService.class);
        setField(service, "npcStateService", stateService);
        when(stateService.stateSegment(eq(1L), eq("npc:3"), any()))
                .thenReturn("【当前状态】\n- 需求：饥饿 68/100（略饿）");
        setField(service, "injectAiDecision", true);
        setField(service, "injectDialog", true);

        String withState = service.buildPerceptionText(1L, "npc:3", null);
        String withoutState = service.buildPerceptionText(1L, "npc:3", null, true, false);
        String dialogText = service.buildDialogPerceptionText(1L, "npc:3", null);

        assertTrue(withState.contains("【当前状态】"), "决策默认应注入【当前状态】段");
        assertTrue(withState.indexOf("【当前状态】") < withState.indexOf("现在是"), "状态段在感知文本前（核心后、当前所见前）");
        assertFalse(withoutState.contains("【当前状态】"), "includeState=false 跳过");
        assertTrue(dialogText.contains("【当前状态】"), "对话注入开关开 → 对话也注入全量状态段");
    }

    /**
     * 批3 决策与感知：感知注入 = 段2D/2E/2H 对齐验证——buildPerceptionText 组合
     * 段2D【世界动态】（InfoPerceptionService）+ 段2E【当前状态】（stateSegment）+
     * 段2H【当前所见】（PerceptionText base）三段齐备，供决策/对话以角色视角读取。
     */
    @Test
    void buildPerceptionTextAlignsSegment2D2E2H() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        // 段2E：当前状态
        com.holzyn.actor.domain.npc.state.service.NpcStateService stateService =
                Mockito.mock(com.holzyn.actor.domain.npc.state.service.NpcStateService.class);
        setField(service, "npcStateService", stateService);
        when(stateService.stateSegment(eq(1L), eq("npc:3"), any()))
                .thenReturn("【当前状态】\n- 需求：饥饿 68/100（略饿）");
        setField(service, "injectAiDecision", true);
        setField(service, "injectDialog", true);
        // 段2D：世界动态（信息感知三层过滤）
        com.holzyn.actor.domain.world.service.InfoPerceptionService infoPerception =
                Mockito.mock(com.holzyn.actor.domain.world.service.InfoPerceptionService.class);
        setField(service, "infoPerceptionService", infoPerception);
        when(infoPerception.worldDynamicsSegment(eq(1L), eq("npc:3"), any()))
                .thenReturn("【世界动态】\n· 村口：米价涨了（经路人传来）");
        // 段2H：可见事件（当前所见数据源）
        ActorEvent e = new ActorEvent();
        e.setTitle("村口粮铺米价上涨");
        e.setContent("米价上涨");
        e.setVisibility("global");
        when(worldEventBus.query(anyLong(), any(EventQuery.class))).thenReturn(List.of(e));

        String text = service.buildPerceptionText(1L, "npc:3", null);

        // 段2D（世界动态）在段2E 前、段2H 前
        assertTrue(text.contains("【世界动态】"), "应含段2D 世界动态: " + text);
        assertTrue(text.contains("米价涨了"), "段2D 内容注入: " + text);
        // 段2E（当前状态）
        assertTrue(text.contains("【当前状态】"), "应含段2E 当前状态: " + text);
        // 段2H（当前所见 = 感知文本 base，含可见事件）
        assertTrue(text.contains("【当前所见】"), "应含段2H 当前所见: " + text);
        assertTrue(text.contains("村口粮铺米价上涨"), "段2H 事件注入: " + text);
        // 顺序：段2E 与段2D 都在段2H（当前所见）前（感知漏斗既有顺序：核心→状态→世界动态→当前所见）
        assertTrue(text.indexOf("【当前状态】") < text.indexOf("【当前所见】"), "段2E 在段2H 前");
        assertTrue(text.indexOf("【世界动态】") < text.indexOf("【当前所见】"), "段2D 在段2H 前");
    }

    /**
     * 空视图 → 空串（无可注入内容，调用方跳过）。
     */
    @Test
    void renderEmptyReturnsBlank() {
        assertEquals("", PerceptionText.render(null));
        assertEquals("", PerceptionText.render(new PerceptionVO(null, null, null, null, List.of(), List.of(), "", "", List.of())));
    }

    /**
     * 仅时间 → 只渲染时间句。
     */
    @Test
    void renderTimeOnly() {
        PerceptionVO vo = new PerceptionVO(100L, "世界历 0002年01月01日 00时00分", null, null, List.of(), List.of(), "", "", List.of());
        String text = PerceptionText.render(vo);
        assertTrue(text.contains("现在是世界历 0002年01月01日 00时00分"));
        assertFalse(text.contains("最近你知晓"));
    }

    /**
     * 事件文本：标题 + 内容首行；内容与标题相同或空内容时仅标题。
     */
    @Test
    void eventTextExtractsFirstLine() {
        ActorEvent e = new ActorEvent();
        e.setTitle("事件标题");
        e.setContent("第一行内容\n第二行");
        assertEquals("事件标题：第一行内容", PerceptionService.eventText(e));
        e.setContent("事件标题");
        assertEquals("事件标题", PerceptionService.eventText(e));
        e.setContent(null);
        assertEquals("事件标题", PerceptionService.eventText(e));
        assertEquals("", PerceptionService.eventText(null));
    }

    /**
     * ref id 解析：统一 {@code npc:{id}} 单前缀（严格）；历史双前缀/非法一律返回 null。
     * <p>G1a：{@code PerceptionService.parseId(ref, prefix)} 私有双前缀解析器已删除，
     * 统一走 {@link NpcRefs#idOf(String)}。</p>
     */
    @Test
    void parseRefId() {
        assertEquals(3L, NpcRefs.idOf("npc:3"));
        assertEquals(12L, NpcRefs.idOf("npc:12"));
        assertNull(NpcRefs.idOf("special:3"), "历史 special: 前缀不再是合法身份引用");
        assertNull(NpcRefs.idOf("crowd:12"), "历史 crowd: 前缀不再是合法身份引用");
        assertNull(NpcRefs.idOf("npc:abc"));
        assertNull(NpcRefs.idOf("npc:0"), "非正数 id 非法");
        assertNull(NpcRefs.idOf(null));
    }

    // ==================== 时间季节系统 M3（2026-09-15）：【当前环境】段注入 ====================

    /**
     * 决策感知：buildPerceptionText 应注入【当前环境】段且置顶（核心段之前）；
     * 对话路径（includeCore=false）按 inject-dialogue 开关注入。
     */
    @Test
    void buildPerceptionTextInjectsEnvironmentSegmentAtTop() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        com.holzyn.actor.domain.world.service.EnvironmentContextService env =
                Mockito.mock(com.holzyn.actor.domain.world.service.EnvironmentContextService.class);
        setField(service, "environmentContextService", env);
        when(env.injectionSegment(eq(1L), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("【当前环境】冬日的傍晚，天已经全黑了，飘着雪（体感 −9℃，日落 17:30）");
        setField(service, "injectEnvironmentPerception", true);
        setField(service, "injectEnvironmentDialogue", true);

        String decision = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(decision.contains("【当前环境】"), "决策感知应注入环境段: " + decision);
        assertTrue(decision.indexOf("【当前环境】") == 0, "环境段应置顶（首部）: " + decision);

        String dialog = service.buildDialogPerceptionText(1L, "npc:3", null);
        assertTrue(dialog.contains("【当前环境】"), "对话应注入环境段: " + dialog);

        // 开关关 → 不注入（行为与 M2 前一致）
        setField(service, "injectEnvironmentPerception", false);
        String off = service.buildPerceptionText(1L, "npc:3", null);
        assertFalse(off.contains("【当前环境】"), "inject-perception=false 不注入");
    }

    /**
     * 环境服务未装配（null）→ 静默降级，感知文本与既有行为一致（无环境段）。
     */
    @Test
    void buildPerceptionTextNoEnvironmentServiceSkips() throws Exception {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        setField(service, "injectEnvironmentPerception", true);
        setField(service, "injectEnvironmentDialogue", true);

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertFalse(text.contains("【当前环境】"), "服务未装配跳过环境段（静默降级）");
    }

    // ==================== M3 感知联动：【当前位置与方向】段 ====================

    /** 装配 WorldMapQueryService + MovementService + 沙盘状态仓库（M3 空间段测试夹具）。 */
    private void setupSpatial(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService wmqs,
                              com.holzyn.actor.domain.worldmap.service.MovementService ms,
                              com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository ssr) {
        ReflectionTestUtils.setField(service, "worldMapQueryService", wmqs);
        ReflectionTestUtils.setField(service, "movementService", ms);
        ReflectionTestUtils.setField(service, "sandboxStateRepository", ssr);
    }

    private com.holzyn.actor.domain.sandbox.entity.ActorSandboxState sandboxState(String ref, double x, double z) {
        com.holzyn.actor.domain.sandbox.entity.ActorSandboxState st =
                new com.holzyn.actor.domain.sandbox.entity.ActorSandboxState();
        st.setProjectId(1L);
        st.setNpcRef(ref);
        st.setPosX(x);
        st.setPosZ(z);
        st.setMoveState("idle");
        return st;
    }

    /**
     * 驻留：有沙盘坐标 + 区域反查命中 → 【当前位置与方向】在{区域口语描述}（不给精确坐标）。
     */
    @Test
    void buildPerceptionTextSpatial_stationary() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("在村口歇脚");
        ch.setLocation("村口石牌坊");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        com.holzyn.actor.domain.worldmap.service.WorldMapQueryService wmqs =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.class);
        com.holzyn.actor.domain.worldmap.service.MovementService ms =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.MovementService.class);
        com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository ssr =
                Mockito.mock(com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository.class);
        setupSpatial(wmqs, ms, ssr);
        when(ssr.findByProjectIdAndNpcRef(1L, "npc:3"))
                .thenReturn(Optional.of(sandboxState("npc:3", -40d, 180d)));
        when(ms.inTransitMove(1L, "npc:3")).thenReturn(null);
        when(wmqs.regionAt(1L, -40d, 180d)).thenReturn("村西口，进村第一景");
        when(wmqs.nearbyRefs(1L, -40d, 180d, "npc:3", 30)).thenReturn(java.util.List.of());

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(text.contains("【当前位置与方向】在村西口，进村第一景"), "驻留应注入区域口语描述: " + text);
        assertFalse(text.matches(".*-?\\d+\\.?\\d*[,-].*-?\\d+\\.?\\d*.*"), "不得出现精确坐标数字: " + text);
    }

    /**
     * 移动中：在途行存在 → 正走在去{目标}的路上（方位/路程口语，不给精确坐标）。
     */
    @Test
    void buildPerceptionTextSpatial_inTransit() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("正赶路");
        ch.setLocation("村西");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        com.holzyn.actor.domain.worldmap.service.WorldMapQueryService wmqs =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.class);
        com.holzyn.actor.domain.worldmap.service.MovementService ms =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.MovementService.class);
        com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository ssr =
                Mockito.mock(com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository.class);
        setupSpatial(wmqs, ms, ssr);
        when(ssr.findByProjectIdAndNpcRef(1L, "npc:3"))
                .thenReturn(Optional.of(sandboxState("npc:3", -40d, 180d)));
        com.holzyn.actor.domain.worldmap.entity.ActorMovement m =
                new com.holzyn.actor.domain.worldmap.entity.ActorMovement();
        m.setFromX(-40d); m.setFromZ(180d);
        m.setToX(10d); m.setToZ(10d);
        m.setTargetLocationName("医馆");
        when(ms.inTransitMove(1L, "npc:3")).thenReturn(m);
        when(wmqs.regionAt(1L, -40d, 180d)).thenReturn("村西");
        when(wmqs.regionAt(1L, 10d, 10d)).thenReturn("村中");
        when(wmqs.nearbyRefs(1L, -40d, 180d, "npc:3", 30)).thenReturn(java.util.List.of());

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(text.contains("正走在去医馆的路上"), "移动中应注入目标与路程: " + text);
        assertTrue(text.contains("村西"), "起点方位: " + text);
        assertTrue(text.contains("村中"), "终点方位: " + text);
        assertFalse(text.matches(".*-?\\d+\\.?\\d*[,-].*-?\\d+\\.?\\d*.*"), "不得出现精确坐标数字: " + text);
    }

    /**
     * 途中遭遇（途中可见）：移动中附近有他角色 → 迎面遇到{名}，点头致意。
     */
    @Test
    void buildPerceptionTextSpatial_encounter() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("正赶路");
        ch.setLocation("村西");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        ActorCharacter other = new ActorCharacter();
        other.setId(9L);
        other.setName("鹿野");
        other.setProjectId(1L);
        when(characterRepository.findById(9L)).thenReturn(Optional.of(other));

        com.holzyn.actor.domain.worldmap.service.WorldMapQueryService wmqs =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.class);
        com.holzyn.actor.domain.worldmap.service.MovementService ms =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.MovementService.class);
        com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository ssr =
                Mockito.mock(com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository.class);
        setupSpatial(wmqs, ms, ssr);
        when(ssr.findByProjectIdAndNpcRef(1L, "npc:3"))
                .thenReturn(Optional.of(sandboxState("npc:3", -40d, 180d)));
        com.holzyn.actor.domain.worldmap.entity.ActorMovement m =
                new com.holzyn.actor.domain.worldmap.entity.ActorMovement();
        m.setFromX(-40d); m.setFromZ(180d);
        m.setToX(10d); m.setToZ(10d);
        m.setTargetLocationName("医馆");
        when(ms.inTransitMove(1L, "npc:3")).thenReturn(m);
        when(wmqs.regionAt(1L, -40d, 180d)).thenReturn("村西");
        when(wmqs.regionAt(1L, 10d, 10d)).thenReturn("村中");
        when(wmqs.nearbyRefs(1L, -40d, 180d, "npc:3", 30)).thenReturn(java.util.List.of("npc:9"));

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(text.contains("迎面遇到鹿野"), "途中遭遇应注入「迎面遇到 X」: " + text);
        assertTrue(text.contains("点头致意"), "招呼动作: " + text);
    }

    /**
     * 驻留可见（途中可见的另一半）：驻留角色看到附近经过的移动者 → 看到{名}正从身旁经过。
     */
    @Test
    void buildPerceptionTextSpatial_stationarySeesPasserby() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("在村口歇脚");
        ch.setLocation("村口石牌坊");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        ActorCharacter other = new ActorCharacter();
        other.setId(9L);
        other.setName("鹿野");
        other.setProjectId(1L);
        when(characterRepository.findById(9L)).thenReturn(Optional.of(other));

        com.holzyn.actor.domain.worldmap.service.WorldMapQueryService wmqs =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.WorldMapQueryService.class);
        com.holzyn.actor.domain.worldmap.service.MovementService ms =
                Mockito.mock(com.holzyn.actor.domain.worldmap.service.MovementService.class);
        com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository ssr =
                Mockito.mock(com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository.class);
        setupSpatial(wmqs, ms, ssr);
        when(ssr.findByProjectIdAndNpcRef(1L, "npc:3"))
                .thenReturn(Optional.of(sandboxState("npc:3", -40d, 180d)));
        when(ms.inTransitMove(1L, "npc:3")).thenReturn(null);
        when(wmqs.regionAt(1L, -40d, 180d)).thenReturn("村西口");
        when(wmqs.nearbyRefs(1L, -40d, 180d, "npc:3", 30)).thenReturn(java.util.List.of("npc:9"));

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertTrue(text.contains("看到鹿野正从身旁经过"), "驻留可见注入: " + text);
    }

    /**
     * 未装配（null）→ 静默降级，感知文本无【当前位置与方向】段（行为与 M3 前一致）。
     */
    @Test
    void buildPerceptionTextSpatial_servicesAbsentSkips() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("罗小黑");
        ch.setProjectId(1L);
        ch.setCurrentActivity("巡视妖灵会馆");
        ch.setLocation("妖灵会馆大厅");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        String text = service.buildPerceptionText(1L, "npc:3", null);
        assertFalse(text.contains("【当前位置与方向】"), "服务未装配跳过空间段（静默降级）: " + text);
    }
}
