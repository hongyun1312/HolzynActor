package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.config.MesoProperties;
import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.repository.ActorMesoDirectiveRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 中观指令路由器单元测试（宏观社会演化系统 M6.5a，domain/world/service，同包）。
 * <p>职责：验证目标成员解析（组指令/全局指令）、有效指令 ttl 到期过滤、【组织处境】段组装
 * （角色/影响力/指令三通道摘要、长度预算）、指令摘要与角色显示名。</p>
 */
class MesoDirectiveRouterTest {

    private MesoProperties props;
    private ActorMesoMembershipRepository membershipRepository;
    private ActorMesoGroupRepository groupRepository;
    private ActorMesoDirectiveRepository directiveRepository;
    private MesoDirectiveRouter router;

    @BeforeEach
    void setUp() {
        props = new MesoProperties();
        membershipRepository = Mockito.mock(ActorMesoMembershipRepository.class);
        groupRepository = Mockito.mock(ActorMesoGroupRepository.class);
        directiveRepository = Mockito.mock(ActorMesoDirectiveRepository.class);
        router = new MesoDirectiveRouter(props, membershipRepository, groupRepository,
                directiveRepository, new ObjectMapper());
    }

    private ActorMesoMembership member(String ref, Long groupId, String role) {
        ActorMesoMembership m = new ActorMesoMembership();
        m.setActorRef(ref);
        m.setGroupId(groupId);
        m.setRole(role);
        m.setStatus("active");
        return m;
    }

    /** 组指令 → 仅组内 active 成员。 */
    @Test
    void groupDirectiveResolvesGroupMembers() {
        when(membershipRepository.findByGroupIdAndStatusOrderByIdAsc(5L, "active"))
                .thenReturn(List.of(member("special:1", 5L, "leader"), member("special:2", 5L, "core")));
        ActorMesoDirective d = new ActorMesoDirective();
        d.setGroupId(5L);
        List<String> actors = router.resolveTargetActors(100L, d);
        assertEquals(List.of("special:1", "special:2"), actors);
    }

    /** 全局指令 → 全部有组织的成员（跨组去重）。 */
    @Test
    void globalDirectiveResolvesAllOrganizedActors() {
        ActorMesoGroup g1 = new ActorMesoGroup();
        g1.setId(5L);
        g1.setStatus("active");
        ActorMesoGroup g2 = new ActorMesoGroup();
        g2.setId(6L);
        g2.setStatus("active");
        when(groupRepository.findByProjectIdAndStatusOrderByIdAsc(100L, "active"))
                .thenReturn(List.of(g1, g2));
        when(membershipRepository.findByGroupIdAndStatusOrderByIdAsc(5L, "active"))
                .thenReturn(List.of(member("special:1", 5L, "leader"), member("special:2", 5L, "core")));
        when(membershipRepository.findByGroupIdAndStatusOrderByIdAsc(6L, "active"))
                .thenReturn(List.of(member("special:2", 6L, "affiliate"), member("special:3", 6L, "core")));
        List<String> actors = router.resolveTargetActors(100L, new ActorMesoDirective());
        assertEquals(List.of("special:1", "special:2", "special:3"), actors, "跨组去重");
    }

    /** 有效指令：组指令 + 全局指令合并；ttl 到期过滤（惰性不写库）。 */
    @Test
    void activeDirectivesMergeAndFilterExpired() {
        ActorMesoDirective live = new ActorMesoDirective();
        live.setIssuedGameSecond(100L);
        live.setTtl(1000L); // 到期 1100
        ActorMesoDirective expired = new ActorMesoDirective();
        expired.setIssuedGameSecond(100L);
        expired.setTtl(10L); // 到期 110
        when(directiveRepository.findByProjectIdAndGroupIdAndStatusOrderByIdDesc(100L, 5L, "active"))
                .thenReturn(List.of(live, expired));
        ActorMesoDirective global = new ActorMesoDirective();
        global.setIssuedGameSecond(500L);
        global.setTtl(1000L);
        when(directiveRepository.findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(100L, "active"))
                .thenReturn(List.of(global));
        List<ActorMesoDirective> ds = router.activeDirectivesFor(100L, 5L, 900L);
        assertEquals(2, ds.size(), "过期指令被过滤，组指令+全局指令保留");
        assertTrue(ds.contains(live));
        assertTrue(ds.contains(global));
        assertFalse(ds.contains(expired));
    }

    /** ttl 到期判定：null ttl / null 时刻永不过期；issued+ttl < now → 过期。 */
    @Test
    void notExpiredSemantics() {
        ActorMesoDirective d = new ActorMesoDirective();
        assertTrue(MesoDirectiveRouter.notExpired(d, 999L), "ttl null 永不过期");
        d.setTtl(100L);
        assertTrue(MesoDirectiveRouter.notExpired(d, null), "now null 永不过期");
        d.setIssuedGameSecond(100L);
        assertTrue(MesoDirectiveRouter.notExpired(d, 200L), "200 < 100+100 → 未到期");
        assertFalse(MesoDirectiveRouter.notExpired(d, 201L), "201 ≥ 200 → 到期");
    }

