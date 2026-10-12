package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BrainStrategyRouter 可插拔决策策略路由器单元测试（P2-3）。
 * <p>验证：链按 priority 降序组链、链式责任首中即胜（高优先级命中后低优先级不再尝试）、
 * applies 按角色类型/脑力档位过滤、链空返回 null、命中写决策链路 trace（stepType=brain_strategy）、
 * 产物携带命中策略/优先级/步骤记录、新策略注册即入链（O6 注入点）。</p>
 */
class BrainStrategyRouterTest {

    private final Consumer<AiCallTrace>[] capturedSink = new Consumer[1];
    private final List<AiCallTrace> traces = new ArrayList<>();

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    private void captureTraces() {
        capturedSink[0] = traces::add;
        AiCallContext.setSink(capturedSink[0]);
    }

    /** 可编程测试策略（固定行为/命中开关）。 */
    private static class StubStrategy implements BrainStrategy {
        private final String name;
        private final int priority;
        private final boolean hit;
        private final NpcKind kind;
        private final boolean programmaticOnly;
        private final boolean skipProgrammatic;

        StubStrategy(String name, int priority, boolean hit) {
            this(name, priority, hit, null, false, false);
        }

        StubStrategy(String name, int priority, boolean hit, NpcKind kind, boolean programmaticOnly) {
            this(name, priority, hit, kind, programmaticOnly, false);
        }

        StubStrategy(String name, int priority, boolean hit, NpcKind kind,
                     boolean programmaticOnly, boolean skipProgrammatic) {
            this.name = name;
            this.priority = priority;
            this.hit = hit;
            this.kind = kind;
            this.programmaticOnly = programmaticOnly;
            this.skipProgrammatic = skipProgrammatic;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean applies(DecisionContext ctx) {
            if (kind != null && kind != ctx.kind()) {
                return false;
            }
            if (programmaticOnly && !"programmatic".equals(ctx.brainLevel())) {
                return false;
            }
            if (skipProgrammatic && "programmatic".equals(ctx.brainLevel())) {
                return false;
            }
            return true;
        }

        @Override
        public Optional<Map<String, Object>> decide(DecisionContext ctx) {
            if (!hit) {
                return Optional.empty();
            }
            return Optional.of(Map.of("action", "策略动作-" + name, "actionType", "custom"));
        }
    }

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setType("special");
        ch.setCurrentActivity("在茶馆喝茶");
        return ch;
    }

    private DecisionContext ctx(NpcKind kind, String brainLevel) {
        return new DecisionContext(100L, character(), 1L, kind, brainLevel, "情境", null, null, false);
    }

