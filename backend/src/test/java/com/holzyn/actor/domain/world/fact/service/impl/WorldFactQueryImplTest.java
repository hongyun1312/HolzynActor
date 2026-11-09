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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事实派生读模型（§4.6）。
 *
 * <p>提示词摘要格式是<b>契约</b>：同 gameSecond、同组事实必须逐字节一致（缓存命中率前提），
 * 因此这里用字符串全等断言，而不是 contains。</p>
 */
class WorldFactQueryImplTest {

    private ActorWorldFactRepository factRepository;
    private ActorWorldFactKnowledgeRepository knowledgeRepository;
    private WorldFactQueryImpl query;

    @BeforeEach
    void setUp() {
        factRepository = mock(ActorWorldFactRepository.class);
        knowledgeRepository = mock(ActorWorldFactKnowledgeRepository.class);
        query = new WorldFactQueryImpl(factRepository, knowledgeRepository);
    }

    @Test
    @DisplayName("digestForPrompt：固定格式（条目+来源注脚+剩余条数+状态版本）逐字节稳定")
    void digestFormatIsStable() {
        when(knowledgeRepository.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                eq(1L), eq("npc:3"), any(PageRequest.class)))
                .thenReturn(List.of(knowledge(1L, "own"), knowledge(2L, "perceive"),
                        knowledge(3L, "told", "npc:9"), knowledge(4L, "rumor")));
        when(factRepository.findAllById(any())).thenReturn(List.of(
                fact(1L, "shop:1", "我的铺子", "营业"),
                fact(2L, "well:1", null, "水位下降"),
                fact(3L, "contract:7", "婚约", "已定"),
                fact(4L, "secret:2", "传闻", "灵脉异动")));
        when(knowledgeRepository.countByProjectIdAndKnowerRef(1L, "npc:3")).thenReturn(6L);
        when(factRepository.maxVersion(1L)).thenReturn(7L);

        String digest = query.digestForPrompt(1L, "npc:3", 500L, 8);

