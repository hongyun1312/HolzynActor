package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrdinaryNpcService D2 C-19（普通 NPC 当日计划 + 事件感知）静态纯逻辑单元测试
 * （domain/crowd/service，同包）。
 * <p>职责：验证——机制化当日计划（mechanismDailyPlan：5~8 条/确定性/游戏日一致）、
 * 计划归属游戏日判定（dailyPlanForDay）、按小时取计划条目（planItemAt）、
 * 计划优先状态机（computeState(npc,hour,rng,disrupt)：事件打断→talk / 计划条目优先 /
 * 无计划回退纯时段规则）。</p>
 */
class OrdinaryNpcServiceC19Test {

    // ==================== mechanismDailyPlan ====================

    /**
     * 机制化当日计划：5~8 条、含 date_game_day、同 seed+游戏日输出稳定（确定性）。
     */
    @Test
    void mechanismDailyPlanIsDeterministicAndStructured() {
        String a = OrdinaryNpcService.mechanismDailyPlan(3, 100);
        String b = OrdinaryNpcService.mechanismDailyPlan(3, 100);
        String c = OrdinaryNpcService.mechanismDailyPlan(3, 200);
        assertNotNull(a);
        assertEquals(a, b, "同 seed+游戏日输出一致（确定性）");
        assertTrue(a.contains("\"date_game_day\":3"), "计划归属游戏日 3");
        Map<String, Object> parsed = OrdinaryNpcService.planItemAt(a, 6);
        assertNotNull(parsed, "首条锚点 6 时存在");
        assertFalse(a.equals(c), "不同 seed 有差异（个体差异）");
        // 条目数 5~8：以 6 时/8 时/… 锚点抽查条数（计划至少覆盖 5 个锚点）
        int count = 0;
        for (int h : List.of(6, 8, 11, 13, 15, 18, 20, 23)) {
            if (OrdinaryNpcService.planItemAt(a, h) != null) {
                count++;
            }
        }
        assertTrue(count >= 5 && count <= 8, "条目 5~8 条，实际锚点命中=" + count);
    }

    // ==================== dailyPlanForDay ====================

    /**
     * 计划归属游戏日判定：匹配返回 true；其他日/非法 JSON/空返回 false。
     */
    @Test
    void dailyPlanForDayMatchesGameDay() {
        String plan = OrdinaryNpcService.mechanismDailyPlan(3, 100);
        assertTrue(OrdinaryNpcService.dailyPlanForDay(plan, 3));
        assertFalse(OrdinaryNpcService.dailyPlanForDay(plan, 4), "其他游戏日不匹配");
        assertFalse(OrdinaryNpcService.dailyPlanForDay("不是 JSON", 3));
        assertFalse(OrdinaryNpcService.dailyPlanForDay("", 3));
        assertFalse(OrdinaryNpcService.dailyPlanForDay(null, 3));
    }

    // ==================== planItemAt ====================

    /**
     * 按小时取计划条目：命中返回 {state,action}；未命中/非法返回 null。
     */
    @Test
    void planItemAtReadsHourEntry() {
        String plan = "{\"date_game_day\":2,\"items\":["
                + "{\"hour\":8,\"state\":\"stop\",\"action\":\"从事上午的劳作\"},"
                + "{\"hour\":12,\"state\":\"rest\",\"action\":\"午间小憩\"}]}";
        Map<String, Object> at8 = OrdinaryNpcService.planItemAt(plan, 8);
        assertNotNull(at8);
        assertEquals("stop", at8.get("state"));
        assertEquals("从事上午的劳作", at8.get("action"));
        assertNull(OrdinaryNpcService.planItemAt(plan, 10), "未命中小时返回 null");
        assertNull(OrdinaryNpcService.planItemAt("不是 JSON", 8));
        assertNull(OrdinaryNpcService.planItemAt(null, 8));
        assertNull(OrdinaryNpcService.planItemAt("{\"items\":[]}", 8));
    }

    // ==================== computeState（计划优先） ====================

    /**
     * computeState：事件打断优先（disruptEventId 非空 → talk，即便计划有 rest）。
     * <p>O6 G1a：入参由「实体」改为<b>计划 JSON 文本</b>——计划已不在主表实体上（迁入
     * {@code actor_state.daily_plan_json}），调用方从批量 Map 取值后传入，纯静态逻辑不变。</p>
     */
    @Test
    void computeStateDisruptsOnSignificantEvent() {
        String plan = "{\"date_game_day\":2,\"items\":[{\"hour\":12,\"state\":\"rest\",\"action\":\"午间小憩\"}]}";
        assertEquals("talk", OrdinaryNpcService.computeState(plan, 12, new Random(1), 99L),
                "重大事件打断 → 该小时停下议论/应对");
    }

    /**
     * computeState：计划条目优先（计划该小时 rest → rest，而非纯时段规则 talk）。
     */
    @Test
    void computeStatePrefersPlanItem() {
        String plan = "{\"date_game_day\":2,\"items\":[{\"hour\":21,\"state\":\"rest\",\"action\":\"就寝\"}]}";
        // 21 时纯时段规则是 talk，但计划 rest → 计划优先（行为不再重复单一化，C-19）
        assertEquals("rest", OrdinaryNpcService.computeState(plan, 21, new Random(1), null));
    }

    /**
     * computeState：无计划条目回退纯时段规则（20-22 时 → talk；0-5 时 → rest）。
     */
    @Test
    void computeStateFallsBackToTimeRulesWhenNoPlanItem() {
        assertEquals("talk", OrdinaryNpcService.computeState((String) null, 21, new Random(1), null),
                "无计划回退纯时段规则（20-22→talk）");
        assertEquals("rest", OrdinaryNpcService.computeState((String) null, 2, new Random(1), null),
                "无计划回退纯时段规则（0-5→rest）");
        // 计划存在但当前小时无条目 → 回退纯时段规则
        String sparse = "{\"date_game_day\":2,\"items\":[{\"hour\":8,\"state\":\"stop\"}]}";
        assertEquals("talk", OrdinaryNpcService.computeState(sparse, 21, new Random(1), null));
        // null 计划同样安全回退
        assertEquals("rest", OrdinaryNpcService.computeState((String) null, 2, new Random(1), null));
    }
}
