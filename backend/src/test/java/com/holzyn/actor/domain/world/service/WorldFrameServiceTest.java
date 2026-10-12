package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.WorldFrameProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldFrameVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日世界帧服务单元测试（世界演化与游戏时间系统 M4，domain/world/service）。
 * <p>职责：验证——① 每日生成 AI 主通道成功（帧+信息包+宏观事件发布，source=ai）；
 * ② 幂等/缓存 24h（当日帧已存在 → AI 零调用零写库）；③ AI 失败回退机制兜底帧
 * （1 条宏观事件 + 1 条衍生信息包，source=mechanism）；④ AI 帧含 NPC 名称 → 边界校验失败
 * 回退机制兜底；⑤ 新帧生成旧 active 置 superseded；⑥ 紧急更新就地更新（source=emergency/
 * emergency=1/同 frameId/天气保持）+ 限频 400 + 无 active 帧 404；⑦ 自动触发判定静态逻辑
 * （重要度阈值/world_frame 自身事件防环）。</p>
 */
class WorldFrameServiceTest {

    private ActorWorldFrameRepository frameRepository;
    private ActorInfoPacketRepository packetRepository;
    private ActorInfoDiffusionRepository diffusionRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private WorldLocationRepository locationRepository;
    private ActorCharacterRepository characterRepository;
    private WorldEventBus worldEventBus;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private WorldFrameProperties props;
    private WorldFrameService service;

    /** 已保存帧快照（验证产物） */
    private final List<ActorWorldFrame> savedFrames = new ArrayList<>();
    /** 已保存信息包快照 */
    private final List<ActorInfoPacket> savedPackets = new ArrayList<>();
    /** 已发布事件快照 */
    private final List<EventSpec> published = new ArrayList<>();

    /** 合法 AI 帧 JSON（无 NPC 名称）。 */
    private static final String AI_FRAME_JSON = """
            { "gameDay": 1, "weather": { "青禾村": { "condition": "晴", "temperature": 18, "wind": "东南风" } },
              "macro_events": [ { "event_id": "E-1-001", "type": "集市繁荣", "location": "青禾村", "severity": 3,
                "summary": "青禾村集市迎来入秋以来最热闹的一天，粮价平稳。", "start_day": 1, "duration_days": 2,
                "affected_area": ["青禾村"] } ],
              "info_packages": [ { "info_id": "I-1-001-1", "origin": "青禾村", "content": "集市粮价平稳，货品充足。",
                "reliability": 0.8, "spread_channels": ["商队"], "spread_speed_days_per_km": 1.0,
                "valid_until_day": 4, "distortion_rules": [], "visibility_condition": null } ],
              "environment_changes": [] }
            """;

    /** 含 NPC 名称的违规 AI 帧 JSON（校验应失败回退机制兜底）。 */
    private static final String NPC_VIOLATION_JSON = """
            { "gameDay": 1, "weather": {},
              "macro_events": [ { "event_id": "E-1-001", "type": "议事", "location": "祠堂", "severity": 3,
                "summary": "沈砚秋主持了祠堂议事，决定修缮堤坝。", "start_day": 1, "duration_days": 2,
                "affected_area": ["祠堂"] } ],
              "info_packages": [], "environment_changes": [] }
            """;

