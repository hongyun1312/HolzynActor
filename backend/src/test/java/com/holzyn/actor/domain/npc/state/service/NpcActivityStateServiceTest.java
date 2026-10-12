package com.holzyn.actor.domain.npc.state.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 作息运行态枚举映射单元测试（治理批次 0 · 卡4-A② · G-ENGINE-ACT-02，{@code domain/npc/state/service} 同包）。
 *
 * <p>断言 {@link NpcActivityStateService#toActivityState} / {@link NpcActivityStateService#toMoveState}
 * 映射表与 V1.1 实锤的两套枚举一致：activityState 五值 ↔ moveState 二值；
 * 映射表为<b>唯一来源</b>（卡3-B/卡4-B 的 moveState 投影与沙盘复位共用本表，禁止散落第二份映射）。</p>
 */
class NpcActivityStateServiceTest {

    /** activityState 五值 → moveState 二值（walk→walking；其余→idle；null/空→idle） */
    @Test
    @DisplayName("G-ENGINE-ACT-02: activityState→moveState 全值映射")
    void enumMapping_activityStateToMoveState_allValues() {
        assertEquals("walking", NpcActivityStateService.toMoveState("walk"));
        assertEquals("idle", NpcActivityStateService.toMoveState("idle"));
        assertEquals("idle", NpcActivityStateService.toMoveState("stop"));
        assertEquals("idle", NpcActivityStateService.toMoveState("talk"));
        assertEquals("idle", NpcActivityStateService.toMoveState("rest"));
        assertEquals("idle", NpcActivityStateService.toMoveState(null));
        assertEquals("idle", NpcActivityStateService.toMoveState("  "));
        assertEquals("idle", NpcActivityStateService.toMoveState("WALK"), "大小写不敏感归一后按 idle 兜底（仅小写 walk 为移动）");
    }

    /** 引擎活动文本 → activityState（walk 语义必须经 toMoveState 落到 "walking"） */
    @Test
    @DisplayName("G-ENGINE-ACT-02: 引擎活动文本→activityState（walk 语义 → walking）")
    void enumMapping_activityTextToActivityState_walkSemantics() {
        assertEquals("walk", NpcActivityStateService.toActivityState("前往集市"));
        assertEquals("walk", NpcActivityStateService.toActivityState("去王都酒馆赴约"));
        assertEquals("rest", NpcActivityStateService.toActivityState("深夜在驻地歇息就寝"));
        assertEquals("rest", NpcActivityStateService.toActivityState("回房睡觉"));
        assertEquals("idle", NpcActivityStateService.toActivityState("上午在城东市场忙于日常事务"));
        assertEquals("talk", NpcActivityStateService.toActivityState("闲谈"));
        assertEquals("idle", NpcActivityStateService.toActivityState(null));
        assertEquals("idle", NpcActivityStateService.toActivityState(""));
        // walk 语义必须映射 walking（沙盘复位共用本表）
        assertEquals("walking", NpcActivityStateService.toMoveState(
                NpcActivityStateService.toActivityState("前往集市")));
    }
}
