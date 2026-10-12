package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.vo.WorldMapGenerateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldMapGenerationService 单元测试（位置移动与地图生成系统 M1，V1.3 第六层：仓库全 mock）。
 * <p>职责：AI 主通道成功 → source=ai；AI 失败/校验失败 → 机制兜底 source=mechanism；
 * 无地点跳过；配置关闭 → 机制兜底（零 AI）。</p>
 */
class WorldMapGenerationServiceTest {

    private final WorldMapProperties properties = new WorldMapProperties();
    private final WorldMapParser parser = new WorldMapParser(new ObjectMapper());
    private final WorldMapValidator validator = new WorldMapValidator();
    private final WorldMapMechanismGenerator mechanismGenerator = new WorldMapMechanismGenerator();

    private final WorldLocationRepository locationRepository = mock(WorldLocationRepository.class);
    private final ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
    private final ActorCharacterRelationRepository relationRepository = mock(ActorCharacterRelationRepository.class);
    private final ActorSandboxSceneRepository sceneRepository = mock(ActorSandboxSceneRepository.class);
    private final ActorWorldSettingRepository worldSettingRepository = mock(ActorWorldSettingRepository.class);
    private final PromptTemplateService templateService = mock(PromptTemplateService.class);
    private final AiSpringChatGateway aiChatGateway = mock(AiSpringChatGateway.class);
    private final UsageLogService usageLogService = mock(UsageLogService.class);
    private final WorldMapPersistService persistService = mock(WorldMapPersistService.class);

    private WorldMapGenerationService service;

    private final ActorWorldLocation loc1 = location(1L, "村口石牌坊", "地标");
    private final ActorCharacter npc1 = character(1L, "沈砚秋", "special");

    @BeforeEach
    void setUp() {
        service = new WorldMapGenerationService(properties, parser, validator, mechanismGenerator, persistService,
                locationRepository, characterRepository, relationRepository, sceneRepository, worldSettingRepository,
                templateService, aiChatGateway, usageLogService, new ObjectMapper());
        when(templateService.render(anyLong(), anyLong(), any(), any())).thenReturn("渲染后的提示词");
        when(templateService.systemMessage(anyLong(), anyLong(), any(), any())).thenReturn("系统提示");
        when(sceneRepository.findFirstByProjectIdOrderByIdAsc(anyLong())).thenReturn(Optional.empty());
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(anyLong()))
                .thenReturn(Optional.of(new ActorWorldSetting()));
        // 默认落库结果（各用例可覆盖）
        when(persistService.persist(anyLong(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new WorldMapPersistService.WorldMapPersistResult(1L, 2, "mechanism", 1, 1, 2, 1, 1));
    }

    private void seedContext() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(anyLong())).thenReturn(List.of(loc1));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(npc1));
        when(relationRepository.findByProjectId(anyLong())).thenReturn(List.of());
    }

    @Test
    void aiSuccessPath() {
        seedContext();
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any())).thenReturn(new AiChatResult(
                """
                        {"mapName":"青禾村总图","regions":[{"locationId":1,"name":"村口石牌坊","centerX":250,
                        "centerZ":430,"radius":12,"regionDesc":"村西口"}],
                        "homes":[{"name":"沈家大宅","locationId":1,"posX":240,"posZ":420,
                        "members":[{"characterRef":"npc:1","role":"户主"}]}]}
                        """, "qwen3.6-35b", 100, 200, 300, 1L));
        when(persistService.persist(anyLong(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new WorldMapPersistService.WorldMapPersistResult(1L, 2, "ai", 1, 0, 0, 1, 1));

        WorldMapGenerateVO vo = service.generate(1L, 1L, null);

        assertEquals("ai", vo.getSource(), "AI 成功应标记 ai 来源");
        assertEquals(1, vo.getRegionCount());
        assertEquals(1, vo.getHomeCount());
        verify(persistService).persist(anyLong(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aiFailureFallsBackToMechanism() {
        seedContext();
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("AI 网关不可用"));

        WorldMapGenerateVO vo = service.generate(1L, 1L, null);

        assertEquals("mechanism", vo.getSource(), "AI 失败应回退机制兜底");
        assertNotNull(vo.getSceneId());
        verify(persistService).persist(anyLong(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aiInvalidOutputFallsBackToMechanism() {
        seedContext();
        // AI 返回非法布局：区域为空 → 校验失败 → 机制兜底
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any())).thenReturn(new AiChatResult(
                "{\"mapName\":\"坏地图\",\"regions\":[],\"homes\":[]}", "qwen3.6-35b", 10, 10, 20, 1L));

        WorldMapGenerateVO vo = service.generate(1L, 1L, null);

        assertEquals("mechanism", vo.getSource(), "AI 输出非法应回退机制兜底");
    }

    @Test
    void emptyLocationsSkips() {
        when(locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(anyLong())).thenReturn(List.of());

        WorldMapGenerateVO vo = service.generate(1L, 1L, null);

        assertEquals("skipped", vo.getSource());
        verify(persistService, never()).persist(anyLong(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void disabledConfigUsesMechanismWithoutAi() {
        seedContext();
        properties.setEnabled(false);

        WorldMapGenerateVO vo = service.generate(1L, 1L, null);

        assertEquals("mechanism", vo.getSource());
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 工具 ====================

    private ActorWorldLocation location(long id, String name, String type) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        l.setType(type);
        l.setIntro(name + "简介");
        return l;
    }

    private ActorCharacter character(long id, String name, String type) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setImportance(4);
        return c;
    }
}
