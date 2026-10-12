package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenNodeAttempt;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenNodeAttemptRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WorldGenCharacterService（M4 character 工作流，2026-09-29_05 适配轮）单元测试。
 * <p>职责：验证——世界设定注入（分节拼装/空项目降级）、阵容检查点、角色落库
 * （depth+roster 合并建行、幂等跳过、结构列/重要度/建档锚定文本组装）。</p>
 */
class WorldGenCharacterServiceTest {

    /** character 定义简化版（2 agent 节点 + 输出变量，镜像 worldgen_character_v1.json 契约） */
    private static final String NODES_JSON = """
            [ {"id":"agent_roster","label":"角色阵容设计","nodeType":"agent","agentType":"holzyn-character-roster",
               "firstMessage":"{{worldSetting}}","outputVariable":"agent_roster_output"},
              {"id":"agent_character_depth","label":"逐角色深度生成","nodeType":"agent","agentType":"holzyn-character-depth",
               "firstMessage":"{{agent_roster_output}}","outputVariable":"agent_character_depth_output",
               "nodeParams":{"batch":true,"batchInputVariable":"agent_roster_output"}} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"agent_roster"},
              {"source":"agent_roster","target":"agent_character_depth"},
              {"source":"agent_character_depth","target":"__end__"} ]
            """;

    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private ActorCharacterRepository characterRepository;
    private WorldGenCharacterService service;

