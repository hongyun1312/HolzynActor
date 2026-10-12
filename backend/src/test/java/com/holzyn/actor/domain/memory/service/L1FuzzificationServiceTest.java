package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
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
 * L1 动态模糊引擎单元测试（O1 记忆内核扩展 O1a，domain/memory/service 同包）。
 * <p>职责：验证——写入路径（episodic 先进 L1 short_term + 1:1 fuzz_state + 去重）、
 * 艾宾浩斯渐进模糊（到点触发/规则降级确定性）、睡眠清洗（分档：蒸馏/琐事合并/模糊跳升 +
 * 冷却防抖）、超清醒兜底、检索标记、规则降级（细节剥离/限定词/琐事合并模板）。</p>
 * <p>设计依据：《HolzynActor_O1记忆内核扩展_L1动态模糊与跨层级巩固_设计方案_V1.0》§3/§5.3。</p>
 */
class L1FuzzificationServiceTest {

    private ActorMemoryWorkingRepository workingRepository;
    private ActorMemoryFuzzStateRepository fuzzRepository;
    private WorldClockService worldClockService;
    private MemoryVectorService memoryVectorService;
    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private UsageLogService usageLogService;
    private AiIntensityGate aiIntensityGate;
    private L1FuzzificationService service;

    /** 内存保存桩（模拟 DB：save 后可按 id 读） */
    private final List<ActorMemoryWorking> workingStore = new ArrayList<>();
    private final List<ActorMemoryFuzzState> fuzzStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        fuzzRepository = Mockito.mock(ActorMemoryFuzzStateRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        memoryVectorService = Mockito.mock(MemoryVectorService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        service = new L1FuzzificationService(workingRepository, fuzzRepository, worldClockService,
                memoryVectorService, aiChatGateway, promptTemplateService, usageLogService,
                new ObjectMapper());
        // @Value 字段回填生产默认值
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "writeShortTermFirst", true);
        ReflectionTestUtils.setField(service, "initialIntervalSeconds", 1800L);
        ReflectionTestUtils.setField(service, "growthFactor", 1.8);
        ReflectionTestUtils.setField(service, "maxIntervalSeconds", 86400L);
        ReflectionTestUtils.setField(service, "baseRate", 0.15);
        ReflectionTestUtils.setField(service, "dampeningFactor", 0.7);
        ReflectionTestUtils.setField(service, "rateFloor", 0.02);
        ReflectionTestUtils.setField(service, "maxNoise", 0.12);
        ReflectionTestUtils.setField(service, "sleepCleanupEnabled", true);
        ReflectionTestUtils.setField(service, "bigSleepKeywords", List.of("睡觉", "就寝", "卧床", "歇息", "眠", "睡"));
        ReflectionTestUtils.setField(service, "sleepMinIntervalGameSeconds", 43200L);
        ReflectionTestUtils.setField(service, "awakeMaxGameSeconds", 86400L);
        ReflectionTestUtils.setField(service, "preSleepWindowSeconds", 10800L);
        ReflectionTestUtils.setField(service, "fuzzJump", 0.3);
        ReflectionTestUtils.setField(service, "fuzzJumpCap", 0.7);
        ReflectionTestUtils.setField(service, "trivialImportance", 2);
        ReflectionTestUtils.setField(service, "distillImportance", 4);
        ReflectionTestUtils.setField(service, "distillRetrieval", 3);
        ReflectionTestUtils.setField(service, "mergedFuzzStart", 0.5);
        ReflectionTestUtils.setField(service, "maintenanceEnabled", true);
        // programmatic 档：规则降级路径（确定性）
        when(aiIntensityGate.allowsAi(anyLong())).thenReturn(false);
        when(aiIntensityGate.isLight(anyLong())).thenReturn(false);
        ReflectionTestUtils.setField(service, "aiIntensityGate", aiIntensityGate);
        // 保存桩
        when(workingRepository.save(any(ActorMemoryWorking.class))).thenAnswer(inv -> {
            ActorMemoryWorking w = inv.getArgument(0);
            if (w.getId() == null) {
                w.setId((long) (workingStore.size() + 1));
            }
            workingStore.removeIf(x -> x.getId().equals(w.getId()));
            workingStore.add(w);
            return w;
        });
        when(fuzzRepository.save(any(ActorMemoryFuzzState.class))).thenAnswer(inv -> {
            ActorMemoryFuzzState fs = inv.getArgument(0);
            if (fs.getId() == null) {
                fs.setId((long) (fuzzStore.size() + 1));
            }
            fuzzStore.removeIf(x -> x.getId().equals(fs.getId()));
            fuzzStore.add(fs);
            return fs;
        });
    }