        String expected = """
                【我所知道的事】
                - 我的铺子：营业（我自己的事）
                - well:1：水位下降（我今天亲眼看见）
                - 婚约：已定（听npc:9说的）
                - 传闻：灵脉异动（听说，未必准）
                - 还有 2 件事你记着
                [世界状态版本 gs500/v7]""";
        assertEquals(expected, digest, "提示词摘要必须逐字节稳定");
    }

    @Test
    @DisplayName("digestForPrompt：无认知/非法入参返回空串，不拼状态版本")
    void digestEmptyWhenNothingKnown() {
        when(knowledgeRepository.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                anyLong(), any(), any(PageRequest.class))).thenReturn(List.of());
        assertEquals("", query.digestForPrompt(1L, "npc:3", 500L, 8));
        assertEquals("", query.digestForPrompt(1L, " ", 500L, 8));
        assertEquals("", query.digestForPrompt(1L, "npc:3", 500L, 0));
        assertEquals("", query.digestForPrompt(null, "npc:3", 500L, 8));
    }

    @Test
    @DisplayName("knownBy：只在有效期内的事实可见（过期/未生效/非 active 过滤）")
    void knownByFiltersValidity() {
        when(knowledgeRepository.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                eq(1L), eq("npc:3"), any(PageRequest.class)))
                .thenReturn(List.of(knowledge(1L, "own"), knowledge(2L, "own"), knowledge(3L, "own")));
        ActorWorldFact active = fact(1L, "shop:1", "铺子", "营业");
        active.setStatus("active");
        active.setValidFromGameSecond(100L);
        active.setValidToGameSecond(900L);
        ActorWorldFact expired = fact(2L, "shop:2", "旧铺", "已拆");
        expired.setStatus("expired");
        ActorWorldFact future = fact(3L, "shop:3", "新铺", "未开张");
        future.setValidFromGameSecond(600L);
        when(factRepository.findAllById(any())).thenReturn(List.of(active, expired, future));

        List<ActorWorldFact> known = query.knownBy(1L, "npc:3", 500L, 10);

        assertEquals(1, known.size());
        assertEquals("shop:1", known.get(0).getFactKey());
    }

    @Test
    @DisplayName("knows：事实存在且认知行存在才为 true；事实不存在为 false")
    void knowsRequiresFactAndKnowledge() {
        ActorWorldFact fact = fact(1L, "shop:1", "铺子", "营业");
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(fact));
        when(knowledgeRepository.findByProjectIdAndFactIdAndKnowerRef(1L, 1L, "npc:3"))
                .thenReturn(Optional.of(knowledge(1L, "own")));
        assertTrue(query.knows(1L, "shop:1", "npc:3"));

        when(knowledgeRepository.findByProjectIdAndFactIdAndKnowerRef(1L, 1L, "npc:4"))
                .thenReturn(Optional.empty());
        assertFalse(query.knows(1L, "shop:1", "npc:4"));

        when(factRepository.findByProjectIdAndFactKey(1L, "missing")).thenReturn(Optional.empty());
        assertFalse(query.knows(1L, "missing", "npc:3"));
        assertFalse(query.knows(1L, " ", "npc:3"));
    }

    @Test
    @DisplayName("stateVersion/maxVersion：状态版本固定为 gs{秒}/v{最大版本}")
    void stateVersionFormat() {
        when(factRepository.maxVersion(1L)).thenReturn(12L);
        assertEquals(12L, query.maxVersion(1L));
        assertEquals("gs500/v12", query.stateVersion(1L, 500L));
        assertEquals(0L, query.maxVersion(null));
        assertEquals("gs0/v0", query.stateVersion(null, 0L));
    }

    @Test
    @DisplayName("hasFactDueWithin：只对窗口内到期且已知的 active 事实返回 true")
    void hasFactDueWithinUsesKnowledgeAndWindow() {
        when(knowledgeRepository.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                eq(1L), eq("npc:3"), any(PageRequest.class)))
                .thenReturn(List.of(knowledge(1L, "told"), knowledge(2L, "own")));
        ActorWorldFact due = fact(1L, "contract:due", "短约", "一小时内还钱");
        due.setValidFromGameSecond(100L);
        due.setValidToGameSecond(900L);
        ActorWorldFact expired = fact(2L, "contract:old", "旧约", "已过期");
        expired.setValidFromGameSecond(100L);
        expired.setValidToGameSecond(400L);
        when(factRepository.findAllById(any())).thenReturn(List.of(due, expired));

        assertTrue(query.hasFactDueWithin(1L, "npc:3", 500L, 400L),
                "due=900, gs=500, window=400 → 命中");
        assertFalse(query.hasFactDueWithin(1L, "npc:3", 500L, 399L),
                "窗口不够 → 不命中");
        assertFalse(query.hasFactDueWithin(1L, "npc:3", 500L, 0L), "非法窗口");
        assertFalse(query.hasFactDueWithin(null, "npc:3", 500L, 400L), "非法项目");
        // 因果挂接：最近到期的一条（due=900 比已过期/远期更近）
        assertEquals("contract:due", query.nearestDueWithin(1L, "npc:3", 500L, 400L).getFactKey());
        assertNull(query.nearestDueWithin(1L, "npc:3", 500L, 399L), "窗口不够 → 无最近事实");
        assertNull(query.nearestDueWithin(null, "npc:3", 500L, 400L), "非法项目");
    }

    // ==================== 辅助 ====================

    private static ActorWorldFactKnowledge knowledge(Long factId, String sourceType) {
        return knowledge(factId, sourceType, null);
    }

    private static ActorWorldFactKnowledge knowledge(Long factId, String sourceType, String sourceRef) {
        ActorWorldFactKnowledge row = new ActorWorldFactKnowledge();
        row.setFactId(factId);
        row.setKnowerRef("npc:3");
        row.setSourceType(sourceType);
        row.setSourceRef(sourceRef);
        row.setConfidence(100);
        return row;
    }

    private static ActorWorldFact fact(Long id, String factKey, String name, String stateText) {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(id);
        fact.setProjectId(1L);
        fact.setFactKey(factKey);
        fact.setName(name);
        fact.setStateText(stateText);
        fact.setStatus("active");
        fact.setVisibility("public");
        fact.setAuthority("brain");
        fact.setVersion(1);
        return fact;
    }
}
