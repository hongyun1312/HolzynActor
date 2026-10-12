package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * EventRoutingService（D3 F10 事件按地点/角色路由）单元测试。
 * <p>职责：验证路由匹配规则——① 角色路由（character_ids → 含该角色会话）；② 地点路由
 * （会话所在地 location_id 或任一成员所在地 location_id = 事件地点）；③ 地点名兜底；
 * ④ 无匹配返回 null（仅广播不注入，修 C-8）。</p>
 */
class EventRoutingServiceTest {

    private ActorConversationRepository conversationRepository;
    private ActorConversationMemberRepository memberRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private EventRoutingService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        service = new EventRoutingService(conversationRepository, memberRepository, characterRepository, npcRepository);
    }

    private ActorConversation conv(Long id, Long locationId, String location, LocalDateTime updatedAt) {
        ActorConversation c = new ActorConversation();
        c.setId(id);
        c.setProjectId(1L);
        c.setUserId(1L);
        c.setLocationId(locationId);
        c.setLocation(location);
        c.setUpdatedAt(updatedAt);
        return c;
    }

    private ActorConversationMember member(Long convId, String type, Long characterId) {
        ActorConversationMember m = new ActorConversationMember();
        m.setConversationId(convId);
        m.setMemberType(type);
        m.setCharacterId(characterId);
        return m;
    }

    /** 地点路由：会话所在地 location_id = 事件地点 → 命中 */
    @Test
    void routeByConversationLocationId() {
        ActorConversation chengXi = conv(10L, 2L, "城西酒馆", LocalDateTime.now());
        ActorConversation chengDong = conv(11L, 3L, "城东市场", LocalDateTime.now().plusMinutes(1));
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(chengDong, chengXi));

        ActorConversation target = service.findTarget(1L, 1L, 2L, "城西酒馆", null);
        assertNotNull(target);
        assertEquals(10L, target.getId(), "事件地点=城西（2），应路由到城西会话而非更新的城东会话");
    }

    /** 地点路由：会话成员当前所在地 location_id = 事件地点（角色在场）→ 命中 */
    @Test
    void routeByMemberLocation() {
        ActorConversation convA = conv(10L, 1L, "妖灵会馆", LocalDateTime.now());
        ActorConversation convB = conv(11L, null, "远程通讯", LocalDateTime.now().plusMinutes(1));
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(convB, convA));
        when(memberRepository.findByConversationId(10L)).thenReturn(List.of(member(10L, "special", 5L)));
        when(memberRepository.findByConversationId(11L)).thenReturn(List.of(member(11L, "special", 6L)));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(5L);
        ch.setLocationId(2L); // 成员 5 当前在城西（2）
        when(characterRepository.findById(5L)).thenReturn(Optional.of(ch));
        when(characterRepository.findById(6L)).thenReturn(Optional.of(new ActorCharacter()));

        ActorConversation target = service.findTarget(1L, 1L, 2L, "城西酒馆", null);
        assertNotNull(target);
        assertEquals(10L, target.getId(), "事件地点=城西（2），会话 A 成员在场 → 路由到 A");
    }

    /** 角色路由：事件带当事人 character_ids → 命中含该角色的会话（优先于地点） */
    @Test
    void routeByCharacterIds() {
        ActorConversation convA = conv(10L, null, null, LocalDateTime.now());
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(convA));
        when(memberRepository.findByConversationId(10L)).thenReturn(List.of(member(10L, "special", 5L)));

        ActorConversation target = service.findTarget(1L, 1L, null, null, List.of("npc:5"));
        assertNotNull(target);
        assertEquals(10L, target.getId());
    }

    /** 普通型（common）成员：按 actor_profile 当前位置参与地点路由（G1a 单表，不再按 member_type 分表取行） */
    @Test
    void routeByCrowdMemberLocation() {
        ActorConversation convA = conv(10L, null, null, LocalDateTime.now());
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(convA));
        when(memberRepository.findByConversationId(10L)).thenReturn(List.of(member(10L, "crowd", 30L)));
        ActorCharacter npc = new ActorCharacter();
        npc.setId(30L);
        npc.setLocationId(2L);
        npc.setType("common");
        when(characterRepository.findById(30L)).thenReturn(Optional.of(npc));

        ActorConversation target = service.findTarget(1L, 1L, 2L, "城西酒馆", null);
        assertNotNull(target);
        assertEquals(10L, target.getId());
    }

    /** 地点名兜底：无 location_id 但地点名匹配会话所在地名称 */
    @Test
    void routeByNameFallback() {
        ActorConversation convA = conv(10L, null, "城西酒馆", LocalDateTime.now());
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(convA));

        ActorConversation target = service.findTarget(1L, 1L, null, "城西酒馆", null);
        assertNotNull(target);
        assertEquals(10L, target.getId());
    }

    /** 无匹配（地点不在任何会话/成员处）→ null（仅广播不注入） */
    @Test
    void routeNoneReturnsNull() {
        ActorConversation convA = conv(10L, 1L, "妖灵会馆", LocalDateTime.now());
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(convA));
        when(memberRepository.findByConversationId(10L)).thenReturn(List.of(member(10L, "special", 5L)));
        when(characterRepository.findById(5L)).thenReturn(Optional.of(new ActorCharacter()));

        assertNull(service.findTarget(1L, 1L, 99L, "未知地点", null));
        assertNull(service.findTarget(1L, 1L, null, "未知地点", null));
        assertNull(service.findTarget(1L, 1L, null, null, null));
        assertNull(service.findTarget(null, 1L, 1L, "城西酒馆", null));
        assertNull(service.findTarget(1L, null, 1L, "城西酒馆", null));
    }

    /** 空会话列表 → null */
    @Test
    void routeNoConversationsReturnsNull() {
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of());
        assertNull(service.findTarget(1L, 1L, 2L, "城西酒馆", null));
    }

    /** 排除源会话：源会话刚刷新 lastMessageAt 排最前，路由须跳过它命中下一个同地点会话（D3 F10 冒烟抓修） */
    @Test
    void routeSkipsExcludedSourceConversation() {
        ActorConversation source = conv(10L, 2L, "城西酒馆", LocalDateTime.now().plusMinutes(5));
        ActorConversation other = conv(11L, 2L, "城西酒馆", LocalDateTime.now());
        when(conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of(source, other));

        ActorConversation target = service.findTarget(1L, 1L, 2L, "城西酒馆", null, 10L);
        assertNotNull(target);
        assertEquals(11L, target.getId(), "应排除源会话（10）命中同地点的其他在场会话（11）");

        // 不排除时仍命中源会话（引擎跨日路径无源会话概念，保留该行为）
        ActorConversation noExclude = service.findTarget(1L, 1L, 2L, "城西酒馆", null);
        assertNotNull(noExclude);
        assertEquals(10L, noExclude.getId());
    }
}
