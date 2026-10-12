package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.memory.service.MemoryInjectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentMemoryService（B4 F5 行动/事件→记忆 + F18 项目级记忆写入方）单元测试
 * （与实现同包，domain/agent/memory）。
 * <p>职责：验证非对话经历沉淀（special 双轨/crowd 仅 ref/去重/预算淘汰含 ref）、
 * 项目级大事记写入（characterId=null+ref=null/去重/预算淘汰）、检索
 * （recency 指数衰减评分/relevance 文本降级/命中刷新 last_access_at/access_count）、静态评分函数。</p>
 * <p>测试要点对齐系统框架 V2.0 §4.2：多类存取、ref 双类型、recency 边界、relevance 降级、
 * 行动沉淀去重（复用 isDuplicate）、预算淘汰含 ref、项目级记忆写入。</p>
 */
class AgentMemoryServiceTest {

    private ActorMemoryRepository memoryRepository;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        service = new AgentMemoryService(memoryRepository);
        // @Value 字段在单元测试中不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "budget", 50);
        ReflectionTestUtils.setField(service, "dedupThreshold", 0.6);
        ReflectionTestUtils.setField(service, "retrievalTopK", 8);
        ReflectionTestUtils.setField(service, "recencyDecayHours", 24);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== recordObservation（F5 行动经历） ====================

    /**
     * 特殊 NPC（special:{id}）经历记忆双轨落库：character_id + character_ref 同时写入。
     */
    @Test
    void recordObservationForSpecialWritesDualTrack() {
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(anyLong(), anyString(), eq(0)))
                .thenReturn(List.of());

