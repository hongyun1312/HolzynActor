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

package com.holzyn.actor.domain.observability.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.observability.vo.GlobalSearchVO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobalSearchService 单元测试（观测域·只读端点的行为断言）。
 *
 * <p>职责：锁定「五类分组与顺序 / 类型过滤与非法类型回退 / 空关键词与超长关键词 /
 * limit 截断标记 / 单类型失败隔离 / 摘要截断 / 跳转路径 / 条数上限下传」。</p>
 */
class GlobalSearchServiceTest {

    private ActorCharacterRepository characterRepository;
    private WorldLocationRepository locationRepository;
    private ActorEventRepository eventRepository;
    private ActorKnowledgeDocRepository knowledgeRepository;
    private ActorProjectRepository projectRepository;
    private GlobalSearchService service;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        eventRepository = Mockito.mock(ActorEventRepository.class);
        knowledgeRepository = Mockito.mock(ActorKnowledgeDocRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        service = new GlobalSearchService(characterRepository, locationRepository, eventRepository,
                knowledgeRepository, projectRepository);
    }

    /** 造一个角色 */
    private ActorCharacter character(Long id, String name, String detail) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setType("special");
        c.setTitle("青禾村村民");
        c.setOccupation("药铺掌柜");
        c.setDetail(detail);
        return c;
    }

    /** 造一个地点 */
    private ActorWorldLocation location(Long id, String name, String intro) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        l.setType("outdoor");
        l.setIntro(intro);
        return l;
    }

    /** 造一个事件 */
    private ActorEvent event(Long id, String title, String content) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setTitle(title);
        e.setContent(content);
        e.setGameHour(48L);
        return e;
    }

    /** 造一个知识文档 */
    private ActorKnowledgeDoc knowledge(Long id, String title, String content) {
        ActorKnowledgeDoc d = new ActorKnowledgeDoc();
        d.setId(id);
        d.setTitle(title);
        d.setContent(content);
        return d;
    }

    /** 造一个项目 */
    private ActorProject project(Long id, String name, String summary) {
        ActorProject p = new ActorProject();
        p.setId(id);
        p.setName(name);
        p.setSummary(summary);
        p.setCode("qinghe");
        return p;
    }

    /** 五类全命中：分组顺序固定为 character → location → event → knowledge → project */
    @Test
    void returnsGroupedHitsInTypeOrder() {
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(character(1L, "沈砚秋", "青禾村药铺掌柜")));
        Mockito.when(locationRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(location(2L, "药铺", "村东的药铺")));
        Mockito.when(eventRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(event(3L, "药铺进货", "沈砚秋去了药铺")));
        Mockito.when(knowledgeRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(knowledge(4L, "药铺规矩", "药铺的规矩")));
        Mockito.when(projectRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(project(5L, "青禾村·测试村庄", "药铺林立的村庄")));

        GlobalSearchVO vo = service.search(5L, "药铺", null, null);

        assertEquals(5, vo.total());
        assertEquals(List.of("character", "location", "event", "knowledge", "project"), vo.types());
        assertEquals("character", vo.groups().get(0).type());
        assertEquals("角色", vo.groups().get(0).label());
        assertEquals(1, vo.groups().get(0).count());
        assertEquals(1L, vo.groups().get(0).items().get(0).id());
        assertEquals("沈砚秋", vo.groups().get(0).items().get(0).title());
        assertEquals("/world/5/characters", vo.groups().get(0).items().get(0).route());
        assertEquals("/world/5/world", vo.groups().get(1).items().get(0).route());
        assertEquals("/world/5/timeline", vo.groups().get(2).items().get(0).route());
        assertEquals("/world/5/knowledge", vo.groups().get(3).items().get(0).route());
        assertEquals("/world/5/dashboard", vo.groups().get(4).items().get(0).route());
        assertFalse(vo.truncated());
    }

    /** 类型过滤：只查指定类型，其余仓库不被调用 */
    @Test
    void typesFilterRestrictsSearch() {
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(character(1L, "沈砚秋", null)));

        GlobalSearchVO vo = service.search(5L, "沈", "character", null);

        assertEquals(List.of("character"), vo.types());
        Mockito.verify(locationRepository, Mockito.never())
                .searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any());
        Mockito.verify(eventRepository, Mockito.never())
                .searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }

    /** 非法类型字面量 → 回退为「全部类型」（不让用户因为拼错类型而搜不到东西） */
    @Test
    void unknownTypesFallBackToAll() {
        GlobalSearchVO vo = service.search(5L, "沈", "npc,foobar", null);
        // 全部仓库被调用（返回空 → 无分组），不抛异常
        Mockito.verify(characterRepository).searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any());
        Mockito.verify(eventRepository).searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any());
        assertEquals(0, vo.total());
        assertTrue(vo.groups().isEmpty());
    }

    /** 空白关键词 → 400 业务异常 */
    @Test
    void blankQueryRejected() {
        assertThrows(BizException.class, () -> service.search(5L, null, null, null));
        assertThrows(BizException.class, () -> service.search(5L, "   ", null, null));
    }

    /** 关键词两侧空白被去除；超长关键词截断到 64 字符（不报错） */
    @Test
    void keywordIsTrimmedAndTruncated() {
        GlobalSearchVO vo = service.search(5L, "  沈砚秋  ", null, null);
        assertEquals("沈砚秋", vo.q());

        String longQ = "甲".repeat(200);
        GlobalSearchVO vo2 = service.search(5L, longQ, "character", null);
        assertEquals(64, vo2.q().length());
    }

    /** limit 生效：单类型条数上限 8，并被总配额截断 */
    @Test
    void limitCapsTotalAndMarksTruncated() {
        List<ActorCharacter> many = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> character((long) i, "角色" + i, null))
                .collect(Collectors.toList());
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(many);

        GlobalSearchVO capped = service.search(5L, "角色", "character", 3);
        assertEquals(3, capped.groups().get(0).count());
        assertTrue(capped.truncated(), "命中数用满配额 → truncated=true");

        GlobalSearchVO perType = service.search(5L, "角色", "character", 50);
        assertEquals(GlobalSearchService.DEFAULT_PER_TYPE, perType.groups().get(0).count(),
                "单类型最多 8 条（避免单类型淹没其它类型）");
        assertFalse(perType.truncated());
    }

    /** 每类型条数上限被下传给仓库（Pageable 必须带 limit，不得全表加载） */
    @Test
    void pageableLimitPassedDown() {
        service.search(5L, "角色", "character", 2);
        Mockito.verify(characterRepository).searchByKeyword(Mockito.eq(5L), Mockito.eq("角色"),
                Mockito.argThat((Pageable p) -> p != null && p.getPageSize() == 2));
    }

    /** 单类型仓库异常 → 只丢该组，其余类型照常返回 */
    @Test
    void singleTypeFailureIsIsolated() {
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("character table locked"));
        Mockito.when(eventRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(event(9L, "药铺进货", null)));

        GlobalSearchVO vo = service.search(5L, "药铺", null, null);

        assertEquals(List.of("event"), vo.types(), "角色组失败被隔离，事件组仍返回");
        assertEquals(1, vo.total());
    }

    /** 摘要：去换行 + 超长截断（≤120 + 省略号）；空文本 → null */
    @Test
    void snippetIsFlattenedAndTruncated() {
        // title 置空以逼出 detail 作为摘要（有 title 时优先用 title）
        ActorCharacter c = character(1L, "沈砚秋", "第一行\n第二行\t第三行 " + "字".repeat(300));
        c.setTitle(null);
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(c));
        Mockito.when(locationRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(location(2L, "药铺", null)));

        GlobalSearchVO vo = service.search(5L, "药铺", null, null);
        String snippet = vo.groups().get(0).items().get(0).snippet();
        assertNotNull(snippet);
        assertFalse(snippet.contains("\n"), "换行已压平");
        assertTrue(snippet.length() <= 121, "截断到 120 + 省略号，实际 " + snippet.length());
        assertTrue(snippet.endsWith("…"));
        // 无简介 → snippet 为 null（不是空串）
        assertEquals(null, vo.groups().get(1).items().get(0).snippet());
    }

    /** 元信息拼接：空值跳过、去重 */
    @Test
    void metaSkipsBlanks() {
        Mockito.when(characterRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(character(1L, "沈砚秋", null)));
        GlobalSearchVO vo = service.search(5L, "沈", "character", null);
        assertEquals("特殊角色 · 药铺掌柜", vo.groups().get(0).items().get(0).meta());

        // 项目类型：meta 取 code
        Mockito.when(projectRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(project(5L, "青禾村", null)));
        GlobalSearchVO vo2 = service.search(5L, "青禾", "project", null);
        assertEquals("qinghe", vo2.groups().get(0).items().get(0).meta());
    }

    /** 无命中 → 空分组 + total 0 + truncated false */
    @Test
    void noHitsProducesEmptyResult() {
        GlobalSearchVO vo = service.search(5L, "不存在的词", null, null);
        assertEquals(0, vo.total());
        assertTrue(vo.groups().isEmpty());
        assertFalse(vo.truncated());
        assertEquals(5L, vo.projectId());
    }

    /** 事件元信息：无 gameHour 时退回地点名 */
    @Test
    void eventMetaFallsBackToLocation() {
        ActorEvent e = new ActorEvent();
        e.setId(1L);
        e.setTitle("集市");
        e.setLocationName("村口石牌坊");
        Mockito.when(eventRepository.searchByKeyword(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(e));
        GlobalSearchVO vo = service.search(5L, "集市", "event", null);
        assertEquals("村口石牌坊", vo.groups().get(0).items().get(0).meta());
    }
}
