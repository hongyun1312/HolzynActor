package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 档案保存管线单元测试（治理批次 0 · 卡14 + 卡9，{@code domain/growth/persona} 同包）。
 *
 * <p>PersonaService 当前无测试类——本类承载 {@code savePersona} 管线聚合场景：</p>
 * <ul>
 *   <li><b>G-GOAL-01</b>：档案移除目标 → actor_goal 孤儿行软删（deleted=1，保留行）；</li>
 *   <li><b>G-GOAL-02</b>：运行期目标（非种子特征）不被删除同步误伤（守护）；</li>
 *   <li><b>G-GENOME-01</b>：建档 AI 输出的 ⑩ genome/epigenetic_marks 经 detach 剥离，不落 persona_json。</li>
 * </ul>
 *
 * <p>测试范式：手工构造 PersonaService + 内存目标仓库 fake（{@link #goalStore}）+
 * {@code Mockito.mock} 其余依赖；无 Spring 上下文。</p>
 */
class PersonaServiceTest {

    private ActorCharacterRepository characterRepository;
    private PersonaProjectionService projectionService;
    private PersonaRenderer renderer;
    private PersonaSchemaValidator validator;
    private NpcStateService npcStateService;
    private ActorCharacterGoalRepository goalRepository;
    private ActorKnowledgeDocRepository knowledgeDocRepository;
    private com.holzyn.actor.domain.knowledge.service.CharacterKnowledgeCardSyncService knowledgeCardSyncService;
    private com.holzyn.actor.domain.account.CurrentUserProvider currentUserProvider;
    private PersonaRevisionService revisionService;
    private PersonaService service;

    /** 内存目标仓库：软删行（deleted=1）从 active 查询中消失，与全库 AndDeleted(...,0) 语义一致 */
    private final List<ActorCharacterGoal> goalStore = new ArrayList<>();
    private ActorCharacter ch;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        projectionService = Mockito.mock(PersonaProjectionService.class);
        renderer = Mockito.mock(PersonaRenderer.class);
        validator = Mockito.mock(PersonaSchemaValidator.class);
        npcStateService = Mockito.mock(NpcStateService.class);
        goalRepository = Mockito.mock(ActorCharacterGoalRepository.class);
        knowledgeDocRepository = Mockito.mock(ActorKnowledgeDocRepository.class);
        knowledgeCardSyncService = Mockito.mock(
                com.holzyn.actor.domain.knowledge.service.CharacterKnowledgeCardSyncService.class);
        currentUserProvider = Mockito.mock(com.holzyn.actor.domain.account.CurrentUserProvider.class);
        revisionService = Mockito.mock(PersonaRevisionService.class);
        service = new PersonaService(characterRepository, projectionService, renderer, validator,
                npcStateService, goalRepository, knowledgeDocRepository, knowledgeCardSyncService,
                currentUserProvider, revisionService);
        ch = new ActorCharacter();
        ch.setId(7L);
        ch.setProjectId(1L);
        ch.setName("孙二娘");
        when(characterRepository.findById(7L)).thenReturn(Optional.of(ch));
        when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(goalRepository.findByProjectIdAndAgentRefAndDeleted(anyLong(), anyString(), any()))
                .thenAnswer(inv -> goalStore.stream()
                        .filter(g -> g.getProjectId().equals(inv.getArgument(0))
                                && g.getAgentRef().equals(inv.getArgument(1))
                                && Integer.valueOf(0).equals(g.getDeleted()))
                        .toList());
        when(goalRepository.save(any(ActorCharacterGoal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(validator.validate(any(), any()))
                .thenReturn(new PersonaValidationResult(List.of(), List.of(), Map.of()));
    }

    /** 种子目标行构造（GoalSeedFixture：dispatchGoals 创建的种子特征 = priority∈{2,4} 且 description==title） */
    private ActorCharacterGoal seedGoal(String title, int priority, int deleted) {
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setProjectId(1L);
        g.setAgentRef("npc:7");
        g.setTitle(title);
        g.setDescription(title);
        g.setPriority(priority);
        g.setStatus("active");
        g.setDeleted(deleted);
        return g;
    }

    /** 含 ⑤ life_goals 的档案构造 */
    private PersonaProfile profileWithLifeGoals(List<String> lifeGoals) {
        PersonaProfile profile = PersonaProfile.empty(1L, 7L);
        profile.setDims(new PersonaProfile.Dims());
        PersonaDims.Values v = new PersonaDims.Values();
        v.setLifeGoals(lifeGoals);
        profile.getDims().setValues(v);
        return profile;
    }

    /**
     * G-GOAL-01（卡14 修复断言，起始 FAIL）：档案移除目标 → actor_goal 软删 deleted=1。
     * <p>现状错误值：dispatchGoals 只 upsert 不删除 → 孤儿行 deleted=0。</p>
     */
    @Test
    @DisplayName("G-GOAL-01: 档案删除目标 → actor_goal 软删（deleted=1）")
    void dispatchGoals_softDeletesRemovedSeedGoal() {
        ActorCharacterGoal seed = seedGoal("成为村长", 2, 0);
        goalStore.add(seed);

        service.savePersona(7L, profileWithLifeGoals(List.of()), "manual");

        assertEquals(1, seed.getDeleted(), "档案移除目标后孤儿行软删 deleted=1");
        // 读侧（AndDeleted(...,0)）不再返回该行
        List<ActorCharacterGoal> active = goalStore.stream()
                .filter(g -> Integer.valueOf(0).equals(g.getDeleted())).toList();
        assertFalse(active.stream().anyMatch(g -> "成为村长".equals(g.getTitle())),
                "软删后 active 查询不再返回孤儿目标（行保留，deleted=1）");
    }

    /**
     * G-GOAL-02（卡14 守护断言，起始 PASS）：运行期目标（PlanningService 创建）不被删除同步误伤。
     * <p>防伪机制：若实现用「title 不在档案即删」的粗暴规则，本断言立即 FAIL——强制按种子特征匹配。</p>
     */
    @Test
    @DisplayName("G-GOAL-02: 运行期目标不被种子删除同步误伤（守护）")
    void dispatchGoals_keepsRuntimeCreatedGoals() {
        ActorCharacterGoal seed = seedGoal("成为村长", 2, 0);
        // 运行期目标（模拟 PlanningService 创建：priority=3 且 description≠title）
        ActorCharacterGoal runtime = seedGoal("给父亲报仇", 3, 0);
        runtime.setDescription("父亲被害，誓要复仇");
        goalStore.add(seed);
        goalStore.add(runtime);

        service.savePersona(7L, profileWithLifeGoals(List.of("成为村长")), "manual");

        assertEquals(0, seed.getDeleted(), "档案含该目标 → 保留 deleted=0");
        assertEquals(0, runtime.getDeleted(), "运行期目标（非种子特征）不被误删");
    }

    /**
     * G-GENOME-01（卡9 修复断言，起始 FAIL）：建档 AI 输出的 ⑩ genome/epigenetic_marks 不落 persona_json。
     * <p>现状错误值：detachModuleOwnedDims 只剥离 ③/⑩ 谱系，不剥离 genome → 死拷贝产生。</p>
     */
    @Test
    @DisplayName("G-GENOME-01: 建档 AI 输出 genome 不落 persona_json（detach 剥离）")
    void detachModuleOwnedDims_stripsGenomeAndEpigeneticMarks() {
        PersonaProfile profile = PersonaProfile.empty(1L, 7L);
        profile.setDims(new PersonaProfile.Dims());
        PersonaDims.Development dev = new PersonaDims.Development();
        Map<String, String> genome = new LinkedHashMap<>();
        genome.put("source_parent_a", "npc:1");
        genome.put("source_parent_b", "npc:2");
        dev.setGenome(genome);
        List<String> marks = new ArrayList<>();
        marks.add("韧性增强");
        dev.setEpigeneticMarks(marks);
        profile.getDims().setDevelopment(dev);

        service.savePersona(7L, profile, "manual");

        // 落库 JSON 数据段不得含 genome / epigenetic_marks（保存侧剥离，读取侧不打补丁；
        // 注意 _sources.fieldsModule 元数据会声明 genome 归属，故断言只查 dims.development 数据段）
        String json = ch.getPersonaJson();
        assertNotNull(json, "档案应已落库");
        JsonNode node = PersonaJson.mapper().readTree(json);
        JsonNode development = node.path("dims").path("development");
        assertTrue(development.isMissingNode() || development.isNull()
                        || development.path("genome").isMissingNode() || development.path("genome").isNull(),
                "development 数据段不得携带 genome（建档 AI 输出剥离）");
        assertTrue(development.isMissingNode() || development.isNull()
                        || development.path("epigenetic_marks").isMissingNode() || development.path("epigenetic_marks").isNull(),
                "development 数据段不得携带 epigenetic_marks");
    }
}
