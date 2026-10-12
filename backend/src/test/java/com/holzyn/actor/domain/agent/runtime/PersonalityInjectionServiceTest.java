package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.service.PhysioProfile;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.config.EnvironmentProperties;
import com.holzyn.actor.domain.world.service.EnvironmentContextService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.EnvironmentContextVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 个性注入服务单元测试（实时行为决策系统 M3 个性注入 + 社交差异化，domain/agent/runtime，同包）。
 * <p>职责：验证【角色个性】段组装——① 各段齐全（档案分片[决策场景 action_gen]/L3 核心块/
 * brainProfile 行事倾向/关系摘要/时间感）；② 社交差异化=相关人物关系优先 + 全量兜底 ≤5；
 * ③ 逐段降级（服务未装配/数据缺失/异常自动跳过该段，全空返回空串；分片为空回退身份基线段）；
 * ④ 全段总长上限截断；⑤ 情境拼装 composeStimulusSituation（个性段 + 刺激 + 决策要求；空个性段回退纯刺激）；
 * ⑥ 静态纯逻辑（relationLine/stateLabel/otherName/normalizeNames/personaEnvironmentHints/environmentConstraints）。</p>
 * <p><b>O6 G1a 口径</b>：不再读角色卡（表已废弃）——角色 facet 文本由
 * {@code PersonaService.renderShardsForScene(id, "action_gen")} 按场景渲染 11 维档案分片；
 * 环境偏好关键词匹配读 {@code actor_profile.persona_json}（主表实体，v2 键路径
 * {@code dims.personality.traits_summary} / {@code dims.habits.quirks} /
 * {@code dims.appearance.constitution_weaknesses}），并合并 ③ 生理基线派生词条
 * （权威在状态模块 {@code actor_state.physio_json}）；统一身份引用为 {@code npc:{id}}。</p>
 * <p>设计依据：《HolzynActor_实时行为决策系统_开发路径_V1.0》§4.4（个性注入零新表）
 * + M3 用户逐题决策（2026-09-07：双轨注入[即时决策+恢复决策]/拼入 situation/相关人物优先+全量兜底）。</p>
 */
class PersonalityInjectionServiceTest {

    private ActorCharacterRepository characterRepository;
    private ActorCharacterRelationRepository relationRepository;
    private RealtimeDecisionProperties props;
    private PersonalityInjectionService service;
    private PersonaService personaService;
    private CoreMemoryService coreMemoryService;
    private BrainProfileService brainProfileService;
    private WorldClockService worldClockService;
    private ActorSandboxStateRepository sandboxStateRepository;

    /** {@code actor_profile.persona_json}（v2 11 维档案；供环境偏好关键词匹配） */
    private static final String PERSONA_JSON = """
            {
              "schema_version": 2,
              "dims": {
                "identity": {"identity_title":"青禾村村长","livelihood":"一村之长，掌村印与宗祠"},
                "personality": {"traits":["稳重隐忍","面冷心热","思虑周全"],
                  "quirks":["说话前先捋胡须"],"openness":4,"conscientiousness":5,
                  "extraversion":3,"agreeableness":4,"neuroticism":2},
                "voice": {"tone":"稳重内敛，慢条斯理",
                  "vocabulary":"村务、印信、叨扰","catchphrases":["村土平安，比什么都紧要。"],
                  "taboos":"不轻议巫祝神契"},
                "habits": {"daily_routine":["晨起巡村","傍晚在茶馆听半盏茶"]}
              }
            }
            """;

