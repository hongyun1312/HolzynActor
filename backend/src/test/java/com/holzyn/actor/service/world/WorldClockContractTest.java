package com.holzyn.actor.service.world;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 世界时钟契约测试（P4-2 API 契约，不加载 Spring 上下文）。
 * <p>职责：验证世界时钟 VO 字段完整性与统一响应 R&lt;WorldClockVO&gt; 的契约行为。</p>
 */
class WorldClockContractTest {

    /**
     * WorldClockVO 字段完整性：关键字段可读写（前端渲染依赖）。
     */
    @Test
    void worldClockVoFields() {
        WorldClockVO vo = new WorldClockVO();
        vo.setProjectId(1L);
        vo.setRate(24);
        vo.setWorldStartAt(LocalDateTime.of(2026, 8, 14, 10, 0));
        vo.setWorldStartGameHour(0L);
        vo.setGameHour(100L);
        vo.setDay(5L);
        vo.setHourOfDay(4);
        vo.setPeriodText("深夜");
        vo.setPaused(0);
        vo.setLastGameHour(100L);
        vo.setLastSummary("推进摘要");
        assertEquals(24, vo.getRate());
        assertEquals(100L, vo.getGameHour());
        assertEquals(5L, vo.getDay());
        assertEquals("深夜", vo.getPeriodText());
        assertEquals(0, vo.getPaused());
        assertNull(vo.getLastSimTime(), "未推进时 lastSimTime 为空");
    }

    /**
     * 契约：世界时钟 API 成功响应 R 包装（code=200、error 为 null）。
     */
    @Test
    void worldClockResponseContract() {
        R<WorldClockVO> r = R.ok(new WorldClockVO());
        assertEquals(200, r.getCode());
        assertEquals("ok", r.getMessage());
        assertEquals("ok", r.getMessage());
        assertNull(r.getError());
    }
}
