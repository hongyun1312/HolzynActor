package com.holzyn.actor.domain.growth.attention;

import com.holzyn.actor.ai.decision.DecisionState;
import com.holzyn.actor.ai.decision.gate.DecisionGate;
import com.holzyn.actor.ai.decision.gate.DecisionScenes;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.agent.runtime.StimulusType;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认知注意力服务单元测试（N5 认知 LOD + 高频微决策，2026-09-26）。
 *
 * <p>测试纪律：字段注入 + {@code ReflectionTestUtils.setField}（构造器不变）；决策模型 判定门
 * 全 mock（脚本化响应，零真实 AI）；直接反射调用同步体（{@code tickSync}/{@code eventRelevanceSync}）
 * 保证确定性（生产入口为异步虚拟线程）。概率门控（记忆写入/打断）通过把配置概率调到 1.0 使行为确定。</p>
 *
 * <p>所属模块：domain/growth/attention（N5）</p>
 */
class CognitiveAttentionServiceTest {

    private CognitiveAttentionService service;
    private DecisionGate gate;
    private BrainTierService brainTierService;
    private ActorCharacterRepository characterRepository;
    private MemoryWorkingService memoryWorkingService;
    private RealTimeDecisionService realTimeDecisionService;
    private GrowthProperties properties;
    private ActorCharacter ch;

    @BeforeEach
    void setUp() {
        service = new CognitiveAttentionService();
        gate = mock(DecisionGate.class);
        brainTierService = mock(BrainTierService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        memoryWorkingService = mock(MemoryWorkingService.class);
        realTimeDecisionService = mock(RealTimeDecisionService.class);
        properties = new GrowthProperties();
        // 概率门控调满：记忆写入 / 打断必发生（确定性断言）
        properties.getAttention().setMemoryWriteProbability(1.0);
        properties.getAttention().setInterruptProbability(1.0);
        ReflectionTestUtils.setField(service, "decisionGate", gate);
        ReflectionTestUtils.setField(service, "brainTierService", brainTierService);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "npcStateService", mock(NpcStateService.class));
        ReflectionTestUtils.setField(service, "worldClockService", null);
        ReflectionTestUtils.setField(service, "eventRepository", mock(ActorEventRepository.class));
        ReflectionTestUtils.setField(service, "realTimeDecisionService", realTimeDecisionService);
        ReflectionTestUtils.setField(service, "memoryWorkingService", memoryWorkingService);
        ReflectionTestUtils.setField(service, "growthProperties", properties);
        ch = new ActorCharacter();
        ch.setId(1L);
        ch.setProjectId(1L);
        ch.setName("张三");
        ch.setType("common");
        ch.setStage("YOUTH");
        ch.setCurrentActivity("在田里锄草");
        ch.setLocation("村南田");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(ch));
        when(((com.holzyn.actor.domain.world.repository.ActorEventRepository)
                ReflectionTestUtils.getField(service, "eventRepository"))
                .findByProjectIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(gate.isEnabled(DecisionScenes.ATTENTION)).thenReturn(true);
        when(gate.isEnabled(DecisionScenes.MICRO_DECISION)).thenReturn(true);
        when(brainTierService.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_AMBIENT);
    }

    /** 反射调用私有同步体（生产异步入口的确定化执行）。 */
    private Object invokeSync(String name, Object... args) throws Exception {
        Method m = CognitiveAttentionService.class.getDeclaredMethod(name,
                java.util.stream.IntStream.range(0, args.length)
                        .mapToObj(i -> args[i].getClass()).toArray(Class[]::new));
        m.setAccessible(true);
        return m.invoke(service, args);
    }

    @Test
    @DisplayName("N5 每 tick 批量：注意力高 → applyAttention(level=3)；即时意向 chat → 记忆轨迹 + MENTAL_URGE 打断")
    void tickSyncAppliesAttentionAndMicroIntent() throws Exception {
        // 注意力 score=3（高）+ 即时意向 chat（主动意向）
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_ATTENTION_STATE,
                new DecisionGate.ScoreOutcome(3, 2.8, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.MICRO_DECISION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_MICRO_INTENT,
                new DecisionGate.ChoiceOutcome("chat", true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("tickSync", 1L, 1_000_000L);

        // 注意力 → 语义通道
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 1_000_000L);
        // 微决策非「继续」意向 → 低频记忆轨迹（概率 1.0 必写）
        verify(memoryWorkingService).writePerception(eq(1L), eq("npc:1"),
                anyString(), anyInt());
        // chat 属主动意向 → 极低概率打断（概率 1.0 必触发）
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"),
                eq(StimulusType.MENTAL_URGE), anyString(), eq(1_000_000L));
    }

