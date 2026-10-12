package com.holzyn.actor.domain.worldgen.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WorldGenProperties 默认值（2026-09-29_05 工作流世界观生成适配轮）校验。
 * <p>锁定的不变量：maxTokens=8192（对齐 routing tool 档位，防复杂维度截断）、
 * parallelism=3（前 3 维并行）、rosterLimit=20（character 阵容上限）、既有开关默认不变。</p>
 */
class WorldGenPropertiesDefaultsTest {

    @Test
    @DisplayName("默认值：maxTokens=8192 / parallelism=3 / rosterLimit=20 / storyCharacterLimit=12 / 既有开关")
    void defaultsMatchAdaptationContract() {
        WorldGenProperties p = new WorldGenProperties();
        assertEquals(8192, p.getMaxTokens(), "maxTokens 应对齐 routing tool 档位 8192");
        assertEquals(3, p.getParallelism(), "并行度默认 3（前 3 维并行）");
        assertEquals(20, p.getRosterLimit(), "character 阵容上限默认 20");
        assertEquals(12, p.getStoryCharacterLimit(), "story-plan 角色摘要注入上限默认 12");
        assertEquals(true, p.isEnabled());
        assertEquals(true, p.isAiEnabled());
        assertEquals(2, p.getSchemaRetry());
        assertEquals(0.7, p.getTemperature(), 1e-9);
        assertEquals(true, p.isCheckpointEnabled());
    }

    @Test
    @DisplayName("自定义配置生效（env 逃生门语义）")
    void customValuesTakeEffect() {
        WorldGenProperties p = new WorldGenProperties();
        p.setMaxTokens(4096);
        p.setParallelism(0);
        p.setRosterLimit(5);
        p.setStoryCharacterLimit(4);
        assertEquals(4096, p.getMaxTokens());
        assertEquals(0, p.getParallelism());
        assertEquals(5, p.getRosterLimit());
        assertEquals(4, p.getStoryCharacterLimit());
    }
}
