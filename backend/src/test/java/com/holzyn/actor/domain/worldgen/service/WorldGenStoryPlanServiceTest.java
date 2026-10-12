package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorStoryPlan;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorStoryPlanRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
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
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenStoryPlanService（M4 story-plan 工作流，2026-09-29_06）单元测试。
 * <p>职责：验证——输入注入（世界设定/角色摘要/限流）、故事引擎落库（版本递增/幂等/渲染）、
 * 风格档案落库（同版本更新/氛围提取/缓存失效）、氛围片段元概念过滤、Markdown 渲染。</p>
 */
class WorldGenStoryPlanServiceTest {

    /** story-plan 定义简化版（2 agent 节点 + 输出变量，镜像 worldgen_story_plan_v1.json 契约） */
    private static final String NODES_JSON = """
            [ {"id":"agent_story_engine","label":"故事宏观规划","nodeType":"agent","agentType":"novel-story-planner",
               "firstMessage":"{{worldSetting}}","outputVariable":"agent_story_engine_output"},
              {"id":"agent_style_profile","label":"风格档案生成","nodeType":"agent","agentType":"novel-style-profiler",
               "firstMessage":"{{agent_story_engine_output}}","outputVariable":"agent_style_profile_output"} ]
            """;

    private static final String EDGES_JSON = """
            [ {"source":"__start__","target":"agent_story_engine"},
              {"source":"agent_story_engine","target":"agent_style_profile"},
              {"source":"agent_style_profile","target":"__end__"} ]
            """;

    private ActorWorldgenTaskRepository taskRepository;
    private ActorWorldgenWorkflowRepository workflowRepository;
    private ActorWorldgenNodeAttemptRepository attemptRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private ActorCharacterRepository characterRepository;
    private ActorStoryPlanRepository storyPlanRepository;
    private com.holzyn.actor.domain.world.service.StoryAtmosphereService atmosphereService;
    private WorldGenStoryPlanService service;

    private final List<ActorWorldgenNodeAttempt> attempts = new ArrayList<>();
    private final List<ActorCharacter> characters = new ArrayList<>();
    private final List<ActorStoryPlan> plans = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(ActorWorldgenTaskRepository.class);
        workflowRepository = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        attemptRepository = Mockito.mock(ActorWorldgenNodeAttemptRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        storyPlanRepository = Mockito.mock(ActorStoryPlanRepository.class);
        atmosphereService = Mockito.mock(com.holzyn.actor.domain.world.service.StoryAtmosphereService.class);

        ActorWorldgenWorkflow workflow = new ActorWorldgenWorkflow();
        workflow.setId(1L);
        workflow.setCode(WorldGenStoryPlanService.CODE_STORY_PLAN_V1);
        workflow.setName("故事规划生成");
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
        doAnswer(inv -> {
            ActorStoryPlan p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId((long) (plans.size() + 1));
                plans.add(p);
            } else {
                // 更新既有行：替换同 id 元素（防重复添加破坏 plans 语义）
                for (int i = 0; i < plans.size(); i++) {
                    if (plans.get(i).getId().equals(p.getId())) {
                        plans.set(i, p);
                        return p;
                    }
                }
                plans.add(p);
            }
            return p;
        }).when(storyPlanRepository).save(any(ActorStoryPlan.class));
        when(storyPlanRepository.findByProjectIdAndTaskId(anyLong(), anyLong()))
                .thenAnswer(inv -> plans.stream()
                        .filter(p -> p.getProjectId().equals(inv.getArgument(0))
                                && p.getTaskId().equals(inv.getArgument(1)))
                        .findFirst());
        when(storyPlanRepository.findTopByProjectIdOrderByVersionDesc(anyLong()))
                .thenAnswer(inv -> plans.stream()
                        .filter(p -> p.getProjectId().equals(inv.getArgument(0)))
                        .max(java.util.Comparator.comparingInt(ActorStoryPlan::getVersion)));