    // ==================== 构造 ====================

    private ActorMemoryFuzzState state(long id, long workingId, double fuzz, double importance,
                                       int retrieval, String content, String status, Long nextGs) {
        ActorMemoryFuzzState fs = new ActorMemoryFuzzState();
        fs.setId(id);
        fs.setProjectId(100L);
        fs.setMemoryWorkingId(workingId);
        fs.setCharacterRef("special:3");
        fs.setOriginalContent(content);
        fs.setCurrentContent(content);
        fs.setFuzzLevel(fuzz);
        fs.setImportanceScore(importance);
        fs.setRetrievalCount(retrieval);
        fs.setFuzzCount(0);
        fs.setFuzzHistoryJson("[]");
        fs.setEmbeddingNoise(0.0);
        fs.setStatus(status);
        fs.setNextFuzzGameSecond(nextGs);
        return fs;
    }

    private ActorMemoryWorking working(long id, String itemType, String content, Long gameSecond) {
        ActorMemoryWorking w = new ActorMemoryWorking();
        w.setId(id);
        w.setProjectId(100L);
        w.setCharacterRef("special:3");
        w.setItemType(itemType);
        w.setContent(content);
        w.setStatus("active");
        w.setGameSecond(gameSecond);
        return w;
    }

    // ==================== 写入路径 ====================

