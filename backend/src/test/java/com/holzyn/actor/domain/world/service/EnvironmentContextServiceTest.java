package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.EnvironmentProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.vo.EnvironmentContextVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一环境上下文服务单元测试（时间季节系统 M2/M3，domain/world/service，同包）。
 * <p>职责：验证——① 昼夜模型（冬 18:00=夜 vs 夏 18:00=昼，用户示例场景）、时刻阶段静态判定
 * （夜/晨/昼/昏边界）；② 体感气温（气温+时段修正；帧温优先、无帧温按季基准+天气修正）；
 * ③ 环境状态文本（确定性模板：冬夜全黑/夏日炎热/雨雪补句）与感官标签；④ 注入段文本形态；
 * ⑤ AI 润色（异步预生成缓存命中 source=ai-cached / 未命中回退模板 / 每日预算耗尽零调用）。</p>
 * <p>设计依据：《HolzynActor_记忆系统重构与世界时间季节系统_设计方案_V1.0》§5.2/§5.3。</p>
 */
class EnvironmentContextServiceTest {

    private ActorWorldFrameRepository frameRepository;
    private EnvironmentProperties props;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private EnvironmentContextService service;

    @BeforeEach
    void setUp() {
        frameRepository = mock(ActorWorldFrameRepository.class);
        props = new EnvironmentProperties();
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        service = new EnvironmentContextService(frameRepository, props, promptTemplateService,
                aiChatGateway, usageLogService);
        CurrentUserProvider c = mock(CurrentUserProvider.class);
        when(c.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(service, "currentUserProvider", c);
        when(promptTemplateService.render(any(), any(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(any(), any(), anyString(), any())).thenReturn("system");
    }

    /** 无 active 帧（天气/温度保持空 → 季基准+修正）。 */
    private void noFrame() {
        when(frameRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of());
    }

    /** 构造 active 帧（主区域天气）。 */
    private void frameWeather(String weatherJson) {
        ActorWorldFrame f = new ActorWorldFrame();
        f.setId(10L);
        f.setProjectId(1L);
        f.setGameDay(10L);
        f.setStatus("active");
        f.setWeatherJson(weatherJson);
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));
    }

    // ==================== 昼夜模型（用户示例：冬 18:00 全黑 vs 夏 18:00 天还亮） ====================

    @Test
    void computeContext_winter18hNight() {
        // 冬（11 月）18:00：gameSecond = 10*30*86400 + 18*3600
        Long second = 10L * 30L * 86400L + 18L * 3600L;
        frameWeather("{\"主区域\":{\"condition\":\"小雪\",\"temperature\":-5,\"wind\":\"北风\"}}");

        EnvironmentContextVO vo = service.computeContext(1L, second);

        assertEquals(SeasonService.WINTER, vo.getSeason());
        assertEquals("冬", vo.getSeasonName());
        assertEquals(11, vo.getMonth());
        assertEquals(18, vo.getHourOfDay());
        assertEquals("night", vo.getPhase(), "冬 18:00 日落 17:30 后 → 夜（用户示例：已全黑）");
        assertEquals("夜晚", vo.getPhaseName());
        assertEquals("小雪", vo.getWeather());
        assertEquals(-5.0, vo.getTempC(), "帧温优先");
        assertEquals(-8.0, vo.getFeelsLikeC(), "体感 = 气温 −5 + 夜 −3");
        assertTrue(vo.getDesc().contains("冬"), "环境文本应带季节: " + vo.getDesc());
        assertTrue(vo.getSensory().contains("天色全黑"), "夜感官: " + vo.getSensory());
        assertTrue(vo.getSensory().contains("下着雪"), "雪感官: " + vo.getSensory());
        assertEquals("template", vo.getSource());
    }

    @Test
    void computeContext_summer18hDay() {
        // 夏（6 月）18:00：gameSecond = 5*30*86400 + 18*3600
        Long second = 5L * 30L * 86400L + 18L * 3600L;
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":31,\"wind\":\"南风\"}}");

        EnvironmentContextVO vo = service.computeContext(1L, second);

        assertEquals(SeasonService.SUMMER, vo.getSeason());
        assertEquals("day", vo.getPhase(), "夏 18:00 日落 20:00 前 → 昼（用户示例：天还亮）");
        assertEquals(31.0, vo.getTempC());
        assertEquals(31.0, vo.getFeelsLikeC(), "昼时段体感修正 0");
        assertTrue(vo.getDesc().contains("夏"), "环境文本应带季节: " + vo.getDesc());
        assertTrue(vo.getDesc().contains("晚霞") || vo.getDesc().contains("日头"), "夏日昼文本: " + vo.getDesc());
    }

    @Test
    void phaseOf_boundaries() {
        assertEquals("night", EnvironmentContextService.phaseOf(0, 360, 1110));
        assertEquals("dawn", EnvironmentContextService.phaseOf(330, 360, 1110), "日出前 30 分=晨");
        assertEquals("dawn", EnvironmentContextService.phaseOf(419, 360, 1110), "日出后 60 分内=晨");
        assertEquals("day", EnvironmentContextService.phaseOf(420, 360, 1110), "日出后 60 分起=昼");
        assertEquals("day", EnvironmentContextService.phaseOf(1000, 360, 1110));
        assertEquals("dusk", EnvironmentContextService.phaseOf(1050, 360, 1110), "日落前 60 分=昏");
        assertEquals("night", EnvironmentContextService.phaseOf(1110, 360, 1110), "日落起=夜");
    }

