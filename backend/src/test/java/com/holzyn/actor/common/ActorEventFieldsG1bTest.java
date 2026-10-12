package com.holzyn.actor.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ActorEventFields G1b 收敛测试（O6 轨道 G1b：对外契约值 crowd→common）。
 * <p>职责：验证事件总线白名单同时接受 {@code common}（新写入契约值）与 {@code crowd}（历史值），
 * 归一化不把两者错归为 event/manual——这是"居民活动"时间轴档位与沙盘渲染不静默错档的底线。</p>
 * <p>所属模块：common（测试）。</p>
 */
class ActorEventFieldsG1bTest {

    @Test
    @DisplayName("kind 归一化：common 与 crowd 都在白名单内（新值可用、历史值兼容）")
    void kindWhitelistAcceptsBoth() {
        assertEquals("common", ActorEventFields.normalizeKind("common"));
        assertEquals("crowd", ActorEventFields.normalizeKind("crowd"));
        assertEquals("common", ActorEventFields.normalizeKind("COMMON"));
    }

    @Test
    @DisplayName("source 归一化：common 与 crowd 都在白名单内")
    void sourceWhitelistAcceptsBoth() {
        assertEquals("common", ActorEventFields.normalizeSource("common"));
        assertEquals("crowd", ActorEventFields.normalizeSource("crowd"));
    }

    @Test
    @DisplayName("非法值仍回退安全缺省（白名单新增不破坏失败安全方向）")
    void illegalStillFallsBack() {
        assertEquals("event", ActorEventFields.normalizeKind("whatever"));
        assertEquals("manual", ActorEventFields.normalizeSource("whatever"));
    }
}