    /** 无组织成员 → 段为空串。 */
    @Test
    void noMembershipReturnsEmpty() {
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(100L, "special:9", "active"))
                .thenReturn(List.of());
        assertEquals("", router.composePerceptionSegment(100L, "special:9", 900L));
    }

    /** 【组织处境】段：角色 + 影响力 + 指令三通道摘要；未启用返回空。 */
    @Test
    void perceptionSegmentComposesRoleInfluenceAndHint() {
        ActorMesoMembership m = member("special:1", 5L, "leader");
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(100L, "special:1", "active"))
                .thenReturn(List.of(m));
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(5L);
        g.setName("王氏宗族");
        g.setStatus("active");
        g.setInfluence(60.0);
        when(groupRepository.findById(5L)).thenReturn(java.util.Optional.of(g));
        ActorMesoDirective d = new ActorMesoDirective();
        d.setGroupId(5L);
        d.setStatus("active");
        d.setPayloadJson("{\"tendency\":{\"bias\":\"囤积粮食，谨慎出货\"},"
                + "\"constraint\":{\"forbidden\":[\"贱卖粮食\"]},"
                + "\"resourceDelta\":{\"hint\":\"行会统筹调配\"}}");
        when(directiveRepository.findByProjectIdAndGroupIdAndStatusOrderByIdDesc(100L, 5L, "active"))
                .thenReturn(List.of(d));
        when(directiveRepository.findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(100L, "active"))
                .thenReturn(List.of());
        String seg = router.composePerceptionSegment(100L, "special:1", 900L);
        assertTrue(seg.startsWith("【组织处境】"));
        assertTrue(seg.contains("王氏宗族"));
        assertTrue(seg.contains("首领"), "leader 角色显示名");
        assertTrue(seg.contains("影响力 60"));
        assertTrue(seg.contains("囤积粮食"), "指令倾向摘要");
        assertTrue(seg.contains("贱卖粮食"), "约束摘要");
        assertTrue(seg.length() <= props.getPerceptionBudget());
        // 未启用 → 空
        props.setEnabled(false);
        assertEquals("", router.composePerceptionSegment(100L, "special:1", 900L));
    }

    /** 决策情境段：同源更短（无影响力行）。 */
    @Test
    void decisionContextOmitsInfluence() {
        ActorMesoMembership m = member("special:1", 5L, "core");
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(100L, "special:1", "active"))
                .thenReturn(List.of(m));
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(5L);
        g.setName("同业行会");
        g.setStatus("active");
        g.setInfluence(40.0);
        when(groupRepository.findById(5L)).thenReturn(java.util.Optional.of(g));
        when(directiveRepository.findByProjectIdAndGroupIdAndStatusOrderByIdDesc(100L, 5L, "active"))
                .thenReturn(List.of());
        when(directiveRepository.findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(100L, "active"))
                .thenReturn(List.of());
        String seg = router.composeDecisionContext(100L, "special:1", 900L);
        assertTrue(seg.contains("同业行会"));
        assertFalse(seg.contains("影响力"), "决策段不注入影响力行");
        assertTrue(seg.length() <= props.getDecisionBudget());
    }

    /** 指令摘要：倾向+禁忌+资源提示合成；非法 payload 返回空。 */
    @Test
    void directiveHintSummarizesChannels() {
        ActorMesoDirective d = new ActorMesoDirective();
        d.setPayloadJson("{\"tendency\":{\"bias\":\"守好本分\"},"
                + "\"constraint\":{\"forbidden\":[\"聚众\",\"煽动\"]},"
                + "\"resourceDelta\":{\"hint\":\"族中公账统筹\"}}");
        String hint = router.directiveHint(d);
        assertTrue(hint.contains("守好本分"));
        assertTrue(hint.contains("聚众/煽动"));
        assertTrue(hint.contains("族中公账统筹"));
        assertTrue(hint.length() <= 40, "摘要限 40 字");
        ActorMesoDirective bad = new ActorMesoDirective();
        bad.setPayloadJson("{broken");
        assertEquals("", router.directiveHint(bad));
        assertEquals("", router.directiveHint(null));
    }

    /** 角色显示名映射。 */
    @Test
    void roleLabelMapping() {
        assertEquals("首领", MesoDirectiveRouter.roleLabel("leader"));
        assertEquals("长老", MesoDirectiveRouter.roleLabel("elder"));
        assertEquals("核心成员", MesoDirectiveRouter.roleLabel("core"));
        assertEquals("外围成员", MesoDirectiveRouter.roleLabel("affiliate"));
        assertEquals("成员", MesoDirectiveRouter.roleLabel(null));
        assertEquals("成员", MesoDirectiveRouter.roleLabel("unknown"));
    }

    /** 参数防御：null 入参不抛。 */
    @Test
    void nullGuards() {
        assertTrue(router.resolveTargetActors(null, new ActorMesoDirective()).isEmpty());
        assertTrue(router.resolveTargetActors(100L, null).isEmpty());
        assertEquals("", router.composePerceptionSegment(null, "special:1", 1L));
        assertEquals("", router.composePerceptionSegment(100L, null, 1L));
        assertNotNull(router.activeDirectivesFor(100L, null, 1L));
        assertTrue(router.activeDirectivesFor(100L, null, 1L).isEmpty());
    }
}
