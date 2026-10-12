package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.roleplay.RoleplayContextService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * ConversationService B5（F4 普通 NPC 参与通道）单元测试（domain/conversation/service）。
 * <p>职责：验证会话创建支持统一成员引用 memberRefs——{@code npc:{id}} 单前缀（G1a：不再编码类型），
 * 类型取 {@code actor_profile.type}：special → member_type=special；common（历史 crowd）→ member_type=common
 * + member_name 快照；越权/不存在校验；characterIds 兼容。</p>
 */
class ConversationServiceCrowdTest {

    private ActorConversationRepository conversationRepository;
    private ActorConversationMemberRepository memberRepository;
    private ActorProjectRepository projectRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private RoleplayContextService roleplayContextService;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        memberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        roleplayContextService = Mockito.mock(RoleplayContextService.class);
        ActorMessageRepository messageRepository = Mockito.mock(ActorMessageRepository.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        service = new ConversationService(conversationRepository, memberRepository, projectRepository,
                characterRepository, npcRepository, roleplayContextService, messageRepository, currentUserProvider);
        when(conversationRepository.save(any(ActorConversation.class))).thenAnswer(inv -> {
            ActorConversation c = inv.getArgument(0);
            c.setId(9L);
            return c;
        });
        when(memberRepository.save(any(ActorConversationMember.class))).thenAnswer(inv -> {
            ActorConversationMember m = inv.getArgument(0);
            savedMembers.add(m); // 收集保存的成员供 detailVO 组装断言（findByConversationId 基于此）
            return m;
        });
        // detailVO 组装：会话成员（含 member_type/name）
        when(memberRepository.findByConversationId(9L)).thenAnswer(inv ->
                ((List<ActorConversationMember>) savedMembers).stream().toList());
    }

    private final java.util.List<ActorConversationMember> savedMembers = new java.util.ArrayList<>();

    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(100L);
        p.setUserId(1L);
        return p;
    }

    /**
     * create 支持 memberRefs 混搭：{@code npc:3}（special）+ {@code npc:12}（common）→ 成员类型化落库。
     */
    @Test
    void createWithMemberRefsMixesSpecialAndCrowd() {
        when(projectRepository.findByIdAndUserIdAndDeleted(100L, 1L, 0)).thenReturn(Optional.of(project()));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("玄铁");
        ch.setDeleted(0);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        ActorCharacter npc = new ActorCharacter();
        npc.setId(12L);
        npc.setProjectId(100L);
        npc.setName("林小满");
        // G1a：单表后成员校验只查 actor_profile；行内 type 决定 member_type 与名称快照
        npc.setType("common");
        when(characterRepository.findById(12L)).thenReturn(Optional.of(npc));
        when(roleplayContextService.characterName("special", 3L)).thenReturn("玄铁");

        ConversationVO vo = service.create(100L, new ConversationDTO("group", "测试",
                null, List.of("npc:3", "npc:12"), null, null));

        assertEquals(2, savedMembers.size(), "两个成员落库");
        ActorConversationMember special = savedMembers.get(0);
        assertEquals("special", special.getMemberType());
        assertEquals(Long.valueOf(3L), special.getCharacterId());
        ActorConversationMember crowd = savedMembers.get(1);
        assertEquals("common", crowd.getMemberType(), "G1a：DB 列 member_type 写 common（历史值 crowd 由 NpcRefs 归一）");
        assertEquals(Long.valueOf(12L), crowd.getCharacterId());
        assertEquals("林小满", crowd.getMemberName(), "普通 NPC 名称快照");
        // VO 成员详情
        assertEquals(2, vo.members().size());
        assertEquals("common", vo.members().get(1).memberType());
        assertEquals("林小满", vo.members().get(1).memberName());
        assertNull(special.getMemberName(), "特殊角色名称由角色表解析（快照可空）");
    }

    /**
     * 普通型成员不属于该项目/不存在 → 拒绝创建（越权 404 语义；G1a 统一提示文案）。
     */
    @Test
    void createRejectsCrowdNotInProject() {
        when(projectRepository.findByIdAndUserIdAndDeleted(100L, 1L, 0)).thenReturn(Optional.of(project()));
        when(characterRepository.findById(12L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () ->
                service.create(100L, new ConversationDTO("single", null,
                        null, List.of("npc:12"), null, null)));
        assertTrue(e.getMessage().contains("NPC 不存在"), "应提示 NPC 不存在或不属于该项目");
    }

    /**
     * 特殊型成员不属于该项目 → 拒绝创建（G1a 单表后与普通型同一提示文案）。
     */
    @Test
    void createRejectsSpecialNotInProject() {
        when(projectRepository.findByIdAndUserIdAndDeleted(100L, 1L, 0)).thenReturn(Optional.of(project()));
        when(characterRepository.findById(99L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () ->
                service.create(100L, new ConversationDTO("single", null,
                        null, List.of("npc:99"), null, null)));
        assertTrue(e.getMessage().contains("NPC 不存在"), "应提示 NPC 不存在或不属于该项目");
    }

    /**
     * characterIds 兼容旧调用（无 memberRefs）：按 actor_profile.type 落类型（夹具为 special）。
     */
    @Test
    void createWithCharacterIdsFallsBackToSpecial() {
        when(projectRepository.findByIdAndUserIdAndDeleted(100L, 1L, 0)).thenReturn(Optional.of(project()));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("玄铁");
        ch.setDeleted(0);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));

        ConversationVO vo = service.create(100L, new ConversationDTO("single", null,
                List.of(3L), null, null, null));

        assertEquals(1, savedMembers.size());
        assertEquals("special", savedMembers.get(0).getMemberType(), "characterIds 兼容=special");
        assertEquals(Long.valueOf(3L), savedMembers.get(0).getCharacterId());
    }
}