    @BeforeEach
    void setUp() {
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        diffusionRepository = Mockito.mock(ActorInfoDiffusionRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        props = new WorldFrameProperties();
        service = new WorldFrameService(frameRepository, packetRepository, diffusionRepository,
                worldSettingRepository, locationRepository, characterRepository, worldEventBus,
                promptTemplateService, aiChatGateway, usageLogService, props, new ObjectMapper());

        CurrentUserProvider c = Mockito.mock(CurrentUserProvider.class);
        when(c.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(service, "currentUserProvider", c);

        // 模板渲染默认
        when(promptTemplateService.render(any(), any(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(any(), any(), anyString(), any())).thenReturn("你只输出严格的 JSON");
        // 仓库默认
        when(frameRepository.findByProjectIdAndGameDay(any(), any())).thenReturn(Optional.empty());
        when(frameRepository.findByProjectIdOrderByGameDayDesc(any())).thenReturn(List.of());
        when(frameRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of());
        when(packetRepository.findByFrameId(any())).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(locationRepository.findByProjectId(any())).thenReturn(List.of());
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(any())).thenReturn(Optional.empty());
        // 保存快照（mock 回填自增 id——真实 JPA IDENTITY 插入后回填，replacePackets 依赖非空 frameId）
        Mockito.doAnswer(inv -> {
            ActorWorldFrame f = inv.getArgument(0);
            if (f.getId() == null) {
                f.setId(100L + savedFrames.size());
            }
            savedFrames.add(f);
            return f;
        }).when(frameRepository).save(any(ActorWorldFrame.class));
        Mockito.doAnswer(inv -> {
            savedPackets.add(inv.getArgument(0));
            return inv.getArgument(0);
        }).when(packetRepository).save(any(ActorInfoPacket.class));
        Mockito.doAnswer(inv -> {
            published.add(inv.getArgument(2));
            return null;
        }).when(worldEventBus).publish(any(), any(), any(EventSpec.class));
    }

    /** mock AI 帧生成返回。 */
    private void mockAiFrame(String json) {
        AiChatResult result = Mockito.mock(AiChatResult.class);
        when(result.content()).thenReturn(json);
        when(result.providerId()).thenReturn(1L);
        when(result.model()).thenReturn("mock-model");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);
    }

    /** 构造一帧（紧急更新当前 active 帧用）。 */
    private ActorWorldFrame frame(Long id, Long projectId, Long gameDay, String source, String weatherJson) {
        ActorWorldFrame f = new ActorWorldFrame();
        f.setId(id);
        f.setProjectId(projectId);
        f.setGameDay(gameDay);
        f.setSource(source);
        f.setStatus("active");
        f.setWeatherJson(weatherJson);
        f.setGeneratedAtGameSecond(gameDay * 86400L);
        return f;
    }

    private ActorCharacter charOf(String name) {
        ActorCharacter ch = new ActorCharacter();
        ch.setName(name);
        ch.setProjectId(1L);
        return ch;
    }

    // ==================== 每日生成 ====================

    @Test
    void generateDaily_aiSuccessPersistsFramePacketAndPublishesEvents() {
        mockAiFrame(AI_FRAME_JSON);
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        assertEquals(1, savedFrames.size(), "应保存 1 帧");
        ActorWorldFrame f = savedFrames.get(0);
        assertEquals("ai", f.getSource());
        assertEquals("active", f.getStatus());
        assertEquals(1L, f.getGameDay());
        assertEquals(Long.valueOf(86400L), f.getGeneratedAtGameSecond());
        assertEquals(1, savedPackets.size(), "应实体化 1 条信息包");
        assertEquals("I-1-001-1", savedPackets.get(0).getInfoId());
        // 宏观事件发布：1 条
        assertEquals(1, published.size(), "应发布 1 条宏观事件");
        EventSpec spec = published.get(0);
        assertEquals("world_frame", spec.getKind());
        assertEquals("engine", spec.getSource());
        assertEquals("global", spec.getVisibility());
        assertEquals("frame:1:1:E-1-001", spec.getSourceKey());
        assertEquals(3, spec.getImportance());
        verify(aiChatGateway, Mockito.times(1)).chatCompletion(any(), any(), any(), any());
    }

    /** 批6 收口：world_frame_daily 属「世界视角」——AI 帧请求前置 段2D【当前世界动态】。 */
    @Test
    void generateDaily_aiRequestPrependsWorldDynamics() {
        mockAiFrame(AI_FRAME_JSON);
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年05月15日 · 夏 · 天气晴");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(any(), any(), any(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertTrue(userMsg.startsWith("【世界观】"), "world_frame_daily 必须前置 段1A【世界观】: " + userMsg);
        assertTrue(userMsg.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + userMsg);
    }

    @Test
    void generateDaily_idempotentSkipsWhenFrameExists() {
        when(frameRepository.findByProjectIdAndGameDay(1L, 1L)).thenReturn(Optional.of(frame(9L, 1L, 1L, "ai", null)));
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        assertEquals(0, savedFrames.size(), "幂等：当日帧已存在不重复生成");
        assertEquals(0, published.size());
    }

    @Test
    void generateDaily_aiFailureFallsBackToMechanismFrame() {
        when(aiChatGateway.chatCompletion(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        assertEquals(1, savedFrames.size());
        assertEquals("mechanism", savedFrames.get(0).getSource(), "AI 失败应回退机制兜底帧");
        assertEquals(1, savedPackets.size(), "机制兜底帧应含 1 条衍生信息包");
        // 机制帧宏观事件同样发布
        assertEquals(1, published.size());
        assertEquals("frame:1:1:E-1-001", published.get(0).getSourceKey());
    }

    @Test
    void generateDaily_npcNameViolationFallsBackToMechanism() {
        mockAiFrame(NPC_VIOLATION_JSON);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(charOf("沈砚秋"), charOf("陆沉舟")));
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        assertEquals(1, savedFrames.size());
        assertEquals("mechanism", savedFrames.get(0).getSource(), "边界校验失败应回退机制兜底帧");
        assertEquals("active", savedFrames.get(0).getStatus());
    }

    @Test
    void generateDaily_aiDisabledGoesMechanism() {
        props.setAiEnabled(false);
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        assertEquals("mechanism", savedFrames.get(0).getSource());
    }

    // ==================== M5.5 微层接线：AI 强度档位（世界帧 AI 主通道） ====================

    @Test
    void generateDaily_programmaticIntensityGoesMechanismFrame() {
        // AI 强度 programmatic 档：即使帧 AI 开关开着也零调用——AI 主通道整体关闭走机制帧
        props.setAiEnabled(true);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository repo =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository.class);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                new com.holzyn.actor.domain.agent.runtime.ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setAiIntensity("programmatic");
        when(repo.findByProjectId(1L)).thenReturn(Optional.of(engine));
        com.holzyn.actor.domain.agent.runtime.AiIntensityGate gate =
                new com.holzyn.actor.domain.agent.runtime.AiIntensityGate(repo);
        ReflectionTestUtils.setField(service, "aiIntensityGate", gate);

        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        assertEquals("mechanism", savedFrames.get(0).getSource(), "programmatic 档零 AI：恒机制帧");
        assertEquals(1, savedPackets.size(), "机制帧仍实体化 1 条衍生信息包（回灌路③不缺失）");
    }

    @Test
    void generateDaily_maxIntensityStillUsesAiChannel() {
        // max 档：AI 主通道正常走（帧独立预算每日≤1 次天然无限制）
        props.setAiEnabled(true);
        mockAiFrame(AI_FRAME_JSON);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository repo =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository.class);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                new com.holzyn.actor.domain.agent.runtime.ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setAiIntensity("max");
        when(repo.findByProjectId(1L)).thenReturn(Optional.of(engine));
        com.holzyn.actor.domain.agent.runtime.AiIntensityGate gate =
                new com.holzyn.actor.domain.agent.runtime.AiIntensityGate(repo);
        ReflectionTestUtils.setField(service, "aiIntensityGate", gate);

        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        verify(aiChatGateway, Mockito.times(1)).chatCompletion(any(), any(), any(), any());
        assertEquals("ai", savedFrames.get(0).getSource());
    }

    @Test
    void generateDaily_supersedesPreviousActive() {
        mockAiFrame(AI_FRAME_JSON);
        // 第 1 日帧已存在（active）
        ActorWorldFrame day1 = frame(1L, 1L, 1L, "ai", "{\"青禾村\":{\"condition\":\"晴\"}}");
        when(frameRepository.findByProjectIdAndGameDay(1L, 2L)).thenReturn(Optional.empty());
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(day1));
        when(frameRepository.findByProjectIdOrderByGameDayDesc(1L)).thenReturn(List.of(day1));

        service.generateDailyFrameIfNeeded(1L, 2L, 2L * 86400L);

        assertEquals("superseded", day1.getStatus(), "旧 active 帧应置 superseded");
        assertEquals(2, savedFrames.size(), "应保存第 2 日新帧");
        assertEquals(2L, savedFrames.get(1).getGameDay());
    }

