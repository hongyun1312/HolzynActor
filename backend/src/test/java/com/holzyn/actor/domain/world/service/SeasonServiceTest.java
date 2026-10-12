package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldClimateConfig;
import com.holzyn.actor.domain.world.repository.ActorWorldClimateConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 季节服务单元测试（时间季节系统 M1，domain/world/service，同包）。
 * <p>职责：验证——游戏月→季节固定绑定边界（1/3/4/6/7/9/10/12 月）、游戏秒→月/季解析、
 * 日出日落季节查表（春 18:30 落/夏 20:00 落/冬 17:30 落）+ 每日确定性波动、天气倾向机制选样
 * 归一化、气温修正口径（晴+2/雨-3/雪-8）、雪切换气温（冬=季基准-8/非冬=5℃）、
 * 项目骨架配置覆盖（custom 覆盖 baseTempC）与默认回退。</p>
 * <p>设计依据：《HolzynActor_记忆系统重构与世界时间季节系统_设计方案_V1.0》§5.1。</p>
 */
class SeasonServiceTest {

    // ==================== 月 → 季节固定绑定 ====================

    @Test
    void seasonOfMonth_fixedBindingBoundaries() {
        assertEquals(SeasonService.SPRING, SeasonService.seasonOfMonth(1), "1 月=春");
        assertEquals(SeasonService.SPRING, SeasonService.seasonOfMonth(3), "3 月=春");
        assertEquals(SeasonService.SUMMER, SeasonService.seasonOfMonth(4), "4 月=夏");
        assertEquals(SeasonService.SUMMER, SeasonService.seasonOfMonth(6), "6 月=夏");
        assertEquals(SeasonService.AUTUMN, SeasonService.seasonOfMonth(7), "7 月=秋");
        assertEquals(SeasonService.AUTUMN, SeasonService.seasonOfMonth(9), "9 月=秋");
        assertEquals(SeasonService.WINTER, SeasonService.seasonOfMonth(10), "10 月=冬");
        assertEquals(SeasonService.WINTER, SeasonService.seasonOfMonth(12), "12 月=冬");
        assertEquals(SeasonService.AUTUMN, SeasonService.seasonOfMonth(0), "非法月兜底秋");
    }

    // ==================== 游戏秒 → 月/季 ====================

    @Test
    void monthOf_gameSecondParsing() {
        assertEquals(1, SeasonService.monthOf(0L), "纪元起点=1 月");
        assertEquals(1, SeasonService.monthOf(29L * 86400L), "第 30 日前=1 月");
        assertEquals(2, SeasonService.monthOf(30L * 86400L), "第 31 日=2 月");
        assertEquals(6, SeasonService.monthOf(5L * 30L * 86400L + 86400L), "第 6 月首日=6 月");
        assertEquals(12, SeasonService.monthOf(11L * 30L * 86400L), "第 12 月首日=12 月");
        assertEquals(1, SeasonService.monthOf(12L * 30L * 86400L), "跨年后回到 1 月");
        assertEquals(1, SeasonService.monthOf(-100L), "负值按 0");
    }

    @Test
    void seasonOf_gameSecondFixedBinding() {
        assertEquals(SeasonService.SPRING, SeasonService.seasonOf(0L), "1 月=春");
        assertEquals(SeasonService.SUMMER, SeasonService.seasonOf(3L * 30L * 86400L), "4 月=夏");
        assertEquals(SeasonService.AUTUMN, SeasonService.seasonOf(6L * 30L * 86400L), "7 月=秋");
        assertEquals(SeasonService.WINTER, SeasonService.seasonOf(9L * 30L * 86400L), "10 月=冬");
    }

    // ==================== 日出日落查表 + 每日波动 ====================

