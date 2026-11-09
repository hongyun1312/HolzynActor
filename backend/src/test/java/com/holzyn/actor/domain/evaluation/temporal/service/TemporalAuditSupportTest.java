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

package com.holzyn.actor.domain.evaluation.temporal.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间性审计纯函数单测：固定 JSD/置换/秩相关/动作分类口径，
 * 防止后续改阈值时「报告数字变了但没人知道为什么」。
 */
class TemporalAuditSupportTest {

    @Test
    void jsdIsZeroForSameDistributionAndLargeForDisjoint() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("移动", 100);
        a.put("交谈", 100);
        assertEquals(0.0, TemporalAuditSupport.jsd(a, a), 1e-9);

        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("劳作", 100);
        b.put("等待", 100);
        // 有 Laplace 平滑（+1/键）：即使支撑集完全不相交，JSD 也不会精确到 1，故取 0.4 为下界
        assertTrue(TemporalAuditSupport.jsd(a, b) > 0.4, "完全不相交的分布 JSD 应显著偏大");
    }

    @Test
    void permutationDetectsGroupDifference() {
        List<String> labels = new ArrayList<>();
        List<Boolean> group = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            labels.add("移动");
            group.add(false);
        }
        for (int i = 0; i < 12; i++) {
            labels.add("交谈");
            group.add(true);
        }
        double p = TemporalAuditSupport.permutationP(labels, group, 2000, 42L);
        assertTrue(p < 0.05, "两组动作完全分离时置换检验应拒绝原假设，实际 p=" + p);
    }

    @Test
    void permutationKeepsNullForIdenticalGroups() {
        List<String> labels = new ArrayList<>();
        List<Boolean> group = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            labels.add(i % 2 == 0 ? "移动" : "交谈");
            group.add(i < 12);
        }
        double p = TemporalAuditSupport.permutationP(labels, group, 1000, 7L);
        assertTrue(p > 0.2, "分组与动作无关时不应显著，实际 p=" + p);
    }

    @Test
    void spearmanIsNegativeWhenAgeGrowsAndScoreFalls() {
        double[] ages = {1, 3, 6, 12, 24, 48, 72, 168, 336, 720};
        double[] scores = new double[ages.length];
        for (int i = 0; i < ages.length; i++) {
            scores[i] = Math.exp(-ages[i] / 24.0);
        }
        double rho = TemporalAuditSupport.spearman(ages, scores);
        assertTrue(rho < -0.95, "年龄越大得分越低时 ρ 应接近 -1，实际 " + rho);
    }

    @Test
    void classifyActionCoversFiveTypes() {
        assertEquals("移动", TemporalAuditSupport.classifyAction("前往镇口查看动静"));
        // 「睡去」不是位移：裸「去」曾把这类小动作误判成移动，连带虚高
        assertEquals("等待", TemporalAuditSupport.classifyAction("夜半醒来，听着屋外风声又迷糊睡去"));
        assertEquals("交谈", TemporalAuditSupport.classifyAction("和铁匠说了几句"));
        assertEquals("劳作", TemporalAuditSupport.classifyAction("修补屋顶"));
        assertEquals("等待", TemporalAuditSupport.classifyAction("在门口歇息"));
        assertEquals("延续", TemporalAuditSupport.classifyAction("继续收拾摊位"));
        assertEquals("其它", TemporalAuditSupport.classifyAction("沉思"));
    }

    @Test
    void bucketAndDayNightUseGameSeconds() {
        long noon = 12 * 3600L;
        assertEquals("12-16", TemporalAuditSupport.bucketOf(noon));
        assertEquals("day", TemporalAuditSupport.dayNight(noon));
        assertEquals("night", TemporalAuditSupport.dayNight(2 * 3600L));
        assertEquals("第1日 12:00", TemporalAuditSupport.timeLabel(noon));
    }

    @Test
    void bigramJaccardRewardsOverlap() {
        double high = TemporalAuditSupport.bigramJaccard("前往铁匠铺修补锄头", "去铁匠铺修补锄头");
        double low = TemporalAuditSupport.bigramJaccard("前往铁匠铺修补锄头", "在家睡觉");
        assertTrue(high > low);
        assertEquals(0.0, TemporalAuditSupport.bigramJaccard(null, "在家睡觉"), 1e-9);
    }
}
