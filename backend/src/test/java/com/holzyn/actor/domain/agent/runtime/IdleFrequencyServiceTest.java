package com.holzyn.actor.domain.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲降频服务单元测试（实时行为决策系统 M5，domain/agent/runtime，同包）。
 * <p>职责：验证——① 最近决策过（距上次决策 &lt; 窗口）→ 本次周期决策跳过；② 窗口过后恢复；
 * ③ 从未决策过不降频（需要决策）；④ 总开关关不降频；⑤ 窗口配置非法不降频。</p>
 * <p>设计依据：主开发方案 §7 M5「空闲降频（可选）」+ 用户决策 Q6「做：行动延续期间降频」。</p>
 */
class IdleFrequencyServiceTest {

    private RealtimeDecisionProperties props;
    private IdleFrequencyService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        service = new IdleFrequencyService();
        java.lang.reflect.Field f = IdleFrequencyService.class.getDeclaredField("props");
        f.setAccessible(true);
        f.set(service, props);
    }

    @Test
    void withinWindowSkipsPeriodic() {
        service.noteDecision(1L, "special:1", 100000L);
        assertTrue(service.shouldSkipPeriodic(1L, "special:1", 100000L + 60 * 60),
                "距上次决策 1 游戏小时 < 窗口 120 分钟 → 跳过周期决策（行动延续零 AI）");
    }

    @Test
    void afterWindowResumesPeriodic() {
        service.noteDecision(1L, "special:1", 100000L);
        assertFalse(service.shouldSkipPeriodic(1L, "special:1", 100000L + 121 * 60),
                "距上次决策 ≥ 窗口 → 恢复周期通道（行动视为完成）");
    }

    @Test
    void noRecordNeedsDecision() {
        assertFalse(service.shouldSkipPeriodic(1L, "special:1", 100000L),
                "从未决策过 → 需要决策，不降频");
    }

    @Test
    void disabledNeverSkips() {
        props.setIdleReductionEnabled(false);
        service.noteDecision(1L, "special:1", 100000L);
        assertFalse(service.shouldSkipPeriodic(1L, "special:1", 100000L + 60 * 60),
                "总开关关 → 不降频（回到 M2 每小时全量决策）");
    }

    @Test
    void invalidWindowNeverSkips() {
        props.setIdleDecisionWindowGameMinutes(0);
        service.noteDecision(1L, "special:1", 100000L);
        assertFalse(service.shouldSkipPeriodic(1L, "special:1", 100000L + 60 * 60),
                "窗口配置 <=0 → 不降频");
    }

    @Test
    void differentCharacterIndependent() {
        service.noteDecision(1L, "special:1", 100000L);
        assertFalse(service.shouldSkipPeriodic(1L, "special:2", 100000L + 60 * 60),
                "不同角色独立判定（未决策者不降频）");
    }
}
