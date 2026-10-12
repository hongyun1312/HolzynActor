package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PhysioProfile 单元测试（O6 轨道 G1a：档案 ③ 生理基线 ↔ NPC 状态模块联动）。
 *
 * <p>职责：锁定"生理基线不独立于状态模块"这条要求的可验证证据——</p>
 * <ol>
 *   <li><b>换算正确性</b>：③ 维（代谢/餐制/睡眠需求/作息型/耐痛/耐寒热）→ 个体生效系数；</li>
 *   <li><b>个体睡眠相位</b>：早鸟/中间/夜猫子三类 NPC 的夜间时段各不相同（取代全局统一窗口）；</li>
 *   <li><b>联动生效</b>：同一段时间内，代谢快与代谢慢的 NPC 经 {@link NpcStateService#applyDecay}
 *       衰减出的饥饿值不同——证明档案数值<b>真的驱动</b>了状态模块，而不是写进文本里当装饰；</li>
 *   <li><b>零阻断</b>：脏 JSON / 缺字段回退默认系数，绝不让状态模块停摆。</li>
 * </ol>
 * <p>所属模块：domain/npc/state/service（测试）。</p>
 */
class PhysioProfileTest {

    /** 构造一份档案 ③ 维 JSON（字段名 snake_case，与 persona_schema_v2 一致）。 */
    private static String physioJson(String metabolism, double mealCycle, double sleepNeed,
                                     String chronotype, int pain, int cold, int heat) {
        return "{"
                + "\"chronotype\":\"" + chronotype + "\","
                + "\"sleep_need_hours\":" + sleepNeed + ","
                + "\"meal_cycle_hours\":" + mealCycle + ","
                + "\"metabolism\":\"" + metabolism + "\","
                + "\"pain_tolerance\":" + pain + ","
                + "\"cold_tolerance\":" + cold + ","
                + "\"heat_tolerance\":" + heat
                + "}";
    }

    /** 默认系数：全 1.0，睡眠窗口 22-6（等价于状态模块全局默认，不影响未建档 NPC）。 */
    @Test
    void defaultsAreNeutral() {
        PhysioProfile d = PhysioProfile.defaults();
        assertEquals(1.0, d.hungerScale(), 1e-9);
        assertEquals(1.0, d.thirstScale(), 1e-9);
        assertEquals(1.0, d.energyScale(), 1e-9);
        assertEquals(1.0, d.bowelScale(), 1e-9);
        assertEquals(8.0, d.sleepNeedHours(), 1e-9);
        assertEquals(22, d.sleepStartHour());
        assertEquals(6, d.sleepEndHour());
        assertEquals(1.0, d.painFactor(), 1e-9);
    }

    /** 缺省/脏数据零阻断：空 JSON、非法 JSON、缺字段都回退默认系数。 */
    @Test
    void dirtyJsonFallsBackToDefaults() {
        assertEquals(1.0, PhysioProfile.fromPhysioJson(null).hungerScale(), 1e-9);
        assertEquals(1.0, PhysioProfile.fromPhysioJson("").hungerScale(), 1e-9);
        assertEquals(1.0, PhysioProfile.fromPhysioJson("{不是合法 JSON").hungerScale(), 1e-9);
        assertEquals(1.0, PhysioProfile.fromPhysioJson("{}").hungerScale(), 1e-9);
        assertEquals(1.0, PhysioProfile.fromCoefJson("垃圾").hungerScale(), 1e-9);
    }

    /** 换算：代谢慢 → 饿得慢/渴得慢；代谢快 → 全部加速。 */
    @Test
    void metabolismScalesDecay() {
        PhysioProfile slow = PhysioProfile.fromPhysioJson(physioJson("slow", 8, 8, "intermediate", 5, 5, 5));
        PhysioProfile normal = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "intermediate", 5, 5, 5));
        PhysioProfile fast = PhysioProfile.fromPhysioJson(physioJson("fast", 8, 8, "intermediate", 5, 5, 5));
        assertEquals(0.85, slow.hungerScale(), 1e-9);
        assertEquals(1.00, normal.hungerScale(), 1e-9);
        assertEquals(1.18, fast.hungerScale(), 1e-9);
        assertTrue(slow.thirstScale() < normal.thirstScale(), "代谢慢 → 口渴更慢");
        assertTrue(fast.thirstScale() > normal.thirstScale(), "代谢快 → 口渴更快");
    }

    /** 换算：餐隔越短 → 饿得越快（以 8 游戏小时为基线）。 */
    @Test
    void mealCycleScalesHunger() {
        PhysioProfile every6 = PhysioProfile.fromPhysioJson(physioJson("normal", 6, 8, "intermediate", 5, 5, 5));
        PhysioProfile every12 = PhysioProfile.fromPhysioJson(physioJson("normal", 12, 8, "intermediate", 5, 5, 5));
        // 8/6 ≈ 1.33；8/12 ≈ 0.67
        assertEquals(1.33, every6.hungerScale(), 0.01);
        assertEquals(0.67, every12.hungerScale(), 0.01);
        assertTrue(every6.hungerScale() > every12.hungerScale(), "吃得勤的人饿得快");
    }

    /** 换算：睡眠需求短 → 精力耗得慢（以 8 小时为基线）。 */
    @Test
    void sleepNeedScalesEnergy() {
        PhysioProfile lightSleeper = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 6.0, "intermediate", 5, 5, 5));
        PhysioProfile heavySleeper = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 9.0, "intermediate", 5, 5, 5));
        assertEquals(0.75, lightSleeper.energyScale(), 0.01);
        assertEquals(1.13, heavySleeper.energyScale(), 0.01);
    }

    /** 个体睡眠相位：早鸟 / 中间 / 夜猫子三类各不相同。 */
    @Test
    void chronotypeShiftsSleepWindow() {
        PhysioProfile early = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "early_bird", 5, 5, 5));
        PhysioProfile mid = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "intermediate", 5, 5, 5));
        PhysioProfile owl = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "night_owl", 5, 5, 5));
        assertEquals(21, early.sleepStartHour());
        assertEquals(22, mid.sleepStartHour());
        assertEquals(24, owl.sleepStartHour());
        assertEquals(8, owl.sleepEndHour());

        // 21 点：早鸟已入睡，中间型与夜猫子还醒着
        assertTrue(early.isSleepWindow(21));
        assertFalse(mid.isSleepWindow(21));
        assertFalse(owl.isSleepWindow(21));
        // 3 点：三者都在睡
        assertTrue(early.isSleepWindow(3));
        assertTrue(mid.isSleepWindow(3));
        assertTrue(owl.isSleepWindow(3));
        // 10 点：都醒着
        assertFalse(early.isSleepWindow(10));
        assertFalse(owl.isSleepWindow(10));
    }

    /** 换算：耐受阈值 → 系数（耐痛高 → 健康侵蚀慢；耐寒低 → 寒冷敏感放大）。 */
    @Test
    void toleranceScalesFactors() {
        PhysioProfile hardy = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "intermediate", 8, 8, 8));
        PhysioProfile frail = PhysioProfile.fromPhysioJson(physioJson("normal", 8, 8, "intermediate", 2, 2, 2));
        assertEquals(0.7, hardy.painFactor(), 1e-9);
        assertEquals(1.3, frail.painFactor(), 1e-9);
        assertEquals(0.7, hardy.coldSens(), 1e-9);
        assertEquals(1.3, frail.coldSens(), 1e-9);
        assertTrue(frail.painFactor() > hardy.painFactor(), "耐受低 → 健康侵蚀更快");
    }

    /** 系数 JSON 往返无损（落库/读库口径）。 */
    @Test
    void coefJsonRoundTrip() {
        PhysioProfile p = PhysioProfile.fromPhysioJson(
                physioJson("slow", 8.5, 6.5, "early_bird", 7, 3, 5)).withEndurance(4);
        String json = p.toJson();
        assertNotNull(json);
        assertTrue(json.contains("hungerScale"));
        PhysioProfile back = PhysioProfile.fromCoefJson(json);
        assertEquals(p.hungerScale(), back.hungerScale(), 1e-9);
        assertEquals(p.sleepNeedHours(), back.sleepNeedHours(), 1e-9);
        assertEquals(p.sleepStartHour(), back.sleepStartHour());
        assertEquals(p.coldSens(), back.coldSens(), 1e-9);
        assertEquals(p.recoverScale(), back.recoverScale(), 1e-9);
        assertEquals(p.chronotype(), back.chronotype());
    }

    /**
     * ★ 联动验证（本测试类存在的核心理由）：
     * 同一段时间、同一份全局配置下，<b>只有生理系数不同</b>的两个 NPC，衰减结果必须不同——
     * 证明档案 ③ 维数值真正驱动了 NPC 状态模块，而不是"独立出来"的装饰数据。
     */
    @Test
    void applyDecayUsesPerNpcPhysioProfile() {
        NpcStateProperties props = new NpcStateProperties();
        long from = 12 * 3600L;      // 当日 12:00（白天，三者都醒着）
        long to = from + 10 * 3600L; // 推进 10 游戏小时

        ActorNpcState slow = new ActorNpcState();
        slow.setNpcType("common");
        slow.setHunger(70);
        slow.setEnergy(80);
        slow.setPhysioCoefJson(PhysioProfile.fromPhysioJson(
                physioJson("slow", 8, 8, "intermediate", 5, 5, 5)).toJson());

        ActorNpcState fast = new ActorNpcState();
        fast.setNpcType("common");
        fast.setHunger(70);
        fast.setEnergy(80);
        fast.setPhysioCoefJson(PhysioProfile.fromPhysioJson(
                physioJson("fast", 6, 8, "intermediate", 5, 5, 5)).toJson());

        NpcStateService.applyDecay(slow, from, to, props);
        NpcStateService.applyDecay(fast, from, to, props);

        assertTrue(fast.getHunger() < slow.getHunger(),
                "代谢快 + 吃得勤的 NPC 应更饿：fast=" + fast.getHunger() + " slow=" + slow.getHunger());

        // 无系数的 NPC 等价于全局默认（defaults 全 1.0）
        ActorNpcState plain = new ActorNpcState();
        plain.setNpcType("common");
        plain.setHunger(70);
        plain.setEnergy(80);
        NpcStateService.applyDecay(plain, from, to, props);
        assertTrue(plain.getHunger() > fast.getHunger() && plain.getHunger() < slow.getHunger(),
                "默认系数应落在 slow 与 fast 之间：plain=" + plain.getHunger());
    }

    /**
     * ★ 联动的第二重验证：个体睡眠相位会让"同一时刻"的两个 NPC 走不同分支
     * （一个在恢复精力，一个在消耗精力）。
     */
    @Test
    void applyDecayHonoursIndividualSleepWindow() {
        NpcStateProperties props = new NpcStateProperties();
        // 21:00 → 23:00：早鸟（21-5）应进入睡眠恢复，中间型（22-6）22 点后进入，夜猫子（24-8）仍在醒着
        long from = 21 * 3600L;
        long to = 23 * 3600L;

        ActorNpcState early = stateWith("early_bird", 40);
        ActorNpcState owl = stateWith("night_owl", 40);
        NpcStateService.applyDecay(early, from, to, props);
        NpcStateService.applyDecay(owl, from, to, props);

        assertTrue(early.getEnergy() > 40, "早鸟在 21-23 点应在睡眠恢复精力，实际 " + early.getEnergy());
        assertTrue(owl.getEnergy() < 40, "夜猫子在 21-23 点仍清醒，精力应继续下降，实际 " + owl.getEnergy());
    }

    /** 构造带指定作息型与初始精力的状态行。 */
    private static ActorNpcState stateWith(String chronotype, int energy) {
        ActorNpcState st = new ActorNpcState();
        st.setNpcType("common");
        st.setHunger(70);
        st.setEnergy(energy);
        st.setPhysioCoefJson(PhysioProfile.fromPhysioJson(
                physioJson("normal", 8, 8, chronotype, 5, 5, 5)).toJson());
        return st;
    }
}