    /** 链按 priority 降序尝试（高优先级先命中）。 */
    @Test
    void chainTriesByPriorityDescendingAndFirstHitWins() {
        AtomicInteger called = new AtomicInteger();
        BrainStrategy high = new StubStrategy("high", 100, true) {
            @Override
            public Optional<Map<String, Object>> decide(DecisionContext ctx) {
                called.incrementAndGet();
                return super.decide(ctx);
            }
        };
        BrainStrategy low = new StubStrategy("low", 10, true) {
            @Override
            public Optional<Map<String, Object>> decide(DecisionContext ctx) {
                called.incrementAndGet();
                return Optional.of(Map.of("action", "低优先级不应被调", "actionType", "custom"));
            }
        };
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(low, high));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out);
        assertEquals("high", out.hitStrategy(), "高优先级先命中");
        assertEquals(100, out.priority());
        assertEquals("策略动作-high", out.decision().get("action"));
        assertEquals(1, called.get(), "命中后低优先级不再尝试（链式责任首中即胜）");
        assertEquals("high✓命中", out.steps().get(out.steps().size() - 1));
    }

    /** 未命中则依次尝试直到命中（链式责任）。 */
    @Test
    void chainFallsThroughUntilHit() {
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("a", 90, false),
                new StubStrategy("b", 60, false),
                new StubStrategy("c", 30, true)));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out);
        assertEquals("c", out.hitStrategy());
        assertEquals(3, out.steps().size(), "前两步未命中记录在案");
        assertTrue(out.steps().contains("a×未命中"));
        assertTrue(out.steps().contains("b×未命中"));
    }

    /** 全部未命中 → 链空返回 null（调用方兜底）。 */
    @Test
    void chainEmptyReturnsNull() {
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("a", 90, false),
                new StubStrategy("b", 60, false)));

        assertNull(router.decide(ctx(NpcKind.SPECIAL, "full")));
    }

    /** applies 按角色类型过滤：COMMON 上下文不进入 SPECIAL 专属策略。 */
    @Test
    void appliesFiltersByKind() {
        BrainStrategy specialOnly = new StubStrategy("special_only", 90, true, NpcKind.SPECIAL, false);
        BrainStrategy commonOnly = new StubStrategy("common_only", 80, true, NpcKind.COMMON, false);
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(specialOnly, commonOnly));

        DecisionOutcome common = router.decide(ctx(NpcKind.COMMON, null));
        assertEquals("common_only", common.hitStrategy(), "COMMON 链只含 COMMON 策略");

        DecisionOutcome special = router.decide(ctx(NpcKind.SPECIAL, "full"));
        assertEquals("special_only", special.hitStrategy(), "SPECIAL 链只含 SPECIAL 策略");
    }

    /** applies 按脑力档位过滤：programmatic 档跳过非 programmatic 策略。 */
    @Test
    void appliesFiltersByBrainLevel() {
        BrainStrategy progOnly = new StubStrategy("prog", 50, true, NpcKind.SPECIAL, true);
        BrainStrategy nonProg = new StubStrategy("deliberate", 60, true, NpcKind.SPECIAL, false, true);
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(progOnly, nonProg));

        DecisionOutcome prog = router.decide(ctx(NpcKind.SPECIAL, "programmatic"));
        assertEquals("prog", prog.hitStrategy(), "programmatic 档跳过深思策略");
        assertFalse(prog.steps().contains("deliberate×未命中"), "不适用策略不进入链");

        DecisionOutcome full = router.decide(ctx(NpcKind.SPECIAL, "full"));
        assertEquals("deliberate", full.hitStrategy(), "full 档深思策略优先");
    }

    /** 命中写决策链路 trace（stepType=brain_strategy）。 */
    @Test
    void hitRecordsDecisionTraceStep() {
        captureTraces();
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("hit", 80, true),
                new StubStrategy("miss", 40, false)));

        router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertFalse(traces.isEmpty(), "命中应写 trace 行");
        assertEquals("brain_strategy", traces.get(0).getStepType());
        assertTrue(traces.get(0).getContent().contains("hit"), "trace 内容含命中策略名");
    }

    /** 策略执行异常只跳过该策略（隔离），链继续命中后续策略。 */
    @Test
    void strategyExceptionIsolatedAndChainContinues() {
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("boom", 90, true) {
                    @Override
                    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
                        throw new IllegalStateException("策略炸了");
                    }
                },
                new StubStrategy("safe", 40, true)));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out);
        assertEquals("safe", out.hitStrategy(), "异常策略被跳过，链继续");
        assertTrue(out.steps().contains("boom×异常"));
    }

    /** 可插拔：新策略注册进策略集合即入链（O6 发育阶段门控注入点语义）。 */
    @Test
    void newlyRegisteredStrategyJoinsChain() {
        List<BrainStrategy> list = new ArrayList<>(List.of(
                new StubStrategy("base", 40, true)));
        BrainStrategyRouter router = new BrainStrategyRouter(list);

        // 模拟未来模块注册新策略（如 O6 发育阶段门控）：加入集合即参与组链
        list.add(new StubStrategy("stage_gate", 70, true));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));
        assertEquals("stage_gate", out.hitStrategy(), "新策略按 priority 优先命中");
    }

    /** O6 注入点实证：真实 DevelopmentalGatingStrategy 注册即入链（受限阶段命中、成年阶段跳过）。 */
    @Test
    void realStageGatingStrategyJoinsChain() {
        DevelopmentalGatingStrategy gating = new DevelopmentalGatingStrategy();
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("base", 40, true), gating));

        // 受限阶段（CHILD）→ stage_gating（priority=90）先于 base 命中
        ActorCharacter child = character();
        child.setStage("CHILD");
        DecisionOutcome restricted = router.decide(new DecisionContext(
                100L, child, 1L, NpcKind.SPECIAL, "full", null, null, null, false));
        assertNotNull(restricted);
        assertEquals("stage_gating", restricted.hitStrategy(), "受限阶段 → 阶段门控按 priority=90 先命中");
        assertEquals(90, restricted.priority());
        assertNotNull(restricted.decision().get("actionDetail"), "阶段行为文本产出");

        // 成年阶段（YOUTH）→ gating 不适用（applies=false），链继续到 base
        ActorCharacter adult = character();
        adult.setStage("YOUTH");
        DecisionOutcome adultOut = router.decide(new DecisionContext(
                100L, adult, 1L, NpcKind.SPECIAL, "full", null, null, null, false));
        assertNotNull(adultOut);
        assertEquals("base", adultOut.hitStrategy(), "成年阶段 → 阶段门控不适用，链继续");
        assertFalse(adultOut.steps().contains("stage_gating×未命中"), "不适用策略不进链（applies 过滤）");
    }

    // ==================== 旁路校验（自省-行为一致性校验·候选 A） ====================

    /** 旁路校验器：链命中产出决策后自动调用（DecisionPostCheck 收集）。 */
    @Test
    void postChecksCalledAfterHit() {
        captureTraces();
        List<String> checked = new ArrayList<>();
        DecisionPostCheck check = new DecisionPostCheck() {
            @Override
            public String name() {
                return "self_check";
            }

            @Override
            public void check(DecisionContext ctx, Map<String, Object> decision) {
                checked.add(String.valueOf(decision.get("action")));
            }
        };
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("hit", 80, true)));
        ReflectionTestUtils.setField(router, "postChecks", List.of(check));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out, "校验器不阻断决策产出");
        assertEquals("策略动作-hit", out.decision().get("action"));
        assertEquals(1, checked.size(), "命中后调用旁路校验器一次");
        assertEquals("策略动作-hit", checked.get(0), "校验器收到已产出决策");
    }

    /** 旁路校验器异常隔离：校验器抛异常不影响决策返回。 */
    @Test
    void postCheckExceptionIsolated() {
        captureTraces();
        DecisionPostCheck boom = new DecisionPostCheck() {
            @Override
            public String name() {
                return "self_check";
            }

            @Override
            public void check(DecisionContext ctx, Map<String, Object> decision) {
                throw new IllegalStateException("校验器炸了");
            }
        };
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("hit", 80, true)));
        ReflectionTestUtils.setField(router, "postChecks", List.of(boom));

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out, "校验器异常被隔离，决策照常返回");
        assertEquals("hit", out.hitStrategy());
    }

    /** 旁路校验器未装配（null）→ 决策照常，无校验调用。 */
    @Test
    void postChecksUnwiredNoEffect() {
        captureTraces();
        BrainStrategyRouter router = new BrainStrategyRouter(List.of(
                new StubStrategy("hit", 80, true)));
        // postChecks 默认 List.of()（字段注入未装配时）→ 无校验

        DecisionOutcome out = router.decide(ctx(NpcKind.SPECIAL, "full"));

        assertNotNull(out);
        assertEquals("hit", out.hitStrategy());
    }
}
