package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N4 空间候选选择单测：precondition 程序化三态评估器（SpacePreconditionEvaluator 纯静态）。
 *
 * <p>测试纪律（V1.3 第六层）：纯静态构造断言——时刻词/数值区间/需求值比较/需求弱词/
 * 在场者/天气/地点/否定前缀/且段组合逐类覆盖；三态口径（SATISFIED/NOT_SATISFIED/UNKNOWN）。</p>
 *
 * <p>所属模块：domain/growth/space（N 轨道 N4 程序化件）</p>
 */
class SpacePreconditionEvaluatorTest {

    private static SpacePreconditionEvaluator.Context ctx(Integer hour, ActorNpcState st,
                                                          List<String> present, List<String> village,
                                                          String weather, String location) {
        return new SpacePreconditionEvaluator.Context(hour, st, present, village, weather, location);
    }

    private static ActorNpcState state(int energy, int hunger, int thirst, int mood) {
        ActorNpcState st = new ActorNpcState();
        st.setEnergy(energy);
        st.setHunger(hunger);
        st.setThirst(thirst);
        st.setMood(mood);
        return st;
    }

    @Test
    @DisplayName("空/空白条件 → SATISFIED（无约束）")
    void blankIsSatisfied() {
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate(null, ctx(10, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("   ", ctx(10, null, null, null, null, null)));
    }

    @Test
    @DisplayName("时刻词：白天/夜晚/凌晨/午后 命中与未命中")
    void timeWords() {
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天", ctx(12, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天", ctx(20, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("夜晚", ctx(22, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("夜晚", ctx(12, null, null, null, null, null)));
        // 跨午夜区间：凌晨 2 点命中（凌晨=0-6）
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("凌晨", ctx(2, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("午后", ctx(15, null, null, null, null, null)));
    }

    @Test
    @DisplayName("时刻数值：N点前/N点后/N点到M点")
    void hourBounds() {
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("8点以前", ctx(7, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("8点以前", ctx(9, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("21点以后", ctx(22, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("9点到11点", ctx(10, null, null, null, null, null)));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("9点到11点", ctx(12, null, null, null, null, null)));
        // 时刻上下文缺失 → UNKNOWN（宽松放行，语义兜底）
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("白天", ctx(null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("需求值比较：精力/心情 × ≥/≤/</关键词")
    void needCompare() {
        SpacePreconditionEvaluator.Context c = ctx(10, state(50, 70, 70, 80), null, null, null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("精力不低于40", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("精力≥60", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("心情高于70", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("心情小于60", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("体力<60", c));
    }

    @Test
    @DisplayName("需求弱词：疲惫/饥饿/饱/口渴/心情好差")
    void needWords() {
        SpacePreconditionEvaluator.Context tired = ctx(10, state(20, 50, 60, 70), null, null, null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("疲惫", tired));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("不疲惫", tired));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("饿了", tired));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("饱", tired));
        SpacePreconditionEvaluator.Context fed = ctx(10, state(80, 90, 90, 90), null, null, null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("饱", fed));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("口渴", fed));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("心情好", fed));
        SpacePreconditionEvaluator.Context low = ctx(10, state(80, 90, 90, 30), null, null, null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("心情低落", low));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("心情好", low));
        // 状态行缺失 → UNKNOWN
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("饿了", ctx(10, null, null, null, null, null)));
    }

    @Test
    @DisplayName("在场者：独处/无人/XX在场/XX不在/XX在村中")
    void presence() {
        SpacePreconditionEvaluator.Context alone = ctx(10, null, List.of(), List.of("张三"), null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("独处", alone));
        SpacePreconditionEvaluator.Context withPpl = ctx(10, null, List.of("李四"), List.of("张三", "李四"), null, null);
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("独处", withPpl));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("无人", withPpl));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("李四在场", withPpl));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("李四不在场", withPpl));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("张三在场", withPpl)); // 张三不在同地点
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("张三在村中", withPpl));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("张三不在村中", withPpl));
        // 名单未知 → UNKNOWN
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("独处", ctx(10, null, null, null, null, null)));
    }

    @Test
    @DisplayName("天气：下雨/晴/雪/阴/大风 命中与未命中")
    void weather() {
        SpacePreconditionEvaluator.Context rainy = ctx(10, null, null, null, "小雨", null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("下雨", rainy));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("晴天", rainy));
        SpacePreconditionEvaluator.Context sunny = ctx(10, null, null, null, "晴", null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("晴天", sunny));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("下雨", sunny));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("没下雨", sunny));
        SpacePreconditionEvaluator.Context snowy = ctx(10, null, null, null, "大雪", null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("下雪", snowy));
        SpacePreconditionEvaluator.Context windy = ctx(10, null, null, null, "北风", null);
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("大风", windy));
        // 天气未知 → UNKNOWN
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("下雨", ctx(10, null, null, null, null, null)));
    }

    @Test
    @DisplayName("地点：在XX / 不在XX（当前地点包含判定）")
    void location() {
        SpacePreconditionEvaluator.Context c = ctx(10, null, null, null, null, "村口老槐树下");
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("在村口", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("在河边", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("不在河边", c));
        // 地点未知 → UNKNOWN
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("在村口", ctx(10, null, null, null, null, null)));
    }

    @Test
    @DisplayName("且段组合：全部满足才 SATISFIED，任一段不满足即出局")
    void andSegments() {
        SpacePreconditionEvaluator.Context c = ctx(10, state(60, 80, 80, 80),
                List.of(), List.of("张三"), "晴", "田间");
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天且精力不低于50", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天，精力≥90", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天且不疲惫、晴天", c));
        assertEquals(SpacePreconditionEvaluator.Verdict.NOT_SATISFIED,
                SpacePreconditionEvaluator.evaluate("白天且下雨", c));
        // 语义段混入 → UNKNOWN（任一段无法判定；「若有人提及旧契」类语义条件须语义兜底）
        assertEquals(SpacePreconditionEvaluator.Verdict.UNKNOWN,
                SpacePreconditionEvaluator.evaluate("白天且若有人提及旧契", c));
    }
}
