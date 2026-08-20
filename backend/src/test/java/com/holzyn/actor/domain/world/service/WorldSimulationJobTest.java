package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.service.WorldSimulationJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldSimulationJob 纯逻辑单元测试（P4-2 世界模拟）。
 * <p>职责：验证程序化角色状态描述（深夜歇息/白天按位置忙日常）。</p>
 */
class WorldSimulationJobTest {

    /**
     * 程序化行动描述：深夜歇息就寝；白天按位置忙于日常；无位置回退驻地。
     */
    @Test
    void programmaticActivityByPeriod() {
        assertTrue(WorldSimulationJob.programmaticActivity("城东市场", 2).contains("歇息就寝"),
                "深夜应歇息就寝");
        assertTrue(WorldSimulationJob.programmaticActivity("城东市场", 10).contains("城东市场"),
                "白天应引用位置");
        assertTrue(WorldSimulationJob.programmaticActivity(null, 14).contains("驻地"),
                "无位置回退驻地");
        assertTrue(WorldSimulationJob.programmaticActivity(null, 23).contains("歇息就寝"),
                "深夜无位置也应歇息");
    }
}
