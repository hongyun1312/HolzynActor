package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unity 环境适配服务单元测试（U 轨道 U1，domain/unity/service）——帧天气覆盖。
 * <p>职责：验证——环境上报就地改写 active 帧 weather_json（condition/temperature/wind）、
 * 未上报字段保留、无帧时仅记日志（no_frame）、参数无效拒绝、天气文本长度截断。</p>
 */
class UnityEnvironmentServiceTest {

    private ActorWorldFrameRepository frameRepository;
    private WorldClockService worldClockService;
    private WorldEventBus worldEventBus;
    private CurrentUserProvider currentUserProvider;
    private UnityEnvironmentService service;

    @BeforeEach
    void setUp() {
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        service = new UnityEnvironmentService(frameRepository, new ObjectMapper(),
                worldEventBus, currentUserProvider, worldClockService);
        when(worldClockService.currentEngineGameSecond(37L)).thenReturn(3600L);
    }

    private ActorWorldFrame frame(String weatherJson) {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(1L);
        frame.setProjectId(37L);
        frame.setStatus("active");
        frame.setWeatherJson(weatherJson);
        return frame;
    }

    @Test
    void invalidParams() {
        var r = service.applyEnvironmentReport(37L, null, null, null);
        assertEquals("invalid", r.status());
        var r2 = service.applyEnvironmentReport(37L, "  ", 25.0, null);
        assertEquals("invalid", r2.status());
        verify(frameRepository, never()).save(Mockito.any());
    }

    @Test
    void noFrame() {
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of());
        var r = service.applyEnvironmentReport(37L, "雷雨", 25.0, "南风2级");
        assertEquals("no_frame", r.status());
        verify(frameRepository, never()).save(Mockito.any());
    }

    @Test
    void appliesAndOverwritesFrameWeather() {
        ActorWorldFrame frame = frame("{\"主区域\":{\"condition\":\"晴\",\"temperature\":28.0,\"wind\":\"东风3级\"}}");
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of(frame));

        var r = service.applyEnvironmentReport(37L, "雷雨", 25.0, "南风2级");
        assertTrue(r.applied());
        assertEquals("雷雨", r.condition());

        verify(frameRepository).save(frame);
        String updated = frame.getWeatherJson();
        assertTrue(updated.contains("\"condition\":\"雷雨\""), "天气覆盖");
        assertTrue(updated.contains("\"temperature\":25.0"), "气温覆盖");
        assertTrue(updated.contains("\"wind\":\"南风2级\""), "风力覆盖");
        assertEquals(Integer.valueOf(1), frame.getEmergency(), "就地改写标记");
    }

    @Test
    void keepsUnreportedFields() {
        ActorWorldFrame frame = frame("{\"主区域\":{\"condition\":\"晴\",\"temperature\":28.0,\"wind\":\"东风3级\"}}");
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of(frame));

        // 只上报天气：温度/风保留
        var r = service.applyEnvironmentReport(37L, "阴", null, null);
        assertTrue(r.applied());
        String updated = frame.getWeatherJson();
        assertTrue(updated.contains("\"condition\":\"阴\""));
        assertTrue(updated.contains("\"temperature\":28.0"), "未上报温度保留帧温");
        assertTrue(updated.contains("\"wind\":\"东风3级\""), "未上报风保留帧风");
    }

    @Test
    void firstRegionFallbackAndCreate() {
        // 帧无「主区域」→ 取首个区域；空对象 → 新建「主区域」
        ActorWorldFrame frame = frame("{\"灵溪河\":{\"condition\":\"晴\"}}");
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of(frame));
        service.applyEnvironmentReport(37L, "雨", null, null);
        assertTrue(frame.getWeatherJson().contains("\"灵溪河\""), "落到首个区域");
        assertTrue(frame.getWeatherJson().contains("\"condition\":\"雨\""));

        ActorWorldFrame empty = frame("{}");
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of(empty));
        service.applyEnvironmentReport(37L, "雪", null, null);
        assertTrue(empty.getWeatherJson().contains("\"主区域\""), "空帧新建主区域");
        assertTrue(empty.getWeatherJson().contains("\"condition\":\"雪\""));
    }

    @Test
    void conditionTruncated() {
        ActorWorldFrame frame = frame("{\"主区域\":{\"condition\":\"晴\"}}");
        when(frameRepository.findByProjectIdAndStatus(37L, "active")).thenReturn(List.of(frame));
        String longCondition = "晴转多云局部地区有雷阵雨伴有短时强降水冰雹";
        service.applyEnvironmentReport(37L, longCondition, null, null);
        assertTrue(frame.getWeatherJson().contains("\"condition\":\"" + longCondition.substring(0, 20) + "\""),
                "超长天气文本截断到 20 字");
    }
}