    // ==================== 紧急更新 ====================

    @Test
    void emergencyUpdate_inPlaceUpdatesCurrentActiveFrame() {
        mockAiFrame(AI_FRAME_JSON);
        ActorWorldFrame current = frame(5L, 1L, 1L, "ai",
                "{\"青禾村\":{\"condition\":\"晴\",\"temperature\":18,\"wind\":\"东南风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(current));

        WorldFrameVO vo = service.emergencyUpdate(1L, "战争爆发", "青禾村", "灵脉封印异动", 86400L);

        assertNotNull(vo);
        assertEquals(5L, vo.getFrameId(), "紧急更新应就地更新同一帧（同 frameId）");
        ActorWorldFrame saved = savedFrames.get(0);
        assertEquals("emergency", saved.getSource());
        assertEquals(Integer.valueOf(1), saved.getEmergency());
        assertEquals(5L, saved.getId());
        // 天气保持原帧（AI 帧 weather 被合并逻辑丢弃，取原帧）
        assertTrue(saved.getWeatherJson().contains("青禾村"), "紧急更新应保持原帧天气");
        // 信息包替换
        verify(packetRepository).findByFrameId(5L);
    }

    @Test
    void emergencyUpdate_rateLimitedSameGameDay() {
        mockAiFrame(AI_FRAME_JSON);
        ActorWorldFrame current = frame(5L, 1L, 1L, "ai", null);
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(current));

        service.emergencyUpdate(1L, "战争爆发", "青禾村", "灵脉封印异动", 86400L);
        BizException ex = assertThrows(BizException.class,
                () -> service.emergencyUpdate(1L, "灾荒", "青禾村", "粮价飞涨", 86400L),
                "同日第二次紧急更新应超限");
        assertTrue(ex.getMessage().contains("每日限制"), "限频错误信息应明确: " + ex.getMessage());
    }

