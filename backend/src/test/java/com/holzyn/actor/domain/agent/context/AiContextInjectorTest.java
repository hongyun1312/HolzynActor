package com.holzyn.actor.domain.agent.context;

import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 统一 AI 上下文注入器单测（domain/agent/context，同包）。
 * <p>职责：① 世界观简报拼装（结构化字段 + 自由文本兜底 + 预算截断 + 无数据空串）；
 * ② 角色核心卡拼装（身份/档案分片/核心记忆/关键关系/当前状态 + 段边界预算装配[放不下整段省略+WARN]）；
 * ③ 组合注入 compose（【世界观】+【我的身份与处境】双段头）；④ enrichState（注入段 + 原 state）；
 * ⑤ 逐段降级零阻断（服务未装配/数据缺失/异常自动跳过该段，全空返回空串）。</p>
 * <p>背景：2026-10-07 统一上下文注入根治——AI 角色视角调用（对话/行动/决策/记忆/NanoJev 判定）
 * 统一注入世界观简报（恒定）+ 角色核心卡（涉及即注入），杜绝「只有关系变化、无角色身份与世界观」。</p>
 */
class AiContextInjectorTest {

    private ActorCharacterRepository characterRepository;
    private ActorWorldSettingRepository worldSettingRepository;
    private PersonaService personaService;
    private CoreMemoryService coreMemoryService;
    private ActorCharacterRelationRepository relationRepository;
    private NpcStateService npcStateService;
    private WorldClockService worldClockService;
    private ActorConversationMemberRepository conversationMemberRepository;
    private ActorConversationRepository conversationRepository;
    private ActorMessageRepository messageRepository;
    private AiContextInjector injector;

    private static final String PERSONA_SHARDS = """
            我是沈砚秋（青禾村村长 · 沈氏宗族族长）。人格与价值观：稳重隐忍、面冷心热、思虑周全；
            言语风格：稳重内敛，慢条斯理；「村土平安，比什么都紧要。」""";

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        personaService = Mockito.mock(PersonaService.class);
        coreMemoryService = Mockito.mock(CoreMemoryService.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        npcStateService = Mockito.mock(NpcStateService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        conversationMemberRepository = Mockito.mock(ActorConversationMemberRepository.class);
        conversationRepository = Mockito.mock(ActorConversationRepository.class);
        messageRepository = Mockito.mock(ActorMessageRepository.class);
        injector = new AiContextInjector(400, 300, 3);
        Reflection.set(injector, "characterRepository", characterRepository);
        Reflection.set(injector, "worldSettingRepository", worldSettingRepository);
        Reflection.set(injector, "personaService", personaService);
        Reflection.set(injector, "coreMemoryService", coreMemoryService);
        Reflection.set(injector, "relationRepository", relationRepository);
        Reflection.set(injector, "npcStateService", npcStateService);
        Reflection.set(injector, "worldClockService", worldClockService);
        Reflection.set(injector, "conversationMemberRepository", conversationMemberRepository);
        Reflection.set(injector, "conversationRepository", conversationRepository);
        Reflection.set(injector, "messageRepository", messageRepository);
    }

    private ActorWorldSetting world() {
        ActorWorldSetting w = new ActorWorldSetting();
        w.setName("青禾村志");
        w.setGenre("乡土奇幻");
        w.setEra("架空古代");
        w.setGeography("青禾村四面环山，村西有溪，村东为梯田。");
        w.setFactions("沈氏宗族掌村；巫祝神契世代守护。");
        w.setMagicSystem("灵脉沉寂，神契尚存微弱感应。");
        w.setCulture("逢节气祭谷神；村老议事。");
        w.setHistory("三百年前避兵灾建村，靠山吃山。");
        w.setFreeText("补充：村口老槐树下埋着百年前的契约碑。");
        return w;
    }

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setProjectId(1L);
        ch.setName("沈砚秋");
        ch.setTitle("青禾村村长");
        ch.setGender("男");
        ch.setRace("人族");
        ch.setOccupation("村长");
        ch.setAffiliation("沈氏宗族");
        ch.setAge(42);
        ch.setCurrentActivity("在祠堂核对账册");
        return ch;
    }