    /** 写入：无重复时写 working short_term + 1:1 fuzz_state + 向量化。 */
    @Test
    void writeShortTermWritesWorkingAndFuzzState() {
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("special:3"), eq("short_term"), eq("active"), eq(0)))
                .thenReturn(List.of());
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(5000L);

        boolean ok = service.writeShortTerm(100L, "special:3", "在集市与赵屠户议价，肉价三文一斤", 4, null);

        assertTrue(ok);
        verify(workingRepository).save(any(ActorMemoryWorking.class));
        ActorMemoryWorking saved = workingStore.get(0);
        assertEquals("short_term", saved.getItemType());
        assertEquals(0.8, saved.getImportanceScore());
        assertEquals(5000L, saved.getGameSecond());
        assertEquals("active", saved.getStatus());
        verify(fuzzRepository).save(any(ActorMemoryFuzzState.class));
        ActorMemoryFuzzState fs = fuzzStore.get(0);
        assertEquals(0.0, fs.getFuzzLevel());
        assertEquals(0.8, fs.getImportanceScore());
        assertEquals(5000L + (long) (1800 * (1 + 0.8)), fs.getNextFuzzGameSecond());
        assertEquals("active", fs.getStatus());
        verify(memoryVectorService).addVector(anyLong(), eq(100L), eq(1L), anyString());
    }

    /** 写入：与既有 short_term 去重命中 → 跳过。 */
    @Test
    void writeShortTermSkipsDuplicate() {
        ActorMemoryWorking dup = working(1L, "short_term", "在集市与赵屠户议价，肉价三文一斤", 5000L);
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq("special:3"), eq("short_term"), eq("active"), eq(0)))
                .thenReturn(List.of(dup));

        boolean ok = service.writeShortTerm(100L, "special:3", "在集市与赵屠户议价，肉价三文一斤", 4, 6000L);

        assertFalse(ok);
        verify(workingRepository, never()).save(any(ActorMemoryWorking.class));
        verify(fuzzRepository, never()).save(any(ActorMemoryFuzzState.class));
    }

    /** 写入：开关关闭 → 回退旧行为（不写 L1）。 */
    @Test
    void writeShortTermDisabledWhenSwitchOff() {
        ReflectionTestUtils.setField(service, "writeShortTermFirst", false);
        boolean ok = service.writeShortTerm(100L, "special:3", "内容", 4, 5000L);
        assertFalse(ok);
        verify(workingRepository, never()).save(any(ActorMemoryWorking.class));
    }

    /** 写入：gameSecond 缺失且时钟不可用 → 不写（零阻断）。 */
    @Test
    void writeShortTermSkipsWhenNoGameSecond() {
        when(workingRepository.findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(worldClockService.currentEngineGameSecond(100L)).thenThrow(new RuntimeException("时钟不可用"));
        boolean ok = service.writeShortTerm(100L, "special:3", "内容", 4, null);
        assertFalse(ok);
    }

    // ==================== 大睡关键词判定 ====================

    @Test
    void bigSleepKeywordHitAndNapExcluded() {
        assertTrue(service.isBigSleepAction("回到房间睡觉"));
        assertTrue(service.isBigSleepAction("就寝"));
        assertTrue(service.isBigSleepAction("卧床休息"));
        assertTrue(service.isBigSleepAction("夜深了，歇息吧"));
        assertTrue(service.isBigSleepAction("安眠"));
        assertFalse(service.isBigSleepAction("小憩片刻"));
        assertFalse(service.isBigSleepAction("午休"));
        assertFalse(service.isBigSleepAction("打盹"));
        assertFalse(service.isBigSleepAction(null));
        assertFalse(service.isBigSleepAction(""));
    }

    // ==================== 清醒期艾宾浩斯渐进模糊 ====================

    /** 扫描：仅到点（next_fuzz ≤ now）的记忆被模糊。 */
    @Test
    void scanDueFuzzOnlyFuzzesDueItems() {
        ActorMemoryFuzzState due = state(1L, 1L, 0.0, 0.4, 0, "昨天在集市遇到阿福", "active", 500L);
        ActorMemoryFuzzState notDue = state(2L, 2L, 0.0, 0.4, 0, "前天去河边洗衣服", "active", 99999L);
        when(fuzzRepository.findByProjectIdAndStatusOrderByNextFuzzGameSecondAsc(100L, "active"))
                .thenReturn(List.of(due, notDue));

        int done = service.scanDueFuzz(100L, 1000L);

        assertEquals(1, done);
        // due 条已模糊：fuzz 增量 = max(0.02, 0.15×(1−0.4×0.7)) = 0.108
        assertEquals(0.11, Math.round(due.getFuzzLevel() * 100.0) / 100.0);
        assertEquals(1, due.getFuzzCount());
        assertNotNull(due.getFuzzHistoryJson());
        // 未到点条不动
        assertEquals(0.0, notDue.getFuzzLevel());
        assertEquals(0, notDue.getFuzzCount());
        verify(fuzzRepository, Mockito.atLeastOnce()).save(due);
    }

    /** 单条模糊：规则降级确定性——高重要记忆模糊极慢（rate 封底 0.02）。 */
    @Test
    void fuzzOneRateFloorForHighImportance() {
        ActorMemoryFuzzState fs = state(1L, 1L, 0.0, 1.0, 0, "非常重要的核心经历", "active", 0L);
        boolean ok = service.fuzzOne(100L, fs, 1000L);
        assertTrue(ok);
        // rate = max(0.02, 0.15×(1−1×0.7)) = 0.045 → 0.05
        assertEquals(0.05, Math.round(fs.getFuzzLevel() * 100.0) / 100.0);
        assertEquals(1, fs.getFuzzCount());
        assertEquals(1000L, fs.getLastFuzzGameSecond());
        assertTrue(fs.getNextFuzzGameSecond() > 1000L);
        verify(fuzzRepository).save(fs);
    }

    /** 手动触发：无视艾宾浩斯间隔直接模糊。 */
    @Test
    void triggerFuzzForcesImmediateFuzz() {
        ActorMemoryFuzzState fs = state(1L, 1L, 0.0, 0.4, 0, "一条普通经历", "active", 999999L);
        when(fuzzRepository.findByMemoryWorkingId(1L)).thenReturn(Optional.of(fs));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(2000L);

        boolean ok = service.triggerFuzz(100L, 1L, null);

        assertTrue(ok);
        assertTrue(fs.getFuzzLevel() > 0.0);
    }

    // ==================== 批6 收口：AI 模糊请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** AI 档位（max/full）下 memory_fuzz 请求前置 memoryChainContext；原始提示保留在后。 */
    @Test
    void aiFuzzRequestPrependsMemoryChainContext() throws Exception {
        // AI 档位：override programmatic 默认（max/full 每次 AI）
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        when(aiIntensityGate.isLight(100L)).thenReturn(false);
        when(promptTemplateService.render(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_FUZZ), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_FUZZ), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"content\":\"新的模糊内容\",\"keptTypes\":[],\"dropped\":[]}",
                        "qwen", 10, 5, 15, 1L));
        AiContextInjector injector = Mockito.mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("special:3"), any()))
                .thenReturn("【世界观】青禾村【我的身份与处境】走镖人【当前世界动态】春日【当前状态】赶路");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        ActorMemoryFuzzState fs = state(1L, 1L, 0.0, 0.4, 0, "昨天在集市遇到阿福", "active", 500L);
        boolean ok = service.fuzzOne(100L, fs, 1000L);

        assertTrue(ok);
        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }

    /** 未装配注入器（fail-open）→ AI 请求原样，不带段位前缀。 */
    @Test
    void aiFuzzRequestNoPrefixWhenInjectorMissing() throws Exception {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        when(aiIntensityGate.isLight(100L)).thenReturn(false);
        when(promptTemplateService.render(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_FUZZ), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(PromptTemplateService.CODE_MEMORY_FUZZ), any()))
                .thenReturn("sys");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"content\":\"新的模糊内容\"}", "qwen", 10, 5, 15, 1L));

        ActorMemoryFuzzState fs = state(1L, 1L, 0.0, 0.4, 0, "昨天在集市遇到阿福", "active", 500L);
        boolean ok = service.fuzzOne(100L, fs, 1000L);

        assertTrue(ok);
        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        assertEquals("prompt", cap.getValue().messages().get(1).content(), "未装配注入器 → 原样");
    }

    // ==================== 睡眠清洗 ====================

    /** 睡眠清洗分档：高重要/高频 → 蒸馏；琐事 → 合并；重要一点 → 模糊跳升。 */
    @Test
    void sleepCleanupClassifiesByImportance() {
        // 用大号 id 避免与保存桩自动分配 id（1/2/3）撞车——否则归档重存时会误删合并行
        ActorMemoryFuzzState high = state(101L, 101L, 0.0, 0.8, 1, "重要：答应帮阿福修屋顶", "active", null);
        ActorMemoryFuzzState trivial = state(102L, 102L, 0.0, 0.4, 0, "上午喝了碗豆浆", "active", null);
        ActorMemoryFuzzState mid = state(103L, 103L, 0.0, 0.6, 0, "今天与邻居聊了几句", "active", null);
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of(high, trivial, mid));
        MemoryDistillService distillService = Mockito.mock(MemoryDistillService.class);
        ReflectionTestUtils.setField(service, "distillService", distillService);
        when(distillService.distillL1toL2(eq(100L), eq(high), anyLong())).thenReturn(true);

        int handled = service.sleepCleanup(100L, "special:3", 50000L);

        // 高重要蒸馏 1 + 琐事合并 1 + 模糊跳升 1
        assertEquals(3, handled);
        verify(distillService).distillL1toL2(eq(100L), eq(high), anyLong());
        // 琐事档：原行归档 + 合并行重存（fuzz 起点 0.5）
        assertEquals("archived", trivial.getStatus());
        ActorMemoryFuzzState merged = fuzzStore.stream()
                .filter(x -> x.getFuzzLevel() != null && Math.abs(x.getFuzzLevel() - 0.5) < 1e-9)
                .findFirst().orElse(null);
        assertNotNull(merged);
        assertEquals("active", merged.getStatus());
        // 模糊档：+0.3 → 0.3
        assertEquals(0.3, Math.round(mid.getFuzzLevel() * 100.0) / 100.0);
    }

    /** 睡眠清洗冷却：12 游戏时内重复大睡不洗第二次。 */
    @Test
    void onSleepActionCooldownBlocksRepeat() {
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        boolean first = service.onSleepAction(100L, "special:3", 10000L);
        assertFalse(first); // 无记忆可洗
        boolean second = service.onSleepAction(100L, "special:3", 10000L + 1000L);
        assertFalse(second); // 冷却内（< 43200）
        // 超过冷却后允许再次清洗
        boolean third = service.onSleepAction(100L, "special:3", 10000L + 50000L);
        assertFalse(third); // 仍无记忆可洗，但已过冷却（不返回 true，仅不阻塞）
    }

    /** 睡眠清洗：无记忆时返回 0。 */
    @Test
    void sleepCleanupEmptyStates() {
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of());
        assertEquals(0, service.sleepCleanup(100L, "special:3", 50000L));
    }

    // ==================== 检索标记 ====================

    /** 检索命中标记：retrieval_count +1（蒸馏触发依据）。 */
    @Test
    void markRetrievedIncrementsCount() {
        ActorMemoryFuzzState fs = state(1L, 1L, 0.3, 0.4, 2, "内容", "active", null);
        when(fuzzRepository.findByMemoryWorkingId(1L)).thenReturn(Optional.of(fs));
        service.markRetrieved(1L);
        assertEquals(3, fs.getRetrievalCount());
        verify(fuzzRepository).save(fs);
    }

    // ==================== 规则降级（零 AI 兜底，确定性） ====================

    /** 规则模糊：剥离细节 + 确定性限定词（>0.7 档「好像…记不太清」）。 */
    @Test
    void rulesFuzzStripsDetailsAndHighLevelQualifier() {
        L1FuzzificationService.FuzzResult r = L1FuzzificationService.rulesFuzz(
                "昨天上午在集市花了3文钱买了条鱼，「这鱼真新鲜」", 0.8);
        String c = r.content();
        assertTrue(c.contains("记不太清"));
        assertTrue(c.startsWith("好像"));
        // 引号对话原文被剥离
        assertFalse(c.contains("这鱼真新鲜"));
        // 数字/时间词被剥离
        assertFalse(c.contains("昨天"));
        assertFalse(c.contains("3文"));
    }

    /** 规则模糊：0.3~0.7 档「可能」限定词。 */
    @Test
    void rulesFuzzMidLevelQualifier() {
        L1FuzzificationService.FuzzResult r = L1FuzzificationService.rulesFuzz("今天去河边洗衣服", 0.5);
        assertTrue(r.content().startsWith("可能"));
        assertFalse(r.content().contains("记不太清"));
    }

    /** 细节剥离：时间词/数字/引号原文正则。 */
    @Test
    void stripDetailsRemovesTimeNumbersQuotes() {
        String s = L1FuzzificationService.stripDetails("昨天3月5日上午在「老张酒馆」喝了二两酒，花了12文");
        assertFalse(s.contains("昨天"));
        assertFalse(s.contains("3月5日"));
        assertFalse(s.contains("12"));
        assertFalse(s.contains("老张酒馆"));
        assertFalse(s.contains("「」"));
    }

    /** 规则琐事合并：前两条主干 + 概括后缀。 */
    @Test
    void rulesMergeTriviaKeepsFirstTwoMains() {
        ActorMemoryFuzzState a = state(1L, 1L, 0.0, 0.2, 0, "上午喝了碗豆浆", "active", null);
        ActorMemoryFuzzState b = state(2L, 2L, 0.0, 0.2, 0, "下午在门口晒了会太阳", "active", null);
        ActorMemoryFuzzState c = state(3L, 3L, 0.0, 0.2, 0, "晚上逗了会猫", "active", null);
        String merged = L1FuzzificationService.rulesMergeTrivia(List.of(a, b, c));
        assertTrue(merged.contains("等琐事"));
        assertTrue(merged.contains("细节记不清"));
        // 只取前两条主干
        assertFalse(merged.contains("逗了会猫"));
        assertTrue(merged.contains("豆浆"));
        assertTrue(merged.contains("晒了会太阳"));
    }

    /** 琐事合并：空输入回退默认概括。 */
    @Test
    void rulesMergeTriviaEmptyFallback() {
        assertEquals("近日有些琐碎日常", L1FuzzificationService.rulesMergeTrivia(List.of()));
    }

    // ==================== O2 世界运行时门控（2026-09-16 用户需求：启动全静止，进入沙盘才运行） ====================

    /**
     * O2 门控：定时维护（超清醒兜底 + 孤儿清理）只处理运行中的世界——
     * 未激活项目的 fuzz_state/working 行不被触碰（世界静止=数据静止）。
     */
    @Test
    void maintenanceSkipsStoppedWorlds() {
        var projectRepository = Mockito.mock(com.holzyn.actor.domain.project.repository.ActorProjectRepository.class);
        var p1 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p1.setId(100L);
        var p2 = new com.holzyn.actor.domain.project.entity.ActorProject();
        p2.setId(101L);
        when(projectRepository.findAll()).thenReturn(List.of(p1, p2));
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
        var gate = Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService.class);
        when(gate.isActive(100L)).thenReturn(true);
        when(gate.isActive(101L)).thenReturn(false);
        ReflectionTestUtils.setField(service, "runtimeGate", gate);
        // 运行中的 100：空数据（不触发任何处理）；静止的 101：孤儿 fuzz_state 存在也不清理
        when(fuzzRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of());
        when(fuzzRepository.findByProjectIdOrderByIdAsc(101L)).thenReturn(
                List.of(state(1L, 999L, 0.2, 2, 0, "内容", "active", 0L)));
        when(workingRepository.existsById(999L)).thenReturn(false);
        service.maintenance();
        // 静止世界：孤儿清理连查询都不发生（门控在仓库访问前拦截）
        verify(fuzzRepository, never()).findByProjectIdOrderByIdAsc(101L);
        verify(fuzzRepository, never()).delete(any(ActorMemoryFuzzState.class));
        // 运行中世界：正常遍历
        verify(fuzzRepository).findByProjectIdOrderByIdAsc(100L);
    }
}
