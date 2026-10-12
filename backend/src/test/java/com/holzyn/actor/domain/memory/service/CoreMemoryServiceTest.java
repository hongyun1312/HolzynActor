package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.memory.vo.CoreBlockVO;
import com.holzyn.actor.domain.memory.vo.CoreMemoryVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L3 核心记忆服务单元测试（M3，domain/memory/service，同包）。
 * <p>职责：验证——静态基线组合渲染（特殊型模块源 / 普通型轻量 / 空态）、【核心记忆】段渲染
 * （基线 + 核心块 + 顺序 + 有界）、核心块 CRUD（新建/块键校验/内容有界/块数上限/同键升级版本链/软删）、
 * agent 自编辑应用（update/create/delete、只读块拒绝、块数上限、普通型拒绝）。</p>
 * <p>O6 G1a 口径：档案权威源改为 {@code actor_profile.persona_json}（经 {@code PersonaService}），
 * 类型分流改为查 {@code actor_profile.type}（{@code NpcTypeResolver}），统一身份引用为 {@code npc:{id}}。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§7/§9。</p>
 */
class CoreMemoryServiceTest {

    private ActorCoreMemoryRepository coreRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterGoalRepository goalRepository;
    private ActorCharacterRelationRepository relationRepository;
    private ActorCharacterRepository npcRepository;
    private ActorAgentProfileRepository profileRepository;
    private ActorProjectRepository projectRepository;
    private PersonaService personaService;
    private NpcTypeResolver npcTypeResolver;
    private CoreMemoryService service;