    @Test
    void sunriseSunset_seasonalTable() {
        SeasonService.SeasonSkeleton sk = SeasonService.defaultSkeleton().get(SeasonService.SUMMER);
        // 夏基准日出 05:00 日落 20:00 + 每日波动（同一天确定性一致）
        int sunrise = SeasonService.sunriseMinuteOf(sk, SeasonService.SUMMER, 10L);
        int sunset = SeasonService.sunsetMinuteOf(sk, SeasonService.SUMMER, 10L);
        assertTrue(sunset > 19 * 60, "夏日落应晚于 19:00（用户示例：夏 18:00 天还亮）: " + sunset);
        assertTrue(sunrise <= 5 * 60 + 15, "夏日出应 ≤ 05:00+波动15分: " + sunrise);
        assertEquals(sunrise, SeasonService.sunriseMinuteOf(sk, SeasonService.SUMMER, 10L), "同日波动确定性一致");
    }

    @Test
    void sunriseSunset_winterEarlyDusk() {
        SeasonService.SeasonSkeleton sk = SeasonService.defaultSkeleton().get(SeasonService.WINTER);
        int sunset = SeasonService.sunsetMinuteOf(sk, SeasonService.WINTER, 3L);
        assertTrue(sunset <= 17 * 60 + 45, "冬日落应 ≤ 17:45（用户示例：冬 18:00 已全黑）: " + sunset);
    }

    @Test
    void dailyWave_deterministicAndBounded() {
        SeasonService.SeasonSkeleton sk = SeasonService.defaultSkeleton().get(SeasonService.SPRING);
        for (long d = 0; d < 100; d++) {
            int s = SeasonService.sunriseMinuteOf(sk, SeasonService.SPRING, d);
            // 基准 06:00 ±15 波动
            assertTrue(s >= 5 * 60 + 45 && s <= 6 * 60 + 15, "波动应限制在 ±15 分钟: " + s);
        }
        assertNotEquals(SeasonService.sunriseMinuteOf(sk, SeasonService.SPRING, 0L),
                SeasonService.sunriseMinuteOf(sk, SeasonService.SPRING, 1L), "不同日波动应可能不同");
    }

    @Test
    void minuteToText_formats() {
        assertEquals("06:00", SeasonService.minuteToText(360));
        assertEquals("17:30", SeasonService.minuteToText(17 * 60 + 30));
        assertEquals("00:00", SeasonService.minuteToText(0));
    }

    // ==================== 气温修正口径 ====================

    @Test
    void conditionTempOffset_weatherCorrections() {
        assertEquals(2, SeasonService.conditionTempOffset("晴"));
        assertEquals(-3, SeasonService.conditionTempOffset("雨"));
        assertEquals(-3, SeasonService.conditionTempOffset("小雨"));
        assertEquals(-5, SeasonService.conditionTempOffset("雷雨"));
        assertEquals(-8, SeasonService.conditionTempOffset("雪"));
        assertEquals(-8, SeasonService.conditionTempOffset("小雪"));
        assertEquals(-1, SeasonService.conditionTempOffset("阴"));
        assertEquals(1, SeasonService.conditionTempOffset("微风"));
        assertEquals(0, SeasonService.conditionTempOffset("未知天气"));
        assertEquals(0, SeasonService.conditionTempOffset(null));
    }

    @Test
    void snowSwitchTemp_seasonal() {
        // 冬 mid=0.5 → 雪 = −7.5（正常冬季雪）
        assertEquals(-7.5, SeasonService.snowSwitchTemp(SeasonService.WINTER, 0.5));
        // 夏 mid=30 → 雪 = 5（反季节固定 5℃）
        assertEquals(5.0, SeasonService.snowSwitchTemp(SeasonService.SUMMER, 30));
        assertEquals(5.0, SeasonService.snowSwitchTemp(SeasonService.SPRING, 17));
    }

    // ==================== 机制帧天气选样 ====================

    @Test
    void pickMechanismWeather_seasonalTendency() {
        SeasonService svc = new SeasonService();
        // 春倾向 {晴40,雨35,阴20,风5}；游戏日取模加权抽样（无配置行 → 内置默认）
        String w0 = svc.pickMechanismWeather(1L, SeasonService.SPRING, 0L);
        assertTrue(isFrameCondition(w0), "应产出帧天气条件: " + w0);
        // 夏含雷雨倾向；冬含雪倾向
        String winter = svc.pickMechanismWeather(1L, SeasonService.WINTER, 100L);
        assertTrue(winter.equals("小雪") || winter.equals("晴") || winter.equals("阴") || winter.equals("微风"),
                "冬机制帧天气应来自冬倾向: " + winter);
        // 同一游戏日选择确定性一致
        assertEquals(svc.pickMechanismWeather(1L, SeasonService.SPRING, 5L),
                svc.pickMechanismWeather(1L, SeasonService.SPRING, 5L), "同日选择一致");
    }