    /** 决策场景（action_gen）档案分片渲染文本——由 PersonaService 按场景产出（单测以 mock 代表） */
    private static final String PERSONA_SHARDS = """
            我是沈砚秋（青禾村村长 · 沈氏宗族族长）。身份锚定：青禾村村长，掌村印与宗祠。
            人格与价值观：稳重隐忍、面冷心热、思虑周全；开放性=4、尽责性=5、外向性=3、宜人性=4、神经质=2。
            言语风格：稳重内敛，慢条斯理；「村土平安，比什么都紧要。」""";

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        props = new RealtimeDecisionProperties();
        // O6 G1a：角色卡仓库已删（单主表）——构造器为 4 参，personaService 为 @Autowired(required=false) 字段注入
        service = new PersonalityInjectionService(characterRepository, relationRepository,
                props, new ObjectMapper());
        personaService = Mockito.mock(PersonaService.class);
        coreMemoryService = Mockito.mock(CoreMemoryService.class);
        brainProfileService = Mockito.mock(BrainProfileService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        sandboxStateRepository = Mockito.mock(ActorSandboxStateRepository.class);
        Reflection.set(service, "personaService", personaService);
        Reflection.set(service, "coreMemoryService", coreMemoryService);
        Reflection.set(service, "brainProfileService", brainProfileService);
        Reflection.set(service, "worldClockService", worldClockService);
        Reflection.set(service, "sandboxStateRepository", sandboxStateRepository);

        ActorCharacter ch = newChar(1L, "沈砚秋", "在祠堂核对账册", "祠堂");
        // 档案（v2）挂在主表实体上：环境偏好关键词匹配读 actor_profile.persona_json
        ch.setPersonaJson(PERSONA_JSON);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        when(personaService.renderShardsForScene(1L, "action_gen")).thenReturn(PERSONA_SHARDS);
        when(coreMemoryService.renderCoreSegment(any(), anyString()))
                .thenReturn("【核心记忆】我是一村之长，把村土平安看得比性命还重。");
        ActorAgentProfile profile = new ActorAgentProfile();
        profile.setBrainLevel("full");
        profile.setDecisionFrequency("realtime");
        profile.setThinkingEnabled(1);
        profile.setPlanningEnabled(0);
        when(brainProfileService.resolveByRef(anyString())).thenReturn(profile);
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        when(worldClockService.currentGameHour(any())).thenReturn(10L);
        when(worldClockService.gameTimeTextOf(any(), any())).thenReturn("灵溪历 0103年02月03日 巳时");
        ActorSandboxState st = new ActorSandboxState();
        st.setNpcRef("npc:1");
        st.setGameSecond(30000L);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(eq(1L), eq("npc:1"))).thenReturn(Optional.of(st));
    }

    private ActorCharacter newChar(Long id, String name, String activity, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setTitle("青禾村村长 · 沈氏宗族族长");
        ch.setCurrentActivity(activity);
        ch.setLocation(location);
        return ch;
    }

    private ActorCharacterRelation newRelation(Long fromId, Long toId, String fromName, String toName,
                                               String type, Integer affinity, Integer trust, String state,
                                               String description) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(1L);
        r.setFromCharacterId(fromId);
        r.setToCharacterId(toId);
        r.setFromName(fromName);
        r.setToName(toName);
        r.setRelationType(type);
        r.setAffinity(affinity);
        r.setTrust(trust);
        r.setRelationState(state);
        r.setDescription(description);
        return r;
    }

    // ==================== 各段齐全组装 ====================

    @Test
    void buildPersonalitySegment_assemblesAllSections() {
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertNotNull(seg);
        // G1a：角色 facet 段（身份锚定/人格与价值观/言语风格）由 action_gen 场景档案分片整体注入
        Mockito.verify(personaService).renderShardsForScene(1L, "action_gen");
        assertTrue(seg.contains("沈砚秋"), "档案分片：身份锚定（姓名）");
        assertTrue(seg.contains("青禾村村长"), "档案分片：身份锚定（头衔）");
        assertTrue(seg.contains("稳重隐忍"), "档案分片：人格 traits");
        assertTrue(seg.contains("开放性=4"), "档案分片：OCEAN 五维");
        assertTrue(seg.contains("稳重内敛"), "档案分片：言语风格");
        assertTrue(seg.contains("我是一村之长"), "L3 核心段");
        assertTrue(seg.contains("行事倾向"), "brainProfile 段");
        assertTrue(seg.contains("灵溪历"), "时间感段：当前游戏时刻");
        assertTrue(seg.contains("已持续约"), "时间感段：已持续时长");
    }

    /**
     * 无档案分片（空串）→ 回退身份基线段（零阻断兜底；保证角色仍有最小身份锚定）。
     */
    @Test
    void buildPersonalitySegment_identityFallbackWhenNoShards() {
        when(personaService.renderShardsForScene(1L, "action_gen")).thenReturn("");
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertTrue(seg.contains("我是沈砚秋"), "无分片 → 回退身份基线");
        assertTrue(seg.contains("青禾村村长 · 沈氏宗族族长"), "回退段含头衔");
        assertTrue(seg.contains("此刻正在：在祠堂核对账册"), "回退段含当前行动");
    }

    @Test
    void buildPersonalitySegment_disabledReturnsEmpty() {
        props.setPersonalityInjectEnabled(false);
        assertEquals("", service.buildPersonalitySegment(1L, "npc:1", 36000L, null));
    }

    @Test
    void buildPersonalitySegment_identityOnlyWhenNoPersonaData() {
        when(personaService.renderShardsForScene(1L, "action_gen")).thenReturn("");
        when(coreMemoryService.renderCoreSegment(any(), anyString())).thenReturn("");
        when(brainProfileService.resolveByRef(anyString())).thenReturn(null);
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        ActorCharacter ch = newChar(1L, "沈砚秋", "在祠堂核对账册", "祠堂");
        ch.setTitle(null);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        // 时间感：无游戏时刻（gameSecond=null 且时钟无值）+ 无 sandbox 行 → 空
        when(worldClockService.currentGameHour(any())).thenReturn(null);
        when(sandboxStateRepository.findByProjectIdAndNpcRef(any(), any())).thenReturn(Optional.empty());
        String seg = service.buildPersonalitySegment(1L, "npc:1", null, null);
        assertTrue(seg.contains("我是沈砚秋"), "身份基线恒在（角色有名字即非空）");
        assertFalse(seg.contains("人格与价值观"), "无档案分片 → 跳过人格段");
        assertFalse(seg.contains("核心信念"), "无 L3 → 跳过核心段");
        assertFalse(seg.contains("行事倾向"), "无 brainProfile → 跳过脑力段");
        assertFalse(seg.contains("【人际关系】"), "无关系 → 跳过关系段");
        assertFalse(seg.contains("【时间感】"), "无时刻/时长 → 跳过时间感段");
    }

    @Test
    void buildPersonalitySegment_sectionFailureDegrades() {
        // L3 核心段失败 → 跳过该段，其余段正常
        when(coreMemoryService.renderCoreSegment(any(), anyString()))
                .thenThrow(new RuntimeException("L3 失败"));
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertTrue(seg.contains("沈砚秋"), "其余段不受影响");
        assertFalse(seg.contains("核心信念"), "L3 段跳过");
    }

    // ==================== 能力自评段（P1 ①-2 能力自评卡） ====================

    @Test
    void buildPersonalitySegment_selfEfficacyInjectedWhenPresent() {
        when(personaService.latestPersonaJson(1L)).thenReturn(Optional.of("""
                {
                  "schema_version": 2,
                  "dims": {
                    "ability": {
                      "skills": {"农事耕作": 8.0, "记账算筹": 5.0},
                      "self_efficacy": {"农事耕作": 4, "记账算筹": 2},
                      "self_comment": "农活是把好手，就是算账总犯迷糊"
                    }
                  }
                }
                """));
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertTrue(seg.contains("【能力自评】"), "有自评 → 注入能力自评段");
        assertTrue(seg.contains("农事耕作=4/5"), "自评领域 + 分数");
        assertTrue(seg.contains("记账算筹=2/5"), "自评领域 + 分数（低分领域）");
        assertTrue(seg.contains("整体：农活是把好手，就是算账总犯迷糊"), "整体自评一句");
    }

    @Test
    void buildPersonalitySegment_selfEfficacySkippedWhenAbsent() {
        when(personaService.latestPersonaJson(1L)).thenReturn(Optional.of("""
                {
                  "schema_version": 2,
                  "dims": {
                    "ability": {"skills": {"农事耕作": 8.0}}
                  }
                }
                """));
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertFalse(seg.contains("【能力自评】"), "无自评 → 跳过能力自评段");
    }

    @Test
    void buildPersonalitySegment_selfEfficacySkippedWhenNoPersona() {
        when(personaService.latestPersonaJson(1L)).thenReturn(Optional.empty());
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertFalse(seg.contains("【能力自评】"), "无档案 → 跳过能力自评段");
    }

    @Test
    void buildPersonalitySegment_truncatesToMaxLength() {
        props.setPersonalityMaxLength(200);
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertTrue(seg.length() <= 200, "全段总长上限截断");
    }

    @Test
    void buildPersonalitySegment_characterNotFoundEmpty() {
        when(characterRepository.findById(1L)).thenReturn(Optional.empty());
        assertEquals("", service.buildPersonalitySegment(1L, "npc:1", 36000L, null));
    }

    // ==================== 社交差异化（相关人物优先 + 全量兜底） ====================

    @Test
    void relationSegment_relatedNamesFirstThenFallback() {
        ActorCharacterRelation toWen = newRelation(1L, 4L, "沈砚秋", "温叙", "相敬相惜", 80, 75, "trust", "医者仁心");
        ActorCharacterRelation toLu = newRelation(1L, 2L, "沈砚秋", "陆沉舟", "恩义旧识", 70, 60, "friendly", "曾助村清匪");
        ActorCharacterRelation toBai = newRelation(1L, 3L, "沈砚秋", "白灵犀", "合作存疑", 50, 40, "distrust", "神契之事");
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of(toWen, toLu, toBai));
        // 相关人物=温叙 → 温叙关系优先出现
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, List.of("温叙"));
        int idxWen = seg.indexOf("与温叙");
        int idxLu = seg.indexOf("与陆沉舟");
        int idxBai = seg.indexOf("与白灵犀");
        assertTrue(idxWen >= 0 && idxLu >= 0 && idxBai >= 0, "三条关系都在");
        assertTrue(idxWen < idxLu && idxWen < idxBai, "相关人物（温叙）优先于其余关系");
        assertTrue(seg.contains("好感80"), "关系含好感数值");
        assertTrue(seg.contains("信任75"), "关系含信任数值");
        assertTrue(seg.contains("信任"), "关系状态中文标签");
    }

    @Test
    void relationSegment_totalCappedAtFive() {
        List<ActorCharacterRelation> rels = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rels.add(newRelation(1L, (long) (10 + i), "沈砚秋", "人物" + i, "相识", 50, 50, "neutral", null));
        }
        when(relationRepository.findByProjectId(1L)).thenReturn(rels);
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertTrue(seg.contains("【人际关系】"));
        assertTrue(countOccurrences(seg, "与人物") <= 5, "全量兜底 ≤5 条");
    }

    @Test
    void relationSegment_noRelationsSkip() {
        when(relationRepository.findByProjectId(1L)).thenReturn(List.of());
        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);
        assertFalse(seg.contains("【人际关系】"), "无关系跳过关系段");
    }

    // ==================== 情境拼装（composeStimulusSituation） ====================

    @Test
    void composeStimulusSituation_withSegment() {
        String s = PersonalityInjectionService.composeStimulusSituation("我是沈砚秋，稳重隐忍",
                "粮仓失火：浓烟从村东粮仓升起");
        assertTrue(s.contains("【角色个性】"));
        assertTrue(s.contains("我是沈砚秋"));
        assertTrue(s.contains("【本次刺激】"));
        assertTrue(s.contains("粮仓失火"));
        assertTrue(s.contains("【决策要求】"), "决策要求指令（是否反应/关系/是否回去继续）");
        assertTrue(s.contains("继续手头的事"), "允许「不理会」合理反应（真人感）");
        assertTrue(s.contains("OOC"), "禁止 OOC");
    }

    @Test
    void composeStimulusSituation_withoutSegmentFallsBackToStimulus() {
        String s = PersonalityInjectionService.composeStimulusSituation("", "集市异动");
        assertFalse(s.contains("【角色个性】"), "空个性段不注入个性标题");
        assertTrue(s.contains("集市异动"));
        // 注意：空个性段时调用方直接使用纯刺激情境（本方法仍输出刺激+决策要求，调用方兜底处理）
        assertTrue(s.contains("【本次刺激】"));
    }

    // ==================== 静态纯逻辑 ====================

    @Test
    void relationLine_formatsAffinityTrustState() {
        ActorCharacterRelation r = newRelation(1L, 4L, "沈砚秋", "温叙", "相敬相惜", 80, 75, "trust", "医者仁心");
        String line = PersonalityInjectionService.relationLine(r, "温叙");
        assertTrue(line.startsWith("与温叙"));
        assertTrue(line.contains("相敬相惜"));
        assertTrue(line.contains("好感80"));
        assertTrue(line.contains("信任75"));
        assertTrue(line.contains("信任"));
        assertTrue(line.contains("医者仁心"));
    }

    @Test
    void stateLabel_mapsKnownStates() {
        assertEquals("信任", PersonalityInjectionService.stateLabel("trust"));
        assertEquals("猜忌", PersonalityInjectionService.stateLabel("distrust"));
        assertEquals("敌对", PersonalityInjectionService.stateLabel("hostile"));
        assertEquals("未知", PersonalityInjectionService.stateLabel(null));
        assertEquals("自定义", PersonalityInjectionService.stateLabel("自定义"));
    }

    @Test
    void otherName_byIdAndByName() {
        ActorCharacterRelation r = newRelation(1L, 4L, "沈砚秋", "温叙", "相识", null, null, null, null);
        assertEquals("温叙", PersonalityInjectionService.otherName(r, 1L, null), "按 from id 匹配 → toName");
        assertEquals("沈砚秋", PersonalityInjectionService.otherName(r, 4L, null), "按 to id 匹配 → fromName");
        assertEquals("温叙", PersonalityInjectionService.otherName(r, null, "沈砚秋"), "按名称匹配");
        assertEquals(null, PersonalityInjectionService.otherName(r, 99L, null), "无关联 → null");
    }

    @Test
    void normalizeNames_dedupsKeepsOrder() {
        assertEquals(List.of("温叙", "陆沉舟"),
                PersonalityInjectionService.normalizeNames(java.util.Arrays.asList(" 温叙 ", "陆沉舟", "温叙", "", null)));
        assertTrue(PersonalityInjectionService.normalizeNames(null).isEmpty());
    }

    // ==================== 时间季节系统 M3（2026-09-15）：六类性格加权 + 极值高权重 ====================

    @Test
    void personaEnvironmentHints_sixCategoryMatching() {
        ObjectMapper m = new ObjectMapper();
        // O6 G1a：夹具按 **v2 档案真实键路径**给出（怕冷/不耐寒 → ②体质弱点；
        // 喜雨/夜猫子 → ④人格特质摘要；身子弱 → ⑧习惯癖好）。
        // 旧夹具用的是 v1 平铺键 personality.traits/quirks，在 v2 档案下恒为空 →
        // 六类加权会静默失效，故这里必须锚定 v2 形态（与真库 actor_profile.persona_json 一致）。
        JsonNode persona = m.createObjectNode()
                .set("dims", m.createObjectNode()
                        .set("personality", m.createObjectNode()
                                .put("traits_summary", "怕冷、喜雨、夜猫子"))
                        .set("habits", m.createObjectNode()
                                .set("quirks", m.createArrayNode().add("身子弱")))
                        .set("appearance", m.createObjectNode()
                                .set("constitution_weaknesses", m.createArrayNode().add("不耐寒"))));
        EnvironmentContextVO winter = ctx("winter", "小雪", "night", -9.0);
        List<String> hints = PersonalityInjectionService.personaEnvironmentHints(persona, winter);
        assertTrue(hints.stream().anyMatch(h -> h.contains("怕冷")), "怕冷+冬 → 提示: " + hints);
        assertTrue(hints.stream().anyMatch(h -> h.contains("身子弱") || h.contains("保重")), "体弱+雪 → 提示: " + hints);
        assertTrue(hints.stream().anyMatch(h -> h.contains("夜猫子")), "夜猫子+夜 → 提示: " + hints);

        // 喜雨 + 雨天
        EnvironmentContextVO rainy = ctx("spring", "小雨", "day", 14.0);
        List<String> rain = PersonalityInjectionService.personaEnvironmentHints(persona, rainy);
        assertTrue(rain.stream().anyMatch(h -> h.contains("喜欢下雨")), "喜雨+雨 → 提示: " + rain);

        // 好动 + 晴天白昼
        JsonNode activePersona = m.createObjectNode()
                .set("dims", m.createObjectNode()
                        .set("personality", m.createObjectNode()
                                .put("traits_summary", "闲不住"))
                        .set("habits", m.createObjectNode()
                                .set("quirks", m.createArrayNode())));
        List<String> active = PersonalityInjectionService.personaEnvironmentHints(activePersona,
                ctx("autumn", "晴", "day", 18.0));
        assertTrue(active.stream().anyMatch(h -> h.contains("出门走动")), "好动+晴昼 → 提示: " + active);

        // 无命中 → 空
        JsonNode plain = m.createObjectNode()
                .set("dims", m.createObjectNode()
                        .set("personality", m.createObjectNode()
                                .put("traits_summary", "稳重"))
                        .set("habits", m.createObjectNode()
                                .set("quirks", m.createArrayNode())));
        assertTrue(PersonalityInjectionService.personaEnvironmentHints(plain, winter).isEmpty(), "无命中空");
        assertTrue(PersonalityInjectionService.personaEnvironmentHints(null, winter).isEmpty(), "无 persona 空");
        assertTrue(PersonalityInjectionService.personaEnvironmentHints(persona, null).isEmpty(), "无环境空");
    }

    /**
     * ③ 生理基线派生词条（O6 G1a：③ 的权威在<b>状态模块</b> actor_state.physio_json，
     * 档案里没有该维）——夜猫子/怕冷/怕热 由生效系数派生后参与同一张六类关键词表。
     */
    @Test
    void personaEnvironmentHints_physioTagsFromStateModule() {
        // 生效系数：夜猫子 + 明显怕冷（coldSens 1.3 = 耐寒 ≤3/10）+ 不怕热
        PhysioProfile p = PhysioProfile.fromCoefJson(
                "{\"chronotype\":\"night_owl\",\"coldSens\":1.3,\"heatSens\":0.8}");
        List<String> tags = PersonalityInjectionService.physioEnvTags(p);
        assertTrue(tags.contains("夜猫子"), "night_owl → 夜猫子词条: " + tags);
        assertTrue(tags.contains("怕冷"), "coldSens 1.3 → 怕冷词条: " + tags);
        assertFalse(tags.contains("怕热"), "heatSens 0.8 不产生怕热词条: " + tags);

        // 默认系数（intermediate / 1.0 / 1.0）→ 不产生任何词条
        assertTrue(PersonalityInjectionService.physioEnvTags(PhysioProfile.defaults()).isEmpty(), "默认系数无词条");
        assertTrue(PersonalityInjectionService.physioEnvTags(null).isEmpty(), "无系数无词条");

        // 端到端：档案里什么都不写，仅靠生理词条也能生成"夜猫子+夜"提示
        ObjectMapper m = new ObjectMapper();
        JsonNode emptyPersona = m.createObjectNode().set("dims", m.createObjectNode());
        List<String> hints = PersonalityInjectionService.personaEnvironmentHints(
                emptyPersona, ctx("winter", "晴", "night", -3.0), tags);
        assertTrue(hints.stream().anyMatch(h -> h.contains("夜猫子")), "生理词条+夜 → 提示: " + hints);
        assertTrue(hints.stream().anyMatch(h -> h.contains("怕冷")), "生理词条+低温 → 提示: " + hints);
    }

    @Test
    void environmentConstraints_extremeWeights() {
        EnvironmentProperties p = new EnvironmentProperties();
        // 暴雪/暴雨 + 体感 ≤ 5 → 出行降权
        List<String> storm = PersonalityInjectionService.environmentConstraints(
                ctx("winter", "小雪", "day", 3.0), null, p);
        assertTrue(storm.stream().anyMatch(h -> h.contains("天气恶劣")), "暴雪出行降权: " + storm);
        // 深夜 23 点 + energy 20 < 30 → 回家倾向
        EnvironmentContextVO nightCtx = ctx("spring", "阴", "night", 8.0);
        nightCtx.setHourOfDay(23);
        List<String> night = PersonalityInjectionService.environmentConstraints(nightCtx, 20, p);
        assertTrue(night.stream().anyMatch(h -> h.contains("回家休息")), "深夜+疲劳回家倾向: " + night);
        // 深夜但 energy 充足 → 不触发疲劳约束
        List<String> nightOk = PersonalityInjectionService.environmentConstraints(nightCtx, 60, p);
        assertTrue(nightOk.stream().noneMatch(h -> h.contains("回家休息")), "有精神不强制回家: " + nightOk);
        // 极端低温体感 -16 ≤ -15 → 户外降权
        List<String> cold = PersonalityInjectionService.environmentConstraints(
                ctx("winter", "晴", "day", -16.0), 80, p);
        assertTrue(cold.stream().anyMatch(h -> h.contains("寒冷刺骨")), "极寒户外降权: " + cold);
        // 无极端 → 空
        assertTrue(PersonalityInjectionService.environmentConstraints(
                ctx("spring", "晴", "day", 18.0), 80, p).isEmpty(), "常态无极值约束");
        assertTrue(PersonalityInjectionService.environmentConstraints(null, 80, p).isEmpty(), "无环境空");
    }

    @Test
    void buildPersonalitySegment_includesEnvironmentSegments() {
        // 注入环境服务与配置（六类映射：②体质弱点 加「怕冷」；冬夜环境 → 环境偏好段）
        // G1a：关键词匹配读主表实体 actor_profile.persona_json（不再是角色卡行），
        // 且夹具按 **v2 档案键路径**给出（dims.appearance.constitution_weaknesses）——
        // v1 平铺键 personality.traits 在 v2 档案下恒为空，那样断言会失去意义。
        ActorCharacter ch = newChar(1L, "沈砚秋", "在祠堂核对账册", "祠堂");
        ch.setPersonaJson("{\"dims\":{\"appearance\":{\"constitution_weaknesses\":[\"怕冷\",\"畏寒\"]},"
                + "\"personality\":{\"traits_summary\":\"稳重隐忍\"},\"habits\":{\"quirks\":[]}}}");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));

        EnvironmentContextService env = Mockito.mock(EnvironmentContextService.class);
        EnvironmentContextVO ctx = ctx("winter", "小雪", "night", -9.0);
        ctx.setSeasonName("冬");
        ctx.setPhaseName("夜晚");
        ctx.setDesc("冬日的夜晚，寒风呼啸，飘着雪");
        ctx.setWeather("小雪");
        ctx.setFeelsLikeC(-9.0);
        ctx.setSunset("17:30");
        when(env.computeContext(any(), any())).thenReturn(ctx);
        when(env.injectionSegment(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                "【当前环境】冬日夜晚，飘着雪（体感 −9℃，日落 17:30）");
        Reflection.set(service, "environmentContextService", env);
        Reflection.set(service, "environmentProperties", new EnvironmentProperties());

        String seg = service.buildPersonalitySegment(1L, "npc:1", 36000L, null);

        assertTrue(seg.contains("【当前环境】"), "时间+环境段: " + seg);
        assertTrue(seg.contains("【环境偏好】"), "性格加权段: " + seg);
        assertTrue(seg.contains("怕冷"), "怕冷映射命中: " + seg);
        // 极值约束：冬夜小雪体感 −9 ≤ 5 → 「天气恶劣」出行降权段
        assertTrue(seg.contains("【环境约束】"), "雪天体感低 → 极值约束段: " + seg);
        assertTrue(seg.contains("天气恶劣"), "暴雪出行降权文案: " + seg);
    }

    /** 构造环境上下文（静态辅助）。 */
    private static EnvironmentContextVO ctx(String season, String weather, String phase, Double feelsLike) {
        EnvironmentContextVO vo = new EnvironmentContextVO();
        vo.setSeason(season);
        vo.setWeather(weather);
        vo.setPhase(phase);
        vo.setFeelsLikeC(feelsLike);
        return vo;
    }

    /** 出现次数统计辅助。 */
    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** 反射设置私有字段（同包测试辅助，对齐 RealTimeDecisionServiceTest 风格）。 */
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
}