        service.recordObservation(100L, "npc:3", "前往城东市场采购药材", 4);

        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository).save(cap.capture());
        ActorMemory m = cap.getValue();
        assertEquals(Long.valueOf(100L), m.getProjectId());
        assertEquals(Long.valueOf(3L), m.getCharacterId(), "特殊 NPC 应双写 character_id");
        assertEquals("npc:3", m.getCharacterRef(), "特殊 NPC 应双写 character_ref");
        assertEquals("episodic", m.getType(), "M1 起 observation→episodic");
        assertEquals("前往城东市场采购药材", m.getContent());
        assertEquals(Integer.valueOf(4), m.getImportance());
    }

    /**
     * 普通型 NPC 经历记忆：单表后会一并写 character_id（={@code actor_profile.id}）与 character_ref。
     * <p><b>G1a 语义变化</b>：B4 双轨时代"普通型只有 character_ref、character_id 为 null"，是因为普通型
     * 行在另一张表（{@code actor_profile_ordinary.id} 与 {@code actor_profile.id} 不同源，写入会串号）；
     * 单主表后 {@code npc:{id}} 的数字部分<b>就是</b>主键，故 character_id 恒可写——口径见
     * {@code AgentMemoryService} 的 {@code NpcRefs.idOf(ref)}。</p>
     */
    @Test
    void recordObservationForCommonNpcWritesBothTracks() {
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(anyLong(), anyString(), eq(0)))
                .thenReturn(List.of());

        service.recordObservation(100L, "npc:12", "在集市吆喝叫卖", 3);

        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository).save(cap.capture());
        ActorMemory m = cap.getValue();
        assertEquals(Long.valueOf(12L), m.getCharacterId(),
                "G1a 单表后 character_id 恒可写（= actor_profile.id），不再有普通型 null 特例");
        assertEquals("npc:12", m.getCharacterRef(), "统一引用 npc:{id}");
        assertEquals("episodic", m.getType(), "M1 起 observation→episodic");
    }

    /**
     * 去重（复用 MemoryService.isDuplicate）：与已有记忆文本重叠度过高 → 丢弃不落库。
     */
    @Test
    void recordObservationDedupedAgainstExisting() {
        ActorMemory existing = mem(1L, 5, LocalDateTime.now(), "前往城东市场采购药材");
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:3", 0))
                .thenReturn(List.of(existing));

        service.recordObservation(100L, "npc:3", "前往城东市场采购药材（目标：城东市场）", 5);

        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 预算淘汰含 ref（复用 MemoryService.evictToBudget）：同 ref 记忆超预算软删低价值旧记忆。
     */
    @Test
    void recordObservationEvictsOverBudgetIncludingRef() {
        // 内存仓库：写库后 reload 可见新增（模拟真实 DB），便于验证「超预算淘汰」
        List<ActorMemory> store = new java.util.ArrayList<>(List.of(
                mem(1L, 1, LocalDateTime.now().minusDays(2), "旧低价值"),
                mem(2L, 3, LocalDateTime.now().minusHours(1), "较高价值")));
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:12", 0))
                .thenAnswer(inv -> store);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> {
            ActorMemory m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(100L);
                store.add(m);
            }
            return m;
        });
        // 预算 2：写入第 3 条后超预算 1 条 → 淘汰「重要度低+旧」
        ReflectionTestUtils.setField(service, "budget", 2);

        service.recordObservation(100L, "npc:12", "新经历", 4);

        // 第一次 save=新记忆；第二次 save=淘汰软删（旧低价值 deleted=1）
        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository, times(2)).save(cap.capture());
        List<ActorMemory> all = cap.getAllValues();
        assertEquals("新经历", all.get(0).getContent());
        ActorMemory evicted = all.get(all.size() - 1);
        assertEquals(Integer.valueOf(1), evicted.getDeleted(), "超预算应软删低价值旧记忆");
        assertEquals("旧低价值", evicted.getContent());
    }

    // ==================== recordProjectMemory（F18 项目级大事记） ====================

    /**
     * 项目级大事记：characterId=null + character_ref=null（所有角色可见），kind=fact。
     */
    @Test
    void recordProjectMemoryWritesProjectLevel() {
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(anyLong(), eq(0)))
                .thenReturn(List.of());

        service.recordProjectMemory(100L, "第3日：城东集市发生异动，商旅传闻四起。", 4);

        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository).save(cap.capture());
        ActorMemory m = cap.getValue();
        assertEquals(Long.valueOf(100L), m.getProjectId());
        assertNull(m.getCharacterId(), "项目级记忆 characterId 必须为 null");
        assertNull(m.getCharacterRef(), "项目级记忆 character_ref 必须为 null");
        assertEquals("semantic", m.getType(), "M1 起 fact→semantic");
        assertEquals(Integer.valueOf(4), m.getImportance());
    }

    /**
     * 项目级大事记去重：与已有项目级记忆文本重叠 → 丢弃不落库。
     */
    @Test
    void recordProjectMemoryDeduped() {
        ActorMemory existing = mem(9L, 4, LocalDateTime.now(), "第3日：城东集市发生异动，商旅传闻四起。");
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of(existing));

        service.recordProjectMemory(100L, "第3日：城东集市发生异动，商旅传闻四起。", 4);

        verify(memoryRepository, never()).save(any(ActorMemory.class));
    }

    /**
     * 项目级预算淘汰：超预算软删低价值项目级记忆（F18 与既有项目级记忆共用预算）。
     */
    @Test
    void recordProjectMemoryEvictsOverBudget() {
        List<ActorMemory> store = new java.util.ArrayList<>(List.of(
                mem(1L, 1, LocalDateTime.now().minusDays(1), "旧日常琐事"),
                mem(2L, 5, LocalDateTime.now(), "重要事件")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenAnswer(inv -> store);
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> {
            ActorMemory m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(100L);
                store.add(m);
            }
            return m;
        });
        ReflectionTestUtils.setField(service, "budget", 2);

        service.recordProjectMemory(100L, "新增大事记", 4);

        // 第一次 save=新记忆；第二次 save=淘汰软删（旧日常琐事 deleted=1）
        ArgumentCaptor<ActorMemory> cap = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository, times(2)).save(cap.capture());
        List<ActorMemory> all = cap.getAllValues();
        assertEquals("新增大事记", all.get(0).getContent());
        ActorMemory evicted = all.get(all.size() - 1);
        assertEquals(Integer.valueOf(1), evicted.getDeleted());
        assertEquals("旧日常琐事", evicted.getContent());
    }

    // ==================== 检索（Smallville 评分） ====================

    /**
     * recency 指数衰减：无基准=0；刚发生=1；衰减半程后≈e^-1；负龄钳制为 1。
     */
    @Test
    void recencyScoreDecaysExponentially() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        assertEquals(0, AgentMemoryService.recencyScore(null, now, 24), 1e-9, "无基准=0");
        assertEquals(1, AgentMemoryService.recencyScore(now, now, 24), 1e-9, "刚发生=1");
        assertEquals(1, AgentMemoryService.recencyScore(now.plusHours(1), now, 24), 1e-9, "now 早于基准钳制 1");
        double afterDay = AgentMemoryService.recencyScore(now.minusHours(24), now, 24);
        assertEquals(Math.exp(-1), afterDay, 1e-9, "24 小时=衰减半程 e^-1");
        double after2Days = AgentMemoryService.recencyScore(now.minusHours(48), now, 24);
        assertTrue(after2Days < afterDay, "越久远新近度越低");
    }

    /**
     * 检索总评分：重要度主导 + recency + relevance 加分。
     */
    @Test
    void retrievalScoreWeights() {
        // 高重要度旧记忆 vs 低重要度新记忆：重要度主导（2×5 vs 2×1+3=5 → 高重要度仍胜）
        double highOld = AgentMemoryService.retrievalScore(5, 0.1, 0);
        double lowNew = AgentMemoryService.retrievalScore(1, 1.0, 0);
        assertTrue(highOld > lowNew, "重要度主导：高重要度旧记忆仍高于低重要度新记忆");
        // 相关性加分：同重要度同新近度下 query 相关的更高
        double related = AgentMemoryService.retrievalScore(3, 0.5, 0.8);
        double unrelated = AgentMemoryService.retrievalScore(3, 0.5, 0);
        assertTrue(related > unrelated, "relevance 加分");
    }

    /**
     * 检索（recency 衰减 + relevance 降级）：按评分排序取 top-K，命中刷新 last_access_at/access_count。
     */
    @Test
    void retrieveRanksByScoreAndTouches() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorMemory imp5 = mem(1L, 5, now.minusHours(1), "昨日与城主密谈边境战事");
        ActorMemory imp1 = mem(2L, 1, now.minusMinutes(5), "今晨在集市买了三个馒头");
        ActorMemory imp3 = mem(3L, 3, now.minusDays(2), "三天前参与城防巡视");
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:3", 0))
                .thenReturn(List.of(imp5, imp1, imp3));

        List<ActorMemory> result = service.retrieve(100L, "npc:3", "密谈边境战事", 2);

        assertEquals(2, result.size(), "topK=2");
        assertEquals("昨日与城主密谈边境战事", result.get(0).getContent(),
                "重要度最高且 query 相关（relevance 命中）应排第一");
        // 命中刷新 last_access_at/access_count
        ArgumentCaptor<ActorMemory> touched = ArgumentCaptor.forClass(ActorMemory.class);
        verify(memoryRepository, times(2)).save(touched.capture());
        for (ActorMemory t : touched.getAllValues()) {
            assertEquals(1, t.getAccessCount());
        }
    }

    /**
     * 检索无 query → 仅重要度+新近排序；空参/非法 ref → 空列表。
     */
    @Test
    void retrieveWithoutQueryAndInvalidRef() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorMemory imp5 = mem(1L, 5, now.minusDays(3), "旧高重要");
        ActorMemory imp1 = mem(2L, 1, now.minusMinutes(1), "新低重要");
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:9", 0))
                .thenReturn(List.of(imp5, imp1));

        List<ActorMemory> result = service.retrieve(100L, "npc:9", null, 5);
        assertEquals(2, result.size());
        assertEquals("旧高重要", result.get(0).getContent(), "无 query 时重要度优先");

        assertTrue(service.retrieve(null, "npc:1", "q", 5).isEmpty());
        assertTrue(service.retrieve(100L, "  ", "q", 5).isEmpty());
        verify(memoryRepository, never()).findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(eq(1L), anyString(), eq(0));
    }

    /**
     * 特殊角色检索兼容存量：仅 character_id 的旧记忆（B4 前无 ref）一并纳入检索。
     */
    @Test
    void retrieveMergesLegacyCharacterIdMemories() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorMemory legacy = mem(9L, 4, now.minusHours(2), "存量旧记忆仅 character_id");
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:3", 0))
                .thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0))).thenReturn(List.of(legacy));

        List<ActorMemory> result = service.retrieve(100L, "npc:3", null, 5);
        assertEquals(1, result.size());
        assertEquals("存量旧记忆仅 character_id", result.get(0).getContent());
    }

    // ==================== M5 分场景注入器接入（MemoryInjectorService 字段注入） ====================

    /**
     * M5 注入器可用：memoryContext 对话场景走统一注入器（build + render），不再走旧 rankForInjection 路径。
     */
    @Test
    void memoryContextUsesInjectorWhenAvailable() {
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        MemoryInjectorService injector = Mockito.mock(MemoryInjectorService.class);
        ReflectionTestUtils.setField(service, "injectorService", injector);
        MemoryInjectorService.InjectionResult result = new MemoryInjectorService.InjectionResult(
                Map.of("l2", new MemoryInjectorService.Segment("l2", "【长期记忆】L2", "【长期记忆】注入文本", 10, List.of())),
                "dialog");
        when(injector.build(eq(100L), eq("npc:3"), eq("dialog"), isNull(), isNull())).thenReturn(result);
        when(injector.render(result)).thenReturn("【长期记忆】注入文本");

        String out = service.memoryContext(1L, 100L, 3L, null);
        assertEquals("【长期记忆】注入文本", out);
        verify(injector).build(100L, "npc:3", "dialog", null, null);
        verify(memoryRepository, never())
                .findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(anyLong(), anyLong(), anyList(), eq(0));
    }

    /**
     * M5 注入器返回空结果（不可用/无内容）：回退旧 rankForInjection 路径保持行为。
     */
    @Test
    void memoryContextFallsBackWhenInjectorReturnsEmpty() {
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        // 旧路径 rankForInjection 用 topK 字段（与 retrieve 的 retrievalTopK 独立）
        ReflectionTestUtils.setField(service, "topK", 8);
        MemoryInjectorService injector = Mockito.mock(MemoryInjectorService.class);
        ReflectionTestUtils.setField(service, "injectorService", injector);
        when(injector.build(anyLong(), anyString(), anyString(), isNull(), any())).thenReturn(
                MemoryInjectorService.InjectionResult.empty("dialog"));
        when(injector.render(any())).thenReturn("");
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
                eq(100L), eq(3L), anyList(), eq(0)))
                .thenReturn(List.of(mem(1L, 5, now, "罗小黑擅长酿酒")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(anyLong(), eq(0)))
                .thenReturn(List.of());

        String out = service.memoryContext(1L, 100L, 3L, null);
        assertTrue(out.contains("罗小黑擅长酿酒"), "注入器空结果应回退旧路径注入角色级记忆");
    }

    /**
     * 带 query 重载：query 透传注入器（tier 判定与 L2 语义检索的情境文本）——
     * 修复「对话注入空查询→memory_tier 空跑 + L2 相关性=0」缺陷（2026-09-30 长跑实测）。
     */
    @Test
    void memoryContextThreadsQueryToInjector() {
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        MemoryInjectorService injector = Mockito.mock(MemoryInjectorService.class);
        ReflectionTestUtils.setField(service, "injectorService", injector);
        MemoryInjectorService.InjectionResult result = new MemoryInjectorService.InjectionResult(
                Map.of("l2", new MemoryInjectorService.Segment("l2", "【长期记忆】L2", "【长期记忆】注入文本", 10, List.of())),
                "dialog");
        when(injector.build(eq(100L), eq("npc:3"), eq("dialog"), eq("今日集市上发生了什么"), isNull()))
                .thenReturn(result);
        when(injector.render(result)).thenReturn("【长期记忆】注入文本");

        String out = service.memoryContext(1L, 100L, 3L, "今日集市上发生了什么", null);

        assertEquals("【长期记忆】注入文本", out);
        verify(injector).build(100L, "npc:3", "dialog", "今日集市上发生了什么", null);
    }

    // ==================== 辅助 ====================

    /** 构造记忆实体 */
    private ActorMemory mem(long id, int importance, LocalDateTime createdAt, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        m.setContent(content);
        m.setDeleted(0);
        return m;
    }
}