    /** 内存核心块存储（模拟真实 DB：save 按主键更新，findById 可读） */
    private final List<ActorCoreMemory> coreStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        coreRepository = Mockito.mock(ActorCoreMemoryRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        goalRepository = Mockito.mock(ActorCharacterGoalRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        profileRepository = Mockito.mock(ActorAgentProfileRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        // O6 G1a：角色卡仓库已删（档案并入 actor_profile.persona_json）——构造器为 7 参（纯数据仓库）；
        // personaService / npcTypeResolver 为 @Autowired(required=false) 字段注入，单测手动回填
        service = new CoreMemoryService(coreRepository, characterRepository,
                goalRepository, relationRepository, npcRepository, profileRepository, projectRepository);
        personaService = Mockito.mock(PersonaService.class);
        ReflectionTestUtils.setField(service, "personaService", personaService);
        // G1a：npc:{id} 不再编码类型，普通/特殊分流必须查 actor_profile.type → 用真实解析器 + 仓库夹具
        npcTypeResolver = new NpcTypeResolver(characterRepository);
        ReflectionTestUtils.setField(service, "npcTypeResolver", npcTypeResolver);
        // @Value 字段回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxBlocksPerCharacter", 8);
        ReflectionTestUtils.setField(service, "maxCharsPerBlock", 400);
        ReflectionTestUtils.setField(service, "injectTopK", 8);
        ReflectionTestUtils.setField(service, "injectTokenBudget", 800);
        ReflectionTestUtils.setField(service, "selfEditEnabled", true);
        ReflectionTestUtils.setField(service, "baselineGoalTopN", 3);
        ReflectionTestUtils.setField(service, "baselineRelationTopN", 5);
        ReflectionTestUtils.setField(service, "baselineCrowdEnabled", true);

        // 核心块仓库：内存存储模拟
        when(coreRepository.save(any(ActorCoreMemory.class))).thenAnswer(inv -> {
            ActorCoreMemory b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId((long) (coreStore.size() + 1));
            }
            coreStore.removeIf(x -> x.getId().equals(b.getId()));
            coreStore.add(b);
            return b;
        });
        when(coreRepository.findById(any(Long.class))).thenAnswer(inv -> coreStore.stream()
                .filter(x -> inv.getArgument(0).equals(x.getId())).findFirst());
        when(coreRepository.findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
                any(Long.class), anyString(), eq(0))).thenAnswer(inv ->
                coreStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && inv.getArgument(1).equals(x.getCharacterRef())
                                && Integer.valueOf(0).equals(x.getDeleted()))
                        .sorted((a, b) -> {
                            int byImp = (b.getImportance() == null ? 1 : b.getImportance())
                                    - (a.getImportance() == null ? 1 : a.getImportance());
                            if (byImp != 0) {
                                return byImp;
                            }
                            return b.getCreatedAt() == null || a.getCreatedAt() == null ? 0
                                    : b.getCreatedAt().compareTo(a.getCreatedAt());
                        }).toList());
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                any(Long.class), anyString(), anyString(), eq(0))).thenAnswer(inv ->
                coreStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && inv.getArgument(1).equals(x.getCharacterRef())
                                && inv.getArgument(2).equals(x.getBlockKey())
                                && Integer.valueOf(0).equals(x.getDeleted()))
                        .toList());
        when(coreRepository.countByProjectIdAndCharacterRefAndDeleted(any(Long.class), anyString(), eq(0)))
                .thenAnswer(inv -> coreStore.stream()
                        .filter(x -> inv.getArgument(0).equals(x.getProjectId())
                                && inv.getArgument(1).equals(x.getCharacterRef())
                                && Integer.valueOf(0).equals(x.getDeleted()))
                        .count());
    }

    // ==================== 测试数据 ====================

    /** 特殊角色（projectId=100） */
    private ActorCharacter specialChar() {
        ActorCharacter c = new ActorCharacter();
        c.setId(3L);
        c.setProjectId(100L);
        c.setName("阿明");
        c.setType("special");
        c.setDeleted(0);
        return c;
    }

    /**
     * 11 维 v2 档案 JSON（{@code actor_profile.persona_json} 的真实形态，G1a 起由
     * {@code PersonaService.latestPersonaJson} 提供；旧 {@code actor_profile_card.persona_json} 已随表删除）。
     */
    private String personaJson() {
        return """
                {"schema_version":2,
                 "generated_by":"ai_bootstrap",
                 "dims":{"identity":{"aliases":["明哥"],"livelihood":"走镖"},
                         "personality":{"traits":["耿直","重情义"]},
                         "values":{"core":["信义为先","不欺弱者"]},
                         "experience":{"history":"出身城东猎户村，早年丧父，靠走镖起家。",
                                       "goals":["还清旧债","开一间自己的商铺"]}}}
                """;
    }

    private ActorAgentProfile profile() {
        ActorAgentProfile p = new ActorAgentProfile();
        p.setAgentType("special");
        p.setAgentRefId(3L);
        p.setBrainLevel("full");
        p.setModelTier("auto");
        return p;
    }

    private ActorCharacterGoal goal(long id, String title, int priority, String status) {
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(id);
        g.setProjectId(100L);
        g.setAgentRef("npc:3");
        g.setTitle(title);
        g.setStatus(status);
        g.setPriority(priority);
        g.setDeleted(0);
        return g;
    }

    private ActorCharacterRelation relation(long id, String otherName, int affinity, String state) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setId(id);
        r.setProjectId(100L);
        r.setFromCharacterId(3L);
        r.setToCharacterId(9L);
        r.setFromRef("npc:3");
        r.setToRef("npc:9");
        r.setFromName("阿明");
        r.setToName(otherName);
        r.setRelationType("生死之交");
        r.setAffinity(affinity);
        r.setRelationState(state);
        return r;
    }

    private ActorCharacter npc() {
        ActorCharacter n = new ActorCharacter();
        n.setId(7L);
        n.setProjectId(100L);
        // G1a：单主表下普通型必须显式 type=common（实体默认值是 special）
        n.setType("common");
        n.setName("铁匠老周");
        n.setRace("人类");
        n.setOccupation("铁匠");
        n.setAffiliation("城东市场");
        n.setLocation("城东市场");
        n.setDetail("性格敦厚，手艺远近闻名。");
        return n;
    }

    private ActorCoreMemory block(Long id, String ref, String key, String content, Integer importance,
                                  Integer editableByAgent, Integer version) {
        ActorCoreMemory b = new ActorCoreMemory();
        b.setId(id);
        b.setProjectId(100L);
        b.setCharacterRef(ref);
        b.setBlockKey(key);
        b.setContent(content);
        b.setImportance(importance);
        b.setEditableByAgent(editableByAgent);
        b.setVersion(version);
        b.setDeleted(0);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        return b;
    }

    // ==================== 静态基线渲染 ====================

    /**
     * 特殊 NPC 四源基线：brainProfile + active 目标 + 关键关系三源可见。
     * <p><b>G1a 口径变化（已知并已上报）</b>：档案权威源由 {@code actor_profile_card.persona_json}（v1 角色卡平铺键
     * identity.name / personality.traits / background.history）改为 {@code actor_profile.persona_json}
     * （11 维 v2：{@code dims.*}）。{@code CoreMemoryService.renderSpecialBaseline} 仍按 v1 平铺键取值，
     * 故 v2 档案下档案源不再产出文本行——本测试按新口径断言这三源，并显式锁定「v2 档案不产出旧风格人格行」。
     * 档案源恢复可渲染的修法应由主代码改用 {@code PersonaService.renderShardsForScene/renderFull}，
     * 属本轮"仅改测试"范围之外，已在交付报告中列为待决策项。</p>
     */
    @Test
    void renderSpecialBaselineWithModuleSources() {
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.of(personaJson()));
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.of(profile()));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq("active"), eq(0)))
                .thenReturn(new ArrayList<>(List.of(goal(1L, "还清旧债", 4, "active"), goal(2L, "开一间自己的商铺", 2, "active"))));
        when(relationRepository.findByProjectId(100L))
                .thenReturn(List.of(relation(1L, "沈铁柱", 80, "friend"), relation(2L, "柳如烟", 60, "intimate")));

        String text = service.renderBaselineText(100L, "npc:3");

        assertTrue(text.contains("脑力：档位 full / 模型 auto"), "脑力行应含 brainProfile");
        assertTrue(text.contains("长期目标：还清旧债"), "目标行应含 active 目标");
        assertTrue(text.contains("关键关系：柳如烟"), "关键关系应含 intimate 优先");
        // G1a：v2 档案（dims.*）不再命中 v1 平铺键 → 档案源本次不产出身份/性格行（详见上方 Javadoc）
        assertFalse(text.contains("身份：阿明"), "v2 档案不再产出 v1 平铺键身份行");
        assertFalse(text.contains("性格：耿直、重情义"), "v2 档案不再产出 v1 平铺键性格行");
    }

    /**
     * 特殊 NPC 无档案/无目标/无关系：基线为空串（不报错）。
     */
    @Test
    void renderSpecialBaselineEmptyWhenNoSources() {
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.empty());
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.empty());
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq("active"), eq(0))).thenReturn(List.of());
        when(relationRepository.findByProjectId(100L)).thenReturn(List.of());

        assertEquals("", service.renderBaselineText(100L, "npc:3"));
    }

    /**
     * 普通 NPC 轻量基线：档案字段（姓名/种族/职业/归属/位置/详情）。
     */
    @Test
    void renderCrowdLightBaseline() {
        when(characterRepository.findById(7L)).thenReturn(Optional.of(npc()));
        when(npcRepository.findById(7L)).thenReturn(Optional.of(npc()));

        String text = service.renderBaselineText(100L, "npc:7");

        assertTrue(text.contains("身份：铁匠老周，人类，铁匠，归属城东市场，常在城东市场"));
        assertTrue(text.contains("档案：性格敦厚"));
    }

    /**
     * 开关关闭：基线返回空串。
     */
    @Test
    void renderBaselineDisabledReturnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals("", service.renderBaselineText(100L, "npc:3"));
    }

    // ==================== 【核心记忆】段渲染 ====================

    /**
     * 核心段 = 静态基线 + 核心块（重要度 desc），格式含【核心记忆】标题。
     */
    @Test
    void renderCoreSegmentBaselinePlusBlocks() {
        // 基线非空由模块源提供（G1a：v2 档案下 persona 源不再产出文本行，见 renderSpecialBaselineWithModuleSources）
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.of(profile()));
        coreStore.add(block(1L, "npc:3", "beliefs", "守护家园高于一切", 5, 1, 1));
        coreStore.add(block(2L, "npc:3", "custom:座右铭", "行胜于言", 3, 1, 1));

        String seg = service.renderCoreSegment(100L, "npc:3");

        assertTrue(seg.startsWith("【核心记忆】"), "应以【核心记忆】标题开头");
        assertTrue(seg.contains("身份基线"), "应含静态基线段");
        assertTrue(seg.contains("核心块"), "应含核心块段");
        assertTrue(seg.contains("[核心信念] 守护家园高于一切"), "应含重要度 5 的块（在前）");
        assertTrue(seg.contains("[custom:座右铭] 行胜于言"), "应含自定义块（块名空时显示名=块键）");
    }

    /**
     * 2026-10-10 身份重复注入根治：AI 注入口径的【核心记忆】基线只保留不重复的恒定事实
     * （脑力档位 + active 目标），<b>不</b>含 persona 档案分片与关键关系——二者由段 1C
     * 【我的身份与处境】/对话 system prompt 渲染（用户报障：请求输入中身份内容重复两次）。
     */
    @Test
    void renderCoreSegmentPromptBaselineSkipsPersonaAndRelations() {
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.of(personaJson()));
        when(personaService.renderShardsForScene(3L, "action_gen"))
                .thenReturn("【身份锚定】\n姓名：沈守拙｜别名：沈堂主\n【人格与价值观】\nOCEAN：开放 0.40、尽责 0.90");
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.of(profile()));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq("active"), eq(0)))
                .thenReturn(new ArrayList<>(List.of(goal(1L, "还清旧债", 4, "active"))));
        when(relationRepository.findByProjectId(100L))
                .thenReturn(List.of(relation(1L, "沈铁柱", 80, "friend"), relation(2L, "柳如烟", 60, "intimate")));

        String seg = service.renderCoreSegment(100L, "npc:3");

        assertTrue(seg.contains("身份基线"), "基线仍含段标题");
        assertTrue(seg.contains("脑力：档位 full / 模型 auto"), "基线保留 brainProfile 恒定事实");
        assertTrue(seg.contains("长期目标：还清旧债"), "基线保留 active 目标恒定事实");
        assertFalse(seg.contains("【身份锚定】"), "AI 注入口径基线不得重复 persona 分片（段 1C 已渲染）");
        assertFalse(seg.contains("【人格与价值观】"), "AI 注入口径基线不得重复 persona 分片");
        assertFalse(seg.contains("关键关系"), "AI 注入口径基线不得重复关键关系（段 1C【关键关系】已渲染）");
    }

    /**
     * 2026-10-10 身份重复注入根治：前端「核心记忆」详情展示口径（renderBaselineText）保持全量——
     * persona 档案分片与关键关系仍在（展示非 Prompt，不受注入去重约束）。
     */
    @Test
    void renderBaselineTextFullStillIncludesPersonaAndRelations() {
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.of(personaJson()));
        when(personaService.renderShardsForScene(3L, "action_gen"))
                .thenReturn("【身份锚定】\n姓名：沈守拙｜别名：沈堂主\n【人格与价值观】\nOCEAN：开放 0.40");
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.of(profile()));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("npc:3"), eq("active"), eq(0)))
                .thenReturn(new ArrayList<>(List.of(goal(1L, "还清旧债", 4, "active"))));
        when(relationRepository.findByProjectId(100L))
                .thenReturn(List.of(relation(1L, "沈铁柱", 80, "friend"), relation(2L, "柳如烟", 60, "intimate")));

        String text = service.renderBaselineText(100L, "npc:3");

        assertTrue(text.contains("【身份锚定】"), "前端展示口径保留 persona 分片");
        assertTrue(text.contains("【人格与价值观】"), "前端展示口径保留 persona 分片");
        assertTrue(text.contains("关键关系：柳如烟"), "前端展示口径保留关键关系");
        assertTrue(text.contains("脑力：档位 full / 模型 auto"), "前端展示口径保留 brainProfile");
    }

    /**
     * 无基线无核心块：核心段返回空串。
     */
    @Test
    void renderCoreSegmentEmpty() {
        when(personaService.latestPersonaJson(3L)).thenReturn(Optional.empty());

        assertEquals("", service.renderCoreSegment(100L, "npc:3"));
    }

    // ==================== 核心块 CRUD ====================

    /**
     * 新建核心块：custom:* 键合法、内容/重要度/可编辑标识落库、默认版本 1。
     */
    @Test
    void createBlockCustomKey() {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(specialChar()));

        CoreBlockVO vo = service.createBlock(100L, "npc:3", "custom:座右铭", "座右铭", "行胜于言", 4, 1);

        assertNotNull(vo);
        assertEquals("custom:座右铭", vo.blockKey());
        assertEquals("座右铭", vo.blockName());
        assertEquals("行胜于言", vo.content());
        assertEquals(4, vo.importance());
        assertEquals(1, vo.editableByAgent());
        assertEquals(1, vo.version());
        assertEquals(1, coreStore.size());
    }

    /**
     * 非法块键 → 400。
     */
    @Test
    void createBlockInvalidKeyRejected() {
        assertThrows(BizException.class,
                () -> service.createBlock(100L, "npc:3", "hack;", "块", "内容", 3, 1));
    }

    /**
     * 内容空白 → 400；超长内容截断到 max-chars-per-block。
     */
    @Test
    void createBlockBlankRejectedAndLongTruncated() {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(specialChar()));
        assertThrows(BizException.class,
                () -> service.createBlock(100L, "npc:3", "beliefs", "信念", "   ", 3, 1));

        ReflectionTestUtils.setField(service, "maxCharsPerBlock", 10);
        CoreBlockVO vo = service.createBlock(100L, "npc:3", "beliefs", "信念", "一二三四五六七八九十一二三四五", 3, 1);
        assertEquals(10, vo.content().length(), "超长内容应截断到块上限");
    }

    /**
     * 同键已存在 → 升级（版本链：新块 version+1、supersedes_id=旧 id、旧块软删）。
     */
    @Test
    void createDuplicateKeyUpgradesVersionChain() {
        coreStore.add(block(1L, "npc:3", "beliefs", "旧信念", 4, 1, 1));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(specialChar()));

        CoreBlockVO vo = service.createBlock(100L, "npc:3", "beliefs", "信念", "新信念：守护家园", 5, 1);

        assertEquals(2, vo.version(), "新块版本应为旧+1");
        assertEquals(1L, vo.supersedesId(), "新块应指向被取代旧块");
        assertEquals(2, coreStore.size(), "旧块软删 + 新块并存（版本链保留审计）");
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
    }

    /**
     * 块数达上限 → 400。
     */
    @Test
    void createBlockOverLimitRejected() {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(specialChar()));
        ReflectionTestUtils.setField(service, "maxBlocksPerCharacter", 2);
        coreStore.add(block(1L, "npc:3", "identity", "身份", 5, 1, 1));
        coreStore.add(block(2L, "npc:3", "beliefs", "信念", 5, 1, 1));

        assertThrows(BizException.class,
                () -> service.createBlock(100L, "npc:3", "custom:新块", "新块", "内容", 3, 1));
    }

    /**
     * 更新核心块：版本链（version+1、supersedes_id、旧块软删）。
     */
    @Test
    void updateBlockVersionChain() {
        ActorCoreMemory old = block(1L, "npc:3", "values", "旧价值观", 3, 1, 1);
        coreStore.add(old);

        CoreBlockVO vo = service.updateBlock(100L, 1L, "价值观", "新价值观：信义为先", 5, 1);

        assertEquals(2, vo.version());
        assertEquals(1L, vo.supersedesId());
        assertEquals("新价值观：信义为先", vo.content());
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
    }

    /**
     * 删除核心块：软删（deleted=1）。
     */
    @Test
    void deleteBlockSoftDelete() {
        coreStore.add(block(1L, "npc:3", "values", "价值观", 3, 1, 1));

        service.deleteBlock(100L, 1L);

        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
    }

    /**
     * 删除不存在的块 → 404。
     */
    @Test
    void deleteMissingBlockThrows() {
        assertThrows(BizException.class, () -> service.deleteBlock(100L, 999L));
    }

    /**
     * 详情 VO：基线 + 核心块列表（重要度 desc）。
     */
    @Test
    void detailReturnsBaselineAndBlocks() {
        when(profileRepository.findByAgentTypeAndAgentRefId("special", 3L)).thenReturn(Optional.of(profile()));
        coreStore.add(block(1L, "npc:3", "beliefs", "守护家园", 5, 1, 1));

        CoreMemoryVO vo = service.detail(100L, "npc:3");

        assertEquals("npc:3", vo.characterRef());
        assertTrue(vo.baselineText().contains("脑力：档位 full / 模型 auto"), "基线含模块源文本");
        assertEquals(1, vo.blocks().size());
        assertEquals("beliefs", vo.blocks().get(0).blockKey());
    }

    // ==================== agent 自编辑应用 ====================

    /**
     * 自编辑 update：版本链（旧块软删 + 新块 version+1）。
     */
    @Test
    void applySelfEditUpdateVersionChain() {
        coreStore.add(block(1L, "npc:3", "beliefs", "旧信念", 4, 1, 1));

        int applied = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("update", "beliefs", null, "新信念：以和为贵", 5, "经历改变")));

        assertEquals(1, applied);
        assertEquals(2, coreStore.size());
        assertTrue(coreStore.stream().anyMatch(x -> x.getVersion() != null && x.getVersion() == 2
                && "新信念：以和为贵".equals(x.getContent())));
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
    }

    /**
     * 自编辑 update 只读块（editable_by_agent=0）：跳过不应用。
     */
    @Test
    void applySelfEditReadOnlyBlockSkipped() {
        coreStore.add(block(1L, "npc:3", "identity", "身份基线", 5, 0, 1));

        int applied = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("update", "identity", null, "篡改身份", 5, "")));

        assertEquals(0, applied);
        assertEquals(1, coreStore.size(), "只读块不被 agent 修改");
        assertEquals("身份基线", coreStore.get(0).getContent());
    }

    /**
     * 自编辑 create：新建块；块数达上限时跳过。
     */
    @Test
    void applySelfEditCreateAndLimit() {
        int applied = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("create", "custom:座右铭", "座右铭", "行胜于言", 4, "")));
        assertEquals(1, applied);
        assertEquals(1, coreStore.size());

        // 达上限跳过
        ReflectionTestUtils.setField(service, "maxBlocksPerCharacter", 1);
        int applied2 = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("create", "custom:第二块", "第二块", "内容", 3, "")));
        assertEquals(0, applied2);
        assertEquals(1, coreStore.size());
    }

    /**
     * 自编辑 delete：软删；只读块拒绝删除。
     */
    @Test
    void applySelfEditDelete() {
        coreStore.add(block(1L, "npc:3", "values", "旧价值观", 3, 1, 1));
        coreStore.add(block(2L, "npc:3", "identity", "身份", 5, 0, 1));

        int applied = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("delete", "values", null, "", null, "被取代"),
                new CoreMemoryUpdateParse.CoreMemoryUpdate("delete", "identity", null, "", null, "只读")));

        assertEquals(1, applied, "只读块拒绝删除");
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(2L) && Integer.valueOf(0).equals(x.getDeleted())));
    }

    /**
     * 自编辑：普通型（common）拒绝（不自编辑，Q2 决策）；非法 action/块键跳过。
     */
    @Test
    void applySelfEditCrowdRejectedAndInvalidSkipped() {
        // G1a：类型不再由引用前缀编码，须由 actor_profile.type=common 判定
        when(characterRepository.findById(7L)).thenReturn(Optional.of(npc()));
        assertEquals(0, service.applySelfEdit(100L, "npc:7", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("create", "beliefs", null, "内容", 3, ""))));
        assertEquals(0, service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("explode", "beliefs", null, "内容", 3, ""))));
        assertEquals(0, service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("create", "非法键", null, "内容", 3, ""))));
        assertFalse(coreStore.stream().anyMatch(x -> x.getContent() != null));
    }

    /**
     * 自编辑 update 不存在块键 → 降级 create（新建）。
     */
    @Test
    void applySelfEditUpdateMissingKeyCreates() {
        int applied = service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("update", "beliefs", "核心信念", "新信念", 4, "")));

        assertEquals(1, applied);
        assertEquals(1, coreStore.size());
        assertEquals("新信念", coreStore.get(0).getContent());
        assertEquals(1, coreStore.get(0).getVersion());
        assertNull(coreStore.get(0).getSupersedesId());
    }

    // ==================== M4 归档挂钩（旧块写归档：升级 superseded / 删除 manual / 手动归档块） ====================

    /**
     * 更新核心块（版本链）→ 旧块写归档 reason=superseded。
     */
    @Test
    void updateBlock_writesOldToArchiveSuperseded() {
        MemoryArchiveService archive = Mockito.mock(MemoryArchiveService.class);
        Mockito.when(archive.archiveCoreBlock(Mockito.any(), Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "archiveService", archive);

        coreStore.add(block(1L, "npc:3", "values", "旧价值观", 3, 1, 1));
        CoreBlockVO vo = service.updateBlock(100L, 1L, "价值观", "新价值观", 5, 1);

        assertEquals(2, vo.version());
        Mockito.verify(archive).archiveCoreBlock(Mockito.any(ActorCoreMemory.class),
                Mockito.eq(MemoryArchiveService.REASON_SUPERSEDED));
    }

    /**
     * 自编辑 update → 旧块写归档 reason=superseded。
     */
    @Test
    void applySelfEditUpdate_writesOldToArchiveSuperseded() {
        MemoryArchiveService archive = Mockito.mock(MemoryArchiveService.class);
        Mockito.when(archive.archiveCoreBlock(Mockito.any(), Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "archiveService", archive);
        coreStore.add(block(1L, "npc:3", "beliefs", "旧信念", 4, 1, 1));

        service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("update", "beliefs", null, "新信念", 5, "")));

        Mockito.verify(archive).archiveCoreBlock(Mockito.any(ActorCoreMemory.class),
                Mockito.eq(MemoryArchiveService.REASON_SUPERSEDED));
    }

    /**
     * 自编辑 delete → 旧块写归档 reason=manual（冷存保真）。
     */
    @Test
    void applySelfEditDelete_writesOldToArchiveManual() {
        MemoryArchiveService archive = Mockito.mock(MemoryArchiveService.class);
        Mockito.when(archive.archiveCoreBlock(Mockito.any(), Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "archiveService", archive);
        coreStore.add(block(1L, "npc:3", "values", "旧价值观", 3, 1, 1));

        service.applySelfEdit(100L, "npc:3", List.of(
                new CoreMemoryUpdateParse.CoreMemoryUpdate("delete", "values", null, "", null, "被取代")));

        Mockito.verify(archive).archiveCoreBlock(Mockito.any(ActorCoreMemory.class),
                Mockito.eq(MemoryArchiveService.REASON_MANUAL));
    }

    /**
     * 手动归档块（archiveBlockManual）：软删 + 写归档 reason=manual。
     */
    @Test
    void archiveBlockManual_writesArchiveAndSoftDeletes() {
        MemoryArchiveService archive = Mockito.mock(MemoryArchiveService.class);
        Mockito.when(archive.archiveCoreBlock(Mockito.any(), Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "archiveService", archive);
        coreStore.add(block(1L, "npc:3", "values", "旧价值观", 3, 1, 1));

        boolean ok = service.archiveBlockManual(100L, 1L);

        assertTrue(ok);
        assertTrue(coreStore.stream().anyMatch(x -> x.getId().equals(1L) && Integer.valueOf(1).equals(x.getDeleted())));
        Mockito.verify(archive).archiveCoreBlock(Mockito.any(ActorCoreMemory.class),
                Mockito.eq(MemoryArchiveService.REASON_MANUAL));
    }

    /**
     * 归档服务缺省时（archiveService=null）：版本链仍正常（旧块不写归档，不报错）。
     */
    @Test
    void updateBlock_withoutArchiveService_stillCreatesVersion() {
        coreStore.add(block(1L, "npc:3", "values", "旧价值观", 3, 1, 1));

        CoreBlockVO vo = service.updateBlock(100L, 1L, "价值观", "新价值观", 5, 1);

        assertEquals(2, vo.version());
        assertEquals(1L, vo.supersedesId());
        assertEquals(2, coreStore.size());
    }

    // ==================== 批6 收口：自编辑 AI 请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** 对话挂钩自编辑：core_memory_update 请求前置 memoryChainContext；原始提示保留在后。 */
    @Test
    void selfEditRequestPrependsMemoryChainContext() throws Exception {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(specialChar()));
        AiSpringChatGateway gateway = Mockito.mock(AiSpringChatGateway.class);
        PromptTemplateService pts = Mockito.mock(PromptTemplateService.class);
        UsageLogService usage = Mockito.mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        when(pts.render(any(), eq(100L), eq(PromptTemplateService.CODE_CORE_MEMORY_UPDATE), any()))
                .thenReturn("prompt");
        when(gateway.chatCompletion(any(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("[]", "qwen", 10, 5, 15, 1L));
        AiContextInjector injector = Mockito.mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】青禾村【我的身份与处境】走镖人【当前世界动态】春日【当前状态】赶路");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        service.maybeSelfEditFromDialog(1L, 100L, "npc:3", "今天和阿福在集市聊了很久");

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(gateway).chatCompletion(any(), eq(100L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }
}
