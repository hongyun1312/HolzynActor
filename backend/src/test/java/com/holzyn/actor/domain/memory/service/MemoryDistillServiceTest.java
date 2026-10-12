package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryLineage;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryLineageRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨层巩固蒸馏服务单元测试（O1 记忆内核扩展 O1b，domain/memory/service 同包）。
 * <p>职责：验证——L1→L2 蒸馏触发（模糊度/检索/重要度三条件）、L2 语义落库 + evidence_ids 锚点、
 * L2→L3 巩固（7 日检索 ≥5 / 活跃目标强相关双触发 + block_key=consolidated:{id} 零新表 + 去重）、
 * 谱系行落库与反向链展开（lineage ≥4 用例）、规则蒸馏降级（L2/L3 抽象差异）。</p>
 * <p>设计依据：《HolzynActor_O1记忆内核扩展_L1动态模糊与跨层级巩固_设计方案_V1.0》§4。</p>
 */
class MemoryDistillServiceTest {

    private ActorMemoryFuzzStateRepository fuzzRepository;
    private ActorMemoryWorkingRepository workingRepository;
    private ActorMemoryRepository memoryRepository;
    private ActorCoreMemoryRepository coreRepository;
    private ActorMemoryLineageRepository lineageRepository;
    private ActorActionPlanRepository planRepository;
    private WorldClockService worldClockService;
    private AiIntensityGate aiIntensityGate;
    private MemoryDistillService service;

