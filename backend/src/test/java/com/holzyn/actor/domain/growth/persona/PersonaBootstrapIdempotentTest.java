package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.growth.config.GrowthPersonaProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PersonaBootstrapService G1b 幂等行为测试（O6 轨道 G1b：恢复基线不再重跑 43 次人格引导）。
 * <p>职责：验证三件事——</p>
 * <ol>
 *   <li><b>幂等跳过</b>：非 force + 开关打开 + 段维度已全覆盖 → 零 AI 调用、复用既有档案；</li>
 *   <li><b>force 重跑</b>：force=true 无视幂等，两段都调 AI；</li>
 *   <li><b>AI 补全缺失维</b>：无缺失 → 零 AI；有缺失 → 只跑对应段，且已覆盖维被保留
 *       （AI 只许填缺、不许改已有）。</li>
 * </ol>
 * <p>AI 路径用 Mockito 桩掉网关（返回段 A / 段 B 合法 JSON），断言落库入参与保留口径。</p>
 * <p>所属模块：domain/growth/persona（测试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PersonaBootstrapIdempotentTest {

    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorWorldSettingRepository worldSettingRepository;
    @Mock
    private WorldLocationRepository worldLocationRepository;
    @Mock
    private ActorCharacterRelationRepository relationRepository;
    @Mock
    private ActorWorldClockRepository worldClockRepository;
    @Mock
    private PersonaService personaService;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private AiSpringChatGateway aiChatGateway;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private UsageLogService usageLogService;

    private GrowthPersonaProperties properties;
    private PersonaBootstrapService bootstrap;

    private static final long PROFILE_ID = 42L;

    /** 段 A 输出（5 维有内容） */
    private static final String SEG_A_JSON = "{"
            + "\"dims\":{\"identity\":{\"aliases\":[\"阿青\"]},"
            + "\"appearance\":{\"build\":\"中等偏瘦\"},"
            + "\"personality\":{\"ocean\":{\"openness\":0.6,\"conscientiousness\":0.6,\"extraversion\":0.4,\"agreeableness\":0.7,\"neuroticism\":0.3}},"
            + "\"values\":{\"life_goals\":[\"守住村子\"]},"
            + "\"experience\":{\"origin\":\"青禾村生人\"}}}";

    /** 段 B 输出（6 维有内容；voice/mask 由 AI 填） */
    private static final String SEG_B_JSON = "{"
            + "\"dims\":{\"physio\":{\"chronotype\":\"night_owl\"},"
            + "\"ability\":{\"knowledge_domains\":[\"武艺\"]},"
            + "\"voice\":{\"dialect\":\"青山腔\"},"
            + "\"habits\":{\"sleep_habits\":[\"子时后入睡\"]},"
            + "\"development\":{\"stage\":\"YOUTH\"},"
            + "\"mask\":{\"context_suppression\":{\"before_authority\":0.3}}}}";

    @BeforeEach
    void setUp() {
        properties = new GrowthPersonaProperties();
        properties.setBootstrapIdempotent(true);
        bootstrap = new PersonaBootstrapService(characterRepository, worldSettingRepository, worldLocationRepository,
                relationRepository, worldClockRepository, personaService, new PersonaSchemaValidator(),
                promptTemplateService, aiChatGateway, currentUserProvider, usageLogService, properties);
    }

    // ==================================================================================
    // 幂等跳过
    // ==================================================================================

    @Test
    @DisplayName("非 force + 11 维全有内容 + 开关开 → 两段都跳过，零 AI 调用，复用既有档案")
    void bootstrapSkipsWhenFullyCovered() {
        ActorCharacter ch = character("沈砚秋", 3);
        when(characterRepository.findById(PROFILE_ID)).thenReturn(Optional.of(ch));
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(fullProfile());
        when(personaService.profileOf(PROFILE_ID)).thenReturn(chProfile(3));
        when(personaService.validate(PROFILE_ID)).thenReturn(okValidation());

        PersonaProfile result = bootstrap.bootstrap(1L, PROFILE_ID, null);

        assertNotNull(result);
        assertEquals(3, result.getPersonaVersion());
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }

    @Test
    @DisplayName("非 force + 段 A 缺一维（experience 空）→ 段 A 跑 AI、段 B 跳过（只补缺失段）")
    void bootstrapSkipsOnlyCoveredSegment() {
        ActorCharacter ch = character("沈砚秋", 2);
        when(characterRepository.findById(PROFILE_ID)).thenReturn(Optional.of(ch));
        // 段 A 的 experience 空（无内容）→ 段 A 不满足"全覆盖"，会跑；其余维有内容
        PersonaProfile partial = fullProfile();
        partial.getDims().getExperience().setOrigin(null);
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(partial);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("sys");
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(SEG_A_JSON, "qwen", 10, 10, 20, 67L));
        when(personaService.savePersona(eq(PROFILE_ID), any(), anyString())).thenReturn(3);
        when(personaService.profileOf(PROFILE_ID)).thenReturn(chProfile(3));
        when(personaService.validate(PROFILE_ID)).thenReturn(okValidation());

        bootstrap.bootstrap(1L, PROFILE_ID, null);

        // 段 A 调了一次 AI；段 B 幂等跳过（不调）
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }

    @Test
    @DisplayName("force=true → 无视幂等，两段都调 AI（编辑器「重新生成」语义）")
    void bootstrapForcesRerun() {
        ActorCharacter ch = character("沈砚秋", 2);
        when(characterRepository.findById(PROFILE_ID)).thenReturn(Optional.of(ch));
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(fullProfile());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("sys");
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        // 段 A 与段 B 各返回一次合法输出
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(SEG_A_JSON, "qwen", 10, 10, 20, 67L))
                .thenReturn(new AiChatResult(SEG_B_JSON, "qwen", 10, 10, 20, 67L));
        when(personaService.savePersona(eq(PROFILE_ID), any(), anyString())).thenReturn(3, 4);
        when(personaService.profileOf(PROFILE_ID)).thenReturn(chProfile(4));
        when(personaService.validate(PROFILE_ID)).thenReturn(okValidation());

        PersonaProfile result = bootstrap.bootstrap(1L, PROFILE_ID, null, true);

        assertNotNull(result);
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }

    @Test
    @DisplayName("开关关闭（bootstrapIdempotent=false）→ 非 force 也重跑")
    void idempotentDisabledReruns() {
        properties.setBootstrapIdempotent(false);
        ActorCharacter ch = character("沈砚秋", 3);
        when(characterRepository.findById(PROFILE_ID)).thenReturn(Optional.of(ch));
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(fullProfile());
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("sys");
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(SEG_A_JSON, "qwen", 10, 10, 20, 67L))
                .thenReturn(new AiChatResult(SEG_B_JSON, "qwen", 10, 10, 20, 67L));
        when(personaService.savePersona(eq(PROFILE_ID), any(), anyString())).thenReturn(4, 5);
        when(personaService.profileOf(PROFILE_ID)).thenReturn(chProfile(5));
        when(personaService.validate(PROFILE_ID)).thenReturn(okValidation());

        bootstrap.bootstrap(1L, PROFILE_ID, null);
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }

    // ==================================================================================
    // AI 补全缺失维
    // ==================================================================================

    @Test
    @DisplayName("补全：11 维全有内容 → 零 AI 调用，直接返回当前档案")
    void completeMissingDimsNoopWhenCovered() {
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(fullProfile());

        PersonaProfile result = bootstrap.completeMissingDims(1L, PROFILE_ID);

        assertNotNull(result);
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class));
    }

    @Test
    @DisplayName("补全：空壳维（{} 有对象无内容）也算缺失——B 段缺 voice/mask → 只跑段 B，已覆盖维原样保留")
    void completeMissingDimsFillsOnlyMissingAndPreservesExisting() {
        ActorCharacter ch = character("沈砚秋", 2);
        when(characterRepository.findById(PROFILE_ID)).thenReturn(Optional.of(ch));
        // 当前档案：A 段 5 维 + B 段的 physio/ability 已存；voice 是空壳对象 {}（无内容）、mask 缺失
        PersonaProfile current = fullProfile();
        current.getDims().setVoice(new PersonaDims.Voice()); // 空壳：无任何字段
        current.getDims().setMask(null);                     // 缺失
        when(personaService.profileOrEmpty(PROFILE_ID)).thenReturn(current);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("sys");
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        // 段 B 输出：physio/ability 给新值、voice/mask 填缺
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(SEG_B_JSON, "qwen", 10, 10, 20, 67L));
        when(personaService.savePersona(eq(PROFILE_ID), any(), anyString())).thenReturn(3);
        when(personaService.profileOf(PROFILE_ID)).thenReturn(current);
        when(personaService.validate(PROFILE_ID)).thenReturn(okValidation());

        bootstrap.completeMissingDims(1L, PROFILE_ID);

        // 断言落库入参：voice/mask 来自 AI（填缺），physio/ability 保留原值（AI 新值被换回）
        ArgumentCaptor<PersonaProfile> captor = ArgumentCaptor.forClass(PersonaProfile.class);
        verify(personaService).savePersona(eq(PROFILE_ID), captor.capture(), anyString());
        PersonaProfile saved = captor.getValue();
        // 已覆盖维被保留：physio 仍是 early_bird（AI 输出的是 night_owl）、ability 仍是"武艺"
        assertEquals("early_bird", saved.getDims().getPhysio().getChronotype());
        assertEquals(List.of("武艺"), saved.getDims().getAbility().getKnowledgeDomains());
        // 缺失维由 AI 填入：voice / mask
        assertEquals("青山腔", saved.getDims().getVoice().getDialect());
        assertEquals(0.3d, saved.getDims().getMask().getContextSuppression().get("before_authority"), 1e-9);
    }

    // ==================================================================================
    // 工具
    // ==================================================================================

    /** 构造角色行（指定 persona_version）。 */
    private ActorCharacter character(String name, int version) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(PROFILE_ID);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setType("special");
        ch.setPersonaVersion(version);
        return ch;
    }

    /** 构造指定版本的档案对象（profileOf 返回值）。 */
    private PersonaProfile chProfile(int version) {
        PersonaProfile p = PersonaProfile.empty(1L, PROFILE_ID);
        p.setPersonaVersion(version);
        return p;
    }

    /** 空的合法校验结果（仅供完成日志的覆盖率统计调用，避免 mock 返回 null）。 */
    private PersonaValidationResult okValidation() {
        return new PersonaValidationResult(List.of(), List.of(), Map.of());
    }

    /** 构造 11 维全有内容的档案（幂等跳过/无缺失判定的基准；每个维度都有实际内容）。 */
    private PersonaProfile fullProfile() {
        PersonaProfile p = PersonaProfile.empty(1L, PROFILE_ID);
        PersonaProfile.Dims d = new PersonaProfile.Dims();
        d.setIdentity(new PersonaDims.Identity());
        d.getIdentity().setAliases(List.of("阿青"));
        d.setAppearance(new PersonaDims.Appearance());
        d.getAppearance().setBuild("中等偏瘦");
        d.setPhysio(new PersonaDims.Physio());
        d.getPhysio().setChronotype("early_bird");
        PersonaDims.Personality personality = new PersonaDims.Personality();
        PersonaDims.Ocean ocean = new PersonaDims.Ocean();
        ocean.setOpenness(0.5);
        ocean.setConscientiousness(0.5);
        ocean.setExtraversion(0.5);
        ocean.setAgreeableness(0.5);
        ocean.setNeuroticism(0.5);
        personality.setOcean(ocean);
        d.setPersonality(personality);
        d.setValues(new PersonaDims.Values());
        d.getValues().setLifeGoals(List.of("守住村子"));
        d.setAbility(new PersonaDims.Ability());
        d.getAbility().setKnowledgeDomains(List.of("武艺"));
        d.setVoice(new PersonaDims.Voice());
        d.getVoice().setDialect("青山腔");
        d.setHabits(new PersonaDims.Habits());
        d.getHabits().setSleepHabits(List.of("早睡"));
        d.setExperience(new PersonaDims.Experience());
        d.getExperience().setOrigin("青禾村生人");
        d.setDevelopment(new PersonaDims.Development());
        d.getDevelopment().setStage("YOUTH");
        d.setMask(new PersonaDims.Mask());
        d.getMask().setContextSuppression(Map.of("before_authority", 0.3d));
        p.setDims(d);
        return p;
    }
}
