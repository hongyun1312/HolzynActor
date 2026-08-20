package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrdinaryNpcService 调度纯逻辑单元测试（普通型人群完全重构后）。
 * <p>职责：验证程序化状态机时段规则、行动描述生成、两级 AI 调度解析
 * （项目级 parseProjectSchedule / 归属级 parseAffiliationSchedule）与快照/概况文本。</p>
 */
class OrdinaryNpcScheduleTest {

    private final Random rng = new Random(42);

    /**
     * 状态机时段规则：凌晨/深夜休息，清晨/傍晚行走，正午/深夜休息，夜晚交谈。
     */
    @Test
    void computeStateFollowsTimeOfDay() {
        assertEquals("rest", OrdinaryNpcService.computeState(0, rng));
        assertEquals("rest", OrdinaryNpcService.computeState(3, rng));
        assertEquals("walk", OrdinaryNpcService.computeState(6, rng));
        assertEquals("walk", OrdinaryNpcService.computeState(7, rng));
        assertEquals("rest", OrdinaryNpcService.computeState(12, rng));
        assertEquals("rest", OrdinaryNpcService.computeState(13, rng));
        assertEquals("walk", OrdinaryNpcService.computeState(18, rng));
        assertEquals("walk", OrdinaryNpcService.computeState(19, rng));
        assertEquals("talk", OrdinaryNpcService.computeState(20, rng));
        assertEquals("talk", OrdinaryNpcService.computeState(22, rng));
        assertEquals("rest", OrdinaryNpcService.computeState(23, rng));
    }

    /**
     * 状态机工作日间：上午/下午为停留或交谈（含个体随机变化），绝不会出现休息。
     */
    @Test
    void computeStateWorkHoursAreStopOrTalk() {
        for (int hour = 8; hour <= 11; hour++) {
            String s = OrdinaryNpcService.computeState(hour, rng);
            assertTrue(List.of("stop", "talk").contains(s), "上午 " + hour + " 点应为 stop/talk，实际 " + s);
        }
        for (int hour = 14; hour <= 17; hour++) {
            String s = OrdinaryNpcService.computeState(hour, rng);
            assertTrue(List.of("stop", "talk").contains(s), "下午 " + hour + " 点应为 stop/talk，实际 " + s);
        }
    }

    /**
     * 行动描述：结合职业/所在地/状态生成可读文本，状态全覆盖；无档案兜底。
     */
    @Test
    void describeActionCoversAllStates() {
        ActorOrdinaryNpc n = new ActorOrdinaryNpc();
        n.setOccupation("商贩");
        n.setLocation("集市");
        assertTrue(OrdinaryNpcService.describeAction(n, "walk", 7).contains("集市"));
        assertTrue(OrdinaryNpcService.describeAction(n, "stop", 9).contains("商贩"));
        assertTrue(OrdinaryNpcService.describeAction(n, "talk", 21).contains("攀谈"));
        assertTrue(OrdinaryNpcService.describeAction(n, "rest", 23).contains("就寝"));
        ActorOrdinaryNpc blank = new ActorOrdinaryNpc();
        assertNotNull(OrdinaryNpcService.describeAction(blank, "stop", 10));
    }

    /**
     * 项目级调度解析：summary + affiliations 指令；非法 JSON 抛中文异常。
     */
    @Test
    void parseProjectScheduleReadsSummaryAndAffiliations() {
        String good = "{\"summary\":\"上午集市熙攘，商贩忙碌待客。\",\"affiliations\":["
                + "{\"affiliation\":\"临江城商会\",\"directive\":\"集市开张，招呼主顾\"}]}";
        OrdinaryNpcService.ProjectSchedule plan = OrdinaryNpcService.parseProjectSchedule(good);
        assertEquals("上午集市熙攘，商贩忙碌待客。", plan.summary());
        assertEquals(1, plan.affiliations().size());
        assertEquals("临江城商会", plan.affiliations().get(0).affiliation());
        assertEquals("集市开张，招呼主顾", plan.affiliations().get(0).directive());
        assertThrows(BizException.class, () -> OrdinaryNpcService.parseProjectSchedule("不是 JSON"));
    }

    /**
     * 归属级调度解析：name/state/action 数组；非法 state 过滤。
     */
    @Test
    void parseAffiliationScheduleReadsActionsAndFiltersBadState() {
        String good = "[{\"name\":\"阿明\",\"state\":\"walk\",\"action\":\"挑着货担走向集市\"},"
                + "{\"name\":\"翠娘\",\"state\":\"talk\",\"action\":\"在布庄门口与邻居闲聊\"}]";
        List<OrdinaryNpcService.NpcAction> actions = OrdinaryNpcService.parseAffiliationSchedule(good);
        assertEquals(2, actions.size());
        assertEquals("阿明", actions.get(0).name());
        assertEquals("walk", actions.get(0).state());
        // 非法 state 被过滤，仅保留合法项
        String bad = "[{\"name\":\"阿明\",\"state\":\"fly\",\"action\":\"x\"},"
                + "{\"name\":\"翠娘\",\"state\":\"stop\",\"action\":\"y\"}]";
        List<OrdinaryNpcService.NpcAction> filtered = OrdinaryNpcService.parseAffiliationSchedule(bad);
        assertEquals(1, filtered.size());
        assertEquals("翠娘", filtered.get(0).name());
        assertTrue(OrdinaryNpcService.parseAffiliationSchedule("不是 JSON").isEmpty());
    }

    /**
     * 群体快照文本：按状态统计 + 时段总述。
     */
    @Test
    void buildSnapshotSummarizesStates() {
        ActorOrdinaryNpc n1 = new ActorOrdinaryNpc();
        n1.setName("阿福");
        ActorOrdinaryNpc n2 = new ActorOrdinaryNpc();
        n2.setName("翠娘");
        Map<String, Long> states = Map.of("walk", 1L, "rest", 1L);
        String s = OrdinaryNpcService.buildSnapshot(List.of(n1, n2), states, 9);
        assertTrue(s.contains("2 名普通居民"));
        assertTrue(s.contains("1 人在路上"));
        assertTrue(s.contains("1 人在歇息"));
    }

    /**
     * 无快照时的静态概况：空列表与有居民均能给出可读文本。
     */
    @Test
    void defaultSummaryProducesReadableText() {
        assertTrue(OrdinaryNpcService.defaultSummary(List.of()).contains("暂无"));
        ActorOrdinaryNpc n = new ActorOrdinaryNpc();
        n.setAffiliation("临江渔村");
        n.setOccupation("渔夫");
        assertTrue(OrdinaryNpcService.defaultSummary(List.of(n)).contains("1 名普通居民"));
        assertTrue(OrdinaryNpcService.defaultSummary(List.of(n)).contains("临江渔村"));
    }

    /**
     * 归属概况：按归属分组统计人数/职业/状态（项目级调度 AI 输入）。
     */
    @Test
    void affiliationOverviewGroupsByAffiliation() {
        ActorOrdinaryNpc n1 = new ActorOrdinaryNpc();
        n1.setAffiliation("临江渔村");
        n1.setOccupation("渔夫");
        n1.setState("stop");
        ActorOrdinaryNpc n2 = new ActorOrdinaryNpc();
        n2.setAffiliation("临江渔村");
        n2.setOccupation("渔夫");
        n2.setState("walk");
        String ov = OrdinaryNpcService.affiliationOverview(List.of(n1, n2));
        assertTrue(ov.contains("临江渔村"));
        assertTrue(ov.contains("共 2 人"));
    }
}
