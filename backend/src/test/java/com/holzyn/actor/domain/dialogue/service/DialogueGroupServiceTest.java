package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorTalkGroupMember;
import com.holzyn.actor.domain.dialogue.repository.ActorTalkGroupMemberRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 对谈群聊场景化成员服务单元测试（零 AI mock）。
 * <p>职责：验证——群聊创建在场成员初始化（显式地点/自动选在场最多地点）、换场重算在场
 * （manual 拉人保留）、拉人（manual 标记/重复 400/跨项目 404）、移除成员、成员列表
 * （present 在前 manual 在后）、清醒过滤（睡着跳过/无状态服务不过滤）、非群聊会话拒绝。</p>
 */
class DialogueGroupServiceTest {

    private DialogueSessionService sessionService;
    private ActorTalkGroupMemberRepository memberRepository;
    private ActorCharacterRepository characterRepository;
    private NpcStateService npcStateService;
    private DialogueGroupService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        memberRepository = mock(ActorTalkGroupMemberRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        npcStateService = mock(NpcStateService.class);
        service = new DialogueGroupService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "memberRepository", memberRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "npcStateService", npcStateService);
        // 项目角色：np1/np2 在村口(10)，np3/np4 在学堂(20)
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(ch(1L, "沈守拙", 10L, "村口"), ch(2L, "林听竹", 10L, "村口"),
                        ch(3L, "陈墨", 20L, "学堂"), ch(4L, "柳三娘", 20L, "学堂")));
    }

    private ActorCharacter ch(Long id, String name, Long locationId, String location) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setLocationId(locationId);
        c.setLocation(location);
        return c;
    }

    private ActorDialogueSession groupSession() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setType("group");
        s.setLocationId(10L);
        s.setLocationName("村口");
        return s;
    }

    private DialogueSessionVO vo() {
        return new DialogueSessionVO(7L, 1L, "群聊", "group", null, null, "村口", null, null,
                "active", 0L, LocalDateTime.now(), LocalDateTime.now());
    }

    private ActorTalkGroupMember member(Long id, String ref, String name, String source) {
        ActorTalkGroupMember m = new ActorTalkGroupMember();
        m.setId(id);
        m.setSessionId(7L);
        m.setCharacterRef(ref);
        m.setCharacterName(name);
        m.setSource(source);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ==================== 群聊创建 ====================

    @Test
    @SuppressWarnings("unchecked")
    void createGroupInitsPresentMembersAtExplicitLocation() {
        when(sessionService.create(1L, 1L, "group", null, "群聊")).thenReturn(vo());
        when(sessionService.get(1L, 7L)).thenReturn(vo());
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(memberRepository.findBySessionIdAndCharacterRef(eq(7L), any())).thenReturn(Optional.empty());

        DialogueSessionVO created = service.createGroup(1L, 1L, 10L, "群聊");

        assertEquals(7L, created.id());
        // 会话行写入口：updateLocation 携带解析出的地点（10=村口）
        verify(sessionService).updateLocation(1L, 7L, 10L, "村口");
        // 在场成员：np1/np2 落 present 行（np3/np4 在学堂不在此地点）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActorTalkGroupMember>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(captor.capture());
        List<ActorTalkGroupMember> rows = captor.getValue();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "npc:1".equals(r.getCharacterRef()) && "沈守拙".equals(r.getCharacterName())));
        assertTrue(rows.stream().anyMatch(r -> "npc:2".equals(r.getCharacterRef()) && DialogueGroupService.SOURCE_PRESENT.equals(r.getSource())));
        assertTrue(rows.stream().noneMatch(r -> "npc:3".equals(r.getCharacterRef())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createGroupAutoPicksMostPopulatedLocation() {
        when(sessionService.create(1L, 1L, "group", null, null)).thenReturn(vo());
        when(sessionService.get(1L, 7L)).thenReturn(vo());
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(memberRepository.findBySessionIdAndCharacterRef(eq(7L), any())).thenReturn(Optional.empty());

        service.createGroup(1L, 1L, null, null);

        // 自动选在场最多的地点：村口(10) 2 人 > 学堂(20) 2 人 → 平局取角色 id 较小者所在 → 村口
        verify(sessionService).updateLocation(1L, 7L, 10L, "村口");
        verify(memberRepository).saveAll(anyList());
    }

    // ==================== 换场 ====================

    @Test
    @SuppressWarnings("unchecked")
    void changeLocationRecomputesPresentKeepsManual() {
        // 会话对象可变：updateLocation 写入口按实际语义更新其地点（换场后在场按新地点重算）
        ActorDialogueSession gs = groupSession();
        when(sessionService.require(1L, 7L)).thenReturn(gs);
        when(sessionService.get(1L, 7L)).thenReturn(vo());
        org.mockito.Mockito.doAnswer(inv -> {
            gs.setLocationId(inv.getArgument(2));
            gs.setLocationName(inv.getArgument(3));
            return null;
        }).when(sessionService).updateLocation(1L, 7L, 20L, "学堂");
        // 既有成员：present npc:1 + manual npc:3
        when(memberRepository.findBySessionIdOrderByIdAsc(7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT),
                        member(2L, "npc:3", "陈墨", DialogueGroupService.SOURCE_MANUAL)));
        when(memberRepository.findBySessionIdAndCharacterRef(eq(7L), eq("npc:4"))).thenReturn(Optional.empty());
        when(memberRepository.findBySessionIdAndCharacterRef(eq(7L), eq("npc:3"))).thenReturn(
                Optional.of(member(2L, "npc:3", "陈墨", DialogueGroupService.SOURCE_MANUAL)));

        service.changeLocation(1L, 7L, 20L);

        // 会话 location 更新为学堂(20)
        verify(sessionService).updateLocation(1L, 7L, 20L, "学堂");
        // 既有 present 删除，manual 保留
        verify(memberRepository).deleteBySessionIdAndSource(7L, DialogueGroupService.SOURCE_PRESENT);
        // 新地点在场 = np3（已是 manual 不重复）+ np4（新增 present）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActorTalkGroupMember>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(captor.capture());
        List<ActorTalkGroupMember> rows = captor.getValue();
        assertEquals(1, rows.size());
        assertEquals("npc:4", rows.get(0).getCharacterRef());
        assertEquals(DialogueGroupService.SOURCE_PRESENT, rows.get(0).getSource());
    }

    @Test
    void changeLocationOnSingleSessionRejected() {
        ActorDialogueSession single = new ActorDialogueSession();
        single.setId(7L);
        single.setProjectId(1L);
        single.setType("single");
        when(sessionService.require(1L, 7L)).thenReturn(single);

        BizException e = assertThrows(BizException.class, () -> service.changeLocation(1L, 7L, 20L));
        assertTrue(e.getMessage().contains("仅群聊会话"));
        verify(sessionService, never()).updateLocation(any(), any(), any(), any());
    }

    @Test
    void changeLocationResolvesNameFromWorldLocationTableWhenLocationEmpty() {
        // 第 61 轮冒烟抓修★：换场到「无角色在场」的地点（如青禾村古井）时，旧实现只靠角色 location
        // 字符串解析名字 → null → 会话 location_name 丢失、按地点名搜索失效；修复=地点表主源。
        WorldLocationRepository wlr = mock(WorldLocationRepository.class);
        ReflectionTestUtils.setField(service, "worldLocationRepository", wlr);
        ActorWorldLocation loc = new ActorWorldLocation();
        loc.setId(99L);
        loc.setName("青禾村古井");
        when(wlr.findById(99L)).thenReturn(Optional.of(loc));

        ActorDialogueSession gs = groupSession();
        when(sessionService.require(1L, 7L)).thenReturn(gs);
        when(sessionService.get(1L, 7L)).thenReturn(vo());
        when(memberRepository.findBySessionIdOrderByIdAsc(7L)).thenReturn(List.of());

        service.changeLocation(1L, 7L, 99L);

        verify(sessionService).updateLocation(1L, 7L, 99L, "青禾村古井");
    }

    // ==================== 拉人 / 移除 ====================

    @Test
    void inviteAddsManualMember() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(characterRepository.findByIdAndProjectId(5L, 1L)).thenReturn(Optional.of(ch(5L, "赵铁柱", null, "铁匠铺")));
        when(memberRepository.findBySessionIdAndCharacterRef(7L, "npc:5")).thenReturn(Optional.empty());
        // 拉人后成员列表 = 既有在场 + 新增 manual（服务保存后重读仓库）
        when(memberRepository.findBySessionIdOrderByIdAsc(7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT),
                        member(3L, "npc:5", "赵铁柱", DialogueGroupService.SOURCE_MANUAL)));

        List<DialogueGroupMemberVO> after = service.invite(1L, 7L, "npc:5");

        // 拉人成员行：manual 标记 + 角色名冗余
        org.mockito.ArgumentCaptor<ActorTalkGroupMember> captor =
                org.mockito.ArgumentCaptor.forClass(ActorTalkGroupMember.class);
        verify(memberRepository).save(captor.capture());
        ActorTalkGroupMember saved = captor.getValue();
        assertEquals("npc:5", saved.getCharacterRef());
        assertEquals("赵铁柱", saved.getCharacterName());
        assertEquals(DialogueGroupService.SOURCE_MANUAL, saved.getSource());
        assertEquals(2, after.size());
        assertEquals("npc:1", after.get(0).characterRef());
        assertEquals("npc:5", after.get(1).characterRef());
    }

    @Test
    void inviteDuplicateThrows400() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(characterRepository.findByIdAndProjectId(1L, 1L)).thenReturn(Optional.of(ch(1L, "沈守拙", 10L, "村口")));
        when(memberRepository.findBySessionIdAndCharacterRef(7L, "npc:1"))
                .thenReturn(Optional.of(member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT)));

        BizException e = assertThrows(BizException.class, () -> service.invite(1L, 7L, "npc:1"));
        assertTrue(e.getMessage().contains("已在群聊中"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    void inviteNonProjectCharacterThrows404() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(characterRepository.findByIdAndProjectId(99L, 1L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.invite(1L, 7L, "npc:99"));
        assertTrue(e.getMessage().contains("角色不存在"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    void removeMemberDeletesRow() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        ActorTalkGroupMember row = member(1L, "npc:2", "林听竹", DialogueGroupService.SOURCE_PRESENT);
        when(memberRepository.findBySessionIdAndCharacterRef(7L, "npc:2")).thenReturn(Optional.of(row));

        service.removeMember(1L, 7L, "npc:2");

        verify(memberRepository).delete(row);
    }

    @Test
    void removeMemberNotFoundThrows404() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(memberRepository.findBySessionIdAndCharacterRef(7L, "npc:9")).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.removeMember(1L, 7L, "npc:9"));
        assertTrue(e.getMessage().contains("不在群聊中"));
    }

    // ==================== 成员列表 / 清醒过滤 ====================

    @Test
    void membersOrderedPresentFirstThenManual() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        // 仓库按 id 序返回 manual(先加入) + present(后重算) → 服务应稳定重排 present 在前
        when(memberRepository.findBySessionIdOrderByIdAsc(7L))
                .thenReturn(List.of(member(2L, "npc:3", "陈墨", DialogueGroupService.SOURCE_MANUAL),
                        member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT)));

        List<DialogueGroupMemberVO> list = service.members(1L, 7L);

        assertEquals(2, list.size());
        assertEquals("npc:1", list.get(0).characterRef());
        assertEquals(DialogueGroupService.SOURCE_PRESENT, list.get(0).source());
        assertEquals("npc:3", list.get(1).characterRef());
        assertEquals(DialogueGroupService.SOURCE_MANUAL, list.get(1).source());
    }

    @Test
    void awakeMembersFiltersSleepingAndFailsOpen() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(memberRepository.findBySessionIdOrderByIdAsc(7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT),
                        member(2L, "npc:2", "林听竹", DialogueGroupService.SOURCE_PRESENT)));
        ActorNpcState asleep = new ActorNpcState();
        asleep.setActivityState("sleep");
        when(npcStateService.stateFor(1L, "npc:1")).thenReturn(new ActorNpcState());
        when(npcStateService.stateFor(1L, "npc:2")).thenReturn(asleep);

        List<DialogueGroupMemberVO> awake = service.awakeMembers(1L, 7L);

        assertEquals(1, awake.size());
        assertEquals("npc:1", awake.get(0).characterRef());

        // 状态服务未装配 → 全部成员视为清醒（fail-open）
        ReflectionTestUtils.setField(service, "npcStateService", null);
        List<DialogueGroupMemberVO> all = service.awakeMembers(1L, 7L);
        assertEquals(2, all.size());
    }

    @Test
    void awakeMembersTreatsNullStateAsAwake() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(memberRepository.findBySessionIdOrderByIdAsc(7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙", DialogueGroupService.SOURCE_PRESENT)));
        when(npcStateService.stateFor(1L, "npc:1")).thenReturn(null);

        List<DialogueGroupMemberVO> awake = service.awakeMembers(1L, 7L);
        assertEquals(1, awake.size());
    }

    @Test
    void membersOnSingleSessionRejected() {
        ActorDialogueSession single = new ActorDialogueSession();
        single.setId(7L);
        single.setProjectId(1L);
        single.setType("single");
        when(sessionService.require(1L, 7L)).thenReturn(single);

        BizException e = assertThrows(BizException.class, () -> service.members(1L, 7L));
        assertTrue(e.getMessage().contains("仅群聊会话"));
    }
}