    private static boolean isFrameCondition(String c) {
        return c.equals("晴") || c.equals("小雨") || c.equals("阴") || c.equals("微风") || c.equals("小雪")
                || c.equals("雷雨") || c.equals("多云");
    }

    // ==================== 项目骨架配置覆盖 ====================

    @Test
    void skeletonOf_customConfigOverridesDefault() {
        ActorWorldClimateConfigRepository repo = mock(ActorWorldClimateConfigRepository.class);
        ActorWorldClimateConfig cfg = new ActorWorldClimateConfig();
        cfg.setConfigJson("{\"spring\":{\"baseTempC\":[5,15],\"weatherTendency\":{\"晴\":50,\"雨\":50},"
                + "\"daylightOffsetMin\":10,\"colorTone\":\"浅绿\"},\"summer\":{\"baseTempC\":[28,36]},"
                + "\"autumn\":{\"baseTempC\":[10,20]},\"winter\":{\"baseTempC\":[-10,2]}}");
        when(repo.findByProjectId(9L)).thenReturn(Optional.of(cfg));
        SeasonService svc = new SeasonService();
        ReflectionTestUtils.setField(svc, "climateConfigRepository", repo);
        ReflectionTestUtils.setField(svc, "objectMapper", new ObjectMapper());

        SeasonService.SeasonSkeleton sk = svc.skeletonOf(9L, SeasonService.SPRING);
        assertEquals(5, sk.baseTempLo(), "custom baseTempLo 应生效");
        assertEquals(15, sk.baseTempHi());
        assertEquals(10, sk.daylightOffsetMin());
        assertEquals("浅绿", sk.colorTone());
        assertEquals(50, sk.weatherTendency().get("晴"));
        // 未配置季节（winter 有，summer 部分字段缺失）→ 缺字段回退默认
        SeasonService.SeasonSkeleton sum = svc.skeletonOf(9L, SeasonService.SUMMER);
        assertEquals(28, sum.baseTempLo());
        assertEquals(36, sum.baseTempHi());
        // 项目无配置行 → 默认
        SeasonService svc2 = new SeasonService();
        ReflectionTestUtils.setField(svc2, "climateConfigRepository", repo);
        ReflectionTestUtils.setField(svc2, "objectMapper", new ObjectMapper());
        when(repo.findByProjectId(10L)).thenReturn(Optional.empty());
        assertEquals(12, svc2.skeletonOf(10L, SeasonService.SPRING).baseTempLo(), "无配置行回退内置默认");
    }

    @Test
    void parseSkeleton_invalidNodeFallsBack() {
        assertTrue(SeasonService.parseSkeleton(null, SeasonService.SPRING) == null, "null 节点返回 null");
        tools.jackson.databind.ObjectMapper m = new tools.jackson.databind.ObjectMapper();
        var node = m.createObjectNode()
                .set("baseTempC", m.createArrayNode().add(4).add(9))
                .put("daylightOffsetMin", 5)
                .put("colorTone", "浅绿");
        var valid = SeasonService.parseSkeleton(node, SeasonService.SPRING);
        assertEquals(4, valid.baseTempLo());
        assertEquals(9, valid.baseTempHi());
        assertEquals(5, valid.daylightOffsetMin());
        assertEquals("浅绿", valid.colorTone());
    }

    // ==================== 季节中文名/色调 ====================

    @Test
    void displayNames() {
        assertEquals("春", SeasonService.seasonDisplayName(SeasonService.SPRING));
        assertEquals("夏", SeasonService.seasonDisplayName(SeasonService.SUMMER));
        assertEquals("秋", SeasonService.seasonDisplayName(SeasonService.AUTUMN));
        assertEquals("冬", SeasonService.seasonDisplayName(SeasonService.WINTER));
        assertEquals("未知", SeasonService.seasonDisplayName(null));
    }
}
