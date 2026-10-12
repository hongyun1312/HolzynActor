package com.holzyn.actor.domain.world.service;

import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.EnvironmentStimulusService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.sandbox.vo.WeatherVO;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 沙盘天气切换服务单元测试（世界沙盘 S4，domain/world/service，同包）。
 * <p>职责：验证——条件校验（非法/缺省 400）、无 active 帧 404、就地更新 weather_json
 * （晴→雨保留温度/风、无主区域取首个区域、非法 JSON 重建）、同天气幂等零副作用、
 * 环境刺激挂钩触发、世界事件发布（kind=world/importance=2/source=sandbox）、
 * SSE weather 推送、总开关关闭 400。</p>
 * <p>设计依据：《HolzynActor_世界沙盘_主开发方案_V1.0》§7.4 干预（天气切换，S4 用户决策）。</p>
 */
class WeatherSwitchServiceTest {

    private ActorWorldFrameRepository frameRepository;
    private WorldEventBus worldEventBus;
    private CurrentUserProvider currentUserProvider;
    private WorldClockService worldClockService;
    private EnvironmentStimulusService environmentStimulusService;
    private WorldSandboxSseHub sandboxSseHub;
    private WeatherSwitchService service;

    @BeforeEach
    void setUp() {
        frameRepository = mock(ActorWorldFrameRepository.class);
        worldEventBus = mock(WorldEventBus.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        worldClockService = mock(WorldClockService.class);
        environmentStimulusService = mock(EnvironmentStimulusService.class);
        sandboxSseHub = mock(WorldSandboxSseHub.class);
        service = new WeatherSwitchService(frameRepository, new ObjectMapper(),
                worldEventBus, currentUserProvider, worldClockService);
        ReflectionTestUtils.setField(service, "environmentStimulusService", environmentStimulusService);
        ReflectionTestUtils.setField(service, "sandboxSseHub", sandboxSseHub);
        ReflectionTestUtils.setField(service, "weatherSwitchEnabled", true);
        // 时间季节系统 M1：开启季节修正（默认 true；关闭=退化为 S4 原行为）
        ReflectionTestUtils.setField(service, "climateEnabled", true);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(86400L);
    }

    /** 构造 active 帧（weather_json 参数化） */
    private ActorWorldFrame frame(String weatherJson) {
        ActorWorldFrame f = new ActorWorldFrame();
        f.setId(10L);
        f.setProjectId(1L);
        f.setGameDay(5L);
        f.setStatus("active");
        f.setWeatherJson(weatherJson);
        return f;
    }

    @Test
    void switchWeather_晴到雨_就地更新并联动刺激事件SSE() {
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"晴\", \"temperature\": 15, \"wind\": \"北风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雨");

        assertEquals("雨", vo.getCondition());
        assertEquals("主区域", vo.getRegion());
        assertEquals(15.0, vo.getTemperature());
        assertEquals("北风", vo.getWind());
        assertEquals(86400L, vo.getSwitchedAtGameSecond());
        // 帧 weather_json 就地更新（温度/风保留）
        verify(frameRepository).save(f);
        assertEquals("{\"主区域\":{\"condition\":\"雨\",\"temperature\":15,\"wind\":\"北风\"}}",
                f.getWeatherJson());
        assertEquals(1, f.getEmergency());
        // 环境刺激挂钩（帧变化 diff）
        verify(environmentStimulusService, times(1))
                .onFrameChanged(eq(1L), any(), any(), eq(86400L));
        // 世界事件（kind=world / importance=2 / source=sandbox）
        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus).publish(eq(1L), eq(1L), spec.capture());
        assertEquals("world", spec.getValue().getKind());
        assertEquals("sandbox", spec.getValue().getSource());
        assertEquals(2, spec.getValue().getImportance());
        assertEquals(24L, spec.getValue().getGameHour());
        assertEquals("天气变化：雨", spec.getValue().getTitle());
        // SSE weather 推送
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(sandboxSseHub).publish(eq(1L), eq(WorldSandboxSseHub.EVENT_WEATHER), payload.capture());
        assertEquals("雨", payload.getValue().get("condition"));
        assertEquals("主区域", payload.getValue().get("region"));
        assertEquals("天气已切换为「雨」", vo.getMessage());
    }

    @Test
    void switchWeather_非法条件_400() {
        when(frameRepository.findByProjectIdAndStatus(1L, "active"))
                .thenReturn(List.of(frame("{\"主区域\": {\"condition\": \"晴\"}}")));
        BizException ex = assertThrows(BizException.class, () -> service.switchWeather(1L, "雷暴"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void switchWeather_条件为空_400() {
        BizException ex = assertThrows(BizException.class, () -> service.switchWeather(1L, "  "));
        assertEquals(400, ex.getCode());
    }

    @Test
    void switchWeather_无active帧_404() {
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of());
        BizException ex = assertThrows(BizException.class, () -> service.switchWeather(1L, "雨"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void switchWeather_同天气幂等_零副作用() {
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"雨\", \"temperature\": 15, \"wind\": \"北风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雨");

        assertEquals("天气未变化（已是「雨」）", vo.getMessage());
        verify(frameRepository, never()).save(any());
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any());
        verify(environmentStimulusService, never()).onFrameChanged(anyLong(), any(), any(), anyLong());
        verify(sandboxSseHub, never()).publish(anyLong(), anyString(), any());
    }

    @Test
    void switchWeather_无主区域_取首个区域() {
        ActorWorldFrame f = frame("{\"青禾村\": {\"condition\": \"晴\", \"temperature\": 12, \"wind\": \"东风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雪");

        assertEquals("青禾村", vo.getRegion());
        assertEquals("雪", vo.getCondition());
        // 时间季节系统 M1：春（1 月）切雪 → 反季节，温度按季节修正为固定 5℃（骤降温）
        assertEquals(5.0, vo.getTemperature());
        assertEquals("东风", vo.getWind());
        assertEquals("{\"青禾村\":{\"condition\":\"雪\",\"temperature\":5.0,\"wind\":\"东风\"}}", f.getWeatherJson());
        // 反季节天气 → 额外发布「反常天气」事件（共 2 条：天气变化 + 反常飞雪）
        assertEquals(2, publishedCount());
    }

    /** 统计发布事件条数（含反常天气事件）。 */
    private int publishedCount() {
        // 重新捕获事件（每次测试独立执行，这里通过 ArgumentCaptor 聚合）
        ArgumentCaptor<EventSpec> captor = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, org.mockito.Mockito.atLeast(1)).publish(eq(1L), eq(1L), captor.capture());
        return captor.getAllValues().size();
    }

    @Test
    void switchWeather_weatherJson非法_按空重建() {
        ActorWorldFrame f = frame("not-json{{{");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "阴");

        assertEquals("主区域", vo.getRegion());
        assertEquals("阴", vo.getCondition());
        // 时间季节系统 M1：无帧温 → 按季基准+天气修正填充（春 mid=17，阴 −1 → 16）
        assertEquals(16.0, vo.getTemperature());
        assertNull(vo.getWind());
        assertNotNull(f.getWeatherJson());
        assertEquals("{\"主区域\":{\"condition\":\"阴\",\"temperature\":16.0}}", f.getWeatherJson());
    }

    @Test
    void switchWeather_夏季切雪_反常天气事件() {
        // 6 月（夏）：gameSecond = 5*30*86400 + 86400（第 6 游戏月首日）
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(5L * 30L * 86400L + 86400L);
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"晴\", \"temperature\": 30, \"wind\": \"南风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雪");

        assertEquals("雪", vo.getCondition());
        assertEquals("summer", vo.getSeason(), "夏季切雪：季节=夏");
        assertEquals(5.0, vo.getTemperature(), "反季节飞雪 → 固定 5℃ 骤降温");
        // 2 条事件：天气变化 + 反常天气（反常事件含「反常天气」标题与灵脉异象文案）
        ArgumentCaptor<EventSpec> captor = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus, org.mockito.Mockito.times(2)).publish(eq(1L), eq(1L), captor.capture());
        List<EventSpec> all = captor.getAllValues();
        assertEquals("天气变化：雪", all.get(0).getTitle());
        assertEquals(2, all.get(0).getImportance(), "天气变化事件仅可视化（重要度 2）");
        assertTrue(all.get(1).getTitle().contains("反常天气"), "应有反常天气事件: " + all.get(1).getTitle());
        assertTrue(all.get(1).getContent().contains("异象"), "反常事件含异象文案");
        assertEquals("sandbox", all.get(1).getSource());
        // SSE weather payload 含季节字段
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(sandboxSseHub).publish(eq(1L), eq(WorldSandboxSseHub.EVENT_WEATHER), payload.capture());
        assertEquals("summer", payload.getValue().get("season"));
    }

    @Test
    void switchWeather_冬季切雪_正常天气无反常() {
        // 11 月（冬）：gameSecond = 10*30*86400 + 86400（第 11 游戏月首日）
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(10L * 30L * 86400L + 86400L);
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"晴\", \"temperature\": 2, \"wind\": \"北风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雪");

        assertEquals("雪", vo.getCondition());
        assertEquals("winter", vo.getSeason(), "冬季切雪：季节=冬");
        // 冬基准 mid = (−5+6)/2 = 0.5 → 雪 = mid−8 = −7.5
        assertEquals(-7.5, vo.getTemperature());
        assertEquals(1, publishedCount(), "冬季雪为正常天气，仅 1 条天气变化事件（无反常）");
    }

    @Test
    void switchWeather_非雪无帧温_按季基准填充() {
        // 春（1 月）切晴、帧无温度 → mid 17 + 晴 2 = 19
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"阴\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "晴");

        assertEquals("晴", vo.getCondition());
        assertEquals(19.0, vo.getTemperature(), "无帧温：季基准 mid=17 + 晴修正 +2");
        assertNull(vo.getWind());
    }

    @Test
    void switchWeather_季节字段_随切换响应返回() {
        ActorWorldFrame f = frame("{\"主区域\": {\"condition\": \"晴\", \"temperature\": 15, \"wind\": \"北风\"}}");
        when(frameRepository.findByProjectIdAndStatus(1L, "active")).thenReturn(List.of(f));

        WeatherVO vo = service.switchWeather(1L, "雨");

        // gameSecond=86400 → 第 1 游戏日 → 游戏月 1 → 春；18:00 前后（86400/3600=24 → 当日 0 时=夜晚）
        assertEquals("spring", vo.getSeason());
        assertEquals("春", vo.getSeasonName());
        assertEquals(1, vo.getMonth());
        assertEquals("night", vo.getPhase(), "gameSecond=86400 为当日 0 时，应属夜晚");
        assertNotNull(vo.getSunrise());
        assertNotNull(vo.getSunset());
        // 体感 = 气温 15 + 夜 −3 = 12
        assertEquals(12.0, vo.getFeelsLikeC());
    }

    @Test
    void switchWeather_总开关关闭_400() {
        ReflectionTestUtils.setField(service, "weatherSwitchEnabled", false);
        when(frameRepository.findByProjectIdAndStatus(1L, "active"))
                .thenReturn(List.of(frame("{\"主区域\": {\"condition\": \"晴\"}}")));
        BizException ex = assertThrows(BizException.class, () -> service.switchWeather(1L, "雨"));
        assertEquals(400, ex.getCode());
        verify(frameRepository, never()).save(any());
    }
}

