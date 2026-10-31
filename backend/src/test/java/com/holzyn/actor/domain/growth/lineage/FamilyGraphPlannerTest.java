/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.growth.lineage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开局家族图规划器单测（M2 验收 A1–A3）。
 *
 * <p>守的是"家族结构不能荒谬"这条底线：同性婚配、未成年婚配、父母比子女还小、
 * 夫妻变成父女、死人在家谱里继续结婚——这些一旦漏进世界库，后面所有剧情都会歪。</p>
 *
 * <p>纯函数测试：不加载 Spring、不碰数据库，输入快照直接断言输出。</p>
 */
class FamilyGraphPlannerTest {

    /** 世界秒/年（与 GrowthProperties 默认一致）。 */
    private static final long YEAR = 31_536_000L;
    /** 世界当前秒（一个很靠后的时刻，便于验证"成婚/出生时刻"落在过去）。 */
    private static final long NOW = 32_628_096_000L;

    /** 造一个角色快照。 */
    private static FamilyGraphPlanner.CharSnap c(long id, String name, String gender, int age) {
        return new FamilyGraphPlanner.CharSnap(id, "npc:" + id, name, gender, age, false, 2);
    }

    /** 三代同堂的样本：沈家（祖辈夫妻 + 父辈 + 孙辈）＋跨族联姻的林小满。 */
    private static List<FamilyGraphPlanner.CharSnap> sampleFamily() {
        return List.of(
                c(1, "沈伯远", "男", 68),
                c(2, "阿苔", "女", 62),
                c(3, "沈明烛", "男", 34),
                c(4, "沈小萤", "女", 10),
                c(5, "林小满", "女", 30));
    }

    /** 同族关系：把「阿苔」并进沈家（姓氏不同，只能靠"同族"这条边聚类）。 */
    private static List<FamilyGraphPlanner.RelSnap> sampleRelations() {
        return List.of(new FamilyGraphPlanner.RelSnap("同族", "npc:1", "npc:2"));
    }

    /** 按默认婚龄/年秒跑一次规划。 */
    private static FamilyGraphPlanner.Plan plan(List<FamilyGraphPlanner.CharSnap> chars,
                                                List<FamilyGraphPlanner.RelSnap> rels) {
        return FamilyGraphPlanner.plan(chars, rels, NOW, YEAR, 18, 33L);
    }

    @Test
    @DisplayName("三代家族：祖辈成婚、父辈成婚、子女挂到真实父母名下")
    void plansThreeGenerationFamily() {
        FamilyGraphPlanner.Plan plan = plan(sampleFamily(), sampleRelations());
        // 2 对婚姻：沈伯远×阿苔（同族同代）、沈明烛×林小满（跨族联姻）
        assertEquals(2, plan.marriages().size(), "应配出 2 对夫妻，实际：" + plan.marriages());
        // 4 条亲子边：沈明烛的父母 2 条 + 沈小萤的父母 2 条
        assertEquals(4, plan.parentEdges().size(), "应有 4 条亲子边，实际：" + plan.parentEdges());
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (FamilyGraphPlanner.ParentPlan p : plan.parentEdges()) {
            parentOf.put(p.childRef() + "@" + p.relation(), p.parentRef());
        }
        assertEquals("npc:1", parentOf.get("npc:3@father"), "沈明烛的父亲应是沈伯远");
        assertEquals("npc:2", parentOf.get("npc:3@mother"), "沈明烛的母亲应是阿苔");
        assertEquals("npc:3", parentOf.get("npc:4@father"), "沈小萤的父亲应是沈明烛");
        assertEquals("npc:5", parentOf.get("npc:4@mother"), "跨族联姻的妻方也该是母亲（不能只有爹没有娘）");
    }

