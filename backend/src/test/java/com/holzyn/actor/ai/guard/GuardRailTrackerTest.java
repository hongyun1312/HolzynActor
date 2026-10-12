package com.holzyn.actor.ai.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 护栏运行时状态单元测试（O3 评估与工程 P2-4，2026-09-21）。
 * <p>职责：验证——①统计计数各字段独立累计；②告警环形缓冲上限与顺序（新→旧）；
 * ③运行时覆盖表增删；④重复检测窗口滚动语义（满窗判定/跨键隔离/截断）。</p>
 */
class GuardRailTrackerTest {

    @Test
    void countersAccumulateIndependently() {
        GuardRailTracker tracker = new GuardRailTracker();
        tracker.recordBlankOutput("action");
        tracker.recordBlankOutput("action");
        tracker.recordDuplicate("action");
        tracker.recordFailure("action");
        tracker.recordTimeout("action");
        tracker.recordDegraded("action");
        tracker.recordRetry("action");
        GuardRailTracker.Counter c = tracker.statsOf("action");
        assertEquals(1, c.retries());
        assertEquals(2, c.blankOutputs());
        assertEquals(1, c.duplicates());
        // failures=2：recordFailure 1 次 + recordTimeout 1 次（超时是失败的子集，双计）
        assertEquals(2, c.failures());
        assertEquals(1, c.timeouts());
        assertEquals(1, c.degraded());
        // 其他场景零计数
        assertEquals(0, tracker.statsOf("dialog").failures());
        // 空场景归 chat
        tracker.recordFailure(null);
        assertEquals(1, tracker.statsOf("chat").failures());
    }

    @Test
    void alertRingBufferCapsAtCapacityAndOrdersNewestFirst() {
        GuardRailTracker tracker = new GuardRailTracker();
        for (int i = 0; i < GuardRailTracker.ALERT_CAPACITY + 20; i++) {
            tracker.alert("action", "duplicate", "告警-" + i);
        }
        List<GuardRailTracker.Alert> alerts = tracker.recentAlerts();
        assertEquals(GuardRailTracker.ALERT_CAPACITY, alerts.size());
        // 新→旧：第一条是最新（告警-219）
        assertEquals("告警-" + (GuardRailTracker.ALERT_CAPACITY + 19), alerts.get(0).message());
        // 最旧被淘汰：告警-0 不在
        assertFalse(alerts.stream().anyMatch(a -> a.message().equals("告警-0")));
    }

    @Test
    void alertMessageTruncatedTo500Chars() {
        GuardRailTracker tracker = new GuardRailTracker();
        tracker.alert("action", "duplicate", "长".repeat(1000));
        assertEquals(500, tracker.recentAlerts().get(0).message().length());
    }

    @Test
    void runtimeOverridesPutGetRemove() {
        GuardRailTracker tracker = new GuardRailTracker();
        GuardRailProperties.SceneGuard guard = new GuardRailProperties.SceneGuard();
        guard.setMaxRetries(5);
        tracker.putOverride("action", guard);
        assertEquals(5, tracker.overrideOf("action").getMaxRetries());
        assertTrue(tracker.overrides().containsKey("action"));
        tracker.removeOverride("action");
        assertFalse(tracker.overrides().containsKey("action"));
        // null guard=清空
        tracker.putOverride("action", guard);
        tracker.putOverride("action", null);
        assertFalse(tracker.overrides().containsKey("action"));
    }

    @Test
    void duplicateWindowRollingSemantics() {
        GuardRailTracker tracker = new GuardRailTracker();
        String text = "沈砚秋决定去青禾集市查看药材行情与铺面经营。";
        // 窗口 3：前 2 次未满不触发，第 3 次起触发
        assertFalse(tracker.checkDuplicate("action", "t1", text, 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "t1", text, 3, 0.9, 800));
        assertTrue(tracker.checkDuplicate("action", "t1", text, 3, 0.9, 800));
        // 窗口滚动：插入不同文本后不再触发
        assertFalse(tracker.checkDuplicate("action", "t1", "白灵犀在灵溪河边晾晒药材，哼着小曲。", 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "t1", "白灵犀在灵溪河边晾晒药材，哼着小曲。", 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "t1", "白灵犀在灵溪河边晾晒药材，哼着小曲。", 3, 0.9, 800));
    }

    @Test
    void duplicateWindowIsolatedAcrossKeys() {
        GuardRailTracker tracker = new GuardRailTracker();
        String text = "温叙决定在医馆整理今日药方。";
        assertFalse(tracker.checkDuplicate("action", "link-a", text, 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "link-b", text, 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "link-a", text, 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "link-b", text, 3, 0.9, 800));
        assertTrue(tracker.checkDuplicate("action", "link-a", text, 3, 0.9, 800));
        // link-b 只有 2 条 → 未触发
    }

    @Test
    void duplicateWindowRespectsThreshold() {
        GuardRailTracker tracker = new GuardRailTracker();
        // 阈值 1.0=必须逐字相同；不同文本永不触发
        assertFalse(tracker.checkDuplicate("action", "t2", "甲去集市买药。", 3, 1.0, 800));
        assertFalse(tracker.checkDuplicate("action", "t2", "乙去河边洗衣。", 3, 1.0, 800));
        assertFalse(tracker.checkDuplicate("action", "t2", "丙去祠堂上香。", 3, 1.0, 800));
        // 窗口 <=1 关闭检测
        assertFalse(tracker.checkDuplicate("action", "t3", "任意文本", 1, 0.9, 800));
    }

    @Test
    void duplicateFingerprintTruncated() {
        GuardRailTracker tracker = new GuardRailTracker();
        String longText = "长".repeat(2000);
        // 截断到 800 参与指纹，仍可触发（内容相同）
        assertFalse(tracker.checkDuplicate("action", "t4", longText, 3, 0.9, 800));
        assertFalse(tracker.checkDuplicate("action", "t4", longText, 3, 0.9, 800));
        assertTrue(tracker.checkDuplicate("action", "t4", longText, 3, 0.9, 800));
    }
}