    /** 内存保存桩 */
    private final List<ActorMemory> memoryStore = new ArrayList<>();
    private final List<ActorCoreMemory> coreStore = new ArrayList<>();
    private final List<ActorMemoryLineage> lineageStore = new ArrayList<>();
    private final List<ActorMemoryWorking> workingStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fuzzRepository = Mockito.mock(ActorMemoryFuzzStateRepository.class);
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        coreRepository = Mockito.mock(ActorCoreMemoryRepository.class);
        lineageRepository = Mockito.mock(ActorMemoryLineageRepository.class);
        planRepository = Mockito.mock(ActorActionPlanRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        service = new MemoryDistillService(fuzzRepository, workingRepository, memoryRepository,
                coreRepository, lineageRepository, planRepository, worldClockService,
                Mockito.mock(AiSpringChatGateway.class), Mockito.mock(PromptTemplateService.class),
                Mockito.mock(UsageLogService.class), new ObjectMapper());
        // @Value 字段回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fuzzThreshold", 0.6);
        ReflectionTestUtils.setField(service, "retrievalThreshold", 3);
        ReflectionTestUtils.setField(service, "importanceThreshold", 0.7);
        ReflectionTestUtils.setField(service, "minReductionRatio", 0.2);
        ReflectionTestUtils.setField(service, "l3Retrieval7d", 5);
        ReflectionTestUtils.setField(service, "l3PlanRelevanceThreshold", 0.5);
        ReflectionTestUtils.setField(service, "l3MaxChars", 400);
        ReflectionTestUtils.setField(service, "promptVersion", "v1");
        // programmatic 档：规则蒸馏路径（确定性）
        when(aiIntensityGate.allowsAi(anyLong())).thenReturn(false);
        ReflectionTestUtils.setField(service, "aiIntensityGate", aiIntensityGate);
        // 保存桩（分配 id）
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> {
            ActorMemory m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (memoryStore.size() + 1));
            }
            memoryStore.removeIf(x -> x.getId().equals(m.getId()));
            memoryStore.add(m);
            return m;
        });
        when(coreRepository.save(any(ActorCoreMemory.class))).thenAnswer(inv -> {
            ActorCoreMemory c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId((long) (coreStore.size() + 1));
            }
            coreStore.removeIf(x -> x.getId().equals(c.getId()));
            coreStore.add(c);
            return c;
        });
        when(lineageRepository.save(any(ActorMemoryLineage.class))).thenAnswer(inv -> {
            ActorMemoryLineage l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId((long) (lineageStore.size() + 1));
            }
            lineageStore.removeIf(x -> x.getId().equals(l.getId()));
            lineageStore.add(l);
            return l;
        });
        when(workingRepository.save(any(ActorMemoryWorking.class))).thenAnswer(inv -> {
            ActorMemoryWorking w = inv.getArgument(0);
            if (w.getId() == null) {
                w.setId((long) (workingStore.size() + 1));
            }
            workingStore.removeIf(x -> x.getId().equals(w.getId()));
            workingStore.add(w);
            return w;
        });
    }

    // ==================== 构造 ====================

    private ActorMemoryFuzzState state(long id, long workingId, double fuzz, double importance,
                                       int retrieval, String content, String status) {
        ActorMemoryFuzzState fs = new ActorMemoryFuzzState();
        fs.setId(id);
        fs.setProjectId(100L);
        fs.setMemoryWorkingId(workingId);
        fs.setCharacterRef("npc:3");
        fs.setOriginalContent(content);
        fs.setCurrentContent(content);
        fs.setFuzzLevel(fuzz);
        fs.setImportanceScore(importance);
        fs.setRetrievalCount(retrieval);
        fs.setFuzzCount(0);
        fs.setFuzzHistoryJson("[]");
        fs.setStatus(status);
        return fs;
    }

    private ActorMemory mem(long id, Long characterId, String ref, String type, String content,
                            Integer accessCount, LocalDateTime lastAccessAt) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterId(characterId);
        m.setCharacterRef(ref);
        m.setType(type);
        m.setContent(content);
        m.setImportance(3);
        m.setAccessCount(accessCount);
        m.setLastAccessAt(lastAccessAt);
        m.setDeleted(0);
        return m;
    }

    // ==================== 规则蒸馏（零 AI 降级，确定性） ====================

    /** 规则蒸馏 L2：截断前 60% + 源模糊语义保留。 */
    @Test
    void rulesDistillL2KeepsHeadAndFuzzMarker() {
        String out = MemoryDistillService.rulesDistill("昨天在集市遇到了阿福，他让我帮忙修屋顶（细节记不太清）", "L2");
        assertTrue(out.startsWith("昨天在集市遇到了阿福"));
        assertTrue(out.contains("记不清")); // 规则追加「（细节记不清）」语义标记
    }

    /** 规则蒸馏 L3：更抽象一层（「长期来看」前缀）。 */
    @Test
    void rulesDistillL3AbstractsWithPrefix() {
        String out = MemoryDistillService.rulesDistill("与阿福约定每月十五日在集市碰面", "L3");
        assertTrue(out.startsWith("长期来看，"));
        assertFalse(out.contains("记不太清"));
    }

    // ==================== L1→L2 蒸馏 ====================

    /** 单条蒸馏：写 L2 semantic（evidence_ids=L1 id）+ 谱系 + fuzz_state 状态流转。 */
    @Test
    void distillL1toL2WritesL2SemanticAndLineage() {
        ActorMemoryFuzzState fs = state(1L, 10L, 0.7, 0.5, 0, "昨天在集市与赵屠户议价，肉价三文一斤", "active");
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));

        boolean ok = service.distillL1toL2(100L, fs, 50000L);

        assertTrue(ok);
        ActorMemory m = memoryStore.get(0);
        assertEquals("semantic", m.getType());
        assertEquals("consolidation", m.getSource());
        assertEquals("10", m.getEvidenceIds());
        assertEquals(3, m.getCharacterId());
        assertEquals("npc:3", m.getCharacterRef());
        // 谱系 L1→L2（缩减率 token 比）
        assertEquals(1, lineageStore.size());
        ActorMemoryLineage ln = lineageStore.get(0);
        assertEquals("L1", ln.getSourceTier());
        assertEquals("L2", ln.getTargetTier());
        assertEquals(10L, ln.getSourceMemoryId());
        assertEquals(m.getId(), ln.getTargetMemoryId());
        assertEquals("L1->L2", ln.getConsolidateType());
        assertEquals(50000L, ln.getGameSecond());
        assertEquals("v1", ln.getPromptVersion());
        // 状态流转
        assertEquals("distilled", fs.getStatus());
        assertEquals(m.getId(), fs.getDistilledMemoryId());
        assertEquals("consolidated", workingStore.get(0).getStatus());
    }

    /**
     * P0-2 蒸馏幂等守卫（2026-09-28）：evidence_ids 已存在巩固 L2 行时重跑——
     * 只复用该行标记 fuzz_state distilled，不新建 L2、不重复写谱系（拆事务后
     * 「L2 已写、状态未标」的半截重跑不会产生重复行）。
     */
    @Test
    void distillL1toL2ReusesExistingL2OnReRun() {
        ActorMemoryFuzzState fs = state(1L, 10L, 0.7, 0.5, 0, "昨天在集市与赵屠户议价，肉价三文一斤", "active");
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));
        // 幂等命中：已存在 evidence_ids 精确含 10 的巩固 L2 行
        ActorMemory existing = mem(77L, 3L, "npc:3", "semantic", "与赵屠户议价过肉价", 0, null);
        existing.setSource("consolidation");
        existing.setEvidenceIds("10");
        when(memoryRepository.findConsolidatedL2ByEvidenceId(100L, "10")).thenReturn(List.of(existing));

        boolean ok = service.distillL1toL2(100L, fs, 50000L);

        assertTrue(ok);
        // 复用既有 L2：不新建行、不重复写谱系
        assertEquals(0, memoryStore.size(), "幂等命中不产生重复 L2 行");
        assertEquals(0, lineageStore.size(), "幂等命中不重复写谱系");
        // 状态流转仍完成（标记 distilled + 复用 id + working consolidated）
        assertEquals("distilled", fs.getStatus());
        assertEquals(77L, fs.getDistilledMemoryId());
        assertEquals("consolidated", workingStore.get(0).getStatus());
    }

    /**
     * P0-2 蒸馏幂等查询契约（2026-09-28）：evidence_ids 为逗号分隔 token 列表，仓库 @Query
     * 必须按 token 边界精确匹配（= :wid / 前缀 / 后缀 / 中段四种形态），不得退化为裸 LIKE
     * 子串（如 34 会误命中 134）。本测试固化查询文本契约（无 @SpringBootTest 环境下的
     * 契约快照），防止日后「简化」回 LIKE 造成幂等误命中。
     */
    @Test
    void evidenceIdQueryUsesTokenBoundaryMatching() throws Exception {
        java.lang.reflect.Method m = ActorMemoryRepository.class
                .getMethod("findConsolidatedL2ByEvidenceId", Long.class, String.class);
        org.springframework.data.jpa.repository.Query q =
                m.getAnnotation(org.springframework.data.jpa.repository.Query.class);
        assertNotNull(q, "幂等查询必须有 @Query 注解");
        String jpql = q.value();
        assertTrue(jpql.contains("m.evidenceIds = :wid"), "须含「= :wid」整 token 形态");
        assertTrue(jpql.contains("CONCAT('%,', :wid)"), "须含「后缀 ,wid」边界形态");
        assertTrue(jpql.contains("CONCAT(:wid, ',%')"), "须含「前缀 wid,」边界形态");
        assertTrue(jpql.contains("CONCAT('%,', :wid, ',%')"), "须含「中段 ,wid,」边界形态");
        assertFalse(jpql.contains("LIKE '%'"), "不得退化为裸子串匹配（34 误命中 134）");
    }

    private ActorMemoryWorking workingRow(long id) {
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setId(id);
        w.setProjectId(100L);
        w.setCharacterRef("npc:3");
        w.setItemType("short_term");
        w.setContent("昨天在集市与赵屠户议价，肉价三文一斤");
        w.setStatus("active");
        return w;
    }

    /** 单条蒸馏：非 active 状态跳过。 */
    @Test
    void distillL1toL2SkipsNonActive() {
        ActorMemoryFuzzState fs = state(1L, 10L, 0.9, 0.5, 0, "内容", "distilled");
        assertFalse(service.distillL1toL2(100L, fs, 50000L));
        verify(memoryRepository, never()).save(any());
    }

    /** 每日扫描：仅满足三条件之一（fuzz≥0.6 / 检索≥3 / 重要度≥0.7）的蒸馏。 */
    @Test
    void scanL1toL2DistillsOnlyQualifying() {
        ActorMemoryFuzzState byFuzz = state(1L, 1L, 0.8, 0.2, 0, "模糊到记不清但记得大概", "active");
        ActorMemoryFuzzState byRetrieval = state(2L, 2L, 0.1, 0.2, 5, "反复被检索的重要线索", "active");
        ActorMemoryFuzzState byImportance = state(3L, 3L, 0.1, 0.9, 0, "高重要快车道", "active");
        ActorMemoryFuzzState skip = state(4L, 4L, 0.2, 0.3, 1, "普通记忆不满足条件", "active");
        when(fuzzRepository.findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(100L, "active"))
                .thenReturn(List.of(byFuzz, byRetrieval, byImportance, skip));

        int done = service.scanL1toL2(100L, 50000L);

        assertEquals(3, done);
        assertEquals("distilled", byFuzz.getStatus());
        assertEquals("distilled", byRetrieval.getStatus());
        assertEquals("distilled", byImportance.getStatus());
        assertEquals("active", skip.getStatus());
    }

    // ==================== 批6 收口：AI 蒸馏请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** AI 档位下 memory_distill 请求前置 memoryChainContext（角色引用+游戏时刻）；原始提示保留在后。 */
    @Test
    void aiDistillRequestPrependsMemoryChainContext() throws Exception {
        // AI 档位：override programmatic 默认
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        AiSpringChatGateway gateway = Mockito.mock(AiSpringChatGateway.class);
        PromptTemplateService pts = Mockito.mock(PromptTemplateService.class);
        UsageLogService usage = Mockito.mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        when(pts.render(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_DISTILL), any()))
                .thenReturn("prompt");
        when(pts.systemMessage(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_DISTILL), any()))
                .thenReturn("sys");
        when(gateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"content\":\"与赵屠户议价过肉价\"}", "qwen", 10, 5, 15, 1L));
        AiContextInjector injector = Mockito.mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】青禾村【我的身份与处境】屠户【当前世界动态】春日【当前状态】集市开市");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        ActorMemoryFuzzState fs = state(1L, 10L, 0.7, 0.5, 0, "昨天在集市与赵屠户议价，肉价三文一斤", "active");
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));

        boolean ok = service.distillL1toL2(100L, fs, 50000L);

        assertTrue(ok);
        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(gateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }

    /** 蒸馏开关关闭：不蒸馏。 */
    @Test
    void scanL1toL2Disabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        when(fuzzRepository.findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(100L, "active"))
                .thenReturn(List.of(state(1L, 1L, 0.9, 0.2, 0, "内容", "active")));
        assertEquals(0, service.scanL1toL2(100L, 50000L));
        verify(memoryRepository, never()).save(any());
    }

    // ==================== L2→L3 巩固 ====================

    /** 巩固触发①：7 游戏日内检索 ≥5 次 → 写 L3 核心块（block_key=consolidated:{id} 零新表）。 */
    @Test
    void consolidateL2toL3ByFrequentRetrieval() {
        LocalDateTime recent = LocalDateTime.now();
        ActorMemory m = mem(1L, 3L, "npc:3", "semantic", "与阿福约定每月十五日在集市碰面",
                5, recent);
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(10L); // 今天游戏时=10 → 访问日=0
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                eq(100L), eq("npc:3"), eq("consolidated:1"), eq(0))).thenReturn(List.of());

        boolean ok = service.consolidateL2toL3(100L, m, 5 * 86400L); // 第 5 游戏日

        assertTrue(ok);
        assertEquals(1, coreStore.size());
        ActorCoreMemory core = coreStore.get(0);
        assertEquals("consolidated:1", core.getBlockKey());
        assertEquals("巩固记忆", core.getBlockName());
        assertEquals("npc:3", core.getCharacterRef());
        assertEquals(0, core.getEditableByAgent());
        assertTrue(core.getContent().startsWith("长期来看，"));
        // 谱系 L2→L3
        assertEquals(1, lineageStore.size());
        assertEquals("L2", lineageStore.get(0).getSourceTier());
        assertEquals("L3", lineageStore.get(0).getTargetTier());
        assertEquals(1L, lineageStore.get(0).getSourceMemoryId());
        assertEquals(core.getId(), lineageStore.get(0).getTargetMemoryId());
        assertEquals("L2->L3", lineageStore.get(0).getConsolidateType());
    }

    /** 巩固触发②：活跃目标强相关（进行中计划文本 Dice ≥ 0.5）。 */
    @Test
    void consolidateL2toL3ByPlanRelevance() {
        ActorMemory m = mem(1L, 3L, "npc:3", "semantic", "计划明日去集市卖鱼", 0, null);
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(9L);
        plan.setCharacterId(3L);
        plan.setActionJson("计划明日去集市卖鱼");
        plan.setStatus("planned");
        when(planRepository.findByCharacterIdAndStatusOrderByIdDesc(3L, "planned"))
                .thenReturn(List.of(plan));
        when(planRepository.findByCharacterIdAndStatusOrderByIdDesc(3L, "executing"))
                .thenReturn(List.of());
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                eq(100L), eq("npc:3"), eq("consolidated:1"), eq(0))).thenReturn(List.of());

        boolean ok = service.consolidateL2toL3(100L, m, 50000L);

        assertTrue(ok);
        assertEquals(1, coreStore.size());
        assertEquals("consolidated:1", coreStore.get(0).getBlockKey());
    }

    /** 巩固去重：block_key 已存在 → 跳过。 */
    @Test
    void consolidateL2toL3SkipsExistingBlock() {
        ActorMemory m = mem(1L, 3L, "npc:3", "semantic", "与阿福约定每月十五日在集市碰面", 5,
                LocalDateTime.now());
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(10L);
        ActorCoreMemory existing = new ActorCoreMemory();
        existing.setId(7L);
        existing.setBlockKey("consolidated:1");
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                eq(100L), eq("npc:3"), eq("consolidated:1"), eq(0))).thenReturn(List.of(existing));

        boolean ok = service.consolidateL2toL3(100L, m, 5 * 86400L);

        assertFalse(ok);
        verify(coreRepository, never()).save(any());
        assertEquals(0, lineageStore.size());
    }

    /** 巩固不满足触发（无检索频率/无计划相关）→ 跳过。 */
    @Test
    void consolidateL2toL3SkipsNoTrigger() {
        ActorMemory m = mem(1L, 3L, "npc:3", "semantic", "普通语义记忆", 1, LocalDateTime.now());
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(10L);
        when(planRepository.findByCharacterIdAndStatusOrderByIdDesc(3L, "planned")).thenReturn(List.of());
        when(planRepository.findByCharacterIdAndStatusOrderByIdDesc(3L, "executing")).thenReturn(List.of());

        boolean ok = service.consolidateL2toL3(100L, m, 5 * 86400L);

        assertFalse(ok);
        verify(coreRepository, never()).save(any());
    }

    /** 每日巩固扫描：跳过项目级（characterId=null）与普通 NPC（无 characterRef 锚点）记忆。 */
    @Test
    void scanL2toL3SkipsProjectLevelAndCrowd() {
        ActorMemory project = mem(1L, null, null, "semantic", "世界大事记", 9, LocalDateTime.now());
        ActorMemory crowd = mem(2L, null, "npc:5", "semantic", "普通居民记忆", 9, LocalDateTime.now());
        ActorMemory ok = mem(3L, 3L, "npc:3", "semantic", "与阿福约定每月十五日在集市碰面", 5, LocalDateTime.now());
        when(memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(100L, 0))
                .thenReturn(List.of(project, crowd, ok));
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(10L);
        when(coreRepository.findByProjectIdAndCharacterRefAndBlockKeyAndDeleted(
                eq(100L), eq("npc:3"), eq("consolidated:3"), eq(0))).thenReturn(List.of());

        int done = service.scanL2toL3(100L, 5 * 86400L);

        assertEquals(1, done);
        assertEquals(1, coreStore.size());
        assertEquals("consolidated:3", coreStore.get(0).getBlockKey());
    }

    // ==================== 谱系（lineage ≥4） ====================

    /** 谱系反向链：L3 → L2 → L1 逐层展开（自目标向根）。 */
    @Test
    void lineageOfExpandsChainToRoot() {
        ActorMemoryLineage l2 = new ActorMemoryLineage();
        l2.setId(1L);
        l2.setProjectId(100L);
        l2.setSourceMemoryId(10L);   // L1 working
        l2.setSourceTier("L1");
        l2.setTargetMemoryId(20L);   // L2 memory
        l2.setTargetTier("L2");
        l2.setConsolidateType("L1->L2");
        l2.setGameSecond(1000L);
        l2.setReductionRatio(0.6);
        ActorMemoryLineage l3 = new ActorMemoryLineage();
        l3.setId(2L);
        l3.setProjectId(100L);
        l3.setSourceMemoryId(20L);   // L2 memory
        l3.setSourceTier("L2");
        l3.setTargetMemoryId(30L);   // L3 core
        l3.setTargetTier("L3");
        l3.setConsolidateType("L2->L3");
        l3.setGameSecond(2000L);
        l3.setReductionRatio(0.5);
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 30L))
                .thenReturn(List.of(l3));
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 20L))
                .thenReturn(List.of(l2));
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 10L))
                .thenReturn(List.of());
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));
        ActorMemory srcMem = mem(20L, 3L, "npc:3", "semantic", "与赵屠户议价过", 0, null);
        when(memoryRepository.findById(20L)).thenReturn(Optional.of(srcMem));

        List<MemoryDistillService.LineageVO> chain = service.lineageOf(100L, 30L);

        assertEquals(2, chain.size());
        MemoryDistillService.LineageVO first = chain.get(0);
        assertEquals("L2", first.sourceTier());
        assertEquals(20L, first.sourceMemoryId());
        assertEquals("L3", first.targetTier());
        assertEquals(30L, first.targetMemoryId());
        assertEquals("L2->L3", first.consolidateType());
        MemoryDistillService.LineageVO second = chain.get(1);
        assertEquals("L1", second.sourceTier());
        assertEquals(10L, second.sourceMemoryId());
        assertEquals("L2", second.targetTier());
        assertEquals("L1->L2", second.consolidateType());
        // 源内容快照解析（L1 working 原文）
        assertNotNull(second.sourceContent());
        assertTrue(second.sourceContent().contains("议价"));
    }

    /** 谱系：根记忆无谱系行 → 空链。 */
    @Test
    void lineageOfEmptyWhenNoHops() {
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 99L))
                .thenReturn(List.of());
        assertTrue(service.lineageOf(100L, 99L).isEmpty());
    }

    /** 谱系：L1 直入 L2 的单一跳。 */
    @Test
    void lineageOfSingleHopL1toL2() {
        ActorMemoryLineage hop = new ActorMemoryLineage();
        hop.setId(1L);
        hop.setProjectId(100L);
        hop.setSourceMemoryId(10L);
        hop.setSourceTier("L1");
        hop.setTargetMemoryId(20L);
        hop.setTargetTier("L2");
        hop.setConsolidateType("L1->L2");
        hop.setGameSecond(1000L);
        hop.setReductionRatio(0.6);
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 20L))
                .thenReturn(List.of(hop));
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 10L))
                .thenReturn(List.of());
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));

        List<MemoryDistillService.LineageVO> chain = service.lineageOf(100L, 20L);

        assertEquals(1, chain.size());
        assertEquals("L1", chain.get(0).sourceTier());
    }

    /** 谱系：源内容缺失（working 行已删）→ 内容快照为 null 不抛异常。 */
    @Test
    void lineageOfToleratesMissingSource() {
        ActorMemoryLineage hop = new ActorMemoryLineage();
        hop.setId(1L);
        hop.setProjectId(100L);
        hop.setSourceMemoryId(10L);
        hop.setSourceTier("L1");
        hop.setTargetMemoryId(20L);
        hop.setTargetTier("L2");
        hop.setConsolidateType("L1->L2");
        hop.setGameSecond(1000L);
        hop.setReductionRatio(0.6);
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 20L))
                .thenReturn(List.of(hop));
        when(lineageRepository.findByProjectIdAndTargetMemoryIdOrderByIdAsc(100L, 10L))
                .thenReturn(List.of());
        when(workingRepository.findById(10L)).thenReturn(Optional.empty());

        List<MemoryDistillService.LineageVO> chain = service.lineageOf(100L, 20L);

        assertEquals(1, chain.size());
        assertNull(chain.get(0).sourceContent());
    }

    /** 谱系：蒸馏落库时 reduction_ratio = 目标/源 token 比（缩减记录）。 */
    @Test
    void distillLineageRecordsReductionRatio() {
        ActorMemoryFuzzState fs = state(1L, 10L, 0.7, 0.5, 0, "昨天在集市与赵屠户议价，肉价三文一斤，他说下月要涨价", "active");
        when(workingRepository.findById(10L)).thenReturn(Optional.of(workingRow(10L)));

        service.distillL1toL2(100L, fs, 50000L);

        assertEquals(1, lineageStore.size());
        ActorMemoryLineage ln = lineageStore.get(0);
        assertNotNull(ln.getSourceTokens());
        assertNotNull(ln.getTargetTokens());
        assertTrue(ln.getReductionRatio() > 0 && ln.getReductionRatio() <= 1.0);
        assertTrue(ln.getTargetTokens() <= ln.getSourceTokens());
    }
}
