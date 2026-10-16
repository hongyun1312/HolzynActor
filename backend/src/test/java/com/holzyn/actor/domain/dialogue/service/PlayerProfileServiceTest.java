package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerProfile;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerProfileRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import com.holzyn.actor.domain.dialogue.vo.PlayerProfileVO;
import com.holzyn.actor.domain.dialogue.vo.PlayerRelationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家档案服务单元测试（M3，零 AI）。
 * <p>职责：验证——玩家档案惰性创建（默认名=账户昵称）/persona 六维合并更新（null 保持、
 * 空串清空）；关系设定读改写（条目合并、他角色保留、全空移除条目）；关系行惰性创建
 * （好感 0/信任 0/阶段「陌生」）/更新（钳制 0~100、AI 字段只读保留）；列表 VO 转换。</p>
 */
class PlayerProfileServiceTest {

    private ActorPlayerProfileRepository profileRepository;
    private ActorPlayerRelationRepository relationRepository;
    private LocalAccountRepository accountRepository;
    private PlayerProfileService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ActorPlayerProfileRepository.class);
        relationRepository = mock(ActorPlayerRelationRepository.class);
        accountRepository = mock(LocalAccountRepository.class);
        service = new PlayerProfileService();
        ReflectionTestUtils.setField(service, "profileRepository", profileRepository);
        ReflectionTestUtils.setField(service, "relationRepository", relationRepository);
        ReflectionTestUtils.setField(service, "accountRepository", accountRepository);
        // save 返回入参（模拟 JPA 落库回填）
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ActorPlayerProfile profile(String personaJson, String relationSettingsJson) {
        ActorPlayerProfile p = new ActorPlayerProfile();
        p.setId(7L);
        p.setUserId(1L);
        p.setName("阿岚");
        p.setPersonaJson(personaJson);
        p.setRelationSettingsJson(relationSettingsJson);
        return p;
    }

    // ==================== 玩家档案 ====================

    @Test
    void profileOfLazilyCreatesWithAccountNickname() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        LocalAccount a = new LocalAccount();
        a.setNickname("林晚");
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(a));

        ActorPlayerProfile p = service.profileOf(1L);

        assertEquals(1L, p.getUserId());
        assertEquals("林晚", p.getName());
        assertEquals("{}", p.getRelationSettingsJson());
        verify(profileRepository).save(p);
    }

    @Test
    void profileOfReturnsExistingWithoutSave() {
        ActorPlayerProfile existing = profile(null, null);
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        ActorPlayerProfile p = service.profileOf(1L);

        assertEquals(existing, p);
        verify(profileRepository, never()).save(any());
    }

    @Test
    void updateProfileMergesPersonaKeepsUntouched() {
        ActorPlayerProfile existing = profile("{\"identity\":\"旧身份\",\"voice\":\"文静\"}", "{}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        PlayerProfileVO vo = service.updateProfile(1L, "阿岚",
                Map.of("identity", "新身份", "personality", "开朗"));

        assertEquals("阿岚", vo.getName());
        assertEquals("新身份", vo.getPersona().get("identity"));
        assertEquals("开朗", vo.getPersona().get("personality"));
        assertEquals("文静", vo.getPersona().get("voice")); // 未传键保持
        verify(profileRepository).save(existing);
    }

    @Test
    void updateProfileBlankClearsPersonaField() {
        ActorPlayerProfile existing = profile("{\"identity\":\"旧身份\",\"voice\":\"文静\"}", "{}");
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        PlayerProfileVO vo = service.updateProfile(1L, null, Map.of("voice", "  "));

        assertFalse(vo.getPersona().containsKey("voice"));
        assertEquals("旧身份", vo.getPersona().get("identity"));
    }

    // ==================== 关系设定 ====================

    @Test
    void relationSettingOfReadsEntry() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null,
                "{\"npc:1\":{\"past\":\"幼时相识\",\"identity\":\"青梅竹马\",\"attitude\":\"亲近\"}}")));

        Map<String, String> setting = service.relationSettingOf(1L, "npc:1");

        assertEquals("幼时相识", setting.get("past"));
        assertEquals("青梅竹马", setting.get("identity"));
        assertEquals("亲近", setting.get("attitude"));
    }

    @Test
    void relationSettingOfMissingReturnsEmpty() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null, "{}")));
        assertTrue(service.relationSettingOf(1L, "npc:9").isEmpty());
    }

    @Test
    void updateRelationSettingMergesAndPreservesOthers() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null,
                "{\"npc:1\":{\"past\":\"旧过往\",\"attitude\":\"疏离\"},\"npc:2\":{\"past\":\"另一段\"}}")));

        // past 传 null 保持；identity 新增；attitude 空串清空
        PlayerProfileVO vo = service.updateRelationSetting(1L, "npc:1", null, "同乡", "");

        Map<String, String> one = vo.getRelationSettings().get("npc:1");
        assertEquals("旧过往", one.get("past"));
        assertEquals("同乡", one.get("identity"));
        assertFalse(one.containsKey("attitude"));
        // 他角色条目保留
        assertEquals("另一段", vo.getRelationSettings().get("npc:2").get("past"));
    }

    @Test
    void updateRelationSettingEmptyEntryNotAdded() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null, "{}")));

        PlayerProfileVO vo = service.updateRelationSetting(1L, "npc:3", " ", "", "");

        assertTrue(vo.getRelationSettings().isEmpty());
    }

    // ==================== 关系行 ====================

    @Test
    void relationOfLazilyCreatesWithDefaults() {
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.empty());
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null, "{}")));

        ActorPlayerRelation r = service.relationOf(1L, 1L, "npc:1");

        assertEquals(1L, r.getProjectId());
        assertEquals(7L, r.getPlayerProfileId()); // 关联玩家档案
        assertEquals("npc:1", r.getCharacterRef());
        assertEquals(0, r.getAffinity());
        assertEquals(0, r.getTrust());
        assertEquals("陌生", r.getStage());
        verify(relationRepository).save(r);
    }

    @Test
    void upsertRelationClampsAndPreservesAiFields() {
        ActorPlayerRelation existing = new ActorPlayerRelation();
        existing.setId(3L);
        existing.setProjectId(1L);
        existing.setCharacterRef("npc:1");
        existing.setAffinity(10);
        existing.setTrust(20);
        existing.setStage("相识");
        existing.setStateSummary("AI 维护的近况");
        existing.setVersion(3);
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1")).thenReturn(Optional.of(existing));

        PlayerRelationVO vo = service.upsertRelation(1L, 1L, "npc:1", 150, -5, "熟络", "新备注");

        assertEquals(100, vo.getAffinity()); // 钳制上限
        assertEquals(0, vo.getTrust());      // 钳制下限
        assertEquals("熟络", vo.getStage());
        assertEquals("新备注", vo.getNote());
        assertEquals("AI 维护的近况", vo.getStateSummary()); // M6 字段只读保留
        assertEquals(3, vo.getVersion());
        verify(relationRepository).save(existing);
    }

    @Test
    void relationsOfConvertsToList() {
        ActorPlayerRelation a = new ActorPlayerRelation();
        a.setProjectId(1L);
        a.setCharacterRef("npc:1");
        a.setAffinity(60);
        ActorPlayerRelation b = new ActorPlayerRelation();
        b.setProjectId(1L);
        b.setCharacterRef("npc:2");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(a, b));

        List<PlayerRelationVO> rows = service.relationsOf(1L);

        assertEquals(2, rows.size());
        assertEquals("npc:1", rows.get(0).getCharacterRef());
        assertEquals(60, rows.get(0).getAffinity());
    }

    @Test
    void profileVOIncludesPersonaAndRelationSettings() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(
                "{\"identity\":\"编辑\",\"goals\":\"写完小说\"}",
                "{\"npc:1\":{\"past\":\"老友\"}}")));

        PlayerProfileVO vo = service.profileVO(1L);

        assertNotNull(vo.getId());
        assertEquals("编辑", vo.getPersona().get("identity"));
        assertEquals("写完小说", vo.getPersona().get("goals"));
        assertNull(vo.getPersona().get("background"));
        assertEquals("老友", vo.getRelationSettings().get("npc:1").get("past"));
    }

    // ==================== M6 沉淀写回（applyConsolidatedRelation） ====================

    private ActorPlayerRelation relation(int affinity, int trust, String stage, int version) {
        ActorPlayerRelation r = new ActorPlayerRelation();
        r.setId(3L);
        r.setProjectId(1L);
        r.setCharacterRef("npc:1");
        r.setAffinity(affinity);
        r.setTrust(trust);
        r.setStage(stage);
        r.setVersion(version);
        return r;
    }

    @Test
    void applyConsolidatedRelationAppliesDeltasAndIncrementsVersion() {
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1"))
                .thenReturn(Optional.of(relation(40, 35, "相识", 3)));

        PlayerRelationVO vo = service.applyConsolidatedRelation(1L, 1L, "npc:1",
                5, 2, "熟络", "玩家答应帮忙修屋顶", "与玩家关系渐熟，信任其承诺");

        assertEquals(45, vo.getAffinity());
        assertEquals(37, vo.getTrust());
        assertEquals("熟络", vo.getStage());
        assertEquals("玩家答应帮忙修屋顶", vo.getNote());
        assertEquals("与玩家关系渐熟，信任其承诺", vo.getStateSummary());
        assertEquals(4, vo.getVersion()); // version 幂等：每次沉淀 +1
        assertNotNull(vo.getUpdatedAt());
        verify(relationRepository).save(any(ActorPlayerRelation.class));
    }

    @Test
    void applyConsolidatedRelationClampsDeltas() {
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1"))
                .thenReturn(Optional.of(relation(95, 5, "熟络", 1)));

        PlayerRelationVO vo = service.applyConsolidatedRelation(1L, 1L, "npc:1",
                10, -10, null, null, null);

        assertEquals(100, vo.getAffinity()); // 95+10 钳制上限
        assertEquals(0, vo.getTrust());      // 5-10 钳制下限
        assertEquals("熟络", vo.getStage()); // stage null 保持
        assertEquals(2, vo.getVersion());
    }

    @Test
    void applyConsolidatedRelationCreatesRowWhenMissing() {
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:9")).thenReturn(Optional.empty());
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile(null, "{}")));

        PlayerRelationVO vo = service.applyConsolidatedRelation(1L, 1L, "npc:9",
                3, 0, "相识", null, "刚认识玩家");

        assertEquals(3, vo.getAffinity()); // 新行基线 0 + delta
        assertEquals(0, vo.getTrust());
        assertEquals("相识", vo.getStage());
        assertEquals("刚认识玩家", vo.getStateSummary());
        assertEquals(1, vo.getVersion());
        assertEquals(1L, vo.getProjectId()); // 项目归属
        // 惰性创建（1 次）+ 沉淀写回（1 次）
        verify(relationRepository, times(2)).save(any(ActorPlayerRelation.class));
    }

    @Test
    void applyConsolidatedRelationKeepsStageWhenBlank() {
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1"))
                .thenReturn(Optional.of(relation(20, 20, "相识", 0)));

        PlayerRelationVO vo = service.applyConsolidatedRelation(1L, 1L, "npc:1",
                0, 0, "  ", null, "");

        assertEquals("相识", vo.getStage()); // 空串阶段不覆盖
        assertNull(vo.getStateSummary());   // 空串状态摘要不覆盖
        assertEquals(1, vo.getVersion());   // 版本仍递增（沉淀动作发生）
    }
}
