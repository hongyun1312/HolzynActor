package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.assistant.vo.AssistantCardVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldGraphQueryService;
import com.holzyn.actor.domain.world.service.WorldOutlookService;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 助手工具执行器单元测试（能力域 A/B/C/D/G 数据源 + 实体解析 + 降级）。
 * <p>职责：验证——角色名/npc:id 解析、角色状态/档案/地点/宏观/事件/导览/知识/文档各工具取数与文本组织、
 * 未知实体降级（返回失败说明而非异常）、世界知识命中渲染。</p>
 */
class AssistantToolExecutorTest {

    private WorldGraphQueryService worldGraph;
    private NpcStateService npcStateService;
    private WorldClockService worldClockService;
    private WorldOutlookService worldOutlookService;
    private KnowledgeRetrievalService knowledgeRetrievalService;
    private AssistantDocsRagService docsRagService;
    private ActorCharacterRepository characterRepository;
    private CurrentUserProvider currentUserProvider;
    private AssistantProperties properties;
    private AssistantObservationService observationService;
    private AssistantInterventionService interventionService;
    private AssistantToolExecutor executor;

    private ActorCharacter shen;

    @BeforeEach
    void setUp() {
        worldGraph = Mockito.mock(WorldGraphQueryService.class);
        npcStateService = Mockito.mock(NpcStateService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldOutlookService = Mockito.mock(WorldOutlookService.class);
        knowledgeRetrievalService = Mockito.mock(KnowledgeRetrievalService.class);
        docsRagService = Mockito.mock(AssistantDocsRagService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        properties = new AssistantProperties();
        executor = new AssistantToolExecutor(worldGraph, npcStateService, worldClockService,
                worldOutlookService, knowledgeRetrievalService, docsRagService, characterRepository,
                currentUserProvider, properties);
        // M3/M4：观测与干预服务为字段注入（不改变构造签名），测试经反射注入 mock
        observationService = Mockito.mock(AssistantObservationService.class);
        interventionService = Mockito.mock(AssistantInterventionService.class);
        ReflectionTestUtils.setField(executor, "observationService", observationService);
        ReflectionTestUtils.setField(executor, "interventionService", interventionService);

        shen = new ActorCharacter();
        shen.setId(1L);
        shen.setProjectId(1L);
        shen.setName("沈砚秋");
        shen.setTitle("青禾村村长");
        shen.setType("special");
        shen.setAffiliation("沈氏宗族");
        shen.setOccupation("村长");
        shen.setLocation("村东水田");
        shen.setLocationId(3L);
        shen.setStage("adult");
        shen.setPersonaJson("{\"personality\":{\"ocean\":{\"conscientiousness\":0.8}}}");
        when(characterRepository.findByIdAndProjectId(1L, 1L)).thenReturn(Optional.of(shen));

        WorldGraphVO.CharacterNode cNode = new WorldGraphVO.CharacterNode(1L, "npc:1", "沈砚秋", "青禾村村长",
                "special", "沈氏宗族", "村长", "村东水田", 3L, "adult", 5, 1);
        when(worldGraph.characters(anyLong(), isNull(), isNull())).thenReturn(List.of(cNode));
        when(worldGraph.locations(anyLong())).thenReturn(List.of(
                new WorldGraphVO.LocationNode(3L, "村东水田", "农田", "村东的稻田", 3, 1)));

        WorldGraphVO.TimeNode timeNode = new WorldGraphVO.TimeNode(13044175200L, 3623382L, 150974L, 1, 0,
                "青禾村历", "0420-05-15 06:00");
        when(worldGraph.time(anyLong())).thenReturn(timeNode);

        ActorNpcState st = new ActorNpcState();
        st.setNpcRef("npc:1");
        st.setLastAction("察看稻情");
        when(npcStateService.stateFor(anyLong(), any())).thenReturn(st);

        when(worldOutlookService.trendSummary(anyLong())).thenReturn("最近三天粮价上涨，民怨上升。");

        WorldGraphVO.Overview overview = new WorldGraphVO.Overview(
                timeNode,
                new WorldGraphVO.Counts(15, 12, 300, 40, 3),
                new WorldGraphVO.MacroNode(120L, 5000.0, 1200.0, 76.69, 0.6, 900.0, 1200.0,
                        45.0, "春", 1.1, "沈氏宗族", "粮价", "宏观摘要"),
                List.of(new WorldGraphVO.EventNode(1L, "roleplay", "赶集", "内容", 100L, 3L, "村东水田", "public", 3,
                        List.of("沈砚秋"))),
                List.of(new WorldGraphVO.ChronicleNode(1L, 150974L, "春耕", "内容", 3, "春", "daily")));
        when(worldGraph.overview(anyLong())).thenReturn(overview);
        when(worldGraph.macro(anyLong())).thenReturn(overview.macro());

        WorldGraphVO.LocationView locView = new WorldGraphVO.LocationView(
                new WorldGraphVO.LocationNode(3L, "村东水田", "农田", "村东的稻田", 3, 1),
                List.of(cNode),
                List.of(new WorldGraphVO.EventNode(2L, "roleplay", "插秧", "内容", 102L, 3L, "村东水田", "public", 2,
                        List.of("沈砚秋"))),
                List.of());
        when(worldGraph.locationView(anyLong(), anyLong())).thenReturn(locView);

        WorldGraphVO.CharacterView chView = new WorldGraphVO.CharacterView(cNode,
                new WorldGraphVO.LocationNode(3L, "村东水田", "农田", "村东的稻田", 3, 1),
                List.of(new WorldGraphVO.RelationEdge(1L, "npc:1", "沈砚秋", "npc:2", "孙二娘", "同村", 60, 55,
                        "active", "邻居")),
                List.of(new WorldGraphVO.FactionNode(1L, "沈氏宗族", "clan", "active", 0.8, "npc:1", 5L, Map.of())),
                List.of(new WorldGraphVO.EventNode(3L, "roleplay", "开祠堂", "内容", 90L, 1L, "沈家祠堂", "public", 3,
                        List.of("沈砚秋"))));
        when(worldGraph.characterView(anyLong(), anyLong())).thenReturn(chView);

        when(worldGraph.events(anyLong(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(new WorldGraphVO.EventNode(4L, "roleplay", "庙会", "内容", 200L, 2L, "村口",
                        "public", 2, List.of("沈砚秋"))));
        when(worldGraph.events(anyLong(), isNull(), any(), anyInt())).thenReturn(chView.events());

        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    @Test
    void characterStateResolvesByName() {
        var r = executor.execute(1L, "character_state", Map.of("character", "沈砚秋"));
        assertEquals("character_state", r.name());
        assertTrue(r.detail().contains("沈砚秋"));
        assertTrue(r.detail().contains("村东水田"));
        assertTrue(r.detail().contains("察看稻情"));
        assertNotNull(r.card());
        assertEquals("character", r.card().type());
        assertTrue(r.sources().stream().anyMatch(s -> s.source().contains("NpcStateService")));
    }

    @Test
    void characterStateByNpcRef() {
        var r = executor.execute(1L, "character_state", Map.of("character", "npc:1"));
        assertTrue(r.detail().contains("沈砚秋"));
    }

    @Test
    void unknownCharacterDegradesWithoutException() {
        var r = executor.execute(1L, "character_state", Map.of("character", "不存在的人"));
        assertTrue(r.detail().contains("未找到角色"));
        assertTrue(r.sources().isEmpty());
    }

    @Test
    void characterProfileIncludesPersonaAndRelations() {
        var r = executor.execute(1L, "character_profile", Map.of("character", "沈砚秋"));
        assertTrue(r.detail().contains("角色档案"));
        assertTrue(r.detail().contains("conscientiousness"));
        assertTrue(r.detail().contains("孙二娘"));
        assertTrue(r.detail().contains("沈氏宗族"));
    }

    @Test
    void locationOverview() {
        var r = executor.execute(1L, "location_overview", Map.of("location", "村东水田"));
        assertTrue(r.detail().contains("村东水田"));
        assertTrue(r.detail().contains("沈砚秋"));
    }

    @Test
    void worldOverview() {
        var r = executor.execute(1L, "world_overview", Map.of());
        assertTrue(r.detail().contains("世界概览"));
        assertTrue(r.detail().contains("76.69"));
        assertNotNull(r.card());
        assertEquals("world", r.card().type());
    }

    @Test
    void macroOverviewIncludesTrend() {
        var r = executor.execute(1L, "macro_overview", Map.of());
        assertTrue(r.detail().contains("宏观态势"));
        assertTrue(r.detail().contains("上涨"));
    }

    @Test
    void recentEventsByLocation() {
        var r = executor.execute(1L, "recent_events", Map.of("location", "村东水田"));
        assertTrue(r.detail().contains("插秧"));
    }

    @Test
    void projectGuideKeywordFilter() {
        var all = executor.execute(1L, "project_guide", Map.of());
        assertTrue(all.detail().contains("功能导览"));
        var kw = executor.execute(1L, "project_guide", Map.of("keyword", "记忆"));
        assertTrue(kw.detail().contains("世界记忆"));
        var miss = executor.execute(1L, "project_guide", Map.of("keyword", "不存在功能xyz"));
        assertTrue(miss.detail().contains("未找到与"));
    }

    @Test
    void searchWorldKnowledgeRendersHits() {
        when(knowledgeRetrievalService.search(anyLong(), anyLong(), isNull(), any(), anyInt()))
                .thenReturn(List.of(new KnowledgeHit(1L, "灵脉考", "青禾村地脉深处有灵石矿脉", 0.9, null)));
        var r = executor.execute(1L, "search_world_knowledge", Map.of("query", "灵脉"));
        assertTrue(r.detail().contains("世界知识命中"));
        assertTrue(r.detail().contains("灵脉考"));
    }

    @Test
    void searchWorldKnowledgeEmptyHits() {
        when(knowledgeRetrievalService.search(anyLong(), anyLong(), isNull(), any(), anyInt()))
                .thenReturn(List.of());
        var r = executor.execute(1L, "search_world_knowledge", Map.of("query", "无内容"));
        assertTrue(r.detail().contains("未检索到"));
    }

    @Test
    void searchProjectDocsRendersHits() {
        when(docsRagService.search(anyLong(), any()))
                .thenReturn(List.of(new AssistantDocsRagService.Hit("记忆系统分 L1/L2/L3 三层",
                        new AssistantSourceVO("项目文档·向量检索", "主对接文档.md §2（相关度 0.88）"))));
        var r = executor.execute(1L, "search_project_docs", Map.of("query", "记忆系统"));
        assertTrue(r.detail().contains("项目文档命中"));
        assertTrue(r.detail().contains("L1/L2/L3"));
    }

    @Test
    void unknownToolReturnsFail() {
        var r = executor.execute(1L, "not_a_tool", Map.of());
        assertTrue(r.detail().contains("未知工具"));
    }

    @Test
    void specsExposeTwelveTools() {
        var specs = executor.specs();
        assertEquals(12, specs.size());
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("character_state")));
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("search_project_docs")));
        // M3：运行观测
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("trace_chain")));
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("ai_call_stats")));
        // M4：世界干预
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("world_intervene")));
    }

    @Test
    void traceChainTool() {
        when(observationService.traceChain(anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(new AssistantObservationService.ObsResult("决策链路已获取（2 个环节）",
                        "决策链路 abc…（2 个环节）：\n- 感知：…",
                        List.of(new AssistantSourceVO("actor_ai_call_log·决策链路", "traceId=abc")),
                        new AssistantCardVO("trace", "决策链路 · abc", List.of())));
        var r = executor.execute(1L, "trace_chain", Map.of("traceId", "abc"));
        assertTrue(r.detail().contains("决策链路"));
        assertNotNull(r.card());
        assertEquals("trace", r.card().type());
    }

    @Test
    void aiCallStatsTool() {
        when(observationService.aiCallStats(anyLong(), anyInt()))
                .thenReturn(new AssistantObservationService.ObsResult("AI 调用统计已获取",
                        "AI 调用统计（近 1 天）：\n总调用 12 次：成功 10 / 失败 2；成功率 83.3%",
                        List.of(new AssistantSourceVO("actor_ai_call_log·统计", "窗口 1 天")),
                        new AssistantCardVO("stats", "AI 调用统计 · 近 1 天", List.of())));
        var r = executor.execute(1L, "ai_call_stats", Map.of("days", 1));
        assertTrue(r.detail().contains("总调用 12 次"));
        assertEquals("stats", r.card().type());
    }

    @Test
    void worldIntervenePendingProducesConfirmCard() {
        when(interventionService.plan(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new AssistantInterventionService.PlanResult("pending", 7L, "weather",
                        "切换天气为「雨」", "项目 1 世界（全境天气）", "low", null, null));
        var r = executor.execute(1L, "world_intervene",
                Map.of("_sessionId", "1", "operation", "weather",
                        "params", Map.of("condition", "雨")));
        assertTrue(r.detail().contains("需要用户确认"));
        assertTrue(r.summary().contains("待确认"));
        assertNotNull(r.card());
        assertEquals("confirm", r.card().type());
    }

    @Test
    void worldInterveneExecutedProducesResultCard() {
        when(interventionService.plan(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new AssistantInterventionService.PlanResult("executed", null, "weather",
                        "切换天气为「晴」", "项目 1 世界（全境天气）", "low", "天气已切换为「晴」", Map.of()));
        var r = executor.execute(1L, "world_intervene",
                Map.of("_sessionId", "1", "operation", "weather",
                        "params", Map.of("condition", "晴")));
        assertTrue(r.detail().contains("已执行"));
        assertEquals("result", r.card().type());
    }

    @Test
    void worldInterveneMissingSessionFails() {
        var r = executor.execute(1L, "world_intervene",
                Map.of("operation", "weather", "params", Map.of("condition", "雨")));
        assertTrue(r.detail().contains("会话上下文"));
        assertTrue(r.card() == null);
    }
}
