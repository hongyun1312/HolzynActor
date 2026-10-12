package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * RoleplayContextService（B5 F4 普通 NPC 档案渲染）单元测试（domain/agent/roleplay）。
 * <p>职责：验证普通 NPC 成员 system_prompt 渲染（姓名/性别/种族含次级种族/年龄/归属/职业/所在地/详情
 * + 世界观片段，修 L-09）与特殊角色成员<b>档案分片渲染</b>（O6 G1a：角色卡 system_prompt 已废弃，
 * 改由 {@code PersonaService.renderShardsForScene(id, "dialog_system")} 按场景产出）、世界观片段节选。</p>
 */
class RoleplayContextServiceTest {

    private PersonaService personaService;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private ActorProjectRepository projectRepository;
    private RoleplayContextService service;

    @BeforeEach
    void setUp() {
        personaService = Mockito.mock(PersonaService.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        // O6 G1a：角色卡仓库已删（单主表），构造器为 3 参；personaService 为 @Autowired(required=false) 字段注入
        service = new RoleplayContextService(characterRepository, npcRepository, projectRepository);
        ReflectionTestUtils.setField(service, "personaService", personaService);
    }

    /** 构造普通 NPC 档案 */
    private ActorCharacter npc(String name, String gender, String race, String subRace, Integer age,
                                 String affiliation, String occupation, String location, String detail) {
        ActorCharacter n = new ActorCharacter();
        n.setId(12L);
        n.setProjectId(100L);
        n.setName(name);
        n.setGender(gender);
        n.setRace(race);
        n.setSubRace(subRace);
        n.setAge(age);
        n.setAffiliation(affiliation);
        n.setOccupation(occupation);
        n.setLocation(location);
        n.setDetail(detail);
        return n;
    }

    /**
     * 普通 NPC 档案渲染：姓名/性别/种族含次级种族/年龄/归属/职业/所在地/详情 + 世界观片段齐全。
     */
    @Test
    void renderCrowdPromptIncludesIdentityAndWorld() {
        ActorCharacter n = npc("林小满", "女", "人族", "汉族", 18, "城南布庄", "账房学徒", "城南街巷",
                "性子安静，记账时爱走神，常被掌柜唠叨。");
        String prompt = RoleplayContextService.renderCrowdPrompt(n, "一座依山傍水的古城，商贸繁华。");

        assertTrue(prompt.contains("林小满"), "含姓名");
        assertTrue(prompt.contains("女"), "含性别");
        assertTrue(prompt.contains("汉族"), "含次级种族（代替一级种族人族）");
        assertTrue(prompt.contains("18岁"), "含年龄");
        assertTrue(prompt.contains("城南布庄"), "含归属");
        assertTrue(prompt.contains("账房学徒"), "含职业");
        assertTrue(prompt.contains("城南街巷"), "含所在地");
        assertTrue(prompt.contains("性子安静"), "含角色详情");
        assertTrue(prompt.contains("一座依山傍水的古城"), "含世界观片段");
        assertTrue(prompt.contains("不要编造"), "含防 OOC 约束");
    }

    /**
     * 渲染容错：无次级种族用一级种族；空字段跳过不堆砌；null 档案回退提示。
     */
    @Test
    void renderCrowdPromptToleratesMissingFields() {
        ActorCharacter n = npc("阿花", null, "妖精", null, null, null, null, null, null);
        String prompt = RoleplayContextService.renderCrowdPrompt(n, null);
        assertTrue(prompt.contains("阿花"));
        assertTrue(prompt.contains("妖精"), "无次级种族用一级种族");
        assertTrue(prompt.contains("普通居民"), "空职业回退普通居民");
        assertTrue(RoleplayContextService.renderCrowdPrompt(null, null).contains("普通居民"), "null 档案回退");
    }

    /**
     * resolveCharacterPrompt：crowd 成员走档案渲染（mock npc 仓库）。
     */
    @Test
    void resolveCrowdMemberRendersProfile() {
        ActorCharacter n = npc("阿花", "女", "人族", "汉族", 30, "码头", "搬运工", "东港", "力气大话不多。");
        when(npcRepository.findById(12L)).thenReturn(Optional.of(n));
        ActorProject p = new ActorProject();
        p.setId(100L);
        p.setSummary("临海小城，渔业与商路交汇。");
        when(projectRepository.findById(100L)).thenReturn(Optional.of(p));

        String prompt = service.resolveCharacterPrompt(100L, "crowd", 12L);

        assertTrue(prompt.contains("阿花"), "crowd 成员应档案渲染");
        assertTrue(prompt.contains("搬运工"));
        assertTrue(prompt.contains("临海小城"), "应含世界观片段");
    }

    /**
     * resolveCharacterPrompt：special 成员走 11 维档案分片渲染（G1a：角色卡 system_prompt 已废弃），
     * 且按 Prompt 工程规范 V1.0 §1 前置「你是{name}，绝不能跳出角色」角色锚定（SYSTEM 层）。
     */
    @Test
    void resolveSpecialMemberUsesPersonaShards() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setName("玄铁");
        when(characterRepository.findById(3L)).thenReturn(Optional.of(ch));
        when(personaService.renderShardsForScene(3L, "dialog_system"))
                .thenReturn("你是玄铁，隐居云雾山的铸剑师。");

        String prompt = service.resolveCharacterPrompt(100L, "special", 3L);

        assertTrue(prompt.contains("玄铁"), "special 成员应档案分片渲染");
        assertTrue(prompt.startsWith("你是玄铁，绝不能跳出角色。"), "system 应以角色锚定开场（规范 §1 SYSTEM 层）");
    }

    /**
     * 空档案（分片为空串）→ 降级通用世界观提示（数据缺失兜底，不是兼容层）。
     */
    @Test
    void resolveSpecialMemberFallsBackWhenNoPersona() {
        when(personaService.renderShardsForScene(3L, "dialog_system")).thenReturn("");

        String prompt = service.resolveCharacterPrompt(100L, "special", 3L);

        assertEquals("你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。", prompt);
    }

    /**
     * 世界观片段节选：项目概要前 N 字 + 省略号；无概要/项目返回 null。
     */
    @Test
    void worldSnippetTruncatesAndToleratesMissing() {
        ActorProject p = new ActorProject();
        p.setId(100L);
        StringBuilder longSummary = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longSummary.append("这是一段世界观设定。");
        }
        p.setSummary(longSummary.toString());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(p));

        String snippet = service.worldSnippet(100L);
        assertTrue(snippet.length() <= RoleplayContextService.WORLD_SNIPPET + 1, "节选不超上限（+省略号）");
        assertTrue(snippet.endsWith("…"), "超长以省略号结尾");

        when(projectRepository.findById(101L)).thenReturn(Optional.of(new ActorProject()));
        assertEquals(null, service.worldSnippet(101L), "无概要返回 null");
        assertEquals(null, service.worldSnippet(999L), "项目不存在返回 null");
        assertEquals(null, service.worldSnippet(null), "空项目 ID 返回 null");
    }
}
