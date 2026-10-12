package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.growth.space.GrowthSpaceParse.ActionItem;
import com.holzyn.actor.domain.growth.space.GrowthSpaceParse.GrowthSpaceDoc;
import com.holzyn.actor.domain.growth.space.GrowthSpaceParse.Inner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N4 空间候选选择单测：候选集组装器（SpaceDecisionCandidates 纯静态）。
 *
 * <p>测试纪律（V1.3 第六层）：纯静态构造断言——通用三项恒在（与空间候选去重）、权重作
 * 「（倾向 x%）」情境描述、上限 8、空间候选键 spaceN/通用键 continue_current·rest·chat。</p>
 *
 * <p>所属模块：domain/growth/space（N 轨道 N4 程序化件）</p>
 */
class SpaceDecisionCandidatesTest {

    private static GrowthSpaceDoc doc(ActionItem... actions) {
        return new GrowthSpaceDoc(new Inner(null, null, null),
                List.of(), List.of(actions), List.of());
    }

    private static ActionItem action(String text, Double weight, String precondition, String target) {
        return new ActionItem(text, weight, precondition, target);
    }

    private static ActorNpcState state(Integer energy) {
        ActorNpcState st = new ActorNpcState();
        if (energy != null) {
            st.setEnergy(energy);
        }
        return st;
    }

