package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.project.service.ProjectService;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.LocationNormalizationService;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenPersistService（M2-3：6 维产物 → 分节/地点映射预览 + 确认落库）单元测试。
 * <p>职责：验证——预览映射（core_laws→magicSystem、space_time→geography+地点、society→factions、
 * history_culture→culture+history、existence/information→freeText）、确认落库（世界观分节 +
 * 地点按名去重合并）、维度缺失时预览/确认 400。</p>
 */
class WorldGenPersistServiceTest {

    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private ProjectService projectService;
    private ActorProjectRepository projectRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private WorldLocationRepository locationRepository;
    private LocationNormalizationService locationNormalizationService;
    private WorldGenPersistService service;

    private ActorWorldgenTask task;
    private final List<ActorWorldgenNodeAttempt> attempts = new ArrayList<>();

    /** 6 维 agent 节点（链式顺序），输出变量/agentType 对齐 build_v1 */
    private static final String NODES_JSON = """
            [ {"id":"agent_corelaws","label":"维度一","nodeType":"agent","agentType":"novel-worldbuilder-corelaws","outputVariable":"agent_corelaws_output"},
              {"id":"agent_spacetime","label":"维度二","nodeType":"agent","agentType":"novel-worldbuilder-spacetime","outputVariable":"agent_spacetime_output"},
              {"id":"agent_society","label":"维度三","nodeType":"agent","agentType":"novel-worldbuilder-society","outputVariable":"agent_society_output"},
              {"id":"agent_historyculture","label":"维度四","nodeType":"agent","agentType":"novel-worldbuilder-historyculture","outputVariable":"agent_historyculture_output"},
              {"id":"agent_existence","label":"维度五","nodeType":"agent","agentType":"novel-worldbuilder-existence","outputVariable":"agent_existence_output"},
              {"id":"agent_information","label":"维度六","nodeType":"agent","agentType":"novel-worldbuilder-information","outputVariable":"agent_information_output"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"agent_corelaws"},
              {"source":"agent_corelaws","target":"agent_spacetime"},
              {"source":"agent_spacetime","target":"agent_society"},
              {"source":"agent_society","target":"agent_historyculture"},
              {"source":"agent_historyculture","target":"agent_existence"},
              {"source":"agent_existence","target":"agent_information"},
              {"source":"agent_information","target":"__end__"} ]
            """;

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        projectService = Mockito.mock(ProjectService.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        locationNormalizationService = Mockito.mock(LocationNormalizationService.class);
        service = new WorldGenPersistService(taskRepository, workflowRepository, attemptRepository,
                new WorldGenDefinitionParser(), projectService, projectRepository,
                worldSettingRepository, locationRepository, locationNormalizationService);
        attempts.clear();

        task = new ActorWorldgenTask();
        task.setId(10L);
        task.setWorkflowId(1L);
        task.setProjectId(5L);
        task.setStatus(WorldGenStatus.SUCCEEDED);
        task.setInputJson("{\"premise\":\"废土之上\",\"genre\":\"科幻末世\",\"language\":\"中文\"}");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode("worldgen_build_v1");
        workflow.setName("世界观生成（6 维链式）");
        workflow.setVersion(1);
        workflow.setNodesJson(NODES_JSON);
        workflow.setEdgesJson(EDGES_JSON);
        workflow.setVariablesJson("[]");
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));

        ActorProject project = new ActorProject();
        project.setId(5L);
        project.setName("废土纪元");
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project));
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.empty());
        when(locationRepository.findByProjectId(5L)).thenReturn(List.of());
        when(locationNormalizationService.normalizedName(anyLong(), any())).thenAnswer(
                inv -> inv.getArgument(1));

        // 6 个维度成功尝试（含完整字段）
        for (String nodeId : List.of("agent_corelaws", "agent_spacetime", "agent_society",
                "agent_historyculture", "agent_existence", "agent_information")) {
            attempts.add(attempt(nodeId, outputFor(nodeId)));
        }
        // thenAnswer：调用时取 attempts 当前状态（供「移除某维度 → 校验 400」用例）
        when(attemptRepository.findByTaskIdOrderByIdAsc(10L)).thenAnswer(inv -> new ArrayList<>(attempts));
    }

    private static ActorWorldgenNodeAttempt attempt(String nodeId, String output) {
        ActorWorldgenNodeAttempt a = new ActorWorldgenNodeAttempt();
        a.setId((long) nodeId.hashCode());
        a.setTaskId(10L);
        a.setNodeId(nodeId);
        a.setNodeOrder(1);
        a.setStepType("agent");
        a.setStatus(WorldGenStatus.SUCCEEDED);
        a.setOutputJson(output);
        return a;
    }

    private static String outputFor(String nodeId) {
        return switch (nodeId) {
            case "agent_corelaws" -> """
                    {"core_laws":{"essence":"灵气循雾脉流转","power_system":"四阶修炼体系","axioms":[{"name":"雾脉守恒","statement":"总量守恒","cost":"施法耗灵","boundary":"禁区无效","enforcement":"雾脉自行纠正"}],"taboos":["弑杀雾脉之灵"],"power_manifestation":"雾光环绕"}}
                    """;
            case "agent_spacetime" -> """
                    {"space_time":{"essence":"山海分层","world_layout":"九洲分立","key_locations":[{"name":"雾都","terrain":"盆地","feature":"灵气最浓","risk":"雾兽出没","controlling_force":"雾脉宗"},{"name":"枯脉岭","terrain":"山脉","feature":"灵气枯竭","risk":"无","controlling_force":"无人"}],"ecology":"灵兽栖息","era":"玄黄纪","environment_texture":"晨雾弥漫"}}
                    """;
            case "agent_society" -> """
                    {"society":{"essence":"宗门控权","races":[{"name":"人族","traits":"习气","population":"众多","social_status":"平民"}],"class_structure":"宗门为尊","political_system":"宗派自治","forces":[{"name":"雾脉宗","type":"宗门","base_of_power":"灵气垄断","goals":"护脉","methods":"巡查"}],"force_relations":[{"source":"雾脉宗","target":"散修","relation":"收编","tension_point":"控制权"}],"power_visibility":"高"}}
                    """;
            case "agent_historyculture" -> """
                    {"history_culture":{"essence":"雾脉兴衰","major_events":[{"event":"雾脉开辟","era":"上古","lasting_impact":"灵气秩序建立"}],"religions":[{"name":"雾神教","core_belief":"雾脉即神","follower_scope":"下层"}],"customs":"采雾节","economy":"以灵易物","daily_slice":"晨起纳气"}}
                    """;
            case "agent_existence" -> """
                    {"existence":{"essence":"万物有寿","calendar":"一年十二月","lifespan":"人族百年","death":"魂魄归雾","disease_and_birth":"医馆行针"}}
                    """;
            case "agent_information" -> """
                    {"information":{"essence":"消息沿脉走","info_speed":"顺潮三日","knowledge_medium":"玉简藏书阁","info_barriers":"宗门垄断典籍","rumor_and_truth":"谣言随潮失真"}}
                    """;
            default -> "{}";
        };
    }

    // ==================== 预览 ====================

    @Test
    void previewBuildsSectionsAndLocations() {
        WorldGenPreviewVO vo = service.preview(10L);
        assertEquals(6, vo.getDimensions().size());
        Map<String, String> sections = vo.getSections();
        assertEquals("废土纪元", sections.get("name"));
        assertEquals("科幻末世", sections.get("genre"));
        assertEquals("玄黄纪", sections.get("era"));
        // core_laws → magicSystem
        assertTrue(sections.get("magicSystem").contains("四阶修炼体系"));
        assertTrue(sections.get("magicSystem").contains("公理铁律"));
        assertTrue(sections.get("magicSystem").contains("雾脉守恒"));
        assertTrue(sections.get("magicSystem").contains("弑杀雾脉之灵"));
        // space_time → geography + 地点
        assertTrue(sections.get("geography").contains("九洲分立"));
        assertTrue(sections.get("geography").contains("雾都"));
        assertEquals(2, vo.getLocations().size());
        assertEquals("雾都", vo.getLocations().get(0).getName());
        assertEquals("盆地", vo.getLocations().get(0).getType());
        assertTrue(vo.getLocations().get(0).getIntro().contains("雾脉宗"));
        // society → factions
        assertTrue(sections.get("factions").contains("人族"));
        assertTrue(sections.get("factions").contains("宗派自治"));
        // history_culture → culture + history
        assertTrue(sections.get("culture").contains("雾神教"));
        assertTrue(sections.get("history").contains("雾脉开辟"));
        // existence/information → freeText
        assertTrue(sections.get("freeText").contains("五、存在基础"));
        assertTrue(sections.get("freeText").contains("六、信息传播"));
        assertTrue(sections.get("freeText").contains("消息沿脉走"));
    }

    @Test
    void previewMissingDimensionRejected() {
        attempts.removeIf(a -> a.getNodeId().equals("agent_information"));
        BizException e = assertThrows(BizException.class, () -> service.preview(10L));
        assertTrue(e.getMessage().contains("信息传播"));
    }

    // ==================== 确认落库 ====================

    @Test
    void confirmPersistsWorldSettingAndMergesLocations() {
        Map<String, Object> out = service.confirm(10L);

        // 世界观分节落库（DTO 字段断言；M3：3 参重载携带 worldgen 来源任务 ID）
        ArgumentCaptor<WorldSettingDTO> dtoCaptor = ArgumentCaptor.forClass(WorldSettingDTO.class);
        verify(projectService).saveWorldSetting(eq(5L), dtoCaptor.capture(), eq(10L));
        WorldSettingDTO dto = dtoCaptor.getValue();
        assertEquals("废土纪元", dto.name());
        // 题材落库前收敛到固定八类："科幻末世"命中「末世」关键字（最具体优先），归一为「末世」。
        // 提示词预览里的原文不受影响——上面 sections.get("genre") 仍断言"科幻末世"。
        assertEquals("末世", dto.genre());
        assertEquals("玄黄纪", dto.era());
        assertTrue(dto.magicSystem().contains("四阶修炼体系"));
        assertTrue(dto.geography().contains("九洲分立"));
        assertTrue(dto.factions().contains("人族"));
        assertTrue(dto.culture().contains("雾神教"));
        assertTrue(dto.history().contains("雾脉开辟"));
        assertTrue(dto.freeText().contains("五、存在基础"));
        // 地点合并：2 个新地点入库
        ArgumentCaptor<ActorWorldLocation> locCaptor = ArgumentCaptor.forClass(ActorWorldLocation.class);
        verify(locationRepository, Mockito.times(2)).save(locCaptor.capture());
        assertEquals("雾都", locCaptor.getAllValues().get(0).getName());
        assertEquals(2, out.get("locationAdded"));
        assertEquals(2, out.get("locationCandidates"));
    }

    @Test
    void confirmSkipsDuplicateLocationNames() {
        ActorWorldLocation existing = new ActorWorldLocation();
        existing.setId(1L);
        existing.setProjectId(5L);
        existing.setName("雾都");
        existing.setSortOrder(0);
        when(locationRepository.findByProjectId(5L)).thenReturn(List.of(existing));

        Map<String, Object> out = service.confirm(10L);
        // 仅枯脉岭新增
        ArgumentCaptor<ActorWorldLocation> locCaptor = ArgumentCaptor.forClass(ActorWorldLocation.class);
        verify(locationRepository).save(locCaptor.capture());
        assertEquals("枯脉岭", locCaptor.getValue().getName());
        assertEquals(1, out.get("locationAdded"));
    }

    @Test
    void confirmMissingDimensionRejectedAndNoPersist() {
        attempts.removeIf(a -> a.getNodeId().equals("agent_corelaws"));
        assertThrows(BizException.class, () -> service.confirm(10L));
        verify(projectService, never()).saveWorldSetting(anyLong(), any(), any());
        verify(locationRepository, never()).save(any());
    }
}
