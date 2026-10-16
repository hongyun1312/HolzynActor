package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerProfile;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerProfileRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 玩家-角色关系服务单元测试（M3，只读渲染，零 AI）。
 * <p>职责：验证——玩家名解析优先级（档案名→账户昵称→「你」）；「与{玩家}的关系」段
 * （关系设定 + 好感/信任/阶段/近况组合、无数据整段跳过 fail-open）；「关于{玩家}」段
 * （persona 六维渲染、缺数据跳过）。</p>
 */
class DialogueRelationServiceTest {

    private ActorPlayerProfileRepository profileRepository;
    private ActorPlayerRelationRepository relationRepository;
    private LocalAccountRepository accountRepository;
    private DialogueRelationService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ActorPlayerProfileRepository.class);
        relationRepository = mock(ActorPlayerRelationRepository.class);
        accountRepository = mock(LocalAccountRepository.class);
        service = new DialogueRelationService();
        ReflectionTestUtils.setField(service, "profileRepository", profileRepository);
        ReflectionTestUtils.setField(service, "relationRepository", relationRepository);
        ReflectionTestUtils.setField(service, "accountRepository", accountRepository);
    }

    // ==================== 玩家名解析 ====================

    @Test
    void playerNamePrefersProfileName() {
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setName("阿岚");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(p));
        assertEquals("阿岚", service.playerNameOf(1L));
    }

    @Test
    void playerNameFallsBackToAccountNickname() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        LocalAccount a = new LocalAccount();
        a.setNickname("林晚");
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(a));
        assertEquals("林晚", service.playerNameOf(1L));
    }

    @Test
    void playerNameFallsBackToYou() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertEquals("你", service.playerNameOf(1L));
    }

    // ==================== 与{玩家}的关系段 ====================

    @Test
    void relationSegmentCombinesSettingAndRow() {
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setRelationSettingsJson("{\"npc:1\":{\"past\":\"幼时相识\",\"identity\":\"青梅竹马\",\"attitude\":\"亲近\"}}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(p));
        ActorPlayerRelation r = new ActorPlayerRelation();
        r.setStage("熟络");
        r.setAffinity(66);
        r.setTrust(58);
        r.setStateSummary("近日常一起饮茶");
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.of(r));

        String seg = service.relationSegmentOf(1L, "npc:1", "阿岚");

        assertTrue(seg.startsWith("【与阿岚的关系】"));
        assertTrue(seg.contains("关系设定：过往：幼时相识，身份关系：青梅竹马，态度：亲近"));
        assertTrue(seg.contains("关系阶段：熟络"));
        assertTrue(seg.contains("好感66"));
        assertTrue(seg.contains("信任58"));
        assertTrue(seg.contains("近况：近日常一起饮茶"));
    }

    @Test
    void relationSegmentSkipsWhenNoData() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.empty());
        assertEquals("", service.relationSegmentOf(1L, "npc:1", "阿岚"));
    }

    @Test
    void relationSegmentDefaultsWhoToYou() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        ActorPlayerRelation r = new ActorPlayerRelation();
        r.setStage("陌生");
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.of(r));
        String seg = service.relationSegmentOf(1L, "npc:1", "  ");
        assertTrue(seg.startsWith("【与你的关系】"));
    }

    // ==================== 关于{玩家}段（persona 六维） ====================

    @Test
    void playerPersonaSegmentRendersSixDims() {
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setPersonaJson("{\"identity\":\"旅行编辑\",\"background\":\"走遍九州\",\"personality\":\"温和\","
                + "\"voice\":\"慢条斯理\",\"goals\":\"收集民间故事\",\"ability\":\"善写\"}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(p));

        String seg = service.playerPersonaSegmentOf(1L, "阿岚");

        assertTrue(seg.startsWith("【关于阿岚】"));
        assertTrue(seg.contains("身份：旅行编辑"));
        assertTrue(seg.contains("背景：走遍九州"));
        assertTrue(seg.contains("性格：温和"));
        assertTrue(seg.contains("说话风格：慢条斯理"));
        assertTrue(seg.contains("目标：收集民间故事"));
        assertTrue(seg.contains("能力：善写"));
    }

    @Test
    void playerPersonaSegmentSkipsWhenMissing() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertEquals("", service.playerPersonaSegmentOf(1L, "阿岚"));
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setPersonaJson("{}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(p));
        assertEquals("", service.playerPersonaSegmentOf(1L, "阿岚"));
    }

    @Test
    void playerPersonaSegmentDefaultsWhoToYou() {
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setPersonaJson("{\"identity\":\"编辑\"}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(p));
        String seg = service.playerPersonaSegmentOf(1L, null);
        assertTrue(seg.startsWith("【关于你】"));
        assertTrue(seg.contains("身份：编辑"));
    }
}