    private final List<ActorWorldgenNodeAttempt> attempts = new ArrayList<>();
    private final List<ActorCharacter> characters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode(WorldGenCharacterService.CODE_CHARACTER_V1);
        workflow.setName("角色阵容生成");
        workflow.setVersion(1);
        workflow.setNodesJson(NODES_JSON);
        workflow.setEdgesJson(EDGES_JSON);
        workflow.setVariablesJson("[]");
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));

        ActorWorldgenTask task = new ActorWorldgenTask();
        task.setId(10L);
        task.setWorkflowId(1L);
        task.setProjectId(5L);
        task.setStatus(WorldGenStatus.SUCCEEDED);
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        when(attemptRepository.findByTaskIdAndNodeIdOrderByIdAsc(anyLong(), anyString()))
                .thenAnswer(inv -> attempts.stream()
                        .filter(a -> a.getNodeId().equals(inv.getArgument(1))).toList());
        doAnswer(inv -> {
            ActorCharacter c = inv.getArgument(0);
            c.setId((long) (characters.size() + 1));
            characters.add(c);
            return c;
        }).when(characterRepository).save(any(ActorCharacter.class));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(anyLong(), any()))
                .thenAnswer(inv -> new ArrayList<>(characters));

        service = new WorldGenCharacterService(taskRepository, workflowRepository, attemptRepository,
                new WorldGenDefinitionParser(), new WorldGenSchemaValidator(),
                worldSettingRepository, characterRepository);
    }

    /** 追加一次成功尝试。 */
    private void succeed(String nodeId, String outputJson) {
        ActorWorldgenNodeAttempt a = new ActorWorldgenNodeAttempt();
        a.setId((long) (attempts.size() + 1));
        a.setTaskId(10L);
        a.setNodeId(nodeId);
        a.setStatus(WorldGenStatus.SUCCEEDED);
        a.setOutputJson(outputJson);
        attempts.add(a);
    }

    private static final String ROSTER_JSON = """
            {"characters":[
              {"name":"沈砚秋","role":"村长·族长","importance":"主角","gender":"男","race":"人族","age":"56","affiliation":"沈氏宗族","occupation":"村长","tagline":"守旧而刚正"},
              {"name":"孙二娘","role":"茶馆老板娘","importance":"重要","gender":"女","race":"人族","age":"38","affiliation":"云来茶馆","occupation":"掌柜","tagline":"泼辣热心"}
            ]}
            """;

    private static final String DEPTH_JSON = """
            {"characters":[
              {"name":"沈砚秋","depth":{"character_depth":{
                "skeleton":{"name":"沈砚秋","title":"青禾村村长","gender":"男","race":"人族","age":"56","origin":"青禾村","background":"沈氏宗族族长","appearance":"花白胡须"},
                "beliefs":{"core_values":["守规矩"],"desires":["村落安宁"],"fears":["家业败落"],"beliefs":["规矩立村"]},
                "deep":{"secret":"曾放过一个逃犯","trauma":"年轻时丧父","moral_boundary":"不害无辜","growth_arc":"学会放手"},
                "voice":{"speech_style":"沉稳官腔","catchphrases":["这个嘛"],"tone":"庄重","language_habits":"自称老朽"}
              }}},
              {"name":"孙二娘","depth":{"character_depth":{
                "skeleton":{"name":"孙二娘","title":"茶馆老板娘","gender":"女","race":"人族","age":"38","origin":"青禾村","background":"夫家祖传茶馆","appearance":"利落短发"},
                "beliefs":{"core_values":["待客诚"],"desires":["茶馆兴旺"],"fears":["无人光顾"],"beliefs":["和气生财"]},
                "deep":{"secret":"藏着一坛女儿红","trauma":"丧夫","moral_boundary":"不欺客","growth_arc":"放下执念"},
                "voice":{"speech_style":"爽利","catchphrases":["客官您坐"],"tone":"热络","language_habits":"满口客套话"}
              }}}
            ]}
            """;

    @Test
    @DisplayName("世界设定注入：9 分节拼装摘要")
    void buildCharacterInputsAssemblesWorldSetting() {
        ActorWorldSetting ws = new ActorWorldSetting();
        ws.setName("青禾村");
        ws.setGenre("仙侠");
        ws.setGeography("群山环抱的村落");
        ws.setFactions("沈氏宗族、云来茶馆");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.of(ws));

        Map<String, String> inputs = service.buildCharacterInputs(5L);
        assertTrue(inputs.get("worldSetting").contains("【世界名称】") && inputs.get("worldSetting").contains("青禾村"));
        assertTrue(inputs.get("worldSetting").contains("【地理设定】") && inputs.get("worldSetting").contains("群山环抱的村落"));
        assertEquals("仙侠", inputs.get("genre"));
        assertEquals("中文", inputs.get("language"));
    }

    @Test
    @DisplayName("世界设定注入：项目无设定 → 空 worldSetting 降级不抛错")
    void buildCharacterInputsDegradesWithoutSetting() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.empty());
        Map<String, String> inputs = service.buildCharacterInputs(5L);
        assertEquals("", inputs.getOrDefault("worldSetting", ""));
        assertEquals("中文", inputs.get("language"));
    }

    @Test
    @DisplayName("阵容检查点：重放产物 + 校验通过返回角色数")
    void persistRosterChecksAndReturnsCount() {
        succeed("agent_roster", ROSTER_JSON);
        Map<String, Object> out = service.persistRoster(10L);
        assertEquals(2, out.get("rosterChecked"));
    }

    @Test
    @DisplayName("角色落库：depth+roster 合并建行（结构列/重要度/建档锚定文本）")
    void persistCharactersCreatesProfileRows() {
        succeed("agent_roster", ROSTER_JSON);
        succeed("agent_character_depth", DEPTH_JSON);

        Map<String, Object> out = service.persistCharacters(10L);
        assertEquals(2, out.get("created"));
        assertEquals(0, out.get("skipped"));

        ArgumentCaptor<ActorCharacter> captor = ArgumentCaptor.forClass(ActorCharacter.class);
        Mockito.verify(characterRepository, Mockito.times(2)).save(captor.capture());
        ActorCharacter c1 = captor.getAllValues().get(0);
        assertEquals("沈砚秋", c1.getName());
        assertEquals("special", c1.getType());
        assertEquals("村长·族长", c1.getTitle());
        assertEquals("男", c1.getGender());
        assertEquals("人族", c1.getRace());
        assertEquals("沈氏宗族", c1.getAffiliation());
        assertEquals("村长", c1.getOccupation());
        assertEquals(56, c1.getAge());
        assertEquals(4, c1.getImportance(), "主角→重要度 4");
        assertEquals(1, c1.getIsProtagonist());
        assertEquals(1, c1.getStatus());
        // 建档锚定文本：4 维深度逐段渲染
        assertTrue(c1.getDetail().contains("【角色档案锚定】沈砚秋"));
        assertTrue(c1.getDetail().contains("核心价值：守规矩"));
        assertTrue(c1.getDetail().contains("秘密：曾放过一个逃犯"));
        assertTrue(c1.getDetail().contains("口头禅：这个嘛"));
        ActorCharacter c2 = captor.getAllValues().get(1);
        assertEquals(3, c2.getImportance(), "重要→重要度 3");
        assertEquals(0, c2.getIsProtagonist());
    }

    @Test
    @DisplayName("角色落库幂等：同项目同名已存在 → 跳过不建行")
    void persistCharactersSkipsExistingNames() {
        ActorCharacter existing = new ActorCharacter();
        existing.setId(99L);
        existing.setProjectId(5L);
        existing.setName("沈砚秋");
        existing.setDeleted(0);
        characters.add(existing);

        succeed("agent_roster", ROSTER_JSON);
        succeed("agent_character_depth", DEPTH_JSON);

        Map<String, Object> out = service.persistCharacters(10L);
        assertEquals(1, out.get("created"));
        assertEquals(1, out.get("skipped"));
        // 只保存 1 个新角色（孙二娘）
        Mockito.verify(characterRepository, Mockito.times(1)).save(any(ActorCharacter.class));
    }

    @Test
    @DisplayName("角色落库：depth 产物缺失 → 400 拒绝（防残缺落库）")
    void persistCharactersRejectsWithoutDepth() {
        succeed("agent_roster", ROSTER_JSON);
        try {
            service.persistCharacters(10L);
            assertTrue(false, "应抛 BizException");
        } catch (com.holzyn.actor.common.BizException e) {
            assertTrue(e.getMessage().contains("深度档案尚未成功生成"));
        }
    }

    @Test
    @DisplayName("worldSettingText：分节拼接与超长截断")
    void worldSettingTextTruncatesLongSections() {
        ActorWorldSetting ws = new ActorWorldSetting();
        ws.setName("青禾村");
        ws.setGeography("山".repeat(3000));
        String text = WorldGenCharacterService.worldSettingText(ws);
        assertTrue(text.contains("【世界名称】") && text.contains("青禾村"));
        assertTrue(text.contains("【地理设定】"));
        assertTrue(text.length() < 2200, "地理设定应被截断（2000 上限），实际长度=" + text.length());
        assertEquals("", WorldGenCharacterService.worldSettingText(null), "null 世界设定返回空串（非 null）");
        assertNotNull(WorldGenCharacterService.worldSettingText(new ActorWorldSetting()));
    }
}
