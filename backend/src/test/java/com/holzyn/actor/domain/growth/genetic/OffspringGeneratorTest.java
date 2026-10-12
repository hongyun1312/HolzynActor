package com.holzyn.actor.domain.growth.genetic;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.romance.AiDecisionSourceSupport;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新生儿生成器单元测试（O6 G4：七步流程全链路——校验/遗传合成/人格基底/关系模板/阶段初始化/
 * 初始记忆/世界广播）。
 */
@ExtendWith(MockitoExtension.class)
class OffspringGeneratorTest {

    @Mock private ActorCharacterRepository characterRepository;
    @Mock private ActorNpcStateRepository stateRepository;
    @Mock private ActorGrowthGenomeRepository genomeRepository;
    @Mock private ActorCharacterRelationRepository relationRepository;
    @Mock private ActorWorldClockRepository worldClockRepository;
    @Mock private ActorEventRepository eventRepository;
    @Mock private ActorCoreMemoryRepository coreMemoryRepository;
    @Mock private GrowthMilestoneRepository milestoneRepository;
    @Mock private LineageService lineageService;
    @Mock private PersonaService personaService;
    @Mock private AgentMemoryService agentMemoryService;
    @Mock private WorldEventBus worldEventBus;
    @Mock private WorldSandboxSseHub sandboxSseHub;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private AiDecisionSourceSupport aiSupport;

    private OffspringGenerator generator;
    private ActorCharacter mother;
    private ActorCharacter father;

