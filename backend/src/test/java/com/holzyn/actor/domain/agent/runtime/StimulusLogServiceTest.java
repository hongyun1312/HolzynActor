package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刺激日志服务单元测试（实时行为决策系统 M5，domain/agent/runtime，同包）。
 * <p>职责：验证——① 记录 + 最近查询（倒序/项目过滤/limit 截断）；② 护栏命中统计分组；
 * ③ 环形缓冲上限（超出丢弃最旧，防内存膨胀）。</p>
 */
class StimulusLogServiceTest {

    @Test
    void logAndRecent() {
        StimulusLogService s = new StimulusLogService();
        s.log(1L, 1000L, "environment", "special:1", "天气变化：青禾村：晴 → 暴雨", "triggered", "环境刺激即时决策");
        s.log(1L, 1300L, "environment", "special:1", "天气变化：青禾村：暴雨 → 雾", "cooldown", "冷却期内跳过");
        s.log(2L, 2000L, "chain_reaction", "special:9", "沈砚秋在集市：查看摊贩", "triggered", "链式反应");
        List<StimulusLogService.Entry> all = s.recent(null, 50);
        assertEquals(3, all.size());
        assertEquals("chain_reaction", all.get(0).getType(), "倒序：最新在前");
        List<StimulusLogService.Entry> p1 = s.recent(1L, 10);
        assertEquals(2, p1.size(), "项目过滤");
        assertTrue(p1.stream().allMatch(e -> e.getProjectId().equals(1L)));
        List<StimulusLogService.Entry> one = s.recent(1L, 1);
        assertEquals(1, one.size(), "limit 截断");
        assertEquals("environment", one.get(0).getType());
        assertTrue(one.get(0).getStimulusText().contains("暴雨 → 雾"));
    }

    @Test
    void statsGroupByResult() {
        StimulusLogService s = new StimulusLogService();
        s.log(1L, 1000L, "environment", "special:1", "a", "triggered", "");
        s.log(1L, 1300L, "environment", "special:1", "b", "cooldown", "");
        s.log(1L, 1400L, "environment", "special:2", "c", "triggered", "");
        s.log(2L, 2000L, "idle_skip", "special:3", "", "idle_skip", "");
        Map<String, Object> stats = s.stats(1L);
        assertEquals(3L, stats.get("total"));
        Map<?, ?> by = (Map<?, ?>) stats.get("byResult");
        assertEquals(2L, by.get("triggered"));
        assertEquals(1L, by.get("cooldown"));
        assertNotNull(s.stats(null).get("total"), "全部项目统计");
    }

    @Test
    void ringBufferCapsAtMax() {
        StimulusLogService s = new StimulusLogService();
        for (int i = 0; i < StimulusLogService.MAX_ENTRIES + 100; i++) {
            s.log(1L, (long) i, "environment", "special:1", "刺激" + i, "triggered", "");
        }
        List<StimulusLogService.Entry> all = s.recent(null, 1000);
        assertEquals(StimulusLogService.MAX_ENTRIES, all.size(), "环形缓冲上限生效（丢弃最旧）");
        assertEquals("刺激" + 100, all.get(all.size() - 1).getStimulusText(), "最旧条目被丢弃，保留最新 500 条");
    }
}
