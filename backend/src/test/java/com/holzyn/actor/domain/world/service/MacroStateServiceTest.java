package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroSnapshotRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观状态服务单元测试（宏观社会演化系统 M1/M3，domain/world/service，同包）。
 * <p>职责：验证世界观默认状态（三百口人/模板骨架势力/议题/四维科技）、模板语义
 * （M3 双维度模板抽象）、手动初始化覆盖、序列化往返、快照幂等 upsert、滚动上限清理、
 * 入账聚合、具名 NPC 选取确定性。</p>
 */
class MacroStateServiceTest {

    private ActorWorldMacroSnapshotRepository snapshotRepository;
    private ActorWorldMacroDeltaRepository deltaRepository;
    private ActorWorldMacroEventRepository eventRepository;
    private ActorCharacterRepository npcRepository;
    private ActorCharacterRepository characterRepository;
    private WorldClockService worldClockService;
    private ActorWorldSettingRepository worldSettingRepository;
    private MacroTemplateService macroTemplateService;
    private MacroStateService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = Mockito.mock(ActorWorldMacroSnapshotRepository.class);
        deltaRepository = Mockito.mock(ActorWorldMacroDeltaRepository.class);
        eventRepository = Mockito.mock(ActorWorldMacroEventRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        worldSettingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        macroTemplateService = new MacroTemplateService();
        service = new MacroStateService(new MacroProperties(), snapshotRepository, deltaRepository,
                eventRepository, npcRepository, worldClockService,
                worldSettingRepository, macroTemplateService, new ObjectMapper());
        when(snapshotRepository.findTopByProjectIdAndTickTypeOrderByGameDayDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(snapshotRepository.save(any(ActorWorldMacroSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(worldClockService.currentEngineGameSecond(anyLong())).thenReturn(86400L * 30L); // 第 31 日
    }

    /**
     * 世界观默认状态：人口 300、模板骨架势力、初始议题/信仰/禁忌、四维科技。
     */
    @Test
    void defaultStateFollowsWorldSetting() {
        MacroState st = service.defaultState(100L);
        assertEquals(300L, st.getPopulation().getTotal());
        assertEquals(5, st.getFactions().size());
        assertTrue(st.getFactions().containsKey("村中宗族"));
        assertTrue(st.getFactions().containsKey("往来商帮"));
        assertEquals(3, st.getIssues().size());
        assertEquals(0.85, st.getBeliefs().getOrDefault("神祇信仰", 0.0), 0.001);
        assertTrue(st.getTechLevels().containsKey("medicine"));
        // 模板骨架势力无关系基线（关系图由运行时演化建立）
        assertTrue(st.getFactions().get("村中宗族").getRelations() != null);
    }

    /**
     * 无快照 → 惰性初始化落 init 快照行（当前游戏日）。
     */
    @Test
    void lazyInitWritesInitSnapshot() {
        MacroState st = service.stateOf(100L);
        assertEquals(300L, st.getPopulation().getTotal());
        assertEquals(30L, st.getGameDay());
        verify(snapshotRepository).save(any(ActorWorldMacroSnapshot.class));
    }

    /**
     * 手动初始化 + 覆盖参数（人口/粮价基准）。
     */
    @Test
    void initializeWithOverrides() {
        MacroState st = service.initialize(100L, Map.of("population", 500L, "grainPriceBase", 150.0));
        assertEquals(500L, st.getPopulation().getTotal());
        assertEquals(150.0, st.getEconomy().getGrainPrice(), 0.001);
    }

    /**
     * 序列化往返：状态 → JSON → 状态（五段字段保真）。
     */
    @Test
    void serializeRoundTrip() {
        MacroState st = service.defaultState(100L);
        String json = service.toJson(st);
        MacroState back = service.fromJson(json);
        assertNotNull(back);
        assertEquals(st.getPopulation().getTotal(), back.getPopulation().getTotal());
        assertEquals(st.getEconomy().getGrainStock(), back.getEconomy().getGrainStock(), 0.001);
        assertEquals(st.getFactions().size(), back.getFactions().size());
        assertEquals(st.getIssues().size(), back.getIssues().size());
    }

    /**
     * 快照幂等 upsert（uk 命中更新不新建）；summary 简报含人口/粮价。
     */
    @Test
    void snapshotUpsertAndSummary() {
        ActorWorldMacroSnapshot existing = new ActorWorldMacroSnapshot();
        existing.setId(1L);
        when(snapshotRepository.findByProjectIdAndGameDayAndTickType(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.of(existing));
        MacroState st = service.defaultState(100L);
        st.setGameDay(5L);
        service.saveSnapshot(100L, st, ActorWorldMacroSnapshot.TICK_DAILY, 5L * 86400L);
        verify(snapshotRepository).save(any(ActorWorldMacroSnapshot.class));
        String summary = service.buildSummary(st, ActorWorldMacroSnapshot.TICK_DAILY);
        assertTrue(summary.contains("人口 300"));
        assertTrue(summary.contains("粮价"));
    }

    /**
     * 滚动上限：超出 maxPerProject 时删最旧行（count 顺序桩：1001 → 1000 递减，防死循环）。
     */
    @Test
    void snapshotRollingTrim() {
        when(snapshotRepository.countByProjectId(anyLong())).thenReturn(1001L, 1000L);
        when(snapshotRepository.findTopByProjectIdOrderByGameDayAsc(anyLong()))
                .thenReturn(Optional.of(new ActorWorldMacroSnapshot()));
        MacroState st = service.defaultState(100L);
        st.setGameDay(7L);
        service.saveSnapshot(100L, st, ActorWorldMacroSnapshot.TICK_DAILY, 7L * 86400L);
        verify(snapshotRepository).delete(any(ActorWorldMacroSnapshot.class));
    }

    /**
     * 入账聚合：区间 + 目标子系统 → 指标求和。
     */
    @Test
    void aggregateDeltasByIndicator() {
        var d1 = new com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta();
        d1.setIndicator("market_activity");
        d1.setDelta(0.6);
        var d2 = new com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta();
        d2.setIndicator("market_activity");
        d2.setDelta(0.4);
        var d3 = new com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta();
        d3.setIndicator("tax_revenue");
        d3.setDelta(0.2);
        when(deltaRepository.findByProjectIdAndGameDayBetweenAndTargetMacro(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(List.of(d1, d2, d3));
        Map<String, Double> agg = service.aggregateDeltas(100L, 4L, 4L, "economy");
        assertEquals(1.0, agg.getOrDefault("market_activity", 0.0), 0.001);
        assertEquals(0.2, agg.getOrDefault("tax_revenue", 0.0), 0.001);
    }

    /**
     * 读接口组装：stateVO 核心字段。
     */
    @Test
    void stateVOAssembles() {
        var vo = service.stateVO(100L);
        assertEquals(300L, vo.getPopulation());
        assertTrue(vo.getGrainPrice() > 0, "初始粮价应为模型推导正值");
        assertNotNull(vo.getIndicators());
        assertEquals("村中宗族", vo.getTopFaction());
        assertEquals("天地异象", vo.getTopIssue());
    }
}