    // ==================== 体感气温 ====================

    @Test
    void computeContext_noFrameTempFallsBackToSeasonBase() {
        // 无帧温度：夏 mid=30 + 晴+2 = 32（昼 0 修正）
        Long second = 5L * 30L * 86400L + 12L * 3600L;
        frameWeather("{\"主区域\":{\"condition\":\"晴\"}}");

        EnvironmentContextVO vo = service.computeContext(1L, second);

        assertEquals(32.0, vo.getTempC(), "无帧温 → 季基准 mid=30 + 晴 +2");
        assertEquals(32.0, vo.getFeelsLikeC());
    }

    @Test
    void computeContext_winterNightFeelsLikeCorrection() {
        // 冬夜无帧温：mid=0.5 + 雪 −8 = −7.5 → 体感 −7.5 −3 = −10.5
        Long second = 10L * 30L * 86400L + 23L * 3600L;
        frameWeather("{\"主区域\":{\"condition\":\"雪\"}}");

        EnvironmentContextVO vo = service.computeContext(1L, second);

        assertEquals(-7.5, vo.getTempC());
        assertEquals(-10.5, vo.getFeelsLikeC(), "夜 −3 修正");
        assertTrue(vo.getSensory().contains("有些冷") || vo.getSensory().contains("寒冷刺骨"), "冷感官");
    }

    // ==================== 环境文本模板 ====================

    @Test
    void templateDesc_winterDuskSnow() {
        String desc = EnvironmentContextService.templateDesc(SeasonService.WINTER, "dusk", "小雪", "北风", -9.0);
        assertTrue(desc.contains("冬"), desc);
        assertTrue(desc.contains("全黑"), "冬昏=天已全黑: " + desc);
        assertTrue(desc.contains("飘着雪"), "雪补句: " + desc);
    }

    @Test
    void templateDesc_summerDayHot() {
        String desc = EnvironmentContextService.templateDesc(SeasonService.SUMMER, "day", "晴", null, 35.0);
        assertTrue(desc.contains("热浪") || desc.contains("酷热"), "夏昼热文本: " + desc);
    }

    @Test
    void sensoryOf_tags() {
        assertTrue(EnvironmentContextService.sensoryOf("night", "雪", "北风", -12.0).contains("寒冷刺骨"));
        assertTrue(EnvironmentContextService.sensoryOf("dusk", "雨", null, 20.0).contains("天快黑了"));
        assertTrue(EnvironmentContextService.sensoryOf("day", "晴", null, null).contains("天气平稳"),
                "无任何标签时兜底「天气平稳」");
        assertTrue(EnvironmentContextService.sensoryOf("day", "晴", null, 22.0).contains("暖和"));
    }

    // ==================== 注入段 ====================

    @Test
    void injectionSegment_formats() {
        Long second = 10L * 30L * 86400L + 18L * 3600L;
        frameWeather("{\"主区域\":{\"condition\":\"小雪\",\"temperature\":-5,\"wind\":\"北风\"}}");

        String seg = service.injectionSegment(1L, second, 200);

        assertTrue(seg.startsWith("【当前环境】"), seg);
        assertTrue(seg.contains("冬日"), "含季节日时段: " + seg);
        assertTrue(seg.contains("体感"), "含体感: " + seg);
        assertTrue(seg.contains("日落"), "含日落: " + seg);
    }

    @Test
    void injectionSegment_noFrameStillReturns() {
        noFrame();
        String seg = service.injectionSegment(1L, 0L, 200);
        assertTrue(seg.startsWith("【当前环境】"), "无帧也应返回（季基准兜底）: " + seg);
    }

    // ==================== AI 润色（异步预生成 + 缓存 + 预算） ====================

