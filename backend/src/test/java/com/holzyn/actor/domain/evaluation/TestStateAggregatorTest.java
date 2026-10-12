package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.agent.roleplay.AutonomousChatService;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TestStateAggregator 单元测试（O2.5 ② 状态聚合：引擎推进/群聊活跃/预算/用量归因四块）。
 */
class TestStateAggregatorTest {

    private WorldClockService worldClockService;
    private RealtimeDecisionActivationService activationService;
    private AiIntensityGate aiIntensityGate;
    private ActorWorldEngineRepository engineRepository;
    private BudgetGuard budgetGuard;
    private AutonomousChatService autonomousChatService;
    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorConversationRepository conversationRepository;
    private ActorMessageRepository messageRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private TestStateAggregator aggregator;

    @BeforeEach
    void setUp() {
        worldClockService = Mockito.mock(WorldClockService.class);
        activationService = Mockito.mock(RealtimeDecisionActivationService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        budgetGuard = new BudgetGuard();
        autonomousChatService = Mockito.mock(AutonomousChatService.class);
        aiCallLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        EvaluationProperties properties = new EvaluationProperties();
        ReflectionTestUtils.setField(properties, "defaultWindowGameMinutes", 1440);
        aggregator = new TestStateAggregator(worldClockService, activationService, aiIntensityGate,
                engineRepository, budgetGuard, autonomousChatService, aiCallLogRepository,
                conversationRepository, messageRepository, characterRepository, ordinaryNpcRepository,
                properties);
    }

    /** 四块全量聚合：引擎位置/激活态/AI 强度、群聊消息与发言者分布、预算命名空间、用量归因。 */
    @Test
    @SuppressWarnings("unchecked")
    void aggregateFourBlocks() {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setLastGameSecond(86400L);
        clock.setPaused(0);
        clock.setWorldRunning(1);
        clock.setRate(24);
        clock.setLastEngineRunAt(LocalDateTime.now());
        when(worldClockService.requireClock(1L)).thenReturn(clock);
        when(activationService.isActive(1L)).thenReturn(true);
        when(activationService.isManualEnabled(1L)).thenReturn(true);
        when(activationService.isSandboxActive(1L)).thenReturn(false);
        when(activationService.isEvolutionActive(1L)).thenReturn(false);
        when(aiIntensityGate.intensityOf(1L)).thenReturn("max");
        ActorWorldEngine engine = new ActorWorldEngine();
        engine.setRunMode("ultimate");
        engine.setBudgetPerTick(8);
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.of(engine));
        when(autonomousChatService.runningEpisodeCount()).thenReturn(2);

        // 群聊：1 个 group 会话 + 3 条消息（沈砚秋×2 / 王二×1）
        ActorConversation conv = new ActorConversation();
        conv.setId(1L);
        conv.setProjectId(1L);
        when(conversationRepository.findByProjectIdAndMode(1L, "group")).thenReturn(List.of(conv));
        ActorMessage m1 = msg(1L, 5L, LocalDateTime.now());
        ActorMessage m2 = msg(2L, 5L, LocalDateTime.now());
        ActorMessage m3 = msg(3L, 9L, LocalDateTime.now());
        when(messageRepository.findByConversationIdInAndCreatedAtGreaterThanEqualOrderByIdAsc(
                eq(List.of(1L)), any(LocalDateTime.class))).thenReturn(List.of(m1, m2, m3));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(5L);
        ch.setProjectId(1L);
        ch.setName("沈砚秋");
        when(characterRepository.findById(5L)).thenReturn(Optional.of(ch));
        ActorCharacter npc = new ActorCharacter();
        npc.setId(9L);
        npc.setProjectId(1L);
        npc.setName("王二");
        when(ordinaryNpcRepository.findById(9L)).thenReturn(Optional.of(npc));

        // 预算：引擎 tick 窗口（5）+ 剧集窗口（32 剩 20）
        budgetGuard.beginTick(1L, 5);
        budgetGuard.beginEpisode(1L, 32);
        for (int i = 0; i < 12; i++) {
            budgetGuard.tryConsumeEpisode(1L);
        }

        // 用量：4 条 AI 调用（2 成功 2 失败，含角色归因）
        when(aiCallLogRepository.findByProjectIdAndCreatedAtGreaterThanEqual(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        call(5L, "action_gen", "success", 100, 50),
                        call(5L, "action_gen", "failed", 30, 0),
                        call(9L, "dialog", "success", 60, 40),
                        call(null, "world_frame", "success", 200, 100)));

        var out = aggregator.aggregate(1L, 1440);

        // ① 引擎推进
        var engineBlock = (java.util.Map<String, Object>) out.get("engine");
        assertEquals(86400L, engineBlock.get("gameSecond"));
        assertEquals(24L, engineBlock.get("gameHour"));
        assertEquals(true, engineBlock.get("active"));
        assertEquals("max", engineBlock.get("aiIntensity"));
        assertEquals("ultimate", engineBlock.get("runMode"));
        assertEquals(8, engineBlock.get("budgetPerTick"));
        assertEquals(4, engineBlock.get("aiCallTotal"));
        assertEquals(3, engineBlock.get("aiCallSuccess"));
        assertEquals(1, engineBlock.get("aiCallFailed"));
        // ② 群聊活跃
        var chatBlock = (java.util.Map<String, Object>) out.get("chat");
        assertEquals(2, chatBlock.get("runningEpisodes"));
        assertEquals(1, chatBlock.get("groupConversations"));
        assertEquals(3, chatBlock.get("groupMessages"));
        List<java.util.Map<String, Object>> speakers = (List<java.util.Map<String, Object>>) chatBlock.get("speakers");
        assertEquals(2, speakers.size());
        assertEquals("沈砚秋", speakers.get(0).get("npcName"));
        assertEquals(2L, speakers.get(0).get("count"));
        assertEquals("npc:9", speakers.get(1).get("npcRef"));
        // ③ 预算
        var budgetBlock = (java.util.Map<String, Object>) out.get("budget");
        var tick = (java.util.Map<String, Object>) budgetBlock.get("tick");
        assertEquals(5, tick.get("total"));
        assertEquals(true, tick.get("hasWindow"));
        var episode = (java.util.Map<String, Object>) budgetBlock.get("episode");
        assertEquals(true, episode.get("hasWindow"));
        assertEquals(20, episode.get("remaining"));
        assertEquals(12, episode.get("used"));
        // ④ 用量归因
        var usageBlock = (java.util.Map<String, Object>) out.get("usage");
        assertEquals(4, usageBlock.get("totalCalls"));
        assertEquals(390L, usageBlock.get("totalTokenIn"));
        List<java.util.Map<String, Object>> byNpc = (List<java.util.Map<String, Object>>) usageBlock.get("byNpc");
        assertEquals(2, byNpc.size());
        assertTrue(byNpc.get(0).get("calls").equals(2) || byNpc.get(0).get("calls").equals(1));
        // 时间窗说明
        var window = (java.util.Map<String, Object>) out.get("window");
        assertEquals(1440, window.get("gameMinutes"));
        assertEquals(60L, window.get("realMinutes"), "1440 游戏分 / rate=24 = 60 真实分");
    }

    private ActorMessage msg(long id, Long characterId, LocalDateTime at) {
        ActorMessage m = new ActorMessage();
        m.setId(id);
        m.setConversationId(1L);
        m.setCharacterId(characterId);
        m.setCreatedAt(at);
        return m;
    }

    private ActorAiCallLog call(Long characterId, String scene, String status, int inTok, int outTok) {
        ActorAiCallLog c = new ActorAiCallLog();
        c.setId(characterId == null ? 999L : characterId * 10);
        c.setProjectId(1L);
        c.setCharacterId(characterId);
        c.setScene(scene);
        c.setStatus(status);
        c.setTokenIn(inTok);
        c.setTokenOut(outTok);
        return c;
    }
}
