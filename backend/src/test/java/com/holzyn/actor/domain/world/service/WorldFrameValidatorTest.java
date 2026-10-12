package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldFrameParse.FrameJson;
import com.holzyn.actor.domain.world.service.WorldFrameParse.InfoPackage;
import com.holzyn.actor.domain.world.service.WorldFrameParse.MacroEvent;
import com.holzyn.actor.domain.world.service.WorldFrameParse.WeatherEntry;
import com.holzyn.actor.domain.world.service.WorldFrameValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界帧边界校验器单元测试（世界演化与游戏时间系统 M4，纯静态）。
 * <p>职责：验证——规则① 禁 NPC 行为（帧文本命中项目 NPC 名称 → 校验失败；单字名/空名单跳过）；
 * 规则② 世界观越界关键词（命中 → 失败；空关键词不校验）；规则③ 归一化（severity 钳制 1~5、
 * 信息包扩散参数缺省补全[spread_speed=1.0/valid_until=当日+3/reliability=0.8 钳制 0~1]、
 * 无天气区域兜底「主区域 晴」）。</p>
 */
class WorldFrameValidatorTest {

    /** 构造带宏观事件与信息包的帧。 */
    private static FrameJson frame(String summary, InfoPackage packet) {
        MacroEvent e = new MacroEvent("E-1", "异动", "青禾村", 3, summary, 1L, 3L, List.of("青禾村"));
        return new FrameJson(1L, Map.of("青禾村", new WeatherEntry("晴", 15.0, "北风")),
                List.of(e), packet == null ? List.of() : List.of(packet),
                List.of(), null, List.of());
    }

    private static InfoPackage packet(String origin, String content, Double speed, Long validUntil, Double reliability) {
        return new InfoPackage("I-1", origin, content, reliability, List.of("商队"), speed, validUntil,
                List.of(), null);
    }

    @Test
    void validateFailsWhenNpcNameAppearsInMacroEvent() {
        FrameJson f = frame("沈砚秋主持了祠堂议事，村中决定修缮堤坝。", packet("青禾村", "堤坝将修缮", 1.0, 4L, 0.8));
        ValidationResult r = WorldFrameValidator.validate(List.of("沈砚秋", "陆沉舟"), List.of(), f);
        assertFalse(r.passed(), "宏观事件出现 NPC 名称应校验失败");
        assertTrue(r.reason().contains("沈砚秋"), "失败原因应指明命中名称");
    }

    @Test
    void validateFailsWhenNpcNameAppearsInPacketContent() {
        FrameJson f = frame("粮价平稳，集市照常。", packet("青禾村", "听说陆沉舟在茶馆与人起了争执。", 1.0, 4L, 0.8));
        ValidationResult r = WorldFrameValidator.validate(List.of("陆沉舟"), List.of(), f);
        assertFalse(r.passed(), "信息包内容出现 NPC 名称应校验失败");
    }

    @Test
    void validatePassesWhenNoNpcName() {
        FrameJson f = frame("粮价平稳，集市照常。", packet("青禾村", "临水镇粮价上涨的消息传开。", 1.0, 4L, 0.8));
        ValidationResult r = WorldFrameValidator.validate(List.of("沈砚秋", "陆沉舟"), List.of(), f);
        assertTrue(r.passed(), "无 NPC 名称应校验通过");
    }

    @Test
    void validateSkipsSingleCharNamesAndEmptyList() {
        FrameJson f = frame("阿青在田里劳作了一整天。", null);
        // 单字名「阿青」长度≥2 会被检测？——此处用单字「青」应跳过（防误命中）
        ValidationResult r1 = WorldFrameValidator.validate(List.of("青"), List.of(), f);
        assertTrue(r1.passed(), "单字名应跳过（误命中率高）");
        ValidationResult r2 = WorldFrameValidator.validate(List.of(), List.of(), f);
        assertTrue(r2.passed(), "空名单应跳过名称检测");
    }

    @Test
    void validateFailsOnBannedKeyword() {
        FrameJson f = frame("灵气潮汐异常，山神显灵了。", null);
        ValidationResult r = WorldFrameValidator.validate(List.of(), List.of("山神显灵"), f);
        assertFalse(r.passed(), "命中世界观越界关键词应校验失败");
        assertTrue(r.reason().contains("山神显灵"));
    }

    @Test
    void normalizeFillsPacketDefaultsAndClampsSeverity() {
        FrameJson f = frame("粮价上涨。", packet("青禾村", "消息", null, null, 2.5));
        FrameJson norm = WorldFrameValidator.normalize(f, 10L);
        assertNotNull(norm);
        assertEquals(1, norm.macroEvents().size());
        assertEquals(3, norm.macroEvents().get(0).severity(), "severity 3 保持不变");
        assertEquals(1, norm.infoPackages().size());
        InfoPackage p = norm.infoPackages().get(0);
        assertEquals(Double.valueOf(1.0), p.spreadSpeedDaysPerKm(), "缺失传播速度应补 1.0");
        assertEquals(Long.valueOf(13L), p.validUntilDay(), "缺失有效期应补 当日+3");
        assertEquals(Double.valueOf(1.0), p.reliability(), "越界可信度 2.5 应钳制到 1.0（0~1 区间）");
    }

    @Test
    void normalizeClampsSeverityOutOfRange() {
        MacroEvent e = new MacroEvent("E-1", "异动", "青禾村", 9, "摘要", 1L, 3L, List.of());
        FrameJson f = new FrameJson(1L, Map.of(), List.of(e), List.of(), List.of(), null, List.of());
        FrameJson norm = WorldFrameValidator.normalize(f, 1L);
        assertEquals(5, norm.macroEvents().get(0).severity(), "severity 9 应钳制到 5");
        // 无天气区域 → 兜底「主区域 晴」
        assertEquals(1, norm.weather().size());
        assertEquals("晴", norm.weather().get("主区域").condition());
    }

    @Test
    void normalizeNullFrameReturnsEmpty() {
        FrameJson norm = WorldFrameValidator.normalize(null, 7L);
        assertNotNull(norm);
        assertTrue(norm.macroEvents().isEmpty());
        assertTrue(norm.weather().isEmpty());
    }
}