    @Test
    @DisplayName("N5 每 tick 批量：多数「继续当下事」→ 零副作用（不写记忆不打断）")
    void tickSyncContinueCurrentNoSideEffect() throws Exception {
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_ATTENTION_STATE,
                new DecisionGate.ScoreOutcome(2, 1.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.MICRO_DECISION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_MICRO_INTENT,
                new DecisionGate.ChoiceOutcome("continue_current", true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("tickSync", 1L, 1_000_000L);

        // 中性注意力（level=2）→ applyAttention 仍调用（清痕迹语义）；继续当下事 → 无记忆/无打断
        verify(brainTierService).applyAttention(1L, "npc:1", 2, 1_000_000L);
        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
        verify(realTimeDecisionService, never()).onStimulus(anyLong(), anyString(),
                any(StimulusType.class), anyString(), anyLong());
    }

    @Test
    @DisplayName("N5 事件相关度批量：高相关（level=3 ≥ promote 3）→ 写 L1 记忆 + 语义升档")
    void eventRelevanceHighPromotes() throws Exception {
        ActorEvent event = new ActorEvent();
        event.setId(10L);
        event.setProjectId(1L);
        event.setContent("集市上张屠户与人起了争执，惊动了半条街");
        event.setImportance(3);
        event.setGameHour(100L);
        event.setCharacterIds("npc:1"); // 当事人（在场名单来源）
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_EVENT_RELEVANCE,
                new DecisionGate.ScoreOutcome(3, 2.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("eventRelevanceSync", 1L, event);

        // level=3 ≥ memoryLevel(2) → 记忆；≥ promoteLevel(3) → 语义升档 focus
        verify(memoryWorkingService).writePerception(eq(1L), eq("npc:1"), anyString(), eq(2));
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 100L * 3600L);
    }

    @Test
    @DisplayName("N5 事件相关度批量：低相关（level=1）→ 零副作用")
    void eventRelevanceLowNoEffect() throws Exception {
        ActorEvent event = new ActorEvent();
        event.setId(11L);
        event.setProjectId(1L);
        event.setContent("张三家丢了一只鸡");
        event.setImportance(2);
        event.setGameHour(101L);
        event.setCharacterIds("npc:1"); // 当事人（在场名单来源）
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_EVENT_RELEVANCE,
                new DecisionGate.ScoreOutcome(1, 0.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("eventRelevanceSync", 1L, event);

        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
        verify(brainTierService, never()).applyAttention(anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("N5 异步入口：场景未启用 → 零成本跳过（不触碰角色仓库）")
    void tickAsyncDisabledSkips() {
        when(gate.isEnabled(DecisionScenes.ATTENTION)).thenReturn(false);
        when(gate.isEnabled(DecisionScenes.MICRO_DECISION)).thenReturn(false);
        service.tickAsync(1L, 1_000_000L);
        service.eventRelevanceAsync(1L, trivialEvent());
        // 无任何异步工作：角色仓库未被触碰（gate 开关短路）
        verify(characterRepository, never()).findByProjectIdAndDeletedOrderByIdAsc(anyLong(), anyInt());
    }

    @Test
    @DisplayName("N5 调用风暴抓修：kind=relation 事件 → 跳过全 NPC 相关度评估（零 gate 调用）")
    void eventRelevanceAsyncSkipsRelationKind() {
        // relation 事件 = 关系更新后的派生记录（单次群聊可 500+ 条），逐条评估 = 调用风暴放大器
        ActorEvent relation = new ActorEvent();
        relation.setId(200L);
        relation.setProjectId(1L);
        relation.setKind("relation");
        relation.setContent("好感 100→101（Δ1），信任 28→28；共同经历事件：云来茶馆 · NPC 自发闲聊收束");
        relation.setImportance(3);
        service.eventRelevanceAsync(1L, relation);
        // relation 类事件被入口过滤：不提交异步任务、不触碰角色仓库、不触碰 gate
        verify(characterRepository, never()).findByProjectIdAndDeletedOrderByIdAsc(anyLong(), anyInt());
        verify(gate, never()).isEnabled(DecisionScenes.ATTENTION);
    }

    @Test
    @DisplayName("N5 调用风暴抓修：非 relation 事件（roleplay）仍正常触发相关度评估")
    void eventRelevanceAsyncKeepsRoleplayKind() throws Exception {
        ActorEvent roleplay = new ActorEvent();
        roleplay.setId(201L);
        roleplay.setProjectId(1L);
        roleplay.setKind("roleplay");
        roleplay.setContent("16 名角色在 云来茶馆 交谈");
        roleplay.setImportance(2);
        roleplay.setGameHour(100L);
        roleplay.setCharacterIds("npc:1"); // 当事人（在场名单来源）
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_EVENT_RELEVANCE,
                new DecisionGate.ScoreOutcome(3, 2.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("eventRelevanceSync", 1L, roleplay);

        verify(memoryWorkingService).writePerception(eq(1L), eq("npc:1"), anyString(), eq(2));
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 100L * 3600L);
    }

    private ActorEvent trivialEvent() {
        ActorEvent e = new ActorEvent();
        e.setId(99L);
        e.setProjectId(1L);
        e.setContent("村口来了个卖货郎");
        e.setImportance(1);
        return e;
    }

    // ==================== 在场名单修（2026-10-08：偶遇场景不再评估全 NPC） ====================

    /** 构造测试角色（location 为当前地点名，供同地点在场匹配）。 */
    private ActorCharacter character(long id, String name, String location) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(1L);
        c.setName(name);
        c.setType("special");
        c.setStage("YOUTH");
        c.setCurrentActivity("在忙");
        c.setLocation(location);
        return c;
    }

    /** 捕获一次 attention 批量调用的事件相关度 state 名单（key=npcRef）。 */
    @SuppressWarnings("unchecked")
    private java.util.Set<String> capturedRelevanceStates() throws Exception {
        org.mockito.ArgumentCaptor<Map<String, DecisionState>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gate).mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                captor.capture(), any(Map.class));
        return captor.getValue().keySet();
    }

    private DecisionGate.ScoreOutcome score(int level) {
        return new DecisionGate.ScoreOutcome(level, level - 0.1, true,
                DecisionGate.DISP_ADOPTED, null, null, null, 0);
    }

    @Test
    @DisplayName("N5 在场修：move_encounter 偶遇只评估双方当事人在场，无关 NPC 零评估")
    void eventRelevanceEncounterOnlyBothPresentParties() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1L, "沈守拙", "村口粮铺"),
                        character(2L, "王阿婆", "村口粮铺"),
                        character(3L, "林听竹", "青禾村古井")));
        ActorEvent encounter = new ActorEvent();
        encounter.setId(30L);
        encounter.setProjectId(1L);
        encounter.setKind("move_encounter");
        encounter.setContent("沈守拙 途中与 王阿婆 招呼：早啊");
        encounter.setCharacterIds("npc:1,npc:2"); // 偶遇双方当事人（在场）
        encounter.setVisibility("location");
        encounter.setImportance(3);
        encounter.setGameHour(100L);
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of(
                "npc:1", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(3)),
                "npc:2", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(2))));

        invokeSync("eventRelevanceSync", 1L, encounter);

        // 只有 2 位当事人入评（此前全 NPC 评估，现在 npc:3 零评估）
        assertEquals(java.util.Set.of("npc:1", "npc:2"), capturedRelevanceStates());
        verify(memoryWorkingService).writePerception(eq(1L), eq("npc:1"), anyString(), eq(2));
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 100L * 3600L);
        verify(brainTierService, never()).applyAttention(eq(1L), eq("npc:3"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("N5 在场修：location 可见事件 = 当事人 + 同地点在场者（location_name 匹配 NPC 当前 location）")
    void eventRelevanceLocationIncludesSameLocationWitnesses() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1L, "沈守拙", "村口粮铺"),
                        character(2L, "陈百草", "村口粮铺"),
                        character(3L, "林听竹", "青禾村古井")));
        ActorEvent ev = new ActorEvent();
        ev.setId(31L);
        ev.setProjectId(1L);
        ev.setContent("沈守拙在村口粮铺挑拣粮根");
        ev.setCharacterIds("npc:1");
        ev.setVisibility("location");
        ev.setLocationName("村口粮铺"); // 同地点在场者 = 陈百草
        ev.setImportance(3);
        ev.setGameHour(100L);
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of(
                "npc:1", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(3)),
                "npc:2", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(2))));

        invokeSync("eventRelevanceSync", 1L, ev);

        assertEquals(java.util.Set.of("npc:1", "npc:2"), capturedRelevanceStates());
    }

    @Test
    @DisplayName("N5 在场修：private 事件仅当事人评估（同地点在场者也不评估）")
    void eventRelevancePrivateOnlyParticipants() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1L, "沈守拙", "村口粮铺"),
                        character(2L, "王阿婆", "村口粮铺"),
                        character(3L, "林听竹", "青禾村古井")));
        ActorEvent ev = new ActorEvent();
        ev.setId(32L);
        ev.setProjectId(1L);
        ev.setContent("沈守拙与王阿婆私下说了一件事");
        ev.setCharacterIds("npc:1,npc:2");
        ev.setVisibility("private");
        ev.setLocationName("村口粮铺"); // private 不看地点
        ev.setImportance(3);
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of(
                "npc:1", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(3)),
                "npc:2", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(2))));

        invokeSync("eventRelevanceSync", 1L, ev);

        assertEquals(java.util.Set.of("npc:1", "npc:2"), capturedRelevanceStates());
    }

    @Test
    @DisplayName("N5 在场修：global 事件全项目知晓 → 全部存活 NPC 评估（行为不变）")
    void eventRelevanceGlobalAllAlive() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1L, "沈守拙", "村口粮铺"),
                        character(2L, "王阿婆", "村口粮铺"),
                        character(3L, "林听竹", "青禾村古井")));
        ActorEvent ev = new ActorEvent();
        ev.setId(33L);
        ev.setProjectId(1L);
        ev.setContent("天降暴雨，全村的庄稼受损");
        ev.setVisibility("global");
        ev.setImportance(4);
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of(
                "npc:1", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(3)),
                "npc:2", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(2)),
                "npc:3", Map.of(CognitiveAttentionService.QID_EVENT_RELEVANCE, score(1))));

        invokeSync("eventRelevanceSync", 1L, ev);

        assertEquals(java.util.Set.of("npc:1", "npc:2", "npc:3"), capturedRelevanceStates());
    }

    @Test
    @DisplayName("N5 在场修：location 事件无当事人且无地点名 → 无在场者，零 gate 调用")
    void eventRelevanceNoPresentSkips() throws Exception {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(character(1L, "沈守拙", "村口粮铺"),
                        character(2L, "王阿婆", "村口粮铺")));
        ActorEvent ev = new ActorEvent();
        ev.setId(34L);
        ev.setProjectId(1L);
        ev.setContent("有人丢了东西"); // 无 character_ids、无 location_name → 在场者=空
        ev.setVisibility("location");
        ev.setImportance(3);

        invokeSync("eventRelevanceSync", 1L, ev);

        verify(gate, never()).mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(), any());
        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
    }


    @Test
    @DisplayName("N5 情境文本构建：自包含且截断（超长截断到 stateTextMaxChars）")
    void buildStateTextTruncates() throws Exception {
        properties.getAttention().setStateTextMaxChars(60);
        ch.setCurrentActivity("正在做一件非常非常非常非常非常非常非常非常非常长的事情来测试截断");
        Method m = CognitiveAttentionService.class.getDeclaredMethod("buildStateText",
                Long.class, ActorCharacter.class, Long.class, String.class);
        m.setAccessible(true);
        Object text = m.invoke(service, 1L, ch, 1_000_000L, null);
        assertNotNull(text);
        assertNotNull(text.toString());
    }

    // ==================== N6 认知 LOD 深化（2026-09-27：参与度 / 相机聚焦 / 注意力合成） ====================

    @Test
    @DisplayName("N6 参与度统计：加权次数按步长分档（2→1、4→2、6→3）；窗口外重置")
    void participationLevelWindowed() {
        // 近窗口内：spoke 1 次 → 加权 1 < 步长 2 → 档位 0（开口一次不算「常参与」）
        service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_000_000L);
        assertEquals(0, service.participationLevel(1L, "npc:1"));
        // 再 spoke 1 次 → 加权 2 ≥ 步长 → 档位 1
        service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_001_000L);
        assertEquals(1, service.participationLevel(1L, "npc:1"));
        // 再 spoke 2 次 → 加权 4 ≥ 2×步长 → 档位 2
        service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_002_000L);
        service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_003_000L);
        assertEquals(2, service.participationLevel(1L, "npc:1"));
        // attended 权重 0.5：再加 4 次在场 → 加权 6 ≥ 3×步长 → 档位 3
        for (int i = 0; i < 4; i++) {
            service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_ATTENDED, 1_004_000L + i);
        }
        assertEquals(3, service.participationLevel(1L, "npc:1"));
        // 窗口外（> 6 游戏小时=21600s 之后）→ 窗口重置，档位回落
        service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_000_000L + 21_700L);
        assertEquals(0, service.participationLevel(1L, "npc:1"), "超窗后新窗口重新计数");
    }

    @Test
    @DisplayName("N6 参与度回灌注意力：参与度档 2 + 语义档位 2 → applyAttention(3)（+1 封顶）")
    void tickSyncParticipationBoostsAttention() throws Exception {
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_ATTENTION_STATE,
                new DecisionGate.ScoreOutcome(2, 1.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.MICRO_DECISION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_MICRO_INTENT,
                new DecisionGate.ChoiceOutcome("continue_current", true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));
        // 近窗口内 4 次发言 → 参与度档 2
        for (int i = 0; i < 4; i++) {
            service.recordParticipation(1L, "npc:1", CognitiveAttentionService.PARTICIPATION_SPOKE, 1_000_000L + i);
        }

        invokeSync("tickSync", 1L, 1_000_000L);

        // 语义档 2 + 参与度加成 → 3（封顶）；微决策 continue_current 零副作用
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 1_000_000L);
        verify(memoryWorkingService, never()).writePerception(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("N6 相机注意力聚焦：距离连续映射（≤25→3、≤50→2、≤100→1、>100→0；无相机=0）")
    void cameraAttentionLevelMapping() {
        com.holzyn.actor.domain.growth.lod.CameraDistanceProvider camera =
                mock(com.holzyn.actor.domain.growth.lod.CameraDistanceProvider.class);
        ReflectionTestUtils.setField(service, "cameraDistanceProvider", camera);
        when(camera.hasActiveCamera(1L)).thenReturn(true);
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(10.0);
        assertEquals(3, service.cameraAttentionLevel(1L, "npc:1"));
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(40.0);
        assertEquals(2, service.cameraAttentionLevel(1L, "npc:1"));
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(80.0);
        assertEquals(1, service.cameraAttentionLevel(1L, "npc:1"));
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(150.0);
        assertEquals(0, service.cameraAttentionLevel(1L, "npc:1"));
        // 无活动相机 → 0（零副作用）
        when(camera.hasActiveCamera(1L)).thenReturn(false);
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(10.0);
        assertEquals(0, service.cameraAttentionLevel(1L, "npc:1"));
    }

    @Test
    @DisplayName("N6 tick 注意力合成：相机近处（档 3）覆盖低语义档位（取 max，只抬不压）")
    void tickSyncCameraOverridesLowSemanticLevel() throws Exception {
        com.holzyn.actor.domain.growth.lod.CameraDistanceProvider camera =
                mock(com.holzyn.actor.domain.growth.lod.CameraDistanceProvider.class);
        ReflectionTestUtils.setField(service, "cameraDistanceProvider", camera);
        when(camera.hasActiveCamera(1L)).thenReturn(true);
        when(camera.distanceMeters(1L, "npc:1")).thenReturn(8.0);
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.ATTENTION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_ATTENTION_STATE,
                new DecisionGate.ScoreOutcome(1, 0.9, true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));
        when(gate.mixedBatchDecisions(eq(1L), eq(DecisionScenes.MICRO_DECISION),
                any(Map.class), any(Map.class))).thenReturn(Map.of("npc:1", Map.of(
                CognitiveAttentionService.QID_MICRO_INTENT,
                new DecisionGate.ChoiceOutcome("continue_current", true,
                        DecisionGate.DISP_ADOPTED, null, null, null, 0))));

        invokeSync("tickSync", 1L, 1_000_000L);

        // 语义档 1 → 相机档 3（max）→ applyAttention(3)
        verify(brainTierService).applyAttention(1L, "npc:1", 3, 1_000_000L);
    }

    @Test
    @DisplayName("N6 接口实现：tierOf 委托 BrainTierService；无装配返回 null/0（消费方零阻断）")
    void providerInterfaceFallbacks() {
        assertEquals(BrainTierService.TIER_AMBIENT, service.tierOf(1L, "npc:1"));
        ReflectionTestUtils.setField(service, "brainTierService", null);
        assertEquals(null, service.tierOf(1L, "npc:1"));
        // 相机未装配 → 0；参与度未记录 → 0
        assertEquals(0, service.cameraAttentionLevel(1L, "npc:1"));
        assertEquals(0, service.participationLevel(1L, "npc:1"));
    }
}
