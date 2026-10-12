package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * NPC 状态服务单元测试（状态模块 M1，domain/npc/state/service，同包）。
 * <p>职责：验证——惰性初始化（建行默认值/幂等/参数非法）、程序化衰减（特殊全列/夜间睡眠恢复/
 * 人群轻量/边界钳制/惰性补偿）、行动恢复映射（进食/睡眠/如厕等）、urgency 分级与文本、
 * 健康联动（侵蚀/回升）、mood 派生、人群作息挂钩（rest 恢复精力）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§6（程序化更新模型）/ §12 M1。</p>
 */
class NpcStateServiceTest {

    private ActorNpcStateRepository stateRepository;
    private NpcActivityStateService npcActivityStateService;
    private NpcStateProperties props;
    private NpcStateService service;

    /** 内存状态行仓库模拟：save 后可读，模拟真实 DB 语义 */
    private final List<ActorNpcState> stateStore = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stateRepository = Mockito.mock(ActorNpcStateRepository.class);
        props = new NpcStateProperties();
        // 仓库模拟：findByProjectIdAndNpcRef 读内存 store；save 追加/覆盖并返回实体
        when(stateRepository.findByProjectIdAndNpcRef(any(), any())).thenAnswer(inv -> {
            Long pid = inv.getArgument(0);
            String ref = inv.getArgument(1);
            return stateStore.stream()
                    .filter(s -> pid.equals(s.getProjectId()) && ref.equals(s.getNpcRef()))
                    .findFirst();
        });
        when(stateRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any()))
                .thenAnswer(inv -> stateStore);
        when(stateRepository.save(any(ActorNpcState.class))).thenAnswer(inv -> {
            ActorNpcState s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId((long) (stateStore.size() + 1));
                stateStore.add(s);
            }
            return s;
        });
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository charRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository npcRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        // advance/syncProjectStates 内惰性建行遍历用：默认返回空列表（避免 mock 默认 null 致 NPE）
        when(charRepo.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(java.util.List.of());
        when(npcRepo.findByProjectIdOrderByIdAsc(any())).thenReturn(java.util.List.of());
        service = new NpcStateService(stateRepository, charRepo, npcRepo, props);
        // G1a：作息运行态（state）已从 actor_profile 收敛到 actor_state——注入访问器 mock（默认空 Map = 全员 idle）
        npcActivityStateService = Mockito.mock(NpcActivityStateService.class);
        java.lang.reflect.Field fState = NpcStateService.class.getDeclaredField("npcActivityStateService");
        fState.setAccessible(true);
        fState.set(service, npcActivityStateService);
    }

    private ActorNpcState newState(Long projectId, String npcType, String npcRef, long gameSecond) {
        ActorNpcState s = new ActorNpcState();
        s.setId((long) (stateStore.size() + 1));
        s.setProjectId(projectId);
        s.setNpcType(npcType);
        s.setNpcRef(npcRef);
        s.setGameSecond(gameSecond);
        return s;
    }

    // ==================== 惰性初始化 ====================

    @Test
    void ensureState_createsRowWithDefaults() {
        ActorNpcState st = service.ensureState(10L, "special", "npc:5", 3600L);
        assertNotNull(st);
        assertEquals(10L, st.getProjectId());
        assertEquals("special", st.getNpcType());
        assertEquals("npc:5", st.getNpcRef());
        assertEquals(3600L, st.getGameSecond());
        // 默认值（主开发方案 §5）
        assertEquals(70, st.getHunger());
        assertEquals(70, st.getThirst());
        assertEquals(80, st.getEnergy());
        assertEquals(50, st.getBladder());
        assertEquals(100, st.getHealth());
        assertEquals(90, st.getHygiene());
        assertEquals(60, st.getSocial());
        assertEquals(60, st.getFun());
        assertEquals(80, st.getComfort());
        assertEquals(0.10d, st.getEmotionPleasure());
        assertEquals("calm", st.getDominantEmotion());
        assertEquals(0, st.getUrgencyLevel());
        assertEquals(1, stateStore.size());
    }

    @Test
    void ensureState_isIdempotent() {
        service.ensureState(10L, "special", "npc:5", 3600L);
        ActorNpcState again = service.ensureState(10L, "special", "npc:5", 7200L);
        assertNotNull(again);
        assertEquals(1, stateStore.size(), "重复调用不应重复建行");
        assertEquals(3600L, again.getGameSecond(), "已存在行不应被覆盖游戏时刻");
    }

    @Test
    void ensureState_nullParamsReturnsNull() {
        assertNull(service.ensureState(null, "special", "npc:5", 1L));
        assertNull(service.ensureState(1L, "special", "  ", 1L));
    }

    // ==================== 程序化衰减（纯静态） ====================

    @Test
    void applyDecay_specialDaytimeDecaysAllColumns() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        // 白天 10 点（fromSecond=10*3600）到 12 点：2 游戏小时
        NpcStateService.applyDecay(st, 10L * 3600, 12L * 3600, props);
        // hunger 70-2.4=67.6→68；thirst 70-4=66；energy 80-2=78；bladder 50-3=47
        assertEquals(68, st.getHunger());
        assertEquals(66, st.getThirst());
        assertEquals(78, st.getEnergy());
        assertEquals(47, st.getBladder());
        // hygiene 90-0.6=89.4→89；social 60-0.8=59.2→59；fun 60-1=59
        assertEquals(89, st.getHygiene());
        assertEquals(59, st.getSocial());
        assertEquals(59, st.getFun());
        // comfort 不随时间自衰减
        assertEquals(80, st.getComfort());
    }

    @Test
    void applyDecay_nightRestoresEnergyAndBladderFrozen() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setBladder(70);
        // 夜间 23 点（fromSecond=23*3600）到 01 点：2 游戏小时（睡眠时段 → 精力恢复、膀胱不下降）
        NpcStateService.applyDecay(st, 23L * 3600, 25L * 3600, props);
        assertEquals(100, st.getEnergy(), "夜间精力恢复（80+20 封顶 100）");
        assertEquals(70, st.getBladder(), "睡眠时段膀胱不下降");
        assertNotNull(st.getLastSleepGameSecond());
        assertEquals(25L * 3600, st.getLastSleepGameSecond());
    }

    @Test
    void applyDecay_crowdOnlyTwoColumns() {
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        NpcStateService.applyDecay(st, 10L * 3600, 13L * 3600, props);
        // 人群轻量：仅 hunger/energy 衰减（其余列保持默认不衰减）
        assertEquals(66, st.getHunger());   // 70-1.2*3=66.4→66
        assertEquals(77, st.getEnergy());   // 80-3=77
        assertEquals(90, st.getHygiene(), "人群清洁不衰减");
        assertEquals(60, st.getSocial(), "人群社交不衰减");
        assertEquals(100, st.getHealth(), "人群健康不衰减");
    }

    @Test
    void applyDecay_clampsAtZero() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(5);
        st.setThirst(3);
        // 白天 100 游戏小时：各需求大幅衰减 → 下限 0
        NpcStateService.applyDecay(st, 12L * 3600, 112L * 3600, props);
        assertEquals(0, st.getHunger());
        assertEquals(0, st.getThirst());
        assertTrue(st.getEnergy() >= 0 && st.getEnergy() <= 100);
    }

    @Test
    void applyDecay_taperSlowsInLowRegion() {
        props.getDecay().setTaper(true);
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(15); // 低于 taper 阈值 20
        NpcStateService.applyDecay(st, 10L * 3600, 12L * 3600, props);
        // taper=true：低值区按 50% 衰减 → 15-1.2*2*0.5=13.8→14（对比非 taper 15-2.4=12.6→13）
        assertEquals(14, st.getHunger());
    }

    // ==================== 惰性补偿（advance） ====================

    /**
     * 冒烟抓修锁定（2026-09-19）：onNpcAction 未传 gameSecond 时，回退时间基准必须用「引擎推进位置
     * （currentEngineGameHour）」而非「实时流动时钟（currentGameHour）」——高速率下流动时钟远超引擎
     * 模拟位置（锚点久远×速率），会把需求整体衰减归零（实测 special 行 hunger/health→0 缺陷）。
     */
    @Test
    void onNpcAction_nullGameSecondFallsBackToEngineHour() throws Exception {
        com.holzyn.actor.domain.world.service.WorldClockService clock =
                Mockito.mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        // ⚠️ 本项目 Mockito 未打桩 Long 方法默认返回 0（而非 null），必须显式打桩秒级位置为 null
        // 以模拟「存量时钟行尚无秒级位置」→ 触发回退小时位置（currentEngineGameHour=48）
        when(clock.currentEngineGameSecond(10L)).thenReturn(null);
        when(clock.currentEngineGameHour(10L)).thenReturn(48L); // 引擎推进位置 48h
        when(clock.currentGameHour(10L)).thenReturn(9999L);     // 流动时钟 9999h（膨胀，不得使用）
        java.lang.reflect.Field f = NpcStateService.class.getDeclaredField("worldClockService");
        f.setAccessible(true);
        f.set(service, clock);

        ActorNpcState st = newState(10L, "special", "npc:5", 47L * 3600);
        st.setHunger(50);
        stateStore.add(st);
        // 行动不传 gameSecond → 回退引擎位置 48h（非流动 9999h）→ 补 1 小时衰减 50-1.2=48.8→49 → 进食 +40 → 89
        service.onNpcAction(10L, "npc:5", "special", "吃饭", "custom", null);
        assertEquals(89, st.getHunger(), "回退引擎位置（48h）补衰减+恢复，而非膨胀流动时钟把需求归零");
        assertEquals(48L * 3600, st.getGameSecond());
    }

    @Test
    void advance_lazyCompensatesToTarget() {
        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        stateStore.add(st);
        service.advance(10L, 12L * 3600);
        // 从 10 点补到 12 点（2 小时）→ hunger 70-2.4=67.6→68
        assertEquals(68, st.getHunger());
        assertEquals(12L * 3600, st.getGameSecond(), "衰减后游戏时刻追平");
        assertEquals(1, stateStore.size());
    }

    @Test
    void advance_skipsWhenAlreadyCaughtUp() {
        ActorNpcState st = newState(10L, "special", "npc:5", 12L * 3600);
        stateStore.add(st);
        service.advance(10L, 12L * 3600);
        assertEquals(70, st.getHunger(), "已追平不应重复衰减");
    }

    @Test
    void advance_nullTargetSkips() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        stateStore.add(st);
        service.advance(10L, null);
        assertEquals(70, st.getHunger());
        assertEquals(0L, st.getGameSecond());
    }

    // ==================== 实时行为决策系统 M1：urgency 升档检测（刺激①，感知 tick 内） ====================

    @Test
    void advance_urgencyRiseTriggersRealtimeDecision() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rt =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field f = NpcStateService.class.getDeclaredField("realtimeDecisionService");
        f.setAccessible(true);
        f.set(service, rt);

        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        st.setHunger(35); // 推进 5 小时：35-6=29 < mild(30) → urgency 0→1 升档
        stateStore.add(st);
        service.advance(10L, 15L * 3600);
        Mockito.verify(rt).onStateUrgencyRise(10L, "npc:5", 0, 1, 15L * 3600);
    }

    @Test
    void advance_urgencyNoRiseDoesNotTrigger() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rt =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field f = NpcStateService.class.getDeclaredField("realtimeDecisionService");
        f.setAccessible(true);
        f.set(service, rt);

        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        st.setHunger(70); // 推进 2 小时：68 > mild(30) → urgency 持平 0，不触发
        stateStore.add(st);
        service.advance(10L, 12L * 3600);
        Mockito.verify(rt, Mockito.never())
                .onStateUrgencyRise(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // ==================== 行动挂钩恢复 ====================

    @Test
    void onNpcAction_eatRecoversHungerAfterCompensation() {
        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        st.setHunger(30);
        stateStore.add(st);
        // 行动时刻 12 点：先补 2 小时衰减（30-2.4=27.6→28），再进食 +40 → 68
        service.onNpcAction(10L, "npc:5", "special", "前往酒馆用餐", "custom", 12L * 3600);
        assertEquals(68, st.getHunger());
        assertEquals(12L * 3600, st.getGameSecond());
        assertEquals(12L * 3600, st.getLastEatGameSecond(), "记录最近进食游戏时刻");
    }

    @Test
    void onNpcAction_sleepRestoresEnergyAndHealth() {
        ActorNpcState st = newState(10L, "special", "npc:5", 20L * 3600);
        st.setEnergy(10);
        st.setHealth(80);
        stateStore.add(st);
        service.onNpcAction(10L, "npc:5", "special", "回房就寝休息", "custom", 22L * 3600);
        // 10 点→22 点衰减（白天 energy 10-2=8）→ 睡眠恢复 +45 → 53；
        // 健康：energy<20 触发侵蚀（80-1=79）→ 睡眠微恢复 +5 → 84
        assertEquals(53, st.getEnergy());
        assertEquals(84, st.getHealth());
        assertNotNull(st.getLastSleepGameSecond());
    }

    @Test
    void applyActionRecovery_toiletAndWash() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setBladder(20);
        st.setHygiene(40);
        NpcStateService.applyActionRecovery(st, "去如厕解手", "custom", 100L, props);
        NpcStateService.applyActionRecovery(st, "沐浴洗漱", "custom", 100L, props);
        assertEquals(75, st.getBladder()); // 20+55
        assertEquals(90, st.getHygiene()); // 40+50
    }

    @Test
    void applyActionRecovery_noMatchKeepsValues() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(30);
        NpcStateService.applyActionRecovery(st, "前往城东市场采购药材", "custom", null, props);
        assertEquals(30, st.getHunger(), "移动类行动不应恢复需求");
    }

    // ==================== mood 派生（M1 需求加权 → M2 情绪积分） ====================

    @Test
    void deriveMood_specialWeightedAndEnvModifier() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(100); st.setThirst(100); st.setEnergy(100); st.setBladder(100);
        st.setHealth(100); st.setHygiene(100); st.setSocial(100); st.setFun(100);
        // M2：需求 100×0.7 + 情绪分 50（calm P=0.10 × 强度 0 +1）/2×100 ×0.3 = 70+15 = 85
        assertEquals(85, NpcStateService.deriveMood(st, props));
        st.setEnvModifier(-10);
        assertEquals(75, NpcStateService.deriveMood(st, props), "env_modifier 参与 mood");
    }

    @Test
    void deriveMood_crowdUsesHungerEnergyOnly() {
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(50); st.setEnergy(50);
        assertEquals(50, NpcStateService.deriveMood(st, props));
    }

    @Test
    void deriveMood_positiveEmotionRaisesMood() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(100); st.setThirst(100); st.setEnergy(100); st.setBladder(100);
        st.setHealth(100); st.setHygiene(100); st.setSocial(100); st.setFun(100);
        // 高兴（P=0.8 强度 50）：情绪分=(0.8×0.5+1)/2×100=70 → 100×0.7+70×0.3=91
        st.setEmotionPleasure(0.8d);
        st.setEmotionIntensity(50);
        assertEquals(91, NpcStateService.deriveMood(st, props));
    }

    @Test
    void deriveMood_negativeEmotionLowersMood() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(100); st.setThirst(100); st.setEnergy(100); st.setBladder(100);
        st.setHealth(100); st.setHygiene(100); st.setSocial(100); st.setFun(100);
        // 悲伤（P=-0.4 强度 40）：情绪分=(-0.4×0.4+1)/2×100=42 → 100×0.7+42×0.3=82.6→83
        st.setEmotionPleasure(-0.4d);
        st.setEmotionIntensity(40);
        assertEquals(83, NpcStateService.deriveMood(st, props));
    }

    // ==================== urgency 分级与文本 ====================

    @Test
    void deriveUrgency_levels() {
        // 全部 ≥ 轻度阈值 → 0
        ActorNpcState ok = newState(10L, "special", "npc:5", 0);
        assertEquals(0, NpcStateService.deriveUrgency(ok, props));
        // 任一需求 < 30 → 1 轻度
        ActorNpcState mild = newState(10L, "special", "npc:5", 0);
        mild.setHunger(25);
        assertEquals(1, NpcStateService.deriveUrgency(mild, props));
        assertEquals("有些饥饿", mild.getUrgencyText());
        // 任一需求 < 15 → 2 强烈
        ActorNpcState strong = newState(10L, "special", "npc:5", 0);
        strong.setBladder(10);
        assertEquals(2, NpcStateService.deriveUrgency(strong, props));
        assertEquals("尿意很强，急需如厕", strong.getUrgencyText());
        // health < 25 → 3 健康告急
        ActorNpcState alert = newState(10L, "special", "npc:5", 0);
        alert.setHealth(20);
        assertEquals(3, NpcStateService.deriveUrgency(alert, props));
        assertEquals("身体不适，急需休息或就医", alert.getUrgencyText());
    }

    // ==================== 健康联动 ====================

    @Test
    void applyDecay_healthCorrodesWhenNeedsLow() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(10); st.setThirst(10); st.setEnergy(10); st.setBladder(10);
        st.setHealth(100);
        // 白天 10 小时低需求 → 健康侵蚀 0.5*10=5
        NpcStateService.applyDecay(st, 8L * 3600, 18L * 3600, props);
        assertEquals(95, st.getHealth());
    }

    @Test
    void applyDecay_healthRecoversWhenNeedsHigh() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(90); st.setThirst(90); st.setEnergy(90); st.setBladder(90);
        st.setHealth(80);
        // 白天 10 小时全部高于回升阈值 → 健康回升 0.2*10=2
        NpcStateService.applyDecay(st, 8L * 3600, 18L * 3600, props);
        assertEquals(82, st.getHealth());
    }

    // ==================== 人群作息挂钩 ====================

    @Test
    void onCrowdSchedule_restRecoversEnergyAndHealth() {
        ActorNpcState st = newState(10L, "common", "npc:8", 20L * 3600);
        st.setEnergy(30);
        stateStore.add(st);
        ActorCharacter n = new ActorCharacter();
        n.setId(8L);
        n.setType("common");
        // G1a：作息运行态改读 actor_state（经 NpcActivityStateService 批量 Map），主表实体已无 state 列
        when(npcActivityStateService.activityStateByRef(10L)).thenReturn(java.util.Map.of("npc:8", "rest"));
        service.onCrowdSchedule(10L, 22L * 3600, List.of(n));
        // 20→22 点衰减（白天 energy 30-2=28）→ rest 恢复 +45 → 73
        assertEquals(73, st.getEnergy());
        assertEquals(100, st.getHealth(), "健康恢复封顶 100");
        assertEquals(22L * 3600, st.getGameSecond());
    }

    @Test
    void onCrowdSchedule_skipsRestRecoveryForActionRecoveredNpc() {
        // M3 防双恢复（2026-09-03 冒烟抓修）：分层驱动的 rest 动作已由 onNpcAction 恢复睡眠，
        // 作息挂钩对已恢复集合中的 NPC 跳过 rest 恢复（只做衰减/情绪/派生重算）
        ActorNpcState st = newState(10L, "common", "npc:8", 20L * 3600);
        st.setEnergy(30);
        stateStore.add(st);
        ActorCharacter n = new ActorCharacter();
        n.setId(8L);
        n.setType("common");
        when(npcActivityStateService.activityStateByRef(10L)).thenReturn(java.util.Map.of("npc:8", "rest"));
        service.onCrowdSchedule(10L, 22L * 3600, List.of(n), java.util.Set.of(8L));
        // 20→22 点衰减（白天 energy 30-2=28），rest 恢复被跳过 → 28（无 +45）
        assertEquals(28, st.getEnergy());
        assertEquals(22L * 3600, st.getGameSecond());
    }

    @Test
    void onCrowdSchedule_restRecoveryStillAppliesForNonDrivenNpc() {
        // 4 参版空集合 = 纯程序化路径：rest 仍按作息恢复（与 3 参版语义一致）
        ActorNpcState st = newState(10L, "common", "npc:9", 20L * 3600);
        st.setEnergy(30);
        stateStore.add(st);
        ActorCharacter n = new ActorCharacter();
        n.setId(9L);
        n.setType("common");
        when(npcActivityStateService.activityStateByRef(10L)).thenReturn(java.util.Map.of("npc:9", "rest"));
        service.onCrowdSchedule(10L, 22L * 3600, List.of(n), java.util.Set.of());
        assertEquals(73, st.getEnergy());
    }

    // ==================== M2 情绪模型：程序化规则 / 衰减 / 锚点 / 评估 ====================

    /** 构造 7 锚点列表（M2 主导情绪最近锚点判定用，数值取自主开发方案 §7.1） */
    private List<com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor> anchors() {
        List<com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor> list = new ArrayList<>();
        list.add(anchor("calm", 0.10, -0.10, 0.00));
        list.add(anchor("happy", 0.80, 0.50, 0.45));
        list.add(anchor("angry", -0.50, 0.60, 0.25));
        list.add(anchor("sad", -0.40, -0.20, -0.50));
        list.add(anchor("fearful", -0.64, 0.60, -0.43));
        list.add(anchor("surprised", 0.30, 0.70, -0.20));
        list.add(anchor("disgusted", -0.60, 0.35, 0.10));
        return list;
    }

    private com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor anchor(String code, double p, double a, double d) {
        com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor an =
                new com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor();
        an.setCode(code);
        an.setPleasure(p);
        an.setArousal(a);
        an.setDominance(d);
        return an;
    }

    // ---- 程序化情绪规则 ----

    @Test
    void applyProceduralEmotion_hungerLowPushesPupDownA() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(15);
        NpcStateService.applyProceduralEmotion(st, props, false, false);
        assertEquals(0.00d, st.getEmotionPleasure(), 0.001); // 0.10-0.10
        assertEquals(0.00d, st.getEmotionArousal(), 0.001);  // -0.10+0.10
    }

    @Test
    void applyProceduralEmotion_bladderLowPushesDownD() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setBladder(10);
        NpcStateService.applyProceduralEmotion(st, props, false, false);
        assertEquals(-0.10d, st.getEmotionDominance(), 0.001); // 0.00-0.10
    }

    @Test
    void applyProceduralEmotion_socialLowAndNightAndAction() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setSocial(10);
        NpcStateService.applyProceduralEmotion(st, props, true, true);
        // P: 0.10 - 0.05(社交) + 0.05(行动满足) = 0.10；A: -0.10 - 0.05(夜间) = -0.15
        assertEquals(0.10d, st.getEmotionPleasure(), 0.001);
        assertEquals(-0.15d, st.getEmotionArousal(), 0.001);
    }

    @Test
    void applyProceduralEmotion_clampsAtBounds() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(0.95d);
        st.setHunger(10); // P -0.10
        st.setThirst(10); // P -0.10
        st.setSocial(10); // P -0.05
        NpcStateService.applyProceduralEmotion(st, props, false, false);
        assertEquals(0.70d, st.getEmotionPleasure(), 0.001); // 0.95-0.25
    }

    // ---- 情绪衰减 ----

    @Test
    void applyEmotionDecay_movesTowardCalmHalfLife() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(0.80d); // 高兴 P
        st.setEmotionArousal(0.50d);
        st.setEmotionDominance(0.45d);
        // 半衰期 6h：2 小时衰减比例 = 1-0.5^(2/6) = 1-0.7937 = 0.2063
        NpcStateService.applyEmotionDecay(st, 0, 2L * 3600, props);
        double expectedP = 0.10 + (0.80 - 0.10) * (1 - 0.2063);
        assertEquals(expectedP, st.getEmotionPleasure(), 0.05);
        assertTrue(st.getEmotionPleasure() < 0.80 && st.getEmotionPleasure() > 0.10, "向平静衰减但未到平静");
    }

    @Test
    void applyEmotionDecay_atCalmNoChange() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        NpcStateService.applyEmotionDecay(st, 0, 100L * 3600, props);
        assertEquals(0.10d, st.getEmotionPleasure(), 0.001);
        assertEquals(-0.10d, st.getEmotionArousal(), 0.001);
        assertEquals(0.00d, st.getEmotionDominance(), 0.001);
    }

    @Test
    void applyEmotionDecay_moodSynergySlowsNegativeDecay() {
        // 心情低 + P<0：负面情绪退得慢（衰减量 ×0.5）
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setMood(30); // 低于协同阈值 50
        st.setEmotionPleasure(-0.60d);
        NpcStateService.applyEmotionDecay(st, 0, 6L * 3600, props);
        // 半衰期 6h：无协同 1 个半衰期后 P = 0.10 + (-0.60-0.10)*0.5 = -0.25；
        // 协同 factor=0.5：fraction=0.5*0.5=0.25 → P = 0.10 + (-0.60-0.10)*0.75 = -0.425（退得更慢）
        assertEquals(-0.425d, st.getEmotionPleasure(), 0.01);
    }

    // ---- 主导情绪 / 强度 ----

    @Test
    void deriveDominantEmotion_nearestAnchor() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(0.85d); st.setEmotionArousal(0.55d); st.setEmotionDominance(0.40d);
        assertEquals("happy", NpcStateService.deriveDominantEmotion(st, anchors()));
    }

    @Test
    void deriveDominantEmotion_emptyAnchorsFallsBackCalm() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(0.85d);
        assertEquals("calm", NpcStateService.deriveDominantEmotion(st, List.of()));
        assertEquals("calm", NpcStateService.deriveDominantEmotion(st, null));
    }

    @Test
    void deriveEmotionIntensity_normalizesMagnitude() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(1.0d); st.setEmotionArousal(1.0d); st.setEmotionDominance(1.0d);
        assertEquals(100, NpcStateService.deriveEmotionIntensity(st));
        st.setEmotionPleasure(0.0d); st.setEmotionArousal(0.0d); st.setEmotionDominance(0.0d);
        assertEquals(0, NpcStateService.deriveEmotionIntensity(st));
    }

    @Test
    void refreshEmotionDerived_recomputesDominantWhenNotExplicit() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEmotionPleasure(0.85d); st.setEmotionArousal(0.55d); st.setEmotionDominance(0.40d);
        NpcStateService.refreshEmotionDerived(st, props, anchors());
        assertEquals("happy", st.getDominantEmotion());
        assertTrue(st.getEmotionIntensity() > 0);
        assertEquals(0, st.getEmotionLabelExplicit());
    }

    @Test
    void refreshEmotionDerived_keepsExplicitLabel() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setDominantEmotion("angry");
        st.setEmotionLabelExplicit(1);
        st.setEmotionPleasure(0.85d); st.setEmotionArousal(0.55d); st.setEmotionDominance(0.40d);
        NpcStateService.refreshEmotionDerived(st, props, anchors());
        assertEquals("angry", st.getDominantEmotion(), "显式标签在 PAD 变化时保持");
        assertTrue(st.getEmotionIntensity() > 0, "强度仍重算");
    }

    // ---- AI 评估应用 ----

    @Test
    void applyEmotionEval_padDeltaAppliedAndClamped() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        var eval = new EmotionEvalParse.EmotionEval("angry", 60,
                0.9d, 0.9d, 0.9d, null, null, null, "被羞辱");
        boolean applied = NpcStateService.applyEmotionEval(st, eval, props, anchors());
        assertTrue(applied);
        // P=0.10+0.9=1.0（钳制上限）；A=-0.10+0.9=0.80；D=0.00+0.9=0.90
        assertEquals(1.0d, st.getEmotionPleasure(), 0.001);
        assertEquals(0.80d, st.getEmotionArousal(), 0.001);
        assertEquals(0.90d, st.getEmotionDominance(), 0.001);
        assertEquals("angry", st.getDominantEmotion(), "显式标签当次写入生效");
        assertEquals(1, st.getEmotionLabelExplicit());
        assertEquals(60, st.getEmotionIntensity(), "AI 提供的强度当次生效");
    }

    @Test
    void applyEmotionEval_noExplicitLabelUsesNearestAnchor() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        // 状态已处于「高兴」附近，评估仅给小幅位移（无显式标签）→ 主导情绪按最近锚点 = happy
        st.setEmotionPleasure(0.85d); st.setEmotionArousal(0.55d); st.setEmotionDominance(0.40d);
        var eval = new EmotionEvalParse.EmotionEval("", null, 0.02d, null, null, null, null, null, "微调");
        boolean applied = NpcStateService.applyEmotionEval(st, eval, props, anchors());
        assertTrue(applied);
        assertEquals("happy", st.getDominantEmotion(), "无显式标签 → 最近锚点");
        assertEquals(0, st.getEmotionLabelExplicit());
    }

    @Test
    void applyEmotionEval_occDisabledIgnoresOccDims() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        props.getEmotion().setOccEnabled(false);
        var eval = new EmotionEvalParse.EmotionEval("", null, null, null, null,
                -0.8d, 0.0d, 0.0d, "仅 OCC 维度");
        // OCC 关：忽略 OCC 字段，仅 emotion 空 → 返回 false（无可应用内容）
        boolean applied = NpcStateService.applyEmotionEval(st, eval, props, anchors());
        assertFalse(applied);
        assertEquals(0.10d, st.getEmotionPleasure(), 0.001, "OCC 字段被忽略");
    }

    @Test
    void applyEmotionEval_occEnabledMapsRules() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        props.getEmotion().setOccEnabled(true);
        // 合意性 +0.8（P +0.8×0.5=+0.4）；称赞性 +0.6（D +0.6×0.3=+0.18，P +0.6×0.3×0.3=+0.054）；
        // 喜好性 +0.5（P +0.25，D +0.25）
        var eval = new EmotionEvalParse.EmotionEval("", null, null, null, null,
                0.8d, 0.6d, 0.5d, "OCC 评价");
        boolean applied = NpcStateService.applyEmotionEval(st, eval, props, anchors());
        assertTrue(applied);
        assertEquals(0.10 + 0.4 + 0.054 + 0.25, st.getEmotionPleasure(), 0.001);
        assertEquals(0.00 + 0.18 + 0.25, st.getEmotionDominance(), 0.001);
    }

    @Test
    void applyEmotionEval_nullEvalReturnsFalse() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        assertFalse(NpcStateService.applyEmotionEval(st, null, props, anchors()));
    }

    // ==================== M3 【当前状态】注入段（§8.1） ====================

    @Test
    void renderStateSegment_specialFullSegment() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(68); st.setThirst(45); st.setEnergy(30); st.setBladder(82);
        st.setMood(62); st.setDominantEmotion("happy"); st.setEmotionIntensity(58);
        st.setUrgencyText("有些饥饿"); st.setUrgencyLevel(1); st.setHealth(100);
        String text = NpcStateService.renderStateSegment(st, props, 300);
        assertTrue(text.startsWith("【当前状态】"));
        assertTrue(text.contains("饥饿 68/100"));
        assertTrue(text.contains("口渴 45/100"));
        assertTrue(text.contains("精力 30/100"));
        assertTrue(text.contains("膀胱 82/100"));
        assertTrue(text.contains("心情：62/100"));
        assertTrue(text.contains("happy"));
        assertTrue(text.contains("强度 58"));
        assertTrue(text.contains("紧急：有些饥饿"));
        assertTrue(text.contains("健康：100/100"));
    }

    @Test
    void renderStateSegment_crowdLightSegment() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(20); st.setEnergy(90);
        String text = NpcStateService.renderStateSegment(st, props, 300);
        assertTrue(text.startsWith("【当前状态】"));
        assertTrue(text.contains("饥饿 20/100"));
        assertTrue(text.contains("精力 90/100"));
        assertFalse(text.contains("口渴"));
        assertFalse(text.contains("膀胱"));
        assertFalse(text.contains("健康"));
    }

    @Test
    void renderStateSegment_budgetHardTruncates() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(68); st.setThirst(45); st.setEnergy(30); st.setBladder(82);
        st.setMood(62); st.setDominantEmotion("happy"); st.setEmotionIntensity(58);
        st.setUrgencyText(""); st.setUrgencyLevel(0); st.setHealth(100);
        // 预算 1 → maxChars=4：先丢健康行再丢派生描述，最后硬截断不超预算
        String text = NpcStateService.renderStateSegment(st, props, 1);
        assertTrue(text.length() <= 4);
    }

    @Test
    void needDescriptor_bands() {
        assertEquals("饱腹", NpcStateService.needDescriptor("饥饿", 90));
        assertEquals("尚可", NpcStateService.needDescriptor("饥饿", 60));
        assertEquals("欠佳", NpcStateService.needDescriptor("口渴", 40));
        assertEquals("比较疲惫", NpcStateService.needDescriptor("精力", 40));
        assertEquals("有点饿", NpcStateService.needDescriptor("饥饿", 20));
        assertEquals("非常口渴", NpcStateService.needDescriptor("口渴", 10));
        assertEquals("尿意很强", NpcStateService.needDescriptor("膀胱", 5));
        assertEquals("健康", NpcStateService.needDescriptor("健康", 95));
    }

    @Test
    void stateSegment_instanceReturnsRenderedText() {
        String text = service.stateSegment(10L, "npc:5", 3600L);
        assertTrue(text.startsWith("【当前状态】"));
        assertTrue(text.contains("饥饿 70/100"), "默认值渲染");
        assertTrue(stateStore.stream().anyMatch(s -> "npc:5".equals(s.getNpcRef())), "注入前惰性建行");
    }

    @Test
    void isCrowdAiEligible_sandboxRepoAbsentAllows() {
        // sandboxStateRepository 未装配 → 宽松放行（小项目默认走 AI 决策）
        assertTrue(service.isCrowdAiEligible(10L, "npc:12"));
        props.setEnabled(false);
        assertFalse(service.isCrowdAiEligible(10L, "npc:12"), "总开关关 → 不触发驱动");
    }

    // ==================== M3 概率化 + 对象评分（§8.2） ====================

    @Test
    void probabilisticAction_emptyCandidatesReturnsNull() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(90); st.setEnergy(95); // 高于阈值 60 → 无候选
        assertNull(NpcStateService.probabilisticAction(st, List.of(), props, new java.util.Random(1)));
    }

    @Test
    void probabilisticAction_hungrySelectsEatWithLocation() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(10); st.setEnergy(80); // 饥饿 10 → 处理概率 ~0.9（10%→90%）
        var act = NpcStateService.probabilisticAction(st, List.of("王都酒馆", "城东市场"), props, new java.util.Random(1));
        assertNotNull(act);
        assertEquals("eat", act.actionType());
        assertNotNull(act.target(), "存在匹配对象地点 → 评分选中");
        assertTrue(act.actionText().contains("用餐") || act.actionText().contains("觅食"));
    }

    @Test
    void probabilisticAction_urgencyForcesMostUrgent() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(10); st.setEnergy(80);
        st.setUrgencyLevel(2); st.setUrgencyText("非常饥饿，急需进食");
        var act = NpcStateService.probabilisticAction(st, List.of(), props, new java.util.Random(1));
        assertNotNull(act);
        assertEquals("eat", act.actionType(), "urgency 强制最紧急需求（打断 daily_plan）");
    }

    @Test
    void probabilisticAction_healthAlertForcesTreat() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHealth(15); st.setHunger(70); st.setThirst(70); st.setEnergy(80); st.setBladder(50);
        st.setUrgencyLevel(3);
        var act = NpcStateService.probabilisticAction(st, List.of("城中药铺"), props, new java.util.Random(1));
        assertNotNull(act);
        assertEquals("treat", act.actionType(), "健康告急 → 强制就医倾向");
        assertEquals("城中药铺", act.target());
    }

    // ==================== 生理需求耦合（危险优先 + 三餐窗口，2026-10-02） ====================

    @Test
    void probabilisticAction_dangerZoneForcesEatOutsideMealWindow() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(15); st.setEnergy(80); // 低于危险阈值 20
        // 22 点（非餐点）——危险区不受三餐窗口限制（快饿死的人不受用餐时间约束）
        var act = NpcStateService.probabilisticAction(st, List.of("王都酒馆"), props,
                new java.util.Random(1), 22);
        assertNotNull(act);
        assertEquals("eat", act.actionType(), "危险区强制进食（0 概率化，无论时段）");
        assertEquals("王都酒馆", act.target());
    }

    @Test
    void probabilisticAction_dangerZoneBladderForcesToilet() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(70); st.setEnergy(80); st.setBladder(10); // < danger-bladder 15
        var act = NpcStateService.probabilisticAction(st, List.of("村口茅房"), props,
                new java.util.Random(1), 22);
        assertNotNull(act);
        assertEquals("toilet", act.actionType(), "膀胱危险区强制如厕");
        assertEquals("村口茅房", act.target());
    }

    @Test
    void probabilisticAction_mealWindowGatesLowHungerCandidate() {
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(40); st.setEnergy(95); // 低值（<60 概率化阈值）但未到危险区
        // 22 点非餐点：进食候选被窗口门控 → 无其余候选 → null
        assertNull(NpcStateService.probabilisticAction(st, List.of("王都酒馆"), props,
                new java.util.Random(1), 22), "非餐点低值饥饿不注入进食候选");
        // 12 点餐点内：进食候选正常
        var act = NpcStateService.probabilisticAction(st, List.of("王都酒馆"), props,
                new java.util.Random(1), 12);
        assertNotNull(act);
        assertEquals("eat", act.actionType(), "餐点内低值饥饿正常注入");
    }

    @Test
    void probabilisticAction_4ParamDelegatesNoWindowGate() {
        // 4 参重载（既有调用/测试）：hourOfDay=null → 进食不受窗口门控（兼容语义）
        ActorNpcState st = newState(10L, "common", "npc:12", 0);
        st.setHunger(40); st.setEnergy(95);
        var act = NpcStateService.probabilisticAction(st, List.of("王都酒馆"), props, new java.util.Random(1));
        assertNotNull(act);
        assertEquals("eat", act.actionType(), "4 参重载时刻未知 → 进食候选照常");
    }

    @Test
    void probabilisticAction_dangerZonePrecedesHealthAlertOrder() {
        // 危险区与健康告急并存：健康告急（special）仍优先（就医先于吃饭——保命语义）
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHealth(15); st.setHunger(10); st.setEnergy(80);
        var act = NpcStateService.probabilisticAction(st, List.of("城中药铺", "王都酒馆"), props,
                new java.util.Random(1), 22);
        assertNotNull(act);
        assertEquals("treat", act.actionType(), "健康告急强制优先于生理危险区（保命先）");
    }

    // ==================== 生理需求危险区实时打断（advance 感知 tick 钩子，2026-10-02） ====================

    @Test
    void advance_physioDangerTriggersPhysiologicalUrgeInterrupt() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rtds =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field fRtds = NpcStateService.class.getDeclaredField("realtimeDecisionService");
        fRtds.setAccessible(true);
        fRtds.set(service, rtds);

        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(10); // 低于危险阈值 20
        stateStore.add(st);

        service.advance(10L, 300L);

        Mockito.verify(rtds).onStimulus(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("npc:5"),
                org.mockito.ArgumentMatchers.eq(
                        com.holzyn.actor.domain.agent.runtime.StimulusType.PHYSIOLOGICAL_URGE),
                org.mockito.ArgumentMatchers.contains("饿得发慌"),
                org.mockito.ArgumentMatchers.eq(300L));
    }

    @Test
    void advance_physioDangerInterruptDisabledSkips() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rtds =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field fRtds = NpcStateService.class.getDeclaredField("realtimeDecisionService");
        fRtds.setAccessible(true);
        fRtds.set(service, rtds);
        props.getPhysio().setDangerInterruptEnabled(false); // 打断开关关

        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(10);
        stateStore.add(st);

        service.advance(10L, 300L);

        Mockito.verify(rtds, Mockito.never()).onStimulus(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void advance_physioHealthyNoInterrupt() throws Exception {
        com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService rtds =
                Mockito.mock(com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService.class);
        java.lang.reflect.Field fRtds = NpcStateService.class.getDeclaredField("realtimeDecisionService");
        fRtds.setAccessible(true);
        fRtds.set(service, rtds);

        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(70); st.setThirst(70); st.setBladder(50); st.setHygiene(90); // 全部健康
        stateStore.add(st);

        service.advance(10L, 300L);

        Mockito.verify(rtds, Mockito.never()).onStimulus(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void pickTarget_matchesKeywords() {
        assertEquals("王都酒馆", NpcStateService.pickTarget(List.of("王都酒馆", "城东市场"), "eat", new java.util.Random(0)));
        assertNull(NpcStateService.pickTarget(List.of("城东市场"), "toilet", new java.util.Random(0)), "无匹配地点 → null");
        assertTrue(NpcStateService.hasMatchingLocation(List.of("镇口水井"), "drink"));
        assertFalse(NpcStateService.hasMatchingLocation(List.of("镇口集市"), "toilet"));
    }

    // ==================== M3 npc:state 显著变化签名（§10） ====================

    @Test
    void stateSignature_thresholdBucketDedup() {
        ActorNpcState a = newState(10L, "common", "npc:1", 0);
        a.setHunger(59); a.setMood(70); a.setDominantEmotion("calm"); a.setUrgencyLevel(0);
        ActorNpcState b = newState(10L, "common", "npc:1", 0);
        b.setHunger(51); b.setMood(70); b.setDominantEmotion("calm"); b.setUrgencyLevel(0);
        // 59/10=5、51/10=5 → 同桶（未跨阈值）→ 签名相同（不广播）
        assertEquals(NpcStateService.stateSignature(a), NpcStateService.stateSignature(b));
        ActorNpcState c = newState(10L, "common", "npc:1", 0);
        c.setHunger(49); c.setMood(70); c.setDominantEmotion("calm"); c.setUrgencyLevel(0);
        // 49/10=4 → 跨阈值 → 签名不同（广播）
        assertNotEquals(NpcStateService.stateSignature(a), NpcStateService.stateSignature(c));
        ActorNpcState d = newState(10L, "common", "npc:1", 0);
        d.setHunger(59); d.setMood(70); d.setDominantEmotion("happy"); d.setUrgencyLevel(0);
        assertNotEquals(NpcStateService.stateSignature(a), NpcStateService.stateSignature(d), "情绪切换触发广播");
    }

    // ==================== M1 历史快照采样小时边界门控（世界演化与游戏时间系统，2026-09-03） ====================

    /**
     * 跨游戏小时边界判断（advance 内历史快照采样门控）：引擎每 5 游戏分钟调一次 advance，
     * 仅当行衰减区间跨小时边界（floorDiv(from/3600) != floorDiv(to/3600)）才采样——
     * 保持 M4「逐游戏小时采样、每 NPC 168 条≈7 游戏日」基线（采样粒度可配在 M2 引入）。
     */
    @Test
    void crossesHourBoundaryGatesSnapshot() {
        assertFalse(NpcStateService.crossesHourBoundary(0, 300), "5 分钟内不跨小时");
        assertFalse(NpcStateService.crossesHourBoundary(0, 3599), "未到整点不跨");
        assertTrue(NpcStateService.crossesHourBoundary(0, 3600), "0→3600 跨入第 1 小时");
        assertTrue(NpcStateService.crossesHourBoundary(3540, 3660), "跨整点");
        assertFalse(NpcStateService.crossesHourBoundary(3600, 7199), "同小时区间内不跨");
        assertTrue(NpcStateService.crossesHourBoundary(3600, 7200), "整小时区间跨入第 2 小时");
        assertTrue(NpcStateService.crossesHourBoundary(3599, 3600), "整点前一秒→整点跨小时");
        assertFalse(NpcStateService.crossesHourBoundary(3600, 3600), "同秒不跨");
    }

    // ==================== M2 采样粒度可配（世界演化与游戏时间系统，2026-09-06） ====================

    /**
     * 跨采样粒度边界判断（M2 历史快照采样门控，按 history.sample-minutes 配置粒度）：
     * 默认 60 分钟=每小时（与 M1 crossesHourBoundary 语义一致）；切 5 分钟=细粒度演示；
     * 配置非法（<=0）回退 60。
     */
    @Test
    void crossesSampleBoundaryByGranularity() {
        // 默认 60 分钟：语义与小时边界一致
        assertFalse(NpcStateService.crossesSampleBoundary(0, 300, 60), "5 分钟内不跨粒度边界");
        assertFalse(NpcStateService.crossesSampleBoundary(0, 3599, 60), "未到整点不跨");
        assertTrue(NpcStateService.crossesSampleBoundary(0, 3600, 60), "0→3600 跨小时");
        assertTrue(NpcStateService.crossesSampleBoundary(3540, 3660, 60), "跨整点");
        // 切 5 分钟：5 分钟粒度边界
        assertTrue(NpcStateService.crossesSampleBoundary(0, 300, 5), "0→300 跨 5 分钟边界");
        assertFalse(NpcStateService.crossesSampleBoundary(0, 299, 5), "未到 5 分钟不跨");
        assertTrue(NpcStateService.crossesSampleBoundary(295, 305, 5), "跨 5 分钟整点");
        assertFalse(NpcStateService.crossesSampleBoundary(300, 599, 5), "同 5 分钟区间内不跨");
        assertTrue(NpcStateService.crossesSampleBoundary(300, 600, 5), "跨入下一个 5 分钟区间");
        // 配置非法回退 60（每小时）
        assertFalse(NpcStateService.crossesSampleBoundary(0, 300, 0), "sample<=0 回退 60=每小时");
        assertFalse(NpcStateService.crossesSampleBoundary(0, 300, -1), "负数回退 60=每小时");
        assertTrue(NpcStateService.crossesSampleBoundary(0, 3600, 0), "回退 60 下跨小时才采样");
    }

    /**
     * 保留上限自动放大（M2 History.effectiveMaxPerNpc）：每小时粒度 168≈7 游戏日；
     * 切 5 分钟时自动放大 168→2016（7×24×12≈7 游戏日）；max-per-npc 手动调大仍可覆盖；
     * 非 5 分钟档保持配置值。
     */
    @Test
    void historyEffectiveMaxPerNpcScalesWithGranularity() {
        NpcStateProperties.History h = new NpcStateProperties.History();
        assertEquals(168, h.effectiveMaxPerNpc(), "默认每小时：168≈7 游戏日");
        h.setSampleMinutes(5);
        assertEquals(2016, h.effectiveMaxPerNpc(), "切 5 分钟：自动放大到 2016≈7 游戏日");
        h.setMaxPerNpc(3000);
        assertEquals(3000, h.effectiveMaxPerNpc(), "手动调大 >2016 以手动值为准");
        h.setMaxPerNpc(100);
        assertEquals(2016, h.effectiveMaxPerNpc(), "手动调小 <2016 仍保证至少 7 游戏日覆盖");
        h.setSampleMinutes(10);
        assertEquals(100, h.effectiveMaxPerNpc(), "非 5 分钟档（10 分钟）保持配置值");
        h.setSampleMinutes(0);
        assertEquals(100, h.effectiveMaxPerNpc(), "配置非法回退每小时档，保持配置值");
    }

    // ==================== P0-1 双精度余额结转（2026-09-11 状态循环修复：24h 压测整数取整停滞根因） ====================

    /**
     * P0-1 核心回归：引擎 5 游戏分钟 tick（300 游戏秒 = 0.083 游戏小时）稳态小步进下，
     * 需求必须持续真实下降（旧 Math.round 每 tick 0.1 点被吞 → hunger 恒 66 永久停滞，24h 压测实证）。
     * hunger 1.2/时 × 5h = 6 点 → 70 → 64；小数余额写回 decay_frac 结转。
     */
    @Test
    void applyDecay_smallTicksAccumulateWithoutStall() {
        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        long base = 10L * 3600;
        for (int i = 0; i < 60; i++) { // 60 × 5 游戏分钟 = 5 游戏小时
            NpcStateService.applyDecay(st, base + i * 300L, base + (i + 1) * 300L, props);
        }
        assertEquals(64, st.getHunger(), "5 游戏小时 hunger 70-1.2×5=64（微量衰减持续累积，不再停滞）");
        assertNotNull(st.getDecayFrac(), "小数余额应落库结转");
        assertFalse(st.getDecayFrac().isBlank(), "小数余额 JSON 不应为空");
    }

    /**
     * P0-1 健康联动同样经余额结转：hunger=10（低于侵蚀阈值 20）→ 健康 0.5/时侵蚀，
     * 5 游戏小时 → 100-2.5=97.5 → 展示 97 或 98（浮点迭代累积在 .5 边界 ±1e-9 内漂移，
     * 两值均正确；旧 round 每 tick 0.04 点则永久停滞 100——本用例锁定「真实侵蚀」）。
     */
    @Test
    void applyDecay_healthCorrosionSmallTicksNotStall() {
        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        st.setHunger(10);
        long base = 10L * 3600;
        for (int i = 0; i < 60; i++) {
            NpcStateService.applyDecay(st, base + i * 300L, base + (i + 1) * 300L, props);
        }
        int h = st.getHealth();
        assertTrue(h == 97 || h == 98,
                "5 游戏小时健康侵蚀 100-0.5×5=97.5（97/98 均正确，不得停滞 100），实际=" + h);
    }

    /**
     * P0-1 夜间精力恢复同样经余额结转：energy 50 + 10/时 × 5 游戏小时 = 100（封顶）。
     */
    @Test
    void applyDecay_nightEnergyRestoreAccumulates() {
        ActorNpcState st = newState(10L, "special", "npc:5", 23L * 3600);
        st.setEnergy(50);
        long base = 23L * 3600;
        for (int i = 0; i < 60; i++) {
            NpcStateService.applyDecay(st, base + i * 300L, base + (i + 1) * 300L, props);
        }
        assertEquals(100, st.getEnergy(), "夜间 5 游戏小时精力 50+50=100");
    }

    /**
     * P0-1 余额跨「重载」保持（重启/行重读语义）：把展示值 + decay_frac + game_second 拷贝到新行
     * 继续推进，与不间断推进结果完全一致——小数余额随行持久化，重启不丢。
     */
    @Test
    void applyDecay_balanceSurvivesReload() {
        long base = 10L * 3600;
        // 不间断推进 60 tick
        ActorNpcState continuous = newState(10L, "special", "npc:5", base);
        for (int i = 0; i < 60; i++) {
            NpcStateService.applyDecay(continuous, base + i * 300L, base + (i + 1) * 300L, props);
        }
        // 30 tick 后「重载」：新行复制展示值/余额/时刻，再推 30 tick
        ActorNpcState reloaded = newState(10L, "special", "npc:5", base);
        for (int i = 0; i < 30; i++) {
            NpcStateService.applyDecay(reloaded, base + i * 300L, base + (i + 1) * 300L, props);
        }
        ActorNpcState reloaded2 = new ActorNpcState();
        reloaded2.setNpcType("special");
        reloaded2.setHunger(reloaded.getHunger());
        reloaded2.setThirst(reloaded.getThirst());
        reloaded2.setEnergy(reloaded.getEnergy());
        reloaded2.setBladder(reloaded.getBladder());
        reloaded2.setHygiene(reloaded.getHygiene());
        reloaded2.setSocial(reloaded.getSocial());
        reloaded2.setFun(reloaded.getFun());
        reloaded2.setHealth(reloaded.getHealth());
        reloaded2.setDecayFrac(reloaded.getDecayFrac());
        long mid = base + 30 * 300L;
        for (int i = 0; i < 30; i++) {
            NpcStateService.applyDecay(reloaded2, mid + i * 300L, mid + (i + 1) * 300L, props);
        }
        assertEquals(continuous.getHunger(), reloaded2.getHunger(), "重载后继续推进与不间断推进结果一致");
        assertEquals(continuous.getThirst(), reloaded2.getThirst());
        assertEquals(continuous.getEnergy(), reloaded2.getEnergy());
        assertEquals(continuous.getHealth(), reloaded2.getHealth());
    }

    /**
     * P0-1 钳制清余额：触底（0）与触顶（100）时对应键余额清零（防残差漂移）。
     */
    @Test
    void applyDecay_clampClearsBalanceAtBounds() {
        ActorNpcState st = newState(10L, "special", "npc:5", 10L * 3600);
        st.setHunger(3);
        NpcStateService.applyDecay(st, 10L * 3600, 20L * 3600, props); // 3-12=-9 → 钳 0
        assertEquals(0, st.getHunger());
        assertFalse(st.getDecayFrac() != null && st.getDecayFrac().contains("hunger"),
                "触底钳制后 hunger 余额应清零");
        // 触顶：夜间恢复 95+10 → 钳 100，energy 余额清零
        ActorNpcState st2 = newState(10L, "special", "npc:5", 0);
        st2.setEnergy(95);
        NpcStateService.applyDecay(st2, 23L * 3600, 24L * 3600, props);
        assertEquals(100, st2.getEnergy());
        assertFalse(st2.getDecayFrac() != null && st2.getDecayFrac().contains("energy"),
                "触顶钳制后 energy 余额应清零");
    }

    // ==================== P0-2 流动时钟污染防护（2026-09-11 状态循环修复：game_second 钳制） ====================

    /** 注入时钟 mock（引擎位置 10000 秒；流动时钟值在测试内显式给定）。 */
    private void injectClockMock(Long engineSecond, Long engineHour, Long flowHour) throws Exception {
        com.holzyn.actor.domain.world.service.WorldClockService clock =
                Mockito.mock(com.holzyn.actor.domain.world.service.WorldClockService.class);
        when(clock.currentEngineGameSecond(10L)).thenReturn(engineSecond);
        when(clock.currentEngineGameHour(10L)).thenReturn(engineHour);
        when(clock.currentGameHour(10L)).thenReturn(flowHour);
        java.lang.reflect.Field f = NpcStateService.class.getDeclaredField("worldClockService");
        f.setAccessible(true);
        f.set(service, clock);
    }

    /**
     * P0-2 核心回归：onNpcAction 收到流动时钟污染时刻（7552800=87 游戏天，24h 压测实证值）
     * → 钳制到引擎位置（10000）——状态行不再被推到远超引擎位置（否则 advance 永久跳行冻结，
     * 且一次性补衰减把 hunger/health 打到 0，陆沉舟 9/10 实证）。
     */
    @Test
    void onNpcAction_gameSecondBeyondEngineIsClamped() throws Exception {
        injectClockMock(10000L, null, 9999L);
        ActorNpcState st = newState(10L, "special", "npc:5", 9000L);
        st.setHunger(70);
        stateStore.add(st);
        service.onNpcAction(10L, "npc:5", "special", "就着干粮充饥", "custom", 7552800L);
        assertEquals(10000L, st.getGameSecond(), "流动时钟污染时刻必须钳制回引擎位置");
        assertTrue(st.getHunger() >= 60, "仅补 1000 游戏秒衰减+恢复，hunger 不应被一次性衰减归零");
    }

    /**
     * P0-2：stateSegment（决策/对话【当前状态】段惰性补偿）收到超前时刻同样钳制，
     * 状态行 game_second 不超前引擎位置。
     */
    @Test
    void stateSegment_gameSecondBeyondEngineIsClamped() throws Exception {
        injectClockMock(10000L, null, 9999L);
        ActorNpcState st = newState(10L, "special", "npc:5", 9000L);
        stateStore.add(st);
        service.stateSegment(10L, "npc:5", 7552800L);
        assertEquals(10000L, st.getGameSecond(), "状态段惰性补偿时刻应钳制到引擎位置");
    }

    /**
     * P0-2：人群作息挂钩/建行初始时刻钳制——ensureState 与 onCrowdSchedule 不落流动时钟值。
     */
    @Test
    void onCrowdSchedule_gameSecondBeyondEngineIsClamped() throws Exception {
        injectClockMock(10000L, null, 9999L);
        com.holzyn.actor.domain.npc.profile.entity.ActorCharacter n =
                new com.holzyn.actor.domain.npc.profile.entity.ActorCharacter();
        n.setId(8L);
        n.setType("common");
        service.onCrowdSchedule(10L, 7552800L, List.of(n));
        ActorNpcState st = stateStore.get(stateStore.size() - 1);
        assertEquals(10000L, st.getGameSecond(), "人群作息挂钩时刻应钳制到引擎位置");
        assertEquals(70, st.getHunger(), "建行即钳制，不做虚假的大段衰减");
    }

    // ==================== P1-1/P1-2 恢复关键词全量扩充（2026-09-11 状态循环修复） ====================

    /**
     * P1-1 核心回归：实证命中失败文本（陆沉舟「先取水囊装热水，就着干粮充饥」/「翻检干粮袋…灌了几口热水」、
     * 人群默认动作「觅食充饥」）现在全部命中恢复映射——hunger/thirst 正确回升。
     */
    @Test
    void applyActionRecovery_expandedEatAndDrinkKeywords() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setHunger(20);
        st.setThirst(20);
        // 干粮充饥 + 灌热水 → 双恢复
        assertTrue(NpcStateService.applyActionRecovery(st, "先取水囊装热水，就着干粮充饥", "custom", 100L, props));
        assertEquals(60, st.getHunger(), "干粮/充饥命中 → +40");
        assertEquals(55, st.getThirst(), "热水/灌水命中 → +35");
        // 人群程序化默认动作文本「觅食充饥」
        ActorNpcState crowd = newState(10L, "common", "npc:8", 0);
        crowd.setHunger(30);
        assertTrue(NpcStateService.applyActionRecovery(crowd, "觅食充饥", "eat", 100L, props));
        assertEquals(70, crowd.getHunger());
    }

    /**
     * P1-2 核心回归：type=rest 与恢复映射对齐——「闭目调息以缓解身体不适」（type=rest，24h 压测
     * 实证 rest 后 health=15 不变的行动）现在恢复 energy +45 / health +5。
     */
    @Test
    void applyActionRecovery_restTypeAligns() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setEnergy(20);
        st.setHealth(15);
        assertTrue(NpcStateService.applyActionRecovery(st, "闭目调息以缓解身体不适", "rest", 100L, props));
        assertEquals(65, st.getEnergy(), "rest 关键词命中 → +45");
        assertEquals(20, st.getHealth(), "睡眠附带健康微恢复 → +5");
        assertNotNull(st.getLastSleepGameSecond());
    }

    /**
     * P1-1：toilet/wash/chat/play/treat 五类扩充词（出恭/梳洗/拉家常/听戏/诊脉）全命中。
     */
    @Test
    void applyActionRecovery_expandedOtherCategories() {
        ActorNpcState st = newState(10L, "special", "npc:5", 0);
        st.setBladder(20); st.setHygiene(40); st.setSocial(20); st.setFun(20); st.setHealth(50);
        assertTrue(NpcStateService.applyActionRecovery(st, "寻处僻静出恭", "custom", 100L, props));
        assertEquals(75, st.getBladder(), "出恭命中 → +55");
        assertTrue(NpcStateService.applyActionRecovery(st, "晨起梳洗", "custom", 100L, props));
        assertEquals(90, st.getHygiene(), "梳洗命中 → +50");
        assertTrue(NpcStateService.applyActionRecovery(st, "与邻人拉家常", "custom", 100L, props));
        assertEquals(45, st.getSocial(), "拉家常命中 → +25");
        assertTrue(NpcStateService.applyActionRecovery(st, "去村口听戏", "custom", 100L, props));
        assertEquals(45, st.getFun(), "听戏命中 → +25");
        assertTrue(NpcStateService.applyActionRecovery(st, "去医馆请郎中诊脉", "custom", 100L, props));
        assertEquals(80, st.getHealth(), "诊脉命中 → +30");
    }

    // ==================== 治理批次 0 · 卡1：applyCrowdAction locationId 归一 ====================

    /**
     * G-CROWD-LOC-01（卡1 修复断言，起始 FAIL）：人群移动后 locationId 与 location 归一一致。
     * <p>现状错误值：applyCrowdAction 移动后只写 location 不写 locationId → PerceptionService
     * 在场过滤用陈旧 locationId。防伪机制：action.target=「集市」强制走移动分支（禁止空 target 绕过）。</p>
     */
    @Test
    void applyCrowdAction_moveWritesNormalizedLocationId() throws Exception {
        // 地点归一真实服务 + 内存地点仓库（照 LocationNormalizationServiceTest 范式）
        com.holzyn.actor.domain.world.repository.WorldLocationRepository locRepo =
                Mockito.mock(com.holzyn.actor.domain.world.repository.WorldLocationRepository.class);
        when(locRepo.findByProjectIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                locEntity(5L, "村口"), locEntity(21L, "集市")));
        com.holzyn.actor.domain.world.service.LocationNormalizationService locSvc =
                new com.holzyn.actor.domain.world.service.LocationNormalizationService(locRepo);
        // 卡1-方案 A：字段注入（修复前 NpcStateService 无此字段 → 本测试先红）
        java.lang.reflect.Field f = NpcStateService.class.getDeclaredField("locationNormalizationService");
        f.setAccessible(true);
        f.set(service, locSvc);

        ActorCharacter npc = new ActorCharacter();
        npc.setId(10L);
        npc.setProjectId(1L);
        npc.setName("张三");
        npc.setType("common");
        npc.setLocation("村口");
        npc.setLocationId(5L);

        service.applyCrowdAction(1L, npc,
                new CrowdDecisionParse.CrowdAction("去集市采购", "move", "集市", "采购"), 1000L);

        assertEquals("集市", npc.getLocation(), "中间态：位置已更新（现状已成立）");
        assertEquals(21L, npc.getLocationId(), "人群移动后 locationId 与地点表归一一致（集市=21）");
    }

    private com.holzyn.actor.domain.world.entity.ActorWorldLocation locEntity(Long id, String name) {
        com.holzyn.actor.domain.world.entity.ActorWorldLocation l =
                new com.holzyn.actor.domain.world.entity.ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        return l;
    }

    // ==================== P2-3 BrainStrategy 普通 NPC 决策策略链 ====================

    /** 普通 NPC 测试角色（common，npc:8）。 */
    private ActorCharacter commonNpc() {
        ActorCharacter npc = new ActorCharacter();
        npc.setId(8L);
        npc.setProjectId(10L);
        npc.setName("王五");
        npc.setType("common");
        npc.setLocation("村口");
        return npc;
    }

    /** 注入真实 Router（普通链=危险强制→阶段门控→快选择→概率化；服务自身作为策略委托目标）。 */
    private void wireRealRouter() throws Exception {
        com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy danger =
                new com.holzyn.actor.domain.agent.brain.strategy.PhysioDangerStrategy();
        org.springframework.test.util.ReflectionTestUtils.setField(danger, "npcStateService", service);
        // O6 发育演化：真实 stage_gating 策略（无 stage 的普通 NPC 不适用，既有测试行为不变）
        com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy gating =
                new com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy();
        com.holzyn.actor.domain.agent.brain.strategy.CrowdChoiceStrategy choice =
                new com.holzyn.actor.domain.agent.brain.strategy.CrowdChoiceStrategy();
        org.springframework.test.util.ReflectionTestUtils.setField(choice, "npcStateService", service);
        com.holzyn.actor.domain.agent.brain.strategy.ProbabilisticStrategy prob =
                new com.holzyn.actor.domain.agent.brain.strategy.ProbabilisticStrategy();
        org.springframework.test.util.ReflectionTestUtils.setField(prob, "npcStateService", service);
        com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter router =
                new com.holzyn.actor.domain.agent.brain.strategy.BrainStrategyRouter(
                        java.util.List.of(danger, gating, choice, prob));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "brainStrategyRouter", router);
    }

    /**
     * 普通 NPC 链首危险强制（P2-3）：危险区命中 → 程序化强制（0 AI）先于快选择/概率化——
     * 与特殊 NPC 危险前置语义对齐（任何触发源先于快通道）。
     */
    @Test
    void decideCrowdAction_routerChainDangerForcesFirst() throws Exception {
        wireRealRouter();
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(10);
        stateStore.add(st);

        CrowdDecisionParse.CrowdAction drive = service.decideCrowdAction(10L, commonNpc(), 1000L);

        assertNotNull(drive, "危险区普通 NPC 链首强制产出行动");
        assertEquals("eat", drive.actionType());
        assertTrue(drive.action().contains("充饥") || drive.action().contains("用餐"),
                "危险行动文本对齐 onNpcAction 恢复关键词");
        assertEquals("生理需求危险区强制（0 AI）", drive.reason());
    }

    /**
     * 普通 NPC 链式责任（P2-3）：危险未命中 + 快选择未命中（NanoJev/AI 未装配）→
     * 概率化层产出需求满足行动（Sims 模型）。
     */
    @Test
    void decideCrowdAction_routerChainFallsToProbabilistic() throws Exception {
        wireRealRouter();
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(80);
        st.setEnergy(30); // 无危险；精力低 → 概率化候选
        stateStore.add(st);

        CrowdDecisionParse.CrowdAction drive = service.decideCrowdAction(10L, commonNpc(), 1000L);

        assertNotNull(drive, "快选择未命中 → 概率化产出");
        assertEquals("概率化需求满足", drive.reason());
        assertEquals("rest", drive.actionType(), "精力低 → 歇息需求满足");
    }

    /** 普通 NPC 链空（P2-3）：危险/快选择/概率化全未命中 → null（调用方作息状态机兜底）。 */
    @Test
    void decideCrowdAction_routerChainEmptyReturnsNull() throws Exception {
        wireRealRouter();
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(80);
        st.setEnergy(80); // 全正常，无需求候选
        stateStore.add(st);

        assertNull(service.decideCrowdAction(10L, commonNpc(), 1000L),
                "链空 → null（调用方作息状态机兜底，与 P2-3 前一致）");
    }

    /** 普通 NPC 链受限阶段门控（O6 发育演化）：ELDER 空闲 → stage_gating 阶段行为接管。 */
    @Test
    void decideCrowdAction_routerChainStageGatingForElder() throws Exception {
        wireRealRouter();
        ActorCharacter npc = commonNpc();
        npc.setStage("ELDER");
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(80);
        st.setEnergy(80);
        stateStore.add(st);

        CrowdDecisionParse.CrowdAction drive = service.decideCrowdAction(10L, npc, 1000L);

        assertNotNull(drive, "受限阶段普通 NPC 空闲 → 阶段行为接管（非概率化）");
        assertTrue(drive.reason().contains("发育阶段行为"), "reason 标注发育阶段行为：" + drive.reason());
        assertFalse(drive.action().isBlank(), "行动文本非空");
    }

    /** 普通 NPC 链刺激让路（O6 发育演化）：ELDER 有环境刺激 → stage_gating 让路，链继续。 */
    @Test
    void decideCrowdAction_routerChainStageGatingYieldsToEnvironment() throws Exception {
        wireRealRouter();
        ActorCharacter npc = commonNpc();
        npc.setStage("ELDER");
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(80);
        st.setEnergy(80);
        stateStore.add(st);

        CrowdDecisionParse.CrowdAction drive =
                service.decideCrowdAction(10L, npc, 1000L, "天色骤变，大雨将至");

        // 环境刺激 → 阶段门控让路；无 NanoJev/AI 装配 → 链空 null（调用方状态机兜底）
        assertNull(drive, "环境刺激让路：不产出阶段行为模板，交还既有链");
    }

    /** router 未装配（单测基线）→ 既有内联管线（NanoJev/AI 未装配 → null）。 */
    @Test
    void decideCrowdAction_legacyPathWithoutRouter() {
        assertNull(service.decideCrowdAction(10L, commonNpc(), 1000L),
                "legacy 内联管线：NanoJev/AI 未装配 → null（调用方回退概率化/状态机）");
    }

    /** dangerDrive（普通 NPC 危险强制公开委托点）：危险区强制，恢复常态后 null。 */
    @Test
    void dangerDrive_forcesInDangerZoneOnly() {
        ActorNpcState st = newState(10L, "common", "npc:8", 0);
        st.setHunger(10);
        stateStore.add(st);

        CrowdDecisionParse.CrowdAction drive = service.dangerDrive(10L, commonNpc(), 1000L);
        assertNotNull(drive, "危险区强制产出");
        assertEquals("eat", drive.actionType());

        st.setHunger(80);
        assertNull(service.dangerDrive(10L, commonNpc(), 1000L), "恢复常态 → 无危险 → null");
    }

    /** crowdChoiceDrive（普通 NPC 快选择公开委托点）：NanoJev/AI 未装配 → null（链继续）。 */
    @Test
    void crowdChoiceDrive_fallsBackNullWhenUnwired() {
        assertNull(service.crowdChoiceDrive(10L, commonNpc(), 1000L, null),
                "NanoJev/AI 未装配 → null（链继续到概率化）");
    }

    // ==================== characterInfoLine 上下文注入（2026-10-07 根治） ====================

    /** 装配注入器时，characterInfoLine 前置【世界观】+【我的身份与处境】（AI 以「这个人」为基底评估）。 */
    @Test
    void characterInfoLine_injectsContextBeforeBaseIdentity() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.compose(10L, "npc:5"))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：沈砚秋（青禾村村长）");
        setField(service, "aiContextInjector", injector);

        ActorCharacter ch = new ActorCharacter();
        ch.setName("沈砚秋");
        ch.setTitle("青禾村村长");
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository charRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        when(charRepo.findById(5L)).thenReturn(Optional.of(ch));
        setField(service, "characterRepository", charRepo);

        String line = invokeCharacterInfoLine(10L, "special", "npc:5");
        assertTrue(line.startsWith("【世界观】"));
        assertTrue(line.contains("【我的身份与处境】"));
        assertTrue(line.contains("沈砚秋"));
        assertTrue(line.contains("身份：「沈砚秋」（青禾村村长）"));
        assertTrue(line.indexOf("【世界观】") < line.indexOf("身份："),
                "注入段应在简化身份行之前");
    }

    /** 注入器未装配 → 保留简化身份行（零阻断，保既有行为）。 */
    @Test
    void characterInfoLine_withoutInjector_keepsSimplifiedIdentity() throws Exception {
        setField(service, "aiContextInjector", null);
        ActorCharacter ch = new ActorCharacter();
        ch.setName("沈砚秋");
        ch.setTitle("青禾村村长");
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository charRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        when(charRepo.findById(5L)).thenReturn(Optional.of(ch));
        setField(service, "characterRepository", charRepo);

        String line = invokeCharacterInfoLine(10L, "special", "npc:5");
        assertEquals("身份：「沈砚秋」（青禾村村长）", line);
    }

    /** 注入器装配但角色档案缺失 → 注入段仍前置（世界观仍在），身份行回退泛称。 */
    @Test
    void characterInfoLine_injectsWorldEvenWhenIdentityMissing() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.compose(10L, "npc:99"))
                .thenReturn("【世界观】\n青禾村四面环山。");
        setField(service, "aiContextInjector", injector);
        String line = invokeCharacterInfoLine(10L, "special", "npc:99");
        assertTrue(line.startsWith("【世界观】"));
        assertTrue(line.contains("身份：角色"));
    }

    private String invokeCharacterInfoLine(Long projectId, String npcType, String npcRef) throws Exception {
        java.lang.reflect.Method m = NpcStateService.class.getDeclaredMethod(
                "characterInfoLine", Long.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, projectId, npcType, npcRef);
    }

    // ==================== AI 情绪评估 段2D/2E 注入（2026-10-10 批 4 记忆与反思链·段 2F 对齐） ====================

    /**
     * 批 4 段 2F 对齐：AI 情绪评估请求在 character_info（段1A+1C）基础上前置
     * decisionStatePrefix（段2D 当前世界动态 + 段2E 当前状态）——AI 判断情绪时有「此刻处境」依据。
     */
    @Test
    void doEvaluateEmotion_prependsSegment2DAnd2E() throws Exception {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.compose(10L, "npc:5"))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：沈砚秋（青禾村村长）");
        when(injector.decisionStatePrefix(10L, "npc:5", 3600L))
                .thenReturn("【当前世界动态】\n世界历 0001年01月01日 01时00分 · 夏季\n\n"
                        + "【当前状态】\n- 需求：饥饿 68/100（略饿）\n- 情绪：担忧（强度 42）");
        setField(service, "aiContextInjector", injector);

        ActorCharacter ch = new ActorCharacter();
        ch.setName("沈砚秋");
        ch.setTitle("青禾村村长");
        com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository charRepo =
                Mockito.mock(com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository.class);
        when(charRepo.findById(5L)).thenReturn(Optional.of(ch));
        setField(service, "characterRepository", charRepo);

        com.holzyn.actor.domain.settings.service.PromptTemplateService pts =
                Mockito.mock(com.holzyn.actor.domain.settings.service.PromptTemplateService.class);
        com.holzyn.actor.ai.spring.AiSpringChatGateway gateway =
                Mockito.mock(com.holzyn.actor.ai.spring.AiSpringChatGateway.class);
        com.holzyn.actor.domain.usage.service.UsageLogService usage =
                Mockito.mock(com.holzyn.actor.domain.usage.service.UsageLogService.class);
        setField(service, "promptTemplateService", pts);
        setField(service, "aiChatGateway", gateway);
        setField(service, "usageLogService", usage);
        when(pts.render(any(), any(), Mockito.eq("npc_emotion_eval"), any())).thenReturn("情绪评估模板");
        // AI 返回非法 JSON → 解析 null → 程序化回退（仍会发送请求，可捕获注入段）
        when(gateway.chatCompletion(any(), any(), any(), any()))
                .thenReturn(new com.holzyn.actor.ai.AiChatResult("不是JSON", "test-model", 10, 10, 20, 1L));

        // 通过反射调用 doEvaluateEmotion（异步入口 evaluateEmotion 走虚拟线程，测试直接同步调私有方法）
        java.lang.reflect.Method m = NpcStateService.class.getDeclaredMethod("doEvaluateEmotion",
                Long.class, String.class, String.class, String.class, String.class, Long.class);
        m.setAccessible(true);
        m.invoke(service, 10L, "npc:5", "special", "行动执行", "前往村口粮铺", 3600L);

        // 捕获实际请求：user 消息应含段2D 当前世界动态 + 段2E 当前状态（在情绪评估模板之前）
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);
        Mockito.verify(gateway).chatCompletion(any(), any(), any(), reqCaptor.capture());
        String user = reqCaptor.getValue().messages().get(1).content();
        assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
        assertTrue(user.indexOf("【当前世界动态】") < user.indexOf("【当前状态】"), "段2D 应在段2E 前");
        assertTrue(user.indexOf("【当前状态】") < user.indexOf("情绪评估模板"), "段2D/2E 应在模板前");
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