    @Test
    void emergencyUpdate_noActiveFrameThrows404() {
        BizException ex = assertThrows(BizException.class,
                () -> service.emergencyUpdate(1L, "战争爆发", "青禾村", "异动", 86400L));
        assertEquals(404, ex.getCode(), "无 active 帧应 404");
    }

    @Test
    void emergencyUpdate_aiDisabledMechanismKeepsWeather() {
        props.setAiEnabled(false);
        ActorWorldFrame current = frame(5L, 1L, 1L, "ai",
                "{\"青禾村\":{\"condition\":\"小雨\",\"temperature\":12,\"wind\":\"北风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(current));

        service.emergencyUpdate(1L, "灵脉异动", "枯井", "深夜灵脉异动，村民传言四起", 86400L);

        ActorWorldFrame saved = savedFrames.get(0);
        assertEquals("emergency", saved.getSource());
        assertTrue(saved.getWeatherJson().contains("小雨"), "机制紧急帧应保持原帧天气");
        assertEquals(1, published.size(), "机制紧急帧宏观事件应发布");
        assertEquals("frame:1:1:E-1-001e", published.get(0).getSourceKey(), "紧急事件 event_id 带 e 后缀");
    }

    @Test
    void replacePackets_deletesOldDiffusionRowsOnEmergency() {
        // M5（信息扩散）：紧急就地更新替换信息包时，旧包的扩散状态行应级联清理（孤儿行不再参与过滤/回放）
        ActorInfoPacket old = new ActorInfoPacket();
        old.setId(7L);
        when(packetRepository.findByFrameId(5L)).thenReturn(List.of(old));
        ActorInfoDiffusion d = new ActorInfoDiffusion();
        d.setId(1L);
        when(diffusionRepository.findByPacketId(7L)).thenReturn(List.of(d));
        mockAiFrame(AI_FRAME_JSON);
        ActorWorldFrame current = frame(5L, 1L, 1L, "ai", "{\"青禾村\":{\"condition\":\"晴\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(current));

        service.emergencyUpdate(1L, "战争爆发", "青禾村", "异动", 86400L);

        verify(diffusionRepository).deleteAll(List.of(d));
        verify(packetRepository).deleteAll(List.of(old));
        assertEquals(1, savedPackets.size(), "紧急更新后应建新信息包");
    }

    // ==================== 自动触发判定（静态） ====================

    @Test
    void shouldTriggerEmergency_filtersByImportanceAndKind() {
        WorldFrameProperties p = new WorldFrameProperties();
        p.setEmergencyImportanceThreshold(4);
        ActorEvent e = new ActorEvent();
        e.setProjectId(1L);
        e.setImportance(4);
        e.setKind("world");
        assertTrue(WorldFrameService.shouldTriggerEmergency(e, p), "重要度≥阈值应触发");

        e.setImportance(3);
        assertFalse(WorldFrameService.shouldTriggerEmergency(e, p), "低于阈值不触发");

        e.setImportance(5);
        e.setKind("world_frame");
        assertFalse(WorldFrameService.shouldTriggerEmergency(e, p), "world_frame 自身事件防自触发循环");

        assertFalse(WorldFrameService.shouldTriggerEmergency(null, p), "null 事件不触发");
    }

    @Test
    void getActiveFrame_returnsVoOrThrows() {
        when(frameRepository.findByProjectIdAndStatus(1L, "active"))
                .thenReturn(List.of(frame(5L, 1L, 1L, "ai", null)));
        WorldFrameVO vo = service.getActiveFrame(1L);
        assertNotNull(vo);
        assertEquals(5L, vo.getFrameId());

        when(frameRepository.findByProjectIdAndStatus(2L, "active")).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.getActiveFrame(2L), "无 active 帧应 404");
    }

    @Test
    void listFrames_returnsDescendingWithLimit() {
        when(frameRepository.findByProjectIdOrderByGameDayDesc(1L))
                .thenReturn(List.of(frame(3L, 1L, 3L, "ai", null), frame(2L, 1L, 2L, "ai", null)));
        assertEquals(2, service.listFrames(1L, null).size());
        assertEquals(3L, service.listFrames(1L, null).get(0).getGameDay());
        assertEquals(1, service.listFrames(1L, 1).size(), "limit 应生效");
    }

    // ==================== 时间季节系统 M1（2026-09-15）：季节落库/机制季节天气/更替事件 ====================

    @Test
    void generateDaily_frameSavesSeasonByGameMonth() {
        mockAiFrame(AI_FRAME_JSON);
        // gameSecond=86400 → 第 1 游戏日 → 游戏月 1 → 春
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        assertEquals(1, savedFrames.size());
        assertEquals("spring", savedFrames.get(0).getSeason(), "帧落库应带季节（按游戏月固定绑定）");
    }

    @Test
    void generateDaily_mechanismFrameUsesSeasonalWeatherTempWind() {
        props.setAiEnabled(false);
        // 注入季节服务（生产为 Spring 装配；单测手动注入 → 机制帧按季节倾向选天气）
        ReflectionTestUtils.setField(service, "seasonService", new SeasonService());
        // 机制兜底帧：春（1 月）倾向 {晴40,雨35,阴20,风5}，gameDay=1 → 晴；气温=mid17+晴2=19；风=东风
        service.generateDailyFrameIfNeeded(1L, 1L, 86400L);

        ActorWorldFrame f = savedFrames.get(0);
        assertEquals("spring", f.getSeason());
        assertNotNull(f.getWeatherJson(), "机制帧应产出天气");
        assertTrue(f.getWeatherJson().contains("晴"), "春机制帧天气应来自季节倾向: " + f.getWeatherJson());
        assertTrue(f.getWeatherJson().contains("19.0"), "机制帧气温=季基准+天气修正: " + f.getWeatherJson());
        assertTrue(f.getWeatherJson().contains("东风"), "机制帧风=季节默认风向: " + f.getWeatherJson());
    }

    @Test
    void generateDaily_seasonChangePublishesWorldEvent() {
        ReflectionTestUtils.setField(service, "climateEnabled", true);
        mockAiFrame(AI_FRAME_JSON);
        // 前帧：第 89 日（3 月，春）；新帧：第 90 日（4 月首日，夏）→ 季节更替
        ActorWorldFrame prev = frame(7L, 1L, 89L, "ai", "{\"青禾村\":{\"condition\":\"晴\"}}");
        prev.setSeason("spring");
        when(frameRepository.findByProjectIdOrderByGameDayDesc(1L)).thenReturn(List.of(prev));
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(prev));

        service.generateDailyFrameIfNeeded(1L, 90L, 90L * 86400L);

        assertEquals("summer", savedFrames.get(1).getSeason(), "新帧季节=夏");
        // 事件 2 条：宏观事件 + 季节更替事件
        assertEquals(2, published.size(), "应发布宏观事件 + 季节更替事件");
        EventSpec seasonSpec = published.get(1);
        assertEquals("world", seasonSpec.getKind(), "季节事件 kind=world（非 world_frame 防紧急自触发）");
        assertEquals("engine", seasonSpec.getSource());
        assertEquals(3, seasonSpec.getImportance(), "重要度 3：触发实时决策 NPC 感知");
        assertEquals("season:1:90", seasonSpec.getSourceKey(), "幂等键 season:{pid}:{day}");
        assertTrue(seasonSpec.getTitle().contains("夏"), "季节事件标题: " + seasonSpec.getTitle());
        assertTrue(seasonSpec.getContent().contains("春"), "季节事件内容含旧季: " + seasonSpec.getContent());
    }

