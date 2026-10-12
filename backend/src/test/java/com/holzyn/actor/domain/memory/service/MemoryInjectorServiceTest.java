package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆注入器单元测试（M5，domain/memory/service，同包）。
 * <p>职责：验证——开关/参数校验（关/空项目/空角色引用 → 空结果）、分场景注入组合
 * （dialog=core+working+l2+project+summary / decision=core+l2 / perception=core+working+l2 /
 * reflection=core+反思输入）、L2 统一评分制（§6.3 分层权重：episodic 侧重 recency、semantic 侧重
 * relevance、reflection 侧重 importance；plan 不参与评分）、top-K 截断 + token 预算（超预算从低分丢起）、
 * C-16 注入去重、R3 staleness 标记、special 双轨合并（存量 character_id 行）、
 * recency/relevance 评分、rankForInjection 降级兜底、render 拼接。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案 V1.0》§6.3/§9/§9.5。</p>
 */
class MemoryInjectorServiceTest {

    private ActorMemoryRepository memoryRepository;
    private WorldClockService worldClockService;
    private CoreMemoryService coreService;
    private MemoryWorkingService workingService;
    private MemoryInjectorService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        coreService = Mockito.mock(CoreMemoryService.class);
        workingService = Mockito.mock(MemoryWorkingService.class);
        service = new MemoryInjectorService(memoryRepository, worldClockService);
        // @Value 字段回填生产默认值（§9.5 预算 + §6.3 权重 + top-K）
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "budgetCoreTokens", 800);
        ReflectionTestUtils.setField(service, "budgetWorkingTokens", 400);
        ReflectionTestUtils.setField(service, "budgetL2Tokens", 1200);
        ReflectionTestUtils.setField(service, "l2TopK", 8);
        ReflectionTestUtils.setField(service, "projectTopK", 4);
        ReflectionTestUtils.setField(service, "workingTopK", 5);
        ReflectionTestUtils.setField(service, "recencyDecayHours", 24);
        ReflectionTestUtils.setField(service, "dedupThreshold", 0.6);
        ReflectionTestUtils.setField(service, "weightEpisodicImp", 2.0);
        ReflectionTestUtils.setField(service, "weightEpisodicRec", 3.5);
        ReflectionTestUtils.setField(service, "weightEpisodicRel", 1.5);
        ReflectionTestUtils.setField(service, "weightSemanticImp", 2.0);
        ReflectionTestUtils.setField(service, "weightSemanticRec", 2.0);
        ReflectionTestUtils.setField(service, "weightSemanticRel", 3.0);
        ReflectionTestUtils.setField(service, "weightReflectionImp", 3.0);
        ReflectionTestUtils.setField(service, "weightReflectionRec", 2.0);
        ReflectionTestUtils.setField(service, "weightReflectionRel", 1.0);
        ReflectionTestUtils.setField(service, "coreService", coreService);
        ReflectionTestUtils.setField(service, "workingService", workingService);
        // 时钟固定：nowGameHour=baseGameHour=0 → 无 staleness（隔离测试）
        when(worldClockService.gameHourAt(anyLong(), any())).thenReturn(0L);
        // 仓库默认空（各用例按需 stub）
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(anyLong(), anyString(), eq(0)))
                .thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyLong(), any(), eq(0))).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(anyLong(), eq(0)))
                .thenReturn(List.of());
        when(memoryRepository.findSummariesByProjectIdAndCharacterId(anyLong(), anyLong())).thenReturn(List.of());
        // 注入段默认：L3 核心返回非空（恒在，分场景用例均断言 core 段存在）；L1 热缓冲默认空
        when(coreService.renderCoreSegment(anyLong(), anyString())).thenReturn("【核心】罗小黑：青岩山守护使");
        when(workingService.injectWorkingSegment(anyLong(), anyString(), anyInt())).thenReturn(null);
    }

    // ==================== 构造记忆 ====================

    private ActorMemory mem(long id, String ref, String type, String content, int importance,
                            LocalDateTime createdAt, LocalDateTime lastAccessAt) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterRef(ref);
        m.setType(type);
        m.setContent(content);
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        m.setLastAccessAt(lastAccessAt);
        m.setDeleted(0);
        return m;
    }

    // ==================== 开关 / 参数校验 ====================

    /**
     * 总开关关闭：返回空结果（无任何段）。
     */
    @Test
    void buildDisabledReturnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        assertTrue(r.segments().isEmpty());
        assertEquals("dialog", r.scenario());
        assertEquals(0, r.totalTokens());
    }

    /**
     * projectId 为空：返回空结果。
     */
    @Test
    void buildNullProjectReturnsEmpty() {
        MemoryInjectorService.InjectionResult r = service.build(null, "npc:3", "dialog", null, null);
        assertTrue(r.segments().isEmpty());
    }

    /**
     * 角色引用空白：返回空结果。
     */
    @Test
    void buildBlankCharacterRefReturnsEmpty() {
        MemoryInjectorService.InjectionResult r = service.build(100L, "  ", "dialog", null, null);
        assertTrue(r.segments().isEmpty());
    }

    // ==================== 分场景注入组合（§9.1~9.4） ====================

    /**
     * 对话场景：core + working + l2 + project + summary 五段齐全。
     */
    @Test
    void buildDialogIncludesAllSegments() {
        when(coreService.renderCoreSegment(100L, "npc:3")).thenReturn("【核心】罗小黑：猫妖，青岩山守护使");
        when(workingService.injectWorkingSegment(100L, "npc:3", 5)).thenReturn("【情境】今日天气晴");
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "罗小黑擅长酿酒", 5, base, base),
                        mem(2L, "npc:3", ActorMemoryTypes.TYPE_EPISODIC, "昨日在山顶修行", 3, base, base)));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(eq(100L), eq(0)))
                .thenReturn(List.of(
                        mem(9L, null, ActorMemoryTypes.TYPE_SEMANTIC, "青岩山最近频发狼灾", 4, base, base)));
        when(memoryRepository.findSummariesByProjectIdAndCharacterId(100L, 3L))
                .thenReturn(List.of(mem(10L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "上次会话：与猎户交涉", 3, base, base)));

        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        Map<String, MemoryInjectorService.Segment> seg = r.segments();
        assertTrue(seg.containsKey("core"), "对话应含 L3 核心段");
        assertTrue(seg.containsKey("working"), "对话应含 L1 热缓冲段");
        assertTrue(seg.containsKey("l2"), "对话应含 L2 长期段");
        assertTrue(seg.containsKey("project"), "对话应含世界大事记段");
        assertTrue(seg.containsKey("summary"), "对话应含过往摘要段");
        assertEquals(2, seg.get("l2").hits().size(), "L2 段应含 2 条命中");
        assertTrue(seg.get("l2").tokens() > 0);
        assertTrue(r.totalTokens() > 0, "总 token 应 >0");
    }

    /**
     * 决策场景：仅 core + l2（无 working/project/summary——对齐 §9.2）。
     */
    @Test
    void buildDecisionExcludesWorkingProjectSummary() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "市场行情", 4, base, base)));
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "decision", "今日该去何处", null);
        Map<String, MemoryInjectorService.Segment> seg = r.segments();
        assertTrue(seg.containsKey("core"));
        assertTrue(seg.containsKey("l2"));
        assertFalse(seg.containsKey("working"));
        assertFalse(seg.containsKey("project"));
        assertFalse(seg.containsKey("summary"));
    }

    /**
     * 感知场景：core + working + l2（无 project/summary——对齐 §9.3）。
     */
    @Test
    void buildPerceptionIncludesWorkingNotProjectSummary() {
        when(workingService.injectWorkingSegment(100L, "npc:3", 5)).thenReturn("【情境】林中脚步声");
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(mem(1L, "npc:3", ActorMemoryTypes.TYPE_EPISODIC, "看见黑衣人", 4, base, base)));
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "perception", "前方异常", null);
        Map<String, MemoryInjectorService.Segment> seg = r.segments();
        assertTrue(seg.containsKey("core"));
        assertTrue(seg.containsKey("working"));
        assertTrue(seg.containsKey("l2"));
        assertFalse(seg.containsKey("project"));
        assertFalse(seg.containsKey("summary"));
    }

    /**
     * 反思场景：core + 反思输入段（§9.4：高重要 episodic/semantic/reflection，staleness 优先）。
     */
    @Test
    void buildReflectionUsesReflectionInput() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "村民传言山谷有异动", 5, base, base),
                        mem(2L, "npc:3", ActorMemoryTypes.TYPE_REFLECTION, "此前判断需修正", 4, base, base),
                        mem(3L, "npc:3", ActorMemoryTypes.TYPE_PLAN, "计划明日巡山", 2, base, base)));
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "reflection", null, null);
        Map<String, MemoryInjectorService.Segment> seg = r.segments();
        assertTrue(seg.containsKey("reflection"), "反思场景应含反思输入段");
        assertFalse(seg.containsKey("l2"), "反思场景不走 L2 评分段");
        // plan 不参与反思输入：命中 2 条（semantic + reflection）
        assertEquals(2, seg.get("reflection").hits().size());
        // staleness 优先 + 重要度 desc：semantic(5) 在 reflection(4) 前
        assertEquals(1L, seg.get("reflection").hits().get(0).id());
        assertEquals(2L, seg.get("reflection").hits().get(1).id());
    }

    // ==================== L2 统一评分制（§6.3 分层权重） ====================

    /**
     * 分层权重：reflection 侧重 importance（权重 3）、episodic 侧重 recency（权重 3.5）、semantic 侧重 relevance（权重 3）。
     */
    @Test
    void weightedScoreUsesTypeSpecificWeights() {
        // reflection：3*imp + 2*rec + 1*rel
        double reflection = service.weightedScore(ActorMemoryTypes.TYPE_REFLECTION, 5, 0.5, 0.5);
        assertEquals(3 * 5 + 2 * 0.5 + 1 * 0.5, reflection, 1e-9);
        // episodic：2*imp + 3.5*rec + 1.5*rel
        double episodic = service.weightedScore(ActorMemoryTypes.TYPE_EPISODIC, 5, 0.5, 0.5);
        assertEquals(2 * 5 + 3.5 * 0.5 + 1.5 * 0.5, episodic, 1e-9);
        // semantic：2*imp + 2*rec + 3*rel
        double semantic = service.weightedScore(ActorMemoryTypes.TYPE_SEMANTIC, 5, 0.5, 0.5);
        assertEquals(2 * 5 + 2 * 0.5 + 3 * 0.5, semantic, 1e-9);
    }

    /**
     * plan 不参与 L2 评分注入（决策按最近计划直取，非评分路径）。
     */
    @Test
    void planExcludedFromL2Scoring() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "npc:3", ActorMemoryTypes.TYPE_PLAN, "计划明日赴市", 5, base, base)));
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        assertFalse(r.segments().containsKey("l2"), "仅 plan 记忆不应产生 L2 段");
    }

    /**
     * L2 top-K：按评分降序截断（高重要度优先），命中顺序与条数正确。
     */
    @Test
    void l2TopKTruncatesByScore() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "甲事", 5, base, base),
                        mem(2L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "乙事", 3, base, base),
                        mem(3L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "丙事", 1, base, base)));
        ReflectionTestUtils.setField(service, "l2TopK", 2);
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        MemoryInjectorService.Segment l2 = r.segments().get("l2");
        assertNotNull(l2);
        assertEquals(2, l2.hits().size());
        assertEquals(1L, l2.hits().get(0).id(), "高分先入");
        assertEquals(2L, l2.hits().get(1).id());
    }

    /**
     * L2 注入：不设 token 预算（2026-10-10 决策），条数由 top-K 配额控制——两条都在 top-K 内则全量保留。
     */
    @Test
    void l2KeepsAllWithinTopKWithoutTokenBudget() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(
                        mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "第一段较长的事实内容值得记住", 5, base, base),
                        mem(2L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "第二段较长的事实内容也值得记住", 3, base, base)));
        // 2026-10-10 决策：budgetL2Tokens 不再作为截断预算（全量注入），top-K 仍生效（默认 8 ≥ 2）
        ReflectionTestUtils.setField(service, "budgetL2Tokens", 10);
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        MemoryInjectorService.Segment l2 = r.segments().get("l2");
        assertNotNull(l2);
        assertEquals(2, l2.hits().size(), "不设 token 预算：top-K 内两条全量保留");
    }

    /**
     * C-16 注入去重：与历史窗口文本重叠度 ≥ 阈值剔除。
     */
    @Test
    void filterAgainstTextsDedupsOverlapping() {
        ActorMemory m = mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "罗小黑擅长酿酒与调香", 5,
                LocalDateTime.now(), LocalDateTime.now());
        assertFalse(MemoryInjectorService.filterAgainstTexts(m, List.of("罗小黑擅长酿酒与调香"), 0.6),
                "完全相同内容应被剔除");
        assertFalse(MemoryInjectorService.filterAgainstTexts(m, List.of("罗小黑擅长酿酒"), 0.6),
                "高重叠应被剔除");
        assertTrue(MemoryInjectorService.filterAgainstTexts(m, List.of("完全不相关内容"), 0.6));
        assertTrue(MemoryInjectorService.filterAgainstTexts(m, (java.util.List<String>) null, 0.6));
    }

    // ==================== 评分 / staleness / 工具 ====================

    /**
     * recency 指数衰减：同时刻=1；超衰减窗口趋近 0；null 安全。
     */
    @Test
    void recencyScoreExpDecay() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(1.0, MemoryInjectorService.recencyScore(now, now, 24), 1e-9);
        double oneHour = MemoryInjectorService.recencyScore(now.minusHours(1), now, 24);
        assertEquals(Math.exp(-1.0 / 24), oneHour, 1e-6);
        double oneDay = MemoryInjectorService.recencyScore(now.minusHours(24), now, 24);
        assertEquals(Math.exp(-1), oneDay, 1e-6);
        assertEquals(0.0, MemoryInjectorService.recencyScore(null, now, 24), 1e-9);
        assertEquals(0.0, MemoryInjectorService.recencyScore(now, null, 24), 1e-9);
    }

    /**
     * relevance 文本相关分：Dice 二元组重叠（无 embedding 降级）；空输入=0。
     */
    @Test
    void relevanceScoreDiceOverlap() {
        double same = MemoryInjectorService.relevanceScore("罗小黑擅长酿酒", "罗小黑擅长酿酒");
        assertTrue(same > 0.9, "相同文本应高相关");
        double part = MemoryInjectorService.relevanceScore("罗小黑酿酒", "罗小黑擅长酿酒调香");
        assertTrue(part > 0, "部分重叠应 >0");
        assertEquals(0.0, MemoryInjectorService.relevanceScore(null, "内容"), 1e-9);
        assertEquals(0.0, MemoryInjectorService.relevanceScore("查询", ""), 1e-9);
    }

    /**
     * R3 staleness 标记：重要度<3 / 非 semantic·episodic / 游戏日未超阈值 → false；超阈值 → true。
     */
    @Test
    void staleFlagRespectsImportanceTypeAndGameDays() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        // 重要度低 → 不标记
        ActorMemory low = mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "小事", 2, base, base);
        assertFalse(service.staleFlag(100L, low, 100L));
        // 类型不符（reflection）→ 不标记
        ActorMemory refl = mem(2L, "npc:3", ActorMemoryTypes.TYPE_REFLECTION, "洞察", 5, base, base);
        assertFalse(service.staleFlag(100L, refl, 100L));
        // 高重要 semantic + 超 3 游戏日未复核 → 标记
        when(worldClockService.gameHourAt(eq(100L), eq(base))).thenReturn(10L);
        ActorMemory old = mem(3L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "重要旧事", 5, base, base);
        assertTrue(service.staleFlag(100L, old, 100L), "now=100h(第4天) base=10h(第0天) 超 3 游戏日应标记");
        // 未超 3 游戏日 → 不标记
        when(worldClockService.gameHourAt(eq(100L), eq(base))).thenReturn(70L);
        assertFalse(service.staleFlag(100L, old, 100L), "now=100h base=70h 同一天不标记");
    }

    /**
     * 2026-10-10 决策：不设字数硬边界——trimToBudget 恒等返回原文（不再按 token 截断）。
     */
    @Test
    void trimToBudgetReturnsFullText() {
        assertEquals("短文本", MemoryInjectorService.trimToBudget("短文本", 800));
        String longText = "一".repeat(200);
        assertEquals(longText, MemoryInjectorService.trimToBudget(longText, 10), "不设预算：超长文本全量返回不截断");
        assertEquals("", MemoryInjectorService.trimToBudget("", 800));
        assertEquals("", MemoryInjectorService.trimToBudget(null, 800));
    }

    /**
     * 统一身份引用解析（G1a）：私有 {@code parseSpecialId} 已删除，统一走 {@link com.holzyn.actor.common.NpcRefs#idOf}；
     * 严格单前缀 {@code npc:}——历史 special:/crowd: 与非法输入一律返回 null。
     */
    @Test
    void npcRefIdParsingExtractsOrNull() {
        assertEquals(Long.valueOf(3L), com.holzyn.actor.common.NpcRefs.idOf("npc:3"));
        assertEquals(Long.valueOf(12L), com.holzyn.actor.common.NpcRefs.idOf("npc:12"));
        assertNull(com.holzyn.actor.common.NpcRefs.idOf("crowd:3"), "历史 crowd: 前缀不再是合法身份引用");
        assertNull(com.holzyn.actor.common.NpcRefs.idOf("special:3"), "历史 special: 前缀不再是合法身份引用");
        assertNull(com.holzyn.actor.common.NpcRefs.idOf("npc:abc"));
        assertNull(com.holzyn.actor.common.NpcRefs.idOf(null));
    }

    /**
     * rankForInjection 降级兜底排序：重要度 desc + 较新优先，top-K 截断。
     */
    @Test
    void rankForInjectionSortsByImportanceThenRecency() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 2, 10, 0);
        List<ActorMemory> all = List.of(
                mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "旧高重要", 5, t0, t0),
                mem(2L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "新高重要", 5, t1, t1),
                mem(3L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "低重要", 2, t1, t1));
        List<ActorMemory> ranked = MemoryInjectorService.rankForInjection(all, 2);
        assertEquals(2, ranked.size());
        assertEquals(Long.valueOf(2L), ranked.get(0).getId(), "重要度相同较新优先");
        assertEquals(Long.valueOf(1L), ranked.get(1).getId());
        assertTrue(MemoryInjectorService.rankForInjection(List.of(), 3).isEmpty());
        assertTrue(MemoryInjectorService.rankForInjection(null, 3).isEmpty());
    }

    /**
     * render 拼接：多段用换行连接；无内容返回空串；null 安全。
     */
    @Test
    void renderJoinsSegments() {
        MemoryInjectorService.Segment a = new MemoryInjectorService.Segment("core", "【核心记忆】L3", "核心段", 3, List.of());
        MemoryInjectorService.Segment b = new MemoryInjectorService.Segment("l2", "【长期记忆】L2", "长期段", 3, List.of());
        // LinkedHashMap 保证段序（Map.of 迭代序不定）
        Map<String, MemoryInjectorService.Segment> ordered = new java.util.LinkedHashMap<>();
        ordered.put("core", a);
        ordered.put("l2", b);
        MemoryInjectorService.InjectionResult r = new MemoryInjectorService.InjectionResult(ordered, "dialog");
        String rendered = service.render(r);
        assertTrue(rendered.contains("核心段"));
        assertTrue(rendered.contains("长期段"));
        assertTrue(rendered.indexOf("核心段") < rendered.indexOf("长期段"));
        assertEquals("", service.render(MemoryInjectorService.InjectionResult.empty("dialog")));
        assertEquals("", service.render(null));
    }

    /**
     * special 双轨合并：存量仅 character_id 的记忆（ref 为 null）并入注入（按 id 去重）。
     */
    @Test
    void legacyCharacterIdMemoriesMerged() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(100L), eq("npc:3"), eq(0)))
                .thenReturn(List.of(mem(1L, "npc:3", ActorMemoryTypes.TYPE_SEMANTIC, "ref 记忆", 4, base, base)));
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), any(), eq(0)))
                .thenReturn(List.of(
                        mem(2L, null, ActorMemoryTypes.TYPE_EPISODIC, "存量 character_id 记忆", 3, base, base),
                        mem(1L, null, ActorMemoryTypes.TYPE_EPISODIC, "与 ref 重复的存量（应跳过）", 5, base, base)));
        MemoryInjectorService.InjectionResult r = service.build(100L, "npc:3", "dialog", null, null);
        MemoryInjectorService.Segment l2 = r.segments().get("l2");
        assertNotNull(l2);
        // id=1 的存量重复被去重 → 命中 2 条（id=1 ref + id=2 legacy）
        assertEquals(2, l2.hits().size());
    }

    // ==================== memory_tier 判定输入真实化（2026-10：query/urgency 兜底装配） ====================

    /**
     * query 空 → 路由「情境兜底」（recentContextQuery），urgency null → 综合紧迫度
     * （situationalUrgency）——修复「所有 memory_tier 调用查询恒（无明确查询）+ 紧迫度恒 1」缺陷。
     */
    @Test
    void buildResolvesQueryAndUrgencyViaRouterWhenNull() {
        MemoryInjectionRouter routerMock = Mockito.mock(MemoryInjectionRouter.class);
        when(routerMock.recentContextQuery(100L, "npc:3")).thenReturn("情境：当前行动去集市买药");
        when(routerMock.situationalUrgency(100L, "npc:3", "dialog")).thenReturn(3);
        // 批6 收口：determineTier 带角色引用（5 参），memory_tier AI 判定前置记忆链段位
        when(routerMock.determineTier(100L, "npc:3", "dialog", "情境：当前行动去集市买药", 3)).thenReturn("MID");
        when(routerMock.budgetOf("MID")).thenReturn(800);
        // 注意力加权与配额：无注意力信号 → 原样返回/回退默认（避免 mock 默认 null/0 干扰行为）
        when(routerMock.adjustTierByAttention(anyLong(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(routerMock.attentionTopKByTier(anyLong(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(3));
        when(routerMock.scoreL1(anyLong(), anyString(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "routerEnabled", true);
        ReflectionTestUtils.setField(service, "router", routerMock);

        service.build(100L, "npc:3", "dialog", null, null);

        // query 空 → 走情境兜底；urgency 空 → 走综合紧迫度
        verify(routerMock).recentContextQuery(100L, "npc:3");
        verify(routerMock).situationalUrgency(100L, "npc:3", "dialog");
        verify(routerMock).determineTier(eq(100L), eq("npc:3"), eq("dialog"),
                eq("情境：当前行动去集市买药"), eq(3));
    }

    /**
     * 显式传入的 query/urgency 具有覆盖优先级（调用方可精确控制，不走兜底）。
     */
    @Test
    void buildKeepsExplicitQueryAndUrgency() {
        MemoryInjectionRouter routerMock = Mockito.mock(MemoryInjectionRouter.class);
        when(routerMock.determineTier(100L, "npc:3", "dialog", "集市几点开门", 1)).thenReturn("LOW");
        when(routerMock.budgetOf("LOW")).thenReturn(300);
        when(routerMock.adjustTierByAttention(anyLong(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(routerMock.attentionTopKByTier(anyLong(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(3));
        when(routerMock.scoreL1(anyLong(), anyString(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "routerEnabled", true);
        ReflectionTestUtils.setField(service, "router", routerMock);

        service.build(100L, "npc:3", "dialog", "集市几点开门", 1, null);

        verify(routerMock, never()).recentContextQuery(anyLong(), anyString());
        verify(routerMock, never()).situationalUrgency(anyLong(), anyString(), anyString());
        verify(routerMock).determineTier(eq(100L), eq("npc:3"), eq("dialog"), eq("集市几点开门"), eq(1));
    }
}
