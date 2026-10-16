package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionLog;
import com.holzyn.actor.domain.agent.runtime.entity.ActorWorldEngineTickStat;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DecisionLedgerService（P0-03 决策源可追溯 · 度量基座；P0-02 诚实化止血落库口）单元测试。
 * <p>职责：验证决策点台账落库（AI/兜底/预算降级/缺失来源列）、缺失留痕（编年史显式
 * 「该时段无决策」+ 不伪造）、tick 预算统计持久化（窗口各来源计数）、日统计聚合
 * （审计 P0-02 验收 1「三类之和 = 该日决策点总数」）。</p>
 */
class DecisionLedgerServiceTest {

    private ActorDecisionLogRepository decisionLogRepository;
    private ActorWorldEngineTickStatRepository tickStatRepository;
    private ActorWorldChronicleRepository chronicleRepository;
    private DecisionLedgerService service;

    @BeforeEach
    void setUp() {
        decisionLogRepository = Mockito.mock(ActorDecisionLogRepository.class);
        tickStatRepository = Mockito.mock(ActorWorldEngineTickStatRepository.class);
        chronicleRepository = Mockito.mock(ActorWorldChronicleRepository.class);
        service = new DecisionLedgerService(decisionLogRepository, tickStatRepository, chronicleRepository);
    }

    @Test
    void recordWritesRowWithSourceColumn() {
        service.record(1L, 10L, "npc:10", 3600L, DecisionSource.FALLBACK_PROGRAMMATIC, "继续当前行为", "engine_tick");

        ArgumentCaptor<ActorDecisionLog> cap = ArgumentCaptor.forClass(ActorDecisionLog.class);
        verify(decisionLogRepository, times(1)).save(cap.capture());
        ActorDecisionLog row = cap.getValue();
        assertEquals(1L, row.getProjectId());
        assertEquals(10L, row.getCharacterId());
        assertEquals("npc:10", row.getCharacterRef());
        assertEquals(Long.valueOf(3600L), row.getGameSecond());
        assertEquals(Long.valueOf(0L), row.getGameDay(), "3600 游戏秒 = 第 0 游戏日");
        assertEquals("FALLBACK_PROGRAMMATIC", row.getDecisionSource(), "来源列=枚举名");
        assertEquals("继续当前行为", row.getActionText());
        assertEquals("engine_tick", row.getTriggerType());
    }

    @Test
    void recordNullSourceFallsBackToAi() {
        service.record(1L, 10L, "npc:10", 7200L, null, "前往集市", "engine_tick");
        ArgumentCaptor<ActorDecisionLog> cap = ArgumentCaptor.forClass(ActorDecisionLog.class);
        verify(decisionLogRepository, times(1)).save(cap.capture());
        assertEquals("AI", cap.getValue().getDecisionSource(), "null 来源=旧路径未打标 → 视为 AI（台账完整性）");
    }

    @Test
    void recordMissingWritesLedgerAndChronicleWithoutFabrication() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(10L);
        ch.setProjectId(1L);
        ch.setName("沈砚秋");

        service.recordMissing(1L, ch, 3600L, "AI 不可用");

        // 台账行：DECISION_MISSING
        ArgumentCaptor<ActorDecisionLog> ledgerCap = ArgumentCaptor.forClass(ActorDecisionLog.class);
        verify(decisionLogRepository, times(1)).save(ledgerCap.capture());
        assertEquals("DECISION_MISSING", ledgerCap.getValue().getDecisionSource());
        assertTrue(ledgerCap.getValue().getActionText().contains("AI 不可用"), "缺失行带原因摘要");
        // 编年史：显式「该时段无决策」+ 来源列 DECISION_MISSING（P0-02 验收 2：编年史出现显式缺失记录）
        ArgumentCaptor<ActorWorldChronicle> chronicleCap = ArgumentCaptor.forClass(ActorWorldChronicle.class);
        verify(chronicleRepository, times(1)).save(chronicleCap.capture());
        ActorWorldChronicle entry = chronicleCap.getValue();
        assertEquals("DECISION_MISSING", entry.getDecisionSource());
        assertEquals("decision_missing", entry.getSource());
        assertTrue(entry.getContent().contains("本时段无决策记录"), "显式缺失记录内容");
        assertTrue(entry.getContent().contains("沈砚秋"), "记录当事人");
        // 不可污染性：不伪造任何新活动文本（无 programmatic 活动写入）
        assertFalse(entry.getContent().contains("歇息就寝"), "缺失记录不携带伪造活动文本");
    }

    @Test
    void persistTickStatAggregatesSourcesByWindow() {
        when(decisionLogRepository.countBySourceBetween(eq(1L), eq(0L), eq(86400L))).thenReturn(List.of(
                src("AI", 20L),
                src("AI_RETRY", 3L),
                src("FALLBACK_PROGRAMMATIC", 5L),
                src("BUDGET_EXHAUSTED", 2L),
                src("DECISION_MISSING", 1L)));

        service.persistTickStat(1L, 0L, 86400L, 8, 7);

        ArgumentCaptor<ActorWorldEngineTickStat> cap = ArgumentCaptor.forClass(ActorWorldEngineTickStat.class);
        verify(tickStatRepository, times(1)).save(cap.capture());
        ActorWorldEngineTickStat stat = cap.getValue();
        assertEquals(Long.valueOf(86400L), stat.getGameSecond());
        assertEquals(Integer.valueOf(8), stat.getBudgetTotal(), "预算总量持久化（防 endTick 后丢失）");
        assertEquals(Integer.valueOf(7), stat.getBudgetUsed());
        assertEquals(Integer.valueOf(23), stat.getAiDecisions(), "AI+AI_RETRY 归 AI");
        assertEquals(Integer.valueOf(5), stat.getFallbackDecisions(), "FALLBACK_PROGRAMMATIC 归兜底");
        assertEquals(Integer.valueOf(2), stat.getExhaustedDecisions(), "BUDGET_EXHAUSTED 独立计数");
        assertEquals(Integer.valueOf(1), stat.getMissingDecisions(), "DECISION_MISSING 计数");
    }

    @Test
    void dayStatsSumsThreeCategoriesToTotal() {
        when(decisionLogRepository.countByProjectIdAndGameDay(1L, 3L)).thenReturn(31L);
        when(decisionLogRepository.countBySource(1L, 3L)).thenReturn(List.of(
                src("AI", 20L),
                src("AI_RETRY", 3L),
                src("FALLBACK_PROGRAMMATIC", 5L),
                src("BUDGET_EXHAUSTED", 2L),
                src("DECISION_MISSING", 1L)));

        Map<String, Object> stats = service.dayStats(1L, 3L);

        assertEquals(31L, stats.get("total"), "三类之和 = 该日决策点总数（20+3+5+2+1）");
        assertEquals(23L, stats.get("ai"));
        assertEquals(7L, stats.get("fallback"), "兜底 = 程序化兜底 + 预算耗尽降级");
        assertEquals(1L, stats.get("missing"));
        assertEquals(23L + 7L + 1L, stats.get("total"), "ai+fallback+missing = total（验收 1）");
        assertNotNull(stats.get("bySource"));
        assertNotNull(stats.get("aiRatio"));
    }

    /** SourceCount 投影实现（测试用）。 */
    private static ActorDecisionLogRepository.SourceCount src(String source, long cnt) {
        return new ActorDecisionLogRepository.SourceCount() {
            @Override
            public String getSource() {
                return source;
            }

            @Override
            public Long getCnt() {
                return cnt;
            }
        };
    }
}