    @Test
    void generateDaily_sameSeasonNoSeasonEvent() {
        ReflectionTestUtils.setField(service, "climateEnabled", true);
        mockAiFrame(AI_FRAME_JSON);
        // 前帧：第 1 日（春）；新帧：第 2 日（春）→ 无更替
        ActorWorldFrame prev = frame(7L, 1L, 1L, "ai", null);
        prev.setSeason("spring");
        when(frameRepository.findByProjectIdOrderByGameDayDesc(1L)).thenReturn(List.of(prev));
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(prev));

        service.generateDailyFrameIfNeeded(1L, 2L, 2L * 86400L);

        assertEquals(1, published.size(), "同季节不发布更替事件（仅宏观事件）");
    }

    @Test
    void getActiveFrame_returnsSeasonInVo() {
        ActorWorldFrame f = frame(5L, 1L, 1L, "ai", null);
        f.setSeason("spring");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));
        WorldFrameVO vo = service.getActiveFrame(1L);
        assertEquals("spring", vo.getSeason(), "VO 应带季节字段");
    }

    // ==================== M6 世界帧摘要（activeFrameSummary：演化编排注入事实源） ====================

    @Test
    void activeFrameSummary_buildsCompactTextFromActiveFrame() {
        ActorWorldFrame f = frame(5L, 1L, 5L, "ai", "{\"青禾农田\": {\"condition\": \"晴\", \"temperature\": 15, \"wind\": \"北风\"}}");
        f.setMacroEventsJson("[{\"event_id\":\"E-5-001\",\"type\":\"灵脉异动\",\"location\":\"枯井\",\"severity\":4,"
                + "\"summary\":\"枯井深夜传出异响\",\"start_day\":5,\"duration_days\":3}]");
        f.setInfoPacketsJson("[{\"info_id\":\"I-5-001-1\",\"origin\":\"官府\",\"content\":\"寻人告示\"}]");
        f.setEnvironmentChangesJson("[{\"scene\":\"村口\",\"change\":\"雨后泥泞\"}]");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        String text = service.activeFrameSummary(1L);
        assertFalse(text.isBlank(), "有 active 帧应返回摘要");
        assertTrue(text.contains("第5游戏日"), "含游戏日");
        assertTrue(text.contains("青禾农田 晴 15° 北风"), "含天气");
        assertTrue(text.contains("枯井深夜传出异响"), "含宏观事件");
        assertTrue(text.contains("寻人告示"), "含信息包");
        assertTrue(text.contains("雨后泥泞"), "含环境变化");
    }

    @Test
    void activeFrameSummary_noActiveFrameReturnsEmpty() {
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of());
        assertEquals("", service.activeFrameSummary(1L), "无 active 帧 → 空串（演化注入降级）");
    }

    @Test
    void activeFrameSummary_brokenJsonReturnsEmptyGracefully() {
        ActorWorldFrame f = frame(5L, 1L, 5L, "ai", "不是JSON");
        f.setMacroEventsJson(null);
        f.setInfoPacketsJson("[]");
        f.setEnvironmentChangesJson(null);
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));
        assertEquals("", service.activeFrameSummary(1L), "帧 JSON 异常 → 空串（不抛异常）");
    }
}
