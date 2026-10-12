package com.holzyn.actor.domain.growth.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 层社交聚集候选单测：{@link SocialGatheringCandidates} 纯静态组装器。
 *
 * <p>测试纪律（V1.3 第六层）：纯静态构造断言——触发三门（独处/非深夜/不在社交中心）、
 * 措辞按中心类型分化、权重作「（倾向 x%）」描述、target=中心地点名（供 move_to 执行链）、
 * 上限与去重。</p>
 *
 * <p>所属模块：domain/growth/space（N4 空间候选选择程序化件·C 层扩展）</p>
 */
class SocialGatheringCandidatesTest {

    private static final List<String> CENTERS = List.of("青禾茶馆", "青禾集市", "村口石牌坊", "青禾客栈");

    private static List<SpaceDecisionCandidates.SpaceCandidate> build(
            String currentLocation, boolean alone, Integer hourOfDay, int max, Double weight) {
        return SocialGatheringCandidates.candidates(
                CENTERS, currentLocation, alone, hourOfDay, 23, 6, max, weight);
    }

    @Test
    @DisplayName("独处 + 白天 + 不在社交中心 → 按顺序产出聚集候选（target=地点名，action 以「去」开头）")
    void producesCandidatesForLoneNpcInDaytime() {
        List<SpaceDecisionCandidates.SpaceCandidate> c = build("村东民居", true, 10, 4, 0.3);

        assertEquals(4, c.size());
        assertEquals("gather0", c.get(0).key());
        assertEquals("青禾茶馆", c.get(0).target());
        assertTrue(c.get(0).action().startsWith("去青禾茶馆"), "行动以「去」开头（move_to 前缀口径）");
        assertEquals("去青禾茶馆坐坐，喝茶闲聊（倾向 30%）", c.get(0).questionText());
        assertEquals(0.3, c.get(0).weight());
        assertFalse(c.get(0).generic(), "注入候选非通用项");
        assertNull(c.get(0).precondition(), "无前置条件（恒可发生区，交给 NanoJev 判定）");
        // 顺序保持入参顺序
        assertEquals("青禾集市", c.get(1).target());
        assertEquals("村口石牌坊", c.get(2).target());
        assertEquals("青禾客栈", c.get(3).target());
    }

    @Test
    @DisplayName("措辞按中心类型分化（茶馆闲聊/集市攀谈/村口转悠/客栈说话），且均含社交关键词供 crowd 归类 chat")
    void phrasingVariesByCenterType() {
        List<SpaceDecisionCandidates.SpaceCandidate> c = build("村东民居", true, 10, 4, null);

        assertTrue(c.get(0).action().contains("喝茶闲聊"), "茶馆 → 喝茶闲聊");
        assertTrue(c.get(1).action().contains("赶集") && c.get(1).action().contains("攀谈"), "集市 → 赶集攀谈");
        assertTrue(c.get(2).action().contains("转转") && c.get(2).action().contains("闲聊"), "村口 → 转悠闲聊");
        assertTrue(c.get(3).action().contains("和乡亲们说说话"), "客栈 → 坐下说话");
        // 普通 NPC 路径经 crowdTypeOfText 归类：社交措辞必须命中 chat（聊/攀谈/说话），
        // 否则会被误归为 drink/work（只迁移不社交）
        assertTrue(c.stream().allMatch(x -> x.action().contains("聊")
                        || x.action().contains("攀谈") || x.action().contains("说话")),
                "全部社交候选含 chat 语义关键词");
        // 无权重不加后缀
        assertEquals(c.get(0).action(), c.get(0).questionText());
        assertNull(c.get(0).weight());
    }

    @Test
    @DisplayName("门 ①：身边已有人（非独处）→ 不提供聚集候选（可就地攀谈，无需专程迁移）")
    void noCandidatesWhenNotAlone() {
        assertTrue(build("村东民居", false, 10, 4, 0.3).isEmpty());
    }

    @Test
    @DisplayName("门 ②：深夜（23-6）→ 不提供聚集候选（居民入睡，不再四处串门）")
    void noCandidatesInDeepNight() {
        assertTrue(build("村东民居", true, 23, 4, 0.3).isEmpty());
        assertTrue(build("村东民居", true, 2, 4, 0.3).isEmpty());
        assertTrue(build("村东民居", true, 5, 4, 0.3).isEmpty());
        // 边界：22 点与 6 点不算深夜
        assertFalse(build("村东民居", true, 22, 4, 0.3).isEmpty());
        assertFalse(build("村东民居", true, 6, 4, 0.3).isEmpty());
    }

    @Test
    @DisplayName("门 ③：已在社交中心 → 不提供候选（就地成为他人聚集目标，不做无意义迁移）")
    void noCandidatesWhenAlreadyAtSocialCenter() {
        assertTrue(build("青禾茶馆", true, 10, 4, 0.3).isEmpty());
        // 地点名快照带前缀后缀（双向包含口径）
        assertTrue(build("青禾村·青禾茶馆", true, 10, 4, 0.3).isEmpty());
    }

    @Test
    @DisplayName("上限：maxCandidates 限制产出条数（0=不限，仍受键序前 N 个中心约束）")
    void respectsMaxCandidates() {
        assertEquals(2, build("村东民居", true, 10, 2, 0.3).size());
        assertEquals(4, build("村东民居", true, 10, 0, 0.3).size());
    }

    @Test
    @DisplayName("空中心表/空名 → 空候选；同名中心去重")
    void emptyAndDuplicateCenters() {
        assertTrue(SocialGatheringCandidates.candidates(List.of(), "村东民居", true, 10, 23, 6, 4, 0.3).isEmpty());
        assertTrue(SocialGatheringCandidates.candidates(null, "村东民居", true, 10, 23, 6, 4, 0.3).isEmpty());
        List<SpaceDecisionCandidates.SpaceCandidate> dup = SocialGatheringCandidates.candidates(
                List.of("青禾茶馆", "青禾茶馆", " "), "村东民居", true, 10, 23, 6, 4, 0.3);
        assertEquals(1, dup.size());
    }

    @Test
    @DisplayName("时刻未知（null）→ 按非深夜处理；当前地点未知 → 不排除任何中心")
    void unknownHourAndLocation() {
        assertEquals(4, build(null, true, null, 4, 0.3).size());
    }

    @Test
    @DisplayName("深夜判定口径对齐 RoleplayService（h≥start ∪ h<end，支持跨午夜）")
    void deepNightBoundary() {
        assertTrue(SocialGatheringCandidates.isDeepNight(23, 23, 6));
        assertTrue(SocialGatheringCandidates.isDeepNight(0, 23, 6));
        assertTrue(SocialGatheringCandidates.isDeepNight(5, 23, 6));
        assertFalse(SocialGatheringCandidates.isDeepNight(6, 23, 6));
        assertFalse(SocialGatheringCandidates.isDeepNight(12, 23, 6));
        assertFalse(SocialGatheringCandidates.isDeepNight(22, 23, 6));
    }
}