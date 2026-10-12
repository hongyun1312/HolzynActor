package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微观入账服务单元测试（宏观社会演化系统 M2，domain/world/service，同包）。
 * <p>职责：验证规则映射表（贸易→经济三指标/农作→产量/参战→势力[领袖归属]/治疗→医术/
 * 不匹配不入账）、入账日解析（引擎推进位置）、重大行动 AI 判定叠加（urgency≥4 → 异步判定行）。
 * ⚠️ 异步判定为虚拟线程执行，测试用「同步注册无事务」路径验证不抛异常 + 规则行先行落库。</p>
 */
class MacroLedgerServiceTest {

    private MacroProperties props;
    private ActorWorldMacroDeltaRepository deltaRepository;
    private MacroStateService macroStateService;
    private MacroAiService macroAiService;
    private WorldClockService worldClockService;
    private MacroLedgerService service;

    @BeforeEach
    void setUp() {
        props = new MacroProperties();
        deltaRepository = Mockito.mock(ActorWorldMacroDeltaRepository.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        macroAiService = Mockito.mock(MacroAiService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        service = new MacroLedgerService(props, deltaRepository, macroStateService, macroAiService,
                worldClockService);
        when(worldClockService.currentEngineGameSecond(anyLong())).thenReturn(86400L * 9L); // 第 10 日
        when(deltaRepository.save(any(ActorWorldMacroDelta.class))).thenAnswer(inv -> inv.getArgument(0));
        when(macroStateService.stateOf(anyLong())).thenAnswer(inv -> {
            MacroState st = new MacroState();
            st.setProjectId(inv.getArgument(0));
            // 领袖归属：林大 → 王氏宗族（special:110）
            MacroState.Faction f = new MacroState.Faction();
            f.setName("王氏宗族");
            f.setLeaderRef("special:110");
            st.getFactions().put("王氏宗族", f);
            MacroState.Issue issue = new MacroState.Issue();
            issue.setTopic("时局");
            issue.setIntensity(0.6);
            st.getIssues().add(issue);
            return st;
        });
    }

    /**
     * 贸易行动 → 经济三指标流水（market_activity/currency_supply/tax_revenue）。
     */
    @Test
    void tradeActionWritesEconomyDeltas() {
        service.recordAction(100L, "special:110", "林大", "在集市经营米行", "trade", "集市", 2, 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository, times(3)).save(captor.capture());
        List<ActorWorldMacroDelta> rows = captor.getAllValues();
        assertTrue(rows.stream().anyMatch(d -> "market_activity".equals(d.getIndicator()) && d.getDelta() == 0.6));
        assertTrue(rows.stream().anyMatch(d -> "tax_revenue".equals(d.getIndicator())));
        assertTrue(rows.stream().allMatch(d -> d.getGameDay() == 9L));
        assertTrue(rows.stream().allMatch(d -> "special:110".equals(d.getNpcRef())));
        assertTrue(rows.stream().allMatch(d -> d.getAiJudged() == 0));
    }

    /**
     * 农作行动 → grain_production（farming 关键词判定）。
     */
    @Test
    void farmingActionWritesProductionDelta() {
        service.recordAction(100L, "special:111", "林二", "在农田种田", "work", "农田", 1, 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(captor.capture());
        assertEquals("grain_production", captor.getValue().getIndicator());
        assertEquals(0.5, captor.getValue().getDelta(), 0.001);
    }

    /**
     * 参战行动 → 行动者所属势力影响力（领袖归属解析：林大→王氏宗族）。
     */
    @Test
    void fightActionWritesFactionDeltaByLeader() {
        service.recordAction(100L, "special:110", "林大", "率村民剿匪", "fight", "村外竹林", 4, 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(captor.capture());
        assertEquals("faction", captor.getValue().getTargetMacro());
        assertEquals("王氏宗族", captor.getValue().getIndicator());
    }

    /**
     * 治疗行动 → 医术科技累积（M4 消费）。
     */
    @Test
    void treatActionWritesTechDelta() {
        service.recordCrowdAction(100L, "crowd:50", "林三", "在医馆诊脉开方", "treat", 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(captor.capture());
        assertEquals("tech", captor.getValue().getTargetMacro());
        assertEquals("medicine", captor.getValue().getIndicator());
    }

    /**
     * 不匹配行动（进食/睡眠）→ 零入账（流水不膨胀）。
     * <p>M3 后移动类（type=move/赶路关键词）按文本入账 economy.market_activity——
     * 由 MacroDeltaRegistryTest.moveKeywordsBookToMarketActivity 覆盖，此处保持纯无命中用例。</p>
     */
    @Test
    void unmatchedActionNoDeltas() {
        service.recordAction(100L, "special:110", "林大", "在祠堂小憩片刻", "rest", null, 1, 9L * 86400L);
        service.recordAction(100L, "special:110", "林大", "在厨房做饭", "eat", null, 1, 9L * 86400L);
        verify(deltaRepository, never()).save(any(ActorWorldMacroDelta.class));
    }

    /**
     * 冒烟抓修（2026-09-15）：贸易措辞补全——AI 决策文本常用「经营米行/打理集市/照看铺子」，
     * 原关键词表（经商/买卖/售卖…）未含「经营/米行/集市/铺子」→ 真实冒烟中「经营米行」
     * 行动不入账。无 trade 类型、纯措辞亦应命中映射。
     */
    @Test
    void tradeWordingMapsByKeyword() {
        service.recordAction(100L, "special:110", "林大", "去集市经营米行，照看生意", null, "集市", 3, 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository, times(3)).save(captor.capture());
        List<ActorWorldMacroDelta> saved = captor.getAllValues();
        assertTrue(saved.stream().allMatch(d -> "economy".equals(d.getTargetMacro())));
        assertTrue(saved.stream().anyMatch(d -> "market_activity".equals(d.getIndicator())));
        assertTrue(saved.stream().anyMatch(d -> "currency_supply".equals(d.getIndicator())));
    }

    /**
     * 重大行动（urgency≥4）：规则行先行落库，AI 判定异步叠加（虚拟线程；mock 判定返回 null
     *  → 不叠加，验证规则路径零阻断 + 无异常）。
     */
    @Test
    void majorActionSchedulesAiJudgeAfterRules() {
        service.recordAction(100L, "special:110", "林大", "在集市经营米行", "trade", "集市", 5, 9L * 86400L);
        // 规则行先行（3 条）
        verify(deltaRepository, times(3)).save(any(ActorWorldMacroDelta.class));
        // 异步判定（mock 返回 null）不抛异常、不叠加额外行——等待片刻后仍只有规则行
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(deltaRepository, times(3)).save(any(ActorWorldMacroDelta.class));
    }

    /**
     * 人群入账（程序化作息路径，actionType 为空按文本关键词）。
     */
    @Test
    void crowdTextOnlyActionMapsByKeyword() {
        service.recordCrowdAction(100L, "crowd:41", "铁匠", "在铁匠铺打铁", null, 9L * 86400L);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(captor.capture());
        assertEquals("economy", captor.getValue().getTargetMacro());
    }

    /**
     * 入账日解析：无 gameSecond 时取引擎推进位置（第 10 日）。
     */
    @Test
    void dayResolutionFallsBackToEnginePosition() {
        service.recordCrowdAction(100L, "crowd:41", "铁匠", "在铁匠铺打铁", "work", null);
        ArgumentCaptor<ActorWorldMacroDelta> captor = ArgumentCaptor.forClass(ActorWorldMacroDelta.class);
        verify(deltaRepository).save(captor.capture());
        assertEquals(9L, captor.getValue().getGameDay());
    }

    /**
     * 宏观总开关关闭 → 零入账。
     */
    @Test
    void disabledWritesNothing() {
        props.setEnabled(false);
        service.recordAction(100L, "special:110", "林大", "在集市经营米行", "trade", null, 2, 9L * 86400L);
        verify(deltaRepository, never()).save(any(ActorWorldMacroDelta.class));
    }
}