    @Test
    void warmupPolish_cacheHitThenSourceAiCached() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        AiChatResult result = mock(AiChatResult.class);
        when(result.content()).thenReturn("春日暖阳照在青石板路上，柳枝轻摇，十分惬意。");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);

        // 预生成（4 时段逐个尝试，同步调用 warmupPolish 便于断言）
        service.warmupPolish(1L, 86400L);

        verify(aiChatGateway, times(4)).chatCompletion(any(), any(), any(), any());
        // 缓存命中 → 注入读接口 source=ai-cached
        EnvironmentContextVO vo = service.computeContext(1L, 86400L);
        assertEquals("ai-cached", vo.getSource());
        assertNotNull(vo.getDesc());
        assertEquals("春日暖阳照在青石板路上，柳枝轻摇，十分惬意。", vo.getDesc());
    }

    @Test
    void warmupPolish_aiFailureFallsBackTemplate() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));

        service.warmupPolish(1L, 86400L);

        EnvironmentContextVO vo = service.computeContext(1L, 86400L);
        assertEquals("template", vo.getSource(), "AI 失败回退确定性模板");
        assertTrue(vo.getDesc().contains("春"), vo.getDesc());
    }

    @Test
    void warmupPolish_dailyBudgetExhaustedStops() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        props.setPolishDailyCap(2);
        AiChatResult result = mock(AiChatResult.class);
        when(result.content()).thenReturn("描写文本");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);

        service.warmupPolish(1L, 86400L);
        // 预算 2 → 只调用 2 次（晨/昼），余下时段跳过
        verify(aiChatGateway, times(2)).chatCompletion(any(), any(), any(), any());
        // 换一天再润色 → 预算重置
        service.warmupPolish(1L, 2L * 86400L);
        verify(aiChatGateway, times(4)).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void warmupPolish_disabledNoAiCall() {
        props.setAiPolish(false);
        service.warmupAsync(1L, 86400L);
        service.warmupPolish(1L, 86400L);
        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
    }

    /** 批6 收口：environment_desc 属「世界视角」——AI 润色请求前置 段2D【当前世界动态】。 */
    @Test
    void warmupPolish_aiRequestPrependsWorldDynamics() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        AiChatResult result = mock(AiChatResult.class);
        when(result.content()).thenReturn("春日暖阳照在青石板路上。");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年04月01日 · 春 · 天气晴");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        service.warmupPolish(1L, 86400L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway, atLeastOnce()).chatCompletion(any(), any(), any(), cap.capture());
        // 晨/昼/昏/夜 四时段各一次润色 → 每段请求都必须前置 段1A+段2D
        for (AiChatRequest req : cap.getAllValues()) {
            String userMsg = req.messages().get(1).content();
            assertTrue(userMsg.startsWith("【世界观】"), "environment_desc 必须前置 段1A【世界观】: " + userMsg);
            assertTrue(userMsg.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + userMsg);
        }
    }

    @Test
    void computeContext_sunriseSunsetFilled() {
        Long second = 10L * 30L * 86400L + 12L * 3600L;
        noFrame();
        EnvironmentContextVO vo = service.computeContext(1L, second);
        assertNotNull(vo.getSunrise(), "应有日出");
        assertNotNull(vo.getSunset(), "应有日落");
        assertEquals("11", String.valueOf(vo.getMonth()));
        assertNotNull(vo.getColorTone());
    }

    @Test
    void computeContext_gameSecondNullUsesEnginePosition() {
        WorldClockService clock = mock(WorldClockService.class);
        when(clock.currentEngineGameSecond(1L)).thenReturn(5L * 30L * 86400L + 12L * 3600L);
        ReflectionTestUtils.setField(service, "worldClockService", clock);
        noFrame();

        EnvironmentContextVO vo = service.computeContext(1L, null);
        assertEquals(SeasonService.SUMMER, vo.getSeason(), "null=引擎推进位置");
        assertEquals(6, vo.getMonth());
    }

    // ==================== M5.5 微层接线：AI 强度档位（环境润色） ====================

    /** 构造 AI 强度闸门（mock 引擎行，档位可配）并注入服务。 */
    private AiIntensityGate intensityGate(String level) {
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository repo =
                mock(com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository.class);
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                new com.holzyn.actor.domain.agent.runtime.ActorWorldEngine();
        engine.setProjectId(1L);
        engine.setAiIntensity(level);
        when(repo.findByProjectId(1L)).thenReturn(java.util.Optional.of(engine));
        AiIntensityGate gate = new AiIntensityGate(repo);
        ReflectionTestUtils.setField(service, "aiIntensityGate", gate);
        return gate;
    }

    @Test
    void warmupPolish_programmaticSkipsAiKeepsTemplate() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        intensityGate("programmatic");

        service.warmupAsync(1L, 86400L);
        service.warmupPolish(1L, 86400L);

        verify(aiChatGateway, never()).chatCompletion(any(), any(), any(), any());
        EnvironmentContextVO vo = service.computeContext(1L, 86400L);
        assertEquals("template", vo.getSource(), "programmatic 档零 AI：恒回退确定性模板");
    }

    @Test
    void warmupPolish_lightHalvesDailyCap() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        props.setPolishDailyCap(4); // light → ceil(4×0.5)=2
        intensityGate("light");
        AiChatResult result = mock(AiChatResult.class);
        when(result.content()).thenReturn("轻 AI 档润色文本");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);

        service.warmupPolish(1L, 86400L);

        verify(aiChatGateway, times(2)).chatCompletion(any(), any(), any(), any());
    }

    @Test
    void warmupPolish_maxNoBudgetLimit() {
        frameWeather("{\"主区域\":{\"condition\":\"晴\",\"temperature\":20,\"wind\":\"东风\"}}");
        props.setPolishDailyCap(2); // max 无预算限制 → 4 时段全润色
        intensityGate("max");
        AiChatResult result = mock(AiChatResult.class);
        when(result.content()).thenReturn("Max 档润色文本");
        when(aiChatGateway.chatCompletion(any(), any(), any(), any())).thenReturn(result);

        service.warmupPolish(1L, 86400L);

        verify(aiChatGateway, times(4)).chatCompletion(any(), any(), any(), any());
    }
}