        WorldGenProperties props = new WorldGenProperties();
        props.setStoryCharacterLimit(2); // 限流压小便于断言
        service = new WorldGenStoryPlanService(taskRepository, workflowRepository, attemptRepository,
                new WorldGenDefinitionParser(), new WorldGenSchemaValidator(),
                worldSettingRepository, characterRepository, storyPlanRepository, props);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "storyAtmosphereService", atmosphereService);
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

    private static final String PLAN_OK = """
            {"story_plan": {
              "surface": {"essence": "雾隐仙村", "expanded_premise": "灵气循雾脉流转", "core_conflict": "宗门垄断资源",
                "world_experience": "凡人在雾海中求生", "tone_reference": "冷眼旁观", "world_tags": ["雾隐", "仙村"]},
              "engine": {"essence": "局势自驱", "living_trap": "凡人无法离开雾海", "conflict_engine": "资源递减使矛盾升级",
                "conflict_layers": {"external": "宗门高压", "internal": "凡人绝望", "relational": "信任与利用并存"},
                "mystery_box": "雾脉源头", "rhythm_cycle": "收成→粮价→民怨", "dual_story": {"foreground": "日常生计", "background": "雾脉暗流"}},
              "payoff": {"essence": "以弱问强", "tension_archetypes": ["集市冲突", "宗祠决议"],
                "dramatic_moments": "天象异变", "emotional_rhythm": "农忙低谷节庆高涨", "atmosphere_notes": "药香与山雨"}
            }}
            """;

    private static final String STYLE_OK = """
            {"style_profile": {
              "tone": "冷调吐槽，外冷内热",
              "references": [{"work": "雾山五行", "tags": "仙侠群像", "learn": "群像冲突", "apply": "村中多线并行"}],
              "narrative_persona": "村口说书人",
              "commandments": ["禁止悲情独白"],
              "genre_notes": "仙侠惯例"
            }}
            """;

    // ==================== 输入注入 ====================

    @Test
    @DisplayName("buildStoryInputs：世界设定 9 分节 + 角色摘要 + genre 注入")
    void buildStoryInputs_injects() {
        ActorWorldSetting ws = new ActorWorldSetting();
        ws.setName("青禾村");
        ws.setGenre("仙侠");
        ws.setEra("灵气时代");
        ws.setGeography("雾隐山间");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(5L)).thenReturn(Optional.of(ws));

        ActorCharacter c = new ActorCharacter();
        c.setId(1L);
        c.setProjectId(5L);
        c.setName("沈砚秋");
        c.setTitle("村长");
        c.setImportance(4);
        c.setIsProtagonist(1);
        c.setPersonaJson("""
                {"dims":{"personality":{"temperamentType":"沉稳","traitsSummary":"守旧而刚正"},
                 "values":{"coreDesires":["光耀沈氏"],"coreFears":["家道中落"],"lifeGoals":["守住祖产"]}}}
                """);
        characters.add(c);

        Map<String, String> inputs = service.buildStoryInputs(5L);
        assertTrue(inputs.get("worldSetting").contains("青禾村"));
        assertTrue(inputs.get("worldSetting").contains("灵气时代"));
        assertEquals("仙侠", inputs.get("genre"));
        assertTrue(inputs.get("characterSummaries").contains("沈砚秋"));
        assertTrue(inputs.get("characterSummaries").contains("光耀沈氏"));
    }

    @Test
    @DisplayName("buildCharacterSummaries：persona 提取 / detail 降级 / 按重要度限流")
    void buildCharacterSummaries() {
        ActorCharacter a = new ActorCharacter();
        a.setId(1L);
        a.setName("配角甲");
        a.setTitle("杂役");
        a.setImportance(2);
        a.setDetail("【角色档案锚定】配角甲…信念：安分守己");
        characters.add(a);
        ActorCharacter b = new ActorCharacter();
        b.setId(2L);
        b.setName("主角乙");
        b.setTitle("掌门");
        b.setImportance(4);
        b.setIsProtagonist(1);
        b.setPersonaJson("""
                {"dims":{"values":{"coreDesires":["问鼎"],"coreFears":["失势"]}}}
                """);
        characters.add(b);
        ActorCharacter c = new ActorCharacter();
        c.setId(3L);
        c.setName("配角丙");
        c.setTitle("药童");
        c.setImportance(3);
        characters.add(c);

        // props.storyCharacterLimit=2 → 只保留前 2（主角乙 + 配角丙——重要度排序）
        String text = service.buildCharacterSummaries(5L);
        assertTrue(text.contains("主角乙"));
        assertFalse(text.contains("配角甲"), "重要度最低应被限流截断");
        assertTrue(text.contains("问鼎"), "persona 欲望应被提取");
        assertTrue(text.contains("配角丙"));
    }

    @Test
    @DisplayName("buildCharacterSummaries：persona 缺失走 detail 降级；无角色返回空串")
    void buildCharacterSummaries_degrade() {
        ActorCharacter a = new ActorCharacter();
        a.setId(1L);
        a.setName("张三");
        a.setTitle("猎户");
        a.setDetail("【角色档案锚定】张三（猎户）\n信念：以命换命");
        characters.add(a);
        String text = service.buildCharacterSummaries(5L);
        assertTrue(text.contains("张三"));
        assertTrue(text.contains("以命换命"));
        // 空项目 → 空串
        characters.clear();
        assertEquals("", service.buildCharacterSummaries(5L));
    }

    // ==================== 落库 ====================

    @Test
    @DisplayName("persistStoryEngine：首行版本=1 + Markdown 渲染 + 幂等复用行")
    void persistStoryEngine_versionAndIdempotent() {
        succeed("agent_story_engine", PLAN_OK);
        Map<String, Object> out1 = service.persistStoryEngine(10L);
        assertEquals(1, out1.get("version"));
        assertEquals(true, out1.get("created"));
        assertEquals(1, plans.size());
        assertNotNull(plans.get(0).getStoryPlanMd());
        assertTrue(plans.get(0).getStoryPlanMd().contains("显性层"));

        // 同任务重跑 → 复用行不重复建行、版本不变
        Map<String, Object> out2 = service.persistStoryEngine(10L);
        assertEquals(1, out2.get("version"));
        assertEquals(false, out2.get("created"));
        assertEquals(1, plans.size());
    }

    @Test
    @DisplayName("persistStoryEngine：无成功产物抛错")
    void persistStoryEngine_noOutput() {
        assertThrows(BizException.class, () -> service.persistStoryEngine(10L));
    }

    @Test
    @DisplayName("persistStyleProfile：同版本行补齐风格/氛围 + 缓存失效")
    void persistStyleProfile_updatesRow() {
        succeed("agent_story_engine", PLAN_OK);
        service.persistStoryEngine(10L);
        succeed("agent_style_profile", STYLE_OK);

        Map<String, Object> out = service.persistStyleProfile(10L);
        assertEquals(1, out.get("version"));
        assertTrue(((Number) out.get("atmosphereLength")).intValue() > 0);
        ActorStoryPlan row = plans.get(0);
        assertNotNull(row.getStyleProfileMd());
        assertTrue(row.getStyleProfileMd().contains("风格档案"));
        assertTrue(row.getAtmosphereSegment().contains("冷调吐槽"));
        verify(atmosphereService).invalidate(5L);
    }

    @Test
    @DisplayName("persistStyleProfile：引擎未落库抛错")
    void persistStyleProfile_noEngineRow() {
        succeed("agent_style_profile", STYLE_OK);
        assertThrows(BizException.class, () -> service.persistStyleProfile(10L));
    }

    // ==================== 氛围片段 ====================

    @Test
    @DisplayName("extractAtmosphereSegment：过滤元叙事概念，只留世界气质素材")
    void extractAtmosphereSegment_filtersMeta() throws Exception {
        JsonNode plan = new tools.jackson.databind.ObjectMapper().readTree(PLAN_OK).path("story_plan");
        JsonNode profile = new tools.jackson.databind.ObjectMapper().readTree(STYLE_OK).path("style_profile");
        String seg = WorldGenStoryPlanService.extractAtmosphereSegment(plan, profile);
        assertTrue(seg.contains("雾隐仙村"));
        assertTrue(seg.contains("药香与山雨"));
        assertTrue(seg.contains("冷调吐槽"));
        assertTrue(seg.contains("村中多线并行"), "参考作品落地应注入（可感知的气质参照）");
        // 元概念过滤：不出现「读者」「爆点」「叙事人格」「创作边界」等
        assertFalse(seg.contains("读者"));
        assertFalse(seg.contains("爆点"));
        assertFalse(seg.contains("叙事人格"));
        assertFalse(seg.contains("命令"));
        // null 容错
        assertEquals("", WorldGenStoryPlanService.extractAtmosphereSegment(null, null));
    }

    @Test
    @DisplayName("render：故事引擎与风格档案 Markdown 渲染关键段")
    void renderMarkdown() throws Exception {
        JsonNode plan = new tools.jackson.databind.ObjectMapper().readTree(PLAN_OK).path("story_plan");
        String md = WorldGenStoryPlanService.renderStoryPlanMarkdown(plan);
        assertTrue(md.contains("显性层"));
        assertTrue(md.contains("引擎层"));
        assertTrue(md.contains("兑现层"));
        assertTrue(md.contains("雾隐仙村"));

        JsonNode profile = new tools.jackson.databind.ObjectMapper().readTree(STYLE_OK).path("style_profile");
        String smd = WorldGenStoryPlanService.renderStyleProfileMarkdown(profile);
        assertTrue(smd.contains("基调"));
        assertTrue(smd.contains("冷调吐槽"));
        assertTrue(smd.contains("表达边界"));
        assertEquals("", WorldGenStoryPlanService.renderStoryPlanMarkdown(null));
    }
}
