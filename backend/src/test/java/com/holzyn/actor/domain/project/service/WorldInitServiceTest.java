package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WorldInitService 纯逻辑单元测试（2026-08-19 世界初始化）。
 * <p>职责：验证 AI 世界时间推断输出解析（正确取字段 / 越界夹取 / 非法 JSON 抛错）。</p>
 */
class WorldInitServiceTest {

    /** 世界时间推断解析：完整 JSON 正确取 [年,月,日,时,分,秒]。 */
    @Test
    void parseWorldTimeParsesValid() {
        String json = "{\"year\":1050,\"month\":3,\"day\":12,\"hour\":8,\"minute\":30,\"second\":15}";
        assertArrayEquals(new int[]{1050, 3, 12, 8, 30, 15}, WorldInitService.parseWorldTime(json));
    }

    /** 世界时间推断解析：字段缺失回退默认值（year=1，其余=1/1/0/0/0）。 */
    @Test
    void parseWorldTimeMissingFieldsFallback() {
        String json = "{\"year\":1200}";
        assertArrayEquals(new int[]{1200, 1, 1, 0, 0, 0}, WorldInitService.parseWorldTime(json));
    }

    /** 世界时间推断解析：越界值自动夹取（month/day/hour/min/sec 上下限）。 */
    @Test
    void parseWorldTimeClampsBounds() {
        String json = "{\"year\":0,\"month\":20,\"day\":99,\"hour\":25,\"minute\":-5,\"second\":999}";
        assertArrayEquals(new int[]{1, 12, 30, 23, 0, 59}, WorldInitService.parseWorldTime(json));
    }

    /** 世界时间推断解析：非法 JSON 抛 BizException（供外层重试/跳过）。 */
    @Test
    void parseWorldTimeInvalidJsonThrows() {
        assertThrows(BizException.class, () -> WorldInitService.parseWorldTime("不是 JSON"));
        assertThrows(BizException.class, () -> WorldInitService.parseWorldTime("```json {broken}"));
    }
}
