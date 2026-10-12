package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 此刻可发生空间核心单元测试（O6 轨道 G2）。
 * <p>职责：space_compliance 候选剔除（与硬门控 forbidden 冲突 → 剔除 + 硬禁止并入）、
 * 空间内行动判定（含匹配双向）、决策落点记录（空间内/外计数）、渲染块截断上限。</p>
 */
@ExtendWith(MockitoExtension.class)
class GrowthSpaceServiceTest {

    @Mock
    private GrowthSpaceRepository spaceRepository;
    @Mock
    private GrowthSpaceLogRepository spaceLogRepository;
    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorNpcStateRepository stateRepository;
    @Mock
    private GrowthGateService growthGateService;
    @Mock
    private BrainTierService brainTierService;
    @Mock
    private PersonaService personaService;
    @Mock
    private NpcStateService npcStateService;
    @Mock
    private com.holzyn.actor.domain.settings.service.PromptTemplateService promptTemplateService;

    private GrowthProperties properties;
    private GrowthMetricsService metrics;
    private GrowthSpaceService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        metrics = new GrowthMetricsService();
        service = new GrowthSpaceService(spaceRepository, spaceLogRepository, characterRepository,
                stateRepository, properties, growthGateService, brainTierService, metrics,
                personaService, npcStateService, null, null, null, null, null, null);
    }

    private HardGateResult gate(List<HardGateResult.ForbiddenItem> forbidden) {
        return new HardGateResult("npc:1", "ELDER", 70, 0.5,
                List.of("WALK", "TALK"), forbidden, List.of("感官退化"), Map.of());
    }

    private GrowthSpaceParse.GrowthSpaceDoc doc(List<GrowthSpaceParse.ActionItem> actions,
                                                List<GrowthSpaceParse.ForbiddenItem> forbidden) {
        return new GrowthSpaceParse.GrowthSpaceDoc(
                new GrowthSpaceParse.Inner(null, "主导念头", null),
                List.of(new GrowthSpaceParse.StateItem("发呆", 0.5, null)), actions, forbidden);
    }

    // ==================== space_compliance 候选剔除 ====================

    @Test
    @DisplayName("候选与硬门控冲突 → 剔除 + 计拦截；硬禁止项并入空间 forbidden")
    void pruneConflicts() {
        HardGateResult gate = gate(List.of(new HardGateResult.ForbiddenItem("重体力劳作、挑担垦荒", "老年期")));
        GrowthSpaceParse.GrowthSpaceDoc ai = doc(
                List.of(new GrowthSpaceParse.ActionItem("去村口晒太阳", 0.6, null, null),
                        new GrowthSpaceParse.ActionItem("去挑担垦荒", 0.2, null, null),
                        new GrowthSpaceParse.ActionItem("", 0.1, null, null)),
                List.of(new GrowthSpaceParse.ForbiddenItem("远行", "年迈")));
        GrowthSpaceParse.GrowthSpaceDoc pruned = service.pruneConflicts(gate, ai, "npc:1");
        assertEquals(1, pruned.possibleActions().size(), "冲突候选与空行动被剔除");
        assertEquals("去村口晒太阳", pruned.possibleActions().get(0).action());
        // 硬门控禁止项并入（去重）
        assertTrue(pruned.forbidden().stream().anyMatch(f -> f.what().equals("重体力劳作、挑担垦荒")));
        assertTrue(pruned.forbidden().stream().anyMatch(f -> f.what().equals("远行")));
        assertEquals(1L, metrics.snapshot().get("spaceComplianceIntercepts"), "剔除计拦截");
    }

    @Test
    @DisplayName("无冲突候选全部保留；空 forbidden 不产生拦截")
    void pruneNoConflict() {
        HardGateResult gate = gate(List.of());
        GrowthSpaceParse.GrowthSpaceDoc ai = doc(
                List.of(new GrowthSpaceParse.ActionItem("去村口晒太阳", 0.6, null, null)),
                List.of());
        GrowthSpaceParse.GrowthSpaceDoc pruned = service.pruneConflicts(gate, ai, "npc:1");
        assertEquals(1, pruned.possibleActions().size());
        assertEquals(0L, metrics.snapshot().get("spaceComplianceIntercepts"));
    }

    // ==================== 空间内行动判定与落点记录 ====================

    @Test
    @DisplayName("空间内行动判定：含匹配双向（候选含行动/行动含候选）")
    void isActionInSpace() {
        GrowthSpace space = new GrowthSpace();
        space.setProjectId(1L);
        space.setNpcRef("npc:1");
        space.setSpaceJson(doc(
                List.of(new GrowthSpaceParse.ActionItem("去村口晒太阳", 0.6, null, null),
                        new GrowthSpaceParse.ActionItem("张罗吃食填肚子", 0.5, "腹饥", null)),
                List.of()).toJson());
        when(spaceRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(space));
        assertTrue(service.isActionInSpace(1L, "npc:1", "去村口晒太阳"));
        assertTrue(service.isActionInSpace(1L, "npc:1", "慢悠悠去村口晒太阳"), "行动含候选子串也算命中");
        assertTrue(service.isActionInSpace(1L, "npc:1", "张罗吃食"), "候选含行动子串也算命中");
        assertFalse(service.isActionInSpace(1L, "npc:1", "去悬崖边摘花"));
        assertFalse(service.isActionInSpace(1L, "npc:1", ""));
        assertFalse(service.isActionInSpace(1L, "npc:1", null));
    }

    @Test
    @DisplayName("落点记录：空间内/外各计一次")
    void recordDecisionInSpace() {
        GrowthSpace space = new GrowthSpace();
        space.setProjectId(1L);
        space.setNpcRef("npc:1");
        space.setSpaceJson(doc(
                List.of(new GrowthSpaceParse.ActionItem("去村口晒太阳", 0.6, null, null)),
                List.of()).toJson());
        when(spaceRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(space));
        assertTrue(service.recordDecisionInSpace(1L, "npc:1", "去村口晒太阳"));
        assertFalse(service.recordDecisionInSpace(1L, "npc:1", "去悬崖边摘花"));
        Map<String, Object> s = metrics.snapshot();
        assertEquals(1L, s.get("decisionInSpace"));
        assertEquals(1L, s.get("decisionOutside"));
        assertEquals(50.0, ((Number) s.get("inSpaceRate")).doubleValue(), 1e-9);
    }

    // ==================== 渲染块 ====================

    @Test
    @DisplayName("渲染块：四段齐全；超长截断到 injectMaxChars（上限内完整、上限外截断）")
    void renderBlock() {
        GrowthProperties.Space cfg = properties.getSpace();
        cfg.setInjectMaxChars(800);
        String json = doc(
                List.of(new GrowthSpaceParse.ActionItem("去村口晒太阳", 0.6, null, "村口"),
                        new GrowthSpaceParse.ActionItem("张罗吃食填肚子", 0.5, "腹饥", null)),
                List.of(new GrowthSpaceParse.ForbiddenItem("远行", "年迈"))).toJson();
        GrowthSpace space = new GrowthSpace();
        space.setSpaceJson(json);
        String block = service.renderBlock(space);
        assertTrue(block.contains("【此刻可发生空间】"));
        assertTrue(block.contains("内心念头"));
        assertTrue(block.contains("此刻可发生的行动"));
        assertTrue(block.contains("此刻不可发生"));
        assertTrue(block.length() <= cfg.getInjectMaxChars(), "上限内不截断");
        // 上限收紧 → 截断生效（仍以头部保留为原则）
        cfg.setInjectMaxChars(24);
        String cut = service.renderBlock(space);
        assertTrue(cut.length() <= 24, "截断生效");
        assertTrue(cut.startsWith("【此刻可发生空间】"), "截断保留头部标题");
        assertEquals("", service.renderBlock(null));
    }

    // ==================== 关闭开关：程序化零 AI ====================

    @Test
    @DisplayName("空间总开关关闭：ensureSpace 走程序化兜底（不触发 AI 依赖链）")
    void disabledSpaceFallsBackProgrammatic() {
        // Mockito 对未 stub 的 save 返回 null → 实现会返回 null 导致断言失败；stub 返回入参（贴近真实 JPA 行为）
        when(spaceRepository.save(org.mockito.ArgumentMatchers.any(GrowthSpace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("测试");
        ch.setStage("YOUTH");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.empty());
        when(growthGateService.gate(1L, "npc:1")).thenReturn(HardGateResult.lenient("npc:1"));

        properties.setEnabled(false);
        GrowthSpace space = service.ensureSpace(1L, "npc:1", 1000L);
        assertTrue(space != null && GrowthSpace.SOURCE_PROGRAMMATIC.equals(space.getSource()));
        verify(spaceRepository, never()).save(org.mockito.ArgumentMatchers.any(GrowthSpace.class));

        // dormant 档也走程序化（持久化）
        properties.setEnabled(true);
        when(brainTierService.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        GrowthSpace dormant = service.ensureSpace(1L, "npc:1", 1000L);
        assertTrue(dormant != null && GrowthSpace.SOURCE_PROGRAMMATIC.equals(dormant.getSource()));
        verify(spaceRepository, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.any(GrowthSpace.class));
    }

    @Test
    @DisplayName("upsert 语义：已有空间行时复用主键（唯一索引 uk_growth_space_ref 不冲突）")
    void regenerateUpsertsExistingRow() {
        when(spaceRepository.save(org.mockito.ArgumentMatchers.any(GrowthSpace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("测试");
        ch.setStage("YOUTH");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.empty());
        when(growthGateService.gate(1L, "npc:1")).thenReturn(HardGateResult.lenient("npc:1"));
        when(brainTierService.tierOf(1L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        GrowthSpace existing = new GrowthSpace();
        existing.setId(55L);
        existing.setProjectId(1L);
        existing.setNpcRef("npc:1");
        when(spaceRepository.findByProjectIdAndNpcRef(1L, "npc:1")).thenReturn(Optional.of(existing));
        GrowthSpace saved = service.ensureSpace(1L, "npc:1", 1000L);
        assertEquals(55L, saved.getId(), "复用已有行主键（一行一 NPC 的 upsert）");
    }

    // ==================== 世界观简报注入（2026-10-07 根治） ====================

    /** 空间生成 Prompt 注入世界观简报占位符（world_brief 贴合世界地理/势力/规则/文化）。 */
    @Test
    void renderGeneratePrompt_injectsWorldBriefPlaceholder() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldBrief(1L)).thenReturn("青禾村四面环山，灵脉沉寂，沈氏宗族掌村。");
        setField(service, "aiContextInjector", injector);
        setField(service, "promptTemplateService", promptTemplateService);

        when(promptTemplateService.render(any(), eq(1L), eq("growth_space_generate"), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> ph = inv.getArgument(3);
                    worldBriefCaptured = String.valueOf(ph.get("world_brief"));
                    return "rendered";
                });

        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈砚秋");
        ch.setStage("ELDER");
        invokeRenderGeneratePrompt(1L, 1L, ch, 1000L);
        assertEquals("青禾村四面环山，灵脉沉寂，沈氏宗族掌村。", worldBriefCaptured);
    }

    /** 注入器未装配 → world_brief 占位符回退「（无世界观设定）」（零阻断）。 */
    @Test
    void renderGeneratePrompt_worldBriefFallsBackWhenInjectorMissing() throws Exception {
        setField(service, "aiContextInjector", null);
        setField(service, "promptTemplateService", promptTemplateService);
        when(promptTemplateService.render(any(), eq(1L), eq("growth_space_generate"), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> ph = inv.getArgument(3);
                    worldBriefCaptured = String.valueOf(ph.get("world_brief"));
                    return "rendered";
                });
        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈砚秋");
        ch.setStage("ELDER");
        invokeRenderGeneratePrompt(1L, 1L, ch, 1000L);
        assertEquals("（无世界观设定）", worldBriefCaptured);
    }

    // ==================== 批5 世界与叙事：段1A+1C+2D+2E 前缀（2026-10-10） ====================

    /**
     * 空间生成 Prompt 前置 V2.0 memoryChainContext 前缀（段1A 世界观 + 段1C 角色卡 +
     * 段2D 当前世界动态 + 段2E 当前状态）——AI 生成「此刻可发生空间」时知道世界与自身处境。
     */
    @Test
    void renderGeneratePrompt_prependsMemoryChainContextPrefix() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(1L, "npc:1", 1000L))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋（70 岁，村长）\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        setField(service, "aiContextInjector", injector);
        setField(service, "promptTemplateService", promptTemplateService);
        when(promptTemplateService.render(any(), eq(1L), eq("growth_space_generate"), any()))
                .thenReturn("rendered");

        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈砚秋");
        ch.setStage("ELDER");
        Object returned = invokeRenderGeneratePromptReturn(1L, 1L, ch, 1000L);
        String prompt = String.valueOf(returned);
        assertTrue(prompt.contains("【世界观】"), "应含段1A 世界观: " + prompt);
        assertTrue(prompt.contains("【我的身份与处境】"), "应含段1C 角色卡: " + prompt);
        assertTrue(prompt.contains("【当前世界动态】"), "应含段2D: " + prompt);
        assertTrue(prompt.contains("【当前状态】"), "应含段2E: " + prompt);
        assertTrue(prompt.contains("rendered"), "模板 prompt 保留: " + prompt);
    }

    /** 注入器 memoryChainContext 返回空 → 仅模板 prompt（零阻断降级）。 */
    @Test
    void renderGeneratePrompt_blankMemoryChainPrefixDegrades() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(1L, "npc:1", 1000L)).thenReturn("");
        setField(service, "aiContextInjector", injector);
        setField(service, "promptTemplateService", promptTemplateService);
        when(promptTemplateService.render(any(), eq(1L), eq("growth_space_generate"), any()))
                .thenReturn("rendered");

        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈砚秋");
        ch.setStage("ELDER");
        Object returned = invokeRenderGeneratePromptReturn(1L, 1L, ch, 1000L);
        assertEquals("rendered", String.valueOf(returned), "前缀空 → 原模板 prompt");
    }

    private String worldBriefCaptured;

    private void invokeRenderGeneratePrompt(Long uid, Long projectId, ActorCharacter ch, Long gameSecond)
            throws Exception {
        java.lang.reflect.Method m = GrowthSpaceService.class.getDeclaredMethod(
                "renderGeneratePrompt", Long.class, Long.class, ActorCharacter.class, Long.class);
        m.setAccessible(true);
        m.invoke(service, uid, projectId, ch, gameSecond);
    }

    private Object invokeRenderGeneratePromptReturn(Long uid, Long projectId, ActorCharacter ch, Long gameSecond)
            throws Exception {
        java.lang.reflect.Method m = GrowthSpaceService.class.getDeclaredMethod(
                "renderGeneratePrompt", Long.class, Long.class, ActorCharacter.class, Long.class);
        m.setAccessible(true);
        return m.invoke(service, uid, projectId, ch, gameSecond);
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