    @Test
    @DisplayName("不变量：异性婚配、婚龄 ≥18、年龄差 ≤12、父母比子女大 ≥14")
    void respectsMarriageAndParentInvariants() {
        List<FamilyGraphPlanner.CharSnap> chars = List.of(
                c(1, "沈伯远", "男", 68), c(2, "阿苔", "女", 62), c(3, "沈明烛", "男", 34),
                c(4, "沈小萤", "女", 10), c(5, "林小满", "女", 30), c(6, "陈伯远", "男", 41),
                c(7, "白芷", "女", 24), c(8, "阿澈", "男", 22), c(9, "苏晚晴", "女", 29),
                c(10, "老周", "男", 66), c(11, "铁柱", "男", 45), c(12, "小石头", "男", 8));
        FamilyGraphPlanner.Plan plan = plan(chars, sampleRelations());
        Map<String, FamilyGraphPlanner.CharSnap> byRef = new LinkedHashMap<>();
        for (FamilyGraphPlanner.CharSnap ch : chars) {
            byRef.put(ch.ref(), ch);
        }
        for (FamilyGraphPlanner.MarriagePlan m : plan.marriages()) {
            FamilyGraphPlanner.CharSnap h = byRef.get(m.husbandRef());
            FamilyGraphPlanner.CharSnap w = byRef.get(m.wifeRef());
            assertTrue(h != null && w != null, "婚姻边引用了未知角色：" + m);
            assertEquals("男", h.gender(), "夫方必须是男：" + h.name());
            assertEquals("女", w.gender(), "妻方必须是女：" + w.name());
            assertTrue(h.age() >= 18, "夫方婚龄越界：" + h.name() + " " + h.age());
            assertTrue(w.age() >= 18, "妻方婚龄越界：" + w.name() + " " + w.age());
            assertTrue(Math.abs(h.age() - w.age()) <= FamilyGraphPlanner.MAX_SPOUSE_AGE_GAP,
                    "夫妻年龄差越界：" + h.name() + "(" + h.age() + ") × " + w.name() + "(" + w.age() + ")");
        }
        for (FamilyGraphPlanner.ParentPlan p : plan.parentEdges()) {
            FamilyGraphPlanner.CharSnap parent = byRef.get(p.parentRef());
            FamilyGraphPlanner.CharSnap child = byRef.get(p.childRef());
            assertTrue(parent != null && child != null, "亲子边引用了未知角色：" + p);
            assertTrue(parent.age() - child.age() >= FamilyGraphPlanner.MIN_PARENT_AGE_GAP,
                    "父母年龄差不足：" + parent.name() + "(" + parent.age() + ") → "
                            + child.name() + "(" + child.age() + ")");
            assertFalse(p.parentRef().equals(p.childRef()), "不能自己当自己的父母");
        }
    }

    @Test
    @DisplayName("确定性：同一输入两次规划结果完全一致（补齐可重入的前提）")
    void isDeterministic() {
        FamilyGraphPlanner.Plan a = plan(sampleFamily(), sampleRelations());
        FamilyGraphPlanner.Plan b = plan(sampleFamily(), sampleRelations());
        assertEquals(a.marriages(), b.marriages(), "婚姻边不一致 → 重复补齐会长出两套亲戚");
        assertEquals(a.parentEdges(), b.parentEdges(), "亲子边不一致 → 重复补齐会长出两套亲戚");
    }

    @Test
    @DisplayName("对抗关系不撮合：只剩两个互相敌视的人时一对也不配")
    void neverPairsHostile() {
        List<FamilyGraphPlanner.CharSnap> chars = List.of(c(1, "沈伯远", "男", 40), c(2, "阿苔", "女", 38));
        List<FamilyGraphPlanner.RelSnap> rels = List.of(new FamilyGraphPlanner.RelSnap("暗中对抗", "npc:1", "npc:2"));
        FamilyGraphPlanner.Plan plan = plan(chars, rels);
        assertTrue(plan.marriages().isEmpty(), "敌对关系不该被配成夫妻：" + plan.marriages());
    }

    @Test
    @DisplayName("已故角色不进家谱（不配婚、不当父母、不挂父母）")
    void excludesDeceased() {
        List<FamilyGraphPlanner.CharSnap> chars = List.of(
                new FamilyGraphPlanner.CharSnap(1L, "npc:1", "沈伯远", "男", 68, true, 2),
                c(3, "沈明烛", "男", 34));
        FamilyGraphPlanner.Plan plan = plan(chars, List.of());
        assertTrue(plan.marriages().isEmpty());
        assertTrue(plan.parentEdges().isEmpty(), "亡者不能当父母：" + plan.parentEdges());
        assertEquals(1, plan.families().size(), "亡者也要从家族簇里剔除");
    }

    @Test
    @DisplayName("空输入安全返回（世界刚建、还没有角色）")
    void handlesEmptyInput() {
        FamilyGraphPlanner.Plan plan = plan(List.of(), List.of());
        assertTrue(plan.isEmpty());
        assertTrue(plan.families().isEmpty());
    }

    @Test
    @DisplayName("姓氏聚类与代际分层口径")
    void surnameAndBandRules() {
        assertEquals("沈", FamilyGraphPlanner.surnameOf("沈明烛"));
        assertEquals("欧阳", FamilyGraphPlanner.surnameOf("欧阳锋"));
        assertEquals("周", FamilyGraphPlanner.surnameOf("老周"), "「老X」是名号，姓是第二个字");
        assertEquals("异姓", FamilyGraphPlanner.surnameOf("阿澈"), "「阿」不是姓");
        assertEquals("异姓", FamilyGraphPlanner.surnameOf("小萤"), "「小」不是姓");
        assertEquals("异姓", FamilyGraphPlanner.surnameOf(""));
        assertEquals(0, FamilyGraphPlanner.bandOf(17));
        assertEquals(1, FamilyGraphPlanner.bandOf(FamilyGraphPlanner.BAND_PARENT));
        assertEquals(2, FamilyGraphPlanner.bandOf(FamilyGraphPlanner.BAND_ELDER));
    }
}
