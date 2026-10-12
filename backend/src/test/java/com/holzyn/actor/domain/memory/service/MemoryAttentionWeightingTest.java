package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.growth.attention.AttentionLevelProvider;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N6 记忆检索注意力加权单元测试（2026-09-27，domain/memory/service 同包）。
 *
 * <p>职责：验证——层间优先级修正（focus→LOW 升 MID / dormant→HIGH 降 MID）、档位数量配额
 * （L1 清晰段上限 / L2 top-K 按 focus/ambient/dormant 缩放）、L1 段「[清晰]/[模糊]」标签
 * 精简（模糊记忆仅保留「（记不太清）」标记）、无注意力信号时零影响（回滚既有行为）。</p>
 *
 * <p>测试纪律：字段注入 + {@code ReflectionTestUtils.setField}（构造器不变）；注意力档位
 * 提供器全 mock（脚本化响应）；AI tier 判定闸门 mock 为 programmatic（走规则 tier，确定性）。</p>
 */
class MemoryAttentionWeightingTest {

    private MemoryInjectionRouter router;
    private MemoryInjectorService injector;
    private AttentionLevelProvider attentionProvider;
    private ActorMemoryFuzzStateRepository fuzzRepository;
    private ActorMemoryRepository memoryRepository;
    private GrowthProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fuzzRepository = mock(ActorMemoryFuzzStateRepository.class);
        ActorMemoryWorkingRepository workingRepository = mock(ActorMemoryWorkingRepository.class);
        com.holzyn.actor.domain.memory.service.MemoryVectorService vectorService =
                mock(com.holzyn.actor.domain.memory.service.MemoryVectorService.class);
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        com.holzyn.actor.domain.settings.service.PromptTemplateService templateService =
                mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        com.holzyn.actor.domain.usage.service.UsageLogService usageService =
                mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        AiIntensityGate intensityGate = mock(AiIntensityGate.class);
        when(intensityGate.allowsAi(anyLong())).thenReturn(false); // programmatic 档 → 规则 tier 确定性
        attentionProvider = mock(AttentionLevelProvider.class);
        properties = new GrowthProperties();

