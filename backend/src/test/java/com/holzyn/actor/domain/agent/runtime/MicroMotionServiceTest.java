package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 生理小动作服务单元测试（实时行为决策系统 M4，domain/agent/runtime，同包）。
 * <p>职责：验证——① 状态 → 小动作映射（健康告急最优先 / 生理需求最低者 / 主导情绪 / 无触发 null）；
 * ② 随机点缀确定性（randomMotion 按 ref 种子稳定）；③ maybeMicroMotion 节流与落点
 * （开关关跳过 / 冷却窗口拦截 / 触发 → thought_text+SSE publish）。</p>
 */
class MicroMotionServiceTest {

    private RealtimeDecisionProperties props;
    private WorldSandboxStateService sandboxStateService;
    private MicroMotionService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new RealtimeDecisionProperties();
        sandboxStateService = Mockito.mock(WorldSandboxStateService.class);
        service = new MicroMotionService(props);
        java.lang.reflect.Field f = MicroMotionService.class.getDeclaredField("sandboxStateService");
        f.setAccessible(true);
        f.set(service, sandboxStateService);
    }

    private ActorNpcState state() {
        ActorNpcState st = new ActorNpcState();
        st.setProjectId(1L);
        st.setNpcRef("special:1");
        st.setNpcType("special");
        return st;
    }

    @Test
    void resolveMotion_healthAlertFirst() {
        ActorNpcState st = state();
        st.setHealth(20); // 健康告急最优先
        st.setHunger(10);
        assertEquals("捂着胸口，脸色发白", MicroMotionService.resolveMotion(st));
    }

    @Test
    void resolveMotion_mostUrgentNeedWins() {
        ActorNpcState st = state();
        st.setHealth(100);
        st.setHunger(20);
        st.setThirst(70);
        st.setBladder(50);
        st.setEnergy(80);
        assertEquals("摸了摸咕咕直叫的肚子", MicroMotionService.resolveMotion(st), "饥饿最低 → 揉肚子");

        ActorNpcState st2 = state();
        st2.setHealth(100);
        st2.setHunger(70);
        st2.setThirst(15);
        assertEquals("舔了舔干裂的嘴唇", MicroMotionService.resolveMotion(st2), "口渴最低 → 舔唇");

        ActorNpcState st3 = state();
        st3.setHealth(100);
        st3.setHunger(70);
        st3.setThirst(70);
        st3.setBladder(20);
        st3.setEnergy(80);
        assertEquals("不安地挪了挪身子", MicroMotionService.resolveMotion(st3), "膀胱告急 → 不安挪动");

        ActorNpcState st4 = state();
        st4.setHealth(100);
        st4.setHunger(70);
        st4.setThirst(70);
        st4.setBladder(50);
        st4.setEnergy(20);
        assertEquals("打了个哈欠，揉了揉眼睛", MicroMotionService.resolveMotion(st4), "疲劳 → 哈欠揉眼");
    }

    @Test
    void resolveMotion_emotionMapped() {
        ActorNpcState st = state();
        st.setDominantEmotion("sad");
        assertEquals("轻轻叹了口气", MicroMotionService.resolveMotion(st));

        ActorNpcState st2 = state();
        st2.setDominantEmotion("anger");
        assertEquals("攥了攥拳头", MicroMotionService.resolveMotion(st2));
    }

    @Test
    void resolveMotion_noTriggerReturnsNull() {
        ActorNpcState st = state();
        st.setHealth(100);
        st.setHunger(70);
        st.setThirst(70);
        st.setBladder(50);
        st.setEnergy(80);
        st.setDominantEmotion("calm");
        assertNull(MicroMotionService.resolveMotion(st), "无状态触发 → null（随机点缀由外层概率门控）");
    }

    @Test
    void randomMotion_deterministicByRef() {
        String a1 = MicroMotionService.randomMotion("special:5");
        String a2 = MicroMotionService.randomMotion("special:5");
        String b = MicroMotionService.randomMotion("special:6");
        assertEquals(a1, a2, "同一 ref 种子稳定");
        assertNotNull(a1);
        assertTrue(!a1.isBlank());
        // 不同 ref 大概率不同（仅做不相等断言，避免偶发相同导致 flaky——这里只验证池非空且稳定）
    }

    @Test
    void maybeMicroMotion_disabledSkips() {
        props.setMicroMotionEnabled(false);
        service.maybeMicroMotion(1L, "special:1", state(), 36000L);
        verify(sandboxStateService, never()).publishThought(any(), any(), any(), any(), any());
    }

    @Test
    void maybeMicroMotion_triggersPublish() {
        props.setMicroMotionCooldownGameMinutes(30);
        ActorNpcState st = state();
        st.setHealth(100);
        st.setHunger(70);
        st.setThirst(70);
        st.setBladder(50);
        st.setEnergy(15);
        service.maybeMicroMotion(1L, "special:1", st, 36000L);
        verify(sandboxStateService).publishThought(Mockito.eq(1L), Mockito.eq("special:1"),
                Mockito.eq("打了个哈欠，揉了揉眼睛"), Mockito.eq("m4_micro"), Mockito.eq(36000L));
    }

    @Test
    void maybeMicroMotion_cooldownBlocksRepeat() {
        props.setMicroMotionCooldownGameMinutes(30);
        ActorNpcState st = state();
        st.setHealth(100);
        st.setHunger(10);
        service.maybeMicroMotion(1L, "special:1", st, 36000L);
        service.maybeMicroMotion(1L, "special:1", st, 36400L); // +400 秒 < 30 分钟冷却
        verify(sandboxStateService, Mockito.times(1)).publishThought(any(), any(), any(), any(), any());

        service.maybeMicroMotion(1L, "special:1", st, 54000L); // +18000 秒 = 冷却已过
        verify(sandboxStateService, Mockito.times(2)).publishThought(any(), any(), any(), any(), any());
    }

    @Test
    void maybeMicroMotion_crowdAlsoApplies() {
        ActorNpcState st = new ActorNpcState();
        st.setProjectId(1L);
        st.setNpcRef("crowd:12");
        st.setNpcType("crowd");
        st.setHealth(100);
        st.setHunger(5); // 人群也按状态触发小动作（Q7 用户决策：特殊/人群都适用）
        service.maybeMicroMotion(1L, "crowd:12", st, 36000L);
        verify(sandboxStateService).publishThought(Mockito.eq(1L), Mockito.eq("crowd:12"),
                Mockito.eq("摸了摸咕咕直叫的肚子"), Mockito.eq("m4_micro"), Mockito.eq(36000L));
    }
}
