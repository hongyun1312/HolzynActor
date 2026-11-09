package com.holzyn.actor.domain.growth.space;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「继续当前正在做的事」的地点改写。
 *
 * <p>保护两件事：①活动与地点不相容时，候选文本必须自己说出「此处不便、得先回去」——
 * 这是把空间事实写进选项，而不是只写在约束句里；②信息不足（地点未知/活动为空）时
 * 一律不改写，宁可保守也不编造。</p>
 */
class SpaceDecisionCandidatesPlaceTest {

    private static final String BASE = "继续当前正在做的事";

    private static List<SpaceDecisionCandidates.SpaceCandidate> candidates(String activity,
                                                                           String placeName,
                                                                           String category) {
        return SpaceDecisionCandidates.candidatesFrom(null, null, List.of(), false,
                activity, placeName, category);
    }

    @Test
    void keepsGenericWhenActivityFitsPlace() {
        List<SpaceDecisionCandidates.SpaceCandidate> cs = candidates("在铺子里打铁", "铁匠铺", "工坊");
        assertEquals(BASE, cs.get(0).action(), "打铁在工坊里是相容的，不该改写");
    }

    @Test
    void rewritesWhenActivityDoesNotFitPlace() {
        List<SpaceDecisionCandidates.SpaceCandidate> cs = candidates("在铺子里打铁", "阿珍家", "民居");
        String text = cs.get(0).action();
        assertFalse(BASE.equals(text), "在别人家打铁不该说「继续当前正在做的事」");
        assertTrue(text.contains("打铁"), "改写必须保留具体活动，模型才知道回哪儿去干什么");
        assertTrue(text.contains("得先回能做这件事的地方"), "必须点明此处不便");
        assertTrue(text.contains("阿珍家"), "带上地名，模型才知道自己此刻在哪");
    }

    @Test
    void noRewriteWhenPlaceUnknown() {
        assertEquals(BASE, candidates("打铁", null, null).get(0).action(),
                "地点未知时不改写（不猜）");
        assertEquals(BASE, candidates("打铁", "某处", "").get(0).action(),
                "类别为空时不改写（不猜）");
    }

    @Test
    void noRewriteWhenActivityBlank() {
        assertEquals(BASE, candidates(null, "阿珍家", "民居").get(0).action(),
                "活动为空时没有「当前的事」可继续，保持原文");
        assertEquals(BASE, candidates("   ", "阿珍家", "民居").get(0).action());
    }

    @Test
    void farmWorkNotAllowedIndoors() {
        String text = candidates("下地锄草", "阿珍家", "民居").get(0).action();
        assertTrue(text.contains("得先回能做这件事的地方"), "农活在民居里做不了");
        assertEquals(BASE, candidates("下地锄草", "西头水田", "农田").get(0).action(),
                "在农田里锄草是相容的");
    }

    @Test
    void shopWorkRequiresShopOrWorkshop() {
        assertEquals(BASE, candidates("看店卖货", "周家米行", "商铺").get(0).action());
        assertTrue(candidates("看店卖货", "镇中心广场", "公共").get(0).action()
                .contains("得先回能做这件事的地方"), "广场上没法看店");
    }

    @Test
    void unknownActivityDoesNotRewrite() {
        assertEquals(BASE, candidates("发呆", "镇中心广场", "公共").get(0).action(),
                "关键词未命中一律视为相容（宁可不改也不乱判）");
    }
}
