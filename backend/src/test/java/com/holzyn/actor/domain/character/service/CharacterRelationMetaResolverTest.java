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

package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 关系拓扑节点附加语义解析测试（UI V3.0 · 关系工作台「社会结构总览」）。
 *
 * <p>职责：验证「谁和谁是一拨人」按<b>世界内核真实归属</b>解析的六条纪律——</p>
 * <ol>
 *   <li><b>成员关系优先</b>：在组（active）成员关系压过 affiliation 文本兜底；</li>
 *   <li><b>主归属确定性</b>：多组时按组内角色优先级（leader&gt;elder&gt;core&gt;member&gt;affiliate），
 *       同级取组织 ID 小者——同一数据每次结果一致（前端圈子分区必须稳定）；</li>
 *   <li><b>文本兜底同源</b>：无成员关系时按 affiliation 等于/包含组织名命中，且排除自发成组（adhoc）；</li>
 *   <li><b>家族=血缘分量</b>：亲子 + 婚姻边连通分量，取分量多数姓氏为称谓（「沈氏一族」），
 *       ID 取分量最小角色 ID（同姓不同宗不合并）；</li>
 *   <li><b>不编造</b>：无归属 / 无谱系边一律 null，绝不为了画面齐整而猜分组；</li>
 *   <li><b>失败降级</b>：仓库异常 → 空 Map（拓扑接口绝不因缺数据而失败）。</li>
 * </ol>
 *
 * <p>所属模块：domain/character/service（测试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class CharacterRelationMetaResolverTest {

    @Mock
    private ActorMesoGroupRepository groupRepository;
    @Mock
    private ActorMesoMembershipRepository membershipRepository;
    @Mock
    private ActorGrowthLineageRepository lineageRepository;

    private CharacterRelationMetaResolver resolver;

    private static final long PROJECT_ID = 1L;
    private static final long SHEN = 1L;
    private static final long LIN = 2L;
    private static final long ZHAO = 3L;
    private static final long SHEN_SUI = 5L;

    @BeforeEach
    void setUp() {
        resolver = new CharacterRelationMetaResolver(groupRepository, membershipRepository, lineageRepository);
    }

    // ==================================================================================
    // 组织归属（圈子）
    // ==================================================================================

    @Test
    @DisplayName("成员关系优先于 affiliation 文本兜底：在组成员按成员关系定圈")
    void membershipWinsOverAffiliationText() {
        stubGroups(groupRow(10L, "村中宗族", "family"), groupRow(20L, "游侠散人", "community"));
        stubMemberships(membershipRow(SHEN, 10L, "core"));
        stubNoLineage();

        // 角色文本归属写成「游侠散人」，但成员关系在「村中宗族」→ 以成员关系为准
        CharacterRelationGraphVO.NodeMeta meta =
                resolver.resolve(PROJECT_ID, List.of(character(SHEN, "沈守拙", "游侠散人"))).get(SHEN);

        assertNotNull(meta);
        assertEquals(10L, meta.groupId());
        assertEquals("村中宗族", meta.groupName());
        assertEquals("family", meta.groupType());
        assertEquals("core", meta.groupRole());
    }

    @Test
    @DisplayName("主归属确定性：多组时按组内角色优先级，同级取组织 ID 小者")
    void primaryMembershipIsDeterministic() {
        stubGroups(groupRow(30L, "往来商帮", "guild"),
                groupRow(10L, "村中宗族", "family"),
                groupRow(20L, "行医世家", "guild"));
        stubMemberships(membershipRow(SHEN, 30L, "member"), membershipRow(SHEN, 20L, "member"),
                membershipRow(SHEN, 10L, "elder"));
        stubNoLineage();

        CharacterRelationGraphVO.NodeMeta meta =
                resolver.resolve(PROJECT_ID, List.of(character(SHEN, "沈守拙", null))).get(SHEN);
        assertEquals(10L, meta.groupId(), "elder 优先于 member");
        assertEquals("elder", meta.groupRole());
    }

    @Test
    @DisplayName("主归属确定性（同级）：组内角色相同时取组织 ID 小者")
    void primaryMembershipTieBreaksByGroupId() {
        stubGroups(groupRow(30L, "往来商帮", "guild"), groupRow(20L, "行医世家", "guild"));
        stubMemberships(membershipRow(SHEN, 30L, "member"), membershipRow(SHEN, 20L, "member"));
        stubNoLineage();

        assertEquals(20L, resolver.resolve(PROJECT_ID, List.of(character(SHEN, "沈守拙", null)))
                .get(SHEN).groupId());
    }

    @Test
    @DisplayName("文本兜底同源：affiliation 包含组织名即命中，自发成组（adhoc）不参与归属")
    void affiliationTextFallbackExcludesAdhoc() {
        // 列表刻意把 adhoc 排在前面：文本同时命中两者时，必须落到非 adhoc 的正式组织
        stubGroups(groupRow(99L, "沈氏旁支", "adhoc"), groupRow(10L, "村中宗族", "family"));
        stubNoMemberships();
        stubNoLineage();

        CharacterRelationGraphVO.NodeMeta hit = resolver
                .resolve(PROJECT_ID, List.of(character(SHEN, "沈守拙", "青禾村沈氏旁支·村中宗族"))).get(SHEN);
        assertEquals(10L, hit.groupId(), "adhoc 名称也命中文本，但自发成组不作为身份归属");
        assertEquals("family", hit.groupType());

        // 只命中 adhoc 名称 → 无归属（不编造）
        CharacterRelationGraphVO.NodeMeta miss = resolver
                .resolve(PROJECT_ID, List.of(character(ZHAO, "赵万金", "青禾村沈氏旁支"))).get(ZHAO);
        assertNull(miss.groupId());
    }

    @Test
    @DisplayName("不编造：无成员关系且文本不匹配 → groupId/groupName/groupRole 全 null")
    void noAffiliationMeansNoGroup() {
        stubGroups(groupRow(10L, "村中宗族", "family"));
        stubNoMemberships();
        stubNoLineage();

        CharacterRelationGraphVO.NodeMeta meta =
                resolver.resolve(PROJECT_ID, List.of(character(ZHAO, "赵万金", null))).get(ZHAO);
        assertNull(meta.groupId());
        assertNull(meta.groupName());
        assertNull(meta.groupRole(), "无成员关系时组内角色无意义，不得凭空填 member");
    }

    // ==================================================================================
    // 家族（血缘分量）
    // ==================================================================================

    @Test
    @DisplayName("家族=血缘分量：亲子 + 婚姻边连通，称谓取多数姓氏，ID 取分量最小角色 ID")
    void familyDerivedFromBloodAndMarriageEdges() {
        stubNoGroups();
        stubNoMemberships();
        stubLineage(edge(ActorGrowthLineage.EDGE_PARENT_CHILD, "npc:1", "npc:5"),
                edge(ActorGrowthLineage.EDGE_MARRIAGE, "npc:3", "npc:5"));

        List<ActorCharacter> chars = List.of(
                character(SHEN, "沈守拙", null),
                character(LIN, "林听竹", null),
                character(ZHAO, "赵万金", null),
                character(SHEN_SUI, "沈穗", null));
        Map<Long, CharacterRelationGraphVO.NodeMeta> out = resolver.resolve(PROJECT_ID, chars);

        assertEquals("fam-1", out.get(SHEN).familyId(), "分量代表=最小角色 ID（确定性）");
        assertEquals("fam-1", out.get(SHEN_SUI).familyId());
        assertEquals("fam-1", out.get(ZHAO).familyId(), "婚姻把两个姓氏连成一家");
        assertEquals("沈氏一族", out.get(SHEN).familyName(), "多数姓氏（沈 2 : 赵 1）");
        assertNull(out.get(LIN).familyId(), "无谱系边 → 无家族，不编造「一人一族」");
    }

    @Test
    @DisplayName("已终止婚姻（active=0）不再连家")
    void inactiveMarriageDoesNotJoinFamily() {
        stubNoGroups();
        stubNoMemberships();
        ActorGrowthLineage dead = edge(ActorGrowthLineage.EDGE_MARRIAGE, "npc:1", "npc:3");
        dead.setActive(0);
        stubLineage(dead);

        Map<Long, CharacterRelationGraphVO.NodeMeta> out = resolver.resolve(PROJECT_ID,
                List.of(character(SHEN, "沈守拙", null), character(ZHAO, "赵万金", null)));
        assertNull(out.get(SHEN).familyId());
        assertNull(out.get(ZHAO).familyId());
    }

    @Test
    @DisplayName("复姓识别：欧阳/司马等取两字姓")
    void surnameHandlesCompoundSurnames() {
        assertEquals("沈", CharacterRelationMetaResolver.surnameOf("沈守拙"));
        assertEquals("欧阳", CharacterRelationMetaResolver.surnameOf("欧阳锋"));
        assertEquals("司马", CharacterRelationMetaResolver.surnameOf("司马懿"));
        assertNull(CharacterRelationMetaResolver.surnameOf("  "));
        assertNull(CharacterRelationMetaResolver.surnameOf(null));
    }

    // ==================================================================================
    // 降级与档案速览
    // ==================================================================================

    @Test
    @DisplayName("档案速览随节点带出：头像/性别/年龄/阶段/归属/职业/所在地")
    void profileSnapshotIsCarried() {
        stubNoGroups();
        stubNoMemberships();
        stubNoLineage();

        ActorCharacter shen = character(SHEN, "沈守拙", "村中宗族");
        shen.setAvatarUrl("/uploads/avatar/shen.png");
        shen.setGender("男");
        shen.setAge(65);
        shen.setStage("老年");
        shen.setOccupation("守脉堂主");
        shen.setLocation("守脉堂");
        shen.setLocationId(7L);

        CharacterRelationGraphVO.NodeMeta meta = resolver.resolve(PROJECT_ID, List.of(shen)).get(SHEN);
        assertEquals("/uploads/avatar/shen.png", meta.avatarUrl());
        assertEquals("男", meta.gender());
        assertEquals(65, meta.age());
        assertEquals("老年", meta.stage());
        assertEquals("守脉堂主", meta.occupation());
        assertEquals("守脉堂", meta.location());
        assertEquals(7L, meta.locationId());
    }

    @Test
    @DisplayName("失败降级：组织仓库异常 → 空 Map，不抛异常（拓扑接口不得因缺数据而失败）")
    void degradesToEmptyMapOnRepositoryFailure() {
        when(groupRepository.findByProjectIdOrderByIdAsc(PROJECT_ID))
                .thenThrow(new IllegalStateException("db down"));

        Map<Long, CharacterRelationGraphVO.NodeMeta> out =
                resolver.resolve(PROJECT_ID, List.of(character(SHEN, "沈守拙", null)));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("空角色列表零查询：直接返回空 Map")
    void emptyCharactersShortCircuits() {
        assertTrue(resolver.resolve(PROJECT_ID, List.of()).isEmpty());
        assertTrue(resolver.resolve(PROJECT_ID, null).isEmpty());
    }

    // ==================================================================================
    // 桩与夹具
    // ==================================================================================

    /** 桩：项目组织列表。 */
    private void stubGroups(ActorMesoGroup... groups) {
        when(groupRepository.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(groups));
    }

    /** 桩：项目无组织。 */
    private void stubNoGroups() {
        when(groupRepository.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of());
    }

    /** 桩：项目在组成员关系。 */
    private void stubMemberships(ActorMesoMembership... rows) {
        when(membershipRepository.findByProjectIdAndStatusOrderByIdAsc(PROJECT_ID, "active"))
                .thenReturn(List.of(rows));
    }

    /** 桩：项目无在组成员关系。 */
    private void stubNoMemberships() {
        when(membershipRepository.findByProjectIdAndStatusOrderByIdAsc(PROJECT_ID, "active"))
                .thenReturn(List.of());
    }

    /** 桩：项目谱系边。 */
    private void stubLineage(ActorGrowthLineage... edges) {
        when(lineageRepository.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(edges));
    }

    /** 桩：项目无谱系边。 */
    private void stubNoLineage() {
        when(lineageRepository.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of());
    }

    /** 角色行。 */
    private static ActorCharacter character(long id, String name, String affiliation) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(PROJECT_ID);
        c.setName(name);
        c.setType("special");
        c.setAffiliation(affiliation);
        return c;
    }

    /** 组织行。 */
    private static ActorMesoGroup groupRow(long id, String name, String type) {
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(id);
        g.setProjectId(PROJECT_ID);
        g.setName(name);
        g.setType(type);
        g.setStatus("active");
        return g;
    }

    /** 成员关系行。 */
    private static ActorMesoMembership membershipRow(long actorId, long groupId, String role) {
        ActorMesoMembership m = new ActorMesoMembership();
        m.setProjectId(PROJECT_ID);
        m.setActorId(actorId);
        m.setGroupId(groupId);
        m.setActorRef("npc:" + actorId);
        m.setRole(role);
        m.setStatus("active");
        return m;
    }

    /** 谱系边。 */
    private static ActorGrowthLineage edge(String edgeType, String from, String to) {
        ActorGrowthLineage e = new ActorGrowthLineage();
        e.setProjectId(PROJECT_ID);
        e.setEdgeType(edgeType);
        e.setFromRef(from);
        e.setToRef(to);
        e.setActive(1);
        return e;
    }
}
