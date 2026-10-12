package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.growth.persona.ActorProfileRevision;
import com.holzyn.actor.domain.growth.persona.PersonaBootstrapService;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaRevisionService;
import com.holzyn.actor.domain.growth.persona.PersonaSchemaValidator;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CharacterCardService G1b 行为测试（O6 轨道 G1b：档案编辑校验 / 真实版本历史 / 回滚 / AI 补全委托）。
 * <p>职责：验证——</p>
 * <ol>
 *   <li><b>编辑校验</b>：非法 JSON → 400；11 维结构化校验不过（OCEAN 缺失等）→ 400 逐条报错；
 *       合法档案 → savePersona(source=edited) 且 generated_by=manual；</li>
 *   <li><b>版本历史</b>：无快照行 → 回退最新一版；有快照行 → 逐行构造 VO（升序）；</li>
 *   <li><b>回滚</b>：目标版本不存在 → 404；存在 → savePersona(source=restored)；</li>
 *   <li><b>AI 补全</b>：委托 PersonaBootstrapService.completeMissingDims，失败返回 null。</li>
 * </ol>
 * <p>所属模块：domain/character/service（测试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class CharacterCardServiceG1bTest {

    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private PersonaService personaService;
    @Mock
    private PersonaRevisionService personaRevisionService;
    @Mock
    private CharacterService characterService;
    @Mock
    private PersonaBootstrapService personaBootstrapService;

    private CharacterCardService service;

    private static final long CHAR_ID = 42L;

    /** 基准夹具（合法 11 维档案，可通过校验） */
    private static final String FIXTURE = "growth/persona_sample_shenyanqiu.json";

    @BeforeEach
    void setUp() {
        service = new CharacterCardService(characterRepository, personaService, new PersonaSchemaValidator(),
                personaRevisionService, characterService, personaBootstrapService);
    }

    /** 读取基准夹具。 */
    private String fixtureJson() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "基准夹具缺失：" + FIXTURE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 构造角色行（已建档，persona_version=N）。 */
    private ActorCharacter character(int version, String personaJson) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(CHAR_ID);
        ch.setProjectId(1L);
        ch.setName("沈砚秋");
        ch.setType("special");
        ch.setPersonaVersion(version);
        ch.setPersonaJson(personaJson);
        return ch;
    }

    /** 桩好归属校验与 VO 渲染（toVO 依赖；不桩 findById——toVO 不查库）。 */
    private void stubVoBasics(ActorCharacter ch, String personaJson) {
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        when(personaService.personaJsonOf(CHAR_ID)).thenReturn(personaJson);
        when(personaService.renderFull(CHAR_ID)).thenReturn("【身份与社会】\n姓名：沈砚秋");
    }

    // ==================================================================================
    // 编辑校验
    // ==================================================================================

    @Test
    @DisplayName("编辑：非法 JSON → 400（BizException），不落库")
    void editCardRejectsInvalidJson() {
        ActorCharacter ch = character(1, null);
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        BizException ex = assertThrows(BizException.class,
                () -> service.editCard(CHAR_ID, Map.of("personaJson", "{not json")));
        assertEquals(400, ex.getCode());
        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("编辑：结构化校验不过（缺 OCEAN）→ 400 并逐条报错")
    void editCardRejectsSchemaViolation() {
        ActorCharacter ch = character(1, null);
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        // 合法 JSON 但 personality 维存在且缺 ocean（校验器报 error）
        String bad = "{\"schema_version\":2,\"dims\":{\"personality\":{\"traits_summary\":\"沉稳\"}}}";
        BizException ex = assertThrows(BizException.class,
                () -> service.editCard(CHAR_ID, Map.of("personaJson", bad)));
        assertEquals(400, ex.getCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("personality.ocean 缺失"));
        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("编辑：合法档案 → savePersona(source=edited) 且 generated_by=manual，返回新 VO")
    void editCardSavesValidPersona() throws IOException {
        String json = fixtureJson();
        ActorCharacter ch = character(2, json);
        stubVoBasics(ch, json);
        when(personaService.savePersona(eq(CHAR_ID), any(), eq("edited"))).thenReturn(2);

        CharacterCardVO vo = service.editCard(CHAR_ID, Map.of("personaJson", json));

        assertNotNull(vo);
        assertEquals(2, vo.version());
        org.mockito.ArgumentCaptor<PersonaProfile> captor = org.mockito.ArgumentCaptor.forClass(PersonaProfile.class);
        verify(personaService).savePersona(eq(CHAR_ID), captor.capture(), eq("edited"));
        assertEquals("manual", captor.getValue().getGeneratedBy());
    }

    // ==================================================================================
    // 版本历史
    // ==================================================================================

    @Test
    @DisplayName("版本历史：无快照行（存量角色）→ 回退返回最新一版")
    void versionHistoryFallsBackToLatest() {
        ActorCharacter ch = character(3, "{\"schema_version\":2}");
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        when(characterRepository.findById(CHAR_ID)).thenReturn(Optional.of(ch));
        when(personaRevisionService.listVersions(CHAR_ID)).thenReturn(List.of());
        when(personaService.personaJsonOf(CHAR_ID)).thenReturn("{\"schema_version\":2}");
        when(personaService.renderFull(CHAR_ID)).thenReturn("渲染文本");

        List<CharacterCardVO> out = service.versionHistory(CHAR_ID);
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).version());
    }

    @Test
    @DisplayName("版本历史：有快照行 → 逐行构造 VO（升序），旧版按旧内容+当前投影渲染")
    void versionHistoryFromRevisions() {
        ActorCharacter ch = character(2, "{\"schema_version\":2}");
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        when(characterRepository.findById(CHAR_ID)).thenReturn(Optional.of(ch));
        ActorProfileRevision r1 = new ActorProfileRevision();
        r1.setPersonaVersion(1);
        r1.setPersonaJson("{\"schema_version\":2,\"persona_version\":1}");
        r1.setGeneratedBy("ai_bootstrap");
        ActorProfileRevision r2 = new ActorProfileRevision();
        r2.setPersonaVersion(2);
        r2.setPersonaJson("{\"schema_version\":2,\"persona_version\":2}");
        r2.setGeneratedBy("manual");
        when(personaRevisionService.listVersions(CHAR_ID)).thenReturn(List.of(r1, r2));
        when(personaService.renderFullOf(anyString(), eq(CHAR_ID))).thenReturn("旧版渲染");

        List<CharacterCardVO> out = service.versionHistory(CHAR_ID);
        assertEquals(2, out.size());
        assertEquals(1, out.get(0).version());
        assertEquals("ai_bootstrap", out.get(0).source());
        assertEquals(2, out.get(1).version());
        assertEquals("manual", out.get(1).source());
        assertEquals("旧版渲染", out.get(1).personaRenderText());
    }

    // ==================================================================================
    // 回滚
    // ==================================================================================

    @Test
    @DisplayName("回滚：目标版本不存在 → 404")
    void restoreVersionNotFound() {
        ActorCharacter ch = character(2, null);
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        when(personaRevisionService.findVersion(CHAR_ID, 99)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.restoreVersion(CHAR_ID, 99));
        assertEquals(404, ex.getCode());
        verify(personaService, never()).savePersona(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("回滚：目标版本存在 → savePersona(source=restored) 保存为新版本")
    void restoreVersionSaves() {
        String revJson = "{\"schema_version\":2,\"persona_version\":1}";
        ActorCharacter ch = character(2, revJson);
        stubVoBasics(ch, revJson);
        ActorProfileRevision rev = new ActorProfileRevision();
        rev.setPersonaVersion(1);
        rev.setPersonaJson(revJson);
        when(personaRevisionService.findVersion(CHAR_ID, 1)).thenReturn(rev);
        when(personaService.savePersona(eq(CHAR_ID), any(), eq("restored"))).thenReturn(3);

        CharacterCardVO vo = service.restoreVersion(CHAR_ID, 1);
        assertNotNull(vo);
        verify(personaService).savePersona(eq(CHAR_ID), any(), eq("restored"));
    }

    // ==================================================================================
    // AI 补全缺失维
    // ==================================================================================

    @Test
    @DisplayName("AI 补全：委托 bootstrap.completeMissingDims；失败返回 null（不抛异常）")
    void completeMissingDimsDelegates() {
        ActorCharacter ch = character(1, null);
        when(characterService.requireOwned(CHAR_ID)).thenReturn(ch);
        when(personaBootstrapService.completeMissingDims(1L, CHAR_ID)).thenReturn(null);
        assertNull(service.completeMissingDims(CHAR_ID));

        PersonaProfile filled = PersonaProfile.empty(1L, CHAR_ID);
        when(personaBootstrapService.completeMissingDims(1L, CHAR_ID)).thenReturn(filled);
        stubVoBasics(ch, "{\"schema_version\":2}");
        assertNotNull(service.completeMissingDims(CHAR_ID));
    }

    // ==================================================================================
    // 依赖的私有工具（PersonaJson 可解析性）
    // ==================================================================================

    @Test
    @DisplayName("基准夹具可被 PersonaJson 解析（测试自身依赖检查）")
    void fixtureParses() throws IOException {
        PersonaProfile p = PersonaJson.read(fixtureJson());
        assertNotNull(p);
        assertEquals(11, new PersonaSchemaValidator().validate(p, null).getStoredDims().size());
    }
}