        router = new MemoryInjectionRouter(fuzzRepository, workingRepository, vectorService,
                gateway, templateService, usageService, new tools.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(router, "fuzzClearThreshold", 0.3);
        ReflectionTestUtils.setField(router, "budgetLowTokens", 300);
        ReflectionTestUtils.setField(router, "budgetMidTokens", 800);
        ReflectionTestUtils.setField(router, "budgetHighTokens", 2000);
        ReflectionTestUtils.setField(router, "traceMaxEntries", 200);
        ReflectionTestUtils.setField(router, "hybridVecWeight", 0.6);
        ReflectionTestUtils.setField(router, "aiIntensityGate", intensityGate);
        ReflectionTestUtils.setField(router, "attentionLevelProvider", attentionProvider);
        ReflectionTestUtils.setField(router, "growthProperties", properties);

        memoryRepository = mock(ActorMemoryRepository.class);
        WorldClockService clock = mock(WorldClockService.class);
        when(clock.gameHourAt(anyLong(), any())).thenReturn(100L);
        injector = new MemoryInjectorService(memoryRepository, clock);
        ReflectionTestUtils.setField(injector, "enabled", true);
        ReflectionTestUtils.setField(injector, "routerEnabled", true);
        ReflectionTestUtils.setField(injector, "budgetCoreTokens", 800);
        ReflectionTestUtils.setField(injector, "budgetWorkingTokens", 400);
        ReflectionTestUtils.setField(injector, "budgetL2Tokens", 1200);
        ReflectionTestUtils.setField(injector, "l2TopK", 8);
        ReflectionTestUtils.setField(injector, "projectTopK", 4);
        ReflectionTestUtils.setField(injector, "workingTopK", 5);
        ReflectionTestUtils.setField(injector, "recencyDecayHours", 24);
        ReflectionTestUtils.setField(injector, "dedupThreshold", 0.6);
        ReflectionTestUtils.setField(injector, "router", router);
        ReflectionTestUtils.setField(injector, "budgetWorldTokens", 300);

        // 6 条 L1 清晰记忆（fuzz<0.3）+ 6 条 L2 记忆（episodic）
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(anyLong(), anyString(), anyString()))
                .thenReturn(List.of(
                        l1(1L, 101L, 0.1, "昨天在集市帮阿福修了屋顶"),
                        l1(2L, 102L, 0.2, "灵溪河边的浮桥木板松了两块"),
                        l1(3L, 103L, 0.15, "王婆的茶馆新到了一批灵茶"),
                        l1(4L, 104L, 0.25, "村口石牌坊夜里会发光"),
                        l1(5L, 105L, 0.3, "老周头说他家的牛丢了"),
                        l1(6L, 106L, 0.5, "半年前集市上见过一个外乡人（记不太清了）")));
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyString(), anyInt())).thenReturn(List.of(
                l2(11L, "陈大胆欠我三文酒钱"),
                l2(12L, "翠兰与大山定了亲"),
                l2(13L, "春旱那年全村减产三成"),
                l2(14L, "石牌坊是村里的老物件"),
                l2(15L, "雷家与沈家有过节"),
                l2(16L, "后山的竹林里常有野兔")));
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyLong(), anyList(), anyInt())).thenReturn(List.of());
    }

    private static ActorMemoryFuzzState l1(long id, long workingId, double fuzz, String content) {
        ActorMemoryFuzzState fs = new ActorMemoryFuzzState();
        fs.setId(id);
        fs.setProjectId(100L);
        fs.setMemoryWorkingId(workingId);
        fs.setCharacterRef("npc:1");
        fs.setOriginalContent(content);
        fs.setCurrentContent(content);
        fs.setFuzzLevel(fuzz);
        fs.setImportanceScore(0.4);
        fs.setRetrievalCount(0);
        fs.setFuzzCount(0);
        fs.setFuzzHistoryJson("[]");
        fs.setStatus("active");
        return fs;
    }

    private static ActorMemory l2(long id, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterRef("npc:1");
        m.setType(ActorMemoryTypes.TYPE_EPISODIC);
        m.setContent(content);
        m.setImportance(3);
        m.setCreatedAt(LocalDateTime.now());
        m.setLastAccessAt(LocalDateTime.now());
        m.setDeleted(0);
        return m;
    }

    // ==================== 层间优先级修正（adjustTierByAttention） ====================

    @Test
    @DisplayName("N6 层间优先级修正：focus → LOW 升 MID；dormant → HIGH 降 MID；其余原样")
    void adjustTierByAttention() {
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_FOCUS);
        assertEquals(MemoryInjectionRouter.TIER_MID, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_LOW));
        assertEquals(MemoryInjectionRouter.TIER_HIGH, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_HIGH));
        assertEquals(MemoryInjectionRouter.TIER_MID, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_MID));
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        assertEquals(MemoryInjectionRouter.TIER_MID, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_HIGH));
        assertEquals(MemoryInjectionRouter.TIER_LOW, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_LOW));
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_AMBIENT);
        assertEquals(MemoryInjectionRouter.TIER_LOW, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_LOW));
        assertEquals(MemoryInjectionRouter.TIER_HIGH, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_HIGH));
    }

    @Test
    @DisplayName("N6 层间优先级修正：无提供器/开关关 → 原样返回（零影响回滚）")
    void adjustTierByAttentionNoProvider() {
        ReflectionTestUtils.setField(router, "attentionLevelProvider", null);
        assertEquals(MemoryInjectionRouter.TIER_LOW, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_LOW));
        assertEquals(MemoryInjectionRouter.TIER_HIGH, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_HIGH));
        properties.getAttention().setMemoryWeightEnabled(false);
        ReflectionTestUtils.setField(router, "attentionLevelProvider", attentionProvider);
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_FOCUS);
        assertEquals(MemoryInjectionRouter.TIER_LOW, router.adjustTierByAttention(100L, "npc:1", MemoryInjectionRouter.TIER_LOW),
                "memoryWeightEnabled=false 时注意力不参与层间修正");
    }

    // ==================== 档位数量配额（attentionTopKByTier） ====================

    @Test
    @DisplayName("N6 条数配额：focus=5/4、ambient=4/3、dormant=2/2；无信号回退 fallback")
    void attentionTopKByTier() {
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_FOCUS);
        assertEquals(5, router.attentionTopKByTier(100L, "npc:1", "l1", 5));
        assertEquals(4, router.attentionTopKByTier(100L, "npc:1", "l2", 3));
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_AMBIENT);
        assertEquals(4, router.attentionTopKByTier(100L, "npc:1", "l1", 5));
        assertEquals(3, router.attentionTopKByTier(100L, "npc:1", "l2", 3));
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(BrainTierService.TIER_DORMANT);
        assertEquals(2, router.attentionTopKByTier(100L, "npc:1", "l1", 5));
        assertEquals(2, router.attentionTopKByTier(100L, "npc:1", "l2", 3));
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(null);
        assertEquals(5, router.attentionTopKByTier(100L, "npc:1", "l1", 5));
        assertEquals(3, router.attentionTopKByTier(100L, "npc:1", "l2", 3));
    }

    // ==================== 注入组装集成（buildRouted 配额生效 + L1 标签精简） ====================

    /** 组装注入并返回各段命中数。 */
    private Map<String, Integer> buildSegmentHits(String tier) {
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(tier);
        MemoryInjectorService.InjectionResult result = injector.build(100L, "npc:1",
                MemoryInjectorService.SCENE_DIALOG, "最近村里有什么大事", 1, null);
        Map<String, Integer> hits = new java.util.LinkedHashMap<>();
        for (MemoryInjectorService.Segment s : result.segments().values()) {
            hits.put(s.key(), s.hits().size());
        }
        return hits;
    }

    @Test
    @DisplayName("N6 集成：focus 档 L1 清晰 ≤5 条、L2 ≤4 条（预算内全量注入）")
    void buildRoutedFocusCaps() {
        Map<String, Integer> hits = buildSegmentHits(BrainTierService.TIER_FOCUS);
        assertTrue(hits.getOrDefault("l1", 0) <= 5, "focus 档 L1 清晰段上限 5，实际 " + hits.get("l1"));
        assertTrue(hits.getOrDefault("l2", 0) <= 4, "focus 档 L2 top-K 上限 4，实际 " + hits.get("l2"));
        assertEquals(4, hits.getOrDefault("l2", 0), "focus 档 6 条候选按 top-4 注入");
    }

    @Test
    @DisplayName("N6 集成：dormant 档 L1 清晰 ≤2 条、L2 ≤2 条（低关注收窄注入）")
    void buildRoutedDormantCaps() {
        Map<String, Integer> hits = buildSegmentHits(BrainTierService.TIER_DORMANT);
        assertTrue(hits.getOrDefault("l1", 0) <= 2, "dormant 档 L1 清晰段上限 2，实际 " + hits.get("l1"));
        assertTrue(hits.getOrDefault("l2", 0) <= 2, "dormant 档 L2 top-K 上限 2，实际 " + hits.get("l2"));
        assertEquals(2, hits.getOrDefault("l2", 0), "dormant 档 6 条候选按 top-2 注入");
        // 低关注总注入 token 少于高关注（降输出目标）
        String focusText = renderFor(BrainTierService.TIER_FOCUS);
        String dormantText = renderFor(BrainTierService.TIER_DORMANT);
        assertTrue(dormantText.length() < focusText.length(),
                "dormant 注入量应少于 focus（配额收窄降输入），focus=" + focusText.length()
                        + " dormant=" + dormantText.length());
    }

    private String renderFor(String tier) {
        when(attentionProvider.tierOf(100L, "npc:1")).thenReturn(tier);
        MemoryInjectorService.InjectionResult result = injector.build(100L, "npc:1",
                MemoryInjectorService.SCENE_DIALOG, "最近村里有什么大事", 1, null);
        return injector.render(result);
    }

    @Test
    @DisplayName("N6 Prompt 精简：L1 段无「[清晰]/[模糊]」标签，模糊记忆仅保留「（记不太清）」标记")
    void l1LabelSimplified() {
        String text = renderFor(BrainTierService.TIER_FOCUS);
        assertFalse(text.contains("[清晰]"), "标签已精简，不应含「[清晰]」");
        assertFalse(text.contains("[模糊]"), "标签已精简，不应含「[模糊]」");
        assertTrue(text.contains("（记不太清）"), "模糊记忆保留「（记不太清）」标记");
        // 无注意力信号（provider null）时格式与配额同样成立（回滚路径一致）
        ReflectionTestUtils.setField(router, "attentionLevelProvider", null);
        String noProvider = renderFor(null);
        assertFalse(noProvider.contains("[清晰]"));
        assertTrue(noProvider.contains("（记不太清）"));
    }
}