    @Test
    @DisplayName("无空间候选 → 仅通用三项（继续/歇/聊），rest 描述随疲惫动态")
    void genericOnly() {
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc(), state(null));
        assertNotNull(c);
        assertEquals(3, c.size());
        assertTrue(c.get(0).key().equals(SpaceDecisionCandidates.KEY_CONTINUE));
        assertTrue(c.get(1).key().equals(SpaceDecisionCandidates.KEY_REST));
        assertTrue(c.get(2).key().equals(SpaceDecisionCandidates.KEY_CHAT));
        assertTrue(c.stream().allMatch(SpaceDecisionCandidates.SpaceCandidate::generic));
        // 疲惫 → rest 描述带疲惫
        List<SpaceDecisionCandidates.SpaceCandidate> tired =
                SpaceDecisionCandidates.candidatesFrom(doc(), state(30));
        assertTrue(tired.stream().anyMatch(x -> x.key().equals(SpaceDecisionCandidates.KEY_REST)
                && x.questionText().contains("疲惫")));
    }

    @Test
    @DisplayName("空间候选填充：权重→（倾向 x%）后缀、precondition/target 原样保留")
    void spaceCandidatesWithWeight() {
        GrowthSpaceDoc doc = doc(
                action("去河边挑水", 0.8, "白天", "河边"),
                action("坐在门槛上发呆", null, null, null));
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null));
        assertEquals(5, c.size());
        SpaceDecisionCandidates.SpaceCandidate carry = c.get(3);
        assertEquals("space0", carry.key());
        assertEquals("去河边挑水", carry.action());
        assertEquals("去河边挑水（倾向 80%）", carry.questionText());
        assertEquals("白天", carry.precondition());
        assertEquals("河边", carry.target());
        assertFalse(carry.generic());
        SpaceDecisionCandidates.SpaceCandidate idle = c.get(4);
        assertEquals("space1", idle.key());
        assertEquals("坐在门槛上发呆", idle.questionText()); // 无权重不加后缀
        assertNull(idle.weight());
    }

    @Test
    @DisplayName("与空间候选去重：空间已含同类项则不重复加通用项")
    void dedupWithSpaceActions() {
        GrowthSpaceDoc doc = doc(
                action("继续当前正在做的事", null, null, null),
                action("去村口歇一歇", 0.5, null, null));
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null));
        // continue 与 rest 均由空间候选承担（去重）；chat 无同类项 → 通用项补入
        assertFalse(c.stream().anyMatch(x -> x.generic()
                && x.key().equals(SpaceDecisionCandidates.KEY_CONTINUE)));
        assertFalse(c.stream().anyMatch(x -> x.generic()
                && x.key().equals(SpaceDecisionCandidates.KEY_REST)));
        assertTrue(c.stream().anyMatch(x -> x.generic()
                && x.key().equals(SpaceDecisionCandidates.KEY_CHAT)));
        assertEquals(3, c.size()); // space0（继续）+ space1（歇）+ chat
    }

    @Test
    @DisplayName("上限 8：空间候选截断、通用三项恒在")
    void maxTotal() {
        ActionItem[] many = new ActionItem[12];
        for (int i = 0; i < 12; i++) {
            many[i] = action("行动" + i, 0.1 * (i + 1), null, null);
        }
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc(many), state(null));
        assertEquals(SpaceDecisionCandidates.MAX_TOTAL, c.size());
        assertTrue(c.get(0).key().equals(SpaceDecisionCandidates.KEY_CONTINUE));
        assertTrue(c.get(1).key().equals(SpaceDecisionCandidates.KEY_REST));
        assertTrue(c.get(2).key().equals(SpaceDecisionCandidates.KEY_CHAT));
        assertEquals("space4", c.get(c.size() - 1).key()); // 12 项截断 → 仅 5 个空间候选
    }

    @Test
    @DisplayName("null 文档 → 仅通用三项；空文本行动跳过")
    void nullDocAndBlankAction() {
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(null, null);
        assertEquals(3, c.size());
        GrowthSpaceDoc doc = doc(action("  ", null, null, null));
        List<SpaceDecisionCandidates.SpaceCandidate> c2 =
                SpaceDecisionCandidates.candidatesFrom(doc, null);
        assertEquals(3, c2.size());
    }

    // ==================== C 层社交聚集注入（2026-09-24 社会对话层修复） ====================

    /** 构造注入候选（社交聚集：带目标地点、非通用项）。 */
    private static SpaceDecisionCandidates.SpaceCandidate injected(String key, String action, String target) {
        return new SpaceDecisionCandidates.SpaceCandidate(
                key, action, action + "（倾向 30%）", target, null, 0.3, false);
    }

    @Test
    @DisplayName("注入候选排在通用三项之后、空间候选之前（键序 continue/rest/chat → gatherN → spaceN）")
    void injectedCandidatesFillBeforeSpaceActions() {
        GrowthSpaceDoc doc = doc(
                action("去河边挑水", 0.8, "白天", "河边"),
                action("坐在门槛上发呆", null, null, null));
        List<SpaceDecisionCandidates.SpaceCandidate> extra = List.of(
                injected("gather0", "去青禾茶馆坐坐，喝口茶听些闲话", "青禾茶馆"),
                injected("gather1", "去青禾集市赶集，凑凑热闹", "青禾集市"));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null), extra);

        assertEquals(7, c.size());
        assertEquals(SpaceDecisionCandidates.KEY_CONTINUE, c.get(0).key());
        assertEquals(SpaceDecisionCandidates.KEY_REST, c.get(1).key());
        assertEquals(SpaceDecisionCandidates.KEY_CHAT, c.get(2).key());
        assertEquals("gather0", c.get(3).key());
        assertEquals("青禾茶馆", c.get(3).target());
        assertFalse(c.get(3).generic(), "注入候选非通用项");
        assertEquals("gather1", c.get(4).key());
        // 空间候选键序不受注入影响（仍从 space0 起）
        assertEquals("space0", c.get(5).key());
        assertEquals("去河边挑水", c.get(5).action());
        assertEquals("space1", c.get(6).key());
    }

    @Test
    @DisplayName("注入候选优先占用总上限 8：注入项挤占空间候选槽位，超限注入项被截断")
    void injectedCandidatesRespectMaxTotal() {
        ActionItem[] many = new ActionItem[12];
        for (int i = 0; i < 12; i++) {
            many[i] = action("行动" + i, 0.1 * (i + 1), null, null);
        }
        List<SpaceDecisionCandidates.SpaceCandidate> extra = List.of(
                injected("gather0", "去茶馆坐坐", "茶馆"),
                injected("gather1", "去集市赶集", "集市"),
                injected("gather2", "去客栈坐坐", "客栈"),
                injected("gather3", "去村口转转", "村口"),
                injected("gather4", "去祠堂看看", "祠堂"),
                injected("gather5", "去庙里上香", "庙"));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc(many), state(null), extra);

        // 通用三项 + 注入 5 项 = 8（注入第 6 项与空间候选全被挤掉）
        assertEquals(SpaceDecisionCandidates.MAX_TOTAL, c.size());
        assertEquals("gather4", c.get(c.size() - 1).key());
        assertTrue(c.stream().noneMatch(x -> x.key().startsWith("space")), "空间候选被注入项挤占");
    }

    @Test
    @DisplayName("注入候选与既有候选按行动原文去重（先到者胜：注入项优先，重复的空间候选被挤掉）")
    void injectedCandidatesDedupWithExisting() {
        GrowthSpaceDoc doc = doc(action("去河边挑水", 0.8, "白天", "河边"));
        List<SpaceDecisionCandidates.SpaceCandidate> extra = List.of(
                injected("gather0", "找人说说话", "茶馆"),      // 与通用 chat 原文重复 → 去重
                injected("gather1", "去河边挑水", "河边"),      // 与空间候选原文重复 → 空间候选被挤掉
                injected("gather2", "去青禾集市赶集，凑凑热闹", "青禾集市"));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null), extra);

        assertEquals(5, c.size()); // continue/rest/chat + gather1 + gather2（空间候选 space0 因原文重复被去重）
        assertEquals("gather1", c.get(3).key());
        assertEquals("gather2", c.get(4).key());
        assertTrue(c.stream().noneMatch(x -> x.key().startsWith("space")), "重复的空间候选不重复占用槽位");
        assertEquals(1, c.stream().filter(x -> x.action().equals("找人说说话")).count());
        assertEquals(1, c.stream().filter(x -> x.action().equals("去河边挑水")).count());
    }

    @Test
    @DisplayName("空注入/非法注入项（null、空文本）不影响候选集")
    void blankInjectedCandidatesIgnored() {
        GrowthSpaceDoc doc = doc(action("去河边挑水", 0.8, "白天", "河边"));
        List<SpaceDecisionCandidates.SpaceCandidate> extra = new java.util.ArrayList<>();
        extra.add(null);
        extra.add(injected("gatherX", "  ", "某地"));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null), extra);

        assertEquals(4, c.size()); // 通用三项 + 空间一项
        assertEquals("space0", c.get(3).key());
    }

    // ==================== 4 参重载：轻行动抑制（生理需求耦合配比分层，2026-10-02） ====================

    @Test
    @DisplayName("suppressLightGeneric=true → 通用「歇/聊」不注入，「继续」恒在")
    void suppressLightSkipsRestAndChat() {
        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc(), state(null), List.of(), true);
        assertEquals(1, c.size(), "仅 continue 恒在");
        assertEquals(SpaceDecisionCandidates.KEY_CONTINUE, c.get(0).key());
        assertTrue(c.stream().noneMatch(x -> x.key().equals(SpaceDecisionCandidates.KEY_REST)));
        assertTrue(c.stream().noneMatch(x -> x.key().equals(SpaceDecisionCandidates.KEY_CHAT)));
    }

    @Test
    @DisplayName("suppressLightGeneric=true 与注入候选/空间候选兼容（生理候选在场、轻行动让位）")
    void suppressLightWithInjectedAndSpaceCandidates() {
        GrowthSpaceDoc doc = doc(action("去河边挑水", 0.8, "白天", "河边"));
        List<SpaceDecisionCandidates.SpaceCandidate> extra = List.of(
                injected("physio0", "前往云来茶馆用餐", "云来茶馆"),
                injected("physio1", "前往镇口水井饮水", "镇口水井"));

        List<SpaceDecisionCandidates.SpaceCandidate> c =
                SpaceDecisionCandidates.candidatesFrom(doc, state(null), extra, true);

        // continue + 生理注入 2 + 空间 1 = 4（无 rest/chat 轻行动）
        assertEquals(4, c.size());
        assertEquals(SpaceDecisionCandidates.KEY_CONTINUE, c.get(0).key());
        assertEquals("physio0", c.get(1).key());
        assertEquals("physio1", c.get(2).key());
        assertEquals("space0", c.get(3).key());
        assertTrue(c.stream().noneMatch(x -> x.generic()
                && !x.key().equals(SpaceDecisionCandidates.KEY_CONTINUE)), "除 continue 外无通用轻行动");
    }

    @Test
    @DisplayName("3 参重载等价 4 参 suppressLightGeneric=false（不抑制）")
    void threeParamDelegatesToNoSuppression() {
        List<SpaceDecisionCandidates.SpaceCandidate> three =
                SpaceDecisionCandidates.candidatesFrom(doc(), state(null), List.of());
        List<SpaceDecisionCandidates.SpaceCandidate> four =
                SpaceDecisionCandidates.candidatesFrom(doc(), state(null), List.of(), false);
        assertEquals(three.size(), four.size());
        assertEquals(three.stream().map(SpaceDecisionCandidates.SpaceCandidate::key).toList(),
                four.stream().map(SpaceDecisionCandidates.SpaceCandidate::key).toList());
    }
}