    private void wireCharacter(long id) {
        when(characterRepository.findById(id)).thenReturn(Optional.of(character()));
        when(personaService.renderShardsForScene(id, "action_gen")).thenReturn(PERSONA_SHARDS);
        // 2026-10-10 身份去重：renderCoreSegment 自带【核心记忆】段头（对齐真实格式），
        // characterCard 不再重复拼接 → 断言段头只出现一次
        when(coreMemoryService.renderCoreSegment(eq(1L), anyString()))
                .thenReturn("【核心记忆】我是青禾村村长，把村土平安看得比性命还重。");
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(1L);
        r.setFromRef("npc:" + id);
        r.setFromName("沈砚秋");
        r.setToRef("npc:2");
        r.setToName("钱串子");
        r.setAffinity(65);
        r.setTrust(80);
        r.setRelationState("friend");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(r));
        ActorNpcState st = new ActorNpcState();
        st.setDominantEmotion("worried");
        st.setHunger(30);
        st.setThirst(85);
        st.setEnergy(75);
        st.setSocial(50);
        when(npcStateService.stateFor(1L, "npc:" + id)).thenReturn(st);
    }

    // ==================== 世界观简报 ====================

    @Test
    void worldBrief_assemblesStructuredFields() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        String brief = injector.worldBrief(1L);
        assertTrue(brief.contains("青禾村志"));
        assertTrue(brief.contains("乡土奇幻"));
        assertTrue(brief.contains("时代：架空古代"));
        assertTrue(brief.contains("【地理】"));
        assertTrue(brief.contains("【势力】"));
        assertTrue(brief.contains("【规则】"));
        assertTrue(brief.contains("【文化】"));
        assertTrue(brief.contains("【历史】"));
        assertTrue(brief.contains("补充：村口老槐树下"));
        assertTrue(brief.length() <= 400);
    }

    @Test
    void worldBrief_emptyWhenNoSetting() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        assertEquals("", injector.worldBrief(1L));
    }

    @Test
    void worldBrief_emptyWhenRepoMissing() {
        Reflection.set(injector, "worldSettingRepository", null);
        assertEquals("", injector.worldBrief(1L));
    }

    @Test
    void worldBrief_fallbackToFreeTextWhenStructuredEmpty() {
        ActorWorldSetting w = new ActorWorldSetting();
        w.setFreeText("只有自由文本的世界观设定。");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(w));
        String brief = injector.worldBrief(1L);
        assertTrue(brief.contains("只有自由文本的世界观设定"));
    }

    // ==================== 世界常识摘要（V2.0 段 1A，2026-10-08 外网冒烟新增） ====================

    /** 长常识（>400 字预算上限）——证明完整返回不截断。 */
    private static final String COMMON_KNOWLEDGE_400PLUS =
            "青禾村位于青岳山东麓的灵水走廊上，是大雍朝治下的一座灵秀村落。村中灵脉由青岳主峰"
                    + "发源，沿山势向村脚递减，灵气滋养着梯田与河谷；守脉堂的守脉人世代以自身精血供养灵脉，"
                    + "维持全村风调雨顺。临溪镇是山脚平原与外界交通的枢纽，商队与散修在此往来。凡人与"
                    + "灵植亲和的灵修者共处，低阶散修与宗门隐修并存：竹林隐修会守着幽竹秘境的古法聚灵阵，"
                    + "临溪镇商会垄断着灵石与凡俗物资的交换。村中按灵农历行事，月相影响灵力潮汐，逢节气"
                    + "祭谷神、朔望日铁匠不开炉。青禾村史由长老议事与茶摊闲谈口耳相传，巫祝神契世代守护着"
                    + "这份与灵脉共生的秩序。山外的人若要进村，须经灵溪渡口的船夫引路；村中每逢集日，"
                    + "梯田上的农人、铁匠铺的学徒与茶摊的常客都会聚到村口，交换收成与消息，日子就这样"
                    + "顺着灵脉的呼吸一天天过去。这份秩序维系着青禾村百年的安宁，也让外来的商旅愿意"
                    + "在此歇脚、互通有无；而守脉堂与隐修会的默契，让凡人与灵修者得以在同一片屋檐下"
                    + "安生度日，彼此敬畏着灵脉赐予的一切。";

    @Test
    void worldBrief_prefersCommonKnowledge_completeNoTruncation() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge(COMMON_KNOWLEDGE_400PLUS);
        w.setCommonKnowledgeAt(java.time.LocalDateTime.now());
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(w));
        String brief = injector.worldBrief(1L);
        // 完整返回常识：不被 400 字预算截断，且不含结构化拼装段头（【地理】等）
        assertEquals(COMMON_KNOWLEDGE_400PLUS.trim(), brief);
        assertTrue(brief.length() > 400);
        assertFalse(brief.contains("【地理】"));
        assertFalse(brief.contains("【势力】"));
        assertTrue(brief.contains("守脉堂"));
        assertTrue(brief.contains("灵农历"));
    }

    @Test
    void worldBrief_fallsBackToStructuredWhenCommonKnowledgeBlank() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge("   ");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(w));
        String brief = injector.worldBrief(1L);
        // 空白常识 → 回退结构化字段拼装（旧路径行为不变）
        assertTrue(brief.contains("青禾村志"));
        assertTrue(brief.contains("【地理】"));
    }

    // ==================== 角色核心卡 ====================

    @Test
    void characterCard_assemblesAllSegments() {
        wireCharacter(1L);
        String card = injector.characterCard(1L, "npc:1");
        assertTrue(card.contains("沈砚秋"));
        assertTrue(card.contains("青禾村村长"));
        assertTrue(card.contains("人族"));
        assertTrue(card.contains("村长"));
        assertTrue(card.contains("沈氏宗族"));
        assertTrue(card.contains("42岁"));
        assertTrue(card.contains("此刻正在：在祠堂核对账册"));
        assertTrue(card.contains("沈砚秋")); // 档案分片
        assertTrue(card.contains("【核心记忆】"));
        assertTrue(card.contains("【关键关系】"));
        assertTrue(card.contains("钱串子"));
        assertTrue(card.contains("好感65"));
        assertTrue(card.contains("【当前状态】"));
        assertTrue(card.contains("worried"));
        assertTrue(card.contains("饿了"));
        assertTrue(card.length() <= 300);
    }

    @Test
    void characterCard_emptyWhenCharacterMissing() {
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        assertEquals("", injector.characterCard(1L, "npc:1"));
    }

    @Test
    void characterCard_emptyWhenRefBlank() {
        assertEquals("", injector.characterCard(1L, "  "));
        assertEquals("", injector.characterCard(1L, null));
    }

    @Test
    void characterCard_injectsAllSegmentsWithoutBudget() {
        // 2026-10-10 决策：不设字符预算硬边界——即使构造预算极小，所有段全量拼装（不再整段省略）
        injector = new AiContextInjector(400, 60, 3);
        Reflection.set(injector, "characterRepository", characterRepository);
        Reflection.set(injector, "personaService", personaService);
        Reflection.set(injector, "coreMemoryService", coreMemoryService);
        Reflection.set(injector, "relationRepository", relationRepository);
        Reflection.set(injector, "npcStateService", npcStateService);
        wireCharacter(1L);
        String card = injector.characterCard(1L, "npc:1");
        assertTrue(card.contains("沈砚秋"));
        assertTrue(card.contains("【核心记忆】"));
        // 不设预算：关键关系段不再省略
        assertTrue(card.contains("【关键关系】"));
        assertTrue(card.contains("钱串子"));
    }

    /** 2026-10-10 身份去重：renderCoreSegment 自带【核心记忆】段头，characterCard 不得再拼（双段头根治）。 */
    @Test
    void characterCard_coreSegmentHeaderNotDoubled() {
        wireCharacter(1L);
        String card = injector.characterCard(1L, "npc:1");
        // 核心段头只出现一次（renderCoreSegment 自带），不得出现「【核心记忆】【核心记忆】」双头
        assertEquals(1, countOccurrences(card, "【核心记忆】"),
                "【核心记忆】段头不得重复（原「【核心记忆】」+ core 双头已根治）: " + card);
        assertFalse(card.contains("【核心记忆】【核心记忆】"), "不得出现双段头: " + card);
    }

    @Test
    void characterCard_skipsSegmentsWhenServicesMissing() {
        wireCharacter(1L);
        Reflection.set(injector, "personaService", null);
        Reflection.set(injector, "coreMemoryService", null);
        Reflection.set(injector, "relationRepository", null);
        Reflection.set(injector, "npcStateService", null);
        String card = injector.characterCard(1L, "npc:1");
        // 身份段仍在（只依赖 characterRepository）
        assertTrue(card.contains("沈砚秋"));
        assertFalse(card.contains("【关键关系】"));
        assertFalse(card.contains("【当前状态】"));
    }

    // ==================== 组合注入 compose ====================

    @Test
    void compose_combinesWorldAndCardWithHeaders() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        String composed = injector.compose(1L, "npc:1");
        assertTrue(composed.startsWith("【世界观】"));
        assertTrue(composed.contains("【我的身份与处境】"));
        assertTrue(composed.contains("沈砚秋"));
    }

    @Test
    void compose_worldOnlyWhenRefBlank() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        String composed = injector.compose(1L, null);
        assertTrue(composed.startsWith("【世界观】"));
        assertFalse(composed.contains("【我的身份与处境】"));
    }

    @Test
    void compose_emptyWhenBothEmpty() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        assertEquals("", injector.compose(1L, "npc:1"));
    }

    // ==================== enrichState（NanoJev 判定用） ====================

    @Test
    void enrichState_prependsContextToState() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        String enriched = injector.enrichState(1L, "npc:1", "npc:1 与 npc:2 的关系从「stranger」变为「acquaintance」");
        assertTrue(enriched.startsWith("【世界观】"));
        assertTrue(enriched.contains("【我的身份与处境】"));
        assertTrue(enriched.contains("沈砚秋"));
        assertTrue(enriched.contains("关系从「stranger」变为「acquaintance」"));
        assertTrue(enriched.indexOf("【世界观】") < enriched.indexOf("关系从"));
    }

    @Test
    void enrichState_returnsOriginalWhenNoContext() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        String state = "原判定文本";
        assertEquals(state, injector.enrichState(1L, "npc:1", state));
    }

    @Test
    void enrichState_handlesBlankState() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        String enriched = injector.enrichState(1L, null, "");
        assertTrue(enriched.startsWith("【世界观】"));
    }

    // ==================== V2.0 判定输入装配 decisionInput（2026-10-08 规范化重构批1） ====================

    @Test
    void decisionInput_assemblesSegments123() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        String input = injector.decisionInput(1L, "npc:1", "npc:1 与 npc:2 的关系从「stranger」变为「acquaintance」");
        // 段 1 前缀：世界观 + 系统指令 + 角色身份
        assertTrue(input.contains("【世界观】"));
        assertTrue(input.contains("【系统指令】"));
        assertTrue(input.contains(AiContextInjector.SEG_1B_NANOJEV_INSTRUCTION));
        assertTrue(input.contains("【角色身份】"));
        assertTrue(input.contains("沈砚秋"));
        // 段 2 动态切片：世界动态 + 本轮输入
        assertTrue(input.contains("【当前世界动态】"));
        assertTrue(input.contains("【本次情境】"));
        assertTrue(input.contains("关系从「stranger」变为「acquaintance」"));
        // 段 3 Post-History：护栏 + 输出契约在历史之后
        assertTrue(input.contains("【行为护栏】"));
        assertTrue(input.contains("【输出契约】"));
        assertTrue(input.indexOf("【行为护栏】") > input.indexOf("关系从"));
        assertTrue(input.indexOf("【输出契约】") > input.indexOf("【行为护栏】"));
    }

    @Test
    void decisionInput_omitsWorldAndCardWhenMissing_keepsDisciplineSegments() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        String input = injector.decisionInput(1L, "npc:1", "原判定文本");
        // 数据缺失 → 世界观/角色身份段省略，但系统指令+行为护栏+输出契约纪律段保留
        assertFalse(input.contains("【世界观】"));
        assertFalse(input.contains("【角色身份】"));
        assertTrue(input.contains("【系统指令】"));
        assertTrue(input.contains("【本次情境】"));
        assertTrue(input.contains("原判定文本"));
        assertTrue(input.contains("【行为护栏】"));
        assertTrue(input.contains("【输出契约】"));
    }

    @Test
    void decisionInput_handlesBlankStateAndRef() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        // characterRef 空 → 仅世界观 + 纪律段，无角色身份
        String input = injector.decisionInput(1L, null, "情境");
        assertTrue(input.contains("【世界观】"));
        assertFalse(input.contains("【角色身份】"));
        assertTrue(input.contains("情境"));
    }

    // ==================== V2.0 段 2D 当前世界动态 worldDynamicsLine（2026-10-08） ====================

    @Test
    void worldDynamicsLine_assemblesTimeSeasonWeather() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        com.holzyn.actor.domain.world.service.EnvironmentContextService env =
                Mockito.mock(com.holzyn.actor.domain.world.service.EnvironmentContextService.class);
        com.holzyn.actor.domain.world.vo.EnvironmentContextVO vo =
                new com.holzyn.actor.domain.world.vo.EnvironmentContextVO();
        vo.setWeather("晴");
        when(env.computeContext(eq(1L), any())).thenReturn(vo);
        Reflection.set(injector, "environmentContextService", env);
        String line = injector.worldDynamicsLine(1L);
        assertTrue(line.contains("世界历 0001年01月01日"));
        assertTrue(line.contains("季"));
        assertTrue(line.contains("晴"));
        assertTrue(line.length() <= 80);
    }

    @Test
    void worldDynamicsLine_emptyWhenNoClock() {
        Reflection.set(injector, "worldClockService", null);
        assertEquals("", injector.worldDynamicsLine(1L));
    }

    @Test
    void worldDynamicsLine_emptyWhenClockReturnsNull() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(null);
        assertEquals("", injector.worldDynamicsLine(1L));
    }

    @Test
    void worldDynamicsLine_degradesWeatherWhenEnvMissing() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        String line = injector.worldDynamicsLine(1L);
        assertTrue(line.contains("世界历 0001年01月01日"));
        assertFalse(line.contains("天气"));
    }

    // ==================== V2.0 世界视角前缀 worldDynamicsPrefix（2026-10-10 批 6 收口） ====================

    @Test
    void worldDynamicsPrefix_labelsSegment2D() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        String prefix = injector.worldDynamicsPrefix(1L);
        assertTrue(prefix.startsWith("【当前世界动态】"));
        assertTrue(prefix.contains("世界历 0001年01月01日"));
    }

    @Test
    void worldDynamicsPrefix_emptyWhenNoDynamics() {
        Reflection.set(injector, "worldClockService", null);
        assertEquals("", injector.worldDynamicsPrefix(1L));
    }

    // ==================== V2.0 世界视角前缀 worldContextPrefix（2026-10-10 批6 收口） ====================
    // 用户指示：世界视角 prompt 至少注入【世界观】与（有行动者时）角色身份——
    // worldContextPrefix = 段1A【世界观】+ 段1C【我的身份与处境】(有 ref) + 段2D【当前世界动态】

    @Test
    void worldContextPrefix_assemblesWorldCardAndDynamics() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        String prefix = injector.worldContextPrefix(1L, "npc:1");
        assertTrue(prefix.startsWith("【世界观】"), "应前置段1A 世界观: " + prefix);
        assertTrue(prefix.contains("【我的身份与处境】"), "应含段1C 角色身份: " + prefix);
        assertTrue(prefix.contains("沈砚秋"), "应含角色名: " + prefix);
        assertTrue(prefix.contains("【当前世界动态】"), "应含段2D: " + prefix);
        assertTrue(prefix.indexOf("【世界观】") < prefix.indexOf("【我的身份与处境】")
                && prefix.indexOf("【我的身份与处境】") < prefix.indexOf("【当前世界动态】"),
                "段序应为 1A → 1C → 2D: " + prefix);
    }

    @Test
    void worldContextPrefix_worldAndDynamicsOnlyWhenRefBlank() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        String prefix = injector.worldContextPrefix(1L, null);
        assertTrue(prefix.startsWith("【世界观】"), "应前置段1A 世界观: " + prefix);
        assertFalse(prefix.contains("【我的身份与处境】"), "无角色引用 → 不注入段1C: " + prefix);
        assertTrue(prefix.contains("【当前世界动态】"), "应含段2D: " + prefix);
    }

    @Test
    void worldContextPrefix_emptyWhenNoWorldAndNoDynamics() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        Reflection.set(injector, "worldClockService", null);
        assertEquals("", injector.worldContextPrefix(1L, "npc:1"));
    }

    // ==================== V2.0 决策与感知：段2E currentStateSegment + 段2D/2E decisionStatePrefix（2026-10-08 批 3） ====================

    @Test
    void currentStateSegment_delegatesStateService() {
        when(npcStateService.stateSegment(1L, "npc:1", 123L))
                .thenReturn("【当前状态】\n- 需求：饥饿 68/100（略饿）");
        String seg = injector.currentStateSegment(1L, "npc:1", 123L);
        assertTrue(seg.contains("【当前状态】"));
        assertTrue(seg.contains("饥饿 68/100"));
    }

    @Test
    void currentStateSegment_emptyWhenServiceMissing() {
        Reflection.set(injector, "npcStateService", null);
        assertEquals("", injector.currentStateSegment(1L, "npc:1", 0L));
    }

    @Test
    void currentStateSegment_emptyOnInvalidRef() {
        assertEquals("", injector.currentStateSegment(1L, null, 0L));
        assertEquals("", injector.currentStateSegment(1L, "  ", 0L));
    }

    @Test
    void decisionStatePrefix_assemblesSegment2DAnd2E() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        when(npcStateService.stateSegment(1L, "npc:1", 0L))
                .thenReturn("【当前状态】\n- 需求：饥饿 68/100（略饿）\n- 情绪：担忧（强度 42）");
        String prefix = injector.decisionStatePrefix(1L, "npc:1", 0L);
        // 段2D 当前世界动态（带段头）
        assertTrue(prefix.contains("【当前世界动态】"), "应含段2D段头: " + prefix);
        assertTrue(prefix.contains("世界历 0001年01月01日"));
        // 段2E 当前状态（stateSegment 自带段头）
        assertTrue(prefix.contains("【当前状态】"), "应含段2E段头: " + prefix);
        assertTrue(prefix.contains("饥饿 68/100"));
        // 顺序：段2D 在前、段2E 在后
        assertTrue(prefix.indexOf("【当前世界动态】") < prefix.indexOf("【当前状态】"),
                "段2D 应在段2E 前");
    }

    @Test
    void decisionStatePrefix_emptyWhenAllMissing() {
        Reflection.set(injector, "worldClockService", null);
        Reflection.set(injector, "npcStateService", null);
        assertEquals("", injector.decisionStatePrefix(1L, "npc:1", 0L));
    }

    @Test
    void decisionStatePrefix_degradesStateWhenStateServiceEmpty() {
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        when(npcStateService.stateSegment(1L, "npc:1", 0L)).thenReturn("");
        String prefix = injector.decisionStatePrefix(1L, "npc:1", 0L);
        assertTrue(prefix.contains("【当前世界动态】"), "状态空 → 仅保留段2D");
        assertFalse(prefix.contains("【当前状态】"));
    }

    // ==================== V2.0 记忆与反思链：段1A+1C+2D+2E memoryChainContext（2026-10-10 批 4） ====================

    @Test
    void memoryChainContext_assemblesComposeAndStatePrefix() {
        // compose（段1A 世界观 + 段1C 角色卡）
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        // decisionStatePrefix（段2D + 段2E）
        when(worldClockService.currentEngineGameSecond(1L)).thenReturn(0L);
        when(worldClockService.gameTimeTextOf(1L, 0L)).thenReturn("世界历 0001年01月01日 00时00分");
        when(npcStateService.stateSegment(1L, "npc:1", 123L))
                .thenReturn("【当前状态】\n- 需求：饥饿 68/100（略饿）\n- 情绪：担忧（强度 42）");
        String ctx = injector.memoryChainContext(1L, "npc:1", 123L);
        // 段1A 世界观
        assertTrue(ctx.contains("【世界观】"), "应含段1A世界观: " + ctx);
        assertTrue(ctx.contains("青禾村志"));
        // 段1C 角色核心卡（compose 的【我的身份与处境】段头）
        assertTrue(ctx.contains("【我的身份与处境】"), "应含段1C角色卡: " + ctx);
        assertTrue(ctx.contains("沈砚秋"));
        // 段2D 当前世界动态
        assertTrue(ctx.contains("【当前世界动态】"), "应含段2D段头: " + ctx);
        // 段2E 当前状态
        assertTrue(ctx.contains("【当前状态】"), "应含段2E段头: " + ctx);
        assertTrue(ctx.contains("饥饿 68/100"));
        // 顺序：段1（世界观/身份处境）在前、段2D 在段2E 前（段2E 用其唯一内容标记，
        // 避免与段1C 角色卡内的精简状态位「【当前状态】情绪：worried」混淆）
        assertTrue(ctx.indexOf("【世界观】") < ctx.indexOf("【我的身份与处境】")
                && ctx.indexOf("【我的身份与处境】") < ctx.indexOf("【当前世界动态】")
                && ctx.indexOf("【当前世界动态】") < ctx.indexOf("饥饿 68/100"),
                "段位顺序应 1A→1C→2D→2E: " + ctx);
    }

    @Test
    void memoryChainContext_emptyWhenAllMissing() {
        Reflection.set(injector, "worldSettingRepository", null);
        Reflection.set(injector, "characterRepository", null);
        Reflection.set(injector, "worldClockService", null);
        Reflection.set(injector, "npcStateService", null);
        assertEquals("", injector.memoryChainContext(1L, "npc:1", 0L));
    }

    @Test
    void memoryChainContext_keepsComposeWhenDynamicsAndStateMissing() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(world()));
        wireCharacter(1L);
        Reflection.set(injector, "worldClockService", null);
        Reflection.set(injector, "npcStateService", null);
        String ctx = injector.memoryChainContext(1L, "npc:1", 0L);
        assertTrue(ctx.contains("【世界观】"), "段2D/2E 缺失 → 仍保留段1A+1C: " + ctx);
        assertTrue(ctx.contains("【我的身份与处境】"));
        assertFalse(ctx.contains("【当前世界动态】"));
        assertFalse(ctx.contains("【当前状态】"));
    }

    // ==================== V2.0 对话链：段 3I 护栏 + 段 2F few-shot（2026-10-08 批 2） ====================

    @Test
    void dialogueGuardRail_containsFirstPersonAndNoProxyRules() {
        String rail = AiContextInjector.dialogueGuardRail("沈砚秋");
        assertTrue(rail.contains("沈砚秋"));
        assertTrue(rail.contains("第一人称"));
        assertTrue(rail.contains("不替其他角色"));
        assertTrue(rail.contains("只回复这一轮"));
        assertTrue(rail.contains("不要跳出角色"));
    }

    /** 角色发言消息（含群聊契约 rawStream：动作 + 情绪）。 */
    private ActorMessage assistantMsg(Long id, Long characterId, String content, String raw) {
        ActorMessage m = new ActorMessage();
        m.setId(id);
        m.setCharacterId(characterId);
        m.setRole("assistant");
        m.setType("text");
        m.setStatus("done");
        m.setContent(content);
        m.setRawStream(raw);
        return m;
    }

    private ActorMessage userMsg(Long id, String content) {
        ActorMessage m = new ActorMessage();
        m.setId(id);
        m.setRole("user");
        m.setType("text");
        m.setStatus("done");
        m.setContent(content);
        return m;
    }

    /** few-shot 通用打桩：角色 1 有一个会话（conv=7），历史含对方发言 + 本人发言（带动作/情绪）。 */
    private void stubFewShotHistory() {
        ActorConversationMember mem = new ActorConversationMember();
        mem.setConversationId(7L);
        mem.setCharacterId(1L);
        when(conversationMemberRepository.findByCharacterId(1L)).thenReturn(List.of(mem));
        ActorConversation conv = new ActorConversation();
        conv.setId(7L);
        conv.setProjectId(1L);
        conv.setUpdatedAt(java.time.LocalDateTime.of(2026, 10, 8, 10, 0));
        when(conversationRepository.findById(7L)).thenReturn(Optional.of(conv));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character()));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(partner()));
        when(messageRepository.findByConversationIdOrderByIdAsc(7L)).thenReturn(List.of(
                assistantMsg(1L, 2L, "今天这雨下得没完没了，地里的活怕是要耽搁两天。", null),
                assistantMsg(2L, 1L, "无妨，天要下雨娘要嫁人，正好趁空当把祠堂账目理一理。",
                        "{\"dialogue\":\"无妨…\",\"action\":\"端起茶碗抿了一口\","
                                + "\"emotion_update\":{\"emotion\":\"平淡\",\"intensity\":2},\"internal_thought\":\"\"}")));
    }

    /** 对家角色（few-shot 前文归属者）。 */
    private ActorCharacter partner() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(2L);
        ch.setProjectId(1L);
        ch.setName("钱串子");
        return ch;
    }

    @Test
    void dialogueFewShot_extractsRecentRealDialoguesWithActionEmotion() {
        stubFewShotHistory();
        String shot = injector.dialogueFewShot(1L, "npc:1");
        assertTrue(shot.contains("【示例对话】"), "应含示例段头");
        assertTrue(shot.contains("钱串子"), "应含对方名（前文归属者）");
        assertTrue(shot.contains("今天这雨下得没完没了"), "应含对方前文");
        assertTrue(shot.contains("沈砚秋"), "应含本人名");
        assertTrue(shot.contains("把祠堂账目理一理"), "应含本人真实发言");
        assertTrue(shot.contains("动作：端起茶碗抿了一口"), "应从 rawStream 提取动作");
        assertTrue(shot.contains("情绪：平淡"), "应从 rawStream 提取情绪");
    }

    @Test
    void dialogueFewShot_skipsMessageWithoutPreceding_fallsBackToTemplate() {
        // 历史只有本人开场第一句（无对方前文）→ 无可抽真实示例 → 模板预置示例兜底
        ActorConversationMember mem = new ActorConversationMember();
        mem.setConversationId(7L);
        mem.setCharacterId(1L);
        when(conversationMemberRepository.findByCharacterId(1L)).thenReturn(List.of(mem));
        ActorConversation conv = new ActorConversation();
        conv.setId(7L);
        conv.setProjectId(1L);
        when(conversationRepository.findById(7L)).thenReturn(Optional.of(conv));
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character()));
        when(messageRepository.findByConversationIdOrderByIdAsc(7L)).thenReturn(List.of(
                assistantMsg(1L, 1L, "（开场自说自话，没有前文）", null)));
        String shot = injector.dialogueFewShot(1L, "npc:1");
        assertTrue(shot.contains("【示例对话】"));
        assertFalse(shot.contains("开场自说自话"), "无前文的发言不应作为真实示例（只有有来有往才算示例）");
        assertTrue(shot.contains("模板") || shot.contains("说话方式范例"), "应回退模板预置示例");
    }

    @Test
    void dialogueFewShot_fallsBackToTemplateWhenNoHistory() {
        when(conversationMemberRepository.findByCharacterId(1L)).thenReturn(List.of());
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character()));
        String shot = injector.dialogueFewShot(1L, "npc:1");
        assertTrue(shot.contains("【示例对话】"));
        assertTrue(shot.contains("沈砚秋"), "模板兜底应含角色名");
    }

    @Test
    void dialogueFewShot_returnsEmptyWhenReposMissing() {
        Reflection.set(injector, "conversationMemberRepository", null);
        Reflection.set(injector, "conversationRepository", null);
        Reflection.set(injector, "messageRepository", null);
        assertEquals("", injector.dialogueFewShot(1L, "npc:1"));
        assertEquals("", injector.dialogueFewShot(1L, null));
    }

    /** 反射设置私有字段（同包测试辅助，对齐既有测试风格）。 */
    private static final class Reflection {
        static void set(Object target, String field, Object value) {
            try {
                java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 统计子串出现次数（2026-10-10 身份去重断言辅助）。 */
    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