    @BeforeEach
    void setUp() {
        GrowthProperties props = new GrowthProperties();
        generator = new OffspringGenerator(characterRepository, stateRepository, genomeRepository,
                relationRepository, worldClockRepository, eventRepository, coreMemoryRepository,
                milestoneRepository, lineageService, personaService, props, agentMemoryService,
                worldEventBus, sandboxSseHub, currentUserProvider, aiSupport);
        mother = ch(2L, "白小满", "女", 22, GrowthStage.YOUTH.name(), "家中");
        father = ch(1L, "沈青禾", "男", 25, GrowthStage.YOUTH.name(), "家中");
        // 人口计数（默认只母亲+父亲）
        lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(mother, father));
        lenient().when(characterRepository.findById(2L)).thenReturn(Optional.of(mother));
        lenient().when(characterRepository.findById(1L)).thenReturn(Optional.of(father));
        // 母亲运行态
        ActorNpcState motherState = new ActorNpcState();
        motherState.setProjectId(1L);
        motherState.setNpcRef("npc:2");
        lenient().when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:2"))
                .thenReturn(Optional.of(motherState));
        // 新生儿保存：分配 id
        lenient().when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> {
            ActorCharacter c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(60L);
            }
            return c;
        });
        lenient().when(stateRepository.save(any(ActorNpcState.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(genomeRepository.save(any(ActorGrowthGenome.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(milestoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineageService.childrenOf(anyLong(), anyString())).thenReturn(List.of());
        lenient().when(lineageService.motherOf(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(lineageService.parentEdgesOf(anyLong(), anyString())).thenReturn(List.of());
        // AI 起名失败 → 程序化兜底（不阻塞）
        lenient().when(aiSupport.call(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(null);
    }

    private ActorCharacter ch(Long id, String name, String gender, int age, String stage, String location) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setGender(gender);
        c.setAge(age);
        c.setStage(stage);
        c.setLocation(location);
        c.setProjectId(1L);
        return c;
    }

    @Test
    @DisplayName("七步：出生全链路——INFANT 档案行 + 基因行 + 谱系亲子边 + 运行态 + 里程碑 + 母亲产后清空")
    void createOffspringFullChain() {
        ActorCharacter child = generator.createOffspring(1L, "npc:2", "npc:1", 1_000_000L);
        assertNotNull(child, "新生儿应创建成功");
        assertEquals(GrowthStage.INFANT.name(), child.getStage());
        assertEquals(1_000_000L, child.getBirthGameSecond());
        assertTrue("男".equals(child.getGender()) || "女".equals(child.getGender()),
                "新生儿性别 50/50 随机：" + child.getGender());
        assertTrue(child.getName().startsWith("沈"), "姓氏随父：沈");
        // 基因行（父母推断各 1 行 + 子代 inherited 1 行；取 inherited 断言）
        ArgumentCaptor<ActorGrowthGenome> genomeCaptor = ArgumentCaptor.forClass(ActorGrowthGenome.class);
        verify(genomeRepository, org.mockito.Mockito.atLeast(3)).save(genomeCaptor.capture());
        ActorGrowthGenome childGenome = genomeCaptor.getAllValues().stream()
                .filter(g -> "inherited".equals(g.getSource()))
                .findFirst().orElseThrow();
        assertEquals("npc:60", childGenome.getNpcRef());
        assertEquals(2L, childGenome.getSourceParentA());
        assertEquals(1L, childGenome.getSourceParentB());
        assertEquals("inherited", childGenome.getSource());
        // 谱系亲子边（父 + 母）
        verify(lineageService).addParentChild(1L, "npc:2", "npc:60",
                ActorGrowthLineage.REL_MOTHER, 1_000_000L);
        verify(lineageService).addParentChild(1L, "npc:1", "npc:60",
                ActorGrowthLineage.REL_FATHER, 1_000_000L);
        // 运行态（婴儿状态行 + 母亲产后状态更新各 1 次）
        ArgumentCaptor<ActorNpcState> stateCaptor = ArgumentCaptor.forClass(ActorNpcState.class);
        verify(stateRepository, org.mockito.Mockito.atLeast(2)).save(stateCaptor.capture());
        assertTrue(stateCaptor.getAllValues().stream().anyMatch(s -> "npc:60".equals(s.getNpcRef())),
                "新生儿运行态行（npc:60）已落库");
        // 里程碑（birth）
        verify(milestoneRepository).save(any());
        // 母亲产后：孕期清空 + 冷却基准
        assertEquals(1_000_000L, stateOf(mother).getLastBirthGameSecond());
    }

    private ActorNpcState stateOf(ActorCharacter c) {
        return stateRepository.findByProjectIdAndNpcRef(1L, c.npcRef()).orElse(null);
    }

    @Test
    @DisplayName("Step1 校验：人口已达上限 → 不落地（零阻断）")
    void populationCapBlocksBirth() {
        List<ActorCharacter> crowd = new java.util.ArrayList<>();
        for (long i = 100; i < 150; i++) {
            crowd.add(ch(i, "村民" + i, "男", 30, GrowthStage.PRIME.name(), "村中"));
        }
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(crowd);
        ActorCharacter child = generator.createOffspring(1L, "npc:2", "npc:1", 1_000_000L);
        assertNull(child, "人口满 50 → 不落地");
        verify(characterRepository, never()).save(any(ActorCharacter.class));
    }

    @Test
    @DisplayName("Step1 校验：母亲已故/不存在 → 不落地")
    void deceasedMotherBlocksBirth() {
        when(characterRepository.findById(2L))
                .thenReturn(Optional.of(ch(2L, "白小满", "女", 22, GrowthStage.DECEASED.name(), "家中")));
        ActorCharacter child = generator.createOffspring(1L, "npc:2", "npc:1", 1_000_000L);
        assertNull(child);
    }

    @Test
    @DisplayName("Step2/3：遗传合成与人格基底——子代 OCEAN 由父母 midparent 回归派生")
    void geneticSynthesisAndOcean() {
        // 父母基因行缺失 → 程序化推断（任何角色可成为父母）
        when(genomeRepository.findByProjectIdAndNpcRef(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        ActorCharacter child = generator.createOffspring(1L, "npc:2", "npc:1", 1_000_000L);
        assertNotNull(child);
        // 基因行已落库（推断出的父母基因 + 合成的子代基因）
        verify(genomeRepository, org.mockito.Mockito.atLeast(3)).save(any(ActorGrowthGenome.class));
        // 档案落库（出生只填 ①②③④⑩；Q4）
        ArgumentCaptor<com.holzyn.actor.domain.growth.persona.PersonaProfile> personaCaptor =
                ArgumentCaptor.forClass(com.holzyn.actor.domain.growth.persona.PersonaProfile.class);
        verify(personaService).savePersona(org.mockito.ArgumentMatchers.eq(60L), personaCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("genetic"));
        var dims = personaCaptor.getValue().getDims();
        assertNotNull(dims.getPersonality(), "④ 人格基底（基因 → OCEAN）");
        assertNotNull(dims.getPersonality().getOcean(), "OCEAN 五维");
        assertNotNull(dims.getPhysio(), "③ 生理基线");
        assertNotNull(dims.getIdentity(), "① 身份");
        assertNotNull(dims.getAppearance(), "② 外貌");
        assertNull(dims.getValues(), "⑤ 价值观出生不填（Q4 分阶段揭示）");
        assertNull(dims.getExperience(), "⑨ 经历出生不填（Q4 分阶段揭示）");
    }

    /**
     * G-GENOME-02（卡9 守护断言，起始 PASS）：出生路径 actor_growth_genome 表侧写入不受 detach 剥离影响。
     * <p>与 G-GENOME-01 互为对照：档案侧剥离 genome（不落 persona_json） / 表侧保留（出生数据仍完整）。
     * detach 剥离只作用于档案保存管线，不得波及 {@link OffspringGenerator#createOffspring} 的基因行写入。</p>
     */
    @Test
    @DisplayName("G-GENOME-02: 出生路径 actor_growth_genome 表侧写入不受档案 detach 剥离影响")
    void createOffspring_genomeTableSideUnaffectedByArchiveDetach() {
        ActorCharacter child = generator.createOffspring(1L, "npc:2", "npc:1", 1_000_000L);
        assertNotNull(child, "新生儿应创建成功");
        // 基因行（父母推断各 1 行 + 子代 inherited 1 行；取 inherited 断言）
        ArgumentCaptor<ActorGrowthGenome> genomeCaptor = ArgumentCaptor.forClass(ActorGrowthGenome.class);
        verify(genomeRepository, org.mockito.Mockito.atLeast(3)).save(genomeCaptor.capture());
        ActorGrowthGenome childGenome = genomeCaptor.getAllValues().stream()
                .filter(g -> "inherited".equals(g.getSource()))
                .findFirst().orElseThrow();
        assertEquals("npc:60", childGenome.getNpcRef(), "子代基因行 npcRef");
        assertEquals(2L, childGenome.getSourceParentA(), "source_parent_a=母亲 id");
        assertEquals(1L, childGenome.getSourceParentB(), "source_parent_b=父亲 id");
    }
}
